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

## 7. 3.0.0 更新轮登记：外部审计发现（M1–M8）与未决披露

> 背景：批次 1–3 交付后外部审计（Grok）给 FAIL / MUST_FIX。8 条必修经开发侧逐条对着代码核验全部成立，修复波 A/B/C 与 S/N 波处理完毕，复审改判 PASS 后项目冻结于 tag `3.0.0`。完整案情见 [专题 17](../advanced/17-correctness-waves.md)；此处只登记「发现 → 裁决与现状」。

| # | 发现 | 裁决与现状 |
|---|------|-----------|
| 7.1（M1） | refresh 轮换与 logout-all/改密可交错：撤销返回后仍有会话存活；token_version 读-改-写会丢 bump | ✅ 已修复——全局锁序（租户→用户→refresh）+ 用户行锁串行化 + 版本号原子自增；接缝交错合同测试钉死（SessionService 类注释 :44-54） |
| 7.2（M2） | 最后 ADMIN 护栏在 RR 快照里判定，两个并发降级各看到 2 个 ADMIN 双双放行，租户可归零 | ✅ 已修复——成员写路径第一条语句锁租户行，权限/目标/护栏全部锁内当前读（TeamService 类注释） |
| 7.3（M3） | Webhook 聚合 flusher 快照删行两条丢数：快照后增量随删丢失、迟到新桶撞裸窗 eventId 被吞后删行 | ✅ 已修复——事务化 drain（锁行读 + V15 flush_seq + 只删锁行），事件 id 升 `digest-{ep}-{ws}-{seq}`（WebhookAggregateFlusher 类注释） |
| 7.4（M4） | 创建幂等指纹对访问密码只记 set/unset 一位，同 key 异密码被误判重放 | ✅ 已修复——指纹 create-v2 含密码 HMAC（pepper），信封升 v3，旧信封带密码重试 fail-closed（专题 16 节点六、七） |
| 7.5（M5） | CSV 导入只有 settle 后缓存，无在途互斥，并发同 key 可双执行 | ✅ 已按审计合同闸门口径修复——owner 租约（SET NX + Lua CAS，TTL 120s）；**崩溃恢复明确不在合同内**，见 7.9 |
| 7.6（M6） | 控制台 variant 编辑不带 matchRule，整集保存静默清空路由规则 | ✅ 已修复——未动不提交、动了整集带全（index.html M6 注释块）；真实浏览器手点未做，见 7.10 |
| 7.7（M7） | 强制改密墙只挡 JWT 不挡 API Key；管理员重置不撤被重置者的 API Key | ✅ 已修复——两凭据同墙；重置在同事务永久撤销全部 key（口径由开发侧拍板：永久撤销而非仅挂墙） |
| 7.8（M8） | CSV 导出只有引号转义，公式注入（= + - @ 开头）未中和 | ✅ 已修复——CsvFormat.escapeSpreadsheet 全出口换用 |
| 7.9（未决披露 1） | M5 租约不覆盖崩溃恢复：导入持有者中途死亡、部分行已落库时，租约过期后同 key 重试可能再执行 | 已知限制，复审时明示并被接受——关闭需持久化导入操作表，本轮明确不建；登记于此与专题 17 第 7 节 |
| 7.10（未决披露 2） | M6 的证据为 Node 合同测试 + 后端 IT + 源码走读，无真实浏览器手点记录 | 已知限制，复审时明示并被接受 |
| 7.11（未决披露 3） | M1 合同测试覆盖关键交错而非全操作×全时点穷举矩阵 | 已知限制，复审时明示并被接受；其余格子的依据是锁序论证而非逐格测试 |
| 7.12（口径差） | 复审材料中 M5 的同文件在途错误码写作 `IMPORT_IN_PROGRESS`，源码实测为 `IDEMPOTENCY_IN_PROGRESS`（ShortLinkService.acquireImportLease） | 以源码为准；教材（专题 16/17）一律用源码值 |
| 7.13（既存 flaky） | `DimBreakdownIT.breakdownBucketsDeviceAndRefererHost` 偶发失败（异步分桶竞态，期望 device unknown 得 bot），与批次内容无关 | 已知存量 flaky，登记于项目 backlog 运维项 #13；3.0.0 冻结口径的 508 例为全绿运行的结果 |

## 8. 已知但本教材不处理的事项

- FE 波（控制台覆盖）初版写作时在 `feat/fe-console-coverage` 分支进行中；3.0.0 时点控制台已并入主线（`src/main/resources/static/index.html`，1423 行），教材的控制台描述以 3.0.0 源码为准（M6/S2 的相关段落已在专题 16/17 修订）。
- B10（Linux bench）、Geo 维度、Webhook 端口白名单/出口代理：随项目在 3.0.0 整体冻结，教材只登记不展开；批次 4 池与砍掉清单见专题 17 第 8 节。
- `docs/learning/` 旧资料中的时效性口径（如旧的调用链描述）不逐一勘误；冲突时以本教材与仓库源码为准。
