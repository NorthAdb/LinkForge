# 7.3 A/B 权重分流：VariantPicker 抽签与统计分桶

> 本课回答三个问题：一条短链怎么按 70/30 权重把流量分到两个目标？抽签为什么发生在全部门禁之后？统计侧怎么知道这次点击属于哪个桶？

## 1. 本节学习目标

学完本课，你应该能：

1. 手算一组权重的抽签结果，解释 `threshold = r × total` 与半开区间的对应关系。
2. 说出抽签时机为什么必须在全部门禁通过之后，并指出代码注释依据。
3. 解释「非空 variants 压过缓存 URL」的实现与动机。
4. 描述 variant 键从抽签到点击统计的完整数据流。
5. 说清这套分流与「按地域定向」的边界：它不做什么。

## 2. 前置知识

- 模块 6.1 的 `lf_link_variant` 表：一条链可以有 1 到 10 个变体，每个变体有 `variantKey`（标识，区分大小写）、`targetUrl`（该桶目标）、`weight`（权重）。
- 加权随机抽签：把 [0, total) 的数轴按权重切成连续区段，随机数落进哪个区段就选哪个变体。
- 半开区间 `[a, b)`：含左端不含右端。相邻区段才能无缝拼满数轴，不重不漏。
- `DoubleSupplier`（Java 函数式接口）：`() -> double`，返回一个随机数。把随机源做成参数，纯函数就能用固定值单测。
- `ThreadLocalRandom`：JDK 并发场景下的随机数源，比共享的 `Random` 少竞争。

## 3. 本节涉及的源码

| 文件 | 行数 | 职责 | 阅读顺序 |
|----|----|----|----|
| `src/main/java/com/linkforge/link/support/VariantPicker.java` | 35 | 主角：纯函数抽签 | 1 |
| `src/main/java/com/linkforge/link/service/ShortLinkService.java` | 1025 | pickVariant :715-721、抽签点 :705-711 | 2 |
| `src/main/java/com/linkforge/link/entity/LinkVariant.java` | 26 | 变体行：variantKey :21、targetUrl :22、weight :23 | 3 |
| `src/main/java/com/linkforge/stats/mq/ClickEvent.java` | 29 | variant 字段 :27-28 | 4 |
| `src/main/java/com/linkforge/link/service/ShortLinkService.java` | 同上 | recordClick 调用 :574 | 5 |

## 4. 类的定位

- `VariantPicker` 是 `link.support` 里最小的类之一：35 行、无字段、私有构造器、一个静态方法。它是纯函数：同样的输入（变体列表与随机源）必得同样的输出，不碰数据库、不碰 Spring。
- 它的调用方只有 `ShortLinkService.pickVariant`（:715-721）一处。跳转链在第 12 步用它。
- `LinkVariant` 是 `lf_link_variant` 的实体。类注释 :7-8 写明它与 `ShortLink` 的生命周期差异：硬删加插入，没有逻辑删列。
- `ClickEvent` 是点击事件进 MQ 的契约，`variant` 字段（:27-28）带注释：抽中的 A/B 桶键，单目标链为 null。

## 5. 核心源码详解

### 5.1 VariantPicker：半开区间抽签

```java
public static LinkVariant pick(List<LinkVariant> variants, DoubleSupplier random) {
    long total = 0;
    for (LinkVariant variant : variants) {
        total += variant.getWeight();
    }
    // r*total lands in [0, total); each variant owns the half-open step
    // [cumulativeBefore, cumulativeAfter), so the compare must be strict <.
    double threshold = random.getAsDouble() * total;
    long cumulative = 0;
    for (LinkVariant variant : variants) {
        cumulative += variant.getWeight();
        if (threshold < cumulative) {
            return variant;
        }
    }
    // Only reachable when r*total rounds up to exactly total; the top step owns it.
    return variants.get(variants.size() - 1);
}
```
`VariantPicker.java:17-34`

逐段读。第一段求总权重（:18-21）。第二段把随机数映射到 `[0, total)`：`DoubleSupplier.getAsDouble()` 约定返回 `[0.0, 1.0)`，乘以 total 后仍在 `[0, total)`。注释 :22-23 说明了关键约定：每个变体拥有半开区段 `[累计前, 累计后)`，所以比较必须是严格小于。

用一个 70/30 的例子走一遍。设两个变体 A 权重 70、B 权重 30，total=100：

- `r=0.00` → threshold=0 → 落在 A 的区段 `[0, 70)` → A。
- `r=0.6999` → threshold=69.99 → 仍 `< 70`，A。
- `r=0.70` → threshold=70.0 → `70 < 70` 不成立，进 B 的区段 `[70, 100)` → B。
- `r=0.9999` → threshold=99.99 → B。

边界值 70 恰好属于 B 而不是 A，这就是半开区间的含义：`<` 若写成 `<=`，A 会多占 70 这个点，权重变成 70.0…1 对 29.9…9（浮点下是语义污染，纯整数权重下则是明确的错分）。权重与区段一一对应，长跑统计必然收敛到 70/30。

最后两行（:32-33）处理一个浮点角落：`r*total` 可能因舍入恰好等于 total（例如 r 是极接近 1 的 double，乘法向上舍入）。循环走到头都没返回时，最后一个变体兜底。注释写明「只有舍入到恰好 total 时才可达，顶部区段收下它」。

### 5.2 随机源的注入：为什么抽签是纯函数

类注释 :9-11 说：抽签用固定 double 单测，跳转路径传入 `ThreadLocalRandom.current()::nextDouble`。调用点：

```java
private LinkVariant pickVariant(Long linkId) {
    List<LinkVariant> variants = variantMapper.selectByLinkId(linkId);
    if (variants.isEmpty()) {
        return null;
    }
    return VariantPicker.pick(variants, ThreadLocalRandom.current()::nextDouble);
}
```
`ShortLinkService.java:715-721`

`pickVariant` 返回 null 表示单目标链（方法注释 :714）。变体列表来自 `LinkVariantMapper.selectByLinkId`（按 id 排序，保证同一条链每次抽签的区段切法一致）。随机源作为参数传入而不是在 `pick` 内部 new，让「抽签逻辑」与「随机性」解耦：测试可以传 `() -> 0.5` 复现任意边界。

### 5.3 抽签点：全部门禁之后

`resolveTarget` 的收尾：

```java
// D-C3-8/10: the draw happens only after every gate passed, and a non-empty
// variant set always wins over the cached originalUrl (the cache stays a
// single-URL hint, never the Location truth for a split link).
LinkVariant picked = pickVariant(link.getId());
return new RedirectOutcome.Redirect(link,
        picked != null ? picked.getTargetUrl() : originalUrl,
        picked != null ? picked.getVariantKey() : null);
```
`ShortLinkService.java:705-711`

注释讲了两件事。其一，抽签只在每道门（限流、Bloom、缓存、行状态、URL 校验、B9 BLOCK、密码门、B9 WARN）全部通过后发生。被拦的请求根本走不到抽签：403/401/警示页没有 Location，自然没有「抽中哪个目标」的问题。其二，只要链配了非空 variants，Location 就取抽中的 `targetUrl`，绝不取缓存里的 `originalUrl`。

第二件事值得展开。回想 7.1 的缓存回填：`cacheUrl` 写的是行的 `originalUrl`（`ShortLinkService.java:966-977`），L1/L2 里存的永远是「主目标」。对一个 70/30 分流链，这个缓存值只配当提示。`picked != null ? picked.getTargetUrl() : originalUrl`（:710）这个三目就是优先级的全部代码：有变体用变体，没变体才用（可能来自缓存的）主 URL。

### 5.4 unlock 后照常分流

`unlock`（:753-792）整段没有 `pickVariant` 调用：解锁只发 cookie，不做跳转决策。controller 解锁成功后 303 跳回 `/r/{code}`（`RedirectController.java:66-69`），浏览器带 cookie 重新 GET，重走 7.1 的完整判定链，在 705-711 照常抽签。设计效果：分流逻辑只存在于一个位置（resolveTarget 收尾），解锁、WARN 继续、HEAD 之外的任何正常 GET 入口自动继承同一套抽签行为，不存在「解锁路径忘了分流」这类双实现漂移。

### 5.5 统计分桶：variant 进事件

抽中的桶随 `Redirect` outcome 出了 service（`variantKey` 字段，`RedirectOutcome.java:13`），在 `resolveForGet` 里随点击落库：

```java
clickRecordService.recordClick(redirect.link(), request, redirect.variantKey());
```
`ShortLinkService.java:574`

`recordClick` 把它装进 `ClickEvent` 的 `variant` 字段（`ClickEvent.java:27-28`，注释点名 D-C3-11），随事件进入 `lf_click_log` 与后续统计表。于是报表可以按 `variant` 维度拆分点击量，验证 70/30 是否成立。B2 命中的预取请求在 :569-572 提前返回，不记录，也就不会往桶里灌水。

## 6. 技术原理补充

- **线性扫描 vs 轮盘赌/别名法（算法知识）**：本实现的成本是 O(n) 求和加 O(n) 扫描，n 最大 10（创建校验限制，6.2 课）。别名法能做到 O(1) 查询但需要预处理表；n 这么小，线性扫描总成本更低也更易读。不用别名法不是性能疏忽，是规模使然（推断）。
- **`DoubleSupplier` 与可测性（Java 语言知识）**：随机性是测试的天敌。把随机源抽象成接口参数，抽签算法的所有边界（0、恰好越界、浮点舍入）都能用固定值复现。这是「依赖注入」思想在最小尺度上的应用。
- **半开区间的通用性（数学知识）**：任何「把连续区间切成 n 段」的场景（分桶、限流的窗口切分、一致性哈希的环）都应统一用左闭右开，否则边界点要么双计要么漏计。本项目的 `threshold < cumulative` 是这一惯例的具体落地。
- **变体与主 URL 的关系（本项目实现）**：`lf_short_link.original_url` 永远是「主目标」，变体表是附加目标集。详情接口返回主 URL 加整组变体；CSV 导出与 QR 码只涉及主链（QR 内容恒为 `{base}/r/{code}`，根本不含目标 URL）；只有跳转 Location 在有变体时改用抽中目标。缓存存主 URL 因此是安全的——它永远只是提示。

## 7. 可视化

1. [../../diagrams/archify/html/12-redirect-decision.html](../../diagrams/archify/html/12-redirect-decision.html)（由并行任务产出）：第 12 步「有 variants?」分支对应 `pickVariant` :715-721 与 `VariantPicker.pick`；「无」分支直接 `302 + Location=original_url`。
2. [../../diagrams/mermaid/redirect-decision-chain.md](../../diagrams/mermaid/redirect-decision-chain.md)：备用图的末段画了抽签与记录两条终态。
3. [../../diagrams/archify/html/05-database-erd.html](../../diagrams/archify/html/05-database-erd.html)：`lf_link_variant` 与 `lf_short_link` 的关系（link_id 逻辑外键），看变体怎么挂在主行下。

## 8. 项目中的真实执行过程

一条 70/30 分流链的两次访问。

1. 创建时传了两个变体：`a`（权重 70，指向 `/target-a`）与 `b`（权重 30，指向 `/target-b`）。`validateVariants`（6.2 课讲过 :823-843）保证 key 唯一、URL 合法、逐个过 B9。
2. 第一次 GET：判定链前 11 步全过。`pickVariant` 查出两个变体，随机源给出 `r=0.42`，threshold=42，落进 A 区段 `[0, 70)`，返回变体 `a`。
3. 返回 `Redirect(link, "/target-a", "a")`（:709-711）。注意缓存的 `originalUrl` 完全没参与。
4. `resolveForGet` :574 调 `recordClick`，`variant="a"` 进点击事件，最终可按桶聚合。
5. 第二次 GET：`r=0.85`，threshold=85，落进 B 区段 `[70, 100)`，Location 是 `/target-b`，统计记 `variant="b"`。

伴随细节：这两次请求各自照常读 DB 行（7.1 的强一致取舍）、照常过密码门。若这条链同时设了密码，访客解锁后的那次 GET 一样参与抽签，`unlock` 不会预先替他决定桶。

## 9. 为什么这样设计

**抽签放在门禁后。** 顺序反过来的世界：先抽签再过门，被 403 拦下的流量也「消耗」了某个桶的份额，B9 拦截页会泄漏「这次本来要去哪个目标」，WARN 页同理。门禁后的抽签保证每一签都对应一次真实的 302，桶数据不被门禁损耗污染。注释 :705 的 D-C3-8 编号表明这是评审定下的决策而非顺手写法。

**缓存只是单 URL 提示。** 变体信息不存在 Redis，缓存里只有主 URL。若允许缓存值压过抽签，分流链会退化成单目标链直到缓存过期。:706-707 注释把这条写成规则：「对分流链，缓存永远不是 Location 真相」。代价是分流链每请求多一次 `selectByLinkId` 查询（:716），换来分流语义百分之百由 DB 决定。

**与 Geo 定向的诚实边界。** 这套机制只按权重随机分流，不看请求的任何上下文：不解析 IP 归属、不看 Accept-Language、没有条件表达式。`LinkVariant` 实体只有 `variantKey`、`targetUrl`、`weight` 三个业务字段（:21-23），没有任何匹配条件列。`LinkForgeProperties.Phishing` 的注释 :72-74 有一句同源的坦白：本项目仍是 open redirect，没有信誉服务与内容扫描。要按地域定向，需要加归属地查询与条件路由，那是另一套复杂度（IP 库维护、缓存、隐私合规），本项目刻意没做（推断：这是教学项目主动收缩范围的选择，实体字段即证据）。

## 10. 本节总结

- 抽签算法：`threshold = r × total`，半开区段 `[累计前, 累计后)`，严格 `<` 比较，浮点舍入由最后一个变体兜底（`VariantPicker.java:17-34`）。
- 抽签点：全部门禁通过后的 `resolveTarget` 收尾（:705-711），D-C3-8/10 注释为决策记录。
- 优先级：非空 variants 永远压过缓存的主 URL；无变体链才用 `originalUrl`。
- 解锁后照常分流：unlock 不抽签，303 重走 GET 自动继承。
- 统计：`variantKey` 随 `recordClick`（:574）进 `ClickEvent.variant`（:27-28），按桶可聚合。
- 边界：纯权重随机，无地域、无语言、无任何条件定向。

## 11. 课后练习

**练习 1（概念理解）**：三个变体权重分别为 50、30、20，`r=0.50`，threshold 是多少？抽中谁？若把 `threshold < cumulative` 改成 `<=`，`r=0.50` 时抽中谁？

答案解析：total=100，threshold=50。扫描：A 累计 50，`50 < 50` 不成立；B 累计 80，`50 < 80` 成立，抽中 B（B 的区段是 `[50, 80)`）。改成 `<=` 后：A 累计 50，`50 <= 50` 成立，抽中 A。A 的区段从 `[0, 50)` 膨胀成 `[0, 50]`，边界点 50 被两个区段同时声明，长跑统计 A 偏多。这正是 :22-23 注释强调严格小于的原因。

**练习 2（源码定位）**：找到「缓存 URL 不参与分流链跳转」的两处代码：一处在抽签收尾，一处在 B9 判定。各用一句话说明它们防止了什么。

答案解析：其一，:709-711 的三目 `picked != null ? picked.getTargetUrl() : originalUrl`，防止分流链被缓存退化成单目标链。其二，:684 的 `phishingPolicy.evaluate(link.getOriginalUrl(), false)` 判的是行的 URL 而非缓存值，防止名单更新后陈旧缓存让该拦的链继续放行（配合 :682-683 注释）。两处同属 7.1 说的「行权威」纪律。

**练习 3（执行过程）**：一条 70/30 密码链，访客首次 GET、提交正确密码、跟随 303 后再次 GET。这三次请求里 `pickVariant` 被调用几次？分别在哪次?

答案解析：两次。首次 GET 在密码门（:691-695）被拦，走不到 :708 的抽签，`pickVariant` 未执行。unlock（:753-792）不抽签。303 后的 GET 全链通过，第一次抽签。若访客在 WARN 页点继续，那次 GET 同样是一次抽签。规律：`pickVariant` 的调用次数等于「产出 302 的判定链执行次数」。

**练习 4（设计分析）**：为什么变体列表必须按稳定顺序扫描（`selectByLinkId` 按 id 排序）？顺序不稳定会怎样？

答案解析：抽签把数轴按「列表顺序」切给各变体。顺序漂移时，同一个 `threshold` 会落进不同变体的区段。单个请求看仍是合法抽签，但同一时刻并发请求可能用不同切法，长期统计权重仍趋近正确（每一切法都是按权重切的）。真正的破坏在可复现性：固定随机源的测试与问题排查无法重放。:9-10 注释承诺「unit-tested with fixed doubles」，前提就是切法确定，排序即此承诺的实现。

**练习 5（调用链）**：从 `resolveTarget` 收尾到统计表，写出 variant 值的完整路径（类.字段:行号）。

答案解析：`pickVariant` :715-721 抽中 `LinkVariant`，取 `getVariantKey()`（实体 :21）→ `RedirectOutcome.Redirect` 第三分量 `variantKey`（`RedirectOutcome.java:13`）→ `resolveForGet` :574 传入 `recordClick(link, request, redirect.variantKey())` → `ClickEvent.variant`（`ClickEvent.java:27-28`）→ 事件进入 `lf_click_log` 与下游统计（模块 10/11 讲管道）。全程只有一个字符串，源头是 `lf_link_variant.variant_key`。

## 12. 与后续课程的联系

- 模块 10 讲 `recordClick` 之后的事件如何经 outbox 进 MQ；模块 11 讲 `variant` 维度在 `lf_click_dim_daily` 的聚合与查询。本课的抽签是那条数据链的第一环。
- 6.2 课的 `validateVariants`（:823-843）是本课的前半场：权重合法性、key 唯一、逐个过 B9 都在写入时把关。
- 8.2 课讲 `cacheUrl` 写穿双删：本课解释了为什么那份缓存对分流链「只是提示」。
- 若要做地域定向（本项目未做），需要新增 IP 归属维度，并与 9.1 的 `redirect:ip` 限流键共享 `ClientIps` 的解析纪律。
