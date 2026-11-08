# 8.2 L1/L2 两级缓存：写穿、1 秒延迟双删与广播失效

> 本课回答一个问题：一条短链的 URL 被更新后，三台实例的缓存怎么在约 1 秒内全部对齐？写穿、延迟双删、pub/sub 广播三件事各管哪一段？

## 1. 本节学习目标

学完本课，你应该能：

1. 说出 `UrlLocalCache` 的存储内容（只有 URL 字符串）、容量与 TTL 配置，以及「命中不跳过行读」这个约束的出处。
2. 走通跳转热路径的 L1、L2 读取顺序，报出 `cache.l2{result}` 与 `cache.failopen{op=l2_get}` 两个指标的触发行。
3. 解释 `cacheUrl` 写穿与 `evictCache` 纯失效的分工：为什么 update 用前者、disable/delete 用后者。
4. 复述 1 秒延迟双删的完整时序，说清它清的是什么脏值、为什么 1 秒够。
5. 解释 `CacheBus` 广播的 origin 跳过自己（防 self-harm）与「延迟遍绝不重复广播」两条纪律，并给出多实例最终一致窗口的量级（约 1 秒，上限为 TTL）。

## 2. 前置知识

- 8.1 课的键表：`linkforge:code:{code}` 是 L2，`linkforge:absent:{code}` 本课不涉及。
- Caffeine：JVM 进程内的缓存库，本项目的 L1。功能上等价于一个带容量上限和过期策略的 `ConcurrentHashMap`，API 是 `getIfPresent`/`put`/`invalidate`。
- pub/sub 的投递语义：Redis 频道是 fire-and-forget，发布时不存储、不确认，订阅者断线期间的消息直接丢失。这决定了广播只能当「加速对齐」用，不能当「正确性依赖」用。
- 模块 6.3 的 `update`/`disable`/`delete` 方法签名与本课 :450-558 的尾段。
- 8.4 课的 singleflight：本课读到 :649 的行读时只需要知道「并发同码读会合并」，机制下下课讲。

## 3. 本节涉及的源码

| 文件 | 行数 | 本课关注点 | 阅读顺序 |
|----|----|----|----|
| `src/main/java/com/linkforge/link/support/UrlLocalCache.java` | 96 | 主角：构造 :34-47、订阅 :50-58、失效 :61-74、读写 :76-95 | 1 |
| `src/main/java/com/linkforge/link/support/CacheBus.java` | 85 | 发布 :39-68、载荷解析 :74-84 | 2 |
| `src/main/java/com/linkforge/link/service/ShortLinkService.java` | 1503 | 读路径 resolveTarget、update/setStatus/delete 的收尾段、cacheUrl、evictCache、scheduleStaleClear | 3 |
| `src/main/resources/application.yml` | 266 | l1 段 :140-145 | 4 |
| `src/main/java/com/linkforge/config/RedisConfig.java` | 24 | 监听容器 :17-23 | 5 |

先读 `UrlLocalCache` 与 `CacheBus` 两个小类（合计 181 行），建立「L1 是什么、广播是什么」的直觉；再带着它们进 `ShortLinkService` 的读路径与三个写路径，看五段代码怎么串成一套一致性方案。

## 4. 类的定位

- `UrlLocalCache` 是 B-wave1 B8 引入的进程内 L1，挂在 Redis L2 之前。它的 javadoc（:17-24）用三句话钉死了本课全部主题：只缓存 URL（与 Redis 值同形状）、命中绝不跳过 singleflight 行读（D-Bw1-6）、跨实例陈旧窗口是本 TTL（默认 10 秒）再由广播收紧。
- `CacheBus` 是 C2 wave A/C 引入的 pub/sub 总线，两个频道各服务一个订阅者：`bloom:put` 给 8.3 的 `CodeBloomFilter`，`l1:invalidate` 给本课的 `UrlLocalCache`。载荷格式 `<code>|<origin>` 的解析只有一个实现（:74-80）。
- `ShortLinkService` 是所有写穿与失效动作的发起方。三个写方法（update/setStatus/delete）的尾段形状一致：先 `singleFlight.forget`，再做 L1/L2 动作，最后广播一次。
- 类关系全景见 [../../diagrams/archify/html/02-function-map.html](../../diagrams/archify/html/02-function-map.html)。

## 5. 核心源码详解

### 5.1 UrlLocalCache：Caffeine 构造与三个公开方法

```java
public UrlLocalCache(LinkForgeProperties properties, MeterRegistry meterRegistry,
                     RedisMessageListenerContainer container) {
    this.properties = properties;
    this.meterRegistry = meterRegistry;
    this.container = container;
    LinkForgeProperties.L1 cfg = properties.getL1();
    this.cache = Caffeine.newBuilder()
            .maximumSize(cfg.getMaximumSize())
            .expireAfterWrite(Duration.ofSeconds(cfg.getTtlSeconds()))
            .build();
    log.info("url L1 cache built (enabled={}, maximumSize={}, ttlSeconds={}); "
                    + "process-local, not shared across instances",
            cfg.isEnabled(), cfg.getMaximumSize(), cfg.getTtlSeconds());
}
```
`UrlLocalCache.java:34-47`

两个策略一个容量：`maximumSize 10000`（条目数上限，超了按 Caffeine 的窗口淘汰策略赶最不常用的出去）、`expireAfterWrite 10 秒`（写入后 10 秒必过期）。`expireAfterWrite` 而非 `expireAfterAccess` 是刻意的：L1 的角色是「10 秒兜底窗口」（javadoc :21-22），访问续期会把窗口拉长，陈旧 URL 可能一直活在高频访问下。

公开方法三个，全部带开关守卫：

```java
public String getIfPresent(String code) {
    if (!properties.getL1().isEnabled()) {
        return null;
    }
    String url = cache.getIfPresent(code);
    meterRegistry.counter(url != null ? "linkforge.l1.hit" : "linkforge.l1.miss").increment();
    return url;
}

public void put(String code, String url) {
    if (properties.getL1().isEnabled()) {
        cache.put(code, url);
    }
}

public void invalidate(String code) {
    if (properties.getL1().isEnabled()) {
        cache.invalidate(code);
    }
}
```
`UrlLocalCache.java:76-95`

`getIfPresent` 顺手打点 `l1.hit` 与 `l1.miss`（:81），这是看板「L1（本进程）命中率」的数据源。开关关掉时读恒 miss、写直接跳过，行为与没装这个组件完全一致；README 口径 :403 说它「命中不跳过 singleflight/DB 行读，禁用/过期语义零变化」。

### 5.2 跳转热路径的 L1、L2 读取

`resolveTarget` 的缓存段（`ShortLinkService.java:628-644`）：

```java
String cacheKey = CACHE_KEY_PREFIX + code;
// B8 L1 (URL strings only, never the row) in front of the Redis L2; a hit
// saves a Redis round-trip but the row below is still read per request.
String cached = urlLocalCache.getIfPresent(code);
boolean servedFromLocal = cached != null && !cached.isBlank();
if (!servedFromLocal) {
    try {
        cached = stringRedisTemplate.opsForValue().get(cacheKey);
    } catch (Exception ex) {
        // fail-open (D-C4-15): an outage degrades to the singleflight row read below, never a 500
        log.warn("l2 read failed for {}: {}", code, ex.getMessage());
        meterRegistry.counter("linkforge.cache.failopen", "op", "l2_get").increment();
    }
    // B1: the Redis L2 round-trip had no metric — dashboards need the hit rate
    meterRegistry.counter("linkforge.cache.l2", "result",
            cached != null && !cached.isBlank() ? "hit" : "miss").increment();
}
```
`ShortLinkService.java:628-644`

三个细节。第一，L2 查询只在 L1 未命中时发生（:633），L1 命中省的就是这次 Redis 往返。第二，L2 抛异常走 fail-open：置 `cached` 为 null 继续往下，后续逻辑退化到 singleflight 行读（:649），请求绝不 500；代价被记进 `cache.failopen{op=l2_get}`（:639）。第三，`cache.l2{result=hit|miss}`（:642-643）只在真的走了 L2 时打点，L1 命中的请求不计入 L2 命中率，两个面板的数字因此不重叠。

读到的 URL 在 :668 被选为跳转目标候选（行 URL 与缓存值都通过可渲染校验后），随后是两段回填：

```java
if (!servedFromLocal && cached != null && !cached.isBlank()) {
    urlLocalCache.put(code, cached);
}
if (cached == null || cached.isBlank()) {
    cacheUrl(code, originalUrl);
}
```
`ShortLinkService.java:675-680`

第一段是 Bw1-N1 的设计：只有「L2 命中、且该值已经通过行门禁与渲染校验」的 URL 才回填 L1（注释 :672-674 写明），热点码在 10 秒 L1 过期后靠这次回填重新免掉 Redis 往返。第二段是 L2 未命中时的自然回写，`originalUrl` 此时取自行（:668 的三元：缓存有值用缓存，没值用行）。回填发生在全部行校验之后，脏行永远进不了缓存。

### 5.3 cacheUrl：写穿的双写

```java
private void cacheUrl(String code, String url) {
    long ttl = jitteredTtlSeconds(properties.getShortLink().getCacheTtlSeconds(),
            properties.getShortLink().getCacheTtlJitterSeconds());
    urlLocalCache.put(code, url);
    try {
        stringRedisTemplate.opsForValue().set(CACHE_KEY_PREFIX + code, url, ttl, TimeUnit.SECONDS);
    } catch (Exception ex) {
        // fail-open (D-C4-15): L1 already holds the value; Redis warm-up waits for the next read
        log.warn("l2 write failed for {}: {}", code, ex.getMessage());
        meterRegistry.counter("linkforge.cache.failopen", "op", "l2_set").increment();
    }
}
```
`ShortLinkService.java:966-977`

写穿的含义在这一行：`urlLocalCache.put`（:969）先落 L1，`SET ... EX ttl`（:971）再落 L2，一次调用两级都热。L2 写失败不抛：L1 已经持有新值，本实例下次读不缺；Redis 上值的缺席让下次读走 L2 miss 加行读回填，正确性无损，代价记进 `cache.failopen{op=l2_set}`（:975）。调用点有三类：创建后的首次预热（doCreate :269）、update 的新 URL 写穿（:515）、跳转读路径的 L2 回填（:679）。

### 5.4 evictCache 与 scheduleStaleClear：双删的后两段

```java
private void evictCache(String code) {
    urlLocalCache.invalidate(code);
    try {
        stringRedisTemplate.delete(CACHE_KEY_PREFIX + code);
    } catch (Exception ex) {
        // fail-open (D-C4-15): a stale L2 entry ages out at its TTL — the missed delete
        // must not fail the update/disable/delete the caller is committing
        log.warn("l2 evict failed for {}: {}", code, ex.getMessage());
        meterRegistry.counter("linkforge.cache.failopen", "op", "l2_delete").increment();
    }
}

private void scheduleStaleClear(String code) {
    evictor.schedule(() -> {
        try {
            evictCache(code);
        } catch (Exception ex) {
            log.warn("delayed cache clear failed for {}: {}", code, ex.getMessage());
        }
    }, STALE_WRITEBACK_CLEAR_MS, TimeUnit.MILLISECONDS);
}
```
`ShortLinkService.java:979-999`

`evictCache` 与 `cacheUrl` 是一对反操作：L1 `invalidate` 加 L2 `DEL`，L2 删失败同样 fail-open（op=l2_delete，:987），陈旧条目等 TTL 自然过期。`scheduleStaleClear` 把 `evictCache` 推迟 1000 毫秒（常量 :87）再执行一次，这就是「延迟双删」的第二删。专用线程池在 :118-122：单线程、守护线程、命名 `cache-evictor`，`@PreDestroy` 收尾（:124-127）。

它清的是什么脏值？时间线展开：读者 A 在写者提交 DB 前读了旧行；写者提交、写穿新 URL、删除旧缓存；A 此刻才走到回填（:678-680 或 :675），把旧 URL 写回缓存，覆盖新值。第一删发生在 A 回填之前，管不住它；第二删在 1 秒后，管得住。1 秒的量级依据在 :645-648 的注释：A 从读行到回填的间隔是「一个查询延迟」（毫秒级），跟车者看到的行最多比到达时刻旧一个查询延迟，1 秒足够盖住这个窗口。

### 5.5 三个写路径的尾段：写穿与纯失效的分工

`update`（:450-521）的尾段是本课的标本：

```java
singleFlight.forget(code, priorFlight);
// Write-through now, then a delayed delete clears any stale write-back a racing
// reader may have cached from the pre-commit row. Final consistency window ~1s.
cacheUrl(code, effectiveUrl);
scheduleStaleClear(code);
// D-C2-16: one broadcast per resolution-changing write, at the write itself —
// never from scheduleStaleClear's delayed pass (that would publish twice).
cacheBus.publishL1Invalidate(code);
```
`ShortLinkService.java:512-519`

`setStatus`（:531-541）与 `delete`（:546-558）的尾段形状相同，只有一处不同：它们调 `evictCache`（:538、:555）而非 `cacheUrl`。分工逻辑看值的变化方向。update 改的是 URL 本身，新值已知且合法，写穿让本实例与 Redis 立刻持有新值；disable/delete 是行级失效，任何被缓存的 URL 从此刻起都是谎言，写穿反而会散布谎言，所以只删不写。`singleFlight.forget` 的参数与时序纪律是 8.4 课的主题，本课只需看到它在缓存动作之前。

注释 :517-518 是一条容易忽略的纪律：广播只在写路径本尊发一次，`scheduleStaleClear` 的延迟遍绝不补发。延迟遍如果也广播，对端实例会收到两次失效，第二次发生在对方可能已经回填新值之后，白白多剔一次本地 L1（回填立即失效）。一次写、一次广播，收支平衡。

### 5.6 CacheBus 广播与 origin 跳过自己

```java
public void publishL1Invalidate(String code) {
    if (!properties.isCacheBusEnabled()) {
        return;
    }
    try {
        redis.convertAndSend(L1_INVALIDATE_CHANNEL, code + "|" + INSTANCE_ID);
        meterRegistry.counter("linkforge.l1.invalidate.publish", "result", "ok").increment();
    } catch (Exception ex) {
        meterRegistry.counter("linkforge.l1.invalidate.publish", "result", "error").increment();
        log.warn("l1 invalidate publish failed; peers stay stale up to their L1 TTL: {}", ex.getMessage());
    }
}
```
`CacheBus.java:57-68`

载荷是 `code|i-xxx` 两段，`INSTANCE_ID`（:28）是每个 JVM 启动时生成的 `i-` 加 UUID。发布失败吞掉打点（fail-open，:64-67），注释把代价写明：对端陈旧最多到它们的 L1 TTL（10 秒）。

订阅端在 `UrlLocalCache`：

```java
@PostConstruct
void subscribe() {
    if (!properties.isCacheBusEnabled()) {
        return;
    }
    container.addMessageListener((message, pattern) -> applyRemoteInvalidate(message),
            new ChannelTopic(CacheBus.L1_INVALIDATE_CHANNEL));
    log.info("l1 invalidate subscriber registered on {}", CacheBus.L1_INVALIDATE_CHANNEL);
}

private void applyRemoteInvalidate(Message message) {
    CacheBus.Put put = CacheBus.parsePut(new String(message.getBody(), StandardCharsets.UTF_8));
    if (put == null) {
        meterRegistry.counter("linkforge.l1.invalidate.malformed").increment();
        return;
    }
    // D-C2-18: own origin means this instance just wrote the fresh URL through
    // (cacheUrl at the write); wiping it would self-harm. Peers apply, self skips.
    if (CacheBus.INSTANCE_ID.equals(put.origin())) {
        return;
    }
    invalidate(put.code());
    meterRegistry.counter("linkforge.l1.invalidate.remote").increment();
}
```
`UrlLocalCache.java:50-74`

origin 跳过自己（:69-71，D-C2-18）是这套广播里最值得停一步的设计。发布者自己刚写穿过新 URL，`cacheUrl` 已经把本实例 L1 放了新值；如果订阅回调不认 origin，把刚写的新值剔掉，本实例的下一次读就白付一次 L2 往返。对端实例没有刚写穿，它们的 L1 里可能还留着旧 URL，必须剔。畸形载荷（split 后不是两段，`CacheBus.java:74-80`）打点丢弃，绝不让监听线程抛异常。

订阅端的动作边界写在两行注释里：:49 说「对端失效只剔这一个本地 L1 槽位」，:60 说「只动 L1 槽位，从不碰 Redis L2」。从代码看它也确实不碰空值缓存与 Bloom（`invalidate` 只调本地 `cache.invalidate`，:91-95）。L2 是共享的，写者自己已经写穿了；重复动它无益。

### 5.7 多实例最终一致窗口

把三个机制按时间排开，就能回答本课开头的问题。写者实例在 DB 提交后：update 写穿新 URL（毫秒），disable/delete 删旧缓存（毫秒），随后都挂上 1 秒后的第二次删除并广播一次（毫秒）。对端实例：收到广播剔本地 L1（毫秒级，取决于网络与监听线程调度）。此后全集群的新读都会走 L2 拿到新 URL 或拿到「键不存在」（L1 已空，L2 是写者的新状态）。窗口的上界由三个 fail-open 决定：广播丢失（Redis pub/sub 无确认）时对端 L1 残留旧 URL 最多 10 秒（L1 TTL）；L2 删除失败时旧 URL 最多活 3600 秒（L2 TTL）；读者迟到回填的脏值由第二删在 1 秒时清掉。README 口径 :369 概括为「最终一致窗口约 1 秒、上限为 TTL（默认 3600 秒）」。

## 6. 技术原理补充

- **Caffeine L1（框架与项目混合）**：JVM 内存缓存库，Spring Boot 官方推荐。不用它则每个跳转请求都要付一次 Redis 往返（约 0.2 到 1 毫秒级，取决于网络），热点码的 QPS 高时这笔开销可观。代价是每个实例各存一份（最多 1 万条 URL 字符串，内存开销小），跨实例一致性要自己管，也就是本课的三件套。`expireAfterWrite` 保证写后 10 秒必过期，`maximumSize` 保证内存有界。
- **写穿与 Cache-Aside（通用技术）**：Cache-Aside 是读时回填（本项目读路径 :678-680 也有），写时只删缓存；写穿是写时同步双写缓存。写穿的收益是写后立刻热读，代价是写路径多付一次 Redis SET；本项目两者并用，写路径写穿、读路径回填。纯教科书写穿在写失败时会卡住业务，本项目用 fail-open 化解。
- **延迟双删（通用技术）**：写路径在提交后先处理一次缓存，再安排一次延时删除，治的是「并发读者把提交前的旧行回填进缓存」的窗口。本项目的形态是「写穿后延时删」（update）与「删除后延时再删」（disable/delete）。不做的后果：A 的迟到回填把旧 URL 留在缓存里，最长活到 TTL（3600 秒），期间跳转去旧地址。1 秒不是魔法数字，它来自 :645-648 注释的「一个查询延迟」量级。
- **pub/sub 对齐（框架行为）**：Redis pub/sub 是 at-most-once，本项目把它用作对齐加速而不是正确性依赖，丢失由两层 TTL 兜底。对比方案：Redis Streams 有确认与重放，但要管消费组状态，对一个「丢了等 TTL」就够的场景是过度设计（推断，README :393-398 只陈述了现状）。

## 7. 可视化

按顺序看两张图：

1. [../../diagrams/archify/html/06-redis-data-flow.html](../../diagrams/archify/html/06-redis-data-flow.html)：键空间数据流。本课节点对应：`linkforge:code:*` 的写入方 `cacheUrl` :971 与读取方 :635，`linkforge:l1:invalidate` 频道的发布方 :519/:540/:557 与订阅方 `UrlLocalCache.subscribe` :50-58。
2. [../../diagrams/archify/html/13-cache-hit-miss-sequence.html](../../diagrams/archify/html/13-cache-hit-miss-sequence.html)：一次跳转的 L1、L2、DB 时序。本课节点对应：L1 查询 :631、L2 查询 :635、L1 回填 :676、L2 回写 `cacheUrl` :679。

Mermaid 备用图 [../../diagrams/mermaid/redirect-decision-chain.md](../../diagrams/mermaid/redirect-decision-chain.md) 的判定链里，L1/L2 是第 4、5 步。

## 8. 项目中的真实执行过程

场景：两台实例 A、B 共用一个 Redis。B 实例的用户把 `Ab3xK9m` 的目标从 `https://a.example.com` 改成 `https://b.example.com`，A 实例上的用户几乎同时继续访问。

1. B 的 `update` 走到 :495 `peek` 快照在飞行中的行读（8.4 讲），:504 `updateById` 提交 DB。
2. B 的 :515 `cacheUrl` 写穿：B 的 L1 与 Redis 的 `linkforge:code:Ab3xK9m` 同时变成新 URL。
3. B 的 :516 `scheduleStaleClear` 挂上 1 秒后的延迟删；:519 广播 `Ab3xK9m|i-B` 到 `linkforge:l1:invalidate`。
4. A 的监听线程收到广播，`applyRemoteInvalidate` 看 origin 是 `i-B` 不是自己（:69-71），剔掉 A 的 L1 槽位，打点 `l1.invalidate.remote`。
5. A 的用户此刻请求 `/r/Ab3xK9m`：L1 miss（刚被剔），L2 hit 拿到新 URL（B 在第 2 步写穿），行读确认状态与 URL 合法，L1 回填新值（:675-677），302 去新地址。
6. B 的 1 秒延迟删触发（:991-999），`evictCache` 把 A 刚回填的新值也删了，顺带 B 自己的 L1 槽位。这是方案的已知代价：第二删不区分新旧，宁可多删一次，下次读自动回填。下次读时 L2 miss 一次、DB 读一次，回填的又是行里的权威新 URL。
7. 若第 3 步广播丢失（断连窗口）：A 的 L1 残留旧 URL 最多 10 秒，10 秒内 A 的请求跳旧地址；10 秒后 L1 过期，L2 早已是新值，自愈。

## 9. 为什么这样设计

**origin 跳过自己，防 self-harm。** 广播是全集群可见的，发布者自己也会收到。不跳过的后果是发布者剔掉自己刚写穿的新值，下一次本机读白付一次 L2 往返，写路径的写穿收益归零。D-C2-18 注释（:67-68）一句话讲完。对照组在 8.3：`CodeBloomFilter` 对自己的消息刻意不跳过（`CodeBloomFilter.java:74-76`），因为 Bloom put 幂等，跳过反而多一个丢消息的口子。同一个总线，两种订阅策略，各自的理由都写在注释里。

**L1 只存 URL 字符串，命中不跳过行读。** 两个选择互相成就。只存 URL（javadoc :18-19「Caches NOTHING but the URL」），L1 就不会持有状态、过期、密码哈希这些低频变更字段，陈旧风险被压到最小；而只要「每请求读行」的契约在（7.1 课已确认），L1 命中省的只是 Redis 往返，禁用与过期判定天然不受缓存影响。反过来，若 L1 缓存整行，10 秒窗口内禁用不生效，契约即破。README :403 的口径「命中只省 URL 字段读取」说的就是这层。

**不用分布式 L1。** L1 的全部价值是「省一次网络往返」。放进 Redis，每次读还是一次网络往返，L1 退化成 L2 的镜像，白白翻倍请求数。README :393 明说没有分布式 L1，跨实例对齐走广播加 TTL 兜底。代价是接受 10 秒级的陈旧窗口（广播丢失时），而「行权威」的判定设计保证这个窗口内最坏结果只是跳去旧地址，不产生错误状态。

## 10. 本节总结

- `UrlLocalCache`：Caffeine，最大 1 万条，写后 10 秒过期，只存 URL 字符串；开关关掉即透明。
- 读路径：L1 先行（:631），L2 只在 L1 未命中时查（:635），L2 异常 fail-open 继续行读（op=l2_get），两段回填都在行校验之后（:675-680）。
- 写路径尾段三件套：写穿或纯失效（update 用 `cacheUrl`，setStatus/delete 用 `evictCache`）、1 秒延迟双删、一次广播；广播只发在写路径本尊（:517-518）。
- 广播订阅只剔本地 L1，origin 跳过自己防 self-harm（D-C2-18），畸形载荷打点丢弃。
- 最终一致窗口约 1 秒，广播丢失上界 10 秒（L1 TTL），L2 删除失败上界 3600 秒（L2 TTL）。

## 11. 课后练习

**练习 1（概念理解）**：`update` 写穿新 URL，`delete` 却只删不写。如果 `delete` 也写穿一个空串或哨兵值，会引入什么问题？

答案解析：`resolveTarget` 的缓存命中判定是 `cached != null && !cached.isBlank()`（:632、:668），空串会走 miss 分支，行为碰巧不出错；但语义上把「键不存在」和「键存在但值无效」两种状态混进一个键里，`absentKnown` 那套哨兵设计（8.3 的 `"1"`）说明本项目倾向为不同语义建不同键。更实际的害处：多写一个键多一次失败面，而 `evictCache` 的 DEL 更便宜。设计上「失效用删，更新用写」，读者一眼能从调用点看出值的方向。

**练习 2（源码定位）**：找到「延迟遍绝不重复广播」的注释行号，并说明如果延迟遍也广播，对端会多发生什么。

答案解析：注释在 `ShortLinkService.java:517-518`（"never from scheduleStaleClear's delayed pass (that would publish twice)"）。若延迟遍也广播：对端在写后 1 秒再收到一次失效，此时对端 L1 里存的已是新 URL（第 5 节时序第 5 步的回填），第二次广播把它剔掉，下一次读退回 L2 miss。正确性无损，纯粹是浪费一次 Redis 往返。本项目的原则是广播按「次解析变更写」计一次（D-C2-16）。

**练习 3（执行过程）**：在第 8 节时序第 6 步中，B 的 1 秒延迟删把 A 刚回填的新值删掉了。推演下一次 A 实例的跳转读的完整路径。

答案解析：A 的 L1 在第 4 步被剔、第 5 步回填的新值在 B 的延迟删里又被剔（`evictCache` 先 `urlLocalCache.invalidate` :980），所以 A 下次读 L1 miss；L2 也 miss（第 6 步 DEL 了）；走 singleflight 行读拿到权威新 URL，:678-680 的 `cacheUrl` 把 L1 与 L2 双双回填。即多付一次 L2 往返加一次行读（行读本来每请求都要读），无任何错误状态。这题练「延迟双删宁可错删」的代价核算。

**练习 4（设计分析）**：把 L1 TTL 从 10 秒调到 60 秒（配置 `linkforge.l1.ttl-seconds`），广播机制不变，哪些量变了？README 的哪个口径被突破？

答案解析：变差的量有三个。广播丢失窗口从 10 秒涨到 60 秒（`CacheBus.java:66` 注释"peers stay stale up to their L1 TTL"的量级直接放大）；`LinkForgeProperties.L1` 的注释 :165 写明「Must stay well under the Redis positive-cache TTL」，60 秒仍满足 3600 秒的硬约束；但 :647 注释给出的一致性界（跟车者最多旧一个查询延迟）不受影响，受影响的是「非跟车」读者的陈旧读概率。README :369 口径「最终一致窗口约 1 秒、上限为 TTL」中「上限为 TTL」指 L2 的 3600 秒，未突破；但「约 1 秒」的常态窗口在广播丢失时被拉长到 60 秒。要点：TTL 是广播的兜底，兜底变长，常态承诺的可信度跟着变。

## 12. 与后续课程的联系

- 8.3 课讲空值缓存与 Bloom，其中 `CodeBloomFilter` 对广播「不跳过自己」与本课 origin 跳过形成对照；`ShortLinkMapper.selectAllCodes` 的绕过逻辑删除设计也在这课展开。
- 8.4 课讲 `singleFlight.forget` 的 peek 快照与 :111-113 残留窗口，本课三个写路径尾段的第一个动作在那里补全。
- 7.1 课的 12 步判定链里，本课对应第 4、5 步（:631-644）；advanced/05-cache-hit-and-miss.md 专题会从缓存视角重走一遍全链路。
