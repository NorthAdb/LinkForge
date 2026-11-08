# 功能覆盖矩阵：README 功能总览 → 功能行 → 课程/调用链/配图

> 口径：2026-11-03。功能行拆自 `D:\LinkForge\README.md` 的「功能总览」表（11 行能力）与「明确不做」以外的全部能力，按 9 个功能域重排。调用链节点取自五份调查报告（`D:\LinkForge\.audit\inventory\`），行号级细节见各课正文。基线 911640c（docs 侧 01f0b5a）。
> **3.0.0 更新（2026-11-08）**：基线升至 tag `3.0.0`。批次 1–3、修复波与 S/N 波的新能力补为 F45–F56（文末专节），F38/F39 两行按新基线修订；F1–F44 的课程去向未动。
> 课程列写法：`6.2` 指 `docs/source-learning/modules/06-short-link/02-create-flow.md`，`advanced/N` 指 `docs/source-learning/advanced/` 综合专题。配图列为 slug，对应 `docs/source-learning/diagrams/archify/html/<NN>-<slug>.html`（共 17 张，全部已通过 finalize）。

## 域到 README 行的映射

README 功能总览 11 行 → 本矩阵 44 个功能行。拆分规则：

| README 行 | 本矩阵功能行 |
|---|---|
| 认证（JWT 登录/注册/bootstrap/多租户） | F1-F4 |
| 短链（CRUD、自定义码、过期禁用、幂等、撞码、CSV 导出、QR、UTM） | F5-F12 |
| 短链（A/B 权重分流） | F13 |
| 短链（批量 CSV 导入） | F10 |
| 跳转（302+HEAD、五层防护） | F14-F16 |
| 限流（滑动窗口+日配额） | F17-F18 |
| 统计（Outbox 管道+UV+维度） | F19-F25 |
| 安全（密码短链/域名门禁/非人点击） | F26-F28 |
| 开放（API Key/Webhook） | F29-F34 |
| 可观测（Prometheus/Grafana/gauge） | F35-F37 |
| 工程（Flyway/测试/CI/prod） | F38-F44（补演练脚本与 Compose，出处 15.3） |

---

## 认证（F1-F4）

| # | 功能 | HTTP 入口或触发点 | 核心类 | 调用链摘要 | 对应课程 | 配图 |
|---|---|---|---|---|---|---|
| F1 | JWT 登录 | POST /api/auth/login | AuthController, AuthService, JwtService | AuthController → AuthService.login（租户/用户/BCrypt 三查合一 401）→ JwtService.createToken → ApiResponse{token} | 5.2；advanced/01 | 10-auth-sequence |
| F2 | 开放注册（按 IP 限流） | POST /api/auth/register | AuthController, AuthService, RedisRateLimiter, Tenant, UserAccount | AuthController → RedisRateLimiter(register 限流，拒绝抛 RateLimitedException) → AuthService.register @Transactional（建租户+首用户 ADMIN）→ JwtService.createToken | 5.2；advanced/01 | 10-auth-sequence |
| F3 | bootstrap 演示租户 | 应用启动（ApplicationRunner） | BootstrapDataLoader, TenantMapper, UserAccountMapper | 启动完成 → BootstrapDataLoader.run（配置开关）→ 建 demo 租户 + admin（BCrypt）→ 重复启动幂等跳过 | 1.2 | 00-system-architecture（组件位置） |
| F4 | 多租户隔离 | 全部业务查询（无独立入口） | UserContext, ShortLinkService, ApiKeyService | Controller → UserContext 取 AuthUser → getOwnedLink/isTenantWide（越权按 404 语义）→ scope=TENANT 或 tenant_role=ADMIN 放宽边界 | 5.3；advanced/03 | 04-core-class-relations（依赖面） |

## 短链管理（F5-F13）

| # | 功能 | HTTP 入口或触发点 | 核心类 | 调用链摘要 | 对应课程 | 配图 |
|---|---|---|---|---|---|---|
| F5 | 创建短链（自定义码/大小写敏感） | POST /api/links | ShortLinkController, ShortLinkService, LinkValidations, ShortCodes | ShortLinkController → ShortLinkService.create（限流/拒夹带/幂等前置）→ doCreate（校验链+B9 门禁+UTM 合并）→ 写库（自定义码撞 uk 409，随机码 16 次重试）→ Bloom put+缓存写穿 | 6.2；advanced/02 | 11-shortlink-create |
| F6 | 创建幂等（幂等键两段式） | POST /api/links（幂等键） | ShortLinkService | create → SETNX idem:{userId}:{key} pending 5s → 抢到 doCreate / 没抢到轮询重放 → 提交后终值回写 TTL 86400 → 重放读终值 | 6.2、9.2；advanced/02 | 11-shortlink-create、06-redis-data-flow |
| F7 | 短链生命周期管理（list/detail/PATCH/启停/删除） | GET/PATCH/DELETE /api/links 等 | ShortLinkController, ShortLinkService, UpdateShortLinkRequest | Controller → 归属校验 getOwnedLink → update/setStatus/delete（variants 整表替换或同事务硬删）→ singleflight snapshot+forget → 缓存写穿双删 → CacheBus 广播 | 6.3；advanced/03 | 04-core-class-relations |
| F8 | 过期/禁用语义 | GET /r/{code}（判定） | ShortLinkService | resolveTarget → status≠1 → 404；expireAt 已过 → 410；缓存与行以「行权威」口径统一 | 7.1 | 12-redirect-decision |
| F9 | CSV 导出 | GET /api/links/export | ShortLinkService, CsvFormat | Controller → exportOwnCsv（BOM 头+RFC4180 转义，上限 1 万行）→ text/csv 响应 | 6.3（2.2 讲 CsvFormat） | — |
| F10 | CSV 批量导入 | POST /api/links/import（multipart） | ShortLinkService, CsvImportParser | Controller → import 专用限流 → CsvImportParser 状态机解析（256KiB/100 行上限）→ 逐行走 doCreate → ImportResponse 返回行号+原因 | 6.3 | — |
| F11 | QR 物料 + 短时分享链 | GET /api/links/{code}/qr.png | QrMaterialService, ShareTokenSigner, QrShareAuthFilter | Controller → QrMaterialService（ZXing PNG，内容恒 {base}/r/{code}）→ ShareTokenSigner 签 exp+sig 分享链 → QrShareAuthFilter 验签授 ROLE_QR_SHARE 放行 | 6.4 | — |
| F12 | UTM 写时合并 | 创建/更新时触发 | UtmMerger, CreateShortLinkRequest | doCreate/update → rejectSmuggledAttribution（拒 ^utm 前缀与归因 id 夹带）→ UtmMerger（不覆盖已有、fragment 重挂、超 2048 拒 400）→ 落库最终 URL | 6.2、6.4；advanced/02 | 11-shortlink-create（夹带拒绝分支） |
| F13 | A/B 权重分流 | GET /r/{code}（variants 非空） | ShortLinkService, LinkVariantMapper, VariantPicker | resolveTarget → 全部门禁通过后 → LinkVariantMapper.selectByLinkId（id 序）→ VariantPicker 抽签（threshold=r·total 半开区间）→ 302 到 variant 目标并记录 variantKey | 7.3；advanced/04 | 12-redirect-decision |

## 跳转（F14-F16）

| # | 功能 | HTTP 入口或触发点 | 核心类 | 调用链摘要 | 对应课程 | 配图 |
|---|---|---|---|---|---|---|
| F14 | 匿名 302 跳转 | GET /r/{code} | RedirectController, ShortLinkService, RedirectOutcome | RedirectController → resolveForGet → render：Redirect→302 / Blocked→403 拦截页 / Locked→200 解锁页 / Warn→200 告警页 | 7.1；advanced/04 | 12-redirect-decision |
| F15 | HEAD 探测（不计数） | HEAD /r/{code} | RedirectController, ShortLinkService | HEAD → resolveForProbe（warn 不继续、不记点击、不经 meter redirects）→ 302 或解锁页 401 | 7.1 | 12-redirect-decision（HEAD 差异在节点说明） |
| F16 | 五层读取防护 | GET /r/{code} 内部读路径 | RedisRateLimiter, CodeBloomFilter, UrlLocalCache, SingleFlight | redirect:ip 限流 → Bloom 快筛（fail-open）→ absent 空值缓存 → L1/L2 缓存（异常 fail-open）→ singleflight 行读 → 行权威校验+缓存回填 | 7.1、8.2、8.3、8.4；advanced/05 | 13-cache-hit-miss-sequence、06-redis-data-flow |

## 限流（F17-F18）

| # | 功能 | HTTP 入口或触发点 | 核心类 | 调用链摘要 | 对应课程 | 配图 |
|---|---|---|---|---|---|---|
| F17 | 滑动窗口限流（Retry-After/fail-open） | 各入口 acquire（redirect/create/register/import/unlock） | RedisRateLimiter, RateLimitKeys, RateLimitedException, GlobalExceptionHandler | 调用方 → RedisRateLimiter.acquire → sliding_window.lua（Redis TIME 时钟+nonce 防撞）→ 放行才占名额 / 拒绝算 Retry-After → 抛 RateLimitedException → 429+Retry-After 头 | 9.1；advanced/12 | 06-redis-data-flow（限流数据流） |
| F18 | API Key 双闸配额（分钟滑窗+日固定窗） | X-API-Key 请求触发 | ApiKeyAuthFilter, RedisRateLimiter | ApiKeyAuthFilter → 认证通过 → 分钟滑窗 sw(min:keyId) → 日固定窗 day:keyId:yyyyMMdd（Asia/Shanghai）→ 超限 429+Retry-After | 9.2、12.1；advanced/10 | 06-redis-data-flow（限流数据流） |

## 统计（F19-F25）

| # | 功能 | HTTP 入口或触发点 | 核心类 | 调用链摘要 | 对应课程 | 配图 |
|---|---|---|---|---|---|---|
| F19 | 点击记录（同事务 Outbox 双写） | GET /r/{code} 触发 | ShortLinkService, ClickRecordService, Outbox | resolveForGet → B2 过滤 → recordClick @Transactional（lf_click_log + lf_outbox PENDING 同事务）→ DataAccessException 由事务外 fail-open，照常 302 | 11.1；advanced/07 | 14-click-pipeline-dataflow |
| F20 | Outbox Relay 投递 | @Scheduled（fixedDelay 2s） | ClickOutboxRelay, OutboxMapper, RabbitClickEventPublisher | 定时批扫 PENDING（批 100）→ 反序列化 ClickEvent → RabbitClickEventPublisher（confirm+returns 同步等 5s）→ markSent / 退避 markRetry（DB 时钟）/ attempts≥30 markFailed | 10.2；advanced/07 | 15-outbox-lifecycle、14-click-pipeline-dataflow |
| F21 | 幂等消费与预聚合 | @RabbitListener | ClickEventConsumer, ClickStatsService, ClickDailyMapper, ClickDimDailyMapper | ClickEventConsumer → consume：lf_consumed_event 主键去重 → UUID 事件回填明细 event_id → lf_click_daily / lf_click_dim_daily upsert → webhook 入队（同事务，失败整体回滚进 DLQ） | 10.3、11.2；advanced/08 | 14-click-pipeline-dataflow |
| F22 | 断链自动追平（对账补偿） | @Scheduled（30s） | ClickLogReconciler, ClickLogMapper | 定时 LEFT JOIN 扫未消费明细（≤500 行）→ 重构 ClickEvent("log-{id}") → 逐行 consume 重放 → 行级失败仅 warn 不中断 | 11.3；advanced/09 | 14-click-pipeline-dataflow（旁路） |
| F23 | 统计 API（total/精确 UV/按日序列） | GET /api/links/{code}/stats | StatsController, ClickStatsService, ClickLogMapper | StatsController → detail 归属校验 → SUM(lf_click_daily) + COUNT(DISTINCT ip) UV（依赖 sync 双写开着）→ 缺日补 0 返回 | 11.3；advanced/09 | 05-database-erd（读面表关系） |
| F24 | 维度分析（设备/OS/浏览器/来源） | GET /api/links/{code}/breakdown | StatsController, ClickDimensions, ClickDimDailyMapper | consume 期 ClickDimensions 分桶（顺序即契约）→ dim upsert → breakdown 查询（dim 白名单 5 个，违规 400）→ GROUP BY 降序返回 | 11.2；advanced/09 | 14-click-pipeline-dataflow |
| F25 | 点击明细分页查询 | GET /api/links/{code}/clicks | StatsController, ClickLogMapper | StatsController → 归属校验 → 分页查 lf_click_log（size 1..100 默认 20）→ 只回 4 字段 | 11.3 | — |

## 安全门禁（F26-F28）

| # | 功能 | HTTP 入口或触发点 | 核心类 | 调用链摘要 | 对应课程 | 配图 |
|---|---|---|---|---|---|---|
| F26 | BCrypt 密码短链（签名 cookie） | POST /r/{code}/unlock | RedirectController, ShortLinkService, LinkAccessTokens | unlock → Bloom/absent 预筛 → 两级限流（先限流后校验）→ BCrypt 恒时校验 → LinkAccessTokens 签 HMAC cookie（pwdVer 绑定改密失效）→ 后续跳转带 cookie 放行 | 7.2；advanced/06 | 12-redirect-decision |
| F27 | 域名名单门禁 BLOCK/WARN（B9） | 创建时 + 跳转时 | PhishingPolicy, TargetPolicy, GatePages | 创建：rejectBlockedTarget（BLOCK→400 不回显域名）→ 跳转：B9 判行 URL（BLOCK→403 拦截页，WARN→200 告警页+lf_continue 继续）→ 名单快照 mtime 30s 热载 | 7.2 | 12-redirect-decision |
| F28 | 非人点击不计（B2） | GET /r/{code} 触发 | ClickFilter, ShortLinkService | resolveForGet → ClickFilter.classify（prefetch 头精确匹配+bot UA token）→ 命中则 meter click.skipped → 照常 302 但不记点击 | 11.1（7.1 调用点） | 14-click-pipeline-dataflow |

## 开放能力（F29-F34）

| # | 功能 | HTTP 入口或触发点 | 核心类 | 调用链摘要 | 对应课程 | 配图 |
|---|---|---|---|---|---|---|
| F29 | API Key 签发与认证（哈希存储） | POST/GET/DELETE /api/keys；请求头 X-API-Key | ApiKeyController, ApiKeyService, ApiKeyAuthFilter | 创建：明文只返回一次 → 存 SHA-256（uk_api_key_hash）→ 请求：ApiKeyAuthFilter 哈希点查 → revoked 与 invalid 对外同文案 → ROLE_API_KEY + touchLastUsed（SETNX 60s 节流） | 12.1；advanced/10 | 10-auth-sequence（C 段） |
| F30 | API Key 双 scope（USER/TENANT） | 创建时定 scope | ApiKeyService, ApiKeyAuthFilter, ShortLinkService | 创建：非 USER/TENANT 400，TENANT 需 ADMIN（否则 403）→ filter 只透传 scope → ShortLinkService.isTenantWide 生效跨租户可见 | 12.1、5.3；advanced/10 | 10-auth-sequence（C 段） |
| F31 | 点击 Webhook 逐次投递（HMAC+退避重试） | 消费事务末尾触发 | WebhookService, WebhookDeliveryRelay, WebhookSigner | recordClickDelivery（featureOff 直接返回）→ enqueuePerClick（evt_ 前缀跨重试稳定）→ Relay 批扫（master key 缺失整批跳过）→ WebhookSigner HMAC v1 → HTTP：2xx SENT / 408 429 5xx 重试 / 其他 4xx FAILED | 13.2；advanced/11 | 16-webhook-delivery-lifecycle、14-click-pipeline-dataflow |
| F32 | Webhook 聚合投递（AGGREGATE digest） | AGGREGATE 模式端点 | WebhookService, AggregateWindows, WebhookAggregateFlusher | recordClickDelivery → bufferAggregateClick（epoch 网格窗 upsert，sample 保留首击）→ 阈值内联 flush（并入 consume 事务）/ sweep 5s → digest 分片（每片≤500 桶，超 10 片截断）→ 走同一投递状态机 | 13.2 | 16-webhook-delivery-lifecycle |
| F33 | Webhook 密钥加密存储（AES-256-GCM） | endpoint 保存时 | WebhookService, WebhookSecretCipher | upsert → generateSecret（whsec_+64hex）→ encrypt "v1:"+Base64(nonce‖ct‖tag) → 明文仅一次展示 → 投递时 decrypt（失败走重试） | 13.1 | — |
| F34 | Webhook SSRF 防护（钉死 rebinding） | 保存时 + 每次投递 | WebhookTargetGuard, PinnedHttpSender | 保存：check 违规→400 → 发送：resolveForSend 一次解析返回 pinned 地址 → PinnedDnsResolver 只认 pinned → 禁重定向 → IPv6 内嵌 IPv4 单独回退 v4 规则 | 13.3 | — |

## 可观测（F35-F37）

| # | 功能 | HTTP 入口或触发点 | 核心类 | 调用链摘要 | 对应课程 | 配图 |
|---|---|---|---|---|---|---|
| F35 | Prometheus 端点令牌守卫 | GET /actuator/prometheus | MetricsScrapeTokenFilter, SecurityConfig | 请求 → MetricsScrapeTokenFilter（恒时比较 scrape-token，空=fail-closed）→ 授 ROLE_METRICS → 角色矩阵放行（须排在 /actuator/** 之前）→ 抓取文本暴露 | 14.1 | — |
| F36 | 业务指标与积压 Gauge | Micrometer 采集/抓取 | BacklogGauges, MeterRegistry（全项目打点） | @Scheduled 定时采样 → JdbcTemplate 查 outbox/webhook 积压 → Gauge 注册（采样与抓取解耦）→ /actuator/prometheus 暴露 → Grafana 面板消费 | 14.2 | 08-scheduled-tasks（采样任务在图） |
| F37 | Grafana 自动 provision 看板 | docker compose --profile observability | （compose provisioning，无 Java 类） | compose 起 grafana → provisioning 挂载数据源+看板 → 自动注入 127.0.0.1:3001 | 14.2、15.3 | 01-runtime-deployment |

## 工程化（F38-F44）

| # | 功能 | HTTP 入口或触发点 | 核心类 | 调用链摘要 | 对应课程 | 配图 |
|---|---|---|---|---|---|---|
| F38 | Flyway 版本化迁移（V1–V15，17 表） | 应用启动 / migrate.sh | （Flyway + SQL 脚本，无 Java 业务类） | 启动 → baseline-on-migrate 兜底遗留库 → V1..V15 顺序执行 → flyway_schema_history 记账（V11 会话、V12 邀请、V13 标签/规则、V14 恶意主机、V15 冲刷状态） | 4.2 | 05-database-erd（图冻结在 V10 口径，见图表覆盖矩阵时效表） |
| F39 | 测试体系（508 例：226 单测 + 280 IT + 2 时区 fork） | mvn verify | AbstractIntegrationTest（Testcontainers 单例容器） | surefire 跑 UT 类 → failsafe 起 IT 类（Testcontainers 三容器）→ 时区 fork 跑 WebhookRetryClockIT（初版口径 319 例，3.0.0 冻结口径 508） | 15.1 | 09-test-ci-pipeline（图内数字为旧口径） |
| F40 | GitHub Actions CI | push / 全 PR 触发 | （ci.yml，无 Java 类） | checkout@v4 → setup-java temurin 17 → 缓存 ~/.m2 → mvn -B verify | 15.2 | 09-test-ci-pipeline |
| F41 | prod profile 一键收紧 | SPRING_PROFILES_ACTIVE=prod | SecurityConfig（@Value docs-enabled）、application.yml prod 文档 | prod 生效 → 静态资源 add-mappings=false → springdoc 关 → docs-enabled=false 收紧角色矩阵 → health when_authorized | 1.3、15.3 | 01-runtime-deployment |
| F42 | Compose 一键环境（5 service） | docker compose up | （docker-compose.yml，无 Java 类） | up → mysql/redis/rabbitmq 健康检查 → 可选 observability profile 追加 prometheus/grafana → 5 命名卷持久化 | 15.3 | 01-runtime-deployment |
| F43 | OpenAPI 文档与双安全方案 | /v3/api-docs、swagger-ui | OpenApiConfig | 启动 → build-info 注入版本 → bearer + X-API-Key 两套 securityScheme → docs-enabled=false 分支整体关闭 | 15.2、3.1 | — |
| F44 | 故障演练与压测脚本 | 人工执行 scripts/*.sh | （bash 脚本+compose+wrk，无 Java 类） | smoke 冒烟 → bench 压测三场景 → mq-drill 断 MQ → outbox-drill 断链追平 → fault-drill（redis/webhook/mysql-ro 三段）验证自愈 | 15.3 | 01-runtime-deployment（部署面） |

---

## 3.0.0 增补（F45-F56，批次 1–3 / 修复波 / S-N 波）

| # | 功能 | HTTP 入口或触发点 | 核心类 | 调用链摘要 | 对应课程 | 配图 |
|---|---|---|---|---|---|---|
| F45 | 登录双桶限流 + 哑哈希抹时（批次 1） | POST /api/auth/login | AuthService, RedisRateLimiter | IP 桶 login:{ip} 5/600 → 账号桶 login-acct:{tenant}:{user} 小写归一 5/600 → 账号不存在/停用照样对 DUMMY_PASSWORD_HASH 跑一次 matches → 失败统一 401 | 5.2；advanced/01、advanced/15 | — |
| F46 | refresh 会话族（轮换+重用侦测+全会话撤销，批次 2a） | POST /api/auth/refresh、/logout、/logout-all | SessionService, RefreshTokenMapper, JwtService | 登录开新 family → refresh 在用户行锁内 rotate+插后继 → ROTATED 超 10s 宽限再出示整族 REUSE_KILL+tv 自增（主动撤销的只回 401）→ 改密/logout-all 原子撤全族 | 5.2；advanced/15 | — |
| F47 | 每请求回查 + 强制改密墙（批次 2a/2b，M7 补全） | 每个带凭据的请求 | JwtAuthFilter, ApiKeyAuthFilter, UserAccountMapper | 验签后回查用户行（status/tv/must_change）→ 身份取行不取 claim → must_change 时两凭据同体 403（豁免改密/登出/刷新）→ 存储故障稳定 503（S4-5） | 5.1、5.2；advanced/15、advanced/10 | — |
| F48 | 团队邀请与成员管理（批次 2b） | /api/tenant/invitations、/members、POST /api/auth/accept-invitation | TeamService, TeamController, TenantInvitationMapper | ADMIN 建邀请（只存哈希，链接带外投递）→ 接受 = 建账号 + 条件 UPDATE 单次认领同事务 → 成员改角色/启停/移除/重置全走租户行锁 + 锁内护栏（M2）→ 重置永久撤销全部 API Key（M7） | 5.3；advanced/15 | — |
| F49 | 标签与列表搜索（批次 3a） | GET /api/links、/export（keyword/tag 参数）、PATCH tags 三态 | LinkTags, ShortLinkService | tags 单列归一化存储（小写去重、至多 10）→ keyword 对 code/url/title 转义 LIKE → tag 走 FIND_IN_SET 整词 → 导出同筛选集 | 6.3；advanced/16 | — |
| F50 | 动态路由 match_rule（批次 3a，S4-4 修订星期语义） | GET /r/{code}（判定链末段） | MatchRule, ClickDimensions, VariantPicker | 门禁全过 → parseLenient 逐 variant 求值（设备/OS/语言前缀/时间窗）→ 候选收窄后权重抽签 → 全落选回退完整集；跨午夜尾段按开窗日读 weekdays | 7.3；advanced/16 | 12-redirect-decision（图冻结在无规则口径） |
| F51 | 原始点击导出（批次 3b，M8 中和） | GET /api/links/{code}/clicks/export | ClickStatsService, ClickLogMapper, CsvFormat | id 游标分批（批 1000）拼 CSV → 上限 100,000 行触顶加注释行 + X-Export-Truncated → 全部文本格 escapeSpreadsheet 中和公式注入 | 11.3；advanced/16 | — |
| F52 | A/B 拟合诊断 + 适用性门（批次 3b，S1 补门） | GET /api/links/{code}/ab-report | AbReport, ClickStatsService | 当前配置 × 窗口 variant 桶 → 先过 checkApplicability（CONDITIONAL_ROUTING > CONFIG_CHANGED_IN_WINDOW > CONFIG_HISTORY_UNKNOWN）→ 过门才卡方拟合（CONSISTENT/SKEWED），NA 时拟合字段全 null | 11.3；advanced/16 | — |
| F53 | 恶意主机 feed（批次 3b，S3 修顺序） | @Scheduled（默认关，6 小时）+ 创建/跳转判定 | MaliciousHostSyncService, MaliciousHostSnapshot, PhishingPolicy | fetch URLhaus hostfile → ingest 入库 lf_malicious_host → snapshot.refresh() 应用成功才推进 last_success → 判定读内存快照并入 B9 名单（纯本地） | 7.2；advanced/16 | — |
| F54 | 幂等深化（批次 3b + M4/M5） | POST /api/links、/import（Idempotency-Key） | ShortLinkService, ImportIdempotencyLease | 创建指纹 create-v2 含密码 HMAC（pepper）+ 信封 v3，同 key 异体 409、旧信封带密码 fail-closed → 导入加在途租约（SET NX + Lua CAS，同文件 409 IN_PROGRESS、异文件 409 REUSED），重放不烧限流 | 9.2、6.3；advanced/16 | 06-redis-data-flow（图为两段式旧口径） |
| F55 | 请求关联 id 与恰记一次（S/N 波 N1） | 每个 HTTP 请求 | CorrelationIdFilter, GlobalExceptionHandler | 链首服务端铸 cid → MDC + 请求属性 + X-Correlation-Id 响应头 → handleBiz 对 5xx/带 cause 恰记一次（wire 体不带 cause） | 2.2；advanced/17 | — |
| F56 | Webhook 聚合 drain 事务化（修复波 M3） | 阈值 inline / 5 秒 sweep | WebhookAggregateFlusher, WebhookFlushStateMapper | 锁行读桶 → 分配 flush_seq（V15）→ 以 digest-{ep}-{ws}-{seq} 插投递 → 只删锁行，整单同事务回滚 | 13.2；advanced/11、advanced/17 | 16-webhook-delivery-lifecycle（状态机仍准，drain 细节见专题） |

---

## 配图覆盖索引（17 张图各服务哪些功能行）

| 图（slug） | 内容 | 服务的功能行 |
|---|---|---|
| 00-system-architecture | 系统总架构 | F3（组件位置），全书背景 |
| 01-runtime-deployment | 运行与部署 | F37、F42、F44 |
| 02-function-map | 用户能力地图（7 域 37 叶） | 全部功能行的总览镜像 |
| 03-package-map | 源码包地图（12 包 120 类） | 与类覆盖矩阵互为索引 |
| 04-core-class-relations | 核心类协作 | F4、F7 |
| 05-database-erd | 13 表 ERD | F23、F38 |
| 06-redis-data-flow | 缓存/限流/幂等数据流 | F6、F16、F17、F18 |
| 07-rabbitmq-topology | MQ 拓扑 | F20、F21（拓扑背景） |
| 08-scheduled-tasks | 定时任务地图 | F20、F22、F36 |
| 09-test-ci-pipeline | 测试与 CI | F39、F40 |
| 10-auth-sequence | 注册/登录/过滤器链 | F1、F2、F29、F30 |
| 11-shortlink-create | 创建短链流程 | F5、F6、F12 |
| 12-redirect-decision | 跳转 12 步判定链 | F8、F13、F14、F15、F26、F27 |
| 13-cache-hit-miss-sequence | 缓存命中/未命中 | F16 |
| 14-click-pipeline-dataflow | 点击→Outbox→MQ→聚合→Webhook | F19、F20、F21、F22、F24、F28、F31 |
| 15-outbox-lifecycle | Outbox 状态机 | F20 |
| 16-webhook-delivery-lifecycle | Webhook 投递状态机 | F31、F32 |

## 统计与缺口检查

### 统计

| 项 | 数 |
|---|---|
| 功能行总数 | 56（F1-F44 初版 + F45-F56 的 3.0.0 增补） |
| 有模块课的功能行 | 56 / 56 |
| 有调用链摘要的功能行 | 56 / 56 |
| 有专属配图的功能行 | 36 / 56（无专属图行见下；3.0.0 增补行多无新图，靠专题正文与 mermaid 备用图） |
| 有 advanced 专题复现的功能行 | 34（初版 22：F1、F2、F4、F5、F6、F7、F12、F13、F14、F16、F17、F18、F19、F20、F21、F22、F23、F24、F26、F29、F30、F31；增补 12：F45-F56 全部） |

### 缺口检查

- **课程缺口：0。** 44 行全部落到具体模块课，无一悬空。
- **调用链缺口：0。** 44 行全部给出 3-6 节点摘要；F38/F39/F40/F42/F44 无 Java 业务类，链以脚本/流程节点表达，已在「核心类」列注明。
- **配图缺口：8 行无专属图**，属图额缺口而非课程缺口，均已标注替代：F9 CSV 导出、F10 CSV 导入、F11 QR 物料、F25 明细分页、F33 AES-GCM、F34 SSRF、F35 Prometheus 守卫、F43 OpenAPI。其中 F11/F33/F34 的类已在 6.4/13.1/13.3 正文以源码方式讲透；F9/F10 同属 6.3，F25 属 11.3；F35 是一个 66 行过滤器，14.1 正文覆盖；F43 的配置装配在 15.2 正文展开。若后续补图，F34（SSRF 钉死时序）教学价值最高。
- **口径差异登记**：02-function-map 为 7 域 37 叶（限流并入跳转、安全拆入跳转与统计），本矩阵按 9 域 44 行组织，与 README 11 行的映射见文首表，两者不冲突。

### 与上游材料的一致性说明

- 行为描述全部取自五份调查报告与 manifest，报告与源码如有出入以源码为准（写作代理已在各课核对）。
- stats.dto 五个 DTO 的职责为按端点行为反推，标〔推断〕于类覆盖矩阵；本矩阵不涉及其内部字段。
- F3 的幂等跳过与 F26 的恒时比较细节在报告中的表述为推断级，已按报告原文收录，未加码。
