# 测试分层与 CI

优先级 P1，目标层级 L2（能写合格的单测与 IT），测试策略取舍与连接预算到 L3。

## 先说人话（初学者第一遍从这里读）

自动化测试回答一个问题：**「你怎么敢改代码？」**。没有测试时，改一行代码要靠人把所有可能受影响的功能手工点一遍；有测试时，一条命令把这套检查跑完。第一遍带走三件事：

1. **测试分两层**：单测（毫秒级，不起 Spring，不起容器，类名以 `*Test` 结尾）和集成测试（起真的数据库、Redis、RabbitMQ 容器，类名以 `*IT` 结尾）。名字后缀就是分层标记。
2. **不 mock 自己的基础设施**。mock（替身对象）测的是「你想象的依赖行为」，容器测的是「依赖的真实行为」。MySQL 的特殊 SQL 语法、Redis 的 Lua 脚本，替身模拟不出来。
3. **修 bug 的正确顺序是「红先」**：先写一个会失败的测试证明 bug 存在，再修代码让它变绿。红和绿都是证据。

本章最值得精读的两个真实坑：上下文缓存与连接预算（测试越写越慢、突然报连接耗尽），以及时区 fork（用测试环境重现一个只有特定时区才发作的 bug）。

## 学习目标

- 说清测试金字塔每层测什么，为什么快的测试应该多、慢的测试应该少。
- 会用 JUnit 5 写一个合格的单测：`@Test`、`@BeforeEach`、断言，能解释「测行为不测实现」为什么成立。
- 说清 mock 该用在什么地方、mock 过度的代价。
- 看懂本项目测试基建的每个决定：共享容器单例、动态属性、上下文缓存与连接预算、surefire 与 failsafe 的分离。
- 逐 step 读懂 `.github/workflows/ci.yml`，解释「红先」纪律为什么能防烂尾。

## 前置知识

[22-tools.md](22-tools.md)（Maven 生命周期与 Surefire/Failsafe 双插件）、[30-spring-core.md](30-spring-core.md)（Spring 容器是什么，本章的「上下文」就是它的一次实例化）、[注解、反射与动态代理](java/10-07-annotations-reflection-proxy.md)（JUnit 靠反射读注解）。

## 为什么要有自动化测试

先设想没有测试的项目。你给短链接服务加一个「密码保护」功能，改了 `ShortLinkService` 三个方法。改完你怎么确认跳转、统计、租户隔离没被改坏？只能手工把整个网站点一遍。功能到 30 个时，没人点得完，于是改坏的代码直接上线。自动化测试把这个「点一遍」变成机器执行的三种价值：

1. **回归网**：每次改动后重跑全部测试，昨天能用的功能今天坏了，几秒到几分钟内被发现。「回归」指以前好好的行为重新变坏。
2. **重构底气**：重构（不改行为只改代码结构）的前提是「改完确认行为没变」。测试就是那份确认。没有测试的重构是盲改。
3. **文档价值**：代码会过时，测试不会允许自己过时。测试一过时就会变红，逼人更新。所以「这个接口被限流时返回什么」这种问题，最可靠的答案在测试里。

代价也要说：测试代码本身要写、要维护、跑一遍要时间。本章后面所有设计（分层、共享容器、双插件）都是在压这份成本。

## 测试金字塔：快的测试多，慢的测试少

```
      ▲   E2E（整个部署形态跑一遍，最慢，最少）
     ▲▲   集成测试（真数据库、真 Redis，秒级到十秒级）
    ▲▲▲   单元测试（不起任何框架，毫秒级，最多）
```

| 层 | 测什么 | 依赖 | 本项目形态 |
|----|--------|------|-----------|
| 单元 | 单个类或纯函数的逻辑 | 无（或 mock 协作者） | `*Test`，15 个类 |
| 集成 | 模块协作 + 依赖的真实行为 | 真 MySQL/Redis/RabbitMQ | `*IT`，32 个类 |
| E2E | 部署后的完整系统 | 全环境起一遍 | 本项目没有 |

为什么是金字塔形，不是反过来的「顶多底少」？推演一遍反馈速度：单元测试不起框架，一个毫秒级，几千个也就几十秒，坏了直接定位到那个类；集成测试要起容器、起 Spring 上下文，一个几秒到几十秒；E2E 要整个环境，一次几分钟起。如果最重的检查都堆在顶层，跑一遍验证要小时级，你就不会每改一次都跑，测试就退化成摆设。所以每层的分工是：**逻辑对不对交给单测，部件接起来对不对交给集成测试，部署对不对交给 E2E**。

一个具体对照。本项目判断「短码是否合法」有两处测试：

- `ShortCodesTest`（`src/test/java/com/linkforge/link/support/ShortCodesTest.java`）：不起 Spring，直接 new 出逻辑调方法，毫秒级。它回答「随机码生成器本身的规则对不对」。
- `SlidingWindowRateLimiterIT`（`src/test/java/com/linkforge/common/ratelimit/SlidingWindowRateLimiterIT.java`）：起真 Redis 容器跑 Lua 脚本。它回答「这段 Java 和 Redis 协作后，窗口边界行为对不对」。

两层都有，且单测数量远多于集成测试，这就是金字塔。

## 一个好测试的标准：AAA 与测行为

先看问题。这段测试很难维护：

```java
// 片段（反例，不是项目代码）
@Test
void bad() {
    OrderService s = new OrderService();
    s.discount(100);
    verify(s).recalcPrice();          // 断言「内部调了 recalcPrice」
    assertEquals(90, s.cachedPrice);  // 断言内部缓存字段
}
```

初学者写这种测试通常有两个原因：一是想「测得细一点」，觉得连内部调用都验证了更保险；二是框架的替身验证工具（如 Mockito 的 `verify`）顺手就能写，就用了。它断言的是**实现细节**（内部方法被调用、内部字段的值），不是行为（调用方看得见的返回值和效果）。后果：明天你把「调 `recalcPrice`」重构成「在 `discount` 里直接算」，行为完全没变，测试却红了。**重构不应弄红测试**，这是判据：测试红，应当只发生在「行为真的变了」的时候。红了但行为没变，说明测试测错了东西，以后没人信测试，测试资产报废。

好测试有两个标准：

**标准一：Arrange-Act-Assert（准备-执行-断言，缩写 AAA）**。三段结构：准备输入和依赖，调用被测行为，断言可观察结果。下面是不依赖 JUnit 的最小可运行版，`check` 手工扮演断言：

```java
import java.util.function.IntUnaryOperator;

public class RedFirstDemo {

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    // buggy version: boundary written as > instead of >= (spend 100 yuan, get 10 yuan off)
    static int discountedBuggy(int price) {
        return price > 100 ? price - 10 : price;
    }

    // fixed version: boundary uses >=
    static int discountedFixed(int price) {
        return price >= 100 ? price - 10 : price;
    }

    // the test: asserts observable behavior (return value), never how it is computed
    static void testExactlyAtThresholdPays90(IntUnaryOperator impl) {
        check(impl.applyAsInt(100) == 90, "price 100 should cost 90, got " + impl.applyAsInt(100));
    }

    public static void main(String[] args) {
        // step 1: run the test against the buggy implementation -> red
        try {
            testExactlyAtThresholdPays90(RedFirstDemo::discountedBuggy);
            System.out.println("unexpected: buggy implementation passed");
        } catch (AssertionError e) {
            System.out.println("RED: " + e.getMessage());
        }
        // step 2: fix the implementation, the same test runs green
        testExactlyAtThresholdPays90(RedFirstDemo::discountedFixed);
        System.out.println("GREEN: price 100 costs 90");
    }
}
```

```text
RED: price 100 should cost 90, got 100
GREEN: price 100 costs 90
```

逐步读：`testExactlyAtThresholdPays90` 里，`check(...)` 之前是被测函数调用（Act），`check` 里的条件是断言（Assert），这里没有依赖要准备（Arrange 为空）。它断言的是「付 100 实付 90」这个**行为**，不管内部用三元表达式还是 if 还是查表。所以换成 `discountedFixed` 任何一个行为正确的实现，测试照样绿。

**标准二：一个测试一个行为，名字就是行为描述**。本项目测试名都是完整句子：`TenantIsolationIT` 里的 `crossUserAccessIsRejectedEverywhere`（跨用户访问处处被拒）。测试失败时，红的名字直接告诉你哪条保证被破坏了。

## JUnit 5：注解只是标签，框架靠反射读它

先说问题：`mvn test` 怎么知道哪个方法该跑？你写的 `void respectsLengthAndAlphabet()` 是包私有方法，没人调用它。答案是 JUnit 5（测试框架，项目用 `spring-boot-starter-test` 自带的版本）：你在方法上贴 `@Test` 注解，JUnit 启动时用**反射**（运行时读取类结构的 API，见[注解、反射与动态代理](java/10-07-annotations-reflection-proxy.md)）扫描测试类，把带 `@Test` 的方法逐个调用。注解自己不执行任何代码，标签是死的，读标签的框架才是活的。

下面用 40 行手写一个迷你 JUnit，把 `@Test` 和 `@BeforeEach` 的机制拆开看。`@BeforeEach` 的作用：每个测试方法跑之前先跑一遍它，让每个测试拿到干净状态（JUnit 还会为每个测试方法 new 一个新的测试类实例，防止测试之间共享字段）：

```java
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class MiniJUnit {

    @Retention(RetentionPolicy.RUNTIME)
    @interface Test {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface BeforeEach {
    }

    static class StackTest {
        Deque<String> stack = new ArrayDeque<>();

        @BeforeEach
        void freshStack() {
            stack.clear();
            System.out.println("  setup: fresh stack");
        }

        @Test
        void pushThenPopReturnsTheValue() {
            stack.push("a");
            stack.push("b");
            if (!"b".equals(stack.pop())) {
                throw new AssertionError("pop lost the value");
            }
            // stack still holds "a" here: residue the next test must not see
        }

        @Test
        void newStackIsEmpty() {
            if (!stack.isEmpty()) {
                throw new AssertionError("stack not empty after setup");
            }
        }

        void helperNotATest() {
            throw new AssertionError("this must never run");
        }
    }

    public static void main(String[] args) throws Exception {
        List<Method> tests = new ArrayList<>();
        Method before = null;
        for (Method m : StackTest.class.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Test.class)) {
                tests.add(m);
            } else if (m.isAnnotationPresent(BeforeEach.class)) {
                before = m;
            }
        }
        tests.sort((a, b) -> a.getName().compareTo(b.getName()));
        int passed = 0;
        int failed = 0;
        for (Method t : tests) {
            Object instance = StackTest.class.getDeclaredConstructor().newInstance();
            before.setAccessible(true);
            before.invoke(instance);
            try {
                t.setAccessible(true);
                t.invoke(instance);
                passed++;
                System.out.println("PASS " + t.getName());
            } catch (Exception e) {
                failed++;
                System.out.println("FAIL " + t.getName() + " -> " + e.getCause());
            }
        }
        System.out.println(passed + " passed, " + failed + " failed");
    }
}
```

```text
  setup: fresh stack
PASS newStackIsEmpty
  setup: fresh stack
PASS pushThenPopReturnsTheValue
2 passed, 0 failed
```

逐步推导：`main` 里反射扫出两个带 `@Test` 的方法和一个带 `@BeforeEach` 的方法；每个测试前先 new 新实例、调 `freshStack`，所以 setup 打印两次；`helperNotATest` 没有标签，从未被调用，输出里没有它抛的异常；`pushThenPopReturnsTheValue` 故意在栈里留下 `"a"`，下一个测试照样能过，因为 setup 清掉了残留。这就是「测试之间互不污染」的实现原理。测试抛出 `AssertionError` 就算失败，所以测试里的断言本质上就是「条件不满足就抛 AssertionError」。

### 断言：JUnit 风格与 AssertJ 链式

看真实项目写法。`ShortCodesTest`（`src/test/java/com/linkforge/link/support/ShortCodesTest.java`）：

```java
// 片段，摘自 ShortCodesTest.java
assertEquals(7, code.length());
assertTrue(ShortCodes.inAlphabet(code), "unexpected char in " + code);
```

`assertEquals(expected, actual)` 期望值在前、实际值在后，不等则抛 `AssertionError`；第二个参数是失败时显示的说明文字，帮你定位。`assertTrue` 同理。

JUnit 的断言还有一个兄弟形态：`assertThrows(BizException.class, () -> ...)`，lambda 抛出该类型异常则通过，否则失败。异常机制见[异常](java/10-06-exceptions.md)。项目在 `LinkValidationsTest`（`src/test/java/com/linkforge/link/support/LinkValidationsTest.java`）里大量使用它，测「非法输入必须被拒」。

另一形态是 **AssertJ 的链式断言**。AssertJ 也在 `spring-boot-starter-test` 带来的测试类路径里，写法是把断言串成一句：

```java
// 片段（AssertJ 风格演示，本项目测试未使用 assertThat）
import static org.assertj.core.api.Assertions.assertThat;

assertThat(code).hasSize(7).matches("[0-9a-zA-Z]+");
```

链式的好处：读起来像句子（「断言 code 有长度 7 且匹配该正则」），IDE 能对 `assertThat(code)` 的结果自动补全所有可用断言，类型不对直接编译报错。链式断言内部靠的正是泛型返回 `this`。两种风格都是框架行为，选一种统一即可。

诚实口径：本项目 47 个测试类全部用 JUnit 的 `Assertions`，`assertThat` 出现 0 次（已 grep 核对）。方法名已经把行为说清，消息参数补定位信息，这套组合够用。同理，JUnit 还提供 `@DisplayName("中文说明")` 给测试起展示名，本项目也一处未用，因为方法名本身就是行为句子。知道有这两个工具，读到用它们的项目不慌即可。

## Mock：替身什么时候该上，什么时候是自欺

先说问题。限流器 `RedisRateLimiter`（`src/main/java/com/linkforge/common/ratelimit/RedisRateLimiter.java`）的逻辑分两半：一半是「拿到 Redis 脚本的返回值后怎么判定」（Java 决策代码），一半是「Redis 上的 Lua 脚本本身怎么算」。现在你想测前一半：Redis 挂了要放行（fail-open，失败时开放）、重试秒数要向上取整、`max <= 0` 要直接拒绝。这些测试每次都起真 Redis 吗？不需要，因为被测的逻辑不依赖 Redis 怎么算，只依赖「Redis 返回了什么」。

**mock 就是在这里上场的替身对象**。`RedisRateLimiterTest`（`src/test/java/com/linkforge/common/ratelimit/RedisRateLimiterTest.java`，纯单测，不起 Spring）：

```java
// 片段，摘自 RedisRateLimiterTest.java
private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

@Test
void redisFailureDegradesOpenAndCountsError() {
    when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
            .thenThrow(new RedisConnectionFailureException("down"));

    assertTrue(limiter.tryAcquire(KEY, 2, 60));
    assertEquals(1, failOpenCount("error"));
}
```

逐段讲：`mock(StringRedisTemplate.class)` 用 Mockito（mock 框架，随 `spring-boot-starter-test` 提供）在运行时生成一个 `StringRedisTemplate` 的假子类实例，所有方法返回默认值；`when(...).thenThrow(...)` 教这个替身「execute 被调用时抛连接异常」——真实环境里很难随手制造 Redis 宕机，替身一秒造出来；然后断言真代码 `limiter.tryAcquire` 在替身抛异常时仍然放行且计数器加一。这里 mock 的对象是「基础设施的客户端包装」，替身回答的问题被严格限定为「Java 代码对替身返回值作何反应」。

**mock 过度的代价**：把替身当成真依赖用，测的就不是你的代码，是「你想象中的依赖」。推演一个真实反例：MySQL 的 `ON DUPLICATE KEY UPDATE`（撞键时改走更新）是数据库行为，你用 mock 只能写成「约定 execute 这个 SQL 返回 1」，而真正的语义（插还是更、影响行数是几、和唯一索引怎么互动）mock 全部说不了话。测试绿了，上线照样错，因为被验证的是 mock 脚本，不是数据库。判断标准一句话：**mock 测的应当是「我方代码对依赖返回值的处理」，凡是「依赖本身的行为」，换真件**。位置上：自己的基础设施（MySQL、Redis、MQ）用真容器；真正的外部第三方（比如某个付费短信网关）才用替身，因为你既没权限也没钱在测试里天天调它。项目里对「外部第三方」的替身案例是 `WebhookRetryClockIT` 用 JDK 自带的 `HttpServer` 在本机起一个假 webhook 接收端（后文时区 fork 一节详讲）。

所以同一个限流器在本项目里有两层测试：`RedisRateLimiterTest` 用 mock 测 Java 决策逻辑（毫秒级），`SlidingWindowRateLimiterIT` 用真 Redis 测 Lua 脚本的窗口语义。两层合起来才覆盖完整行为，这正是金字塔「每层各管一段」的实例。

## Testcontainers：让测试用上真依赖

先说问题。集成测试需要 MySQL。三种搞法：每个开发者本机装 MySQL 并约定端口和密码；共用一台开发库（数据互相踩）；或者用 H2 这类纯 Java 内存数据库「模拟」MySQL。前两种问题一大堆（环境不一致、数据互相污染），第三种看似省事，代价在后文。Testcontainers 给出第四种：**测试代码通过 Docker API 起一个一次性容器，用完销毁**。

工作原理分三步（框架行为）：

1. 测试代码声明容器（比如 `new MySQLContainer<>("mysql:8.0")`），Testcontainers 调 Docker daemon 把镜像拉起来。
2. 容器内部监听固定端口（MySQL 是 3306），Docker 把它映射到宿主机一个**随机空闲端口**。随机是因为测试并行跑或端口被占时，写死端口会冲突。`container.getMappedPort(3306)` 能查到实际映射。
3. 测试代码把这个「随机出来的地址和凭据」注入 Spring 配置，应用就像连一个普通数据库一样连容器。

第 3 步在本项目靠 `@DynamicPropertySource`（Spring 测试机制：上下文启动前回调一个静态方法，把配置项连同取值函数注册进去）。为什么比 H2 更可信，逐条推演：

- H2 是另一家数据库，有自己的 SQL 方言。MySQL 的 `ON DUPLICATE KEY UPDATE`、`utf8mb4_0900_bin` 二进制排序规则（项目 V4 迁移用来让短码区分大小写），H2 要么不认，要么「兼容模式」模拟，模拟必有误差。
- 依赖不止数据库。Redis 执行的是 Lua 脚本，RabbitMQ 有 confirm 确认时序，H2 根本没有这两个东西的替身。本项目三个依赖全部上真容器。
- 容器里跑的就是生产同款镜像（`mysql:8.0`），版本和语义与生产一致。测试红了就是真红。

代价也要说清：容器要拉镜像、要启动，一次几十秒，还要求执行环境有 Docker daemon。本机当前环境 Docker Desktop 29.9.2 与项目 Testcontainers 2.0.5 存在已知不兼容（环境事实，已在 `pom.xml` 注释中记录其来龙去脉），所以本机跑不了 `*IT`，集成测试实际跑在 CI 的 Linux runner 上（CI 一节详讲）。这也是「CI 是最终裁判」的一个现实理由：**测试必须能在验证发生的地方跑**。

## 本项目的测试基建：AbstractIntegrationTest

32 个集成测试类都要连 MySQL、Redis、RabbitMQ。如果每个类自己起三个容器，32 类 × 3 容器 = 96 次容器启动，套件根本没法看。项目把公共部分抽进基类 `src/test/java/com/linkforge/AbstractIntegrationTest.java`，核心三段：

```java
// 片段，摘自 AbstractIntegrationTest.java
protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
        .withDatabaseName("linkforge")
        .withUsername("linkforge")
        .withPassword("linkforge_pass")
        .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci",
                "--max-connections=500")
        .withStartupTimeout(java.time.Duration.ofMinutes(4));

protected static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379);

static {
    MYSQL.start();
    REDIS.start();
    RABBIT.start();
}

@DynamicPropertySource
static void containerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.rabbitmq.host", RABBIT::getHost);
    // ...
}
```

三个决定，逐个讲为什么：

**决定一：静态单例容器**。字段是 `static final`，容器在 `static {}` 块里启动。Java 语言规则：初始化一个类之前必须先初始化它的父类。所以第一个集成测试类（都是它的子类）被加载时，父类的静态块执行一次，三个容器起来；之后所有测试类共享这三个容器，全 JVM 只起一次。这是 Testcontainers 官方文档里的 singleton containers 模式。基类 javadoc 原话：套件只需要一个 Docker daemon，不需要宿主机上先跑一套 Compose。反例推演：如果声明成实例字段，每个测试类实例化时各起一套容器，32 个类 × 3 = 96 次启动，时间爆炸，连接数爆炸（下一节算账）。

比喻与边界：静态单例容器像「全考场共用的饮水机」，不是每人自带。失效边界：它假设所有测试能容忍「共用同一套依赖」；如果某个测试要改 MySQL 服务端参数，就不能共用了。本项目所有 IT 都共用，成立。

**决定二：`--max-connections=500`**。这是容器给 MySQL 服务端传的启动参数。它为什么不是默认值，牵出本章最贵的一课，下一节专讲。

**决定三：`@DynamicPropertySource` 注入动态地址**。容器端口是随机映射的，配置里写死不了，只能用取值函数在上下文启动时现场读（`MYSQL::getJdbcUrl` 是方法引用，注册的是「要值时再取」的函数，不是取好的值）。

配套细节三件（都来自该文件原文）：具体 IT 各自声明自己的 `@SpringBootTest` 和 `@ActiveProfiles("test")`，以便调整属性而不动基类；测试用的 `TestRestTemplate` 在每个测试前被换成 JDK HttpClient 工厂，因为默认工厂底层不支持 PATCH 方法（直接报 `Invalid HTTP method`）；`src/test/resources/application-test.yml` 只放非连接类配置（业务参数、MQ 行为），连接配置全由容器注入，注释原话：nothing here points at a host Compose stack。

## 上下文缓存与连接预算

先说问题。你给项目加第 N 个集成测试类，套件突然慢 30 秒，最后报 MySQL `Too many connections`。你没加什么重型逻辑，为什么？

**第一半：Spring 上下文缓存**。每个 `@SpringBootTest` 都要一个 Spring 上下文（就是 [30-spring-core.md](30-spring-core.md) 讲的那个 IoC 容器实例，装着全部 Bean）。Spring 的测试框架按「合并后的配置」做缓存：两个测试类配置完全相同，就共用同一个上下文；有任何差异，就新建一个。算「不同」的常见项（框架行为）：`@SpringBootTest` 的 `properties` 属性值、`@ActiveProfiles` 的 profile 集合、`@MockBean`/`@SpyBean` 注入的替身、`webEnvironment`。本项目每个 IT 类都带自己的属性（比如各自的限流 key 前缀 `linkforge:rl:it-iso:`），据代码推断，32 个 IT 类基本各占一个上下文。

上下文缓存不释放（缓存的全部意义就是复用），所以每个上下文常驻内存。每个上下文里是一整套 Bean，内存占用可观，这正是 [12-jvm.md](12-jvm.md) 讲的堆压力来源之一。

**第二半：连接预算**。每个上下文有自己的数据源连接池（Hikari，Spring Boot 默认连接池），池子默认 `maximumPoolSize=10`（Hikari 框架默认值），且上下文启动时要跑 Flyway 迁移、初始化 Mapper，连接是真开出来的。所有这些池子连的是**同一个共享容器**里的 MySQL。于是有一笔账：

> 上下文数 × 每池最大连接 ≤ MySQL 的 max_connections

本项目的真实心算（据代码推断的最坏情况，即每类一个上下文）：32 个 IT 类里 5 个显式声明 `spring.datasource.hikari.maximum-pool-size=4`（`ApiKeyIT`、`BloomDisabledAtStartupIT`、`SingleFlightRedirectIT`、`WebhookDeliveryIT`、`WebhookRetryClockIT`），其余按默认 10 算：27 × 10 + 5 × 4 = 290，小于 500，预算成立。

反过来验证事故：MySQL 默认 `max_connections=151`。15 个上下文 × 10 = 150，第 16 个上下文一开池就超。`pom.xml` 里这段注释记录了真实踩坑（A9 BloomRedirectIT 时期，套件多长出一个上下文就爆）：

```xml
<!-- 片段，摘自 pom.xml -->
<!-- Every cached Spring context holds its own Hikari pool against this shared
     container; the default max_connections=151 went into "Too many connections"
     once the suite grew one more context (A9 BloomRedirectIT). -->
```

修复两处：容器参数提到 500（治预算上限），新的 IT 尽量复用统一配置（治上下文数量）。你以后写集成测试时的动作顺序应该是：先想这个类能不能不引入新配置（新上下文），不能免的话，把它的池子调小，再心算一次总账。

时区 fork 的补充说明：`WebhookRetryClockIT` 在一个独立 JVM 里跑（下一节），它的池是 4，但那个 JVM 和主套件 JVM 串行执行（Maven 的两个 execution 先后跑），连接不叠加。

## surefire 与 failsafe：*Test 与 *IT 的分离

先说问题。单测要秒级反馈，集成测试要容器要几分钟，混在一起意味着「改一行代码想快速验证逻辑」也要等容器。解法：**用类名约定把两层拆开，用两个 Maven 插件分别执行**。机制细节 [22-tools.md](22-tools.md) 已讲过生命周期绑定，这里讲项目怎么用：

- 单元测试类名以 `*Test` 结尾，被 Surefire 插件（Boot parent 托管的默认测试插件）捡走，绑定在 `test` 阶段，不碰 Docker。
- 集成测试类名以 `*IT` 结尾，被 Failsafe 插件捡走，绑定在 `verify` 阶段。`mvn test` 只跑前者，`mvn verify` 两者都跑。名字后缀就是分层，命名纪律是这套机制的全部地基：名字写错，测试就会跑错层。

项目实际跑一次纯单测的真实输出（本机 JDK 21 + Maven 3.9.9 实测，`mvn -B test "-Dtest=ShortCodesTest"`）：

```text
[INFO] Running com.linkforge.link.support.ShortCodesTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.306 s -- in com.linkforge.link.support.ShortCodesTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Failsafe 在 `pom.xml` 里声明了两个 execution（同一插件的两份配置，Maven 会都执行）：

```xml
<!-- 片段，摘自 pom.xml -->
<execution>
    <id>default</id>
    <goals>
        <goal>integration-test</goal>
        <goal>verify</goal>
    </goals>
    <configuration>
        <excludes>
            <exclude>**/WebhookRetryClockIT.java</exclude>
        </excludes>
    </configuration>
</execution>
<execution>
    <id>webhook-tz-it</id>
    <goals>
        <goal>integration-test</goal>
        <goal>verify</goal>
    </goals>
    <configuration>
        <argLine>-Duser.timezone=Asia/Shanghai</argLine>
        <includes>
            <include>**/WebhookRetryClockIT.java</include>
        </includes>
    </configuration>
</execution>
```

读法：default 执行跑所有 `*IT` 但排除 `WebhookRetryClockIT`；webhook-tz-it 只跑 `WebhookRetryClockIT`，且给它一个 JVM 参数把时区钉在 Asia/Shanghai。为什么一个测试值得单独一份配置，时区 fork 一节讲。

`pom.xml` 还有一段测试依赖的版本对齐注释，值得读，因为它示范了「依赖冲突怎么读注释修」：

```xml
<!-- 片段，摘自 pom.xml -->
<!-- Testcontainers 2.0.5 expects newer commons-lang3 than the Boot 3.3.5 BOM's
     3.14.0 (NoSuchMethodError SystemProperties.getUserName on CI); lang3 is
     binary-compatible upward, so one override aligns every consumer. -->
<commons-lang3.version>3.18.0</commons-lang3.version>
<!-- Testcontainers 2.0.x: Boot-managed 1.x docker-java is rejected by Docker Desktop 29. -->
<testcontainers.version>2.0.5</testcontainers.version>
```

翻译：Boot 3.3.5 的依赖清单（BOM，统一管版本的清单）把 commons-lang3 钉在 3.14.0，但 Testcontainers 2.0.5 调用了 3.14.0 还没有的方法，CI 上直接 `NoSuchMethodError`（运行时才爆的错，因为 Java 按需链接方法）。修法是把 lang3 覆盖到 3.18.0，注释还交代了为什么敢覆盖：lang3 向上二进制兼容（新版本保留旧方法签名）。第二段注释解释为什么 Testcontainers 要从 Boot 管的 1.x 覆盖到 2.0.5：底层 Docker 客户端 docker-java 的旧版被新版 Docker Desktop 拒收。**读第三方库升级说明、把冲突修在版本清单上，是测试基建的日常**。

规模口径：README 徽章写 211 例全绿 = Surefire 单测 90 + Failsafe 集成 119 + 时区 fork 2。用静态数核对（已数）：15 个 `*Test` 类共 83 个 `@Test` 方法，加一个 `@ParameterizedTest`（`LinkValidationsTest` 里 7 组参数各跑一次）展开 7 例，83 + 7 = 90；32 个 `*IT` 类共 121 个 `@Test`，其中 ClockIT 的 2 个走独立 fork，121 - 2 = 119。对上了。这个核对顺带演示一件事：**测试例数不等于测试方法数**，一个参数化方法能贡献多个例。

## 时区 fork：让测试环境重现故障条件

先说问题。[51-rabbitmq-outbox.md](51-rabbitmq-outbox.md) 讲过一次真实故障：webhook 重试的退避时间用 JVM 时钟算，JVM 跑在东八区、MySQL 跑在 UTC，两个时钟差 8 小时，把 20 秒的退避拉成 8 小时。修复方案是调度时间一律用数据库时钟算（SQL 里 `DATE_ADD(NOW(), ...)`）。

现在要给这个修复写测试，遇到一个悖论：**在普通环境里，JVM 和数据库时区同源，bug 复现不出来，测试怎么红？** 新旧实现都能过。一个「怎么改都绿」的测试什么都证明不了。项目解法（`src/test/java/com/linkforge/webhook/WebhookRetryClockIT.java`）：给这个测试单独一个 JVM，用 `-Duser.timezone=Asia/Shanghai` 强制 JVM 时区，而 Testcontainers 的 MySQL 容器默认 UTC，**人为制造出当年事故的时区错配**。这就是「fork」（JVM 分叉，Maven 每个测试执行可以带自己的 JVM 参数独立起一个 JVM）在项目里的用法。

测试本体怎么写（逐段）：先用 `com.sun.net.httpserver.HttpServer` 在 `127.0.0.1` 随机端口起一个**进程内假 webhook 接收端**（前面 mock 一节说的「外部第三方用替身」），脚本化它第一次返回 500 制造失败重试；然后核心断言直接查库：

```java
// 片段，摘自 WebhookRetryClockIT.java
Long diffSeconds = jdbc.queryForObject(
        "SELECT TIMESTAMPDIFF(SECOND, NOW(), next_attempt_at) FROM lf_webhook_delivery "
                + "WHERE tenant_id = ? ORDER BY id DESC LIMIT 1", Long.class, tenantIdOf(token));
assertTrue(diffSeconds >= 0 && diffSeconds <= 20,
        "next_attempt_at must sit within [0, 20]s of the DB NOW(), got " + diffSeconds
                + "s — a JVM-clock schedule under UTC/UTC+8 skew reads ~28800s: row=" + row);
```

读法：`NOW()` 是 MySQL 的时钟，`TIMESTAMPDIFF` 算「下次重试时间距数据库现在多少秒」。修复后的实现在 20 秒内（测试 profile 的退避上限）；旧实现按 JVM 时钟算字面值，在这套环境里会读出约 28800 秒（8 小时）。断言区间 `[0, 20]` 把新旧实现一刀分开。异步等待没有用 `Thread.sleep`，用的是 Awaitility（随 `spring-boot-starter-test` 提供）：

```java
// 片段，摘自 WebhookRetryClockIT.java
await().atMost(java.time.Duration.ofSeconds(60)).untilAsserted(() -> {
    // 反复执行这里的断言，直到全部通过或超时失败
});
```

推演为什么它优于 sleep：`Thread.sleep(1000)` 是猜「1 秒后应该好了」，猜短了测试挂（flaky，时好时坏），猜长了全套件变慢；Awaitility 反过来，最多等 60 秒，期间轮询断言，条件一成立立刻过，只花必要的时间，超时失败还带着断言消息。测异步（消息消费、重试生效、缓存失效）都用这个模式，并发背景见 [13-concurrency.md](13-concurrency.md)。

这个设计的可迁移思想一句话：**测试环境必须能重现故障条件**。时区、时钟、字符集、区域设置，凡是「默认环境里复现不了」的 bug，把那个条件显式造出来钉进 CI。

## CI：每次 push 的强制验证

先说问题。测试只在某台开发机上跑，等于没有：那台机器没开、某人没跑、跑的环境和你不一样，都会让「测试全绿」变成一句没人验证的口号。CI（Continuous Integration，持续集成）把验证变成基础设施：**代码一进仓库，一台干净的机器自动跑全部测试，红就不许合**。项目用 GitHub Actions，配置 `.github/workflows/ci.yml` 全文只有 24 行：

```yaml
# 片段，摘自 .github/workflows/ci.yml
name: ci

on:
  push:
    branches: [main, 'feat/**', 'fix/**']
  pull_request:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - name: Cache Maven repo
        uses: actions/cache@v4
        with:
          path: ~/.m2/repository
          key: ${{ runner.os }}-m2-${{ hashFiles('pom.xml') }}
          restore-keys: ${{ runner.os }}-m2-
      - name: Build, unit and Testcontainers integration tests
        run: mvn -B verify
```

逐 step 讲：

1. **`on:` 触发条件**。push 到 `main`、任何 `feat/**`、`fix/**` 分支，以及所有 pull request，都触发一次流水线。意思是：每个功能分支的每次推送都被验证，PR 合并前也有独立一次验证。
2. **`runs-on: ubuntu-latest`**。GitHub 提供的一台干净 Linux 虚拟机，每次流水线从零装环境，杜绝「我机器上是好的」。关键的是：这个 runner 自带 Docker，所以 Testcontainers 的集成测试直接能跑，**不需要配置任何额外的服务容器**，被测应用自带依赖容器。
3. **checkout**。把仓库代码拉到虚拟机。
4. **setup-java**。装 Temurin 发行版的 JDK 17，和 `pom.xml` 的 `java.version=17` 对齐（本机用 JDK 21 也能跑，项目基准是 17）。
5. **cache**。缓存 `~/.m2/repository`（Maven 本地依赖仓库）。键里带 `hashFiles('pom.xml')`，即 pom 文件内容的哈希：pom 不变，键相同，缓存命中，几百兆依赖不用重下；pom 一变，键变，缓存失效重新下载。`restore-keys` 是兜底前缀，键没完全命中时也把旧缓存凑合用上，缺的增量补下。
6. **`mvn -B verify`**。一条命令做完：编译、Surefire 单测、打包、Failsafe 集成测试（起容器）、结果判定。`-B` 关掉下载进度条，CI 日志干净。这和 [22-tools.md](22-tools.md) 的结论一致：CI 跑的是 verify，本地日常开发最少也该跑 `mvn test`，合并前跑 `mvn verify`。

「main 恒绿」纪律由这条流水线执行：任何时刻 main 分支上的测试必须是绿的。红了不许合，红了立刻修或回滚。main 一红，所有人的合并都堵住，这就是全队最急的事，红不会被容忍过夜。

## 红先：让 bug 先有证据

先说问题。两种测试烂尾：一种是「测试永不绿」，写了测试一直失败，修不动，最后被删掉或注释掉，测试资产变垃圾；另一种是「测试永不红」，断言写反了或恒真，怎么改实现都绿，看起来在守护其实什么都没测。红先（red-first）工作流同时防住这两种。

流程四步：**先写一个会红的测试复现缺陷 → 亲眼看它红 → 修代码让它绿 → 红绿两个提交一起进 PR**。

为什么能防「永不绿」：你被要求**先看到一次红**，红的出现证明「这个测试能失败、且失败原因就是那个 bug」。之后修绿是一个小步收敛过程，修不动就意味着 bug 比想象的大，这个事实立刻可见，而不是烂在分支里。红提交是「bug 存在过」的证据，绿提交是「修复有效」的证据，一红一绿放进同一个 PR，评审者能看到缺陷和修复的完整对应。项目在 `docs/progress.md` 里记录了各轮「分支红史」作为流程痕迹。

为什么能防「永不红」：如果你写的测试一开始就是绿的，说明它要么测错了行为、要么根本没测到缺陷路径。红先逼你先证明「这个测试有红的能力」。本文前面 `RedFirstDemo` 演示的正是这个顺序：对有 bug 的实现跑出红，修边界，同一个测试变绿。

和 [40-mysql.md](40-mysql.md) 的「约束先行、然后处理失败」是同一个思想的两处应用：先让失败可复现、可观察，再修根因，而不是凭感觉改。

## 常见错误与误区

| 错误 | 后果 | 正确姿势 |
|------|------|----------|
| `Thread.sleep` 等异步 | 猜短了 flaky，猜长了慢 | 轮询 + 终值断言（Awaitility） |
| mock 自己的基础设施 | 验证的是 mock 脚本不是依赖 | 真 MySQL/Redis/MQ 用容器；mock 留给外部第三方 |
| 断言实现细节（内部方法调用、私有字段） | 重构即红，测试失去信用 | 断言可观察行为：返回值、HTTP 状态、落库结果 |
| 一个测试方法塞十种行为 | 失败无法定位 | 一个测试一个行为，名字写成行为句子 |
| 每个 IT 新造一套属性配置 | 上下文暴增，连接预算爆 | 尽量复用统一配置；新配置先心算预算 |
| 测试红了就重跑碰运气 | flaky 信用破产 | 按根因分类修：竞态、时钟、共享状态；修不了先禁用并立单 |

补一个本机会遇到的：本机 Docker 与 Testcontainers 版本不兼容（当前环境事实）时，`*IT` 跑不起来不是你的代码错，跑 `mvn test` 验证单测，`*IT` 交给 CI。

## 自测

1. 为什么容器声明为 `static final` 字段并在静态块里启动？
   答：Java 初始化子类前必先初始化父类，父类静态块在第一个 IT 类加载时执行一次，三个容器全 JVM 只起一次，之后所有测试上下文共享，摊薄启动成本。若为实例字段，每个测试类各起一套容器，时间和连接都爆炸。
2. `--max-connections=500` 不设会发生什么？
   答：每个缓存的 Spring 上下文有自己的 Hikari 池（默认 10 连接）连同一个 MySQL 容器，MySQL 默认 `max_connections=151`，约 16 个上下文就耗尽，新上下文拿不到连接，套件报 `Too many connections`（项目真实踩坑，见 pom 注释）。
3. 为什么「断言内部方法被调用了」是坏断言？
   答：它断言的是实现不是行为。重构（不改行为）会弄红它，红变成常态后没人再信测试。断言应面向可观察结果：返回值、HTTP 状态码、数据库落库值。
4. `RedisRateLimiterTest` mock 了 `StringRedisTemplate`，为什么不算「mock 自己的基础设施」？
   答：它测的是 Java 决策逻辑对「Redis 返回值」的处理（fail-open、取整、直接拒绝），不是 Redis 的行为本身；Lua 脚本的窗口语义由 `SlidingWindowRateLimiterIT` 用真 Redis 容器另测。两层分工，替身只替「行为不可控且不属于被测逻辑」的部分。
5. `WebhookRetryClockIT` 为什么必须单独 fork 到 Asia/Shanghai？
   答：它要证明的是「JVM(+8) 与 MySQL(UTC) 错配下退避仍正确」。普通环境两个时钟同源，bug 复现不出来，新旧实现都绿，测试无区分力。fork 人为制造故障条件，把旧实现钉成红。
6. CI 缓存键里 `hashFiles('pom.xml')` 起什么作用？
   答：pom 内容的哈希进缓存键。依赖不变则键不变、命中缓存，省去重新下载依赖；pom 一变（升降级）则键变、缓存失效重下，保证新依赖被真实解析。

## 面试追问

- **测试金字塔怎么取舍？** 逻辑给单测（毫秒级、定位准），协作与真实依赖语义给集成测试（容器、秒级到十秒级），部署形态给 E2E。取舍的锚点是反馈速度与可信度的乘积：单测快但测不到依赖语义，集成测试可信但贵，所以每层只测属于它的那一半，比例下宽上尖。追问「覆盖率多少才够」：关键路径的行为覆盖比百分比有意义，本项目口径是每个特性至少一个 IT 钉住行为。
- **上下文缓存与连接预算怎么算？** 同配置的 `@SpringBootTest` 共享上下文，任一属性/profile/`@MockBean` 差异都新建上下文，缓存不释放。每个上下文一个 Hikari 池（默认 10），乘上上下文数要对得起容器的 `max_connections`。本项目案例：32 个 IT 类、5 个池 4，最坏 27×10+5×4=290 < 500；默认 151 时约 16 个上下文就爆。
- **Testcontainers 比 H2 强在哪？什么时候 H2 也够？** 强在测的是生产同款依赖的真实语义（MySQL 方言、Redis Lua、MQ confirm），H2 只是方言模拟且替不了 Redis/MQ。H2 的位置：纯 CRUD 且没用数据库特有语义的小项目，或只想验证 SQL 映射不想付容器成本的场合。一旦用了 `ON DUPLICATE KEY UPDATE`、二进制排序规则这类方言特性，H2 的绿就不再可信。

## 下一步

- 集成测试的依赖容器与部署侧的 Compose 栈是什么关系，prod profile 怎么收紧，见 [61-docker-deploy.md](61-docker-deploy.md)。
- 时区 bug 的完整因果与修复规则，见 [51-rabbitmq-outbox.md](51-rabbitmq-outbox.md)。
- 测试钉住的每个设计决定，见 [82-linkforge-decisions.md](82-linkforge-decisions.md)。
- Maven 生命周期与双插件绑定，见 [22-tools.md](22-tools.md)。
