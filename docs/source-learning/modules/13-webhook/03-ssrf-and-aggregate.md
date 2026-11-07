# 13.3 SSRF 防护：三层钉死 DNS rebinding

> 投递链的最后一跳是本服务器向用户填写的 URL 发 HTTP POST，这是整个项目里唯一一处「服务器主动向外部地址发请求」，也是 SSRF（服务端请求伪造）的经典攻击面。本课拆开三层防护：保存时校验、发送时一次解析校验、连接时钉死解析结果，并回答两个问题：为什么 JDK 自带的 HttpClient 不行，为什么这套防护与跳转侧的 B9 门禁互不复用。

## 1. 本节学习目标

1. 说出 webhook URL 这个攻击面上攻击者能做什么，以及为什么「保存时校验一次」挡不住 DNS rebinding。
2. 逐段读懂 WebhookTargetGuard.check 与 resolveForSend，背出 IP 黑名单的完整清单（含 CGNAT 与 ULA）。
3. 解释 IPv6 内嵌 IPv4 的回退规则，说出 ::ffff:、::/96、64:ff9b::/96、2002::/16 四种形态各自怎么处理。
4. 读懂 PinnedHttpSender 的 PinnedDnsResolver，解释「校验与连接之间没有第二次解析窗口」的机制，以及禁重定向、保 Host/SNI 的原因。
5. 划清本防护与 B9 域名门禁的分工边界，并指出诚实的未覆盖项。

## 2. 前置知识

- SSRF：攻击者让服务器替他发请求。webhook 场景里用户填写回调 URL，服务器照着发，如果用户填 `http://169.254.169.254/...`（云厂商元数据服务）或内网地址，服务器就成了他的内网探头。
- DNS rebinding 与 TOCTOU：TOCTOU 是 time-of-check to time-of-use，「检查时」与「使用时」之间状态被换掉。DNS rebinding 是它的网络版：域名第一次解析返回公网 IP，通过校验；同一域名第二次解析返回内网 IP，服务器拿着内网 IP 建连。攻击者控制自己域名的 DNS 应答就能做到，DNS 协议本身不防。
- IPv6 内嵌 IPv4：几种 IPv6 地址形态把 32 位 IPv4 装进地址尾部（如 ::ffff:127.0.0.1、64:ff9b::7f00:1）。只做 IPv4 黑名单会漏掉「穿上 IPv6 外衣的内网地址」。
- SNI 与证书校验：TLS 握手时客户端报 SNI（服务器名称指示）告诉对方要连哪个域名，证书校验也对着这个域名。如果「域名解析换了 IP 连接」时把 SNI 一并换掉，中间人伪装与内网误连都会失控。

## 3. 本节涉及的源码

| 文件 | 行数 | 职责 | 阅读顺序 |
|----|----|----|----|
| `src/main/java/com/linkforge/webhook/support/WebhookTargetGuard.java` | 169 | URL/IP 校验与一次解析（本课主角一） | 1 |
| `src/main/java/com/linkforge/webhook/support/PinnedHttpSender.java` | 101 | 钉死 DNS 的发送器（本课主角二） | 2 |
| `src/main/java/com/linkforge/webhook/service/WebhookDeliveryRelay.java` | 167 | 发送时调用点（`:99-108`） | 3 |
| `src/main/java/com/linkforge/webhook/service/WebhookService.java` | 327 | 保存时调用点（`:87`） | 4 |
| `src/test/java/com/linkforge/webhook/support/WebhookTargetGuardTest.java` | 137 | 10 个校验单测 | 5 |
| `src/test/java/com/linkforge/webhook/support/WebhookRebindingIT.java` | 281 | 3 个钉死回归测试 | 6 |

配置只有一个开关：`application.yml:168-169` 的 `allow-private-targets`（默认 false，注释标 IT-only）。README 的口径在 `README.md:521`（SSRF 防护与诚实限制）与 `README.md:447`（与 B9 的分工）。

## 4. 类的定位

support 包的这两个类组成 webhook 出站安全的全部。WebhookTargetGuard 是纯判断器：给 URL，回答「违反哪条规则」或「这些解析地址可用」。PinnedHttpSender 是纯执行器：拿 guard 给的地址列表发请求，保证 socket 只连列表内的 IP。两者被两个调用点串起来：保存时 WebhookService 调 `check`（`WebhookService.java:87`），发送时 WebhookDeliveryRelay 调 `resolveForSend`（`WebhookDeliveryRelay.java:99`）。类注释（`WebhookTargetGuard.java:14-25`）一句话概括设计：resolveForSend 把 host 恰好解析一次，sender 把连接钉死在这些地址上，resolve-then-connect 的 TOCTOU（DNS rebinding，决策号 D-C4-1）就此关闭。

与 B9 的分工写在 README（`README.md:447`）：Guard 防**服务端出站请求**，解析 DNS 后拒绝私网与元数据 IP；B9（PhishingPolicy，模块 7.2）管**浏览器去访问的目标域**，私网目标照常允许，因为企业内网短链是合法用法。两个门禁解决两个方向的风险，互不复用：把 Guard 套到跳转上会误杀内网短链，把 B9 套到 webhook 上则完全不设防（B9 根本不看解析出的 IP）。

聚合模式的 digest 投递行（13.2 的 5.9）与逐条投递行走同一条路，本课的三层防护对两类事件一体生效，下面不再区分事件类型。

## 5. 核心源码详解

### 5.1 攻击面与第一层：保存时 check 拒成 400

第一层防护是保存时的静态校验（`WebhookService.java:87` 的 `targetGuard.check(url)`）：

```java
/** Save-time check: raises 400 on a blocked target. */
public void check(String url) {
    String reason = violationOf(url);
    if (reason != null) {
        throw new BizException(400, "webhook target not allowed: " + reason);
    }
}
```
（`WebhookTargetGuard.java:44-50`）

用户保存 `http://127.0.0.1:8080/admin` 这类目标当场被拒，得到 400 与原因文案。这一层的价值是把明显的误配置挡在持久化之前。但它单独不构成防线：域名是租户的，DNS 是攻击者可操纵的，保存那一刻合法的公网域名，投递那一刻可以翻转成内网地址。这就是为什么还需要后两层。

### 5.2 第二层：resolveForSend 一次解析与全 IP 校验

```java
public ResolvedAddresses resolveForSend(String url) {
    URI uri;
    try {
        uri = URI.create(url);
    } catch (IllegalArgumentException ex) {
        return ResolvedAddresses.blocked("unparseable url");
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
    if (!scheme.equals("http") && !scheme.equals("https")) {
        return ResolvedAddresses.blocked("scheme must be http or https");
    }
    if (uri.getUserInfo() != null) {
        return ResolvedAddresses.blocked("userinfo is not allowed");
    }
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
        return ResolvedAddresses.blocked("missing host");
    }
    InetAddress[] addresses;
    try {
        addresses = resolveHost(host);
    } catch (UnknownHostException ex) {
        return ResolvedAddresses.blocked("host does not resolve");
    }
    if (properties.getWebhook().isAllowPrivateTargets()) {
        // IT-only escape hatch; never true in prod
        return ResolvedAddresses.pinned(addresses);
    }
    for (InetAddress address : addresses) {
        if (violationOf(address) != null) {
            // Bw2-N6: the reason must not echo resolved addresses — one uniform reason
            return ResolvedAddresses.blocked("blocked target");
        }
    }
    return ResolvedAddresses.pinned(addresses);
}
```
（`WebhookTargetGuard.java:57-92`）

方法返回一个 record（`:33-42`）：要么 `violationReason` 非空，要么 `pinned` 装着校验通过的地址数组，两者互斥。读法分五段。

一，URL 结构规则（`:58-74`）：解析失败、scheme 不是 http/https、带 userinfo（`user:pass@host` 形态可以伪装真实目标）、没有 host，四种一律 blocked。scheme 与 userinfo 规则在 escape hatch 开着时也生效（单测 `allowPrivateTargetsEscapeHatchOnlySkipsIpChecks`，`WebhookTargetGuardTest.java:49-57` 断言 ftp 与 userinfo 照样拒）。

二，解析（`:75-80`）：`resolveHost` 是包私有方法（`:166-168`）直接包 `InetAddress.getAllByName`，解析失败 blocked。它被留成可覆盖的接缝，单测与 IT 都靠覆盖它喂假地址（`WebhookTargetGuardTest.java:115-128` 的匿名子类、`WebhookRebindingIT.java:90-91` 的 @SpyBean），测试全程不碰真实 DNS。

三，escape hatch（`:81-84`）：`allow-private-targets` 开着就跳过 IP 检查直接放行。注释写明 IT-only，生产必须 false（`application.yml:168-169`）。注意它只跳 IP 检查，scheme/userinfo/host 规则照旧。

四，全 IP 校验（`:85-90`）：遍历**每一个**解析结果，任一违规整条 URL blocked。只查第一个地址会漏掉多 A 记录域名里混进的私有 IP。

五，统一理由（`:87-88`）：违规时不回显解析到的地址，统一报 `blocked target`（决策号 Bw2-N6）。这与 relay 侧日志不回显（`WebhookDeliveryRelay.java:104-106`，决策号 D-C4-8）是同一条纪律的两端：如果 400 文案或日志写出「127.0.0.1 被拒」，攻击者就把校验器当内网扫描器用，逐个 IP 试出哪些地址存在。单测 `blockedReasonDoesNotEchoResolvedAddresses`（`WebhookTargetGuardTest.java:97-106`）断言文案里不含具体 IP。

### 5.3 IP 黑名单：JDK 五谓词加四段扩展

`violationOf(InetAddress)`（`WebhookTargetGuard.java:99-136`）是黑名单本体。第一组用 JDK 内建的五个判断（`:100-104`）：环回（loopback，127.0.0.0/8 与 ::1）、任意本地（anyLocal，0.0.0.0 与 ::）、组播（multicast）、链路本地（linkLocal，169.254.0.0/16，云元数据服务 169.254.169.254 就在这个段）、站点本地（siteLocal，RFC1918 私网 10/8、172.16/12、192.168/16 与 IPv6 fe80::/10）。

第二组是 JDK 没有判断的四段 IPv4 扩展（`:106-120`）：

```java
if (bytes[0] == 0) {
    return "this-network block (0.0.0.0/8)";
}
if ((bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 64) {
    return "shared address space (CGNAT)";
}
if ((bytes[0] & 0xFF) == 198 && (bytes[1] & 0xFE) == 18) {
    return "benchmarking block (198.18.0.0/15)";
}
if ((bytes[0] & 0xF0) == 0xF0) {
    return "reserved block (240.0.0.0/4)";
}
```
（`WebhookTargetGuard.java:109-120`）

0.0.0.0/8 是「本网络」段，历史上有过用 0.0.0.0 绕过环回检查的攻击。CGNAT 100.64.0.0/10 是运营商级 NAT 段，很多企业内网与云 VPC 落在里面。198.18.0.0/15 是基准测试保留段，本地代理工具（Clash/Surge 的 fake-IP 模式）默认用它，README 专门提醒（`README.md:272`）：本机开着 fake-IP 联调，保存公网 webhook 目标会得到 400，因为解析出的假 IP 落在这段。240.0.0.0/4 是保留段含广播地址。单测把清单钉死（`WebhookTargetGuardTest.java:31-38` 的 11 个必拒 IP、`:71-77` 的四个扩展段）。

IPv6 侧单独拒绝 ULA fc00::/7（`:132-134`），这是 IPv6 的私网等价物。整个方法的拒绝面可以背成一句：环回、任意本地、组播、链路本地、站点本地、0/8、CGNAT、198.18/15、240/4、ULA，十类。

### 5.4 IPv6 内嵌 IPv4 的回退规则

IPv4 黑名单再严，攻击者只要把内网地址包进 IPv6 形态就绕过了，除非系统认识这些形态。`embeddedIpv4`（`:142-164`）处理三种：

- 全零前缀形态（`:143-154`）：::/96 兼容形态（::127.0.0.1）与 ::ffff:0:0/96 映射形态都由尾部 4 字节携带 IPv4。注释（`:151-153`）说明 JDK 已把 ::ffff:a.b.c.d 折叠成 Inet4Address，所以这段代码实际只见到 ::/96 形态，两种都取尾部 4 字节。
- NAT64 形态（`:155-158`）：64:ff9b::/96，前 96 位是固定值，尾部 4 字节是 IPv4。
- 6to4 形态（`:160-162`）：2002::/16，第 3 到 6 字节是 IPv4。

提取出内嵌 IPv4 后**递归回用 IPv4 规则**（`:123-131` 的 `violationOf(InetAddress.getByAddress(embedded))`）：`64:ff9b::a9fe:a9fe` 内嵌 169.254.169.254，被拒；`64:ff9b::808:808` 内嵌公网 8.8.8.8，放行（单测 `publicNat64TargetIsAllowed`，`WebhookTargetGuardTest.java:89-94`）。回退规则的意义是「外衣不改变身份判断」，三种包装形态单测齐全（`WebhookTargetGuardTest.java:80-86`）。

### 5.5 第三层：PinnedHttpSender 把解析钉死

前两层做完，手里是一组校验过的地址。最后一层的任务是：连接时不得重新解析。先看为什么换了实现，类注释（`PinnedHttpSender.java:21-30`）把 JDK HttpClient 的三个问题写全：它在连接时自己解析 DNS，guard 的校验与它的连接之间有第二次解析窗口；它的 Host 头受限，不能保原 hostname；它连到选定 IP 时没法对原 hostname 做证书校验。Apache HttpClient 5 的 DnsResolver 可插拔，三个问题一并解决。

```java
private record PinnedDnsResolver(String host, InetAddress[] addresses) implements DnsResolver {

    @Override
    public InetAddress[] resolve(String target) throws UnknownHostException {
        if (!target.equalsIgnoreCase(host)) {
            throw new UnknownHostException(target);
        }
        return addresses;
    }

    @Override
    public String resolveCanonicalHostname(String target) throws UnknownHostException {
        resolve(target);
        return target;
    }
}
```
（`PinnedHttpSender.java:85-100`）

钉死的机制就这十几行：对钉住的 host，解析永远返回 guard 给的地址数组；对任何其他 host，直接抛 UnknownHostException。请求带着**原始 URI** 发（`:67`），HttpClient 内部无论因重定向、代理还是别的什么想解析任何主机名，要么拿到同一组已校验地址，要么当场失败。「校验通过后 DNS 再改」没有可以发生的窗口，因为除了这个解析器，client 根本没有别的 DNS 通路。

send 方法（`:50-78`）还有三个值得停留的细节。其一，每次投请新建 client（`:61-66`），注释（`:44-49`）解释：钉住的地址集存在连接管理器里，投递频率是秒级退避的低流量，共享跨线程的 per-route 钉定状态不划算，泄漏的 client 状态反而危险。其二，禁跟随重定向（`:57-59`，决策号 D-C4-6）：302 的 Location 是新的目标，跟随等于对未校验地址发请求，必须把整个 resolve+validate+pin 流程重跑才有资格跟；干脆禁掉。其三，`setExpectContinueEnabled(false)`（`:56`）保持与被替换的 JDK client 相同的线上行为，注释点明这是刻意对齐。

返回值是密封接口（`:35-42`）：`Responded(statusCode)` 或 `Failed(IOException)`，relay 的 retry 分流（13.2 的 5.4）就建立在两态穷尽上。

### 5.6 诚实边界：没做的两件事

类注释（`WebhookTargetGuard.java:23-25`）与 README（`README.md:521`）都承认两个未覆盖项。其一，无端口白名单：80、443 之外的端口（比如内网 Redis 的 6379）不被拒绝，黑名单只看 IP。其二，无出口代理隔离：webhook 出站与业务共享网络位置，理想形态是 webhook 流量走独立 egress，被攻破的 URL 也摸不到内网。两者都属「后置」决策：当前威胁模型（租户是半信任方，内网无敏感服务可探）下收益不抵复杂度。配套的测试开关也属于诚实边界的一部分：`allow-private-targets` 只在 IT 里打开，生产配置保持 false。

### 5.7 测试如何钉死这三层

单测 WebhookTargetGuardTest 覆盖规则面：10 个测试从 scheme/userinfo（`:21-28`）、11 个必拒 IP（`:31-38`）、映射形态（`:41-46`）、escape hatch 的边界（`:49-57`）、不可解析（`:60-68`）、扩展段（`:71-77`）、内嵌形态（`:80-86`）、公网 NAT64（`:89-94`）、不回显（`:97-106`）到 check 抛 400（`:109-113`）。

IT WebhookRebindingIT 把「钉死」变成可断言的行为，三个测试都在共享上下文里 @SpyBean 替换 `resolveHost`（`:90-91`），不碰真实 DNS。第一个测试 `toctouDeliveryNeverReachesUnvalidatedIp`（`:178-207`）最精彩：endpoint 填 `http://localhost:port/hook`，spy 让 guard 的解析永远返回公网 IP 93.184.216.34（`:182-183`）。保存时 IP 检查通过（公网），投递时钉死到公网 IP。断言四件事：行先结算（`:190-195`）、最终 FAILED（`:198-199`）、attempts 耗满 3 次（`:200-201`）、桩（真实监听在 127.0.0.1）收到的请求数为 0（`:202-203`）。测试内注释（`:180-181`）点明对照：JDK 风格的 client 会把 localhost 重新解析成 127.0.0.1 并打到桩上，那个 TOCTOU 窗口在这个断言下永远关闭。第二个测试（`:209-246`）反向：投递时解析变成私网 10.0.0.5，关掉 escape hatch，断言 FAILED、`linkforge.webhook.blocked` 计数上涨（`:232-234`）、last_error 恰是统一文案 `blocked target`（`:236-238`）。第三个测试（`:248-275`）验证超时重试：桩睡 2000ms 超过 1000ms 请求超时，三次尝试全部真实到达端点（`:271-274`），期间另一租户的跳转 302 不受影响（`:258-263`），呼应 13.2 的调度隔离。三个测试所在类标 `@DirtiesContext(AFTER_CLASS)`（`:67-69`），理由是 relay 线程若活过类边界，会拿错误配置去结算别的类的行。

## 6. 技术原理补充

**SSRF 防护的两段论（行业共识，本项目实现）。** 入口校验（scheme、userinfo、host）与出口校验（解析出的 IP）必须分离。只做入口，攻击者用自己控制的 DNS 绕过；只做出口，畸形 URL 与 userinfo 伪装漏进。本项目入口规则无开关，出口规则有测试开关，权重分配与威胁一致。

**DNS rebinding 的封锁点（本项目实现）。** 通用建议（校验后设短 TTL、复用解析结果）都是缩小窗口，钉死解析是消除窗口。消除的办法是接管解析通道：应用自己解析一次，把结果作为唯一事实源交给网络层。DnsResolver 接口正好是 HttpClient5 留的这个接管点。任何「校验与使用分离」的场景都可以套这个模式。

**为什么不跟随重定向（协议语义，本项目实现）。** 重定向是服务器指挥客户端去访问新地址的机制。对 webhook 发送方，跟随 Location 意味着把「向哪发」的决定权交给对端，而目标校验是对原 URL 做的。GitHub 与 Stripe 的 webhook 同样不跟随。接收方要换地址应该换 endpoint 配置，而不是发 302。

**密封接口表达互斥结果（Java 17 特性，本项目实现）。** Result 只有 Responded 与 Failed 两个实现，switch 与 instanceof 的穷尽性由编译器保证。这个语言点与 7.1 跳转判定链的 RedirectOutcome 同款：结果空间封闭时，密封接口比布尔加可空字段少一半错误状态。

## 7. 可视化

1. [05-database-erd](../../diagrams/archify/html/05-database-erd.html)：lf_webhook_endpoint.url 列（2048 长度）是本课全部防护的输入。
2. [04-core-class-relations](../../diagrams/archify/html/04-core-class-relations.html)：WebhookDeliveryRelay 与 PinnedHttpSender 的支撑关系在类卡片里（图主体把它们移入卡片保持主链清晰）。
3. 三层防护流：

```mermaid
flowchart TD
    A["租户 URL"] --> B["第一层 保存时 check<br/>WebhookService:87 → 400"]
    B --> C["relay 选中投递行"]
    C --> D["第二层 resolveForSend<br/>scheme/userinfo/host 规则"]
    D --> E["一次解析 getAllByName"]
    E --> F{"allow-private<br/>hatch?"}
    F -- 开 --> G["跳过 IP 检查直接 pinned"]
    F -- 关 --> H{"全 IP 过十类黑名单?<br/>含内嵌 IPv4 回退"}
    H -- 违规 --> I["FAILED 'blocked target'<br/>统一文案不回显"]
    H -- 通过 --> J["第三层 PinnedDnsResolver<br/>连接只到 pinned 地址"]
    J --> K["POST 原始 URI<br/>Host/SNI/TLS 保 hostname"]
    K --> L["302 不跟随"]
```

## 8. 项目中的真实执行过程

推演一次真实形态的 DNS rebinding 攻击，看三层怎么接住。攻击者租户保存 `http://rebind.attacker.example/hook`，他的 DNS 服务器配置为：第一次查询返回 93.184.216.34（公网），此后查询返回 10.0.0.5（内网）。

1. 保存时第一层 check：解析得 93.184.216.34，公网，通过。endpoint 落库。
2. 有点击进入投递。relay 调 resolveForSend：此刻攻击者把 DNS 切到 10.0.0.5，`getAllByName` 返回 10.0.0.5。第二层全 IP 校验发现站点本地地址，整条 blocked，投递行 FAILED，last_error 只有 `blocked target` 五个词，meter `linkforge.webhook.blocked` 加一。攻击者从文案读不出任何内网拓扑。
3. 攻击者换时序：DNS 在 resolveForSend 解析时仍是公网，投递行通过校验拿到 pinned=[93.184.216.34]。他期望「到连接时再切回内网」赢下竞速。第三层接住：PinnedDnsResolver 对这个 host 永远返回 pinned 数组，攻击者后续的 DNS 答案根本进不了流程，连接只发往 93.184.216.34。第二次解析窗口不存在。
4. 三次尝试失败，行进 FAILED（对 WebhookRebindingIT 第一个测试的同构场景，只是那里把「公网 IP」换成 spy 注入，把「连接失败」换成真实断言）。
5. 若攻击者改走重定向：接收方（他在公网的服务器）回 302 指向 `http://10.0.0.5/`，`setRedirectsEnabled(false)` 让这个 Location 死在原地，投递以 302 状态码结算，302 不属于 2xx 也不属于 408/429/5xx，按其他 4xx 语义 FAILED。

## 9. 为什么这样设计

- **三层各挡一类绕过，叠加成闭环。** 只有保存校验，DNS 翻转绕过；只有发送校验，竞速与重定向绕过；只有钉死，畸形 URL 与 userinfo 绕过。三层共享一个规则源（violationOf），文案统一，成本是每次投递一次解析，收益是把「服务器可被指使访问哪里」收敛为白名单语义（只连校验过的地址）。
- **统一文案是主动的信息纪律。** 错误信息是攻击者的免费探测器。`blocked target` 五个词（`:88`）让被拒与「为什么被拒」解耦，排障需求由 metrics 标签与日志（服务端侧可见、攻击者不可见）承担。这条纪律在 13.2 的 relay 日志（D-C4-8）与 5.3 节 API Key 的「同文案不同标签」是同一思想在三个地方的落点。
- **escape hatch 开关只削 IP 检查这一刀。** 测试要能对 127.0.0.1 起的本地桩做端到端投递，但 scheme/userinfo/host 规则与钉死机制在任何配置下都不能松动。一刀切的总开关会让测试环境悄悄失去对另一半防护的覆盖。注释与 yml 双处标注 IT-only，README 的环境变量表（`README.md:593`）同样只给 false 默认。
- **换 HttpClient 是为可插拔的解析通道。** 修 rebinding 的最小改动是把校验后的地址缓存下来，但 JDK client 没有注入点，缓存了也会被它内部的重解析旁路。换到有 DnsResolver 的实现，防护从「尽力缩小窗口」升级为「结构上不存在窗口」。类注释把这段对比留下来（`PinnedHttpSender.java:21-30`），后来者不会误以为这是框架偏好。

## 10. 本节总结

Webhook 的出站请求有三层。保存时 check 把 scheme/userinfo/host 与 IP 违规拒成 400。发送时 resolveForSend 只解析一次，对全部解析地址过十类黑名单（环回、任意本地、组播、链路本地、站点本地、0/8、CGNAT、198.18/15、240/4、ULA），内嵌 IPv4 的三种形态回退 v4 规则，违规只回 `blocked target` 不回显地址。连接时 PinnedDnsResolver 把 client 的解析通道整个接管，socket 只到已校验地址，Host/SNI/TLS 保原 hostname，重定向一律不跟。诚实边界：无端口白名单、无出口代理，escape hatch 仅测试。与 B9 的分工：这里防服务端出站，B9 防浏览器目标，互不复用。

## 11. 课后练习

**练习 1（概念理解）。** 为什么保存时的 check 不能省、但也不能只有它？

参考答案：不能省，因为它是用户反馈环最近的一层，明显的误配置（填了 127.0.0.1）当场 400，不用等到投递失败才发现。不能只有它，因为校验发生在一个时刻、连接发生在另一个时刻，中间的 DNS 应答攻击者可控（TOCTOU）。两层之间还隔着 endpoint 的生命周期，域名易主、DNS 策略变化都发生在保存之后，所以发送时必须重新解析并校验（`WebhookTargetGuard.java:14-20`）。

**练习 2（源码定位）。** 攻击者保存 `http://user:pass@169.254.169.254/latest/meta-data/`，在哪个方法哪几行被挡下？给出的 reason 是什么？

参考答案：save 时 `WebhookTargetGuard.check`（`:44-50`）内部走 resolveForSend，`uri.getUserInfo() != null` 在 `:68-70` 命中，reason 为 `userinfo is not allowed`，BizException 400 文案是 `webhook target not allowed: userinfo is not allowed`。userinfo 规则在 escape hatch 开着时也生效（`WebhookTargetGuardTest.java:49-57`）。即使没有 userinfo，169.254.169.254 也过不了 linkLocal 判断（`:100-104`）。

**练习 3（执行过程）。** resolveForSend 解析得到 [10.0.0.5, 93.184.216.34]（多 A 记录，一私一公），结果如何？如果代码改成只检查 addresses[0] 会怎样？

参考答案：遍历检查（`:85-90`）在 10.0.0.5 命中站点本地，整条 blocked，返回统一的 `blocked target`，公网地址也一并作废。改成只查第一个：解析顺序若公网在前，校验通过并 pinned，但 sender 钉死的是整个数组，连接可能轮询到 10.0.0.5（HttpClient 连接池按地址试连），SSRF 成立。全 IP 校验与全数组钉死必须配套。

**练习 4（调用链）。** WebhookRebindingIT 第一个测试里，桩在 127.0.0.1 监听、guard 的 spy 返回公网 IP、escape hatch 开着。为什么最终 stubHitsFor 为 0？

参考答案：escape hatch 只跳过第二层的 IP 检查（`:81-84`），保存因此通过；spy 让 resolveForSend 返回 [93.184.216.34]。第三层 PinnedDnsResolver 对该 host 的任何解析都返回这个公网数组（`PinnedHttpSender.java:88-93`），连接发往 93.184.216.34 而非 127.0.0.1，三次尝试全部连接失败，行进 FAILED、attempts=3（`:198-201`），桩从头到尾没被碰到（`:202-203`）。测试证明的是结构：解析通道被接管后，localhost 再也没有机会变回 127.0.0.1。

**练习 5（设计分析）。** 若给 webhook 加端口白名单（只许 443），会拦住哪些今天合法的用？README 说它「后置」，代价是什么？

参考答案：本地联调（IT 桩用随机端口，如 `http://127.0.0.1:9/hook`）、内网接收方的 8080 等常见回调、走非标准端口的 SaaS 全部被拒，同时逃过端口的攻击仍在（例如 443 上的内网 HTTPS 服务）。README 的取舍（`WebhookTargetGuard.java:23-25`）是先保 IP 面（元数据与内网探测的主要面），端口面用「租户是半信任方」的威胁模型兜住。代价是放行的 URL 仍可探测内网高端口服务，这是文档承认而非隐藏的边界。

## 12. 与后续课程的联系

13.1 的保存链、13.2 的投递链在本课合龙：每个 URL 先过 check，每条投递行都从钉死的通道出去。模块 7.2 的 B9 门禁是镜像面（防浏览器被短链指去恶意域），对照读能理解「同一个 URL，服务端访问与用户访问需要两套规则」。本课的 @DirtiesContext 陷阱与时区 fork 一样属于测试基建，15.1 的测试金字塔与 14.3 的定时任务地图会再收拢。综合演练见专题「Webhook 投递与重试」（`docs/source-learning/advanced/`）。
