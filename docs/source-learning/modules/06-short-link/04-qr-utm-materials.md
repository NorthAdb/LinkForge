# 6.4 QR 物料与 UTM：渲染、签名分享链、写时归因合并

> 本课回答一个问题：印在海报上的二维码内容是什么、谁都能扫吗？营销团队要的 utm 归因是怎么写进短链的、为什么恶意夹带会被拒？

## 1. 本节学习目标

学完本课，你应该能：

1. 说出 QR 码面内容的固定公式 `{base}/r/{code}`，以及为什么绝不编码目标 URL。
2. 解释 qr.png 的参数钳制范围（size 128-1024、margin 0-4、ec L/M/Q/H）与两种 Cache-Control 的区别。
3. 完整走通短时分享链：签名公式、TTL 边界、`QrShareAuthFilter` 的放行条件、无 permitAll 路由的含义。
4. 逐条说明 UTM 写时合并的四条规则：不覆盖、fragment 保留、2048 上限、9 个归因 id 拒夹带。
5. 说出密码短链导出物料时「不绕过解锁」的具体含义。

## 2. 前置知识

- 短链跳转的公开入口是 `/r/{code}`（模块 7 展开），QR 物料编码的正是这个入口而非目标站。
- HMAC-SHA256：带密钥的哈希，只有持密钥者能生成，任何人可用同密钥验证。与模块 5.2 JWT 的签名思想同源。
- HTTP Cache-Control：`private, max-age=60` 允许浏览器缓存 60 秒，代理不得缓存；`private, no-store` 完全不缓存。
- Servlet `OncePerRequestFilter`：每请求最多执行一次的过滤器基类，模块 3.2 已讲位置，模块 5.1 已讲注册方式。
- UTM 参数：营销归因的五件套 `utm_source/medium/campaign/term/content`，Google Analytics 约定俗成。

## 3. 本节涉及的源码

| 文件 | 行数 | 职责 | 阅读顺序 |
|----|----|----|----|
| `src/main/java/com/linkforge/link/service/QrMaterialService.java` | 77 | QR 渲染与分享 URL | 1 |
| `src/main/java/com/linkforge/link/support/QrParams.java` | 41 | 查询参数钳制 | 2 |
| `src/main/java/com/linkforge/link/support/ShareTokenSigner.java` | 74 | HMAC 签发与校验 | 3 |
| `src/main/java/com/linkforge/security/QrShareAuthFilter.java` | 73 | 签名换身份 | 4 |
| `src/main/java/com/linkforge/security/SecurityConfig.java` | 100 | qr.png 的角色矩阵 :59-62 | 5 |
| `src/main/java/com/linkforge/link/support/UtmMerger.java` | 93 | 写时合并纯函数 | 6 |
| `src/main/java/com/linkforge/link/controller/ShortLinkController.java` | :99-127 | 物料与分享两个端点 | 7 |

## 4. 类的定位（在整个项目中的位置）

本课三类角色，全部围绕「短链离开系统之后的生命」。

- `QrMaterialService`（77 行）是把短链变成线下物料的出口。它依赖 `ShortLinkService` 做归属判定、依赖 `ShareTokenSigner` 做签名，自身不碰数据库。
- `ShareTokenSigner` 与 `QrShareAuthFilter` 是一对钥匙：前者铸钥匙（sign），后者验钥匙（verify 后授予一次性角色）。
- `UtmMerger` 是纯函数，创建与更新两个入口共用，把营销归因写进 `original_url`。

三个角色都不持有状态，逻辑上属于「物料与归因」这个次级域，因此独立于 6.2/6.3 的写读主线，合成一课。

## 5. 核心源码详解

### 5.1 QR 渲染：码面恒为 {base}/r/{code}

```java
/**
 * C1 wave B: QR material. The matrix content is ALWAYS {public-base-url}/r/{code} —
 * never the target URL, never any credential (D-C1-3). Share links are short-lived
 * HMAC tokens over code|exp (D-C1-2).
 */
@Service
@RequiredArgsConstructor
public class QrMaterialService {

    private static final int MIN_SHARE_TTL_SECONDS = 1;
    private static final int MAX_SHARE_TTL_SECONDS = 3600;

    private final ShortLinkService shortLinkService;
    private final LinkForgeProperties properties;
    private final ShareTokenSigner shareTokenSigner;

    /** Ownership via getOwnedLink; a valid share signature bypasses it (D-C1-2). */
    public ShortLink resolveForMaterial(String code, boolean shared) {
        return shared ? shortLinkService.requireExisting(code) : shortLinkService.getOwnedLink(code);
    }

    public byte[] render(String code, QrParams params) {
        String content = publicBase() + "/r/" + code;
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE,
                    params.size(), params.size(), Map.of(
                            EncodeHintType.MARGIN, params.margin(),
                            EncodeHintType.ERROR_CORRECTION,
                            ErrorCorrectionLevel.valueOf(String.valueOf(params.ec()))));
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", png);
            return png.toByteArray();
        } catch (WriterException | IOException ex) {
            // bounded short-URL content: only a size/content mismatch lands here
            throw new BizException(400, "qr rendering failed for the requested size");
        }
    }
```
（`src/main/java/com/linkforge/link/service/QrMaterialService.java:24-60`）

类注释第一段就是本课最重要的产品决策（D-C1-3）：码面内容永远是 `{public-base-url}/r/{code}`。三条理由。

1. 目标 URL 可能超过二维码容量上限（QR 最多约 4296 个字母数字字符，2048 字节 URL 已接近危险区），短入口恒短。
2. 目标 URL 可以随时改（6.3 的 update），改了之后海报上的码自动跟随；编码目标 URL 的码一旦印出去就定格了。
3. 码面不含任何凭据，扫出来只是平台的公开跳转入口。

渲染本身是 ZXing 的标准三步：`QRCodeWriter().encode` 出点阵、`MatrixToImageWriter` 写 PNG、字节数组返回。内容来自 `publicBase()`（:73-76），取 `linkforge.public-base-url` 配置并去掉尾部斜杠。

### 5.2 QrParams：三个参数的钳制

```java
public record QrParams(int size, int margin, char ec) {

    public static QrParams parse(String size, String margin, String ec) {
        return new QrParams(intIn(size, "size", 128, 1024, 256),
                intIn(margin, "margin", 0, 4, 1), ecIn(ec));
    }
```
（`src/main/java/com/linkforge/link/support/QrParams.java:10-15`）

record 承载三个值：边长像素、静区宽度、纠错等级。钳制规则一眼可读：size 128 到 1024（默认 256），margin 0 到 4（默认 1），ec 单字符且必须是 L、M、Q、H 之一（默认 M，`ecIn` :32-40）。任何越界值是 400 而不是静默修正，调用方立即知道自己拼错了。

纠错等级 L/M/Q/H 是 QR 规范的四档（约 7%/15%/25%/30% 可修复面积）。等级越高码点越密。海报户外场景常用 Q 或 H 抗污损，屏幕展示用默认 M 足够。

### 5.3 端点：两种缓存策略的分流

```java
@GetMapping("/{code}/qr.png")
public ResponseEntity<byte[]> qrPng(@PathVariable String code,
                                    @RequestParam(required = false) String size,
                                    @RequestParam(required = false) String margin,
                                    @RequestParam(required = false) String ec) {
    QrParams params = QrParams.parse(size, margin, ec);
    boolean shared = openedByShare(code);
    qrMaterialService.resolveForMaterial(code, shared);
    return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_PNG)
            .header(HttpHeaders.CACHE_CONTROL, shared ? "private, no-store" : "private, max-age=60")
            .body(qrMaterialService.render(code, params));
}
```
（`src/main/java/com/linkforge/link/controller/ShortLinkController.java:100-112`）

登录用户自己看物料，允许浏览器缓存 60 秒。签名分享打开的物料不缓存（no-store），因为分享链接是短时的，缓存的图可能比授权更长寿。`openedByShare`（:123-127）判断依据是 SecurityContext 里的 principal 是否为 `QrSharePrincipal` 且码一致。

归属判定在 `resolveForMaterial`（`QrMaterialService.java:41-43`）：登录路径走 `getOwnedLink`（只见自己的行），分享路径走 `requireExisting`（ShortLinkService 的行加载段，只要码存在即可，因为「签名本身就是所有者的授权」）。越权分享在铸链接那一侧已经拦住（:119，qrShare 端点先走 getOwnedLink）。

### 5.4 ShareTokenSigner：HMAC-SHA256(code|exp)

```java
public String sign(String code, long expEpochSeconds) {
    return HexFormat.of().formatHex(hmac(key, (code + "|" + expEpochSeconds)
            .getBytes(StandardCharsets.UTF_8)));
}

/** Expired (exp strictly before now) or malformed sig fails closed. */
public boolean verify(String code, long expEpochSeconds, String sigHex) {
    if (sigHex == null || expEpochSeconds < nowSeconds()) {
        return false;
    }
    byte[] expected = hmac(key, (code + "|" + expEpochSeconds).getBytes(StandardCharsets.UTF_8));
    try {
        return MessageDigest.isEqual(expected, HexFormat.of().parseHex(sigHex));
    } catch (IllegalArgumentException ex) {
        return false;
    }
}
```
（`src/main/java/com/linkforge/link/support/ShareTokenSigner.java:43-59`）

签名消息是 `code|exp` 的字节串，密钥 HMAC-SHA256，输出小写 hex。三个安全细节都值得点名。

密钥从哪来（:29-41）：配置了 `linkforge.qr.share-secret` 就直接用；没配则从 JWT secret 确定性派生（以固定标签 `linkforge:qr-share:v1` 为消息再做一次 HMAC）。运营者零配置可用，且派生密钥与 JWT 密钥不是同一个值，泄漏不连坐。

验证为什么先比 exp（:50）：过期直接 false，连 HMAC 都不用算，省一次密码学开销，也让「过期链接必然失效」成为无条件的合同。

比较为什么用 `MessageDigest.isEqual`（:55）：恒时比较，无论从哪个字节开始不同耗时相同，杜绝逐字节比较的时序侧信道。模块 5.1 的 MetricsScrapeTokenFilter 用的是同一个套路。

TTL 的钳制发生在 `shareUrl`（`QrMaterialService.java:63-71`）：请求体的 `ttlSeconds` 为空用配置默认 300 秒，否则夹到 1..3600 区间（:33-34 两个常量）。过期时刻是 epoch 秒，直接拼进 URL。

### 5.5 QrShareAuthFilter：签名换一次性身份

```java
/**
 * C1 wave B (D-C1-2): opens GET /api/links/{code}/qr.png ONLY for a request whose
 * exp+sig share token validates — there is deliberately no permitAll QR route. The
 * filter runs solely on signed material requests; on anything else (unsigned,
 * non-GET, other paths, a failed or expired token) it does nothing, so the usual
 * JWT/API-key auth still applies and anonymous traffic meets the standard 401.
 */
@Component
@RequiredArgsConstructor
public class QrShareAuthFilter extends OncePerRequestFilter {

    public static final String ROLE_QR_SHARE = "ROLE_QR_SHARE";
    private static final Pattern QR_PATH = Pattern.compile("^/api/links/([^/]+)/qr\\.png$");
```
（`src/main/java/com/linkforge/security/QrShareAuthFilter.java:20-32`）

过滤器的行为分两面。

**不作为的一面**（`shouldNotFilter` :37-43）：非 GET、URL 不匹配 qr.png、缺 exp 或缺 sig 参数，直接跳过，过滤器不存在一样。验签失败（:50-53）也不拦截，只是不授予身份，继续走链。效果是匿名无签名请求照常撞上安全链的 401，JWT 用户照常用自己的身份。

**作为的一面**（:55-62）：验签通过，构造 `QrSharePrincipal(code)` 放进 SecurityContext，授予唯一的权限 `ROLE_QR_SHARE`，finally 里清空上下文防止身份泄漏到同请求之外。

SecurityConfig 侧的角色矩阵（`SecurityConfig.java:59-62`）：

```java
// C1-2: signed share material opens exactly this method+pattern with
// ROLE_QR_SHARE (minted by QrShareAuthFilter); no permitAll QR route.
auth.requestMatchers(HttpMethod.GET, "/api/links/{code}/qr.png")
        .hasAnyRole("USER", "API_KEY", "QR_SHARE");
```
（`src/main/java/com/linkforge/security/SecurityConfig.java:59-62`）

「无 permitAll 路由」的含义：没有任何路径是免认证的。分享链接能打开物料，是因为验签后临时获得了 QR_SHARE 角色，而不是因为这条路径对匿名放行。过滤器的注册位置在链尾（:92，`addFilterAfter(qrShareAuthFilter, ApiKeyAuthFilter.class)`），JWT 与 API Key 先有机会建立身份。

### 5.6 UtmMerger：写时合并的四条规则

```java
public static String merge(String url, Map<String, String> utm) {
    if (utm == null || utm.isEmpty()) {
        return url;
    }
    int fragmentAt = url.indexOf('#');
    String base = fragmentAt >= 0 ? url.substring(0, fragmentAt) : url;
    String fragment = fragmentAt >= 0 ? url.substring(fragmentAt) : "";
    int queryAt = base.indexOf('?');
    String query = queryAt >= 0 ? base.substring(queryAt + 1) : null;

    StringBuilder merged = new StringBuilder(base);
    boolean pendingQueryMark = queryAt < 0;
    boolean appended = false;
    for (String key : CANONICAL_KEYS) {
        String value = utm.get(key);
        if (value == null || value.isBlank() || queryHasParam(query, key)) {
            continue;
        }
        if (pendingQueryMark) {
            merged.append('?');
            pendingQueryMark = false;
        } else if (merged.charAt(merged.length() - 1) != '?') {
            merged.append('&');
        }
        merged.append(key).append('=').append(value);
        appended = true;
    }
    if (!appended) {
        return url;
    }
    String result = merged.append(fragment).toString();
    return result.length() > MAX_MERGED_LENGTH ? null : result;
}
```
（`src/main/java/com/linkforge/link/support/UtmMerger.java:46-78`）

纯字符串操作，四条规则对应代码四处。

规则一，**不覆盖已有参数**（:61）：`queryHasParam` 对原始查询串做精确参数名匹配（:81-92），`utm_source` 已存在就跳过，营销页自己埋的归因优先于用户请求带的。大小写敏感，`UTM_Source` 不算已有。

规则二，**fragment 保留**（:50-52 与 :76）：先把 `#` 后的部分摘下来暂存，追加完查询再原样挂回。没有这步，`?` 会插到 fragment 后面，URL 直接作废。

规则三，**2048 上限返 null**（:77）：合并结果超长返回 null，调用方 `mergeUtm`（ShortLinkService 的 UTM 合并段）把它翻译成 400「merged originalUrl exceeds 2048」。数据库列就是 2048，在应用层先挡，错误信息可读。

规则四，**键序固定**（:16-17）：五个键按 canonical 顺序追加，同一输入永远产出同一输出。类注释 :9-10 写明这个顺序是 IT 钉死的契约。

### 5.7 拒夹带：utm 前缀与 9 个归因 id

```java
private static void rejectSmuggledAttribution(Map<String, Object> unknown) {
    for (String name : unknown.keySet()) {
        if (UTM_PREFIX.matcher(name).lookingAt()
                || CLICK_ATTRIBUTION_IDS.contains(name.toLowerCase(Locale.ROOT))) {
            throw new BizException(400, "unknown attribution parameter: " + name);
        }
    }
}
```
（ShortLinkService 的 rejectSmuggledAttribution）

9 个归因 id 在常量区（:91-92）：`gclid`（Google Ads）、`fbclid`（Meta）、`msclkid`（Microsoft）、`ttclid`（TikTok）、`yclid`（Yandex）、`dclid`（DoubleClick）、`igshid`（Instagram）、`wbraid`、`gbraid`（Google web/iOS 转化）。这些参数与 utm 一样是归因参数，但不在五件套白名单里，项目不代管它们。调用方把它们塞进请求顶层会撞上 6.1 课的 `@JsonAnySetter` 捕获，然后在这里 400。错误信息回显参数名（这是调用方自己发来的内容，回显安全），正确用法是拼进目标 URL 自己管理。

`create` 与 `update` 都在入口调用它（:140 与 :451），两条写路径口径一致。

### 5.8 密码链出 QR 不绕过解锁

密码短链（accessPasswordHash 非空）的物料导出有一个必须想清楚的问题：扫海报的人没输过密码，凭什么能到目标页？

答案是不绕过。物料编码的是 `/r/{code}`，不是目标 URL。跳转路径上密码门（resolveTarget 的 Locked 步）照常生效：无解锁 cookie 的扫描者拿到的是密码输入页（Locked 结果，200 HTML），不是 302。物料的用途是「让用户到达密码门」，不是「替用户解锁」。解锁仍走 POST unlock 加两级限流（模块 7.2 讲）。

同理，B9 域名门禁对扫码流量照常生效（BLOCK 返回 403 拦截页）。物料永远只是入口的图形化，不携带任何特权。

## 6. 技术原理补充

**HMAC 签名分享**。是什么：把「谁在什么时刻被授权」编码进 URL 的 exp 参数，用 HMAC 证明这个授权是服务器发的。为什么需要：物料要转给第三方（代理商、印刷厂）打开预览，不能发账号密码。不用会怎样：要么物料的打开需要登录（转不出去），要么完全放开（任何人扫到链接格式就能枚举）。优点：无状态、过期自动失效、密钥不出服务器。缺点：签出的链接在 exp 前无法吊销，所以 TTL 上限压到 1 小时。

**恒时比较**。是什么：比较耗时与内容无关的实现，防时序攻击。为什么需要：逐字节短路比较会让攻击者用响应时间逐位猜签名。本项目两处用它（ShareTokenSigner :55、LinkAccessTokens），是安全类代码的标配习惯。

**写时合并（write-time merge）**。是什么：utm 在创建/更新时一次性算进 original_url 落库。为什么需要：跳转是全系统最热的路径，任何那里的额外计算都要谨慎；落库后跳转零成本。不用会怎样（跳转时合并的替代方案）：每次 302 都要解析加改写 URL，且不同渠道想带不同 utm 时，缓存里只能存一个版本。缺点：归因是「链级」的，同一条链的所有点击带同一组 utm（推断的产品取舍）。

**ZXing**。是什么：开源条码图像库，本项目的 QR 生成器（版本 3.5.3）。为什么需要：二维码生成是纯算法问题，自研无意义。项目只用了 encode 到 PNG 这条最窄的路径。

## 7. 可视化

1. [02-function-map](../../diagrams/archify/html/02-function-map.html)：看 QrMaterialService 与 ShareTokenSigner 的依赖方向，前者依赖后者，后者只依赖配置。
2. [03-package-map](../../diagrams/archify/html/03-package-map.html)：注意 `QrShareAuthFilter` 物理上在 security 包而不是 link 包，因为它消费 `ShareTokenSigner`、产出安全上下文，职责在安全侧。
3. [00-system-architecture](../../diagrams/archify/html/00-system-architecture.html)：物料请求从浏览器进安全链再到 Controller 的全景位置。

## 8. 项目中的真实执行过程

走一遍「运营者生成分享链接发给代理商，代理商在浏览器打开」的完整旅程。

1. 运营者 POST `/api/links/spring25/qr-share`，body 可选。Controller 先 `resolveForMaterial(code, false)` 确认这条链属于他（:119）。
2. `shareUrl` 计算过期时刻：当前 epoch 秒加 TTL。请求没给 ttlSeconds，用配置默认 300。签名 `sign("spring25", exp)` 得 64 位 hex。
3. 返回 `{base}/api/links/spring25/qr.png?exp=1767200000&sig=9f2c...`。运营者把链接发给代理商。
4. 代理商浏览器 GET 这个 URL，无 JWT。请求过安全链：`QrShareAuthFilter` 的 shouldNotFilter 放行进 doFilterInternal（GET、路径匹配、双参数齐全），`verify` 验签通过且未过期，授予 `ROLE_QR_SHARE`。
5. SecurityConfig 的 qr.png 规则（:61-62）看到 QR_SHARE 角色，放行。
6. Controller 的 `openedByShare` 返回 true，走 `requireExisting`（码存在即可），缓存头给 `private, no-store`，渲染 PNG 返回。
7. 一小时后代理商再开同一个链接：exp 已过，verify 第一个判断返回 false，过滤器不授予身份，匿名请求撞 401。链接自然死亡。

密码链变体：第 6 步换成一条带密码的链，渲染照常成功（物料只编码 `/r/{code}`）。代理商扫码测试，302 环节被密码门拦下，见到的是密码输入页。

## 9. 为什么这样设计

**码面短入口而不是目标 URL**（QrMaterialService 类注释 D-C1-3）：目标可变而物料不可变，唯一能让旧物料长期有效的编码对象就是稳定入口。这与 6.3 的「改 URL 缓存双删」形成闭环：改目标后，旧海报上的码立即跳新目标。

**签名链接替代开放路由**（QrShareAuthFilter 注释）：开放一条 permitAll 的 QR 路由等于给爬虫一个免登录探测面。签名方案里，路径本身不构成任何漏洞，攻击者构造的 sig 过不了 HMAC。「exact this method+pattern」（:59-60）连 POST 都不豁免，分享者不能借身份做别的请求。

**TTL 上限 1 小时**（:33-34）：分享是「给人看一眼物料」的场景，不是长期授权。短 TTL 让泄漏的链接价值快速衰减，也无状态可吊销的缺陷被时间窗兜住。

**UTM 拒夹带而不是静默忽略**（rejectSmuggledAttribution 的上游 D-C1-8）：utm 参数从请求顶层混入时，调用方显然以为归因会生效。静默忽略会让错误的营销数据无声丢失，400 逼调用方拼进 URL 或删掉字段。其余未知属性维持静默忽略的兼容契约，两类参数区别对待。

**验证先行于密码学计算**（verify :50）：过期检查是纯数字比较，放在 HMAC 前，过期链接的拒绝成本接近零。这也是防御性编程的通用形态：便宜的检查在前，昂贵的检查在后。

## 10. 本节总结

- 码面恒为 `{base}/r/{code}`，物料因此对目标修改免疫，且永不携带凭据。
- qr.png 三参数钳制：size 128-1024 默认 256，margin 0-4 默认 1，ec 四档默认 M，越界 400。
- 分享链 = `HMAC-SHA256(code|exp)` 的 hex 签名，TTL 夹在 1..3600 秒，无状态、自动过期、恒时比较验证。
- `QrShareAuthFilter` 验签后只授 `ROLE_QR_SHARE`，只开 GET qr.png 一个方法加模式，没有 permitAll 路由。
- UTM 写时合并四规则：已有参数不覆盖、fragment 保留、超 2048 返 null 变 400、键序固定。
- 9 个广告归因 id 与 utm 前缀在两条写路径上统一 400 拒夹带。
- 密码链与 B9 门禁对扫码流量照常生效，物料不给任何特权。

## 11. 课后练习

1. 【概念理解】为什么不把目标 URL 直接编码进二维码？
   答案：三点。目标 URL 可能逼近 QR 容量上限，短入口恒短；目标可改而物料不可改，编码入口才能让旧海报跟随新目标；目标 URL 可能含密码门上下文等敏感信息，入口则只是公开路径。QrMaterialService 类注释（:24-28）的 D-C1-3 原文写了前两点。
2. 【源码定位】找到签名的消息原文与派生密钥的标签常量，各在哪个文件哪一行？
   答案：消息是 `code + "|" + expEpochSeconds`（`ShareTokenSigner.java:44` 与 :53），派生标签是 `linkforge:qr-share:v1`（:24）。密钥优先取 `linkforge.qr.share-secret`，缺失时以 JWT secret 为 HMAC 键、标签为消息派生（:29-41）。
3. 【执行过程】攻击者匿名 GET `/api/links/spring25/qr.png`（不带任何参数），逐层说明他收到什么。
   答案：`shouldNotFilter` 因缺 exp/sig 返回 true，过滤器完全跳过（:37-43）。SecurityContext 无身份，qr.png 规则要求 USER/API_KEY/QR_SHARE 之一（`SecurityConfig.java:61-62`），匿名不满足，`authenticationEntryPoint` 返回 JSON 401。数据库从未被触及。
4. 【调用链】合并输入 `https://a.com/p?x=1` 与 `utm_source=mail`，写出输出；再合并 `https://a.com/p?utm_source=winter#top` 与同一输入，写出输出。
   答案：第一个输出 `https://a.com/p?x=1&utm_source=mail`（无同名参数，追加在既有查询后；无 fragment）。第二个输出原串 `https://a.com/p?utm_source=winter#top`（`utm_source` 已存在，:61 跳过，appended 为 false 走 :73-75 返回原样，fragment 不受影响）。
5. 【设计分析】分享物料为什么用 `no-store` 而登录用 `max-age=60`？
   答案：分享 URL 是短时凭据的载体，浏览器若缓存图片，授权过期后本机仍能从缓存看到物料，凭据的时间窗被缓存击穿。登录物料属于用户自己的长期资源，60 秒缓存省渲染开销，且用户本来就有权随时再看。Controller :110 的三元表达式就是这个决策的全部实现。

## 12. 与后续课程的联系

- 模块 7 将打开 `/r/{code}` 的内部：本课所有「扫码者」在第 7 课里变成跳转判定链的输入，密码门与 B9 门禁是 7.2 的主角。
- 模块 8 讲 `publicBase` 之外的另一批配置（cache TTL、absent TTL），配置体系见模块 1.3。
- 模块 12 的 API Key（USER/TENANT scope）与本课的 QR_SHARE 是三种「以物代权」的并列形态，可对照复习。
- `rejectSmuggledAttribution` 依赖的 `@JsonAnySetter` 机制在 6.1 课 5.6 节，忘记时回看。
