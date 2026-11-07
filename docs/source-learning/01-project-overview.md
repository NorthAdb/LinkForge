# 01 · 项目全景：LinkForge 到底是什么

> 阅读顺序：本文是整套教材的第 1 篇。读完再看 [02-architecture-map.md](02-architecture-map.md) 建立代码地图，然后进 [03-curriculum-roadmap.md](03-curriculum-roadmap.md) 开始按课学习。
> 本文所有事实均可在仓库内核对：标注 `(README)` 的见根目录 `README.md`，标注文件路径的是真实源码。

## 1. 一句话定位

LinkForge 是一个**多租户短链 + 访问分析后端服务**：用户把一个长 URL 注册成短码（如 `/r/M0OKvbe`），任何人访问短码时服务端 302 跳转到目标 URL，同时把这次点击记录下来，最终聚合成统计报表，并可通过 Webhook 把点击事件推给外部系统。

它同时是一个**为面试准备而生的工程项目**（README：面向 2027 春招实习简历项目），所以整个项目有一个鲜明特征：每个模块都写明「诚实边界」，明确说清自己能做到什么、不做什么。本教材会保持同样的口径：**只讲代码里真实存在的东西，推断一律标注**。

## 2. 它解决什么问题，给谁用

| 问题 | LinkForge 的回答 |
|------|------------------|
| 长链接不好传播、不好统计 | 短码 + 302 跳转 + 点击记录 |
| 多个团队/用户共用一个短链服务，数据要隔离 | 多租户模型：`lf_tenant` + `lf_user`，所有业务行按租户/创建者隔离（跨用户越权统一 404） |
| 营销活动想知道「哪来的点击、什么设备」 | 点击明细 + 按天聚合 + 设备/来源/variant 维度 |
| 外部系统（如 CI 机器人）不能拿着人用的 JWT 调接口 | API Key（哈希存储、配额、双 scope） |
| 点击发生时第三方系统要实时知道 | 点击 Webhook（HMAC 签名、重试、聚合模式） |
| 运维要知道「服务现在健康吗、积压了多少」 | Actuator + Micrometer + Prometheus + Grafana 看板 |

两类使用者：**交互式用户**（浏览器打开 `http://localhost:8080/` 的静态控制台，或直接调 API）和**外部程序**（API Key 调 API、接收 Webhook）。

## 3. 一个完整用户故事（先有体感，再进代码）

1. **注册**：`POST /api/auth/register` 自助创建全新租户和首个用户（首个用户即租户 ADMIN），成功直接返回 JWT。按 IP 限流默认 5 次/10 分钟（README「认证与多租户」）。
2. **建链**：`POST /api/links` 带 `originalUrl`，可选 `customCode`、`expireAt`、`accessPassword`、`variants`、UTM 参数。服务端校验 URL、查保留字、做域名门禁 BLOCK 检查，随机生成 7 位短码（`AbC123` 与 `abc123` 是两个码，V4 迁移把列改成大小写敏感排序），撞码靠 `uk_code` 唯一索引兜底自动重试。
3. **分享**：把 `http://localhost:8080/r/{code}` 发出去；也可以 `GET /api/links/{code}/qr.png` 出二维码物料。
4. **访问**：访客 GET `/r/{code}`，服务端走一条 12 步判定链（限流 → Bloom → 空值缓存 → L1/L2 → 行读 → 状态/过期 → URL 校验 → B9 BLOCK → 密码门 → B9 WARN → bot 分类 → variants 抽签）后 302。
5. **记录**：302 的同一个数据库事务里写点击明细 `lf_click_log` 和 Outbox 行 `lf_outbox`（PENDING）。
6. **投递**：`ClickOutboxRelay` 每 2 秒把 Outbox 批量投到 RabbitMQ，消费端用 `lf_consumed_event` 唯一索引做幂等，聚合成 `lf_click_daily`（按天）和 `lf_click_dim_daily`（设备/来源/variant 维度），并为配置了 Webhook 的租户落一条投递意图。
7. **查看**：链主调 `GET /api/links/{code}/stats`（总量 + 精确 UV + 按天序列）、`.../stats/breakdown?dim=device`、`.../clicks`（明细分页）。
8. **推送**：`WebhookDeliveryRelay` 轮询投递行，HMAC 签名后 HTTP POST 到租户配置的 endpoint；失败按指数退避重试，上限 8 次。

这套故事里出现的每一个类（`ShortLinkService`、`ClickRecordService`、`ClickOutboxRelay`、`ClickEventConsumer`、`WebhookDeliveryRelay`……）都会在后面的课程里逐个拆开。

## 4. 功能地图（业务功能 → 入口 → 核心类）

### 4.1 业务功能

| 域 | 能力 | HTTP 入口 | 核心类（`src/main/java/com/linkforge/` 下） |
|----|------|-----------|---------------------------------------------|
| 认证 | 注册/登录、JWT、bootstrap 演示租户 | `/api/auth/register` `/api/auth/login` | `user.service.AuthService`、`user.controller.AuthController`、`security.JwtService`、`bootstrap.BootstrapDataLoader` |
| 短链管理 | CRUD、自定义码、过期/禁用、幂等创建、CSV 导入导出、A/B 变体 | `/api/links/**` | `link.controller.ShortLinkController`、`link.service.ShortLinkService`、`link.support.CsvImportParser`、`link.support.VariantPicker` |
| 跳转 | 302、HEAD 探测、密码解锁 | `/r/{code}`、`/r/{code}/unlock` | `link.controller.RedirectController`、`link.support.GatePages`、`link.support.LinkAccessTokens` |
| 缓存/防护 | L1/L2 缓存、空值缓存、Bloom、singleflight、广播失效 | （内部） | `link.support.UrlLocalCache`、`link.support.CodeBloomFilter`、`link.support.SingleFlight`、`link.support.CacheBus`、`link.support.ShortLinkRowLoader` |
| 限流 | 滑动窗口日志（Lua）+ 日固定配额 | （横切） | `common.ratelimit.RedisRateLimiter`、`common.ratelimit.RateLimitKeys` |
| 统计 | 明细/UV/按天/维度 | `/api/links/{code}/stats**` | `stats.controller.StatsController`、`stats.service.ClickStatsService`、`stats.service.ClickRecordService` |
| 异步管道 | Outbox、Relay、MQ、幂等消费、对账 | （内部） | `stats.service.ClickOutboxRelay`、`stats.mq.ClickEventConsumer`、`stats.mq.RabbitClickEventPublisher`、`stats.service.ClickLogReconciler` |
| 开放能力 | API Key + 配额 | `/api/keys/**` + `X-API-Key` | `apikey.service.ApiKeyService`、`security.ApiKeyAuthFilter` |
| Webhook | 端点管理、签名投递、聚合 digest | `/api/webhooks/**` | `webhook.service.WebhookService`、`webhook.service.WebhookDeliveryRelay`、`webhook.support.WebhookSigner`、`webhook.support.PinnedHttpSender` |
| 可观测 | Prometheus/Grafana、积压 gauge | `/actuator/**` | `observability.BacklogGauges`、`observability.HttpUriTagAllowList`、`security.MetricsScrapeTokenFilter` |

### 4.2 安全门禁类（跳转链上的横切判定）

| 机制 | 类 | 一句话 |
|------|----|--------|
| B2 非人点击 | `link.support.ClickFilter` | 预取头/具名预览 bot 照常 302 但不计点击 |
| B9 域名门禁 | `link.support.TargetPolicy` / `link.support.PhishingPolicy` | 本地域名名单 BLOCK/WARN，label 边界后缀匹配 |
| B3 密码短链 | `link.support.LinkAccessTokens` | BCrypt 哈希存储 + HMAC 签名 cookie |
| 页面渲染 | `link.support.GatePages` | 解锁页/拦截页/警示页，全部 `HtmlUtils.htmlEscape` 转义 |
| SSRF 守卫 | `webhook.support.WebhookTargetGuard` + `webhook.support.PinnedHttpSender` | 一次解析全 IP 校验后只向已校验地址建连 |

### 4.3 运维/支撑功能

- **Flyway 迁移**（V1–V10，`src/main/resources/db/migration/`）：schema 版本化，存量库 baseline。
- **保留清理**：`retention.SentRowCleanup` 定时删除过期的 SENT 行（outbox 用 DB 时钟、webhook 用 JVM 时钟，两个时钟口径不同，这是真实源码里的细节）。
- **CI**：GitHub Actions `mvn -B verify`（单测 surefire + Testcontainers IT failsafe）。
- **故障演练脚本**：`scripts/smoke.sh`、`mq-drill.sh`、`outbox-drill.sh`、`fault-drill.sh`。

## 5. 系统内部的关键支撑能力（后面每一条都是一门课）

1. **五层读取防护**（跳转热路径）：限流 → Bloom 防穿透 → 空值缓存 → Caffeine L1 → Redis L2，再叠加 singleflight 合并并发行读。诚实口径：每请求仍读一次 DB 行判禁用/过期，缓存只省 URL 字段（README「跳转读取路径」）。
2. **同事务 Outbox**：点击写入与消息意图在同一个 DB 事务，Relay 异步投递，断 MQ 期间点击停在 outbox、恢复自动追平（`scripts/outbox-drill.sh` 演练）。
3. **幂等三件套**：创建幂等（Redis `pending→code` 两段式）、消费幂等（`lf_consumed_event` 唯一索引 + 捕获 `DuplicateKeyException`）、投递幂等（Webhook 按 event id 去重）。
4. **fail-open 与可观测的降级**：限流器、缓存、Bloom 广播、点击记账在依赖故障时都选择放行/跳过，但全部打指标（`linkforge.ratelimit.failopen`、`linkforge.cache.failopen`、`linkforge.click.record.failopen` 等），「降级」从不静默。
5. **滑动窗口限流**：Redis ZSET + Lua（`src/main/resources/redis/sliding_window.lua`），时钟取 Redis TIME，只有被放行的请求占名额，精确计算 Retry-After。
6. **多实例协调**：没有分布式 L1，用 Redis pub/sub 广播 Bloom put 与 L1 失效（`CacheBus`），定时 rebuild + 短 TTL 兜底。

## 6. 技术栈（pom.xml:20-191 为准）

| 层 | 选型 | 版本 |
|----|------|------|
| 语言/框架 | Java、Spring Boot（web/validation/security/data-redis/amqp/actuator） | 17 / 3.3.5 |
| ORM | MyBatis-Plus（+ jsqlparser 分页） | 3.5.9 |
| 数据库 | MySQL（mysql-connector-j） | 8.0 |
| 缓存 | spring-data-redis（Lettuce）+ Caffeine | BOM 管 |
| 消息 | spring-boot-starter-amqp（RabbitMQ 3.13） | BOM 管 |
| 认证 | jjwt（api/impl/jackson）、Spring Security | 0.12.6 / BOM |
| 迁移 | Flyway core + mysql | 10.10.0 |
| 特色依赖 | Guava（Bloom）、ZXing（QR）、httpclient5（钉地址投递）、springdoc、micrometer-prometheus | 33.4.8 / 3.5.3 / BOM / 2.6.0 |
| 测试 | JUnit 5、Testcontainers（mysql/rabbitmq）、spring-security-test | 2.0.5 |
| 构建 | Maven、spring-boot-maven-plugin（build-info）、failsafe 双执行 | 3.9+ |

## 7. 明确不做（冻结决策，写进 README/backlog）

- AI / PostgreSQL / Supabase / 向量库：整体延期另立方案。
- Spring Cloud / Seata / 多机房：不引入。
- 独立前端 SPA：只有单文件静态控制台（`src/main/resources/static/`）。
- 云公网部署：本地 Docker Compose（prod profile 备好了收紧开关但不上云）。
- Geo 维度、RedisBloom、端口白名单/出口代理、Linux bench 复跑（B10）：登记在 `docs/backlog.md`，无运营者指令不开工。
- **功能冻结**：C5 审计通过后项目冻结，不再开产品能力轮（backlog 2026-11-01 备注）。

## 8. 版本演进（从哪里来，现在在哪）

| 轮次 | 日期 | 一句话 |
|------|------|--------|
| v1.0.0（P0–P5） | 2026-10-24 | 认证、短链 CRUD、缓存跳转、MQ 异步聚合、限流、CI 全链路 |
| v2.0.0（A2–A10 + B 档 1–5 波） | 2026-10-25 | HEAD 探测、Flyway、Outbox、维度、Bloom、singleflight、空值缓存、L1、API Key、Webhook、滑动窗口、密码短链、域名门禁、Prometheus |
| C1–C5 | 2026-10-31 ~ 11-01 | QR/UTM、API Key 双 scope、Webhook 聚合、Bloom 广播/rebuild、L1 失效广播、A/B 分流、CSV 导入、SSRF rebinding 钉死、点击记账 fail-open |
| 现在 | 功能冻结 | main @ 911640c，319 例测试 |

## 9. 关键数字速查（引用时带口径）

| 数字 | 值 | 口径 |
|------|-----|------|
| 主源码文件 | 120 个 Java 文件 | 2026-11-02 `find` 实测 |
| 测试 | README 口径 319 = 151 单测（144 `@Test` + 1 个参数化方法展开 7 例）+ 166 IT + 2 时区 fork | 按执行实例计数；`@Test` 注解合计 312 不含参数化方法，两口径完全可平 |
| 数据表 | 13 张业务表 + flyway_schema_history | V1–V10 迁移实测 |
| Redis 键族 | 正缓存 / 空值缓存 / 限流滑窗 / 幂等键 + 2 个 pub/sub 频道 | application.yml 与 CacheBus 实测 |
| 默认限流 | 注册 5/600s·IP、创建 30/60s·用户、导入 5/60s·用户、跳转 300/60s·IP、API Key 120/分钟+10000/日 | application.yml 默认值（env 可覆盖） |
| 性能 | 现存数字是 Windows 历史实测（2026-10-24，固定窗口时期），不代表当前 tip，Linux bench 未跑 | README 页头声明 |

## 10. 性能数字的正确姿势

仓库里的压测数字（health 888 QPS、13→276 QPS 等）是 **Windows 历史实测**（2026-10-24，固定窗口限流时期），不代表当前版本，也不是 Linux 性能；B10 Linux bench 未跑。本教材引用任何数字都会带着这个口径。教学上会讲**为什么**慢（同步明细写、每请求读行）和这些取舍换来了什么，而不是背数字。

## 10. 初学者常见问题速答

| 问题 | 简答 | 去哪学 |
|------|------|--------|
| 「多租户」到底是什么？ | 一套部署服务多个互不可见的客户（租户）。LinkForge 里一个租户 = 一家团队：`lf_tenant` 一行，注册即建租户，用户挂在租户下（`lf_user.tenant_id`），所有业务查询都带租户/创建者过滤，跨租户访问一律 404 | 模块 5.3 |
| 为什么用 302 而不是 301？ | 301 是永久重定向，浏览器会缓存，短链一旦禁用/过期/改目标，已被缓存的浏览器不再询问服务器；302 每次都问服务端，控制权留在服务端 | 模块 7.1 |
| 点击统计为什么不实时写聚合表？ | 热路径（跳转）必须快，聚合走异步管道（Outbox→MQ→消费）；最终一致性换取 302 不被统计拖慢 | 模块 10/11 |
| 「幂等」在这里指什么？ | 同一个操作做多次与做一次效果相同：创建重放同键同码、同一事件重放只计一次、Webhook 重发接收方可去重 | 模块 9.2/10.3/13.2 |
| 什么是「诚实边界」？ | 项目每个功能都写明「不做什么」（如 B9 不是防钓鱼系统、密码链只保护入口）。这是面试时防吹牛的口径，也是教材反复强调的读码纪律 | README 各模块、模块对应课程 |
| 为什么每个请求都要读一次数据库行？ | 用强一致换性能：禁用/过期判定永远以 DB 行为准，缓存只加速 URL 字段读取。「缓存只许加速、不许改判定」 | 模块 8.2/7.1 |

## 11. 为什么这个项目适合当教材载体

- **规模合适**：主源码 120 个文件、单模块最大 1025 行（ShortLinkService），一周内可以通读，又不至于简单到没有工程问题。
- **问题真实**：缓存一致性、并发撞码、消息可靠性、限流、幂等、SSRF，都是后端面试与生产里的高频真题，且项目里都有「为什么这么做」的注释与决策记录可查（progress.md 的决策表）。
- **有红绿史**：319 例测试、CI 恒绿的纪律、「先提交测试再提交实现」的红先流程，让每条结论都能被复核——教材里引用行为时都能给出测试名。
- **诚实文化**：文档与注释不掩饰缺口（fail-open 的残余不一致、Bloom 不可删除等都有原文注释），适合练习「读代码不相信注释、用测试验证行为」。

## 12. 下一步

- 想看「代码怎么组织、谁连着谁」→ [02-architecture-map.md](02-architecture-map.md)
- 想直接开课 → [03-curriculum-roadmap.md](03-curriculum-roadmap.md)
- 随手查术语 → [reference/technical-glossary.md](reference/technical-glossary.md)（编写中，随课程更新）
