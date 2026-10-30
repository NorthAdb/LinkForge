# Spring 核心：IoC 容器、依赖注入与声明式事务

优先级 P0，目标层级 L2（读懂项目里的装配与事务边界），IoC 原理与事务失效到 L3。
前置：[注解、反射与动态代理](java/10-07-annotations-reflection-proxy.md)。那篇里你手写过 JDK 动态代理，本章的 AOP 与事务全是它的工业化版本。

## 学习目标

读完本章，你应该能做到三件事：

1. 画出 LinkForge 里以 `ShortLinkService` 为中心的 Bean 依赖方向图，说出每个依赖是怎么进来的。
2. 解释"依赖注入到底注入了什么"：不是注解，不是类，是容器里那份现成对象的引用。
3. 把 `@Transactional` 的执行过程讲到代理层：谁开事务、谁提交、谁回滚、自调用为什么失效。

## 先说人话

Spring 容器干的核心事情：**启动时把一批对象造好、装好、存起来，运行时你需要谁就给你谁的引用**。第一遍带走三句：

1. 你不 `new` 也能拿到依赖，是因为容器替你 `new` 了。`@Service`、`@Component` 这些注解只是"报名参加"的标记，标记本身不执行任何代码，干活的一直是容器。
2. "单例"在 Spring 里指"每个容器每个类型一份"，由容器保管。它不等于线程安全。
3. `@Transactional` 不是 Java 语法，是框架行为：方法调用先进代理，代理开事务、转发方法、按结果提交或回滚。它失效的头号原因是自调用绕过了代理。

## 从问题开始：没有容器时，对象图要自己拼

写一个跳转服务。`RedirectService` 要查短链，依赖 `CodeCache`；`CodeCache` 预热时也要查库，依赖 `UrlRepo`；`UrlRepo` 依赖数据库连接池。手工组装长这样：

```java
// 片段：手工组装。每 new 一个对象，都要亲手回答"它的依赖从哪来"
UrlRepo repo = new UrlRepo(pool);
CodeCache cache = new CodeCache(repo);
RedirectService service = new RedirectService(repo, cache);
```

三个代价：

1. **组装代码散落**。`RedirectService` 在十个地方被用到，这段组装就抄十份。哪天 `CodeCache` 加了个依赖，十处都要改。
2. **换实现要改源码**。单测里想用假的 `UrlRepo`（不碰数据库），只能改 `RedirectService` 的构造代码。
3. **生命周期没人管**。该全局共享的对象每处 `new` 一份；该关闭的资源（连接池、线程池）没人负责关。

把这三件事（创建、组装、保管）从业务代码里抽出去，交给一个专门的程序，这个程序就是 **IoC 容器**。IoC（Inversion of Control，控制反转）名字的由来就在这里：对象"什么时候创建、创建几份、依赖是谁"，控制权原来在你的代码里，现在反转给了容器。**IoC 是思想，DI（Dependency Injection，依赖注入）是实现手段**：你的类只在构造器参数里声明"我需要什么"，容器把现成对象的引用送进来。

打个比方：容器像公司后勤。你入职只填"我需要工位、电脑、门禁卡"，后勤按清单备齐送到工位。比喻的失效边界要清楚：后勤不会猜你要什么，你要什么必须在构造器签名里明说；后勤只负责"给"，不负责"怎么用"，业务逻辑还是你自己写。

下面用一个 50 行左右的可运行程序证明：IoC 容器的本质就是"递归地帮你 new，并把结果存起来"。

```java
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class MiniIocDemo {

    static class UrlRepo {
        String find(String code) {
            return "https://example.com/" + code;
        }
    }

    static class CodeCache {
        private final UrlRepo repo;

        CodeCache(UrlRepo repo) {
            this.repo = repo;
        }

        String load(String code) {
            return repo.find(code);
        }
    }

    static class RedirectService {
        private final UrlRepo repo;
        private final CodeCache cache;

        RedirectService(UrlRepo repo, CodeCache cache) {
            this.repo = repo;
            this.cache = cache;
        }

        String resolve(String code) {
            return "302 -> " + repo.find(code);
        }

        String fromCache(String code) {
            return "cache: " + cache.load(code);
        }
    }

    // 最小容器：Map 存单例，缺谁就递归造谁，依赖从构造器参数声明
    static class Container {
        private final Map<Class<?>, Object> singletons = new HashMap<>();

        @SuppressWarnings("unchecked")
        <T> T get(Class<T> type) {
            if (singletons.containsKey(type)) {
                return (T) singletons.get(type);
            }
            Constructor<?> ctor = type.getDeclaredConstructors()[0];
            Object[] args = Arrays.stream(ctor.getParameterTypes()).map(this::get).toArray();
            Object obj;
            try {
                obj = ctor.newInstance(args);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            singletons.put(type, obj);
            System.out.println("create " + type.getSimpleName());
            return (T) obj;
        }
    }

    public static void main(String[] args) {
        Container c = new Container();
        RedirectService s1 = c.get(RedirectService.class);
        RedirectService s2 = c.get(RedirectService.class);
        System.out.println("same instance: " + (s1 == s2));
        System.out.println(s1.resolve("ab12x9"));
        System.out.println(s1.fromCache("ab12x9"));
    }
}
```

```text
create UrlRepo
create CodeCache
create RedirectService
same instance: true
302 -> https://example.com/ab12x9
cache: https://example.com/ab12x9
```

逐步看发生了什么：

1. `main` 只要一个 `RedirectService`，容器看它的构造器要 `UrlRepo` 和 `CodeCache` 两个参数。
2. 先满足 `UrlRepo`：无依赖，直接 `new`，打印 `create UrlRepo`，存进 Map。
3. 再满足 `CodeCache`：它要 `UrlRepo`，Map 里已经有了，直接复用。然后 `new CodeCache`，打印。
4. 最后 `new RedirectService`，存进 Map。
5. 第二次 `get(RedirectService.class)`，Map 命中，返回同一个实例。所以 `s1 == s2` 是 `true`。

这就是"依赖方向图"的雏形：`RedirectService → CodeCache → UrlRepo`，箭头永远指向被依赖者。业务类互相不 `new`，只声明要什么；组装发生在容器里。Spring 的 IoC 容器比这个 demo 多做的事：靠注解扫描决定"管哪些类"（下一节）、生命周期回调（`@PostConstruct`/`@PreDestroy`）、作用域、以及给需要代理的 Bean 换成代理对象（AOP 一节）。核心思想没有超出这 20 行。

## 依赖注入：注入的到底是什么

注入的是**对象的引用**。容器造好 `UrlRepo` 后，`new RedirectService(repo, cache)` 把这份引用传进构造器，赋给 `final` 字段。此后 `RedirectService` 里的 `repo` 和容器里那份是同一个对象。

一个关键问题：既然 Java 没有任何"自动给字段赋值"的语法，字段注入为什么能在 Spring 里工作？用下面的可运行程序验证"脱离容器，注入根本不会发生"。

```java
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public class FieldInjectDemo {

    // 模仿 Spring 的 @Autowired：一个只有运行时可见的标记注解
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface Autowired {
    }

    static class UrlRepo {
        String find(String code) {
            return "https://example.com/" + code;
        }
    }

    static class RedirectService {
        @Autowired
        UrlRepo repo;

        String resolve(String code) {
            return repo.find(code);
        }
    }

    public static void main(String[] args) {
        RedirectService service = new RedirectService();
        System.out.println(service.resolve("ab12x9"));
    }
}
```

```text
运行异常：
java.lang.NullPointerException: Cannot invoke "FieldInjectDemo$UrlRepo.find(String)" because "this.repo" is null
	at FieldInjectDemo$RedirectService.resolve(FieldInjectDemo.java:25)
	at FieldInjectDemo.main(FieldInjectDemo.java:31)
```

推导：

1. 这是**语言行为**：`new RedirectService()` 执行无参构造器。没有任何代码给 `repo` 赋值，引用类型字段的默认值是 `null`（[类型与引用](java/10-01-types-and-references.md)）。
2. 调用 `resolve` 时对 `null` 解引用，JDK 15 起的帮助性 NPE 信息直接指出"因为 this.repo 是 null"。
3. 注解 `@Autowired` 自己不执行代码，这是 [java/10-07](java/10-07-annotations-reflection-proxy.md) 的结论。**框架行为**是：Spring 容器创建 Bean 时，用反射找到带 `@Autowired` 的字段，把自己持有的对象赋进去。脱离容器手工 `new`，没有人做这一步。

结论：**"注入"是容器在创建 Bean 时的一个动作，不是 Java 语法**。所以"加了 `@Autowired` 就能用"有个前提：这个对象本身必须由容器创建。这直接引出字段注入的问题。

## 三种注入方式，为什么推荐构造器注入

依赖写进类里有三种位置：

```java
// 片段：方式一，构造器注入（推荐）
@Service
public class AuthService {
    private final TenantMapper tenantMapper;
    public AuthService(TenantMapper tenantMapper) {
        this.tenantMapper = tenantMapper;
    }
}

// 片段：方式二，Setter 注入
@Service
public class ReportService {
    private Mailer mailer;
    @Autowired
    public void setMailer(Mailer mailer) { this.mailer = mailer; }
}

// 片段：方式三，字段注入（不推荐）
@Service
public class LegacyService {
    @Autowired
    private Mailer mailer;
}
```

字段注入的问题在上一节已经演示了第一个：脱离容器无法构造，依赖全是 `null`。另外两个：

2. 依赖藏在类内部。看字段列表才知道它要什么，构造器签名看不出来。
3. 纵容膨胀。加依赖只需再写一行字段，没有"构造器已经 8 个参数了"这种视觉警告，类会悄悄长成上帝类。

构造器注入被推荐（包括 Spring 官方文档的立场）有三个理由：

1. **测试友好**。单测里 `new AuthService(fakeMapper)` 就能跑，不需要启动 Spring 容器。测试见 [60-testing-ci.md](60-testing-ci.md)。
2. **字段可以是 `final`**。构造完成后依赖不再变，多线程环境下只读引用是安全的。字段注入做不到 `final`，因为赋值发生在构造器之外。
3. **问题尽早暴露**。容器造不齐依赖，启动直接失败，而不是等到第一次请求才 NPE。类依赖太多时，长长的构造器参数表会逼你审视设计。

Setter 注入的合理场景是"可选依赖"：没有它也能工作，有就增强。

### 项目案例：`@RequiredArgsConstructor` 是构造器注入的省模板形态

`src/main/java/com/linkforge/link/service/ShortLinkService.java` 的开头（第 54 到 78 行）：

```java
// 片段，引自 src/main/java/com/linkforge/link/service/ShortLinkService.java
@Slf4j
@Service
@RequiredArgsConstructor
public class ShortLinkService {

    private final ShortLinkMapper shortLinkMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final LinkForgeProperties properties;
    private final ClickRecordService clickRecordService;
    private final RedisRateLimiter rateLimiter;
    private final MeterRegistry meterRegistry;
    private final CodeBloomFilter codeBloom;
    private final SingleFlight<String, ShortLink> singleFlight;
    private final ShortLinkRowLoader rowLoader;
    private final UrlLocalCache urlLocalCache;
    private final ClickFilter clickFilter;
    private final PhishingPolicy phishingPolicy;
    private final LinkAccessTokens linkAccessTokens;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
```

逐项说明：

- `@RequiredArgsConstructor` 是 **Lombok 的注解，不是 Spring 的**。Lombok 是编译期工具：它为"所有没有初始化表达式的 `final` 字段"生成一个构造器，参数顺序与字段顺序一致。上面 14 个字段，生成的构造器就有 14 个参数。
- Spring 拿着这个构造器做构造器注入。整个类没有一个 `new` 依赖（LinkForge 业务逻辑）。
- 14 个依赖按角色分四类：**数据访问** 1 个（`shortLinkMapper`，MyBatis 为接口生成的代理 Bean）；**本项目其他服务** 1 个（`clickRecordService`）；**缓存与防护** 9 个（`stringRedisTemplate`、`urlLocalCache`、`codeBloom`、`singleFlight`、`rowLoader`、`clickFilter`、`phishingPolicy`、`linkAccessTokens`、`rateLimiter`）；**配置与观测** 3 个（`properties`、`meterRegistry`、`passwordEncoder`）。
- 注意 `ShortLinkService` 其实有第 15 个 `final` 字段 `evictor`（第 80 行），但它有初始化表达式（`Executors.newSingleThreadScheduledExecutor(...)`），**Lombok 不会把有初始化表达式的字段收进生成的构造器**（Lombok 行为）。它是类自己内部管理的线程池，不是依赖，不进依赖图；销毁时由 `@PreDestroy` 回调关闭（生命周期一节）。

读这个字段列表就是在读这个类的依赖方向图：

```text
ShortLinkController / RedirectController / StatsController
        │ 调用（HTTP 请求入口，链 31）
        ▼
  ShortLinkService
        ├──► ShortLinkMapper          （MyBatis 生成的 Mapper 代理 Bean，链 41）
        ├──► ClickRecordService       （本项目 stats 服务的 Bean）
        │        └──► ClickLogMapper / OutboxMapper（又是 Mapper 代理）
        ├──► StringRedisTemplate      （Boot 自动配置的 Redis 客户端）
        ├──► RedisRateLimiter         （项目自己的限流器 Bean）
        ├──► meterRegistry            （Boot 自动配置的 Micrometer）
        ├──► passwordEncoder          （SecurityConfig 里 @Bean 方法产出的 Bean）
        └──► rowLoader / codeBloom / singleFlight / ...（项目自己的支撑 Bean）
```

三个控制器调 `ShortLinkService`（已核实 `src/main/java/com/linkforge/link/controller/` 下的引用），`ShortLinkService` 再依赖数据访问与支撑设施。箭头单向向下，没有回头路，这个事实在循环依赖一节会变得重要。

## Bean：容器管理的对象

**Bean 是由 IoC 容器创建、装配、保管、并负责销毁的对象**。你写 `@Service`，容器扫描到它，就为它造一个对象并保管起来；别的 Bean 声明需要它，容器把这份引用注入过去。

两个常见误解先拆掉：

- Bean 和"JavaBean"不是一回事。JavaBean 是 getter/setter 命名规范；Bean 是"容器管的对象"，跟写法规范无关。
- Bean 的"单例"不是 JVM 全局唯一。singleton 作用域指**每个容器每个类型一份**。你自己 `new ShortLinkService()` 出来的对象与容器里的 Bean 毫无关系；同一个 JVM 起两个容器，也各有各的一份。

### 生命周期的五个关键节点

一个 Bean 从生到死，概念上有五步（框架行为）：

1. **实例化**：容器选好构造器，调用它拿到一个对象。构造器注入发生在这一步，所以构造器里**不能**依赖"还没注入的字段之外的东西"。
2. **属性填充**：容器把依赖注入进来（字段注入、Setter 注入发生在这里；构造器注入的 Bean 这步基本无事可做）。
3. **初始化**：依赖全部到位后，容器调用初始化回调。最常用的是 `@PostConstruct` 注解的方法，适合做"必须等依赖齐了才能做的准备"，比如读配置、建连接。
4. **使用**：业务代码从容器拿它、调它。
5. **销毁**：容器关闭时，调用 `@PreDestroy` 注解的方法，适合释放资源。

AOP 相关的一步藏在第 3 步之后：容器提供的扩展钩子 BeanPostProcessor（直译"Bean 后处理器"，一个能对每个新建 Bean 做最后加工的容器组件）会在初始化完成后检查这个 Bean 是否需要被代理；需要的话，**放进容器的就不是原对象，而是包着它的代理**。事务一节会用到这个事实。

项目里的两处实例：

- `src/main/java/com/linkforge/link/service/ShortLinkService.java` 第 86 到 89 行：`@PreDestroy void shutdownEvictor()`。`evictor` 是内部线程池，容器关闭时 Bean 的销毁回调把它停掉，避免 JVM 退出被非守护线程拖住（LinkForge 业务逻辑）。
- `src/main/java/com/linkforge/common/exception/GlobalExceptionHandler.java`：类上 `@RestControllerAdvice`（第 15 行）。它本身也是组件扫描注册的 Bean。Spring 启动时用反射读它的每个 `@ExceptionHandler` 注解，建立"异常类型到方法"的映射表；运行时 controller 抛出的异常冒泡到 Spring MVC 的异常解析器，按异常类型查表，反射调用对应方法。摘录两个方法（框架行为是路由，方法体是 LinkForge 业务逻辑）：

```java
// 片段，引自 src/main/java/com/linkforge/common/exception/GlobalExceptionHandler.java
@ExceptionHandler(RateLimitedException.class)
public ResponseEntity<ApiResponse<Void>> handleRateLimited(RateLimitedException ex) {
    return ResponseEntity.status(429)
            .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
            .body(ApiResponse.fail(ex.getCode(), ex.getMessage()));
}

@ExceptionHandler(BizException.class)
public ResponseEntity<ApiResponse<Void>> handleBiz(BizException ex) {
    int status = ex.getCode() >= 400 && ex.getCode() <= 599 ? ex.getCode() : 400;
    return ResponseEntity.status(status).body(ApiResponse.fail(ex.getCode(), ex.getMessage()));
}
```

业务代码于是可以全程只抛 `BizException` 这类业务异常，HTTP 状态码的翻译集中在这一个 Bean 里。这就是"对象交给容器"换来的组织方式：`GlobalExceptionHandler` 不需要被谁 `new`，它注册进 MVC 的异常处理链，等异常送上门。

### singleton Bean 里的可变状态

容器里只有一个实例，意味着所有请求共享它的字段。**单例保证的是"一份"，不保证"并发安全"**。字段只读（`final` 且指向不可变对象）没问题；有可变状态就要自己想办法，例如 `SimpleDateFormat` 这种非线程安全对象放在 singleton 字段里，并发使用必然出错。细节见 [13-concurrency.md](13-concurrency.md)。LinkForge 的服务 Bean 基本只持有只读依赖，状态放 Redis 和数据库（LinkForge 业务逻辑）。

## 把类交给容器的三条路

容器要先知道"管哪些对象"。有三条路，按你的代码要不要出现在装配现场分。

### 路一：组件扫描

在类上打 `@Component`，容器启动时从主类所在包向下扫描，把带标记的类逐个注册成 Bean。`@Service`、`@Repository`、`@Controller`、`@RestController`、`@Configuration` 在机制上都是 `@Component`（框架行为：元注解，扫描器一视同仁）。差异只在语义：看到 `@Service` 你知道这是业务层，看到 `@RestController` 你知道这是 HTTP 入口。选语义正确的别名，读代码的人不用点进类就知道它属于哪层。

主类 `src/main/java/com/linkforge/LinkForgeApplication.java` 决定了扫描根：

```java
// 片段，引自 src/main/java/com/linkforge/LinkForgeApplication.java
@SpringBootApplication
// Explicit mapper packages: a broad "com.linkforge" scan also proxied plain
// interfaces like ClickEventPublisher into mapper beans.
@MapperScan({"com.linkforge.user.mapper", "com.linkforge.link.mapper", "com.linkforge.stats.mapper", "com.linkforge.apikey.mapper", "com.linkforge.webhook.mapper"})
public class LinkForgeApplication {
```

`@SpringBootApplication` 包含组件扫描，从 `com.linkforge` 向下扫，所以入口类必须放在最外层包。`@MapperScan` 是特例：MyBatis 要求把**接口**也注册成 Bean（用 JDK 动态代理实现，见 [java/10-07](java/10-07-annotations-reflection-proxy.md)），它扫的不是类而是 Mapper 接口包。源码注释记录了一次真实事故：曾经宽泛地扫 `com.linkforge`，结果 `src/main/java/com/linkforge/stats/mq/ClickEventPublisher.java` 这种普通接口（消息发布的抽象，全项目没有任何类实现它的行为发生在启动后）也被套上 Mapper 代理注册成了 Bean，行为错乱。教训：扫描对扫到的类型是无差别装配的，机制不理解业务意图，扫描范围要精确到语义边界。

### 路二：`@Configuration` + `@Bean` 方法

对象不是你的类（第三方库造的），或者构造过程需要逻辑时，用配置类。`@Bean` 方法的返回值注册成 Bean，**方法参数就是依赖注入**。

`src/main/java/com/linkforge/config/MybatisPlusConfig.java` 全文 19 行，是标准形态：

```java
// 片段，引自 src/main/java/com/linkforge/config/MybatisPlusConfig.java
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
```

`MybatisPlusInterceptor` 是 MyBatis-Plus 的类，你没法去它源码上加 `@Component`，只能在自己的配置类里 `new` 好交出去。这就是"第三方对象我造"。

配置类还有第二种用法：不打 `@Bean`，只做特性开关。`src/main/java/com/linkforge/config/SchedulingConfig.java` 全文只有三行有效代码：`@Configuration` 加 `@EnableScheduling`。后者会让容器导入"开启定时任务"所需的一批内部设施（框架行为）。它没有任何 `@Bean` 方法，也是合法的配置类。

`src/main/java/com/linkforge/security/SecurityConfig.java` 两者都用（概述，细节见 [32-spring-security.md](32-spring-security.md)）：`@Bean` 方法 `securityFilterChain(HttpSecurity http)` 组装安全过滤器链，参数 `HttpSecurity` 由容器注入；另一个 `@Bean` 方法返回 `new BCryptPasswordEncoder()`。回看依赖图里 `ShortLinkService` 和 `AuthService` 注入的 `passwordEncoder`，源头就是这个方法。一个 `@Bean` 方法把密码加密器供给了全项目。

### 路三：自动配置

前两条路管你自己写的类。Redis 客户端 `StringRedisTemplate`、内嵌 Web 服务器这些第三方设施，Spring Boot 用自动配置包办。机制两步（框架行为，细节链 [31-spring-boot-mvc.md](31-spring-boot-mvc.md)）：

1. **读名单**。启动时，Boot 从 classpath 上每个 jar 的清单文件（`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`）里读出候选配置类名单。
2. **按条件取舍**。名单里每个配置类带条件注解。`@ConditionalOnClass` 说"类路径上有某个类我才装配"；`@ConditionalOnMissingBean` 说"你没自己定义我才装配"。条件不满足的候选被跳过。

所以项目引入 `spring-boot-starter-data-redis` 后一行配置不写，`StringRedisTemplate` 就能注入：名单里有 Redis 的自动配置类，类路径上又确实有 Redis 客户端，条件满足。想排除某个自动配置，用 `@SpringBootApplication(exclude = 某自动配置类.class)`。

三条路的分工一句话：**组件扫描管你写的类，`@Bean` 管你替第三方造的对象，自动配置管 Boot 替第三方造的对象**。

## AOP：把你手写过的代理工业化

[java/10-07](java/10-07-annotations-reflection-proxy.md) 里你手写过 20 行的动态代理：`Proxy.newProxyInstance` 生成替身，`InvocationHandler` 里写"拦截后做什么、要不要转发"。Spring AOP 就是这段代码的工业化版本，工业化在三点：你不用手写任何代理代码，Spring 启动时自动判断哪些 Bean 需要代理并批量生成；多个增强逻辑自动排成拦截链，按顺序执行；拦截的判定规则用注解或表达式声明。

先把五个术语逐个定义（每个都是名词解释，不神秘）：

- **横切关注点**：日志、事务、限流、鉴权这类逻辑。它们散布在很多业务方法里，和业务本身无关，"横切"过整个代码库。
- **切面（Aspect）**：切点加通知的打包单元。"对哪些方法（切点）、拦到后做什么（通知）"写在一起。
- **切点（Pointcut）**：一条表达式或规则，回答"拦截哪些方法"，例如"所有 `@Transactional` 注解的方法"。
- **通知（Advice）**：拦截后执行的代码。`@Before` 在目标方法前执行，`@After` 在后，`@Around` 前后都包住、还能决定要不要放行。
- **织入（Weaving）**：把通知安插进调用流程的动作。Spring 在**运行期**织入，手段就是生成代理对象；AspectJ 框架在编译期或类加载期直接改字节码。Spring AOP 是功能子集：只能拦截 Spring Bean 的方法调用。

代理选择规则（框架行为，记结论）：目标类有接口且配置允许时，可用 JDK 动态代理；Spring Boot 从 2.x 起默认 `proxyTargetClass=true`，即默认用 CGLIB 生成**子类**代理。CGLIB 的边界：`final` 类和 `final` 方法无法被子类重写，代理不了。无论哪种，代理的工作模式与手写版一致：**调用先进代理，代理决定增强什么、再转发原对象**。哪些 Bean 会被代理：标了 `@Transactional`、`@Async`，或命中了你自定义切面的切点的那些。

## @Transactional：从事务注解讲到代理层

### 问题：两条 INSERT 必须同生共死

先补两个词。**提交**（commit）：让这批数据库修改永久生效。**回滚**（rollback）：撤销这批修改，像没发生过。数据库保证同一个事务里的多条语句要么全部提交、要么全部不生效（原理见 [40-mysql.md](40-mysql.md)）。

LinkForge 的记录点击场景：用户点短链，服务要写一条点击明细（`lf_click_log`），同时往消息队列的发件箱表（`lf_outbox`）写一条待发事件。两条 INSERT 必须同生共死：只发明细不发生件，统计丢了数据；只发生件不发明细，消费端拿到对不上的账。这就是事务存在的理由。

### 执行过程：一步一步到代理层

`@Transactional` 是声明式事务：你在方法上贴注解声明"这个方法要事务"，执行由框架代劳。对照的手写版（编程式）长这样，业务方法里全是管道代码：

```java
// 片段：编程式事务，每个方法都要重复这段样板
conn.setAutoCommit(false);
try {
    // 业务 SQL
    conn.commit();
} catch (Exception e) {
    conn.rollback();
    throw e;
}
```

带 `@Transactional` 的方法被调用时，实际发生的事按顺序是（框架行为，逐步）：

1. **启动时**。容器为带 `@Transactional` 的 Bean 生成 CGLIB 代理，容器里放的是代理（BeanPostProcessor 在初始化后替换，生命周期一节提过）。哪些方法要增强，靠反射读方法与类上的注解元数据决定。
2. **调用先进代理**。调用方（另一个 Bean）持有的引用是代理对象，方法调用先进代理的拦截器 `TransactionInterceptor`。
3. **开事务**。拦截器通过事务管理器获取数据库连接，关闭自动提交，并把连接**绑定到当前线程**。此后同一个事务里的所有 SQL（包括 MyBatis 的 mapper 调用）都复用这条绑定在当前线程的连接，这是它们同属一个事务的原因。
4. **转发**。拦截器反射调用原始对象的方法，业务代码执行两条 INSERT。
5. **收尾**。方法正常返回，拦截器提交；方法抛出 `RuntimeException` 或 `Error`，拦截器回滚；方法抛出**受检异常**（checked exception，编译器强制声明的异常类型，见 [异常](java/10-06-exceptions.md)），默认规则是**提交**，想让它回滚要显式声明：

```java
// 片段：rollbackFor 让受检异常也回滚
@Transactional(rollbackFor = Exception.class)
public void importUsers(List<UserRow> rows) throws IOException { ... }
```

6. 无论提交还是回滚，拦截器把连接从线程解绑、归还连接池。

默认回滚规则值得单独记：**只有 `RuntimeException` 和 `Error` 触发回滚**。`BizException` 继承自 `RuntimeException`（已核实 `src/main/java/com/linkforge/common/exception/BizException.java`），所以在项目里抛它天然回滚。

### 传播行为：方法套方法时事务怎么算

业务方法互相调用，内外方法都带 `@Transactional` 时，事务要不要合并？这叫传播行为（propagation）。常用就两个：

- `REQUIRED`（默认）：当前有事务就加入，没有就新建。`register` 里调 `tenantMapper.insert` 就是加入 `register` 自己的事务。
- `REQUIRES_NEW`：挂起当前事务，另起一个独立事务，两者提交回滚互不影响。典型用法：操作日志。主业务回滚了，"有人尝试过这次操作"的日志也应该留下。

其余传播值（`NESTED`、`MANDATORY`、`SUPPORTS` 等）面试前补一遍即可，概念都是"加入、新建、嵌套、拒绝"的排列组合。

### 失效场景一：自调用（重点）

代理拦的是"从外面进来的调用"。目标对象内部用 `this` 调自己的方法，走的是原始对象，代理根本不在调用路径上。用可运行程序验证这个机理（这是纯 Java 模型，Spring 的真实行为与它一致）：

```java
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public class TxProxyDemo {

    // 模仿 Spring 的 @Transactional：标记哪些方法需要事务
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface Transactional {
    }

    interface OrderService {
        @Transactional
        void placeOrder(boolean fail);

        @Transactional
        void audit(boolean fail);
    }

    static class OrderServiceImpl implements OrderService {
        public void placeOrder(boolean fail) {
            System.out.println("  [target] insert order row");
            audit(fail); // self-invocation: this.audit(..), does NOT pass the proxy
        }

        public void audit(boolean fail) {
            System.out.println("  [target] write audit row");
            if (fail) {
                throw new IllegalStateException("boom");
            }
        }
    }

    // 模仿事务代理：进入时开事务，正常返回提交，抛异常回滚
    static class TxHandler implements InvocationHandler {
        private final Object target;

        TxHandler(Object target) {
            this.target = target;
        }

        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            boolean tx = method.isAnnotationPresent(Transactional.class);
            if (tx) {
                System.out.println("BEGIN (proxy)");
            }
            try {
                Object result = method.invoke(target, args);
                if (tx) {
                    System.out.println("COMMIT (proxy)");
                }
                return result;
            } catch (InvocationTargetException e) {
                if (tx) {
                    System.out.println("ROLLBACK (proxy)");
                }
                throw e.getCause();
            }
        }
    }

    public static void main(String[] args) {
        OrderService proxy = (OrderService) Proxy.newProxyInstance(
                TxProxyDemo.class.getClassLoader(),
                new Class<?>[] {OrderService.class},
                new TxHandler(new OrderServiceImpl()));
        System.out.println("proxy class == impl class: " + (proxy.getClass() == OrderServiceImpl.class));
        System.out.println("-- placeOrder(false) via proxy --");
        proxy.placeOrder(false);
        System.out.println("-- placeOrder(true) via proxy --");
        try {
            proxy.placeOrder(true);
        } catch (IllegalStateException e) {
            System.out.println("caught: " + e);
        }
        System.out.println("-- audit() directly via proxy --");
        proxy.audit(false);
    }
}
```

```text
proxy class == impl class: false
-- placeOrder(false) via proxy --
BEGIN (proxy)
  [target] insert order row
  [target] write audit row
COMMIT (proxy)
-- placeOrder(true) via proxy --
BEGIN (proxy)
  [target] insert order row
  [target] write audit row
ROLLBACK (proxy)
caught: java.lang.IllegalStateException: boom
-- audit() directly via proxy --
BEGIN (proxy)
  [target] write audit row
COMMIT (proxy)
```

读输出，三件事：

1. `proxy class == impl class: false`。你调用的不是原类实例，是运行时生成的替身。
2. 第一段：`placeOrder` 走代理，`BEGIN` 后转发目标；目标内部 `audit(fail)` 是 `this.audit(..)`，**没有再打印 BEGIN**。`audit` 明明也标了 `@Transactional`，自调用让它失效了。
3. 第三段：同一个 `audit`，从外面通过代理调，`BEGIN` 出现了。方法没变，变的是调用路径。

Spring 的实现细节更多（读的是 Spring 自己的 `@Transactional`、开的是真实 JDBC 事务），但"自调用绕过代理导致注解失效"的机理一模一样（框架行为）。修法两条：把这个方法拆到另一个 Bean，让调用跨对象；或注入自身代理。拆类通常更干净。

### 失效场景二：异常类型不对（重点）

代理只看到"方法最终抛出了什么"。两个方向的坑：

- 抛受检异常（如 `IOException`）：默认规则不回滚。修法：`rollbackFor = Exception.class`。
- 异常被 `catch` 后不重新抛出：代理看到的是"正常返回"，照常提交。该回滚的事悄悄提交了，这比报错更糟，因为错在库里。修法：要么不吞，要么 catch 后抛出运行时异常。

其余失效场景列出来，机理都是"代理没参与"或"规则没命中"：

| 场景 | 机理 | 结果 |
|------|------|------|
| 非 public 方法 | 默认只拦截 public 方法 | 注解无效 |
| `final` 方法 | CGLIB 子类无法重写 | 注解无效 |
| 方法里另起线程 | 事务绑定在线程上，新线程拿不到 | 新线程的 SQL 不在事务里 |
| 传播属性配错 | 如 `NOT_SUPPORTED` 挂起事务 | 代码在无事务状态运行 |

### 项目案例：全仓库只有两处 `@Transactional`

全仓库搜索（已核实）只有两处注解，每处都有明确理由。

第一处，`src/main/java/com/linkforge/stats/service/ClickRecordService.java` 第 40 行。`recordClick` 先看配置决定是否同步写明细 `lf_click_log`（第 56 行），再看配置决定是否写 `lf_outbox` 的 PENDING 行（第 72 行）。两条 INSERT 在同一个事务里提交，这就是 Outbox 模式"业务与事件同事务"的落点（链 [51-rabbitmq-outbox.md](51-rabbitmq-outbox.md)）。javadoc 写明 eventId 规则：同步写开着时用 `log-{明细id}`，重放可去重；关了就用随机 UUID，存在 outbox 行里。

第二处，`src/main/java/com/linkforge/user/service/AuthService.java` 第 63 行。`register` 在一个事务里建租户和该租户的首个用户（第 80、92 行两次 insert），用户插入失败不能留下孤儿租户。有个值得停一下的细节：第 81 行 catch 了 `DuplicateKeyException`（并发重名时租户名唯一索引抛出），转手抛 `BizException(409, ...)`。**catch 后抛新异常会回滚吗？会。** 代理看的是方法最终抛出的异常类型，`BizException` 是 `RuntimeException`，命中默认回滚规则（框架行为）。这里的 409 语义是"注册失败，库中无残留"，不是部分成功。

两处的自调用风险，如实判断：**都不存在**。`recordClick` 的调用方是 `ShortLinkService`（`src/main/java/com/linkforge/link/service/ShortLinkService.java` 第 348 行，`clickRecordService.recordClick(...)`），跨 Bean 调用，走代理；`ClickRecordService` 类内部没有任何方法调用 `recordClick`。`register` 的调用方是 controller（跨 Bean），类内同样无自调用。两处方法都是 public。写代码时要保持这个状态：一旦有人在同类里加一个方法转手调它们，事务就悄悄失效了。

反向对照同样是教材：`ShortLinkService` 全类没有 `@Transactional`。它的写路径多为单条 INSERT 或 UPDATE，单条语句在数据库层面本身原子，不需要显式事务。创建的幂等也不靠数据库事务，靠 Redis 键状态机（`setIfAbsent` 占位 pending，成功后回填 code，链 [50-redis.md](50-redis.md)）。如实补充一个例外：`update` 方法的清除密码分支（第 292 到 297 行）是两条相邻 UPDATE（先显式 SET NULL，再 `updateById`），没有包事务；若第二条失败，行停留在"密码已清、其他字段未改"的中间态，可由调用方重试修复（据代码推断：作者对单行更新接受了这个代价，换来更少的事务使用）。结论：**事务是工具不是习惯**，多条语句才谈事务，单语句与外部状态机各有各的原子性方案。

## 循环依赖与三级缓存：一段带过

A 的构造器要 B，B 的构造器要 A。用本章开头那个 MiniIocDemo 的逻辑心算一下：`get(A)` 触发 `get(B)`，又触发 `get(A)`，递归没有出口，直到栈溢出。**构造器注入的循环依赖无解**，因为实例化这一步就需要依赖，连"半成品"都拿不出来。

字段注入为什么能活：它把两步拆开了。容器可以先把 A、B 都实例化成空壳（构造器不需要依赖），再互相填充字段。填充时 A 缺 B 就把半成品 B 给它。Spring 内部用三个 Map 支撑这件事：一级放成品 Bean，二级放提前暴露的半成品，三级放"能造出半成品（含代理）的工厂"。工厂存在的意义是配合 AOP：提前暴露时才决定要不要生成代理，并保证只生成一次（框架行为，面试讲到这里即可，不必背源码）。

两个补充事实：

- Spring Boot 从 2.6 起默认拒绝循环依赖（`spring.main.allow-circular-references=false`）。框架的态度是：循环依赖多半是职责没拆清，先改设计再谈技巧。
- LinkForge 的依赖图无环。`ShortLinkService → ClickRecordService` 是单向的（已核实 `ClickRecordService` 的 4 个字段里没有 `ShortLinkService`），所以项目从未触发这个问题。构造器注入推荐理由的第三条"问题尽早暴露"，在这里得到印证：万一画出环，启动即失败，而不是运行时玄学。

## 常见错误与误区

- **"加个字段再 `@Autowired` 一下就行"**。初学者图省事用字段注入，因为它不用写构造器。代价：脱离容器无法测试、依赖不可 `final`、类膨胀无警报。改法：一律构造器注入，模板交给 `@RequiredArgsConstructor`。
- **"`@Transactional` 加上就完事"**。自调用、吞异常、受检异常、非 public，四个坑都会让它静默失效（不报错，只是不回滚）。改法：写完确认调用方走的是代理（跨 Bean 调用）、异常路径真的抛出了 `RuntimeException`。
- **"singleton 就是线程安全"**。单例只保证一份实例。可变字段照样被并发请求踩。改法：Bean 无状态，状态放数据库、Redis 或方法局部变量。
- **"循环依赖加个 `@Lazy` 绕过去"**。能启动了，但环还在，改动风险与日俱增。改法：先把公共部分拆成第三个 Bean，让箭头变成单向。
- **"扫描包越宽越省事"**。本项目 `@MapperScan` 的真实事故就是反例：宽扫把普通接口装配成了 Mapper 代理。改法：扫描与 `@MapperScan` 都精确到语义边界。
- **"把业务逻辑写进 `@Configuration`"**。配置类的职责是造对象和开开关，规则归 Service。配置类里出现 if 业务分支，通常意味着职责放错了地方。

## 自测

先自己答，再看参考答案。

1. 依赖注入注入的到底是什么？
   答：容器里那份单例对象的**引用**，通过构造器（或 Setter、字段）传给你的对象。注解只是给容器读的标记，注入动作由容器在创建 Bean 时执行。
2. `ShortLinkService` 用 `@RequiredArgsConstructor` 加 14 个 `private final` 字段，Spring 怎么完成注入？第 15 个 `final` 字段 `evictor` 为什么不在构造器参数里？
   答：Lombok 在编译期为所有没有初始化表达式的 final 字段生成一个 14 参构造器，Spring 用它做构造器注入。`evictor` 有初始化表达式（自己 `new` 的内部线程池），Lombok 不收它，它不是依赖，由 `@PreDestroy` 负责关闭。
3. `recordClick` 为什么必须 `@Transactional`，而 `ShortLinkService.create` 不需要？
   答：`recordClick` 里两条 INSERT（明细 + outbox）必须同生共死，需要事务打包。`create` 的写路径是单条 INSERT（冲突时换 code 重试），语句级原子已够，幂等由 Redis 键状态机保证。
4. 为什么同类内部自调用会让 `@Transactional` 失效？
   答：容器里放的是代理，代理只拦"从外面进来的调用"。目标对象内部 `this.method()` 走原始对象，不经过代理，开事务的拦截器没有机会执行。TxProxyDemo 的输出演示了同一方法经代理有 BEGIN、自调用没有。
5. `register` 里租户插入抛了 `DuplicateKeyException` 被 catch 后转抛 `BizException(409)`，事务会回滚吗？租户会残留吗？
   答：会回滚。代理看的是方法最终抛出的异常类型，`BizException` 继承 `RuntimeException`，命中默认回滚规则。回滚后租户行不存在，无残留；409 只是 HTTP 语义。
6. 自动配置的两步机制是什么？它和组件扫描的分工？
   答：先读各 jar 清单文件得到候选配置类名单，再用条件注解（类路径上有无某类、你是否已定义同类型 Bean）逐个取舍。组件扫描管你写的类，自动配置管 Boot 替第三方装配的设施。

## 面试追问

**DI 方式取舍**：构造器、Setter、字段三种怎么选？口径：默认构造器（可测、`final`、问题早暴露），可选依赖用 Setter，字段注入不写新代码。追问"为什么字段注入的循环依赖能活"答：先实例化后填充，半成品可先借人；构造器注入实例化就要依赖，环解不开，Boot 2.6+ 默认直接拒绝循环依赖。

**事务失效**：让讲全 `@Transactional` 失效场景并给机理。口径：自调用绕过代理、吞异常等于正常返回、受检异常默认不回滚（`rollbackFor` 可改）、非 public 与 final 方法拦截不到、新线程脱离事务绑定。每个都要能讲到代理层，不能只背名词。追问 `REQUIRED` 与 `REQUIRES_NEW` 区别答：加入当前事务与另起独立事务；例子是"操作日志独立于主业务提交"。

**Bean 生命周期**：五节点（实例化、属性填充、初始化、使用、销毁）加两个注解（`@PostConstruct`、`@PreDestroy`）。追问"初始化之后容器还会做什么"答：BeanPostProcessor 后处理，AOP 代理在这之后替换原对象，事务、`@Async` 的能力都从这里来。

## 下一步

- HTTP 请求怎么从内嵌 Tomcat 一路走到这些 Bean：[31-spring-boot-mvc.md](31-spring-boot-mvc.md)。
- 安全过滤器链与 `SecurityConfig` 的细节：[32-spring-security.md](32-spring-security.md)。
- `recordClick` 写入的 outbox 事件如何被中继和消费：[51-rabbitmq-outbox.md](51-rabbitmq-outbox.md)。
- Mapper 代理背后的 SQL 与分页插件：[41-mybatis-plus.md](41-mybatis-plus.md)。
