# 15.1 测试金字塔：319 例的构成、Testcontainers 基类与红先纪律

> 模块 15 第一节。这一课回答三个问题：这套代码的 319 个测试例是怎么数出来的；42 个集成测试类靠一个什么样的基类把 Docker 里的 MySQL、Redis、RabbitMQ 接进 Spring 容器；以及这个项目的贡献纪律为什么要求「先提交测试（CI 红），再提交实现（CI 绿）」。读完你应能独立写出一个新的 `*IT` 类，并且知道基类里每一个「奇怪」决定（500 连接上限、不带 `@SpringBootTest`、换请求工厂）各自防过一次什么事故。

## 1. 本节学习目标

1. 用两个口径说出测试规模：源码里的 312 个 `@Test` 注解，README 与 CI 汇报的 319 个执行实例，并解释 7 个例子的差从哪来。
2. 逐段读懂 `AbstractIntegrationTest`：三个 static 容器、`static {}` 启动块、`@DynamicPropertySource` 注入、四个 HTTP 助手方法。
3. 推导 `--max-connections=500` 这条配置的连接预算：27×10+15×4=330，为什么默认的 151 会炸。
4. 说出基类不带 `@SpringBootTest` 的原因（上下文缓存键），以及 `FlywayBaselineIT` 刻意不继承基类的原因（必须拿到一个没被迁移过的库）。
5. 复述 `@DirtiesContext(AFTER_CLASS)` 背后的真实事故：relay 线程跨类存活，会用错配置结算别的类的行。

## 2. 前置知识

- **JUnit 5（语言知识）**：`@Test` 标记测试方法，断言来自 `org.junit.jupiter.api.Assertions`（`assertEquals`、`assertTrue`、`assertThrows`）。`@BeforeAll`/`@BeforeEach` 是静态/实例级的前置钩子。
- **surefire 与 failsafe（框架行为）**：Maven 两个测试插件。surefire 绑定 `test` 阶段跑 `*Test` 结尾的类；failsafe 绑定 `integration-test`/`verify` 阶段跑 `*IT` 结尾的类。文件名即路由规则，本项目零配置沿用（插件细节在 15.2 深讲）。
- **Testcontainers（框架行为）**：一个 Java 库，能在测试里启动并管理真实 Docker 容器。它把「集成测试要连真 MySQL」从环境问题变成代码问题：容器对象就是变量，端口是运行时随机分配的。
- **Spring 上下文缓存（框架行为）**：`@SpringBootTest` 启动的 ApplicationContext 在同一个 JVM 里按「配置签名」缓存。两个测试类的注解配置完全相同，就复用同一个上下文；差一个属性就是两个上下文。这是理解本课一半设计决定的钥匙。
- **Awaitility（框架行为）**：`await().atMost(60s).untilAsserted(() -> 断言)`。轮询执行断言直到通过或超时，用于等异步结果收敛。
- **Hikari 连接池（框架行为）**：Spring Boot 的默认数据库连接池，`maximum-pool-size` 默认 10，即每个 Spring 上下文最多向 MySQL 开 10 条连接。

## 3. 本节涉及的源码

| 文件 | 行数 | 职责 | 阅读顺序 |
|----|----|----|----|
| `src/test/java/com/linkforge/AbstractIntegrationTest.java` | 176 | 全部 42 个 IT 的共享基类，本课主角 | 1 |
| `src/test/java/com/linkforge/FlywayBaselineIT.java` | 141 | 刻意不继承基类的遗留库升级测试 | 2 |
| `src/test/java/com/linkforge/webhook/WebhookDeliveryIT.java` | 412 | `@DirtiesContext` 事故注释与两段式断言 | 3 |
| `src/test/java/com/linkforge/webhook/WebhookRetryClockIT.java` | 199 | 时区 fork 的主角（15.2 深讲，此处读类头注释） | 4 |
| `src/test/resources/application-test.yml` | 69 | IT 专用 profile：非连接类默认值 | 5 |
| `src/test/java/com/linkforge/link/support/LinkValidationsTest.java` | 80 | 纯单测样例，含唯一的参数化测试 | 6 |
| `pom.xml` | 281 | `:219-249` failsafe 双执行（15.2 展开） | 7 |

阅读顺序的理由：先立基类，再看「为什么不走基类」的反例，然后用两个 webhook IT 理解上下文卫生与断言纪律，最后回到单测对照两种世界。

## 4. 类的定位（测试树的唯一基类）

`src/test/java` 共 70 个文件，包结构与 `src/main/java` 一一镜像：`link/`、`webhook/`、`stats/` 各自的测试放在同名包里，唯二的例外是 `com.linkforge` 根包下的 `AbstractIntegrationTest`（基类）和五个全局性 IT（`FlywayBaselineIT`、`HealthEndpointIT`、`ObservabilityIT`、`OpenApiInfoIT`、`ProdProfileIT`）。

`AbstractIntegrationTest` 不注册为任何 Spring Bean，也不被生产代码引用。它是纯继承意义上的工具类：42 个 `*IT` 类继承它拿到容器、连接属性和 HTTP 助手；27 个 `*Test` 类完全不碰它。测试金字塔的两层由文件名切开：`*Test` 归 surefire 免 Docker 秒跑，`*IT` 归 failsafe 需要 Docker daemon。

## 5. 核心源码详解

### 5.1 两个口径：312 与 319

先给结论（两者我都逐文件实测核对过）：

- **312** = 源码里 `@Test` 注解总数 = 单测 144 + IT 168。这是静态口径，写代码的人数这一侧。
- **319** = README 徽章与 CI 汇报的执行实例数 = surefire 151 + failsafe 默认组 166 + 时区 fork 2（`README.md:623`）。这是动态口径，参数化用例按展开次数计数。

166 比 168 少 2，因为 failsafe 默认执行排除了 `WebhookRetryClockIT`（`pom.xml:231`），它的 2 个 `@Test` 在独立 JVM fork 里另跑（15.2 详解）。surefire 侧：144 这个数只数 `@Test` 注解，不含全项目唯一的参数化方法——`LinkValidationsTest.java:62-63` 的 `@ParameterizedTest` 配 `@ValueSource` 的 7 个字符串是独立方法，展开后 **144+7=151**，与 README 的 surefire 口径精确对平。于是 151+166+2=319，两套账完全可平。这里曾出现另一版推导：编写者把参数化方法误当作已含在 144 内，做了一次双重扣减，推出一个静态解释不了的缺口并登记在 [reference/issues-and-discrepancies.md](../../reference/issues-and-discrepancies.md) §2.1——后来被审计抓出。教训：算式里每一项都要注明出处与含义，口径对账才不会凭空长出幽灵缺口。

### 5.2 AbstractIntegrationTest 逐段：三个容器与启动块

```java
public abstract class AbstractIntegrationTest {

    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("linkforge")
            .withUsername("linkforge")
            .withPassword("linkforge_pass")
            .withUrlParam("allowPublicKeyRetrieval", "true")
            .withUrlParam("useSSL", "false")
            // Every cached Spring context holds its own Hikari pool against this shared
            // container; the default max_connections=151 went into "Too many connections"
            // once the suite grew one more context (A9 BloomRedirectIT).
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci",
                    "--max-connections=500")
            .withStartupTimeout(java.time.Duration.ofMinutes(4));

    protected static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    protected static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.13-management-alpine"))
            .withAdminUser("linkforge")
            .withAdminPassword("linkforge_pass");

    static {
        MYSQL.start();
        REDIS.start();
        RABBIT.start();
    }
```

（逐字摘自 `AbstractIntegrationTest.java:31-58`，`static {}` 块为节选合并。）

三个语言点：`static final` 字段在类加载时初始化一次；`static {}` 块在基类第一次被加载（即第一个 IT 类开始跑）时执行，所以容器每个 JVM 只启动一次，被该 JVM 里所有 Spring 上下文共享；`abstract` 表示它自己不会被 failsafe 当作测试类执行。`withExposedPorts(6379)` 声明容器内端口，宿主机侧的随机映射端口之后用 `REDIS.getMappedPort(6379)` 取（`AbstractIntegrationTest.java:66`）。

类头注释（`AbstractIntegrationTest.java:23-30`）写明了契约：具体类自带 `@SpringBootTest`/`@ActiveProfiles("test")`，依赖来自每 JVM 一次的 Testcontainers 单例，所以整套 IT 只需要 Docker daemon，不需要宿主机上的 Compose 栈。

### 5.3 max-connections=500 的连接预算推导

上面的注释记录了一次真实事故：默认 MySQL 的 `max_connections` 是 151，每上下文一个 Hikari 池，套件多长一个上下文后撞上 `Too many connections`（`AbstractIntegrationTest.java:39-43`，A9 BloomRedirectIT 时期）。为什么改服务器上限而不是减池子？因为瓶颈在乘法：上下文数 × 池大小。

推导（口径出处 `README.md:626`）：42 个 IT 类，其中 15 个显式把池压到 4（如 `WebhookDeliveryIT.java:58` 的 `"spring.datasource.hikari.maximum-pool-size=4"`），其余 27 个用 Hikari 默认 10。最坏情形是每个类都独占一个上下文且每个池都同时满载，纸面峰值 27×10+15×4=330。服务器上限 500 留出 170 余量，另外时区 fork 是独立 JVM、容器独立计数。三个 relay-enabled 的 webhook IT 用 `@DirtiesContext` 类结束即关上下文，所以实际峰值低于纸面。这是配置侧的天花板推导，不是实测值；全绿即零 `Too many connections` 是它的验收标准。

### 5.4 @DynamicPropertySource：把随机端口塞进 Spring

```java
    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBIT.getMappedPort(5672).toString());
        registry.add("spring.rabbitmq.username", () -> "linkforge");
        registry.add("spring.rabbitmq.password", () -> "linkforge_pass");
    }
```

（逐字摘自 `AbstractIntegrationTest.java:60-71`。）

`@DynamicPropertySource` 是 Spring TestContext 的机制：静态方法在上下文启动前被调用，`registry.add(键, 供应商)` 注册的属性优先级高于任何 yml。关键在「供应商是懒求值的」：容器启动完成后才取值，而 Docker 分配的宿主机端口是随机的，写死在 yml 里根本不可能。这解释了 `application-test.yml` 的头注释（`application-test.yml:1-3`）：这个 profile 只放非连接类默认值，连接信息一律由基类注入，绝不指向宿主机 Compose 栈。

### 5.5 为什么基类不带 @SpringBootTest

直觉上「写测试基类当然把注解也放基类」，这个项目偏不放。类头注释给的理由（`AbstractIntegrationTest.java:24-26`）：具体类各自声明 `@SpringBootTest`/`@ActiveProfiles("test")`，为的是每个类能调自己的属性而不用改基类。机制原因回到 5.2 前置里的上下文缓存键。`@SpringBootTest` 的 `properties` 数组是缓存键的一部分，`WebhookDeliveryIT.java:47-59` 声明了 relay 定时、`hikari.maximum-pool-size=4`、`allow-private-targets=true` 等 12 项，这些差异让它的上下文必然独立。如果基类统一加了 `@SpringBootTest`，子类虽然还能在注解上加 `properties` 覆盖，但基类就成了「默认配置的事实收口」，任何一个类的调整都要先想「基类里写了什么」。把注解留在子类，每个测试文件的配置自包含，读一个类就知道它全部的运行条件。基类只保留与配置无关的资产：容器、注入器、HTTP 助手。

### 5.6 PATCH 之坑与 JdkClientHttpRequestFactory

```java
    @BeforeEach
    void usePatchCapableRequestFactory() {
        // The default factory rejects PATCH ("Invalid HTTP method"); the JDK HttpClient one does not.
        rest.getRestTemplate().setRequestFactory(
                new JdkClientHttpRequestFactory(java.net.http.HttpClient.newHttpClient()));
    }
```

（逐字摘自 `AbstractIntegrationTest.java:79-84`。）

这是框架行为补课：`TestRestTemplate` 默认基于 `HttpURLConnection`，而 JDK 的这个老客户端硬编码只支持有限的方法，发 PATCH 直接抛 `Invalid HTTP method`。`JdkClientHttpRequestFactory` 换成 `java.net.http.HttpClient`（JDK 11 起），PATCH 正常。放在 `@BeforeEach` 而不是构造器，是因为 `rest` 是缓存上下文里的共享 Bean，每个测试方法前重设一次最稳。短链管理接口的更新用的是 `PATCH /api/links/{code}`（见 6.3），没有这 4 行，所有走 `patchJson` 的 IT 都跑不起来。

### 5.7 FlywayBaselineIT 为何刻意不继承基类

```java
/**
 * Legacy-volume upgrade path: a database created the pre-Flyway way (all tables
 * from schema.sql, no flyway_schema_history) must be baselined at 1 on app start,
 * skip V1, and stay fully usable. The complementary empty-db path (Flyway applies
 * V1) is exercised by every other IT, which now boots without an init script.
 *
 * Standalone on purpose: it must NOT share the suite's already-migrated MySQL
 * singleton. RabbitMQ is never contacted (test profile: listener off, async off).
 */
```

（逐字摘自 `FlywayBaselineIT.java:20-28`。）

这个类测的是「老库升级」：一个按 Flyway 之前方式建好的库（表全在、没有 `flyway_schema_history` 表），应用启动时应打 baseline@1、跳过 V1、继续可用。它自建 `LEGACY_MYSQL` 容器并挂 init 脚本（`FlywayBaselineIT.java:45` 的 `withInitScript("db/schema.sql")`），`static {}` 只启动 MySQL 和 Redis（`FlywayBaselineIT.java:51-54`），自己的 `@DynamicPropertySource` 只注入这两个连接（`:56-63`）。

不继承基类的必然性：基类的 MYSQL 单例是全 JVM 共享的，任何一个 IT 跑过它就被迁移过了，history 表里早有记录，「恰好 1 条 BASELINE」的断言立刻失真。所以它必须拿一个全新卷、用 schema.sql 预灌成「遗留库」。这也顺手定义了互补覆盖：其余 41 个 IT 起空库、Flyway 正常应用 V1 到 V10，两条升级路径各测各的。断言本身值得精读：`FlywayBaselineIT.java:79-85` 验证恰好 1 条 BASELINE@1 且 V1 未重跑，`:88-97` 验证 V2 到 V6 叠加生效，`:99-102` 验证 `lf_short_link.code` 的 collation 是 `utf8mb4_0900_bin`（V4 的大小写敏感修复，见 4.2），`:104-105` 验证遗留卷上无种子数据。迁移机制本身见模块 4.2。

### 5.8 @DirtiesContext(AFTER_CLASS)：relay 线程跨类存活的教训

```java
// Relay-enabled contexts share one Testcontainers MySQL delivery table; a cached
// context keeps its @Scheduled drain thread alive after its class finishes, so a
// later class's rows would be settled by an earlier class's relay (max-attempts and
// timeouts included). Closing the context per class keeps exactly one relay alive.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WebhookDeliveryIT extends AbstractIntegrationTest {
```

（逐字摘自 `WebhookDeliveryIT.java:61-66`。）

事故链是这样：Spring 上下文默认跨测试类缓存。`WebhookDeliveryIT` 开着 `linkforge.webhook.relay-enabled=true`（`WebhookDeliveryIT.java:57`），它的上下文里 `WebhookDeliveryRelay` 的 `@Scheduled` 抽干线程活着。类跑完后上下文本该留在缓存里备用，但线程也跟着活着。而 `lf_webhook_delivery` 表是共享的：下一个类插入的投递行，会被上一个类的 relay 按上一个类的配置（重试上限、超时）结算掉。结果是别的类的测试随机红，且难以复现。

修复是 `@DirtiesContext(classMode = AFTER_CLASS)`：类结束即关闭并丢弃该上下文，保证任意时刻只有一个 relay 存活。全项目恰好三个类需要它，`WebhookDeliveryIT.java:65`、`WebhookAggregateIT.java:67`、`WebhookRebindingIT.java:69`，后两处的注释直接写「See WebhookDeliveryIT」。代价是这三个类各付一次上下文重启（秒级），这是给正确性买的保险，不能大面积用。

### 5.9 红先纪律与同步/异步断言纪律

**红先**（README 口径 `README.md:624`）：每个特性先提交测试，CI 红＝证据，再提交实现，CI 绿＝修好；B 波 3+4+5 轮 13 次分支红全部留档根因。`docs/progress.md:997` 记录了操作细则：红先提交只含测试、其 CI run 即红证据；绿提交才含实现加文档改口。这条纪律的价值在于证明「测试真的会失败」，防止写一个恒真的测试陪跑。

**同步/异步断言纪律**：计数断言打在同步明细表上，聚合值只做事后收敛断言。落点是 `WebhookDeliveryIT` 的两段式等待（类头注释 `WebhookDeliveryIT.java:40-46`）：链路依赖的测试先等 DELIVERY ROW（第一阶段，消费钩子的证据，不看状态），再等 stub 收到 HTTP（第二阶段，relay 的证据）；失败消息永远带着行数据，红了一次 run 就能看出断在哪一跳。具体写法上，投递行数用 SQL 直查（`WebhookDeliveryIT.java:156-159` 的 `deliveryCount`），`:209` 与 `:238` 各有一条 `assertEquals(1, deliveryCount(eventId))` 兜住「恰好一行」的幂等；而 stub 侧的到达只做 `awaitStubSaw` 的收敛判断（`:161-165`），不赌时序。原理：明细表行是同步事务写的，断言确定；聚合数字由异步管道推进，只能等收敛。

## 6. 技术原理补充

- **Testcontainers 单例容器模式（框架行为）**：把容器放进 `static final` 字段、在 `static {}` 里 start，整个 JVM 共享一份。好处是省启动时间（MySQL 容器冷启数十秒）；代价是所有类共享一个数据库实例，隔离只能靠数据不靠容器。不用它会怎样：要么连宿主机手装的服务（CI 不可复现），要么 H2 内存库（方言差异让 MySQL 专属行为失真，比如 V4 的 collation）。
- **上下文缓存与缓存键（框架行为）**：`@SpringBootTest` 的 `classes`、`properties`、`webEnvironment`、`@ActiveProfiles` 都参与缓存键。同键复用，异键新建。本项目 42 个 IT 里大量类只差 rate-limit 前缀等小属性，各自成键，这正是 5.3 连接预算按「每类一个上下文」纸面推导的原因。
- **@DirtiesContext（框架行为）**：声明该上下文「脏了」，按 `classMode` 决定何时关闭。`AFTER_CLASS` 在类结束时关。它不是清理数据的工具（数据靠 `unique()` 前缀与租户隔离），是清理「活线程」的工具，本课只用在 relay 场景。
- **Awaitility（框架行为）**：轮询断言，默认 100ms 一次。本项目 IT 统一 `atMost(60s)` 或 `30s`，给 CI 慢机留余量。不用它就得 `Thread.sleep` 固定等待，既慢又抖。
- **com.sun.net.httpserver.HttpServer（JDK 自带）**：`WebhookDeliveryIT.java:101-107` 用它起本地 stub 收 webhook，零第三方依赖；`:117-119` 按路径取脚本化状态码队列，每个测试租户一条专属路径互不串台（`:72-76` 注释）。

## 7. 可视化

打开 [09-test-ci-pipeline.html](../../diagrams/archify/html/09-test-ci-pipeline.html)（测试与 CI 流程图，showcase 档）。阅读顺序与节点对应：

1. 从「push/PR」节点进入 CI 五步（checkout、setup-java、缓存、`mvn -B verify`、结果），对应 15.2 的 `ci.yml` 逐行。
2. `mvn verify` 节点分出 surefire（151）与 failsafe（166）两条支路，对应本课 5.1 的口径拆分。
3. failsafe 分支里的时区 fork 小节点（2 例）对应 `WebhookRetryClockIT` 的独立 JVM，15.2 展开；图内卡片的例数出处口径（312 注解 vs 319 执行实例）与本课 5.1 一致。本课不引运行部署图，15.3 的 Compose 与宿主机 JVM 才是 [01-runtime-deployment.html](../../diagrams/archify/html/01-runtime-deployment.html) 的内容。

## 8. 项目中的真实执行过程

以 `mvn -B verify` 跑到 `WebhookDeliveryIT` 为例（时序，非虚构，各步均可回溯到上文行号）：

1. failsafe 起 JVM，加载 `WebhookDeliveryIT`，先加载父类 `AbstractIntegrationTest`：第一个被加载的 IT 触发 `static {}`（`AbstractIntegrationTest.java:54-58`），启动 MySQL、Redis、RabbitMQ 三个容器，全 JVM 就这一次。
2. 该类自己的 `@SpringBootTest(properties = {...})` 与 `@ActiveProfiles("test")` 生效，Spring 查缓存键未命中，启动新上下文。基类的 `@DynamicPropertySource` 把容器地址注入；`application-test.yml` 提供 `relay-enabled: false` 等默认值，被类上 `properties` 里的 `"linkforge.webhook.relay-enabled=true"`（`WebhookDeliveryIT.java:57`）覆盖。
3. `@BeforeAll startStub`（`WebhookDeliveryIT.java:101-107`）起本地 HTTP stub；`@BeforeEach resetSpies`（`:96-99`）与 `usePatchCapableRequestFactory`（基类）就位。
4. 测试方法跑 `registerForToken()`（基类 `:90-101`，注册新租户拿 JWT）→ `createLink()`（`:103-111`）→ `get("/r/"+code)` 打 302。点击进入落库、outbox、MQ、消费的完整链路。
5. 第一段 `await` 等投递行出现并 SENT（`:186-190`），第二段 `awaitStubSaw` 等 stub 收到带签名的请求（`:195-201`），最后 `assertEquals(1, deliveryCount(eventId))` 钉住幂等（`:209`）。
6. 类结束，`AFTER_CLASS` 关闭这个 relay-enabled 上下文；下一个 IT 类复用同一组容器与已缓存的其他上下文。42 个类跑完，failsafe 的 verify 阶段汇总结果。

## 9. 为什么这样设计

- **真依赖胜过 mock**：MQ 链路、Flyway 迁移、MySQL collation 这类行为，mock 出来只是「复述实现」。用真容器，测试失败即产品缺陷。
- **单例容器换速度，隔离交给数据**：容器冷启太贵，所以每 JVM 一份；隔离用 `unique()` 随机前缀（基类 `:86-88`）保证租户/短码不冲突。共享表带来的跨类污染风险不靠侥幸，靠 `@DirtiesContext` 精确拆除（5.8）。
- **基类薄、子类自包含**：容器与 HTTP 助手是纯机械复用，进基类；配置声明是每个测试的「运行条件说明书」，留在子类（5.5）。
- **反例有专门一课**：`FlywayBaselineIT` 证明「不继承」也是设计决定（5.7）。基类是便利，不是教条。
- **断言纪律先于断言数量**：两段式等待加明细表计数（5.9），让红 run 自带定位信息。测试多不等于可靠，可诊断性才是这套 IT 的门槛。

## 10. 本节总结

- 312 是源码 `@Test` 注解口径，不含 1 个独立的 `@ParameterizedTest` 方法；319 = 312 + 参数化展开 7 例 = surefire 151（144+7）+ failsafe 166 + 时区 fork 2，两账完全可平。
- `AbstractIntegrationTest` 四件套：三个 static 容器、`static {}` 单次启动、`@DynamicPropertySource` 懒注入、PATCH 可用的请求工厂；连接预算 27×10+15×4=330 对天花板 500。
- 基类不带 `@SpringBootTest` 是为让每个子类的配置签名自包含；`FlywayBaselineIT` 不继承是为保住一个未迁移的遗留库。
- `@DirtiesContext(AFTER_CLASS)` 恰好钉在三个 relay-enabled 的类上，防上下文缓存里的调度线程跨类结算数据。
- 红先纪律把「测试会失败」变成 CI 留档；同步计数、异步收敛的断言分工让失败可定位。

## 11. 课后练习

**练习 1（概念理解）** 同事问：「README 说 319 个测试，我 grep `@Test` 只有 312 个，是不是漏了？」写出你的解释。

答案：不是漏了，是两个口径的统计对象不同。312 是 `@Test` 注解的静态计数（单测 144 + IT 168），它**不含**全项目唯一的 `@ParameterizedTest` 方法（`LinkValidationsTest.java:62-63`，展开 7 例）。319 是 README/CI 的执行实例口径：surefire 151 = 144 个 `@Test` + 参数化展开 7 例；failsafe 默认组 166 = 168 − 2（`WebhookRetryClockIT` 被 `pom.xml:231` 排除），这 2 例在时区 fork 另跑。151+166+2=319，与注解口径 312+7=319 完全可平。引用时注明口径即可，不存在缺口。

**练习 2（源码定位）** 找出钉死 MySQL `max_connections` 的行，并说明为什么调这个参数而不是把每个上下文的 Hikari 池改小。

答案：`AbstractIntegrationTest.java:42-43`，`--max-connections=500` 是容器启动命令参数；`:39-41` 注释记录了默认 151 在上下文增多后触发 `Too many connections`（A9 BloomRedirectIT 时期）。池子已经压过一档：15 个 IT 显式设 `maximum-pool-size=4`，但 42 个类对应几十个缓存上下文，池太小会拖慢并发查询，服务器侧抬高上限（500 对 27×10+15×4=330 的纸面峰值留 170 余量，`README.md:626`）是更便宜的杠杆。

**练习 3（执行过程）** 如果把 `FlywayBaselineIT` 改成继承 `AbstractIntegrationTest` 并删掉自己的 `LEGACY_MYSQL`，哪个断言最先失去意义？为什么？

答案：`FlywayBaselineIT.java:79-80` 的「history 表里恰好 1 条 BASELINE 类型记录」。基类的 MYSQL 单例是全 JVM 共享的，此前任何一个 IT 已经在它上面执行过 V1 到 V10 迁移，history 表里躺着全套 SQL 记录；`withInitScript("db/schema.sql")` 预灌的「遗留库」前提也不存在了。所以类头注释明说 it must NOT share the suite's already-migrated MySQL singleton（`FlywayBaselineIT.java:26-27`）。

**练习 4（调用链）** 假如删掉 `WebhookDeliveryIT` 上的 `@DirtiesContext(AFTER_CLASS)`，紧接着跑 `WebhookAggregateIT` 可能出现什么症状？请按「谁的线程、结算谁的行、用谁的配置」描述。

答案：`WebhookDeliveryIT` 的上下文（含 `WebhookDeliveryRelay` 的 `@Scheduled` 抽干线程）被缓存存活，而两个类共享同一个 Testcontainers MySQL 的 `lf_webhook_delivery` 表。`WebhookAggregateIT` 插入的投递行会被前一个类的 relay 抢先结算：重试次数按前一个类的 `max-attempts` 算，超时按前一个类的配置计，聚合模式相关的行也可能被按 PER_CLICK 语义改写。症状是隔类随机红、单跑复现不出来。这正是 `WebhookDeliveryIT.java:61-64` 注释描述的事故，三个 relay-enabled 类（`WebhookAggregateIT.java:67`、`WebhookRebindingIT.java:69`）都钉了同一注解。

**练习 5（设计分析）** `WebhookDeliveryIT` 为什么把「恰好一行投递行」的断言写在投递表计数上，而 stub 收到的请求数只做收敛等待、不做精确计数？

答案：投递行由消费钩子在同步事务里写入（消费 outbox 事件的同一条链路），行数是确定值，适合 `assertEquals(1, deliveryCount(eventId))`（`WebhookDeliveryIT.java:209`、`:238`）。stub 侧是 relay 的 HTTP 出站，异步且受网络与调度抖动影响，重试场景下到达次数只能保证下界（`:236-237` 用 `>= 3`），精确计数会把确定性断言变成赌时序。这就是「计数打同步明细表、聚合/异步只做收敛」的纪律。

## 12. 与后续课程的联系

下一课 15.2 从「测试怎么写」转到「测试怎么被编排」：Maven 生命周期里 surefire 与 failsafe 的分工、`webhook-tz-it` 这条时区 fork 执行的完整动机（`WebhookRetryClockIT` 类头注释里的 ~8h 退避事故）、`build-info` 如何让 OpenAPI 版本以 pom 为单一事实源，以及 CI 工作流的缓存键设计。再往后 15.3 离开测试进入运维视角：Compose 五个 service、四个演练脚本与 prod profile 收紧。想看这套测试守护的业务链路，读专题「Webhook 投递与重试」（[../../advanced/11-webhook-delivery-retry.md](../../advanced/11-webhook-delivery-retry.md)）；想回顾 webhook 退避与双时钟口径本身，回到 [../13-webhook/02-delivery-signing-retry.md](../13-webhook/02-delivery-signing-retry.md)。
