# Spring Security、JWT 与 Web 安全

优先级 P1，目标层级 L3：过滤器链讲得清、每类攻击有防法有实例、fail-open 与 fail-closed 会选。

想象一个刚上线、还没接安全层的短链服务。任何人发一个请求，就能删掉别人创建的短链；任何人打开浏览器访问 `/actuator/prometheus`，就能把数据库连接串、JVM 内存这些内部指标全部抓走；接收 webhook 的合作方收到一条"LinkForge 说：链接已创建"的消息，但它没法确认这条消息真的来自 LinkForge，还是来自一个想骗它的中间人。

安全层要做的，是在业务代码运行之前回答一个问题：**凭什么信你**。本文围绕 LinkForge 的真实安全代码，讲清一次带 token 的请求经过哪些组件、每个组件防的是哪种攻击，以及 JWT 这类无状态凭证的能力边界。

## 学习目标

读完本文，你应当能：

- 对着代码讲清一次带 token 的请求在 LinkForge 经过哪几个安全组件，每个组件防什么攻击。
- 区分认证与授权，解释 401 和 403 各自的含义。
- 理解 JWT 的能力边界：签名防篡改不防泄露、无状态 token 为什么无法真正登出、过期时间怎么取舍。
- 讲清 BCrypt 慢哈希、HMAC 签名、常量时间比较这三个机制的原理，而不只是背名词。
- 说清 SSRF 的攻击路径、`WebhookTargetGuard` 防了什么、诚实承认它没防什么。
- 解释 AES-GCM 这种 AEAD 为什么把加密和防篡改做成一个操作。

## 前置知识

- [31-spring-boot-mvc.md](31-spring-boot-mvc.md)：一个 HTTP 请求从 Tomcat 到 Controller 的完整旅程。本文的安全组件全部住在那条链路的过滤器段。
- [13-concurrency.md](13-concurrency.md)：ThreadLocal 的原理与泄漏风险。
- [20-network.md](20-network.md)：Cookie 属性（`HttpOnly`、`SameSite`、`Secure`）的网络语义。
- [java/10-03-equality-and-strings.md](java/10-03-equality-and-strings.md)：`String.equals` 怎么比较。本文的计时侧信道攻击，地基就是它的短路行为。
- [java/10-06-exceptions.md](java/10-06-exceptions.md)：`@RestControllerAdvice` 拦截不到过滤器里的异常。这是理解本文好几处"为什么过滤器自己写 JSON 响应"的钥匙。

## 先分清两件事：认证与授权

### 问题：门卫认出了你，然后呢

一栋写字楼，门卫看了你的工牌放你进门，这是**认证**（Authentication）：确认"你是谁"。你走进档案室被拦下，前台说"员工才能进"，这是**授权**（Authorization）：确认"这个身份能干什么"。

比喻的边界：门卫是同一个人，而系统里认证和授权是两个独立环节。认证通过不等于授权通过；授权失败也不该推翻认证结论。"工牌有效但你是外包，进不了档案室"，工牌本身没被骗过。

### 准确定义

- 认证：拿凭证（密码、JWT、API Key）换一个"已验证的身份"。
- 授权：拿这个身份去比对规则，决定放行还是拒绝。

两者分开的直接好处：同一个身份可以配不同权限集；反过来，权限不足时的报错不必暴露"你的身份验证是否成功"。

### 401 与 403 的分工

- 401 Unauthorized：服务器没认出你。没带凭证、凭证无效、凭证过期，都归这里。
- 403 Forbidden：认出了你，但你没权限。

LinkForge 把这两条做成了两段固定 JSON，写在 `SecurityConfig` 里（框架机制：`authenticationEntryPoint` 处理"没有有效认证"的请求，`accessDeniedHandler` 处理"有认证但规则不通过"的请求）：

```java
// 片段：src/main/java/com/linkforge/security/SecurityConfig.java
.exceptionHandling(eh -> eh
        .authenticationEntryPoint((request, response, ex) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"unauthorized\",\"data\":null}");
        })
        .accessDeniedHandler((request, response, ex) -> {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"message\":\"forbidden\",\"data\":null}");
        }))
```

一个真实对照（据 `ApiKeyAuthFilter` 的 javadoc 与代码）：API Key 是有效的、能建立身份的，但打到了管理接口，服务器回的是干净的 403，不是 401。因为失败发生在授权环节，不在认证环节。而无效或已吊销的 API Key 统一回 401，响应体一个字都不差，调用方无法从报错区分"key 写错了"和"key 被吊销了"，少给一个探测线索。

## Filter：安全代码住在请求链路的哪一站

### 问题：这段代码该写在哪

假设没有统一的安全层。每个 Controller 方法开头都要写一遍"取 token、验 token、没权限就报 401"。几十个方法就有几十份拷贝，漏一份就是一个洞。静态资源、404 路径更麻烦：Controller 都进不去，谁来保护它们。

### Filter 是什么

Servlet 规范定义了一种叫 **Filter**（过滤器）的组件。它是一个实现了 `jakarta.servlet.Filter` 接口的对象，请求到达 Servlet 之前依次穿过一串 Filter，每个 Filter 有权做三件事：改请求、直接写响应、或者调用 `filterChain.doFilter(...)` 把请求传给下一站。不调用 `doFilter`，请求就到此为止（框架行为，Servlet 规范保证）。

这决定了 Filter 的天然优势：它排在 `DispatcherServlet` 之前。不管请求最终去 Controller、去静态资源、还是 404，都会先经过它。安全检查放这里，覆盖所有入口，一处声明，处处生效。

在 31 号文档的请求链路图里，位置是这样：

```text
Tomcat 接收连接
  → Servlet 容器的 Filter 链（第一个就是 springSecurityFilterChain）
    → DispatcherServlet（Spring MVC 的入口 Servlet）
      → Controller → Service → Mapper
```

### Spring Security 的过滤器链：SecurityFilterChain

Spring Security 不是"一个过滤器"，而是**一串过滤器**。它向 Servlet 容器注册了一个名为 `springSecurityFilterChain` 的 Filter。请求进来后，这个 Filter 内部按固定顺序排着十几个内置过滤器：有解析凭证的、有检查匿名访问的、有做授权比对的。这一串有序过滤器加一条"我管哪些请求"的匹配规则，封装成一个 `SecurityFilterChain` 对象（框架行为，Spring Security 6 的设计，此处为示意描述）。

关键规则有三条：

1. **多条链按声明顺序匹配**。你可以注册多个 `SecurityFilterChain` Bean，每个用 `securityMatcher` 声明自己管哪些请求（比如一条只管 `/api/**`，一条只管管理后台）。请求进来，按顺序找第一条匹配的链，之后只走这一条，其他链不再参与。LinkForge 只注册了一条链，且没设 matcher，所以管全部请求。
2. **自定义过滤器用锚点插入**。`addFilterBefore(x, UsernamePasswordAuthenticationFilter.class)` 的意思是：把自定义过滤器 `x` 插到内置过滤器 `UsernamePasswordAuthenticationFilter` 的前面。锚点只是一个位置参照物，跟"用户名密码登录"功能本身没关系。
3. **链上顺序是安全正确性的一部分**。谁先看到请求，谁先有机会拒绝或放行。本文后面 `/actuator/prometheus` 的案例会让这句话具体起来。

自定义过滤器通常继承 `OncePerRequestFilter`：Servlet 规范允许一次请求被内部转发，普通 Filter 可能执行两次，`OncePerRequestFilter` 保证每个请求最多执行一次（框架行为）。子类实现 `doFilterInternal` 写逻辑，重写 `shouldNotFilter` 声明"哪些路径我不处理"。

`shouldNotFilter` 的语义要专门记：**返回 true 表示跳过（这个请求不归我管），返回 false 表示照常执行**。名字读作"应该不过滤吗"，true 就是"对，别过滤"。这个方法名很反直觉，LinkForge 有三个过滤器靠它分工，见下一节。

## 项目案例：SecurityConfig 逐段读

需求：一个无状态的短链服务，要支持三种身份（登录用户、API Key、监控抓取器），API 返回 JSON 错误而不是重定向到登录页，文档和前端控制台在生产环境要彻底关闭。如果不用集中声明的方式写，这些规则会散落在每个 Controller 里。

文件：`src/main/java/com/linkforge/security/SecurityConfig.java`。逐段看（Spring Security 3.3.5 带来的写法，框架行为；本文标注"示意"处均指无法脱离应用验证的整体行为）。

第一段，全局策略：

```java
// 片段：src/main/java/com/linkforge/security/SecurityConfig.java
http.csrf(AbstractHttpConfigurer::disable)
        .cors(Customizer.withDefaults())
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
```

- 关闭 CSRF。CSRF（跨站请求伪造）攻击依赖"浏览器自动带上用户的 Cookie"。这个服务的接口用 `Authorization` 头带 token，浏览器不会在跨站请求里自动带这个头，攻击面不存在，所以关。注意因果：**先改掉"凭证在 Cookie 里"这个前提，才能关 CSRF**；有 Cookie 会话的系统禁 CSRF 是事故。
- `STATELESS`：告诉 Spring Security 永远不要为请求创建 HttpSession。每个请求自带凭证，服务器不存任何会话状态。

第二段，放行规则（匿名可访问）：

```java
// 片段：src/main/java/com/linkforge/security/SecurityConfig.java
auth.requestMatchers("/actuator/health", "/api/health").permitAll()
        .requestMatchers("/api/auth/**").permitAll()
        .requestMatchers(HttpMethod.GET, "/r/**").permitAll()
        .requestMatchers(HttpMethod.HEAD, "/r/**").permitAll()
        // B3 unlock form: anonymous by design, brute-force limited in the service
        .requestMatchers(HttpMethod.POST, "/r/*/unlock").permitAll();
```

- 健康检查放行：负载均衡要探活，探活的人不该需要登录。
- `/api/auth/**` 放行：登录注册接口本身就是用来换取凭证的，不能要求先有凭证。
- 跳转接口 `GET /r/**` 放行：短链是给公开点击用的，访客是匿名网民。
- 解锁接口 `POST /r/*/unlock` 放行：受密码保护的短链，输入密码这个动作本身就发生在"没有任何身份"的时候。注释原文写明：暴力破解的限流在 Service 层做。匿名不等于无防线，防线换了位置，见后文 BCrypt 一节。

第三段，文档与控制台的开发/生产切换：

```java
// 片段：src/main/java/com/linkforge/security/SecurityConfig.java
if (docsEnabled) {
    auth.requestMatchers(docsPaths).permitAll()
            .requestMatchers(consolePaths).permitAll();
} else {
    // prod: deny docs and the static console at the filter chain;
    // add-mappings=false alone leaves "/" erroring with 500.
    auth.requestMatchers(docsPaths).denyAll()
            .requestMatchers(consolePaths).denyAll();
}
```

开发环境放行接口文档和前端控制台。生产环境 `denyAll()`。注释解释了一个坑：只靠关闭 Spring MVC 的静态资源映射（`add-mappings=false`）不够，访问 `/` 会落进错误处理报 500，而不是一个干净的拒绝。在过滤器链上直接 `denyAll` 才是干净关法。

第四段，授权规则。**从上到下，第一条命中的规则生效**，所以顺序本身就是逻辑：

```java
// 片段：src/main/java/com/linkforge/security/SecurityConfig.java
auth.requestMatchers("/api/links/**").hasAnyRole("USER", "API_KEY")
        .requestMatchers("/api/keys/**", "/api/webhooks/**").hasRole("USER")
        // B1: the scrape endpoint answers only to ROLE_METRICS
        // (granted by MetricsScrapeTokenFilter); must sit before /actuator/**
        .requestMatchers("/actuator/prometheus").hasRole("METRICS")
        .requestMatchers("/actuator/**").hasRole("USER")
        .anyRequest().hasRole("USER");
```

- `/api/links/**` 允许 `ROLE_USER` 或 `ROLE_API_KEY`：API Key 是给外部程序调用短链功能用的能力白名单，只开放 link 这一层。
- `/api/keys/**` 和 `/api/webhooks/**` 只允许 `ROLE_USER`。API Key 不能管理 Key 自己，也不能管理 webhook。这就是"能力白名单"：给机器的身份只配机器需要的面，管理面永远是人的。
- `/actuator/prometheus` 要求 `ROLE_METRICS`，且**必须写在 `/actuator/**` 前面**。如果反过来，任何访问 prometheus 的请求会先命中 `/actuator/**` 这条规则，按 `ROLE_USER` 判定，`ROLE_METRICS` 永远轮不到出场。
- 兜底 `anyRequest().hasRole("USER")`：没写到的路径默认只有登录用户能进。默认拒绝，而不是默认放行。

第五段，三个自定义过滤器的挂载：

```java
// 片段：src/main/java/com/linkforge/security/SecurityConfig.java
.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
.addFilterBefore(metricsScrapeTokenFilter, JwtAuthFilter.class)
.addFilterAfter(apiKeyAuthFilter, JwtAuthFilter.class);
```

最终链上的顺序是：`MetricsScrapeTokenFilter` → `JwtAuthFilter` → `ApiKeyAuthFilter` → 后续内置过滤器 → 授权判定 → Controller。注释原文说明：JWT 先拿到链上位置；抓取令牌过滤器插在它前面（必须先于 JWT 过滤器看到原始 Bearer 头）；`X-API-Key` 过滤器排在它后面，后两者都可能建立 SecurityContext。为什么是这个顺序，下一节用三条真实请求走一遍。

第六段，密码编码器：

```java
// 片段：src/main/java/com/linkforge/security/SecurityConfig.java
@Bean
public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
}
```

一个 Bean，全项目共用：登录密码、密码短链的访问密码都由它加密与校验。BCrypt 的机制后文专门讲。

## 一次带 token 的请求走哪几站

现在回答本文的核心问题。下面三条真实请求，逐站跟踪（整条链的行为是据代码与框架文档推断的描述，本机无法起应用实测，标注示意）。

### 请求一：带 JWT 的用户请求 `GET /api/links`

```text
MetricsScrapeTokenFilter  shouldNotFilter: URI 不是 /actuator/prometheus 且无 X-API-Key 头
                          → 返回 false，照常执行 → 但请求头是 JWT 不是抓取令牌？
                          → 不对：它只看 Bearer 头里的值，与配置的抓取令牌做常量时间比较，不匹配
                          → 写 401，直接返回，请求到此结束？？
```

等等，这条链上有个容易看错的细节，值得停一下。`MetricsScrapeTokenFilter` 的 `shouldNotFilter` 有两个跳过条件（`MetricsScrapeTokenFilter.java` 第 34-41 行）：带了 `X-API-Key` 头就跳过；URI 不是 `/actuator/prometheus` 就跳过。这条请求的 URI 是 `/api/links`，第二个条件命中，**整个过滤器跳过**，根本不看 Bearer 头。它只对 prometheus 这一个端点生效。

所以真实流程是：

```text
站 1  MetricsScrapeTokenFilter   URI 不是 /actuator/prometheus → shouldNotFilter=true → 跳过
站 2  JwtAuthFilter              有 Bearer 头 → JwtService.parse 验签+验过期
                                    成功：构造 AuthUser 放进 UserContext（ThreadLocal）
                                          构造 UsernamePasswordAuthenticationToken（ROLE_USER）
                                          放进 SecurityContextHolder（ThreadLocal）
                                    失败：抛 JwtException → catch → 写 401 JSON，请求终止
站 3  ApiKeyAuthFilter           无 X-API-Key 头 → 直接放行
站 4  授权规则                   /api/links/** 允许 ROLE_USER → 通过
站 5  Controller → Service       业务代码从 UserContext 取当前用户干活
最后  JwtAuthFilter 的 finally    清 UserContext、清 SecurityContextHolder
```

每站防什么：站 2 防"伪造凭证"（签名不对、改过 payload、过期，都进不了站 4）；站 4 防"越面"（有合法身份但访问不该访问的路径）；站 5 的归属校验防"水平越权"，后文专讲。

### 请求二：带 API Key 的请求 `GET /api/links`

```text
站 1  MetricsScrapeTokenFilter   URI 不是 prometheus → 跳过
站 2  JwtAuthFilter              没有 Bearer 头 → 什么都不设置 → 放行
站 3  ApiKeyAuthFilter           有 X-API-Key 头：
                                    先检查有没有同时带 Bearer 头，带了 → 400
                                    查库验 key，无效或已吊销 → 统一 401
                                    检查配额（分钟滑动窗口 + 当日固定窗口），超了 → 429 带 Retry-After
                                    成功：构造 AuthUser（ROLE_API_KEY）放进两个 ThreadLocal → 放行
站 4  授权规则                   /api/links/** 允许 ROLE_API_KEY → 通过
最后  两层 finally                ApiKeyAuthFilter 清一次，JwtAuthFilter 的 finally 再清一次（双保险）
```

三个设计点：

- **同时带 JWT 和 API Key 回 400**。两种凭证都有效时以谁为准是歧义，歧义在安全系统里就是漏洞，直接拒绝。`ApiKeyAuthFilter` 还给这个场景打了一个 `ambiguous` 计数指标。
- **无效与已吊销统一 401、响应体相同**。代码里只有指标 tag 区分原因（`result=invalid` 与 `result=revoked`），对外完全一致，不给探测者"这个 key 曾经有效"的信息。
- **配额检查在认证之后**：先确认你是谁，才知道该用哪个配额桶。配额靠 Redis 限流器实现，Redis 挂了它选择放行（fail-open），与后文指标端点的选择正好相反。

### 请求三：带抓取令牌的请求 `GET /actuator/prometheus`

```text
站 1  MetricsScrapeTokenFilter   URI 是 prometheus、无 X-API-Key 头 → 执行：
                                    配置里没设令牌 → 401（fail-closed：宁可没监控也不裸奔）
                                    Bearer 值与配置值 MessageDigest.isEqual 不匹配 → 401
                                    匹配 → 授予 ROLE_METRICS → 放行
站 2  JwtAuthFilter              shouldNotFilter 对 prometheus 返回 true → 跳过
                                    （抓取令牌不是 JWT，若解析会抛 JwtException 变成 401，
                                      ROLE_METRICS 永远授予不了）
站 3  ApiKeyAuthFilter           无 X-API-Key 头 → 放行
站 4  授权规则                   /actuator/prometheus 要求 ROLE_METRICS → 通过
最后  MetricsScrapeTokenFilter 的 finally 清 SecurityContextHolder
```

这条请求解释了链序为什么不能动：

- 抓取令牌过滤器在 JWT 过滤器**前面**：只有它先跑，抓取令牌才不会被 JWT 过滤器当成坏 token 拦下。
- JWT 过滤器对 prometheus 用 `shouldNotFilter` 返回 true 跳过：这是链序之外的第二道保险。两道机制目标相同，任意一道单独存在也能工作，同时存在则互相兜底。
- 一个合法用户的 JWT 打到 prometheus 会怎样？站 1 执行（无 X-API-Key 头、URI 匹配），Bearer 值是 JWT 不是抓取令牌，比较失败，直接 401。**用户角色访问不到指标端点**，指标只留给抓取器。
- 一个带 API Key 的请求打到 prometheus 会怎样？站 1 看到 `X-API-Key` 头主动跳过（注释原文：让 API Key 得到干净的 403 而不是匿名的 401），站 3 建立 `ROLE_API_KEY` 身份，站 4 的规则要求 `ROLE_METRICS`，不匹配 → `accessDeniedHandler` → 403。

到这里，学习目标的第一条已经完整：三条请求、四个安全组件（抓取令牌过滤器、JWT 过滤器、API Key 过滤器、授权规则），各自防的攻击分别是指标泄露、凭证伪造、配额滥用与凭证歧义、越面访问。

## JWT：无状态服务的身份票据

### 问题：不存会话，下次请求怎么认人

`SecurityConfig` 设了 `STATELESS`：服务器不给请求建 HttpSession。传统做法是登录后发一个 sessionId，服务器存一张"sessionId → 用户"的表，下次请求凭 id 查表认人。这条路的问题在多实例部署时显现：第一个请求打到实例 A 建了会话，第二个请求打到实例 B，B 的表里没有这条会话。要么做粘性路由，要么让所有实例共享会话存储（引入 Redis 依赖）。两条路都把"认人"变成了有状态操作。

JWT（JSON Web Token）走另一条路：把身份信息**放进凭证本身**，再加一个防伪签名。服务器不存任何东西，收到 token 自己验签，验过就认。比喻：Session 是食堂饭卡（卡里只有卡号，余额在食堂系统里），JWT 是钞票（面额写在票面上，靠防伪特征辨真伪）。比喻失效处：钞票的真伪靠物理特征人人可验，JWT 的真伪只有持有密钥的服务器可验；而且钞票可以上交销毁，JWT 一旦签发，服务器没有任何"收回"的手段，这一点是它最深的边界，后文展开。

### 三段结构：自己动手签一个

JWT 是三段 Base64URL 编码，用 `.` 连接：`头.载荷.签名`。JDK 自带 HMAC-SHA256 和 Base64URL，不依赖任何库就能手工签一个，看清每一段是什么：

```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class JwtDemo {
    static String b64url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static byte[] hmac(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    static boolean verify(byte[] key, String token) throws Exception {
        int first = token.indexOf('.');
        int last = token.lastIndexOf('.');
        String signedPart = token.substring(0, last);
        String signature = token.substring(last + 1);
        return b64url(hmac(key, signedPart)).equals(signature);
    }

    public static void main(String[] args) throws Exception {
        byte[] key = "demo-secret-key-0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String payload = "{\"sub\":\"42\",\"username\":\"alice\"}";
        String head = b64url(header.getBytes(StandardCharsets.UTF_8));
        String body = b64url(payload.getBytes(StandardCharsets.UTF_8));
        String signature = b64url(hmac(key, head + "." + body));
        String token = head + "." + body + "." + signature;

        System.out.println("token  = " + token);
        String decoded = new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8);
        System.out.println("payload decoded without any key: " + decoded);
        System.out.println("verify original: " + verify(key, token));

        String tamperedBody = b64url("{\"sub\":\"43\",\"username\":\"alice\"}".getBytes(StandardCharsets.UTF_8));
        String tampered = head + "." + tamperedBody + "." + signature;
        System.out.println("verify tampered: " + verify(key, tampered));
    }
}
```

```text
token  = eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiI0MiIsInVzZXJuYW1lIjoiYWxpY2UifQ.kYUa3IWC-eH5iza_j3fG4t-_58v98PR6mkTQTrX0oIs
payload decoded without any key: {"sub":"42","username":"alice"}
verify original: true
verify tampered: false
```

逐行推导：

- 头 `{"alg":"HS256","typ":"JWT"}` 声明签名算法。放在 token 里是让接收方知道"该用哪种算法验"，接收方同时应该校验这个声明是否在允许清单里（否则可能被诱导用弱算法）。
- 载荷 `{"sub":"42","username":"alice"}` 是身份信息。`sub`（subject）按惯例放用户标识。
- 签名 = HMAC-SHA256(密钥, 头 + "." + 载荷)。注意签名材料是**编码后的头和载荷原样拼接**，所以载荷改一个字节，重算的签名就对不上。`verify tampered: false` 就是这个机制的直接展示：攻击者把 `sub` 从 42 改成 43（把别人的 token 改成自己的），但旧签名还是对旧载荷算的，校验失败。想通过，必须重算签名，而重算需要密钥。
- 第二行输出是本文最重要的一条边界证据：**载荷不需要任何密钥就能解码**。Base64URL 是编码不是加密，任何人拿到 token 都能读出全部内容。所以签名防的是篡改，完全不防泄露。手机号、邮箱、权限明细都不该进 JWT 载荷。
- 为什么是 Base64URL 而不是普通 Base64：普通 Base64 的字符集含 `+` 和 `/`，放进 HTTP 头和 URL 里会被转义破坏。Base64URL 把它们换成 `-` 和 `_`（输出里签名段的 `-` 和 `_` 可见），并省去 `=` 填充。

### 无法真正登出：无状态的代价

Session 路线想踢掉一个登录，删掉服务端那条记录就行。JWT 路线没有那张表：服务器收到 token 只做"验签 + 查过期"，token 本身始终是"有效的"，因为签名和过期时间都没变。这就是"无状态 token 无法真正登出"的完整推导：**作废的能力来自状态，放弃状态就放弃了作废**。

能做的补救，每一条都在重新引入状态或缩小损害：

- 短过期时间：泄露的 token 很快自然失效，代价是要有续期机制。
- 换密钥：一换全部 token 同时作废，代价是所有用户被登出，是事故响应手段不是日常手段。
- 服务端黑名单：被登出的 token 记进 Redis，校验时多查一次。可用，但它就是把 Session 那张表加回来了，每查一次就离"无状态"远一步。

过期时间的取舍因此是个平衡：设得长，泄露窗口大；设得短，用户频繁被登出，且续期本身要么引入 refresh token（又是一个要保管的凭证，通常配服务端存储），要么让用户反复登录。LinkForge 的选择：`expireHours` 默认 24 小时（`LinkForgeProperties` 的 Jwt 配置，走配置文件可改），对纯 API 服务这是一个不折腾的中间值；真出事时换 JWT 密钥全量作废，运维路径写在预案里。

### 项目案例：JwtService 与 JwtAuthFilter

需求：签发与解析 JWT。不用库怎么写：手拼 JSON、处理转义、实现验签和过期判断，每个环节都可能写错。项目用 jjwt 库（`io.jsonwebtoken`）承担这些细节。

文件：`src/main/java/com/linkforge/security/JwtService.java`，全文只有两个方法：

```java
// 片段：src/main/java/com/linkforge/security/JwtService.java
public String createToken(Long userId, Long tenantId, String username) {
    Instant now = Instant.now();
    return Jwts.builder()
            .subject(String.valueOf(userId))
            .claim("tenantId", tenantId)
            .claim("username", username)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(expireHours, ChronoUnit.HOURS)))
            .signWith(key)
            .compact();
}

public Claims parse(String token) {
    return Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .getPayload();
}
```

- 算法 HS256 = HMAC-SHA256，密钥来自配置的 JWT 密钥字节（`Keys.hmacShaKeyFor`，库行为：字节数组包装成 HMAC 密钥对象）。
- 载荷只有 userId、tenantId、username 三个字段和时间戳，没有任何敏感信息，符合"载荷可读"的边界。
- `parse` 一行同时完成验签和过期检查（库行为）：签名不对、载荷被改、token 过期，都抛 `JwtException` 的子类（过期具体是 `ExpiredJwtException`）。调用方只需 catch 一个父类。

文件：`src/main/java/com/linkforge/security/JwtAuthFilter.java`。它是认证的落地处，逐段看：

```java
// 片段：src/main/java/com/linkforge/security/JwtAuthFilter.java
protected boolean shouldNotFilter(HttpServletRequest request) {
    // the scrape token is not a JWT — parsing it would 401 before the metrics
    // filter can grant ROLE_METRICS (D-Bw345-42)
    return request.getRequestURI().equals("/actuator/prometheus");
}
```

prometheus 路径返回 true（跳过），原因就是上一节走链时讲的：这里来的 Bearer 头装的是抓取令牌，不是 JWT，解析必炸。

```java
// 片段：src/main/java/com/linkforge/security/JwtAuthFilter.java
String header = request.getHeader(HttpHeaders.AUTHORIZATION);
if (header != null && header.startsWith("Bearer ")) {
    String token = header.substring(7);
    Claims claims = jwtService.parse(token);
    Long userId = Long.valueOf(claims.getSubject());
    Long tenantId = claims.get("tenantId", Long.class);
    String username = claims.get("username", String.class);
    AuthUser authUser = new AuthUser(userId, tenantId, username);
    UserContext.set(authUser);
    var authentication = new UsernamePasswordAuthenticationToken(
            authUser, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    SecurityContextHolder.getContext().setAuthentication(authentication);
}
filterChain.doFilter(request, response);
```

解析成功后做两件事，喂给两套消费者：

- `SecurityContextHolder` 是 Spring Security 自己的 ThreadLocal，存 `Authentication` 对象。授权规则（`hasRole(...)`）从这里读角色。`ROLE_USER` 这样的角色名，对应授权规则里的 `hasRole("USER")`，前缀 `ROLE_` 是框架的默认约定（框架行为）。
- `UserContext` 是项目自建的 ThreadLocal（见下），业务代码从这里拿当前用户，不必穿透 Spring Security 的 API。

没有 Bearer 头就不设置任何东西，直接放行：这个过滤器只负责"能认就认"，认不出交给后面的授权规则按路径决定给 401 还是放行（放行路径是设计好允许匿名的）。

```java
// 片段：src/main/java/com/linkforge/security/JwtAuthFilter.java
} catch (JwtException ex) {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType("application/json;charset=UTF-8");
    response.getWriter().write("{\"code\":401,\"message\":\"invalid token\",\"data\":null}");
} finally {
    UserContext.clear();
    SecurityContextHolder.clearContext();
}
```

- `catch (JwtException)` 里**自己手写响应**而不是抛出去。因为过滤器在 `DispatcherServlet` 之前，异常传播到不了 `GlobalExceptionHandler`（框架行为，详见 [java/10-06-exceptions.md](java/10-06-exceptions.md)）。`ApiKeyAuthFilter` 的 javadoc 把这点说破了：它的错误响应体都在模仿 `GlobalExceptionHandler` 的形状，让客户端看到一致的结构。
- `finally` 清两个 ThreadLocal，是本文必须带走的习惯。Tomcat 用线程池处理请求，线程用完回收复用。不清，下一个请求复用这条线程时，`UserContext.get()` 会读到**上一个用户**的身份，张三的请求可能以李四的身份执行。这是 ThreadLocal 泄漏最危险的形态（机制详见 [13-concurrency.md](13-concurrency.md)）。`UserContext` 类本身极简（`src/main/java/com/linkforge/security/UserContext.java`）：一个 `ThreadLocal<AuthUser>` 加 set/get/clear 三个静态方法，`clear` 里调用 `remove()`。

`AuthUser`（`src/main/java/com/linkforge/security/AuthUser.java`）是个三字段的 `@Data` 类：userId、tenantId、username。JWT 过滤器和 API Key 过滤器都构造它，业务层因此不关心凭证是哪种。

## BCrypt：密码为什么用慢哈希存

### 问题：数据库被拖走了

口令存储要按"数据库终将泄露"来设计。三道防线里只剩最后一道是真防线：

- 别存明文：泄露即全部裸奔，这条没人反对。
- 别用快哈希：SHA-256、MD5 对口令是错的。这些算法的设计目标是快。攻击者拿显卡集群离线猜，主流硬件对 MD5 每秒能试数十亿次（量级描述，非精确值），一个百万人库里弱口令几秒内全灭。加盐能挡住预计算表（彩虹表，见下），挡不住"逐个试"。
- 用慢哈希：把"试一次"的成本人为抬到毫秒级。爆破成本乘上一千万倍，慢哈希才有意义。

### 慢哈希的直觉：成本是可调的

慢哈希的核心设计是**内部把基础哈希重复很多轮，轮数是一个参数**。用 JDK 自带的 PBKDF2（另一种慢哈希算法，与 BCrypt 是不同算法但同一思想：成本可调）演示，迭代一万次量级、看迭代翻倍时耗时怎么变：

```java
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;

public class SlowHashDemo {
    static long hashOnce(char[] password, byte[] salt, int iterations) throws Exception {
        long start = System.nanoTime();
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        return (System.nanoTime() - start) / 1_000_000;
    }

    public static void main(String[] args) throws Exception {
        char[] password = "hunter2".toCharArray();
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);

        for (int w = 0; w < 5; w++) {
            hashOnce(password, salt, 40_000);
        }

        for (int iterations : new int[]{10_000, 20_000, 40_000}) {
            for (int r = 0; r < 3; r++) {
                System.out.println("iterations=" + iterations + " cost=" + hashOnce(password, salt, iterations) + "ms");
            }
        }
    }
}
```

```text
输出（示意）：
iterations=10000 cost=12ms
iterations=10000 cost=19ms
iterations=10000 cost=11ms
iterations=20000 cost=25ms
iterations=20000 cost=21ms
iterations=20000 cost=19ms
iterations=40000 cost=35ms
iterations=40000 cost=35ms
iterations=40000 cost=34ms
```

标注（示意）的原因：毫秒数依赖机器、JVM 预热和当次调度，每次运行都不同。先空跑 5 次做预热，是为了排除 JIT 编译期的噪声，让"迭代翻倍、耗时翻倍"的线性关系显出来。10k 次约十几毫秒，40k 次约三十五毫秒，比例大致 1:2:4。

推导这个数字的意义：登录时服务器为这一次校验付十几毫秒，完全可接受；攻击者拖走数据库后想试一个口令，同样要付十几毫秒，想扫完全库弱口令，成本瞬间从秒级变成天级。**慢是特性，不是缺陷**。这也是"BCrypt 为什么慢"的答案骨架：BCrypt 用 2^cost 轮 Blowfish 密钥扩展把成本做出来，cost 加 1，耗时翻倍。

### BCrypt 的输出串：盐就住在里面

一个 BCrypt 哈希长这样（字符数固定 60）：

```text
$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy
 │  │  │                │
 │  │  │                └─ 31 字符：哈希结果
 │  │  └─ 22 字符：128 位盐的 Base64 编码
 │  └─ cost=10，即 2 的 10 次方轮
 └─ 算法版本
```

两个直接推论：

- **盐不需要单独存储，也不需要保密**。它就明文写在哈希串里，校验时从串里读出来。盐的作用是让"相同口令"产生"不同哈希"，从而让攻击者无法用一张预计算表（彩虹表：提前算好"口令 → 哈希"的对照表）覆盖所有用户。攻击者拿到盐后只能针对这一个用户现算，预计算的投资全部作废。常见误解"盐要保密"就此澄清：保密的是慢哈希的**代价**（让每次猜测变贵），不是盐。
- **校验和加密一样慢**。校验时从串里读出 cost 和盐，用同样的参数对输入密码再算一遍，比对结果。登录方付多少成本，爆破方就付多少成本，没有捷径。

cost 怎么选：每加 1，登录耗时翻倍，服务器的登录吞吐减半，而爆破成本也翻倍。这是运营预算和安全边际之间的旋钮。LinkForge 用 `new BCryptPasswordEncoder()` 的默认 cost 10（据代码推断），对单机验证码场景是稳妥起点。

### 项目案例

`SecurityConfig` 提供 `PasswordEncoder` Bean（见前文第六段），两处消费：

- 登录校验：比对用户输入与库里的 BCrypt 串。
- 密码短链：创建者给短链设访问密码，`ShortLinkService` 的 `encodeAccessPassword` 方法（`src/main/java/com/linkforge/link/service/ShortLinkService.java` 第 555-565 行）把 4-64 字符的访问密码用同一个 `PasswordEncoder` 加密入库。密码短链的解锁 cookie 如何跟这把密码绑定，见 pwdVer 一节。

## HMAC 与 webhook 签名

### 问题：接收方凭什么信这条 POST

LinkForge 的事件（比如"短链被点击了"）会以 HTTP POST 通知到用户注册的 webhook 地址。接收方服务器看到一条 POST，它要回答两个问题：这真是 LinkForge 发的吗（而不是冒充者）？内容从发出到送达没有被改过吗？

第一直觉是"对 body 做个 SHA-256，一起发过去"。不行：哈希没有密钥，攻击者改完 body 自己重算一个哈希放上去，接收方核对不出任何破绽。缺的是**只有收发双方知道、攻击者不知道的东西**，也就是密钥。

### HMAC：带密钥的哈希

HMAC（Hash-based Message Authentication Code）是把密钥混进哈希计算的标准化构造。效果用一句话说清：同一个消息，用不同密钥算出的 HMAC 完全不同；不知道密钥，既算不出正确值，也无法从已知值反推或伪造。收发双方共享密钥，发送方签名，接收方用同样材料重算再比对。JDK 的 `javax.crypto.Mac` 直接支持，最小示例（与 `WebhookSigner` 的构造一致，密钥、时间戳、body 全部固定，输出确定）：

```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

public class HmacDemo {
    static String hmacHex(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    public static void main(String[] args) throws Exception {
        String secret = "whsec_0123456789abcdef";
        long timestamp = 1760000000L;
        String body = "{\"eventId\":\"evt_1\",\"type\":\"link.created\"}";

        String signature = hmacHex(secret, timestamp + "." + body);
        System.out.println("signature = " + signature);

        System.out.println("verify same body:     " + signature.equals(hmacHex(secret, timestamp + "." + body)));
        System.out.println("verify flipped body:  " + signature.equals(hmacHex(secret, timestamp + "." + body.replace("created", "deleted"))));
        System.out.println("verify wrong secret:  " + signature.equals(hmacHex("whsec_ffffffffffffffff", timestamp + "." + body)));
    }
}
```

```text
signature = c3ac27465b1a4dca5ff3a37195456ef9afd052eadca466fdc0e339c59af09c1a
verify same body:     true
verify flipped body:  false
verify wrong secret:  false
```

三行验证各说明一件事：材料一致则通过；body 改一个词，签名对不上（完整性）；密钥换一个字符，也对不上（真实性）。示例为了简洁用了 `String.equals` 比对签名，生产代码应当用常量时间比较，原因见下一节。

### 时间戳防重放

HMAC 只保证"这条消息来自持有密钥的人、没被改过"，不保证"它是刚发的"。攻击者截获一条完全合法的请求，原样重发一百次，每次签名都是对的。这就是重放攻击。

解法是把发送时刻绑进签名材料，接收方检查时间新鲜度：超过容差（比如 300 秒）直接拒绝。推导一下为什么这样就够了：重放的请求带着旧时间戳，接收方一看"这是 5 分钟前的签名"，拒。攻击者改时间戳？改了签名就对不上，因为时间戳在签名材料里。容差不为零是为了容忍两台服务器之间的时钟偏差，也正因为窗口存在，重放攻击被压缩成"在容差窗口内重发"，接收方还要补第三招：按事件 id 去重，同一个 eventId 只处理一次。去重同时解决了另一个非攻击问题：消息队列的投递语义是至少一次，正常重试也会产生重复（见 [51-rabbitmq-outbox.md](51-rabbitmq-outbox.md)）。

### 项目案例：WebhookSigner

需求：给每条 webhook 请求生成可验证的签名头。文件 `src/main/java/com/linkforge/webhook/support/WebhookSigner.java`，核心一个静态方法（片段）：

```java
// 片段：src/main/java/com/linkforge/webhook/support/WebhookSigner.java
/** X-LinkForge-Signature = "v1=" + hex(HMAC-SHA256(secret, timestamp + "." + rawBody)). */
public static String sign(String secret, long unixTimestampSeconds, String rawBody) {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] digest = mac.doFinal((unixTimestampSeconds + "." + rawBody).getBytes(StandardCharsets.UTF_8));
    // ... 拼成 hex 字符串，返回 "v1=" + hex
}
```

（完整实现见源文件，demo 里的 `hmacHex` 就是它的骨架。）三个设计细节：

- 签名材料是 `时间戳 + "." + rawBody`：时间戳在签名里，改不得；`rawBody` 是原始字节对应的字符串，签名覆盖完整内容，body 改一个字符签名即不匹配。
- 时间戳与 body 之间用 `.` 分隔：分隔符防拼接歧义（时间戳 `17` 和 body `6x`，与时间戳 `176` 和 body `x` 必须区分开）。
- 前缀 `v1=` 是签名格式的版本号：将来换算法（比如换 SHA-512 或改材料构造），接收方按前缀分派验证逻辑，旧签名仍可验。无版本号的签名格式，升级时新旧接收方互相读不懂。

javadoc 同时写明接收方应当怎么验：常量时间比较签名、±300 秒时间容差、按 `X-LinkForge-Event-Id` 去重。发送方与接收方是契约的两半，文档里把另一半也钉住了。

## 常量时间比较：equals 的计时侧信道

### 问题：比较两个字符串，怎么就成了漏洞

`String.equals` 的比较策略是逐段比对，**遇到第一个不同就立即返回 false**（`equals` 的具体行为见 [java/10-03-equality-and-strings.md](java/10-03-equality-and-strings.md)）。这意味着耗时与"第一个不同字符的位置"相关：两个串前 1000 个字符都相同、最后一个不同，比"第 1 个字符就不同"慢得多。

把这个性质放进"服务器比较用户提交的凭证与真实密钥"的场景，就变成了武器：攻击者不知道密钥，但能测服务器的响应时间。构造一个猜测串发过去，如果响应时间比上次长了一点，说明第一位猜对了（服务器多比了一个字符）；逐字节推进，每个字节最多试几十次，纯靠掐秒表把完整密钥猜出来。这就是计时侧信道攻击：信息不走数据通道，走时间通道泄露。

### 实验：把时间差测出来

JDK 的 `MessageDigest.isEqual` 专为比对敏感数据设计：实现里没有任何"不同就提前返回"，无论第几个字节不同，耗时都一样。下面的实验对两个 1200 字符的长串分别测 `String.equals` 与 `MessageDigest.isEqual`，差别位置分别在开头和结尾（演示用长串放大信号，真实 token 更短、单次差异更小，但攻击者可以海量采样把微小的均值差测出来）：

```java
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class TimingDemo {
    static int sink;

    static long timeEquals(String a, String b, int rounds) {
        long start = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            if (a.equals(b)) {
                sink++;
            }
        }
        return (System.nanoTime() - start) / rounds;
    }

    static long timeDigestEqual(byte[] a, byte[] b, int rounds) {
        long start = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            if (MessageDigest.isEqual(a, b)) {
                sink++;
            }
        }
        return (System.nanoTime() - start) / rounds;
    }

    static String make(int length, int diffAt) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(i == diffAt ? 'x' : 'a');
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        int length = 1200;
        String secret = make(length, -1);
        String firstDiff = make(length, 0);
        String lastDiff = make(length, length - 1);
        byte[] secretB = secret.getBytes(StandardCharsets.UTF_8);
        byte[] firstB = firstDiff.getBytes(StandardCharsets.UTF_8);
        byte[] lastB = lastDiff.getBytes(StandardCharsets.UTF_8);

        for (int i = 0; i < 20_000; i++) {
            timeEquals(secret, firstDiff, 1);
            timeDigestEqual(secretB, firstB, 1);
        }

        int rounds = 200_000;
        System.out.println("string length=" + length);
        System.out.println("equals   diff at first char: " + timeEquals(secret, firstDiff, rounds) + " ns/op");
        System.out.println("equals   diff at last  char: " + timeEquals(secret, lastDiff, rounds) + " ns/op");
        System.out.println("isEqual  diff at first char: " + timeDigestEqual(secretB, firstB, rounds) + " ns/op");
        System.out.println("isEqual  diff at last  char: " + timeDigestEqual(secretB, lastB, rounds) + " ns/op");
        System.out.println("sink=" + sink);
    }
}
```

```text
输出（示意）：
string length=1200
equals   diff at first char: 2 ns/op
equals   diff at last  char: 22 ns/op
isEqual  diff at first char: 1010 ns/op
isEqual  diff at last  char: 1015 ns/op
sink=0
```

标注（示意）的原因：纳秒级测量被 CPU 频率、缓存状态、JIT 优化共同影响，不同机器数字不同；本实验先空跑 2 万次预热、每档测 20 万次取均值，才让信号稳定到能读出结论。三行数字各有含义：

- `equals`：差别在第 1 个字符时约 2 纳秒，在第 1200 个字符时约 22 纳秒，十倍的差。时间在泄露"对了多少"。
- `isEqual`：两种情况都在 1010-1015 纳秒，时间只由长度决定，与内容无关（本机 JDK 21 的实现已核实：结果用异或累积到变量里，循环走满 `lenA` 次，无任何提前返回）。
- `isEqual` 比 `equals` 慢约两个数量级。常量时间是有代价的：安全换性能。这个代价只该付在比对密钥、签名、token 的地方，比业务数据照常 `equals`。

实验里还有一个工程细节：`sink` 字段看似没用，没有它 JIT 可能把"结果没人用"的 `equals` 调用整体优化掉，测出来的就是空循环的时间（第一次写这个 demo 时两条都测出 1 纳秒，就是这个原因）。计时实验必须让被测代码的结果"被消费"。

### 项目案例：两处凭证比较

`MetricsScrapeTokenFilter`（`src/main/java/com/linkforge/security/MetricsScrapeTokenFilter.java`）比对抓取令牌：

```java
// 片段：src/main/java/com/linkforge/security/MetricsScrapeTokenFilter.java
!MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8),
        provided.getBytes(StandardCharsets.UTF_8))
```

`LinkAccessTokens.validate`（`src/main/java/com/linkforge/link/support/LinkAccessTokens.java`）比对解锁 cookie 的签名：

```java
// 片段：src/main/java/com/linkforge/link/support/LinkAccessTokens.java
return MessageDigest.isEqual(provided, expected);
```

两处的共同点：被比较的一方是**攻击者可控的输入**，另一方是机密。凡是这种形状的比较，都用常量时间版本。`WebhookSigner` 的 javadoc 对接收方的第一条要求也是它。

## 无状态 token 的撤销难题：pwdVer

### 问题：改了密码，发出去的解锁 cookie 怎么办

密码短链的解锁 cookie 是一张无状态签名票据：格式 `{exp}.{sig}`，`sig = Base64url(HMAC-SHA256(key, linkId|code|exp|pwdVer))`。没有服务端会话，没有撤销表。现在链接主人把访问密码改了，之前已经发出去的 cookie 应当**立刻全部作废**。在"服务器什么状态都不存"的前提下怎么做到？

答案藏在签名材料里：把"当前密码的指纹"作为材料之一。文件 `src/main/java/com/linkforge/link/support/LinkAccessTokens.java`：

```java
// 片段：src/main/java/com/linkforge/link/support/LinkAccessTokens.java
/** First 8 bytes of SHA-256 of the current hash — a cheap binding to "this password". */
private static String pwdVer(String passwordHash) {
    byte[] digest = sha256(passwordHash == null ? "" : passwordHash);
    StringBuilder hex = new StringBuilder();
    for (int i = 0; i < 8; i++) {
        hex.append(String.format("%02x", digest[i]));
    }
    return hex.toString();
}
```

`pwdVer` 是**当前**密码哈希（库里那份 BCrypt 串）的 SHA-256 前 8 字节。整个机制推一遍：

1. 用户用密码 P 解锁成功，服务器算 `pwdVer(P的哈希)`，把它连同 linkId、code、过期时间一起做 HMAC 签名，发 cookie。
2. 主人改成密码 Q。库里存的哈希变成 Q 的。
3. 旧 cookie 再来：服务器用**现在**的哈希重算 `pwdVer`，得到的是 Q 的指纹；签名材料与当初签 P 指纹时不同，重算的签名对不上旧 cookie 里的签名。校验失败。
4. 所有基于 P 的 cookie 同时作废，**没有存任何撤销记录**。

撤销能力本来来自状态，这里把"状态"换成了"签名材料随当前密码变化"这个性质，撤销从 token 层挪到了材料层。这是无状态系统里值得记住的巧法。

### 密钥派生：一把主密钥，多个用途

cookie 的 HMAC 密钥不是直接用 JWT 密钥，而是派生的：

```java
// 片段：src/main/java/com/linkforge/link/support/LinkAccessTokens.java
private static final String KEY_DERIVE_INFO = "linkforge:link-access:v1";

this.hmacKey = hmacSha256(
        properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8),
        KEY_DERIVE_INFO.getBytes(StandardCharsets.UTF_8));
```

`hmacKey = HMAC-SHA256(JWT密钥, "linkforge:link-access:v1")`。这叫域分离（domain separation）：同一把主密钥，给每个用途加一个不同的标签派生出独立子密钥。好处是隔离：假设将来 cookie 的 HMAC 侧出了密码学分析，攻击者拿到的也只是一个专用子密钥，反推不出主密钥，更造不出 JWT。标签里的 `v1` 是派生方案的版本号，将来换派生方式时新旧格式可区分。代价也要看清：主密钥一换，所有子密钥跟着变，全部解锁 cookie 作废。项目接受这个代价（javadoc 原文：no new env, nothing in the repository，不引入新环境变量，也不把密钥落库）。

### validate：先便宜的检查，后昂贵的检查

校验方法（片段，完整逻辑见源文件）按这个顺序执行：

1. 任何畸形输入（没有点号、点号在首尾、exp 不是数字）**返回 false 而不抛异常**。校验函数把"解析失败"当普通结果处理，攻击者无法用畸形输入制造异常路径。
2. 先查 `exp` 是否过期。一次整数比较，几乎免费。
3. 再算 HMAC 并用 `MessageDigest.isEqual` 比对。计算量大。

顺序不是随便排的：把攻击者挡在昂贵操作之前，爆破请求买不到 HMAC 的算力。这与"解锁接口的两级限流先于 BCrypt 执行"是同一个思想在不同层的重复出现（`PasswordLinkIT` 用"第 6 次正确密码仍 429"钉住这个顺序，据测试代码与原项目决策记录）。慢哈希的 CPU 永远不为爆破流量服务。

边界：pwdVer 只绑"当前密码"，无法单独作废某一张 cookie（想只作废一张，要么缩短 TTL，要么引入服务端状态）；换密码是全量作废的粒度。

## 水平越权：查得到不等于归你

### 问题：A 的 token 访问 B 的数据

用户 A 登录后，把请求里的短链 code 换成 B 创建的那条，发 `GET /api/links/{B的code}`。走一遍安全链：JWT 有效，`ROLE_USER` 建立成功；授权规则只看路径和角色，`/api/links/**` 允许 `ROLE_USER`，通过。**过滤器链和授权规则对这个攻击完全无感**，因为 A 的身份是真实有效的，坏的只是"这个身份碰了别人的数据"。

越权分两种：垂直越权是低权限者拿到高权限功能（比如普通用户调管理接口），授权规则防它；水平越权是同级用户之间互看互改数据，规则防不了它，因为规则里根本没有"数据归属"的概念。归属校验只能由业务层做。

### 项目案例：getOwnedLink

文件 `src/main/java/com/linkforge/link/service/ShortLinkService.java` 第 567-574 行：

```java
// 片段：src/main/java/com/linkforge/link/service/ShortLinkService.java
private ShortLink getOwnedLink(String code) {
    AuthUser user = requireUser();
    ShortLink link = shortLinkMapper.selectOne(new LambdaQueryWrapper<ShortLink>().eq(ShortLink::getCode, code));
    if (link == null || link.getCreatedBy() == null || !link.getCreatedBy().equals(user.getUserId())) {
        throw new BizException(404, "short link not found");
    }
    return link;
}
```

逐行读：先从 `UserContext` 取当前用户（取不到抛 401）；按 code 查这条短链；然后三个条件合并成同一个结果：记录不存在、记录没有归属人、记录的归属人不是当前用户，**全部抛 404、文案完全一致**。

一个常见误解要在这纠正：这里的 SQL 并没有把 `createdBy = 当前用户` 写进查询条件"让别人的数据根本查不到"。真实机制是"查出来再在 Java 里比对归属"，只是对外表现与"不存在"完全相同。两种写法都能防住越权；把归属写进 SQL 条件的好处是数据库层就少返回数据，"查了再比"的好处是这段代码一处就能同时表达"不存在"与"不是你的"，且查询本身可以吃到 code 上的缓存与索引。结论相同：**A 看 B 的数据，得到的是 404**。

### 403 还是 404：泄露存在性的取舍

归属校验失败时，报 403 还是 404，取决于"资源是否存在"这件事本身算不算情报：

- 回 403：告诉攻击者"这个 code 存在，只是不归你"。攻击者枚举 code，用 403/404 的差异把全库的 code 探出来。
- 回 404：存在与不存在不可区分，枚举一无所获。

LinkForge 选 404 防枚举。什么时候 403 反而合理：资源存在性本来就公开的场景，比如管理后台里"你有账号但没这个页面权限"，藏页面存在性没有意义，明确报 403 反而减少排查成本。判断标准就一条：**这个 403 有没有泄露攻击者本不该知道的存在性**。

对比另一处：API Key 打到 `/api/keys/**` 得到的是 403 不是 404。因为"接口存在"不是秘密（API 文档写着），要保护的只是调用权，不存在枚举问题。两个选择并不矛盾，按泄露什么来定。

## SSRF：你的服务器替攻击者出手

### 问题：目标 URL 是用户填的

webhook 的目标地址由用户注册。用户填什么服务器就往哪儿发 POST。攻击者不填自己的服务器，填内网地址：`http://127.0.0.1:6379`、`http://192.168.1.0/...`。你的服务器在机房内网里，有防火墙之外的请求者没有的网络位置。攻击者借你的服务器出手，把请求打进内网。这就是 SSRF（Server-Side Request Forgery，服务端请求伪造）。

最危险的目标是云元数据地址 `169.254.169.254`。它是链路本地（link-local）地址，每台云主机上的这个地址背后是云厂商的元数据服务，返回的内容包括**临时访问凭证**。攻击者诱导你的服务器请求它，等于让服务器亲手把云凭证交出来。

### 为什么字符串黑名单没用

直觉解法是"检查 URL 字符串里有没有 127.0.0.1"。挡不住的变体一抓一把：十进制写法 `http://2130706433/`（等于 127.0.0.1）、IPv6 写法 `http://[::1]/`、缩写变体、还有最根本的一条：URL 里写的是**域名**，域名解析出的 IP 才是真正要连的地址，字符串里根本看不到 IP。正确姿势只有一种：把 URL 解析成结构（scheme、host），对 host 做 DNS 解析，然后**校验解析出的每一个 IP**。`InetAddress.getAllByName` 返回数组是有原因的，一个域名可能解析出多个地址，漏检任何一个都可能放行。

### 项目案例：WebhookTargetGuard

文件 `src/main/java/com/linkforge/webhook/support/WebhookTargetGuard.java`。它的执行时机有两处（javadoc 原文：applied at save time AND re-checked before every send）：保存 webhook 时 `check` 抛 400 拒绝；**每次发送前** `isBlocked` 再查一遍（域名指向可以随时改，保存时合法不代表发送时合法）。

第一段，入口校验（片段）：

```java
// 片段：src/main/java/com/linkforge/webhook/support/WebhookTargetGuard.java
String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
if (!scheme.equals("http") && !scheme.equals("https")) {
    return "scheme must be http or https";
}
if (uri.getUserInfo() != null) {
    return "userinfo is not allowed";
}
String host = uri.getHost();
if (host == null || host.isBlank()) {
    return "missing host";
}
```

协议白名单（只许 http/https，file、gopher 之类直接拒），禁 userinfo（`http://user@host/` 这种写法可用来干扰对真实目标的判断），host 必须存在。

第二段，解析后逐个 IP 校验（片段）：

```java
// 片段：src/main/java/com/linkforge/webhook/support/WebhookTargetGuard.java
InetAddress[] addresses = resolveHost(host);
for (InetAddress address : addresses) {
    if (violationOf(address) != null) {
        // Bw2-N6: the 400 body must not echo resolved addresses — one uniform reason
        return "blocked target";
    }
}
```

`violationOf(InetAddress)` 拦截的地址类目（方法原文逻辑，一段一段读）：环回（loopback，127.0.0.0/8 与 `::1`）、任意本地（0.0.0.0）、组播、链路本地（含云元数据 169.254.169.254）、站点本地（内网 10/172.16/192.168 段）、CGNAT 共享段 100.64/10、基准测试段 198.18/15、保留段 240/4、IPv6 唯一本地段 fc00::/7。此外还有一段专门处理**内嵌 IPv4 的 IPv6 地址**（`::ffff:a.b.c.d` 映射形、`64:ff9b::a.b.c.d` NAT64 形、`2002:a.b.c.d` 6to4 形）：取出内嵌的 IPv4，再按 IPv4 规则校验一遍。防的是用 IPv6 伪装 IPv4 内网地址的绕过变形。

两个细节见功力：

- 命中黑名单时统一返回 `"blocked target"`，注释原文说明原因：400 响应体不回显解析出的地址。如果回显"你解析到了 192.168.1.5"，攻击者就把校验器变成了内网探测仪。
- `allow-private-targets` 开关放行私有地址，注释与配置文件都写明仅限集成测试，生产永远 false。逃生门要标注适用范围，否则迟早有人图省事打开。

### 防护边界：它没防什么

javadoc 诚实声明了边界，这段原文值得直接译述：

- **DNS rebinding 未防**。校验发生在"解析后"，连接发生在"解析后"的稍后时刻，两次之间域名可以再变（rebind）：第一次解析到公网 IP 通过校验，真正连接时域名已解析到内网 IP。这是 resolve-then-connect 的 TOCTOU（检查与使用之间有时间差）问题，彻底解法要么用连接后校验，要么让出网流量走受控代理，javadoc 写明这两样本波次不做。
- 重定向不跟随。防护是"relay 的 HTTP 客户端从不跟随重定向"实现的，如果哪天改成跟随，SSRF 校验立刻绕过（先返回一个 302 指向内网）。
- 端口白名单、出网代理隔离：未做。

这节给读者的元教训：安全组件的文档必须写清"我防什么、我不防什么"。没写边界的防护会让人误以为安全，比没有防护更危险。

## AES-GCM：静态加密为什么用 AEAD

### 问题：webhook 的密钥要存库，怎么存

每个 webhook 有一个签名密钥（发请求时用来算 HMAC）。这个密钥必须**能还原成明文**（发送时要拿它算签名），所以哈希路线直接排除：哈希不可逆，存了哈希就没法签名了。剩下的选择是加密存储（静态加密，encryption at rest）。但普通加密立刻引出新问题：密文存进数据库，谁来保证密文没被人改过？解密是照单全收的，攻击者改掉密文的一个字节，解密出来就是另一个密钥，之后的签名全部错乱。

答案是 AEAD（Authenticated Encryption with Associated Data，带关联数据的认证加密）：把"加密"和"防篡改"合成**一个操作**。加密时输出密文加一段认证标签（tag），标签由密钥、密文共同算出；解密时先验标签，密文错一个比特，标签对不上，直接抛异常，根本不会产出明文。

JDK 原生支持 AEAD 的代表算法 AES-GCM，最小示例（密钥、nonce 全部固定，输出确定）：

```java
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

public class GcmDemo {
    public static void main(String[] args) throws Exception {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 7);
        byte[] nonce = new byte[12];
        Arrays.fill(nonce, (byte) 3);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, nonce));
        byte[] packed = cipher.doFinal("whsec_a1b2c3d4".getBytes(StandardCharsets.UTF_8));
        System.out.println("ciphertext+tag = " + Base64.getEncoder().encodeToString(packed));

        Cipher ok = Cipher.getInstance("AES/GCM/NoPadding");
        ok.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        System.out.println("decrypt intact: "
                + new String(ok.doFinal(packed), StandardCharsets.UTF_8));

        byte[] tampered = packed.clone();
        tampered[0] ^= 0x01;
        Cipher bad = Cipher.getInstance("AES/GCM/NoPadding");
        bad.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        try {
            bad.doFinal(tampered);
            System.out.println("decrypt tampered: no error");
        } catch (Exception ex) {
            System.out.println("decrypt tampered: " + ex.getClass().getName());
        }
    }
}
```

```text
ciphertext+tag = UpbQZjl3P3MYciBvj2NZoQyOvuJMsCMb8EYlapGv
decrypt intact: whsec_a1b2c3d4
decrypt tampered: javax.crypto.AEADBadTagException
```

推导：密文第一个字节翻转一个比特（`tampered[0] ^= 0x01`），解密抛 `AEADBadTagException`。篡改检测不是解密后再比对哈希，而是 AEAD 的解密步骤内建的：tag 验不过就没有"解密成功"这回事。这就是"一体"的含义：机密性与完整性由同一次运算、同一把密钥同时保证，不存在"忘了校验完整性"这种实现疏漏的空间。

`nonce`（number used once，一次性数字）是 GCM 的必需输入：12 字节，同一密钥下**绝不能重复**。GCM 在密钥加 nonce 重复时会发生灾难性的失效（内部认证密钥流可被恢复出来）。所以每次加密都用新的随机 nonce，并把它和密文存在一起（解密需要它，它不是秘密）。示例里为了确定性手工填了固定 nonce，生产代码不可这样做。

### 项目案例：WebhookSecretCipher

文件 `src/main/java/com/linkforge/webhook/support/WebhookSecretCipher.java`，逐段看：

```java
// 片段：src/main/java/com/linkforge/webhook/support/WebhookSecretCipher.java
private static final String VERSION_PREFIX = "v1:";
private static final int GCM_NONCE_BYTES = 12;
private static final int GCM_TAG_BITS = 128;

public String encrypt(String plaintext) {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey, "AES"));
    byte[] nonce = cipher.getIV();
    byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
    byte[] packed = ByteBuffer.allocate(nonce.length + ciphertext.length)
            .put(nonce).put(ciphertext).array();
    return VERSION_PREFIX + Base64.getEncoder().encodeToString(packed);
}
```

- 存储格式是 `v1:` 加 Base64(12 字节 nonce ‖ 密文 ‖ 16 字节 tag)，`doFinal` 返回的字节数组里密文和 tag 连在一起（JDK GCM 的输出约定，框架行为）。`v1:` 前缀让密文格式自描述：将来换算法或参数，按版本前缀分派解密逻辑，旧密文平滑迁移。无版本的密文格式是迁移噩梦。
- `cipher.getIV()` 取的是 JDK 内部随机源生成的 nonce，每加密一次一个新值，避开了"同密钥同 nonce"的灾难。
- 主密钥从环境变量读入（Base64 解码后必须恰好 32 字节，AES-256），只存在内存，从不落库落文件（javadoc 原文：never persisted）。

构造函数里的 fail 设计（片段）：

```java
// 片段：src/main/java/com/linkforge/webhook/support/WebhookSecretCipher.java
if (key != null && key.length != 32) {
    log.warn("LINKFORGE_WEBHOOK_SECRET_KEY must decode to 32 bytes (AES-256), got {} — webhook disabled",
            key.length);
    key = null;
}
// ...
if (key == null) {
    log.warn("webhook master key unavailable: endpoint management and delivery signing are OFF");
}
```

密钥缺失、Base64 解不开、长度不对，三种情况汇成同一个结果：`masterKey = null`，`isAvailable()` 返回 false，**整个 webhook 功能降级关闭**，端点存不了、投递签不了名。为什么不是"用默认密钥凑合加密"？因为加密的前提是一把真正的密钥，前提不成立还继续加密，就是假装安全，比明说不可用更糟。这是 fail-closed 在密码学配置上的应用（与后文 fail-open 的取舍对照着看）。另一个方向也要写清：主密钥丢了，所有已存的 secret 不可恢复，运维必须轮换全部租户密钥（javadoc 原文注明）。

## 攻防地图

每行连着"不防会怎样"一起记。全部条目对应的项目文件都已在本文出现并读过源码。

| 攻击 | 是什么 | 防法 | 项目实例 |
|------|--------|------|----------|
| XSS | 注入脚本在他人浏览器执行 | 输出转义，页面禁脚本 | `GatePages` 所有动态值过 `HtmlUtils.htmlEscape`，CSP `default-src 'none'` |
| CSRF | 借用户浏览器自动带 Cookie 伪造请求 | SameSite 或 CSRF token；API 用头带凭证免疫 | API 走 Bearer 头并禁 CSRF；解锁 cookie `SameSite=Lax` |
| SQL 注入 | 拼接 SQL 改变语义 | 参数化查询 | MyBatis-Plus Wrapper 预编译（见 [41-mybatis-plus.md](41-mybatis-plus.md)） |
| SSRF | 诱导服务器请求内网 | 解析后校验每个 IP，禁重定向 | `WebhookTargetGuard` |
| 时序攻击 | 用耗时差逐字节猜秘密 | 常量时间比较 | `MessageDigest.isEqual` 比对令牌与签名 |
| 暴力破解 | 无限试口令 | 慢哈希抬价 + 限流先于慢哈希 | BCrypt + 解锁接口两级限流 |
| 水平越权 | 访问同级他人数据 | 归属校验 + 统一 404 | `getOwnedLink` |
| 重放 | 截获合法请求重发 | 时间戳进签名 + 容差 + 去重 | `WebhookSigner` |

XSS 一行的细节（`src/main/java/com/linkforge/link/support/GatePages.java` 已读）：服务端渲染的解锁表单与拦截页里，唯一可能被注入的位置是用户控制的 code 与错误文案，全部经 `HtmlUtils.htmlEscape` 转义；响应头带 CSP（Content-Security-Policy，内容安全策略），`default-src 'none'` 禁止页面加载任何脚本，转义万一漏了也跑不起来。解锁 cookie 的 `SameSite=Lax`、`HttpOnly` 设置在 `RedirectController` 的 unlock 处理里（已读，第 59-65 行）。

## fail-open 与 fail-closed：故障时开门还是锁门

安全组件自己也会故障：Redis 挂了、配置没填、密钥解不开。这时组件有两个选择：fail-open（故障时放行，按没有这个组件处理）和 fail-closed（故障时拒绝，宁可业务不可用）。

选择依据不是"哪个更安全"，而是**这个组件守的是什么**：

| 组件 | 故障场景 | 策略 | 为什么 |
|------|----------|------|--------|
| Redis 限流器（[50-redis.md](50-redis.md)） | Redis 不可用 | fail-open | 限流是保护措施，不是安全边界；Redis 挂了还拒绝所有业务，损失比放行大 |
| 指标抓取端点 | 未配置抓取令牌 | fail-closed | 注释原话：指标泄露比缺监控更糟。配置缺失时拒绝所有请求 |
| webhook 主密钥非法 | 配置错误或缺失 | 降级关闭功能 | 加密前提不成立，不能假装加密 |

同一个"Redis 挂了"，限流器放行、（若令牌校验依赖 Redis 则）令牌校验拒绝：前者的故障损失是"可能被刷"，后者的故障损失是"指标裸奔"。保护措施 fail-open 保业务，安全边界 fail-closed 保数据，背结论之前先把这条依据推一遍。

## 常见错误与误区

- 「JWT 载荷是加密的」。只是 Base64URL 编码，`JwtDemo` 一行代码就解出来了。防篡改靠签名，防泄露靠"别把秘密放进去"。
- 「BCrypt 的盐要保密」。盐明文住在哈希串第 13 到 34 字符里，从来不是秘密；它防彩虹表，不防泄密。
- 「关掉 CSRF 就行」。前提是凭证不在 Cookie 里。有 Cookie 会话的系统关 CSRF 是事故；本项目敢关，是因为凭证走 `Authorization` 头。
- 「越权返回 403 更礼貌」。403 确认了资源存在。要防枚举就统一 404；只有存在性本来就公开的场景才选 403。
- 「SSRF 校验 URL 字符串」。字符串里看不到解析后的 IP，十进制 IP、IPv6 变体、域名解析全都能绕。必须 URI 解析加 DNS 解析后逐个 IP 校验。
- 「`shouldNotFilter` 返回 true 表示要过滤」。反的。true 是跳过。这个方法名害过很多人，LinkForge 三个过滤器都靠它分工。
- 「过滤器顺序无所谓」。抓取令牌过滤器与 JWT 过滤器的顺序错了，指标端点直接瘫痪（所有请求 401）。链序是安全正确性的一部分。

## 面试追问

**JWT 与 Session 怎么选，JWT 怎么"登出"？**
先答取舍：Session 把状态放服务端（踢人容易、多实例要共享存储），JWT 把状态放进凭证（多实例天然无状态、踢人难）。再答登出：无状态 token 签发后不可单方作废，方案是短过期加换密钥，或服务端黑名单（代价是把状态加回来）。追问"项目里怎么解决改密码后旧凭证"时，答解锁 cookie 的 pwdVer：把当前密码的指纹绑进签名材料，改密码即全量作废，撤销从 token 层挪到材料层。

**BCrypt 为什么慢是优点？**
慢哈希把"猜一次口令"的成本抬到毫秒级，登录方与爆破方付同样的价，拖库后的爆破成本乘上千万倍。cost 因子每加 1 翻倍，是安全与登录吞吐之间可调的旋钮。追问"为什么不用 SHA-256 加盐"答：SHA 的设计目标就是快，快正是爆破者要的；盐防的是预计算表，防不了逐个试。

**过滤器链的顺序为什么重要？**
用项目案例答：抓取令牌过滤器必须排在 JWT 过滤器前，否则 prometheus 的 Bearer 头被 JWT 过滤器当坏 token 拦成 401，`ROLE_METRICS` 永远授不出去；JWT 过滤器再用 `shouldNotFilter` 对该路径跳过，双保险。同时带两种凭证回 400 消除歧义，API Key 打管理面给干净 403 对齐能力白名单。一句话收束：链序决定每个过滤器看到的请求形状，顺序错了，后面的机制全部失效。

## 本章总结

一条链：`MetricsScrapeTokenFilter` → `JwtAuthFilter` → `ApiKeyAuthFilter` → 授权规则 → Controller，四个组件各防一类攻击，`shouldNotFilter` 负责分工，`finally` 双清负责 ThreadLocal 不串号。

一张票据：JWT 三段结构，签名防篡改不防泄露，无状态意味着不能真正登出，撤销只能靠短过期、换密钥，或像 pwdVer 那样把撤销绑进签名材料。

三件密码学常识：慢哈希（BCrypt，cost 是安全与吞吐的旋钮，慢是特性）、HMAC 加时间戳（防伪造、防篡改、防重放三合一）、常量时间比较（时间本身是侧信道，`MessageDigest.isEqual` 是它的封口）。

两个取舍：403 与 404 按"泄露不泄露存在性"来选；fail-open 与 fail-closed 按"守的是保护措施还是安全边界"来选。

判断新代码该不该用某个机制的顺序：先问这个位置要防什么攻击，再问这个机制防的恰好是不是它，最后问它的边界（没防什么）清不清楚。三个问题答不全，就还没到写代码的时候。

## 自测

1. 为什么抓取令牌过滤器必须排在 JWT 过滤器之前？此时 `JwtAuthFilter.shouldNotFilter` 对 `/actuator/prometheus` 返回 true 还是 false，各代表什么？
   答：prometheus 的 Bearer 头里是抓取令牌不是 JWT，JWT 过滤器先跑会把它当坏 token 直接 401，`ROLE_METRICS` 永远授予不了。`shouldNotFilter` 返回 true 表示"这个请求不归我管，跳过"，JwtAuthFilter 对该路径返回 true（跳过解析）；这是链序之外的第二道保险。
2. 改密码后旧解锁 cookie 立刻全部失效，机制是什么？
   答：签名材料包含 pwdVer（当前密码哈希的 SHA-256 前 8 字节）。改密码后服务器用新密码的指纹重算 HMAC，与旧 cookie 里的签名对不上，校验返回 false。撤销能力来自"签名材料随当前密码变化"，不需要任何服务端撤销记录。
3. 带有效 API Key 的请求打到 `/api/keys/**`，返回 401 还是 403？为什么？
   答：403。key 认证本身成功（过滤器已建立 ROLE_API_KEY 身份），失败发生在授权环节：能力白名单没把管理面开放给 key。401 的含义是"没认出你"，与事实不符。
4. JWT 的签名防什么、不防什么？手机号放进 JWT 载荷安全吗？
   答：防篡改（改载荷签名就对不上，重算需要密钥），不防泄露（载荷只是 Base64URL，无需密钥即可解码，JwtDemo 第二行输出证明了这点）。不安全：任何拿到 token 的人都能读出手机号，敏感信息不该进载荷。
5. `MessageDigest.isEqual` 比 `String.equals` 慢约两个数量级，为什么所有凭证比较还是用它？
   答：`String.equals` 遇到第一个不同字符就返回，耗时泄露"对了多少"，攻击者可测响应时间逐字节猜出密钥（计时侧信道）。`isEqual` 无短路，耗时只由长度决定。慢约百纳秒是安全代价，只付在比对机密的地方。
6. webhook 主密钥配置非法时，`WebhookSecretCipher` 为什么不退回明文存储或用默认密钥加密，而是把整个 webhook 功能关闭？
   答：加密的前提是一把真正可用的密钥。前提不成立还继续"加密"，要么是明文伪装成密文，要么是用已知默认密钥的伪加密，都比明说不可用更危险。安全边界的故障要 fail-closed：宁可功能不可用，不可保护假在。

## 下一步

- 一次请求的完整旅程（安全链只是其中一段）：[31-spring-boot-mvc.md](31-spring-boot-mvc.md)。
- ThreadLocal 与线程池复用的完整机制：[13-concurrency.md](13-concurrency.md)。
- `String.equals` 与字符串比较的底层：[java/10-03-equality-and-strings.md](java/10-03-equality-and-strings.md)；过滤器异常与全局异常处理的边界：[java/10-06-exceptions.md](java/10-06-exceptions.md)。
- 参数化查询如何消灭 SQL 注入：[41-mybatis-plus.md](41-mybatis-plus.md)。
- 限流器 fail-open 的完整决策：[50-redis.md](50-redis.md)。
- webhook 投递的产生、重试与 eventId 去重的队列侧：[51-rabbitmq-outbox.md](51-rabbitmq-outbox.md)。
- 解锁 cookie 在跳转判定链中的位置：[81-linkforge-flows.md](81-linkforge-flows.md)。
- Cookie 属性的网络语义（`SameSite`、`Secure`、`HttpOnly`）：[20-network.md](20-network.md)。
