# LinkForge 源码学习教材 · 总索引

> 这是一套以 **LinkForge 仓库源码**为主线的体系化课程：从 Java/Spring Boot 初学者出发，最终目标是能独立读懂、解释、修改和扩展这个项目。
> 基线：tag `3.0.0` @ `f27b9cbfec4f`（main tip `3f545ccf`，2026-11-08 冻结），Flyway V1–V15，测试 508 例。
> 历史注：本教材最初按 v2.0.0（`main` @ `911640c`，319 例）编写，2026-11-08 已全量更新到 3.0.0 口径；正文里出现的旧数字都已标注为历史对照。
> 编写/学习进度看 [reference/progress.md](reference/progress.md)。

## 从哪里开始

1. 第一次接触本项目：按顺序读 [01-project-overview.md](01-project-overview.md)（产品全景）→ [02-architecture-map.md](02-architecture-map.md)（代码地图）→ [03-curriculum-roadmap.md](03-curriculum-roadmap.md)（选课）。
2. 已有 Spring 基础：跳过模块 01–03 的基础段，从[模块 05](modules/05-security/) 开始，按路线图跳读。
3. 只想搞懂某条链路：直接进 [advanced/](advanced/) 的 17 个业务专题（原 14 个 + 3.0.0 新增的会话与团队、链接能力增强、正确性波次三个附录专题）。

## 目录结构

```text
docs/source-learning/
├── 00-README.md            本文件（总索引）
├── 01-project-overview.md  项目定位、功能地图、用户故事
├── 02-architecture-map.md  运行架构、包结构、类关系、数据与中间件地图
├── 03-curriculum-roadmap.md 全部 43 节课的路线图
├── modules/                模块 01–15，每课一个 md（12 节统一模板）
│   ├── 01-spring-boot/     Maven/pom、IoC/DI、配置体系
│   ├── 02-common/          统一响应、异常、工具
│   ├── 03-spring-mvc/      Controller、请求生命周期
│   ├── 04-mybatis-mysql/   实体/Mapper、Flyway、事务
│   ├── 05-security/        过滤器链、JWT、UserContext、刷新会话与团队协作
│   ├── 06-short-link/      数据模型、创建流程、生命周期、QR/UTM
│   ├── 07-redirection/     跳转判定链、密码/域名门禁、A/B 分流
│   ├── 08-redis-cache/     键空间、L1/L2、空值缓存/Bloom、singleflight
│   ├── 09-rate-limit/      滑动窗口 Lua、幂等键与配额
│   ├── 10-rabbitmq-outbox/ 拓扑与确认、Outbox/Relay、消费幂等
│   ├── 11-statistics/      点击管道、聚合维度、统计 API 与对账
│   ├── 12-api-key/         API Key 全流程
│   ├── 13-webhook/         密钥加密、投递重试、SSRF 与聚合
│   ├── 14-observability/   Actuator/Micrometer、指标、定时任务地图
│   └── 15-testing-deployment/ 测试金字塔、Maven/CI、Compose 与演练
├── advanced/               17 个综合业务专题（含面试指南与 3.0.0 附录三篇）
├── diagrams/
│   ├── archify/source/     Archify 图源 JSON（保留）
│   ├── archify/html/       独立 HTML 交互图（浏览器打开）+ finalize 回执
│   ├── archify/exports/    SVG 等导出（如生成）
│   ├── archify/manifest.md 图表总表（类型/证据/校验结果）
│   └── mermaid/            核心流程的 Mermaid 备用图
├── reference/              覆盖矩阵、术语表、导航索引、进度、差异登记
└── tools/check_docs.py     教材 QA 校验器
```

## 每节课的固定结构

学习目标 / 前置知识 / 本节涉及的源码 / 类的定位 / 核心源码详解 / 技术原理补充 / 可视化 / 项目中的真实执行过程 / 为什么这样设计 / 本节总结 / 课后练习（带解析）/ 与后续课程的联系。

## 学习原则

- **先跑起来再读代码**：模块 01 结束你就有一套能启动的本地环境；之后每条链路都建议亲手 curl 一遍再对照源码（README「五步跑通」）。
- **练习先答后看**：每课末尾的练习是验收手段。答不上来回到对应小节重读，不要硬闯下一课。
- **调用链优先于单类**：读懂一个类不算懂，能在调用链上指出它的位置才算（每课「类的定位」与「真实执行过程」两节就是为此设计）。
- **怀疑一切二手结论**：教材引用都有 `文件路径:行号`，动手核对是本教材最希望你养成的习惯。
- 卡住超过 30 分钟就记录下来带着问题继续，别硬磕（上一版资料 docs/learning 的经验，同样适用于本教材）。

## 配套图表

每课「可视化」小节给出 Archify 交互图链接（`diagrams/archify/html/*.html`，浏览器打开可缩放、悬停查看节点说明）与 Mermaid 备用图。图表全部要求带源码证据，证据表见 [diagrams/archify/manifest.md](diagrams/archify/manifest.md)。**校验通过只代表图表结构与渲染合格，内容真实性以课程正文引用的源码为准。**

## 质量约定（写作纪律）

- 所有代码片段必须能在仓库中找到对应真实文件；示意性伪代码用 `java ignore-check` 围栏标记。
- 不虚构性能数字。仓库现存的压测数字是 Windows 历史口径（见 [01-project-overview.md](01-project-overview.md) 第 9 节）。
- 语言知识 / 框架行为 / 本项目实现 / 合理推断四类内容分开表述，推断必须标注「推断」。
- 改动只发生在本目录；业务源码零修改。

## 校验与维护

```bash
python -I docs/source-learning/tools/check_docs.py
```

校验器检查：计划文件齐全与行数下限、站内链接可解析、课程 12 节模板齐全、Java 代码块类型真实存在、没有留白待填的内容、图表源/HTML/回执齐全。退出码 0 才算通过。

每次新增或修改课程后运行一次；`reference/progress.md` 记录每次批次的完成状态。任务被打断后，从 progress.md 的迭代日志最后一行恢复。

## 时点说明（2026-11，3.0.0 更新）

- 教材基线：tag `3.0.0` @ `f27b9cbfec4f`（main tip `3f545ccf`）。项目已在外部复审通过 M1–M8 修复波后**功能冻结**：批次 1–3、审计修复波与 S/N 波的全部机制都以 3.0.0 源码为准。本教材最初编写于 v2.0.0（`main` @ `911640c`，2026-11-02），本次更新逐课核对源码后改写。
- 控制台（`static/index.html`，3.0.0 共 1,423 行）已随 S/N 波 S2 收尾定型：强制改密页、邀请接受视图、modal 口令框（`prompt()` 已全量移除）、启动会话恢复、logout-all 按钮、列表 keyword/tag 筛选都在其中；本教材只讲它调用的 API 面，不钉 DOM 细节。
- 性能数字口径见 [01-project-overview.md](01-project-overview.md) 第 9 节：现有数字是 Windows 历史实测，不代表当前版本。
- 测试例数：README 口径 508 = 226 单测（204 `@Test` + 2 个参数化方法展开 22 例）+ 280 IT + 2 时区 fork；源码 `@Test` 注解计数 486（单测 204 + IT 282，其中 2 例在时区 fork 里跑）不含参数化方法，两口径完全可平。详见 [reference/issues-and-discrepancies.md](reference/issues-and-discrepancies.md)。
