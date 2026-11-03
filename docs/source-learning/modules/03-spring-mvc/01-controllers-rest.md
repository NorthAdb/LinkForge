# 3.1 Controller 与 REST 设计

## 1. 本节学习目标

1. 盘点项目全部 8 个 Controller，说出每个类的路由前缀、端点清单和依赖的 Service。
2. 掌握五类参数绑定：`@PathVariable`、`@RequestParam`、`@RequestHeader`、`@RequestBody`、`MultipartFile`。
3. 背下项目的状态码约定：404/409/410/429/503 各在什么场景抛出、源码在哪一行。
4. 理解 Jackson 在本项目的三个实践点：未知属性捕获、LocalDateTime 序列化、record DTO。
5. 知道 Swagger/OpenAPI 文档怎么开、怎么关、为什么 prod 必须关。

## 2. 前置知识

- [2.1 统一响应与全局异常](../02-common/01-unified-response-exceptions.md)：`ApiResponse` 壳与 `GlobalExceptionHandler` 映射表。本节所有 Controller 的返回体都套这个壳。
- [1.2 启动类、IoC 与依赖注入](../01-spring-boot/02-bootstrap-ioc-di.md)：`@RestController` 是被扫描的 Bean，构造器注入由 Lombok `@RequiredArgsConstructor` 生成。
- HTTP 方法语义（框架无关常识）：GET 读、POST 建、PATCH 改、DELETE 删、PUT 幂等覆盖。
- （Java 语言知识）`record`：Java 16+ 的不可变数据载体，自动生成构造器与访问器，本项目的 DTO 大量使用。

## 3. 本节涉及的源码

| 文件 | 职责 | 阅读顺序 | 为什么读它 |
|------|------|----------|------------|
| `link/controller/ShortLinkController.java`（128 行） | 短链 CRUD+CSV+QR 入口 | 第 1 | 端点最多、绑定方式最全 |
| `link/controller/RedirectController.java`（123 行） | `/r/{code}` 跳转入口 | 第 2 | 非 JSON 响应的代表 |
| `link/controller/HealthController.java`（15 行） | 探活端点 | 第 3 | 最小 Controller 样板 |
| `link/controller/BloomAdminController.java`（49 行） | Bloom 重建触发 | 第 4 | Service 层做二次授权的样板 |
| `stats/controller/StatsController.java`（66 行） | 统计三端点 | 第 5 | 枚举白名单校验 |
| `user/controller/AuthController.java`（33 行） | 注册/登录 | 第 6 | `@Valid` + `HttpServletRequest` |
| `apikey/controller/ApiKeyController.java`（54 行） | API Key 管理 | 第 7 | DTO 嵌套类组织法 |
| `webhook/controller/WebhookController.java`（58 行） | Webhook 配置 | 第 8 | PUT upsert 语义 |
| `link/dto/CreateShortLinkRequest.java`（78 行） | 创建入参 | 第 9 | Jackson 未知属性捕获 |
| `config/OpenApiConfig.java`（41 行） | OpenAPI 文档信息 | 第 10 | springdoc 装配 |

路径缩写：`link/` = `src/main/java/com/linkforge/link/`，下同。本节覆盖 8 个 Controller 的全部 26 个端点。

## 4. 类的定位

Controller 是项目的「门面层」：HTTP 进来先到 Filter 链做认证（3.2 课），再到 Controller 做参数绑定，然后立刻下沉到 Service。Controller 自己不写业务，最长的 ShortLinkController 每个方法也只有 1-4 行。位置见[包结构图](../../diagrams/archify/html/03-package-map.html)：controller 子包挂在各业务包下，方法级入口的分布见[功能地图](../../diagrams/archify/html/02-function-map.html)的 HTTP 入口层。

## 5. 核心源码详解

### 5.1 八个 Controller 全景表

| Controller | 路由前缀 | 端点数 | 注入的 Service | 行数 |
|------------|----------|--------|----------------|------|
| ShortLinkController | `/api/links` | 11 | ShortLinkService, QrMaterialService | 128 |
| RedirectController | `/r/{code}` | 3 | ShortLinkService, LinkAccessTokens | 123 |
| HealthController | `/api/health` | 1 | 无 | 15 |
| BloomAdminController | `/api/admin/bloom` | 1 | UserAccountMapper, CodeBloomFilter | 49 |
| StatsController | `/api/links/{code}/stats*` | 3 | ShortLinkService, ClickStatsService | 66 |
| AuthController | `/api/auth` | 2 | AuthService | 33 |
| ApiKeyController | `/api/keys` | 3 | ApiKeyService | 54 |
| WebhookController | `/api/webhooks` | 4 | WebhookService | 58 |

（本项目实现）两个反常点提前说。第一，StatsController 的路由挂在 `/api/links` 下却住在 `stats` 包：资源归属压过代码包归属，URL 是给客户端的，包是给维护者的。第二，BloomAdminController 直接注入 Mapper（BloomAdminController.java:27-28）而不是 Service：重建只是一个只读统计加一次内存重建，为它包一层 Service 不划算（推断：作者按复杂度决定层级）。

### 5.2 ShortLinkController：绑定方式全家桶（ShortLinkController.java:35-97）

```java
@RestController
@RequestMapping("/api/links")
@RequiredArgsConstructor
public class ShortLinkController {

    private final ShortLinkService shortLinkService;
    private final QrMaterialService qrMaterialService;

    @PostMapping
    public ApiResponse<CreateShortLinkResponse> create(@Valid @RequestBody CreateShortLinkRequest request,
                                                       @RequestHeader(value = "Idempotency-Key", required = false)
                                                       String idempotencyKey) {
        return ApiResponse.ok(shortLinkService.create(request, idempotencyKey));
    }
```

（ShortLinkController.java:35-48）

一个方法集齐三种绑定。（框架行为）`@RequestBody` 把请求体 JSON 反序列化成对象；`@Valid` 让校验注解在方法体执行前生效；`@RequestHeader(required = false)` 取可选的请求头，缺失时参数为 null 而不是报错。方法不写任何状态码，成功默认 200。

列表端点展示 `@RequestParam` 的默认值写法（:51-52）：

```java
    public ApiResponse<PageResponse<ShortLinkDetail>> list(@RequestParam(defaultValue = "1") int page,
                                                           @RequestParam(defaultValue = "20") int size) {
```

（框架行为）`defaultValue` 兼任「可选」与「类型转换」：查询串里没有 page 时自动填 1，字符串 "3" 自动转 int。非法字符串（如 `?page=abc`）会抛 `MethodArgumentTypeMismatchException`，它不在 `GlobalExceptionHandler` 的专门分支里，会落进兜底变 500（（本项目实现）现状如此，见 2.1 课的兜底分支分析）。

CSV 导入展示文件上传绑定（:65-68）：

```java
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ImportResponse> importCsv(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(shortLinkService.importCsv(file));
    }
```

（框架行为）`consumes` 限定请求的 Content-Type 必须是 multipart/form-data，否则 415；`MultipartFile` 由 multipart 解析器注入。256KiB 上限的配置与超限 400 分支见 3.2 课。

导出端点是少数返回裸 `ResponseEntity<String>` 的地方（:56-61）：`produces = "text/csv;charset=UTF-8"` 声明响应类型，`Content-Disposition` 头让浏览器下载而不是展示。这与 ApiResponse 壳无关，文件不是 JSON API。

### 5.3 RedirectController：返回 HTML 的 Controller（RedirectController.java:36-48, 85-117）

```java
    @GetMapping("/r/{code}")
    public ResponseEntity<String> redirect(
            @Parameter(description = "short code, case-sensitive (exact match)")
            @PathVariable String code, HttpServletRequest request) {
        return render(shortLinkService.resolveForGet(code, request), code, true);
    }
```

（RedirectController.java:36-41）

两个新东西。`@PathVariable` 把 URL 模板里的 `{code}` 抠出来做参数。`HttpServletRequest` 整个注入，让 Service 层自己读 header 与 cookie（跳转链路要 User-Agent、Referer、解锁 cookie）。

render 方法把一个 sealed interface 翻译成四种响应（:85-116）：`Redirect` → 302 + Location；`Blocked` → 403 + HTML；`Locked` → GET 200 / HEAD 401；`Warn` → 200 告警页。每个 HTML 分支都带 `Cache-Control: no-store`、`Content-Security-Policy`、`X-Content-Type-Options` 三个响应头（:93-95），来源见 3.2 课的安全响应头一节。方法级注释（:84）写明 HEAD 契约：状态码全同，body 恒空。

（框架行为）`@RequestMapping(value = "/r/{code}", method = RequestMethod.HEAD)`（:43-46）单独声明 HEAD 方法。没有它，HEAD 会落到 GET 方法上并尝试写 body（Servlet 容器会丢弃 body 但行为不可控）。

### 5.4 状态码约定：五种非 200 从哪来

| 状态码 | 场景 | 源码 |
|--------|------|------|
| 400 | 校验失败、非法输入、坏 JSON | GlobalExceptionHandler.java:33-49 各分支 |
| 401 | 未认证（三个来源：entryPoint、坏 JWT、坏 Key） | SecurityConfig.java:72-76, JwtAuthFilter.java:51-54, ApiKeyAuthFilter.java:82 |
| 403 | 已认证但无角色 / 非 ADMIN | SecurityConfig.java:79-83, BloomAdminController.java:35-36 |
| 404 | 短链不存在（含越权伪装） | ShortLinkService.java:884-901 |
| 409 | 自定义码撞车、租户名重复 | ShortLinkService.java:244-246, AuthService.java:80-84 |
| 410 | 短链过期、目标 URL 不可渲染 | ShortLinkService.java:659-661, 665-670 |
| 429 | 限流或配额耗尽（带 Retry-After） | RateLimitedException.java:14-17 |
| 503 | 幂等创建未落定、webhook 功能不可用 | ShortLinkService.java:171-173, WebhookService.java:195-199 |

重点看 404 的语义（ShortLinkService.java:890-903）：

```java
    ShortLink getOwnedLink(String code) {
        AuthUser user = requireUser();
        ShortLink link = shortLinkMapper.selectOne(new LambdaQueryWrapper<ShortLink>().eq(ShortLink::getCode, code));
        if (isTenantWide(user)) {
            if (link == null || !Objects.equals(link.getTenantId(), user.getTenantId())) {
                throw new BizException(404, "short link not found");
            }
            return link;
        }
        if (link == null || link.getCreatedBy() == null || !link.getCreatedBy().equals(user.getUserId())) {
            throw new BizException(404, "short link not found");
        }
        return link;
    }
```

（ShortLinkService.java:890-903）

「别人的短链」和「不存在的短链」返回同一个 404、同一句话。这是安全惯例：403 会告诉攻击者这个码存在，404 什么都不泄露。（本项目实现）归属判定分两档：TENANT scope 的 API Key 按租户匹配（:893-897），其余按创建人匹配（:899-901）。

503 是本项目「服务端暂时不可用」的信号（ShortLinkService.java:171-173）：

```java
            // The winner has not settled its code; forcing a second create could mint
            // two links under one Idempotency-Key, so the caller retries instead.
            throw new BizException(503, "idempotent create still in progress, retry later");
```

创建者还在等自己的事务提交，第二个携带同一幂等键的请求等了 2 秒（10 次 × 200ms）仍未等到结果，返回 503 让客户端稍后重试，而不是冒险再建一条。

### 5.5 Jackson 序列化的三个实践点

**未知属性捕获**（CreateShortLinkRequest.java:64-77）：

```java
    @JsonIgnore
    private final Map<String, Object> unknown = new LinkedHashMap<>();

    @JsonIgnore
    public Map<String, Object> getUnknown() {
        return unknown;
    }

    @JsonAnySetter
    void captureUnknown(String name, Object value) {
        unknown.put(name, value);
    }
```

（本项目实现，注释 D-C1-8）（框架行为）默认行为是反序列化遇到未知字段直接抛异常。`@JsonAnySetter` 把每个未知字段塞进 `unknown` Map，Service 层再决定哪些前缀要拒绝（`utm*` 与归因字段 400，其余静默忽略，见 ShortLinkService 的 rejectSmuggledAttribution :140 与 create 流程）。这样客户端多传字段不会炸接口，但也不能用多传字段夹带统计归因。

**LocalDateTime 序列化**（application.yml:82-85）：`time-zone: Asia/Shanghai` 加 `write-dates-as-timestamps: false`。效果是 `expireAt` 输出 ISO 文本（`2026-01-01T00:00:00`）而不是数字时间戳，时区固定东八。（框架行为）这个配置对全项目唯一共享的 `ObjectMapper` 生效；`stats/mq` 的 MQ 消息转换器特意复用它，防止 LocalDateTime 进 DLQ（RabbitMqConfig 注释，模块 10 展开）。

**record 当 DTO**（ImportResponse.java:10-17）：

```java
public record ImportResponse(int successCount, int failureCount,
                             List<RowSuccess> successes, List<RowFailure> failures) {

    public record RowSuccess(int row, String code) {
    }

    public record RowFailure(int row, String reason) {
    }
}
```

（Java 语言知识）record 不可变、无 setter，天然适合「只出不易」的响应体。CSV 导入的逐行成败报告用它正合适。需要校验注解的字段仍用 `@Data` 类（可变性无妨），两种风格并存。

### 5.6 Swagger / OpenAPI

依赖 springdoc 2.6.0（pom.xml:67-71）。装配在 OpenApiConfig.java:18-40：

```java
    @Bean
    public OpenAPI linkForgeOpenApi(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();
        return new OpenAPI()
                .info(new Info()
                        .title("LinkForge")
                        .version(build != null ? build.getVersion() : "dev")
                        ...
                .components(new io.swagger.v3.oas.models.Components()
                        .addSecuritySchemes("bearerAuth", ...)
                        .addSecuritySchemes("apiKeyAuth", ...));
    }
```

（OpenApiConfig.java:18-40，中间省略的是标题与描述文本）

三点值得学。版本号来自 Maven 的 build-info（1.1 课），IDE 直跑没有这个文件时回退 "dev"，用 `ObjectProvider` 而不是直接注入表达了「可缺席」。（框架行为）两个 SecurityScheme 注册后，swagger-ui 页面会出现 Authorize 按钮，支持 Bearer JWT 与 X-API-Key 两套认证调试。方法级的文档用 `@Parameter`（RedirectController.java:38），目前全项目只有跳转接口用了它，其余接口靠字段名与校验 message 自解释。

开关分三层（全关才算真关）：`springdoc.api-docs.enabled=false` 与 `swagger-ui.enabled=false`（application.yml:255-259）关掉端点本体；`linkforge.docs-enabled=false`（:260-261）让 SecurityConfig 把文档路径与控制台路径 `denyAll`（SecurityConfig.java:47-55）；dev profile 两者全开。（推断）三层重叠是防御纵深：只关 springdoc 时 `/` 上的控制台静态页仍可能被摸到。

## 6. 技术原理补充

**@RestController 与 @Controller**（框架行为）。`@RestController` = `@Controller` + `@ResponseBody`：返回值直接写进响应体（经 Jackson 转 JSON），不再走视图解析。这就是为什么返回 `ApiResponse` 对象就得到 JSON，返回 `ResponseEntity<String>` 就得到原始字符串。视图渲染不是不需要，是被 GatePages 这类手拼 HTML 替代了（本项目没有模板引擎）。

**@RequestMapping 的层级组合**（框架行为）。类上的 `@RequestMapping("/api/links")` 与方法上的 `@PostMapping` 拼接出最终路径。StatsController 没有类级前缀，三个方法各自写全路径（StatsController.java:33, 45, 57），因为它们的前缀各不相同。两种写法等价，端点少时平铺反而直观。

**consumes / produces 的匹配时机**（框架行为）。`consumes` 按请求的 Content-Type 筛方法，`produces` 按客户端的 Accept 筛。不匹配时报 415/406，方法体不执行。`/export` 用 produces 锁死 CSV 类型，防止内容协商把同一 URL 协商成 JSON。

**springdoc 如何生成文档**（框架行为）。启动时 springdoc 扫描全部 Controller 的注解与 DTO 字段，生成 `/v3/api-docs` JSON，swagger-ui 读它渲染交互页。校验注解（`@NotBlank` 等）会转成字段约束说明，所以写好 message 等于写好一半文档。

## 7. 可视化

- [功能地图](../../diagrams/archify/html/02-function-map.html)：37 条能力叶的 HTTP 入口按 @Mapping 实测，可与 5.1 全景表互查。
- [包结构图](../../diagrams/archify/html/03-package-map.html)：8 个 controller 类分布在 5 个业务包里。
- [系统架构图](../../diagrams/archify/html/00-system-architecture.html)（由并行图表任务产出）：Controller 层在架构中的位置。

阅读顺序：功能地图选一个能力，顺着 HTTP 入口找到 5.1 表中的 Controller，再用包结构图确认它属于哪个包。

## 8. 项目中的真实执行过程

走一遍 `POST /api/links`（自定义码撞车，最终 409）。

1. 请求先过 Filter 链：JWT 合法，拿到 ROLE_USER（3.2 课详述）。
2. DispatcherServlet 按 `POST + /api/links` 匹配到 `create` 方法。
3. Jackson 把 body 反序列化成 CreateShortLinkRequest，`@Valid` 校验通过。
4. Controller 一行下沉（ShortLinkController.java:47）：`shortLinkService.create(request, idempotencyKey)`。
5. create 里限流通过、幂等键处理完毕，进入 doCreate 的事务段（ShortLinkService.java:237-246）：自定义码插入撞上 `uk_code` 唯一索引，抛 DuplicateKeyException，被翻译成 `BizException(409, "code already exists")`。
6. 异常冒出 Controller，`GlobalExceptionHandler.handleBiz`（GlobalExceptionHandler.java:27-31）把 409 写回 HTTP 状态码，body 为 `{"code":409,"message":"code already exists","data":null}`。

Controller 在第 4 步之后就退场了。它绑定参数、声明路由、交给 Service，剩下的错误翻译由 2.1 课的异常层接管。这个分工是读懂任何一条新链路的入口：先看 Controller 签名，就知道一次调用需要什么、会返回什么。

## 9. 为什么这样设计

- 端点用动作子资源（`/disable`、`/enable`、`/rotate-secret`、`/rebuild`）而不是 PUT 状态字段。禁用一个短链是状态机事件，不是字段修改，动词 URL 在这里比教条 RESTful 诚实（推断：从命名一致性反推）。
- Webhook 的 `PUT /endpoint` 是 upsert（WebhookController.java:30-33）。每租户只允许一个 endpoint（V6 迁移的 `uk_webhook_tenant`），PUT 语义与「单例资源、整体覆盖」吻合。
- 管理面二次授权放在 Service 而不是只靠 URL 角色（BloomAdminController.java:32-36）。SecurityConfig 的 `anyRequest().hasRole("USER")` 只能挡住非 USER，挡不住「USER 里的非 ADMIN」，ADMIN 判定必须查库，所以下沉。
- 大部分错误处理集中在 GlobalExceptionHandler，Filter 是唯一例外。为什么 Filter 做不到，是下一课的主题。

## 10. 本节总结

- 8 个 Controller、26 个端点；门面层薄到每方法 1-4 行，业务全在 Service。
- 绑定五件套：`@PathVariable`、`@RequestParam(defaultValue)`、`@RequestHeader`、`@RequestBody + @Valid`、`MultipartFile`。
- 状态码约定：400 输入、401 认证、403 授权、404 存在性与归属伪装、409 冲突、410 过期/坏目标、429 限流、503 暂不可用。
- Jackson：`@JsonAnySetter` 捕获未知属性交给 Service 裁决；日期固定东八 ISO 文本；只读响应体用 record。
- OpenAPI 三层开关，prod 全关；版本号来自 build-info。

## 11. 课后练习

**练习 1（概念理解）**：`GET /api/links?page=abc` 会得到什么状态码？为什么这个错误没有被专门分支接住？

答案解析：500。Spring 抛 `MethodArgumentTypeMismatchException`，它不是 `GlobalExceptionHandler` 七个分支中任何一个的类型（GlobalExceptionHandler.java:20-70），落入 Exception 兜底（:72-77）变 500 并打全栈。（推断）补法与缺参分支同理：加一个 `@ExceptionHandler(MethodArgumentTypeMismatchException.class)` 返回 400。

**练习 2（源码定位）**：把 ShortLinkController 的 11 个端点按 HTTP 方法分类列出，并指出哪一个用了 `produces`。

答案解析：POST 4 个（创建 :43、导入 :65、disable :81、enable :87）、GET 4 个（列表 :50、导出 :56、详情 :70、qr.png :100）、PATCH 1 个（:75）、DELETE 1 个（:93）、再加 qr-share POST :116。用 produces 的是导出（:56），`text/csv;charset=UTF-8`。

**练习 3（执行过程）**：用户 A 调用 `GET /api/links/{code}`，code 存在但属于用户 B。响应是什么？若把 404 改成 403 会有什么信息泄露？

答案解析：`getOwnedLink`（ShortLinkService.java:899-901）发现创建人不是自己，抛 404 "short link not found"。若改 403，攻击者枚举短码时能区分「存在但别人的」与「不存在」，等于免费获得码位探测；404 把两种情况合并，探测不可行。

**练习 4（调用链）**：`POST /api/webhooks/endpoint` 用 PUT upsert，而创建短链用 POST。为什么 webhook 不怕「重复创建两条」？

答案解析：数据库层面每租户只有一个 endpoint（V6 迁移 `uk_webhook_tenant` 唯一索引，V6__webhook.sql:15），upsert 的写法（WebhookService.upsert）按 tenantId 找到就更新、找不到才插入。资源是单例时 PUT 覆盖语义天然防重；POST 的「每次新建」只适合多实例资源。

**练习 5（设计分析）**：CreateShortLinkRequest 用 `@JsonAnySetter` 收下未知字段，Service 再对 `utm*` 前缀 400。若改成直接在 Jackson 层抛异常（fail on unknown），客户端体验会差在哪？

答案解析：全拒。合法新字段（如未来加的备注）会让旧客户端炸 400，且 utm 夹带与普通笔误无法区别处理。现状是「白名单裁决」：Service 只拒绝会污染统计归因的键（D-C1-8 注释），其余静默忽略，接口对演化友好、对归因作弊严格。

## 12. 与后续课程的联系

下一课 [3.2 请求生命周期与 Filter](02-request-lifecycle-filters.md) 把镜头拉远：请求在到达这些 Controller 之前要经过什么，Filter 里抛的异常为什么永远到不了 GlobalExceptionHandler。模块 04 起进入 Service 背后的持久层，本节出现过的 `LambdaQueryWrapper`（ShortLinkService.java:892）会在 [4.1 实体与 Mapper](../04-mybatis-mysql/01-entities-mappers-crud.md) 里完整拆解。StatsController 的三个端点在模块 11 会重新出现，那时你已经认识它背后的 lf_click_daily 与 lf_click_dim_daily 两张表。
