# 8.3 空值缓存与 Bloom：挡住「从不存在的码」

> 本课回答一个问题：扫描器拿着一本字典反复请求不存在的短码，怎么保证 MySQL 不被这些必然失败的查询打穿？空值缓存与 Bloom 过滤器各挡哪一段、各自的边界在哪？

## 1. 本节学习目标

学完本课，你应该能：

1. 报出空值缓存三个方法的行号与行为：`absentKnown`、`markAbsent`、`clearAbsent`，说出「只缓存 DB 确认不存在」这条边界的代码证据。
2. 用一页话讲清 Bloom 过滤器原理：位数组、k 个哈希、负答案为什么权威、fpp 是什么含义。
3. 走通 `CodeBloomFilter` 的启动播种顺序：先订阅（:56-65）、先发布后播种（:91-95），并说出这两步各自防的是什么窗口。
4. 描述定时与手动 rebuild 的「旁路建新再原子替换」流程，以及失败时为什么恢复旧 filter 而不是降级 fail-open。
5. 解释「删码不可撤销」这个诚实边界：经典 Bloom 为什么不能删、rebuild 为什么仍装入逻辑删码。

## 2. 前置知识

- 8.1 课的 `linkforge:absent:{code}` 键（60+30 秒抖动 TTL）。
- 哈希函数的常识：同一输入算出同一输出，不同输入可能撞车。
- 概率常识：误判率（false positive probability，fpp）是「把不存在的说成存在」的概率，Bloom 过滤器永远不会把存在的说成不存在。
- Spring 的 `@PostConstruct`（依赖注入完成后执行）与 `@EventListener(ApplicationReadyEvent)`（应用完全就绪后执行）的先后：前者早于后者，这个先后差是本课的设计支点。
- 模块 4.1 的逻辑删除：`lf_short_link.deleted=1` 的行还在表里占着 `uk_code` 唯一索引。
- Guava：Google 的 Java 工具库，`BloomFilter` 是它的一个并发安全结构。

## 3. 本节涉及的源码

| 文件 | 行数 | 本课关注点 | 阅读顺序 |
|----|----|----|----|
| `src/main/java/com/linkforge/link/support/CodeBloomFilter.java` | 210 | 主角：javadoc :24-38、订阅 :56-65、播种 :80-113、rebuild :134-180、查询 :186-200 | 1 |
| `src/main/java/com/linkforge/link/service/ShortLinkService.java` | 1025 | 空值缓存三方法 :926-964、判定链 :615-626 与 :650-654、创建尾段 :266-269 | 2 |
| `src/main/java/com/linkforge/link/mapper/ShortLinkMapper.java` | 24 | `selectAllCodes` 绕过逻辑删除 :13-23 | 3 |
| `src/main/java/com/linkforge/link/controller/BloomAdminController.java` | 49 | 手动触发与 ADMIN 校验 :30-40 | 4 |
| `src/main/resources/application.yml` | 266 | bloom 段 :120-131 | 5 |
| `src/test/java/com/linkforge/link/BloomDisabledAtStartupIT.java` | 113 | 关闭态与空值缓存验证 :65-97 | 6 |
| `src/test/java/com/linkforge/link/BloomRebuildIT.java` | 122 | rebuild 与权限验证 :82-121 | 7 |

先读 `CodeBloomFilter` 的 javadoc 与查询方法（:24-38、:186-200）建立语义，再读播种与 rebuild（:80-180），然后回到 `ShortLinkService` 看空值缓存与判定链的两道闸，最后用两个 IT 验收。

## 4. 类的定位

- `CodeBloomFilter` 是 A9 引入的进程内布隆过滤器，只存短码字符串。它守在跳转判定链第 2 步（`ShortLinkService.java:615-618`），负答案可以在不碰 MySQL 的情况下 404。
- 空值缓存（B7）不是独立类，是 `ShortLinkService` 里的三个私有方法加一个 Redis 键，守在判定链第 3 步（:623-626）。
- `BloomAdminController` 是唯一的管理触发口：`POST /api/admin/bloom/rebuild`，只重灌本进程的 filter。
- 三者与 8.1 的 `CacheBus` 构成「进程内结构加广播对齐」的完整图景，包位置见 [../../diagrams/archify/html/03-package-map.html](../../diagrams/archify/html/03-package-map.html)。

## 5. 核心源码详解

### 5.1 空值缓存：三方法与写入边界

判定链第 3 步在 Bloom 之后、L1/L2 之前查它：

```java
// B7 negative cache: the DB has confirmed this code absent recently. Only
// confirmed-absent codes live here — never disabled (404) or expired (410),
// whose rows still exist and must take effect again immediately.
if (absentKnown(code)) {
    meterRegistry.counter("linkforge.cache.absent.hit").increment();
    throw new BizException(404, "short link not found");
}
```
`ShortLinkService.java:620-626`

注释就是边界声明：这个缓存只收「DB 确认不存在」的码。什么算确认不存在？判定链第 6 步，singleflight 行读返回 null：

```java
ShortLink link = singleFlight.run(code, () -> rowLoader.load(code));
if (link == null) {
    // B7: DB says the code does not exist (Bloom false positive, logical
    // delete, or Bloom off) — remember for a short jittered TTL.
    markAbsent(code);
    throw new BizException(404, "short link not found");
}
```
`ShortLinkService.java:649-655`

行不存在只有三种成因：Bloom 误报、逻辑删除后的码、Bloom 关闭。三种都落到 `markAbsent`。而禁用（:656-658）与过期（:659-661）的行读得到的是非 null 的行，走的是 404/410 异常，`markAbsent` 根本不在那个分支上。这就是「禁用/过期绝不写入」的代码级保证：不是靠纪律，是靠分支结构。

三个方法本身：

```java
private boolean absentKnown(String code) {
    if (properties.getShortLink().getAbsentTtlSeconds() <= 0) {
        return false;
    }
    try {
        return ABSENT_SENTINEL.equals(stringRedisTemplate.opsForValue().get(ABSENT_KEY_PREFIX + code));
    } catch (Exception ex) {
        // fail-open: a broken probe must not break redirects (Bw1 D-Bw1-12)
        log.warn("absent probe failed for {}: {}", code, ex.getMessage());
        return false;
    }
}

private void markAbsent(String code) {
    if (properties.getShortLink().getAbsentTtlSeconds() <= 0) {
        return;
    }
    long ttl = jitteredTtlSeconds(properties.getShortLink().getAbsentTtlSeconds(),
            properties.getShortLink().getAbsentTtlJitterSeconds());
    try {
        stringRedisTemplate.opsForValue().set(ABSENT_KEY_PREFIX + code, ABSENT_SENTINEL, ttl, TimeUnit.SECONDS);
        meterRegistry.counter("linkforge.cache.absent.put").increment();
    } catch (Exception ex) {
        log.warn("absent write failed for {}: {}", code, ex.getMessage());
        meterRegistry.counter("linkforge.cache.failopen", "op", "absent_mark").increment();
    }
}

private void clearAbsent(String code) {
    if (properties.getShortLink().getAbsentTtlSeconds() <= 0) {
        return;
    }
    try {
        stringRedisTemplate.delete(ABSENT_KEY_PREFIX + code);
    } catch (Exception ex) {
        log.warn("absent clear failed for {}: {}", code, ex.getMessage());
        meterRegistry.counter("linkforge.cache.failopen", "op", "absent_clear").increment();
    }
}
```
`ShortLinkService.java:926-964`

配置 `absentTtlSeconds <= 0` 时整个机制透明（探针恒 false、写与删直接跳过）。三个方法各自 fail-open：查询异常当未命中（:932-936），写失败打点 `absent_mark`（:948-951），删失败打点 `absent_clear`（:960-963）。写入的是哨兵 `"1"`（常量 :85），TTL 是 60 加 uniform[0,30] 秒的抖动（8.1 课讲过只向上的理由）。

清除点在创建尾段：`doCreate` 提交 DB 后立即 `clearAbsent(link.getCode())`（:268）。场景是扫描器先把一个码打成「确认不存在」，用户随后恰好创建了这个自定义码，不清缓存就要等 TTL 才能跳转。`clearAbsent` 把这个窗口压到零。

### 5.2 Bloom 过滤器原理与 Guava API

Bloom 过滤器（布隆过滤器）是一个 m 位位数组加 k 个哈希函数。插入一个元素：对它算 k 次哈希，把 k 个位置全置 1。查询一个元素：算 k 次哈希，看 k 个位置。只要有一个是 0，这个元素必然没插入过（负答案权威）；全是 1，大概率插入过，但可能是别的元素把这几个位置都占了（正答案含误报，误报率就是 fpp）。

Guava 的构造把容量与误报率换算成 m 和 k：

```java
BloomFilter<String> fresh = BloomFilter.create(
        Funnels.stringFunnel(StandardCharsets.UTF_8), cfg.getExpectedInsertions(), cfg.getFpp());
```
`CodeBloomFilter.java:88-90`

`expectedInsertions` 是容量预估（默认 100000，`application.yml:124`），`fpp` 是期望误报率（默认 0.0001 即万分之一，`application.yml:125`）。容量超得越多，fpp 越偏离承诺值，`LinkForgeProperties.java:142-143` 的 Bloom javadoc 写明「插入远超容量会退化误报率」。按标准公式推导（推断，非实测）：10 万条、fpp 万分之一约需 190 万位，约 240 KB 内存，k 约 13 个哈希。一个过滤器不到四分之一兆，这就是「进程内放得起」的底气。`approximateElementCount()`（:206-209）给出估计元素数，播种日志（:102-104）与测试断言（`BloomRebuildIT.java:90-91`）用它。

### 5.3 CodeBloomFilter 播种：先订阅、先发布后播种

订阅与播种分两步走，中间隔着一个刻意的时间差：

```java
@PostConstruct
void subscribe() {
    if (!properties.isCacheBusEnabled()) {
        return;
    }
    // Registered pre-load (D-C2-5): the container is a SmartLifecycle that subscribes at context start, strictly before the ApplicationReadyEvent load, so no put can land between load and subscribe (that gap would stay invisible until rebuild).
    container.addMessageListener((message, pattern) -> applyRemotePut(message),
            new ChannelTopic(CacheBus.BLOOM_PUT_CHANNEL));
    log.info("bloom put broadcast subscriber registered on {}", CacheBus.BLOOM_PUT_CHANNEL);
}
```
`CodeBloomFilter.java:56-65`

`@PostConstruct` 在依赖注入完成后立即执行，`ApplicationReadyEvent` 在整个应用就绪后才发。订阅先于播种注册，多实例场景下别的实例在播种窗口内创建短链发出的广播不会被漏掉。若反过来（先播种后订阅），播种完成到订阅生效之间的 put 消息会静默丢失，被漏的码在本实例上一直是「假阴性」（可能不存在），要等下一次 rebuild 才补上。注释 :61 把这个窗口论证写全了。

播种本身由 `load()` 完成：

```java ignore-check
@EventListener(ApplicationReadyEvent.class)
public void load() {
    if (!properties.getBloom().isEnabled()) {
        log.info("bloom disabled by config; redirects stay on the cache+DB path "
                + "(the filter is process-local and is not shared across instances)");
        return;
    }
    try {
        LinkForgeProperties.Bloom cfg = properties.getBloom();
        BloomFilter<String> fresh = BloomFilter.create(
                Funnels.stringFunnel(StandardCharsets.UTF_8), cfg.getExpectedInsertions(), cfg.getFpp());
        // Publish the (still fail-open) filter before seeding: a create landing in
        // the seeding window calls put() straight into `fresh` (Guava bit array is
        // concurrency-safe) instead of being dropped and staying invisible until
        // the next restart (A9 audit S1).
        filter = fresh;
        List<String> codes = shortLinkMapper.selectAllCodes();
        for (String code : codes) {
            fresh.put(code);
        }
        ready = true;
        meterRegistry.counter("linkforge.bloom.loaded").increment();
    } catch (Exception ex) {
        // No half-seeded filter may linger: back to the fail-open state
        // (filter==null, ready==false) so every probe keeps going to the DB.
        filter = null;
        ready = false;
        meterRegistry.counter("linkforge.bloom.loadfailed").increment();
        log.warn("bloom load failed; fail-open on the cache+DB path: {}", ex.getMessage(), ex);
    }
}
```
`CodeBloomFilter.java:80-113`（示意：成功日志的结尾几行以省略号折叠，其余逐字）

两个关键点。第一，「先发布后播种」（A9 audit S1）：`filter = fresh`（:95）在逐码 put 之前执行。全量播种可能持续数秒，期间用户创建短链会调 `codeBloom.put`（:195-200），Guava 位数组并发安全，这个 put 直接落进 `fresh`，播种完成后它天然在里面。若先播种后发布，播种窗口内的 put 全部落到旧引用上（初始为 null 时直接丢弃），漏掉的码同样要等 rebuild。第二，播种失败把 `filter` 置 null、`ready` 置 false（:108-109），回退到 fail-open 态：所有探针返回 true，全部走 DB，绝不留下一个「半灌的过滤器」。

### 5.4 rebuild：旁路建新、原子替换、失败恢复

rebuild 有两个触发器：定时任务（:116-122，`@Scheduled` 默认 24 小时，`application.yml:131`）和管理接口（:125-127）。两者汇入同一个私有方法：

```java
private long rebuild(boolean manual) {
    if (!properties.getBloom().isEnabled()) {
        return 0;
    }
    if (!rebuilding.compareAndSet(false, true)) {
        meterRegistry.counter("linkforge.bloom.rebuild", "result", "busy").increment();
        if (manual) {
            throw new BizException(409, "bloom rebuild already in progress");
        }
        return 0;
    }
    try {
        LinkForgeProperties.Bloom cfg = properties.getBloom();
        BloomFilter<String> fresh = BloomFilter.create(
                Funnels.stringFunnel(StandardCharsets.UTF_8), cfg.getExpectedInsertions(), cfg.getFpp());
        // Publish before seeding (same A9 audit S1 reason as load()): a create landing
        // in the seeding window puts straight into `fresh` instead of being lost.
        BloomFilter<String> previous = filter;
        filter = fresh;
        try {
            List<String> codes = shortLinkMapper.selectAllCodes();
            for (String code : codes) {
                fresh.put(code);
            }
            ready = true;
            meterRegistry.counter("linkforge.bloom.rebuild", "result", "ok").increment();
            return codes.size();
        } catch (Exception ex) {
            // Restore the pre-rebuild reference; never downgrade a loaded filter to
            // fail-open (ready stays true unless the old filter was already absent).
            filter = previous;
            ready = previous != null;
            meterRegistry.counter("linkforge.bloom.rebuild", "result", "error").increment();
            log.warn("bloom rebuild failed; previous filter restored: {}", ex.getMessage());
            if (manual) {
                throw new BizException(500, "bloom rebuild failed", ex);
            }
            return 0;
        }
    } finally {
        rebuilding.set(false);
    }
}
```
`CodeBloomFilter.java:134-180`

四层设计逐层看。第一层，`AtomicBoolean` 加 `compareAndSet` 保证一个进程同时只有一个 rebuild（D-C2-13）：定时任务和管理接口撞车时后者收到 409，定时触发则静默放弃并打点 busy。第二层，「旁路建新再原子替换」：新过滤器在旁路变量 `fresh` 上灌数据，`filter = fresh` 是一次 volatile 写，对读者原子；查询方法先读进局部变量再用，整个替换期间查询要么看旧的要么看新的，没有中间态。第三层，失败恢复旧 filter：rebuild 失败说明 DB 读挂了，但旧 filter 里的内容仍然有效，恢复它而不是降级 fail-open，注释点明「绝不把已加载的过滤器降级成 fail-open」，防止一次 rebuild 失败造成可用性倒退（fail-open 后所有查询都撞 DB）。第四层，`finally` 释放重建锁，异常路径也不会卡死后续 rebuild。

失败出口的文案在 S/N 波 N1 里收紧过（旧版把 `ex.getMessage()` 拼进异常 message 又在 warn 里打了全栈——双记且泄漏细节）：现行版 warn 只记一行消息不带栈；手动触发失败抛 `BizException(500, "bloom rebuild failed", cause)`——wire 文案是**固定字符串**，底层原因只经 cause 链进 handler 的那一次全栈日志（2.1 的 N1 纪律）。客户端看到的永远是同一句，排障的人拿 cid 去日志里对那一栈。

### 5.5 查询与写入的 fail-open 语义

```java
public boolean mightContain(String code) {
    BloomFilter<String> current = filter;
    if (!properties.getBloom().isEnabled() || !ready || current == null) {
        return true;
    }
    return current.mightContain(code);
}

public void put(String code) {
    BloomFilter<String> current = filter;
    if (current != null) {
        current.put(code);
    }
}
```
`CodeBloomFilter.java:186-200`

`mightContain` 在禁用、未就绪、播种失败三种状态下都返回 true（fail-open），判定链第 2 步（:615-618）因此放行，请求继续走空值缓存与 DB。唯一会 404 的路径是「过滤器就绪且负答案」，而这个负答案是权威的。`put` 在 filter 缺席时是空操作，创建路径不因 Bloom 挂掉而失败。两方法都先把 `filter` 读进局部变量（:187、:197），这是 volatile 字段的标准读法：一次读、后续用同一引用，避免方法执行中字段被 rebuild 替换导致的两次读不一致。

### 5.6 BloomAdminController：手动触发与权限链

```java
@PostMapping("/rebuild")
public ApiResponse<Map<String, Object>> rebuild() {
    AuthUser user = requireUser();
    UserAccount account = userAccountMapper.selectById(user.getUserId());
    // pre-V8 rows have a null role, which reads as MEMBER
    if (account == null || !"ADMIN".equals(account.getTenantRole())) {
        throw new BizException(403, "only a tenant ADMIN can trigger a bloom rebuild");
    }
    long codes = codeBloomFilter.rebuildManual();
    return ApiResponse.ok(Map.of("result", "ok", "codes", codes));
}
```
`BloomAdminController.java:30-40`

权限是两层。第一层在 `SecurityConfig`：这个路径没有匿名放行条目，落入 `anyRequest().hasRole("USER")`，API Key 天然 403（javadoc :18-21）。第二层在方法体内查 `tenant_role`，只有 ADMIN 放行（:35-37），pre-V8 的空角色按 MEMBER 对待。返回值是本次播种的码数，对应 `rebuildManual` 的返回值（`CodeBloomFilter.java:125-127`）。注意范围：这个接口只重灌被请求命中的那个进程，多实例部署要么逐个打，要么等各自的定时任务。

### 5.7 两个 IT 的验收点

`BloomDisabledAtStartupIT`（bloom.enabled=false 的独立 Spring 上下文）验证三件事：启动后 `isReady` 恒 false 且 `bloom.loaded`、`bloom.rejected` 两个计数器从未注册（:65-68）；不存在的码 404 走纯 DB 路径（:70）；已存在的码正常 302（:74）。它的第二个用例 `absentNegativeCacheStopsRepeatedDbReadsForTheSamePhantomCode`（:78-97）用 `@SpyBean` 包住 `ShortLinkRowLoader` 数行读：第一个幽灵码请求读到 DB（:87-89），随后 5 次重复请求行读计数不增长（:91-95），`absent.hit` 计数器确认走了空值缓存（:96）。这是空值缓存「挡重复 DB miss」的直接证据。

`BloomRebuildIT` 验证 rebuild 的功能与权限：ADMIN 的 JWT 触发后 filter 保持就绪、重建包含新建的码（:86-92）；MEMBER 403（:95-96）；API Key 无论 scope 403（:98-103）。第二个用例删码后重建（:107-121）：逻辑删除不改行数（:112-114），重建后 `mightContain` 对已删码仍是 true（:118-120），断言注释点名 D-C2-11。这把我们引向最后一个设计问题。

## 6. 技术原理补充

- **Bloom 过滤器（通用技术）**：空间高效的概率集合成员判断。为什么需要它：空值缓存要等一次真实 DB miss 才能挡下一次，首个幽灵码仍然穿到 DB；Bloom 在 DB 之前用几百 KB 内存挡掉绝大多数「从未存在」。不用的后果：扫描器每个新码都打一次 DB。优缺点：负答案零误判、空间极小；正答案有误报、不能删除元素、容量需预估。本项目怎么用：只存短码，负答 404，正答落正常路径。
- **fpp 参数语义（框架知识）**：fpp 是期望误报率不是保证值，Guava 按 `expectedInsertions` 与 `fpp` 选最优位数组与哈希数。容量打满后误报率上升但正确性不变（误报只是多一次 DB miss，:32-33 注释「correctness unaffected」）。调参方向：内存紧张降 expectedInsertions 换更高 fpp，DB 压力大加 expectedInsertions 保 fpp。
- **空值缓存与 Bloom 的分层（本项目实现）**：两者都挡「不存在的码」，层次不同。Bloom 是权威否定，代价是进程内结构、多实例要靠广播与 rebuild 对齐（漏广播窗口内可能假阴性）；空值缓存是共享的 Redis 结构，确认信息来自 DB 本身，代价是要先付一次 DB miss。判定链把它们排在第 2、3 步，前者的漏网由后者兜住，后者的首miss由前者尽量挡掉。README :400-403 的顺序「Bloom、空值缓存、L1/L2」就是这层递进。
- **删码不可撤销的诚实边界（本项目实现）**：经典 Bloom 没有删除操作：清零一个码的位会把共位的其他码也拖成假阴性，计数 Bloom（Counting Bloom）能删但要 4 倍空间，本项目不引入。于是删除短链后 filter 仍说「可能存在」，代价只是该码多一次 DB miss 后 404（且空值缓存 60 秒内接管）。`selectAllCodes` 故意绕过 `@TableLogic` 装入逻辑删码（`ShortLinkMapper.java:13-23`），rebuild 的 yml 注释（:126-128）写明「rebuild 是容量/fpp 重置与漏广播修复，不是删码撤销」。已删码留在 filter 里还有个好处：它的 `uk_code` 还占着，自定义码撞码要在 DB 层拒绝而不是让 Bloom 放行后撞出 409。

## 7. 可视化

按顺序看两张图：

1. [../../diagrams/archify/html/13-cache-hit-miss-sequence.html](../../diagrams/archify/html/13-cache-hit-miss-sequence.html)：命中与未命中时序。本课节点对应：Bloom 闸 :615-618、空值缓存闸 :623-626、`markAbsent` :653、`clearAbsent` :959。
2. [../../diagrams/archify/html/06-redis-data-flow.html](../../diagrams/archify/html/06-redis-data-flow.html)：`linkforge:absent:{code}` 的写入方与读取方，以及 `linkforge:bloom:put` 频道从 `publishBloomPut` :44 到 `applyRemotePut` :68-77 的路径。

`CodeBloomFilter` 与 `BloomAdminController` 的类位置见 [../../diagrams/archify/html/02-function-map.html](../../diagrams/archify/html/02-function-map.html)（已通过）。

## 8. 项目中的真实执行过程

场景一：扫描器请求一个从未存在的码 `ghost001`，Bloom 已就绪。

1. 限流放行（:604-610）；Bloom `mightContain("ghost001")` 返回 false（:615），打点 `bloom.rejected`（:616），抛 404（:617）。全程未触 Redis 缓存与 MySQL。
2. 误报情形：若 Bloom 碰巧正答（fpp 万分之一概率），请求走到空值缓存（:623）未命中，singleflight 行读返回 null（:650），`markAbsent` 写入 60 到 90 秒的哨兵（:939-952），404。此后同码重复请求在空值缓存就被挡住（:623-626），不再碰 DB。

场景二：Bloom 关闭（环境变量 `LINKFORGE_BLOOM_ENABLED=false`）。

1. `load()` 的禁用分支直接返回（:82-86），filter 永远缺席，`mightContain` 恒 true（:188-191）。
2. 每个幽灵码的第一次请求穿到 DB（`BloomDisabledAtStartupIT.java:87-89` 验证），第二次起被空值缓存挡住（:91-95 验证行读不增长）。
3. 用户创建 `ghost001`：`doCreate` 尾段 `clearAbsent`（:268）删掉哨兵，创建后的下一次跳转直达 L1/L2/行读，不再被空值缓存误挡。

场景三：运维手动 rebuild。ADMIN 的 JWT 打 `POST /api/admin/bloom/rebuild`（`BloomAdminController.java:30-40`），`rebuildManual` 进 `rebuild(true)`，CAS 抢锁（:140）、旁路建新（:148-150）、先发布后播种（:153-159）、打点 ok 返回码数（:161-164）。期间并发创建的 put 直接落进新 filter；若 DB 读失败，旧 filter 原样恢复（:168-169），接口返回 500（:172-174），线上过滤能力无倒退。

## 9. 为什么这样设计

**为什么两个「挡不存在」的结构都要。** 单用 Bloom：进程内结构在多实例下有漏广播窗口，漏掉的码在本实例上是假阴性（404），直到 rebuild 才修复，窗口最长 24 小时（定时周期）。单用空值缓存：每类新幽灵码的第一个请求必穿 DB。组合后：Bloom 挡大头（含误报），空值缓存兜误报与漏广播（首次 DB miss 后 60 到 90 秒内免 DB），两者的缺陷互为补丁。README :391 的「五层省的是」口径里，「不存在码的 DB miss」由这两层共同负责。

**为什么播种失败降级 fail-open，rebuild 失败却恢复旧值。** 播种发生在启动时，此刻没有旧 filter 可言，半灌的过滤器比没有更危险（它可能对大量真实存在的码给出假阴性，把正常跳转 404 掉），所以回退到「没有 filter」这个已知安全态。rebuild 发生在运行中，旧 filter 虽然可能漏了些新码（广播丢失的那批），但它对既有码的判断全部有效，恢复它保住的是已有的防护能力。两个分支的方向统一成一句话：永远朝着「更接近正确」的状态退。

**为什么 ADMIN 才能触发 rebuild。** rebuild 是一次全表 SELECT 加数秒 CPU（逐码哈希），管理接口不能留给普通成员误触；409 语义（:143）告诉运维「正在重建，别催」。推断：单进程 rebuild 加上多实例需要逐个触发的现实，说明这个接口定位是运维工具而非用户功能，权限收紧与范围诚实（javadoc :20 明说 "rebuilds THIS process's filter only"）一致。

## 10. 本节总结

- 空值缓存三方法 :926-964：查询、写入（60+30 抖动）、清除，配置小于等于 0 时整体透明；只收 DB 确认不存在的码，分支结构保证禁用与过期进不来；创建尾段 `clearAbsent` :268 清窗口。
- Bloom 语义：负答案权威可直接 404，正答案含 fpp 误报只是多一次 DB miss；禁用、未就绪、失败一律 fail-open 返 true。
- 播种顺序：订阅先于播种（:56-65，D-C2-5），发布先于逐码 put（:91-95 与 :151-154，A9 audit S1）；失败回退到无 filter 态。
- rebuild：CAS 单飞（:140-146）、旁路建新原子替换（:153-154）、失败恢复旧 filter（:168-169）、定时 24 小时加 ADMIN 手动（:116-127）。
- 删码不可撤销：逻辑删码被 `selectAllCodes` 特意重灌（`ShortLinkMapper.java:13-23`），rebuild 不是删除操作。

## 11. 课后练习

**练习 1（概念理解）**：禁用（status=0）与过期（expireAt 已过）的码为什么绝不进空值缓存？从代码找出保证。

答案解析：语义与机制两层。语义上，`markAbsent` 的唯一调用点在 `resolveTarget` :650-654，前置条件是行读返回 null，即 DB 里根本没有这一行；禁用与过期的行存在，走 :656-661 的异常分支，不在 `markAbsent` 的可达路径上。机制上，若强行把禁用码写进空值缓存，重新启用（`enable`，:527-529）后跳转仍要 404 到 TTL 过期，违背「状态变化下一个请求生效」的模块 7 契约。过期码同理：TTL 短的空值缓存会把刚过期的码再挡 60 秒，业务上等于偷偷延长有效期。

**练习 2（源码定位）**：「先发布后播种」在 `load()` 与 `rebuild()` 各占哪一行？如果调换顺序，举出一条会丢失的 put 的完整时序。

答案解析：`load()` 在 `CodeBloomFilter.java:95`（`filter = fresh` 先于 :97-99 的播种循环），`rebuild()` 在 :154（先于 :156-159）。调换后时序：t1 播种循环开始读 `selectAllCodes()`；t2 另一请求创建短链 `newCode`，`doCreate` 尾段调 `codeBloom.put("newCode")`（`ShortLinkService.java:266`），此刻 `filter` 还是旧引用（load 场景下是 null），put 落空或落进即将被替换的旧 filter；t3 播种完成，`filter = fresh`，`newCode` 不在 fresh 里，本实例对它持续假阴性直到下次 rebuild。注释 :91-94 与 :151-152 分别点名 A9 audit S1。

**练习 3（执行过程）**：Bloom 开启且就绪时，请求一个「三小时前被逻辑删除」的自定义码，完整推演判定链走到哪一步、返回什么、各计数器怎么动。

答案解析：限流放行；Bloom 说可能存在（删码仍在 filter 里，`ShortLinkMapper.java:13-21` 的设计），正答不拦（:615-618 不触发，`bloom.rejected` 不动）；空值缓存若 60 到 90 秒内挡过它会直接 404（`absent.hit` 加一）；否则行读返回 null（`deleted=1` 被 MyBatis-Plus 逻辑删过滤），`markAbsent` 写哨兵（`absent.put` 加一），404。此后 60 到 90 秒内重复请求全在空值缓存挡住。这道题的要点：删码的成本从「永久 Bloom 误判」被空值缓存压到「每 60 到 90 秒一次 DB miss」。

**练习 4（设计分析）**：`BloomRebuildIT` 断言 rebuild 后 `mightContain(已删码)` 为 true（:118-120）。有人提议 rebuild 时跳过逻辑删码，让已删码「真正消失」。分析这个提议破坏了什么。

答案解析：破坏两层。其一，唯一索引层：已删码的 `uk_code` 仍在（`BloomRebuildIT.java:112-114` 验证行数为 1），如果新码撞上一个已删码，Bloom 放行后 DB 插入撞唯一索引，自定义码返回 409（`ShortLinkService.java:239-246`），这个结果碰巧没错；但跳转场景里，Bloom 负答会让已删码在 :615-618 直接 404，与现在「空值缓存或行读 404」结果相同。真正破坏的是其二：`selectAllCodes` 的 javadoc（:13-21）写明码位不可复用的前提是已删码保持 Bloom 正，若跳过它们，`rebuild` 就成了隐式的「删码撤销操作」，与 yml 注释 :126-128「rebuild 不是删码撤销」的明文口径冲突，运维对 filter 内容的推理模型随之失效。这个提议省下的只是已删码每分钟级的零星 DB miss，代价是口径复杂化，不值得。

## 12. 与后续课程的联系

- 8.4 课讲 singleflight：本课 :649 的行读就是 singleflight 的加载器，Bloom 误报的幽灵码也会在那里被合并成一次 DB miss。
- 9.1 课的滑动窗口是判定链第 1 步，9.2 课的幂等键与 API Key 配额把 8.1 键表里剩下的两族键讲透。
- 15.1 课的测试金字塔会把 `CodeBloomFilterTest`、`BloomRedirectIT`、`BloomBroadcastIT` 归入各自的层次；advanced/12-dependency-failure-handling.md 汇总本课全部 fail-open 分支的故障演练对照。
