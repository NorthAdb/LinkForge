# 专题 14 · 项目面试讲解指南

> 本教材前 13 个专题教你「读懂这个项目」，专题 15–17 补上 3.0.0 的新机制，本专题教你「讲出这个项目」。素材只有四个来源：`docs/demo-talk.md`（面试 Demo 五步与现场话术，v3.0.0 冻结版）、`docs/resume.md`（v3.0.0 冻结口径的简历稿）、docs/progress.md 决策日志（真实取舍案例）、各模块课的「为什么这样设计」节。所有口径与 [专题 13](13-performance-and-consistency.md)、[专题 15](15-auth-sessions-and-teams.md)、[专题 16](16-link-power-features.md)、[专题 17](17-correctness-waves.md) 完全一致：不报当前 QPS、数字带时期、边界主动说。docs/learning/91-interview-project.md 是旧版面试资料，其追问结构有参考价值，但数字是 v2.0.0 时代口径，引用时以本专题为准（第 6 节有对照）。

## 1. 一分钟版（电梯陈述）

进门先说这一段，说完再动手或等追问。它要同时完成三件事：说清是什么，给出可信的规模证据，主动划出诚实边界。

> 我做的是 LinkForge，一个自研的多租户短链与点击分析服务，Spring Boot 3、MySQL、Redis、RabbitMQ，不是拿开源项目换皮。技术上最有含金量的四块：跳转读路径的五层防护，限流、Bloom、空值缓存、Caffeine 加 Redis 两级缓存、singleflight 合并行读，并且每个请求仍然读库里的那一行，禁用和过期不会被旧缓存放出去；点击统计的 Transactional Outbox 管道，明细和 outbox 行同事务落库、热路径不发 MQ、confirm 后才标已发、消费端用确定性 eventId 幂等，断消息队列 302 照常、恢复后自动追平；会话与团队，access 缩到 1 小时、refresh 按 family 轮换、重用超宽限就整族撤销，每个请求回库复核身份，租户有邀请、成员与最后 ADMIN 护栏；还有一次真实的外部审计闭环，审计给了 8 条必修，我逐条核验后分三个 PR 修完，复审改判通过，项目随即冻结在 3.0.0。测试 508 个，226 单测加 280 个 Testcontainers 集成加 2 个时区 fork，CI 全绿。性能数字我只引用带时期口径的历史实测，当前版本的 Linux 复测在待办里还没跑。

这段话的结构是「是什么、四个亮点、规模证据、诚实边界」，四个要素缺一不可。四个亮点的顺序有讲究：读路径（大众考点）、Outbox（可靠性深度）、会话与团队（3.0.0 的新地基）、审计闭环（差异化最强，面试官最容易顺着往下挖），从最容易被追问的排起。规模证据只报测试构成，因为它是唯一「当前为真」的数字。诚实边界一句带过，把详细的边界话术留给第 5 节。

两个纪律。第一，电梯稿里不报 QPS（`docs/demo-talk.md` 电梯稿末行原话「不要在电梯稿里报 QPS」）：面试官还没建立上下文时抛一个数字，只会引来「这数字怎么测的」这类你此刻最不想接的追问。第二，版本只报 3.0.0：`v2.0.0` 是 2026-10-25 的旧 tag（211 例），C1 到 C5 与 2026-11 的三批解冻都没有单独发版，全部收在 3.0.0 里，没有 v2.1（`docs/resume.md` 版本注）。

### 1.1 数字口径卡（背这一张就够）

能报的数字全部在这张卡上，卡外的一律不报。每条数字后面跟着它的口径句，口径句和数字要一起说出来。标「历史」的行只能在讲沿革时用，不能当现状报。

| 数字 | 口径句 |
|------|--------|
| 测试 508 = 226 单测 + 280 Testcontainers 集成 + 2 时区 fork | 「3.0.0 冻结线，CI 全绿」 |
| pom 3.0.0、Flyway V1–V15 | 「3.0.0 功能冻结，tag 已打；V11 会话、V12 邀请、V13 标签与路由、V14 恶意域名、V15 冲刷序号」 |
| 独立复审 PASS_WITH_NITS | 「对产品祖先 `3f545cc` 判的，Gate M1–M8 通过；三项披露在第 5 节，我主动说」 |
| 台阶（历史）：211 → 319 → 440 → 472 → 508 | 「分别是 v2.0.0 tag、C5 冻结线、解冻三批、审计修复波、S/N 波的例数，只讲沿革时用」 |
| 13.3 对 276.1 QPS、P50 678ms 对 31.4ms | 「2026-10-24 Windows 历史窗，固定窗口限流时期，早于当前 tip；报的是『同步明细写支配』这个结构结论」 |
| health 基线 888.8 QPS | 同一时期注，且只用来排除框架瓶颈 |
| 命中率段内 100%（hits +497 / misses +0） | 「预热后那 30 秒的段内数字，不是常态承诺」 |
| 20.1 QPS（sync 关 + Outbox 单插入） | 同一 Windows 时期注，progress.md A7 节，relay 关隔离测量 |
| fault-drill 7 行 PASS（2026-11-01，tip `e0c567c`） | 「行为证据，不是性能证据；那是 C5 时代的实跑，3.0.0 没有新跑」 |
| relay 默认 3 秒初始 + 2 秒节拍、批量 100 | 「配置默认值，不是实测延迟」 |

卡上没有的：任何当前 tip 的吞吐、Linux 数字、万级、BCrypt 毫秒数、Grafana 看板上的阈值（那是 Demo 目标不是生产 SLO）、多实例的任何实测结论（没测过，见第 5 节）。

### 1.2 三十秒压缩版

时间被压缩到半分钟时只说三句：「自研多租户短链，Spring Boot 3 加三件套，508 个测试 CI 绿，版本冻结在 3.0.0。两个最有含金量的点：跳转五层防护但每请求仍读行，会话是 1 小时 access 加可撤销的 refresh 族；再加一个完整的外部审计闭环，8 条必修修完复审改判通过。性能数字我只报带时期口径的历史实测，Linux 复测还没跑。」三句分别完成定位、亮点、边界。

## 2. 十五分钟 Demo 讲法

现场演示按 `docs/demo-talk.md` 的面试 Demo 五步走（README 同款）。默认 12 分钟，追问多就用到 15。下面是压缩成可背的提纲，每步给「说什么、指什么、别说」三样。

| 步 | 内容与词 | 指什么 | 别说 |
|----|----------|--------|------|
| 1. 建链与 302（约 90 秒） | 创建返回 `data.code`，GET 后第一行 `HTTP/1.1 302`、`Location` 是目标。读路径按顺序说：滑窗限流、Bloom 挡从未出现的码、空值缓存挡确认不存在的码、L1/L2、singleflight 合并，判定禁用和过期以这一次读到的行为准 | 状态行 302 与 Location 头 | 「缓存命中所以没读库」（口径是每请求仍读行）；用 `curl -I`（HEAD 不记点击） |
| 2. A/B 分流（约 2 分半，必做） | 一码多目标按权重抽签，weight 是相对权重不必凑 100；3.0.0 起 variant 可带 matchRule，先按设备、语言、时间窗收窄候选集再抽签，无命中回退全集；门禁、密码、UTM 都在链层评估，都过了才抽签；等 5 秒再查 `dim=variant` 统计桶。被问到拟合结论时补一句：配置不稳定或带条件路由的链，报告直接回 NOT_APPLICABLE，不硬给 p 值 | 几次不同的 Location、breakdown JSON、`dim=geo` 的 400 | 「按地区分流」「实验平台」；承诺六次一定 1:3（随机，六连 b 也正常） |
| 3. CSV 部分成功（约 90 秒） | 同步 multipart 上限 100 行，两行成功一行失败，失败带文件行号（表头算第 1 行）；结构性错误整单 400 一行不写；专用 import 限流。导入认 Idempotency-Key：同 key 同文件顺序重放回第一次结果、换文件 409、并发同文件有 owner 租约挡 | `successCount=2`、`failures[0].row=4` | 「异步任务」（它是同步的）；「崩溃也能恰好一次」（租约只管在途并发，崩溃恢复明确不覆盖）；「同 key 并发只执行一次」（创建路径没有租约） |
| 4. 故障演练（约 2 分钟，可口播） | 三段：Redis 停了跳转仍 302（L2 fail-open）；Webhook 5xx 退避重试终态 FAILED 不堵跳转；MySQL 只读时 GET 仍 302（点击记账 fail-open）。收口一句：主链路 302 不依赖 Redis、点击写库、Webhook 任何一侧写成功 | 脚本逐行 PASS 与终行 `FAULT DRILL OK`；历史实跑的 7 行 PASS 在 progress 里（2026-11-01，C5 时代） | 把 SKIP 说成失败；「我刚才又跑绿了」（除非当场看到终行） |
| 5. SSRF 口播（约 60 秒） | 以前缺的是发送时的第二次 DNS；现在一次解析、每个地址过违规段校验、自定义 DnsResolver 只返回校验过的地址；Host、SNI、证书仍按原 hostname；选型 httpclient5 的理由。聚合投递补一句：eventId 带单事务分配的 flush_seq，同窗多次 drain 不撞号吞增量 | 类名 `PinnedHttpSender`、测试 `WebhookRebindingIT`（说「Demo 后可以打开看」） | 现场改 DNS 或扫内网「证明」；「防住所有 SSRF」（端口白名单、出口代理未做） |

演示前先背下三条禁区（`docs/demo-talk.md` 开场禁区节）：

1. 不说「现在能到 xxx QPS」「万级」。被问就翻 `docs/bench.md` 并声明时期：2026-10-24 Windows、固定窗口限流时期，早于当前 tip，B10 没跑。
2. 不说做了 Geo、自定义域名、已上云、集群 Bloom、出口代理、多实例验证。
3. 不说「浏览器我点过了」「导入崩溃也能恰好一次」「同一个 Idempotency-Key 并发只会建一条」——这三句都超出复审接受的范围（`docs/demo-talk.md` 禁区原文）。
4. 不说「我刚刚又跑了一遍全绿」，除非本场真的看到 `SMOKE OK` 或 `FAULT DRILL OK`。

收尾 30 秒把五步收拢：「跳转是稳定的 302 且读行；A/B 在门禁之后收窄加抽签；CSV 能部分成功且导入认幂等键；三个依赖任何一侧写失败 302 还在；Webhook 出站把 rebinding 钉死。」然后主动交底：代码停在 3.0.0 冻结线，测试 508，pom 3.0.0，外部复审 PASS_WITH_NITS，「我后面准备的是把这些取舍讲清楚，不是再加一条产品线」。这句交底既诚实又把话题引向你的强项。

### 2.1 上场前检查（浓缩自 `docs/demo-talk.md` 检查清单）

- `docker compose ps` 三行 healthy；应用已在 8080 跑着（宿主 jar `linkforge-3.0.0.jar`，不靠 compose 起）。
- 终端窗口先导入 `.env` 再登录；PowerShell 只贴 PowerShell 块，`TOKEN=$(...)` 和 `source` 是 bash。
- `fault-drill` 要么开场前自己跑通过，要么改口播 progress 里的 7 行 PASS，不在面试里第一次调试脚本。
- 默念七件套：508、pom 3.0.0、不报 B10、不说 Geo、不说手点过浏览器、不说崩溃恰好一次、SSRF 只口播。
- 步 2 的 A/B 创建带 `-Depth 5`（PowerShell），否则嵌套 variants 被折成类型名，创建会失败。

### 2.2 失败时的四句应对

| 现象 | 第一句话 |
|------|----------|
| 命令报 `is not recognized` | 「提示符是 PS，我贴成 bash 了，换 PowerShell 段。」（环境问题不是项目问题） |
| 429 出现 | 「正好演示限流：ZSET 滑窗，响应带 Retry-After。」（把失败变成考点） |
| breakdown 空数组 | 「再等 5 秒查；outbox relay 是 3 秒初始加 2 秒节拍，统计不在跳转的返回路径上，这正好说明它异步。」 |
| 500 | 「看应用终端最后一屏，多半是依赖没连上；我讲设计，输出以现场为准。」（不现场改代码） |

## 3. 追问树（六层）

旧版资料（`docs/learning/91-interview-project.md`）用三层追问（是什么、为什么、深挖）。本教材把结构展开成六层，让每个考点都能答满一层不重复：是什么 → 为什么这么做 → 不用会怎样 → 还有什么方案 → 你的方案缺陷 → 怎么改进。六层答完，面试官通常自己会跳层，这正是你要的效果：主动权在答案的完整性上。下面九个考点按模块组织，答要点全部出自本教材与源码，锚点可当场翻。

用这棵树有三个技巧。第一，答「缺陷」层时不辩解：先说缺陷本身，再说它被什么兜住，最后说改进登记在哪，三句内完成。第二，「还有什么方案」层要能答出「为什么没选它」，只报名字不给理由是半层。第三，被问到树外的考点（比如分库分表、K8s），如实归位：没做过，但可以说「按本项目的判断顺序会先 EXPLAIN 再分区再读写分离」（旧版资料的数据量十倍答案，方向可用）。

### 3.1 缓存五层防护（[8.1-8.4 课](../modules/08-redis-cache/01-keys-and-ttl.md)、[专题 5](05-cache-hit-and-miss.md)）

| 层 | 答要点 | 锚点 |
|----|--------|------|
| 是什么 | 读路径五层：限流 → Bloom → 空值缓存 → L1/L2 → singleflight 行读；顺序、失败出口、每层挡什么 | `ShortLinkService.java:1065-1116` |
| 为什么这么做 | 三类 miss 各有专属组件：击穿归 singleflight、穿透归 Bloom 加空值缓存、雪崩归 TTL 抖动；行是唯一权威，缓存只许加速不许改判定 | 决策 #8、`UrlLocalCache` javadoc :17-20 |
| 不用会怎样 | 逐层：无 Bloom 则每个幽灵码一次 DB 读；无 absent 则误报码每 60-90 秒穿一次库；无 L1 则每请求多一次 Redis 往返；无 singleflight 则热点码失效瞬间 N 倍行读 | [8.3](../modules/08-redis-cache/03-negative-cache-bloom.md) 第 9 节 |
| 还有什么方案 | RedisBloom 共享结构、缓存整行、分布式锁合并、XFetch 式概率提前刷新；每个方案的取舍说得出 | [8.4](../modules/08-redis-cache/04-singleflight.md) 第 9 节 |
| 你的方案缺陷 | Bloom 假阳性仍付行读；位删不掉，已删码恒阳性靠行读 404 兜；L1 与 Bloom 进程内，多实例有漏广播窗口，最长到 24 小时 rebuild 周期；且多实例从未实测，这条只讲设计不讲结论 | [专题 5](05-cache-hit-and-miss.md) 第 7 节 |
| 怎么改进 | RedisBloom 与行级 L1 都在 backlog 登记未做；改动的先决条件是先过契约：任何「跳过行读」的优化要回答 7.1 练习 4 的三处契约怎么保 | [7.1](../modules/07-redirection/01-decision-chain.md) 练习 4 |

### 3.2 Transactional Outbox（[10.1/10.2 课](../modules/10-rabbitmq-outbox/01-topology-confirm.md)、[专题 7](07-click-record-outbox.md)）

| 层 | 答要点 | 锚点 |
|----|--------|------|
| 是什么 | 点击明细与 outbox 行同一事务落库；relay 每 2 秒捞批发布，correlated confirm，broker ack 才标 SENT；失败 2^n 秒退避上限 60 秒 | `ClickRecordService.java:40-74`、`ClickOutboxRelay.java:37-60` |
| 为什么这么做 | 热路径不碰 MQ（`README.md` 点击统计管道节的设计句）；两行同生共死，明细和事件不会半有半无；D-A7-2 删除了旧直发 | 决策 D-A7-2 |
| 不用会怎样 | 直发 MQ 时 MQ 抖动传染 302、断链期间点击永久丢失、对账等式（聚合=明细=消费）从此对不上 | [专题 13](13-performance-and-consistency.md) 账二 |
| 还有什么方案 | Debezium 读 binlog、RocketMQ 事务消息、直发加重度对账；项目选轮询 relay 因为不引入新组件且演练可脚本化（Debezium 在交接单禁区） | D-A7-3 |
| 你的方案缺陷 | 每跳一次持久化提交，历史口径下它是吞吐支配项（13.3 对 276.1，Windows 时期注）；轮询有 0 到 2 秒可见延迟；PENDING 积压可观测 | `docs/bench.md`、[专题 8](08-mq-consume-idempotency.md) |
| 怎么改进 | sync 关模式把明细降为消费端回填（D-A7-4）；批量攒批提交；双开关矩阵按部署现实降级；结构结论待 B10 复测 | [专题 7](07-click-record-outbox.md) 第 3 节 |

### 3.3 幂等（[10.3 课](../modules/10-rabbitmq-outbox/03-consumer-idempotency-dlq.md)、[专题 8](08-mq-consume-idempotency.md)、[专题 16](16-link-power-features.md) 节点六至八）

| 层 | 答要点 | 锚点 |
|----|--------|------|
| 是什么 | 消费端：eventId 出生即定（sync 开时 `log-{明细id}`，sync 关时 UUID 持久在 outbox 行）；独立事务 INSERT 去重表，捕 DuplicateKeyException 判重；去重与聚合同一短事务。写侧（3.0.0 深化）：创建指纹含密码 HMAC、同键异体 409、重放先于限流；导入有 owner 租约 | 决策 #10、#22、`ClickStatsService.java:57-75`；`ShortLinkService.java:213-264`、`:294-307` |
| 为什么这么做 | 传输是 at-least-once，重放必然发生；确定性键让实时消费与对账重放算出同一个键，幂等天然成立；写侧认体不认键，是因为「同键不同请求」静默重放会吞掉用户的真实修改（M4 原案） | 决策 #10、[专题 17](17-correctness-waves.md) M4 |
| 不用会怎样 | 消费端重放双计；若去重与聚合分两个事务，中间崩溃会少计且对账器永久跳过该行（决策 #22 修的正是这个）；写侧不认体则换密码的重试被当重放，密码被静默吞掉 | 决策 #22、M4 |
| 还有什么方案 | `ON DUPLICATE KEY UPDATE` 探测（被实测否决）、Redis SETNX 判重、消费端业务级判重 | 决策 #11 |
| 你的方案缺陷 | 消费端正确性押在 eventId 出生规则上；写侧并发首创没有 owner 租约（只有 5 秒 pending 标记，尽力而为）；导入租约不覆盖进程崩溃，导一半死掉靠 TTL 过期后重跑 | [专题 7](07-click-record-outbox.md) 第 5 节节点二、[专题 17](17-correctness-waves.md) 第 7 节披露 |
| 怎么改进 | 幂等键尽量提前生成、来源尽量原始（决策 #10 已是此口径）；对账器作为最后安全网常开（sync 开时）；导入崩溃恢复要建持久化操作表，冻结登记未做 | [专题 9](09-stats-reconcile.md)、批次 4 池 |

### 3.4 限流 Lua（[9.1 课](../modules/09-rate-limit/01-sliding-window-lua.md)）

| 层 | 答要点 | 锚点 |
|----|--------|------|
| 是什么 | ZSET 加 Lua 的滑动窗口日志：score 用 Redis `TIME` 微秒时钟，成员 `now-nonce` 保唯一；只有放行才 ZADD；429 带 Retry-After，报价取窗口最老成员分数 | `redis/sliding_window.lua:1-18`（`RedisRateLimiter.java:30` 加载） |
| 为什么这么做 | 精确计数、多实例共享一个窗口；时钟取 Redis 避免多实例漂移；拒绝不占名额防「越重试越锁死」 | [9.1](../modules/09-rate-limit/01-sliding-window-lua.md) 第 9 节 |
| 不用会怎样 | 固定窗口有边界突刺（Bw2-N12 记录的问题，B6 改滑窗修复）；本地计数多实例不共享；被拒也计数则重试风暴自我放大 | D-Bw345（B6 决策行） |
| 还有什么方案 | 令牌桶、GCRA、固定窗口。固定窗口没删：保留给 API Key 日配额（自然日语义，`RedisRateLimiter.java:71-96`）；简历上写滑窗不写令牌桶 | `docs/resume.md` 数字纪律 |
| 你的方案缺陷 | ZSET 内存随窗口内流量线性涨；每请求一次 Lua 往返；纯拒绝式限流对突发不如令牌桶平滑 | [9.1](../modules/09-rate-limit/01-sliding-window-lua.md) 第 6 节 |
| 怎么改进 | 故障 fail-open 加指标已落地；窗口采样、按维度分片未做（如实说，不在 backlog 的已排期项里） | `README.md` 指标参考节 |

### 3.5 会话与团队（[5.2 课](../modules/05-security/02-jwt-auth.md)、[5.4 课](../modules/05-security/04-sessions-and-teams.md)、[专题 15](15-auth-sessions-and-teams.md)）

| 层 | 答要点 | 锚点 |
|----|--------|------|
| 是什么 | access 1 小时（带 `tv` claim）+ refresh 30 天按 family 轮换（只存 SHA-256）；JwtAuthFilter 每请求回查用户行（status、token_version、must_change 三项）；refresh 已轮换且超 10 秒宽限再出示 = 整族撤销加 tv 自增；租户有邀请（单次、7 天、只存哈希）与 ADMIN/MEMBER，管理员重置强制改密并永久撤销该成员全部 API Key | `SessionService.java:133-195`、`TeamService.java:104-121`、`:318-341` |
| 为什么这么做 | v2.0.0 的 24 小时纯 JWT 不可撤销：禁用、改密、踢人都不即时生效。会话进库换来可撤销与可侦测盗用；回查用一次主键点查换全部即时性；找回密码在没有邮件设施的前提下只能是「管理员重置加强制改密」 | [专题 15](15-auth-sessions-and-teams.md) 第 1 节 |
| 不用会怎样 | 回到 v2.0.0 的实景：管理员禁用成员后他的 token 还能用到明天；「退出所有设备」无从谈起，因为没有会话这个实体可撤 | 同上 |
| 还有什么方案 | opaque token 加内省端点、服务端会话表、纯 JWT 加黑名单；API Key 走的是另一条（SHA-256 存库加 scope，且属主回查与改密墙与 JWT 同步覆盖，M7），两条腿对比着讲 | [5.2](../modules/05-security/02-jwt-auth.md)、[12.1 课](../modules/12-api-key/01-apikey-full.md) |
| 你的方案缺陷 | 单 family 登出不 bump tv，该设备的 access 最多残留 1 小时（明示取舍，注释在案）；每请求回查给认证加了 DB 依赖，回查坏了是稳定 503 不是放行；没有邮件找回，信任根转移到租户 ADMIN 本人，对大组织不成立；控制台没有成员管理页，成员管理只有 API | [专题 15](15-auth-sessions-and-teams.md) 第 8 节取舍一、二 |
| 怎么改进 | 会话列表与逐设备撤销 UI、邮件设施都是冻结登记的未做项；回查读副本化要先回答一致性（禁用即时性正是回查买的，不能为性能卖掉） | 批次 4 池、[专题 17](17-correctness-waves.md) 第 8 节 |

### 3.6 Webhook SSRF（[13.3 课](../modules/13-webhook/03-ssrf-and-aggregate.md)）

| 层 | 答要点 | 锚点 |
|----|--------|------|
| 是什么 | 保存与发送各一道校验：只许 http/https、拒绝环回/私网/链路本地/云元数据/CGNAT/ULA；投递时一次解析、每个地址过校验、只连校验过的地址 | `WebhookTargetGuard.java:14-25`、`PinnedHttpSender.java:85-100` |
| 为什么这么做 | 旧实现的洞是 TOCTOU：校验用第一次解析，send 时客户端可能再问一次 DNS，名字被换成内网地址则校验白做 | `WebhookRebindingIT`（测试钉住此场景） |
| 不用会怎样 | DNS rebinding 可把校验通过的域名重绑到 169.254.169.254 这类元数据地址，出站请求变成内网探测 | [13.3](../modules/13-webhook/03-ssrf-and-aggregate.md) |
| 还有什么方案 | 出口代理隔离、端口白名单、JDK HttpClient（被否决：Host 是受限头，连 IP 时证书难按原 hostname 校验） | `docs/demo-talk.md` 第 6 节 |
| 你的方案缺陷 | 端口白名单与出口代理未做，已在 backlog 登记；DnsResolver 只管地址不管端口 | `docs/backlog.md` |
| 怎么改进 | rebinding 已按「一次解析加钉死地址集」关掉；Host、SNI、证书仍按原 hostname 保住虚拟主机与 TLS 语义；剩余边界主动说，不声称「防住所有 SSRF」 | `docs/resume.md` SSRF bullet |

### 3.7 Bloom filter（[8.3 课](../modules/08-redis-cache/03-negative-cache-bloom.md)）

| 层 | 答要点 | 锚点 |
|----|--------|------|
| 是什么 | Guava 进程内位数组，只存短码；负答案权威直接 404，正答案只是提示；启动播种失败回退无 filter 状态，C2 起创建广播 put 加 24 小时旁路 rebuild 与 ADMIN 手动触发 | `CodeBloomFilter.java:186-200` |
| 为什么这么做 | 幽灵码零 DB 成本、进程内零网络；播种失败回退是因为半灌的过滤器可能把真实码 404 掉，比没有更危险；rebuild 失败恢复旧值，两个降级方向统一为「更接近正确」 | [8.3](../modules/08-redis-cache/03-negative-cache-bloom.md) 第 9 节 |
| 不用会怎样 | 每个不存在的码都要付一次 DB 行读加一次 absent 探测，扫描器流量直接打穿到 MySQL | [专题 5](05-cache-hit-and-miss.md) 第 7 节 |
| 还有什么方案 | RedisBloom（共享、可扩展）、只用空值缓存、不做（量小时合理） | [8.3](../modules/08-redis-cache/03-negative-cache-bloom.md) 第 9 节 |
| 你的方案缺陷 | 假阳性无害但行读照付；经典 Bloom 不能删位，已删码恒阳性；多实例下漏广播的码在本实例是假阴性，最长 24 小时窗口 | C2 决策（Bloom 广播与 rebuild 行） |
| 怎么改进 | rebuild 已是旁路原子替换、单飞、409 防重入；RedisBloom 与跨实例 rebuild 广播未做，属于「需指令再开」的范围外项 | progress.md A9 范围声明 |

### 3.8 SingleFlight（[8.4 课](../modules/08-redis-cache/04-singleflight.md)）

| 层 | 答要点 | 锚点 |
|----|--------|------|
| 是什么 | 同码并发合并为一次在途行读：首个请求当 Leader，其余跟车；600 毫秒等待上限，超时自读；飞行结束条目即删，不是缓存 | `SingleFlight.java:57-100` |
| 为什么这么做 | 缓存失效瞬间的热点码击穿是行读放大的主因；Leader 同步执行不引线程池（javadoc :22-23）；超时降级自读保证热点码可用性不被慢 Leader 绑架 | [8.4](../modules/08-redis-cache/04-singleflight.md) 第 9 节 |
| 不用会怎样 | N 个并发同码就是 N 次行读，DB 压力随并发线性放大；这恰是缓存击穿的定义场景 | [8.4](../modules/08-redis-cache/04-singleflight.md) 第 5.2 节 |
| 还有什么方案 | 分布式锁（跨实例但贵且复杂）、异步线程池合并、给条目加 TTL 变缓存（javadoc 拒绝：它不是缓存） | 同上 |
| 你的方案缺陷 | 进程内不跨实例，另一台进程各飞各的；Follower 阻塞等待，占用请求线程 | A10 交接口径 |
| 怎么改进 | 写路径 peek 加 forget 两步删除已修提交竞争（A10 审计 N1）；跨实例合并要引入分布式锁，项目判定为当前量级不值得（推断，如实标注） | progress.md D-A10 决策表 |

### 3.9 外部审计与并发护栏（[专题 17](17-correctness-waves.md)，3.0.0 的收官考点）

| 层 | 答要点 | 锚点 |
|----|--------|------|
| 是什么 | 批次 1–3 交付后把全量代码交外部审计，结论 FAIL 加 8 条必修：撤销非原子（M1）、护栏被旧快照击穿（M2）、聚合冲刷丢数（M3）、指纹少算密码（M4）、导入无在途互斥（M5）、控制台静默清规则（M6）、改密墙漏 Key（M7）、导出公式注入（M8）；分三个 PR 修完，S/N 波处理应修项，复审改判 PASS_WITH_NITS 后冻结 | [专题 17](17-correctness-waves.md) 第 3、5 节 |
| 为什么这么做 | 这 8 条没有一条是功能缺失，全是并发与边界语义：单线程测试全过，错误只在交错、重放、跨组件的缝里现形。自查很难查出自己世界观里的缝，外部审计的价值就在这里 | 同上第 1 节 |
| 不用会怎样（不修） | 撤销返回成功但会话还活着；两个并发降级把租户 ADMIN 降到 0；聚合窗口的增量被删行吞掉；同键换密码被静默重放——每一条都是「演示时全绿、上线后咬人」的形状 | M1–M4 各条目 |
| 还有什么方案 | M5 审计文本内部有两种深度（完整检查点表对闸门租约），开发侧选了闸门口径并在复审提交里明示分歧请审计裁决——审计是输入不是命令，分歧要摆上桌，不能挑对自己有利的读法默默执行 | [专题 17](17-correctness-waves.md) 第 3 节 |
| 你的方案缺陷 | 三项披露至今未关：M5 崩溃恢复不覆盖、M6 真实浏览器手点未做（证据是 Node 合同测试加后端 IT)、M1 参数化矩阵未逐格铺满。复审是在知情下给的 PASS_WITH_NITS | 同上第 7 节 |
| 怎么改进 | 冻结登记：三项披露与批次 4 池（短码防枚举居首）都是解冻后的入口，重启需要新的明确指令，不在面试里承诺时间表 | 同上第 8 节 |

## 4. 设计决策故事 6 个

决策日志（`docs/progress.md`）是这个项目最面试友好的部分：它记的不是「我们决定用 X」，而是「X 是踩过什么之后才长成这样的」。每个故事都是「测试或审计抓住真问题、定位到根因、修复、再被测试钉死」的完整闭环。讲故事的固定结构是四段：事故、定位、修复、钉住。六个都有证据可指（CI run 号、测试名、迁移文件、审计条目号），这正是它与「背题」的区别。

| 故事 | 类型 | 面试价值 |
|------|------|----------|
| matched-rows 幂等探测（#11） | 驱动默认行为反直觉 | 会讲排查过程的层次感 |
| Jackson 进 DLQ（#12） | 框架组件的隐藏依赖 | 注入优于自建的实例 |
| MapperScan 吞接口（#13） | 隐式契约 + 潜伏雷 | 说明测试盲区怎么形成 |
| V4 排序规则（S2） | 正确性下沉到 DB 层 | 「在正确的层修」的示范 |
| @DirtiesContext 竞态（C4） | 测试基建缺陷 | 上下文隔离的单位是什么 |
| 审计 M1/M2（3.0.0） | 并发护栏的通用形状 | 「先查后改」在并发下只是建议，不是保证 |

**故事一：matched-rows 幂等探测失效（决策 #11）。** 事故：2026-10-23 的 DDL 轮，`ClickIdempotencyTest` 抓到重复事件被当成新事件，聚合多计。定位：幂等键原用 `ON DUPLICATE KEY UPDATE` 加影响行数探测，而 MySQL Connector/J 默认返回 matched rows 而非 found rows，重复键场景下返回值永远像「新插入」，探测形同虚设。修复：弃用探测式写法，改为独立事务 INSERT 去重行、捕获 `DuplicateKeyException` 判重。钉住：`ClickIdempotencyTest` 从此是幂等契约的看门人。这个故事的面试价值在于：它不是「不知道 API」型 bug，是「驱动默认行为与直觉相反」型，值得展开讲排查过程。

**故事二：Jackson 日期序列化把事件全送进 DLQ（决策 #12）。** 事故：`MqEndToEndTest` 抓到所有含 `LocalDateTime` 的点击事件变成死信。定位：`Jackson2JsonMessageConverter` 自建的 ObjectMapper 没注册 JavaTimeModule，序列化 java.time 类型直接抛异常；[专题 7](07-click-record-outbox.md) 第 5 节讲过容器 ObjectMapper 带 JavaTimeModule 才写得动。修复：转换器注入 Spring 容器的 ObjectMapper，与业务序列化共用一个配置。钉住：`MqEndToEndIT` 端到端走通含时间字段的事件。教训一句话：框架组件自建的从属对象（内部 ObjectMapper）不会继承你的定制，注入比自建安全。

**故事三：MapperScan 广扫描吞掉业务接口（决策 #13）。** 事故：`ClickEventPublisher.publish()` 运行时报 Invalid bound statement。定位：`@MapperScan("com.linkforge")` 扫了整个包树，把这个纯业务接口也代理成了 MyBatis mapper；stub 时代异步发布恒关，所以埋着一直没炸，接上真 MQ 后第一次触发。修复：扫描收窄到三个 mapper 包。钉住：消费链 IT 端到端覆盖 publisher 路径。这个故事教会两件事：组件扫描是隐式契约，越窄越安全；「测试没覆盖的路径上潜伏多久都可能」，stub 时代的恒关恰好说明了 IT 全绿不等于没有雷。

**故事四：V4 排序规则，大小写在 DB 层就错了（A9 审计 S2，D-A10-9）。** 事故：短码语义要求区分大小写，但 `lf_short_link.code` 用默认 `utf8mb4_0900_ai_ci` 排序规则，建出 `A10x1792843534` 后小写变体在 DB 层同码，Bloom 与唯一索引都拦不住语义错误。定位：这是审计 S2 指出的正确性问题，不是运行时异常；根因在排序规则层，任何应用层校验都只能补丁。修复：V4 迁移把该列改为 `utf8mb4_0900_bin`（NO PAD，刻意不用 PAD SPACE 的 `utf8mb4_bin`）；只改这一列，关联表按「跨表关联一律 link_id」规则不动。钉住：`FlywayBaselineIT` 断言 collation；Bloom 开与关两个上下文的小写变体都 404；关掉 singleflight 后小写变体仍 404，证明语义在 DB 层而非某个开关。这个故事展示的是「把正确性下沉到正确的层」，修复越往下层走，需要钉的测试越少。

**故事五：跨上下文 relay 竞态（C4 轮，progress.md C4 章第 7 项）。** 事故：`WebhookDeliveryIT` 的 maxAttemptsEndsInFailed 用例 90 秒超时，CI run 41223431052。定位：三个 relay-enabled webhook IT 共享同一 Testcontainers 投递表，而 Spring 缓存的应用上下文跨测试类存活，前一个类的 `@Scheduled` relay 还活着，按它自己类的 max-attempts=3 把后跑类刚插的行提前标 FAILED。修复：三类钉 `@DirtiesContext(AFTER_CLASS)`，任意时刻至多一个 relay 存活。插曲要主动讲：第一次修复把 `@DirtiesContext` 的 import 写错包（run 41224568503 编译败），又补一提交修正，两次 failure 都留档。钉住：连接预算小节注明这三类类结束即关上下文，实际峰值低于纸面。这个故事的面试价值在于它是测试基建缺陷而非业务 bug，能讲清「集成测试的隔离单位是上下文」。

**故事六：审计 M1/M2，先查后改的护栏在并发下只是建议（3.0.0 修复波）。** 事故：外部审计在批次 2 的新代码里抓到两个同构的并发洞。M1：refresh 轮换与 logout-all 没有共同串行化点，撤销先提交、轮换后提交时，新铸的后继行逃过撤销——接口返回成功，会话却还活着。M2：最后 ADMIN 护栏先在 REPEATABLE READ 快照里数人再执行降级，两个并发降级各看到「还有 2 个 ADMIN」双双放行，租户归零。定位：两条的根因是同一句——护栏的判定读与它保护的写不在同一个串行化世界里；逻辑都对，读的时机错了。修复：确立全局锁序（租户行 → 用户行 → refresh 行），全部关键区第一条语句先锁行、锁内当前读复核、版本号只走原子 SQL 自增。钉住：用受保护方法做接缝的交错合同测试（把轮换卡在半路并发撤销，断言撤销返回后无会话存活）与屏障交错的双降级测试（断言恰一成功一 400）。这个故事的收口最值得背：**任何「先查后改」的护栏在并发下都是建议而非保证；把检查搬进锁内、把计数搬进 SQL，才是护栏。**（全案见 [专题 17](17-correctness-waves.md) M1、M2。）

## 5. 诚实边界话术

被问到没做的功能时，答案的固定结构是四步：承认（不绕）、说为什么不在范围（拍板或 backlog，不是「忘了」）、说现在的替代（系统怎么兜、怎么报错）、不承诺（不给时间表）。七个例子：

| 被问到 | 话术要点 |
|--------|----------|
| B10（Linux bench 复跑） | 「没跑，登记在 backlog。现有数字全是 2026-10-24 Windows 历史窗，固定窗口时期，早于当前 tip。我用它们报结构结论（同步明细写支配），不报成绩。当前 tip 热路径多了密码门、域名门禁、滑窗 ZSET，没有实测我不猜。」 |
| Geo / 地区分流 | 「没做。统计维度白名单只有 device、referer_host、os、browser、variant，`dim=geo` 返回 400，这个 400 本身就是产品决定（`docs/demo-talk.md` 步 2 演过）。Geo 需要离线 IP 库与隐私口径，在拍板范围外。」 |
| Webhook 端口白名单 / 出口代理 | 「没做。SSRF 的 rebinding 类已钉死（一次解析、全 IP 校验、DnsResolver 只返回已校验地址），但端口维度和出口代理隔离还在 backlog。所以我的说法是『把校验后再解析这一类关上了』，不说过头。」 |
| 前端 SPA / 管理界面 | 「没有 SPA。控制台是单个静态页 `static/index.html`，纯静态、打同一套 API、无 Vue/React、无构建链。3.0.0 它有强制改密页和邀请接受视图，但**没有成员管理页**，成员管理只有 API。它不是产品方向，是演示入口。」 |
| 自定义域名 / ES / 其他 backlog 项 | 「没做，集中登记在 backlog：B10、Geo、端口白名单这些都在同一张表里。这条线的拍板是把主链路、开放面和故障语义做实，开放域名的证书管理与 ES 的成本都不在这条线上。」 |
| CSV 导入崩溃恢复 | 「不宣称。导入租约只管健康 Redis 下的在途并发；持有者进程中途死掉、已写部分行时，租约过期后同 key 重试可能再执行一遍。复审时这条是明示披露之一。要关它得建持久化的导入操作记录表，冻结登记未做。」 |
| 控制台浏览器手点 | 「没做过。控制台改动的证据是 Node 合同测试（断言提交体形状）加后端 IT 加源码走读；M6 的复审披露里这一格是空着的，我不说点过。」 |
| 多实例部署会坏什么 | 「先说结论：没有多实例实测，不宣称验证过。设计上的兜底是：Bloom 与 L1 在进程内，跨实例靠 CacheBus 广播 put 与失效，广播失败打点不阻塞写；漏广播的码本实例假阴性最长到 24 小时 rebuild；relay 多实例抢单靠消费端 eventId 幂等兜住（[专题 9](09-stats-reconcile.md)）；会话与团队的写路径靠行锁串行化，锁序全局统一。每条的上界都说得出，但『设计上成立』和『实测过』是两回事，我只报前者。」 |

通用兜底句（背下来）：「这个没做，我的理解是它属于 X 类问题，如果要做我会先看 Y。不确定的部分我现场推。」（最后半句来自旧版资料的减分表达清单，方向不变。）反方向同样重要：不把做过的说成没做（点击记账 fail-open 已落地，批次 1 起口径），也不把 fault-drill 的 SKIP 说成失败。还有一条 3.0.0 的新纪律：测试全绿之外，被问到稳定性时可以主动提 `DimBreakdownIT` 历史上偶发不稳（异步分桶竞态，已登记为已知 flaky），藏着掖着不如先说。

### 5.1 减分表达对照表

旧版资料（`docs/learning/91-interview-project.md`）的减分表达清单值得整表继承，替换列已按 3.0.0 口径更新：

| 减分表达 | 替代表达 |
|----------|----------|
| 「用了 Bloom 所以没有缓存穿透」 | 「Bloom 负答案权威拦掉从未创建的码，假阳性落回空值缓存与行读兜底，fail-open 有指标」 |
| 「消息不会丢」 | 「at-least-once 加确定性 eventId 幂等，断链有 outbox 行兜底，outbox-drill 可复现追平」 |
| 「支持高并发」 | 「限流五入口加行读合并；历史压测显示同步明细写是支配项（带 Windows 时期注）」 |
| 「我们的安全做得很好」 | 「已防清单（rebinding、密码顺序、HMAC、会话可撤销）加未防边界（端口白名单、出口代理），主动说」 |
| 「同 key 只建一条」 | 「顺序重放只回第一次的结果；同 key 异体是 409；并发首创是尽力标记、导入才有租约；崩溃不恰好一次」 |
| 「外部审计全过了」 | 「复审 PASS_WITH_NITS，Gate 全过，三项披露（崩溃恢复、浏览器手点、矩阵未穷举）照实说」 |
| 「这个很简单，没做是因为没必要」 | 「没做，登记在 backlog，拍板理由是 X；要做的话先看 Y」 |
| 万级 QPS、精通、熟悉全家桶 | 白名单数字（第 1.1 节的卡）加「能独立完成」的具体定义 |

## 6. 简历条目对接

`docs/resume.md` 是 v3.0.0 冻结口径的粘贴稿，它的 bullet 与本教材的对应关系如下。面试官沿着简历往下挖时，每一行都有可翻的章节。

| 简历 bullet（`docs/resume.md`） | 教材支撑 |
|--------------------------------|----------|
| 多租户短链与鉴权（404 隔离、滑窗限流、唯一索引兜并发） | [5.1-5.3 课](../modules/05-security/01-security-filter-chain.md)、[9.1 课](../modules/09-rate-limit/01-sliding-window-lua.md)、决策 #6/#16/#20 |
| 链接面与数据面（标签/检索、matchRule 动态路由、A/B 拟合诊断加适用性门、幂等深化、原始导出、恶意 feed） | [专题 16](16-link-power-features.md)、[6.1/6.3 课](../modules/06-short-link/01-data-model.md)、[11.3 课](../modules/11-statistics/03-stats-api-reconciler.md) |
| 会话与团队协作（access 1h 加 refresh 族、每请求回查、邀请与成员、最后 ADMIN 护栏、重置连坐） | [5.4 课](../modules/05-security/04-sessions-and-teams.md)、[专题 15](15-auth-sessions-and-teams.md)、[专题 17](17-correctness-waves.md) M1/M2/M7 |
| 跳转读路径五层（Bloom、空值缓存、L1/L2、singleflight、写穿双删） | [8.1-8.4 课](../modules/08-redis-cache/01-keys-and-ttl.md)、[专题 5](05-cache-hit-and-miss.md) |
| 点击统计 Outbox（同事务、confirm、eventId 幂等、维度预聚合） | [10.1-10.3 课](../modules/10-rabbitmq-outbox/01-topology-confirm.md)、[专题 7/8/9](07-click-record-outbox.md) |
| 物料与开放面（QR、UTM 写时合并、API Key 双 scope、Webhook HMAC 与聚合 flush_seq） | [6.4 课](../modules/06-short-link/04-qr-utm-materials.md)、[12.1 课](../modules/12-api-key/01-apikey-full.md)、[13.1-13.3 课](../modules/13-webhook/01-endpoints-and-cipher.md) |
| A/B 分流与批量 CSV（部分成功、导入认幂等键与租约） | [7.3 课](../modules/07-redirection/03-variants-split.md)、[6.3 课](../modules/06-short-link/03-crud-lifecycle-csv.md)、[专题 16](16-link-power-features.md) 节点八 |
| SSRF 钉死与故障演练、点击记账 fail-open | [13.3 课](../modules/13-webhook/03-ssrf-and-aggregate.md)、[15.3 课](../modules/15-testing-deployment/03-compose-and-drills.md)、D-C5-1/6 |
| 安全边界（BCrypt 顺序、HMAC cookie、B9 门禁、feed 快照） | [7.2 课](../modules/07-redirection/02-password-and-domain-gates.md)、[专题 16](16-link-power-features.md) 节点九 |
| 压测定位（带时期的历史窗） | `docs/bench.md`、[专题 13](13-performance-and-consistency.md) 第 3 节 |

三份材料的分工。简历是入口（30 秒扫读，每行经得起两层追问）；Demo 是现场（15 分钟，按第 2 节的五步走）；本教材是弹药库（被追问时翻锚点，六层追问树照着答）。简历末尾的「简历上不要加的句子」（不写当前 Linux QPS、不写 Geo、不写 v2.1 或 2.0.0、不把 SKIP 说成失败、不写万级、不说崩溃恰好一次）与本专题全部小节同源，改简历时先过这一组。

每次面试后的 15 分钟复盘，对着这张自查清单过一遍（结构沿用旧版资料的模面复盘模板）：

1. 有没有说出第 1.1 节数字卡之外的数字？哪句？
2. 六层追问树在哪一层被打穿？对应第 3 节哪个考点？
3. 有没有把 SKIP、环境故障说成失败，或把没跑的脚本说成跑绿？
4. 被打断时先延期了吗（`docs/demo-talk.md` 第 9 节的应对句）？
5. 薄弱考点回哪个模块课补，deadline 是哪天？

### 6.1 旧资料口径校准

`docs/learning/91-interview-project.md` 写于 v2.0.0 之后、C1-C5 之前。它的追问结构（结论、原理、边界、项目对照四段答法；录音复盘；减分表达清单）仍是好方法，本专题继承了后两者。但数字与能力清单已过时两代，对照如下（中间一代是 C5 口径，本专题的上一版用的就是它）：

| 项 | 旧版（91-interview-project.md） | 当前口径（以 `docs/resume.md` 为准） |
|----|--------------------------------|--------------------------------------|
| 测试构成 | 211 例（90+119+2） | 508 例（226+280+2），3.0.0 冻结线；中间台阶 319（C5）/440（三批）/472（修复波）只作沿革 |
| 版本 | 2.0.0 | 3.0.0；`v2.0.0` 只是 2026-10-25 的 tag |
| 五层防护说法 | 五层读取防护 | 相同，但补充「每请求仍读行」必须一起说 |
| 压测数字 | 13→276 QPS 白名单 | 相同数字，但时期注从可选变成了强制句 |
| 能力清单 | 无 A/B、CSV、SSRF 加固、记账 fail-open | 已含 A/B 与 matchRule 动态路由、CSV 与导入幂等、会话族与团队、恶意 feed、A/B 适用性门；追问树第 3.5/3.9 节是 3.0.0 的新增考点 |
| 审计 | 无 | 外部审计 FAIL → 三波修复 → 复审 PASS_WITH_NITS → tag 3.0.0 冻结，是当前最强的叙事主线 |
| Demo 主线 | 密码链与 Grafana | 面试五步（建链、A/B、CSV、故障、SSRF），Grafana 降为可选 |

两份材料数字冲突时，一律以 `docs/resume.md` 与 `README.md` 的当前口径为准；本教材模块课与综合专题（[专题 13](13-performance-and-consistency.md)、[专题 15](15-auth-sessions-and-teams.md)、[专题 16](16-link-power-features.md)、[专题 17](17-correctness-waves.md)）是答案要点的最终出处。
