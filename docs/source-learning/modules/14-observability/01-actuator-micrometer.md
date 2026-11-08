# 14.1 Actuator 与 Micrometer：端点暴露、健康检查与抓取安全

> 从本课起进入模块 14：可观测性与调度任务。第一课回答三个问题：这个应用对外暴露哪些管理端点，谁来读它们，读到的数据长什么样。核心结论先给出：只开 health、info、metrics、prometheus 四个端点；`/actuator/prometheus` 用专用令牌保护，令牌没配置就拒绝所有人；每个指标带 `application=linkforge` 公共标签，HTTP 指标的 `uri` 标签只允许模板值。

## 1. 本节学习目标

1. 说出 Actuator 与 Micrometer 的分工：一个提供 HTTP 管理端点，一个提供指标门面，Prometheus 从抓取端点拉数据。
2. 背出暴露的四个端点，解释为什么只开这四个，以及 health 细节的 `always` 与 `when_authorized` 两档。
3. 逐行读懂 `MetricsScrapeTokenFilter`：常量时间比较、未配置时 fail-closed 401、API Key 走 403 的完整路径。
4. 解释 `http.server.requests` 为什么单独开直方图和 SLO 桶，`http.client.requests` 为什么关闭。
5. 读懂 `HttpUriTagAllowList` 的有界 tag 设计：`uri` 只允许模板值，白名单外一律折叠为 UNKNOWN。

## 2. 前置知识

- **Actuator**：Spring Boot 的管理端点框架。加上 `spring-boot-starter-actuator` 依赖（`pom.xml:59`）后，应用自动获得一组 `/actuator/**` HTTP 端点。默认只开 health，其余要在配置里显式暴露。
- **Micrometer**：Spring Boot 的指标门面（facade，统一接口层）。业务代码面向 `MeterRegistry` 计数，不直接依赖某个监控系统；它负责把计数翻译成 Prometheus 文本格式、Netflix Atlas 格式等。加 `micrometer-registry-prometheus`（`pom.xml:63-64`）后，导出格式就有了 Prometheus 这一种。
- **Meter 与 tag**：Meter 是一个指标的句柄，四种常用形态是 Counter（只增计数）、Gauge（当前值快照）、Timer（次数加耗时分布）、DistributionSummary。tag 是指标的键值对维度，比如 `uri="/r/{code}"`。Prometheus 按指标名加 tag 集合区分时间序列，tag 的取值组合数叫基数（cardinality）。基数失控是监控系统最常见的翻车方式：每条时间序列常驻内存，一个「用户 ID 级」的 tag 能把序列数放大几十万倍。
- **拉取模型**：Prometheus 主动定期来抓（scrape），应用只管在抓取端点吐出当前快照。这决定了本课后面两个设计：导出必须便宜，不能在抓取线程里查库（14.2 的 BacklogGauges 就为此做了采样解耦）。
- **Servlet Filter（模块 5 已铺垫）**：`OncePerRequestFilter` 子类插入过滤器链，本课的抓取令牌过滤器就是链上一环。

## 3. 本节涉及的源码

| 文件 | 行数 | 职责 | 阅读顺序 |
|----|----|----|----|
| `src/main/resources/application.yml` | 266 | management 段 `:26-48` 是本课的配置中心；prod 覆盖 `:265-266` | 1 |
| `src/main/java/com/linkforge/security/MetricsScrapeTokenFilter.java` | 67 | 抓取令牌过滤器（本课主角一） | 2 |
| `src/main/java/com/linkforge/security/SecurityConfig.java` | 100 | 过滤器顺序与 `/actuator/**` 授权规则（`:41`、`:65-68`、`:89-92`） | 3 |
| `src/main/java/com/linkforge/observability/HttpUriTagAllowList.java` | 51 | uri tag 白名单（本课主角二） | 4 |
| `src/main/java/com/linkforge/config/LinkForgeProperties.java` | 239 | Metrics 内部类（`:53-59`） | 5 |
| `src/main/java/com/linkforge/link/controller/HealthController.java` | 15 | 应用自己的静态 `/api/health` | 6 |
| `src/main/java/com/linkforge/config/OpenApiConfig.java` | 41 | BuildProperties 消费方（info 数据的旁证） | 7 |
| `src/test/java/com/linkforge/observability/PrometheusScrapeIT.java` | 157 | 令牌门禁、tag 有界、直方图的回归测试 | 8 |
| `pom.xml` | 281 | actuator 与 prometheus registry 依赖（`:59`、`:63-64`） | 9 |

## 4. 类的定位

本课没有新业务，全部代码都在「给 Spring Boot 的可观测默认值定形状」。配置面集中在 `application.yml:26-48` 的 management 段。代码面只有两个类：MetricsScrapeTokenFilter 放在 security 包，因为它是 `OncePerRequestFilter`，是模块 5 过滤器链的正式成员，插在 JwtAuthFilter 之前（`SecurityConfig.java:89-92`）；HttpUriTagAllowList 放在 observability 包，是个两方法配置类，只向容器交一个 `MeterFilter` Bean。observability 包总共就这两个类加上 14.2 的 BacklogGauges，是这个项目里最小的包。

与 SecurityConfig 的关系值得画一张链路图：MetricsScrapeTokenFilter 决定「谁能建立 SecurityContext」（授予 ROLE_METRICS），SecurityConfig 的 `requestMatchers` 决定「每种身份能到哪些路径」（`/actuator/prometheus` 只认 ROLE_METRICS，`/actuator/**` 其余只认 ROLE_USER）。两层各管一半，合起来才有完整语义。

## 5. 核心源码详解

### 5.1 management 段逐行：暴露什么、给谁看

`application.yml:26-48` 是默认文档里的配置，所有 profile 生效：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus   # B1: scrape endpoint, token-gated (D-Bw345-42)
  endpoint:
    health:
      show-details: always
      show-components: always
  metrics:
    tags:
      application: linkforge                      # B1: common tag on every meter
    enable:
      http.client.requests: false
    distribution:
      percentiles-histogram:
        http.server.requests: true
      slo:
        http.server.requests: 5ms,10ms,25ms,50ms,100ms,250ms,500ms,1s
```

（逐字摘自 `application.yml:26-48`，注释行有删节，原文见文件。）

四组配置各管一件事：

- **暴露面**（`:30`）：Actuator 默认自带十几个端点（env、beans、threaddump、configprops……），任何一个都可能泄漏配置或内部结构。这里白名单式只开四个。README 的指标参考节也按这个口径写：`/actuator/metrics/**`（JWT）与 `/actuator/prometheus`（抓取令牌）。
- **health 细节**（`:33-34`）：`show-details: always` 让 health 响应携带每个健康组件的细节（数据库连接、Redis ping 结果等）。`show-components: always` 把每个组件的状态也列出来。这是开发与演示口径；prod 文档在 `application.yml:265-266` 覆盖为 `when_authorized`，未认证用户只看到整体 `{"status":"UP"}`，看不到组件明细。
- **公共 tag**（`:37`）：`application=linkforge` 挂到每个 meter 上。多应用共用一个 Prometheus 时，`sum by (application)` 才能不串台。这是一行配置换来所有指标可归属，性价比极高。
- **分布与开关**（`:38-48`）：下文 5.2 与 5.3 展开。

prod 的收紧不只在 health。`application.yml:250` 声明 `on-profile: prod`，整个 prod 文档只有十几行，只做加法覆盖：health 收细、springdoc 关闭、静态控制台关闭。这就是 15.3 要讲的「prod 一键收紧」的一角。

### 5.2 http.server.requests 的直方图与 SLO 桶

`http.server.requests` 是 Boot 自动配置的 Timer，每个 MVC 请求按 `method`、`uri`、`status`、`outcome` 四个 tag 计数并记耗时。Timer 默认只有百分位近似值，不发布桶（bucket）。要在 Prometheus 里算 P99 与 SLO 达标率，必须有累积直方图，所以 `application.yml:44-46` 只给这一个 meter 开 `percentiles-histogram`，`:47-48` 给出 SLO 桶边界 `5ms,10ms,25ms,50ms,100ms,250ms,500ms,1s`。

桶边界就是 Grafana 看板的查询原料。看板里「SLO: redirect ≤ 100ms」一块，查询是 `le="0.1"` 桶的增速除以总增速（`ops/observability/grafana/dashboards/linkforge-slo.json`，14.2 逐面板讲）。桶只在这一个 meter 上开，因为直方图让时间序列数翻十几倍（每个桶一条），全量开是基数自杀。

### 5.3 关闭 http.client.requests 的理由

`application.yml:38-42`：

```yaml
    enable:
      # the only instrumented client in this app is the test harness's RestTemplate
      # (the webhook relay uses a raw java.net.http.HttpClient); client-side meters
      # would record raw request paths of tests into the export — off for good
      http.client.requests: false
```

Boot 会给 Spring 客户端（RestTemplate、RestClient、WebClient）自动装客户端计时器，名字是 `http.client.requests`。这个项目的主代码里没有 Spring 客户端：webhook 投递走 Apache HttpClient 5（`PinnedHttpSender.java:5-8` import 的 `org.apache.hc.client5`），它不是 Boot 的自动插桩对象。唯一会被插桩的 Spring 客户端是测试基座的 `TestRestTemplate`（`AbstractIntegrationTest.java:74`）。开着它，导出里会混进测试流量的原始请求路径；关掉，导出只反映真实服务端流量。注释里的客户端名（java.net.http.HttpClient）与现状（HttpClient5）有一词之差，但不影响决策成立。

### 5.4 MetricsScrapeTokenFilter 逐行：令牌门禁

```java
@Override
protected boolean shouldNotFilter(HttpServletRequest request) {
    // API-key requests fall through so ApiKeyAuthFilter can answer its clean 403
    // (a key against actuator is a 403 by the B4 whitelist, never a 401)
    if (request.getHeader(com.linkforge.security.ApiKeyAuthFilter.API_KEY_HEADER) != null) {
        return true;
    }
    return !request.getRequestURI().equals("/actuator/prometheus");
}
```

（逐字摘自 `MetricsScrapeTokenFilter.java:34-41`。）

`shouldNotFilter` 是 `OncePerRequestFilter` 的快捷通道，返回 true 就跳过本过滤器。两条跳过规则：请求带 `X-API-Key` 头（常量定义在 `ApiKeyAuthFilter.java:44`）时放行，让 API Key 过滤器走它自己的认证路径；路径不是 `/actuator/prometheus` 时放行，本过滤器只管抓取端点，别的 `/actuator/**` 走 JWT。

```java
String configured = properties.getMetrics().getScrapeToken();
String header = request.getHeader(HttpHeaders.AUTHORIZATION);
String provided = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
if (configured == null || configured.isBlank() || provided == null
        || !MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8))) {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType("application/json;charset=UTF-8");
    response.getWriter().write("{\"code\":401,\"message\":\"unauthorized\",\"data\":null}");
    return;
}
```

（逐字摘自 `MetricsScrapeTokenFilter.java:46-55`。）

这段有四个设计点：

1. **fail-closed**：`configured == null || configured.isBlank()` 排在最前。令牌没配置（`LinkForgeProperties.java:56` 默认空串），任何请求都 401。注释（`:23-24`）写明理由：指标泄漏比抓不到指标更糟。对比「fail-open」：没配置就放行，这是另一种选择，被明确否决了。
2. **常量时间比较**：`MessageDigest.isEqual` 按字节逐一比较且不短路（比较耗时与是否相同无关）。`String.equals` 找到第一个不同字节就返回，攻击者可以逐字节探测令牌前缀（计时侧信道）。这里处理的是持久的抓取令牌，值得用防御性写法；普通会话比较不必都这么写。
3. **Bearer 解析**（`:47-48`）：只认 `Authorization: Bearer <token>` 头，与 Prometheus 的 `authorization: type: Bearer` 抓取配置（`ops/observability/prometheus.yml:9-11`）严格对齐。
4. **401 响应体**：`{"code":401,"message":"unauthorized","data":null}`，与全局异常处理器的响应壳一致（模块 2.1），抓取方报错时看到的是熟悉的形状。

认证通过后授予角色并继续链：

```java
var authentication = new UsernamePasswordAuthenticationToken(
        "metrics-scrape", null, List.of(new SimpleGrantedAuthority("ROLE_METRICS")));
SecurityContextHolder.getContext().setAuthentication(authentication);
try {
    filterChain.doFilter(request, response);
} finally {
    SecurityContextHolder.clearContext();
}
```

（逐字摘自 `MetricsScrapeTokenFilter.java:57-64`。）

主体是 `metrics-scrape`、角色 ROLE_METRICS 的 Authentication，放进 SecurityContext 后继续过滤链；finally 清空上下文，请求级状态不外溢（与 JwtAuthFilter 的 finally 清理同款，模块 5.2 讲过）。

### 5.5 SecurityConfig 里的两个半场

过滤器顺序（`SecurityConfig.java:89-92`）：JwtAuthFilter 最前，MetricsScrapeTokenFilter 插在它前面，ApiKeyAuthFilter 在 JWT 之后。注释（`:84-88`）说明顺序原因：抓取令牌必须在 JWT 之前看到原始 Bearer 头，否则 JWT 过滤器会先把 Bearer 当 JWT 解析。三个过滤器都可能建立 SecurityContext，谁先匹配谁生效。

授权规则（`:65-68`）：

```java
.requestMatchers("/actuator/prometheus").hasRole("METRICS")
.requestMatchers("/actuator/**").hasRole("USER")
```

`/actuator/prometheus` 的规则必须写在 `/actuator/**` 之前（`:66` 注释明确要求），因为 `requestMatchers` 按声明顺序首条命中即停。由此得到三种身份的三种结果：

- 抓取令牌 → ROLE_METRICS → 第一条命中 → 200。
- JWT 用户 → ROLE_USER → 第二条命中 → `/actuator/metrics`、`/actuator/health` 可读（这正是 README 说 metrics 走 JWT 的出处），prometheus 也带 ROLE_USER 但第一条不匹配他，仍是 403。
- API Key → ROLE_API_KEY → 两条都不匹配，落到 `:79-83` 的 accessDeniedHandler，返回 JSON 403。

### 5.6 API Key 摸 actuator 的 403 路径

把 401 与 403 的分界走一遍。API Key 请求 `/actuator/prometheus` 时：

1. MetricsScrapeTokenFilter 的 `shouldNotFilter` 看到带 `X-API-Key` 头，直接放行（`MetricsScrapeTokenFilter.java:37-39`），不产生 401。
2. JwtAuthFilter 无 JWT 可解析，跳过。
3. ApiKeyAuthFilter 认证成功，授予 ROLE_API_KEY。
4. 授权阶段，`/actuator/prometheus` 要求 METRICS、`/actuator/**` 要求 USER，ROLE_API_KEY 都不满足，403（`SecurityConfig.java:79-83`）。

这是「能力白名单」语义：API Key 是被授予了明确能力清单的凭证，actuator 不在清单里，所以是 403（无此能力）而不是 401（没认出你是谁）。`PrometheusScrapeIT.apiKeysStayForbidden`（`PrometheusScrapeIT.java:75-85`）把这条路径钉死：注册用户创建 API Key，带 `X-API-Key` 头请求抓取端点，断言 403。

### 5.7 HttpUriTagAllowList：uri tag 的有界设计

```java
private static final Set<String> ALLOWED = Set.of(
        "/r/{code}", "/r/{code}/unlock",
        "/api/links", "/api/links/{code}", "/api/links/{code}/stats",
        "/api/links/{code}/stats/breakdown", "/api/links/{code}/clicks",
        "/api/links/export",
        // C1 wave B: qr.png and qr-share must not collapse into UNKNOWN (javadoc above)
        "/api/links/{code}/qr.png", "/api/links/{code}/qr-share",
        "/api/auth/login", "/api/auth/register",
        "/api/keys", "/api/keys/{id}",
        "/api/webhooks/endpoint", "/api/webhooks/endpoint/{id}",
        "/api/health",
        "/actuator/health", "/actuator/prometheus", "/actuator/metrics", "/actuator/info");
```

（逐字摘自 `HttpUriTagAllowList.java:22-33`。）

问题从哪来：`http.server.requests` 的 `uri` tag 正常取 MVC 匹配到的模板（`/r/{code}`）。但有些请求根本没走到 MVC 匹配，比如容器层拒绝、连接中途断开，这类请求记录的 `uri` 是原始路径。跳转端点的原始路径长什么样？`/r/XyZ1234`。每创建一条短链，导出里就多一条时间序列，这正是 5.1 说的基数爆炸，而且 `uri` 里出现的真实短码还构成信息泄漏（外部不可见但可猜测的码值进了监控数据）。

解法是一个 `MeterFilter` Bean：

```java
@Bean
MeterFilter httpUriTagFilter() {
    return new MeterFilter() {
        @Override
        public Meter.Id map(Meter.Id id) {
            if (!"http.server.requests".equals(id.getName())) {
                return id;
            }
            String uri = id.getTag("uri");
            if (uri == null || ALLOWED.contains(uri)) {
                return id;
            }
            return id.withTag(Tag.of("uri", "UNKNOWN"));
        }
    };
}
```

（逐字摘自 `HttpUriTagAllowList.java:36-50`。）

只拦 `http.server.requests`（`:40`）；tag 缺失或命中白名单则原样放行；否则把 `uri` 改写成 UNKNOWN。类注释（`:12-18`）点出失败方向的安全设计：新端点忘记加白名单，它的指标不会丢，只是静默并入 UNKNOWN 序列。指标缺失会触发告警查找，UNKNOWN 突增同样可观测，两条路都是「错误可见」，比「错误即崩溃」或「错误即泄漏」都好。

## 6. 技术原理补充

- **Actuator（框架行为）**：管理端点框架，端点即 Spring Bean，`include` 白名单决定暴露面。不用它会怎样：进程内外都只能靠日志与猜测判断应用状态。本项目怎么用：四端点白名单加双层鉴权。缺点：默认暴露过宽，配置不当就泄漏，所以白名单 + prod 收紧是标配。
- **Micrometer（框架行为）**：指标门面，`MeterRegistry` 是唯一入口，`MeterFilter` 是注册时的拦截器。不用它会怎样：业务代码直接依赖 Prometheus client，换监控系统要动所有打点。本项目怎么用：业务侧全程 `meterRegistry.counter(...)`，监控差异全部沉淀在两个配置类与一段 yml。
- **基数控制（通用工程）**：tag 取值必须有限集。不用会怎样：一条用户级 tag 让序列数随用户数线性增长，Prometheus 内存与查询延迟双爆。本项目怎么用：uri 白名单折叠 UNKNOWN；全项目指标不用任何 code、URL、IP、用户、Key id 做 tag（README「tag 有界」口径，`PrometheusScrapeIT.tagsStayBounded` 在 `:107-120` 用导出全文不含短码来断言）。
- **fail-closed 与常量时间比较（安全工程）**：门禁类组件在「配置缺失」时默认拒绝，在「比较秘密」时不短路。两者都是让失败方向保守。

## 7. 可视化

- [01-runtime-deployment.html](../../diagrams/archify/html/01-runtime-deployment.html)：宿主 JVM 与 Compose 的部署图。看图顺序：宿主 JVM 里的应用进程 → 右侧 observability profile 的 Prometheus 与 Grafana 容器 → Prometheus 每 15 秒向 `:8080/actuator/prometheus` 发起带 Bearer 的抓取。图中「Prometheus」「Grafana」两个节点对应本课的 `ops/observability/` 配置，应用进程内的 Actuator/Micrometer 对应本课全部类。
- [00-system-architecture.html](../../diagrams/archify/html/00-system-architecture.html)：系统总架构中 observability 一域，对应 observability 包与 management 配置；其余域（限流、缓存、MQ）是 14.2 指标表的打点来源。

## 8. 项目中的真实执行过程

一次完整的抓取，从 Prometheus 到文本响应：

1. Prometheus 每 15 秒触发抓取 job `linkforge`，目标是 `host.docker.internal:8080`，路径 `/actuator/prometheus`，带 `Authorization: Bearer <token文件内容>`（`ops/observability/prometheus.yml:1-13`；token 文件挂载自 `ops/observability/scrape-token`，值等于环境变量 `LINKFORGE_METRICS_SCRAPE_TOKEN`）。
2. 请求进过滤器链。MetricsScrapeTokenFilter 的 `shouldNotFilter` 判定路径匹配且无 API Key 头，进入 `doFilterInternal`。
3. 令牌比对通过（`MetricsScrapeTokenFilter.java:50-51`），ROLE_METRICS 写入 SecurityContext（`:57-59`）。
4. 授权阶段命中 `:65-68` 的第一条规则，放行。
5. Prometheus 导出器把注册表渲染成文本格式：每个指标一行，形如 `http_server_requests_seconds_bucket{application="linkforge",uri="/r/{code}",le="0.1",...} 42`。公共 tag `application="linkforge"` 由 5.1 的 `:37` 配置注入。
6. finally 清空 SecurityContext（`:60-64`），连接关闭，Prometheus 存样本。

反例各一条：无 Bearer 头或令牌错误，第 3 步 401 JSON；带 `X-API-Key` 头，第 2 步放行、第 4 步 403 JSON。`PrometheusScrapeIT.scrapeIsTokenGated`（`:65-73`）把三种情况全部断言。

## 9. 为什么这样设计

- **为什么专用令牌而不是复用 JWT**：抓取方是 Prometheus 不是人，给它一个静态长效、可单独吊销（换文件重启）的令牌最省事；JWT 会过期，抓取半夜失败没人处理。这是推断，依据是配置名 `metrics.scrapeToken` 与 README「抓取令牌」措辞。
- **为什么 fail-closed**：类注释（`MetricsScrapeTokenFilter.java:23-24`）给了原话「a metrics leak is worse than a missing scrape」。指标里有路径形状、错误率、积压量，对攻击者是地图；抓不到指标是可用性问题，有告警兜底。
- **为什么 health 的细节在默认 profile 全开**：demo 与开发场景是主要受众，组件明细（哪个依赖 DOWN）是最常用的排障信息。prod 档 `when_authorized` 收紧，README 的公网部署小节明确写了这层语义。
- **为什么 uri 白名单是折叠而不是丢弃**：类注释（`HttpUriTagAllowList.java:17`）「the safe direction」。丢弃会让新端点在监控里隐身，折叠保留总量、只损维度，且 UNKNOWN 的异常增长本身就是信号。
- **决策编号**：本课四个组件全部出自 B1 波（端点注释 `D-Bw345-42`、白名单 `D-Bw345-45`），决策背景在 `docs/progress.md` 的 B1 章。

## 10. 本节总结

- 暴露面：health、info、metrics、prometheus 四端点（`application.yml:30`），prometheus 是唯一机器抓取端点。
- 三种身份三种结果：抓取令牌 200、JWT 用户可读 metrics 与 health、API Key 一律 403。
- fail-closed：令牌未配置，端点拒绝所有人（`MetricsScrapeTokenFilter.java:49-55`）；比对用常量时间。
- 数据形状：公共 tag `application=linkforge`；`http.server.requests` 独享直方图与 SLO 桶；`uri` 白名单外折叠 UNKNOWN；客户端计时器关闭。
- 日志侧的对应物（S/N 波 N1）：每行日志带请求 cid（`pattern.level` 注入，2.2 课），指标回答「多少、多快」，cid 回答「这一次是谁」——两者在排障时交叉使用：先看指标定位时间窗，再拿客户端报来的 cid（在响应头里）捞出那一栈。
- 测试兜底：`PrometheusScrapeIT` 钉住 401/403/200 三态、tag 有界与直方图存在性。

## 11. 课后练习

**1.（概念理解）** 把 `MetricsScrapeTokenFilter` 的 fail-closed 判断改成 fail-open（令牌未配置就放行），会出现什么具体风险？再说出一种你见过的 fail-closed 配置。

**答案**：未配置令牌时任何匿名请求都能拉走完整指标导出：`http.server.requests` 泄露端点形状与错误率，业务 counter 泄露流量画像，攻击者据此挑软肋。fail-closed 的常见例子还有：JWT 密钥缺失时启动失败而不是全部放行（本项目 `LinkForgeProperties` 的 jwt secret 是必填无默认）。

**2.（源码定位）** 在 `MetricsScrapeTokenFilter.java` 中找到常量时间比较的那一行，回答：换成 `String.equals(configured, provided)`，攻击面具体在哪里？

**答案**：`:50-51` 的 `MessageDigest.isEqual`。`String.equals` 首个不同字节即返回，网络往返时间会随「正确前缀长度」变长，攻击者可逐字节探测令牌（计时侧信道）。`MessageDigest.isEqual` 不短路，耗时与差异位置无关。

**3.（执行过程）** API Key 请求 `/actuator/health`，写出四个步骤与最终状态码，并指出每步对应的源码行。

**答案**：① MetricsScrapeTokenFilter.shouldNotFilter 见 `X-API-Key` 头，放行（`MetricsScrapeTokenFilter.java:37-39`）；② ApiKeyAuthFilter 认证成功授 ROLE_API_KEY（模块 12）；③ 授权规则 `/actuator/health` 无独立条目，命中 `/actuator/**` 的 `hasRole("USER")`（`SecurityConfig.java:68`），ROLE_API_KEY 不满足；④ accessDeniedHandler 返回 JSON 403（`SecurityConfig.java:79-83`）。

**4.（调用链）** 新增控制器 `GET /api/reports` 后，忘记把它加进 `HttpUriTagAllowList.ALLOWED`，Prometheus 里的表现是什么？如何发现？

**答案**：正常流量仍计入 `http_server_requests_seconds_count`，但 `uri` 全部落进 `uri="UNKNOWN"` 序列。发现方式：UNKNOWN 序列的增速异常，或新端点的独立序列始终不存在。修复是把 `"/api/reports"` 加进白名单（`HttpUriTagAllowList.java:22-33`），重启后新序列出现，旧 UNKNOWN 序列随 Prometheus 时间窗淡出。

**5.（设计分析）** `application.yml:44-48` 为什么只给 `http.server.requests` 开直方图？如果把 SLO 桶同样配给 `linkforge.redirects` 这类业务 counter，会怎样？

**答案**：直方图把一个 meter 的序列数乘以桶数（8 个 SLO 桶加默认桶，十几倍）。业务 counter 本身不带耗时语义，开桶无查询收益，纯付存储与内存。`linkforge.redirects` 是 Counter 不是 Timer，`distribution.slo` 对它本就不生效；题眼在「分布配置要跟着 Timer 走，且只开给要看分位数的那个」。

## 12. 与后续课程的联系

下一课 14.2 把本课的导出变成图：业务指标全表按域逐个定位打点行，BacklogGauges 的采样解耦回答「为什么不在抓取线程里查库」，Grafana 的 SLO 看板直接消费 5.2 的桶配置。本课的令牌配置在 15.3 会再出现：prod profile 与 compose 部署时 `LINKFORGE_METRICS_SCRAPE_TOKEN` 与 `ops/observability/scrape-token` 文件的一致性是部署检查项。过滤器链的完整顺序见 05.1。
