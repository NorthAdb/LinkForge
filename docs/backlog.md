# LinkForge 待办与后续（Backlog）

> 集中登记**未做、明确后置**的事项，来源均为 progress.md 各轮记录或 README「诚实边界」段。
> v2.0.0（2026-10-25）整理。按项目范围纪律：**开新轮次需运营者（萧北）指令，AI 不自行启动**。

## 功能待办（需指令再开）

| # | 事项 | 说明 | 来源 |
|---|------|------|------|
| 1 | **B10：Linux 复跑 bench 并刷新数字** | 现有压测数字（health 888 QPS；关同步明细 13→276 QPS、P50 678→31ms）测于 `df904f8` 固定窗口限流时期，早于 A7–B8 与 B6 滑窗，**不能代表当前版本**；B10 在 Linux 上用 wrk 复测并更新 `docs/bench.md` 与 README 引用 | Bw345-F2；Bw2fix-N9 |
| 2 | **QR 码 + UTM 参数**（B3 备选） | 密码短链的可选配套；差异表见交接单 §1.9，运营者 t94u 拍板本轮不做 | Bw345-F1 |
| 3 | Bloom 共享化（RedisBloom 或广播 put） | 解决多实例「实例 B 新建的码在实例 A 重启前假阴性 404」；需 Redis Stack 模块或新增广播机制 | D-A9-2/D-A9-8 |
| 4 | Bloom 删除回收（定期全量 rebuild） | 经典 Bloom 无法回收条目；已删码永久占位数组 | D-A9-5 |
| 5 | L1 多实例失效广播（pub/sub） | 实例 A 改 URL 后实例 B 在 L1 TTL（≤10s）内可能跳旧值；可加 Redis pub/sub 广播失效 | B8 诚实口径 |
| 6 | 维度扩展：os / browser 维度、Geo | 首版维度只有 device + referer_host | D-A8-1 / D-A8-5 |
| 7 | AI / PostgreSQL / Supabase / 向量库 | 整体延期，将来另立方案 | 冻结决策「边界与不做」 |
| 8 | 云公网部署 | prod profile 已备好收紧开关；D5=本地 Compose | D5 |

## 运维项（小改进，可随手做）

| # | 事项 | 说明 | 来源 |
|---|------|------|------|
| 9 | Outbox SENT 行自动清理 | SENT 行当前永久保留；可按 `DELETE ... WHERE status='SENT' AND sent_at < NOW() - INTERVAL 30 DAY` 定期清理 | A7 审计 N5 |
| 10 | Webhook SSRF 残余 | DNS rebinding（校验与连接两次解析的 TOCTOU）、端口白名单、出口代理隔离未做 | B 波 2 诚实限制 |
| 11 | Webhook SENT 投递行归档 | 同 #9 思路，`lf_webhook_delivery` 长期运行的清理策略 | B5 运维边界 |

## 文档与测试残余（v2.0 发布轮处置状态）

| # | 事项 | 状态 |
|---|------|------|
| 12 | progress.md CI 段漏钉子 run id（tip 表述） | ✅ v2.0 轮已改口（`bdda9a1` / run 40166917264） |
| 13 | 连接预算 256 与上限 500 的口径 | ✅ v2.0 轮已注明：256 是最坏情形推导值，天花板是 `--max-connections=500` |
| 14 | 警告页尖括号转义只有间接证据 | ✅ v2.0 轮补 `GatePagesTest` 直接单测（4 例，纯 JUnit 绕开 URL 校验前置拦截） |
| 15 | scrape-token 文件可读性未写文档 | ✅ v2.0 轮写入 README 可观测节（Linux chmod 644 / 容器内 nobody 可读） |
