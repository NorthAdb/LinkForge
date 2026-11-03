# 4.2 Flyway 与 schema 演进

## 1. 本节学习目标

1. 说清 Flyway 的版本化迁移模型：V 前缀文件、flyway_schema_history、baseline 机制。
2. 逐版本讲出 V1–V10 各自改了什么、对应哪个功能波次。
3. 完整复述 V4 的排序规则选型：为什么是 `utf8mb4_0900_bin` 而不是 `utf8mb4_bin`。
4. 记住快照列纪律：跨表比较必须走 `link_id`，直连 code 会 ERROR 1267。
5. 会读迁移文件里的决策注释，把「这条 DDL 为什么长这样」讲给面试官。

## 2. 前置知识

- [4.1 Entity、Mapper 与 CRUD](01-entities-mappers-crud.md)：13 张表的实体侧，本章讲它们的来路。
- [1.3 配置体系与 Profile](../01-spring-boot/03-configuration-profiles.md)：`spring.flyway:` 配置段的位置。
- MySQL 基础（框架无关常识）：`ALTER TABLE`、唯一索引、字符集（charset）与排序规则（collation）的区别。字符集决定能存什么字符，排序规则决定怎么比较与排序。

## 3. 本节涉及的源码

| 文件 | 内容 | 阅读顺序 | 为什么读它 |
|------|------|----------|------------|
| `src/main/resources/db/migration/V1__baseline_schema.sql`（73 行） | 六张基线表 | 第 1 | 一切的地基 |
| `V2__outbox.sql`（21 行） | outbox 表 + click_log.event_id | 第 2 | 一迁移双动作的样本 |
| `V3__click_dim_daily.sql`（18 行） | 维度聚合表 | 第 3 | 最简单的新表迁移 |
| `V4__short_code_case_sensitive.sql`（26 行） | code 排序规则改 bin | 第 4 | 本课核心论证 |
| `V5__api_key.sql`（18 行） | API Key 表 | 第 5 | 哈希列设计 |
| `V6__webhook.sql`（28 行） | webhook 两表 | 第 6 | 单租户单 endpoint 的唯一索引 |
| `V7__link_access_password.sql`（2 行） | 一列 ALTER | 第 7 | 最小迁移的样子 |
| `V8__api_key_scope.sql`（4 行） | 两列 ALTER | 第 8 | 默认值回填旧行 |
| `V9__webhook_aggregate.sql`（22 行） | 三列 + 聚合缓冲表 | 第 9 | 数值范围进列注释 |
| `V10__link_variant.sql`（34 行） | 变体表 + click_log.variant | 第 10 | 内联 COLLATE 的对照 |
| `application.yml`（:18-24） | flyway 配置 | 第 11 | baseline 开关 |

## 4. 类的定位（机制视角）

Flyway 是「SQL 文件的版本控制器」：把 DDL 按版本号排进 `classpath:db/migration`，启动时对比 `flyway_schema_history` 表逐个补跑。它解决的问题是每支代码都会遇到的：同事 B 拉下代码后，数据库怎么自动长出同事 A 新加的列。本项目 V1–V10 全部是启动时自动执行（application.yml:21 `enabled: true`），没有手工 DDL。表全景见[数据库 ERD](../../diagrams/archify/html/05-database-erd.html)，它逐列录自本章这 10 个文件。

## 5. 核心源码详解

### 5.1 配置与 baseline 机制（application.yml:18-24）

```yaml
  flyway:
    # Versioned migrations for every profile; empty DBs get V1, legacy volumes
    # (tables already present, no history) are baselined at 1 and skip V1.
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
    baseline-version: 1
```

（框架行为）普通情况：空库启动，Flyway 建出 flyway_schema_history 表，依次执行 V1 到 V10，每成功一个记一行。麻烦在「接手存量库」：库里有表但没有历史表，Flyway 不知道从哪开始。`baseline-on-migrate: true` 让它先写一条 version=1 的 BASELINE 记录再继续，于是 V1 被跳过（假设存量库已是 V1 形状），V2 起正常补跑。`baseline-version: 1` 指定基线版本号。

V1 文件自己的头注释（V1__baseline_schema.sql:1-3）与这个机制呼应：

```sql
-- LinkForge V1 baseline: the schema as of v1.0.0 (deepen phase A6).
-- Runs on empty databases only. Existing volumes without flyway_schema_history
-- are baselined at version 1 by the app (baseline-on-migrate) and skip this file.
```

（本项目实现）测试套件为此专门写了一个 IT：FlywayBaselineIT 用旧版 schema.sql 预灌一个「遗留库」，断言基线后恰有一条 BASELINE@1、V1 没有重跑（V1 的 CREATE TABLE 若重跑会直接报表已存在）。生产接老库的路线在代码合并前就被演练过。

顺带认识 flyway_schema_history 本身：Flyway 自维护，无业务外键，ERD 刻意不画它（manifest 有偏离说明）。

### 5.2 V1–V10 逐版本

| 版本 | 文件主题 | DDL 动作 | 功能波次 |
|------|----------|----------|----------|
| V1 | 六张基线表 | CREATE lf_tenant / lf_user / lf_short_link / lf_click_log / lf_click_daily / lf_consumed_event | A6 基线 |
| V2 | outbox | CREATE lf_outbox；ALTER lf_click_log ADD event_id | A7 事务性 outbox |
| V3 | 维度表 | CREATE lf_click_dim_daily | A8 维度聚合 |
| V4 | 大小写敏感 | ALTER lf_short_link MODIFY code COLLATE utf8mb4_0900_bin | A10 审计 A9-S2 |
| V5 | API Key | CREATE lf_api_key | B4 开放能力 |
| V6 | Webhook | CREATE lf_webhook_endpoint / lf_webhook_delivery | B5 webhook |
| V7 | 密码链 | ALTER lf_short_link ADD access_password_hash | B3 密码短链 |
| V8 | scope/role | ALTER lf_api_key ADD scope；ALTER lf_user ADD tenant_role | C1 双 scope |
| V9 | 聚合投递 | ALTER endpoint 三列；CREATE lf_webhook_agg_buffer | C1 聚合模式 |
| V10 | A/B 分流 | CREATE lf_link_variant；ALTER lf_click_log ADD variant | C3 A/B |

挑五个有教学价值的细看。

**V2 的一文件双动作**（V2__outbox.sql:1-21）：先 CREATE outbox 表，再给 lf_click_log 加 event_id 列。列注释写清了 NULL 的双重语义：NULL 是热路径同步行（隐式 "log-{id}"），非 NULL 是消费者回填行（Reconciler 必须跳过）。一列两个世界，读 ClickLogMapper.selectUnconsumed 的 `l.event_id IS NULL` 条件时（4.1 课实例）要回到这条注释。

**V5 的哈希列设计**（V5__api_key.sql:1-5 注释）：只存 SHA-256 hex（CHAR(64)），明文只在创建响应里出现一次。安全上值得学的是注释里的前提声明：「high-entropy random string, so a fast hash is appropriate」——SHA-256 能用于 API Key 是因为 key 本身高熵，不可用于人类密码（密码必须 BCrypt，见 lf_user.password_hash）。

**V6 的单例约束**（V6__webhook.sql:15）：`UNIQUE KEY uk_webhook_tenant (tenant_id)` 把「每租户一个 endpoint」钉进数据库。3.1 课说 PUT upsert 不怕重复创建，底气就是这条索引。

**V8 的默认值回填**（V8__api_key_scope.sql:5-6）：`ADD COLUMN scope VARCHAR(16) NOT NULL DEFAULT 'USER'`。存量行自动填 USER，语义安全（新能力默认收窄）。代码侧还要兼容 pre-V8 的 null（ApiKeyAuthFilter 相关的 BloomAdminController.java:34 注释「pre-V8 rows have a null role, which reads as MEMBER」），说明真实库可能有迁移前的历史行。

**V10 的内联 COLLATE**（V10__link_variant.sql:11）：

```sql
    variant_key VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
```

后注释（:19-22）点破：在 CREATE TABLE 里内联声明，就不用像 V4 那样对既有列做重建迁移。两次需求同源（键值大小写敏感），一处在改表（贵），一处在建表（免费），对照读。

### 5.3 V4 深读：utf8mb4_0900_bin 的选型论证

整个仓库最值得背诵的一段迁移注释（V4__short_code_case_sensitive.sql:1-26）。分四段拆。

**问题**（:1-5）：V1 建表时 code 继承了默认排序规则 `utf8mb4_0900_ai_ci`（不区分大小写）。后果不是「排序难看」而是行为分裂：`/r/abc1234` 在 Bloom 关闭时能从库里查到 `AbC1234` 的行（ai_ci 相等），Bloom 开启时却在 Bloom 处被精确比较拒绝（404）。同一 URL 的存亡取决于一个无关开关。（框架行为）Guava Bloom 的 contains 是字节级精确比较，与 ai_ci 语义错位。

**候选与淘汰**（:7-11）：

```sql
-- Why utf8mb4_0900_bin and NOT utf8mb4_bin: utf8mb4_bin is PAD SPACE
-- ('abc' = 'abc ' compares equal), which would let /r/abc%20 hit code "abc" via
-- the DB while the Bloom gate (exact string) rejects it — the same
-- "behaviour depends on the toggle" inconsistency in a new form. utf8mb4_0900_bin
-- is NO PAD (like the current 0900_ai_ci) and case-sensitive ('abc' = 'ABC' is 0).
```

两个 bin 候选的区别在尾部空格处理。（框架行为）`utf8mb4_bin` 是 PAD SPACE 排序规则：比较前先补齐空格，`'abc'` 与 `'abc '` 相等。若选它，`/r/abc%20`（URL 里的尾部空格）能经库查到 abc，而 Bloom（Java String 精确比较）拒绝——同一个 bug 换了个马甲。`utf8mb4_0900_bin` 是 MySQL 8 的 NO PAD 二进制排序规则：大小写敏感且空格敏感，与 Bloom 的比较语义完全对齐。选型标准不是「哪个更严」而是「与进程内语义一致」。

**安全性与成本**（:12-14）：uk_code 随列重建，注释论证迁移必然成功——存量 ai_ci 索引已经排除了「仅大小写不同」的重复码（建库以来插不进去），收紧到 bin 不可能冒出新冲突。这半句是「迁移前先证明不可失败」的范例。

**不动的部分与纪律**（:16-24）：

```sql
-- Only lf_short_link.code changes. lf_click_log.code / lf_click_daily.code are
-- redundant snapshots written from the DB row (canonical case) and are never
-- compared or joined on, so their collation is irrelevant today — and skipping
-- them avoids rebuilding the ever-growing detail tables.
--
-- RULE: joins across these tables must go through link_id, never through code.
-- Comparing lf_short_link.code against lf_click_log.code directly would raise
-- ERROR 1267 (illegal mix of collations); if a code-based join is ever really
-- needed, migrate the snapshot columns to the same collation in a new migration.
```

lf_click_log.code / lf_click_daily.code 是写入时从权威行复制的快照列，永不参与比较，改了纯属浪费（明细表巨大，MODIFY 会锁表重建）。但「不改」留下一个雷：两张表 collation 不同，直接 `JOIN ... ON a.code = b.code` 报 ERROR 1267（illegal mix of collations）。所以有了 RULE：跨表关联走 link_id。这条规则在 4.1 课的所有查询里都能验证——没有任何一个 join 走 code。

### 5.4 迁移与功能波次的对应

（本项目实现）从迁移注释里的代号能还原开发史：A 系列（A6/A7/A8/A9/A10）是「deepen」阶段的地基与统计管道；B 系列（B3/B4/B5）是 wave2 的用户可见功能（密码链、API Key、webhook）；C 系列（C1/C3）是双 scope 与 A/B 分流。对应关系见 5.2 表的末列。读法：看到一个类里的决策代号（如 ShortLinkService 注释里的 D-C3-6），就能定位到引入它的迁移与波次。V4 注释尾部的「Chosen by 萧北 2026-10-24」还留下决策人与日期——技术选型留痕的好习惯。

## 6. 技术原理补充

**Flyway 的版本号与命名**（框架行为）。`V<version>__<description>.sql`，双下划线分隔；版本号必须递增，跑过的文件内容不能改（校验和会变，启动报错）。要改已发布的 schema 就追加新 V 文件，这是「数据库变更也走 review」的载体。本项目最高 V10，全部符合。

**baseline 与 drop 的取舍**（框架行为）。接存量库除了 baseline 还有「clean + 全量重放」路线，后者要求停机与数据迁移。`baseline-on-migrate` 的适用前提正是 V1 注释声明的：存量库已经与 V1 形状一致。若形状不一致，baseline 会把差异掩盖成「永远无人执行也无人发现」，所以 FlywayBaselineIT 才有价值——用测试锁住「存量库恰好是 V1」这个假设。

**PAD SPACE 是什么**（框架行为）。MySQL 8 里只有 `utf8mb4_0900_*` 家族是 NO PAD，旧的 `_bin`/`_general_ci` 等 uca 8.0 之前的排序规则多为 PAD SPACE：比较与 UNIQUE 索引都忽略尾部空格。它解释了一个经典事故：`'a'` 与 `'a '` 在 PAD 列上唯一索引冲突。选 collation 时「空格算不算字符」与「大小写算不算同字」是两个独立开关，要分别确认。

**ERROR 1267 的触发面**（框架行为）。不同 collation 的列直接比较（WHERE/JOIN/UNION）都会报。解法三选一：改表对齐（本项目拒绝，成本高）、比较时加 `COLLATE` 后缀（散弹枪，容易漏）、换关联键（本项目选的 link_id）。第三种最稳，因为它把「字符串不参与关联」变成 schema 设计约束。

## 7. 可视化

- [数据库 ERD](../../diagrams/archify/html/05-database-erd.html)：每张表卡片标注了引入它的 V 版本；lf_click_log.event_id 到 lf_outbox.event_id 的虚线逻辑引用对应 V2。
- [包结构图](../../diagrams/archify/html/03-package-map.html)：db/migration 不在 Java 包里，但每张表都有且只有一个实体类对它负责。

阅读顺序：先 ERD 通览 13 表，回 5.2 表对号入座版本，再进 V4 原文读论证全文。

## 8. 项目中的真实执行过程

场景一：新开发者第一天，空库启动。

1. application.yml 读到 flyway 配置（:18-24），容器启动时 Flyway 先于 MyBatis 初始化。
2. Flyway 建 flyway_schema_history，依次执行 V1..V10，每个记一行（version、description、checksum、execution_time）。
3. 实体层开始工作：ShortLink 的 @TableName 找得到 lf_short_link（V1 建，V4 改 code 排序规则，V7 加密码列）。
4. 全程无人工步骤，`mvn verify` 里的 Testcontainers 每次都重演这条路径（模块 15）。

场景二：运维接手一个 v1.0.0 时代的存量库。

1. 启动时 Flyway 发现 lf_short_link 等表存在但 flyway_schema_history 不存在。
2. baseline-on-migrate 生效：先写 BASELINE@1 记录，跳过 V1。
3. 从 V2 开始补跑：outbox 表建立，click_log 获得 event_id，依次到 V10。
4. FlywayBaselineIT 断言的正是这个过程（遗留库恰 1 条 BASELINE、V1 不重跑）。

## 9. 为什么这样设计

- 迁移注释写成论证文而不是流水账（V4 是极致）：DDL 的「为什么」比「是什么」更容易失传，注释里连被否决的候选（utf8mb4_bin）都写了淘汰理由。（推断：作者预期读者会在 Bloom 语义对齐这类问题上反复踩坑。）
- 每版本单一主题、文件数与功能波次对齐：回滚定位、cherry-pick、code review 都按功能切。V7 只有 2 行也不合并进 V8——它们是两个功能。
- 快照列不改 collation：明细表是「只增不改」的日志语义，为对齐一个永不参与比较的列去重建亿级行表，收益为零风险巨大；风险用 RULE（join 走 link_id）消化。
- baseline 显式启用而不是「我们永远新库」：代码一旦开源/交付，接手方的第一个问题就是存量库，这个开关加测试是提前买单。

## 10. 本节总结

- Flyway 按版本号补跑 V 文件，flyway_schema_history 记账；baseline-on-migrate 处理「有表无史」的存量库，FlywayBaselineIT 锁住假设。
- V1–V10 对应 A6→C3 的功能波次：六基线表、outbox、维度、大小写敏感、API Key、webhook、密码列、scope/role、聚合、A/B 变体。
- V4 的选型核心是「与进程内比较语义对齐」：ai_ci 大小写不敏感、utf8mb4_bin PAD SPACE、0900_bin NO PAD 全不敏感，Bloom 只认第三种。
- 快照列永不参与比较；跨表关联一律走 link_id，直连 code 报 ERROR 1267。
- 建表能内联 COLLATE 就不留给将来 ALTER（V10 vs V4）。

## 11. 课后练习

**练习 1（概念理解）**：一个 v1.0.0 存量库首次启动，flyway_schema_history 里会有几条记录？第一条的 version 是多少？

答案解析：11 条。1 条 BASELINE（version=1，由 baseline-on-migrate 写入，不执行任何 SQL）加 V2 到 V10 共 9 条补跑记录。V1 被跳过（存量库已具 V1 形状，重跑会 CREATE TABLE 失败）。FlywayBaselineIT 断言「恰 1 条 BASELINE@1、V1 不重跑」。

**练习 2（源码定位）**：`/r/abc%20` 请求码 abc。分别指出 V1 默认 collation、utf8mb4_bin、utf8mb4_0900_bin 三种情形下，数据库比较 `code = 'abc '` 的结果。

答案解析：V1 的 0900_ai_ci 是 NO PAD 但大小写不敏感——'abc ' 与 'abc' 不等（空格敏感），真正的 ai_ci 问题是 'ABC'='abc'。utf8mb4_bin 是 PAD SPACE，'abc '='abc' 为真，会错误命中。0900_bin 是 NO PAD 且大小写敏感，'abc '≠'abc'，拒绝。三种里只有 0900_bin 与 Bloom 的 Java 精确比较完全一致（V4__short_code_case_sensitive.sql:7-11）。

**练习 3（执行过程）**：Bloom 开启时 `/r/AbC1234` 与库中 `abc1234` 是什么关系？关闭时呢？V4 之前各是什么？

答案解析：V4 之后两个开关下行为一致：Bloom 精确比较判定 abc1234 不是 AbC1234（先到先拒），即使关掉 Bloom，0900_bin 的库比较也不等，404。V4 之前（ai_ci）：Bloom 开启时 404（Bloom 拒绝），关闭时库比较 'AbC1234'='abc1234' 为真，命中并 302——这就是注释里「行为取决于开关」的 bug（V4 :1-5）。

**练习 4（调用链）**：写一条会触发 ERROR 1267 的 SQL，并指出本项目靠哪条 RULE 与哪个索引避免它。

答案解析：`SELECT * FROM lf_click_log c JOIN lf_short_link s ON c.code = s.code WHERE s.id = 1`——c.code 继承表默认 collation（V1 建表未改），s.code 是 utf8mb4_0900_bin（V4 改），直接比较报 1267。RULE（V4 :21-24）：跨表 join 一律走 link_id（lf_click_log.link_id 对 lf_short_link.id），明细表的 idx_click_link_time(link_id, clicked_at)（V1 :53）让这条路索引友好。

**练习 5（设计分析）**：V7 只有 2 行 ALTER，为什么不把它并进 V6 或 V8 凑个「webhook/权限大迁移」？

答案解析：V7 属于 B3（密码短链），V6 属于 B5（webhook），V8 属于 C1（双 scope），三个功能不同波次、不同 PR。合并会让「回滚 webhook 功能」变成「回滚密码功能」的依赖，review 与定位成本陡增。Flyway 版本号是功能史的物化，一版本一主题比文件数量重要。

## 12. 与后续课程的联系

本章的 DDL 确定了「什么能保证」（唯一索引、NOT NULL），[4.3 事务](03-transactions.md) 讲「怎么一起保证」：recordClick 同事务双写 lf_click_log 与 lf_outbox（V2 引入的两张表在一个事务里各写一行），以及 InnoDB 语句级回滚如何被三个幂等设计反复利用。密码列（V7）与变体表（V10）的行为面分别在模块 07 与 07.3 展开。
