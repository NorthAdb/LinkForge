# 图表覆盖矩阵（diagram coverage matrix）

> 数据源：[diagrams/archify/manifest.md](../diagrams/archify/manifest.md)（证据摘要与偏差备案）。本表回答三个问题：每张图有没有教学作用、关键关系有没有源码依据、HTML/JSON/回执是否齐备。
> 校验口径：全部 17 张图通过 Archify `finalize` 四道门（validate / deliver / check / browser-check）；回执 `*.finalize-summary.json` 与 HTML 同目录且 `ok=true`（tools/check_docs.py 强制）。**结构校验通过不代表内容被源码证实**——内容证据见下表「证据主源」列。

## 覆盖总表

| slug | 类型 | 关联课程 | 源码证据主源 | 校验 | 结构性偏差 |
|------|------|----------|--------------|------|------------|
| 00-system-architecture | architecture | 01/02、多课 | README 架构节、application.yml、6 个源码类行号 | ✅ showcase（3 轮） | 无 |
| 01-runtime-deployment | architecture | 01、15.3 | docker-compose.yml:1-101、application.yml、SecurityConfig | ✅ showcase（首跑） | 无 |
| 02-function-map | tree | 01/02 | README 功能总览:34-44 + 控制器 @Mapping 实测 | ✅ showcase（2 轮） | 限流并入跳转域、安全拆两域（manifest） |
| 03-package-map | tree | 02/全课程 | find 全量 120 文件全等 diff + 每类声明行号 | ✅ showcase（首跑） | 无 |
| 04-core-class-relations | class | 02/06/07/10 | ShortLinkService:100-116、RedirectOutcome 全文、Publisher 接口/实现 | ✅ showcase（3 轮） | 6 个次要类入卡片；relay↔record 无箭头（经 lf_outbox 解耦） |
| 05-database-erd | erd | 4.2/11 | V1–V10 SQL 逐列（121 列） | ✅ standard（2 轮） | 7 域 4 列布局（浏览器可读性门）；flyway_schema_history 不画 |
| 06-redis-data-flow | dataflow | 8.1-8.3 | UrlLocalCache/CacheBus/ShortLinkService 缓存段 | ✅ showcase（4 轮） | 3 条跨行边入卡片 |
| 07-rabbitmq-topology | architecture | 10.1 | RabbitMqConfig.java:17-62 逐 bean | ✅ showcase（首跑） | binding 以连线标签表达 |
| 08-scheduled-tasks | architecture | 14.3 | 8 个 @Scheduled 类 + yml 节奏配置 | ✅ showcase（3 轮） | advisory 交叉保留（manifest） |
| 09-test-ci-pipeline | workflow | 15.1/15.2 | ci.yml 全文、pom.xml:219-249、AbstractIntegrationTest | ✅ showcase（首跑） | 例数出处为 .audit 盘点（图内注明） |
| 10-auth-sequence | sequence | 5.1-5.3、advanced/01 | SecurityConfig/JwtAuthFilter/AuthService/ApiKeyAuthFilter | ✅ showcase（5 轮） | ApiKeyAuthFilter 降级卡片（宽度门禁互斥） |
| 11-shortlink-create | workflow | 6.2、advanced/02 | ShortLinkService create/doCreate :129-283 | ✅ standard（~15 轮） | 400 扇形边收敛；幂等分区未重设计 |
| 12-redirect-decision | workflow | 7.1、advanced/04 | resolveTarget :601-712、render :85-117 | ✅ showcase（首跑） | 404 按源拆两终端 |
| 13-cache-hit-miss-sequence | sequence | 7.1/8.2-8.4 | resolveTarget 分层 + UrlLocalCache/SingleFlight | ✅ showcase（4 轮） | HEAD/unlock/B2/抽签入卡片 |
| 14-click-pipeline-dataflow | dataflow | 10/11、advanced/07-09 | stats+webhook 全链（recordClick→relay→consume→投递） | ✅ showcase（4 轮） | consumer 回填边入卡片 |
| 15-outbox-lifecycle | lifecycle | 10.2 | ClickOutboxRelay/OutboxMapper/Outbox 状态常量 | ✅ showcase（2 轮） | advisory 自环 stretch 保留 |
| 16-webhook-delivery-lifecycle | lifecycle | 13.2、advanced/11 | WebhookDeliveryRelay/Mapper/Entity + SentRowCleanup | ✅ showcase（7 轮） | 4 类 FAILED 出口合并单边（卡片承载） |

## 覆盖检查结论

- **每张图的教学作用**：17 张图各绑定至少 1 门课程（见「关联课程」列）；课程端「可视化」小节全部给出阅读顺序与节点→类对应。零「为画而画」的图。
- **关键关系的源码依据**：全部图的证据主源为源码文件或逐文件迁移 SQL；三张图（02/09/16）的少量汇总性数字/分组标注了出处（README 功能表、.audit 测试盘点、模块 13 课程），均非虚构。
- **不存在不存在的类/方法**：04/03 两图经全等 diff 核对；各图代理简报均含「不发明类名/端口/组件」纪律且回报零发明。
- **同步/异步不混淆**：00（边标签区分）、08（任务地图单列）、14（同步/异步边界入卡片）、10（三段时序）逐图落实。
- **遗漏的关键分支**：无已知的「图外关键分支」——12 步判定、Outbox 状态机、Webhook 状态机、缓存命中/未命中/故障三线、认证三来源均有专属图或图内分支；局部细节按「语义入卡片」原则降维且在 manifest 逐条备案。
- **HTML 可打开性**：browser-check 门（真实浏览器 1440×900）17/17 通过。
- **JSON 源文件保留**：source/*.json 17/17 在库。
- **Obsidian 链接**：课程→图表的相对链接由 tools/check_docs.py 全量校验（该脚本最终态全绿即含此校验）。

## 与 Mermaid 备用图的关系

`diagrams/mermaid/` 下 3 张备用图（redirect-decision-chain / click-pipeline / create-flow）覆盖三条最核心链路，供 Obsidian 原生渲染与无浏览器环境使用；与对应 Archify 图（12/14/11）同源同口径，课程两处都链接。

## 分类型的校验要点回顾

- **architecture（5 张）**：组件均对应真实类/服务/容器；部署图坚持「应用在宿主机、依赖在容器」的本项目事实；同步/异步边在标签显式区分。
- **tree（2 张）**：包地图经 120 文件全等 diff（无缺无多无重复）；功能地图叶子全部可溯源到 README 功能表或控制器注解。
- **class（1 张）**：实现/组合/依赖三种关系按 schema 区分；省略方入卡片；「经数据库行解耦的类之间不画箭头」。
- **erd（1 张）**：121 列逐列录入、21 条逻辑外键、无显式 FK 的 schema 用 references 表达；主键/唯一索引角色齐全。
- **sequence（2 张）**：参与者与消息可直接对应到方法；401 三来源、finally 双清等分支都在消息线上。
- **workflow（2 张）**：判定链每步失败终态独立可达；幂等四分支齐备。
- **dataflow（2 张）**：TTL/键名/表名与 8/10/11 章正文一致；at-least-once 与双时钟口径入卡片。
- **lifecycle（2 张）**：状态、守卫条件、退避公式与 10.2/13.2 逐字对齐；两个状态机的守卫差异（Outbox 无状态守卫 vs Webhook 全带）都画进了卡片。

## 已知限制（读图前必看）

1. **均为自动化门禁通过，未经人工感知审查**：颜色审美、观感交叉（advisory 级）未做主观评审；如需发版级视觉，可对单图跑 `visual-check` 流程补齐。
2. **三处结构性偏差**（11 图 standard 档、16 图 4 类出口合并、10 图参与者降卡片）与两处 advisory 交叉（08/15/16 自环 stretch）已在 manifest 逐条备案，语义无损失。
3. **汇总性数字的出处**：09 图的 151/166/2 与 02 图的功能行数来自 README 与 .audit 盘点，属文档口径；151 与静态推演 150 的 1 例差见 issues §2.1。
4. **快照时效**：全部图冻结在 01f0b5a 口径；仓库后续演化（如 FE 波落地）不影响后端口径，但包地图若在新增类后需重新生成（find 全等 diff 会立即暴露差异）。

> 维护约定：新增图表时同步更新本表、manifest.md 与 tools/check_docs.py 的 DIAGRAMS 清单；三者不一致时以校验器失败形式暴露。
