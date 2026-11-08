# 1.2 启动类、IoC 与依赖注入

## 1. 本节学习目标

1. 说清 `LinkForgeApplication` 这 16 行里每个注解的作用，以及应用从 main 方法到对外服务的启动主线。
2. 理解 IoC（控制反转）与 DI（依赖注入）到底是什么，用 LinkForge 自己的类举例。
3. 掌握本项目统一使用的构造器注入写法：`final` 字段加 `@RequiredArgsConstructor`，并用 ShortLinkService 的 17 个依赖做实例。
4. 记住 `@MapperScan` 收窄的教训：扫描范围过宽会把普通接口误注册成 MyBatis 代理。

## 2. 前置知识

- 1.1 课的依赖清单。本节讲的每个 Bean 背后的库都来自那份 pom。
- Java 接口与实现的区别。
- 注解（annotation）只是贴在类和方法上的标记，本身不执行任何逻辑；是某个框架读到标记后才做事。记住这句，后面的注解才不会显得玄。

本节首次出现两个核心术语。IoC 控制反转，指对象的创建和拼装交给容器（Spring），你的类只声明「我需要什么」，不再自己 new。DI 依赖注入，是实现 IoC 的手段，容器把依赖从构造器塞给你。

## 3. 本节涉及的源码

| 文件 | 职责 | 阅读顺序 | 为什么读它 |
|------|------|----------|------------|
| `src/main/java/com/linkforge/LinkForgeApplication.java`（16 行） | 启动类与 mapper 扫描 | 第 1 | 整个应用的组装根 |
| `src/main/java/com/linkforge/config/RedisConfig.java`（24 行） | 声明两个 Redis 基础 Bean | 第 2 | 最简单的 `@Bean` 工厂范例 |
| `src/main/java/com/linkforge/config/MybatisPlusConfig.java`、`SchedulingConfig.java` | 分页插件、开启调度 | 第 3 | 两个极小配置类 |
| `src/main/java/com/linkforge/config/RabbitMqConfig.java`（63 行） | MQ 拓扑与 JSON 转换器 | 第 4 | 展示 Bean 之间互相注入 |
| `src/main/java/com/linkforge/config/OpenApiConfig.java`（41 行） | OpenAPI 文档 | 第 5 | 可选依赖的教科书写法 |
| `src/main/java/com/linkforge/common/ratelimit/RedisRateLimiter.java`（105 行，看头部） | 限流器组件 | 第 6 | 扫描注册加构造器注入的典型 |
| `src/main/java/com/linkforge/link/service/ShortLinkService.java`（看 :78-127） | 短链核心服务 | 第 7 | 17 个构造器依赖的大实例 |

## 4. 类的定位

启动类在包结构的最外层 `com.linkforge` 下，这是刻意的。（框架行为）`@SpringBootApplication` 默认扫描启动类所在包及其全部子包，启动类放最外层，扫描就覆盖了整个项目。`config` 包是六个配置类的集中地，其中五个是「Bean 工厂」，`LinkForgeProperties` 是配置载体（1.3 课单独讲）。包的全貌见[包结构图](../../diagrams/archify/html/03-package-map.html)（由并行图表任务产出）。

## 5. 核心源码详解

### 5.1 启动类全文（LinkForgeApplication.java:7-16，省略 :12 空行）

```java
@SpringBootApplication
// Explicit mapper packages: a broad "com.linkforge" scan also proxied plain
// interfaces like ClickEventPublisher into mapper beans.
@MapperScan({"com.linkforge.auth.mapper", "com.linkforge.user.mapper", "com.linkforge.link.mapper", "com.linkforge.stats.mapper", "com.linkforge.apikey.mapper", "com.linkforge.webhook.mapper"})
public class LinkForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(LinkForgeApplication.class, args);
    }
}
```

`main` 方法只有一行。（框架行为）`SpringApplication.run` 按顺序做：创建应用上下文（容器）、启用自动配置、扫描组件、按依赖关系实例化全部 Bean、启动内嵌 Tomcat。结束前不监听端口，日志里的 "Started LinkForgeApplication" 就是装配完成的信号。`@SpringBootApplication` 是三个注解的合体（框架行为）：`@SpringBootConfiguration`（自身是配置类）、`@EnableAutoConfiguration`（按 classpath 自动配置，比如检测到 data-redis 就装配 Redis 连接工厂）、`@ComponentScan`（扫描注解标记的类注册为 Bean）。第三点解释了为什么 `config` 包的 `@Configuration` 类、common 包的 `@Component` 类都不需要在别处登记，容器自己找得到。

### 5.2 @MapperScan 收窄的教训（LinkForgeApplication.java:8-10）

注解下面那两行英文注释就是教训原文：曾经用宽泛的 `com.linkforge` 作为扫描范围，结果 MyBatis 把 `ClickEventPublisher` 这种和数据库毫无关系的普通接口也代理成了 mapper Bean。看这个接口本体（ClickEventPublisher.java:8-10）：

```java
public interface ClickEventPublisher {
    void publish(ClickEvent event);
}
```

（框架行为）MyBatis 的 mapper 机制是「接口动态代理」：`@MapperScan` 把列出的包里所有接口注册成 `MapperFactoryBean`，运行期生成代理，你对接口的方法调用被翻译成 SQL。这个机制无法区分「想当 mapper 的接口」和「碰巧长得像 mapper 的接口」，扫描范围必须人工收窄。本项目枚举六个 `*.mapper` 包（LinkForgeApplication.java:10；批次 2 的 RefreshTokenMapper 住在新包 `auth.mapper`，登记时只加了一个字符串——这就是收窄式枚举的好处：新包必须显式登记，永远不会被顺带扫进来），一个不多。教训的推广写法：注册类机制（mapper 扫描、组件扫描、事件监听）的范围越窄越好，宽范围的错误通常在运行期才炸。

### 5.3 @Bean 方法：手动声明的 Bean（RedisConfig.java:12-23）

```java
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    /** Shared listener container; C2 cache-bus subscribers register on it before it starts. */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory factory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        return container;
    }
```

（框架行为）`@Configuration` 类里的 `@Bean` 方法，返回值注册为一个 Bean，方法名默认就是 Bean 名字。方法参数不是你传的，是容器传的：容器看到参数类型是 `RedisConnectionFactory`，先备好这个 Bean 再来调用方法。这就是最朴素的依赖注入，而 `RedisConnectionFactory` 本身由自动配置提供。（本项目实现）javadoc 那句 "subscribers register on it before it starts" 是时序约定：缓存总线（C2）的订阅者要在容器启动前挂上这个共享 listener container，Bean 之间的装配顺序约束用注释固定。

### 5.4 另外四个配置类速览

- `MybatisPlusConfig.java:12-17` 唯一 Bean 是 `MybatisPlusInterceptor` 加 MySQL 方言分页拦截器，MyBatis-Plus 的分页不是自动的，插件必须显式注册。`SchedulingConfig.java:6-9` 是空类加 `@EnableScheduling`，开关在代码、容量（线程池大小）在 yml，分工见 1.3 课。
- `RabbitMqConfig.java:17-22` 定义六个常量字符串（交换机、队列、路由键、死信对应物），:24-55 用它们声明拓扑 Bean。重点看 :57-62：

```java
    @Bean
    public MessageConverter jacksonMessageConverter(ObjectMapper objectMapper) {
        // Spring's mapper has JavaTimeModule; the converter's default one cannot
        // round-trip LocalDateTime and dead-letters every ClickEvent.
        return new Jackson2JsonMessageConverter(objectMapper);
    }
```

（RabbitMqConfig.java:57-62）

参数 `ObjectMapper` 又是容器给的（Spring Boot 预配置的那个）。注释记录了一个真实事故：MQ 转换器默认自己 new 的 ObjectMapper 不认识 `LocalDateTime`，每一条点击事件都会序列化失败进死信队列。解法是复用 Spring 的 mapper。Bean 之间不是孤岛，这种「注入现成 Bean 而不是自己 new」的写法正是为了共享配置。

- `OpenApiConfig.java:18-24`：`ObjectProvider<BuildProperties>` 可选注入。1.1 课已讲过它的链路，本课要记住的是模式：依赖可能不存在时（IDE 裸跑没有 build-info 文件），用 `ObjectProvider` 的 `getIfAvailable()` 而不是硬注入。

### 5.5 组件扫描注册：RedisRateLimiter（RedisRateLimiter.java:22-35）

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisRateLimiter {

    private static final DefaultRedisScript<List> SLIDING_WINDOW_SCRIPT = new DefaultRedisScript<>();

    static {
        SLIDING_WINDOW_SCRIPT.setLocation(new ClassPathResource("redis/sliding_window.lua"));
        SLIDING_WINDOW_SCRIPT.setResultType(List.class);
    }

    private final StringRedisTemplate stringRedisTemplate;
    private final MeterRegistry meterRegistry;
```

三种注册方式对照：`@Configuration` 加 `@Bean` 手动造（适合第三方库的类）；`@Component`（及其衍生 `@Service`、`@RestController`、`@Repository`）贴在自家类上被扫描注册；自动配置兜底。（本项目实现）RedisRateLimiter 走第二条路。`@RequiredArgsConstructor` 是 Lombok 注解（Java 语言知识，编译期生成代码），为所有 `final` 字段生成一个构造器，上面两个字段的构造器等价于 `public RedisRateLimiter(StringRedisTemplate stringRedisTemplate, MeterRegistry meterRegistry)`。（框架行为）类只有一个构造器时，Spring 不需要 `@Autowired` 注解就直接用它注入。这三行注解加两个 final 字段，就是本项目全部注入写法。

### 5.6 大实例：ShortLinkService 的 17 个构造器依赖（ShortLinkService.java:78-116）

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class ShortLinkService {
```

（ShortLinkService.java:78-81，类声明）

```java
    private final ShortLinkMapper shortLinkMapper;
    private final LinkVariantMapper variantMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final LinkForgeProperties properties;
    private final ClickRecordService clickRecordService;
    private final RedisRateLimiter rateLimiter;
    private final MeterRegistry meterRegistry;
    private final CodeBloomFilter codeBloom;
    private final CacheBus cacheBus;
    private final SingleFlight<String, ShortLink> singleFlight;
    private final ShortLinkRowLoader rowLoader;
    private final UrlLocalCache urlLocalCache;
    private final ClickFilter clickFilter;
    private final PhishingPolicy phishingPolicy;
    private final LinkAccessTokens linkAccessTokens;
    private final TransactionTemplate transactionTemplate;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
```

（ShortLinkService 的字段声明段，18 个 final 依赖逐行）

18 个依赖按来源分六组，读者以后在别的服务里看到的依赖基本都落在这些组里。

1. MyBatis mapper 两个：数据库访问代理。
2. Spring 基础设施三个：`StringRedisTemplate`（Redis 客户端，来自 5.3 的 @Bean）、`TransactionTemplate`（编程式事务）、`MeterRegistry`（Micrometer 指标注册表）。
3. link 包 support 支持类九个：Bloom 过滤器、缓存广播、singleflight、行加载器、URL 本地缓存、点击过滤、钓鱼门禁、访问令牌、导入租约（批次 3/修复波 M5 新增的 `ImportIdempotencyLease`，6.3 讲）。
4. 跨包服务两个（stats 的点击记录服务与 common 的限流器）；配置载体一个（`LinkForgeProperties`，1.3 课展开）；安全一个（密码编码器，注意它写的是全限定名）。

为什么坚持构造器注入而不写字段注入？（框架行为加工程判断）对比看：

```java ignore-check
// 反例（示意）：字段注入，本项目不使用这种写法
@Service
public class BadExampleService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
}
```

字段注入的三个问题在这个项目里都是真实代价。第一，字段不能是 final，依赖随时可变。第二，单测必须靠反射或 Spring 容器才能塞依赖，而构造器注入直接 `new ShortLinkService(shortLinkMapper, ...)` 就能测。第三，18 个依赖写在构造器参数上很吓人，写在字段上容易无感膨胀；构造器把依赖规模变成显式清单，这 18 行就是这个类的「能力清单」。另外框架保证缺依赖时启动即失败（`UnsatisfiedDependencyException`），错误暴露在部署时刻而不是第一次请求。

### 5.7 不是注入的成员：自管生命周期（ShortLinkService.java:118-127）

```java
    private final ScheduledExecutorService evictor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cache-evictor");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    void shutdownEvictor() {
        evictor.shutdown();
    }
```

evictor 线程池没走注入，是字段初始化时自己创建的，配 `@PreDestroy` 在容器关闭时停掉。区分两类成员：容器管的依赖（构造器注入）和自己管的生命周期资源（线程、连接）。把后者也塞进构造器当成依赖注入是常见错误，因为容器根本没有这种 Bean。

## 6. 技术原理补充

**IoC 容器**（框架行为）。本质是一个存 Bean 的 Map 加一张依赖图。容器按拓扑序实例化：被依赖的先建。所以 `StringRedisTemplate` 一定先于 `RedisRateLimiter`（它注入了模板，RedisRateLimiter.java:34），`RedisRateLimiter` 一定先于 `ShortLinkService`（:105 注入了限流器）。你无法在字段初始化块里用「还没建好」的 Bean，容器会把这种环检测出来并报错。

**Bean 生命周期四步**（框架行为）。实例化（调构造器）→ 依赖注入（构造器参数在实例化时给）→ 初始化回调（如 `@PostConstruct`）→ 使用 → 销毁回调（`@PreDestroy`）。5.7 的 evictor 走到了第一步和最后一步。

**Lombok**（Java 语言知识）。注解处理器在编译期改写字节码，`@RequiredArgsConstructor` 生成构造器，`@Data` 生成 getter/setter/equals/hashCode/toString，`@Slf4j` 生成日志字段（2.2 课细讲）。IDE 需要装 Lombok 插件才不飘红，构建工具不需要额外步骤。

## 7. 可视化

- [系统架构图](../../diagrams/archify/html/00-system-architecture.html)（由并行图表任务产出）：找「短链服务」节点，它对应本节的 ShortLinkService；它旁边一圈支撑组件对应 17 个依赖里的 support 组。
- [包结构图](../../diagrams/archify/html/03-package-map.html)（由并行图表任务产出）：确认启动类在最外层、config/common 是横切包。
- [创建短链 Mermaid 备用图](../../diagrams/mermaid/create-flow.md)：看 ShortLinkService 拿着这 17 个依赖如何在创建流程里分工。

阅读顺序：先包结构图定位，再架构图看 ShortLinkService 的邻居，最后用 create-flow 图验证依赖列表。

## 8. 项目中的真实执行过程

以一次启动为例（dev profile，步骤细节含推断的已标注）。

1. JVM 加载 `LinkForgeApplication`，执行 main，`SpringApplication.run` 开始。
2. 自动配置根据 classpath 装配基础 Bean：`RedisConnectionFactory`、`DataSource`、`ObjectMapper`、`MeterRegistry` 等（框架行为）。
3. 扫描注册：六个 config 类、`RedisRateLimiter` 等全部 `@Component/@Service` 进入候选。
4. `@MapperScan` 注册六个 mapper 包的接口为代理 Bean。
5. 拓扑序实例化。片段示例：`StringRedisTemplate`（RedisConfig.java:12）→ `RedisRateLimiter`（拿到模板和 MeterRegistry）→ `ShortLinkService`（拿到 17 个依赖，构造器由 Lombok 生成）。
6. `@PreDestroy` 钩子登记，内嵌 Tomcat 起在 8080，调度线程池启动（SchedulingConfig 的开关）。
7. 容器就绪，第一个请求进来时所有 Bean 已在内存。

失败路径（框架行为）：若把 RedisConfig 的 `stringRedisTemplate` Bean 注释掉，第 5 步 `RedisRateLimiter` 找不到依赖，应用启动失败并打印完整的依赖链，比线上空指针好查得多。这正是构造器注入换来的早期报错。

## 9. 为什么这样设计

- 构造器注入加 final 全项目统一。不是个人口味，它同时买到不可变、可单测、依赖显式、启动期失败四样东西。
- `@MapperScan` 手工枚举六个包（批次 2 新增 auth.mapper）。注释写明宽扫描误伤过 `ClickEventPublisher`（LinkForgeApplication.java:8-9）。教训编码在结构里，后人改包名会撞见这行。
- config 集中一个包。查找「这个库是怎么被配置的」只需要看一处；Bean 声明散落在业务包里是这个项目明确避免的。
- `ObjectMapper` 复用 Spring 的（RabbitMqConfig.java:57-62）。一个事故注释解释了为什么不自己 new。

## 10. 本节总结

- `@SpringBootApplication` 三合一：配置类、自动配置、组件扫描；扫描范围由启动类位置决定。
- 注册方式三分：@Bean 手造、@Component 扫描、自动配置兜底。
- 注入方式全项目一种：final 字段加 `@RequiredArgsConstructor`，构造器由 Lombok 生成、Spring 自动使用。
- ShortLinkService 的 17 个依赖是这套写法的全景样本，六组来源。
- `@MapperScan` 必须收窄到 mapper 包，宽扫描会把普通接口变成 SQL 代理。
- 依赖可能缺席时用 `ObjectProvider`；线程这类自管资源不装成依赖。

## 11. 课后练习

**练习 1（概念理解）**：用自己的话解释「控制反转」。ShortLinkService 创建 `RedisRateLimiter` 的控制权在哪？

答案解析：传统写法 ShortLinkService 自己 `new RedisRateLimiter(模板, 注册表)`，控制权在使用方。IoC 之后控制权在容器：ShortLinkService 只声明需要它，实例由容器在启动时造好塞进来。好处是全项目共享单例、依赖关系集中可见、换实现只动配置。

**练习 2（源码定位）**：找出证明「宽扫描出过事故」的源码证据，说出被误伤的接口名。

答案解析：LinkForgeApplication.java:8-10，注释 "a broad \"com.linkforge\" scan also proxied plain interfaces like ClickEventPublisher into mapper beans"，被误伤的是 stats/mq 包的 `ClickEventPublisher`（ClickEventPublisher.java:8-10）。修复是显式枚举五个 mapper 包。

**练习 3（执行过程）**：容器创建 ShortLinkService 之前，哪些 Bean 必须已存在？至少列出五个并各给一个源码依据。

答案解析：任选五个即可：`ShortLinkMapper`（字段 :100）、`StringRedisTemplate`（:102，Bean 声明在 RedisConfig.java:12-15）、`LinkForgeProperties`（:103，1.3 课）、`RedisRateLimiter`（:105，它自己又依赖模板）、`MeterRegistry`（:106，Boot 自动配置）、`PasswordEncoder`（:116，Security 配置提供）。构造器注入要求全部参数就绪才能调用构造器。

**练习 4（调用链）**：把 `RabbitMqConfig.jacksonMessageConverter` 里的 `ObjectMapper` 参数换成方法内 `new ObjectMapper()`，会发生什么？

答案解析：答案在注释里（RabbitMqConfig.java:59-60）：Spring 的 mapper 注册了 JavaTimeModule，新 new 的没有，`ClickEvent` 里的 `LocalDateTime` 字段无法序列化，每条消息进死信队列。这题考两件事：Bean 复用的意义（共享定制配置）与事故注释的阅读价值。

**练习 5（设计分析）**：OpenApiConfig.java:19 为什么用 `ObjectProvider<BuildProperties>` 而不是直接 `BuildProperties buildProperties`？什么场景下后者会挂？

答案解析：`BuildProperties` Bean 只在 jar 里有 META-INF/build-info.properties 时才存在（1.1 课链路）。IDE 里直接跑 main 不经过 Maven 打包，该 Bean 缺席，硬注入会 `UnsatisfiedDependencyException` 启动失败。`getIfAvailable()` 返回 null，代码回退到 version="dev"（:24）。可选依赖永远用 ObjectProvider。

## 12. 与后续课程的联系

下一课 [1.3 配置体系](03-configuration-profiles.md) 讲 17 个依赖里 `properties` 这个是怎么填充的，以及 profile 如何改变它的内容。本节出现的 `RedisRateLimiter` 在模块 09 逐行拆解；ShortLinkService 的创建流程在模块 06 完整走；统一响应与异常（`@RestControllerAdvice` 也是一个被扫描的 Bean）见 [2.1 统一响应与全局异常](../02-common/01-unified-response-exceptions.md)。
