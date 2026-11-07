# 8.4 singleflight：并发同码读合并与 peek/forget 时序

> 本课回答一个问题：热搜把一个短码打爆，一秒钟两千个请求同时 miss 缓存，怎么保证 MySQL 只被读一次？写路径（更新、禁用、删除）提交时，怎么既掐断在飞行的旧读、又不误杀读到新行的飞行？

## 1. 本节学习目标

学完本课，你应该能：

1. 说清缓存击穿问题与 singleflight 的解法轮廓，并解释它为什么不是缓存（飞行结束条目即删）。
2. 解释 `putIfAbsent` 而不是 `computeIfAbsent` 的原因（CHM bin 锁），这是本课最重要的 Java 语言点。
3. 走通 Leader 与 Follower 两条路径：同步执行、500 毫秒超时降级自读、异常原样传播，报出 `loads`/`shared`/`timeout` 三个指标的触发行。
4. 复述 `peek` 快照加两参 `forget` 的时序纪律，指认 :111-113 承认的残留窗口，以及三个写路径调用点的行号。
5. 说出「不引入分布式锁」的理由与出处，并用两个测试类验证以上全部行为。

## 2. 前置知识

- 缓存击穿（hotspot invalidation）：一个热点键过期或未缓存的瞬间，N 个并发请求同时发现 miss，各自去读 DB。N 很大时 DB 瞬时压力是平时的 N 倍。
- `CompletableFuture`（Java 8 引入）：一个「将来才有值」的容器。`complete(v)` 把值放进去并唤醒所有 `get()` 的等待者；`completeExceptionally(ex)` 放进去的是异常。
- `ConcurrentHashMap`（CHM）的分段加锁：内部是数组，每个槽位（bin）一把锁，`computeIfAbsent` 在执行映射函数期间持有 bin 锁。这是本课要绕开的坑。
- 模块 7.1 课的 12 步判定链：本课聚焦第 6 步「singleflight 行读」（`ShortLinkService.java:649`）。
- 8.2 课的写路径尾段：`forget` 是尾段第一个动作，本课补全它的参数与时序。

## 3. 本节涉及的源码

| 文件 | 行数 | 本课关注点 | 阅读顺序 |
|----|----|----|----|
| `src/main/java/com/linkforge/link/support/SingleFlight.java` | 120 | 主角：javadoc :16-42、run :57-100、peek :103-105、forget :107-119 | 1 |
| `src/main/java/com/linkforge/link/support/ShortLinkRowLoader.java` | 23 | 行读加载器 :20-22 | 2 |
| `src/main/java/com/linkforge/link/service/ShortLinkService.java` | 1025 | 热路径调用 :649 与 :757、三个写路径 :495/:512、:535/:537、:548/:554 | 3 |
| `src/main/java/com/linkforge/config/LinkForgeProperties.java` | 239 | SingleFlight 段 :152-158 | 4 |
| `src/test/java/com/linkforge/link/support/SingleFlightTest.java` | 242 | 7 个单测全览 | 5 |
| `src/test/java/com/linkforge/link/SingleFlightRedirectIT.java` | 163 | 3 个集成测试 | 6 |

先读 `SingleFlight` 全文（120 行，一屏读完），再读 `ShortLinkRowLoader` 知道「加载器」长什么样，然后看调用点，最后用两个测试类逐条验收行为。配置只有两行（enabled 与 wait-timeout-ms，`application.yml:135-139`），放在第 4 步顺路看。

## 4. 类的定位

- `SingleFlight` 是 A10 引入的进程内并发合并器，泛型 `<K,V>`，本项目实例化成 `<String, ShortLink>`（`ShortLinkService.java:109` 的注入声明）。它不依赖 Redis、不依赖 DB，纯内存结构。
- `ShortLinkRowLoader` 是 `code` 到行的查询适配 bean：javadoc :9-13 写明抽成独立 bean 的两个原因，让行读能放进 singleflight 的加载器 lambda，也让测试能用 `@SpyBean` 替身数行读次数。管理接口不走它（`getOwnedLink` 直接用 mapper，:890-903），只有热路径合并。
- 调用面极小：热路径两处（`resolveTarget` :649、`unlock` :757）加三个写路径的 peek/forget 对。
- 类位置见 [../../diagrams/archify/html/02-function-map.html](../../diagrams/archify/html/02-function-map.html)。

## 5. 核心源码详解

### 5.1 状态与 javadoc：de-duplicator，不是缓存

类只有一个字段：

```java
private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();
```
`SingleFlight.java:51`

键是短码，值是「这次行读的未来结果」。javadoc（:16-42）把这个结构的身份讲死：它是一个去重器（de-duplicator），不是结果缓存。飞行结束条目即删（:28-30），没有 TTL、没有 last-value memory；null 结果（Bloom 误报、已删码）只在飞行内共享（:30-31）；空值缓存是另一个组件的职责（:31-32 点名 `linkforge:absent:{code}`）。javadoc 还预先回答了两个「为什么不用 X」：不用线程池，Leader 在自己的请求线程上同步执行（:22-23）；不引入分布式锁，多实例各自合并，分布式互斥只会把读串行化而不消除读（:40-41，README 口径 :404 同句）。

### 5.2 run：Leader 路径

```java ignore-check
public V run(K key, Supplier<V> loader) {
    LinkForgeProperties.SingleFlight cfg = properties.getSingleFlight();
    if (!cfg.isEnabled()) {
        return loader.get();
    }
    CompletableFuture<V> mine = new CompletableFuture<>();
    CompletableFuture<V> existing = inFlight.putIfAbsent(key, mine);
    if (existing == null) {
        // leader: counts every lead attempt, successful or not
        meterRegistry.counter("linkforge.singleflight.loads").increment();
        try {
            V value = loader.get();
            mine.complete(value);
            return value;
        } catch (RuntimeException ex) {
            mine.completeExceptionally(ex);
            throw ex;
        } finally {
            inFlight.remove(key, mine);
        }
    }
    // follower 路径在 5.3
}
```
`SingleFlight.java:57-77`（示意：最后一段注释为本课导航，原文件此处直接接 follower 代码）

开关关掉时退化为逐调用执行（:59-61），连 `loads` 计数器都不注册，`SingleFlightTest.java:223-232` 断言了这一点。Leader 的判定是 `putIfAbsent` 返回 null：说明表里没有在飞行的同键条目，本次调用来当 Leader。它做三件事：打点 `loads`（:66，无论成败，每次领队都算）；在自己的请求线程上同步执行 `loader.get()`（:68，没有线程切换，没有异步回调）；无论成功（`mine.complete`，:69）还是异常（`mine.completeExceptionally`，:72），`finally` 里两参 `remove(key, mine)` 把条目摘掉（:74-76）。

`complete` 与 `completeExceptionally` 唤醒的是所有已经在 `existing.get()` 上等着的 Follower。`finally` 的 `remove` 发生在 complete 之后，所以等待者先拿到结果，条目随后消失，下一个调用者重新领队。这里用两参 `remove(key, mine)` 而不是 `remove(key)`：只摘「自己领的那次飞行」，如果此刻已有新的领队换上了自己的 future，旧 Leader 绝不把它误删。与 `forget` 的两参删除是同一个纪律，5.4 展开为什么。

### 5.3 run：Follower 路径与三种异常

```java
try {
    V value = existing.get(cfg.getWaitTimeoutMs(), TimeUnit.MILLISECONDS);
    meterRegistry.counter("linkforge.singleflight.shared").increment();
    return value;
} catch (TimeoutException ex) {
    meterRegistry.counter("linkforge.singleflight.timeout").increment();
    return loader.get();
} catch (InterruptedException ex) {
    Thread.currentThread().interrupt();
    meterRegistry.counter("linkforge.singleflight.timeout").increment();
    return loader.get();
} catch (ExecutionException ex) {
    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
    if (cause instanceof RuntimeException runtime) {
        throw runtime;
    }
    if (cause instanceof Error error) {
        throw error;
    }
    throw new IllegalStateException(cause);
}
```
`SingleFlight.java:79-99`

Follower 等待上限是 `wait-timeout-ms`（默认 500，`LinkForgeProperties.java:157`）。三个 catch 是三种命运。`TimeoutException`（:83-85）：Leader 太慢（DB 卡住、机器卡顿），Follower 打点 `timeout` 后自己读，宁可多一次 DB 读也不把尾延迟拴在别人身上，这就是 fail-open；`SingleFlightTest.java:133-154` 把超时压到 50 毫秒，断言等待确实发生（至少 40 毫秒）且降级自读返回了自己的值。`InterruptedException`（:86-89）：先恢复中断标志再降级自读，中断礼貌不丢。`ExecutionException`（:90-99）：Leader 抛了异常，`get()` 把它包在 `ExecutionException` 里送过来，解包后按原类型重抛（`RuntimeException` 原样、`Error` 原样、受检异常包成 `IllegalStateException`），效果等价于「每个 Follower 自己读了一遍然后自己也失败了」，调用方看到的异常类型与无 singleflight 时一致。

### 5.4 peek 与 forget：写路径的时序纪律

```java
/** Read-only view of the current in-flight entry; write paths snapshot before committing. */
public CompletableFuture<V> peek(K key) {
    return inFlight.get(key);
}

/**
 * Drops the in-flight entry only if it is still the one snapshotted before the
 * DB write (two-arg remove), so a flight that started after the commit is never
 * killed (A10 audit N1). A null snapshot (nothing was in flight) is a no-op.
 * Residual window: a flight that starts between the snapshot and the commit
 * still reads the old row and gets removed here — same order as the accepted
 * per-query-latency window.
 */
public void forget(K key, CompletableFuture<V> priorSnapshot) {
    if (priorSnapshot != null) {
        inFlight.remove(key, priorSnapshot);
    }
}
```
`SingleFlight.java:102-119`

为什么要这个纪律，从问题本身推。写路径（比如 `disable`）提交 DB 后，如果在飞行中的读还没结束，它会读到禁用前的旧行，把 302 发出去；更糟的是后续请求会「跟车」这个旧飞行。所以提交后要 `forget`：掐断在飞行的旧读，让下一个请求重新领队读新行。

但「掐断」不能瞎掐。设想：快照（拿到旧飞行 F1）、DB 提交、F1 自然结束自己摘除了自己、新请求领队 F2 开始读新行、写路径这才执行 `forget`。若 `forget` 无条件 `remove(key)`，F2 被误杀，读新行的请求白白重来。两参 `remove(key, priorSnapshot)` 只在「表里躺着的还是快照那个 F1」时才动手（A10 audit N1），F2 安然无恙。

:111-113 的 javadoc 承认了一个残留窗口：在「快照之后、提交之前」这段时间里启动的飞行，读到的还是旧行，然后被 `forget` 摘除。坐上这个飞行的跟车者拿到的是旧行判定（比如禁用前的 302）。这个窗口为什么可接受？项目已有的另一扇更宽的门：普通跟车者读到的行本来就可能比到达时刻旧一个查询延迟（毫秒级，`ShortLinkService.java:645-648` 的注释），残留窗口的陈旧量级与它相同。这不是被忽略的缺陷，是被测量过的取舍。

三个写路径调用点，快照总在提交前、forget 总在提交后：

```java ignore-check
// update: ShortLinkService.java:495 与 :512
CompletableFuture<ShortLink> priorFlight = singleFlight.peek(code);
... // 清密码、updateById、variants 事务（:499-511）
singleFlight.forget(code, priorFlight);

// setStatus: :535 与 :537（peek 在 updateById 前，forget 在其后）
// delete:   :548 与 :554（peek 在事务前，forget 在事务后）
```
`ShortLinkService.java:495-519`、`:531-541`、`:546-558`（示意：三处调用点按行号折叠罗列）

注释 :492-494 把纪律写成话：快照必须在提交前，这样提交后的 forget 永远杀不到提交后启动的飞行。`SingleFlightTest.java:157-191` 用一个完整的线程编排复现了 N1 场景：旧飞行结束、新飞行启动之后，拿着旧快照的 `forget` 没伤到新飞行，搭车的请求拿到了新行。

### 5.5 热路径调用与指标族

跳转判定链第 6 步（`ShortLinkService.java:649`）：

```java
ShortLink link = singleFlight.run(code, () -> rowLoader.load(code));
```
`ShortLinkService.java:649`

加载器是 `rowLoader.load`（`ShortLinkRowLoader.java:20-22`），一次 `selectOne`。注意 ：649 的注释（:645-648）：无论 Redis 命中与否都走这行，行是判定权威；Follower 看到的行最多旧一个查询延迟。第二个热路径调用在 `unlock`（:757），密码解锁前同样合并同码行读，让爆破请求也合并成一次 DB 读（限流在行读之后才生效，:767-780，7.2 课讲过这个顺序的安全理由）。

指标族三个，全是 Counter：`linkforge.singleflight.loads`（每次领队，:66）、`linkforge.singleflight.shared`（每个成功跟车，:81）、`linkforge.singleflight.timeout`（每次降级自读，:84 与 :88）。看板读法（推断）：`loads` 稳定说明流量分散在众多码上，`shared` 高说明热点码的合并生效，`timeout` 抬升说明 DB 或 Leader 变慢，跟车等不及。禁用态三个都不注册（`SingleFlightTest.java:231` 的断言依据）。

### 5.6 测试验收

`SingleFlightTest`（纯单测，不启 Spring）：

| 用例 | 行号 | 验收点 |
|----|----|----|
| concurrentCallersShareExactlyOneLoad | :60-87 | 1 个 Leader 加 32 个 Follower，加载器只执行 1 次，loads=1、shared=32 |
| leaderFailurePropagatesAndNextCallerReleads | :90-130 | Follower 收到原类型异常，条目消失，下次调用重新领队 |
| followerTimeoutFallsBackToSelfRead | :133-154 | 50 毫秒超时后自读，timeout=1、shared=0，飞行结束无残留 |
| forgetWithStaleSnapshotSparesThePostWriteFlight | :157-191 | 旧快照的 forget 杀不到提交后的新飞行，搭车者拿到新行 |
| forgetWithNullSnapshotIsANoOp | :194-200 | 空快照 forget 不抛不误删 |
| forgetDropsTheInFlightEntrySoNextCallerReleads | :203-220 | 写路径 forget 后下一个调用重新领队 |
| disabledRunsTheLoaderPerCaller | :223-232 | 禁用后逐调用执行且不注册任何指标 |

`SingleFlightRedirectIT`（Testcontainers 全栈）：用 `@SpyBean` 包住 `ShortLinkRowLoader` 加 300 毫秒人为延迟（:62-70，注释 ：30-34 说明行读本身 1-2 毫秒难以稳定重叠，制造宽窗口），Hikari 池压到 4（:40）让堆积可见。三个用例：24 个并发 GET 全部 302 且行读恰好 1 次、loads 增 1、shared 增 23（:78-106）；合并关掉时 12 个请求 12 次行读且 loads 不增（:109-141）；飞行中 `disable` 之后，下一个 GET 必须拿到 404 而不是搭旧飞行（:144-162，注释 ：155 点名 forget 的作用）。

## 6. 技术原理补充

- **缓存击穿与 singleflight（通用技术）**：Go 语言的 `golang.org/x/sync/singleflight` 让这个词流行起来。不用它的后果：热点码过期瞬间（或未缓存首发）N 个并发请求 N 次 DB 读。同族方案对比：分布式锁把 N 次读变 1 次但引入锁服务依赖与锁等待；过期前异步续期治过期击穿不治首发击穿；singleflight 零依赖、只合并不缓存。本项目的选择理由见 README :404：前提是「每请求必须读行」，分布式锁只会把读串行化、拉长尾延迟。
- **CHM 与 computeIfAbsent 的 bin 锁（Java 语言知识）**：`computeIfAbsent(key, fn)` 在 fn 执行期间持有 key 所在 bin 的锁。fn 若做慢操作（DB 读、RPC），哈希到同一 bin 的无关键全部被堵。`putIfAbsent` 返回后立即释放，慢操作移到锁外用 `CompletableFuture` 承载。这是「锁内只做原子簿记，锁外做慢活」的典型应用。`SingleFlight.java:25-26` 的 javadoc 一句话点名：a blocking loader inside computeIfAbsent would hold the CHM bin lock and stall unrelated keys。
- **CompletableFuture 作为共享结果的容器（Java 语言知识）**：本项目只用它最朴素的三个能力：`complete`、`completeExceptionally`、带超时的 `get`。没有线程池、没有 then 链。`get` 的三受检异常恰好对应三种 Follower 命运（5.3），语言机制与业务分支一一对应。
- **两参 remove 与「检查再行动」的原子化（Java 语言知识）**：`Map.remove(key, value)` 只在当前映射等于 value 时删除，把「先 get 判断再 remove」两步并成一步原子操作。本项目里它是防止误杀新飞行的关键，`SingleFlightTest.java:157-191` 是它的行为规范。

## 7. 可视化

按顺序看两张图：

1. [../../diagrams/archify/html/13-cache-hit-miss-sequence.html](../../diagrams/archify/html/13-cache-hit-miss-sequence.html)：缓存命中与未命中的时序，本课对应其中「行读 singleflight」节点：Leader 执行 `rowLoader.load`（`SingleFlight.java:68`），Follower 等待 `existing.get`（:80）。
2. [../../diagrams/archify/html/02-function-map.html](../../diagrams/archify/html/02-function-map.html)（已通过）：`SingleFlight` 与 `ShortLinkRowLoader` 在 `link.support` 包的位置，以及 `ShortLinkService` 三条调用边（读 :649/:757，写 :512/:537/:554）。

模块 7.1 的判定链图（12-redirect-decision）把本课内容画成第 6 步，与 [../../diagrams/mermaid/redirect-decision-chain.md](../../diagrams/mermaid/redirect-decision-chain.md) 的备用图等价。

## 8. 项目中的真实执行过程

场景一：24 个并发请求打向同一个未缓存的热码（`SingleFlightRedirectIT.java:78-106` 的剧本）。

1. 24 个线程同时进 `resolveTarget`，限流与 Bloom、空值缓存全部放行（码真实存在），L1、L2 都 miss。
2. 全部到达 :649。第一个线程 `putIfAbsent` 返回 null，成为 Leader，打点 `loads`，进入 `rowLoader.load`（被测试替身延迟 300 毫秒）。
3. 其余 23 个线程 `putIfAbsent` 各自拿到同一个 future，成为 Follower，在 `existing.get` 上挂起等待。
4. Leader 行读返回，`mine.complete(link)` 唤醒全部等待者，`finally` 摘除条目。
5. 23 个 Follower 逐个醒来，各自打点 `shared`，拿着同一行对象跑完后续判定（状态、过期、URL 校验、门禁、抽签都按各自请求独立执行，:645-648 注释），24 个 302 全部发出。
6. 事后核对：行读计数 1，loads 增 1，shared 增 23。若 Hikari 池只有 4 个连接，没有 singleflight 时 24 个请求要在池边排队，这就是「合并」省下的东西。

场景二：飞行中禁用（`SingleFlightRedirectIT.java:144-162` 的剧本）。

1. 请求 A 进入 :649 成为 Leader，行读被延迟 300 毫秒。
2. 运维调 `POST /api/links/{code}/disable`，`setStatus` 在 ：535 `peek` 到 A 的飞行快照，:536 提交 DB（status=0），:537 `forget` 掐断 A 的飞行条目，:538-540 走缓存尾段（8.2 讲过）。
3. 请求 B 此刻到达 :649：条目已被 forget 摘除，B 自己领队，行读拿到禁用后的新行，返回 404。B 没有搭上 A 的旧飞行。
4. A 的行读结束，返回旧行，302 照发（A 到达早于提交，这个陈旧量级在 ：647 注释的既收窗口内）。B 的 404 与 A 的 302 并存，正是 ：111-113 残留窗口的现实形状。

## 9. 为什么这样设计

**为什么 Leader 同步执行而不是丢进线程池。** 行读本来就是毫秒级操作，Leader 在自己的请求线程上执行，总延迟等于「无 singleflight 时自己的读延迟」；丢进线程池要付一次任务调度，还要管池的大小、拒绝策略、生命周期。javadoc :22-23 写明「no extra pool, no async hand-off」。Follower 等待是阻塞式的，但这正是预期：跟车本来就要等 Leader 的读结束。

**为什么超时要降级自读。** Leader 卡住（DB 抖动、GC 停顿）时，所有后续同码请求都堵在 `get` 上，热点码的可用性被一个慢 Leader 绑架。500 毫秒上限把每个请求的最坏等待钉死，超时者自己读，最多多一次 DB 读。`timeout` 指标让这种状态可见。这和 8.2 课的 fail-open 是同一个哲学：任何共享结构故障时，退化路径必须是「多花点钱把事办成」，而不是「跟着一起挂」。

**为什么写路径要 peek 加 forget 两步而不是一步。** 一步方案是「提交后直接 `inFlight.remove(code)`」，它有一个致命场景：提交与新领队之间赛跑。提交前快照、提交后两参删除，把「要杀谁」的决定从提交时刻提前到快照时刻，天然避开与后到飞行的竞争（A10 audit N1）。两个动作隔着一个 DB 提交，看似别扭，实则是把「判断依据固定在提交前的一致时刻」。null 快照跳过（:116-118）让「当时没有在飞行的读」这个常态零成本。

## 10. 本节总结

- `SingleFlight` 是进程内去重器：`CHM<code, CompletableFuture<行>>`，飞行结束条目即删，不缓存任何结果。
- Leader 用 `putIfAbsent` 判定（避开 computeIfAbsent 的 bin 锁），在自己线程上同步执行，成功 complete、异常 completeExceptionally、finally 两参自摘。
- Follower 阻塞等待，上限 500 毫秒；超时或中断降级自读（`timeout` 指标），Leader 异常解包原样重抛。
- 写路径时序纪律：提交前 `peek` 快照（:495/:535/:548），提交后 `forget` 两参删除（:512/:537/:554）；:111-113 残留窗口的陈旧量级等于既收的查询延迟窗口。
- 指标 `loads`/`shared`/`timeout` 三个 Counter；禁用态零注册。行为由 7 个单测与 3 个集成测试钉死。

## 11. 课后练习

**练习 1（概念理解）**：`computeIfAbsent` 写法大概长这样：`inFlight.computeIfAbsent(key, k -> { link = load(k); return future; })`。说出它在两类场景下的具体故障。

答案解析：场景一，无关键被堵。`load` 是毫秒到秒级的阻塞 DB 读，执行期间 CHM 持有该 bin 的锁，任何哈希到同一 bin 的其他键做 `computeIfAbsent`、`put`、`remove` 都会阻塞。热点码一卡，一片无辜码跟着卡。场景二，重入与超时语义混乱。`computeIfAbsent` 的映射函数内无法表达「等别人 500 毫秒、超时就自己干」，它必须同步返回映射结果，等于逼着每个调用者排队等完整行读。javadoc :25-27 的警告就是这两句。正确形态是「putIfAbsent 抢位，慢活在锁外，future 承载结果」。

**练习 2（源码定位）**：找到 `SingleFlight.java` 中「两参删除」的两处使用，说明各自防止的误删对象。

答案解析：第一处 :75（Leader 的 finally `inFlight.remove(key, mine)`），防止误删「在我飞行期间别人重新领队换上的新 future」，比如 Leader 卡了 600 毫秒、Follower 已超时自读并领了新队。第二处 :117（forget 内 `inFlight.remove(key, priorSnapshot)`），防止误杀提交后启动的新飞行（A10 audit N1，`SingleFlightTest.java:157-191` 复现）；三个写路径（`ShortLinkService.java:512`、:537、:554）都经由这一处生效。共同点：只删「明确知道是哪一个」的条目，不做无差别清场。

**练习 3（执行过程）**：第 8 节场景二里，请求 A 拿到 302（旧行）而请求 B 拿到 404（新行）。如果 B 到达时 A 的飞行还没被 forget（时序换成 disable 晚于 B 到达 1 毫秒），B 的结果是什么？

答案解析：B 会成为 A 的 Follower，等待 A 的行读结果。A 读到的是提交前的旧行（A 的 DB 查询发生在 disable 提交前），所以 B 拿到 302。这是 javadoc :111-113 残留窗口的另一个入口：B 的飞行「开始于快照与提交之间」，坐上了读旧行的车。系统的承诺不是「提交后的请求必然看到新状态」，而是「陈旧量级不超过一个查询延迟，且下一个不搭车的请求必然看到新状态」（B 之后的请求会重新领队，因为 forget 摘了条目）。

**练习 4（设计分析）**：把 `SingleFlight` 改成「飞行结束后把结果再留 1 秒」（变成短 TTL 的结果缓存），能进一步省 DB 读，但破坏什么？

答案解析：破坏三件事。其一，身份变了：javadoc :28-32 明言这是去重器不是缓存，null 结果（Bloom 误报、已删码）会在 1 秒内被重复发放，而正确的空值缓存路径（8.3）要求 null 必须走 `markAbsent` 写 Redis 哨兵，本地短 TTL 会让多实例间出现「A 实例知道不存在、B 实例还在重复读」的分裂。其二，写路径一致性变差：disable 后 1 秒内，读请求拿缓存里的启用行直接 302，`forget` 掐不断一个「已经结束的飞行留下的值」，`peek`/`forget` 的整个时序设计作废。其三，超时语义混乱：迟到的 Follower 在 `get` 上等到的可能是飞行结果，也可能是遗留缓存值，两者的新鲜度承诺不同。要点：去重与缓存是两种契约，本项目把它们分给 `SingleFlight` 与空值缓存两个组件，边界写在 javadoc 里。

## 12. 与后续课程的联系

- 8.1 到 8.4 串起来就是判定链第 2 到 6 步的完整防护栈；9.1 课回到第 1 步，把滑动窗口 Lua 逐行拆开。
- 模块 14 的看板课（14.2）会把这些指标的 Grafana 面板归位：L1 与 Bloom 面板标注「this process」，singleflight 面板同理，多实例各看各的曲线。
- 15.1 课的测试金字塔里，`SingleFlightTest` 是「纯单测」层的范例（不启 Spring、线程编排验证并发行为），`SingleFlightRedirectIT` 是「Testcontainers 集成」层验证同一行为的另一面；advanced/04-redirect-302.md 专题会重走本课的场景一。
