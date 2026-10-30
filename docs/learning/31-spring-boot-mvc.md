# Spring Boot、Spring MVC 与 REST API 设计

优先级 P0，目标层级 L2（能独立完成一个规范的业务接口），请求处理流程与状态码语义到 L3。
前置：[30-spring-core.md](30-spring-core.md)（容器、依赖注入；统一响应与异常处理器在那一章已讲过机制，本章只讲位置关系）。HTTP 报文、302 与幂等的网络层定义见 [20-network.md](20-network.md)。反射基础见 [java/10-07-annotations-reflection-proxy.md](java/10-07-annotations-reflection-proxy.md)，自动配置与参数调用都靠它。

## 学习目标

读完本章，你应该能做到三件事：

1. 从浏览器敲下一个 URL 讲起，把请求在内嵌 Tomcat、过滤器链、`DispatcherServlet`、参数解析、Controller、JSON 序列化之间的每一站说清楚。接口行为异常时，能判断"卡在哪一站"。
2. 解释 Spring Boot 的自动配置怎么做到"加一个依赖就有功能"，以及为什么你自己的 Bean 能覆盖 Boot 的默认装配。
3. 按 REST 语义设计一个新接口：选方法、起路径、定状态码、加校验、决定要不要幂等键。

## 先说人话

Spring Boot 做的事一句话：**它没有发明新的编程模型，底层还是 Spring MVC 那套 Servlet；它把"装配与部署"包办了**。第一遍带走三句：

1. 一次请求的旅程：过滤器认人 → `DispatcherServlet` 按路径找到你的方法 → 解析参数并校验 → Controller 调 Service → 返回值序列化成 JSON 写回。**出任何问题，先问"卡在旅程的哪一站"**。
2. Boot 的"自动"不是魔法，是两步：读各 jar 里的候选清单，再用条件注解逐个筛选。你的配置永远优先，机制是"自动配置被刻意拖到最后才评估"。
3. 状态码与幂等是设计工具，不是事后填空。404 与 410 分别表达"暂时不在"与"永久没了"；POST 不幂等，重试可能双花，幂等键是补救手段，LinkForge 真的用了它。

## 没有 Boot 的年代：一个接口要背多少配置

先看痛点。2015 年前用 Spring MVC 写一个返回 JSON 的接口，你要做的事（历史事实，带过）：

1. 写一个 `web.xml`，在里面手工注册 `DispatcherServlet` 并把它映射到 `/`。`DispatcherServlet` 是 Spring MVC 的前端控制器：所有请求先到它，它再分发。那时的它自己不会启动，要你登记。
2. 写一个 XML 配置文件，声明视图解析器、消息转换器、数据源、连接池，几十行起步，少一行功能就缺一块。
3. 依赖版本自己配对：spring-context 配 jackson-databind 配 servlet-api，版本矩阵错了就是一堆 `ClassNotFoundException`（类路径上找不到某个类，[java/10-07](java/10-07-annotations-reflection-proxy.md) 讲过反射查找的过程）。
4. 打成 war 包，装一个独立的 Tomcat，把包扔进它的部署目录，调它的端口和日志。

这四件事与你的业务毫无关系，每个项目都要重来一遍。Spring Boot 的回答是四件事：

- **starter**：一组互相兼容的依赖打包成一个依赖。`spring-boot-starter-web` 把 Spring MVC、Jackson（JSON 库）、内嵌 Tomcat 一次带齐（框架事实：这是 starter 的固定内容，Boot 文档明示）。
- **自动配置**：按 classpath 上有什么，自动装配对应组件。你一行 XML 都不写。
- **外部化配置**：yml、环境变量、命令行参数统一收进一个配置环境，改行为不改代码。
- **嵌入式容器**：Tomcat 以 jar 的形式进入你的应用，`java -jar` 一条命令，应用自己就是服务器。

Boot 没有替换 Spring MVC，它只是把"装修"变成"精装交付"。下面把最不直观的两件（自动配置、内嵌容器）讲透，再把请求旅程一站站走完。

## 自动配置：两步讲透"加依赖就有功能"

问题场景：项目 pom.xml 加了一行 `spring-boot-starter-data-redis`，没有写任何配置类，`StringRedisTemplate`（Redis 客户端）就能直接注入。它是谁造的？

第一步，**读清单**。`@SpringBootApplication` 是三个注解的合成（框架行为，看 Boot 源码的注解声明）：`@SpringBootConfiguration`（声明这是配置类）、`@EnableAutoConfiguration`（开启自动配置）、`@ComponentScan`（组件扫描，30 章讲过）。`@EnableAutoConfiguration` 自身又导入了一个选择器 `AutoConfigurationImportSelector`（框架源码事实：它通过 `@Import` 挂进来）。启动时这个选择器扫描 classpath 上每个 jar 的清单文件 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`，把里面列的候选配置类全名读出来。spring-boot-autoconfigure 这个 jar 的清单里列着 Web、Redis、Jackson、数据源等各领域候选。这就是"加了依赖什么都不写就有功能"的第一半：**classpath 上有 jar，候选名单里就有它**。

第二步，**条件筛选**。候选类上贴着条件注解，逐个判断去留：

- `@ConditionalOnClass`：类路径上存在某个类才装配。例如 Jackson 的自动配置要求 `ObjectMapper` 在 classpath；Web MVC 的自动配置要求 `DispatcherServlet` 相关类在 classpath。starter 把这些类带进来了，条件成立。
- `@ConditionalOnMissingBean`：你没定义同类型 Bean 我才装配。这是"用户配置永远优先"的机制出处。
- `@ConditionalOnProperty`：配置开关。例如 `spring.flyway.enabled` 控制数据库迁移的自动配置（框架行为）。

还有一个面试常问的细节：`@ConditionalOnMissingBean` 怎么保证评估时你已经注册了自己的 Bean？机制是 `AutoConfigurationImportSelector` 实现了 `DeferredImportSelector`（延迟导入选择器，框架行为）。Spring 解析配置类时，把"延迟导入"的候选**拖到所有用户配置类解析完之后**才处理。顺序是：先扫你的 `@Component`、先跑你的 `@Bean` 方法，最后才轮到自动配置评估条件。你定义了，它就闭嘴。

验证口径：LinkForge 没有定义 `StringRedisTemplate` 的 Bean，自动配置装配了它；`passwordEncoder` 是项目自己在 `SecurityConfig` 里 `@Bean` 的，Spring Security 相关的自动配置就不会再装配一个同类型 Bean（推断：`@ConditionalOnMissingBean` 生效，见 30 章的依赖图）。想显式关掉某个自动配置，用 `@SpringBootApplication(exclude = 某自动配置类.class)`。

反例提醒：自动配置"不生效"通常不是坏了，而是条件没满足——依赖没进 classpath（`@ConditionalOnClass` 不成立）、你定义了同类型 Bean（`@ConditionalOnMissingBean` 不成立）、或开关关了（`@ConditionalOnProperty` 不成立）。排查时按这三问走。

## 嵌入式容器：应用自己就是服务器

概念先立住。**Servlet 容器**（也叫 Web 容器，Tomcat 是其一）是负责收 HTTP 连接、解析报文、调用你的 Servlet、写回响应的服务器程序。旧时代它外置：装 Tomcat、部署 war、运维一套独立进程。嵌入式容器把这件事倒过来：Tomcat 的核心以 jar 形式进了你的 classpath，成为 Spring 容器里的一个 Bean（`ServletWebServerFactory`），应用启动时顺带把它拉起来。

装配链路（框架行为）：`spring-boot-starter-web` 带入 `tomcat-embed-core` → `ServletWebServerFactoryAutoConfiguration` 的嵌套配置上有 `@ConditionalOnClass(Tomcat.class)`，类路径上有 Tomcat 就装配 `TomcatServletWebServerFactory`。启动时序（框架行为，按启动日志的出现顺序）：

1. 容器 refresh 的早期阶段创建 Web 服务器实例，日志打 `Tomcat initialized with port(s): 8080`。此时**端口还没绑定**。
2. 容器初始化所有单例 Bean。
3. refresh 收尾阶段启动服务器并绑定端口，日志打 `Tomcat started on port(s): 8080`。
4. 最后才打 `Started LinkForgeApplication in ...`。看到它才算就绪。

所以"端口绑定 ≠ 服务就绪"要修正得更精确：**端口绑定发生在所有 Bean 初始化完成之后**，`Tomcat started` 之前应用其实已经失败不了多久——如果某个 Bean 初始化抛异常，refresh 中止，已创建的服务器被销毁，进程退出，不会留下"端口通了但应用半死"的状态（框架行为；数据库密码配错时启动直接失败，就是这一幕，见 [21-os.md](21-os.md) 的连接与端口两节）。这个时序也是探活配置的依据：`/actuator/health` 返回 200 才代表能接请求，只看端口通不通会误判。

## 一次请求的九站旅程

现在把镜头对准运行期。你在浏览器敲 `http://localhost:8080/api/links/ab12x9`，到拿到 JSON，一共九站。每一站都标注了它是框架行为还是项目逻辑。

1. **内嵌 Tomcat 收下连接**。工作线程把 TCP 字节流解析成 HTTP 报文，装进 `HttpServletRequest`/`HttpServletResponse` 两个对象（框架行为）。线程模型见 [21-os.md](21-os.md)：Tomcat 工作线程名字形如 `http-nio-8080-exec-1`。
2. **过滤器链**。Filter 是 Servlet 规范的接口：请求进 Servlet 之前（和响应出来之后）可以插入处理逻辑，多个 Filter 排成链。LinkForge 的安全过滤器链在这一站完成认证——JWT 或 API Key 认不认得过（框架机制 + 项目配置，细节见 [32-spring-security.md](32-spring-security.md)）。401/403 多数在这站产生。
3. **进入 DispatcherServlet**。Boot 把 `DispatcherServlet` 映射到 `/`，所有请求先到它（框架行为）。它的核心方法 `doDispatch` 干接下来所有的事。
4. **HandlerMapping 查表**。启动时，Spring 扫描所有 `@RestController` 类，把每个带 `@RequestMapping` 的方法登记进一张表：键是"HTTP 方法 + 路径 + 产出类型"，值是"哪个 Bean 的哪个方法"（框架行为：`RequestMappingHandlerMapping`）。请求来了查表，查不到就 404——**这个 404 产生于你的代码执行之前**。
5. **参数解析与校验**。查到表后由 `RequestMappingHandlerAdapter` 真正执行（框架行为）：给方法的每个参数找一个参数解析器（`HandlerMethodArgumentResolver`）——`@PathVariable` 从路径取、`@RequestParam` 从查询串取、`@RequestBody` 用 Jackson 把 JSON 反序列化成对象。参数标了 `@Valid`，解析完立刻校验，不通过抛 `MethodArgumentNotValidException`。**此时方法体一行还没执行**。
6. **Controller 执行**。反射调用你的方法（框架机制）。LinkForge 的 Controller 一律一行：收参、调 Service、包响应壳（LinkForge 业务约定）。
7. **序列化返回**。方法返回 `ApiResponse<T>` 对象后，返回值处理器发现类上有 `@ResponseBody`，把对象交给 `HttpMessageConverter`（消息转换器）：`MappingJackson2HttpMessageConverter` 调 Jackson 把对象按字段序列化成 JSON 字节写进响应体，响应头 `Content-Type` 设为 `application/json`（框架行为）。
8. **异常分叉**。`doDispatch` 用 try/catch 包住整个流程（框架行为）。任何一站抛出的异常被接住，送进异常解析器链，第一站是 `ExceptionHandlerExceptionResolver`：按异常类型查 `@RestControllerAdvice` 类里的 `@ExceptionHandler` 映射表，反射调用匹配方法。LinkForge 的翻译表在 `GlobalExceptionHandler`（30 章已逐段讲过 429/409/400/500 的翻译），它不挂在任何 Controller 里，就等在解析器上。
9. **响应离开 Tomcat**。JSON 字节经 Tomcat 写回 TCP 连接，浏览器或客户端拿到响应。

九站全是框架行为的骨架，本质可以用一个纯 Java 程序演出来。路由表就是 HandlerMapping，"从路径取参数加反射调用"就是参数解析器与 HandlerAdapter 的本质，查不到表就 404。下面这个文件自包含，可直接编译运行（纯 Java 模型，不是 Spring 代码；真实 `doDispatch` 还多了拦截器、消息转换、异常解析三块）。

```java
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

public class MiniMvcDemo {

    // A stand-in for a controller class. In real MVC it carries @RestController.
    static class LinkController {
        public String list() {
            return "{\"records\":[\"ab12x9\",\"zz9top\"]}";
        }

        public String detail(String code) {
            return "{\"code\":\"" + code + "\",\"url\":\"https://example.com\"}";
        }
    }

    public static void main(String[] args) throws Exception {
        Object controller = new LinkController();
        // Route table: path template -> method. This Map is what HandlerMapping really is.
        Map<String, Method> routes = new LinkedHashMap<>();
        routes.put("GET /links", LinkController.class.getMethod("list"));
        routes.put("GET /links/{code}", LinkController.class.getMethod("detail", String.class));

        dispatch(routes, controller, "GET /links");
        dispatch(routes, controller, "GET /links/ab12x9");
        dispatch(routes, controller, "GET /nolink/ab12x9");
    }

    // A stand-in for DispatcherServlet.doDispatch: lookup -> resolve args -> reflect -> respond
    static void dispatch(Map<String, Method> routes, Object controller, String request) throws Exception {
        String[] parts = request.split(" ");
        String[] seg = parts[1].split("/");
        Method handler = routes.get(request);
        if (handler == null && seg.length == 3) {
            handler = routes.get(parts[0] + " /" + seg[1] + "/{code}"); // template match
        }
        if (handler == null) {
            System.out.println(request + " -> 404 no handler");
            return;
        }
        Object[] params = new Object[handler.getParameterCount()];
        if (params.length == 1) {
            params[0] = seg[seg.length - 1]; // the {code} value comes from the last path segment
        }
        Object body = handler.invoke(controller, params);
        System.out.println(request + " -> 200 " + body);
    }
}
```

```text
GET /links -> 200 {"records":["ab12x9","zz9top"]}
GET /links/ab12x9 -> 200 {"code":"ab12x9","url":"https://example.com"}
GET /nolink/ab12x9 -> 404 no handler
```

逐步看发生了什么：

1. 路由表是 `Map<String, Method>`：键是模板字符串，值是反射拿到的 `Method` 对象。Spring 启动时建的注册表就是这个形状，只是键更丰富（方法、路径、produces、consumes 的组合）。
2. 第一个请求精确命中 `GET /links`，调 `list()`，参数个数为 0，反射调用返回 JSON。
3. 第二个请求精确查找失败，走模板匹配：`GET /links/{code}` 命中，路径最后一段 `ab12x9` 被塞进参数数组，`Method.invoke` 调用 `detail("ab12x9")`。这一步就是 `@PathVariable` 的本质。
4. 第三个请求 `GET /nolink/ab12x9`：精确与模板都查不到，返回 404。真实 MVC 里同样查不到就 404，根本轮不到你的代码。

这张图的用法：接口行为异常时，按站定位。401/403 在第 2 站（过滤器）；404 先分清是第 4 站（路径写错，根本没进 Controller，方法不打日志）还是 Service 里的业务判定（第 6 站之后，会打日志）；400 在第 5 站（参数解析、校验）；500 几乎都在 Service/DB 层，走第 8 站的兜底。

## 五个注解各管一段

问题场景：一个方法怎么知道"这个请求归我"？参数怎么从 HTTP 报文里长出来？答案全在注解上——注解本身不执行代码（[java/10-07](java/10-07-annotations-reflection-proxy.md) 的结论），执行的是框架，注解只是给框架读的说明书。

**`@RestController`**：类级注解，是 `@Controller` 加 `@ResponseBody` 的组合（框架行为：看它的源码，上面元注解了这两个）。`@Controller` 让组件扫描把它注册成 Bean（30 章）；`@ResponseBody` 改变返回值的去向：返回对象直接交给消息转换器写响应体，不再去找视图模板。常见误解：类上只写 `@Controller`，接口返回字符串"abc"，框架把 `abc` 当视图名去找模板文件，找不到就报错——表现为"接口怎么 500 了"。

**`@GetMapping`**：`@RequestMapping(method = GET)` 的缩写（框架事实）。同族还有 `@PostMapping`、`@PatchMapping`、`@DeleteMapping`。类级的 `@RequestMapping("/api/links")` 与方法级路径拼接成完整路径。`produces` 属性声明响应类型并参与路由匹配：`/export` 的 `produces = "text/csv;charset=UTF-8"` 要求客户端 Accept 头兼容，否则不进这个方法（框架行为）。

**`@PathVariable`**：把 URI 模板占位符的值绑到参数。`GET /api/links/ab12x9` 对 `@GetMapping("/{code}")` 的 `@PathVariable String code`，code 就是 `ab12x9`。大小写敏感是 LinkForge 业务约定（短码用大小写敏感的排序规则存库，`CreateShortLinkRequest` 的 javadoc 注明 AbC123 与 abc123 是两条链）。

**`@RequestParam`**：把查询串绑到参数。`?page=2&size=10` 对 `@RequestParam(defaultValue = "1") int page`。`required` 默认 true，客户端没带这个参数就 400（框架默认行为）；写了 `defaultValue` 则隐含可选，缺了用默认值。

**`@RequestBody`**：把整个请求体经 Jackson 反序列化成一个对象。一个方法最多一个（HTTP 体只有一份）；请求头 `Content-Type` 必须是转换器支持的类型如 `application/json`，否则 415（框架行为）；体缺失或 JSON 破损抛 `HttpMessageNotReadableException`，LinkForge 翻成 400 固定文案 "malformed request body"（不回显内部细节，见 `GlobalExceptionHandler`）。

三个补充。其一，`@RequestHeader` 从请求头取值：`ShortLinkController.create` 的第二参数是 `@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey`，头可缺席，缺席值为 null（框架行为）。其二，Servlet API 对象可以直接当方法参数，Spring 认识它们并传现成对象进来（框架行为）：`AuthController.register` 的第二参数 `HttpServletRequest` 就是这么来的，Service 用它取客户端 IP。其三，`@Valid` 不是取值的，是校验的，下一节讲。

## 项目案例：ShortLinkController 逐方法对照 REST 语义

需求：对短链做完整的增删改查与状态管理，对外暴露 JSON API。不用这套机制怎么写：手写一个 Servlet，在 `doGet`/`doPost` 里用 if-else 分发路径，手写正则解析查询串，手写 JSON 拼接——每个接口重复一遍，且没有校验和异常的统一去处。

`src/main/java/com/linkforge/link/controller/ShortLinkController.java` 全文 80 行，8 个方法。类上三个注解：`@RestController`、`@RequestMapping("/api/links")`、`@RequiredArgsConstructor`（Lombok 生成构造器，30 章讲过注入）。逐方法对照：

| 方法与路径 | 语义 | 成功响应 |
|---|---|---|
| `POST /api/links` | 创建 | 200，壳内含新 code 与跳转路径 |
| `GET /api/links?page=&size=` | 分页列表 | 200，`PageResponse` 五件套 |
| `GET /api/links/export` | CSV 导出 | 200，`text/csv` 附件下载 |
| `GET /api/links/{code}` | 查单条 | 200，`ShortLinkDetail` |
| `PATCH /api/links/{code}` | 部分更新 | 200，更新后详情 |
| `POST /api/links/{code}/disable`、`/enable` | 状态迁移动作 | 200，`data=null` |
| `DELETE /api/links/{code}` | 删除 | 200，`data=null` |

四个值得停下来想的设计决策（都是 LinkForge 业务选择，不是标准答案）：

1. **create 为什么 200 而不是 201**。纯 REST 风格创建成功回 201 加 `Location` 头。项目选了统一响应壳加统一 200，新建的资源标识放 `data` 里。两种都合法；统一壳的好处是全 API 一套解包逻辑，代价是客户端不能只靠状态码区分"新建了"和"查到了"。
2. **disable/enable 为什么是 POST 动作端点，而不是 PATCH 一个 status 字段**。这不是"改一个字段"，是状态迁移：服务端要联动清缓存、退出并发合并窗口（`ShortLinkService.setStatus`，已核实它会调用 `singleFlight.forget` 与 `evictCache`）。显式端点把意图写进 URI，也避免客户端 PUT 整个对象造成的并发覆盖。当动作没有自然的资源形态时，"POST 到动作端点"是 REST 认可的写法。
3. **delete 为什么 200 加壳，不是 204**。204 的语义是"成功且无响应体"，和统一壳互斥（壳要有 code/message 字段）。选了壳就全 API 一致地 200。纯 REST 项目可以选 204。
4. **export 为什么不用壳**。CSV 是文件不是 JSON，包不进壳。`ResponseEntity<String>` 是逃生门：状态码、响应头、体全部自己控制。`Content-Disposition: attachment; filename=links.csv` 让浏览器按附件下载。Spring 对 String 返回值用字符串转换器而不是 Jackson（框架行为），所以 CSV 原样输出。

每个方法体都是一行"调 Service 包壳"，例如 create：

```java
// 片段，引自 src/main/java/com/linkforge/link/controller/ShortLinkController.java
@PostMapping
public ApiResponse<CreateShortLinkResponse> create(@Valid @RequestBody CreateShortLinkRequest request,
                                                   @RequestHeader(value = "Idempotency-Key", required = false)
                                                   String idempotencyKey) {
    return ApiResponse.ok(shortLinkService.create(request, idempotencyKey));
}
```

Controller 不写业务，所以它薄得可以一眼审完；业务规则、事务、缓存全在 Service（30 章的依赖图）。这是"分层"在 Web 层的落地：**Controller 管 HTTP 形状，Service 管业务语义**。

如何自己验证：启动应用后用 `curl -i` 打这些接口看状态码与头（框架行为演示需要运行环境：本机无 Docker，无法起 MySQL/Redis，行为按代码与框架文档描述，标注"示意"）。

## ApiKeyController：另一种 Controller 形态，和幂等键的真相

`src/main/java/com/linkforge/apikey/controller/ApiKeyController.java` 全文 55 行（已读），管 API Key 的创建、列表、吊销。它的价值在 `requireUser`：

```java
// 片段，引自 src/main/java/com/linkforge/apikey/controller/ApiKeyController.java
private static AuthUser requireUser() {
    AuthUser user = UserContext.get();
    if (user == null) {
        throw new com.linkforge.common.exception.BizException(401, "unauthorized");
    }
    return user;
}
```

`UserContext.get()` 读 ThreadLocal（线程局部变量，每个线程各自一份的存储）里由安全过滤器写入的登录用户（框架机制，链 [32-spring-security.md](32-spring-security.md)）。拿不到就抛 `BizException(401)`，由全局异常处理器翻成 HTTP 401——Controller 全程不碰 `HttpServletResponse`。类注释（D-Bw2-4）写明这个端点只认 JWT：API Key 不能用来管理 Key，防止 Key 自我复制。这是"身份从上下文取，不进方法参数"的形态，和 `ShortLinkController` 的"身份进 Service 内部再取"并存。

顺带澄清一个容易张冠李戴的点：**幂等键不在 `ApiKeyController`**。逐行读完这 55 行可以确认它没有任何幂等逻辑。全项目唯一使用 `Idempotency-Key` 的地方是 `ShortLinkController.create` 的方法签名（第 34 行）与 `ShortLinkService.create` 的实现。引用类先读原文，这与 30 章 `@MapperScan` 宽扫描事故是同一条教训。

## 参数校验：形状给注解，语义给 Service

问题场景：注册接口要校验用户名非空、邮箱格式、密码长度。手写 if 十个字段十段，校验代码比业务还长，而且散在每个接口里。

先立两个名词。**DTO**（Data Transfer Object，数据传输对象）：只为在层与层之间搬运数据而生的类，字段与请求体一一对应，不带业务方法；区别于实体类（对应数据库表，链 [41-mybatis-plus.md](41-mybatis-plus.md)）。**Bean Validation**：一套用注解声明校验规则的规范，`jakarta.validation-api` 定义注解，Hibernate Validator 实现校验逻辑；`spring-boot-starter-validation` 把实现带进 classpath（pom.xml 已核实）。

`src/main/java/com/linkforge/link/dto/CreateShortLinkRequest.java`（已读全文）是标准形态：

```java
// 片段，引自 src/main/java/com/linkforge/link/dto/CreateShortLinkRequest.java
@NotBlank(message = "originalUrl required")
@Size(max = 2048, message = "originalUrl too long")
private String originalUrl;

@Size(max = 16, message = "customCode too long")
private String customCode;
```

逐注解说：`@NotBlank` 是 `@NotNull` 的加强版，null、空串、纯空格都不过；`@Size` 管长度。注意 `customCode` 只有 `@Size(max = 16)`，而业务规则是"4 到 16 位、只含 `[A-Za-z0-9_-]`、不是保留字"——这一串写不成单个注解，它是**语义规则**，落在 Service：`ShortLinkService.doCreate` 里调 `LinkValidations.requireValidCustomCode(customCode)`（已核实第 151 到 153 行）。同理 `expireAt`（必须在将来）也没有注解，Service 里比对当前时间（第 148 行）。**注解管形状，Service 管语义**，两层混写会让校验逻辑一半在注解一半在代码，审计要看两处。

`@Valid` 的触发点（框架行为）：`@RequestBody` 参数反序列化完成后、Controller 方法体执行前，框架对该 DTO 执行所有注解校验。失败抛 `MethodArgumentNotValidException`，异常对象里装着 **BindingResult**——所有字段错误的集合，每条含字段名与消息。`GlobalExceptionHandler` 的校验处理（第 31 到 41 行，30 章讲过它的位置，这里看内容）取第一个字段错误的消息回 400：

```java
// 片段，引自 src/main/java/com/linkforge/common/exception/GlobalExceptionHandler.java
@ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
@ResponseStatus(HttpStatus.BAD_REQUEST)
public ApiResponse<Void> handleValidation(Exception ex) {
    String msg = "validation failed";
    if (ex instanceof MethodArgumentNotValidException manv && manv.getBindingResult().getFieldError() != null) {
        msg = manv.getBindingResult().getFieldError().getDefaultMessage();
    }
    return ApiResponse.fail(400, msg);
}
```

（节选；`instanceof 模式匹配`是 Java 16 特性，见 [java/10-10-java17.md](java/10-10-java17.md)。表单绑定场景的 `BindException` 走同一处理。）

**BindingResult 的另一种用法**（框架行为）：在 `@Valid` 参数后面紧跟一个 `BindingResult` 类型参数，框架就不抛异常，把错误放进它，由你在方法内处理。LinkForge 的 Controller 没有用这种写法（已核实全仓库 Controller 无此签名）。为什么不用：每个方法都要重复判错，忘了判断，带错的请求就被当成功放行；统一走全局异常处理器，错误响应的形状全 API 一致。BindingResult 适合的场景：某个接口的校验失败确实需要特殊响应形状时。代价就是上面的"容易忘"。

反例（初学者最高频的坑）：DTO 字段上注解写得整整齐齐，参数上却忘了 `@Valid`——校验机制根本不启动，所有请求放行，看起来"什么都没发生"。检查方法：看 Controller 方法参数，`@Valid` 和 `@RequestBody` 必须同时在场，`AuthController` 的两个接口都是这么写的（已核实）。

## 统一响应体的得与失

`src/main/java/com/linkforge/common/result/ApiResponse.java`（已读全文，27 行）：`code`、`message`、`data` 三段，成功 code 为 0，`ApiResponse.ok(data)` 与 `ApiResponse.fail(code, message)` 两个工厂。配套 `src/main/java/com/linkforge/common/result/PageResponse.java` 装分页五件套：`records`、`total`、`current`、`size`、`pages`（已读全文）。

**得**：前端解包逻辑写一次（响应壳形状全 API 一致）；错误通道统一（失败时 message 总在场）；业务码可以比 HTTP 状态码更细，前端分支不用解析十种错误格式。

**失**有三条，都值得知道：

1. **双轨风险**。如果失败也包成 HTTP 200 加 `code != 0`（"永远 200"风格，不少团队这么写），HTTP 层就瞎了：网关与监控的重试、告警、4xx/5xx 指标、浏览器与代理的缓存判断，全部按成功处理。HTTP 状态码是给机器看的通用语言（网关、负载均衡、curl 都认识），业务 code 是给自家前端看的方言。LinkForge 的选择：HTTP 状态码是真码——429 带 `Retry-After`、409、410、500 都由 `GlobalExceptionHandler` 设成真实状态码，响应体里的 code 与之同步。两套都在，两套都对，谁也不吞谁。
2. **非 JSON 响应包不进壳**。CSV 导出、302 跳转、HTML 门禁页都是 `ResponseEntity` 裸响应（`ShortLinkController.export`、`RedirectController`，链 [20-network.md](20-network.md)）。壳只覆盖"JSON API"这个子集，处理这两类响应时前端必须知道壳不在场。
3. **无数据响应也带壳**。`disable` 返回 `{"code":0,"message":"ok","data":null}`。一致性换来的冗余，多数团队认为值得。

新项目怎么判断：要"HTTP 状态码真实，壳只装数据与业务码"的组合；避免用 200 吞掉一切失败。30 章从依赖注入的角度讲过 `ApiResponse` 与异常处理器的协作，本章补的是这两套语义的取舍。

## REST 语义、状态码与幂等键

REST 不是库也不是协议，是"用 HTTP 本来的语义设计接口"的风格约定：URI 是资源（名词），方法是动作（GET/POST/…），状态码是结果。

**资源命名**，对照项目：`/api/links` 名词复数；`/{code}` 用业务标识而非数据库自增 id（短链场景 code 本来就是对外标识）；动词只留给没有资源形态可表达的迁移动作（`/disable`、`/enable`）。反例：`/createLink`、`/getLinkList`——方法语义全丢，路由表翻倍，状态码无从谈起。

**方法语义与幂等**。幂等的定义在 [20-network.md](20-network.md)：同一请求执行一次与执行一百次，服务器状态相同。规范给出的语义（框架无关，RFC 9110 语义层面）：

| 方法 | 语义 | 幂等（规范语义） |
|------|------|------|
| GET | 读，无副作用 | 是 |
| HEAD | 同 GET 只取头 | 是 |
| PUT | 全量替换 | 是 |
| DELETE | 删除 | 是 |
| POST | 创建、触发 | 否 |
| PATCH | 部分更新 | 不保证，取决于实现 |

幂等值得在设计时想清楚的原因只有一个：**超时之后敢不敢重试，取决于它**。GET 重试永远安全；POST 重试可能建两条记录。

**状态码**，每个都给项目真实出处（均已核实源码）：

| 状态码 | 语义 | 项目真实出处 |
|--------|------|------|
| 200 | 成功 | 所有 Controller 正常返回 |
| 400 | 格式或校验错 | `@Valid` 失败、请求体不可读（`GlobalExceptionHandler`） |
| 401 | 未认证 | `ApiKeyController.requireUser`；带密码短链未解锁（链 20） |
| 403 | 已认证但禁止 | 命中拦截名单的链接（`RedirectOutcome.Blocked`） |
| 404 | 现在不存在 | 码不存在、禁用链（`ShortLinkService.resolveTarget`） |
| 409 | 冲突 | 自定义码已被占用（`doCreate` 捕获 `DuplicateKeyException` 转 409） |
| 410 | 永久消失 | 过期链、目标不可渲染（`resolveTarget`） |
| 429 | 太频繁 | `RateLimitedException`，响应带 `Retry-After` 头 |
| 500 | 服务端错误 | 兜底 `Exception`，细节只进日志 |

404 与 410 的分工值得单独记：禁用链回 404，行还在，重新 enable 就回来，客户端"以后再试"是对的；过期链回 410，永久没了，爬虫与缓存应当把它从索引里删掉。语义给对，缓存与爬虫的行为才对（浏览器对 410 的具体处理是常见实现行为，规范定义的是语义）。

**Idempotency-Key**。问题：客户端 POST 创建短链，网关超时。请求可能成功了也可能没成功。重试，可能建两条；放弃，可能没建成。POST 不幂等，协议帮不上。通用解法（开放 API 的标准设计，概念通用）：客户端为这次操作生成一个唯一键（如 UUID）放进请求头，重试时带同一个键；服务端保证同一个键无论到达几次，返回同一条结果。它把"是否重复"的判断权从网络层拿回业务层。

LinkForge 的实现（已核实 `ShortLinkService.create` 第 91 到 124 行与 `doCreate` 第 198 到 201 行）：

1. Controller 从请求头收键，`required = false`——键是可选增强，不带就走普通流程。
2. Service 先过限流（每用户创建 30 次/60 秒，yml 配置）。
3. 有键时操作 Redis 键 `linkforge:rl:idem:{userId}:{key}`（键拼了 userId，已核实第 103 行：不同用户同键互不干扰，LinkForge 业务设计）：
   - 键里已回填 code：查库、校验这条链的属主就是当前用户、返回同一条响应——重放。
   - 键不存在：`setIfAbsent` 占位为 "pending"，TTL 5 秒（Redis 原子操作，链 [50-redis.md](50-redis.md)），然后执行创建。
   - 占位失败（同键请求正在执行）：轮询 10 次、每次 200 毫秒等回填；还没回填就抛 `BizException(503, "idempotent create still in progress, retry later")`——宁可让客户端稍后重试，也不在同一键下建第二条（第 120 到 122 行注释原意）。
4. 创建成功后回填：把 code 写进键，TTL 取配置 `idempotency-ttl-seconds`，yml 默认 86400 秒——重放窗口一天。

边界与代价：幂等只覆盖"创建"这一个动作；pending 窗口内并发的同键请求会吃 503；Redis 不可用时幂等失效（据代码推断：`setIfAbsent` 的异常未被捕获，会沿异常链变成 500）。代价是每个带键请求多两三次 Redis 往返，买来的是"重试安全"。

## 配置与观测：application.yml 的 server 与 actuator

`src/main/resources/application.yml`（已读全文）里与 Web 层直接相关的两段。

**server 段**只有一行：`port: ${SERVER_PORT:8080}`。占位符语法 `${名字:默认值}`：环境变量 `SERVER_PORT` 存在就用它，否则 8080。同键多源时的优先级（高到低，框架行为）：命令行参数、JVM 系统属性、环境变量、`application-{profile}.yml`、`application.yml`。环境变量排得高是容器化的惯例：镜像不变，部署时注入环境。

**profile 分段**：yml 用 `---` 分成三段（已核实）。全局段管应用名、调度池、Flyway；`dev` 段（`spring.config.activate.on-profile: dev`）放本地数据源、Redis、RabbitMQ 地址与全部业务参数；`prod` 段放收紧项。启动时 active profile 决定激活哪段，同名键后激活的覆盖先的（框架行为）。

**actuator 段**：`management.endpoints.web.exposure.include: health,info,metrics,prometheus`。Actuator 是 Boot 的运维端点集（框架设施）：`/actuator/health` 给负载均衡探活，`/actuator/prometheus` 给监控系统抓指标。Boot 默认 web 只暴露 health（框架默认最小暴露），要哪个显式列哪个。健康详情 `show-details` 在 dev 是 `always`、prod 是 `when_authorized`（已核实 226 到 230 行）——健康详情里写着数据源、Redis 的连通性信息，生产不该让匿名访问者看。`/actuator/prometheus` 再加一道 bearer token：配置项为空时拒绝所有人（LinkForge 业务决策，fail-closed）。

**业务参数的收拢**：所有 `linkforge.*` 配置绑定到一个类 `@ConfigurationProperties(prefix = "linkforge")` 的 `LinkForgeProperties`（已核实该类声明）。环境变量 `LINKFORGE_BLOOM_FPP` 能覆盖 `linkforge.bloom.fpp`，靠的是 relaxed binding（宽松绑定，框架行为）：Boot 把环境变量的大写下划线映射回小写点分。配置文件里每项都有注释记录它的来历（A9、B7、B8 等演进编号），配置文件本身就是决策记录。

## OpenAPI：接口契约自动生成

问题：前端要知道"这个接口收什么、回什么"。手写文档会漂移——代码改了文档没改，比没有文档更害人。

两个名词：**OpenAPI** 是一份描述接口的规范，产出机器可读的 JSON/YAML（路径、参数、请求响应结构、认证方式）；**Swagger UI** 是把这份描述渲染成交互页面的工具，页面上能直接发请求调试。`springdoc-openapi-starter-webmvc-ui`（pom.xml 已核实）启动时扫描 `@RestController` 注解，自动生成 `/v3/api-docs`（JSON 契约）与 `/swagger-ui.html`（交互页面）。路径是默认值，示意；生成过程全部发生在启动期，不占请求路径。

项目定制在 `src/main/java/com/linkforge/config/OpenApiConfig.java`（已读全文）：一个 `OpenAPI` Bean 定 title、version、description；version 取自 `BuildProperties`，它由 `spring-boot-maven-plugin` 的 `build-info` goal 生成（pom.xml 已核实第 182 行），编译时把 pom 版本写进 jar 内的 `build-info.properties`；IDE 里没有这个文件就退回 "dev"（类 javadoc 原文）。description 写明双认证（JWT Bearer 与 `X-API-Key` 头）与 webhook 签名头，`components` 里注册两种 SecurityScheme——Swagger UI 上能带着认证调试。

生产收紧（yml prod 段，已核实 219 到 223 行）：`springdoc.api-docs.enabled: false` 与 `swagger-ui.enabled: false`。理由是 LinkForge 的业务决策：接口清单本身就是攻击面的侦察资料，不该给陌生人看。

## 常见错误与误区

| 错误写法 | 实际发生什么 | 怎么改 |
|---|---|---|
| Controller 里写业务与 SQL | 无法复用、无法单测，HTTP 壳长成上帝类 | 收参、调 Service、包壳（ShortLinkController 每方法一行） |
| 500 响应里回显异常栈 | 泄露表名、SQL、框架版本 | 日志留全栈，响应给固定文案（GlobalExceptionHandler 兜底） |
| 分页 size 不设上限 | size=100000 一次拖垮数据库 | 服务端钳制（list：size 小于 1 或大于 100 归 20） |
| 该 PATCH 的地方用 PUT | 全量替换把没提交的字段抹掉 | 部分更新用 PATCH，DTO 字段可空，只改提交的字段 |
| 失败也回 HTTP 200 加 code | 网关、监控、缓存全按成功处理 | HTTP 状态码真实，业务码与之一致 |
| DTO 注解齐全但忘写 @Valid | 校验不启动，全部放行 | 参数上 @Valid 与 @RequestBody 同时在场 |
| 404 与 410 混用 | 缓存与爬虫行为错位 | 可恢复 404，永久消失 410 |
| 429 不带 Retry-After | 客户端只能盲等盲试 | 异常带秒数，处理器写进响应头 |

## 自测

先自己答，再看参考答案。

1. 一个请求到达后，Controller 方法体一行都没执行，可能发生在哪几站？
   答：过滤器链（401/403）、路由未命中（404，方法连日志都不会打）、参数解析失败（JSON 不可读或参数类型对不上，400）、`@Valid` 校验失败（400）。先看状态码，再按站排查。
2. 你定义了一个自己的 `ObjectMapper` Bean，Boot 自动配置的那个还会装配吗？为什么？
   答：不会。自动配置经 `DeferredImportSelector` 被拖到用户配置全部解析完之后才评估，`@ConditionalOnMissingBean` 看到你的 Bean 已注册，条件不成立，自动配置让位。
3. `@Valid` 校验失败时，异常在哪里产生、叫什么、谁把它变成 400？
   答：在参数解析阶段产生（`@RequestBody` 反序列化之后、方法体执行之前），异常是 `MethodArgumentNotValidException`；`@RestControllerAdvice` 的 `GlobalExceptionHandler` 按类型匹配，取第一个字段错误的消息返回 400。
4. 禁用链为什么 404 而不是 410？
   答：410 的语义是永久消失，客户端与爬虫应放弃重访；禁用是可恢复状态（enable 即回）。语义给对，缓存与爬虫的行为才对。
5. 说一遍 Idempotency-Key 在本项目的完整生命周期。
   答：客户端生成唯一键放请求头 → Controller 以 `required = false` 收 → Service 限流后查 Redis 键 `idem:{userId}:{key}` → `setIfAbsent` 占位 pending（5 秒）→ 创建成功回填 code（TTL 默认 86400 秒）→ 同键重放查库并校验属主后返回同响应 → 占位被占则轮询 10 次后仍 pending 则 503。
6. `UpdateShortLinkRequest`（PATCH 用的 DTO）的字段为什么不写 `@NotBlank`？
   答：PATCH 是部分更新，字段缺席是合法请求；`@NotBlank` 会把"没传"判成非法。形状注解只挡"传了但太长"（`@Size`），传不传由业务语义决定。

## 面试追问

**一次请求的完整链路**。口径：Tomcat 工作线程解析报文 → 过滤器链认证 → `DispatcherServlet` → HandlerMapping 查表 → HandlerAdapter 解析参数并执行 `@Valid` → Controller → Service → 返回值经 Jackson 序列化写响应；异常被 `doDispatch` 捕获进 `HandlerExceptionResolver`，落到 `@RestControllerAdvice`。追问"`@RestController` 与 `@Controller` 的区别"答：前者自带 `@ResponseBody`，返回值直接进消息转换器，不走视图解析。追问"404 但代码没报错"答：路由未命中，请求没进 Controller。

**自动配置机制**。口径：starter 带依赖 → `@EnableAutoConfiguration` 导入选择器读各 jar 的 `AutoConfiguration.imports` 得候选名单 → `@ConditionalOnClass`/`OnMissingBean`/`OnProperty` 条件筛选 → 选择器是 `DeferredImportSelector`，等用户配置解析完才评估，所以用户的 Bean 永远优先。追问"怎么关掉某个自动配置"答：`exclude` 属性，或让条件不成立。

**REST 幂等**。口径：天然幂等的方法（GET/PUT/DELETE）之外，POST 用幂等键——客户端生成唯一键，服务端保证"键到结果"可重放。项目：`Idempotency-Key` 头（可选）加 Redis `setIfAbsent` 占位 pending 5 秒，成功回填 code（TTL 默认 86400 秒），重放校验属主返回同响应，进行中轮询后仍 pending 则 503。追问"键怎么防串用户"答：键里拼了 userId；追问"为什么轮询后宁可 503"答：强制二次创建会在同一键下造出两条链，违背幂等承诺。

## 下一步

- 过滤器链在九站的第 2 站怎么认人：[32-spring-security.md](32-spring-security.md)。
- Service 之下的 SQL 与索引：[40-mysql.md](40-mysql.md)。
- 幂等键与缓存依赖的 Redis：[50-redis.md](50-redis.md)。
- 本项目一次跳转请求的逐行追踪：[81-linkforge-flows.md](81-linkforge-flows.md)。
- HTTP 报文、302 与幂等的网络层定义：[20-network.md](20-network.md)。
