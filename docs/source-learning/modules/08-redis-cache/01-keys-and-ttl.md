# 8.1 Redis 使用面与键空间：StringRedisTemplate、键频道清单与 TTL 抖动

> 本课回答一个问题：LinkForge 往 Redis 里放了什么、放了多久、谁来读写？为什么这个项目坚持「Redis 只省 URL 字段，不替 DB 挡流量」，而不把整行数据、更不把 L1 缓存放进 Redis？

## 1. 本节学习目标

学完本课，你应该能：

1. 说出 `RedisConfig` 提供的两个 bean，解释 `StringRedisTemplate` 和泛型 `RedisTemplate` 的差别。
2. 背出本项目全部 Redis 键与频道：正缓存、空值缓存、限流滑窗、幂等键、API Key 配额，以及 `bloom:put` 与 `l1:invalidate` 两个频道，每个都能指到写入点行号。
3. 解释 TTL 抖动公式 `base + uniform[0, jitter]` 为什么「只向上」，并指出实现的 7 行代码。
4. 说清「写穿」和「1 秒延迟双删」各自解决什么问题（机制细节在 8.2 展开）。
5. 复述「无分布式 L1、无 RedisBloom」的设计立场，并给出 README 与代码注释两处出处。

## 2. 前置知识

- Redis 三种用法（模块 00 与 01-project-overview 有全景）：STRING 键值对（可设 TTL）、ZSET 有序集合、pub/sub 频道。频道消息不落地，发出去就没了，没人订阅就丢失。
- TTL（time to live，存活时间）：键附带一个倒计时，到期 Redis 自动删除。`SET key value EX 3600` 表示 3600 秒后删。
- 缓存三大经典问题，本模块各治一个：穿透（查永不存在的数据，缓存永远帮不上忙，8.3 的 Bloom 与空值缓存负责）、击穿（热点键过期瞬间大量请求直冲 DB，8.4 的 singleflight 负责）、雪崩（大批键同时过期，DB 瞬间过载，本课的 TTL 抖动负责）。
- Spring 的依赖注入与 `@Bean`（模块 1.2）：容器启动时按方法创建对象并交给容器管理。
- 模块 6.3 的写路径：`update`、`disable`、`delete` 都在 `ShortLinkService` 里，本课只看它们的缓存尾段。

## 3. 本节涉及的源码

| 文件 | 行数 | 本课关注点 | 阅读顺序 |
|----|----|----|----|
| `src/main/java/com/linkforge/config/RedisConfig.java` | 24 | 两个 bean：模板 + 监听容器 | 1 |
| `src/main/resources/application.yml` | 266 | short-link/bloom/singleflight/l1/cache-bus 段 :111-145 | 2 |
| `src/main/java/com/linkforge/config/LinkForgeProperties.java` | 239 | ShortLink :103-112、L1 :160-167、cacheBusEnabled :23-24 | 3 |
| `src/main/java/com/linkforge/link/service/ShortLinkService.java` | 1025 | 键常量 :83-87、jitteredTtlSeconds :918-924、cacheUrl :966-977 | 4 |
| `src/main/java/com/linkforge/link/support/CacheBus.java` | 85 | 两个频道常量 :24-28、publish :39-68 | 5 |
| `src/main/java/com/linkforge/common/ratelimit/RateLimitKeys.java` | 16 | 滑窗键命名空间 :13-15 | 6 |
| `src/main/java/com/linkforge/common/ratelimit/RedisRateLimiter.java` | 105 | 键作为参数进入 Lua :45-47 | 7 |

阅读顺序的理由：先看容器给了什么工具，再看配置定了什么参数，然后进 `ShortLinkService` 看键怎么拼、TTL 怎么算，最后看频道和限流键这两个「旁支」。限流算法本身是 9.1 课的内容，本课只关心它的键长什么样。

## 4. 类的定位

- `RedisConfig` 是 `config` 包里最短的配置类（24 行），只提供两个 bean：`StringRedisTemplate` 和 `RedisMessageListenerContainer`。全项目对 Redis 的读写都从第一个 bean 走，全部订阅都挂在第二个 bean 上。
- `ShortLinkService` 是键空间的实际主人：正缓存与空值缓存的前缀常量（:83-84）、TTL 抖动函数（:918-924）、读写方法（:926-999）都在它身上。
- `CacheBus` 是唯一定义 pub/sub 频道名的类。频道常量、载荷格式 `<code>|<origin>`、解析函数集中在这一个文件（:24-28、:74-80），别处只引用不复制。
- `RateLimitKeys` 用 16 行守住限流键的命名空间纪律，它的 javadoc 记录了一次 WRONGTYPE 事故（D-Bw345-10），是「键命名空间不是小事」的实证。
- 包全景见 [../../diagrams/archify/html/03-package-map.html](../../diagrams/archify/html/03-package-map.html)。

## 5. 核心源码详解

### 5.1 RedisConfig：两个 bean

```java
@Bean
public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
    return new StringRedisTemplate(factory);
}

/** Shared listener container; C2 cache-bus subscribers register on it before it starts. */
@Bean
public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory factory) {
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(factory);
    return container;
}
```
`RedisConfig.java:12-23`

`StringRedisTemplate` 是 Spring Data Redis 提供的现成子类，键和值的序列化器都固定为字符串。泛型 `RedisTemplate<Object,Object>` 默认用 JDK 序列化，存进去的值带序列化头，`redis-cli` 里看到的是乱码。本项目缓存值就是 URL 字符串、限流脚本参数也是字符串，`StringRedisTemplate` 恰好够用，还换来一个好处：直接用 `redis-cli` 就能核对线上数据。监听容器（第二个 bean）是 pub/sub 的接收端，`CodeBloomFilter` 和 `UrlLocalCache` 在自己的 `@PostConstruct` 里往它注册监听器，8.2 与 8.3 课分别展开。

### 5.2 键与频道清单表

下表是本项目 Redis 键空间的全量。前缀 `linkforge:rl:` 来自 `linkforge.rate-limit.key-prefix`（默认值在 LinkForgeProperties 的 RateLimit 段），表中写作 `{rl}`。批次 3 的两个新键（idem 信封、idem-import）与 create 的键同前缀不同子空间，刻意分开命名——导入与创建的结算格式不同，混用一键必然互相误读。

| 键或频道 | 类型 | 写入点 | 读取点 | TTL |
|----|----|----|----|----|
| `linkforge:code:{code}` | STRING，值为 URL 字符串 | `cacheUrl`（ShortLinkService） | 跳转热路径 | 3600 + uniform[0,300] 秒 |
| `linkforge:absent:{code}` | STRING，哨兵值 `"1"` | `markAbsent` | `absentKnown` | 60 + uniform[0,30] 秒 |
| `{rl}sw:{logical}` | ZSET，滑动窗口日志 | Lua `ZADD`（9.1 课逐行；键里含 login/refresh/logout 等新入口） | 同一脚本内 `ZCARD` | 窗口秒数，`PEXPIRE` 保活 |
| `{rl}apikey:sw:min:{keyId}` | ZSET，API Key 分钟配额 | ApiKeyAuthFilter 的双闸 | 同左 | 60 秒 |
| `{rl}apikey:day:{keyId}:{yyyyMMdd}` | STRING，INCR 计数 | ApiKeyAuthFilter 的双闸 | 同左 | 86400 秒 |
| `{rl}idem:{userId}:{key}` | STRING，`"pending"` → 信封 `v3:{指纹}:{code}` | 抢占 SET NX，落定 Lua CAS | 重放读取（先于限流） | pending 5 秒；落定值 86400 秒 |
| `{rl}idem-import:{userId}:{key}` | STRING，在途 `pending:{owner}:{fileSha}` → 结算 JSON | ImportIdempotencyLease 抢占/结算（Lua CAS） | 导入重放读取 | 在途 120 秒；结算 86400 秒 |
| 频道 `linkforge:bloom:put` | pub/sub | `CacheBus.publishBloomPut` | `CodeBloomFilter` 订阅 | 无（消息不落地） |
| 频道 `linkforge:l1:invalidate` | pub/sub | `CacheBus.publishL1Invalidate` | `UrlLocalCache` 订阅 | 无 |

三个键族对应三类读防护，正好是 README「五层读路径」（限流、Bloom、空值缓存、L1/L2、行读）里落在 Redis 上的部分。Bloom 与 L1 是进程内存结构，不在 Redis 里，这正是本课最后要讲的设计立场。

### 5.3 键常量与 TTL 抖动实现

键前缀和延迟双删的 1 秒常量集中在 `ShortLinkService` 顶部：

```java
private static final String CACHE_KEY_PREFIX = "linkforge:code:";
private static final String ABSENT_KEY_PREFIX = "linkforge:absent:";
private static final String ABSENT_SENTINEL = "1";
private static final int CODE_ATTEMPTS = 16;
private static final long STALE_WRITEBACK_CLEAR_MS = 1000;
```
`ShortLinkService.java:83-87`

TTL 不是写死的常数，而是「基准值加随机抖动」：

```java
/** B-wave1 D-Bw1-5: TTL = base + uniform [0, jitter] (upward only); jitter<=0 degenerates to base. */
static long jitteredTtlSeconds(long baseSeconds, long jitterSeconds) {
    if (jitterSeconds <= 0) {
        return baseSeconds;
    }
    return baseSeconds + ThreadLocalRandom.current().nextLong(jitterSeconds + 1);
}
```
`ShortLinkService.java:918-924`

三个语言点。其一，`ThreadLocalRandom.current()` 是每个线程一个的随机数生成器，多线程下比共享的 `Random` 少一次竞争。其二，`nextLong(jitterSeconds + 1)` 返回 `[0, jitterSeconds]` 的均匀整数，加 1 是因为上界开区间，想要「jitter 也可能取满」就得加 1。其三，方法冠 `static` 且包私有，939-952 与 966-977 两处调用都在本类，单测也能直接调用它验证边界。

两个调用点决定了两种缓存的 TTL。正缓存 `cacheUrl` 用 `cacheTtlSeconds + cacheTtlJitterSeconds`（:967-968，默认 3600+300，`application.yml:113-114`）；空值缓存 `markAbsent` 用 `absentTtlSeconds + absentTtlJitterSeconds`（:943-944，默认 60+30，`application.yml:117-118`）。幂等键的 5 秒与 86400 秒（:155、:271-274）没有抖动，它们是单键而非同批键，没有「一批同时过期」的问题，这是诚实的省略而不是遗漏。

### 5.4 写穿与延迟双删的语义（预览）

`cacheUrl`（:966-977）的名字叫写穿（write-through）：写路径在 DB 提交之后，顺手把新值同时写进 L1（`urlLocalCache.put`，:969）和 L2（`SET ... EX ttl`，:971）。于是「下一次读」大概率命中缓存，不必等自然回填。

但只有写穿不够。设想并发：读者 A 在 DB 提交前读了旧行，提交后 A 把旧 URL 回填进缓存，覆盖了写者刚写入的新值。这就是「延迟双删」要治的窗口：写者在写穿之后再安排一次延迟 1 秒的删除（`scheduleStaleClear`，:991-999，常量 1000 毫秒在 :87），把 A 可能迟到写入的脏值再删一次。1 秒的量级依据写在注释里：读者从读行到回填，间隔是一个查询延迟（毫秒级），1 秒足以覆盖。完整机制、广播失效与多实例窗口是 8.2 课的主菜，本课记住分工：写穿负责「让下次读是新值」，双删负责「清掉可能迟到的旧值」。

### 5.5 幂等键两段式（预览）

创建接口支持 `Idempotency-Key` 头。抢占阶段 `setIfAbsent(idemKey, "pending", 5s)`，抢到的实例执行创建；落定阶段经 Lua CAS 把值从 `"pending"` 换成信封 `v3:{请求指纹}:{短码}` 并续 TTL 86400 秒。同键重试的请求先读这个键（且先于限流），验指纹后返回旧结果或 409。导入有它自己的键与租约（`idem-import:`，在途值带 owner 与文件哈希，TTL 120 秒），全套语义在 6.2/6.3 与 9.2 课细讲，本课只需要知道这两个键的形状：**值都是「在途标记 → 结算值」的两段式，导入的在途标记自带所有权与内容指纹，创建的结算值自带请求指纹**。

## 6. 技术原理补充

- **StringRedisTemplate（框架行为）**：Spring Data Redis 对「全是字符串的键值」提供的专用模板。不用它而用默认 `RedisTemplate` 会得到 JDK 序列化的二进制值，可读性与跨语言兼容都差。本项目所有 Redis 调用共用这一个实例。
- **TTL 抖动防雪崩（通用技术）**：批量写入的键若用同一 TTL，会在同一时刻集体失效，请求洪峰直冲 DB，称为雪崩。抖动把每键的过期时刻在 `[base, base+jitter]` 内摊开。本项目选「只向上」而非「上下浮动」：向下抖会把某些键的 TTL 压到比预期更短，保护强度不可控；向上抖只增加缓存时长，最坏情况是多留一会儿旧值，而旧值本来就有 1 秒双删与广播失效兜底（8.2）。
- **键命名空间（工程实践）**：`RateLimitKeys.java:13-15` 把滑窗键全部收进 `{prefix}sw:` 子空间，javadoc 写明原因：固定窗口时代的遗留 STRING 键若与 ZSET 键同名，ZSET 命令会抛 WRONGTYPE，限流器静默 fail-open（`RedisRateLimiter.java:48-53`）。一次真实事故换来的规则：改数据结构必须换命名空间。
- **无分布式 L1 的设计立场（本项目实现）**：README 明说「进程内结构保留 Guava Bloom / Caffeine L1（没有 RedisBloom/Redis Stack，没有分布式 L1），跨实例对齐走 Redis pub/sub 广播」（`README.md:393`）。`UrlLocalCache` 的启动日志也自称 "process-local, not shared across instances"（`UrlLocalCache.java:44-46`）。理由在 8.2 展开：L1 的价值是省一次 Redis 往返，放进 Redis 就退化成了 L2 的复制品，白付一次网络开销。

## 7. 可视化

按顺序看三张图：

1. [../../diagrams/archify/html/06-redis-data-flow.html](../../diagrams/archify/html/06-redis-data-flow.html)：Redis 键空间与读写方的数据流图。节点对应：`linkforge:code:*` 的读写方是 `ShortLinkService.cacheUrl`/`resolveTarget`，`linkforge:absent:*` 的是 `markAbsent`/`absentKnown`，两个频道的是 `CacheBus` 与两个订阅者。
2. [../../diagrams/archify/html/13-cache-hit-miss-sequence.html](../../diagrams/archify/html/13-cache-hit-miss-sequence.html)：一次跳转里 L1、L2、空值缓存、DB 的命中时序，本课的键表是它的静态对照物。
3. [../../diagrams/archify/html/03-package-map.html](../../diagrams/archify/html/03-package-map.html)（已通过）：确认 `RedisConfig` 在 `config` 包、`CacheBus` 在 `link.support` 包的位置。

Mermaid 备用图 [../../diagrams/mermaid/redirect-decision-chain.md](../../diagrams/mermaid/redirect-decision-chain.md) 画了跳转判定链，键的读写点都能对上。

## 8. 项目中的真实执行过程

场景：用户带着 `Idempotency-Key: abc` 创建短链成功，随后一个浏览器访问它。看 Redis 里发生了什么。

1. 限流放行。创建入口先过 `create:user` 滑窗键，Lua 脚本 `ZADD` 一个成员并 `PEXPIRE`（:132-138 调用，算法 9.1 课），用户配额 30/60 秒。
2. 抢占幂等键。`setIfAbsent("linkforge:rl:idem:1:abc", "pending", 5s)` 成功，Redis 里出现第一个键。
3. DB 提交后立即写穿。`cacheUrl(code, url)` 把 URL 写进 L1 并 `SET linkforge:code:Ab3xK9m <url> EX [3600..3900]`；`clearAbsent` 顺手 `DEL linkforge:absent:Ab3xK9m`（通常本来就不存在）；`publishBloomPut` 向频道发一条 `Ab3xK9m|i-xxx`。
4. 幂等键落定。Lua CAS 把 `linkforge:rl:idem:1:abc` 从 `pending` 换成 `v3:{指纹}:Ab3xK9m EX 86400`，两段式闭合。
5. 浏览器请求 `/r/Ab3xK9m`。命中限流键（ZSET 查询），Bloom 是进程内的不碰 Redis，`absentKnown` 查空值缓存未命中（:623-626），L1 命中刚写穿的 URL（:631-632），L2 这次根本没被查询（:633 只在 L1 未命中时走），DB 行照读（8.4 讲为什么）。
6. 一小时后这条链的 `linkforge:code:Ab3xK9m` 在 `[3600..3900]` 秒之间某个时刻过期消失；下一次跳转走 L2 miss、DB 读、再回填，TTL 重新抖动，过期时刻又错开了。

## 9. 为什么这样设计

**Redis 只省 URL 字段，不替 DB 挡流量。** README 的口播口径（`README.md:391`）说得直白：为了强一致，跳转每个请求都要读一次 DB 行判禁用与过期，Redis 省的是 URL 字段读取、不存在码的 DB miss、同码并发的重复行读。把整行缓存进 Redis 看似更快，代价是禁用、改密、改目标都要等缓存失效才生效，而「下一个请求立即生效」是模块 7 已确认的契约。

**每类键的 TTL 都有各自的量级依据。** 正缓存 3600 秒起：URL 是低频变更数据，长 TTL 换高命中率，抖动 300 秒摊平批量创建的过期尖峰；空值缓存 60 秒起：它挡的是恶意扫描不存在的码，值本身「不存在」这件事很稳定，但为了不给重建留长尾，TTL 明显更短；幂等键 86400 秒：对齐「24 小时内重试同键都返回同一结果」的承诺；pending 只给 5 秒，因为它是锁不是数据，持有者几分钟内必然落定或失败。推断：这组数字是「数据变更频率越高、语义越接近锁，TTL 越短」的统一尺度，配置全部可被环境变量覆盖（`application.yml:113-118`）。

## 10. 本节总结

- `RedisConfig` 只给两个 bean：`StringRedisTemplate`（全部读写）与监听容器（全部订阅）。
- 键空间五族：`linkforge:code:{code}` 正缓存、`linkforge:absent:{code}` 空值缓存、`{rl}sw:*` 与 `{rl}apikey:*` 限流配额、`{rl}idem:*` 创建幂等键、`{rl}idem-import:*` 导入幂等键；两个频道 `linkforge:bloom:put` 与 `linkforge:l1:invalidate`。
- TTL 抖动 `base + uniform[0, jitter]` 只向上（:918-924），治的是同批过期雪崩；幂等键无抖动是合理的单键省略。
- 写穿让下次读是新值，1 秒延迟双删清迟到的旧值；机制细节在 8.2。
- 设计立场：没有分布式 L1、没有 RedisBloom，进程内结构加 pub/sub 对齐，README :391-404 是权威出处。

## 11. 课后练习

**练习 1（概念理解）**：为什么空值缓存的 TTL（60+30 秒）比正缓存（3600+300 秒）短一个数量级？

答案解析：两者兜的险不同。正缓存 miss 的代价只是多读一次 DB，TTL 长纯粹为命中率；空值缓存错的代价是把「码已存在」误判成 404，靠 TTL 自愈。它服务的场景是扫描器反复打不存在的码（`BloomDisabledAtStartupIT.java:78-97` 的 phantom 用例），只需要短窗内挡住重复 DB miss。TTL 短意味着「误判窗口」短，创建短链时还有 `clearAbsent`（:954-964）主动清除，双保险下 60 秒足够安全。

**练习 2（源码定位）**：`jitteredTtlSeconds` 里 `nextLong(jitterSeconds + 1)` 的 `+1` 去掉会怎样？找两处调用点说明影响。

答案解析：`nextLong(bound)` 返回 `[0, bound)`，去掉 `+1` 后抖动上限变成 `jitter-1`，`jitter=300` 时实际范围是 `[3600, 3899]` 而不是注释承诺的 `[3600, 3900]`。功能无害但语义漂移：注释 :918 写明 uniform [0, jitter]。调用点在 `markAbsent` :943-944 与 `cacheUrl` :967-968。这题练的是「读随机数 API 必须确认开闭区间」。

**练习 3（执行过程）**：按 5.2 的清单表，说出浏览器访问一条已存在短链时，Redis 上最多发生几次网络往返，各是什么。

答案解析：最多三次。第一次是限流滑窗键的 Lua 执行（`resolveTarget` :604-610）；第二次是空值缓存的 `GET linkforge:absent:{code}`（:631 前的 :623-626）；第三次是 L1 未命中时的 `GET linkforge:code:{code}`（:635）。若 L1 命中则第三次省掉，只剩两次。Bloom 查询与行读都不碰 Redis（前者在进程内，后者走 MySQL）。注意「行读」永远发生，这正是第 9 节的立场。

**练习 4（设计分析）**：把正缓存 TTL 从 3600+300 改成 60+0（不抖动），哪些现象会变差？这算雪崩吗？

答案解析：两点变差。其一，命中率大跌：每个码每小时过期 60 次，DB 行读没有减少（本来每请求都读），但 Redis 往返次数暴增，省 URL 字段的目标落空。其二，同批键同时过期：批量导入 100 行（`ShortLinkService.java:375-385` 逐行 `doCreate`）时 100 个键在同一秒写入同一 TTL，一小时后同一秒集体失效。是否构成「雪崩」取决于并发量级，Demo 量级下 DB 扛得住，但形态已经对了，抖动的意义就在抹平这个形态。这题也解释了为什么导入路径复用 `doCreate` 的缓存尾段而不用批量写。

## 12. 与后续课程的联系

- 8.2 课展开 L1/L2 读写路径、写穿、双删、广播失效与多实例一致窗口，本课 5.4 的预览在那里变成完整时序。
- 8.3 课讲空值缓存的完整边界与 Bloom，8.4 课讲 singleflight；9.1 课逐行拆滑窗 Lua，9.2 课讲幂等两段式的 fail-open 不对称。
- 15.3 课的 `fault-drill.sh` 有 redis 停服段：跳转仍然 302，因为本课列出的每次 Redis 交互都有 fail-open 分支（`RedisRateLimiter.java:48-53`、`ShortLinkService.java:636-640`、:932-936、:948-951、:960-963、:972-976）。
