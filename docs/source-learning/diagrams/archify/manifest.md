# Archify 图表总表（manifest）

> 每张图：交互 HTML 在 `html/`，源 JSON 在 `source/`，finalize 回执（`*.finalize-summary.json` / `*.browser-check.json`）与 HTML 同目录。
> **校验口径**：finalize 通过 = validate / deliver / check / browser-check 四道门全过，只证明结构与渲染合格；内容真实性以各课程正文引用的源码为准。
> 基线：仓库 `911640c`（调查口径）→ `01f0b5a`（docs-only，schema/源码未变）。质量档：架构/树/流程类 showcase，ERD 首遍 standard。

## 图表清单与状态

| # | slug | 类型 | 内容 | 状态 | 关联课程 | 证据主源 |
|---|------|------|------|------|----------|----------|
| 00 | system-architecture | architecture | 系统总架构（组件与数据设施） | ✅ 通过（第 3 轮） | 01/02/多 | README 架构节 + inventory |
| 01 | runtime-deployment | architecture | 运行与部署（宿主 JVM + Compose） | ✅ 通过（首跑） | 01/15.3 | docker-compose.yml + application.yml |
| 02 | function-map | tree | 用户能力地图（7 域 37 叶） | ✅ 通过 | 01/02 | README 功能总览 :34-44 + 控制器 @Mapping 实测 |
| 03 | package-map | tree | 源码包地图（12 包 120 类） | ✅ 通过 | 02/全课程 | find src/main/java 全等 diff |
| 04 | core-class-relations | class | 核心类协作 | ✅ 通过（第 3 轮） | 02/06/07 | inventory link.md + 源码 |
| 05 | database-erd | erd | 13 表实体关系（standard，4 列布局） | ✅ 通过（第 2 轮） | 4.2/11 | V1–V10 SQL 逐文件 + schema-tests 报告 |
| 06 | redis-data-flow | dataflow | 缓存/限流/幂等数据流 | ✅ 通过（4 轮） | 08/09 | ShortLinkService + RedisRateLimiter |
| 07 | rabbitmq-topology | architecture | Exchange/Queue/DLX 拓扑 | ✅ 通过（首跑） | 10.1 | RabbitMqConfig.java 逐 bean |
| 08 | scheduled-tasks | architecture | 定时任务地图 | ✅ 通过（3 轮后） | 14.3 | 全部 @Scheduled 类 |
| 09 | test-ci-pipeline | workflow | 测试与 CI 流程 | ✅ 通过（首跑） | 15 | ci.yml + pom failsafe |
| 10 | auth-sequence | sequence | 注册/登录/过滤器链 | ✅ 通过（第 5 轮） | 5 | SecurityConfig + JwtAuthFilter |
| 11 | shortlink-create | workflow | 创建短链流程 | ✅ 通过（standard 档，见下） | 6.2 | ShortLinkService.create/doCreate |
| 12 | redirect-decision | workflow | 跳转 12 步判定链 | ✅ 通过（首跑 showcase） | 7.1 | resolveTarget |
| 13 | cache-hit-miss-sequence | sequence | 缓存命中/未命中读路径 | ✅ 通过（4 轮） | 7.1/8.2 | resolveTarget 分层 |
| 14 | click-pipeline-dataflow | dataflow | 点击→Outbox→MQ→聚合→Webhook | ✅ 通过（4 轮） | 10/11 | stats+webhook inventory |
| 15 | outbox-lifecycle | lifecycle | Outbox PENDING→SENT/FAILED 状态机 | ✅ 通过（2 轮） | 10.2 | ClickOutboxRelay + OutboxMapper |
| 16 | webhook-delivery-lifecycle | lifecycle | Webhook 投递重试状态机 | ✅ 通过（7 轮） | 13.2 | WebhookDeliveryRelay |

## 已完成图的证据摘要

- **03-package-map**（通过，showcase，首跑即过）：节点=全部 120 个类（与 `find src/main/java -name '*.java'` 全等 diff，无缺无多无重复），12 个一级包 + 32 个子包；每节点带仓库相对路径与类型声明行号。丢弃：无；合并：无。
- **02-function-map**（通过，showcase，修复 2 轮画布宽度）：7 域（认证/短链管理/跳转/统计/开放能力/可观测/工程）37 条能力叶，全部出自 README 功能总览表；HTTP 入口按控制器 @Mapping 行号实测。合并：README「限流」行并入跳转域、「安全」行拆入跳转与统计；机制叶子细节（五层防护层名以下的内容）留给正文。
- **05-database-erd**（通过，standard，第 2 轮过 browser-check）：13 表 121 列逐列录入自 V1–V10（含可空标记与索引卡片），未增删列；7 个功能域；21 条逻辑外键全虚线（identifying=false），attribute 级 references 齐全；endpoint→tenant 按 uk_webhook_tenant 标一对一。偏离说明：因 8px 徽标投影低于可读下限，布局从 7 列改 4 列（每列两域实心矩形）；flyway_schema_history 不画实体（Flyway 自维护、无业务外键），理由入卡片；lf_click_log.event_id → lf_outbox.event_id 画为可选逻辑引用（V2 注释 + ClickStatsService 佐证）；finalize 附带 3 条绕行路由的 advisory（advisory 性质，未做感知审查）。

- **07-rabbitmq-topology**（通过，showcase，首跑零诊断）：六个 AMQP bean 全部落图（RabbitMqConfig.java:17-62 逐 bean），binding 以 routing key 连线标签表达（未画独立节点）；Jackson converter 复用容器 ObjectMapper 的陷阱收进卡片。
- **08-scheduled-tasks**（通过，showcase，3 轮修复）：8 个 @Scheduled 任务 + 共享调度池 size=6；reconciler 的聚合写入细节收进卡片；仍存 advisory 级路由交叉（review-2/3 各 5/8 处，不阻塞门禁，未做感知审查）。
- **09-test-ci-pipeline**（通过，showcase，首跑零诊断）：CI 五步 + surefire/failsafe 双执行 + 时区 fork 分支；151/166/2 例数来自 .audit 测试盘点（不入库文件），图内卡片已注明出处口径。

- **04-core-class-relations**（通过，showcase，第 3 轮）：18 类型 17 关系（主链 5 + RedirectOutcome sealed 1 + 支撑 7 + 管道 5）；ShortLinkService 只画核心 8 依赖（实测 17，省略方入卡片）；实现边 RabbitClickEventPublisher⇢ClickEventPublisher；组合边 PhishingPolicy◆→TargetPolicy。**建模纠正**：ClickRecordService 与 ClickOutboxRelay 无类级依赖（经 lf_outbox 行解耦），未画箭头；WebhookService/WebhookDeliveryRelay/PinnedHttpSender/ClickFilter/LinkVariantMapper/ShortLinkRowLoader 移入卡片。3 条 advisory 绕行路由，未做感知审查。
- **10-auth-sequence**（通过，showcase，第 5 轮）：一条主时间线三段（A 注册/登录、B JWT 过滤器链含 401 分支与 finally 双清、C X-API-Key）；ApiKeyAuthFilter 参与方降级为卡片（4 要点），原因：9 参与方超 1085px 自动宽度上限且与 viewport-height 门禁互斥（宽度诊断两建议冲突耗 2 轮后按预授权降级）。

- **11-shortlink-create**（通过，**standard 档**——偏离默认 showcase）：完整创建流程含幂等四分支与全部失败终态；7 条 400 扇形边收敛为 1 条共享终止边、语义移入节点说明。**偏差理由**：showcase 下「无键直建」「终值重放」与 e503 走廊在 6 列网格互斥，约 15 轮修复后仍有 2 条走廊共享 warning（standard 下不构成失败）；升级需把幂等段重设为独立分区，属重设计，超出修边范畴。
- **12-redirect-decision**（通过，showcase，首跑零诊断）：12 步判定链 + 全部失败终态；404 按源拆两个就近终端；HEAD 差异全部以节点说明表达（resolveForProbe 不计数、解锁页 HEAD 401、lf_continue 仅 GET）；meta.repository 冻结在 01f0b5a。

- **13-cache-hit-miss-sequence**（通过，showcase，4 轮）：九参与者、公共前缀（限流→Bloom→absent）+ 命中线/双 miss 未命中线/行分叉（markAbsent 404 / cacheUrl 预热 302）/L2 fail-open；HEAD/unlock/B2/抽签细节入卡片。偏差：第 4 轮超 3 轮上限一轮——卡点是自动画宽 920px < 九参与者最小宽 982px 且手写 viewBox 触发死锁，按 skill 的实现查验路径恢复自动宽度后通过。
- **15-outbox-lifecycle**（通过，showcase，2 轮）：PENDING→SENT→7 天清理主路、自环重试（2^n≤60s）、≥30 次 FAILED、手工重放回 PENDING；扫描条件/mark 无状态守卫差异/DB 时钟三张卡；advisory（pending 自环 route stretch 3.308）保留，未做感知审查。

- **06-redis-data-flow**（通过，showcase，4 轮）：四组流（正缓存/空值/限流/幂等）+ 双频道行齐备，TTL 入键 tag 与卡片；三条跨行边降维为卡片/子标签（语义未丢）。经验：dataflow 网格对跨行流边极敏感，同类型图应按「每行一条链」起稿。
- **14-click-pipeline-dataflow**（通过，showcase，4 轮）：主链 302→双写→relay→MQ→consume→webhook→endpoint，旁路 reconciler/cleanup/DLQ；同步/异步边界、at-least-once、双时钟口径入卡片与流标签。

- **16-webhook-delivery-lifecycle**（通过，showcase，7 轮 validate 修复）：PENDING→SENT/FAILED 主路 + 自环退避 + master-key 跳过 + AGGREGATE digest 同状态机 + 删除留痕；**偏差**：PENDING→FAILED 的 4 类出口合并为 1 条边（语义与行号移入「4 类出口」与手工重放卡片）；advisory（自环 stretch 3.308，与图 15 同款）保留。
