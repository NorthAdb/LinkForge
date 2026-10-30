# 异常体系与异常设计：失败是正常业务的一部分

优先级 P0，目标层级 L3（能讲清 checked/unchecked 的取舍并应对追问）。前置：[类与多态](10-02-oop.md)（异常也是类，继承链在这里首次实战）。

## 从一个问题开始

服务里要查一个用户。数据库可能连不上、用户可能不存在。失败信息怎么传给调用方？不用异常，最直觉的方案是返回值约定：

```java
// 方案 A：返回值当错误码
static final int OK = 0;
static final int NOT_FOUND = 1;
static final int DB_DOWN = 2;

int findUser(long id) { ... }
```

调用方拿到 `int`，必须记得检查。不检查呢？把错误码当用户 ID 继续往下算，错误**静默传播**，最后在无关的地方炸开。而且每个返回值都被"可能是错误码"占用，真正的数据没地方放，只能再加一个出参。C 语言靠 errno + 返回值就是这个模式，Java 的设计者认为它失败了，因为**编译器帮不上任何忙**：忘检查不报错，正常逻辑和错误处理搅在一起。

Java 的方案是给失败修一条**专用通道**：方法正常执行就返回值；执行不下去就抛出（throw）一个异常对象，沿调用栈向上传递，直到有人接住（catch）或程序终止。失败信息和正常数据走两条路，互不污染。

## 异常的家族树

所有异常类型的祖先是 `Throwable`，树长这样：

```
Throwable
├── Error                    // JVM 级严重故障：OutOfMemoryError、StackOverflowError
│                              程序设计上无法恢复，不要捕获
└── Exception
    ├── RuntimeException     // unchecked：NullPointerException、IllegalArgumentException……
    └── 其他 Exception        // checked：IOException、SQLException……编译器强制处理
```

分界线只有一条，而且**来自编译器的行为**：`RuntimeException` 及其子类、`Error` 及其子类是 **unchecked**（不受检），编译器不要求你处理；其余 Exception 子类是 **checked**（受检），编译器强制调用方做出回应。

强制到什么程度？看编译器怎么对待一个会失败的方法：

```java
import java.io.FileInputStream;

public class CheckDemo {
    public static void main(String[] args) {
        FileInputStream in = new FileInputStream("nope.txt");
    }
}
```

```text
编译错误：
error: unreported exception FileNotFoundException; must be caught or declared to be thrown
```

`FileInputStream` 构造器声明了 `throws FileNotFoundException`，FileNotFoundException 是 checked 异常，编译器要求每个调用点二选一：**catch 住**，或**自己也 throws 声明出去**。改成 `RuntimeException` 的子类（比如把构造器换成 `Integer.parseInt("x")` 抛的 NumberFormatException），同样的调用编译器一言不发。

"编译器是否强制"就是 checked/unchecked 的全部区别。除此之外两种异常的运行时行为完全一样：都能携带信息、都能带堆栈、都沿栈向上传。

## 处理异常：try、catch、finally

```java
public class TryDemo {
    public static void main(String[] args) {
        try {
            int n = 1 / 0;
        } catch (ArithmeticException ex) {
            System.out.println("caught: " + ex.getMessage());
        } finally {
            System.out.println("finally always runs");
        }
        System.out.println("main continues");
    }
}
```

```text
caught: / by zero
finally always runs
main continues
```

三段各管一件事：`try` 包住可能失败的操作；`catch` 接住指定类型的异常（多类型用 `catch (A | B ex)`）；`finally` 是**无论成败都执行**的清理段。catch 之后程序从中断处继续走，main 的最后一行能打印就是证据。

finally 的价值在"异常路径也要清理"这个保证上。资源不释放，连接和文件句柄就漏了。但手写 finally 容易出错，Java 7 起**优先用 try-with-resources**，实现 `AutoCloseable` 的对象在 try 括号里声明，编译器自动生成关闭代码（即使异常也关闭）：

```java
try (FileInputStream in = new FileInputStream(path)) {
    // 用 in
}
// 离开 try 块，in 的 close() 保证被调用
```

项目里到处是这个模式。判断某资源要不要手动关，看它是否实现 AutoCloseable，是就塞进 try 括号。

异常没被接住时会发生什么，以及 finally 和异常的先后：

```java
public class FinallyOrder {
    public static void main(String[] args) {
        try {
            innerDemo();
        } catch (ArithmeticException ex) {
            System.out.println("caught outside: " + ex.getMessage());
        }
    }

    static void innerDemo() {
        try {
            int n = 1 / 0;
        } finally {
            System.out.println("cleanup ran");
        }
    }
}
```

```text
cleanup ran
caught outside: / by zero
```

`innerDemo` 里没有 catch，异常向上传播，但传播前 finally 先执行了：第一行 cleanup ran 是 finally 打的，第二行是外层 main 接住异常打的。**清理的保证在异常路径上依然成立**，而且"finally 先执行、异常后传播"的顺序在输出里直接可见。异常真的没人接住时（比如 10-01 里的 NpeDemo），它会一路上穿 main，线程终止并打印堆栈。

### 反例：finally 里的 return 会吞掉一切

```java
public class Steal {
    static int steal() {
        try {
            return 1;
        } finally {
            return 99;
        }
    }

    public static void main(String[] args) {
        System.out.println(steal());
    }
}
```

```text
99
```

try 里的 `return 1` 被 finally 的 `return 99` **覆盖**。吞返回值只是小事，吞异常才是事故：如果 try 里抛了异常而 finally 里 return，异常无声无息消失，程序带着假成功继续跑。规矩一句话：**finally 只做清理，不写 return，不抛新异常**。同样危险的还有空 catch 块 `catch (Exception e) {}`，异常被吃掉后故障无痕迹，排查地狱的开端。

## 设计选择：业务异常该 checked 还是 unchecked

这是把语言机制变成工程判断的地方。先把两边的账算清楚。

**checked 的价值**：编译器强迫每个调用方面对失败。适合"调用方确实能做点什么"的场景——重试一次、换个备用路径、提示用户重新输入。`FileNotFoundException` 就是好设计：文件不在，调用方可以弹个选择框让用户重选。

**checked 的代价**，在典型的三层 Web 业务里看最清楚：

```java
// 如果业务异常做成 checked，三层签名全被污染
User loadUser(long id) throws BizException {           // Service
User loadUser(long id) throws BizException { ... }     // Controller 里调用还要再 try 或再 throws
```

中间的每一层要么处理（可它处理不了，业务失败的处置方式是"翻译成 HTTP 响应"，那是 Web 层统一的事），要么继续 throws。几十个方法签名都挂着 throws，读代码的信号被稀释，**"被迫声明"不等于"真的处理"**。更麻烦的是 Lambda：`Function<T, R>` 这类函数式接口的抽象方法不允许抛 checked 异常，checked 异常进不了 Stream 管道，这是结构性冲突。

**所以 Java 业务项目的主流选择是：业务失败用 unchecked（继承 RuntimeException），由全局异常处理器统一兜底**。注意这是"业务代码的惯例"，不是语言规则；文件、网络这类"调用方能针对性恢复"的底层 IO，标准库仍然用 checked，也仍然合理。

**为什么继承 RuntimeException 不等于可以被忽略？** 两个理由。第一，unchecked 逃过的是编译器，逃不过运行时：没人接住它就一路上炸穿 main，现场一目了然。第二，在 Web 项目里它会被全局处理器接住，这正是设计意图——`BizException` 的处置从来不是"忽略"，而是"集中翻译"。

## LinkForge 里的真实代码

### 异常链的三层设计

`src/main/java/com/linkforge/common/exception/BizException.java`：

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

四个设计点：继承 RuntimeException 走 unchecked 通道（上面的取舍）；`code` 字段把业务错误码和异常绑在一起，后面直接映射成 HTTP 状态；双构造器是重载，单参版本把公共逻辑委托给双参（`this(400, message)`，链到[类与多态](10-02-oop.md)的构造器委托）；`super(message)` 把 message 交给父类存，`getMessage()` 才有值。

`RateLimitedException`（同目录）再叠一层：

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

它是"限流"这一种业务失败的特化：code 固定 429，还携带 Retry-After 需要的秒数。**异常类型本身就是一种分类信息**：catch 方（或框架）看到类型就知道怎么处置。

### 全局异常处理器：异常怎么变成 HTTP 响应

`src/main/java/com/linkforge/common/exception/GlobalExceptionHandler.java` 是 unchecked 业务异常方案的另一半。核心方法：

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException ex) {
        int status = ex.getCode() >= 400 && ex.getCode() <= 599 ? ex.getCode() : 400;
        return ResponseEntity.status(status).body(ApiResponse.fail(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleOther(Exception ex) {
        log.error("unhandled error", ex);
        return ApiResponse.fail(500, "internal error");
    }
}
```

工作方式：`@RestControllerAdvice` 声明"这是给所有 Controller 的公共处理组件"，`@ExceptionHandler(BizException.class)` 声明"抛出的异常是 BizException（或其子类）时调我这个方法"。业务代码于是可以到处 `throw new BizException(404, "short link not found")`（`ShortLinkService.resolveTarget` 里就是这么抛的），翻译成响应的事一处搞定。**注意哪些是框架行为**：Advice 的注册、异常到方法的多态匹配、"更具体的异常类型优先"的挑选规则，都是 Spring MVC 的机制，不是 Java 语言行为；Java 语言提供的只是"子类异常对象可以传给父类类型参数"这一点（[类与多态](10-02-oop.md)）。最后那个 `handleOther(Exception)` 是兜底：没分类的异常记 error 日志（带完整堆栈）并统一返回 500，**对内保留全部证据，对外不泄露细节**。

异常在抛出处的使用也值得一看（`ShortLinkService.resolveTarget`）：

```java
if (!codeBloom.mightContain(code)) {
    meterRegistry.counter("linkforge.bloom.rejected").increment();
    throw new BizException(404, "short link not found");
}
```

抛出前先记指标再 throw，失败同时是数据。"短链不存在"在业务语义上是常态分支（用户乱输），所以它不是 checked IO 错误，而是 unchecked 业务异常，由上面那个处理器翻成 404 响应。一条异常从抛出到 HTTP 响应的完整旅程：Service 抛 → 沿调用栈上传（语言机制）→ Controller 没有 catch → Spring MVC 捕获并匹配 Advice（框架机制）→ 翻译成带 code 的 JSON 或 HTML（项目逻辑）。

## 常见错误清单

| 错误 | 实际发生什么 | 修正 |
|------|-------------|------|
| `catch (Exception e) {}` 空吞 | 故障无痕迹 | 至少记日志，或向上抛 |
| finally 里 return | 吞掉返回值甚至吞掉异常 | finally 只清理 |
| catch (Exception e) 一把抓 | 连不该管的 Error 级问题一起模糊分类 | 按类型 catch，窄优先 |
| 业务异常做成 checked | 签名污染，Lambda 兼容性断裂 | unchecked + 全局处理器 |
| 用异常做正常流程控制（循环里 try-catch 跳出） | 异常构造带堆栈开销，语义混乱 | 用返回值/Optional 表达常态分支 |
| 丢失原始异常：catch 后抛新异常不带 cause | 根因堆栈断链 | `new XxxException(msg, ex)` 带 cause |
| 捕获 Error | OutOfMemoryError 这类无法恢复，捕获无意义 | 不碰 Error |

"用异常控制正常流程"值得一句展开：`while (true) { try { x = parse(next()); break; } catch (...) {} }` 这种"靠异常退出循环"的代码，异常对象的构造（填充堆栈）是有成本的操作，而且读代码的人完全看不出这是常态路径。常态用返回值或 Optional（见[Lambda 与 Stream](10-08-lambda-stream-optional.md)），异常留给"不应该发生但必须兜住"的分支。

## 面试追问

**第一层：checked 和 unchecked 的区别从哪来？**
来自编译器：checked（RuntimeException/Error 之外的 Exception）强制调用方 catch 或 throws；unchecked 不强制。运行时两者行为一致。

**第二层：为什么业务项目选 unchecked 业务异常？**
三层业务里 checked 会污染每层签名、 lambda 函数式接口结构性不兼容，"被迫声明"换不来"真处理"。unchecked + @RestControllerAdvice 把翻译集中一处，业务代码保持干净。这是工程惯例不是语言规则，底层 IO 库用 checked 依然合理。

**第三层：全局异常处理器是怎么工作的？**
Spring MVC 的 DispatcherServlet 在 Controller 抛出异常后，按"异常类型最具体匹配"找到 @RestControllerAdvice 里的 @ExceptionHandler 方法执行，方法返回值被正常处理为响应。注册、匹配是框架机制；类型匹配能成立依赖 Java 多态（子类对象赋给父类参数）。

**第四层：finally 一定执行吗？有什么例外？**
正常路径和异常路径都执行；System.exit、JVM 崩溃、以及 finally 自身死循环/阻塞时到不了。finally 里 return 会吞异常，是高频事故点。

**第五层：BizException 若改成继承 Exception（checked），项目要改什么？**
所有 Service/Controller 方法签名加 throws 或就地 try-catch；GlobalExceptionHandler 依然接得住（多态匹配不关心 checked/unchecked），但代码被声明淹没，Lambda 封装处还要包一层。这正好反向解释了 unchecked 的选择。

## 自测

1. checked 与 unchecked 的判定标准是什么？`IOException`、`IllegalArgumentException`、`OutOfMemoryError` 各属哪类？
   答：是否被编译器强制处理。IOException 是 Exception 的 checked 子类；IllegalArgumentException 是 RuntimeException 子类（unchecked）；OutOfMemoryError 是 Error（unchecked，且不要捕获）。
2. try 里有 return，finally 还会执行吗？finally 里再 return 会怎样？
   答：会执行（finally 先于方法返回）。finally 的 return 覆盖 try 的返回值，若 try 抛了异常则异常被吞。
3. 为什么业务异常选 RuntimeException？
   答：checked 会在每层签名留下 throws、污染代码且 lambda 不兼容；业务失败的统一处置（翻译为 HTTP 响应）在 Web 层一处完成更合理。见 GlobalExceptionHandler。
4. `RateLimitedException extends BizException` 为什么可行？框架怎么区分对待它们？
   答：异常就是类，继承表达"是一种更具体的业务失败"。Spring MVC 按 @ExceptionHandler 声明的类型做多态匹配，更具体的优先。
5. 为什么不要用异常控制正常流程？
   答：异常对象构造要填堆栈、有开销；语义上异常应表达"不正常"。常态分支用返回值。

## 下一步

- 全局处理器里 `instanceof MethodArgumentNotValidException manv` 这种模式匹配写法：[Java 17 新语法](10-10-java17.md)。
- 异常如何配合 @Transactional 回滚（框架行为）：[Spring 核心](../30-spring-core.md)。
- 布隆过滤器抛 404 前的判定逻辑：[算法训练](../14-algorithms.md)。
