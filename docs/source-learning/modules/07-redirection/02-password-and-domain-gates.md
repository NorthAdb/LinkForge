# 7.2 密码门与域名门禁：B3 解锁协议与 B9 名单判定

> 本课回答两个问题：一个受密码保护的短链如何在不引入服务器会话的前提下安全解锁？一个本地维护的域名名单如何做到「坏文件不毁名单、误配不回显目标」？

## 1. 本节学习目标

学完本课，你应该能：

1. 说出 B3 密码的存储方式（BCrypt）与 4-64 字符校验的位置。
2. 解释 unlock 为什么用两级限流（按 IP + 按短码）且都先于 BCrypt 执行。
3. 逐步还原签名 cookie 的结构 `exp.HMAC(linkId|code|exp|pwdVer)`，解释改密即失效的实现，并说出恒时比较防的是什么攻击。
4. 描述 B9 的判定规则：标签边界后缀匹配、IDN 归一化、BLOCK 优先于 WARN、userinfo 建链拒/存量 WARN 差异，并说明名单热载失败时为什么保留旧快照。
5. 说明 `GatePages` 三张页面的转义与 CSP 设计。

## 2. 前置知识

- BCrypt：一种慢哈希密码算法，自带盐，验证用 `passwordEncoder.matches(明文, 哈希)`。慢是特性：爆破者每次猜也要付同样的 CPU 代价。
- HMAC-SHA256：带密钥的哈希消息认证码，持有密钥的一方才能生成或验证签名。
- 内容安全策略（CSP）：HTTP 响应头，声明页面允许加载哪些资源，`default-src 'none'` 是最严档（脚本、图片、外部请求全禁）。HTML 转义则把 `<`、`>`、`&` 换成 HTML 实体，防止用户输入被浏览器当代码执行（XSS）。两者是本课页面安全的两道防线。
- IDN/punycode：国际化域名的 ASCII 编码，`例子.com` 的 punycode 形如 `xn--fsqu00a.com`，两种写法指向同一主机。Cookie 属性（HttpOnly/Secure/SameSite/Path）在 5.4 节随代码解释。

## 3. 本节涉及的源码

| 文件 | 行数 | 职责 | 阅读顺序 |
|----|----|----|----|
| `src/main/java/com/linkforge/link/service/ShortLinkService.java` | 1025 | unlock :753-792、accessCookie :724-736、encodeAccessPassword :810-819 | 1 |
| `src/main/java/com/linkforge/link/support/LinkAccessTokens.java` | 112 | 签发与校验解锁 cookie | 2 |
| `src/main/java/com/linkforge/link/support/TargetPolicy.java` | 129 | B9 纯判定函数 | 3 |
| `src/main/java/com/linkforge/link/support/PhishingPolicy.java` | 165 | 名单快照持有与热载 | 4 |
| `src/main/java/com/linkforge/link/support/GatePages.java` | 84 | 三张 HTML 门禁页 | 5 |
| `src/main/java/com/linkforge/link/controller/RedirectController.java` | 123 | unlock 端点 :52-82、响应头 | 6 |
| `src/main/resources/application.yml` | 266 | link-access :221-226、phishing :213-220 | 7 |

## 4. 类的定位

- `LinkAccessTokens` 是 `link.support` 里的无状态签名器（`@Component`），只依赖配置类。没有撤销表、没有服务器会话，安全状态全部压进 cookie 签名。
- `TargetPolicy` 是纯静态工具类（私有构造器，:45-46），输入 URL 与名单、输出 `Verdict`，无任何字段，可以脱离 Spring 单测。
- `PhishingPolicy` 是唯一可变的名单持有者：`volatile` 快照（:34）加 mtime 热载（:35）。`TargetPolicy` 判定，`PhishingPolicy` 供数，职责分离。
- `GatePages` 是无模板引擎的 HTML 工厂（:6-8 注释：生产环境不提供静态资源），三张页面覆盖 403/警示/解锁。
- `RedirectController.unlock` 是匿名表单端点，javadoc :50-51 点明设计：限流和 BCrypt 活在 service 里，过滤器层异常不可能绕过两级限流。

## 5. 核心源码详解

### 5.1 B3 密码的存储：BCrypt 加长度校验

```java
private String encodeAccessPassword(String password) {
    if (password == null || password.isBlank()) {
        return null;
    }
    String trimmed = password.trim();
    if (trimmed.length() < 4 || trimmed.length() > 64) {
        throw new BizException("accessPassword must be 4-64 characters");
    }
    return passwordEncoder.encode(trimmed);
}
```
`ShortLinkService.java:810-819`

创建与更新共用这一个入口（doCreate :218 调用）。空串表示「不设密码」，存 null；非空则 4-64 字符后过 BCrypt。改密即换哈希，这为后面 cookie 的 `pwdVer` 机制埋下伏笔。清除密码走显式 `SET NULL`（update 路径 :497-502，注释 :497-499 解释 MyBatis-Plus 默认策略会跳过 null 字段）。

### 5.2 判定侧：密码门读行、读 cookie

`resolveTarget` 的第 10 步：

```java
if (link.getAccessPasswordHash() != null
        && !linkAccessTokens.validate(accessCookie(request, code), link.getId(), code, link.getAccessPasswordHash())) {
    meterRegistry.counter("linkforge.link.locked").increment();
    return new RedirectOutcome.Locked(link);
}
```
`ShortLinkService.java:691-695`

两个条件是「与」关系：行里有哈希，且 cookie 无效，才上锁。`accessCookie`（:724-736）遍历请求 cookie 找 `lf_ac_{code}`（名字来自 `LinkAccessTokens.cookieName` :38-40），找不到返回 null，`validate` 对 null 直接返回 false（`LinkAccessTokens.java:55-57`）。

### 5.3 unlock：两级限流先于 BCrypt

```java
RateLimitDecision perIp = rateLimiter.acquire(
        RateLimitKeys.sw(rl.getKeyPrefix(), "unlock:code:" + code + ":ip:" + ip),
        cfg.getUnlockIpMax(), cfg.getUnlockWindowSeconds());
if (!perIp.allowed()) {
    meterRegistry.counter("linkforge.link.unlock", "result", "rate_limited").increment();
    throw new RateLimitedException(perIp.retryAfterSeconds());
}
RateLimitDecision perCode = rateLimiter.acquire(
        RateLimitKeys.sw(rl.getKeyPrefix(), "unlock:code:" + code),
        cfg.getUnlockCodeMax(), cfg.getUnlockWindowSeconds());
if (!perCode.allowed()) {
    meterRegistry.counter("linkforge.link.unlock", "result", "rate_limited").increment();
    throw new RateLimitedException(perCode.retryAfterSeconds());
}
if (link.getAccessPasswordHash() == null) {
    return new UnlockResult(link, null);
}
if (password == null || password.isBlank()
        || !passwordEncoder.matches(password, link.getAccessPasswordHash())) {
    meterRegistry.counter("linkforge.link.unlock", "result", "bad_password").increment();
    return new UnlockResult(link, null);
}
```
`ShortLinkService.java:767-788`

方法 javadoc（:749-751）写明动机：brute force 绝不能换来 BCrypt 的 CPU（D-Bw345-34）。顺序是先 Bloom/空值缓存/行状态镜像跳转路径（:754-763），再两级限流（:767-780），最后才碰 BCrypt（:784-785）。两级窗口默认值：单 IP 对单码 5 次、单码全局 50 次，窗口 300 秒（`application.yml:224-226`，`LinkForgeProperties.java:64-67`）。按 IP 一级挡单点爆破，按码一级挡分布式爆破（换了 IP 也会撞上码级总额）。注意 BCrypt 失败返回的是 401 解锁页而不是异常，错误信息通过 `UnlockResult` 的 `cookieValue == null` 传递，meter 按结果分桶（rate_limited / bad_password / ok）。

### 5.4 签名 cookie：exp.HMAC(linkId|code|exp|pwdVer)

签发在 `issue`：

```java
public String issue(long linkId, String code, String passwordHash) {
    long exp = epochSecond() + ttlSeconds;
    String payload = payload(linkId, code, exp, pwdVer(passwordHash));
    return exp + "." + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(hmacSha256(hmacKey, payload.getBytes(StandardCharsets.UTF_8)));
}
```
`LinkAccessTokens.java:46-51`

cookie 值是 `过期秒.签名`，签名的输入由 `payload`（:77-79）拼成 `linkId|code|exp|pwdVer` 四段。`pwdVer` 是当前密码哈希的 SHA-256 前 8 字节（:82-89）。构造器（:31-36）从 JWT 密钥派生 HMAC 密钥（派生信息串 `linkforge:link-access:v1` 在 :25），javadoc :19-21 说明理由：不加新环境变量，且轮换 JWT 密钥顺带吊销全部解锁 cookie（D-Bw345-36）。

校验在 `validate`（:54-75）：先查格式（有点分且不在两端，:58-61），再解析过期秒并检查未过期（:62-70），然后本地重算 HMAC，与 cookie 里的签名做恒时比较 `MessageDigest.isEqual(provided, expected)`（:74）。恒时比较（Java 语言知识）：普通逐字节比较在第一个不同字节就返回，耗时差可被测量，攻击者据此逐字节猜签名（计时攻击）；`MessageDigest.isEqual` 的耗时只与长度相关、与内容无关，堵死这条信道。

改密即失效的机制在 `pwdVer`：签名输入绑定了「当前密码哈希的指纹」。改密后哈希变了，`pwdVer` 变了，旧 cookie 的签名对不上新指纹，`validate` 返回 false（D-Bw345-35，javadoc :17-18）。无需任何撤销表。

controller 侧把签发结果装进 `ResponseCookie`（`RedirectController.java:59-65`）：`Path` 限到 `/r/{code}`、`HttpOnly`、`SameSite=Lax`、`maxAge` 与签名 TTL 一致、`Secure` 跟随请求。解锁成功后 303 跳回 `/r/{code}`（:66-69），浏览器带 cookie 重走 GET 判定链。

### 5.5 B9：TargetPolicy 纯判定

```java
public static Verdict evaluate(String url, Config config, boolean forCreate) {
    URI uri = parse(url);
    String host = hostOf(uri);
    if (host != null) {
        if (matchesAny(host, config.blockDomains())) {
            return new Verdict(Action.BLOCK, Reason.BLOCK_LIST);
        }
        if (matchesAny(host, config.warnDomains())) {
            return new Verdict(Action.WARN, Reason.WARN_LIST);
        }
    }
    if (uri != null && uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
        return forCreate
                ? new Verdict(Action.BLOCK, Reason.USERINFO)
                : new Verdict(Action.WARN, Reason.USERINFO);
    }
    return Verdict.ALLOW;
}
```
`TargetPolicy.java:52-69`

四条规则一次看清。规则一与规则二：先查黑名单再查警示名单，BLOCK 永远压过 WARN（:56-61）——一个域名同时出现在两个名单里时按重的算。规则三 userinfo：`https://user@evil.com` 这种带用户信息的 URL 在创建/更新时直接拒（400），已存在的行只 WARN（`forCreate` 参数，:63-67；javadoc :48-51 点名 D-Bw345-25）。建链拒是因为创建入口能给出明确错误；存量 WARN 是因为已有链可能是历史误配，拦截页给用户选择权。规则四兜底 ALLOW。

标签边界匹配在 `matchesAny`（:121-128）：一行判定 `host.equals(entry) || host.endsWith("." + entry)`（:123）说了全部。`evil.com` 命中 `a.evil.com`（子域），不命中 `notevil.com`（`.entry` 前缀挡住），也不命中 `evil.com.cn`（那是另一个完整标签序列）。类注释 :11-15 举的就是这三个例子。

IDN 归一化在 `hostOf`（:85-103）：小写化（:93）、剥尾部点（:94-96）、`IDN.toASCII` 转 punycode（:98）。名单条目走同一条归一化管道 `normalizeEntry`（:108-119），保证「Unicode 名单条目」与「punycode URL 主机」落进同一个比较空间。URI 解析失败时 `parse` 返回 null（:76-83），host 为 null 跳过名单检查，最终 ALLOW——解析不了的 URL 宁可放过，交给 :665-671 的可渲染校验兜底。

### 5.6 PhishingPolicy：快照与热载

名单状态只有一个字段：`private volatile TargetPolicy.Config snapshot = TargetPolicy.Config.EMPTY;`（:34），是不可变 record。读侧 `evaluate`（:52-57）直接拿快照判定，开关关了返回 ALLOW。热载在 `reload`（:69-92）：每 30 秒（`reload-interval-ms`，`application.yml:219`）比一次文件 mtime，变了才重解析。坏文件的处理在 `parseFile`（:101-125）：任何读写或解析异常都打点 `reload{result=failed}` 并返回 null，调用方保留旧快照（:84-87），类注释 :22-23 给出方向：陈旧规则好过没有规则（fail-safe 保安全侧）。文件格式一行一个域名，`#` 注释，`!warn ` 前缀标记警示级（:110-113）；javadoc :24-25 写明刻意不提供 HTTP 管理接口：租户不得修改全局名单。配置与文件合并取并集（`merge`，:142-147），配置项默认为空列表（`LinkForgeProperties.java:77-78`；`application.yml:216-217` 留空，避免影响现有 IT 用例）。

### 5.7 GatePages：三张页面、一律转义、CSP 锁死

```java
public static final String CSP = "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'";
```
`GatePages.java:13`

三张页面共用这条 CSP：无脚本、无外链资源、无框架嵌入，只允许内联样式和提交到本站的表单。动态值全部过 `HtmlUtils.htmlEscape`：

- `warn` 页（:37-58）：目标 URL、ASCII 主机、短码三个值先转义再 `formatted` 进模板（:38-40）。页面给出唯一继续链接 `/r/{code}?lf_continue=1`（:54）。
- `unlock` 页（:61-83）：短码转义（:62），错误消息可空、非空时也转义（:63-64）。表单 POST 到 `/r/{code}/unlock`。
- `block` 页（:19-34）：纯静态文案，没有任何目标回显。

controller 给每个 HTML 响应补 `Cache-Control: no-store`、CSP、`X-Content-Type-Options: nosniff`（`RedirectController.java:92-111`），并由 `html()` 辅助方法（:120-122）显式设 `text/html;charset=UTF-8`，注释 :119 说明：否则 `StringHttpMessageConverter` 会按默认 `text/plain` 输出。

## 6. 技术原理补充

- **BCrypt（是什么/为什么/不用会怎样/本项目怎么用）**：自适应慢哈希。数据库泄露时，慢哈希让离线爆破代价陡增；用 MD5/SHA-256 这类快哈希，GPU 每秒能猜数十亿次。本项目在创建/更新时编码（:810-819），unlock 时 `matches`（:784-785）。缺点是慢，这正是它在判定链里必须排在限流之后的原因。
- **HMAC 签名 cookie vs 服务器会话**：会话把状态放服务端（内存或 Redis），天然可撤销但要查询；签名 cookie 把状态放客户端，服务端零存储、零查询，代价是撤销困难。本项目用 `pwdVer` 把「撤销」收窄成「改密即全量失效」，换取整条解锁路径无状态。恒时比较（Java 语言知识）见 5.4 节：不用它，签名校验存在计时侧信道。
- **IDN punycode（协议知识）**：DNS 协议只认 ASCII，中文等域名转成 `xn--` 前缀形式。不归一化的后果：名单写 `例子.com`、URL 写 `xn--fsqu00a.com`，字符串比对失配，名单形同虚设。
- **CSP（框架/浏览器行为）**：浏览器执行的声明式白名单。本项目页面无 JS、无外链，`default-src 'none'` 使「即使转义遗漏，注入的脚本也跑不起来」成为第二道防线。

## 7. 可视化

1. [../../diagrams/archify/html/12-redirect-decision.html](../../diagrams/archify/html/12-redirect-decision.html)：步骤 9（B9 BLOCK）、10（密码门）、11（B9 WARN）三个节点即本课主干；解锁后的回流（303 → 带 cookie 重走 GET）对应 unlock 分支。
2. [../../diagrams/archify/html/02-function-map.html](../../diagrams/archify/html/02-function-map.html)：功能地图的跳转域，可见「密码解锁」与「域名拦截」两条能力叶。

## 8. 项目中的真实执行过程

场景一：密码链从设密到解锁。

1. 用户创建链时带 `accessPassword: "abc12345"`，`encodeAccessPassword` :810-819 产出 BCrypt 哈希入行。
2. 访客 `GET /r/Ab3xK9m`，判定链走到第 10 步，行有哈希且无 cookie，返回 `Locked`（:694），`render` 出 200 解锁页；访客提交表单 `POST /r/Ab3xK9m/unlock`（`RedirectController.java:52-56`）。
3. `unlock` :753-792：Bloom/行状态镜像通过，IP 限流 5 次窗口内放行，码级限流放行，BCrypt `matches` 成功，meter `unlock{result=ok}`，签发 cookie。
4. controller 组装 `Set-Cookie`（:59-65）并以 303 跳回（:66-69）；浏览器重发 GET，cookie 有效，第 10 步通过，继续到 302。

场景二：名单命中。管理员把 `evil.com` 写进黑名单文件，30 秒内 `reload` 热载。此后任何指向 `evil.com` 及其子域的链，判定链第 9 步命中 BLOCK，返回 403 加 `GatePages.block()` 页面，无 `Location` 头、无目标回显（:684-688 与 `RedirectController.java:91-97`）。若该链同时带密码，用户永远看不到解锁页：BLOCK 在前。

场景三：警示页确认。指向 WARN 名单域名的链返回 200 警示页，页面显示转义后的目标与主机。用户点「仍要继续」，带 `lf_continue=1` 的 GET 在第 11 步走 continue 分支（:697-699），打点后继续 302。

## 9. 为什么这样设计

**限流先于 BCrypt。** BCrypt 一次验证的成本是设计出来的「慢」。若先验密码再限流，攻击者用垃圾请求就能把服务端 CPU 打满：每个请求都白付一次 BCrypt。先限流，超限请求连 BCrypt 的门都摸不到。两级键的设计（按 IP + 按码）覆盖两种攻击形态：单点高频与低速分布式。

**无状态 cookie 配 pwdVer 指纹。** 传统做法是服务端会话表，撤销容易但要存储与查询。本项目选签名 cookie，把撤销问题转译成「签名输入是否还成立」：`pwdVer` 绑定当前密码哈希，改密、清密都改变哈希，全部旧 cookie 立即失效。推断：选 8 字节指纹而非完整哈希，是在防碰撞需求（指纹只需区分「同一行的不同密码版本」）与签名输入长度之间取平衡。

**B9 放在 service 内、名单供数放组件内。** `rejectBlockedTarget`（:798-807）在创建/更新时把关，BLOCK 拒绝的 400 消息固定为 "target domain is not allowed by policy"（:802），不回显命中的域名，避免向探测者确认「某个域名在名单里」。跳转侧判行的 URL 而非缓存值（:684），创建侧判 UTM 合并后的最终 URL，两侧口径一致。

**GatePages 不用模板引擎。** 生产配置关掉了静态资源映射，模板引擎只会多一层依赖与注入面。三张页面总共 84 行 Java 字符串模板，转义显式、CSP 显式，安全审查一眼到底。

## 10. 本节总结

- 密码存储：BCrypt，4-64 字符，清除走显式 SET NULL。
- 解锁协议：Bloom/行状态镜像 → IP 级限流（5/300s）→ 码级限流（50/300s）→ BCrypt → 签发 cookie。
- cookie：`exp.HMAC-SHA256(key, linkId|code|exp|pwdVer)`，密钥由 JWT 密钥派生，恒时比较，改密即失效。
- B9：纯函数判定，标签边界后缀匹配，IDN 双侧归一，BLOCK > WARN，userinfo 建链拒/存量 WARN；名单 volatile 快照加 mtime 热载，坏文件保旧，无管理接口。
- 页面：三张 Java 字符串模板，动态值全转义，CSP `default-src 'none'`。

## 11. 课后练习

**练习 1（概念理解）**：unlock 的两级限流为什么都要在 `passwordEncoder.matches` 之前？调换顺序会发生什么？

答案解析：`ShortLinkService.java:767-780` 两个限流在前，:784-785 才验密码。BCrypt 的耗时是算法刻意放大的（javadoc :749-751 点名 D-Bw345-34）。调换后，每个请求无论是否超限都要先付一次 BCrypt CPU，攻击者可以用合法形状的请求消耗服务器算力，限流形同虚设。meter 计数也印证顺序：rate_limited 分支（:771、:778）出现时绝不会走到 bad_password 分支。

**练习 2（源码定位）**：在 `LinkAccessTokens.java` 里找出「改密即失效」的三行代码，并解释为什么改的是哈希、失效的是 cookie。

答案解析：:82-89 的 `pwdVer` 对传入哈希做 SHA-256 取前 8 字节；:48 签发时和 :72-73 校验时都把它拼进 HMAC 输入。改密后行里换了新 BCrypt 哈希，`validate` 用新哈希算出的 `pwdVer` 与旧 cookie 签名所用指纹不同，重算的 HMAC 对不上，:74 返回 false。cookie 本身没变，变的是验证基准。

**练习 3（执行过程）**：黑名单里有 `evil.com`。判断以下三个 URL 哪些被 BLOCK：`https://a.evil.com/x`、`https://notevil.com/`、`https://evil.com.cn/`，并指出对应的匹配代码。

答案解析：第一个 BLOCK：`a.evil.com`.endsWith(`.evil.com`) 成立（`TargetPolicy.java:123` 第二个分支）。后两个 ALLOW：`notevil.com` 不等于 `evil.com` 也不以 `.evil.com` 结尾（子串 `evil.com` 出现在 `notevil.com` 中，但标签边界挡住）；`evil.com.cn` 的最后一个标签序列是 `com.cn`，与条目 `evil.com` 既不相等也无点前缀关系。类注释 :11-15 正是这三个例子。

**练习 4（设计分析）**：把 blocklist 文件改成语法错乱的内容后重启前会发生什么？meter 上看什么？

答案解析：`reload` 比对 mtime 发现变化，调 `parseFile`（:84）。解析抛异常被 :116-121 捕获，打点 `linkforge.phishing.reload{result=failed}` 并返回 null，:85-87 直接 return，快照保持旧值。进程重启则是另一回事：`loadInitial`（:37-50）在文件解析失败时仍以配置项名单（可能为空）构造快照，所以「坏文件保旧」只保运行中的旧快照，重启后旧文件内容不可恢复。设计注释 :22-23 的「陈旧规则好过没有规则」描述的是运行期方向。

**练习 5（调用链）**：解锁成功后浏览器看到的是 303 而不是 302，为什么？

答案解析：unlock 端点返回 `HttpStatus.SEE_OTHER`（303，`RedirectController.java:66-69`），语义是「去别处再发一次 GET」。它同时带 `Set-Cookie`。浏览器收到 303 后对 `/r/{code}` 发起新 GET，这次判定链第 10 步验 cookie 通过，最终由 `render` 的 Redirect 分支给出真正的 302。303 与 302 各司其职：前者是表单提交后的重定向惯例（防表单重复提交语义），后者才是跳转本体。

## 12. 与后续课程的联系

- 7.3 课讲第 12 步：门禁全过后的权重抽签，解锁后的请求同样参与抽签。
- 8.2 课解释为什么改密不需要清缓存：密码门读的是行（:691 注释「无缓存 TTL 参与」），写路径的双删与广播只影响 URL 提示。
- 9.1 课实现本课用到的 `RedisRateLimiter`：两级限流的滑动窗口日志算法与 Lua 脚本。
- 警示页与解锁页不计点击：Locked 与 Warn 都不是 `Redirect`，`resolveForGet` :567 的 instanceof 守卫挡住了记录路径；模块 11 的统计只看到真正 302 的请求。
