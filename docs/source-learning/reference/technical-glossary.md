# 技术术语表（Technical Glossary）

> 组织方式：A 通用后端术语（是什么 / 本项目实例 / 去哪课）、B 本项目专有概念、C 缩写表。
> 行号口径：基线 `911640c`（调查）→ `01f0b5a`（docs-only，schema 与源码未变）。未实测的行号标「推断」。
> 术语在课程里首次出现时都会就地解释，本表用于跳读时快速回查。课程清单见 [03-curriculum-roadmap.md](../03-curriculum-roadmap.md)。

## A. 通用后端术语

### 安全与并发

### 多租户（multi-tenancy）
一套系统同时服务多个租户，数据与配额按租户隔离。本项目：全部业务表带 `tenant_id`；鉴权主体 `AuthUser` 携带 tenantId；越权访问一律返回 404 而不是 403（`ShortLinkService.getOwnedLink`，ShortLinkService.java:890-903）。
详见 [5.3 UserContext 与授权](../modules/05-security/03-usercontext-authz.md)、[advanced/04 匿名跳转 302 全链路](../advanced/04-redirect-302.md)。

### JWT（JSON Web Token）
自包含的签名令牌，服务端不存会话。本项目：`JwtService` 用 jjwt 0.12.6 做 HMAC 签发与校验（JwtService.java:21-45）；`JwtAuthFilter` 解析 Bearer 头后写入 `UserContext`，失效走直写 401。
详见 [5.2 JWT 签发与校验](../modules/05-security/02-jwt-auth.md)、图 [10-auth-sequence](../diagrams/archify/html/10-auth-sequence.html)。

### BCrypt
慢哈希密码摘要算法，自带盐值，专为对抗暴力破解设计。本项目：登录密码与短链访问密码都存 `password_hash` / `access_password_hash`，哈希永不出库（V7 引入列，V1/V7）；解锁短链时逐条 BCrypt 校验（`unlock`，ShortLinkService.java:784-787）。
详见 [5.2 JWT 签发与校验](../modules/05-security/02-jwt-auth.md)、[7.2 密码门与域名门禁](../modules/07-redirection/02-password-and-domain-gates.md)。

### ThreadLocal
线程私有的存储槽，同一线程内随处可取。本项目：`UserContext` 用静态 `ThreadLocal<AuthUser>` 存当前用户；只在两个认证过滤器 set，并在 finally 里无条件 clear，防止线程复用串号。
详见 [5.3 UserContext 与授权](../modules/05-security/03-usercontext-authz.md)。

### 幂等（idempotency）
同一操作执行多次与执行一次结果相同。本项目：消费端用 `lf_consumed_event` 主键插入判重（DuplicateKey 即已消费，ClickStatsService.java:70-78）；创建端用幂等键两段式（见「幂等键」词条）。
详见 [10.3 消费幂等与死信](../modules/10-rabbitmq-outbox/03-consumer-idempotency-dlq.md)、[9.2 幂等键与配额](../modules/09-rate-limit/02-idempotency-and-quotas.md)。

### 乐观锁 / 悲观锁
乐观锁假设冲突少，提交时校验版本；悲观锁先锁再改。本项目：实体没有 `@Version` 注解，并发收敛靠唯一索引兜底（先写、冲突即败），主键 INSERT 在 MySQL 里天然是并发屏障（推断：项目未用显式乐观/悲观锁）。
详见 [4.2 Flyway 与 schema](../modules/04-mybatis-mysql/02-flyway-migrations.md)、[10.3 消费幂等与死信](../modules/10-rabbitmq-outbox/03-consumer-idempotency-dlq.md)。

### 事务传播（transaction propagation）
事务方法互相调用时，事务如何延续或新开。本项目：`consume` 用手工 `TransactionTemplate` 包去重段（ClickStatsService.java:42-54）；webhook 入队挂在消费事务末尾，失败向上传播整体回滚进 DLQ（WebhookService.recordClickDelivery，:270-276）。
详见 [4.3 事务](../modules/04-mybatis-mysql/03-transactions.md)、[advanced/08 MQ 消费与幂等](../advanced/08-mq-consume-idempotency.md)。

### N+1 查询
一次列表查询后对每行再发一次子查询。本项目：统计聚合全部下推数据库（GROUP BY / SUM / ON DUPLICATE KEY upsert，ClickDimDailyMapper.java:32-39），分桶 upsert 是固定 4 条而非随行数膨胀（推断：作为教学对照点）。
详见 [4.1 Entity/Mapper/CRUD](../modules/04-mybatis-mysql/01-entities-mappers-crud.md)、[11.2 聚合与维度](../modules/11-statistics/02-aggregation-dimensions.md)。

### 逻辑删除（logical delete）
删除只打标记不物理移除。本项目：`@TableLogic` + `deleted` 1/0（yml mybatis-plus 段）；短链删除 = 逻辑删主行 + 硬删 variants 同事务（ShortLinkService.java:546-558）；`selectAllCodes` 故意绕过逻辑删给 Bloom 播种（ShortLinkMapper.java:13-23）。
详见 [4.1 Entity/Mapper/CRUD](../modules/04-mybatis-mysql/01-entities-mappers-crud.md)、[8.3 空值缓存与 Bloom](../modules/08-redis-cache/03-negative-cache-bloom.md)。

### 唯一索引兜底
应用层判重之外，数据库唯一索引作为并发下最后一道闸。本项目：`uk_code` 撞码重试后 409（doCreate，ShortLinkService.java:239-246）；`uk_daily_link_date` 是计数 upsert 的「幂等落桶承重墙」；`uk_outbox_event_id`、`uk_webhook_endpoint_event`、`uk_link_variant`、`uk_agg_window_link` 各守一条幂等线。
详见 [4.2 Flyway 与 schema](../modules/04-mybatis-mysql/02-flyway-migrations.md)、图 [05-database-erd](../diagrams/archify/html/05-database-erd.html)。

### 缓存

### 限流滑动窗口（sliding window log）
按时间窗精确计数，窗口随请求滑动。本项目：`RedisRateLimiter` 用 18 行 Lua 实现 ZSET 日志版，Redis TIME 取时钟，只有放行请求占名额（sliding_window.lua:10-14）。
详见 [9.1 滑动窗口限流](../modules/09-rate-limit/01-sliding-window-lua.md)、图 [06-redis-data-flow](../diagrams/archify/html/06-redis-data-flow.html)。

### 令牌桶（token bucket）对比
令牌桶按固定速率补令牌、允许突发；滑窗日志精确但每请求写一条成员。本项目选滑窗日志（Retry-After 可精确计算）；固定窗口只用在 API Key 日配额（INCR + EXPIRE，EXPIRE 失败尽力 DEL 防永久 429，RedisRateLimiter.java:71-96）。
详见 [9.1 滑动窗口限流](../modules/09-rate-limit/01-sliding-window-lua.md)。

### 缓存穿透（penetration）
反复查询不存在的键，打穿缓存直击数据库。本项目：三层联防，Bloom 拒绝大概率不存在的码（resolveTarget，:615-618）、空值缓存挡已知不存在的码（:623-626）、行读加 singleflight 合并。
详见 [8.3 空值缓存与 Bloom](../modules/08-redis-cache/03-negative-cache-bloom.md)。

### 缓存击穿（breakdown / hot key expiry）
热点键过期瞬间，并发请求同时回源。本项目：`SingleFlight` 把同码并发合并为一次行读，跟随者等待或超时降级自读（SingleFlight.java:51-99）。
详见 [8.4 singleflight](../modules/08-redis-cache/04-singleflight.md)。

### 缓存雪崩（avalanche）
大量键同时过期造成回源洪峰。本项目：TTL 加抖动 `jitteredTtlSeconds`（ShortLinkService.java:919-924），正负缓存 TTL 在配置基础上随机偏移。
详见 [8.1 Redis 使用面与键空间](../modules/08-redis-cache/01-keys-and-ttl.md)。

### TTL 抖动（TTL jitter）
过期时间加随机量，错开同时失效。本项目实例即上一条 `jitteredTtlSeconds`；键清单与各键 TTL 见课程 8.1。
详见 [8.1 Redis 使用面与键空间](../modules/08-redis-cache/01-keys-and-ttl.md)。

### 写穿（write-through）
写请求同时更新缓存与数据库，读路径不承担回填。本项目：创建成功后 `cacheUrl` 直接把 URL 写进两层缓存（doCreate :269；cacheUrl :966-977）。
详见 [8.2 L1/L2 与写穿双删](../modules/08-redis-cache/02-l1-l2-write-through.md)。

### 延迟双删（delayed double delete）
先删一次，写库后再延迟删第二次，清掉并发期被旧读回填的脏缓存。本项目：update 流程写穿后双删（:515-516），延迟 1 秒的第二次经 `scheduleStaleClear` 执行且这一遍绝不发布广播（:517-518 注释、scheduleStaleClear :991-999）。
详见 [8.2 L1/L2 与写穿双删](../modules/08-redis-cache/02-l1-l2-write-through.md)、[6.3 生命周期与 CSV](../modules/06-short-link/03-crud-lifecycle-csv.md)。

### 布隆过滤器（Bloom filter）
概率型集合，可能误报存在、绝不漏报。本项目：`CodeBloomFilter` 基于 Guava，进程内过滤跳转请求；重建走「先发布后播种」，失败恢复旧 filter；删除短码不可撤销（javadoc 自认，:34-36）。
详见 [8.3 空值缓存与 Bloom](../modules/08-redis-cache/03-negative-cache-bloom.md)。

### 误判率 fpp（false positive probability）
布隆过滤器允许的误报概率。本项目配置期望 10 万码、fpp=1e-4（LinkForgeProperties.Bloom，:138-150）；未就绪或禁用时 `mightContain` 一律放行（fail-open，CodeBloomFilter.java:186-192）。
详见 [8.3 空值缓存与 Bloom](../modules/08-redis-cache/03-negative-cache-bloom.md)。

### singleflight
同键并发请求合并为一次底层调用。本项目：`SingleFlight` 用 CHM `putIfAbsent` 选主，Leader 同步执行、Follower 等待 500ms 超时降级（SingleFlight.java:64-99）；写路径靠 peek 与两参 forget 保护（见 B 节「两参 forget」）。
详见 [8.4 singleflight](../modules/08-redis-cache/04-singleflight.md)。

### 消息与一致性

### 发布确认（publisher confirm）
Broker 对收到的消息回执 ack/nack，弥补「发了就算发了」的不可靠。本项目：`RabbitClickEventPublisher` 开 correlated confirm，等待 future 5 秒，nack 抛异常让 Relay 保持 PENDING（RabbitClickEventPublisher.java:44-67）。
详见 [10.1 拓扑与确认机制](../modules/10-rabbitmq-outbox/01-topology-confirm.md)。

### mandatory returns
消息设 mandatory 后不可路由会被退回而不是丢弃。本项目：returns callback 收集 returnedIds，发布后命中即抛 unroutable（RabbitClickEventPublisher.java:33-41、:57-59）；与 confirm、5 秒超时合称「完备写法」。
详见 [10.1 拓扑与确认机制](../modules/10-rabbitmq-outbox/01-topology-confirm.md)。

### 死信队列（DLQ）
消费失败消息的收容队列。本项目：队列声明时挂 DLX（RabbitMqConfig.java:29-35）；消费者抛异常且 `default-requeue-rejected=false` 时进 `linkforge.click.dlq`；Jackson converter 缺 JavaTimeModule 会把事件全打进 DLQ（:57-62 注释的教学点）。
详见 [10.3 消费幂等与死信](../modules/10-rabbitmq-outbox/03-consumer-idempotency-dlq.md)、图 [07-rabbitmq-topology](../diagrams/archify/html/07-rabbitmq-topology.html)。

### at-least-once / at-most-once
投递语义的两端：至少一次可能重复，至多一次可能丢失。本项目：confirm 成功才 markSent，故障重试，属于 at-least-once；消费端幂等表把「重复」变成无害，两种语义的取舍见课程 10.2/10.3。
详见 [10.2 Outbox 与 Relay](../modules/10-rabbitmq-outbox/02-outbox-relay.md)、[10.3 消费幂等与死信](../modules/10-rabbitmq-outbox/03-consumer-idempotency-dlq.md)。

### Transaction Outbox
业务数据与待发消息同事务落库，再由后台中继发往 MQ。本项目：点击记录同事务写 `lf_click_log` + `lf_outbox`（ClickRecordService.java:40-74）；`ClickOutboxRelay` 每 2 秒批 100 条抽水，退避 2^n 上限 60 秒，attempts≥30 转 FAILED（:48-53、:75-78）。
详见 [10.2 Outbox 与 Relay](../modules/10-rabbitmq-outbox/02-outbox-relay.md)、[advanced/07 点击记录与 Outbox](../advanced/07-click-record-outbox.md)。

### 对账（reconciliation）
周期性核对两侧数据并补偿差额。本项目：`ClickLogReconciler` 每 30 秒扫描「有明细、无 event_id、未被消费」的行重放进消费逻辑（ClickLogMapper.java:14-18 扫描 SQL）。
详见 [11.3 统计 API 与对账](../modules/11-statistics/03-stats-api-reconciler.md)、[advanced/09 统计与对账补偿](../advanced/09-stats-reconcile.md)。

### 最终一致性（eventual consistency）
允许短暂不一致，保证收敛。本项目：明细行同事务落库（强一致），日聚合与维度表异步 upsert 收敛；测试纪律是「同步断言打明细表，聚合只做事后收敛断言」。
详见 [11.1 点击记录管道](../modules/11-statistics/01-click-record-pipeline.md)、[advanced/13 性能与一致性分析](../advanced/13-performance-and-consistency.md)。

### 幂等键（idempotency key）
客户端带键防重复提交，服务端按键去重。本项目：创建短链 `idem:{userId}:{key}` 两段式，SETNX pending 5 秒抢建、终值 TTL 86400 供重放；acquire 与 settle 是不对称 fail-open（settle 失败键停 pending，停机期同键重试会再造一条，:276-279 注释自认）。
详见 [9.2 幂等键与配额](../modules/09-rate-limit/02-idempotency-and-quotas.md)、[6.2 创建短链全流程](../modules/06-short-link/02-create-flow.md)、图 [11-shortlink-create](../diagrams/archify/html/11-shortlink-create.html)。

### 安全与加密

### HMAC
带密钥的哈希消息认证码，防篡改兼防伪造。本项目四处使用：JWT 签名（JwtService）；QR 分享链 HMAC-SHA256(code|exp)（ShareTokenSigner）；解锁 cookie HMAC(linkId|code|exp|pwdVer)（LinkAccessTokens）；Webhook 签名 v1=HmacSHA256(ts.body)（WebhookSigner.java:21-34）。
详见 [5.2 JWT 签发与校验](../modules/05-security/02-jwt-auth.md)、[6.4 QR 物料与 UTM](../modules/06-short-link/04-qr-utm-materials.md)、[13.2 投递、签名、重试与聚合](../modules/13-webhook/02-delivery-signing-retry.md)。

### AES-GCM
带认证标签的对称加密，密文被篡改会解密失败。本项目：`WebhookSecretCipher` AES-256-GCM 存 Webhook secret，格式 `v1:` + Base64(nonce(12)||ct||tag)；主密钥丢失则存量 secret 不可恢复（:21-23）。
详见 [13.1 端点配置与密钥加密](../modules/13-webhook/01-endpoints-and-cipher.md)。

### SSRF（server-side request forgery）
诱使服务端请求内网地址的攻击。本项目：`WebhookTargetGuard` 保存时校验目标 URL，`allowPrivateTargets=false`，违规 400 且日志不回显内部原因（WebhookService.java:87、D-C4-8）。
详见 [13.3 SSRF 防护](../modules/13-webhook/03-ssrf-and-aggregate.md)。

### DNS rebinding（DNS 重绑定）
攻击者让域名先解析到合法 IP 过检查、投递时再翻转到内网 IP。本项目解法是「发送时一次解析 + 钉死」：`PinnedDnsResolver` 只对同 host 返回钉住地址（PinnedHttpSender.java:85-100）。
详见 [13.3 SSRF 防护](../modules/13-webhook/03-ssrf-and-aggregate.md)。

### TOCTOU（time-of-check to time-of-use）
检查时刻与使用时刻之间状态可变。本项目：保存时 check 过了不代表发送时安全（DNS 可翻转），所以 `resolveForSend` 在发送时重新解析并返回钉死地址，检查与使用合并为一次（WebhookTargetGuard.java:57-92）。
详见 [13.3 SSRF 防护](../modules/13-webhook/03-ssrf-and-aggregate.md)。

### fail-open / fail-closed
依赖故障时「放行」还是「拒绝」的两种取向。本项目 fail-open：限流 Redis 异常放行并打点（RedisRateLimiter.java:48-53）、Bloom 未就绪一律 true、L2 读异常回源、点击记录 catch DataAccessException 照常 302。fail-closed：指标抓取 token 未配置就拒绝（见 B 节「scrape-token fail-closed」）。
详见 [9.1 滑动窗口限流](../modules/09-rate-limit/01-sliding-window-lua.md)、[advanced/12 依赖故障处理](../advanced/12-dependency-failure-handling.md)。

### TLS SNI
TLS 握手时客户端上报要访问的域名，服务端据此选证书。本项目：`PinnedHttpSender` 钉死 IP 连接的同时保持 Host/SNI/TLS 校验仍用域名（PinnedHttpSender.java:25-29），防止「连了真 IP、验了个假域名」。
详见 [13.3 SSRF 防护](../modules/13-webhook/03-ssrf-and-aggregate.md)。

### 短链域

### Base62
0-9、a-z、A-Z 共 62 字符的编码 alphabet。本项目：`ShortCodes` 用 SecureRandom 从 62 字符表生成 7 位短码，撞码最多重试 16 次（CODE_ATTEMPTS=16）；`code` 列 V4 起用 `utf8mb4_0900_bin` 保证大小写敏感。
详见 [6.2 创建短链全流程](../modules/06-short-link/02-create-flow.md)、[4.2 Flyway 与 schema](../modules/04-mybatis-mysql/02-flyway-migrations.md)。

### 秒链 / 短链服务
把长 URL 换成短码、跳转时还原的服务形态。本项目本身就是实例：`lf_short_link` 存长 URL，`/r/{code}` 12 步判定链后 302；判定总顺序见 B 节「五层读取防护」。
详见 [01-project-overview.md](../01-project-overview.md)、[7.1 跳转判定链](../modules/07-redirection/01-decision-chain.md)。

### 302 vs 301
302 临时重定向（浏览器不长期缓存、每次回源，统计可控）；301 永久重定向（会被缓存，后续点击可能不再经过服务端）。本项目跳转一律 302（RedirectController render，:87-89），403 BLOCK、200 warn/解锁页另算。
详见 [7.1 跳转判定链](../modules/07-redirection/01-decision-chain.md)、图 [12-redirect-decision](../diagrams/archify/html/12-redirect-decision.html)。

### UTM 参数
utm_source/medium/campaign 等归因查询参数。本项目：`UtmMerger` 写时合并，不覆盖已有值、保留 fragment、超 2048 返 null 转入 400；创建请求里夹带的 `^utm` 与 9 个归因 id 会被拒收（rejectSmuggledAttribution，:871-878）。
详见 [6.2 创建短链全流程](../modules/06-short-link/02-create-flow.md)、[6.4 QR 物料与 UTM](../modules/06-short-link/04-qr-utm-materials.md)。

### QR EC 级别（error correction）
二维码纠错等级 L/M/Q/H，越高越耐污损、容量越低。本项目：`QrParams` 默认 256px、margin 1、EC 级别 M；QR 内容恒为 `{base}/r/{code}`，渲染用 ZXing 3.5.3。
详见 [6.4 QR 物料与 UTM](../modules/06-short-link/04-qr-utm-materials.md)。

### 可观测

### Prometheus gauge / counter / histogram
三种指标形态：gauge 是可升可降的瞬时值，counter 只增不减，histogram 分桶看分布。本项目：counter 如 redirects、created、failopen{reason}；gauge 如 `BacklogGauges` 的 outbox/webhook 积压（15 秒采样，与抓取解耦）；只有 `http.server.requests` 开 histogram（SLO 桶 5ms..1s）。
详见 [14.1 Actuator 与 Micrometer](../modules/14-observability/01-actuator-micrometer.md)、[14.2 业务指标与 Grafana](../modules/14-observability/02-metrics-gauges-grafana.md)。

### SLO（service level objective）
服务等级目标，通常落在延迟/可用性分位上。本项目：histogram 的 SLO 桶在 yml management 段配置（5ms..1s），Grafana 面板消费；B10 Linux 基准未跑，性能数字一律只引 README 历史口径。
详见 [14.2 业务指标与 Grafana](../modules/14-observability/02-metrics-gauges-grafana.md)、[reference/issues-and-discrepancies.md](issues-and-discrepancies.md)。

### 基数爆炸（cardinality explosion）
标签取值无界导致时间线数量失控。本项目：`HttpUriTagAllowList` 给 `http.server.requests` 的 uri tag 上白名单（51 行 MeterFilter），动态路径不进白名单就归并。
详见 [14.1 Actuator 与 Micrometer](../modules/14-observability/01-actuator-micrometer.md)。

## B. 本项目专有概念

### 五层读取防护
跳转读路径的五道闸，7.1 权威顺序（源码实测）：限流 → Bloom → 空值缓存 → L1/L2 缓存 → 行读 singleflight，之后才进入状态/过期、URL 校验、B9、密码门、B9 WARN、variants 抽签、B2 分类等后续步骤。
详见 [7.1 跳转判定链](../modules/07-redirection/01-decision-chain.md)、[8.1 Redis 使用面与键空间](../modules/08-redis-cache/01-keys-and-ttl.md)、图 [13-cache-hit-miss-sequence](../diagrams/archify/html/13-cache-hit-miss-sequence.html)。

### 空值缓存（absent 缓存）
对「确认不存在」的短码也缓存一份否定答案，挡穿透。本项目：`absentKnown` / `markAbsent` / `clearAbsent` 三件套（ShortLinkService.java:926-964）；创建成功会 clearAbsent；键名清单见课程 8.1。
详见 [8.3 空值缓存与 Bloom](../modules/08-redis-cache/03-negative-cache-bloom.md)。

### 行权威
数据库行是唯一事实，缓存只是加速提示。三处落地：URL 校验以行为准（resolveTarget :663-671）、B9 判行的 URL（:683-687）、variants 非空永远压过缓存 URL（:705-707）。
详见 [7.1 跳转判定链](../modules/07-redirection/01-decision-chain.md)、[8.2 L1/L2 与写穿双删](../modules/08-redis-cache/02-l1-l2-write-through.md)。

### 两参 forget
写路径避免误删别人正在飞行的读请求：提交前 `peek` 快照，提交后 `forget(snapshot)` 只删自己占的位。调用点 update :495/:512、setStatus :535/:537、delete :548/:554；残留窗口是代码自认的已知限制（SingleFlight.java:111-113）。
详见 [8.4 singleflight](../modules/08-redis-cache/04-singleflight.md)。

### digest（聚合摘要投递）
Webhook AGGREGATE 模式按时间窗聚合成一条摘要消息。eventId 形如 `digest-{endpointId}-{windowStart}`[-partN]；每片最多 500 个 link 桶，超过 max-parts(10) 只发前 N 片并标 truncated；与 PER_CLICK 共用同一投递状态机。
详见 [13.2 投递、签名与重试](../modules/13-webhook/02-delivery-signing-retry.md)、图 [16-webhook-delivery-lifecycle](../diagrams/archify/html/16-webhook-delivery-lifecycle.html)。

### pwdVer（密码版本指纹）
解锁 cookie 里绑定的密码哈希指纹（SHA-256 前 8 字节）。改密后 pwdVer 变化，旧 cookie 即刻失效（LinkAccessTokens.java:81-89）；cookie 名 `lf_ac_{code}`（:38-40）。
详见 [7.2 密码门与域名门禁](../modules/07-redirection/02-password-and-domain-gates.md)。

### scrape-token fail-closed
`/actuator/prometheus` 由 `MetricsScrapeTokenFilter` 用常量时间比较守卫；配置的 scrape-token 为空时直接拒绝而非放行（LinkForgeProperties.Metrics，:53-59），这是全项目少数 fail-closed 的点。
详见 [14.1 Actuator 与 Micrometer](../modules/14-observability/01-actuator-micrometer.md)。

### 红先纪律
测试必须先红后绿：断言先经历过一次失败才算有效，防「永远绿的假测试」。口径以课程正文为准。
详见 [15.1 测试金字塔](../modules/15-testing-deployment/01-test-pyramid.md)、图 [09-test-ci-pipeline](../diagrams/archify/html/09-test-ci-pipeline.html)。

### 诚实边界
代码注释自认局限而非粉饰。三处代表：WebhookTargetGuard 自认未覆盖端口白名单与 egress 代理（:23-25）；幂等 settle 失败自认停机期可能再造一条（doCreate :276-279）；SingleFlight 自认残留窗口（:111-113）。
详见 [13.3 SSRF 防护](../modules/13-webhook/03-ssrf-and-aggregate.md)、[8.4 singleflight](../modules/08-redis-cache/04-singleflight.md)。

## C. 缩写表

| 缩写 | 全称与含义 | 详见 |
|------|-----------|------|
| L1 | 一级进程内缓存（本项目为 Caffeine，只存 URL 字符串） | [8.2](../modules/08-redis-cache/02-l1-l2-write-through.md) |
| L2 | 二级 Redis 缓存（跨实例共享） | [8.2](../modules/08-redis-cache/02-l1-l2-write-through.md) |
| DLX | Dead Letter Exchange，死信交换机（linkforge.click.dlx） | [10.1](../modules/10-rabbitmq-outbox/01-topology-confirm.md) |
| DLQ | Dead Letter Queue，死信队列（linkforge.click.dlq） | [10.3](../modules/10-rabbitmq-outbox/03-consumer-idempotency-dlq.md) |
| UT | Unit Test 单元测试，surefire 跑 `*Test`，免 Docker | [15.1](../modules/15-testing-deployment/01-test-pyramid.md) |
| IT | Integration Test 集成测试，failsafe 跑 `*IT`，需 Testcontainers | [15.1](../modules/15-testing-deployment/01-test-pyramid.md) |
| EC | Error Correction，二维码纠错级别（L/M/Q/H，本项目默认 M） | [6.4](../modules/06-short-link/04-qr-utm-materials.md) |
| FPP | False Positive Probability，布隆过滤器误判率（本项目 1e-4） | [8.3](../modules/08-redis-cache/03-negative-cache-bloom.md) |
| SSRF | Server-Side Request Forgery，服务端请求伪造 | [13.3](../modules/13-webhook/03-ssrf-and-aggregate.md) |
| TSV | Tab-Separated Values，制表符分隔文本；本仓库见于 .audit 盘点文件（如 bw345.tsv），与业务无涉；CSV 处理见课程 6.3 | [6.3](../modules/06-short-link/03-crud-lifecycle-csv.md) |
| XFF | X-Forwarded-For 请求头，代理链上的客户端 IP；`ClientIps` 默认不信任 | [2.2](../modules/02-common/02-logging-utils-clientip.md) |
| UA | User-Agent，用户代理头；点击维度分桶的输入之一 | [11.2](../modules/11-statistics/02-aggregation-dimensions.md) |
| MQ | Message Queue 消息队列，本项目指 RabbitMQ 3.13 | [10.1](../modules/10-rabbitmq-outbox/01-topology-confirm.md) |
| BOM | Byte Order Mark 字节序标记；CSV 导入解析器会剥离 | [6.3](../modules/06-short-link/03-crud-lifecycle-csv.md) |
| CRLF | 回车换行符；CSV 状态机区分 CR / LF / CRLF | [6.3](../modules/06-short-link/03-crud-lifecycle-csv.md) |

> 词条与课程的对应关系如有出入，以课程正文为准，差异可登记到 [issues-and-discrepancies.md](issues-and-discrepancies.md)。
