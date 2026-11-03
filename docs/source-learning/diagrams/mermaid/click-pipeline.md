# Mermaid 备用图 · 点击统计管道（Outbox → MQ → 消费聚合 → Webhook）

> 交互版见 [../archify/html/14-click-pipeline-dataflow.html](../archify/html/14-click-pipeline-dataflow.html)。
> 口径来源：README「点击统计管道（A7）」「维度分析（A8）」「开放能力 B5」；实现类 `stats/service/ClickRecordService.java`、`stats/service/ClickOutboxRelay.java`、`stats/mq/ClickEventConsumer.java`、`webhook/service/*`。

```mermaid
flowchart LR
    subgraph HOT["跳转热路径(同一 DB 事务)"]
        RC["ClickRecordService.recordClick"] --> CL[("lf_click_log<br/>点击明细")]
        RC --> OB[("lf_outbox<br/>PENDING")]
    end

    subgraph RELAY["ClickOutboxRelay @Scheduled 2s/批100"]
        OB -- "轮询 (status, next_attempt_at)" --> PUB["RabbitClickEventPublisher<br/>correlated confirm + mandatory"]
        PUB -- "ack" --> SENT["markSent (SENT)"]
        PUB -- "nack/超时/不可路由" --> RETRY["markRetry: 2^n 秒退避, 上限60s"]
        RETRY --> OB
        RETRY -- "超过 30 次" --> FAIL["markFailed + linkforge.outbox.failed"]
    end

    MQ{{"RabbitMQ<br/>linkforge.click.exchange<br/>→ click.queue"}}

    subgraph CONSUME["ClickEventConsumer @RabbitListener"]
        MQ --> IDEM{"lf_consumed_event<br/>INSERT 去重(唯一索引)"}
        IDEM -- "重复" --> ACK1["ACK 丢弃(幂等)"]
        IDEM -- "首次" --> BACK["UUID 事件回填 lf_click_log(event_id 标记)"]
        BACK --> DAY[("lf_click_daily<br/>按天累加")]
        BACK --> DIM[("lf_click_dim_daily<br/>device/referer_host/os/browser/variant")]
        BACK --> WHQ["Webhook 投递意图<br/>lf_webhook_delivery(PENDING)"]
        CONSUME_ERR["消费异常"] -- "default-requeue-rejected=false" --> DLQ{{"linkforge.click.dlq"}}
    end

    subgraph WEBHOOK["WebhookDeliveryRelay @Scheduled"]
        WHQ --> SIGN["WebhookSigner<br/>HMAC-SHA256(ts.body)"]
        SIGN --> GUARD["WebhookTargetGuard 校验<br/>→ PinnedHttpSender 只连已校验 IP"]
        GUARD -- "2xx" --> WSENT["SENT"]
        GUARD -- "5xx/429/超时" --> WRETRY["退避 10s·2^n ≤1h, 上限8次"]
        GUARD -- "其它4xx 首败" --> WFAIL["FAILED"]
    end

    subgraph RECON["ClickLogReconciler @Scheduled 30s(仅 sync 开)"]
        DETAIL[("lf_click_log")] -- "未追平行补聚合" --> DAY
    end

    SENT --> MQ
    CL -. "对账等式: 明细数 = 聚合和" .-> RECON
```

要点核对表：

| 图上环节 | 关键语义 | 源码位置主题 |
|---|---|---|
| 同事务双写 | `@Transactional`：明细 + outbox 要么都在要么都不在 | `ClickRecordService` |
| Relay 投递 | broker ack 后才标 SENT；确认超时按失败重试 | `ClickOutboxRelay`、`RabbitClickEventPublisher` |
| 消费幂等 | 去重插入与聚合同一短事务（防中间崩溃少计） | `ClickStatsService.consume`/`ClickEventConsumer` |
| 回填明细 | sync 关闭后明细由消费者补写，event_id 标记防对账重放 | `ClickEventConsumer` |
| 维度聚合 | 只在消费路径解析 UA/Referer，热路径零新增计算 | `ClickDimensions` |
| Webhook 意图 | 在幂等事务内落 PENDING；断 MQ 期间停在 outbox，恢复自动补出 | `WebhookService` |
| 两个时钟 | outbox 用 DB 时钟退避；webhook 投递退避也在 DB 时钟，SENT 清理时 webhook 用 JVM 时钟 | `SentRowCleanup` |
