# 7.1 跳转判定链：12 步总顺序与三种入口

> 本课回答一个问题：浏览器敲下 `/r/Ab3xK9m` 之后，系统按什么顺序做判断？每一步失败时返回什么？为什么这些失败语义互不吞并？

## 1. 本节学习目标

学完本课，你应该能：

1. 按顺序背出跳转判定的 12 步，并说出每一步在哪个类的哪个方法、哪几行。
2. 说出 GET、HEAD、unlock 三种入口共用什么、差异在哪。
3. 解释 `RedirectOutcome` 这个 sealed interface（密封接口）为什么把 404/410/429 留在异常、把四类门禁结果放进返回值。
4. 解释「每个请求都必须读一次 DB 行」这个取舍，以及缓存为什么只许加速、不许改判定，并指出三处落地位置。

## 2. 前置知识

- HTTP 状态码语义：302（临时跳转，带 `Location` 头）、403（服务器拒绝）、404（资源不存在）、410（资源已永久消失）、429（请求过多）。
- 模块 6.1 的 `ShortLink` 实体：`code` 短码、`originalUrl` 目标、`status` 状态（1 为启用）、`expireAt` 过期时间、`accessPasswordHash` 密码哈希。
- 模块 2.1 的 `BizException` 家族与模块 3.2 的过滤器链位置：业务异常带业务码，`GlobalExceptionHandler` 把它翻译成 HTTP 状态码。
- Java 17 的 sealed interface（密封接口）：接口声明 `sealed` 后，只允许列出的类实现它，本课用它表达「结果只可能是这几种」。
- Bloom filter（布隆过滤器）：「可能存在 / 一定不存在」的概率结构，负答案可靠，正答案可能有误报（模块 8.3 细讲）。

## 3. 本节涉及的源码

| 文件 | 行数 | 职责 | 阅读顺序 |
|----|----|----|----|
| `src/main/java/com/linkforge/link/controller/RedirectController.java` | 123 | 三种 HTTP 入口 + 判定结果渲染 | 1 |
| `src/main/java/com/linkforge/link/service/RedirectOutcome.java` | 28 | 判定结果的类型契约 | 2 |
| `src/main/java/com/linkforge/link/service/ShortLinkService.java` | 1025 | 主角：resolveForGet :565-585、resolveForProbe :591-593、resolveTarget :601-712 | 3 |
| `src/main/java/com/linkforge/link/support/ClickFilter.java` | 111 | B2 预取/预览 bot 识别（判预取头 :74-88、判 bot token :61-68） | 4 |
| `src/main/java/com/linkforge/link/support/SingleFlight.java` | 120 | 并发同码合并读（run :57-100） | 5 |
| `src/main/java/com/linkforge/link/support/ShortLinkRowLoader.java` | 23 | code → 行查询的适配 bean（load :20-22） | 6 |
| `src/main/java/com/linkforge/link/support/UrlLocalCache.java` | 96 | 进程内 L1 缓存，只存 URL 字符串（getIfPresent :76-83） | 7 |

为什么这个顺序读：先看入口和结果类型，建立「控制器只做翻译」的心智；再进 `ShortLinkService` 读判定链主干；最后读三个 support 组件补齐细节。

## 4. 类的定位

- `RedirectController` 是 `link.controller` 包里唯一的公开跳转入口。它和 `ShortLinkController`（管理接口）共用一个 `ShortLinkService`，但面向的角色完全不同：前者服务匿名浏览器，后者服务登录用户。
- `ShortLinkService` 是全项目的域服务核心（17 个注入依赖在 :100-116）。跳转侧只用到它的三个公开方法：`resolveForGet`、`resolveForProbe`、`unlock`。
- `RedirectOutcome` 是 service 与 controller 之间的结果契约：不继承异常、不带 HTTP 状态码，只描述「判定走到哪类结果」。
- `ClickFilter`、`SingleFlight`、`ShortLinkRowLoader`、`UrlLocalCache` 都在 `link.support` 包，是判定链调用的纯组件。包结构全景见 [../../diagrams/archify/html/03-package-map.html](../../diagrams/archify/html/03-package-map.html)。

## 5. 核心源码详解

### 5.1 三种入口：GET、HEAD、unlock

三个方法都挂在 `RedirectController` 上：

```java
@GetMapping("/r/{code}")
public ResponseEntity<String> redirect(
        @Parameter(description = "short code, case-sensitive (exact match)")
        @PathVariable String code, HttpServletRequest request) {
    return render(shortLinkService.resolveForGet(code, request), code, true);
}

@RequestMapping(value = "/r/{code}", method = RequestMethod.HEAD)
public ResponseEntity<String> probe(
        @Parameter(description = "short code, case-sensitive (exact match)")
        @PathVariable String code, HttpServletRequest request) {
    return render(shortLinkService.resolveForProbe(code, request), code, false);
}
```
`RedirectController.java:36-48`

GET 入口调 `resolveForGet`，`withBody=true`。HEAD 入口调 `resolveForProbe`，`withBody=false`：HTTP 规定 HEAD 响应没有正文，但状态码和响应头必须与 GET 一致，所以两类入口共用同一条判定链、同一套状态码。第三个入口 `POST /r/{code}/unlock` 在 :52-82，它是密码解锁表单的提交点，自己组装响应（Set-Cookie 加 303），安全响应头与 `render` 同一套，细节留给 7.2 课。

两个 service 方法也很短：

```java ignore-check
public RedirectOutcome resolveForGet(String code, HttpServletRequest request) {
    RedirectOutcome outcome = resolveTarget(code, request, true);
    ... // B2 分类与点击记录在 :567-584，见 5.4 末段
}

public RedirectOutcome resolveForProbe(String code, HttpServletRequest request) {
    return resolveTarget(code, request, false);
}
```
`ShortLinkService.java:565-593`（示意：resolveForGet 的 B2 与记录段以省略号折叠，其余逐字）

差异收敛成一个布尔参数 `warnContinueAllowed`：GET 允许用户在 WARN 警示页点「仍要继续」，HEAD 不允许（:589 注释明确「`?lf_continue` 是 GET 专属能力」）。此外 GET 会做 B2 分类和点击记录，HEAD 永不记录。

### 5.2 render：判定结果到 HTTP 响应的翻译

```java ignore-check
private ResponseEntity<String> render(RedirectOutcome outcome, String code, boolean withBody) {
    if (outcome instanceof RedirectOutcome.Redirect redirect) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(redirect.url()))
                .build();
    }
    if (outcome instanceof RedirectOutcome.Blocked) {
        ... HttpStatus.FORBIDDEN ... GatePages.block() ...
    }
    if (outcome instanceof RedirectOutcome.Locked) {
        ... withBody ? HttpStatus.OK : HttpStatus.UNAUTHORIZED ...
    }
    if (outcome instanceof RedirectOutcome.Warn warn) {
        ... HttpStatus.OK ... GatePages.warn(...) ...
    }
    throw new IllegalStateException("unknown redirect outcome " + outcome.getClass());
}
```
`RedirectController.java:85-117`（示意：Blocked 与 Warn 分支以省略号折叠，其余逐字）

四种结果对应四种响应：Redirect 返回 302 加 `Location`（:86-90）；Blocked 返回 403 加拦截页（:91-97）；Locked 在 GET 下是 200 解锁页、HEAD 下是 401（:98-105）；Warn 返回 200 警示中间页（:106-115）。所有 HTML 响应都带 `Cache-Control: no-store` 和 CSP 头。末尾的 `throw new IllegalStateException`（:116）不是防御性废话：如果未来有人给 `RedirectOutcome` 加了新分支却忘了改 `render`，请求会显式炸出来，而不是静默漏掉。

### 5.3 RedirectOutcome：sealed interface 的领域建模价值

```java
public sealed interface RedirectOutcome {

    record Redirect(ShortLink link, String url, String variantKey) implements RedirectOutcome {
    }

    record Blocked() implements RedirectOutcome {
    }

    record Locked(ShortLink link) implements RedirectOutcome {
    }

    record Warn(String code, String url, String asciiHost) implements RedirectOutcome {
    }
}
```
`RedirectOutcome.java:9-27`

这个接口的价值在两类结果的分界上。接口 javadoc（:6-7）写明：404/410/429 留在异常里，四类门禁结果用返回值。404/410/429 是「这次跳转失败，没有页面可渲染」，直接抛 `BizException` 交给全局异常处理器，所有接口共享同一套错误出口；Redirect/Blocked/Locked/Warn 是「判定成功，但要渲染不同页面」，它们是跳转业务的正常分支，不该混进异常通道。

sealed 关键字保证分支穷尽：编译器知道实现类只有四个，`render` 的 `instanceof` 逐段处理后每段类型自带字段（Redirect 带 `url` 和 `variantKey`，Warn 带 `asciiHost`）。新增一种门禁结果必然编译报错，逼着开发者同时改 `render`。

### 5.4 resolveTarget：12 步判定总顺序

`resolveTarget`（`ShortLinkService.java:1061` 起）是 GET 和 HEAD 共用的判定链。下表是 12 步的完整展开，行号为 3.0.0 时点位置，每步只列「失败出口」：

| 步 | 判定 | 代码位置 | 失败出口 |
|----|----|----|----|
| 1 | 按 IP 滑动窗口限流 | :1064-1070 | 429 + Retry-After（`RateLimitedException`） |
| 2 | Bloom filter 负答（码从未创建） | :1075-1078 | 404（`BizException`），不碰 MySQL |
| 3 | 空值缓存命中（DB 近期确认不存在） | :1083-1086 | 404 |
| 4 | L1 进程内缓存查 URL | :1091 | 无失败出口，只影响 URL 来源 |
| 5 | L2 Redis 查 URL | :1095-1104 | Redis 异常 fail-open 继续 |
| 6 | singleflight 合并并发后读 DB 行 | :1109-1114 | 行不存在 → 写空值缓存 + 404 |
| 7 | 状态与过期判定 | :1116-1121 | 禁用 404；过期 410 |
| 8 | URL 可渲染校验 | :1123-1131 | 行脏 410；缓存值脏 410 |
| 9 | B9 域名门禁 BLOCK（含恶意 feed 判定，批次 3b） | :1144-1148 | 403 拦截页（`Blocked`） |
| 10 | 密码门 | :1150-1155 | 解锁页（`Locked`，GET 200 / HEAD 401） |
| 11 | B9 域名门禁 WARN | :1156-1166 | 警示页（`Warn`，200；`lf_continue=1` 可继续） |
| 12 | 条件收窄 + 抽签 → 返回后 resolveForGet 才做 B2 分类 → 记账 | :1168-1171 与 :1029-1050 | 无失败；可能不记点击 |

逐步看关键片段。第 1 步限流在方法最前面，超限直接抛 `RateLimitedException`，算法在 9.1 课专讲。第 2 步的注释讲清了 Bloom 的地位：负答案是权威的（这个码从未被创建），直接 404，省掉一次数据库往返；正答案可能是误报，落到后面的缓存和 DB 路径兜底。第 3 步空值缓存（B7）只存「DB 确认过不存在」的码，注释特意强调：禁用（404）和过期（410）的行还在库里，绝不能进这个缓存，否则状态变化要等 TTL 才生效。

第 4、5 步是两级缓存。L1 是本进程的 Caffeine 缓存（`UrlLocalCache`），L2 是 Redis。注意 L1 命中省的只是 Redis 往返，下面的 DB 行照样读。第 5 步 L2 读取失败时打点 `cache.failopen{op=l2_get}` 然后继续，降级到 singleflight 行读，绝不 500。

第 6 步是唯一的 DB 读：`ShortLink link = singleFlight.run(code, () -> rowLoader.load(code))`，行不存在时 `markAbsent` 写空值缓存再抛 404。`SingleFlight`（A10）让 N 个并发请求同一个码只打一次数据库。它不是缓存：飞行结束条目即删，没有 TTL，没有「上次结果」；超时或禁用时各读各的，只多打点不报错。

第 7、8 步都在行上做文章。禁用 404、过期 410，两个状态码语义不同。URL 校验做两次：先验行的 `originalUrl`，再验准备使用的缓存值，脏行 410 与缓存无关。第 9 到 11 步是三道门：第 9 步 B9 BLOCK 直接返回 `Blocked`，且发生在密码门之前（D-Bw345-51：BLOCK 先于一切）——批次 3b 后这一步的判定输入合并了恶意主机 feed 的本地快照：命中 feed 与命中静态名单走同一个 BLOCK 出口（来源经 `Source.MALICIOUS_FEED` 区分，7.2 详讲）；第 10 步密码门读行的 `accessPasswordHash`，注释写明「设置、清除、修改密码在下一个请求立即生效，与缓存 TTL 无关」；第 11 步 WARN 只在 GET 且没带 `lf_continue=1` 时拦，带了就直接放行并打点 `phishing.continue`。

第 12 步散在两处。`resolveTarget` 的收尾按「有变体先 MatchRule 收窄再抽签、无变体用主 URL」返回 `Redirect`：`picked != null ? picked.getTargetUrl() : originalUrl`（收窄与抽签细节在 7.3 课）。回到 `resolveForGet` 后的 B2 分类与点击记录：

```java
Optional<ClickFilter.SkipReason> skip = clickFilter.classify(request);
if (skip.isPresent()) {
    meterRegistry.counter("linkforge.click.skipped", "reason", skip.get().tag()).increment();
    return redirect;
}
try {
    clickRecordService.recordClick(redirect.link(), request, redirect.variantKey());
} catch (DataAccessException ex) {
    // fail-open (D-C5-1/6): the 302 must not depend on the click write; the tx
    // inside recordClick has already rolled back when we get here, so the catch
    // sits outside any transaction and the jump still counts as a redirect.
    meterRegistry.counter("linkforge.click.record.failopen").increment();
    log.warn("click record failed for {}: {}", code, ex.getClass().getSimpleName());
}
meterRegistry.counter("linkforge.redirects").increment();
```
（`ShortLinkService.java` 的 resolveForGet 段）

预取和预览 bot 拿到一模一样的 302 但什么都不记。点击写库失败时 catch 打点继续：注释讲明 catch 在事务外的原因，302 不能依赖点击写成功。

## 6. 技术原理补充

- **sealed interface（Java 语言知识）**：密封接口列出全部实现类，编译器由此保证穷尽性；不用它则新增分支时编译器不提醒，漏改的分支运行时才炸。本项目在 `RedirectOutcome` 用四个 record 表达四种门禁结果。
- **单飞（singleflight，本项目实现）**：并发同 key 调用共享一次执行，Go 语言社区的概念，本项目用 `CompletableFuture` 加 `ConcurrentHashMap` 实现进程内版本，8.4 课专讲。
- **失败语义互不吞并（设计原则）**：每一步的失败有自己的出口和状态码，后面的步骤永远看不到前面的失败。禁用链的 404 与不存在链的 404 走不同分支（禁用的不进空值缓存），过期 410 不与脏目标 410 混淆（前者是行状态，后者是内容校验）。判定链写成一条直线加提前返回而非嵌套 try-catch，正是为了让每步出口一眼可见。
- **L1/L2 两级缓存（框架与项目混合）**：Caffeine 是 JVM 进程内缓存库，Redis 是跨实例共享缓存；8.2 课细讲双删与广播失效，本课只需记住 L1 只存 URL 字符串。

## 7. 可视化

按顺序看两张图：

1. [../../diagrams/archify/html/12-redirect-decision.html](../../diagrams/archify/html/12-redirect-decision.html)：12 步判定链的交互流程图。节点对应：步骤 1 对应 `resolveTarget` :604-610，步骤 6 对应 `SingleFlight.run` :57-100，步骤 9-11 对应三道门 :684-704，步骤 12 对应 `VariantPicker.pick`。
2. [../../diagrams/archify/html/13-cache-hit-miss-sequence.html](../../diagrams/archify/html/13-cache-hit-miss-sequence.html)：L1/L2/DB 三层读路径的时序，对应步骤 4-6。

Mermaid 备用图：[../../diagrams/mermaid/redirect-decision-chain.md](../../diagrams/mermaid/redirect-decision-chain.md)。备用图把 L2 画进行读步内，本课把 L2 单列为第 5 步，两者顺序一致，只是分组粒度不同。

## 8. 项目中的真实执行过程

场景一：普通链的第二次访问（缓存已热）。

1. 浏览器 `GET /r/Ab3xK9m`，`RedirectController.redirect` :36-41 进入，`resolveForGet` 调 `resolveTarget(code, request, true)`（:566）。
2. 限流放行（:604-610），Bloom 正答（:615），空值缓存未命中（:623），L1 未命中但 L2 命中拿到 URL（:631-644）。
3. singleflight 行读拿到状态 1、未过期的行（:649-661）；行 URL 与缓存 URL 都可渲染（:663-671），L1 回填（:675-677）。
4. 三道门全过：无 BLOCK、无密码、无 WARN；无 variants，返回 `Redirect(link, cached, null)`（:709-711）。
5. B2 分类说这是真人，`recordClick` 同事务写 `lf_click_log` 与 outbox 行（:574），打点 `redirects`（:582）；`render` 输出 302 加 `Location`（`RedirectController.java:86-90`）。

场景二：密码链的第一次访问。前四步同上，在第 10 步密码门停下：行里有 `accessPasswordHash` 且请求没带有效 cookie，返回 `Locked`（:694），`render` 输出 200 加解锁页 HTML（`RedirectController.java:98-105`）。用户提交表单走 unlock（7.2 课），拿到 cookie 后重新 GET，这次第 10 步通过，继续第 11、12 步直到 302。

## 9. 为什么这样设计

**每请求必读 DB 行的强一致取舍。** 判定链有 L1、L2、空值缓存三层，却坚持每请求读一次 DB 行。理由写在两处：`UrlLocalCache` 的 javadoc（:17-20）说「命中绝不跳过 singleflight 行读，状态与过期保持 DB 权威」；`resolveTarget` 的注释（:645-648）补了并发视角：跟车者看到的行最多比到达时刻旧一个查询延迟（毫秒级），远小于既有的 1 秒双删窗口。代价是每请求一次主键级查询，换来的是禁用、改密、改目标在下一个请求立即生效。这是一致性换吞吐的明确取舍，不是疏忽。

**缓存只许加速、不许改判定，三处落地。**

1. URL 可渲染校验：先验行再验缓存值，脏行必须 410，陈旧缓存条目不得掩盖（:663-671，注释 :663-664 点名 legacy-dirty 契约）。
2. B9 域名门禁：判的是行的 URL（:684），注释 :682-683 写明「陈旧缓存条目绝不能掩盖 DB 今天说这个链指向哪」。
3. variants 抽签结果压过缓存 URL：注释 :705-707（D-C3-8/10）说「缓存始终是单 URL 提示，对分流链永远不是 Location 真相」，7.3 课展开。

推断：这套「行权威 + 缓存提示」分层是 Bloom（负答权威）与缓存（正答仅提示）两种可靠性等级的组合运用，与 `CodeBloomFilter` 的 fail-open 设计（8.3 课）同属一个思路。

## 10. 本节总结

- 三入口共用一条判定链：GET 记点击且允许 WARN 继续，HEAD 全不记，unlock 是密码表单提交点。
- `resolveTarget` 12 步：限流 → Bloom → 空值缓存 → L1 → L2 → 行读 → 状态/过期 → URL 校验 → B9 BLOCK → 密码门 → B9 WARN → 抽签（:708）→ 返回后 resolveForGet 才做 B2 分类（:568-572）与记账。
- 失败分两类出口：404/410/429 走异常，四类门禁结果走 `RedirectOutcome` sealed interface。每请求必读 DB 行；缓存三层只加速不改判定，三处代码可查证。

## 11. 课后练习

**练习 1（概念理解）**：为什么禁用（status≠1）的短链不进空值缓存，而 DB 确认不存在的码会进？

答案解析：看 :620-626 的注释。空值缓存的语义是「这个码在 DB 里确认不存在」，不存在的码在可预见未来也不存在（重建要走 create，create 会调 `clearAbsent` 清缓存）。禁用链的行还在库里，管理员随时可能重新启用；若 404 进了空值缓存，启用后要等 TTL（默认 60 秒加抖动）才恢复跳转。这就是注释说的「禁用（404）和过期（410）的行仍存在，必须立即再次生效」。

**练习 2（源码定位）**：找到「B9 BLOCK 发生在密码门之前」的代码证据，并说出这个顺序的一个安全收益。

答案解析：`ShortLinkService.java:684-688` 是 BLOCK 判定，:691-695 是密码门，BLOCK 在前。收益（推断）：恶意链接即使加了密码，也无法借解锁流程把用户引向目标；403 拦截页也从不回显目标 URL（`GatePages.block()` :19-34 正文无任何目标信息）。

**练习 3（执行过程）**：一个 HEAD 请求打向带 WARN 名单的短链，且 URL 带 `?lf_continue=1`。最终响应是什么状态码？

答案解析：HEAD 走 `resolveForProbe`（:591-593），`warnContinueAllowed=false`，第 11 步（:696-704）不进 continue 分支，直接返回 `Warn`。`render` 收到 `withBody=false`，Warn 分支仍返回 200，只是无正文（`RedirectController.java:106-115`）。`lf_continue` 参数被完全忽略，它按 :589 注释是 GET 专属能力。

**练习 4（设计分析）**：如果把「每请求必读 DB 行」改成「L1 命中就直接 302」，会快多少无法确定（不写数字），但会破坏哪些已写明的契约？

答案解析：至少破坏三处。其一，`UrlLocalCache` javadoc :17-20 的承诺（状态/过期保持 DB 权威）失效，禁用与过期判定退化为 TTL 延迟。其二，密码门读的是行的 `accessPasswordHash`（:691），跳过行读等于跳过密码门。其三，:645-648 注释给出的一致性界（最多旧一个查询延迟）消失，改成 L1 TTL（默认 10 秒）级窗口，超过 :647 注释自认的 1 秒双删窗口。要点：性能取舍必须对着写明的契约做，不能只看快慢。

## 12. 与后续课程的联系

- 7.2 课深入第 9、10、11 步（解锁协议、签名 cookie、B9 名单与三张 HTML 页）；7.3 课深入第 12 步（权重抽签、抽签时机、variant 进统计）。
- 8.2 课讲 L1/L2 的写穿双删与广播失效，8.3 讲 Bloom，8.4 讲 singleflight（步骤 2-6 在那里各有专讲）；9.1 课讲第 1 步的滑动窗口限流实现。
