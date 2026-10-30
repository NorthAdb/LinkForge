# 学习路线图：20 周备战 2027 春招

起点 2026-10-26（已开始），目标 2027 年春招 Java 后端实习/校招岗。默认节奏每周 10–12 小时，共 18 个内容周 + 2 个缓冲周。春招主波在 2027 年 3–4 月，提前批可能 2 月就开始，所以第 12 周起简历必须随时可用（见「里程碑」）。

算法不留在最后：从第 1 周起每周固定投入约 3 小时，题单见 [14-algorithms.md](14-algorithms.md)，题目卡片见 [92-interview-coding.md](92-interview-coding.md)。

## 第 0 周准备清单（正式开始前一次做完）

| # | 事项 | 验证命令/方式 | 不通过的出路 |
|---|------|---------------|--------------|
| 1 | JDK 17+（本机 21 亦可，编译目标 17） | `java -version`、`javac -version` | 装Temurin 17/21 |
| 2 | Maven（本机在 `D:\tools\apache-maven-3.9.9`） | `mvn -v`；找不到就用完整路径 `D:\tools\apache-maven-3.9.9\bin\mvn.cmd -v` | 见 [22-tools.md](22-tools.md) 环境提示 |
| 3 | Docker Desktop 运行中 | `docker version` | 启动 Docker Desktop，确认 WSL2 正常 |
| 4 | 仓库拉取并跑起依赖 | 仓库 README「五步跑通」：`cp .env.example .env` → `docker compose up -d` → 三容器 healthy | MySQL 首次初始化约 5 分钟，耐心等 healthy |
| 5 | 应用能起、冒烟通过 | `mvn -q -DskipTests package` → 导出 .env → `java -jar target/…jar` → `bash scripts/smoke.sh` 输出 SMOKE OK | 口令问题按 README 的两套口令说明排查 |
| 6 | IDE 与调试器就绪 | IDEA 导入项目，按 [81-linkforge-flows.md](81-linkforge-flows.md) 的断点实操走通链 4 | 断点实操只装 IDEA 即可做 |
| 7 | 学习环境自检 | `python docs/learning/check.py` 输出 0 FAIL；`python docs/learning/verify_examples.py` 全部示例通过（需 JDK 在 PATH） | Python 3.10+ 已装即可，无需其他依赖 |

全绿之后，从第 1 周开始。**不要跳过第 5 步**——整个 20 周的练习都以「环境跑得起来」为前提。

## 节奏模板（每周 10–12 小时怎么切）

| 板块 | 时长 | 内容 |
|------|------|------|
| 新知 | 4h | 读当周文档，笔记只记「没它怎样/它带来什么新问题」 |
| 实操 | 3h | 把当周文档的示例代码亲手编译运行一遍 + 项目靶场任务 |
| 算法 | 3h | 当周题型 4–6 题，错题入 [93-glossary-review.md](93-glossary-review.md) 复习清单 |
| 复习 | 1–2h | 上周自测错题重做 + 面试问答口述一遍 |

## 阶段总览

| 阶段 | 周 | 主题 | 核心产出 | 验收锚点 |
|------|----|------|----------|----------|
| 1 基础补齐 | w1–4 | Java、集合、JVM、并发入门 + 项目跑起来 | 竞态复现修复、HashMap 简版、项目三容器跑通 | 各文档过关标准 |
| 2 Spring 与业务开发 | w5–8 | Spring 核心、Boot/MVC、MySQL、MyBatis-Plus | 从零 hello API、EXPLAIN 对比报告 | 阶段二验收表 |
| 3 中间件与 CS 基础 | w9–12 | Redis、RabbitMQ/Outbox、Security、网络、OS、工具 | smoke/mq-drill 跑通、缓存三问白板 | 阶段三验收表 |
| 4 项目深入 | w13–15 | 调用链追踪、设计决策、测试/CI、Docker 部署 | 跳转十二站判定门白板、破坏实验 8 项 | 阶段四验收表 |
| 5 面试冲刺 | w16–18 | 面试题库、项目追问、简历、模拟面试 | 每周 2 次模面、简历定稿 | 阶段五验收表 |

## 阶段 1：基础补齐（w1–4，2026-10-26 起）

| 周 | 新知 | 代码与实操 | 算法 |
|----|------|-----------|------|
| w1（10-26） | Java 基础系列前半：[10-01 类型与引用](java/10-01-types-and-references.md)、[10-02 类与多态](java/10-02-oop.md)、[10-03 相等性与 String](java/10-03-equality-and-strings.md)；[80-linkforge-architecture.md](80-linkforge-architecture.md) 通读一遍建立地图 | 按仓库 README「五步跑通」把项目跑起来；`javac`+`java` 手工编译一个类 | 数组与字符串 4 题 |
| w2 | Java 基础系列中段：[10-04 包装类型](java/10-04-wrappers-and-boxing.md)、[10-05 泛型](java/10-05-generics.md)、[10-06 异常](java/10-06-exceptions.md)；[11-java-collections.md](11-java-collections.md) | 双线程各加 10 万次复现竞态，改 `AtomicInteger` 修复；手写含扩容的简版 `MyHashMap` | 链表 4 题 |
| w3 | [10-07 注解反射代理](java/10-07-annotations-reflection-proxy.md)、[10-08 Lambda 与 Stream](java/10-08-lambda-stream-optional.md)；[12-jvm.md](12-jvm.md) | 手写 JDK 动态代理示例（10-07 的 ProxyDemo 逐行敲）；用 `record` 重写一个 DTO | 哈希与集合 4 题 |
| w4 | [10-09 枚举与日期](java/10-09-enum-datetime-bigdecimal.md)、[10-10 Java 17](java/10-10-java17.md)；[13-concurrency.md](13-concurrency.md) 全 | 白板画 JVM 内存图；读 [81-linkforge-flows.md](81-linkforge-flows.md) 第 1 条链路（应用启动） | 栈与队列 4 题；**阶段 1 总自测** |

**阶段 1 验收**：能解释 JDK/JRE/JVM 与栈堆区别；能说清 `BizException` 为什么继承 `RuntimeException`；能讲 `SingleFlight` 里 `putIfAbsent` + 两参 `remove` 的并发意义；项目本机跑通且能指出一次请求的分层路径。

## 阶段 2：Spring 与业务开发（w5–8）

| 周 | 新知 | 代码与实操 | 算法 |
|----|------|-----------|------|
| w5 | [30-spring-core.md](30-spring-core.md)（IoC/DI/Bean/装配） | start.spring.io 生成空工程，写 `POST /api/echo`：JSON 入参、校验、统一响应、统一异常 | 二分查找 4 题 |
| w6 | [30-spring-core.md](30-spring-core.md)（AOP/事务）+ [31-spring-boot-mvc.md](31-spring-boot-mvc.md)（请求链路/REST） | curl `-D -` 观察自己接口的响应头；在 LinkForge 里找出全部关键 Bean 并画出依赖方向 | 滑动窗口与双指针 4 题 |
| w7 | [31-spring-boot-mvc.md](31-spring-boot-mvc.md)（校验/响应体/幂等）+ [40-mysql.md](40-mysql.md)（索引/EXPLAIN） | 造 100 万行测试表，无索引 vs 有索引各跑一次 `EXPLAIN`，记录 type/rows 差异 | 前缀和与区间 4 题 |
| w8 | [40-mysql.md](40-mysql.md)（事务/MVCC/锁）+ [41-mybatis-plus.md](41-mybatis-plus.md) 全 | 复现「先查后插并发漏判」，再用 `uk_code` 唯一索引 + 重试修复；跑 [81-linkforge-flows.md](81-linkforge-flows.md) 第 3 条链（创建短链）断点观察 | **阶段 2 总自测** |

**阶段 2 验收**：不查资料画出一次 HTTP 请求的完整分层；说出构造器注入为什么优于字段注入；解释 `uk_code` 怎么兜底并发撞码；`_bin` 排序规则如何变成「短码区分大小写」这个业务特性。

## 阶段 3：中间件与 CS 基础（w9–12）

| 周 | 新知 | 代码与实操 | 算法 |
|----|------|-----------|------|
| w9 | [50-redis.md](50-redis.md)（结构与缓存三问） | redis-cli 玩五种结构；手工模拟固定窗口边界突刺 | 树与遍历 5 题 |
| w10 | [50-redis.md](50-redis.md)（一致性/滑窗限流） | 白板默画「写穿+延迟双删」时序并指出竞态点；读 `sliding_window.lua` 说出五步流程 | 堆与优先队列 4 题 |
| w11 | [51-rabbitmq-outbox.md](51-rabbitmq-outbox.md) 全 + [32-spring-security.md](32-spring-security.md)（过滤器链/JWT） | RabbitMQ 管理台手工声明拓扑并发/收一条消息；跑 `scripts/mq-drill.sh`；jwt.io 解码自签 token | BFS 与 DFS 5 题 |
| w12 | [20-network.md](20-network.md) 全 + [21-os.md](21-os.md) 全 + [22-tools.md](22-tools.md) 全；[32-spring-security.md](32-spring-security.md)（BCrypt/HMAC/SSRF） | 说出「浏览器敲 URL 到页面」全流程；时序攻击小实验（equals 计时差）；**简历 v1 定稿**（用 [91-interview-project.md](91-interview-project.md) 的诚实边界约束） | 贪心 4 题；**阶段 3 总自测** |

**阶段 3 验收**：白板讲穿透/击穿/雪崩各两套方案与本项目选择；说出 Outbox 解决什么、代价是什么；解释 BCrypt 慢为什么是优点；说清 `Retry-After` 的计算依据。

## 阶段 4：项目深入（w13–15）

| 周 | 新知 | 代码与实操 | 算法 |
|----|------|-----------|------|
| w13 | [81-linkforge-flows.md](81-linkforge-flows.md) 全 + [82-linkforge-decisions.md](82-linkforge-decisions.md)（决策篇） | 白板默画十二站跳转判定门（见 81 号文档）；断点走通创建与跳转两条链 | 动态规划入门 4 题 |
| w14 | [82-linkforge-decisions.md](82-linkforge-decisions.md)（故障篇） + [60-testing-ci.md](60-testing-ci.md) 全 | 破坏实验 8 项全做（每项先预测哪个测试红，再验证）；手算连接预算 | 动态规划进阶 4 题 |
| w15 | [61-docker-deploy.md](61-docker-deploy.md) 全 + [62-design-patterns.md](62-design-patterns.md)（项目实例） | 起观测栈截自己的 Grafana 图；指出项目里三个模式实例并讲取舍 | 回溯与图 4 题；**阶段 4 总自测** |

**阶段 4 验收**：把跳转十二站判定门讲成 10 分钟口头作文（每步防什么/怎么实现/失效场景）；说出 Outbox vs 事务消息 vs CDC 三选一理由；能解释每个破坏实验背后钉住的知识点。

## 阶段 5：面试冲刺（w16–18 + 2 缓冲周，2027-02 下旬起）

| 周 | 新知 | 代码与实操 | 算法 |
|----|------|-----------|------|
| w16 | [90-interview-knowledge.md](90-interview-knowledge.md) 按薄弱域 + [70-distributed.md](70-distributed.md) 全 + [62-design-patterns.md](62-design-patterns.md)（反过度设计） | 每天口述 3 个知识题并录音回听；[91-interview-project.md](91-interview-project.md) 追问树前 3 层自问自答；[94-scenario-drills.md](94-scenario-drills.md) 每天遮答 5 题 | 高频题二刷（错题优先） |
| w17 | [91-interview-project.md](91-interview-project.md) 全 | 简历定稿（每行经得起两层追问，数字只用白名单口径）；5 分钟项目演示排练 | 每日 2 题保持手感 |
| w18 | [93-glossary-review.md](93-glossary-review.md) 全量过 | [95-final-selftest.md](95-final-selftest.md) 全卷验收；2 次完整模拟面试（知识 20min + 项目 15min + 手撕 20min）；复盘录音改表述 | 每日 2 题 |

**阶段 5 验收**：任意知识题能按「结论→原理→边界→项目对照」四段作答；项目追问 3 层内不出现「没做过却声称做过」；手撕题 20 分钟内写出可运行代码并主动说复杂度。

## 里程碑与提前批触发

| 时点 | 必须就绪 |
|------|----------|
| w8 结束（12 月底） | Spring+MySQL 主线闭环，可回答「项目怎么跑起来」 |
| w12 结束（1 月底） | **简历 v1**；中间件三件套能白板讲；提前批开放即可投 |
| w15 结束（2 月中） | 项目深度达标，破坏实验与演练全做过 |
| w16 起（2 月下旬） | 简历 v2 + 模面节奏，应对春招提前批与正式批 |

## 阶段与文档的覆盖映射

每个阶段把哪些文档读到什么深度，按此表执行。重写后的知识文档统一为「问题 → 直觉 → 定义 → 示例 → 反例 → 项目案例 → 自测」结构，周计划一律按「全文」推进，不再引用节号：

| 文档 | 阶段 1 | 阶段 2 | 阶段 3 | 阶段 4 | 阶段 5 |
|------|--------|--------|--------|--------|--------|
| java/ 系列（[总览](10-java-basics.md) + 10-01..10-10） | 全文 | — | — | — | 查漏 |
| 11 集合 | 全文 | — | — | — | 查漏 |
| 12 JVM | 3–9 节 | — | — | — | 查漏 |
| 13 并发 | 全文 | — | — | — | 查漏 |
| 30 Spring 核心 | — | 全文 | — | — | 查漏 |
| 31 Boot/MVC | — | 全文 | — | — | 查漏 |
| 40 MySQL | — | 全文 | — | — | 查漏 |
| 41 MyBatis-Plus | — | 全文 | — | — | 查漏 |
| 50 Redis | — | — | 全文 | — | 查漏 |
| 51 RabbitMQ/Outbox | — | — | 全文 | — | 查漏 |
| 32 Security | — | — | 全文 | — | 查漏 |
| 20/21/22 网络/OS/工具 | — | — | 全文 | — | 查漏 |
| 60 测试/CI | — | — | — | 全文 | 查漏 |
| 61 Docker/部署 | — | — | — | 全文 | — |
| 62 设计模式 | — | — | — | 3–6 节 | 补 7–9 节 |
| 70 分布式 | — | — | — | — | 全文 |
| 80/81/82 项目三件 | w1 通读 80 | 81 第 3 条链 | — | 全文深读 | 白板复述 |
| 90/91/92/93 面试四件 | — | — | 92 每周配套 | 91 前置阅读 | 全文 |

算法线独立于上表：每阶段跟随 [14-algorithms.md](14-algorithms.md) 的题单阶段推进，题目卡片在 [92-interview-coding.md](92-interview-coding.md)。

## 落后了怎么调

- 落后 1 周：用缓冲周吸收，砍当周 P2 内容（62/70 号文档可只读总结节）。
- 落后 2 周以上：算法从每日 2 题降到每日 1 题但不归零；砍 [70-distributed.md](70-distributed.md) 与 [62-design-patterns.md](62-design-patterns.md) 的深入节；P0 文档的自测一项不砍。
- 提前批提前开放：优先保 w12 里程碑（简历 + 中间件白板），阶段 4 的破坏实验可与投递并行补。

## 与其他文档的联动

- 每周验收不过就顺延，不顺延硬闯；验收项的详细标准在 [02-capability-matrix.md](02-capability-matrix.md) 对应行。
- 复习节奏按 [93-glossary-review.md](93-glossary-review.md) 的 D+1 / D+3 / D+7 / D+21 清单执行。
- 岗位要求依据与缺口分析见 [01-job-market.md](01-job-market.md)；优先级 P0–P3 的划分理由在那里，不在本文件。
