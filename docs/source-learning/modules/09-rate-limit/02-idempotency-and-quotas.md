# 9.2 幂等键与配额：创建幂等回顾、API Key 双闸、键族总表

> 本课回答三个问题：同一个 Idempotency-Key 的并发创建为什么只落一条链？API Key 的分钟限额和日配额为什么一个用滑动窗口、一个用固定窗口？全项目 Redis 上的限流与幂等键长什么样？

## 1. 本节学习目标

学完本课，你应该能：

1. 用六句话复述创建幂等两段式，并说清本课与 6.2 课的分工。
2. 逐行解释 API Key 的双闸：分钟滑动窗口加 Asia/Shanghai 日固定窗。
3. 推演「日窗 EXPIRE 失败且不删键」导致的永久 429，解释尽力删键为什么能止损。
4. 背出限流键与幂等键的完整键族表：键名、类型、TTL、写入方。
5. 区分 `Retry-After` 的三种计算口径与两个输出出口。

## 2. 前置知识

- 6.2 课的创建幂等：`SETNX` 抢锁、轮询等结果、终值落 Redis。
- 9.1 课的滑动窗口日志与 fail-open 纪律。
- Redis `INCR`：原子自增，键不存在时从 0 开始。固定窗口计数器的全部基础。
- 时区与 `LocalDate.now(zone)`：同一个瞬间在不同时区是不同日期。`BASIC_ISO_DATE` 格式形如 `20261103`。
- 过滤器与控制器异常处理的边界：`OncePerRequestFilter` 抛出的异常不经过 `@RestControllerAdvice`（模块 3.2 讲过滤器链位置），filter 必须自己写响应。

## 3. 本节涉及的源码

| 文件 | 行数 | 职责 | 阅读顺序 |
|----|----|----|----|
| `src/main/java/com/linkforge/security/ApiKeyAuthFilter.java` | 162 | 主角：双闸 withinQuota :112-137、Retry-After 出口 :139-142 | 1 |
| `src/main/java/com/linkforge/common/ratelimit/RedisRateLimiter.java` | 105 | tryAcquireFixedWindow :71-96 | 2 |
| `src/main/java/com/linkforge/link/service/ShortLinkService.java` | 1025 | 幂等段：create :141-174、replay :182-201、settle :271-281 | 3 |
| `src/main/java/com/linkforge/common/ratelimit/RateLimitKeys.java` | 16 | 键命名空间 | 4 |
| `src/main/java/com/linkforge/common/exception/GlobalExceptionHandler.java` | 78 | Retry-After 出口一 :20-25 | 5 |
| `src/main/resources/application.yml` | 266 | api-key :146-152、rate-limit :191-205 | 6 |

## 4. 类的定位

- `ApiKeyAuthFilter` 在 `security` 包，是挂进 Spring Security 过滤器链的 `OncePerRequestFilter`。它的完整认证流程（X-API-Key 头解析、吊销检查、UserContext 装配）在模块 12 课讲过，本课只切出其中的配额闸 `withinQuota`。
- `ShortLinkService` 的幂等段属于 link 域；本课从「键与配额」的视角回看它。
- 两个 Retry-After 出口分属两层：controller 层异常走 `GlobalExceptionHandler`，filter 层的 `writeQuotaExceeded` 手写响应。
- Redis 键全景见 [../../diagrams/archify/html/06-redis-data-flow.html](../../diagrams/archify/html/06-redis-data-flow.html)；`lf_api_key` 表结构见 [../../diagrams/archify/html/05-database-erd.html](../../diagrams/archify/html/05-database-erd.html)。

## 5. 核心源码详解

### 5.1 创建与导入幂等：两段式回顾与分工声明

6.2/6.3 课已经逐行讲过机制，这里只做回顾，细节以那两课为准：

1. 客户端带 `Idempotency-Key` 头，service 拼键 `linkforge:rl:idem:{userId}:{key}`（键名含 `rl:` 前缀，见 5.4 键族表）。
2. 先查结算 `replayIdempotentCreate`：键里存着信封 `v3:{指纹}:{短码}` 且指纹与本次请求一致、行归属本人，直接重放响应——**且发生在限流之前**（批次 3b），重放不烧 create 桶；同 key 异体当场 409 `IDEMPOTENCY_KEY_REUSED`（指纹含 UTM 五项、tags、变体与口令 HMAC，见 6.2 的 M4 段）。
3. 没有结算就抢 5 秒租约：`setIfAbsent(idemKey, "pending", 5s)`。抢到的执行 `doCreate`。
4. 没抢到的轮询 10 次、每次 200ms 等结算，等不到按 503 让客户端重试，绝不自己再建一条。
5. `doCreate` 提交后经 Lua CAS 把键结算为信封，TTL 换成 86400 秒。
6. 两处 fail-open 不对称：抢锁失败按抢到处理（宁可并发双建）；settle 失败只打点，键停在 pending，停机期同键重试真的会再造一条——源码注释自己承认这一点。
7. 导入是平行的一套：键 `idem-import:{userId}:{key}`，在途值 `pending:{owner}:{文件SHA-256}`（TTL 120 秒），结算值是整份报告 JSON；同 key 同文件在途 409 `IDEMPOTENCY_IN_PROGRESS`、异文件 409 `IDEMPOTENCY_KEY_REUSED`；释放与结算同为 Lua CAS。崩溃恢复不在覆盖范围内（M5 范围声明，6.3）。

与 6.2/6.3 的分工：那两课回答「请求进门后经过哪些闸门、幂等如何调度」，重心在业务流程；本课回答「这些机制在 Redis 上占了哪些键、失败时键处于什么状态、和限流键怎么共存」，重心在键空间与故障面。

### 5.2 API Key 双闸：分钟滑窗加日固定窗

```java
private boolean withinQuota(ApiKey key, HttpServletResponse response) throws IOException {
    LinkForgeProperties.ApiKey cfg = properties.getApiKey();
    String rlPrefix = properties.getRateLimit().getKeyPrefix() + "apikey:";
    int perMinute = key.getQuotaPerMinute() != null ? key.getQuotaPerMinute() : cfg.getDefaultQuotaPerMinute();
    int perDay = key.getQuotaPerDay() != null ? key.getQuotaPerDay() : cfg.getDefaultQuotaPerDay();

    RateLimitDecision minute = rateLimiter.acquire(
            RateLimitKeys.sw(rlPrefix, "min:" + key.getId()), perMinute, 60);
    if (!minute.allowed()) {
        meterRegistry.counter("linkforge.apikey.quota.rejected", "window", "minute").increment();
        writeQuotaExceeded(response, minute.retryAfterSeconds());
        return false;
    }
    ZoneId zone = ZoneId.of("Asia/Shanghai");
    LocalDate today = LocalDate.now(zone);
    String day = today.format(DateTimeFormatter.BASIC_ISO_DATE);
    if (!rateLimiter.tryAcquireFixedWindow(rlPrefix + "day:" + key.getId() + ":" + day, perDay, 86_400)) {
        meterRegistry.counter("linkforge.apikey.quota.rejected", "window", "day").increment();
        long secondsToMidnight = java.time.Duration.between(
                LocalDateTime.now(zone),
                today.plusDays(1).atStartOfDay(zone)).getSeconds();
        writeQuotaExceeded(response, Math.max(1, (int) secondsToMidnight));
        return false;
    }
    return true;
}
```
`ApiKeyAuthFilter.java:112-137`

第一闸（:118-124）：分钟滑动窗口，键 `linkforge:rl:apikey:sw:min:{keyId}`，额度取键行上的 `quotaPerMinute`，为 null 时退到默认 120（`application.yml:148`）。滑动窗口保证任意连续 60 秒内不超额度，挡突发。

第二闸（:125-135）：日固定窗口。键里编入了日期 `day:{keyId}:{20261103}`，TTL 86400 秒。日窗要的语义是「每个自然日一份配额，午夜清零」，这不是「任意 24 小时」而是「日历日」——所以用固定窗口是对的，滑动窗口反而给不出「今天还剩多少」的账本语义。时区硬编码 Asia/Shanghai（:125-126）：重置时刻是上海午夜，运营与客户对齐同一个日历（推断：与项目 `jackson` 时区设置一致，见 `application.yml` dev 段）。被日窗拒绝时 `Retry-After` 报的是距下一个上海午夜的秒数（:130-133）。

方法注释 :110-111 把两闸顺序写明：先分钟后日。先判分钟的意义：超分钟额的请求不必消耗日窗名额，429 的粒度也更贴近真实原因（meter 按 window 分桶 :121/:129）。

### 5.3 日窗的自救：EXPIRE 失败尽力删键

`tryAcquireFixedWindow` 的关键段：

```java
if (count == 1L) {
    try {
        Boolean armed = stringRedisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(armed)) {
            stringRedisTemplate.delete(key);
        }
    } catch (Exception ex) {
        try {
            stringRedisTemplate.delete(key);
        } catch (Exception ignore) {
            log.warn("rate window {} left unarmed: {}", key, ex.getMessage());
        }
    }
}
return count <= max;
```
`RedisRateLimiter.java:81-95`

INCR 返回 1 说明这是窗口内第一个请求，给键武装 TTL 的责任落在它身上。推演不删键的后果：EXPIRE 因网络抖动没执行成功，计数键永不过期，这个键的 INCR 只增不减，从某天起每个请求都 `count > max`，429 到天荒地老。这就是「永久 429」事故。删键的止损逻辑：把计数清零，下一个请求从 count=1 重新尝试武装 TTL；最坏损失是窗口内几个请求少算了配额，换来的是键不会变成永久的墓碑。javadoc :68-69 写明方向：「a key can never lock callers out forever」。连续两层异常都失败（Redis 已经连 DEL 都执行不了）只剩日志；这种程度的故障下后续 INCR 也会失败，:75-77 直接 fail-open 放行，unarmed 键暂时没有拒绝能力。

### 5.4 键族总表

限流与幂等相关的全部 Redis 键。前缀 `keyPrefix` 默认 `linkforge:rl:`（`LinkForgeProperties.java:237`）：

| 键 | 类型 | TTL | 写入方（类:行） |
|----|----|----|----|
| `{prefix}sw:register:{ip}` | ZSET | 窗口 600s（PEXPIRE 保活） | `AuthService.java:67-69` |
| `{prefix}sw:create:user:{userId}` | ZSET | 窗口 60s | `ShortLinkService.java:132-134` |
| `{prefix}sw:import:user:{userId}` | ZSET | 窗口 60s | `ShortLinkService.java:342-344` |
| `{prefix}sw:redirect:ip:{ip}` | ZSET | 窗口 60s | `ShortLinkService.java:604-606` |
| `{prefix}sw:unlock:code:{code}:ip:{ip}` | ZSET | 300s | `ShortLinkService.java:767-769` |
| `{prefix}sw:unlock:code:{code}` | ZSET | 300s | `ShortLinkService.java:774-776` |
| `{prefix}apikey:sw:min:{keyId}` | ZSET | 60s | `ApiKeyAuthFilter.java:118-119` |
| `{prefix}apikey:day:{keyId}:{yyyyMMdd}` | STRING | 86400s（首个 INCR 武装） | `ApiKeyAuthFilter.java:128` |
| `{prefix}idem:{userId}:{key}` | STRING | pending 5s → 信封 `v3:{指纹}:{code}` 86400s | ShortLinkService.create/doCreate |
| `{prefix}idem-import:{userId}:{key}` | STRING | 在途 `pending:{owner}:{fileSha}` 120s → 结算 JSON 86400s | ImportIdempotencyLease + ShortLinkService.importCsv |
| `linkforge:apikey:touch:{keyId}` | STRING | 60s | `ApiKeyAuthFilter.java:153-155` |

三条结构纪律。其一，所有 ZSET 键都带 `sw:` 段，与固定窗口时代的 STRING 键永不重名（9.1 的 WRONGTYPE 事故，`RateLimitKeys.java:4-6`）。日窗键没有 `sw:` 段也不与 ZSET 键冲突：它的 `day:` 段与 `sw:` 段互斥。其二，幂等键挂在 `rl:` 前缀下而不是单独的顶级命名（:142 直接拼 `rl.getKeyPrefix()`），它与限流共享同一套环境隔离前缀，换环境时一起搬走。其三，`touch:` 键（键名写死于 :45 的常量）不属于限流：它是「每键每分钟至多一次 last_used UPDATE」的节流阀（:150-161），放进这张表是因为排查 API Key 问题时会同时遇到这三族键。

缓存侧的 `linkforge:code:{code}` 与 `linkforge:absent:{code}` 不在本表（它们是 8.x 的键族），但共同构成完整键空间，边界在 8.1 课的键清单。

### 5.5 Retry-After：三种口径与两个出口

三种计算口径：

1. **滑动窗口报价**：Lua 用最老成员算 `oldest + win - now` 毫秒（`sliding_window.lua:15-18`），Java 侧取整成秒并封顶窗口长度（`RedisRateLimiter.java:55-56`）。特征：真实、到点必成功。
2. **配置性拒绝报价**：`max <= 0` 时直接给整个窗口秒数（:40-42）。特征：语义是「这个入口关了」，报价等于「下个窗口再看」。
3. **日窗报价**：距下一个 Asia/Shanghai 午夜的秒数（`ApiKeyAuthFilter.java:130-133`）。特征：与配额重置时刻对齐，一天最多报一次大数。

两个输出出口。出口一：controller/service 抛 `RateLimitedException`，`GlobalExceptionHandler.java:20-25` 统一设 429 与 `Retry-After` 头，body 走 `ApiResponse.fail`。出口二：filter 里异常到不了 advice（`ApiKeyAuthFilter` 类注释 :36-37 说明 filter 异常绕过它），`writeQuotaExceeded` :139-142 手动 `setHeader("Retry-After", ...)` 加手写 JSON（:144-148）。两条出口的 body 形状一致是刻意的，注释原话「All bodies mirror the GlobalExceptionHandler shapes」。

## 6. 技术原理补充

- **滑动窗口 vs 固定窗口的适用分界（算法知识）**：滑动窗口答「任意 N 秒内最多几次」，适合防突发；固定窗口答「每个周期给多少份」，适合配额账本。本项目分钟闸用前者、日配额用后者，各取所需。全用滑动窗口行不行：日账本要「今天用了多少、午夜重置」，滑动窗口没有「周期」概念，报不出「还剩多少」。
- **时区敏感的配额（工程知识）**：`LocalDate.now()` 不带参数会用 JVM 默认时区，部署在 UTC 容器里重置时刻就漂到早上 8 点。显式 `ZoneId.of("Asia/Shanghai")` 把语义钉死（:125-126）。测试侧另有 `WebhookRetryClockIT` 用 fork 指定时区的先例（pom failsafe 双执行），印证「时区是显式契约」在这个项目里的地位。
- **幂等键与限流键的生命周期差异（本项目实现）**：限流键由 Lua 里的 `PEXPIRE` 保活，键生死跟随窗口；幂等键有两段生命（pending 租约、结算值 24 小时），创建的租约 5 秒、导入的 120 秒——租约时长跟着单次执行的 P99 走。生命周期跟着业务语义走：租约只要盖住一次执行的时长，结算要盖住客户端的合理重试期。
- **fail-open 的分层默认（设计知识）**：本课出现了第三种失败处理——「尽力修复后继续」（删键重武装）。与 9.1 的 fail-open、6.2 的两处不对称 fail-open 并列，本项目对 Redis 故障的响应库是三种：放行（限流、缓存读）、按成功处理（幂等抢锁）、尽力自愈（日窗武装失败删键）。选择依据都是「该组件失效时的错误代价」。

## 7. 可视化

1. [../../diagrams/archify/html/06-redis-data-flow.html](../../diagrams/archify/html/06-redis-data-flow.html)：限流与幂等两类键的读写流。本课对应：日窗键的 INCR/EXPIRE 分支、幂等键的 pending→结算两段。
2. [../../diagrams/archify/html/02-function-map.html](../../diagrams/archify/html/02-function-map.html)：开放能力域的 API Key 叶子，`withinQuota` 挂在它的认证链上。

## 8. 项目中的真实执行过程

场景一：同一 Idempotency-Key 的并发双击。

1. 两个相同请求几乎同时到 `create`，都过了 create 限流（30/60s），键 `idem:{userId}:k1` 都查不到结算。
2. 请求 A 的 `setIfAbsent` 成功拿到 pending 租约，进 `doCreate`。请求 B 的 setIfAbsent 返回 false。
3. B 进入轮询：最多 10 次 200ms。A 在第 400ms 提交事务并把短码写回键（:273-274，TTL 86400）。
4. B 的下一轮 `replayIdempotentCreate` 读到短码，校验行归属（:196-198），重放响应。DB 里只有一条链。
5. 变体剧情：若 A 的进程在 settle 前崩溃，键停在 pending。B 轮询 2 秒后按 503 退出，客户端稍后重试，此时 pending 已过 5 秒租约，新请求重新抢锁再建。这就是源码注释承认的窗口（导入侧的同形窗口更长：租约 120 秒，且 M5 明确不覆盖崩溃恢复）。

场景二：API Key 撞日配额。

1. 当日第 10001 个请求到达（日额默认 10000）：分钟闸放行，日窗 INCR 返回 10001，`10001 <= 10000` 不成立。
2. meter `apikey.quota.rejected{window=day}`，`secondsToMidnight` 算出到午夜还有 43200 秒。
3. 响应 429、`Retry-After: 43200`、手写 JSON body（:139-142）。
4. 上海午夜后首个请求：INCR 新键 `day:{id}:{次日日期}` 返回 1，武装 TTL 86400，放行。旧键 24 小时后由 Redis 清掉。

场景三：EXPIRE 掉链子。某键首个请求 INCR 成功返回 1，EXPIRE 命令因瞬时超时抛异常：:87-93 的 catch 里再试 DEL，DEL 成功，键清零；下一请求从 count=1 重新开始并再次尝试武装。最坏情况 DEL 也失败，只剩日志 `rate window ... left unarmed`。等 Redis 恢复后，这个孤儿键上的 INCR 全部成功且没有 TTL，计数只增不减，越过 max 后该键开始永久 429。两道兜底把伤害封住：日期编在键名里（:128），午夜一过新请求用新键名重新武装；运维删掉孤儿键立即可用。javadoc :69 的承诺就是靠这两道兜底兑现的。

## 9. 为什么这样设计

**双闸而不是单闸。** 只留日窗：客户可以在 9:00 用完 10000 次然后被 429 到午夜，下游服务跟着雪崩，Retry-After 报 40000+ 秒形同拒绝服务。只留分钟闸：客户可以匀速打满全天 17 万次，日配额的商务承诺失效。分钟闸保稳定、日闸保合同，两层各答一个问题。

**EXPIRE 与 INCR 分两条命令而不是 Lua。** 9.1 的滑窗必须 Lua（判定依赖计数结果），日窗不需要：INCR 的返回值足以判定，EXPIRE 是附带动作。代价就是 :81-93 这段武装失败处理。为什么不干脆 INCR+EXPIRE+Lua 三合一：能做，但为一个「失败即删键重试」的简单动作引入第二个脚本，维护成本大于收益（推断：作者权衡后的选择，:66-69 的 javadoc 把固定窗口定位成「仅日配额用」的专用件而非通用机制）。

**幂等键挂在 `rl:` 前缀下。** 表面看幂等不是限流，该有独立前缀。实际收益：整套「防滥用与防重复」的键共享一个环境隔离前缀，多环境共用 Redis 时一条 `keyPrefix` 环境变量整体切换（:237），不会出现「限流键换了环境、幂等键还指着生产」的半切换状态。

**filter 自绘响应。** Spring MVC 的异常解析器工作在 DispatcherServlet 之后，过滤器在它之前。filter 里抛 `RateLimitedException` 会变成容器默认的 500 页面，客户看到的信息反而更差。手写 40 行内的 JSON 响应是 filter 层的标准姿势，代价是 body 形状要与 advice 手工保持一致（注释 :36-37 记录了这个约定）。

## 10. 本节总结

- 幂等两段式：查结算（验指纹、先于限流）→ 抢租约（create 5 秒 / import 120 秒）→ 轮询或执行 → 结算 TTL 24 小时；fail-open 不对称，settle 失败的窗口被注释明示。
- API Key 双闸：分钟滑动窗口（任意 60 秒不超）加日固定窗（上海自然日重置），先分钟后日。
- 日窗武装失败尽力删键，防「计数永不过期的永久 429」；日期编入键名把最坏持续压到一个自然日。
- 键族 10 键：7 个 ZSET 带 `sw:` 段，3 个 STRING（day/idem/touch）各守其名；幂等键与限流键共享 `rl:` 前缀做环境隔离。
- Retry-After 三口径（滑窗真实报价、配置拒绝整窗、日窗距午夜）两出口（advice 统一翻译、filter 手写镜像）。

## 11. 课后练习

**练习 1（概念理解）**：为什么分钟配额用滑动窗口、日配额用固定窗口？把日配额也改成滑动窗口会失去什么？

答案解析：分钟闸的语义是「任意连续 60 秒内不超过 N 次」，防突发，滑动窗口精确匹配。日配额的语义是「每个自然日一份份额，午夜重置」，是账本。滑动窗口没有周期边界，给不出「今天还剩几次」与「几时重置」；`Retry-After` 也只能报 3600 秒封顶的窗口值而不是「距午夜」。:66-68 的 javadoc 把固定窗口的适用场景钉在「calendar reset」。

**练习 2（源码定位）**：找到防止「永久 429」的三层代码，按触发顺序列出。

答案解析：第一层，`RedisRateLimiter.java:83-86`：EXPIRE 返回非 TRUE（键在命令间隙被删等）直接 DEL。第二层，:87-93：EXPIRE 抛异常时 catch 里再 DEL，DEL 失败仅记日志 `rate window ... left unarmed`。第三层，`ApiKeyAuthFilter.java:128`：日期编入键名，unarmed 键最多影响到上海午夜，次日请求换新键重新武装。javadoc :68-69 的承诺「never lock callers out forever」由这三层共同兑现。

**练习 3（执行过程）**：客户端带 Idempotency-Key 发创建请求，service 拿到租约后、`doCreate` 执行中，同键第三个请求到达。写出它的完整结局（含耗时上限）。

答案解析：它先查结算读不到（键还是 pending），setIfAbsent 失败（租约被占），进入轮询：最多 10 次、每次 sleep 200ms，合计约 2 秒，每轮走完整的重放判定（验指纹）。期间首个请求若 settle 成功则重放短码（异体则 409）；轮询耗尽仍无结算则抛 503「idempotent create still in progress, retry later」。注意 2 秒的轮询窗口小于 5 秒租约，本请求不会自己抢到租约重建；只有客户端收到 503 后在租约过期（或首个请求崩溃）之后重试，新请求才可能抢锁再建——fail-open 只在 Redis 故障时生效。

**练习 4（调用链）**：API Key 请求被日窗拒绝后，HTTP 响应是谁写的？如果是 controller 里的 `RateLimitedException`，路径有何不同？

答案解析：日窗拒绝发生在 `ApiKeyAuthFilter.withinQuota`（:128-135），filter 在 DispatcherServlet 之前，异常到不了 `GlobalExceptionHandler`，所以由同文件 `writeQuotaExceeded`（:139-142）直接 `response.setHeader("Retry-After", ...)` 加 :144-148 的手写 JSON，状态 429。controller 路径：service 抛 `RateLimitedException` → `GlobalExceptionHandler.handleRateLimited`（:20-25）→ 同样的 429 加头，body 是 `ApiResponse.fail(...)` 的统一壳。两者 body 形状按类注释 :36-37 刻意一致。

**练习 5（设计分析）**：把创建幂等键的 pending 租约从 5 秒改成 60 秒，各改善什么、恶化什么？为什么导入的租约却是 120 秒？

答案解析：改善：慢创建（大批量变体、数据库抖动）期间，第二个请求更可能在租约内等到结算，轮询 2 秒内重放成功率上升，503 减少。恶化：租约是防并发的唯一凭证（fail-open 只在 Redis 故障时生效）；首个请求若在 5 秒后崩溃，60 秒租约让同键重试白白等 60 秒（或者轮询方直接 503，客户端要等到租约过期才能重建）。租约时长的本质是「单次执行的 P99 耗时」与「崩溃后的重建延迟」之间的取舍：创建是单行 insert 加至多十行 variants，5 秒宽裕；导入要逐行跑最多 100 次 doCreate，P99 高一个量级，所以 120 秒——同一个参数，两处按各自执行时长定。

## 12. 与后续课程的联系

- 模块 12 课讲 `ApiKeyAuthFilter` 的另一半：X-API-Key 认证、吊销语义、ROLE_API_KEY 的授权边界；本课的 `withinQuota` 只是它 :91 处调用的一个闸。
- 8.1 课的键清单会把 `linkforge:code:`、`linkforge:absent:` 两个缓存键族与本课的限流键族合并成完整键空间视图。
- 9.1 课的滑动窗口是本课分钟闸的引擎；7.1 判定链第 1 步、7.2 解锁两级限流是同一引擎的另两个客户。
- 模块 14 的告警视角：`apikey.quota.rejected{window}` 与 `ratelimit.failopen{reason}` 的组合能区分「客户真超了」与「Redis 挂了限流失守」两种完全不同的事故。
