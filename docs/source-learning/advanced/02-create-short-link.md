# 专题 2 · 创建短链全链路

> 模块课 6.2 把 create 与 doCreate 逐段讲完了。本专题把一次 POST /api/links 从 HTTP 入口拉到响应出口，重点补三件模块课没串起来的事：安全层怎么把请求送到 create 门口、创建写的那些缓存与 Bloom 如何被跳转链路（专题 4）消费、以及每个失败分支的副作用边界在哪里。

## 1. 业务背景：全项目唯一的一条写入口

用户故事。运营同学在控制台粘贴一个长 URL，勾选 UTM 参数，可选地设置自定义码、过期时间、访问密码、A/B 分流，点创建。几百毫秒后拿到 `/r/aB3xK9m`。她不关心背后有幂等锁、滑动窗口和门禁名单，只关心两件事：这个码立刻能用，同一个表单别提交出两条链。

这条链路值得单独讲，因为它是全项目收敛度最高的写路径。`ShortLinkService.create` 是唯一往 `lf_short_link` 插行的公开入口，CSV 导入最终也汇入 `doCreate`（回看 [6.2](../modules/06-short-link/02-create-flow.md) 第 12 节）。限流、幂等、门禁、校验、事务、缓存预热六个横切关切全部挂在一条调用链上，任何一条没看懂，都会在排障时变成「为什么创建失败了/为什么创建了两次/为什么新链 404 了」三类真实事故。

她故事里的「同一个表单别提交出两条链」就是幂等键存在的理由。浏览器双击、网关重发、用户手动刷新，都会让同一个 POST 到达两次。POST 在 HTTP 语义里天然不幂等，所以契约要求客户端带一个 Idempotency-Key 头，服务端保证同键只建一条。这个契约的完整实现是本专题的中轴线。

## 2. 相关类清单

| 类 | 角色 | 模块课 |
|----|----|----|
| `ShortLinkController` | HTTP 入口，透传 Idempotency-Key 头 | [6.2](../modules/06-short-link/02-create-flow.md) |
| `ShortLinkService`（create） | 身份、限流、夹带检查、幂等调度 | [6.2](../modules/06-short-link/02-create-flow.md) |
| `ShortLinkService`（doCreate） | 七步校验、事务落库、提交后动作 | [6.2](../modules/06-short-link/02-create-flow.md) |
| `RedisRateLimiter` | create:user 滑动窗口限流 | 9.1 课（本专题不展开算法） |
| `LinkValidations` | URL 结构与自定义码字符集校验 | [6.2](../modules/06-short-link/02-create-flow.md) |
| `UtmMerger` | UTM 参数规范化与合并（mergeUtm 调用它） | 6.4 课 |
| `TargetPolicy` + `PhishingPolicy` | B9 域名门禁判定与名单供数 | [7.2](../modules/07-redirection/02-password-and-domain-gates.md) |
| `ShortCodes` | SecureRandom 随机短码 | [6.2](../modules/06-short-link/02-create-flow.md) |
| `TransactionTemplate` | 编程式事务边界（create 用它而非 @Transactional） | 4.3 课（本专题不展开） |
| `LinkForgeProperties` | 限流、幂等 TTL、码长的配置集中地 | [6.2](../modules/06-short-link/02-create-flow.md) 第 3 节 |
| `CodeBloomFilter` / `CacheBus` / `UrlLocalCache` | 提交后五连中的三个加速层 | [7.1](../modules/07-redirection/01-decision-chain.md)、[6.2](../modules/06-short-link/02-create-flow.md) |
| `GlobalExceptionHandler` | 400/409/429/503 的统一出口 | [6.2](../modules/06-short-link/02-create-flow.md) 第 2 节 |

源码路径前缀 `src/main/java/com/linkforge/` 省略。字段与唯一索引的表结构口径见 [6.1](../modules/06-short-link/01-data-model.md)。

## 3. 完整调用链

```
POST /api/links（头：Authorization: Bearer、Idempotency-Key 可选）
  → JwtAuthFilter 解析 Bearer，授 ROLE_USER（JwtAuthFilter.java:34-59）
  → 角色矩阵：/api/links/** 允许 USER 或 API_KEY（SecurityConfig.java:63）
  → ShortLinkController.create（ShortLinkController.java:43-48）
      @Valid 校验请求体，Idempotency-Key 头透传（:45-46）
  → ShortLinkService.create（ShortLinkService.java:129-176）
      ├─ requireUser（:130 → :910-916，空身份 401，纵深防御）
      ├─ 滑动窗口限流（:131-138，键 create:user:{userId}，默认 30 次/60 秒）
      ├─ 超限 → RateLimitedException → 429 + Retry-After
      ├─ rejectSmuggledAttribution（:140 → :871-878，utm 前缀与广告归因 id → 400）
      ├─ 有幂等键（:141-173）：
      │    ├─ replay 查终值（:143，replayIdempotentCreate :182-201，回库验归属）
      │    ├─ SETNX pending 5 秒抢锁（:152-155）；Redis 异常 fail-open 视为抢到（:156-160）
      │    ├─ 赢家 → doCreate（:162）
      │    └─ 输家 → 轮询 10 次 × 200ms（:164-170）→ 拿到终值返回，否则 503（:173）
      └─ 无幂等键 → doCreate（:175，幂等键传 null）
  → ShortLinkService.doCreate（ShortLinkService.java:203-283）
      ├─ 校验七步（:204-218）：UTM 合并 → URL 结构 → B9 门禁 → 过期时间
      │    → variants → 自定义码 → 密码编码（全部在任何写之前，注释 :211 D-C3-7）
      ├─ 组装 ShortLink 实体（:220-229）
      ├─ 编程式事务（:237-264，TransactionTemplate）
      │    ├─ 自定义码：insert 撞 uk_code → 409 "code already exists"（:244-246）
      │    ├─ 随机码：最多 16 次重试（:248-256，CODE_ATTEMPTS=16 于 :86）
      │    ├─ 16 次全撞 → "failed to allocate short code"（:258-260）
      │    └─ variants 同事务插入（:261-263，C3-S1）
      ├─ 提交后五连 + 幂等终值（:266-281）：
      │    codeBloom.put → publishBloomPut 广播 → clearAbsent → cacheUrl → 计数
      │    → Redis 写回短码终值，TTL 86400 秒（application.yml:202）
      └─ 返回 CreateShortLinkResponse(id, code, url, "/r/"+code)（:282）
```

### 3.1 输家支线：同键并发请求的完整路径

幂等分支里输家走的代码值得单列，因为它是一次真正的跨请求协作。

```
请求 B（同键，晚于赢家 A 到达）
  → 限流放行（各自计数）→ 夹带检查通过
  → replayIdempotentCreate（ShortLinkService.java:182-201）
      ├─ Redis GET 幂等键（:184-189）；键不存在/空串/pending → 返回 null
      ├─ Redis 异常 → fail-open 视为未重放（:186-190）
      └─ 键里已有短码 → 回库 selectOne 验证这条链属于当前用户（:195-198）
  → SETNX 失败（A 的 pending 还在）→ 进入轮询循环（:164-170）
      ├─ 每 200ms 重放一次 replayIdempotentCreate
      ├─ A 提交后 B 读到终值 → 回库验归属 → 返回与 A 相同的四字段响应
      └─ 10 次后仍无终值 → BizException(503)（:173）
```

线程标注：全程同步，一条 Tomcat 工作线程跑到底。输家的 200ms 轮询睡眠发生在自己的线程上，没有线程切换。事务提交后动作仍在同一线程，只是出了事务边界。

## 4. 可视化

阅读顺序：

1. [11-shortlink-create](../diagrams/archify/html/11-shortlink-create.html)：主图。先看泳道间的限流与幂等四分支（含 503 走廊），再看事务框内的插入循环，最后看提交后动作链。
2. [create-flow（Mermaid 备用）](../diagrams/mermaid/create-flow.md)：同一流程的静态版。图中「SETNX pending 5s 超时 / 24h 终值」对应第 3 节的 :152-155 与 :271-274，「后置动作集合」对应 :266-270。
3. [12-redirect-decision](../diagrams/archify/html/12-redirect-decision.html)：对照读。创建写的 Bloom/缓存，正是跳转判定链第 2、4、5 步读的东西。

主图与源码的对应关系：图中「无键直建」对应 :175（幂等键传 null）；「重放命中」对应 :144-146；「终值重放」对应 :166-168 加归属校验；「e503 走廊」对应 :171-173 的轮询超时。事务框内外的分界线就是 :237 与 :266 之间，框内只有插入循环，框外是五连加 settle。

## 5. 源码详解：只看跨类协作的节点

**节点一：幂等两段式的跨组件协作。** 幂等键形状 `{keyPrefix}idem:{userId}:{客户端key}`（:142），userId 进键名让不同用户互不干扰。第一段 SETNX 抢锁，第二段赢家把短码写回同一个键当终值。输家的重放读（`replayIdempotentCreate` :182-201）拿到短码后还要回库确认归属，租户隔离在幂等路径上不豁免。逐行细节回看 [6.2](../modules/06-short-link/02-create-flow.md) 第 5.2、5.3 节。

pending 锁的 5 秒 TTL 是自愈设计：赢家进程崩溃后锁自动消失，下一个同键请求重新抢锁执行，不存在永久卡死的锁。反过来它也是一把软尺：pending 状态超过 5 秒仍在，几乎可以断定赢家没跑完就死了，或者 settle 失败。这两种情况的善后方式完全不同（前者 503 后重试可自愈，后者会在 TTL 过期后重建成第二条链，即第 7 节的 settle fail-open 行）。

**节点二：门禁在写前，且两侧共用同一套判定。** `rejectBlockedTarget`（:207 → :797-807）在创建时对 BLOCK 抛 400，message 固定为 "target domain is not allowed by policy"，不回显命中的域名。跳转侧（专题 4）对同一个 `TargetPolicy` 用 `forCreate=false` 调用，BLOCK 出 403 拦截页。一个判定函数、两种失败出口，口径由 `forCreate` 参数切换（回看 [7.2](../modules/07-redirection/02-password-and-domain-gates.md) 第 5.5 节）。UTM 合并排在门禁之前，因为门禁检查的必须是入库的那个最终 URL。还有一处不对称值得记住：创建时 WARN 命中只打点放行（:802-806），拦截只发生在跳转侧。写入者不该被名单挡住合法的管理操作，风险拦截延后到真正有人点击的时刻。

**节点三：提交后五连是写给跳转链路的。** `codeBloom.put` 让跳转第 2 步的 Bloom 门放行；`clearAbsent` 防止这个码曾被误判进负缓存导致 404；`cacheUrl` 把主 URL 写进 L1/L2，跳转第 4、5 步直接命中，TTL 为基值加抖动（`ShortLinkService.java:918` 附近的 D-Bw1-5 注释）。这五步与 `resolveTarget` 的读路径严格对应，读完本专题应立刻能读懂专题 4 的前六步。多实例部署下 `publishBloomPut` 负责让其他进程的 Bloom 也播种（回看 [6.2](../modules/06-short-link/02-create-flow.md) 第 5.6 节、[7.1](../modules/07-redirection/01-decision-chain.md) 第 5.4 节）。

**节点四：事务里只留一种意外。** 七步校验全在事务外，进事务后唯一可能的意外是插入撞唯一索引。自定义码撞了是 409 业务冲突，随机码撞了换码重试。variants 与主行同事务，杜绝「有主行没分流」的中间态（回看 [6.2](../modules/06-short-link/02-create-flow.md) 第 5.5 节）。

**节点五：CSV 导入汇入同一个 doCreate。** 导入入口（`ShortLinkController.java:63-65` 起）逐行走 `doCreate`（`ShortLinkService.java:380` 附近，见 6.3 课）。七步校验在导入场景原样生效，这是「防漂移」设计：两条写入口永远不会有两种行为。差异只有两处：导入不认 Idempotency-Key（注释 D-C3-23，逐行响应本身就是回执），导入的限流键与 create 分开（application.yml:197 附近注释「import cannot trip the create window」）。

**节点六：requireUser 的纵深防御。** 过滤器链已经保证受保护路径必有身份，`create` 第一步仍然调 `requireUser`（:130，实现在 :910-916，空身份抛 401）。这是纵深防御：链层配置改动、新入口接入时，服务层的判空让错误表现为干净的 401 而不是 NullPointerException 换 500（回看 [5.3](../modules/05-security/03-usercontext-authz.md) 第 5.4 节）。

**节点七：幂等键与限流键共用一套前缀。** 限流键用 `RateLimitKeys.sw` 拼前缀（:133），幂等键用同一个 `keyPrefix` 手工拼接（:142），默认 `linkforge:rl:`（application.yml:204），TTL 各自独立（pending 5 秒硬编码于 :155，终值 86400 秒走配置 :202）。共用前缀意味着排障时按 `linkforge:rl:*` 能同时看到两类键。区别在键的中段语义：限流键带 `create:user:`，幂等键带 `idem:{userId}:`，userId 进幂等键是租户隔离的一部分。

## 6. 正常流程：带 Idempotency-Key 的一次创建

| 时刻 | 线程 | 动作 | 证据 |
|----|----|----|----|
| t0 | Tomcat W1 | Bearer 解析通过，ROLE_USER 就位 | `JwtAuthFilter.java:38-48` |
| t1 | W1 | Controller 透传请求体与 Idempotency-Key: "abc-key" | `ShortLinkController.java:43-48` |
| t2 | W1 | requireUser 命中；限流键 create:user:42 计数未超 30/60s | `ShortLinkService.java:130-138` |
| t3 | W1 | 夹带检查通过（unknown map 里没有 utm/归因参数） | `ShortLinkService.java:140` |
| t4 | W1 | replay 读 `linkforge:rl:idem:42:abc-key`，键不存在返回 null | `ShortLinkService.java:143` |
| t5 | W1 | SETNX 写 "pending" 成功，TTL 5 秒；本请求成为赢家 | `ShortLinkService.java:152-155` |
| t6 | W1 | 七步校验全过：URL 合法、门禁 ALLOW、variants 可用 | `ShortLinkService.java:204-218` |
| t7 | W1 | 开事务，随机码 aB3xK9m 首次插入成功 | `ShortLinkService.java:237-252` |
| t8 | W1 | variants 插入（如有），事务提交 | `ShortLinkService.java:261-264` |
| t9 | W1 | Bloom 播种、广播、清 absent、写 L1/L2 缓存 | `ShortLinkService.java:266-269` |
| t10 | W1 | 幂等键被终值 aB3xK9m 覆盖，TTL 重置为 24 小时 | `ShortLinkService.java:271-274` |
| t11 | W1 | 响应 200 + 四字段 CreateShortLinkResponse | `ShortLinkService.java:282` |

双请求并发场景的补全：另一个请求 B 携同键在 t5 前后到达，SETNX 失败成为输家，在自己的线程上每 200ms 重放一次；t10 之后 B 的下一次轮询读到终值，回库确认归属后返回与 A 完全相同的响应。数据库里只有一条链、一次插入。

输家 B 的独立时间线：

| 时刻 | 线程 | 动作 | 证据 |
|----|----|----|----|
| b0 | Tomcat W2 | B 通过限流与夹带检查（与 A 各自计数） | `ShortLinkService.java:130-140` |
| b1 | W2 | replay 读键，只看到 pending，返回 null | `ShortLinkService.java:182-194` |
| b2 | W2 | SETNX 返回 false（A 的锁未过期），进轮询 | `ShortLinkService.java:152-163` |
| b3 | W2 | 第 1 次轮询：睡 200ms，重放，仍无终值 | `ShortLinkService.java:164-166` |
| b4 | W2 | 第 2 次轮询：A 已 settle，读到 aB3xK9m | `ShortLinkService.java:166-168` |
| b5 | W2 | 回库确认该链 createdBy 是当前用户 | `ShortLinkService.java:195-198` |
| b6 | W2 | 返回与 A 逐字段相同的响应，未开任何事务 | `ShortLinkService.java:168` |

两条时间线合起来的结论：幂等契约的「一次请求一个结果」由四个部件共同维持，SETNX 抢锁、TTL 自愈、轮询重放、归属校验。少任何一个，要么同键双链，要么重放泄露别人的链，要么赢家崩溃后锁永久卡死。

## 7. 异常流程：每个失败分支

| 触发条件 | 返回 | 副作用 | 出处 |
|----|----|----|----|
| 无/坏 Bearer 撞受保护路径；Bearer 解析失败 | 401（unauthorized / invalid token） | 无 | `SecurityConfig.java:72-76`、`JwtAuthFilter.java:51-54`（专题 1 第 7 节） |
| 60 秒内第 31 次创建 | 429 + Retry-After | 限流键仍在窗口内 | `ShortLinkService.java:135-138` |
| 请求体携带 utm 前缀或 9 个广告归因 id | 400 "unknown attribution parameter" | 无（幂等尚未介入） | `ShortLinkService.java:871-878` |
| UTM 合并后 URL 超 2048 | 400 "merged originalUrl exceeds 2048" | 无 | `ShortLinkService.java:863-869` |
| URL 非 http(s)/含控制字符/非法 URI | 400 | 无 | `LinkValidations.requireHttpUrl`，:206 调用 |
| B9 门禁 BLOCK | 400，不回显域名 | 无 | `ShortLinkService.java:797-807` |
| expireAt 不是未来时间 | 400 | 无 | `ShortLinkService.java:208-210` |
| variants 违规（URL/查重/超 10 条）或自定义码/密码不合规范 | 400 | 无（校验前置，事务未开） | `ShortLinkService.java:212-218`、`:823-843`、`:810-819` |
| 自定义码已被占用（含逻辑删行占位） | 409 "code already exists" | 无 | `ShortLinkService.java:244-246` |
| 随机码 16 次全撞（概率极低） | 400 "failed to allocate short code" | 无（事务内抛出，事务回滚） | `ShortLinkService.java:258-260` |
| 同幂等键输家等待 2 秒仍无终值 | 503 "idempotent create still in progress" | pending 键继续倒计时 | `ShortLinkService.java:171-173` |
| 重放读到终值但那条链属于别的用户 | 视为未重放，走正常创建 | 无 | 归属校验，`ShortLinkService.java:195-198` |
| 校验失败发生在抢锁之后（400 各行） | 400 | pending 键无终值，等 5 秒 TTL 自灭；窗口内同键重试得 503 | `ShortLinkService.java:152-155`（[6.2](../modules/06-short-link/02-create-flow.md) 第 8 节） |
| Redis 整体宕机，SETNX 抛异常 | 200（两个并发请求各自建成一条） | 同键双链 | acquire fail-open，`:156-160` |
| Redis 宕机持续到 settle | 200 | 键停在 pending 到 TTL 过期，停机期同键重试会再造一条 | settle fail-open，`:275-280` |
| 非环境型意外（代码缺陷） | 500，统一兜底 | 无 | `GlobalExceptionHandler` 的 Exception 分支 |

两处 fail-open 的不对称是本专题的核心考点：acquire fail-open 牺牲幂等严格性换可用性，settle fail-open 因为链接已提交而放弃终值。共同前提是「任何情况下不为 Redis 可用性回滚一条已落库的链」（回看 [6.2](../modules/06-short-link/02-create-flow.md) 第 6 节）。

读这张表的顺序建议按「副作用」列分组：整张表里只有三行有持久副作用（429 与两处 fail-open 的 Redis 状态），其余全部无副作用。这不是巧合，是设计取舍三的兑现：所有结构性错误都在开事务之前 400 掉，失败得越早，状态越干净。排障时先问「这次失败留了什么状态」，再问「返回了什么」，比只看状态码快得多。

## 8. 设计取舍

**一、幂等两段式不对称 fail-open（D-C4-16）。** 一个 Redis 键从 pending 到短码终值的状态机，配上两处方向相同、后果不同的 fail-open。不做订阅、不做重试队列，代价显式写在注释里。（推断：管理操作低频，双链与重建的低频风险低于给每个创建加分布式严格性的复杂度。）

**二、唯一索引兜底，不做先查后插。** `uk_code` 是并发下唯一的真相（注释 :231-232）。应用层无论加什么锁都管不住多实例。Bloom 与缓存全部只是加速层，坏了一律可重建。

**三、门禁在写前（D-C3-7）。** 注释 :211 直说：校验前置让失败请求连事务都不用开。这比「依赖回滚」便宜，也让第 7 节表里所有 400 的副作用一栏都是「无」。

**四、提交后动作绝不回滚创建。** 五连加幂等 settle 全部在事务外，且 settle 有自己的 try-catch。（推断，依据是动作链位置与注释 :233-235：Bloom 缺失的代价是跳转 404 一次后被 DB 路径纠正，远比创建 500 便宜。）

**五、503 把决策权交回客户端。** 输家不代替赢家创建，也不无限等待。503 加上客户端重试，要么拿到重放，要么在锁过期后成为新赢家（注释 :171-173）。轮询而非订阅是低频场景下的复杂度取舍（[6.2](../modules/06-short-link/02-create-flow.md) 第 6 节标注为推断）。与幂等相关的另一个边界是重放范围：终值只保留 24 小时（:274），一天后同键重发是一条新链，契约的时间有效期由此划定（[6.2](../modules/06-short-link/02-create-flow.md) 第 6 节）。
