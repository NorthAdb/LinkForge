# Mermaid 备用图 · 创建短链流程（POST /api/links）

> 交互版见 [../archify/html/11-shortlink-create.html](../archify/html/11-shortlink-create.html)。
> 口径来源：README「短链管理」「A/B 权重分流与批量导入（C3）」「目标域名名单门禁（B9）」；实现类 `link/controller/ShortLinkController.java`、`link/service/ShortLinkService.java`（create→doCreate）。

```mermaid
flowchart TD
    REQ["POST /api/links<br/>(JWT 或 API Key 身份)"] --> AUTH["Security 过滤器链认证<br/>(JwtAuthFilter / ApiKeyAuthFilter)"]
    AUTH --> RL{"创建限流<br/>create:user:{userId} 30/60s"}
    RL -- "429" --> R429["429 + Retry-After"]
    RL -- "放行" --> V1{"参数校验<br/>originalUrl 可解析 http(s)?<br/>expireAt 未来? customCode 4-16位?"}
    V1 -- "400" --> B400["400 (BizException → ApiResponse)"]
    V1 -- "通过" --> V2{"自定义码保留字?<br/>(admin/login/stats/... 大小写不敏感)"}
    V2 -- "命中" --> B400
    V2 -- "通过" --> V3{"B9 域名门禁 BLOCK?<br/>(链层 original_url, 含 variants 每个 URL)"}
    V3 -- "BLOCK" --> B400
    V3 -- "通过" --> V4{"UTM 写时合并<br/>合法字符? 已有同名参数不覆盖?<br/>合并后 ≤2048?"}
    V4 -- "400" --> B400
    V4 -- "通过" --> V5{"variants 校验(如带)<br/>key 唯一/weight 1..10000/≤10 组<br/>URL 走同款校验"}
    V5 -- "400" --> B400
    V5 -- "通过" --> IDEM{"带 Idempotency-Key?"}
    IDEM -- "是" --> SETNX["Redis SETNX idem:{key}→pending<br/>(5s 超时 / 24h 终值)"]
    SETNX -- "已有终值 code" --> REPLAY["直接重放返回原短链"]
    SETNX -- "pending 在途" --> WAITP["等待/超时分支"]
    IDEM -- "否" --> GEN["随机 7 位短码<br/>(大小写敏感字符集)"]
    GEN --> INS["INSERT lf_short_link<br/>uk_code 唯一索引兜底"]
    INS -- "DuplicateKey" --> REGEN["换码重试"]
    REGEN --> INS
    INS -- "成功" --> VAR["variants 整表写 lf_link_variant<br/>(与主表同一事务)"]
    VAR --> POST["后置动作集合:<br/>1. Bloom put + 广播 linkforge:bloom:put<br/>2. DEL linkforge:absent:{code}<br/>3. 缓存写穿 + 1s 延迟双删<br/>4. meter linkforge.links.created"]
    POST --> RESP["ApiResponse.ok(data)<br/>code/originalUrl/shortPath"]
    RESP --> SETTLE["幂等 settle: pending→code"]
```

要点核对表（课程 6.2 逐条展开）：

| 图上节点 | 语义要点 |
|---|---|
| 校验顺序 | 结构性校验全部在任何写之前（C3 导入也复用同一路径防漂移） |
| 撞码 | 不做「先查后插」，唯一索引是并发正确性来源，DuplicateKey 重试 |
| 幂等两段式 | pending 5 秒自愈、终值保留 24h；胜者崩溃后下一位可重新领头 |
| 门禁 BLOCK | 建链阶段拒绝存量写入；跳转阶段对行上 URL 复查（名单变更对存量立即生效） |
| 后置动作 | Bloom put、absent 清除、缓存写穿、广播全部在事务提交路径上，失败 fail-open 不阻断创建 |
