# Mermaid 备用图 · 跳转判定链（GET /r/{code}）

> 交互版见 [../archify/html/12-redirect-decision.html](../archify/html/12-redirect-decision.html)。
> 口径来源：课程 7.1「resolveTarget：12 步判定总顺序」表（源码 `ShortLinkService.java:601-712` 实测，编号以此为准；README 列表把 L1/L2 合并为一步且 B2 排在抽签前）；实现类 `link/controller/RedirectController.java` 与 `link/service/ShortLinkService.java`（resolveTarget/resolveForProbe/resolveForGet）。每步失败语义互不吞并。

```mermaid
flowchart TD
    A["请求 GET /r/{code}"] --> L{"1. 按 IP 滑动窗口限流"}
    L -- "超限" --> L429["429 + Retry-After"]
    L -- "放行" --> B{"2. BloomFilter mightContain"}
    B -- "负答(从未存在)" --> N404["404"]
    B -- "可能存在(含误判)" --> AB{"3. 空值缓存 linkforge:absent:{code} 命中?"}
    AB -- "命中" --> N404
    AB -- "未命中" --> C1{"4. L1 Caffeine 命中?"}
    C1 -- "命中(仅省 URL 读取)" --> SF["6. singleflight 合并并发行读(仍读 DB 行)"]
    C1 -- "未命中" --> C2{"5. L2 Redis linkforge:code:{code} 命中?"}
    C2 -- "命中" --> SF
    C2 -- "未命中" --> SF
    SF --> MISS{"行存在?"}
    MISS -- "不存在" --> WAB["写空值缓存 + 404"]
    MISS -- "存在" --> ST{"7. 状态/过期判定(本请求时钟)"}
    ST -- "禁用" --> N404B["404(不进空值缓存)"]
    ST -- "过期" --> G410["410(不进空值缓存)"]
    ST -- "正常" --> UV{"8. URL 可渲染校验"}
    UV -- "脏行" --> G410B["410(与缓存无关)"]
    UV -- "可渲染" --> B9{"9. B9 域名门禁 BLOCK?"}
    B9 -- "命中" --> P403["403 拦截页(无 Location)"]
    B9 -- "未命中" --> PW{"10. 密码短链且有有效凭证?"}
    PW -- "无凭证" --> UNLOCK["200 解锁页(不计点击; HEAD=401)"]
    PW -- "有凭证/无密码" --> W{"11. B9 WARN 命中且未带 lf_continue=1?"}
    W -- "命中" --> WARNPAGE["200 警示中间页"]
    W -- "放行" --> VP{"12. 抽签: selectByLinkId(:708), 有 variants?"}
    WARNPAGE -- "用户点继续" --> VP
    VP -- "有" --> PICK["VariantPicker 按权重抽签"]
    VP -- "无(查空即 null, 抽签已消耗)" --> T302["Redirect: Location=original_url"]
    PICK --> T302V["Redirect: Location=所抽 variant URL"]
    T302 --> B2{"12(续). B2 预取/预览 bot?(resolveForGet :568-572, 抽签返回后)"}
    T302V --> B2
    B2 -- "是" --> T302S["302(全链路不计点击)"]
    B2 -- "否" --> REC["同事务: lf_click_log + lf_outbox(PENDING)"]
    REC -- "记账失败" --> FO["fail-open: 302 保持 + meter linkforge.click.record.failopen"]
```

要点核对表（对应源码主题，课程 07 系列逐条讲）：

| 图上节点 | 源码位置主题 |
|---|---|
| 1 限流 | `RedisRateLimiter` + `sliding_window.lua` |
| 2 Bloom | `CodeBloomFilter` |
| 3 空值缓存 | `ShortLinkService` absent 分支 |
| 4/5/6 L1/L2/行读 | `UrlLocalCache`、`ShortLinkRowLoader`、`SingleFlight` |
| 7/8 状态与校验 | 行状态字段 + `LinkValidations` |
| 9/11 B9 | `TargetPolicy`/`PhishingPolicy`/`GatePages` |
| 10 密码门 | `LinkAccessTokens`、`/r/{code}/unlock` |
| 12 抽签 | `VariantPicker`、`LinkVariantMapper` |
| 12(续) B2 | `ClickFilter` |
| 记账 | `ClickRecordService.recordClick` |
