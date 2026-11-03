# 9.1 滑动窗口限流：sliding_window.lua 逐行与 RedisRateLimiter

> 本课回答三个问题：为什么选滑动窗口日志而不是固定窗口计数？一段 18 行的 Lua 怎么在 Redis 里原子地完成「清窗、计数、决策、报价」？限流器自己坏了，系统怎么办？

## 1. 本节学习目标

学完本课，你应该能：

1. 画出固定窗口的边界突刺场景，解释滑动窗口日志如何消灭它。
2. 逐行解释 `sliding_window.lua`：Redis TIME 时钟、nonce 防碰撞、只有放行请求占名额、Retry-After 的推导。
3. 说出 `RedisRateLimiter.acquire` 的 fail-open 语义与对应的观测指标。
4. 解释 `RateLimitKeys` 的 `sw:` 命名空间防的是什么历史事故。
5. 背出五类入口的限额默认值与键名。

## 2. 前置知识

- Redis ZSET（有序集合）：成员唯一、每个成员带一个 double 分数（score），按分数排序。`ZREMRANGEBYSCORE` 按分数段删，`ZCARD` 数个数，`ZRANGE ... WITHSCORES` 取最小成员。
- Redis Lua 脚本（`EVAL`）：整段脚本在 Redis 单线程内原子执行，中间不会插入其他命令。KEYS 数组传键名，ARGV 数组传参数。
- HTTP 429 与 `Retry-After` 响应头：429 表示请求过多，`Retry-After` 告诉客户端几秒后再试。
- 模块 2.1 的 `GlobalExceptionHandler`：`RateLimitedException` 在 :20-25 被翻译成 429 加 `Retry-After` 头。

## 3. 本节涉及的源码

| 文件 | 行数 | 职责 | 阅读顺序 |
|----|----|----|----|
| `src/main/resources/redis/sliding_window.lua` | 18 | 主角：滑动窗口日志算法本体 | 1 |
| `src/main/java/com/linkforge/common/ratelimit/RedisRateLimiter.java` | 105 | Lua 的调用方：acquire、固定窗口、fail-open | 2 |
| `src/main/java/com/linkforge/common/ratelimit/RateLimitDecision.java` | 8 | 决策 record | 3 |
| `src/main/java/com/linkforge/common/ratelimit/RateLimitKeys.java` | 16 | 键命名空间与 WRONGTYPE 注释 | 4 |
| `src/main/java/com/linkforge/common/exception/RateLimitedException.java` | 18 | 429 异常载体 | 5 |
| `src/main/java/com/linkforge/common/exception/GlobalExceptionHandler.java` | 78 | Retry-After 出口 :20-25 | 6 |
| `src/main/java/com/linkforge/config/LinkForgeProperties.java` | 239 | RateLimit 配置 :220-238 | 7 |

## 4. 类的定位

- `common.ratelimit` 包只有三个文件，不依赖项目其他任何业务包：`RedisRateLimiter` 只认字符串键、数值上限、秒数窗口。它是纯通用组件，link、user、security 三个包都是它的客户。
- `RateLimitDecision` 是结果 record：`(allowed, retryAfterSeconds, count)`，javadoc :4-5 说明 retryAfterSeconds 仅在 allowed=false 时有意义。
- `RateLimitKeys` 是键拼装的唯一入口，:13-15 的 `sw()` 把所有滑动窗口键压进 `{prefix}sw:` 前缀下。
- `sliding_window.lua` 是资源文件，由 `RedisRateLimiter` 的静态块在类加载时读入（:29-32）。
- 包结构见 [../../diagrams/archify/html/03-package-map.html](../../diagrams/archify/html/03-package-map.html)。

## 5. 核心源码详解

### 5.1 先看问题：固定窗口的边界突刺

固定窗口计数器的实现是 `INCR` 加首个 `EXPIRE`：每分钟最多 100 次，计数器在分钟边界清零。它的漏洞在边界两侧：第 0 分 59 秒打 100 次、第 1 分 00 秒再打 100 次，两个窗口各自合法，但两秒内系统实际承受了 200 次。攻击者专挑边界打，限额形同虚设一倍。这叫边界突刺（boundary burst）。

滑动窗口日志（sliding window log）的思路：不按「自然分钟」记账，而是记录每一次放行的时间戳；判断「过去 60 秒内放行了几次」。窗口随时间滑动，不存在边界。

### 5.2 sliding_window.lua 逐行

全文 18 行，一段一段读。

```lua
-- Sliding window log (B6): KEYS[1] = ZSET key; ARGV = max, window_ms, nonce.
-- The clock is Redis TIME (µs) — the JVM never passes a timestamp, so clock
-- drift between instances cannot skew the window.
```
`sliding_window.lua:1-3`

头部注释定下两个契约：KEYS[1] 是 ZSET 键；ARGV 依次是 max、窗口毫秒数、nonce。时钟由 Redis 自己报（下一行的 `TIME` 命令），JVM 永远不传时间戳，多实例间 JVM 时钟漂移影响不到窗口（:2-3）。

```lua
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000000 + tonumber(t[2])
local max = tonumber(ARGV[1])
local win = tonumber(ARGV[2]) * 1000
```
`sliding_window.lua:4-7`

`TIME` 返回秒和当秒内的微秒两个字段，拼成微秒级 `now`（:5）。窗口从秒换算成微秒（:7）。

```lua
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - win)
local n = redis.call('ZCARD', KEYS[1])
```
`sliding_window.lua:8-9`

清窗：删掉所有分数早于 `now - win` 的成员，留下的正好是「窗口内」的放行记录。`ZCARD` 数出窗口内的请求数 `n`。

```lua
if n < max then
  redis.call('ZADD', KEYS[1], now, now .. '-' .. ARGV[3])
  redis.call('PEXPIRE', KEYS[1], ARGV[2])
  return {1, n + 1, 0}
end
```
`sliding_window.lua:10-14`

放行分支。三个细节。第一，member 是 `now .. '-' .. nonce`：ZSET 成员必须唯一，两个请求若落在同一微秒，光用 `now` 当成员会互相覆盖（ZADD 同名成员是更新不是新增），窗口凭空少记一笔；nonce（Java 侧的 `ThreadLocalRandom.nextLong()`，`RedisRateLimiter.java:47`）保证同微秒的两个请求也是两个成员。第二，`PEXPIRE` 把键的 TTL 刷成窗口时长：只要窗口内还有请求，键就活着；键的生死跟着窗口走，不需要独立清理任务。第三，返回 `{1, n+1, 0}`：放行、当前计数、无重试等待。

注意 `n < max` 才放行：被拒的请求不 ZADD。被拒请求不占名额，也就不能靠不断重试把自己的封锁期无限延长——这是 :17-19 注释「Only allowed requests enter the window」的含义。

```lua
local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
local retry_ms = math.ceil((tonumber(oldest[2]) + win - now) / 1000)
if retry_ms < 1 then retry_ms = 1 end
return {0, n, retry_ms}
```
`sliding_window.lua:15-18`

拒绝分支。窗口满了，第 301 个请求要等多久？等最早的那条放行记录滑出窗口：`oldest + win - now` 毫秒（:16），向上取整且至少 1 毫秒（:17）。这个报价是真实的：到点后 ZREMRANGEBYSCORE 删掉 oldest，名额自然空出。返回 `{0, n, retry_ms}`。

整段脚本原子执行：清窗、计数、写入（或不写）、报价之间不可能插入其他请求的判定，不需要分布式锁。

### 5.3 RedisRateLimiter.acquire：调用方视角

```java
public RateLimitDecision acquire(String key, int max, int windowSeconds) {
    if (max <= 0) {
        return new RateLimitDecision(false, windowSeconds, 0);
    }
    List<Long> result;
    try {
        result = stringRedisTemplate.execute(SLIDING_WINDOW_SCRIPT, List.of(key),
                String.valueOf(max), String.valueOf(windowSeconds * 1000L),
                String.valueOf(ThreadLocalRandom.current().nextLong()));
    } catch (Exception ex) {
        return failOpen(key, "error", ex);
    }
    if (result == null || result.size() < 3 || result.get(0) == null) {
        return failOpen(key, "null", null);
    }
    boolean allowed = result.get(0) == 1L;
    long retryMs = Math.max(1L, result.get(2));
    int retryAfter = (int) Math.min(windowSeconds, (retryMs + 999) / 1000);
    return new RateLimitDecision(allowed, retryAfter, result.get(1));
}
```
`RedisRateLimiter.java:39-58`

从上往下五段。第一段（:40-42）：`max <= 0` 是配置性关闭，直接拒绝，报价给整个窗口——配置说「不许请求」就一个都不放。第二段（:44-47）发起 EVAL：KEYS 一个，ARGV 三个，nonce 在这里生成。第三段（:48-50）：Redis 抛任何异常都走 fail-open，reason 记为 `error`。第四段（:51-53）：结果形状不对（脚本版本错位、集群迁移中的空响应）也 fail-open，reason 记为 `null`。第五段（:54-57）：翻译 Lua 的三元组，重试毫秒向上取整成秒，封顶为窗口秒数。

fail-open 的实现与观测：

```java
private RateLimitDecision failOpen(String key, String reason, Exception ex) {
    if (ex != null) {
        log.warn("rate limiter degraded open for {}: {}", key, ex.getMessage());
    }
    meterRegistry.counter("linkforge.ratelimit.failopen", "reason", reason).increment();
    return new RateLimitDecision(true, 0, 0);
}
```
`RedisRateLimiter.java:98-104`

Redis 挂了限流器就放所有人过（决策 true），但每次都打点 `linkforge.ratelimit.failopen{reason=...}`。类注释 :19-20 说的「silently disabled limiter stays visible」就是这个：限流失效是可用性事故不是安全事件，但必须在监控上看得见，否则一个静默的 fail-open 能掩盖真正的流量洪水。

### 5.4 tryAcquireFixedWindow：预览（9.2 详讲）

`RedisRateLimiter.java:71-96` 保留了固定窗口实现，javadoc :66-68 写明唯一用途：API Key 的日配额（Asia/Shanghai 自然日重置）。它有独立的失败自救逻辑（EXPIRE 失败尽力删键），9.2 课逐行讲。

### 5.5 RateLimitKeys：命名空间与 WRONGTYPE 事故

```java
/**
 * The sliding window (ZSET) namespace sits under {@code {prefix}sw:} so keys are never
 * shared with the fixed-window-era STRING counters: a ZSET command against a leftover
 * STRING key would raise WRONGTYPE and silently fail the limiter open (D-Bw345-10).
 */
public static String sw(String keyPrefix, String logicalKey) {
    return keyPrefix + "sw:" + logicalKey;
}
```
`RateLimitKeys.java:4-15`

javadoc 记录了一次真实事故（D-Bw345-10）：历史上固定窗口用 STRING 键存计数；换成 ZSET 后若新旧键名相同，ZADD 打在一个 STRING 键上会抛 WRONGTYPE，而 `acquire` 的 fail-open 会把它放行——限流静默失效。`sw:` 中间段让两代键永不重名。`keyPrefix` 默认 `linkforge:rl:`（`LinkForgeProperties.java:237`），可用环境变量整段换掉（多套环境共用一个 Redis 时用）。

### 5.6 五类入口限额表

| 入口 | 键（`linkforge:rl:sw:` 前缀下） | max / 窗口 | 调用点 | 配置项 |
|----|----|----|----|----|
| 注册 | `register:{ip}` | 5 / 600s | `AuthService.java:67-70` | registerMax :222-223 |
| 创建短链 | `create:user:{userId}` | 30 / 60s | `ShortLinkService.java:132-134` | createMax :225-226 |
| CSV 导入 | `import:user:{userId}` | 5 / 60s | `ShortLinkService.java:342-344` | importMax :228-229 |
| 跳转 | `redirect:ip:{ip}` | 300 / 60s | `ShortLinkService.java:604-606` | redirectMax :231-232 |
| 解锁（两级） | `unlock:code:{code}:ip:{ip}` 5/300s 与 `unlock:code:{code}` 50/300s | 见左 | `ShortLinkService.java:767-776` | LinkAccess :65-67 |

四点口径。跳转与注册按 IP 计，创建与导入按用户计；IP 取自 `ClientIps.resolve`（`ClientIps.java:11-14`），`trustXff=false` 时用 socket 地址——XFF 头可被客户端伪造，只有可信反向代理后面才敢开。导入是 D-C3-22 的专用桶：整个请求一个名额，100 行的导入不会吃掉 100 个 create 名额（:196-197 注释）。解锁的两级在 7.2 课讲过动机。第六个滑窗用户是 API Key 的分钟配额（`ApiKeyAuthFilter.java:118-119`），9.2 课讲。

## 6. 技术原理补充

- **滑动窗口日志（算法知识）**：优点是精确（真正的 N 秒 N 次）且能报出真实的 Retry-After。缺点是每请求一次 ZSET 写，内存与请求数成正比。替代方案：固定窗口（便宜但边界突刺）、令牌桶（允许突发、语义是速率不是计数）、滑动窗口计数（把窗口切成小格近似，省内存但不精确）。本项目跳转是防滥用不是计费，300/60 的精度用日志法换得起。
- **Redis TIME vs JVM 时钟（本项目实现）**：多实例部署时各 JVM 的墙钟可能差几十毫秒甚至秒级（NTP 未同步、虚机漂移）。若 JVM 传时间戳，实例 A 看到的窗口和实例 B 看到的窗口不一致，全局限额失真。Redis 是所有实例共享的单一时钟源（:2-3 注释）。
- **Lua 原子性（Redis 行为）**：单 Redis 实例内脚本执行期间不调度其他命令。不用 Lua 的替代是「读-判-写」三条命令，中间的竞态窗口会让并发请求同时看到 `n = max - 1` 而全部放行。Lua 把它压成一个原子单元，等价于免费锁。
- **fail-open 与 fail-closed（设计知识）**：限流器是保护性组件，它挂掉时不该把业务一起打死（fail-open）；认证器相反，它挂掉时宁可全拒（fail-closed，如 metrics scrape token 为空即 401）。判断标准：这个组件失效后，错误方向的代价是「少保护」还是「拒绝服务」。

## 7. 可视化

1. [../../diagrams/archify/html/06-redis-data-flow.html](../../diagrams/archify/html/06-redis-data-flow.html)（由并行任务产出）：缓存、限流、幂等三类键的数据流。本课对应限流分支：`acquire` 进出 ZSET 的读写与返回三元组。
2. [../../diagrams/archify/html/03-package-map.html](../../diagrams/archify/html/03-package-map.html)：`common.ratelimit` 三件套与五个调用包的位置关系。

## 8. 项目中的真实执行过程

一次被拒的跳转请求的全过程。设 `redirectMax=300`，窗口 60 秒，某 IP 的窗口里已有 300 条记录。

1. `GET /r/Ab3xK9m` 进 `resolveTarget`，:604-606 组键 `linkforge:rl:sw:redirect:ip:203.0.113.7`，调 `acquire(key, 300, 60)`。
2. EVAL 执行 `sliding_window.lua`：ZREMRANGEBYSCORE 清掉 60 秒前的成员（假设无），ZCARD 返回 300，`300 < 300` 不成立，走拒绝分支。
3. ZRANGE 取最老成员，假设分数是 41 秒前的时刻，`retry_ms = ceil((oldest + 60000 - now) / 1000)` ≈ 19000，返回 `{0, 300, 19000}`。
4. Java 侧 :54-57：allowed=false，retryAfter = min(60, ceil(19000/1000)) = 19。
5. :607-610 打点 `ratelimit.rejected{entry=redirect}`，抛 `RateLimitedException(19)`。
6. `GlobalExceptionHandler.java:20-25` 输出 429，头 `Retry-After: 19`，body 是统一壳 `{"code":429,...}`。
7. 19 秒后该 IP 重试：清窗删掉 oldest，`ZCARD` 变 299，`299 < 300` 成立，ZADD 放行。

对照一次 Redis 故障中的同名请求：:48-50 捕获连接异常，打点 `failopen{reason=error}`，返回 allowed=true，跳转链继续走 Bloom 与 DB。用户无感，监控上 failopen 曲线抬升。

## 9. 为什么这样设计

**拒绝不占名额。** 常见错误实现把每个到达的请求都记入窗口（包括被拒的）。后果：被限的客户端越重试、窗口越满，永远出不来；攻击者甚至能靠垃圾流量把无辜第三方的名额吃光。:10-14 只在放行分支 ZADD，名额只属于成功请求。

**Retry-After 报真实值。** 拒绝分支的报价来自 ZSET 里最老成员的分数（:15-16），不是拍脑袋给个常数。客户端等到点重试必然成功（名额已被 oldest 的滑出释放），减少无意义的重试风暴。Java 侧 :56 把毫秒取整成秒并封顶窗口长度，防止 Lua 侧极端值直接透传。

**fail-open 可观测。** 限流是可用性组件，Redis 故障时放行是对的（注释 :19-20）；但「对的降级」必须留痕。`failopen{reason}` 计数器把每次降级变成一条可见曲线，与 :636-640 缓存读的 fail-open、:579 点击记录的 fail-open 属于同一套纪律：降级一律打点。推断：`max <= 0` 直接拒绝（而非放行）的设计意图是「显式关闭某个入口」——运维把某入口 max 设 0 表达的是禁用而非无限，与 Redis 故障的 fail-open 方向相反，两种语义由不同代码路径承载。

## 10. 本节总结

- 滑动窗口日志用 ZSET 存放行时间戳，窗口随时间滑动，没有固定窗口的边界突刺。
- Lua 18 行完成清窗、计数、写入/报价，原子执行；时钟取 Redis TIME，nonce 保证同微秒成员唯一。
- 只有放行请求写 ZSET；拒绝报价 `oldest + win - now`，到点必然放行。
- Java 侧封装 `RateLimitDecision`，Redis 异常与坏结果一律 fail-open 并打点。
- `sw:` 命名空间隔离两代键型，防 WRONGTYPE 静默失效（D-Bw345-10）。
- 五类入口：注册 5/600s、创建 30/60s、导入 5/60s、跳转 300/60s、解锁 5+50/300s。

## 11. 课后练习

**练习 1（概念理解）**：固定窗口 100 次/分钟下，边界突刺最多能打出多少请求？滑动窗口日志 100 次/60 秒下，任意连续 60 秒内最多放行多少？

答案解析：固定窗口理论上 200 次（窗口尾 100 加下一窗口头 100，都合法）；若把「边界」理解成一个瞬间，突刺窗口可以更窄但总数 200 是经典答案。滑动窗口日志下任意连续 60 秒内放行数不超过 100，因为每次放行都留下时间戳，判定就是数窗口内的时间戳。这就是「精确」的含义：不存在合法的 2 倍瞬间。

**练习 2（源码定位）**：在 Lua 里找到防「同微秒两请求互相覆盖」的行，并解释如果删掉 nonce 会出什么问题。

答案解析：:11 的 `ZADD` member 是 `now .. '-' .. ARGV[3]`，nonce 由 :47 的 `ThreadLocalRandom.current().nextLong()` 提供。删掉后 member 就是 `now`：`TIME` 微秒精度下，同一 Redis 单线程内两个 EVAL 仍可能拿到相同微秒（TIME 返回的是调用时刻，两次快速调用完全可能同微秒）。第二个 ZADD 更新同名成员，计数少 1，窗口记录与真实放行数出现永久偏差，且偏差方向是「放更多」，危险。

**练习 3（执行过程）**：`max=2, win=60000ms`。t=0 两个请求放行，t=30000 第三个请求到达。写出 Lua 的返回值与最终 `RateLimitDecision` 的三个字段。

答案解析：清窗删不掉 t=0 的两条（它们的分数 0 大于 now-win=-30000，仍在窗口内），ZCARD=2，`2 < 2` 不成立。oldest 分数 0，`retry_ms = ceil((0 + 60000 - 30000)/1000) = 30000`，返回 `{0, 2, 30000}`。Java 侧 allowed=false，retryAfter = min(60, ceil(30000/1000)) = 30，count=2。`RateLimitDecision(false, 30, 2)`。t=60000 时 oldest 滑出，名额空出。

**练习 4（调用链）**：从 `unlock` 的码级限流到 HTTP 响应，列出 429 全链路的类与方法（含行号）。

答案解析：`ShortLinkService.unlock` :774-776 acquire（键 `unlock:code:{code}`）→ 拒绝时 :777-779 打点并抛 `RateLimitedException(perCode.retryAfterSeconds())` → 异常上抛出 service（unlock 无 catch）→ `GlobalExceptionHandler.handleRateLimited` :20-25 设 429 与 `Retry-After` 头。另注意 filter 层（ApiKeyAuthFilter）抛不出这条路，它手写响应，9.2 课讲第二个出口。

**练习 5（设计分析）**：把 `acquire` 的 fail-open 改成 fail-closed（Redis 异常时返回拒绝），对跳转系统会怎样？对照 7.1 的哪些设计说明这是错的？

答案解析：Redis 故障期间全部跳转 429，短链服务整体不可用。7.1 的判定链里每一层缓存读都选了 fail-open（L2 读 :636-640、空值缓存探测、Bloom 禁用时放行），Bloom 的 mightContain 禁用/未就绪一律 true，整条链的哲学是「辅助设施故障不得阻断主路径」。限流器与其他辅助层同级，也该 fail-open；区别只在认证（fail-closed 合理）。代价是 Redis 故障期间失去限流保护，靠 `failopen` 指标报警补位。

## 12. 与后续课程的联系

- 9.2 课讲 `tryAcquireFixedWindow` 的逐行与 API Key 双闸，以及全项目限流键、幂等键的 Redis 键族总表。
- 7.1 课的判定链第 1 步就是本课的 `redirect:ip` 桶，7.2 课的 unlock 两级限流是本课的两个桶；模块 14 的可观测性用 `ratelimit.rejected{entry}` 与 `ratelimit.failopen{reason}` 两个指标系列做告警原料。
