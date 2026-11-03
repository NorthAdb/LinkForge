# 专题 4 · 匿名跳转 302 全链路

> 模块课 7.1 拆了 12 步判定链，7.2 拆了三道门，7.3 拆了抽签，11.1 拆了记账。本专题把四者串成一次完整跳转：浏览器敲下 `/r/aB3xK9m`，几百毫秒内收到 302；响应返回之后，这次点击还在另一条线程上继续旅行。

## 1. 业务背景：流量最大、身份最轻、约束最多的一条路径

用户故事。某公众号读者点开一篇文章里的推广链接 `https://s.example.com/r/aB3xK9m`。她没有登录、没有账号，浏览器也不会带任何凭据。系统要在不打扰她的前提下做对十几件事：别把不存在的码送去数据库、别把禁用和过期的链放出去、别把指向恶意域名的请求送往目标、别让爬虫的预取请求污染统计，最后用一个干净的 302 把她送到目的地。

这条链路值得单独讲，因为它的约束组合在全项目最苛刻。它是唯一以匿名访客为主体的业务（认证全被 permitAll 放行，防线后撤到服务层）；它是流量最大的路径（每次投放点击都走这里）；它同时背着强一致承诺（禁用、改密、改目标下一个请求立即生效）和记账责任（每次点击要进统计管道）。这三个约束在其他任何一条链路里都不同时出现。

## 2. 相关类清单

| 类 | 角色 | 模块课 |
|----|----|----|
| `RedirectController` | GET/HEAD/unlock 三入口；把判定结果翻译成 HTTP 响应 | [7.1](../modules/07-redirection/01-decision-chain.md) |
| `ShortLinkService.resolveForGet` | 判定链调用方：B2 分类、记账、fail-open catch | [7.1](../modules/07-redirection/01-decision-chain.md)、[11.1](../modules/11-statistics/01-click-record-pipeline.md) |
| `ShortLinkService.resolveTarget` | 12 步判定主干 | [7.1](../modules/07-redirection/01-decision-chain.md) |
| `RedirectOutcome` | sealed interface：四类正常分支的结果契约 | [7.1](../modules/07-redirection/01-decision-chain.md) 第 5.3 节 |
| `CodeBloomFilter` / `UrlLocalCache` / Redis L2 | 三层加速（负答权威/提示/提示） | [7.1](../modules/07-redirection/01-decision-chain.md) |
| `SingleFlight` + `ShortLinkRowLoader` | 并发同码合并行读 | [7.1](../modules/07-redirection/01-decision-chain.md)（8.4 课专讲） |
| `TargetPolicy` + `PhishingPolicy` | B9 门禁判定与名单快照 | [7.2](../modules/07-redirection/02-password-and-domain-gates.md) |
| `LinkAccessTokens` | 解锁 cookie 的签发与校验 | [7.2](../modules/07-redirection/02-password-and-domain-gates.md) |
| `VariantPicker` | A/B 权重抽签（纯函数） | [7.3](../modules/07-redirection/03-variants-split.md) |
| `ClickFilter` | B2 预取/预览 bot 识别 | [11.1](../modules/11-statistics/01-click-record-pipeline.md) 第 5.5 节 |
| `ClientIps` | 跳转限流与记账共用的 IP 解析（trust-xff 开关） | 2.2 课（[11.1](../modules/11-statistics/01-click-record-pipeline.md) 第 5.1 节有引用） |
| `ClickRecordService` | 同事务双写 lf_click_log 与 lf_outbox | [11.1](../modules/11-statistics/01-click-record-pipeline.md) |
| `GatePages` | 403/警示/解锁三张 HTML 页 | [7.2](../modules/07-redirection/02-password-and-domain-gates.md) 第 5.7 节 |

源码路径前缀 `src/main/java/com/linkforge/` 省略。

## 3. 完整调用链

```
GET /r/aB3xK9m（匿名，无凭据）
  → SecurityFilterChain：GET/HEAD /r/** 放行（SecurityConfig.java:43-44）
  → RedirectController.redirect（RedirectController.java:36-41）
  → ShortLinkService.resolveForGet（ShortLinkService.java:565-585）
  → resolveTarget(code, request, true)（:601-712，12 步）
      ├─ 1 IP 滑动窗口限流（:604-610，键 redirect:ip:{ip}，默认 300 次/60 秒）→ 429
      ├─ 2 Bloom 负答 = 码从未创建（:615-618）→ 404，不碰 MySQL
      ├─ 3 空值缓存命中（:623-626）→ 404
      ├─ 4 L1 进程内缓存查 URL（:631-632）
      ├─ 5 L2 Redis 查 URL（:633-644）；Redis 异常 fail-open 继续（:636-640）
      ├─ 6 singleflight 合并并发，读 DB 行（:649-654）；行不存在 → markAbsent + 404
      ├─ 7 状态与过期（:656-661）：禁用 → 404；过期 → 410
      ├─ 8 URL 可渲染校验（:663-671）：脏行 → 410；脏缓存值 → 410
      ├─ 9 B9 BLOCK（:684-688）→ Blocked（403 拦截页）
      ├─ 10 密码门（:691-695）→ Locked（GET 200 解锁页 / HEAD 401）
      ├─ 11 B9 WARN（:696-704）→ Warn（200 警示页；lf_continue=1 且 GET 可继续）
      └─ 12 抽签（:705-711）→ Redirect(link, targetUrl, variantKey)
  → 回到 resolveForGet：
      ├─ B2 分类（:568-572）：预取/预览 bot → 打点后原样返回 302，不记账
      ├─ recordClick（:573-574，ClickRecordService.java:40-75）
      │    同一事务内插 lf_click_log（sync 开，:56-58）与 lf_outbox（async 开，:60-74）
      ├─ DataAccessException → fail-open 打点，302 照常（:575-581）
      └─ redirects 计数（:582）
  → RedirectController.render（RedirectController.java:85-117）
      Redirect 分支 → 302 + Location（:86-90）
```

### 3.1 HEAD 支线与 unlock 入口

HEAD 探测共用同一条判定链，差异收敛在一个布尔参数上：

```
HEAD /r/aB3xK9m
  → SecurityFilterChain 放行（SecurityConfig.java:43-44，GET/HEAD 同一条）
  → RedirectController.probe（RedirectController.java:43-48）
  → resolveForProbe（ShortLinkService.java:591-593）→ resolveTarget(code, request, false)
      ├─ 第 1-9 步与 GET 完全一致
      ├─ 第 11 步：warnContinueAllowed=false，lf_continue 被忽略，照常返回 Warn
      └─ 不做 B2 分类、不记点击（这两步只在 resolveForGet 里）
  → render(outcome, code, withBody=false)：状态码与头逐项一致，仅无正文
      Redirect → 302；Blocked → 403；Locked → 401（GET 是 200）；Warn → 200
```

第三个入口 `POST /r/{code}/unlock`（`RedirectController.java:52-82`）是密码解锁表单的提交点，成功后 Set-Cookie 加 303 跳回 `/r/{code}`，浏览器带 cookie 重走上面的完整 GET 链。unlock 自己不抽签、不记点击，分流语义由重走的 GET 自动继承（回看 [7.3](../modules/07-redirection/03-variants-split.md) 第 5.4 节）。

线程与时刻标注：t0 到出 302 全部在一条 Tomcat 工作线程上，无切换。线程切换发生在响应之后：lf_outbox 行由 `ClickOutboxRelay` 的调度线程在约 2 秒内抽走发往 RabbitMQ，消费端再由 MQ 容器线程聚合（见 [11.1](../modules/11-statistics/01-click-record-pipeline.md) 第 8 节；运输细节回看 10.2 课 `../modules/10-rabbitmq-outbox/02-outbox-relay.md`）。记账的 DB 写在请求内、同步完成；MQ 投递是请求外、异步完成，两段以 outbox 行解耦。

## 4. 可视化

阅读顺序：

1. [12-redirect-decision](../diagrams/archify/html/12-redirect-decision.html)：主图。12 步判定链与全部失败终态。图上 404 画成两个就近终端（缓存层一个、行读层一个），代码里共四个触发点，对应关系见第 7 节。步骤 9-11 三道门与第 12 步抽签是图的后半段。
2. [13-cache-hit-miss-sequence](../diagrams/archify/html/13-cache-hit-miss-sequence.html)：第 4-6 步的时序展开。L1/L2 双命中线、双 miss 未命中线、行分叉（markAbsent 404 / cacheUrl 预热 302）。
3. [redirect-decision-chain（Mermaid 备用）](../diagrams/mermaid/redirect-decision-chain.md)：静态版。备用图把 L2 画进行读步内，与主图的分组粒度不同、顺序一致。

推荐读法：先用主图建立 12 步顺序，再用第 2 张图看懂「为什么有缓存还要读行」，最后用第 3 张图在 Obsidian 里复习。

## 5. 源码详解：只看跨类协作的节点

**节点一：RedirectOutcome 把失败分成了两个通道。** 404/410/429 是「没有页面可渲染」，抛 BizException 走全局异常处理器，所有接口共享；Redirect/Blocked/Locked/Warn 是「判定成功，渲染不同页面」，走 sealed interface 返回值，由 `render` 逐分支翻译。新增分支漏改 render 会显式炸出 IllegalStateException（`RedirectController.java:116`）。回看 [7.1](../modules/07-redirection/01-decision-chain.md) 第 5.3 节。

**节点二：缓存只加速、行才权威，三处可查证。** URL 校验先验行再验缓存值（:663-671）；B9 门禁判行的 URL（:684，注释 :682-683）；抽签结果压过缓存 URL（:705-711）。L1 命中省的只是 Redis 往返，第 6 步的行读照做。三处共享同一个反面假设：陈旧的缓存条目绝不能改变 DB 今天给出的判定。回看 [7.1](../modules/07-redirection/01-decision-chain.md) 第 9 节。

**节点三：门禁顺序有安全含义。** BLOCK 在密码门之前（D-Bw345-51）：恶意链接即使加了密码，用户也永远看不到解锁页。403 拦截页不回显目标。WARN 在密码门之后、抽签之前：被拦的请求走不到抽签，桶数据不被门禁损耗。回看 [7.2](../modules/07-redirection/02-password-and-domain-gates.md) 第 5.5 节、[7.3](../modules/07-redirection/03-variants-split.md) 第 5.3 节。

**节点四：记账与跳转的解耦边界。** `recordClick` 的两行写入在请求线程内同事务完成；302 不依赖这两行成功，catch 写在调用方、事务回滚之后，只捕 `DataAccessException`。序列化失败抛 IllegalStateException 会穿透 catch 变 500。为什么 catch 必须在事务外，回看 [11.1](../modules/11-statistics/01-click-record-pipeline.md) 第 5.4 节。

**节点五：singleflight 是合并，不是缓存。** `singleFlight.run(code, () -> rowLoader.load(code))`（:649）让并发同码的 N 个请求共享一次行读，飞行结束条目即删：没有 TTL、没有「上一次的结果」。它压不住跨请求的重复读（那是缓存的事），也绝不用旧结果回答新请求（那是缓存的禁区）。超时或禁用时各读各的，只多打点不报错。回看 [7.1](../modules/07-redirection/01-decision-chain.md) 第 5.4 节，实现细节在 8.4 课。

**节点六：L1 回填的严格条件。** :672-680 有两个看似相近的分支。只有「Redis 命中且通过行校验」的值才回填 L1（:675-677，注释 Bw1-N1：回填点被刻意挪到两项校验之后，保证进 L1 的值既过了行门又过了可渲染检查）；缓存全 miss 时才 cacheUrl 预热（:678-680）。行脏或缓存脏的请求永远不会污染 L1，这是「缓存只加速」在第 8 步之后 still 成立的保障。

**节点七：三种入口的共性与差异一览。** 共用的是第 1-9 步判定与安全响应头（no-store、CSP、nosniff）。差异有三处：GET 独有 B2 分类与记账（:565-585）；GET 独有 `lf_continue` 确认能力（:589 注释）；Locked 的状态码随入口变化（GET 200 给人看解锁页，HEAD 401 表达未授权）。差异全部压在参数里（warnContinueAllowed、withBody），没有第二套判定代码。

## 6. 正常流程：缓存已热的第二次访问（点击被完整记录）

| 时刻 | 线程 | 动作 | 证据 |
|----|----|----|----|
| t0 | Tomcat W1 | GET /r/aB3xK9m 进链，GET/HEAD 放行 | `SecurityConfig.java:43-44` |
| t1 | W1 | redirect 入口调 resolveForGet | `RedirectController.java:36-41` |
| t2 | W1 | 限流键 redirect:ip:{ip} 计数，未超 300/60s | `ShortLinkService.java:604-610` |
| t3 | W1 | Bloom 正答（码存在），继续 | `ShortLinkService.java:615-618` |
| t4 | W1 | 空值缓存未命中，L1 未命中，L2 命中拿到 URL | `ShortLinkService.java:623-644` |
| t5 | W1 | singleflight 行读：状态 1、未过期 | `ShortLinkService.java:649-661` |
| t6 | W1 | 行 URL 与缓存 URL 均可渲染；L1 回填 | `ShortLinkService.java:663-680` |
| t7 | W1 | 三道门全过：无 BLOCK、无密码、无 WARN | `ShortLinkService.java:684-704` |
| t8 | W1 | 无 variants，Location 取主 URL；返回 Redirect | `ShortLinkService.java:708-711` |
| t9 | W1 | B2 分类：真人，不跳过 | `ShortLinkService.java:568-572` |
| t10 | W1 | recordClick 开事务，插 lf_click_log（id 回填），eventId=log-{id}，插 lf_outbox PENDING，提交 | `ClickRecordService.java:40-75` |
| t11 | W1 | redirects 计数加一 | `ShortLinkService.java:582` |
| t12 | W1 | render 输出 302 + Location，响应离开服务 | `RedirectController.java:86-90` |
| t13 | 调度线程池 | （响应后约 2 秒内）ClickOutboxRelay 抽走 outbox 行发往 RabbitMQ | [11.1](../modules/11-statistics/01-click-record-pipeline.md) 第 8 节 |
| t14 | MQ 消费线程 | 消费事件，聚合表加一；同 eventId 去重 | 10.3、11.2 课 |

t13 与 t14 即使全部失败，t12 的 302 也早已送达；这就是 outbox 模式买来的隔离。

对照场景：同一条链的 HEAD 探测（监控拨测每分钟一次）。

| 时刻 | 线程 | 动作 | 证据 |
|----|----|----|----|
| h0 | Tomcat W3 | HEAD /r/aB3xK9m，与 GET 同一条 permitAll 规则放行 | `SecurityConfig.java:43-44` |
| h1 | W3 | resolveForProbe 直接进 resolveTarget，warnContinueAllowed=false | `ShortLinkService.java:591-593` |
| h2 | W3 | 第 1-9 步照跑（限流键是同一个 redirect:ip:{ip}，拨测消耗配额） | `ShortLinkService.java:604-711` |
| h3 | W3 | 返回 Redirect；没有 B2 分类，没有 recordClick，没有 redirects 计数 | `ShortLinkService.java:566` 直接回传 |
| h4 | W3 | render 输出 302，无正文 | `RedirectController.java:85-90` |

同一条链、同一时刻，GET 与 HEAD 的差别只有一件事：GET 留下一条点击记录，HEAD 什么都不留。这就是「HEAD 契约」的全部含义，监控可以放心用它探活而不污染统计。附带一个推论：HEAD 也不做 B2 分类，所以拨测流量永远不会出现在 click.skipped 指标里，该指标的流量全部来自真实 GET。

## 7. 异常流程：每个失败分支

| 触发条件 | 返回 | 副作用 | 出处 |
|----|----|----|----|
| 同 IP 60 秒内第 301 次跳转 | 429 + Retry-After | Redis 窗口计数在走 | `ShortLinkService.java:604-610` |
| Redis L2 读失败 | 正常继续（降级到行读） | 打点 cache.failopen{op=l2_get} | `ShortLinkService.java:636-640` |
| MySQL 行读本身失败 | 500 | 无缓存兜底（行是权威，无降级路径） | `ShortLinkService.java:649` 无 catch |
| Bloom 负答（码从未创建） | 404 | 无，未碰 MySQL | `ShortLinkService.java:615-618` |
| 空值缓存命中（DB 近期确认不存在） | 404 | 无 | `ShortLinkService.java:623-626` |
| DB 行不存在（Bloom 误报/逻辑删） | 404 | markAbsent 写空值缓存（短 TTL 加抖动） | `ShortLinkService.java:650-654` |
| 链已禁用（status != 1） | 404 | 无；绝不进空值缓存 | `ShortLinkService.java:656-658` |
| 链已过期 | 410 "short link expired" | 无 | `ShortLinkService.java:659-661` |
| 行的 URL 脏 / 缓存值脏 | 410 "short link target is invalid" | 无 | `ShortLinkService.java:663-671` |
| B9 名单 BLOCK | 403 + 拦截页（无目标回显） | 打点 phishing.gate | `ShortLinkService.java:684-688` |
| 链带密码且 cookie 无效 | GET 200 解锁页 / HEAD 401 | 打点 linkforge.locked | `ShortLinkService.java:691-695`、`RedirectController.java:98-105` |
| 解锁表单密码错误 | 401 + 错误解锁页 | 打点 unlock{result=bad_password}；两级限流计数 | `RedirectController.java:71-78`、`ShortLinkService.java:767-788` |
| 对没有密码的链提交 unlock | 303 跳回 /r/{code}，无 Set-Cookie | 两级限流计数（先于判定执行） | `ShortLinkService.java:786-788`、`RedirectController.java:79-81` |
| unlock 的 Bloom/行状态镜像不通过 | 404 | 无 | `ShortLinkService.java:754-763`（[7.2](../modules/07-redirection/02-password-and-domain-gates.md) 第 5.3 节） |
| 短码大小写不匹配 | 404（码是大小写敏感的精确匹配） | 无 | `RedirectController.java:38` 参数说明、`:615-618` |
| HEAD 撞上 WARN 名单 | 200 无正文（lf_continue 被忽略） | 打点 phishing.gate | `ShortLinkService.java:696-704`、`RedirectController.java:112-114` |
| B9 名单 WARN 且未确认 | 200 警示页（lf_continue 仅 GET） | 打点 phishing.gate；确认后打点 phishing.continue | `ShortLinkService.java:696-704` |
| B2 命中（预取头/预览 bot UA） | 302（与正常完全一致） | 不写任何表；打点 click.skipped | `ShortLinkService.java:568-572` |
| 点击写库失败（MySQL 故障等） | 302 照常 | 事务已回滚，明细与事件双丢；打点 failopen | `ShortLinkService.java:575-581` |
| 点击事件序列化失败 | 500 | 无（IllegalStateException 穿透 catch） | `ClickRecordService.java:77-83`、[11.1](../modules/11-statistics/01-click-record-pipeline.md) 第 5.3 节 |

404 的四个触发点在图 [12-redirect-decision](../diagrams/archify/html/12-redirect-decision.html) 上按就近合并成两个终端；排障时按第 2 节清单顺序反查即可定位来源。各失败分支互不吞并：禁用 404 不进空值缓存（重新启用立即生效），过期 410 不与脏目标 410 混淆，BLOCK 永远先于密码门被看见。

读这张表时注意副作用列的分布。四类 HTML/302 结果（403/解锁/警示/302）全部零持久副作用；有持久副作用的只有限流计数、markAbsent 写入与（失败时的）限流键消耗。唯一写库的正常路径是第 6 节 t10 的记账，唯一写库的失败路径是行不存在时的 markAbsent。记住这个不对称，「为什么每次 404 都在写 Redis 但没人担心」这类问题就有了答案。

## 8. 设计取舍

**一、每个请求都读一次 DB 行。** 三层缓存齐全，行读照做。`UrlLocalCache` 的 javadoc（:17-20）承诺命中绝不跳过行读；:645-648 注释给出一致性界（跟车者最多旧一个查询延迟，远小于 1 秒双删窗口）。换来的是禁用、改密、改目标下一个请求立即生效。这是明确的一致性换吞吐，不是疏忽。回看 [7.1](../modules/07-redirection/01-decision-chain.md) 第 9 节。

**二、缓存的可靠性分等：Bloom 负答权威，正答与 URL 值只是提示。** Bloom 说「不存在」可以直接 404（:612-614 注释）；缓存值永远要过行校验。fail-open 方向一致：L2 读失败降级到行读，绝不 500（:636-640）。

**三、判定顺序互不吞并。** 每一步的失败有自己的出口与状态码，后面的步骤永远看不到前面的失败。两个 410 语义不同（行状态对内容校验），两个 404 语义不同（从未创建对存在但不可用）。写成一条直线加提前返回，正是为了让出口一眼可见（回看 [7.1](../modules/07-redirection/01-decision-chain.md) 第 6 节）。

**四、302 不依赖记账。** 跳转是主流程，点击记录是副功能，fail-open 只兜环境故障（DataAccessException），bug 型异常照样打爆在请求路径上（回看 [11.1](../modules/11-statistics/01-click-record-pipeline.md) 第 9 节）。

**五、放行不等于不设防。** permitAll 把认证撤了，防线后撤到服务层：跳转有 IP 限流（300/60s），解锁有两级限流且先于 BCrypt（[7.2](../modules/07-redirection/02-password-and-domain-gates.md) 第 5.3 节），`SecurityConfig.java:45-46` 的注释 "anonymous by design, brute-force limited in the service" 就是这条原则的原文。防线后撤的边界也要看清楚：解锁 cookie 的密钥从 JWT 密钥派生（`LinkAccessTokens.java:25` 附近），轮换 JWT 密钥会顺带吊销全部解锁 cookie，两个安全域共享一个根密钥。（推断：这是减少环境变量的取舍，代价是密钥轮换的爆炸半径变大。）
