# Mermaid 备用图 · 跳转判定链（GET /r/{code}）

> 交互版见 [../archify/html/12-redirect-decision.html](../archify/html/12-redirect-decision.html)。
> 口径来源：README「跳转判定总顺序」12 步；实现类 `link/controller/RedirectController.java` 与 `link/service/ShortLinkService.java`（resolveTarget/resolveForProbe/resolveAndRecord）。每步失败语义互不吞并。

```mermaid
flowchart TD
    A["请求 GET /r/{code}"] --> L{"1. 按 IP 滑动窗口限流"}
    L -- "超限" --> L429["429 + Retry-After"]
    L -- "放行" --> B{"2. BloomFilter mightContain"}
    B -- "负答(从未存在)" --> N404["404"]
    B -- "可能存在(含误判)" --> AB{"3. 空值缓存 linkforge:absent:{code} 命中?"}
    AB -- "命中" --> N404
    AB -- "未命中" --> C1{"4. L1 Caffeine 命中?"}
    C1 -- "命中(仅省 URL 读取)" --> ROW["5. 行读(仍读 DB 行)"]
    C1 -- "未命中" --> C2{"L2 Redis linkforge:code:{code} 命中?"}
    C2 -- "命中" --> ROW
    C2 -- "未命中" --> SF["5. singleflight 合并并发行读"]
    SF --> MISS{"行存在?"}
    ROW --> MISS
    MISS -- "不存在" --> WAB["写空值缓存 + 404"]
    MISS -- "存在" --> ST{"6. 状态/过期判定(本请求时钟)"}
    ST -- "禁用" --> N404B["404(不进空值缓存)"]
    ST -- "过期" --> G410["410(不进空值缓存)"]
    ST -- "正常" --> UV{"7. URL 可渲染校验"}
    UV -- "脏行" --> G410B["410(与缓存无关)"]
    UV -- "可渲染" --> B9{"8. B9 域名门禁 BLOCK?"}
    B9 -- "命中" --> P403["403 拦截页(无 Location)"]
    B9 -- "未命中" --> PW{"9. 密码短链且有有效凭证?"}
    PW -- "无凭证" --> UNLOCK["200 解锁页(不计点击; HEAD=401)"]
    PW -- "有凭证/无密码" --> W{"10. B9 WARN 命中且未带 lf_continue=1?"}
    W -- "命中" --> WARNPAGE["200 警示中间页"]
    W -- "放行" --> B2{"11. B2 预取/预览 bot?"}
    WARNPAGE -- "用户点继续" --> B2
    B2 -- "是" --> T302S["302(全链路不计点击)"]
    B2 -- "否" --> VP{"12. 有 variants?"}
    VP -- "有" --> PICK["VariantPicker 按权重抽签"]
    VP -- "无" --> T302["302 + Location=original_url"]
    PICK --> T302V["302 + Location=所抽 variant URL"]
    T302 --> REC["同事务: lf_click_log + lf_outbox(PENDING)"]
    T302V --> REC
    REC -- "记账失败" --> FO["fail-open: 302 保持 + meter linkforge.click.record.failopen"]
```

要点核对表（对应源码主题，课程 07 系列逐条讲）：

| 图上节点 | 源码位置主题 |
|---|---|
| 1 限流 | `RedisRateLimiter` + `sliding_window.lua` |
| 2 Bloom | `CodeBloomFilter` |
| 3 空值缓存 | `ShortLinkService` absent 分支 |
| 4/5 L1/L2/行读 | `UrlLocalCache`、`ShortLinkRowLoader`、`SingleFlight` |
| 6/7 状态与校验 | 行状态字段 + `LinkValidations` |
| 8/10 B9 | `TargetPolicy`/`PhishingPolicy`/`GatePages` |
| 9 密码门 | `LinkAccessTokens`、`/r/{code}/unlock` |
| 11 B2 | `ClickFilter` |
| 12 variants | `VariantPicker`、`LinkVariantMapper` |
| 记账 | `ClickRecordService.recordClick` |
