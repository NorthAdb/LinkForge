# 代码导航索引（Code Navigation Index）

> 用法：从「我想看 / 改某类东西」出发，先查本索引定位类与课程，再进课程正文。
> 源码路径一律相对仓库根（`D:\LinkForge`），如 `src/main/java/com/linkforge/...`；行号口径基线 `911640c` → `01f0b5a`（docs-only，源码未变），拿不准的标「推断」。
> 全部类的课程去向另见类覆盖矩阵（同目录，Phase E 产出）；课程全表见 [03-curriculum-roadmap.md](../03-curriculum-roadmap.md)。

## 一、按包（12 个一级包 + 根）

### 1. 根：应用装配
- `src/main/java/com/linkforge/LinkForgeApplication.java`：`@SpringBootApplication` 入口，组件扫描覆盖全部子包。
- 课程 [1.2 启动类、IoC 与依赖注入](../modules/01-spring-boot/02-bootstrap-ioc-di.md)。
- 注意：pom 里 spring-boot-maven-plugin 开了 build-info，版本号经 `OpenApiConfig` 注入 OpenAPI。

### 2. apikey：开放能力
- 职责：API Key 生命周期与认证过滤。`ApiKeyController`(54 行) / `ApiKeyService`(165) / `ApiKey` 实体 / `ApiKeyMapper`。
- 认证过滤器 `ApiKeyAuthFilter`(162 行) 放在 security 包（本包共 5 文件）。
- 课程 [12.1 API Key 全流程](../modules/12-api-key/01-apikey-full.md)、[advanced/10 API Key 认证](../advanced/10-api-key-auth.md)。
- 改动注意：明文只在创建响应返回一次，库里只有 SHA-256 `key_hash`（ApiKeyService.java:119-122、:152-164）；撤销要幂等。
- 改动注意：scope 双闸 USER|TENANT，TENANT 需 ADMIN（:40-51）；配额是分钟滑窗 + 固定日窗双闸（ApiKeyAuthFilter.java:112-137）。
- 改动注意：`touchLastUsed` 有 SETNX 60 秒节流，勿改成每请求 UPDATE。

### 3. bootstrap：引导数据
- 职责：`BootstrapDataLoader`(71 行) 建 demo 租户与 admin，只在显式配置时执行（`LINKFORGE_BOOTSTRAP_PASSWORD` 必填无默认）。
- 课程 [1.3 配置体系与 Profile](../modules/01-spring-boot/03-configuration-profiles.md)、[advanced/01 注册与登录全链路](../advanced/01-registration-login.md)。
- 注意：test profile 下关闭（application-test.yml），不要给它加无开关的写入。

### 4. common：通用组件
- 职责：`result/` 统一响应壳 `ApiResponse`、`PageResponse`。
- `exception/` `BizException` 家族与 `GlobalExceptionHandler` 全映射（RateLimitedException→429+Retry-After，:20-25）。
- `ratelimit/` `RedisRateLimiter`(105)、`RateLimitKeys`、`sliding_window.lua`（限流核心三件）。
- `util/` `ClientIps`（XFF 默认不信任）、`CsvFormat`（RFC4180 转义）。
- 课程 [2.1 统一响应与全局异常](../modules/02-common/01-unified-response-exceptions.md)、[2.2 日志与工具类](../modules/02-common/02-logging-utils-clientip.md)、[9.1 滑动窗口限流](../modules/09-rate-limit/01-sliding-window-lua.md)。
- 改动注意：改 `ApiResponse` 形状要同步改认证过滤器里 5 处硬编码 JSON（ApiKeyAuthFilter.java:36-37 注释自述双写漂移）。
- 改动注意：`RateLimitKeys` javadoc 记录 WRONGTYPE 事故，键名空间勿乱改（:13-15）。

### 5. config：配置与装配
- 职责：`LinkForgeProperties`(239 行，14 个内部类，`linkforge.*` 全部业务配置)；`RedisConfig`（StringRedisTemplate + 监听容器）；`MybatisPlusConfig`（分页插件）；`SchedulingConfig`（仅 @EnableScheduling）；`OpenApiConfig`。
- 课程 [1.3 配置体系与 Profile](../modules/01-spring-boot/03-configuration-profiles.md)、[8.1 Redis 使用面与键空间](../modules/08-redis-cache/01-keys-and-ttl.md)。
- 注意：application.yml 是多文档结构，业务配置在 dev 文档内（:102-239），prod 文档不含 datasource/jwt 段（多文档作用域陷阱）；`mq.async-click-enabled` 的 yml 默认 true 与 Java 字段默认 false 不一致，已登记进 [issues-and-discrepancies.md](issues-and-discrepancies.md)。

### 6. link：短链核心域（37 文件）
- 职责：`ShortLinkService`(1025 行，域服务主线，17 个依赖 :100-116)；`ShortLinkController`/`RedirectController` 两个入口；`RedirectOutcome` sealed 结果。
- `support/` 18 个协作类，缓存与并发侧：`CacheBus`、`CodeBloomFilter`(210)、`SingleFlight`(120)、`UrlLocalCache`、`ShortLinkRowLoader`。
- `support/` 门禁与安全侧：`ClickFilter`、`PhishingPolicy`、`TargetPolicy`、`LinkAccessTokens`、`GatePages`。
- `support/` 校验与纯函数侧：`UtmMerger`、`VariantPicker`、`LinkValidations`、`ShortCodes`、`ShareTokenSigner`、`CsvImportParser`、`CsvFormat`、`QrParams`。
- 关键方法地图（ShortLinkService）：create :129-176、doCreate :203-283、update :450-521、resolveForGet :565-585、resolveTarget :601-712、unlock :753-792、delete :546-558、getOwnedLink :890-903。
- 课程 [6.x 短链核心业务](../modules/06-short-link/01-data-model.md)起、[7.x 跳转系统](../modules/07-redirection/01-decision-chain.md)起、[8.x Redis 与缓存](../modules/08-redis-cache/01-keys-and-ttl.md)起。
- 配图 [04-core-class-relations](../diagrams/archify/html/04-core-class-relations.html)、[12-redirect-decision](../diagrams/archify/html/12-redirect-decision.html)。
- 改动注意：改 ShortLinkService 的写路径记得 SingleFlight 的 peek/两参 forget 纪律（update :495/:512、setStatus :535/:537、delete :548/:554）。
- 改动注意：提交后要维护缓存三件套 clearAbsent → cacheUrl 写穿 → CacheBus 广播（doCreate :266-269）。
- 改动注意：B9 判的是 UTM 合并后的最终 URL（:207、:798-807），判错对象是常见翻车点。

### 7. observability：可观测
- 职责：`BacklogGauges`(58) outbox/webhook 积压 3 个 Gauge，定时采样 15 秒；`HttpUriTagAllowList`(51) uri tag 白名单 MeterFilter。
- 课程 [14.2 业务指标与 Grafana](../modules/14-observability/02-metrics-gauges-grafana.md)、[14.1 Actuator 与 Micrometer](../modules/14-observability/01-actuator-micrometer.md)。
- 注意：采样与抓取解耦是刻意的（DB 查询不挂在抓取路径上）；新 uri 路径要进白名单防基数爆炸。

### 8. retention：保留清理
- 职责：`SentRowCleanup`(73) 定时批量删 SENT 状态的 outbox/webhook 行，有界批次 maxPasses。
- 课程 [14.3 定时任务地图](../modules/14-observability/03-scheduled-tasks-map.md)、图 [08-scheduled-tasks](../diagrams/archify/html/08-scheduled-tasks.html)。
- 注意：双时钟口径，outbox 清理用 DB `NOW()`，webhook 清理用 JVM cutoff，两处 mapper 注释对照，勿对调。

### 9. security：过滤器与身份
- 职责：`SecurityConfig`(100) 装配链与路径-角色矩阵(:61-69)、401/403 JSON(:72-83)。
- `JwtAuthFilter`(60)、`JwtService`(46)、`ApiKeyAuthFilter`、`MetricsScrapeTokenFilter`(66)、`QrShareAuthFilter`(73)、`AuthUser`、`UserContext`。
- 过滤器顺序：Metrics → Jwt → ApiKey → QrShare（SecurityConfig.java:89-92）。
- 课程 [5.1 安全过滤器链](../modules/05-security/01-security-filter-chain.md)、[5.2 JWT 签发与校验](../modules/05-security/02-jwt-auth.md)、[5.3 UserContext 与授权](../modules/05-security/03-usercontext-authz.md)。
- 配图 [10-auth-sequence](../diagrams/archify/html/10-auth-sequence.html)。
- 改动注意：`/actuator/prometheus` 的角色规则必须排在 `/actuator/**` 之前(:67)。
- 改动注意：两个认证 filter 的 finally 必须双清 UserContext 与 SecurityContext（JwtAuthFilter.java:55-58），漏一条就是线程串号。

### 10. stats：点击统计管道（25 文件）
- 职责：热路径 `ClickRecordService`(91) 同事务双写明细+outbox；中继 `ClickOutboxRelay`(79)；消费 `ClickEventConsumer`(28) + `ClickStatsService`(154)；对账 `ClickLogReconciler`(55)。
- MQ 侧：`RabbitClickEventPublisher`(68，correlated confirm + returns)、`ClickEventPublisher` 接口、`ClickEvent`(29) 契约、`ClickEventConsumer`(28)。
- Mapper 5 个（ClickLog/Outbox/ClickDaily/ClickDimDaily/ConsumedEvent）+ `ClickDimensions`(136) 纯函数分桶。
- 课程 [10.1 拓扑与确认机制](../modules/10-rabbitmq-outbox/01-topology-confirm.md)、[10.2 Outbox 与 Relay](../modules/10-rabbitmq-outbox/02-outbox-relay.md)、[10.3 消费幂等与死信](../modules/10-rabbitmq-outbox/03-consumer-idempotency-dlq.md)、[11.x 点击统计系统](../modules/11-statistics/01-click-record-pipeline.md)起。
- 配图 [14-click-pipeline-dataflow](../diagrams/archify/html/14-click-pipeline-dataflow.html)、[15-outbox-lifecycle](../diagrams/archify/html/15-outbox-lifecycle.html)。
- 改动注意：消费幂等三件套缺一不可（主键 INSERT 判重 + 确定性 eventId + 明细行 event_id 回填标记）。
- 改动注意：fail-open catch 必须留在事务外（ShortLinkService.resolveForGet :571-580，事务已回滚后只兜 302）。
- 改动注意：`webhookService.recordClickDelivery` 挂在消费事务末尾，失败整体回滚进 DLQ（ClickStatsService.java:106-109）。

### 11. user：用户与租户
- 职责：`AuthController`(33)、`AuthService`(101) 注册/登录；`Tenant`/`UserAccount` 实体与 Mapper。
- 课程 [5.2 JWT 签发与校验](../modules/05-security/02-jwt-auth.md)、[advanced/01 注册与登录全链路](../advanced/01-registration-login.md)。
- 注意：注册是事务建租户+首用户，user 插入失败靠回滚防孤儿占名（AuthService.java:59-63）；登录无 IP 限流且 BCrypt 只在租户有效时执行，计时侧信道是课堂讨论点。

### 12. webhook：对外投递（14 文件）
- 职责：`WebhookService`(327) 端点 CRUD + 入队分流（PER_CLICK :280-305 / AGGREGATE :313-326）。
- 聚合与投递：`WebhookAggregateFlusher`(237，5 秒 sweep)、`WebhookDeliveryRelay`(167，2 秒投递轮)。
- 安全侧：`WebhookSigner`(35)、`WebhookSecretCipher`(106)、`WebhookTargetGuard`(169)、`PinnedHttpSender`(101)、`AggregateWindows`(54)。
- 课程 [13.1 端点配置与密钥加密](../modules/13-webhook/01-endpoints-and-cipher.md)、[13.2 投递、签名、重试与聚合](../modules/13-webhook/02-delivery-signing-retry.md)、[13.3 SSRF 防护](../modules/13-webhook/03-ssrf-and-aggregate.md)、[advanced/11 Webhook 投递与重试](../advanced/11-webhook-delivery-retry.md)。
- 配图 [16-webhook-delivery-lifecycle](../diagrams/archify/html/16-webhook-delivery-lifecycle.html)。
- 改动注意：master key 不可用整批跳过且不耗 attempts（WebhookDeliveryRelay.java:71-76）；`uk_webhook_tenant` 决定每租户仅一个 endpoint。
- 改动注意：SSRF 防护是「保存 check + 发送 resolve 钉死 + 禁重定向」闭环，拆开任何一环都破防；调度列一律 DB 时钟。

## 二、按任务（常见改动场景）

1. **加一个跳转门禁步骤**：入口 `resolveTarget`（ShortLinkService.java:601-712），12 步顺序即契约，新步骤要想清失败语义（404/410/403/200 warn）与插入位置；渲染配合 `GatePages` 与 `RedirectOutcome` sealed 分支。同步更新 [7.1 跳转判定链](../modules/07-redirection/01-decision-chain.md)、图 [12-redirect-decision](../diagrams/archify/html/12-redirect-decision.html)，测试参照 PhishingGateIT。
2. **加一个 Redis 键**：命名走 `RateLimitKeys` 式前缀（`{prefix}sw:{logical}`，RateLimitKeys.java:13-15）；TTL 必须配抖动（jitteredTtlSeconds :919-924）；缓存类键要接 CacheBus 广播失效并补进键清单 [8.1](../modules/08-redis-cache/01-keys-and-ttl.md)；数据流归档图 [06-redis-data-flow](../diagrams/archify/html/06-redis-data-flow.html)。
3. **加一个定时任务**：`@Scheduled` + `SchedulingConfig`（共享池 size=6，yml 配容量）；间隔写法参照 `ClickOutboxRelay`（fixedDelay 2000/initial 3000，每 tick 重读开关）；时钟规则：调度列（next_attempt_at / window_start）一律 DB 时钟；采样类任务参照 `BacklogGauges`。登记进 [14.3 定时任务地图](../modules/14-observability/03-scheduled-tasks-map.md)。
4. **加一张表（Flyway）**：新建 `src/main/resources/db/migration/V11__<名称>.sql`，只增不改历史版本；baseline-on-migrate=true、baselineVersion=1（FlywayBaselineIT 断言遗留库恰 1 条 BASELINE@1）；`schema.sql` 是 REFERENCE ONLY 不参与迁移。流程见 [4.2 Flyway 与 schema](../modules/04-mybatis-mysql/02-flyway-migrations.md)、图 [05-database-erd](../diagrams/archify/html/05-database-erd.html)。
5. **加一个 API 端点**：Controller 返回 `ApiResponse` 壳 → 异常走 `GlobalExceptionHandler` 码表（非 0 code 映射 400-599）→ 在 `SecurityConfig` 路径-角色矩阵(:61-69) 加规则并注意顺序 → 入参 `@Valid`。见 [3.1 Controller 与 REST 设计](../modules/03-spring-mvc/01-controllers-rest.md)、[5.1 安全过滤器链](../modules/05-security/01-security-filter-chain.md)。
6. **加一个指标**：注入 `MeterRegistry`；形态选择见 [14.2](../modules/14-observability/02-metrics-gauges-grafana.md)；`http.server.requests` 的新 uri 必须进 `HttpUriTagAllowList` 白名单；tag 取值要有界，排障 tag（如 revoked）只进 metrics 不进对外文案（ApiKeyAuthFilter.java:81-88 范式）。
7. **改限流参数**：配置在 `LinkForgeProperties` + application.yml dev 文档；算法与 Retry-After 计算（(retryMs+999)/1000，RedisRateLimiter.java:55-56）见 [9.1](../modules/09-rate-limit/01-sliding-window-lua.md)；改完跑 SlidingWindowRateLimiterIT；fail-open 打点 meter `linkforge.ratelimit.failopen{reason}` 勿删。
8. **加一个 MQ 消费方**：`RabbitMqConfig` 四件套（Exchange/Queue 挂 DLX/binding，:17-62）；监听器干净返回=ack、抛异常进 DLQ（default-requeue-rejected=false）；消费逻辑照抄幂等三件套（`lf_consumed_event` 主键判重）；Jackson converter 复用容器 ObjectMapper 的陷阱见 :57-62 注释与 [10.3](../modules/10-rabbitmq-outbox/03-consumer-idempotency-dlq.md)。
9. **改点击 payload**：契约在 `ClickEvent` 10 字段（ClickEvent.java:17-29）；要动三处：写入端 `ClickRecordService.recordClick`(:40-74)、消费端 `ClickStatsService.consume`(:66-113)、如需透出再加 Webhook 派生 payload（WebhookService :280-305）；eventId 前缀语义（log- / evt_ / digest-）是幂等与对账的锚点，改动需迁移明细表 event_id 列并同步 [11.1](../modules/11-statistics/01-click-record-pipeline.md) 与 [10.3](../modules/10-rabbitmq-outbox/03-consumer-idempotency-dlq.md)。
10. **加一个测试（UT vs IT 怎么选）**：纯函数、无容器依赖（校验、签名、分桶、抽签）→ UT，surefire 跑 `*Test`；需要 HTTP/数据库/Redis/MQ 真行为 → IT，failsafe 跑 `*IT` 继承 `AbstractIntegrationTest`（单例三容器）；三个 webhook IT 要 `@DirtiesContext(AFTER_CLASS)`（relay 线程跨类污染的真实事故）；断言纪律：同步明细表可精确断言，异步聚合只做收敛断言。见 [15.1 测试金字塔](../modules/15-testing-deployment/01-test-pyramid.md)、图 [09-test-ci-pipeline](../diagrams/archify/html/09-test-ci-pipeline.html)。
11. **本地跑 IT 的环境口径（Windows 看 CI）**：IT 需要 Docker daemon（Testcontainers），本地没有就依赖 GitHub Actions（.github/workflows/ci.yml：checkout → JDK17 → 缓存 ~/.m2 → `mvn -B verify`，push main/feat*/fix* 与全 PR 触发）；`WebhookRetryClockIT` 由 failsafe 第二执行以 Asia/Shanghai 时区 fork 单跑（pom :219-249）。见 [15.2 Maven 与 CI](../modules/15-testing-deployment/02-maven-ci.md)、[15.3 Compose 与故障演练](../modules/15-testing-deployment/03-compose-and-drills.md)。
12. **加一个配置项**：`LinkForgeProperties` 加内部类字段 + application.yml dev 文档补默认值 + test 文档补隔离值；必填无默认清单（SPRING_DATASOURCE_PASSWORD、JWT_SECRET、LINKFORGE_BOOTSTRAP_PASSWORD 等）见 [1.3 配置体系与 Profile](../modules/01-spring-boot/03-configuration-profiles.md)；注意 yml 多文档作用域陷阱（业务配置只写在 dev 文档内）。
13. **改短码规则 / 加保留字**：`LinkValidations`（码正则 `^[A-Za-z0-9_-]{4,16}$` :15，保留字全 ≥4 字符 :16-21）+ `ShortCodes`（62 字符表，CODE_ATTEMPTS=16）；保留字与 Bloom/空值缓存的关系见 [8.3](../modules/08-redis-cache/03-negative-cache-bloom.md)，流程见 [6.2](../modules/06-short-link/02-create-flow.md)。
14. **改缓存策略（TTL/层）**：正负缓存 TTL 与抖动在 `LinkForgeProperties.ShortLink`(:103-112)；L1 容量 10000/10s(:160-167)；写路径顺序固定为 clearAbsent → 写穿 → 广播（doCreate :266-269），更新侧双删不二次广播（:515-519）；L1 订阅端跳过自己 origin 防自伤，Bloom 刻意不跳（幂等防丢）。见 [8.2](../modules/08-redis-cache/02-l1-l2-write-through.md)、[8.3](../modules/08-redis-cache/03-negative-cache-bloom.md)。

## 三、按调试（排障入口）

| 症状 | 先查什么 | 依据与课程 |
|------|----------|-----------|
| 401 | 三个来源对号：entryPoint "unauthorized"（SecurityConfig.java:72-76）/ 坏 JWT 直写 "invalid token"（JwtAuthFilter.java:51-54）/ 坏 key 或已撤销 "invalid api key"（ApiKeyAuthFilter.java:82,88，对外同文案、metrics tag 区分 revoked） | [5.1](../modules/05-security/01-security-filter-chain.md)、[12.1](../modules/12-api-key/01-apikey-full.md)、图 [10-auth-sequence](../diagrams/archify/html/10-auth-sequence.html) |
| 404 | 跳转链按序查：Bloom 拒（resolveTarget :615-618，meter bloom.rejected）→ absent 命中（:623-626）→ 行不存在（:650-654）→ status≠1（:656-658）；管理面 404 来自 requireExisting(:882-888) 与越权 getOwnedLink(:890-903)，越权刻意 404 不说 403 | [7.1](../modules/07-redirection/01-decision-chain.md)、[5.3](../modules/05-security/03-usercontext-authz.md)、图 [12-redirect-decision](../diagrams/archify/html/12-redirect-decision.html) |
| 429 | 查键：`RateLimitKeys.sw()` = `linkforge:rl:sw:{logical}`，entry 有 create/user/redirect/ip/import；API Key 是 `sw(min:{keyId})` 分钟滑窗 + `day:{keyId}:{yyyyMMdd}` 固定日窗（Asia/Shanghai）；Redis 里滑窗键是 ZSET；WRONGTYPE 先看键类型（RateLimitKeys.java:13-15 注释） | [9.1](../modules/09-rate-limit/01-sliding-window-lua.md)、[9.2](../modules/09-rate-limit/02-idempotency-and-quotas.md) |
| 统计不对 | 顺序查：sync/async 开关（UV 依赖 sync 双写，ClickStatsService.java:122-125）→ B2 过滤（meter click.skipped，prefetch 不计数）→ `lf_consumed_event` 是否已收（DuplicateKey 早退）→ 明细行 event_id 是否回填（NULL=未消费，Reconciler 扫描条件）→ `lf_click_daily` upsert 是否命中唯一索引；对账等 `ClickLogReconciler` 30 秒一轮 | [11.1](../modules/11-statistics/01-click-record-pipeline.md)、[11.3](../modules/11-statistics/03-stats-api-reconciler.md)、[advanced/09](../advanced/09-stats-reconcile.md)、图 [14-click-pipeline-dataflow](../diagrams/archify/html/14-click-pipeline-dataflow.html) |
| 积压 gauge | `BacklogGauges` 3 个 gauge（outbox/webhook，15 秒采样），看 `/actuator/prometheus`（带 scrape-token，fail-closed）或 Grafana（compose 3001 端口）；积压增长伴随 meter `outbox.failed` 说明有行进终态；MQ 侧看 RabbitMQ 管理 15672 队列深度 | [14.2](../modules/14-observability/02-metrics-gauges-grafana.md)、[10.2](../modules/10-rabbitmq-outbox/02-outbox-relay.md) |
| DLQ | 队列 `linkforge.click.dlq`（RabbitMQ 管理 UI 15672 查看与重派）；入队原因：消费抛异常 + default-requeue-rejected=false；Jackson converter 缺 JavaTimeModule 是历史全量入 DLQ 的坑（RabbitMqConfig.java:57-62）；重放走 Outbox 行 UPDATE 回 PENDING（手工重放，FAILED 不自动重试） | [10.3](../modules/10-rabbitmq-outbox/03-consumer-idempotency-dlq.md)、图 [07-rabbitmq-topology](../diagrams/archify/html/07-rabbitmq-topology.html)、[15-outbox-lifecycle](../diagrams/archify/html/15-outbox-lifecycle.html) |

## 相关文档

- 课程全表：[03-curriculum-roadmap.md](../03-curriculum-roadmap.md)；架构总览：[02-architecture-map.md](../02-architecture-map.md)。
- 术语表：[technical-glossary.md](technical-glossary.md)；差异登记：[issues-and-discrepancies.md](issues-and-discrepancies.md)；进度：[progress.md](progress.md)。
