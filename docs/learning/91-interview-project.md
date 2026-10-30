# 项目追问树与简历表达

LinkForge 面试的三份材料：30 秒定位语、5 分钟演示脚本、追问树（三层）。铁律来自仓库 README 与 [82-linkforge-decisions.md](82-linkforge-decisions.md) 的数字纪律：**只讲已验证的事，数字带口径，边界主动说**。

## 30 秒定位语（自我介绍的项目段）

> 「我做了一个多租户短链和点击分析的服务，Spring Boot 3.3 + MySQL + Redis + RabbitMQ，Java 17，211 例测试（单元 90 + 集成 119 + 时区 fork 2）在 Testcontainers 和 CI 上全绿。技术上最有含金量的三块：跳转热路径的五层读取防护（限流、Bloom、空值缓存、两级缓存、singleflight 合并行读）；点击统计的 Transactional Outbox 管道（同事务落库、Relay 投递、幂等消费、断 MQ 演练可追平）；以及一轮真实故障——JVM 和 MySQL 时区错配把 20 秒重试拉成 8 小时，修完用时区 fork 的集成测试钉死。性能数字我只引用带时期口径的实测值，当前版本的复测还在待办里。」

要素：规模（测试数/栈）、三个差异化点、诚实边界。**不出现**未实测 QPS、「防钓鱼系统」、微服务词汇。

## 5 分钟演示脚本

1. 起环境（`docker compose up -d` + jar），`smoke.sh` 全绿——30 秒。
2. 建一条带密码的短链，演示解锁 cookie 与限流顺序（第 6 次正确密码仍 429）——60 秒。
3. 打开 Grafana 跳转面板，压几个请求看 redirects 计数与延迟直方图——60 秒。
4. 跑 `mq-drill.sh`：断 MQ 期间 302 照常、恢复后统计追平——这是全片高潮——90 秒。
5. 收尾：打开 `sliding_window.lua` 或 `ShortLinkService.resolveTarget`，指着一行注释说「每一层防什么写在注释里」——40 秒。

## 追问树（面试官视角，三层）

### 第一层：这是什么、怎么跑的

- Q 项目解决什么问题？→ 多租户短链服务 + 点击分析；业务壳短链，内核是「高并发读路径 + 可靠异步统计」。
- Q 技术栈为什么这么选？→ 学习目标导向（MySQL/Redis/RabbitMQ/JWT 是招聘高频），同时每个组件有真实理由：MP 的单表生产力、RabbitMQ 的 confirm 语义完整（[82-linkforge-decisions.md](82-linkforge-decisions.md) D7 对比）。
- Q 架构画一下？→ [80-linkforge-architecture.md](80-linkforge-architecture.md) 拓扑图+模块地图。

### 第二层：读路径深挖

- Q 一次跳转经过什么？→ 链 4 十二站判定门（[81-linkforge-flows.md](81-linkforge-flows.md)），每步「防什么/怎么实现/失效场景」。
- Q 为什么缓存命中还读 DB？→ 行是真相（D4）：禁用/过期/密码/门禁强一致；缓存只优化 URL 字段。
- Q 缓存和 DB 不一致窗口多大？→ 双删 1s；absent 60s+抖动；L1 10s。每层的上界都说得出。
- Q Bloom 误判/失效了怎么办？→ 假阳性无害（回源）、fail-open、多实例假阴性的诚实边界（backlog #3）。
- Q 热点码被打爆怎么办？→ singleflight 合并 + 限流挡量；追问「为什么不用分布式锁」→ 每请求必须读行，锁只串行化读（D6）。
- Q 限流被拒的请求算不算次数？→ 不算（只放行才 ZADD），否则自我锁定；Redis 挂了 fail-open + 指标可见。

### 第三层：可靠性与安全深挖

- Q 点击数会不会丢？→ 三环节表 + Outbox（D7）：同事务落库、confirm 投递、幂等消费、对账安全网；断 MQ 演练实证。
- Q exactly-once 吗？→ 不是传输层 exactly-once；at-least-once + eventId 幂等 = 效果等价。轻率声称严格 exactly-once 是陷阱。
- Q 重复投递/消息乱序？→ 幂等吸收重复；按天聚合不依赖顺序——如实说没做顺序保证。
- Q 时区 bug 讲一下？→ F1 三段式：现象（20s 变 8h）→ 根因（JVM+8 vs MySQL UTC）→ 修复与钉子（DB 时钟 + 时区 fork IT）。
- Q 密码短链怎么防爆破？→ 两级限流先于 BCrypt；cookie 带 pwdVer 改密即作废；常量时间比较。
- Q 多实例部署会坏什么？→ 背 [70-distributed.md](70-distributed.md) 边界表：Bloom 假阴性、L1 不一致 ≤10s、relay 重复投递（幂等兜底）。
- Q 这不就是培训项目的换皮？→ 反同质化答案：自研骨架；每模块有「诚实边界」；有审计轮与红史；能说出「与典型教程短链的三点差异」——五层防线、Outbox+对账、时区故障的完整闭环（教程项目通常止步于 CRUD+缓存）。

### 第四层（压力测试）：边界与反思

- Q 哪些是过度设计？→ Bloom 对当前量级偏重（backlog 有记录），但防增长且是面试考点；能自评比全盘辩护可信。
- Q 重做会改什么？→ 保留五层与 Outbox；Bloom 可后置、webhook 二跳可并入 MQ——但说清现在的理由。
- Q 数据量十倍怎么办？→ 测量优先：EXPLAIN→分区归档→读写分离→才谈分表（顺序即判断力）。
- Q 压测数字？→ 只报白名单：13→276 QPS（同步明细写是支配项，注明固定窗口时期 Windows 环境）；health 基线 888。**不报**未复测的 v2.0.0 数字（B10 挂起）。

## 简历两行模板（每行经得起两层追问）

- 「实现短链跳转五层读取防护（Redis 滑窗限流、Bloom 防穿透、空值缓存、Caffeine+Redis 两级缓存、singleflight 行读合并），行状态始终以 DB 为真相，禁用/过期语义不被缓存遮蔽。」→ 追问都有 [50-redis.md](50-redis.md) 与 [81-linkforge-flows.md](81-linkforge-flows.md) 支撑。
- 「基于 Transactional Outbox 构建点击统计管道：同事务落库、confirm 感知投递、eventId 幂等消费、断 MQ 演练追平；修复 JVM/MySQL 时区错配导致的退避放大缺陷并以时区 fork 集成测试钉死。」→ 支撑在 [51-rabbitmq-outbox.md](51-rabbitmq-outbox.md) 与 [82-linkforge-decisions.md](82-linkforge-decisions.md) F1。

**不写**：ES/分库分表/K8s/百万 QPS/「精通」。写了的每一行都要能接受「往下挖两层」。

## 减分表达清单（对照自查）

| 减分表达 | 替代表达 |
|----------|----------|
| 「我们用了 Bloom 所以没有穿透」 | 「Bloom 权威否定不存在的码，假阳性回源 DB 兜底，fail-open 有指标」 |
| 「消息不会丢」 | 「三环节各有补丁，at-least-once 投递，重复靠幂等吸收，断 MQ 演练可复现」 |
| 「支持高并发」 | 「限流五入口+行读合并；压测显示同步明细写是支配项（带环境口径）」 |
| 「很熟悉 Kafka（没用过）」 | 「生产用过 RabbitMQ，Kafka 的分区/offset 模型了解概念，可靠性问题域相同」 |
| 「这个我没做过但很简单」 | 「这个没做过，我的理解是 X，不确定的部分我会现场推」 |
| 「我们的安全做得很好」 | 「已防的清单 + 未防的边界（DNS rebinding、无 WAF）」——主动暴露边界是加分 |
| 万级 QPS/精通/熟悉全家桶 | 白名单数字 + 「能独立完成」的具体定义 |

## 模面复盘模板（每次模面后 15 分钟填）

1. **被打穿的位置**：哪道追问第三层答不出？（对应 [90-interview-knowledge.md](90-interview-knowledge.md) 域号）
2. **表达问题**：录音里的「嗯/然后」密度、超长句、术语混用。
3. **事实风险**：有没有说出白名单外的数字或「我们」开头的不实主语（项目是个人做的，「我」更诚实）。
4. **下次动作**：回哪个文档哪一节，deadline 是哪天（进 [93-glossary-review.md](93-glossary-review.md) 薄弱清单）。

## 面试礼仪与表达

- 答题四段：结论→原理→边界→项目对照；不确定就标「这个我推断/未验证」——诚实是差异化。
- 被追问卡住：说出卡点请提示，硬编是减分。
- 面试官沉默时：主动补「这个设计当时的备选是 X，选 Y 因为 Z」——展示决策过程。
- 每次模面录音，对照追问树标记「哪层被打穿」，回对应文档补（[90-interview-knowledge.md](90-interview-knowledge.md) 的域索引）。

## 自测

1. 对镜讲 30 秒定位语，检查是否出现任何白名单外数字。
2. 让朋友（或 AI）扮演面试官走完三层追问，统计被打穿的层。
3. 简历两行各写三层「如果被追问我会答什么」的提纲，缺一层就回文档补。
