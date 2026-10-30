# 设计模式：从 LinkForge 的真实取舍学起

优先级 P2，目标层级 L2（能在项目里指出真实的模式实例并讲清取舍），过度设计判断到 L3。

先给你一个可以验证的事实：LinkForge 的业务代码里找不到 23 种 GoF 模式的清单，连一半都不到。这不是缺陷，是健康的信号。每个出现的模式形态都对应一个具体的痛点，而没出现的地方，是作者判断"不用更好"。本篇就按这个思路组织：每种形态回答四个问题，**没有它代码烂在哪、它的机制是什么、用它付了什么代价、什么时候不该用**。

前置知识：[类、封装、继承与多态](java/10-02-oop.md)（接口与多态是一切行为型模式的地基）、[Spring 核心](30-spring-core.md)（Bean 与代理，读单例和代理两节必需）。

## 先建立正确的读法：从症状出发，不背名词

模式的名字本质是一个沟通压缩包。你写出"用 Map 把枚举映射到处理器"，同事要读三行代码；你说"这是策略模式"，懂的人一秒对齐。压缩包只在双方都拆得开时才有用，所以重点是先把代码写对，名字是事后贴的标签。

正确的顺序是症状先行：

1. 构造调用点一长串位置参数，读的人不知道第 3 个参数是什么。想想静态工厂或建造者。
2. 一个判定有四种互斥结局，调用方要逐个处理。想想封闭结果集。
3. 打开事务、记日志这类代码在每个方法头尾重复。想想代理。

反过来（背了"策略模式"然后到处找地方套）是过度设计的主要来源。本章末尾有三个信号帮你刹住车，以及项目里"没用模式反而更好"的真实位置做正例。

## 学习目标

读完本篇你应该能：

1. 在 LinkForge 里指出至少五个真实模式形态，并说出每个的**代价**。说不出代价的模式不要写进简历。
2. 纠正两个高频混淆：sealed 加 instanceof 并不提供编译期穷举检查；Spring 的单例不是 GoF 单例。
3. 用三个信号判断一个模式该不该用，并举出项目里"没用模式反而更好"的位置。
4. 不背 23 种清单。其余 GoF 模式你只需要在本篇末尾的"一段带过"里混个脸熟，等真的遇到症状再回来查。

## 前置知识

- [类、封装、继承与多态](java/10-02-oop.md)：接口、多态、`static`、抽象类。本篇所有形态都建立在这些机制上。
- [Spring 核心](30-spring-core.md)：Bean 是什么、容器怎么装配、AOP 代理怎么工作。
- [Java 17 新语法](java/10-10-java17.md)：sealed 语法的细节在那篇讲过，本篇讲它在项目里的用途与限度。

## 静态工厂方法：给"怎么造对象"起名字

先看痛点。项目里分页结果的构造是这样的（`src/main/java/com/linkforge/link/service/ShortLinkService.java` 第 216 行）：

```java
return new PageResponse<>(records, result.getTotal(), result.getCurrent(),
        result.getSize(), result.getPages());
```

五个位置参数。读这行的人必须打开 `PageResponse` 的定义（`src/main/java/com/linkforge/common/result/PageResponse.java`），数清楚第 2 个参数是 total 还是 size。参数顺序错了编译器不管，两个 long 换个位置照样通过。

对比同项目另一组构造调用。`ApiResponse`（`src/main/java/com/linkforge/common/result/ApiResponse.java`）长得几乎一样，也是 code、message、data 三个字段，但对外多了三个静态方法：

```java
public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(0, "ok", data);
}

public static ApiResponse<Void> fail(int code, String message) {
    return new ApiResponse<>(code, message, null);
}
```

这就是**静态工厂方法**（static factory method）：一个 static 方法，返回本类（或本接口的实现）的新实例。全项目 24 处调用都写 `ApiResponse.ok(...)` 或 `ApiResponse.fail(...)`，没有一处直接 `new ApiResponse`（构造器只在工厂方法内部出现）。

用最小例子跑一遍两种写法的对比。完整文件如下，保存为 `StaticFactoryDemo.java`：

```java
public class StaticFactoryDemo {

    // 简化版响应对象：只关心"怎么构造"的可读性
    static class Resp {
        final int code;
        final String message;
        final String data;

        Resp(int code, String message, String data) {
            this.code = code;
            this.message = message;
            this.data = data;
        }

        static Resp ok(String data) {
            return new Resp(0, "ok", data);
        }

        static Resp fail(int code, String message) {
            return new Resp(code, message, null);
        }

        @Override
        public String toString() {
            return code + " " + message + " " + data;
        }
    }

    public static void main(String[] args) {
        Resp byCtor = new Resp(0, "ok", "link-1");  // 三个位置参数，0 和 "ok" 是什么含义？
        Resp byFactory = Resp.ok("link-1");         // 名字说了算
        System.out.println(byCtor);
        System.out.println(byFactory);
        System.out.println(Resp.fail(404, "not found"));
    }
}
```

```text
0 ok link-1
0 ok link-1
404 not found null
```

两行输出相同，差别全在读代码的人那一侧。`new Resp(0, "ok", "link-1")` 里，`0` 是错误码还是数量，`"ok"` 是消息还是状态，读的人要猜。`Resp.ok("link-1")` 没有猜的余地。运行命令：`javac --release 17 -encoding UTF-8 StaticFactoryDemo.java && java StaticFactoryDemo`（Git Bash 可用）。

静态工厂买到的三样东西：

1. **名字表达意图**。`ok` 和 `fail` 是两个动词，构造器只能叫类名。
2. **不变式集中一处**。"成功响应 = code 0 + message ok"这条规则只写在 `ok()` 里。改这条规则时改一个方法，而不是改 24 个调用点。
3. **可以返回共享实例**。构造器每次都必须 new 新对象，静态方法可以返回早就备好的那一个。项目里的真实例子：`TargetPolicy`（`src/main/java/com/linkforge/link/support/TargetPolicy.java`）定义了 `public static final Verdict ALLOW = new Verdict(Action.ALLOW, null)`，判定结果是"允许"时全项目共用这一个对象，因为 `Verdict` 是不可变的 record，共享绝对安全。

代价也要说清楚。构造器是语言级入口，IDE 和 javadoc 对它有专门展示；静态工厂藏在方法列表里，不点进类根本不知道有 `ok()` 可用。所以惯例是工厂方法名用约定词：`of`、`from`、`valueOf`、`ok`、`fail`，读者看到这些前缀会主动去找。另一个限制：静态方法内部无法用 `this`，它天生和实例无关，这只是写法约束，不是功能缺陷。

边界：字段两三个、没有需要保护的不变式、名字不添信息时，直接用构造器或 record 更省事。`PageResponse` 加一个 `of(...)` 工厂当然可以，但它没有不变式要保护，收益只剩命名。为每个类都配工厂是仪式，不是设计。

## 建造者：可选参数多到构造器装不下

第二个痛点来自设置 HTTP 响应头。项目在密码解锁成功后要下发一个 Cookie（`src/main/java/com/linkforge/link/controller/RedirectController.java` 第 59 行起，片段）：

```java
ResponseCookie cookie = ResponseCookie.from(linkAccessTokens.cookieName(code), result.cookieValue())
        .path("/r/" + code)
        .httpOnly(true)
        .sameSite("Lax")
        .maxAge(Duration.ofSeconds(linkAccessTokens.ttlSeconds()))
        .secure(request.isSecure())
        .build();
```

Cookie 有 name、value、path、httpOnly、sameSite、maxAge、secure 七八种属性，其中多数可选。用位置参数构造器写就是 `new ResponseCookie(name, value, path, ..., true, "Lax", ...)`，七八个参数一半是 boolean 和字符串，谁也读不清。用 JavaBean 式 setter 逐个设也不行：那样对象在构造过程中是半成品，中途任何一个引用都拿得到一个"还没设完"的可变 Cookie。

**建造者**（Builder）模式在这两种写法之间开了一条路：`from(...)` 先拿到一个中间对象（建造者），每个可选属性对应建造者上的一个方法，方法返回建造者自己（所以能链着写），最后 `build()` 产出不可变的目标对象。构造期间中间对象可变，构造完成后产品不可变，两个世界的好处都拿到。

项目里第二个真实用例在 RabbitMQ 配置（`src/main/java/com/linkforge/config/RabbitMqConfig.java`，片段）：

```java
return QueueBuilder.durable(CLICK_QUEUE)
        .deadLetterExchange(CLICK_DLX)
        .deadLetterRoutingKey(CLICK_DLQ_KEY)
        .build();
```

每个属性一行，"哪些参数被设置了"一眼扫完，这就是链式建造者相对位置参数的全部优势。

要如实说：这两个建造者都是框架提供的类，LinkForge 自己没有手写过建造者。判断标准一致：可选参数多（四五个以上）且产品要不可变，才值得写一个建造者类（一个字段对应一个链式方法，外加 build）。参数少的时候构造器或静态工厂更短，为一个两参数对象写建造者是纯样板。识别这个模式的价值在于：你在框架 API 里看到 `xxxBuilder` 和结尾的 `build()`，立刻知道它造出来的对象不可变、中间步骤随便跳过不需要的。

## 模板方法：骨架在上，差异在下

第三个痛点来自测试。项目有 31 个集成测试类，全部继承 `AbstractIntegrationTest`，每个都要连 MySQL、Redis、RabbitMQ。如果每个类自己起三个容器，套件启动时间没人受得了；如果每个类手写"连接配置 + 注册租户 + 建链接"的公共代码，改一处要同步 31 处。

项目的答案在 `src/test/java/com/linkforge/AbstractIntegrationTest.java`。固定部分写在父类：

```java
public abstract class AbstractIntegrationTest {

    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>(...);
    protected static final GenericContainer<?> REDIS = new GenericContainer<>(...);
    protected static final RabbitMQContainer RABBIT = new RabbitMQContainer(...);

    static {
        MYSQL.start();
        REDIS.start();
        RABBIT.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        // ... 其余容器坐标逐个注册
    }

    @Autowired
    protected TestRestTemplate rest;

    protected String registerForToken() throws Exception { ... }
    protected String createLink(String token, String originalUrl) throws Exception { ... }
}
```

（片段，完整文件见仓库。`MySQLContainer` 这类对象来自 Testcontainers 库，作用是启动一个真实的 MySQL 容器。）

执行过程是这样的。`static` 块在类被 JVM 初始化时执行一次，而 JVM 里一个类只初始化一次，所以三个容器整个测试进程只启动一份。`@DynamicPropertySource` 是 Spring 的机制：容器启动数据源前，把这个方法注册的键值对当作配置属性。于是每个具体的测试类只剩"差异部分"：声明自己的注解、写自己的用例，骨架代码一行不写。

教科书的模板方法模式比这个例子更严格，用最小代码跑一遍。保存为 `ReportTemplate.java`：

```java
public class ReportTemplate {

    abstract static class Report {
        // final 骨架：步骤顺序固定，子类只能填空格，不能改流程
        final String render() {
            return "== " + title() + " ==\n" + body() + "\n(total " + rows() + " rows)";
        }

        // 钩子：给默认实现，子类想换就换
        String title() {
            return "REPORT";
        }

        // 必填步骤：没有默认，子类必须实现
        abstract String body();

        abstract int rows();
    }

    static class LinkReport extends Report {
        @Override
        String body() {
            return "abc -> https://a.com";
        }

        @Override
        int rows() {
            return 1;
        }
    }

    static class EmptyReport extends Report {
        @Override
        String body() {
            return "(none)";
        }

        @Override
        int rows() {
            return 0;
        }

        @Override
        String title() {
            return "EMPTY";  // 换掉钩子的默认实现
        }
    }

    public static void main(String[] args) {
        System.out.println(new LinkReport().render());
        System.out.println(new EmptyReport().render());
    }
}
```

```text
== REPORT ==
abc -> https://a.com
(total 1 rows)
== EMPTY ==
(none)
(total 0 rows)
```

`render()` 是**模板方法**本尊：声明为 final，步骤顺序写死。`body()` 和 `rows()` 是必填的抽象步骤，`title()` 是带默认值的钩子。子类只能填空格，不能重排流程。输出里两个报告的骨架完全一致，差异只在步骤填充。运行命令同前。

这里要诚实区分：`AbstractIntegrationTest` 没有抽象方法，也没有 final 骨架方法，它不走"钩子方法"机制。它是同一思想的测试基建变体：固定的生命周期与工具在父类，差异由子类的注解声明。另外 Spring 的 `JdbcTemplate`、`RedisTemplate` 名字里的 Template 就是这个模式（回调变体），机制见 [Spring 核心](30-spring-core.md)。

代价。模板方法用继承实现复用，子类从此和父类绑死：每个测试类传递依赖 Testcontainers 和三个容器，想写一个完全不碰容器的轻量测试就不能继承它。父类的 protected 助手方法也可能被子类覆盖，骨架语义被悄悄改变。适用前提是"流程顺序稳定、步骤可变"，如果顺序本身经常变，先考虑组合（把步骤对象作为字段注入），别用继承。

## 判定结果用封闭集合：sealed 能保证什么、不能保证什么

看跳转链路的真实需求。`ShortLinkService.resolveTarget`（`src/main/java/com/linkforge/link/service/ShortLinkService.java` 第 368 行起）执行完限流、布隆过滤器、缓存、行状态、目标域名五道门之后，结局有四种：放行 302、拦截页 403、密码页、警告页。GET 请求和 HEAD 探测共享这条判定链，但对结局的处理不同（GET 在放行分支记点击，HEAD 永不记）。这四种结局必须是"可以被携带、被检查、被分别处理"的值，而不是靠异常类型或状态码去猜。

项目的形态是 sealed 接口加四个 record（`src/main/java/com/linkforge/link/service/RedirectOutcome.java`，完整 28 行）：

```java
public sealed interface RedirectOutcome {

    /** Jump: B2 classification and click recording happen on this branch only. */
    record Redirect(ShortLink link, String url) implements RedirectOutcome {
    }

    /** B9 hard block: 403 interstitial, no Location, no target echo. */
    record Blocked() implements RedirectOutcome {
    }

    /** B3: protected link without a valid unlock cookie — GET renders the unlock page,
     * HEAD answers 401. */
    record Locked(ShortLink link) implements RedirectOutcome {
    }

    /** B9 warn: 200 interstitial showing the escaped target; /r/{code}?lf_continue=1 proceeds. */
    record Warn(String code, String url, String asciiHost) implements RedirectOutcome {
    }
}
```

（片段。sealed 是 Java 17 的正式特性：声明"这个接口只允许这几个实现"，语法细节见 [Java 17 新语法](java/10-10-java17.md)。）

`RedirectController.render`（第 85 行起）逐个分支处理四种结局。**这里必须纠正一个广泛流传的错误说法**，很多资料（包括本文的旧版本）声称"sealed 保证新增一种结果时编译器逼你补渲染分支"。事实是：

- `render` 用的是 `if (outcome instanceof RedirectOutcome.Redirect redirect)` 这样的 if 链。**instanceof 模式匹配不提供穷举检查**，这是语言行为：漏了任何一个分支，编译照常通过。
- 有穷举检查的是 switch 的模式匹配，它在 Java 17 还是预览特性（要 `--enable-preview`），Java 21 才转正。项目基准是 17，用的是 if 链。
- 证据就在 `render` 自己身上。它的链尾是：

```java
throw new IllegalStateException("unknown redirect outcome " + outcome.getClass());
```

如果编译器真能穷举检查，这行运行时兜底就没有存在的必要。它的存在恰恰说明：新增第五种结果时，编译器不会拦你，漏处理的分支要到运行时才会炸在这行。

那 sealed 到底买到了什么？两条真实保证。第一，**实现集合封闭**：sealed 不写 permits 时，实现类型必须和接口在同一个文件里，包外的类想 `implements RedirectOutcome` 会被编译器直接拒绝。于是"四种结局"这个事实被语言固定下来，别人无法在别处偷偷加第五种。第二，**每个结局携带自己的数据**：`Redirect` 带 link 和 url，`Warn` 带展示用的域名，分支解构时各取所需，不用一个塞满 null 字段的大对象。

同样的形态在 `TargetPolicy` 里还有一份：`Action` 枚举（ALLOW、WARN、BLOCK）加 `record Verdict(Action action, Reason reason)`。域名判定返回一个封闭的 Verdict，而不是 true、false 或异常。

顺带回答"这里为什么不用策略模式"。域名判定的规则是一条固定顺序的线：先查黑名单，再查警告名单，再看 userinfo。会变的是名单**数据**（`PhishingPolicy` 用 volatile 快照热更新名单，机制见 [并发](13-concurrency.md)），不是判定**算法**。数据驱动就够了，引入一组策略对象是给不存在的变化点付钱。

代价与边界。新增第五种结局要同时改 sealed 文件（实现必须在同文件）和 `render`（编译器不帮你），这是真实的维护成本。404、410、429 这三类"错误"刻意不走这套封闭结果，留在异常体系里，这是项目逻辑（`RedirectOutcome` 的 javadoc 写明"404/410/429 stay exceptions"），不是语言规定。结果只有两种且语义显然时，boolean 或 `Optional` 更短；结果集预期快速增长、且每个分支行为差异巨大时，那才是策略模式的地盘。

## 策略模式：项目里为什么找不到教科书形态

**策略模式**（Strategy）的教科书定义：把一组可互换的算法各自封装成对象，调用方持有统一接口，运行时选用哪一个。先看最小例子怎么从 if-else 长成策略。第一版，保存为 `PriceIfElse.java`：

```java
public class PriceIfElse {

    enum Plan { FREE, PRO }

    static int price(Plan plan, int base) {
        if (plan == Plan.FREE) {
            return base;
        } else if (plan == Plan.PRO) {
            return base / 2;
        } else {
            throw new IllegalStateException("unknown plan " + plan);
        }
    }

    public static void main(String[] args) {
        System.out.println(price(Plan.FREE, 100));
        System.out.println(price(Plan.PRO, 100));
    }
}
```

```text
100
50
```

第二版把每个定价算法变成一个对象，放进 `Map`。保存为 `PriceStrategy.java`：

```java
import java.util.EnumMap;
import java.util.Map;

public class PriceStrategy {

    enum Plan { FREE, PRO }

    // 每个 Plan 对应一个可互换的定价算法
    interface Pricing {
        int price(int base);
    }

    static final Map<Plan, Pricing> STRATEGIES = new EnumMap<>(Plan.class);

    static {
        STRATEGIES.put(Plan.FREE, base -> base);
        STRATEGIES.put(Plan.PRO, base -> base / 2);
    }

    static int price(Plan plan, int base) {
        Pricing strategy = STRATEGIES.get(plan);
        if (strategy == null) {
            throw new IllegalStateException("no strategy for " + plan);
        }
        return strategy.price(base);
    }

    public static void main(String[] args) {
        System.out.println(price(Plan.FREE, 100));
        System.out.println(price(Plan.PRO, 100));
    }
}
```

```text
100
50
```

输出与第一版完全相同。`Pricing` 是只声明一个方法的接口，`base -> base` 是它的实现（lambda 是实现单方法接口的简写，见 [Lambda 与 Stream](java/10-08-lambda-stream-optional.md)）。`EnumMap` 是键为枚举的专用 Map 实现。查表代替了分支链，新增一个 Plan 时 if 版改一个方法，Map 版加一行 put。

两版都要诚实评价。对编译期写死的枚举，其实还有更直白的第三版：switch 表达式（Java 14 起正式，返回值版 switch 对枚举做穷举检查，漏一个分支编译错误）。`Map<枚举, 处理器>` 真正赢的场合是**运行时注册**：处理器不是代码里写死的，而是启动时收集（比如 Spring 容器把所有某接口的实现 Bean 按 key 装配成 Map），调用方按运行时的 key 取。LinkForge 没有这种需求。

所以本节的核心结论是：**在 LinkForge 的第一方代码里，你找不到教科书的策略模式**。项目对两类"分支问题"选了别的答案：

1. 分支少且稳定，直接写卫语句和 if。`ShortLinkService.resolveTarget` 的门序列、`ClickFilter.classify` 的请求头判定都是。`ClickFilter.SkipReason` 这个枚举（PREFETCH、PREVIEW_BOT）只是给指标打的结果标签，不是策略。
2. 需要"可替换的实现"时，用窄接口加单实现。`ClickEventPublisher`（`src/main/java/com/linkforge/stats/mq/ClickEventPublisher.java`）是一个只有一个方法 `publish(ClickEvent)` 的接口，唯一的生产实现是 `RabbitClickEventPublisher`（`src/main/java/com/linkforge/stats/mq/RabbitClickEventPublisher.java`）。发送方 `ClickOutboxRelay` 只依赖接口。javadoc 写明了理由：把 broker 客户端的细节隔离在接口后面，换实现不动 relay。

第 2 条值得多看一眼，因为它经常被误当成"策略模式"。它只是"面向接口编程"：接口的作用是让调用方不认识具体类，而不是管理一簇可互换算法。还有个反直觉的事实：它的测试没有用接口做替身，集成测试跑的是真实 RabbitMQ（测试注释原话：注入一个必然失败的 publisher 会被兄弟测试上下文的健康 relay 抢跑，所以走真实链路）。一个单实现接口依然立得住的理由必须写下来，本项目写在 javadoc 里；只凭"万一将来要换"就抽接口，是投机。

什么时候策略真的值得：分支会作为独立单元增长，每个分支有自己的依赖、自己的测试；或者处理器需要运行时注册。判据一句话：**如果新分支只是"多一行 else"，别建策略**。

## 单例：三个"只有一个"不是一回事

初学者最容易把"单例"当成一个东西，实际项目里有三种形态，唯一性的来源完全不同。

**第一种：static final 共享常量。** `TargetPolicy` 里的 `Config.EMPTY`（`src/main/java/com/linkforge/link/support/TargetPolicy.java` 第 42 行）：

```java
public record Config(Set<String> blockDomains, Set<String> warnDomains) {
    public static final Config EMPTY = new Config(Set.of(), Set.of());
}
```

（片段。）`Config` 是不可变 record，`EMPTY` 是全项目共用的一份"空名单"默认值。它**不是**单例模式：没有私有构造器，你可以 new 一百个 `Config`，没人拦你。它只是把一个安全共享的不可变值存进 static final 字段，让"没有配置"这个默认值不用每次重建。`Verdict.ALLOW` 同理。

**第二种：工具类根本不是单例。** `GatePages`、`LinkValidations`、`TargetPolicy` 都是"final 类 + private 构造器 + 全 static 方法"的形态。private 构造器封死 `new`，但这些类**压根没有实例**，它们只是静态方法的命名空间。把工具类叫单例是概念错误。

**第三种才是真单例，而且要分两个版本。** GoF 单例的定义：私有构造器 + 静态字段 + `getInstance()` 静态方法，用语言机制保证全程序只有一个实例。它有三个真实代价：依赖关系藏在静态调用里，调用方的签名看不出它用了什么；测试想替换它只有改全局状态一条路；并发初始化要自己操心（双检锁或枚举）。已核实的项目事实：`src/main/java` 里没有任何手写的 GoF 单例。

Spring Bean 的默认作用域也叫 singleton，但**它和 GoF 单例不是一回事**。Spring 的"单例"回答"每个容器造几个实例"，GoF 的"单例"回答"整个程序允许几个实例"。区别有三点，每一点都影响日常代码：

1. Bean 不需要私有构造器，就是普通类，容器调用普通构造器创建它。
2. Bean 没有全局访问点，谁需要谁声明注入，依赖关系写在构造器参数上，看得见。
3. "只有一个"由容器的作用域决定，不是语言焊死的。同一个 JVM 起两个容器就有两份实例。项目测试正是这么干的：Spring 测试上下文会缓存复用，但不同的配置组合产生不同上下文，每个上下文各有一套 Bean，共享同一组测试容器（这正是不用 GoF 单例的好处，见 [测试与 CI](60-testing-ci.md)）。

三种形态对照：

| 形态 | "唯一"由谁保证 | 怎么拿到它 |
|------|----------------|-----------|
| static final 常量 | final 字段引用固定，值不可变 | 直接引用字段，如 `Config.EMPTY` |
| 工具类 | 没有实例这回事 | 类名加静态方法 |
| Spring Bean 单例 | 容器作用域，每容器一份 | 构造器注入 |
| GoF 单例 | 语言机制焊死 | `getInstance()`，本项目没有 |

什么时候才需要 GoF 单例：没有容器的小程序、JDK 自身。那时的标准写法是枚举单例（单一元素的枚举天然防反射和反序列化攻击）。在 Spring 应用里，需要共享就注册 Bean，需要共享不可变默认值就 static final 常量，手写 GoF 单例没有正当位置。

## 观察者与发布订阅：把"应用就绪"广播出去

下一个真实需求。`CodeBloomFilter`（布隆过滤器，防缓存穿透的组件）需要在应用能接流量之前，把数据库里全部短码灌进过滤器（`src/main/java/com/linkforge/link/support/CodeBloomFilter.java` 第 44 行）：

```java
@EventListener(ApplicationReadyEvent.class)
public void load() {
```

（片段。）问题在于"谁在什么时机调它"。如果由启动代码手动调用 `bloomFilter.load()`，启动代码就要认识每一个"需要就绪后回调"的组件，以后每加一个这样的组件都要改启动代码；如果放在 `@PostConstruct`（Bean 初始化回调）里做，异常会导致 Bean 创建失败、应用起不来，而布隆过滤器播种失败恰恰不该挡启动。

项目的答案是 Spring 的事件机制。`ApplicationReadyEvent` 是 Spring Boot 在应用完全启动、开始对外服务时发布的一个事件对象。任何 Bean 的方法标注 `@EventListener(ApplicationReadyEvent.class)`，Spring 就会在那个时刻调用它。这是**观察者**（Observer）模式：发布者持有"一组监听者"的列表，事件发生时逐个通知，发布者不需要认识任何监听者的具体类型。据代码与 Spring 语义推断，这个组件选这个时点的关键理由是失败语义：`load()` 捕获一切异常并把过滤器退回 null 的 fail-open 状态（代码注释原话：半播种的过滤器不允许残留），播种失败降级为全走数据库，应用照常服务。

收益要看清楚：明天再加一个"就绪后预热缓存"的组件，只需在新组件上标同一个注解，启动代码和其他组件一行不改。发布者与监听者互不 import，依赖都指向事件基础设施。

代价同样要看清楚，"零直接依赖"不是免费午餐：

1. 调用边从代码导航里消失了。搜 `load(` 找不到谁调用它，必须全局搜注解。静态代码的可发现性换了解耦。
2. 多个监听者的执行顺序默认没有承诺。监听方法在发布线程上同步执行（框架行为），一个监听者慢，所有监听者一起等。
3. 异常语义要想清楚。事件监听者抛异常会影响发布流程，所以本项目让 `load` 自己吞掉全部异常并保持降级状态，这是项目逻辑不是框架恩赐。

概念延伸到消息队列：RabbitMQ 的交换器和绑定是**跨进程**的发布订阅，Spring 事件是**进程内**的。进程内事件和方法调用一样同步可靠，但应用一停事件就没了；MQ 有 confirm、持久化、重试，投递语义完全不同。这条线在 [RabbitMQ 与 Outbox](51-rabbitmq-outbox.md) 全文展开，本篇不重复。

边界：两个组件本来就互相认识、调用顺序确定时，直接注入并调用。事件是"一对多、发布者不该知道接收者"时才值得，为两个固定的调用方发事件是把简单调用绕远。

## 代理：模式视角看 @Transactional

GoF 的**代理**（Proxy）模式：为控制对目标的访问，提供一个与目标同接口的包装对象，调用方拿到的是代理，调用先经过代理再到达目标。Spring 的事务就是这个模式的全自动版。

项目里的两处真实标注：`ClickRecordService.recordClick`（`src/main/java/com/linkforge/stats/service/ClickRecordService.java` 第 40 行）和 `AuthService.register`（`src/main/java/com/linkforge/user/service/AuthService.java` 第 63 行，注释写明"租户和首个用户在一个事务里创建"）。

执行视角走一遍：容器创建 Bean 时检查它有没有需要增强的方法；有的话放进容器的不是原对象，而是一个动态生成的代理对象。代理与原对象实现同样的接口（或继承同样的类），调用方无感。调用 `recordClick` 时，实际先进入代理：代理开启事务，然后转发给真实对象执行方法体，方法正常返回就提交，抛异常就回滚。业务类里没有一行事务代码，事务边界由注解声明、由代理执行。

把"手写包装类"自动化的是 JDK 动态代理：运行期生成实现同接口的类，方法调用统一先进处理器，处理器再决定前置、转发、后置。你在 [注解、反射与动态代理](java/10-07-annotations-reflection-proxy.md) 里手写过这个机制，[Spring 核心](30-spring-core.md) 的 AOP 一节讲了工业版细节（代理在 Bean 生命周期的哪一步换入、自调用为什么失效），本篇不重复。

模式视角只要带走两句话：其一，代理的价值在"横切规则只写一处"，事务、日志、限流这类规则如果写在每个方法里，改一次要动 N 个方法。其二，代价是间接层：同类内部 `this.method()` 不经过代理（事务失效的头号原因），调用栈变深，调试时要在代理帧和业务帧之间跳。一条规则只服务一个类时，直接写在方法里，别为它引入代理。

## 其余 GoF 模式一段带过

剩下的 GoF 条目按"项目里有没有真实形态"逐个交代，遇到症状再回来查，现在不用背。

**工厂方法与抽象工厂**：Spring 容器本身就是。每个 `@Bean` 方法是一个工厂方法，`BeanFactory` 是工厂的工厂。你每天在用的 `RabbitMqConfig.clickQueue()` 就是真实形态，机制见 [Spring 核心](30-spring-core.md)，不再展开。

**适配器**：把已有实现接到调用方期望的另一个接口上。项目直接消费的真实适配器是框架类 `Jackson2JsonMessageConverter`（`RabbitMqConfig` 里注册为 Bean）：它实现 Spring AMQP 的 `MessageConverter` 接口，内部把 Jackson 的 `ObjectMapper` 适配进 AMQP 消息世界。第一方代码没有需要适配的遗留接口，所以没有第一方适配器，不硬造例子。

**装饰器**：与代理同族，同接口层层包裹逐层加行为，经典在 JDK 的 I/O 流（`BufferedInputStream` 包 `FileInputStream`）。项目代码没有同接口包裹链；AOP 代理本质上是运行期生成的装饰器，上一节已讲。

**原型模式**：通过复制现有对象创建新对象。项目大量使用不可变 record，需要"变体"时直接构造新的，复制语义没有用武之地。

**状态模式**：`resolveTarget` 的门序列乍看像状态机，其实是有序卫语句：每个 if 的前提是前面所有 if 的否定，请求从头走到尾，不存在"状态对象在生命周期里迁移"这回事。别被门多吓到去套状态模式。

**生产者-消费者**：Outbox 表加 Relay 加 MQ 加 Consumer 的整条链是架构级形态，队列解耦生产与消费的速率。属于 [RabbitMQ 与 Outbox](51-rabbitmq-outbox.md) 的主题，本篇不重复。

**命令、备忘录、访问者、解释器、享元、组合、外观、中介者**：项目里没有真实形态，硬找例子教不会取舍。它们在普通 CRUD 后端出现频率低，是因为被语言和库吸收了：lambda 吸收了命令，数据库吸收了备忘录，Stream 吸收了迭代器。知道名字，等真的遇到再学。

## 反过度设计：不该用模式的三个信号

模式引入的复杂度是立刻支付的，收益往往在"将来"。所以判断的默认姿态是先不抽，三个信号任何一个出现就停手：

**信号一：今天只有一个实现，且看不到第二个形态。** 项目的所有 Service 都是具体类，没有接口：`ShortLinkService`、`ClickRecordService`、`AuthService`，直接注入直接用。这不是偷懒，是判断：没有第二种形态（可替换实现、测试替身、远程本地两版）时，接口是纯转发层。注意信号一不是"单实现接口有罪"，`ClickEventPublisher` 至今单实现依然立得住，理由记录在 javadoc；判据是**理由是否写下来且真实**，不是实现数量本身。

**信号二：变化点不存在。** 最硬的正例是 `LinkValidations`（`src/main/java/com/linkforge/link/support/LinkValidations.java`）：三个纯静态校验函数（URL 合法性、自定义码合法性、可渲染性），无状态、无配置、规则稳定。它用"final 类加 private 构造器加 static 方法"交付，不注入、不抽接口、不做策略。它的 javadoc 写明设计动机：纯函数可以脱离 Spring 直接单测。往这个类上套任何模式，得到的全是成本。同类的判断还有 `TargetPolicy`：变化的是名单数据（热更新快照），不是判定算法，数据驱动就够了。

**信号三：可读性损失大于收益。** 三个常见病灶：为两个分支建策略接口（读代码要先跳三个文件才知道发生了什么）；分层套娃（Service 只把参数原样转手给下一层，每层必须至少承担一个真实职责，比如校验、编排、事务边界）；为"将来可能"加配置开关（每件事做成开关的组合状态会爆炸，开关必须有默认值和文档）。

补一条经验法则（是启发式不是铁律）：**规则三**。同一段结构第三次重复才抽象。第二次就抽的前提是你确信两处必然同变，做不到这个确信就等第三次。

三个信号合成一句自问：**这个抽象今天有几个消费方？变化点是真的存在，还是我想象出来的？读代码的人为此多付出多少？**

### 顺带把 SOLID 收进同一套读法

SOLID 五条不用背定义，背"违反它的症状"就够，症状和本篇的反过度设计信号是一回事：

| 原则 | 违反的症状 | 项目里的真实落点 |
|------|-----------|------------------|
| 单一职责 | 上帝类，改一处崩三处 | Controller 薄、Service 厚、Mapper 专（[Spring Boot MVC](31-spring-boot-mvc.md)） |
| 开闭 | 加功能靠改老代码 | `TargetPolicy` 加新域名是改配置数据不是改判定代码；注意 sealed 结果集**不**兑现开闭，新增结局就是要改老代码 |
| 里氏替换 | 子类破坏父类约定 | 项目几乎没有继承层级，场景少；异常链 `RateLimitedException extends BizException` 只加字段不改行为 |
| 接口隔离 | 被迫依赖用不到的方法 | `ClickEventPublisher` 只有一个方法，调用方想依赖多点都依赖不了 |
| 依赖倒置 | 高层绑死低层细节 | `ClickOutboxRelay` 依赖 `ClickEventPublisher` 接口而非 RabbitTemplate |

分层的本质是依赖方向单一化：Controller 到 Service 到 Mapper，反向依赖即腐化开始；`support` 子包是本域私有工具的边界。守卫手段与破坏信号见 [架构全景](80-linkforge-architecture.md)。要诚实的一点：项目靠约定与 code review 守分层，没有引入 ArchUnit 这类架构测试，这是"知道但没做"的状态，面试可以主动说并给出方案。

## 症状速查表

面试或自查时从症状出发，落到项目实例：

| 症状 | 候选手段 | 项目落点 |
|------|----------|----------|
| 构造需要语义命名或共享实例 | 静态工厂 | `ApiResponse.ok`、`Verdict.ALLOW`、`GatePages.block()` |
| 可选参数多且产品要不可变 | 建造者 | `ResponseCookie.from(...).build()`（框架类） |
| 判定结局互斥、调用方逐个处理 | sealed 封闭结果集 | `RedirectOutcome`、`TargetPolicy.Verdict` |
| 一组测试共享昂贵基建 | 继承式骨架 | `AbstractIntegrationTest` |
| 就绪后回调，发布者不认识接收者 | 进程内事件 | `CodeBloomFilter` 的 `ApplicationReadyEvent` |
| 横切规则到处重复 | 代理 | `@Transactional` |
| 速率不匹配的两段逻辑互相拖累 | 队列解耦 | Outbox 到 Consumer 链（见 51） |
| 分支作为独立单元增长、需运行时注册 | 策略 | 项目无此需求，仅示例存在 |

## 面试追问

**Spring 用了哪些设计模式？** 逐个落到项目实例，比背名词高一档：代理（AOP 事务，`ClickRecordService.recordClick`）、单例（Bean 作用域，说清与 GoF 单例的三点区别）、工厂方法（`@Bean` 方法）、模板（`JdbcTemplate`，回调变体）、观察者（事件机制，`CodeBloomFilter` 的就绪播种）、建造者（`ResponseCookie` 等 API）。追问"你在自己项目里用了哪些"时，只说你讲得出代价的那几个。

**静态工厂和构造器怎么选？** 工厂赢在命名、不变式集中、可返回共享实例；构造器赢在可发现性和直接。惯例：有不变式要保护或需要语义命名时工厂（`ApiResponse.ok`），纯数据载体直接构造器或 record（`PageResponse`）。追问"工厂必须私有化构造器吗"，答不必，`ApiResponse` 两者共存，私有化只在要控制实例来源时才做。

**你怎么判断一个设计是不是过度设计？** 三个信号：只有一个实现且没有第二形态、变化点不存在、可读性损失大于收益；再加规则三。追问"给单实现抽接口一定是错的吗"，答不是罪，是要求理由真实并记录（`ClickEventPublisher` 的 javadoc）；追问"什么时候推翻自己的抽象"，答第二个消费方真实出现时再抽，早于此时抽的都是预支复杂度。

## 本章总结

本篇覆盖了 LinkForge 里真实存在的七个形态，每个的代价一句话回顾：静态工厂损失可发现性；建造者是样板换不可变；模板方法（测试骨架）绑死继承；sealed 结果集不提供 if 链穷举，兜底靠运行时异常；策略在本项目无处立足，窄接口单实现只是面向接口编程；Spring 单例是作用域不是 GoF 单例；观察者用可发现性换解耦；代理用间接层换横切集中。

决策顺序固定为：先描述症状，再用三个信号（单实现、无变化点、可读性损失）过滤，然后选形态，最后把代价写下来。本篇与其他章节的关系：[类与多态](java/10-02-oop.md)是所有形态的语言地基，[Spring 核心](30-spring-core.md)展开代理与 Bean 生命周期，[测试与 CI](60-testing-ci.md) 全解测试骨架，[RabbitMQ 与 Outbox](51-rabbitmq-outbox.md) 是发布订阅的跨进程版。

## 自测

1. 给 `RedirectOutcome` 新增第五种结局，编译器会强制你补全 `RedirectController.render` 的分支吗？
   答：不会。`render` 是 instanceof 的 if 链，Java 17 的 instanceof 模式匹配没有穷举检查；有穷举检查的 switch 模式匹配在 17 还是预览。兜底是链尾的 `throw new IllegalStateException("unknown redirect outcome ...")`。sealed 真正保证的是实现集合封闭（外部包不能新增实现），不是分支完备。
2. `TargetPolicy.Config.EMPTY`、Spring Bean、GoF 单例，三者的"唯一"分别由谁保证？
   答：`EMPTY` 是 static final 不可变常量，final 保证引用固定，值共享不是实例唯一；Spring Bean 单例由容器作用域保证，每个容器一份，测试多上下文就有多份，通过注入获取；GoF 单例用私有构造器加 getInstance 语言级焊死，本项目没有手写。
3. `ApiResponse.ok` 和 `PageResponse` 的构造器，为什么一个用工厂一个不用？
   答：`ApiResponse` 有不变式（成功等于 code 0 加 message ok）要集中保护，且 `ok`/`fail` 的命名消除位置参数歧义；`PageResponse` 是纯数据分页载体，没有不变式，工厂收益只剩命名。边界是收益大于成本才写工厂。
4. `CodeBloomFilter.load` 为什么挂在 `ApplicationReadyEvent` 上，而不是构造器或 `@PostConstruct`？
   答：就绪事件的时点是"应用开始服务之前"，且监听者自己 catch 全部异常退回 fail-open（播种失败降级为全查数据库）；若放 `@PostConstruct`，异常会导致 Bean 创建失败、应用无法启动。代价是调用边在代码导航里消失，多个监听者顺序无承诺。
5. `LinkValidations` 三个纯静态校验函数，为什么不抽接口、不做策略？
   答：三信号全不满足之外的正面判断：规则稳定（无变化点）、无第二形态、套上模式只会让它难用（现在可以脱离 Spring 直接单测）。纯函数工具类是"不用模式"的正当终点。

## 下一步

- 测试骨架的完整机制与上下文缓存：[测试与 CI](60-testing-ci.md)。
- 代理的工业版细节与事务失效场景：[Spring 核心](30-spring-core.md)。
- 跨进程发布订阅与可靠投递：[RabbitMQ 与 Outbox](51-rabbitmq-outbox.md)。
- 分层与包结构的全景：[架构全景](80-linkforge-architecture.md)。
- sealed 与 record 的语言细节：[Java 17 新语法](java/10-10-java17.md)。
