# 差异与问题登记（issues and discrepancies）

> 教材编写过程中发现的「上游材料（README / inventory 调查报告 / 任务简报 / 业务代码注释）与源码实测不符」之处。每条给出裁决与现状。业务源码零改动是本任务纪律，涉及业务代码注释的问题只登记不修改。
> 编写基线：仓库 `main` @ `911640c` → `01f0b5a`（docs-only）；教材一律以实测源码为准。

## 1. 任务提示词与仓库路径

| # | 发现 | 裁决与现状 |
|---|------|-----------|
| 1.1 | 任务提示词写 `docs/81-linkforge-flows.md`、`docs/82-linkforge-decisions.md`，实际在 `docs/learning/` 子目录 | 上一版「知识点学习库」位置；本教材链接已按真实路径处理，docs 根下不存在这两个文件 |

## 2. 测试与数字口径

| # | 发现 | 裁决与现状 |
|---|------|-----------|
| 2.1 | README 徽章「tests 319 (151+166+2)」与源码 @Test 注解计数 312 不一致 | ✅ 已解决——319 与注解推演 318 的 1 例差系编写者双重扣减错误（把独立的 @ParameterizedTest 误当作已含在 144 内）；实际 144 个 `@Test` 不含参数化方法，`LinkValidationsTest.java:62-63` 的 `@ParameterizedTest` 展开 7 例，144+7=151 与 surefire 精确对平，151+166+2=319 全对平。教训入 15.1 |
| 2.2 | README 页头压测数字是 Windows 历史实测（2026-10-24，固定窗口时期），不代表当前 tip；B10 Linux bench 未跑 | 全教材只引用该口径并标注，不补造任何数字 |
| 2.3 | README:414 称 ClickLogReconciler「仅 sync 开启时运行」，代码门禁实为 `asyncClickEnabled && syncClickEnabled`（ClickLogReconciler.java:33-35） | 子集关系（README 描述是代码行为的特例表述），不矛盾；教材按代码门禁表述 |

## 3. 配置与代码注释（业务源码零改动，仅登记）

| # | 发现 | 裁决与现状 |
|---|------|-----------|
| 3.1 | `application.yml:40` 注释称 webhook relay 使用 `java.net.http.HttpClient`，实际是 Apache HttpClient 5（`PinnedHttpSender.java:5-8`） | 业务代码滞后注释，一词之差；不影响关闭 `http.client.requests` 的决策。14.1 教材如实注明；建议后续随 FE 波之外的维护轮修注释 |
| 3.2 | `WebhookDeliveryRelay.java:27-29` 注释写 "now 4-thread" scheduler，与 `application.yml:11-12` 默认池 6 不符 | 滞后注释（B5 时期 4→6 的调整没同步注释）。14.3 作眼力练习写入教材 |
| 3.3 | `DashboardMetricsIT.java:58` 上下文受限清单把 gauge `linkforge.webhook.failed`（`BacklogGauges.java:37`）写成 `linkforge_webhook_failed_total`——Prometheus 导出 gauge 无 `_total` 后缀 | 无害的不准确命名（放行清单少一个匹配机会）。已登记；建议上游随测试维护轮修正 |
| 3.4 | README「指标参考」表 phishing.reload 漏列 `unchanged` 态（`PhishingPolicy.java:78`） | 文档遗漏。14.2 指标表已补全 |

## 4. 调查报告（inventory）勘误（已随发现修正）

| # | 原记载 | 实测更正 |
|---|--------|----------|
| 4.1 | LinkForgeProperties「14 个内部类」 | 16 个（模块 01 写作代理逐行实测） |
| 4.2 | 业务表「12 张」 | 13 张（V1 六表 + V2 outbox + V3 dim_daily + V5 api_key + V6 两张 + V9 agg_buffer + V10 link_variant） |
| 4.3 | `docs-enabled` 疑似孤儿属性 | 非遗留：`SecurityConfig.java:30-31` 以 @Value 读取，三层收紧开关之一 |
| 4.4 | `ShortLinkService.isTenantWide :908-910` | `:906-908`（javadoc :905） |
| 4.5 | SingleFlight 残留窗口 `:112-114` | `:111-113` |
| 4.6 | absent 族 `:926-964` 混记 | absentKnown :926-937、markAbsent :939-952、clearAbsent :954-964 |
| 4.7 | RateLimitKeys sw() `:8-16` | `:13-15`（sw() 方法体） |
| 4.8 | consume「4 桶逐个 upsert :97-99」 | `:97-100` |
| 4.9 | ClickEvent eventId 语义注释 `:10-12` | `:9-13` |
| 4.10 | （简报口径）Outbox 三个 mark 带 `WHERE status='PENDING'` | 写侧 mark（:19/:22/:25-26）仅 `WHERE id=#{id}` 无状态守卫；PENDING 过滤只在读侧 selectPendingBatch(:14-17)；状态守卫存在于 WebhookDeliveryMapper(:28/:33/:38)。差异评估已作为教材对照素材 |
| 4.11 | （inventory 口径）window_start「一律 DB 时钟」 | window_start 由 JVM 时钟算 epoch 写入（AggregateWindows.java:27-29），仅过期判定用 DB `UNIX_TIMESTAMP()`——全项目唯一混合时钟点 |
| 4.12 | webhook 包文件数 14 | 15（初版调查误计，以 `find` 为准） |

## 5. 教材与图表的结构性偏差（有意为之，记录备案）

| # | 偏差 | 理由 |
|---|------|------|
| 5.1 | 图 11-shortlink-create 以 standard 档交付（默认 showcase） | showcase 下三条走廊在 6 列网格互斥，约 15 轮修复后仍余 2 条走廊共享 warning（standard 下不构成失败）；升级需重设计幂等分区。见 manifest |
| 5.2 | 图 08-scheduled-tasks 保留 advisory 级路由交叉 | review-2/review-3 各 5/8 处、不同走廊互有胜负，不阻塞门禁；继续摆位收益递减 |
| 5.3 | 图 10-auth-sequence 将 ApiKeyAuthFilter 降级为卡片 | 9 参与方超自动宽度上限且与 viewport-height 门禁互斥（宽度诊断两建议冲突） |
| 5.4 | 模块 13 的「聚合推送」写在 13.2（课表原列 13.3） | 写作简报与课表冲突，按简报执行后已顺平课表 |
| 5.5 | ERD 省略 flyway_schema_history 实体、lf_click_log.event_id→lf_outbox.event_id 画为可选逻辑引用 | Flyway 自维护无业务外键；event_id 引用有 V2 注释与 ClickStatsService 佐证 |
| 5.6 | 功能地图把 README「限流」并入跳转域、「安全」拆入跳转与统计 | 按判定链的真实位置组织，README 表格行的归属是文档视角 |

## 6. 已知但本教材不处理的事项

- FE 波（控制台覆盖）在 `feat/fe-console-coverage` 分支进行中：教材涉及控制台 UI 的描述均为时点口径，API 面不受影响。
- B10（Linux bench）、Geo 维度、Webhook 端口白名单/出口代理：项目冻结项，教材只登记不展开。
- `docs/learning/` 旧资料中的时效性口径（如旧的调用链描述）不逐一勘误；冲突时以本教材与仓库源码为准。
