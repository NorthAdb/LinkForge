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
| 10 | Webhook SSRF 残余 | DNS rebinding（校验与连接两次解析的 TOCTOU）**已随 C4 闭环 ✅**（投递时一次解析 + 全 IP 校验 + 连接钉死保 Host/SNI，见 `docs/progress.md` C4 章）；端口白名单、出口代理隔离仍开 | B 波 2 诚实限制；C4 收口 |
| 12 | README「tests 211 green」静态徽章会腐化 | 例数增减时需手改徽章数字（或换 CI 生成/动态方案）；v2.0 发布轮跨模型复审 flag #2；C3 轮（2026-10-31）已同步 307；C4 轮（2026-11-01）已同步 315；C5 轮（2026-11-01）已同步 319；批次 1 对账（2026-11-07）同步 335；批次 2 收尾对账（2026-11-08）同步 366；批次 3 收尾对账（2026-11-08）同步 440；审计修复波收尾对账（2026-11-08）同步 472 | v2.0 复审 |
| 13 | `DimBreakdownIT` 间歇失败（flaky） | 症状：`breakdownBucketsDeviceAndRefererHost` 期望 device 分桶 `unknown`、偶发读到 `bot`——点击事件异步消费未落齐时断言分桶内容，属等待信号不充分。批次 1 已修过一版（改等分桶数据本身，不再拿 totalClicks 当同步信号），仍未根除：2026-11-08 在 `13de6ec` 的同提交双跑中一红一绿（push 跑绿、PR 跑红），其余轮次全绿。加固方向：断言前按本用例的点击特征等待目标分桶行齐备（或给用例独立的 code 前缀隔离数据），单独小批处理，不并入功能批次 | 批次 2 收尾对账 |

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
>
> C4 闭环备注（2026-11-01）：**Webhook SSRF 的 DNS rebinding 段已随 C4 闭环**（`feat/deepen-c4-ssrf-rebinding-drills`——投递时一次解析 + 全 IP 校验 + 连接钉死，故障演练与 C3 审计修复并入，见 `docs/progress.md` C4 章）；#10 余下端口白名单/出口代理仍开。#1（B10）/ #6（Geo）不受影响，仍开放。
>
> C5 闭环备注（2026-11-01）：**C4-S1/N1/N2/N3 已随 C5 闭环**（`feat/deepen-c5-closeout`——点击记账 fail-open：主链路 302 不依赖 Redis / 点击写 / Webhook 任一侧写成功，fault-drill mysql-ro 主探针改 GET 302；C4 章 main tip 记账行、absent fail-open meter、Compose 手验证据入档，见 `docs/progress.md` C5 章）。**功能冻结声明：C5 审计通过后 LinkForge 功能冻结**——不再开产品能力轮；#1（B10）/ #6（Geo）/ 端口白名单 / 出口代理仍开，但「无萧北指令不开」。
>
> 解冻批次闭环备注（2026-11-07/08）：冻结后经运营者指令开两批并已全部合入 main。**批次 1（审计修复，PR #5）**：登录双桶限流 + 失败时序恒定、stats 窗口口径统一、Webhook 聚合冲刷改快照行删除、Outbox 状态守卫、链接 update 单事务、BizException 拆 httpStatus/code + cause 构造。**批次 2（认证重构 + 团队协作，PR #7 + #10）**：access 1h + refresh 30 天轮换/重用检测（V11、`token_version` 即时撤销、过滤器每请求回查用户状态、改密/登出全家桶、控制台静默刷新）；邀请制加入 + ADMIN/MEMBER 成员管理 + 最后 ADMIN 护栏 + 管理员重置密码（V12）。两批收尾各做了一次文档对账（PR #6 与本轮）。测试 319 → 366。#1（B10）/ #6（Geo）仍开放、仍「无指令不开」；新增运维项 #13（DimBreakdownIT flaky）。
>
> 批次 3 闭环备注（2026-11-08）：**P1 主体批已合入 main（PR #12 + #13）**。3a：链接标签（单列）+ 关键词检索、variant 动态路由（设备/语言/时间窗三维规则收窄抽签，V13）。3b：原始点击导出（10 万行上限）、A/B 分布拟合诊断（卡方拟合，非转化口径）、幂等深化（请求指纹同 key 异体 409、重放不烧限流、CSV 导入支持 Idempotency-Key——**D-C3-23「导入刻意不认幂等头」经运营者指令推翻**）、URLhaus 恶意域名 feed 同步（默认关、判定仍纯本地，V14）。测试 366 → 440，收尾文档对账随本轮。P2 候选（UTM 构建器、Bot 过滤扩展、实时点击流、短码防枚举、二维码定制保留条目）未开，仍「无指令不开」；#1（B10）/ #6（Geo）不受影响。

> 外部审计修复波闭环备注（2026-11-08）：**批次 1–3 交付经外部审计（Grok）复审判 FAIL/MUST_FIX，8 项必修已全数修复并合入 main（PR #15 + #21 + #17）**。M1 refresh/logout-all 同锁原子化（全局锁序 租户→用户→refresh、token_version 原子自增、凭据列级更新）；M2 最后 ADMIN 护栏改锁内当前读（成员变更事务首句锁租户行）；M7 管理员重置同事务永久撤销成员全部 API Key、改密墙覆盖被撤 Key；M3 聚合冲刷改单事务 drain + 每 endpoint 单调 flush_seq（V15 `lf_webhook_flush_state`，eventId 撞车吞删路径删除）；M4 幂等指纹纳入密码 HMAC（pepper，信封升 v3，存量 fail-closed）；M5 CSV 导入 owner lease 互斥（同文件 IN_PROGRESS/异文件 REUSED，**崩溃恢复不在本波覆盖**，按审计 §12.4 闸门口径交付）；M6 控制台编辑无损保存 matchRule（Node 合同测试 + HTTP IT；真实浏览器手点未做，待审计方在其待验栈完成）；M8 两处 CSV 导出公式中和。测试 440 → 472（200 + 270 + 2）。审计 Should-fix 排下一波：**S1 优先**（A/B 拟合对条件路由/配置变更返回 NOT_APPLICABLE），其后 S2 控制台闭环、S3 feed 上限与快照观测、S4（stats 一致快照读、pending owner CAS、retention 清理、跨午夜开窗日语义、JwtAuthFilter DB 故障 503）、N1 异常日志卫生、N2 progress.md 索引收口；未有运营者指令不开。
