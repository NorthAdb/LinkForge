# 6.2 创建短链全流程：create → doCreate 两层拆解

> 本课回答一个问题：POST /api/links 从进门到返回短码，中间经过哪些闸门？同一个 Idempotency-Key 发两次，为什么只会造出一条链？

## 1. 本节学习目标

学完本课，你应该能：

1. 按顺序背出 `doCreate` 的全部校验步骤，并说出为什么顺序不能乱。
2. 解释幂等两段式的完整生命周期：SETNX 抢锁、轮询等结果、终值 24 小时、两处不对称 fail-open。
3. 说明随机短码撞库时为什么「重试 16 次」而不是「先查再插」。
4. 说出 variants 为什么必须与主行同事务提交。
5. 列出事务提交之后按序执行的每个动作，以及每个动作失败时创建是否失败。

## 2. 前置知识

- 模块 6.1 的字段表：`ShortLink` 各字段、`uk_code` 唯一索引、DTO 三态语义。
- `@Transactional` 与编程式事务：模块 4.3 已讲。本课主角是 `TransactionTemplate.executeWithoutResult`，它是 `@Transactional` 的手工版，事务边界看得见。
- Redis `SETNX`：`setIfAbsent(key, value, ttl)`，键不存在才写入，存在则返回 false。分布式锁的最简形态。
- 幂等性（idempotency）：同一操作执行多次与执行一次的效果相同。HTTP 语义里 POST 天然不幂等，所以需要客户端配合一个 Idempotency-Key 头。
- `RateLimitedException` 与统一异常出口：模块 2.1 已讲，429 和 400 都从 `BizException` 家族出去。

## 3. 本节涉及的源码

| 文件 | 行数 | 职责 | 阅读顺序 |
|----|----|----|----|
| `src/main/java/com/linkforge/link/service/ShortLinkService.java` | 1025 | 主角：create :129-176 与 doCreate :203-283 | 1 |
| `src/main/java/com/linkforge/link/service/ShortLinkService.java` | 同上 | 辅助段：replay :182-201、validateVariants :823-843、encodeAccessPassword :810-819 | 2 |
| `src/main/java/com/linkforge/link/controller/ShortLinkController.java` | 128 | 入口与 Idempotency-Key 头 :43-48 | 3 |
| `src/main/java/com/linkforge/link/support/ShortCodes.java` | 39 | 随机码生成 | 4 |
| `src/main/java/com/linkforge/link/support/LinkValidations.java` | 74 | URL 与自定义码校验 | 5 |
| `src/main/resources/application.yml` | - | 限流与幂等默认值 :191-205 | 6 |

为什么这个顺序读：Controller 只是转发，先建立「create 管并发与幂等，doCreate 管校验与落库」的两层心智，再逐段看。

## 4. 类的定位（在整个项目中的位置）

`ShortLinkService.create` 是全项目唯一会往 `lf_short_link` 插行的公开入口。CSV 导入最终也走到它内部的 `doCreate`（6.3 课讲）。全项目写入路径收敛到这一个方法，意味着限流、幂等、门禁、缓存预热这些横切关切只需要实现一次。

`create` 方法 48 行，`doCreate` 方法 81 行，两层职责完全不重叠。

- `create`（`ShortLinkService.java:129-176`）：身份、限流、夹带检查、幂等调度。它决定「这次请求能不能跑、跑几次」。
- `doCreate`（`ShortLinkService.java:203-283`）：校验、落库、提交后动作。它决定「一条链怎么被造出来」。

创建时序全景图见 [11-shortlink-create](../../diagrams/archify/html/11-shortlink-create.html)（由并行任务产出，链接暂未生效时稍后再看）。Mermaid 备用图在 [create-flow.md](../../diagrams/mermaid/create-flow.md)，本课第 8 节逐节点核对。

## 5. 核心源码详解

### 5.1 create：限流、夹带、幂等调度

```java
public CreateShortLinkResponse create(CreateShortLinkRequest request, String idempotencyKey) {
    AuthUser user = requireUser();
    LinkForgeProperties.RateLimit rl = properties.getRateLimit();
    RateLimitDecision decision = rateLimiter.acquire(
            RateLimitKeys.sw(rl.getKeyPrefix(), "create:user:" + user.getUserId()),
            rl.getCreateMax(), rl.getCreateWindowSeconds());
    if (!decision.allowed()) {
        meterRegistry.counter("linkforge.ratelimit.rejected", "entry", "create").increment();
        throw new RateLimitedException(decision.retryAfterSeconds());
    }

    rejectSmuggledAttribution(request.getUnknown());
```
（`src/main/java/com/linkforge/link/service/ShortLinkService.java:129-140`）

四步各司其职。

1. `requireUser()`（:130）从 ThreadLocal 取登录态，没有就 401。实现见模块 5.3。
2. 限流键是 `create:user:{userId}`（:133），滑动窗口默认 30 次 / 60 秒（`application.yml:194-195`）。注意按用户限，不是按 IP，因为创建是登录后的操作。
3. 被限流抛 `RateLimitedException`，全局异常处理器翻译成 429 加 Retry-After 头。
4. `rejectSmuggledAttribution` 检查 6.1 课讲的 `unknown` Map，`utm` 前缀和 9 个广告归因 id 直接 400（:871-878）。

接下来是本课的核心：幂等调度。

### 5.2 幂等两段式的第一段：抢锁

```java
if (idempotencyKey != null && !idempotencyKey.isBlank()) {
    String idemKey = rl.getKeyPrefix() + "idem:" + user.getUserId() + ":" + idempotencyKey;
    CreateShortLinkResponse replayed = replayIdempotentCreate(idemKey, user);
    if (replayed != null) {
        return replayed;
    }
    /**
     * D-C4-16 fail-open: with Redis down the pending lease cannot be taken, so
     * the acquire is treated as won — two concurrent same-key creates can both
     * run during an outage, which beats hard-failing every link write.
     */
    boolean acquired;
    try {
        acquired = Boolean.TRUE.equals(stringRedisTemplate.opsForValue()
                .setIfAbsent(idemKey, "pending", Duration.ofSeconds(5)));
    } catch (Exception ex) {
        log.warn("idempotency acquire failed for {}: {}", idemKey, ex.getMessage());
        meterRegistry.counter("linkforge.cache.failopen", "op", "idem_acquire").increment();
        acquired = true;
    }
    if (acquired) {
        return doCreate(request, user, idemKey);
    }
```
（`src/main/java/com/linkforge/link/service/ShortLinkService.java:141-163`）

幂等键的完整形状是 `{rateLimit前缀}idem:{userId}:{客户端key}`（:142）。userId 进入键名是故意的：不同用户撞同一个 key 互不影响。

执行顺序值得精读。

1. 先查值（:143，replay）。如果键里已经存了终值（一个短码），直接把那条链查出来返回，这就是「同 key 第二次请求拿到第一次的结果」。
2. 查不到（键不存在、是空串、或还在 pending 状态，`ShortLinkService.java:192-194`），尝试 SETNX 写入 `"pending"`，TTL 5 秒（:154-155）。写成功表示「我是这次创建的执行者」。
3. SETNX 抛异常时把 `acquired` 置 true（:156-160）。这是第一处 fail-open：Redis 挂了宁可让两个并发请求都去创建，也不让所有创建一起失败。注释原文就是上面的 D-C4-16 段。

`replayIdempotentCreate` 有一个安全细节（:195-198）：从 Redis 拿到短码后还要回库确认这条链属于当前用户，不是别人的行。租户隔离在幂等重放路径上也不豁免。

### 5.3 第二段：输家的等待与 503

```java
    for (int i = 0; i < 10; i++) {
        sleepQuietly(200);
        CreateShortLinkResponse resolved = replayIdempotentCreate(idemKey, user);
        if (resolved != null) {
            return resolved;
        }
    }
    // The winner has not settled its code; forcing a second create could mint
    // two links under one Idempotency-Key, so the caller retries instead.
    throw new BizException(503, "idempotent create still in progress, retry later");
}
return doCreate(request, user, null);
```
（`src/main/java/com/linkforge/link/service/ShortLinkService.java:164-175`）

没抢到锁的请求是「输家」。它不自己创建，而是轮询等赢家的结果：最多 10 次、每次睡 200 毫秒、合计约 2 秒。等到终值就原样返回赢家那条链。等到超时还不落，抛 503 让客户端稍后重试。注释把 503 的理由说透了：抢着再造一条，一个 Idempotency-Key 就会产出两条链，幂等契约彻底破产。

pending 锁只有 5 秒 TTL，这是自愈设计：赢家崩溃后锁自动消失，下一个同 key 请求能重新抢锁执行。5 秒对一次本地创建绰绰有余。

无幂等头的请求（:175）直接进 `doCreate`，幂等键传 null。

### 5.4 doCreate：全部校验在任何写之前

```java
private CreateShortLinkResponse doCreate(CreateShortLinkRequest request, AuthUser user, String idemKey) {
    String url = mergeUtm(request.getOriginalUrl().trim(), UtmMerger.canonical(request.getUtmSource(),
            request.getUtmMedium(), request.getUtmCampaign(), request.getUtmTerm(), request.getUtmContent()));
    LinkValidations.requireHttpUrl(url);
    rejectBlockedTarget(url);
    if (request.getExpireAt() != null && !request.getExpireAt().isAfter(LocalDateTime.now())) {
        throw new BizException("expireAt must be in the future");
    }
    // D-C3-7: validate before ANY write so a bad variant cannot leave a minted link.
    List<LinkVariant> variants = request.getVariants() == null
            ? List.of() : validateVariants(request.getVariants());
    String customCode = request.getCustomCode() == null ? null : request.getCustomCode().trim();
    if (customCode != null && !customCode.isEmpty()) {
        LinkValidations.requireValidCustomCode(customCode);
    }
    String passwordHash = encodeAccessPassword(request.getAccessPassword());
```
（`src/main/java/com/linkforge/link/service/ShortLinkService.java:203-218`）

七步校验，顺序是刻意安排的，从外到内：

| 步骤 | 代码位置 | 失败效果 |
|----|----|----|
| 1 UTM 合并 | :204-205 | 超过 2048 抛 400（mergeUtm :863-869） |
| 2 URL 结构校验 | :206 | 非 http(s)、含控制字符、非法 URI 抛 400 |
| 3 B9 域名门禁 | :207 | BLOCK 名单抛 400，且错误信息不回显域名（:798-807） |
| 4 expireAt 未来 | :208-210 | 过去时间抛 400 |
| 5 variants 校验 | :212-213 | 每条 URL 重复步骤 2 和 3，key 查重，最多 10 条（:823-843） |
| 6 customCode 合法性 | :214-217 | 4-16 位字符集或保留字抛 400 |
| 7 密码编码 | :218 | 长度不在 4-64 抛 400，通过则 BCrypt 加密（:810-819） |

为什么 UTM 合并排第一：门禁检查的必须是「合并后的最终 URL」。攻击者把恶意域名藏在 URL 里、把合法伪装参数放在 utm 里，如果先门禁后合并，门禁看到的就是假象。合并发生在前，门禁看到什么、存进库的就是什么。

为什么 variants 排在 customCode 和密码之前：注释 :211 写明「validate before ANY write so a bad variant cannot leave a minted link」。虽然事务也能回滚，校验前置让失败请求连事务都不用开。

### 5.5 写库：16 次重试与唯一索引兜底

```java
    // uk_code is the concurrency backstop. A taken custom code is a 409; random
    // codes regenerate on collision instead of failing the request.
    // C3-S1: the link row and its variants commit atomically — a variants failure
    // rolls the link back too, and the Bloom/cache/idem tail below only runs after
    // the commit. The DuplicateKeyException catch is safe inside the transaction:
    // InnoDB rolls back only the failed statement, so the retry loop can continue.
    transactionTemplate.executeWithoutResult(status -> {
        boolean inserted = false;
        if (customCode != null && !customCode.isEmpty()) {
            link.setCode(customCode);
            try {
                shortLinkMapper.insert(link);
                inserted = true;
            } catch (DuplicateKeyException ex) {
                throw new BizException(409, "code already exists");
            }
        } else {
            for (int attempt = 0; attempt < CODE_ATTEMPTS && !inserted; attempt++) {
                link.setCode(ShortCodes.randomCode(properties.getShortLink().getCodeLength()));
                try {
                    shortLinkMapper.insert(link);
                    inserted = true;
                } catch (DuplicateKeyException ex) {
                    log.info("short code collision on {}, retrying (attempt {})", link.getCode(), attempt + 1);
                }
            }
        }
        if (!inserted) {
            throw new BizException("failed to allocate short code");
        }
        if (!variants.isEmpty()) {
            insertVariants(link, variants);
        }
    });
```
（`src/main/java/com/linkforge/link/service/ShortLinkService.java:231-264`）

这段代码浓缩了三个并发设计决策。

**决策一：不做「先查后插」。** 查和插之间存在时间窗，两个并发请求都能查到「没人用」，然后双双插入。唯一索引 `uk_code`（6.1 课讲过）是数据库层面唯一的真相，插入撞索引就是并发冲突的信号。注释第一句「uk_code is the concurrency backstop」说的就是这个。

**决策二：两类冲突两种处理。** 用户自定义码撞了是业务冲突，409 让用户换一个。随机码撞了是物理巧合（7 位 62 字符空间里概率极低），换一个码重试即可，`CODE_ATTEMPTS = 16`（:86），16 次全撞抛 `BizException("failed to allocate short code")`（`BizException.java:10-12` 单参构造默认 code=400，`GlobalExceptionHandler` 按 code 映射，实际 HTTP 400，advanced/02 有时间线）。

**决策三：variants 同事务（C3-S1）。** `insertVariants`（:845-854）在同一个 lambda 里执行。variants 校验失败不会走到这里，但插入仍可能因数据库约束失败（比如超长 URL 落进列宽检查），此时整个事务回滚，主行也不会留下。注释还解释了一个容易疑惑的点：在事务内捕 `DuplicateKeyException` 是安全的，因为 InnoDB 只回滚失败的那条语句，重试循环可以继续。

`ShortCodes.randomCode`（`ShortCodes.java:15-21`）用 `SecureRandom` 从 62 字符字母表取码，区分大小写，与 V4 的 collation 决策闭环。

### 5.6 提交后动作链

```java
    codeBloom.put(link.getCode());
    cacheBus.publishBloomPut(link.getCode());
    clearAbsent(link.getCode());
    cacheUrl(link.getCode(), url);
    meterRegistry.counter("linkforge.links.created").increment();
    if (idemKey != null) {
        try {
            stringRedisTemplate.opsForValue().set(idemKey, link.getCode(),
                    Duration.ofSeconds(properties.getRateLimit().getIdempotencyTtlSeconds()));
        } catch (Exception ex) {
            // fail-open (D-C4-16): the link is already committed; the key keeps its
            // pending lease until TTL, so a same-key retry during the outage re-creates
            log.warn("idempotency settle failed for {}: {}", idemKey, ex.getMessage());
            meterRegistry.counter("linkforge.cache.failopen", "op", "idem_settle").increment();
        }
    }
    return new CreateShortLinkResponse(link.getId(), link.getCode(), url, "/r/" + link.getCode());
}
```
（`src/main/java/com/linkforge/link/service/ShortLinkService.java:266-283`）

事务提交后按固定顺序执行六个动作，每一个都服务于「下一个跳转请求立刻可见」。

1. `codeBloom.put`（:266）：新短码进入进程内 Bloom 过滤器，跳转路径的 Bloom 门立刻放行。
2. `cacheBus.publishBloomPut`（:267）：广播给其他实例，多实例部署下别的进程也要播种。
3. `clearAbsent`（:268）：万一这个码曾被误判进过负缓存，先删掉，否则跳转会被 absent 键 404 拦住。
4. `cacheUrl`（:269）：把 URL 写进 L1（Caffeine）和 L2（Redis）两级缓存，第一个跳转请求不用打库。TTL 带抖动。
5. meter 计数（:270）：`linkforge.links.created` 供监控面板使用。
6. 幂等终值（:271-281）：把短码写回幂等键，TTL 86400 秒即 24 小时（`application.yml:202`）。这是第二段式的终点。

六个动作的失败语义与第 9 节呼应，此处先记结论：前五个动作都不在事务里，任何一个失败都不会撤销已提交的链。

## 6. 技术原理补充

**幂等键两段式（本项目实现）**。是什么：Redis 键的值从 `"pending"` 过渡到短码终值的两阶段状态机。为什么需要：POST 不幂等，网络层重试、客户端双击、网关重发都会造成重复创建。不用会怎样：同一次点击产出两条链，报表与物料双双混乱。优点：实现只有一个键、读写各一次。缺点：依赖 Redis 可用性，且终值只保留 24 小时，一天后同 key 重发仍是新链。

**不对称 fail-open（本项目实现，D-C4-16）**。两处 Redis 异常的两处选择并不相同。第一处 acquire fail-open 放行并发双建，理由是「Redis 挂了不能让所有创建都挂」。第二处 settle fail-open 放弃写终值，键停在 pending 直到 5 秒 TTL 过期。后果不对称：

- acquire 失败最多造成同 key 双链（低频、低害）。
- settle 失败意味着赢家创建成功但键里没有终值，同 key 重试会再造一条。源码注释原文：

```java
// fail-open (D-C4-16): the link is already committed; the key keeps its
// pending lease until TTL, so a same-key retry during the outage re-creates
```
（`src/main/java/com/linkforge/link/service/ShortLinkService.java:276-278`）

「链接已提交」是两者的共同前提：任何情况下都不能为回滚一个已落库的链而抛错。两害相权，都选了可用性。

**为什么轮询而不是订阅**（推断，合理依据：代码形态与 TTL 设计）：等待方用 200ms 轮询读 Redis，而不是 pub/sub 通知。创建是低频管理操作，2 秒窗口内轮询 10 次成本可忽略；订阅则要多一条频道和一个监听器，复杂度不划算。

## 7. 可视化

1. [11-shortlink-create](../../diagrams/archify/html/11-shortlink-create.html)：本课专属时序图（并行任务产出，若链接暂未生效请稍后再看）。阅读顺序：先看泳道间的限流与幂等交互，再看事务框内的插入循环，最后看提交后的动作链。
2. [create-flow.md（Mermaid 备用）](../../diagrams/mermaid/create-flow.md)：同一流程的静态版。图中「SETNX pending 5s 超时 / 24h 终值」对应 5.2 与 5.6 两节，「后置动作集合」对应 5.6 的六步。
3. [02-function-map](../../diagrams/archify/html/02-function-map.html)：确认 create/doCreate 在整个函数地图上的位置，以及 CSV 导入如何汇入 doCreate。

## 8. 项目中的真实执行过程

用 Mermaid 备用图的节点口径走一遍完整请求：带 Idempotency-Key 的创建，两个并发请求同时到达。

1. 两个请求都通过 `create:user` 限流（各自计数）。
2. A 请求 SETNX `linkforge:rl:idem:42:abc-key` 成功，值 `pending`，TTL 5 秒。A 进入 doCreate。
3. B 请求 SETNX 返回 false，进入轮询循环。
4. A 完成校验，开事务，随机码 `aB3xK9m` 插入成功，variants 同事务插入，提交。
5. A 执行提交后动作链，Bloom、广播、清 absent、写缓存、计数、终值 set 替换 pending（TTL 重置为 24 小时）。
6. B 在第 1 或第 2 次轮询读到终值 `aB3xK9m`，回库确认归属后返回与 A 相同的四字段响应。
7. 全程数据库只有一条 `lf_short_link` 行，一次插入。

异常分支同样值得走：若 A 在步骤 4 校验失败抛 400，A 的 pending 键没有被 settle 覆盖，要等 5 秒 TTL 过期。这 5 秒内同 key 重试会拿到 503。这是幂等语义对失败请求的轻微副作用，属可接受代价（推断）。

## 9. 为什么这样设计

**校验全部前置**（注释 :211 与 :231-236 直接陈述）：数据库事务是昂贵资源，能不开就不开。所有结构性错误在开事务前 400 掉，事务里只剩「插入是否撞索引」这一种意外。

**唯一索引做并发兜底**（注释 :231-232）：分布式部署下应用层锁管不住多实例，数据库唯一索引才是最后的真相。Bloom、缓存都是加速层，不是正确性来源，这一课与模块 8 的缓存课反复呼应同一原则。

**variants 与主行同事务**（C3-S1，注释 :233-236）：A/B 链的主行与分流行是同一个业务事实，拆开提交会出现「有主行没分流」的中间态。跳转路径抽签时若 variant 缺失，行为会静默退化成单目标链，这类退化最难排查，事务边界直接消灭它。

**提交后动作不回滚**（推断，依据：动作链位于事务块外且全部 try-catch 或天然不抛）：Bloom 和缓存是可重建的加速层，宁可短暂缺失也不让创建失败。Bloom 缺失的代价是跳转 404 一次然后被 absent/DB 路径纠正，比创建失败便宜得多。

**503 而不是排队**（注释 :171-173）：等待方不代替赢家创建，也不能无限等。503 把决策权交回客户端，客户端带着幂等头重试，要么拿到重放，要么在锁过期后成为新赢家。

## 10. 本节总结

- create 管闸门与调度，doCreate 管校验与落库，CSV 导入复用后者。
- 校验七步从外到内：UTM 合并、URL 结构、B9 门禁、过期时间、variants、自定义码、密码。门禁检查的永远是合并后的最终 URL。
- 幂等两段式：SETNX pending 5 秒抢锁，赢家创建后写 24 小时终值，输家轮询 2 秒拿重放或拿 503。
- 两处 fail-open 不对称：acquire 放行并发，settle 放弃终值。共同前提是链接已提交，绝不为 Redis 可用性牺牲落库结果。
- 随机码 16 次重试、自定义码 409，全部依赖 `uk_code` 唯一索引，不做先查后插。
- 提交后六动作按序执行，失败不回滚创建。

## 11. 课后练习

1. 【概念理解】为什么 UTM 合并必须在 B9 门禁检查之前？
   答案：门禁要拦的是用户最终会被送去的目标。合并改变 URL（追加 utm 参数、可能超长），若先检查后合并，检查的就不是入库的那个 URL，藏匿在合并产物里的任何绕过都成为可能。`doCreate` 的顺序是 :204 合并、:206 结构校验、:207 门禁，入库值与被检值是同一个变量 `url`。
2. 【源码定位】找到「停机期同键重试会再造一条」的自认注释，并说出它描述的是哪一处 fail-open。
   答案：`ShortLinkService.java:276-278`，settle 失败分支。Redis 写终值失败时链接已提交，键停在 pending 直到 5 秒 TTL，之后同 key 请求重新抢锁再建一条。
3. 【执行过程】请求带 Idempotency-Key 且 Redis 整体宕机，两个并发请求同时到达，各自得到什么结果？
   答案：replay 读失败返回 null（:186-190，fail-open），SETNX 失败但 acquire fail-open 置 true（:156-160），两个请求都进入 doCreate，各自随机码不同，落库两条链。这是注释 :147-151 明示的取舍：停机期可用性优先于幂等严格性。
4. 【调用链】自定义码 `Sale2027` 已存在（deleted=1 的旧行），新建同码请求走到哪一行代码、客户端收到什么？
   答案：插入撞 `uk_code`（旧行仍占位），`DuplicateKeyException` 在 :244-246 被翻译为 `BizException(409, "code already exists")`。注意逻辑删行不释放码位是 6.1 课的结论。
5. 【设计分析】如果把提交后的 `clearAbsent` 挪进事务内，会引入什么问题？
   答案（要点）：Redis 写入在事务内执行，事务回滚时已删除的 absent 键不会恢复，一个曾确认不存在的码丢失负缓存标记，后续跳转多打几次库，正确性无碍但一致性模型变混。更本质的问题是事务内做外部副作用违反「提交前不留痕迹」原则，注释 :233-235 强调 Bloom/cache/idem 尾部只在提交后运行正是为此。属设计分析题，言之成理即可。

## 12. 与后续课程的联系

- 6.3 课的 CSV 导入逐行走 `doCreate`（`ShortLinkService.java:380`），本课的校验链在导入场景原样复用，这是「防漂移」设计：两条入口永远不会有两种行为。
- 6.3 课的 update 与本课共享 `rejectBlockedTarget`、`mergeUtm`、`validateVariants` 三个私有方法，校验语义一处定义。
- 模块 8 将展开本课一笔带过的缓存细节：`cacheUrl` 的 TTL 抖动、L1/L2 分工、`publishBloomPut` 广播的消费端。
- 模块 9 将展开限流器内部：滑动窗口 Lua 脚本与幂等键的 TTL 语义。
