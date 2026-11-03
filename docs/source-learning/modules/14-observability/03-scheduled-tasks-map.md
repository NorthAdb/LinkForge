# 14.3 定时任务地图：八个 @Scheduled、一个线程池与双时钟口径

> 模块 14 收官课，也是全项目的调度索引。把散在六个包里的 8 个 `@Scheduled` 任务排成一张总表：节奏、门禁条件、读写的表、失败语义；解释共享线程池 size=6 的隔离意图；深讲唯一的运维任务 SentRowCleanup 的有界批次设计；最后用一张双时钟口径总结表收束模块 10、11、13 分散铺垫的时钟规则。

## 1. 本节学习目标

1. 说出调度基础设施的三行代码：`SchedulingConfig` 的 `@EnableScheduling`、yml 的 `pool.size: 6`、注解里的毫秒配置引用。
2. 背出 8 个定时任务的五要素：节奏、门禁、读的表、写的表、失败语义。
3. 逐行读懂 `SentRowCleanup.purgeSent` 的有界批次循环，以及两条 DELETE SQL 的时钟差异。
4. 用「调度列用 DB 时钟、业务时间戳用 JVM 时钟」的口径给全项目的每张表分类，说出唯一一处混合用法。
5. 发现并解释 `WebhookDeliveryRelay.java:27-29` 注释里 4-thread 与配置 6 的滞后，养成「配置是事实」的读码直觉。

## 2. 前置知识

- **@Scheduled（框架行为）**：方法级注解，Spring 容器负责按节奏调用。三种常用节奏：`fixedDelay`（上一次执行**结束**到下一次开始，天然防同任务重叠）、`fixedRate`（固定间隔，重叠时排队）、cron（按钟点）。`fixedDelayString` 是读配置的变体，值来自配置项；这个字符串形式只认毫秒数字，不认 Boot 的时长写法，所以 `LinkForgeProperties` 的相关字段全叫 `xxx-ms` 并在注释里说明（`LinkForgeProperties.java:45`）。
- **@EnableScheduling**：一个注解开关。没有它，所有 `@Scheduled` 都是死代码。它背后的默认调度器是**单线程**的，这是本课线程池话题的根源。
- **门禁条件**：每个任务方法体开头读配置开关并提前 return 的习惯写法。任务常驻但功能可关，开关每 tick 重读，改配置即生效。
- **幂等与退避（模块 10、13 已铺垫）**：relay 类任务重复执行必须无害（消费端 eventId 去重兜底）；失败重试按 2 的幂退避。
- **双时钟口径**：调度类列（下一次何时执行）写与比都用数据库时钟；业务时间戳（事件何时发生）用 JVM 时钟。来龙去脉见 13.2 的 5.8 节，本课收束成总表。

## 3. 本节涉及的源码

| 文件 | 行数 | 职责 | 阅读顺序 |
|----|----|----|----|
| `src/main/java/com/linkforge/config/SchedulingConfig.java` | 9 | 调度总开关（本课最小源码文件） | 1 |
| `src/main/resources/application.yml` | 266 | `:6-12` 调度线程池 | 2 |
| `src/main/java/com/linkforge/stats/service/ClickOutboxRelay.java` | 79 | outbox 抽水（2s） | 3 |
| `src/main/java/com/linkforge/webhook/service/WebhookDeliveryRelay.java` | 167 | webhook 投递（2s） | 4 |
| `src/main/java/com/linkforge/webhook/service/WebhookAggregateFlusher.java` | 237 | 聚合窗冲刷（5s） | 5 |
| `src/main/java/com/linkforge/stats/service/ClickLogReconciler.java` | 55 | 对账（30s） | 6 |
| `src/main/java/com/linkforge/retention/SentRowCleanup.java` | 73 | SENT 行清理（1h，本课深讲） | 7 |
| `src/main/java/com/linkforge/observability/BacklogGauges.java` | 58 | 积压采样（15s） | 8 |
| `src/main/java/com/linkforge/link/support/CodeBloomFilter.java` | 210 | Bloom 全量重建（24h，`:115-122`） | 9 |
| `src/main/java/com/linkforge/link/support/PhishingPolicy.java` | 165 | 名单热载（30s，`:67-92`） | 10 |
| `src/main/java/com/linkforge/stats/mapper/OutboxMapper.java` | 36 | outbox 清理 SQL 的 DB 时钟口径（`:29-35`） | 11 |
| `src/main/java/com/linkforge/webhook/mapper/WebhookDeliveryMapper.java` | 54 | webhook 清理 SQL 的 JVM 口径与时钟规则注释（`:14-19`、`:41-49`） | 12 |
| `src/main/java/com/linkforge/config/LinkForgeProperties.java` | 239 | Retention/Metrics/Mq/Webhook/Bloom/Phishing 配置节 | 13 |
| `src/test/java/com/linkforge/retention/SentRowCleanupTest.java` | 112 | 有界批次与 SQL 口径的单测 | 14 |

## 4. 类的定位

调度在这套代码里没有中央注册处：8 个任务类散在所属域（stats 两个、webhook 两个、link 两个、observability 一个、retention 一个），`@Scheduled` 注解即注册。集中式的只有两样：一个 9 行的配置类做总开关，一段 yml 做容量。retention 包是唯一的跨域任务——它同时删 outbox 与 webhook 两家的表，正好用模块 10 与模块 13 的两个 Mapper。

这张地图与图表 [08-scheduled-tasks.html](../../diagrams/archify/html/08-scheduled-tasks.html) 一一对应：图中心是共享调度池（size=6），8 个任务节点各带节奏与门禁；reconciler 的聚合写入细节在图的卡片里。学到任一模块想查「这事多久跑一次」，先看这张图或本课 5.2 的表。

## 5. 核心源码详解

### 5.1 开关在代码，容量在配置

```java
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
```

（逐字摘自 `SchedulingConfig.java:6-9`，全文。）

```yaml
  task:
    scheduling:
      # B5: relays must not share one thread — a slow webhook endpoint must never
      # delay click outbox catch-up. B9 adds the blocklist reload and B1 the backlog
      # sampler (D-Bw345-54: 4 → 6).
      pool:
        size: ${LINKFORGE_SCHEDULING_POOL_SIZE:6}
```

（逐字摘自 `application.yml:6-12`。）

分工清楚：`@EnableScheduling` 是功能开关，改它要动代码；池大小是容量参数，改环境变量即可。注释记录了演化史：B5 波从默认 1 提到 4，理由是两个 relay 不能共用一线程；B9 加名单热载、B1 加积压采样，D-Bw345-54 又提到 6。8 个任务 6 个线程的含义：至多 6 个任务并发，最坏第 7 个任务等线程。

### 5.2 八任务总表

| # | 任务 | 注解行 | 节奏（默认） | 门禁条件 | 读 | 写 | 失败语义 |
|----|----|----|----|----|----|----|----|
| 1 | ClickOutboxRelay.relay | `ClickOutboxRelay.java:37-38` | fixedDelay 2s，首次 3s | `relayEnabled && asyncClickEnabled`（`:40-43`） | lf_outbox PENDING 批 100（`OutboxMapper.java:14-17`） | markSent/markRetry/markFailed | attempts≥30 → FAILED + `linkforge.outbox.failed`（`:48-53`）；行级退避 `min(60, 2^n)` 秒（`:75-78`，DB 时钟）；外层 catch 吞整轮（`:70-72`） |
| 2 | WebhookDeliveryRelay.drain | `WebhookDeliveryRelay.java:63-64` | fixedDelay 2s，首次 5s | `enabled && relayEnabled`（`:66-69`）；master key 不可用整批跳过不耗 attempts（`:70-76`） | lf_webhook_delivery PENDING | markSent/markRetry/markFailed（均带 `WHERE status='PENDING'`） | 2xx SENT；408/429/5xx 与传输错误退避 `min(3600, 10×2^n)`（首试 20s）；其他 4xx 立即 FAILED；毒行也走 scheduleRetry（`:81-86`） |
| 3 | WebhookAggregateFlusher.sweep | `WebhookAggregateFlusher.java:79-80` | fixedDelay 5s，首次 3s | `webhook.enabled`（`:82-84`） | lf_webhook_agg_buffer 过期窗（`BufferMapper` JOIN endpoint，`window_start <= UNIX_TIMESTAMP()-window`） | digest 投递行 + 删缓冲桶 | 单窗失败 catch 后继续下一窗（`:91-96`），错误在 flushWindow 内计数 `aggregate.flush{result=error}` |
| 4 | ClickLogReconciler.reconcile | `ClickLogReconciler.java:30-31` | fixedDelay 30s，首次 15s | `asyncClickEnabled && syncClickEnabled`（`:33-35`，双写都开才有对账对象） | lf_click_log 未消费 LEFT JOIN lf_consumed_event LIMIT 500（`ClickLogMapper.java:14-18`） | 经 consume 写聚合三表 | 行级 catch warn 继续下一行（`:44-46`） |
| 5 | SentRowCleanup.cleanup | `SentRowCleanup.java:34-35` | fixedDelay 1h，首次 60s | `sentDays <= 0` 关闭（`:55-57`） | lf_outbox + lf_webhook_delivery 的 SENT 行 | 有界 DELETE（batch 1000，maxPasses 10） | 外层 catch warn（`:43-45`）；删多少都幂等 |
| 6 | BacklogGauges.sample | `BacklogGauges.java:40-41` | fixedDelay 15s，首次 15s | 无 | 三条 COUNT（outbox/webhook PENDING、webhook FAILED） | 内存 AtomicLong | 失败保留上次值 + WARN（`:48-57`，14.2 已讲） |
| 7 | CodeBloomFilter.scheduledRebuild | `CodeBloomFilter.java:116` | fixedDelay 24h，首次同 | `bloom.rebuildEnabled`（`:118-120`）且 `bloom.enabled`（`:135-139`） | lf_short_link 全码 | 内存 BloomFilter，bypass-then-atomic-swap（`:153-154`） | 失败恢复旧 filter 引用（`:168`）；重建中再触发计 `rebuild{result=busy}`（`:141`） |
| 8 | PhishingPolicy.reload | `PhishingPolicy.java:69-70` | fixedDelay 30s，首次 30s | 名单文件已配置（`:72-74`）；mtime 未变计 `unchanged` 早退（`:77-79`） | 名单文件 | 内存 snapshot（merge 后整体换引用，`:88`） | 解析失败保留旧快照 + `reload{result=failed}`（`:116-119`） |

读表的方法：节奏决定「多快发现积压」，门禁决定「功能关了任务在不在」，失败语义决定「一次抖动会不会放大」。1、2 是重试状态机的驱动器（模块 10、13 的主角），3 是 2 的分身（digest 也走投递行），4 是 1 丢失兜底，5 是三者的垃圾回收，6 是观察者，7、8 是内存数据结构的保鲜。

### 5.3 SentRowCleanup 深讲：有界批次

```java
public int purgeSent() {
    LinkForgeProperties.Retention cfg = properties.getRetention();
    int days = cfg.getSentDays();
    if (days <= 0) {
        return 0;
    }
    int batch = Math.max(1, cfg.getCleanupBatchSize());
    int maxPasses = Math.max(1, cfg.getCleanupMaxPasses());
    long retentionSeconds = days * SECONDS_PER_DAY;
    LocalDateTime webhookCutoff = LocalDateTime.now().minusDays(days);
    int total = 0;
    for (int pass = 0; pass < maxPasses; pass++) {
        int outbox = outboxMapper.deleteSentOlderThan(retentionSeconds, batch);
        int webhook = deliveryMapper.deleteSentOlderThan(webhookCutoff, batch);
        total += outbox + webhook;
        if (outbox < batch && webhook < batch) {
            break;
        }
    }
    return total;
}
```

（逐字摘自 `SentRowCleanup.java:52-72`。）

五个设计点：

1. **有界批次**：每条 DELETE 带 `LIMIT #{limit}`，单条 SQL 最多删 1000 行（`LinkForgeProperties.java:48`）。大事务删百万行会长时间持锁、刷爆 undo log；LIMIT 把它切成短事务。
2. **maxPasses 封顶**：积压再多，一个 tick 最多 `batch × maxPasses`（默认 10 批 × 1000 行 = 1 万行）。类注释（`:22-23`）写明意图「so it does not sit on a scheduler thread」——任务一小时才跑一次，慢一点无所谓，但不能独占调度线程。
3. **追平即停**：两个 mapper 返回值都小于 batch，说明没有更多可删行，提前 break（`:67-69`），避免空转 9 轮。
4. **只删终态**：两条 SQL 都限定 `status = 'SENT' AND sent_at IS NOT NULL`（`OutboxMapper.java:33-34`、`WebhookDeliveryMapper.java:47-48`）。PENDING、FAILED、在途行永远不碰，重复执行是空操作（幂等）。
5. **双时钟分流**（javadoc `:48-51`）：outbox 的 `sent_at` 是 `NOW()` 写入的 DB 时钟，所以 cutoff 直接在 SQL 里 `DATE_SUB(NOW(), INTERVAL #{retentionSeconds} SECOND)`（`OutboxMapper.java:34`）；webhook 的 `sent_at` 是 Java 侧 `#{now}` 写入的 JVM 时钟（`WebhookDeliveryMapper.java:27-28`），所以 `:61` 在 Java 里算好 `LocalDateTime.now().minusDays(days)` 再作参数传入（`WebhookDeliveryMapper.java:48` 的 `#{cutoff}`）。各跟各的写入时钟，比较才不跨钟。

单测把 3、4、5 全部钉死：`SentRowCleanupTest.deletesOnlySentBatchAndStopsWhenCaughtUp`（`:58-70`）断言 cutoff 是 `now().minusDays(7)` 附近（5 秒容差）与 batch=1000；`fullBatchesContinueUntilTheCapThenALaterPassIsIdle`（`:73-86`）用 batch=10、maxPasses=3 验证满批连跑 3 轮共 60 行后停，追平轮零调用；`deleteStatementsTouchOnlySentRows`（`:89-104`）反射读两条 @Delete 注解 SQL 原文，断言含 `STATUS = 'SENT'`、`SENT_AT IS NOT NULL`、`LIMIT`，不含 PENDING/FAILED，且 outbox 用 `DATE_SUB(NOW()`、webhook 用 `#{cutoff}` 不含 `NOW()`（`:101-103`）。

### 5.4 调度隔离：池是容量，fixedDelay 是纪律

为什么 6 个线程够 8 个任务？三个层次叠加：

- **同任务不重叠**：全部用 fixedDelay，同一任务的下一轮永远等上一轮结束。不会出现「relay 第一轮没跑完，第二轮又进来」的自相踩踏。
- **慢任务的爆炸半径**：webhook relay 的单轮耗时上限由 HTTP 超时配置兜住（`LinkForgeProperties.Webhook` 的投递超时，默认秒级），Bloom rebuild 是一次全表扫描，cleanup 最多 10 个批次。每个任务的最坏时长都有界。
- **池是并发上限**：两个 relay + flusher + reconciler + cleanup + sampler + rebuild + reload 同时到期才需要 8 线程；现实里 7、8 是小时级与天级任务，常态并发是 4-5。6 给了余量，且注释写明底线：**relay 之间绝不共线**（`application.yml:8-10`）。

多实例时每个实例都跑全部 8 个任务。relay 类任务的正确性不依赖「只有一个实例」：at-least-once + 消费端 eventId 幂等（模块 10.3）保证双倍投递无害；SentRowCleanup 的幂等 DELETE 同理。代价是双倍轮询，换来零分布式协调。

### 5.5 双时钟口径总结表

| 列 | 写入（时钟） | 比较（时钟） | 出处 |
|----|----|----|----|
| lf_outbox.next_attempt_at | `DATE_ADD(NOW(),...)`（DB） | `<= NOW()`（DB） | `OutboxMapper.java:25-26`、`:15` |
| lf_outbox.sent_at | `NOW()`（DB） | `DATE_SUB(NOW(),...)`（DB） | `OutboxMapper.java:19`、`:34` |
| lf_webhook_delivery.next_attempt_at | `DATE_ADD(NOW(),...)`（DB） | `<= NOW()`（DB） | `WebhookDeliveryMapper.java:31-33`、`:24` |
| lf_webhook_delivery.sent_at | `#{now}`（JVM） | `#{cutoff}`（JVM） | `WebhookDeliveryMapper.java:27-28`、`:48` |
| lf_webhook_agg_buffer.window_start | `AggregateWindows.windowStartOf`（JVM epoch，`:27-29`） | `UNIX_TIMESTAMP()-window`（DB，sweep SQL） | 混合：写 JVM、判 DB |
| lf_click_log.clicked_at 等业务时间戳 | JVM（`ClickRecordService.java:42`） | 一般不做调度比较 | 业务事实记 JVM |

规则一句话：**调度列写与比同钟；业务时间戳记发生时刻用 JVM；谁写入谁比较，永不跨钟**。webhook 这条是踩过坑后立成的规则——`WebhookDeliveryMapper.java:14-19` 的时钟规则注释（D-Bw2fix-1）写明 `sent_at` 若拿去和 `NOW()` 比，会重新引入 JVM 与 DB 的时区偏差；配套的 `WebhookRetryClockIT` 在独立 Asia/Shanghai 时区 fork 里钉住这条规则（15.2 讲 fork 机制）。

唯一一处混合是 window_start：写入侧按类注释（`AggregateWindows.java:7-9`）刻意用 JVM——窗口归类是纯 JVM 算术（late 判定），写成 DB 时钟反而要把时区搬进 SQL；过期判定（sweep）在 SQL 里用 DB 的 `UNIX_TIMESTAMP()`。同机部署两个时钟一致，无实际影响；这是从源码核实的口径细节，上游报告把 window_start 简写成「一律 DB 时钟」在写入侧不够准确。

### 5.6 眼力练习：注释会说谎，配置才是事实

`WebhookDeliveryRelay.java:27-29` 的类注释：

> Runs on the shared (but now 4-thread) scheduler — the click outbox relay and the reconciler are independent of a slow webhook endpoint because of the pool size, never because of shared execution.

注释的隔离论点至今成立，但「4-thread」过时了：D-Bw345-54 把池提到 6（`application.yml:10-12` 注释），javadoc 没跟上。这是一次免费的审查训练——注释描述的是历史决策，配置文件与 git 历史才是事实来源。读到「now X-thread」这类带时间副词的注释时，顺手核对当前配置，不一致就登记，不要脑补成注释对。

## 6. 技术原理补充

- **Spring 调度线程模型（框架行为）**：`@EnableScheduling` 默认给单线程 `ThreadPoolTaskScheduler`。单线程下任一任务卡住，其余 7 个全部排队，这是 B5 注释防御的具体灾难。不用 `pool.size` 会怎样：webhook 目标 30 秒超时时，outbox 追平延迟 30 秒起步。本项目怎么用：池 6 + fixedDelay 纪律 + 每任务最坏时长有界。
- **有界批处理（通用工程）**：后台任务对共享资源（锁、undo log、连接池）的占用要有上限。LIMIT 切批 + 总轮数封顶 + 追平即停是三件套。不用会怎样：一次千万行清理把业务写入堵死几分钟。
- **任务幂等（通用工程，对应 Make Operations Idempotent）**：调度器不保证恰好一次（进程重启、多实例），所以每个任务的重复执行必须无害。8 个任务逐个检查：relay 双投递靠消费幂等，cleanup 重复删是空操作，sampler 覆盖写，rebuild/reload 换引用。
- **毫秒字段（本项目约定）**：`fixedDelayString` 的字符串解析不认 `15s` 这类 Boot 时长风格，所以配置字段全部 `xxx-ms` 并配注释（`LinkForgeProperties.java:45`、`:82`、`:148`）。跨字段引用同一个值（BacklogGauges 的节奏与 initialDelay 同键）保证两值不漂移。

## 7. 可视化

- [08-scheduled-tasks.html](../../diagrams/archify/html/08-scheduled-tasks.html)：本课的图形版总表。看图顺序：中心「调度线程池 size=6」→ 8 个任务节点（各带节奏标签）→ 每个任务指向它读写的表。图中 reconciler 节点的卡片补了聚合写入细节；节点与 5.2 表的行一一对应。
- [00-system-architecture.html](../../diagrams/archify/html/00-system-architecture.html)：把 8 个任务放回全景：它们都跑在宿主 JVM（01 图）里，读写 MySQL 的表、驱动 RabbitMQ 投递、刷新进程内 Bloom 与名单，观察者输出到 14.2 的指标。

## 8. 项目中的真实执行过程

一次「webhook 目标变慢」的 20 秒时间线：

1. 第 0 秒：某 webhook 目标开始 30 秒超时。WebhookDeliveryRelay 的这一轮在池里的一条线程上逐行投递，逐行等超时。
2. 第 2 秒：ClickOutboxRelay 到期，从池里取**另一条**线程，正常抽水 outbox，PENDING 积压照常追平。这就是 `application.yml:8-9` 那行注释买的保障。
3. 第 5 秒：flusher 到期，第三条线程，冲刷过期聚合窗。第 15 秒：sampler 第四条线程，三条 COUNT。它们都不排在 webhook 后面。
4. 第 15 秒：Prometheus 抓取。`linkforge_webhook_delivery_total{result="retry"}` 增速抬升、`linkforge_webhook_pending` 积压上涨，看板 Webhook row 先于任何告警显形（14.2）。
5. 第 30 秒：超时批次结束，relay 线程归还。下一轮 fixedDelay 2 秒后继续。若目标持续慢，attempts 累计到 8，行进 FAILED 终态，积压 gauge 回落，`delivery{result="failed"}` 计数可见。

反例（池为 1 的假设世界）：第 2 秒的 outbox 抽水排在 webhook 线程后面，点击统计的追平被一个慢目标拖住 30 秒；点击越多积压越大，故障半径从 webhook 域蔓延到统计域。这就是那三行注释存在的理由。

## 9. 为什么这样设计

- **为什么任务散在业务域而不集中**：任务就是业务逻辑的定时形态，放域内与同域 Mapper、Service 就近；集中的中央调度器只会变成上帝类。真正跨域的（retention、采样）单独成包，正好是两个例外。
- **为什么门禁每 tick 重读**：配置热切换免重启。任务常驻、行为由配置决定，是功能开关的标准形态；代价是每 tick 一次属性读，可忽略。
- **为什么 cleanup 的 outbox 走 SQL 内 cutoff、webhook 走参数 cutoff**：一致性来自「比较跟随写入」。两表 `sent_at` 的写入时钟不同，清理解耦成两种 cutoff 是必然结果（5.3 第 5 点）。统一两种写入也能达成一致，但要动模块 10 的 markSent，得不偿失。
- **为什么 window_start 敢混用两钟**：窗口归类需要与 `clickedAt` 同源的算术（`AggregateWindows.java:8-9` 注释），过期判定容忍分钟级偏差（窗口本身 60 秒）。混合的两端各自贴近自己的语义（推断，依据是两处注释）。

## 10. 本节总结

- 基础设施三行：`@EnableScheduling`（`SchedulingConfig.java:7`）、池 6（`application.yml:11-12`）、毫秒配置引用。
- 8 个任务一张表：两个 relay（2s）、flusher（5s）、reconciler（30s）、cleanup（1h）、sampler（15s）、rebuild（24h）、reload（30s）；节奏、门禁、读写、失败语义见 5.2。
- 隔离三层：fixedDelay 防自踩、池 6 防跨任务排队、单任务最坏时长有界；多实例靠幂等不靠锁。
- 双时钟一句话：调度列写比同钟（DB），业务时间戳 JVM，webhook 的 `sent_at` 是 JVM 纪律的样板，`window_start` 是唯一混合点。
- SentRowCleanup 三件套：LIMIT 切批、maxPasses 封顶、追平即停；`SentRowCleanupTest` 用反射读 SQL 原文钉住口径。
- 眼力练习：`WebhookDeliveryRelay.java:28` 的 4-thread 已滞后于配置 6，注释是历史，配置是事实。

## 11. 课后练习

**1.（概念理解）** 把 8 个任务的注解全部从 fixedDelay 改成 fixedRate，哪些风险会出现？至少说出两种。

**答案**：① 同任务重叠：长轮次（Bloom rebuild 全表扫）没结束时下一轮又排队执行，内存 filter 换引用与 busy 计数被无谓冲击；② 慢任务在单轮里挤压他任务：fixedRate 只管触发不管完成，池压力更容易到 8；③ relay 的「处理耗时计入间隔」变成不计入，重试风暴时轮询频率实际翻倍。fixedDelay 的「结束才开始计时」是这些后台任务的正确节奏。

**2.（源码定位）** 在 `SentRowCleanupTest.java` 里找到验证「webhook 清理 SQL 不用 DB 时钟」的三行断言，并说明每行断的是什么。

**答案**：`:102` `assertTrue(webhook.contains("#{cutoff}"))`——cutoff 是 Java 传入的参数；`:103` `assertFalse(webhook.toUpperCase().contains("NOW()"))`——SQL 文本里没有任何 DB 时钟函数；配对的是 `:101` `assertTrue(outbox.toUpperCase().contains("DATE_SUB(NOW()"))`——outbox 反向钉死必须用 DB 时钟。三条合起来把 5.3 第 5 点的口径变成回归资产。

**3.（执行过程）** 运维把 `linkforge.webhook.relay-enabled` 改成 false 并热生效后，已 PENDING 的投递行会怎样？哪个指标先显形？

**答案**：drain 的门禁（`WebhookDeliveryRelay.java:66-69`）每 tick 重读配置，下一个 tick 起整轮提前 return；行保持 PENDING，不耗 attempts、无状态变化。显形顺序：`linkforge_webhook_pending` gauge 停止下降并随新事件上涨（15 秒采样延迟内），delivery{result} 三态计数停更。aggregate 的 digest 行不受影响——flusher 只看 `webhook.enabled`，digest 仍会生成投递行，但同样停在 PENDING。

**4.（调用链）** 写出「outbox 里 5 行 SENT 且 sent_at 是 8 天前」这一批行从被发现到消失的完整链路，标注每步的类与行。

**答案**：① cleanup 到期进入 `SentRowCleanup.cleanup`（`SentRowCleanup.java:34-36`）；② `purgeSent` 检查 sentDays=7>0，算 retentionSeconds=604800（`:52-60`）；③ 循环第一 pass 调 `outboxMapper.deleteSentOlderThan(604800, 1000)`（`:64`）；④ SQL 按 `status='SENT' AND sent_at < DATE_SUB(NOW(), INTERVAL 604800 SECOND)` LIMIT 1000 删除 5 行并返回 5（`OutboxMapper.java:33-35`）；⑤ webhook 侧同 pass 返回 0，两者都 < batch，break（`:67-69`）；⑥ 返回 5，cleanup() 的 `removed > 0` 分支打一条 INFO（`:39-42`）。

**5.（设计分析）** ClickLogReconciler 的门禁为什么要求 `asyncClickEnabled && syncClickEnabled` 同时为真？只开 async 时让它跑会有什么后果？

**答案**：对账的对象是「sync 双写写入的 lf_click_log 明细行」与「async 链路消费结果」的差集（`ClickLogMapper.java:14-18` 的 LEFT JOIN 条件）。只开 async 时明细行根本不存在（写入只走 outbox），扫描永远空转，无害但无意义；更糟的组合是只开 sync——此时 MQ 链路本来就不在投递，对账若还在跑会把这些行当「未消费」反复重放，放大写库。双开才是「两条链路都活着，比对才有意义」的前提（`:33-35` 门禁与 `ClickLogMapper` 的扫描条件互为表里）。

## 12. 与后续课程的联系

本课的表是前面四个模块的索引页：relay 状态机在 10.2 与 13.2 已深讲，对账 SQL 在 11.3，名单热载与 Bloom rebuild 在 8.3 与 9.1 有各自上下文。测试侧，15.1 的测试盘点会把 `SentRowCleanupTest` 归入纯单测 151 例，`WebhookRetryClockIT` 的时区 fork 机制在 15.2 展开；15.3 的 prod profile 与 compose 部署决定这些任务在生产里的真实节奏。advanced/12（依赖故障处理）会重放本课 8 的时间线，把「隔离买到的故障半径」算成数字。
