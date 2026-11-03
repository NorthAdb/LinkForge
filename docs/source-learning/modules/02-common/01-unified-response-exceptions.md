# 2.1 统一响应与全局异常

## 1. 本节学习目标

1. 掌握全项目 HTTP 应答的两个壳：`ApiResponse` 与 `PageResponse`，以及 code=0 表示成功的约定。
2. 掌握业务异常家族：`BizException` 基类与 `RateLimitedException` 子类的分工。
3. 背下 `GlobalExceptionHandler` 的七个分支映射表，知道任何一种报错会变成什么 HTTP 状态码和什么响应体。
4. 走通参数校验链路：`@Valid` 注解如何变成 400 响应。

## 2. 前置知识

- 1.2 课的组件扫描。`GlobalExceptionHandler` 也是一个被扫描的 Bean。
- HTTP 状态码常识：4xx 客户端错，5xx 服务端错。
- Java 异常继承：`RuntimeException` 及其子类不强制调用方捕获（Java 语言知识，称为非受检异常）。Lombok `@Data` 编译期生成 getter/setter 等（1.2 课讲过）。

## 3. 本节涉及的源码

| 文件 | 职责 | 阅读顺序 | 为什么读它 |
|------|------|----------|------------|
| `src/main/java/com/linkforge/common/result/ApiResponse.java`（27 行） | 统一响应壳 | 第 1 | 所有接口的返回外皮 |
| `src/main/java/com/linkforge/common/result/PageResponse.java`（16 行） | 分页响应壳 | 第 2 | 列表接口专用 |
| `src/main/java/com/linkforge/common/exception/BizException.java`（18 行） | 业务异常基类 | 第 3 | 业务拒绝的载体 |
| `src/main/java/com/linkforge/common/exception/RateLimitedException.java`（18 行） | 限流异常 | 第 4 | 唯一带响应头的业务异常 |
| `src/main/java/com/linkforge/common/exception/GlobalExceptionHandler.java`（78 行） | 异常到 HTTP 的全映射 | 第 5 | 本节核心 |
| `src/main/java/com/linkforge/link/dto/CreateShortLinkRequest.java`（看 :16-24） | 校验注解实例 | 第 6 | 校验链路的入口素材 |
| `src/main/java/com/linkforge/user/controller/AuthController.java`（看 :23-31） | `@Valid` 用法 | 第 7 | 校验链路的触发点 |

common 包只有 9 个文件，本节覆盖其中 5 个，剩下 3 个限流类在模块 09、1 个工具类在 [2.2 日志与工具类](02-logging-utils-clientip.md)。

## 4. 类的定位

这五个类构成项目的「横切层」：任何 Controller 都返回 `ApiResponse`，任何业务拒绝都抛 `BizException`，任何漏网的异常都被 `GlobalExceptionHandler` 接住。业务代码因此不写 try/catch 拼响应。包位置见[包结构图](../../diagrams/archify/html/03-package-map.html)（由并行图表任务产出），common 在所有业务包的下层，谁都可以依赖它，它不依赖任何业务包。

## 5. 核心源码详解

### 5.1 ApiResponse：三字段壳（ApiResponse.java:10-26）

```java
public class ApiResponse<T> {

    private int code;
    private String message;
    private T data;

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(0, "ok", null);
    }

    public static ApiResponse<Void> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
```

（Java 语言知识）`<T>` 是泛型，`ApiResponse<LoginResponse>` 表示 data 字段装一个 LoginResponse。三个静态工厂是本类全部使用方式：带数据的成功、不带数据的成功、失败。（本项目实现）约定 code=0 成功，非 0 时通常等于 HTTP 状态码。message 成功时固定 "ok"，失败时是人可读的原因。

Controller 的典型用法（AuthController.java:29-31）：

```java
    public ApiResponse<LoginResponse> register(@Valid @RequestBody RegisterRequest request,
                                               HttpServletRequest httpRequest) {
        return ApiResponse.ok(authService.register(request, httpRequest));
```

注意 HTTP 状态码没写，默认 200。失败时状态码由异常处理器决定，见 5.4。

### 5.2 PageResponse：调用方组装的分页壳（PageResponse.java:8-16）

```java
@Data
@AllArgsConstructor
public class PageResponse<T> {
    private List<T> records;
    private long total;
    private long current;
    private long size;
    private long pages;
}
```

只有 `@AllArgsConstructor`，没有静态工厂，五个字段全部由调用方填。（本项目实现）这是因为分页数据来自 MyBatis-Plus 的 `Page` 对象，转换处自己算 pages。字段语义：records 当前页数据，total 总条数，current 页码，size 每页条数，pages 总页数。泛型 `List<T>` 对应记录类型。

### 5.3 BizException 家族（BizException.java:5-18 与 RateLimitedException.java:9-18）

```java
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        this(400, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }
}
```

（BizException.java:5-18）

两个构造器的分工：单参数默认 400（多数业务拒绝是输入问题），双参数自定义状态码。项目里 `throw new BizException(404, "short link not found")`（ShortLinkService.java:759）与 `throw new BizException(410, "short link expired")`（:762）都走双参数。（Java 语言知识）继承 RuntimeException 意味着方法签名不用声明 throws，调用链上任何一层都能抛，由最外层统一接。

```java
@Getter
public class RateLimitedException extends BizException {

    private final int retryAfterSeconds;

    public RateLimitedException(int retryAfterSeconds) {
        super(429, "too many requests, retry later");
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
```

（RateLimitedException.java:9-18）

子类锁死 429 与固定文案，额外携带 `retryAfterSeconds`。javadoc（:5-8）说明设计意图：响应体与普通 429 完全一致，只是多一个 `Retry-After` 响应头。它由限流器在拒绝时抛出（ShortLinkService.java:137）。

### 5.4 GlobalExceptionHandler 全映射（GlobalExceptionHandler.java:16-77）

类上两个注解（:16-17）：`@Slf4j` 提供日志对象，`@RestControllerAdvice` 使它成为「所有 Controller 的异常出口」。（框架行为）Spring MVC 的任何请求处理抛出的异常，若没人接，会按类型匹配这里的 `@ExceptionHandler` 方法；更具体的类型优先于父类。七个分支一张表：

| 异常类型 | HTTP 状态 | 响应体 | 行号 |
|----------|-----------|--------|------|
| RateLimitedException | 429 + Retry-After 头 | fail(code, message) | :20-25 |
| BizException | code 在 [400,599] 用 code，否则 400 | fail(code, message) | :27-31 |
| MethodArgumentNotValidException / BindException | 400 | 第一个字段错误的 message | :33-43 |
| HttpMessageNotReadableException | 400 | "malformed request body" | :45-49 |
| MissingServletRequestParameterException | 400 | "{参数名} is required" | :56-60 |
| MaxUploadSizeExceededException | 400 | "uploaded file exceeds the size limit" | :66-70 |
| Exception（兜底） | 500 | "internal error" | :72-77 |

两个分支值得逐行看。BizException 分支（:27-31）：

```java
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException ex) {
        int status = ex.getCode() >= 400 && ex.getCode() <= 599 ? ex.getCode() : 400;
        return ResponseEntity.status(status).body(ApiResponse.fail(ex.getCode(), ex.getMessage()));
    }
```

状态码不盲信业务给的 code，先做 [400,599] 区间检查，越界一律 400。（推断）这是防呆：若某处写出 `new BizException(302, ...)`，直接拿去做状态码会发出重定向语义的失败响应，检查把损失压到 400。

兜底分支（:72-77）：

```java
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleOther(Exception ex) {
        log.error("unhandled error", ex);
        return ApiResponse.fail(500, "internal error");
    }
```

注意三件事。第一，对外只给 "internal error"，不泄漏 SQL、类名、堆栈。第二，`log.error` 的第二个参数是异常对象，日志里记完整堆栈供排查。第三，只有这个分支打全栈；其余 4xx 分支不打。客户端错误打栈只会刷屏（2.2 课展开这个纪律）。

返回体的两种写法在文件里并存：要自定义状态码或响应头时返回 `ResponseEntity<ApiResponse<Void>>`（:21、:28），状态码固定时直接返回 `ApiResponse<Void>` 加 `@ResponseStatus` 注解（:34、:47、:57、:67、:73）。（框架行为）两种最终效果一致，`@RestControllerAdvice` 里的返回体会照常走 Jackson 序列化。

### 5.5 参数校验链路（AuthController.java:23-31 加 CreateShortLinkRequest.java:19-24）

依赖来自 starter-validation（pom.xml:53-56），链路四步：

1. Controller 方法参数标 `@Valid`：`register(@Valid @RequestBody RegisterRequest request, ...)`（AuthController.java:29）。
2. DTO 字段上贴约束注解。CreateShortLinkRequest.java:19-24：

```java
    @NotBlank(message = "originalUrl required")
    @Size(max = 2048, message = "originalUrl too long")
    private String originalUrl;

    @Size(max = 128, message = "title too long")
    private String title;
```

（Java 语言知识）`@NotBlank` 拒绝 null 与纯空白，`@Size(max=...)` 限长。message 就是校验失败时用户看到的那句话，所以它们写成英文短句而不是异常名。

3. （框架行为）请求进来，JSON 先反序列化，然后 Hibernate Validator 逐字段检查，任一失败抛 `MethodArgumentNotValidException`，controller 方法体根本不执行。
4. 异常落到 GlobalExceptionHandler.java:33-43：

```java
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidation(Exception ex) {
        String msg = "validation failed";
        if (ex instanceof MethodArgumentNotValidException manv && manv.getBindingResult().getFieldError() != null) {
            msg = manv.getBindingResult().getFieldError().getDefaultMessage();
        } else if (ex instanceof BindException be && be.getBindingResult().getFieldError() != null) {
            msg = be.getBindingResult().getFieldError().getDefaultMessage();
        }
        return ApiResponse.fail(400, msg);
    }
```

`instanceof X manv` 是 Java 17 的模式匹配写法（Java 语言知识），判断类型顺便得到强类型变量。只取第一个字段错误是刻意简化，用户改一处重试再看到下一处。响应体走 5.4 表的第三行。

### 5.6 上传超限分支的前置条件（application.yml:13-17 加 GlobalExceptionHandler.java:62-70）

yml 侧把上传限制为单文件 256KB、单请求 512KB（注释 D-C3-25：只有 CSV 导入用上传）。handler 侧的 javadoc 写明为什么需要这个专门分支：multipart 解析器在请求进入任何 controller 之前就抛 `MaxUploadSizeExceededException`，没有这个分支时它会掉进兜底变 500，而「文件太大」明明是客户端错误。这是「漏进兜底变 500」的第二个真实案例（第一个见 :51-55 javadoc 里的缺参数案例，曾让 `GET /stats/breakdown` 不带 dim 参数时返回 500）。

## 6. 技术原理补充

**@RestControllerAdvice 的机制**（框架行为）。它注册了一个全局的 `HandlerExceptionResolver`。Spring MVC 处理请求时包了一层 try/catch，异常先找 controller 自己的 `@ExceptionHandler`，找不到再到 advice 类里按类型匹配。所以它对全部端点生效，业务类零侵入。注意它接不住「进入 MVC 之前」的异常（如过滤器里抛的），那些走容器的错误页，这是边界，模块 03 会再对照。

**异常当控制流的适用范围**（本项目实现加推断）。本项目用异常表达两类业务结果：资源不存在（404）、请求不合法（400/429）。路径短、栈不深，代价可控；好处是主流程代码没有层层 if 判断「失败了吗」。高频热路径（跳转）同样用它，说明作者判断异常开销在此流量下可接受（推断，无基准数字支撑，见 progress.md 对性能口径的约束）。

**code 与 HTTP 状态码的双轨**（本项目实现）。body 里的 code 携带业务语义（0 成功，4xx/5xx 对应状态），HTTP 状态码本身用于网关、监控、重试策略。两者大多数时候相等，由 5.4 第二行保证。

## 7. 可视化

- [系统架构图](../../diagrams/archify/html/00-system-architecture.html)（由并行图表任务产出）：任选一个 API 节点，本节五个类是它对外的响应层。
- [包结构图](../../diagrams/archify/html/03-package-map.html)（由并行图表任务产出）：common 包的 result/exception/util 三个子包。
- [跳转判定链 Mermaid 备用图](../../diagrams/mermaid/redirect-decision-chain.md)：图中任何一步失败，最终都汇入本节的异常映射表（404/410/429）。

阅读顺序：先包结构图确认 common 的横切位置，再读映射表，最后用跳转链图挑两个失败步骤追到对应分支。

## 8. 项目中的真实执行过程

走一遍完整的 429（创建短链被限流，代码 ShortLinkService.java:131-138）。

1. 用户 1 分钟内第 31 次调用 `POST /api/links`。
2. `create()` 开头向限流器申请配额，`decision.allowed()` 为 false。
3. 代码先给指标 `linkforge.ratelimit.rejected{entry=create}` 加一，再 `throw new RateLimitedException(decision.retryAfterSeconds())`（:136-137）。
4. 异常沿调用栈向上冒，controller 没有 try/catch，冒到 DispatcherServlet。
5. `GlobalExceptionHandler.handleRateLimited`（:20-25）命中，构造 429 响应，`Retry-After` 头填限流器算出的秒数，body 是 `{"code":429,"message":"too many requests, retry later","data":null}`。
6. 用户拿到 429，浏览器或网关可以按 Retry-After 决定何时重试。

再走一遍 400（校验失败）：客户端提交 originalUrl 为空 → `@NotBlank` 失败 → 抛 `MethodArgumentNotValidException` → :33-43 命中 → 400 加 `{"code":400,"message":"originalUrl required",...}`。两条路径业务代码都是一行 throw，响应拼装全部集中在 handler。

## 9. 为什么这样设计

- 响应收进两个壳。前端只学一种结构，错误提示只看 message，这是接口稳定性的最低保障。（推断：单一前端配套使用）
- 业务异常带 code 而不是只带 message。同一个「短链不存在」在详情接口是 404、跳转接口也是 404，code 让 handler 能机械映射，不用为每种消息写分支。
- 429 单独成类只为一个响应头。普通 BizException 挂不了 Retry-After，为它扩字段会污染所有业务异常，继承是这里的低成本正解。
- 4xx 不打栈、5xx 打栈、对外只给笼统文案。日志成本花在服务端故障上，客户端错误靠计数指标观察（2.2 课的日志纪律一节）。

## 10. 本节总结

- `ApiResponse{code,message,data}`，code=0 成功；`PageResponse` 五字段由调用方组装。
- `BizException(message)` 默认 400，`BizException(code,message)` 自定；`RateLimitedException` 固定 429 加 Retry-After 秒数。
- 七分支映射表背下来：限流 429、业务码越界回 400、校验与缺参和坏 JSON 和超限上传 400、其余 500。
- 校验链路：`@Valid` 加约束注解，失败在 controller 前抛出，handler 取第一个字段错误。
- 只有兜底分支打全栈；catch-all 是「漏网变 500」的最终解释处，两个真实案例都因此加了专门分支。

## 11. 课后练习

**练习 1（概念理解）**：`new BizException("名称太长")` 抛出后，HTTP 状态码与响应体各是什么？

答案解析：走单参构造器，code 默认 400（BizException.java:10-12）。handler 第二分支（GlobalExceptionHandler.java:27-31）检查 400 在 [400,599] 内，状态码 400，body `{"code":400,"message":"名称太长","data":null}`。

**练习 2（源码定位）**：业务代码写了 `new BizException(200, "奇怪的成功")`，最终状态码是什么？哪一行保证的？

答案解析：状态码 400。GlobalExceptionHandler.java:29 的区间检查把 [400,599] 之外的 code 统一按 400 处理，200 越界。body 里的 code 仍是 200（:30 原样透传），这印证了「状态码不盲信业务码」的设计。

**练习 3（执行过程）**：限流窗口 60 秒、Redis 返回 retryMs=1500，`Retry-After` 头的值是多少？给出计算式与源码行号。

答案解析：`min(windowSeconds, (retryMs+999)/1000)` = min(60, ceil(1500/1000)) = 2，头为 `Retry-After: 2`。计算在 RedisRateLimiter.java:55-56，使用在 GlobalExceptionHandler.java:23。上取整保证 1 秒内的重试提示不退化为 0。

**练习 4（调用链）**：上传一个 300KB 的 CSV 走到哪个分支？为什么不是兜底 500？

答案解析：multipart 解析在 controller 之前执行，256KB 上限（application.yml:13-17）触发 `MaxUploadSizeExceededException`，命中 GlobalExceptionHandler.java:66-70，返回 400 与 "uploaded file exceeds the size limit"。这个分支是专门补的，javadoc（:62-65）写明补它之前会掉进兜底变 500。

**练习 5（设计分析）**：如果删掉 GlobalExceptionHandler，ShortLinkService.java:759 的 404 会变成什么？说明了该类承担什么职责？

答案解析：异常冒出 controller 后无人处理，Spring 返回 500 并走容器错误页，前端拿不到统一 JSON，404 语义丢失。该类的职责是「把 Java 异常翻译成 HTTP 契约」，翻译表是 5.4 的七行映射。这也是为什么新增异常类型时第一反应应是来这里登记分支。

## 12. 与后续课程的联系

模块 03 会盘点全部 Controller，你会看到本节两个壳出现在每个方法签名里。`RateLimitedException` 的生产者是模块 09 的滑动窗口限流器，`MaxUploadSizeExceeded` 的场景在模块 06 的 CSV 导入。下一个共通组件是日志与客户端 IP 工具，见 [2.2 日志与工具类](02-logging-utils-clientip.md)。
