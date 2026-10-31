# LinkForge 待办与后续（Backlog）

> 集中登记**未做、明确后置**的事项，来源均为 progress.md 各轮记录或 README「诚实边界」段。
> v2.0.0（2026-10-25）整理。按项目范围纪律：**开新轮次需运营者（萧北）指令，AI 不自行启动**。

## 功能待办（需指令再开）

| # | 事项 | 说明 | 来源 |
|---|------|------|------|
| 1 | **B10：Linux 复跑 bench 并刷新数字** | 现有压测数字（health 888 QPS；关同步明细 13→276 QPS、P50 678→31ms）是 **Windows 历史实测**（`df904f8`，2026-10-24，固定窗口限流时期，早于 A7–B8、B6 滑窗和当前 tip），**不能代表当前版本，也不是 Linux 性能**。B10 Linux bench **仍未跑**；跑完再更新 `docs/bench.md` 与 README 引用。未跑之前不要补造数字 | Bw345-F2；Bw2fix-N9 |
| ~~2~~ | ~~**QR 码 + UTM 参数**（B3 备选）~~ | **已闭环（C1，merge `190e1f1`，2026-10-31）**：QR 物料+短时分享链+UTM 写时合并，见 README「物料与投放」；B10（上栏）仍开放 | Bw345-F1 |
| ~~3~~ | ~~Bloom 共享化（RedisBloom 或广播 put）~~ | **已闭环（C2，merge `744ad67`，2026-10-31）**：保留 Guava + Redis pub/sub 广播 put（`linkforge:bloom:put`），RedisBloom 未做（明确不做） | D-A9-2/D-A9-8 |
| ~~4~~ | ~~Bloom 删除回收（定期全量 rebuild）~~ | **已闭环（C2）**：定时 24h + ADMIN 手动 rebuild（旁路原子替换）。诚实口径：rebuild 是容量/fpp 重置与补漏广播，**不是**删码回收——已删码仍占位（经典 Bloom 做不到），DB 层 404 不变 | D-A9-5 |
| ~~5~~ | ~~L1 多实例失效广播（pub/sub）~~ | **已闭环（C2）**：update/disable/enable/delete 广播失效（origin 跳过自己、只剔本地 L1）；TTL 兜底不变 | B8 诚实口径 |
| 6 | 维度扩展：**Geo**（os / browser 已于 C2 闭环，merge `744ad67`） | os/browser 已上线（轻量 UA 规则）；Geo 明确不做于 C2，仍开放 | D-A8-1 / D-A8-5 |
| 7 | AI / PostgreSQL / Supabase / 向量库 | 整体延期，将来另立方案 | 冻结决策「边界与不做」 |
| 8 | 云公网部署 | prod profile 已备好收紧开关；D5=本地 Compose | D5 |

## 运维项（小改进，可随手做）

| # | 事项 | 说明 | 来源 |
|---|------|------|------|
| 10 | Webhook SSRF 残余 | DNS rebinding（校验与连接两次解析的 TOCTOU）、端口白名单、出口代理隔离未做 | B 波 2 诚实限制 |
| 12 | README「tests 211 green」静态徽章会腐化 | 例数增减时需手改徽章数字（或换 CI 生成/动态方案）；v2.0 发布轮跨模型复审 flag #2；C3 轮（2026-10-31）已同步 307 | v2.0 复审 |

## 已做的运维项（v2.1 抛光，仍无新版本号）

| # | 事项 | 落地 |
|---|------|------|
| 9 | Outbox SENT 行自动清理 | `SentRowCleanup` + `LINKFORGE_SENT_RETENTION_DAYS`（默认 7，`<=0` 关闭）。只删 `status='SENT'` 且 `sent_at` 早于窗口的行；outbox 用 DB 时钟。PENDING / FAILED / 在途不删 |
| 11 | Webhook SENT 投递行清理 | 同一任务、同一属性。webhook `sent_at` 用 JVM 时钟比较，与写入时钟一致 |

## 文档与测试残余（v2.0 发布轮处置状态）

| # | 事项 | 状态 |
|---|------|------|
| 12 | progress.md CI 段漏钉子 run id（tip 表述） | ✅ v2.0 轮已改口（`bdda9a1` / run 40166917264） |
| 13 | 连接预算 256 与上限 500 的口径 | ✅ v2.0 轮已注明：256 是最坏情形推导值，天花板是 `--max-connections=500` |
| 14 | 警告页尖括号转义只有间接证据 | ✅ v2.0 轮补 `GatePagesTest` 直接单测（4 例，纯 JUnit 绕开 URL 校验前置拦截） |
| 15 | scrape-token 文件可读性未写文档 | ✅ v2.0 轮写入 README 可观测节（Linux chmod 644 / 容器内 nobody 可读） |

---

> C3 闭环备注（2026-10-31）：**A/B 权重分流 + 批量 CSV 导入已随 C3 闭环**（`feat/deepen-c3-ab-split-bulk-import`，见 `docs/progress.md` C3 章与 README「A/B 权重分流与批量导入（C3）」节）。上表开项 #1（B10）/ #6（Geo）/ #10（SSRF）不受影响，仍开放。
