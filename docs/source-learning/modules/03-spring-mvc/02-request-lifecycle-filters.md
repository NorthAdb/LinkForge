# 3.2 请求生命周期与 Filter

## 1. 本节学习目标

1. 画出一次 HTTP 请求从容器到 Mapper 的完整路径，说出每一层的职责与失败时的走向。
2. 掌握 Filter 与 Controller 的边界：Filter 能做什么、不能做什么、异常到不了哪里。
3. 读懂本项目的四个安全 Filter 的执行顺序与跳过条件（`shouldNotFilter`）。
4. 理解静态控制台与 multipart 在 prod/dev 两个 profile 下的不同行为。
5. 背下三张 HTML 门禁页的安全响应头组合与 CSP 内容。

## 2. 前置知识

- [3.1 Controller 与 REST 设计](01-controllers-rest.md)：Controller 层的绑定与状态码。
- [2.1 统一响应与全局异常](../02-common/01-unified-response-exceptions.md)：`GlobalExceptionHandler` 的七个分支，本节解释它接不到的一类异常。
- Servlet 规范常识（框架行为）：Servlet 容器（这里内嵌 Tomcat）按注册顺序对每个请求执行一串 `Filter`，最后才进 Servlet。
- （Java 语言知识）`finally` 块：无论 try 是否抛异常都会执行，Filter 用它做资源清理。

## 3. 本节涉及的源码

| 文件 | 职责 | 阅读顺序 | 为什么读它 |
|------|------|----------|------------|
| `security/SecurityConfig.java`（100 行） | 装配过滤器链与授权矩阵 | 第 1 | 顺序与放行清单的权威 |
| `common/web/CorrelationIdFilter.java`（103 行） | cid 注入（安全链之外的第一个 Filter） | 第 2 | 请求的对账键（2.2 深讲） |
| `security/JwtAuthFilter.java`（160 行） | Bearer JWT 认证 + 令牌回查 | 第 3 | 认证 Filter 的完整形态（5.1/5.2 深讲） |
| `security/ApiKeyAuthFilter.java`（220 行） | X-API-Key 认证+属主回查+配额 | 第 4 | Filter 异常边界的自述材料 |
| `security/MetricsScrapeTokenFilter.java`（66 行） | 指标抓取守卫 | 第 4 | fail-closed 的对照样本 |
| `security/QrShareAuthFilter.java`（73 行） | 签名分享放行 | 第 5 | 最窄的 Filter，只管一个方法+路径 |
| `link/support/GatePages.java`（84 行） | 三张 HTML 门禁页 | 第 6 | CSP 常量与转义纪律 |
| `src/main/resources/application.yml`（看 :13-17, :251-259） | multipart 与 prod 静态开关 | 第 7 | profile 差异 |

## 4. 类的定位

这一课讲的是 Controller 之前的世界。请求路径：Tomcat → 容器级 Filter 链（第一站是 `CorrelationIdFilter`，`@Order(HIGHEST_PRECEDENCE)`，给请求发 cid，2.2 深讲）→ springSecurityFilterChain（内部还有一层）→ DispatcherServlet → Controller → Service → Mapper。安全 Filter 不再是请求的第一站，但仍是认证的第一站。位置见[包结构图](../../diagrams/archify/html/03-package-map.html)：security 包 10 个类（含被四链共用的 AuthUser/UserContext），link/support 的 GatePages 是 Filter 与 Controller 共用的视图。

## 5. 核心源码详解

### 5.1 过滤器链的装配（SecurityConfig.java:33-94）

```java
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/actuator/health", "/api/health").permitAll()
                            .requestMatchers("/api/auth/**").permitAll()
                            .requestMatchers(HttpMethod.GET, "/r/**").permitAll()
                            .requestMatchers(HttpMethod.HEAD, "/r/**").permitAll()
                            // B3 unlock form: anonymous by design, brute-force limited in the service
                            .requestMatchers(HttpMethod.POST, "/r/*/unlock").permitAll();
```

（SecurityConfig.java:37-46）

（框架行为）CSRF 关闭与 STATELESS 会话是 JWT 架构的标准动作：服务器不存会话，每个请求自带凭证。放行清单五条：两个健康检查、注册登录、匿名跳转的 GET/HEAD、匿名解锁表单 POST。行内注释「brute-force limited in the service」预告了限流的位置：Filter 不挡暴力破解，Service 层的两级限流挡（ShortLinkService.unlock，模块 07）。

Filter 插入顺序（SecurityConfig.java:89-92）：

```java
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(metricsScrapeTokenFilter, JwtAuthFilter.class)
                .addFilterAfter(apiKeyAuthFilter, JwtAuthFilter.class)
                .addFilterAfter(qrShareAuthFilter, ApiKeyAuthFilter.class);
```

（框架行为）`addFilterBefore/After` 以 Spring Security 内置 Filter 为锚点定位。展开后的自定义顺序：MetricsScrapeTokenFilter → JwtAuthFilter → ApiKeyAuthFilter → QrShareAuthFilter。类注释（:84-88）解释了为什么 Metrics 必须在 JWT 前：抓取令牌不是 JWT，若 JWT 先行会先 401。ApiKey 在 JWT 后：混发两种凭证时由它判 400（:73-78）。

授权矩阵（:61-69）值得背：qr.png 三角色；`/api/links/**` 双角色（USER 或 API_KEY）；`/api/keys/**` 与 `/api/webhooks/**` 仅 USER（API Key 摸管理面是 403）；`/actuator/prometheus` 仅 METRICS 且必须排在 `/actuator/**` 前（行内注释 :65-66）；`anyRequest().hasRole("USER")` 兜底。匹配器按声明顺序短路，顺序错了角色矩阵就失效。

### 5.2 认证 Filter 的完整形态：JwtAuthFilter（JwtAuthFilter.java，160 行）

```java
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // the scrape token is not a JWT — parsing it would 401 before the metrics
        // filter can grant ROLE_METRICS (D-Bw345-42)
        if (request.getRequestURI().equals("/actuator/prometheus")) {
            return true;
        }
        // Batch 2a: refresh/logout/accept-invitation present their own credentials
        // (a refresh token or an invitation token) and never need a live access
        // token ... precise POST paths only, so nothing else slips through.
        String path = request.getRequestURI();
        return "POST".equals(request.getMethod()) && (path.equals("/api/auth/refresh")
                || path.equals("/api/auth/logout") || path.equals("/api/auth/accept-invitation"));
    }
```

（框架行为）`OncePerRequestFilter` 保证同一请求只执行一次（转发/include 不重复）。`shouldNotFilter` 返回 true 即跳过，是 Filter 级的路径白名单——批次 2 后它有两类成员：抓取令牌路径（老成员），与三个「自带凭据」的会话入口（新成员，精确路径 + POST 限定）。

主体在批次 2a 后是五步：读 `Authorization: Bearer` 头 → 验签解析 → **回查用户行**（行在、status=1、tv 与令牌一致，任一不过是静默未认证）→ must_change 墙（403 或豁免放行）→ 把以数据库为准的 `AuthUser` 放进 `UserContext` 并授 ROLE_USER。回查失败中的存储故障是唯一会直写响应的分支（503 固定文案）。这五步的逐行拆解在 5.2 课，本课只需记住它在链上的位置与一个结构事实：**Filter 从「无状态验签器」变成了「每请求查一次库的复核器」**，这是短 access 寿命与即时撤销换来的成本。两个收尾仍在 finally：

```java
        } finally {
            UserContext.clear();
            SecurityContextHolder.clearContext();
        }
```

（本项目实现）双清是硬纪律。`UserContext` 是静态 ThreadLocal，Filter 不清则 Tomcat 线程复用时下一个请求可能读到上一个用户的数据。清 SecurityContext 是同一逻辑对 Spring 的复刻。（Java 语言知识）finally 对成功、异常、提前 return 三种路径都执行，所以匿名请求（没带 Bearer 直接放行）也会被清，幂等无害。

### 5.3 Filter 异常到不了 @RestControllerAdvice（ApiKeyAuthFilter.java:32-42）

这是本课的核心边界。ApiKeyAuthFilter 的类 javadoc 自己说了：

```java
/**
 * ... A valid key acts as
 * its creating user with ROLE_API_KEY; JWT and key together are a 400, invalid or
 * revoked keys a 401, quota exhaustion a 429 with Retry-After. All bodies mirror
 * the GlobalExceptionHandler shapes because filter exceptions never reach it.
 */
```

（ApiKeyAuthFilter.java:32-38，节选）

（框架行为）原因在层级：`@RestControllerAdvice` 由 DispatcherServlet 调用，而 Filter 在 DispatcherServlet 之前。Filter 里抛出的异常不会经过 MVC 的异常解析，只会进入 Spring Boot 的 /error 兜底（ERROR dispatch），响应形态随请求 Accept 而定（JSON 或白标页）。所以 Filter 的每个错误分支必须自己手写响应。数一数本类与 SecurityConfig 里的硬编码 JSON：400 ambiguous、401 invalid、401 revoked（对外与 invalid 同文案，只靠 metrics tag 区分）、403 forced_change（批次 2 的 M7 墙）、503 存储不可用（S4-5，与 JwtAuthFilter 逐字节共用同一常量）、429 quota、401 entryPoint（SecurityConfig）、403 denied（SecurityConfig）。这些字面量与 `ApiResponse` 的形状是手工同步的——改 `ApiResponse` 结构必须同步改这些字符串，这是这套架构的真实维护成本。其中 503 的共用做对了一半：两个过滤器引用同一个 Java 常量 `STORE_UNAVAILABLE_BODY`，至少字节级一致有编译期保证；其余各处仍是独立字面量。

对照看正确姿势（authenticate 返回 null 的分支）：

```java
            ApiKey key = apiKeyService.authenticate(apiKey);
            if (key == null) {
                meterRegistry.counter("linkforge.apikey.auth", "result", "invalid").increment();
                writeJson(response, 401, "{\"code\":401,\"message\":\"invalid api key\",\"data\":null}");
                return;
            }
```

先打指标再写响应，`writeJson` 统一设置状态码与 Content-Type。注意这里没有 throw：Filter 用「写完直接 return」终止请求，不进 filterChain。

配额拒绝还多一个响应头：`Retry-After` 秒数，与 2.1 课的 429 分支同构——因为 429 没有 Retry-After 就丢了客户端重试的依据。

### 5.4 两个窄 Filter：fail-closed 与最小放行

MetricsScrapeTokenFilter 是 fail-closed 的样本（MetricsScrapeTokenFilter.java:46-56）：

```java
        String configured = properties.getMetrics().getScrapeToken();
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String provided = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
        if (configured == null || configured.isBlank() || provided == null
                || !MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8),
                        provided.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            ...
            return;
        }
```

类 javadoc（:22-25）给出理由：未配置令牌时拒绝一切请求，因为「指标泄漏比缺一次抓取严重」。`MessageDigest.isEqual` 是常量时间比较，防时序侧信道。它的 `shouldNotFilter`（:34-41）还有个小巧思：带 X-API-Key 头的请求直接跳过，让 ApiKeyAuthFilter 给出干净的 403 而不是这里的 401。

QrShareAuthFilter 只管一件事（QrShareAuthFilter.java:37-43）：GET 且路径匹配 `^/api/links/([^/]+)/qr\.png$` 且带 exp/sig 参数，否则一律跳过。验证通过也只授一个 9 行 record 的 principal（QrSharePrincipal，只携带 code）。javadoc（:21-26）强调「没有 permitAll 的 QR 路由」：签名失效、参数缺失、方法不对，全部退回常规 JWT/API-Key 认证，匿名流量遇到标准 401。Filter 的默认姿态是「什么都不做」，授权矩阵照常兜底。

### 5.5 静态控制台与 multipart：两个 profile 两种人生

**静态控制台**。`src/main/resources/static/index.html` 是单文件控制台（1,423 行，内联样式与逻辑；S2 收尾后已定型：改密页、邀请接受页、模态口令框、启动恢复会话、登出全部设备，本章不钉 DOM 细节）。dev 下 Spring Boot 自动把 static/ 目录映射到 `/`。（本项目实现）prod 下这个映射被关掉（application.yml 的 spring.web.resources 段）：

```yaml
  web:
    resources:
      # no static console in prod
      add-mappings: false
```

只有这个还不够。SecurityConfig.java:51-54 的注释解释了为什么：

```java
                        // prod: deny docs and the static console at the filter chain;
                        // add-mappings=false alone leaves "/" erroring with 500.
                        auth.requestMatchers(docsPaths).denyAll()
                                .requestMatchers(consolePaths).denyAll();
```

`add-mappings=false` 让 `/` 变成 404 语义的缺失资源，但异常路径走得难看（注释实测会 500）。Filter 链上 `denyAll` 给出干净的 403，并且把 swagger 路径一并挡住。`consolePaths = {"/", "/index.html", "/favicon.ico"}`（:36）。prod 还叠加 `springdoc.api-docs.enabled=false`（application.yml:296-301）——3.1 课讲的三层开关在这里合流。

**multipart**。dev/test 下上传映射生效（application.yml:13-17）：

```yaml
  servlet:
    multipart:
      # D-C3-25: only the CSV import accepts uploads (256 KiB file / 512 KiB request)
      max-file-size: 256KB
      max-request-size: 512KB
```

超限时 multipart 解析器在请求进入任何 Controller 之前抛 `MaxUploadSizeExceededException`。GlobalExceptionHandler 为它写了专门的 400 分支，javadoc 说明没有这个分支时会掉进兜底变 500。这是 5.3 边界的反面：异常只要发生在 MVC 内部（哪怕在 Controller 之前），advice 就能接住；只有 Filter 层才是 advice 的盲区。

Service 层不信解析器（ShortLinkService.importCsv 开头）：`file.isEmpty()` 与 `file.getSize() > 256 * 1024` 再查一遍。双层校验是纵深防御的日常形态：解析器上限保护容器内存，业务校验给出更精确的报错文案。

### 5.6 安全响应头与 CSP：HTML 响应的最低装备

三张门禁页共用一个 CSP 常量（GatePages.java:12-13）：

```java
    /** Locks the pages down: no script, no framing, no external forms. */
    public static final String CSP = "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'";
```

（框架行为）`default-src 'none'` 禁止加载一切外部资源；`style-src 'unsafe-inline'` 只为内联样式开口；`form-action 'self'` 只允许表单提交回本站（解锁表单需要）；`frame-ancestors 'none'` 禁止被 iframe 嵌套。页面正文零 JS，动态值全部过 `HtmlUtils.htmlEscape`（GatePages.java:38-40, 62-64）。

头由 Controller 挂（RedirectController.java:92-95）：

```java
            ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .header("Cache-Control", "no-store")
                    .header("Content-Security-Policy", GatePages.CSP)
                    .header("X-Content-Type-Options", "nosniff");
```

三个头的分工：`no-store` 防止门禁页被缓存后张冠李戴；`nosniff` 禁止浏览器嗅探改判 Content-Type；CSP 管内容来源。Warn 分支多一个 `Referrer-Policy: no-referrer`（:109）：告警页上有目标 URL，用户点继续链接时不能把带 code 的来源地址泄给目标站。解锁成功下发的 cookie 同样成套（:59-65）：`httpOnly`（JS 读不到）、`sameSite=Lax`、`secure` 跟随请求协议、`path` 限定在 `/r/{code}`。

## 6. 技术原理补充

**Filter 与 Interceptor 与 Advice 的层次**（框架行为）。三者都「包住」Controller，但位置不同：Filter 在 Servlet 规范层， DispatcherServlet 之前，能改请求响应本身；Spring 拦截器在 MVC 内部，Controller 前后；`@RestControllerAdvice` 只管异常与响应体加工。选型经验：认证、安全头这类「与 MVC 无关的事」放 Filter； Controller 级横切（本项目没有）放拦截器；异常翻译放 advice。

**DispatcherServlet 的角色**（框架行为）。它是所有请求的总入口 Servlet（`/` 映射），负责按 URL+方法选 Controller、做参数绑定、调方法、序列化返回值、解析异常。3.1 课看到的绑定与 2.1 课看到的异常映射都发生在它内部。静态资源在 Boot 下由它后面的 ResourceHttpRequestHandler 兜住，`add-mappings=false` 关掉的就是这个兜底。

**SecurityFilterChain 与容器 Filter 的关系**（框架行为）。Spring Security 整体是一个名叫 `springSecurityFilterChain` 的容器级 Filter，内部再串 Security 自己的几十个内置 Filter 与你 add 的自定义 Filter。所以自定义 Filter 的「之前/之后」锚点（UsernamePasswordAuthenticationFilter 等）都是这条内链的节点。

**OncePerRequestFilter 解决什么**（框架行为）。Servlet 转发可能让同一请求二次进入 Filter 链。该基类用请求属性标记已处理，保证认证逻辑只跑一次。本项目四个安全 Filter 全部继承它。

## 7. 可视化

- [系统架构图](../../diagrams/archify/html/00-system-architecture.html)（由并行图表任务产出）：请求从外部进入 JVM 后的第一站就是过滤器链。
- [运行与部署图](../../diagrams/archify/html/01-runtime-deployment.html)（由并行图表任务产出）：Tomcat（内嵌）与 Compose 边界的相对位置。
- [跳转判定链 Mermaid 备用图](../../diagrams/mermaid/redirect-decision-chain.md)：Filter 链之后的 Service 层判定步骤全在这张图里。

阅读顺序：先用架构图定位「进 MVC 之前」，再按本节 5.1 的顺序在 SecurityConfig 里数四个 Filter，最后用跳转链图看 Filter 放行之后发生什么。

## 8. 项目中的真实执行过程

走一遍 `GET /r/AbC1234`（未解锁的密码链）的完整生命周期。

1. Tomcat 接收请求，进入 springSecurityFilterChain。
2. MetricsScrapeTokenFilter：URI 不是 `/actuator/prometheus`，shouldNotFilter 跳过（MetricsScrapeTokenFilter.java:40）。
3. JwtAuthFilter：匿名浏览器没有 Authorization 头，:37-38 判空放行，finally 双清。
4. ApiKeyAuthFilter：URI 以 `/r/` 开头不以 `/api/` 开头，shouldNotFilter 返回 true（ApiKeyAuthFilter.java:58-61）。
5. QrShareAuthFilter：路径不匹配 QR 正则，跳过（QrShareAuthFilter.java:42）。
6. 授权矩阵：`GET /r/**` permitAll（SecurityConfig.java:43），通过。
7. DispatcherServlet 匹配 RedirectController.redirect（RedirectController.java:36-41），`{code}` 绑定为 "AbC1234"。
8. Service 层判定链走到密码门（ShortLinkService.java:691-694）：行有 accessPasswordHash 且无有效 cookie，返回 `RedirectOutcome.Locked`。
9. render（RedirectController.java:98-104）：GET 带 body，状态 200，`html()` 把 Content-Type 设为 text/html;charset=UTF-8（:120-122，注释说明不能落回 text/plain 默认），三个安全头挂上，body 是 GatePages.unlock 的 HTML（密码表单，form-action 'self'）。
10. 浏览器渲染表单。用户提交后走 `POST /r/AbC1234/unlock`，那是另一个 Filter 都放行、Service 两级限流加 BCrypt 的故事（模块 07）。

错误对照：如果第 7 步 URL 是 `/api/links` 且带着坏 JWT，JwtAuthFilter 在第 3 步就会直写 401 JSON 并 return（JwtAuthFilter.java:51-54），请求永远到不了 DispatcherServlet——GlobalExceptionHandler 对此一无所知。

## 9. 为什么这样设计

- 认证放 Filter 而不是拦截器：认证要在「选不选得上 Controller」之前完成，且 `/r/**` 这类非 JSON 端点也要受保护。（框架行为）这也是 Spring Security 的原生位置。
- Filter 内手写 JSON 而不是抛异常：advice 接不到，抛了就进 Boot 的 /error 兜底（形态随 Accept 而定，还可能 500）。与其造一个「Filter 专用异常翻译器」，本项目选择字面量复制 ApiResponse 形状（ApiKeyAuthFilter.java:36-37 注释自述）。（推断）只有 6 处、形状稳定时，复制比抽象便宜。
- 静态控制台在 prod 双重关闭：静态页与 API 不同源策略、不同缓存策略、不同审计需求，prod 只留 API 是攻击面最小化。（推断：从 denyAll 注释与 docs-enabled 命名反推。）
- 门禁页不进模板引擎：三张页、零 JS、强转义需求，手拼文本块加 HtmlUtils 足够，少一个依赖少一类漏洞（GatePages.java:5-8 注释）。

## 10. 本节总结

- 完整链路：容器 Filter 链 → Security 四 Filter（Metrics→JWT→ApiKey→QrShare）→ DispatcherServlet → Controller → Service → Mapper。
- Filter 异常是 advice 盲区，本项目六处硬编码 JSON 是这一边界的直接代价；改 ApiResponse 形状必须同步改 Filter。
- 每个认证 Filter 的 finally 双清（UserContext + SecurityContext）是 ThreadLocal 卫生纪律。
- 静态控制台 prod 关两层（add-mappings + denyAll），multipart 上限 256KiB 配 advice 专门 400 分支加 Service 复查。
- HTML 响应的最低装备：no-store + nosniff + CSP（default-src 'none' 基调），Warn 页多一个 no-referrer。

## 11. 课后练习

**练习 1（概念理解）**：为什么 `GlobalExceptionHandler` 加一个 `@ExceptionHandler(ServletException.class)` 也接不住 ApiKeyAuthFilter 里抛的异常？

答案解析：advice 的作用域是 DispatcherServlet 内部的处理过程。Filter 在 DispatcherServlet 之前执行，异常抛出时 MVC 上下文尚未建立，advice 没有机会介入，异常直达容器错误页。这是位置问题不是类型问题（ApiKeyAuthFilter.java:36-37 注释点明）。

**练习 2（源码定位）**：找出四个安全 Filter 的 shouldNotFilter 分别放行什么路径，并说明 MetricsScrapeTokenFilter 对带 X-API-Key 请求的特殊处理。

答案解析：JwtAuthFilter 只跳 `/actuator/prometheus`（JwtAuthFilter.java:30）；MetricsScrapeTokenFilter 跳「非 prometheus 或带 X-API-Key 头」的请求（MetricsScrapeTokenFilter.java:37-40），后者是为了让 ApiKeyAuthFilter 回 403；ApiKeyAuthFilter 只审 `/api/**` 与非 health 的 `/actuator/**`（ApiKeyAuthFilter.java:56-62）；QrShareAuthFilter 只处理 GET + qr.png 正则 + exp/sig 齐全（QrShareAuthFilter.java:37-43）。

**练习 3（执行过程）**：同一个请求同时带 `Authorization: Bearer eyJ...` 和 `X-API-Key: lfk_...`，写出完整走向与最终响应。

答案解析：JWT Filter 先跑，凭证合法，置 ROLE_USER 放行；ApiKeyAuthFilter 后跑，发现有 key 头，再查到 Bearer 头，判「混发凭证」400（ApiKeyAuthFilter.java:73-78），写 `{"code":400,"message":"ambiguous credentials: send either a bearer token or X-API-Key","data":null}` 并 return，metrics 记 result=ambiguous。安全头没有（这是 JSON 错误不是 HTML），Filter 直接写响应。

**练习 4（调用链）**：prod 下访问 `GET /` 会发生什么？分别指出 application.yml 与 SecurityConfig 里哪两行让这条路径行为不同。

答案解析：SecurityConfig 的 consolePaths denyAll（SecurityConfig.java:53-54）先命中，Filter 链直接 403，请求进不了 MVC。add-mappings=false（application.yml:251-254）在 dev 之外也关闭了资源映射；注释（SecurityConfig.java:51-52）记录了只关映射不关 Filter 的教训——`/` 会以 500 收场而不是干净 403。

**练习 5（设计分析）**：GatePages.unlock 的表单为什么用 `form-action 'self'` 而不是完全禁止表单？

答案解析：解锁就是密码表单，POST 目标必须是本站 `/r/{code}/unlock`（GatePages.java:76）。CSP 若禁止 form-action 会让解锁功能死掉；限定 'self' 则外部站点无法把本页当提交目标，也不能诱导表单发往第三方。最小开口原则：CSP 每个指令都对应一个真实功能需求（样式、表单），其余全禁（GatePages.java:12-13）。

## 12. 与后续课程的联系

模块 05 会逐字拆解 SecurityConfig 的角色矩阵与 JWT 细节（课表见 [03-curriculum-roadmap.md](../../03-curriculum-roadmap.md) 模块 05 一节）；本节你只需要记住顺序与边界。跳转 Service 的门禁链（第 8 步的密码门）在模块 07 展开。Filter 里 `UserContext.set` 的 ThreadLocal 在 [4.3 事务](../04-mybatis-mysql/03-transactions.md) 会再次出场：ClickRecordService 在事务内读它，读不到就存 null，匿名跳转也能记点击。
