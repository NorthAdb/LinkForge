# LinkForge 项目地图：进代码前的技术栈、模块与数据

本文是「读代码前的地图」。地图只回答三个问题：**东西在哪、为什么在那、和谁相连**。每条调用链逐步怎么走，是 [81-linkforge-flows.md](81-linkforge-flows.md) 的事；每个设计为什么这么定，是 [82-linkforge-decisions.md](82-linkforge-decisions.md) 的事。本文不重复展开，只给方向和出处。

文中所有类名、表名、配置项都逐一对过仓库源码；数量（几张表、几个类）都是数出来的，不是估的。

## 拿到 98 个类，从哪个文件开始读

你打开仓库，看见 `src/main/java/com/linkforge/` 下有 98 个 Java 文件（`find src/main/java -name "*.java" | wc -l` 的结果）。没人能一次读完。地图的作用是把 98 个文件压成几张图：谁在跑（1 个 JVM 进程加 3 个容器）、代码怎么分块（10 个业务包）、数据长什么样（11 张表）、一次请求怎么穿层。读完本文，你遇到任何一段代码，都能先回答「它在哪个包、管哪件事、依赖谁」，再去 [81-linkforge-flows.md](81-linkforge-flows.md) 看它怎么动。

先给一句项目定位：LinkForge 是多租户短链加点击分析的后端。功能面：短链增删改查、302 跳转、点击统计管道、限流、认证、开放 API（API Key 与 Webhook）、可观测指标。技术面：业务壳是短链，真正练的是 MySQL、Redis、RabbitMQ、JWT 的组合。

还有一个读码前必须知道的惯例：源码注释里到处是 `A7:`、`B3:`、`D-Bw345-42` 这样的代号。它们是开发轮次的编号（A 是深化轮，B 是 B 档波次，D- 开头是决策编号），档案在 `docs/progress.md` 与 `docs/backlog.md`。看到代号不用慌，那是「这段代码为什么长这样」的索引，不是乱码。

## 谁在跑：一个 JVM 进程加三个容器

先说没有地图时的痛：你 `docker compose up -d` 之后不知道哪些进程在干活，出了问题不知道该看谁。LinkForge 的运行形态只有一句话：**应用本体是一个跑在宿主机上的 Java 进程，它的三个依赖各占一个 Docker 容器**。

```text
┌─────────────── 宿主机 ──────────────────────────────────────┐
│ java -jar target/linkforge-2.0.0.jar（Spring Boot，8080）    │
│   ├── 内嵌 Tomcat → Security 过滤器链 → DispatcherServlet     │
│   ├── 连接池(HikariCP) → localhost:3306（MySQL 容器发布端口）  │
│   ├── Lettuce(Redis 客户端) → localhost:6379                  │
│   ├── AMQP → localhost:5672（RabbitMQ 容器发布端口）           │
│   └── 调度线程池(6 线程)：5 个 @Scheduled 定时任务（见下）      │
├─────────────── Docker Compose（docker-compose.yml 实况）──────┤
│ mysql:8.0                        3306（带健康检查）           │
│ redis:7-alpine                   6379（带健康检查）           │
│ rabbitmq:3.13-management-alpine  5672 + 管理台 15672          │
│ [profile observability，默认不启动]                            │
│ prom/prometheus:v2.55.1  9090 → 抓宿主机 8080 的指标端点       │
│ grafana/grafana-oss:11.3.0  3001 ← 读 Prometheus              │
└───────────────────────────────────────────────────────────────┘
```

四点说明，全部可自查：

1. **应用不在容器里**。`docker-compose.yml` 只有 mysql、redis、rabbitmq 和两个观测容器，没有 app 服务。应用本体用 `java -jar` 在宿主机跑，靠 `localhost` 加 Compose 发布的端口连依赖。这是有意为之的范围冻结：云上部署在 `docs/backlog.md` 的 #8 挂起，prod profile 已备收紧开关（`application.yml` 第三段）。
2. **调度线程池 6 线程，跑 5 个定时任务**。`spring.task.scheduling.pool.size` 配在 `application.yml` 全局段。5 个任务分别在这五个类里（`grep -rn "@Scheduled" src/main/java` 可数）：`src/main/java/com/linkforge/stats/service/ClickOutboxRelay.java`（搬运发件箱）、`src/main/java/com/linkforge/stats/service/ClickLogReconciler.java`（补偿对账）、`src/main/java/com/linkforge/webhook/service/WebhookDeliveryRelay.java`（投递 Webhook）、`src/main/java/com/linkforge/link/support/PhishingPolicy.java`（热重载域名名单文件）、`src/main/java/com/linkforge/observability/BacklogGauges.java`（采样积压量）。池子给到 6 而不是 4，是为了一个慢任务不能把别的任务饿死——线程是共享的，隔离靠「任务数小于线程数」。
3. **三个依赖容器都有 healthcheck**。Compose 等它们 healthy 之后，你的应用才连得上。这就是 README「五步跑通」第一步先 `docker compose ps` 看状态的原因。
4. **观测栈是可选 profile**。默认 `docker compose up -d` 不含 Prometheus 与 Grafana；`docker compose --profile observability up -d` 才起。Prometheus 抓的是宿主机 8080（应用在容器外），所以它挂载了 `ops/observability/prometheus.yml` 与抓取令牌文件。

进程与线程、容器与端口这些词的底层细节在 [21-os.md](21-os.md) 与 [61-docker-deploy.md](61-docker-deploy.md)。

## 用什么造：技术栈逐项审

地图上最重要的一张表。每一行回答「解决什么问题、谁在用、代价是什么」。版本号全部来自 `pom.xml`。

| 依赖 | 版本 | 解决什么 | 谁在用（已核实） | 代价 |
|------|------|----------|------------------|------|
| spring-boot-starter-parent | 3.3.5 | 依赖版本统一管理 + 自动装配 | 全项目（`java.version=17`） | 装配是隐式的，Bean 来源要自己查 |
| starter-web | Boot 管理 | Spring MVC + 内嵌 Tomcat + Jackson JSON | 7 个业务 Controller | 请求分发的中间层不直观（见分层一节） |
| starter-validation | Boot 管理 | 注解式参数校验 | `CreateShortLinkRequest` 的 `@NotBlank`/`@Size` 等 | 只校验形状，业务规则仍在 Service |
| starter-security + jjwt | 0.12.6 | 过滤器链、BCrypt；JWT 签发与验签 | `SecurityConfig`、三个自研过滤器、`JwtService` | 过滤器顺序错一个就 401/403 乱套 |
| starter-data-redis | Boot 管理 | Lettuce 客户端 + `StringRedisTemplate` | 缓存、限流、幂等键（见下小节） | 缓存一致性要自己设计 |
| starter-amqp | Boot 管理 | `RabbitTemplate`/`@RabbitListener` | Outbox 发布与消费 | 引入最终一致性，要处理重复消费 |
| mybatis-plus | 3.5.9 | 通用 CRUD + 条件构造器 + 逻辑删除 + 分页 | 11 个 Mapper 接口 | 复杂查询仍要手写 SQL |
| flyway core + mysql | Boot BOM | 版本化数据库迁移 | `src/main/resources/db/migration` 的 V1–V7 | 每次改表都要新建迁移文件 |
| guava | 33.4.8-jre | BloomFilter 数据结构 | `CodeBloomFilter` | 有假阳性、不能删除 |
| caffeine | Boot BOM | 进程内 LRU 缓存 | `UrlLocalCache`（L1） | 多实例间互不同步 |
| micrometer-registry-prometheus | Boot BOM | 指标导出端点 | 各业务类埋点 + `BacklogGauges` | 埋点是手写的，漏埋就没有数 |
| springdoc | 2.6.0 | OpenAPI 文档与 UI | `OpenApiConfig` | prod 下整体关闭 |
| testcontainers | 2.0.5 | 集成测试起真容器 | `AbstractIntegrationTest` 与全部 `*IT` | 测试需要 Docker，跑得慢 |
| lombok | Boot 管理 | 编译期生成 getter/构造器/日志字段 | 全仓 45 处 `@Data`、27 处 `@RequiredArgsConstructor` | 源码里看不到生成的方法 |

下面六个是本项目的支柱，逐个说清「为什么是它」。

### Spring Boot 3.3.5：为什么是它

问题：一个后端要连 MySQL、Redis、RabbitMQ，要开 HTTP 端口，要管 JSON 序列化。不用框架的话，每一项都要手写初始化代码和连接管理，还要保证它们之间初始化顺序正确。

Spring Boot 的回答分两层。第一层是「约定优于配置」：你在 `application.yml` 写下 `spring.data.redis.host`，Boot 的自动装配机制（自动装配：框架扫描 classpath，发现某依赖存在就替你创建并装配对应的 Bean）就创建好 Redis 连接。第二层是 starter：`spring-boot-starter-web` 一个依赖带入 Spring MVC、内嵌 Tomcat、Jackson 三件套，你不用自己拼版本。

代价同样真实，而且本项目就踩过一次：自动装配是隐式的，出了问题不好定位。`LinkForgeApplication.java` 里有一行注释记录的事故——`@MapperScan` 一旦宽扫整个 `com.linkforge`，MyBatis 会把 `ClickEventPublisher` 这种普通接口也误代理成数据层 Bean，启动直接出错。最终改成显式列出五个 mapper 包。这就是「便利换控制权」：框架替你做的越多，你越要懂它替你做了什么。Spring 核心（IoC、依赖注入、事务）在 [30-spring-core.md](30-spring-core.md)，Boot 与 MVC 在 [31-spring-boot-mvc.md](31-spring-boot-mvc.md)。

### MyBatis-Plus 3.5.9：不写 SQL 与必须写 SQL 的边界

问题：每张表都要增删改查，手写 SQL 意味着每个 Mapper 接口配一个 XML 文件，一行 `SELECT * FROM lf_short_link WHERE code = ?` 都要写全。

MyBatis-Plus 的回答：Mapper 接口继承 `BaseMapper<实体>`，单表 CRUD 直接就有了。看 `src/main/java/com/linkforge/link/mapper/ShortLinkMapper.java`，它只声明了一个方法，增删改查全从父接口继承。Service 层用 `LambdaQueryWrapper` 拼条件（如 `ShortLinkService.getOwnedLink` 里的 `.eq(ShortLink::getCode, code)`），用方法引用代替字符串列名，列名改了编译期就能发现。逻辑删除也由框架接管：`application.yml` 配 `logic-delete-field: deleted`，实体 `src/main/java/com/linkforge/link/entity/ShortLink.java` 的 `deleted` 字段标 `@TableLogic`，之后 `deleteById` 实际执行的是 `UPDATE ... SET deleted = 1`。

边界在哪：**凡是有特殊语义的查询，本项目全部回到手写 SQL**（注解 `@Select`/`@Insert`/`@Update`）。数一数本项目手写的方法及理由：

- `ShortLinkMapper.selectAllCodes`：`SELECT code FROM lf_short_link`，故意绕过 `@TableLogic`。已删除的码仍占着唯一索引，Bloom 播种必须把它们算进去。
- `ClickDailyMapper.incrementClick`：`INSERT ... ON DUPLICATE KEY UPDATE clicks = clicks + 1`，一天一行的原子累加，框架的通用 insert 做不到。
- `OutboxMapper.selectPendingBatch`/`markSent`/`markRetry`/`markFailed`：发件箱状态机，每个状态转移一条 SQL。
- `ClickLogMapper.selectUnconsumed`/`countDistinctIps`/`selectDailyUv`：跨表 LEFT JOIN、`COUNT(DISTINCT ...)`、按天 GROUP BY，都是 Wrapper 表达不了的。

代价：CRUD 长在框架里，SQL 不在眼前。索引有没有被用到、有没有生成慢查询，要自己去 `EXPLAIN`（[40-mysql.md](40-mysql.md)）。条件拼错不会在编译期报错，只会在运行时返回空或错。MyBatis-Plus 的完整机制在 [41-mybatis-plus.md](41-mybatis-plus.md)。

### Redis 的三个角色：缓存、限流、幂等键

问题：跳转是高频读；注册、创建、跳转都要限流；同一个请求重试两次不能建两条短链。三件事都要求「多个请求看到同一份状态」，而单个 JVM 的内存做不到（重启即失、扩容即裂）。Redis 是一个独立的内存键值服务，所有请求都去它那里读写，状态就集中了。

本项目里 Redis 扮演三个角色，键前缀就能看出来（`ShortLinkService` 与 `src/main/java/com/linkforge/common/ratelimit/RateLimitKeys.java` 中的常量）：

1. **缓存**。正缓存 `linkforge:code:{code}` 存目标 URL，TTL（Time To Live，过期时间）3600 秒加均匀抖动 [0, 300] 秒——抖动让大量键不同时过期，避免过期瞬间流量全砸向 MySQL。负缓存 `linkforge:absent:{code}`（B7 轮引入）存「数据库确认过不存在」的码，TTL 60 秒加抖动，挡住反复探测不存在码的流量。
2. **限流**。`src/main/java/com/linkforge/common/ratelimit/RedisRateLimiter.java` 加载 `src/main/resources/redis/sliding_window.lua`，用 Redis ZSET（有序集合）实现滑动窗口日志：每个被放行的请求记一条时间戳，窗口外的清掉，窗口内的数量超限就拒绝。脚本用 Redis 自己的 `TIME` 做时钟，Java 不传时间，多实例时钟漂移不影响判定。限流键前缀 `linkforge:rl:sw:`，与旧版固定窗口的 STRING 键隔离——注释写明原因：对 STRING 键执行 ZSET 命令会报 WRONGTYPE，限流器会静默失效。另有固定窗口计数 `tryAcquireFixedWindow`，只用于 API Key 的日配额，因为「自然日重置」本来就是日历语义。
3. **幂等键**。创建短链接口接受 `Idempotency-Key` 请求头。`ShortLinkService.create` 用 `setIfAbsent`（不存在才写入，Redis 的原子操作）占住键 `linkforge:rl:idem:{userId}:{key}`，值先写 `pending`，创建成功后改写为短码。重放同一请求头时直接返回已建好的短链，不会建第二条。

三个角色有一个共同设计：**Redis 出错时服务不跟着死**。限流器 Redis 挂了就放行（fail-open，放行但计数暴露），缓存探针失败就当未命中。代价是引入了一个必须保持一致性的外部状态：缓存与数据库双写怎么对齐（`ShortLinkService` 用先写后删加延迟再删）、哪些操作要原子（Lua 脚本）都得自己设计。全部细节在 [50-redis.md](50-redis.md)。

### RabbitMQ：把点击记录从跳转路径上摘下来

问题：每次跳转要记点击、更新日聚合、触发 Webhook。如果这些都在跳转请求里同步做完，统计层的任何抖动都直接拖慢用户跳转；如果只是「发个消息就不管」，进程在发送后崩溃，这条点击就永远丢了。

本项目的答案分三步。第一步，跳转请求里**只写 MySQL**：`src/main/java/com/linkforge/stats/service/ClickRecordService.java` 在同一个数据库事务里写入点击明细行（可选）和一条 `lf_outbox` 发件箱行（状态 PENDING）。第二步，`ClickOutboxRelay` 定时把 PENDING 行取出来发到 RabbitMQ（一个独立的消息代理服务，生产者把消息投给它，消费者从它取），收到 broker 确认才把行标成 SENT；发不出去就退避重试，超过 30 次标 FAILED 留查。第三步，`ClickEventConsumer` 监听队列消费事件，写聚合表，用数据库的 `lf_consumed_event` 表按事件 ID 去重，消息重复投递也不会重复计数。

这套「本地发件箱」（transactional outbox）模式解决的问题是：数据库事务和消息发送是两个系统，无法一起回滚。把「要发消息」这件事本身变成事务里的一行记录，崩溃后可以重发，消息就不丢。代价是最终一致性（统计晚几秒到）和必须处理重复（消费端幂等）。拓扑定义在 `src/main/java/com/linkforge/config/RabbitMqConfig.java`：一个 topic 交换机、一个队列、一个死信交换机加死信队列（消费失败的消息去死信队列而不是无限重试）。发布端 `src/main/java/com/linkforge/stats/mq/RabbitClickEventPublisher.java` 阻塞等 broker 确认（5 秒超时），确认不到就抛异常让发件箱行保持 PENDING。还有一个值得读的注释在 `RabbitMqConfig` 的 `jacksonMessageConverter`：必须用 Spring 的 ObjectMapper（带 JavaTime 模块）替换默认转换器，否则 `LocalDateTime` 序列化失败，每条消息都进死信队列。

为什么选消息队列而不是线程池异步：线程池里的任务随进程死而丢，消息代理是独立进程且有持久化，能扛应用重启。为什么不选事务消息等更重的方案，见 [82-linkforge-decisions.md](82-linkforge-decisions.md) 的 D7。完整链路在 [51-rabbitmq-outbox.md](51-rabbitmq-outbox.md)。

### Guava BloomFilter：进程内的「肯定不存在」判官

问题：大量请求探测不存在的短码（恶意扫描或失效链接）。每个这样的请求都会穿过缓存打到 MySQL，这就是缓存穿透。

`src/main/java/com/linkforge/link/support/CodeBloomFilter.java` 用 Guava 的布隆过滤器（BloomFilter，一个可能误报、绝不漏报的存在性判断结构）挡在 MySQL 前面。启动时（`ApplicationReadyEvent`）用 `selectAllCodes` 把全部短码播种进去；之后每次跳转先问它：**回答「没有」是权威的**——这个码从未创建过，直接 404，不碰 MySQL；回答「可能有」包含配置的假阳性率（fpp 0.0001），正常走缓存加数据库的解析路径，正确性不受影响。创建短链成功后调用 `put` 把新码加进去。

三个代价都写在源码注释里，值得逐条记：

1. 假阳性：万分之一的概率把不存在的码放进数据库路径，只多一次查询，无害。
2. 不能删除：删除的码仍是「可能存在」，多一次数据库未命中，由数据库兜底 404。所以播种才要绕过逻辑删除，把已删码也保持为正。
3. 进程私有：每个实例各自播种。多实例部署时，实例 B 新建的码在实例 A 重启前不在 A 的过滤器里，A 会错误地 404——这是假**阴**性，比假阳性严重，登记在 `docs/backlog.md` #3。单实例演示形态下不存在。

### Lombok：把样板代码生成出去

问题：一个实体类要有 getter、setter、`toString`；一个 Service 要把五六个依赖写成构造器。这些代码没有信息量，却占一半篇幅。

Lombok 是注解处理器（编译期读取注解并生成代码的工具，原理见 [java/10-07-annotations-reflection-proxy.md](java/10-07-annotations-reflection-proxy.md)）。本项目全仓 45 处 `@Data`（生成 getter/setter/equals/hashCode/toString）、27 处 `@RequiredArgsConstructor`（为所有 `final` 字段生成构造器）、19 处 `@Slf4j`（生成 `log` 字段）。看 `src/main/java/com/linkforge/link/entity/ShortLink.java`：12 个字段，类体里一行方法都没有。`ShortLinkService` 声明一批 `private final` 依赖，配合 `@RequiredArgsConstructor` 就完成构造器注入，字段不可变（`final`），依赖关系一目了然。

代价有两个，都要知道。其一，生成的方法在源码里不存在，IDE 不装 Lombok 插件就满屏红线，调试时单步进不了这些方法。其二，`@Data` 一并生成 `equals`/`hashCode`，实体字段多时这两个方法并不总符合你的业务相等语义——本项目实体用它，接受的正是「生成的行为即可」。构建产物里不含 Lombok：`pom.xml` 的 spring-boot-maven-plugin 把它排除了，因为它只在编译期需要。

## 代码怎么分块：com.linkforge 下的包

包（package）是 Java 的命名空间加访问控制单位。`com.linkforge` 下有 10 个业务包加根包的启动类。先上总表，再逐包一段。

| 包 | 职责一句话 | 关键类（均已核实存在） |
|----|-----------|------------------------|
| `user` | 注册、登录、发 JWT | `AuthController` `AuthService` `Tenant` `UserAccount` |
| `security` | 进业务前的认人与授权 | `SecurityConfig` `JwtAuthFilter` `ApiKeyAuthFilter` `MetricsScrapeTokenFilter` `JwtService` `UserContext` |
| `link` | 短链 CRUD 与跳转 | `ShortLinkController` `RedirectController` `HealthController` `ShortLinkService` `ShortLink` + `support/` 12 个工具类 |
| `stats` | 点击落库、发件箱、消费、聚合 | `ClickRecordService` `ClickStatsService` `ClickOutboxRelay` `ClickLogReconciler` `ClickEventConsumer` `RabbitClickEventPublisher` |
| `apikey` | 开放 API 密钥 | `ApiKeyController` `ApiKeyService` `ApiKey` |
| `webhook` | 点击回调 HTTP 推送 | `WebhookController` `WebhookService` `WebhookDeliveryRelay` `WebhookSigner` |
| `bootstrap` | 启动时造演示账号 | `BootstrapDataLoader` |
| `config` | 基础设施装配 | `LinkForgeProperties` `RabbitMqConfig` `MybatisPlusConfig` `RedisConfig` `SchedulingConfig` `OpenApiConfig` |
| `common` | 跨域共用件 | `ApiResponse` `PageResponse` `BizException` `GlobalExceptionHandler` `RedisRateLimiter` `ClientIps` |
| `observability` | 指标补全 | `BacklogGauges` `HttpUriTagAllowList` |

**`common` 是叶子包**。grep 证实它不 import 任何 `com.linkforge` 业务包：异常（`BizException`、`RateLimitedException`）、统一响应壳（`ApiResponse`、`PageResponse`）、限流器（`RedisRateLimiter`）都是只被别人用、不用别人的代码。任何包都可以依赖它，它不依赖任何人——所以它最稳定。

**`config` 是另一个叶子包**。六个装配类只面向框架 API：`LinkForgeProperties` 用 `@ConfigurationProperties(prefix = "linkforge")` 把 `application.yml` 的 `linkforge.*` 全部业务参数收进一个类型安全的对象；`MybatisPlusConfig` 注册分页拦截器；`RedisConfig` 提供 `StringRedisTemplate` Bean；`RabbitMqConfig` 声明交换机、队列与死信拓扑；`SchedulingConfig` 只有一行 `@EnableScheduling`；`OpenApiConfig` 配文档。

**`security` 管认人，不管业务**。`SecurityConfig` 定义过滤器链与 URL 授权矩阵；三个过滤器都是 `OncePerRequestFilter`（每请求只过一次的过滤器基类）：`JwtAuthFilter` 解析 Bearer 令牌把身份放进 `UserContext`（一个 `ThreadLocal`，请求结束在 finally 里清理）、`ApiKeyAuthFilter` 用 `X-API-Key` 请求头验 API Key、`MetricsScrapeTokenFilter` 守 `/actuator/prometheus`。注意一个反直觉的事实：security 包 **import 了 apikey 包**（`ApiKeyAuthFilter` 调 `ApiKeyService.authenticate` 验钥匙）。它不是纯粹的底层。

**`link` 是最大域**。三个 Controller（业务两个加健康检查）、一个主 Service、一个 Mapper、一个实体、四个 DTO，加 `support/` 子包 12 个类：`CodeBloomFilter`、`SingleFlight`（合并并发的同码数据库读）、`UrlLocalCache`、`ShortLinkRowLoader`（跳转行读的独立 Bean，为了能被 singleflight 包住）、`TargetPolicy`、`PhishingPolicy`、`ClickFilter`、`GatePages`（门禁页 HTML）、`LinkAccessTokens`（密码短链签发的 cookie）、`ShortCodes`（Base62 随机码）、`LinkValidations`、`CsvFormat`。`support/` 是域内私有工具区：别的域不该 import 它。

**`stats` 是管道域**。写路径两个服务：`ClickRecordService` 在跳转事务里写明细加发件箱；`ClickStatsService.consume` 幂等消费（去重、补明细、累加日聚合、登记 Webhook 投递，四件事一个事务）。搬路径：`ClickOutboxRelay` 加 `ClickLogReconciler`（对账补偿）。MQ 三件：`ClickEvent`（事件 record）、`ClickEventPublisher`（接口）、`RabbitClickEventPublisher`（实现，含 broker 确认）。五个 Mapper 对应五张表。

**`apikey` 与 `webhook` 是开放能力域**。API Key 只存 SHA-256 哈希（`ApiKeyService.create` 里 `MessageDigest` 现算），请求凭 Key 进来时以创建者身份行事。Webhook 建在 Outbox 链上：消费事务里落投递行，`WebhookDeliveryRelay` 用 JDK 自带 `HttpClient` 推送，`WebhookSigner` 做签名，`WebhookSecretCipher` 用环境变量主密钥做 AES-GCM 加密存储。webhook 包 import 了 stats 包的 `ClickEvent` 与 `ClickDimensions`——它复用点击事件契约，而不是重新定义一份。

**`bootstrap` 只有一个类**。`BootstrapDataLoader` 实现 `ApplicationRunner`，启动后确保 `demo/admin` 账号存在，密码必须来自环境变量，没有默认值。

### 依赖方向：用 import 实测，别凭感觉

「A 依赖 B」在本项目里指的是：A 的源文件里有 `import com.linkforge.B.*`。逐包 grep 的结果（每条都抽查了文件）：

- `user` → common、config、security（`AuthService` 用限流器、配置、`JwtService`）
- `security` → common、config、**apikey**（`ApiKeyAuthFilter` 验 Key）
- `link` → common、config、security、**stats**（`ShortLinkService` 调 `ClickRecordService` 记点击）
- `stats` → common、config、security、**link**（`StatsController` 调 `ShortLinkService` 查链主；`ClickRecordService` 读 `link.entity.ShortLink`）
- `apikey` → common、config、security（取 `AuthUser`/`UserContext`）
- `webhook` → common、config、security、**stats**（复用事件与维度解析）
- `bootstrap` → config、user
- `observability` → config

注意三处**包级互相 import**：link 与 stats、stats 与 webhook、security 与 apikey。初学者会由此得出「架构乱了」的结论，但逐类看，每一处都是单向的类依赖，没有两个类互相依赖：

- `link.service.ShortLinkService` → `stats.service.ClickRecordService`（link 调 stats 记数）；同时 `stats.controller.StatsController` → `link.service.ShortLinkService`（stats 查 link 的链主）。两个类各自依赖对方包里**不同的类**。
- `stats.service.ClickStatsService` → `webhook.service.WebhookService`（消费事务内登记投递）；同时 `webhook.service.WebhookService` → `stats.support.ClickDimensions` 与 `stats.mq.ClickEvent`（工具类与事件契约）。
- `security.ApiKeyAuthFilter` → `apikey.service.ApiKeyService`；同时 `apikey` 的 Controller/Service → `security.UserContext`。

所以本项目的真实边界纪律是（据代码归纳）：**包不是严格的分层单位，类与类的调用方向才是**。可复用的下沉（entity、dto、support 工具、common），业务协作走对方 Service 的公开方法，且保持「Service 调 Service」单向。画依赖图时按类画，别按包画。

启动类 `LinkForgeApplication` 放在根包是为了让组件扫描以它为根覆盖全项目；`@MapperScan` 显式列五个 mapper 包的原因见上文 Spring Boot 一节的事故注释。

## 数据长什么样：11 张表谁写谁读

先立规矩：**活的 schema 只认 `src/main/resources/db/migration/` 下的 V1–V7 迁移文件**（`V1__baseline_schema.sql` 到 `V7__link_access_password.sql`），由 Flyway 在启动时按版本号执行。仓库里另有 `src/main/resources/db/schema.sql`，文件头第一行写明 `REFERENCE ONLY`：它只是旧卷形态的参考件，服务一个 Flyway 基线测试，别往里加 DDL，也别拿它当真相。

11 张表（`grep "CREATE TABLE" src/main/resources/db/migration/*.sql` 可数）分四组。

### 身份两张：lf_tenant、lf_user

- `lf_tenant`（租户）：写 `AuthService.register`（注册即建新租户加首个用户，一个事务）和 `BootstrapDataLoader`；读登录校验。`uk_tenant_name` 唯一索引是并发注册的兜底——撞名靠捕获 `DuplicateKeyException` 返回 409，不是先查后插。
- `lf_user`：同上两处写；读登录。`uk_tenant_username (tenant_id, username)` 保证用户名只在租户内唯一，不同租户可以有同名用户。
- 生命周期：逻辑删除（`deleted` 列），基本只增不改（状态位除外）。

### 短链主表一张：lf_short_link

- 写：`ShortLinkService` 的 create/update/setStatus/delete。读：管理面的 `getOwnedLink`、跳转热路径的 `ShortLinkRowLoader`、Bloom 播种的 `selectAllCodes`。
- 三个关键设计，每个对应一次真实演进：`uk_code` 唯一索引是随机码撞码与自定义码抢占的并发兜底（撞了就换码重试或 409）；V4 把 `code` 列的排序规则钉成 `utf8mb4_0900_bin`（区分大小写且不忽略尾随空格），因为大小写不敏感的默认排序规则会让「Bloom 开关一拨，行为就变」；V7 加 `access_password_hash` 列，密码哈希不出数据库。
- 生命周期：逻辑删除。删了行还在，码还占着 `uk_code`，永不复用。

### 点击管道五张：lf_click_log、lf_outbox、lf_consumed_event、lf_click_daily、lf_click_dim_daily

- `lf_click_log`（明细）：写 `ClickRecordService`（跳转事务内同步写，可开关）与 `ClickStatsService.consume`（同步写关闭时由消费端补明细）；读统计查询（明细分页、精确 UV）与对账扫描。只追加，永不更新删除。V2 加的 `event_id` 列标记行是热路径写的（NULL 或 `log-{id}`）还是消费端补的（UUID）。
- `lf_outbox`（发件箱）：写 `ClickRecordService`（插 PENDING）与 `ClickOutboxRelay`（`markSent`/`markRetry`/`markFailed`）；读 relay 的 `selectPendingBatch` 与 `BacklogGauges` 的 COUNT。生命周期 PENDING → SENT（或超 30 次重试后 FAILED 留查）。`idx_outbox_pending (status, next_attempt_at, id)` 就是为 relay 那条「取到期 PENDING 行」的扫描建的。
- `lf_consumed_event`（消费幂等）：主键就是事件 ID。写与读都是 `ClickStatsService.consume`：插入成功才算第一次消费，撞主键（`DuplicateKeyException`）就是重复事件，直接跳过。只增不删。
- `lf_click_daily`（按天聚合）：写 `ClickDailyMapper.incrementClick` 的 `ON DUPLICATE KEY UPDATE clicks = clicks + 1`；读 `ClickStatsService.stats`。`uk_daily_link_date (link_id, stat_date)` 保证一天一行，累加靠这个唯一索引转成原子更新。
- `lf_click_dim_daily`（维度聚合，V3/A8）：写与 `lf_click_daily` 在同一个去重事务里；读 `breakdown` 接口。维度目前两种：`device`、`referer_host`（解析在 `src/main/java/com/linkforge/stats/support/ClickDimensions.java`）。`uk_dim_link_date_value` 四列唯一。

### 开放能力三张：lf_api_key、lf_webhook_endpoint、lf_webhook_delivery

- `lf_api_key`（V5/B4）：写 `ApiKeyService.create/revoke`；读 `ApiKeyService.authenticate`（`ApiKeyAuthFilter` 每个带 Key 的请求查它）。只存整串明文 Key 的 SHA-256 十六进制（`key_hash CHAR(64)`，`uk_api_key_hash` 唯一），明文只在创建响应里出现一次。配额列 NULL 表示回落到全局默认值。
- `lf_webhook_endpoint`（V6/B5）：写 `WebhookService.upsert/rotateSecret/delete`（每租户一个端点）；读投递 relay。`secret` 列存 AES-GCM 密文，主密钥只在环境变量里。
- `lf_webhook_delivery`：写两处——`WebhookService.recordClickDelivery` 在消费事务内落 PENDING 行（`uk_webhook_endpoint_event (endpoint_id, event_id)` 是幂等兜底），`WebhookDeliveryRelay` 推送后标 SENT/FAILED 或排期重试；读 relay 与积压采样。它是消费事务的产物，不是请求的产物。

一张表记住「谁写谁读」的好处：当你怀疑某张表数据不对时，候选写入者立刻收敛到一两个类；当你要改表结构时，立刻知道要读哪些调用方。

## 一次请求怎么穿过四层

项目的分层是固定的四层：**filter（过滤器）→ controller → service → mapper**。filter 由 Servlet 容器在进入框架路由前调用；controller 把 HTTP 翻译成方法调用；service 装业务规则；mapper 只管一行 SQL。依赖方向严格向下：上层持有下层的引用，下层不知道上层存在。

最小可运行的模型如下（完整文件，可复制运行）：

```java
public class LayerDemo {

    // Layer 1 (lowest): data access. Knows nothing about callers above it.
    static class LinkMapper {
        String findUrlByCode(String code) {
            return code.equals("demo01") ? "https://example.com" : null;
        }
    }

    // Layer 2: business rules. Holds a mapper reference; never the reverse.
    static class LinkService {
        private final LinkMapper mapper;

        LinkService(LinkMapper mapper) {
            this.mapper = mapper;
        }

        String resolve(String code) {
            String url = mapper.findUrlByCode(code);
            return url == null ? "404" : "302 " + url;
        }
    }

    // Layer 3 (top): protocol. Holds a service reference; never the reverse.
    static class LinkController {
        private final LinkService service;

        LinkController(LinkService service) {
            this.service = service;
        }

        String get(String code) {
            return "controller got: " + service.resolve(code);
        }
    }

    public static void main(String[] args) {
        LinkMapper mapper = new LinkMapper();            // build bottom-up
        LinkService service = new LinkService(mapper);   // upper layer takes a lower-layer reference
        LinkController controller = new LinkController(service);
        System.out.println(controller.get("demo01"));
        System.out.println(controller.get("nope"));
    }
}
```

```text
controller got: 302 https://example.com
controller got: 404
```

逐行解释：`main` 从最底层开始手工 new，上层构造器吃下层引用，装配出一条链。调用只发生一次且方向向下（controller 调 service，service 调 mapper），返回值逐层向上。`demo01` 命中返回 302 加地址，`nope` 未命中返回 404。这个手工装配就是 Spring 依赖注入替你做的事——Spring 在启动时扫描这些构造器，自动完成同样的 new 与传递（[30-spring-core.md](30-spring-core.md)）。

把这个模型套到项目里，走两条真实路径。

**路径一：`GET /r/{code}`（跳转，匿名可访问）**

1. 内嵌 Tomcat 接收请求，先过 Spring Security 过滤器链。`SecurityConfig` 对 `/r/**` 的 GET、HEAD 与 unlock POST 全部 `permitAll`，无令牌的请求以匿名身份放行。
2. `RedirectController.redirect`（controller）只做协议翻译：调 `ShortLinkService.resolveForGet`，拿到 `RedirectOutcome`（一个 sealed 接口，四种结果：跳转、封锁、需解锁、警示页）后渲染成对应的 HTTP 响应。
3. `ShortLinkService.resolveTarget`（service）按顺序过门禁：限流（Redis Lua）→ Bloom 判「肯定不存在」→ 负缓存 → L1（Caffeine）→ L2（Redis）→ `SingleFlight.run` 合并并发读 → `ShortLinkRowLoader.load` → mapper。
4. `ShortLinkMapper.selectOne`（mapper）由 MyBatis-Plus 生成 `SELECT * FROM lf_short_link WHERE code = ? AND deleted = 0` 执行。
5. 行状态、过期、目标合法性、域名门禁、密码门全过后，`ClickRecordService.recordClick`（stats 域的 service）在一个事务里写明细与发件箱。

**路径二：`POST /api/links`（创建，需登录）**

1. 过滤器层：`JwtAuthFilter` 解析 `Authorization: Bearer` 头，验签后把 `AuthUser` 放进 `UserContext` 并授予 `ROLE_USER`；`SecurityConfig` 的授权规则要求 `/api/links/**` 具备 `USER` 或 `API_KEY` 角色。
2. `ShortLinkController.create`：`@Valid` 触发 `CreateShortLinkRequest` 上的 `@NotBlank`/`@Size` 校验，失败由 `src/main/java/com/linkforge/common/exception/GlobalExceptionHandler.java` 统一转成 400 JSON。
3. `ShortLinkService.create`：限流 → 幂等键占位 → URL 校验与域名门禁 → 随机码或自定义码 → `ShortLinkMapper.insert`（`DuplicateKeyException` 驱动撞码重试与 409）→ `codeBloom.put` → 写两级缓存 → 幂等键落短码。
4. 返回 `ApiResponse.ok(...)` 统一壳。

两条路径的过滤器不一样（一个匿名放行、一个强制验签），controller 很薄，service 承载全部业务，mapper 只有数据访问——四层各司其职。每一步的变量值与断点位置在 [81-linkforge-flows.md](81-linkforge-flows.md) 的链 3、链 4。

## 配置在哪改：application.yml 三段与 LinkForgeProperties

`application.yml` 按profile 分三段（`---` 分隔）：

1. **全局段**：应用名、默认 profile 为 dev、调度池 6、Flyway 开启、Actuator 暴露哪些端点、哪些指标要分桶。
2. **dev 段**：MySQL/Redis/RabbitMQ 连接串与口令（全部环境变量占位符）、Jackson 时区、MyBatis-Plus 全局配置（驼峰映射、逻辑删除值）、以及 `linkforge.*` 全部业务参数——限流各入口的阈值（注册、创建、跳转、密码解锁、API Key 每分钟配额都走滑动窗口）、Bloom 参数、singleflight、L1、API Key 配额、Webhook、MQ 开关、bot 过滤表、域名名单、密码短链、抓取令牌。
3. **prod 段**：关静态页、关 springdoc、健康详情降为 `when_authorized`。

业务参数不是散在代码里的，而是集中收进 `src/main/java/com/linkforge/config/LinkForgeProperties.java` 的 14 个嵌套配置类。环境变量 `LINKFORGE_BLOOM_FPP` 能覆盖 `linkforge.bloom.fpp`，这套映射由 Spring Boot 的 relaxed binding 完成。`.env.example` 列出了全部可用变量。配置注释里保留着每项参数的来源轮次编号——配置文件本身就是决策档案。

## 怎么验证它活着：测试、CI、演练

- 测试类以 `*Test` 走 Surefire、以 `*IT` 走 Failsafe（Testcontainers）。v2.0.0 发布口径 211 例（Surefire 90 + Failsafe 119 + 时区独立 fork 2）。当前 tip 在此基础上多 9 个纯单测，Surefire 99，合计 220。分层策略见 [60-testing-ci.md](60-testing-ci.md)。
- CI 是 `.github/workflows/ci.yml`：push 与 PR 触发 `mvn -B verify`，ubuntu 自带 Docker 跑 Testcontainers。
- 本地演练五个脚本：`scripts/smoke.sh`、`scripts/mq-drill.sh`、`scripts/outbox-drill.sh`、`scripts/bench.sh`、`scripts/migrate.sh`，用法在 [22-tools.md](22-tools.md)。
- 观测栈一键起：`docker compose --profile observability up -d`，面板细节在 [61-docker-deploy.md](61-docker-deploy.md)。

## 这张地图的边界：本篇不讲什么，以及诚实边界

本篇是地图，三件事刻意不讲：

1. **调用链的逐步走读**（变量、断点、每步发生了什么）→ [81-linkforge-flows.md](81-linkforge-flows.md) 的十条链。
2. **决策论证**（为什么 302 不是 301、为什么随机码不是发号器、fail-open 矩阵）→ [82-linkforge-decisions.md](82-linkforge-decisions.md)。
3. **使用者的上手步骤**（五步跑通、API 逐个列）→ [../../README.md](../../README.md)。

项目自己承认的边界（来自 `docs/backlog.md` 与 README，面试可以主动讲）：

- 文档里的压测数字是 Windows 历史实测（固定窗口限流时期，早于当前 tip），不是当前 Linux 性能。B10 Linux bench 仍未跑。
- Bloom 与 Caffeine L1 都是进程内结构，多实例不共享：Bloom 有假阴性 404，L1 在 TTL（默认 10 秒）内其他实例可能读到旧 URL。`linkforge.bloom.*` / `linkforge.l1.*` 只计本进程，不是集群命中率。空值缓存和 Redis L2 是共享的。本版不做 RedisBloom / 分布式 L1。Webhook 防 SSRF 未覆盖 DNS rebinding。
- 没有告警规则、没有链路追踪、没有熔断组件、应用未容器化。
- 明确不做：AI、PostgreSQL、微服务拆分、K8s、云部署。

## 自测

1. 为什么跳转热路径写 RabbitMQ 前要先写一张 `lf_outbox` 表？
   答：数据库事务和消息发送是两个系统，无法一起回滚。先在事务里落一条 PENDING 发件箱行，relay 收到 broker 确认才标 SENT；崩溃或 broker 不可用只是晚发，不丢。
2. `common` 包和 `config` 包在依赖图里是什么位置？
   答：两个叶子包。它们不 import 任何业务包，只被业务包依赖，所以最稳定；改动它们的成本最高，被所有域共享。
3. 包级 import 出现了 link 与 stats 互相 import，这是否说明分层被破坏？
   答：不是。逐类看是单向的：`ShortLinkService` 调 `ClickRecordService`（记点击），`StatsController` 调 `ShortLinkService`（查链主），没有两个类互相依赖。边界纪律在类与类的调用方向上，不在包粒度上。
4. Bloom 说「可能有」时为什么不直接判错？
   答：布隆过滤器只会误报（把不存在的报成可能存在）不会漏报。「肯定不存在」才能当权威直接 404；「可能存在」必须落到缓存加数据库的路径，由数据库做正确性裁决。
5. `lf_consumed_event` 和 Redis 幂等键分别防什么重复？
   答：`lf_consumed_event`（数据库主键去重）防的是 MQ 重复投递与对账重放导致的重复计数，作用在消费端统计；Redis 的 `idem:` 键防的是用户对同一 `Idempotency-Key` 重试创建短链，作用在写端点的入口。两者作用点不同，不可互换。

## 下一步

- 把地图走成路线：十条调用链逐步追踪见 [81-linkforge-flows.md](81-linkforge-flows.md)。
- 追问「为什么这么设计」：决策与故障案例见 [82-linkforge-decisions.md](82-linkforge-decisions.md)。
- 需要跑起来对照：README 的五步跑通见 [../../README.md](../../README.md)。
