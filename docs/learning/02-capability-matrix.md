# 能力矩阵与验收标准（活文档）

优先级 P0–P3 与层级 L0–L4 的定义见 [README.md](README.md)。状态四值：未开始 / 学习中 / 待验证 / 已掌握。**规则：只有完成对应文档的自测并通过「验收标准」列的检验，才允许改状态为「已掌握」**；每周日更新一次（[00-roadmap.md](00-roadmap.md) 的复习节奏）。

## Java 基础（来源 [java/ 系列](10-java-basics.md)：10-01..10-10、[11-java-collections.md](11-java-collections.md)）

| 知识点 | 优先级 | 目标 | 项目映射 | 验收标准 | 状态 |
|--------|--------|------|----------|----------|------|
| 类型与引用、参数传递 | P0 | L2 | `setStatus(code, int)` | 预测 10-01 全部示例输出并解释 | 未开始 |
| equals/hashCode、String 不可变 | P0 | L2 | `record` 使用分布 | 手写合规 pair 并解释违反后果 | 未开始 |
| 包装类型与拆箱 | P0 | L2 | 实体 `Long id`/`Integer status` | 说清缓存区间的规范保证与实现差异 | 未开始 |
| 泛型与擦除 | P0 | L3 | `BaseMapper<T>`、`ApiResponse<T>` | 推导擦除带来的四个"不能" | 未开始 |
| 异常体系与设计 | P0 | L3 | `BizException` unchecked 选择 | 五段答「为什么 unchecked」 | 未开始 |
| 注解/反射/动态代理 | P0 | L3 | `@MapperScan` 代理 | 手写 JDK 代理 demo | 未开始 |
| Lambda/Stream/Optional | P0 | L2 | `ClickStatsService` 聚合管道 | 说清惰性求值与 toMap 补零分工 | 未开始 |
| record/sealed/text block | P0 | L2 | `RedirectOutcome` 四态 | 解释编译期穷举收益与 17/21 版本边界 | 未开始 |
| HashMap 原理 | P0 | L3 | `SingleFlight` 的 CHM 用法 | put 流程+扩容+树化三问全答 | 未开始 |
| ConcurrentHashMap | P0 | L3 | 同上 | 说清 putIfAbsent vs computeIfAbsent 的锁差 | 未开始 |
| 线程池七参数 | P0 | L3 | 调度池 size 6（B5） | 手写 TPE 并演示拒绝策略 | 未开始 |

## JVM 与并发（[12-jvm.md](12-jvm.md)、[13-concurrency.md](13-concurrency.md)）

| 知识点 | 优先级 | 目标 | 项目映射 | 验收标准 | 状态 |
|--------|--------|------|----------|----------|------|
| 运行时数据区 | P0 | L2 | 时区 fork（Bw2-M1） | 白板画图+共享/私有区分 | 未开始 |
| GC 与分代 | P0 | L2 | （项目未调参，如实） | 分代假设+回收器三档 | 未开始 |
| 排查四件套 | P1 | L2 | 无直接 | jstack 对号 CPU 线程实验 | 未开始 |
| volatile/CAS/ThreadLocal | P0 | L3 | `PhishingPolicy` 快照、`UserContext` 清理 | 三属性表+泄漏场景 | 未开始 |
| CompletableFuture | P1 | L2 | `SingleFlight` 跟车 | 画领导者/跟随者时序 | 未开始 |

## 算法（[14-algorithms.md](14-algorithms.md)、[92-interview-coding.md](92-interview-coding.md)）

| 知识点 | 优先级 | 目标 | 项目映射 | 验收标准 | 状态 |
|--------|--------|------|----------|----------|------|
| 数组/字符串/双指针 | P0 | L2 | — | 题单阶段一 12 题独立完成 | 未开始 |
| 链表 | P0 | L2 | — | 反转+环+合并 三类 | 未开始 |
| 哈希/栈/单调栈 | P0 | L2 | 幂等键思想 | 有效括号+每日温度 | 未开始 |
| 二分/滑窗 | P0 | L2 | — | 二分答案类各 1 题 | 未开始 |
| 树/堆/BFS/DFS | P0 | L2 | — | 岛屿类+前 K 高频 | 未开始 |
| 贪心/DP | P1 | L2 | — | 打家劫舍+最大子数组 | 未开始 |
| LRU | P1 | L2 | LinkedHashMap 练习对照 Caffeine | 20 分钟手写 | 未开始 |

## 网络与 OS（[20-network.md](20-network.md)、[21-os.md](21-os.md)）

| 知识点 | 优先级 | 目标 | 项目映射 | 验收标准 | 状态 |
|--------|--------|------|----------|----------|------|
| TCP 握手挥手 | P0 | L2 | 容器网络 | 为什么三次/四次+TIME_WAIT | 未开始 |
| HTTP 方法/状态码 | P0 | L3 | 302/HEAD/410/429 | 项目状态码逐个讲语义 | 未开始 |
| HTTPS/TLS | P1 | L2 | 门禁页安全头 | 三防对应+证书验证 | 未开始 |
| 进程/线程/切换 | P1 | L2 | 四进程拓扑 | 排障顺序演练 | 未开始 |
| Linux 排查 | P1 | L2 | scripts 演练 | 断依赖排障流程跑通 | 未开始 |

## Spring 与业务开发（[30-spring-core.md](30-spring-core.md)、[31-spring-boot-mvc.md](31-spring-boot-mvc.md)、[41-mybatis-plus.md](41-mybatis-plus.md)）

| 知识点 | 优先级 | 目标 | 项目映射 | 验收标准 | 状态 |
|--------|--------|------|----------|----------|------|
| IoC/DI/Bean 生命周期 | P0 | L3 | 构造器注入 14 依赖 | 画出依赖图+自动配置一句话 | 未开始 |
| AOP 与事务失效 | P0 | L3 | `@Transactional` 全仓两处 | 四场景复现至少两个 | 未开始 |
| MVC 请求路径 | P0 | L2 | `GlobalExceptionHandler` | 请求链路分层白板 | 未开始 |
| REST 设计与幂等 | P0 | L3 | Idempotency-Key 状态机 | 设计一个新接口契约 | 未开始 |
| Wrapper/逻辑删除/原生 SQL | P0 | L2 | SET NULL 坑、Bloom 播种 | 三例对照答 | 未开始 |

## MySQL（[40-mysql.md](40-mysql.md)）

| 知识点 | 优先级 | 目标 | 项目映射 | 验收标准 | 状态 |
|--------|--------|------|----------|------|------|
| B+ 树与索引设计 | P0 | L3 | `idx_outbox_pending` | 最左前缀+覆盖索引+EXPLAIN | 未开始 |
| 事务/隔离/MVCC | P0 | L2 | 同事务双写 | 隔离级别表+快照读 | 未开始 |
| 唯一索引兜底并发 | P0 | L3 | `uk_code` 重试 | 并发复现实验 | 未开始 |
| 字符集与排序规则 | P1 | L3 | V4 `_bin` 决策 | 讲清 PAD SPACE 教训 | 未开始 |

## Redis 与 MQ（[50-redis.md](50-redis.md)、[51-rabbitmq-outbox.md](51-rabbitmq-outbox.md)）

| 知识点 | 优先级 | 目标 | 项目映射 | 验收标准 | 状态 |
|--------|--------|------|----------|------|------|
| 缓存三问与谱系 | P0 | L3 | 五层防线 | 各两套方案+项目选择 | 未开始 |
| 双删与一致性窗口 | P0 | L3 | 1s 延迟清 | 竞态图默画 | 未开始 |
| Lua 原子限流 | P0 | L3 | `sliding_window.lua` | 五步流程+Retry-After 来源 | 未开始 |
| 丢失三环节 | P0 | L3 | confirm/持久化/DLQ | 三环节表+项目实例 | 未开始 |
| Outbox 取舍 | P0 | L3 | A7 全链 | 五段作答+时序默画 | 未开始 |
| 幂等消费 | P0 | L3 | eventId 两态 | 重放实验 | 未开始 |

## 安全与工程（[32-spring-security.md](32-spring-security.md)、[60-testing-ci.md](60-testing-ci.md)、[61-docker-deploy.md](61-docker-deploy.md)）

| 知识点 | 优先级 | 目标 | 项目映射 | 验收标准 | 状态 |
|--------|--------|------|----------|------|------|
| JWT 与过滤器链 | P1 | L3 | 三过滤器编排 | 排序理由+shouldNotFilter | 未开始 |
| BCrypt/HMAC/时序攻击 | P1 | L2 | 解锁 cookie pwdVer | 三件常识+计时实验 | 未开始 |
| 攻防地图 | P1 | L2 | 门禁/SSRF/越权 404 | 八行背熟 | 未开始 |
| 测试分层与基建 | P1 | L2 | 共享容器+连接预算 | 预算心算+上下文缓存 | 未开始 |
| CI 与红先 | P1 | L2 | ci.yml | 完整红绿一轮 | 未开始 |
| Compose 与指标卫生 | P1 | L2 | 观测 profile | 起栈+无界 tag 危害 | 未开始 |

## 项目深度（[80-linkforge-architecture.md](80-linkforge-architecture.md)、[81-linkforge-flows.md](81-linkforge-flows.md)、[82-linkforge-decisions.md](82-linkforge-decisions.md)）

| 知识点 | 优先级 | 目标 | 验收标准 | 状态 |
|--------|--------|------|----------|------|
| 架构与模块地图 | P0 | L3 | 5 分钟画拓扑+栈表逐行 | 未开始 |
| 十二站跳转判定门 | P0 | L3 | 10 分钟口头作文 | 未开始 |
| Outbox 全链时序 | P0 | L3 | 白板默画+断点说明 | 未开始 |
| 决策五段作答 | P0 | L3 | D4/D7/D9/D10 抽查 | 未开始 |
| 数字纪律 | P0 | L3 | 白名单默写+简历口径 | 未开始 |
| 破坏实验 8 项 | P1 | L3 | 全做并预测对测试 | 未开始 |

## 分布式与设计模式（[70-distributed.md](70-distributed.md)、[62-design-patterns.md](62-design-patterns.md)）

| 知识点 | 优先级 | 目标 | 验收标准 | 状态 |
|--------|--------|------|----------|------|
| CAP/BASE/最终一致 | P2 | L2 | 用项目两处策略举例 | 未开始 |
| 多实例边界表 | P2 | L2 | 默写五机制的影响 | 未开始 |
| Saga/Outbox 关系 | P2 | L1 | 一段话讲清 | 未开始 |
| 模式识别与反过度设计 | P2 | L2 | 项目六实例+症状答法 | 未开始 |

## 阶段验收（与 [00-roadmap.md](00-roadmap.md) 的五阶段对应）

| 阶段 | 通过条件 |
|------|----------|
| 1（w4 末） | Java 基础与并发表全「待验证」以上；算法 16 题；项目跑通 |
| 2（w8 末） | Spring/MySQL/MP 三块验收过；EXPLAIN 报告产出 |
| 3（w12 末） | Redis/MQ/安全验收过；**简历 v1**；网络 OS 过一遍 |
| 4（w15 末） | 项目深度表全「已掌握」；破坏实验 8 项完成 |
| 5（w18 末） | 全矩阵 P0 行「已掌握」；[95-final-selftest.md](95-final-selftest.md) 全卷达标（A≥24、B≥4、C 全过、D≥9、E 全过）；[94-scenario-drills.md](94-scenario-drills.md) 遮答第一拍正确率 ≥70%；两次模面完成；简历 v2 |

## 维护约定

- 每周日 30 分钟：跑一遍当周验收项，更新状态列与「后续行动」备注（直接改表加一列文字即可）。
- 状态为「待验证」超过两周的知识点，回对应文档重做练习节。
- 本表是唯一状态源：不维护第二份打卡表。
