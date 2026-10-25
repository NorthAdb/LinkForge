# LinkForge 开发进度与验收记录

> 执行者：GLM（zcode + pstack poteto-mode），按《LinkForge 实施与验收方案》顺序推进。
> 本文件是审计核对的主要证据：每阶段验收勾选、真实执行的命令与输出摘要、决策记录。
> **当前 main tip**：v2.0.0 发布后 docs 提交（本提交＝`resume.md` 刷新至 v2.0.0 口径，不改发布物）。发布链：合并 `5e776fd`（审计 4 小项 + README 重写 + backlog + pom 2.0.0，**211 例全绿**；分支 run 40172677345、合并 run 40172956164）→ 钉子 `623d1e1`（run 40173268350）→ 复审修正 `279c32e`（scripts jar 版本无关解析，run 40174344584）→ 收口 `49fe6af`（run 40174659600）。**发布标识＝tag `v2.0.0`（打在 `49fe6af`；首打 `623d1e1` 因 scripts 修复作废重切，见章末「复审与 tag 重切」）**；审计以 tag 为准，tag 之后仅允许不影响发布物的 docs 提交，`49fe6af`、`279c32e`、`5e776fd`、`bdda9a1`、`b700992015508632331fe676996991fc180d2cfc` 必为其祖先。
> **历史快照说明**：下文 P0–P5 / R2–R4 各节及其引用的 SHA 是**当时的历史快照，非当前 tip**；当前轮次证据以「深化」小节与文末发布轮章为准。
> 本轮边界（v2.0.0 发布轮）：P0–P5、v1.0.0、深化 A2–A10、B 档波 1–5（含修复轮）均已交付并审计通过；本轮只做审计 4 小项 + 文档同步 + README 重写 + 发版。**B10（Linux bench 复跑）与 QR+UTM 继续挂起**，集中登记于 `docs/backlog.md`；开新轮需运营者指令。

## 状态总览

| 阶段 | 主题 | 分支 | 状态 |
|------|------|------|------|
| P0 | 落地卫生 | feat/p0-hygiene | ✅ 完成（见下） |
| P1 | 短链闭环 | feat/p1-links | ✅ 完成（见下） |
| P2 | 异步分析 | feat/p2-async-stats | ✅ 完成（见下） |
| P3 | 可靠性 | feat/p3-reliability | ✅ 完成（见下） |
| 自审 | R1 缺陷修复 | fix/self-review-p0-p3 | ✅ 完成（见下） |
| 追加 | R2 生命周期+自定义短码 | feat/rev2-lifecycle | ✅ 完成（见「追加迭代」） |
| 追加 | R3 点击明细/UV/CSV | feat/rev3-analytics | ✅ 完成（见「追加迭代」） |
| 追加 | R4 可观测/OpenAPI/控制台 | feat/rev4-polish | ✅ 完成（见「追加迭代」） |
| 深化 | A2 保留字 / A3 HEAD 探测 / B12 OpenAPI·文档卫生 | feat/deepen-a2-a3-b12 | ✅ 完成（见「深化 A2/A3/B12」） |
| 深化 | A6 Flyway 版本化迁移 | feat/deepen-a6-flyway | ✅ 完成（见「深化 A6 Flyway」） |
| 深化 | A7 Transactional Outbox | feat/deepen-a7-outbox | ✅ 完成（见「深化 A7 Transactional Outbox」） |
| 深化 | A8 维度预聚合看板 | feat/deepen-a8-dimensions | ✅ 完成（见「深化 A8 维度预聚合」） |
| 深化 | A9 Bloom 防非法短码穿透 | feat/deepen-a9-bloom | ✅ 完成（见「深化 A9 Bloom 防非法短码穿透」） |
| 深化 | A10 singleflight 防热点击穿 | feat/deepen-a10-singleflight | ✅ 完成（见「深化 A10 singleflight 防热点击穿」） |
| 深化 | B 档波 1：B7 空值缓存+抖动 / B8 L1 | feat/deepen-b-wave1-b7b8 | ✅ 完成（见「B 档波 1：B7+B8」） |
| 深化 | B 档波 2：B4 API Key / B5 Webhook | feat/deepen-b-wave2-b4b5 | ✅ 完成（见「B 档波 2：B4+B5」） |
| 深化 | B 波 2 修复轮（M1/S1/S2 + nit） | fix/bw2-audit | ✅ 完成（见「B 波 2 修复轮」；补录索引，终态 `3fe1fb1`） |
| 深化 | B 档波 3+4+5：B6/B2/B9/B3/B1 | feat/deepen-b-wave345 | ✅ 完成（见「B 档波 3+4+5 合并轮」；补录索引，合并 `b700992`） |
| 发布 | v2.0.0（审计 4 小项 + 文档重写 + 发版） | feat/v2-release | ✅ 完成（见文末「v2.0.0 发布轮」；tag `v2.0.0`） |
| P4 | 工程化（CI/bench/隔离/收紧） | feat/p4-engineering | ✅ 完成（见「P4 工程化」） |

## 环境与工具事实（审计人须知）

- 开发机：Windows + Git Bash + Docker Desktop（client/server 29.9.2）。
- 本机无本地 mvn、本机 JDK 为 21。为满足「Java 17」冻结约束，**编译与测试一律在 Docker 镜像 `maven:3.9-eclipse-temurin-17` 内执行**，命令见各阶段记录。
- 运行应用：用本机 JDK 21 直接跑 target jar（Java 17 字节码向前兼容）；依赖（MySQL/Redis/RabbitMQ）全部来自 docker compose。
- Git：gh 登录态 NorthAdb，阶段分支开发 → 验收绿 → 本地合回 main → push。不开 PR。

## 决策日志

| # | 阶段 | 决策 | 理由 |
|---|------|------|------|
| 1 | P0 | 构建走 Docker maven:3.9-eclipse-temurin-17，缓存卷 linkforge_m2 | 本机无 mvn 且 JDK 为 21；镜像保证 JDK17 工具链可复现 |
| 2 | P0 | 新增 scripts/smoke.sh（curl+grep，无 python/jq 依赖） | README 原冒烟依赖 python3，Windows Git Bash 不保证有；冒烟须可重复、可审计 |
| 3 | P0 | 两份方案 md 只留本地（.git/info/exclude），不进仓库 | 它们是互动 Page 的离线副本，仓库只收验收与交付材料 |
| 4 | P1 | poteto-agent 子代理委派两次被服务端拦截（unusual activity），P1 改为总控内联实现 | 子代理通道当时不可用；不阻塞主线 |
| 5 | P1 | 引入 mybatis-plus-jsqlparser（官方模块）+ PaginationInnerInterceptor | 分页 list 必需；MybatisPlusConfig 原注释已预留此路径，不算换栈 |
| 6 | P1 | 注册 = 自助创建全新租户+首个用户，租户名唯一索引兜底 409（不用先查再插） | 免竞态；隔离叙事清晰（每个注册者一个租户） |
| 7 | P1 | 测试用 TestRestTemplate 默认工厂不支持 PATCH，基类切 JdkClientHttpRequestFactory（JDK HttpClient） | 不加依赖，保留 PATCH 语义；服务端本身接受 PATCH |
| 8 | P1 | resolveAndRecord 简化为「总是读 DB 行做状态/过期判定，缓存只省 URL 字段」 | 原实现缓存命中路径重复查询且检查分裂；行为等价、可读性更好。注意：跳转路径的缓存目前并不省掉 DB 读，P3 做缓存失效加固时一并评估 |
| 9 | P1 | RedisRateLimiter 在 Redis 异常时放行（degrade open） | 注册是公开入口，限流器故障不应把注册打死；P3 复审该策略 |
| 10 | P2 | eventId 确定性设计：`log-{click_log.id}`，实时消费与对账重放共用 | 同一点击天然幂等互斥；MQ 宕机丢失的发布由对账器补齐，聚合必追平 |
| 11 | P2 | 幂等键用「独立事务 INSERT + 捕获 DuplicateKeyException」，弃用 `ON DUPLICATE KEY UPDATE` 探测 | 实测 MySQL Connector/J 默认返回 matched rows，重复键探测永远当新事件（ClickIdempotencyTest 抓到） |
| 12 | P2 | Jackson2JsonMessageConverter 注入 Spring ObjectMapper | 转换器自建 ObjectMapper 无 JavaTimeModule，含 LocalDateTime 的事件全进死信（MqEndToEndTest 抓到） |
| 13 | P2 | @MapperScan 从 "com.linkforge" 收窄到三个 mapper 包 | 广扫描把 ClickEventPublisher 接口代理成 MyBatis mapper，publish() 变 Invalid bound statement；stub 时代 async 恒关所以从未触发 |
| 14 | P2 | 消费错误不回队，配 DLX/DLQ（linkforge.click.dlq） | 毒消息热循环无意义；死信留现场可查 |
| 15 | P3 | 创建幂等用 Redis `SETNX pending→code` 两段式（5s pending 超时 + 24h 终值） | 并发同键只建一条；胜者崩溃 5 秒后自愈 |
| 16 | P3 | 短码分配改为「随机 + uk_code 唯一索引兜底 + DuplicateKey 重试」，删掉先查后插 | 唯一索引才是并发正确性来源，先查后插只是微优化且有 TOCTOU |
| 17 | P3 | 缓存失效升级为「写穿 + 1s 延迟双删」；P1 的删除式失效测试同步改为写穿断言 | 清除并发旧值回填；最终一致窗口 ~1s 明确可测 |
| 18 | P3 | management 配置从 dev-profile 文档移到基础文档 | 原先 test profile 下整段失效，health 组件细节不显示（HealthEndpointTest 抓到） |
| 19 | P3 | RegisterRateLimitTest 窗口 2s→10s + 每次运行前清计数键 | 2s 窗口在负载下跨窗口翻转造成偶发 200；测试须确定性可重跑 |
| 20 | 自审 | ClientIps 默认不信任 X-Forwarded-For，新增 `LINKFORGE_TRUST_XFF`（默认 false） | 该头可伪造，直连场景可无限绕开三个按 IP 限流；仅可信反代后开启。新增 RateLimitTest 伪造头用例与 XffTrustTest 独立窗口用例 |
| 21 | 自审 | RedisRateLimiter 的 EXPIRE 纳入降级保护，失败时尽力删键 | 原实现 INCR 有保护而 EXPIRE 裸奔：Redis 抖动会 500，且「INCR 成功 EXPIRE 失败」留下永不过期的键造成永久 429 |
| 22 | 自审 | consume() 的去重插入与聚合计数并入同一短事务 | 原两事务间崩溃窗口会少计且对账器永久跳过该行；MySQL 语句级回滚语义下合并安全 |
| 23 | 自审 | 合成统计测试自清理（@AfterEach 删 daily/consumed 行）；历史污染一次性 TRUNCATE 后由对账器从明细重建 | 自审实测对账等式 87≠118（合成事件无对应明细），审计命令会当场翻车；重建后 104=104=104 实证闭合 |

---

## P0 落地卫生

范围：不写新业务。补文档、跑通 Compose 冒烟、确认本机可演示。

### 退出条件勾选

- [x] (a) README + `.env.example` 齐全（JWT_SECRET、bootstrap 口令、DB/Redis/MQ 连接），仓库无明文默认密码
  - `git grep -inE '(password|secret)\s*[:=]\s*"..."' -- ':!*.md' ':!*.example'` 无命中；yml 里口令均为 `${...}` 占位无默认值；bootstrap 口令无源码默认
  - 本阶段补齐 README：前置依赖行（JDK17/Maven/Docker）、无 mvn 时的 Docker 构建命令、一键冒烟脚本、302 用 GET 不用 `-I` 的说明
- [x] (b) docker compose 依赖可起（healthy），应用可启动
  - `docker compose up -d` 后三容器全部 healthy（mysql 首次初始化约 5 分钟属正常，之后秒级）；`docker compose ps` 三行均 healthy
  - `java -jar target/linkforge-0.0.1-SNAPSHOT.jar` 启动约 10s，日志 `Started LinkForgeApplication in 9.989 seconds`，bootstrap 租户/用户自动创建
- [x] (c) 真实执行：登录 → 创建短链 → `/r/{code}` 返回 302 且 Location 正确
  - `bash scripts/smoke.sh` → `SMOKE OK`，退出码 0。逐行：PASS health / PASS login user=admin tenant=demo / PASS create code=H9l50kd / PASS redirect location=https://example.com/smoke-1792769035 / PASS redis cache key=linkforge:code:H9l50kd
  - 落库核对：`lf_short_link` 两行（两次冒烟各一码）、`lf_click_log` 1 行（首次冒烟的 HEAD 403 未产生点击，行为自洽）、Redis 键 `linkforge:code:{code}` 两个
- [x] (d) `mvn test` 退出码 0（**当前仓库零测试类**，以 `mvn -B package` 全绿为准）
  - Docker JDK17 构建：`docker run --rm -v D:/LinkForge:/app -v linkforge_m2:/root/.m2 -w /app maven:3.9-eclipse-temurin-17 mvn -B -q package` → 退出码 0，产出 `target/linkforge-0.0.1-SNAPSHOT.jar`（47MB）
  - 测试欠账：P1 起补租户隔离与生命周期集成测试
- [x] (e) 已 commit 并 gh 直推 main（不开 PR）
  - 分支 feat/p0-hygiene → 验收绿 → 合回 main → `git push origin main`（SHA 见下）

### 验收证据（命令 + 输出摘要）

复现路径（审计人可直接照跑，Windows Git Bash / WSL）：

```bash
cp .env.example .env
docker compose up -d && docker compose ps        # 等三容器 healthy（mysql 首次初始化约 5 分钟）
docker run --rm -v "$PWD":/app -v linkforge_m2:/root/.m2 -w /app \
  maven:3.9-eclipse-temurin-17 mvn -B package    # 退出码 0，产出 target/*.jar
set -a && . ./.env && set +a
java -jar target/linkforge-0.0.1-SNAPSHOT.jar &  # 等 /api/health 返回 {"service":"linkforge","status":"UP"}
bash scripts/smoke.sh                            # 期望 SMOKE OK，退出码 0
```

Compose 日志扫描：三容器 `grep -icE '\[error\]|fatal'` 均为 0。

### P0 执行记录

- 2026-10-23：仓库基线 9423145（PR #1 合并提交）。compose up 三件套、Docker JDK17 构建、冒烟脚本编写完成。
- 2026-10-23：发现并修复冒烟脚本缺陷：`curl -sI` 发 HEAD，SecurityConfig 仅对 GET 放行 `/r/**`，HEAD 403。改为 `curl -s -D - -o /dev/null`（GET），README 同步修正。这是脚本问题不是应用缺陷，安全语义未动。
- 2026-10-23：冒烟全绿（SMOKE OK），DB/Redis 落库核对一致，日志零错误，密钥扫描干净。P0 收尾提交。

---

## P1 短链闭环

范围：开放注册（限流）与 bootstrap 共存（D3=C）；短链 list/detail/update/disable/expire；租户隔离测试；缓存一致性。不做自定义短码（D6=P5）、不做前端。

### 退出条件勾选

- [x] (a) 开放注册（限流）与 bootstrap 共存；短链 list/detail/update/disable/expire 齐全
  - `POST /api/auth/register`（新建租户+用户、BCrypt、返回 JWT、按 IP 固定窗口限流默认 5/600s、409 冲突、429 超限）；BootstrapDataLoader 未动，冒烟中 admin 登录与 register 同场通过
  - CRUD：`GET /api/links`（分页，MyBatis-Plus PaginationInnerInterceptor）、`GET /api/links/{code}`、`PATCH /api/links/{code}`、`POST /api/links/{code}/disable`；创建支持可选 `expireAt`（过去时间 400）
- [x] (b) 租户隔离集成测试：跨用户访问必须失败（非 200）
  - `TenantIsolationTest.crossUserAccessIsRejectedEverywhere`：B 读/改/禁 A 的 code 全部 404；B 的列表不含 A 的 code；A 侧不受影响仍 302/200。全绿
- [x] (c) 禁用/过期后缓存与 DB 一致，跳转不再 302 到原目标；过期状态码 README 写死
  - 测试 `disableStopsRedirectAndEvictsCache`（禁用→Redis 键删除→跳转 404）、`updateChangesUrlEvictsCacheAndRedirectFollows`（改 URL→键删除→跳转 Location 变新值→键回填新值）、`expiredLinkReturns410`（+2s 过期→410）
  - README 已写死：**禁用/不存在 → 404，过期 → 410**，越权统一 404
- [x] (d) 真实执行 mvn test 退出码 0；curl 冒烟通过
  - Docker JDK17 `mvn -B test` → `Tests run: 15, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS（连续两轮全绿）
  - `bash scripts/smoke.sh` → SMOKE OK：health / admin 登录 / 建链 / 302+Location / Redis 键 / **register** / 建链→详情→PATCH→Location 跟新 / disable→404
- [x] (e) commit + gh 直推 main（不开 PR）；本节已写满

### 验收证据（命令 + 输出摘要）

```bash
# 集成测试（需 compose 三件套在跑）
MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD":/app -v linkforge_m2:/root/.m2 -w /app \
  -e SPRING_DATASOURCE_URL="jdbc:mysql://host.docker.internal:3306/linkforge?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false" \
  -e SPRING_DATASOURCE_PASSWORD=linkforge_pass -e SPRING_DATA_REDIS_HOST=host.docker.internal \
  -e SPRING_RABBITMQ_HOST=host.docker.internal -e SPRING_RABBITMQ_PASSWORD=linkforge_pass \
  -e JWT_SECRET=test-secret-at-least-32-characters-long!! \
  maven:3.9-eclipse-temurin-17 mvn -B test
# → Tests run: 15, Failures: 0, Errors: 0, Skipped: 0 / BUILD SUCCESS

# 冒烟（应用跑 P1 jar 后）
bash scripts/smoke.sh
# → PASS health/login/create/redirect/redis cache/register/lifecycle create+detail+update/disable->404；SMOKE OK
```

测试矩阵：RegisterFlowTest×5（可用 token、租户重名 409、弱口令 400、非法字符 400、坏 JSON 400）、RegisterRateLimitTest×1（第 3 次 429）、LinkLifecycleTest×8（URL 协议校验、过期时间校验、分页与归属、详情、更新+缓存失效+跳转跟随、禁用+缓存失效+404、过期 410、空更新 400）、TenantIsolationTest×1（跨用户全覆盖）。

### P1 执行记录

- 2026-10-23：三段棘轮（注册限流 → CRUD/生命周期 → 隔离测试+冒烟+文档）全部内联完成；中途修复：application-test.yml 冒号值未引号导致 YAML 解析失败；测试 PATCH 请求工厂切换；重打包时旧 app 进程锁 jar（先停进程再打包）。
- 2026-10-23：验收全绿，commit 并合回 main 推送。

---

## P2 异步分析

范围：真实 RabbitMQ 消费者、ClickEvent 契约（eventId）、消费幂等、按天聚合 stats API（仅链主）、断 MQ 演练、D7 双写开关。不做前端/AI/自定义短码。

### 退出条件勾选

- [x] (a) 真实消费者落地；ClickEvent 含 eventId 等契约；消费幂等
  - `ClickEventConsumer`（@RabbitListener，错误进 DLQ 不回队）替换 stub；`ClickEvent` 字段：eventId/tenantId/linkId/code/userId(可空)/ip/userAgent/referer/clickedAt
  - 幂等：`lf_consumed_event` 唯一索引 + 独立事务 INSERT 捕获 DuplicateKeyException；`ClickIdempotencyTest.sameEventIdCountsOnceAcrossReplays`：同 eventId 投递 3 次聚合计数恒为 1
  - `MqEndToEndTest`：真实发布→消费→聚合→stats API 全链路（3+2 次跳转逐一追平）
- [x] (b) stats API 仅链主可查，有租户隔离测试
  - `GET /api/links/{code}/stats?days=`（days 1..90，默认 7；总量 + 补零按天序列）；复用 ShortLinkService.detail 做所有权判定
  - `StatsApiTest.statsVisibleOnlyToOwner`：链主 200 且数值正确，他人 404
- [x] (c) 断 MQ 演练：跳转仍 302，恢复后聚合追平
  - `scripts/mq-drill.sh` 实跑输出（N=8, M=6）：`PASS baseline: 8 clicks aggregated via live MQ` → 停 rabbit → `PASS outage: 6 redirects stayed 302 with MQ down` → 恢复 → `PASS recovery: stats converged to 14 (reconcile caught up)` → `DRILL OK`
- [x] (d) D7=A：双写保留 + 配置开关 + 对账文档
  - `LINKFORGE_ASYNC_CLICK_ENABLED`（默认 true）/ `LINKFORGE_SYNC_CLICK_ENABLED`（默认 true）/ `LINKFORGE_MQ_RECONCILE_FIXED_DELAY_MS`（默认 30s）
  - README「点击统计管道（D7 双写）」写明对账 SQL 与关同步前提；`ClickLogReconciler` 自动追平
- [x] (e) mvn test 绿 + commit/push + 本节写满
  - `Tests run: 19, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS（Docker JDK17）

### 验收证据

测试矩阵（19）：P1 全部 15 个 + ClickIdempotencyTest×2、StatsApiTest×1、MqEndToEndTest×1。

```bash
# 全量测试（同 P1 命令）
mvn -B test  # → Tests run: 19, Failures: 0, Errors: 0  BUILD SUCCESS

# 断链演练（应用跑 P2 jar、compose 在跑）
bash scripts/mq-drill.sh
# → PASS baseline / PASS outage / PASS recovery / DRILL OK

# 手工对账
docker exec linkforge-mysql mysql -ulinkforge -plinkforge_pass linkforge -e \
 "SELECT (SELECT COUNT(*) FROM lf_click_log) detail, (SELECT IFNULL(SUM(clicks),0) FROM lf_click_daily) aggregate;"
```

### P2 执行记录

- 2026-10-23：DDL（lf_click_daily / lf_consumed_event）进 schema.sql 并对运行库执行。三处真实缺陷被测试抓住并修复：matched-rows 幂等探测失效（决策 11）、MQ 消息转换器日期类型死信（决策 12）、MapperScan 吞接口（决策 13）。
- 2026-10-23：P1 冒烟回归 SMOKE OK；mq-drill DRILL OK；验收勾满，commit 并合回 main 推送。

---

## P3 可靠性（本轮终点）

范围：限流（创建按用户 / 跳转按 IP）、缓存失效加固、创建幂等、并发不撞码、Actuator 健康组件。不做前端/AI/自定义短码；不开始 P4/P5。

### 退出条件勾选

- [x] (a) 创建按用户限流、跳转按 IP 限流，超限 429，配置可环境变量调整
  - 键：`{prefix}create:user:{userId}`（默认 30/60s）、`{prefix}redirect:ip:{ip}`（默认 300/60s）；`LINKFORGE_CREATE_RATE_*` / `LINKFORGE_REDIRECT_RATE_*` 可调
  - `RateLimitTest`：同类 props 下第 4 次创建 → 429、第 4 次跳转 → 429（HTTP 状态与 body code 双断言）
- [x] (b) update/disable/expire 后缓存失效，更新后跳转 Location 约定窗口内一致
  - 写穿 + 1 秒延迟双删；`LinkLifecycleTest.updateWritesThroughCacheAndRedirectFollows`：PATCH 后缓存立即为新值 → 跳转 Location 即变 → 1.3s 后（双删已触发）再跳转仍为新值且缓存重建为新值；`disableStopsRedirectAndEvictsCache`：禁用即删键 + 跳转 404；`expiredLinkReturns410`：过期 410（不依赖缓存，每次校验 DB）
- [x] (c) 创建幂等 + 并发创建不撞码（测试证据）
  - `CreateIdempotencyAndConcurrencyTest`：同 `Idempotency-Key` 串行重放同码且仅 1 行；4 线程并发同键 → 全部同码、仅 1 行；10 线程 30 次并发无键创建 → 30 个互异短码、总数 30；无键两次创建 → 两码
- [x] (d) Actuator health 覆盖 db/redis/mq，Compose 下可观察 UP/DOWN
  - `HealthEndpointTest`：components.db/redis/rabbit 全 UP
  - 实机证据（P3 jar + Compose）：全 UP `{"status":"UP","components":{"db":{"status":"UP"...},"rabbit":{"status":"UP","details":{"version":"3.13.7"}},"redis":{"status":"UP","details":{"version":"7.4.11"}}}}`；`docker compose stop rabbitmq` 后 → 整体 **503**、`rabbit: DOWN (AmqpConnectException: Connection refused)`；恢复后 → **UP/200**
- [x] (e) mvn test 绿 + 冒烟通过 + commit/push + 本节写满
  - `Tests run: 26, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS（Docker JDK17）
  - `bash scripts/smoke.sh` → SMOKE OK（P3 jar 回归全过）

### 验收证据

测试矩阵（26）：P0-P2 全部 19 个 + RateLimitTest×2、CreateIdempotencyAndConcurrencyTest×4、HealthEndpointTest×1（LinkLifecycle 更新用例改为写穿断言）。

```bash
# 全量测试（同前）
mvn -B test            # → Tests run: 26, Failures: 0, Errors: 0  BUILD SUCCESS
# 冒烟
bash scripts/smoke.sh  # → SMOKE OK
# 健康组件 UP/DOWN 演示
curl -s http://localhost:8080/actuator/health                       # status UP, db/redis/rabbit UP
docker compose stop rabbitmq && sleep 3 && curl -i http://localhost:8080/actuator/health  # 503 + rabbit DOWN
docker compose start rabbitmq && sleep 10 && curl -s http://localhost:8080/actuator/health # 恢复 UP
# 并发不撞码（测试内实证，也可用 HTTP 工具复压）
# 见 CreateIdempotencyAndConcurrencyTest.parallelCreatesYieldDistinctCodes
```

### P3 执行记录

- 2026-10-23：限流泛化 + 幂等创建 + 撞码重试 + 写穿双删 + health 细节全量实现；修复 health 配置作用域（决策 18）与限流测试窗口偶发（决策 19）。
- 2026-10-23：26/26 全绿；冒烟回归 SMOKE OK；health UP/DOWN/UP 实证完成；P3 收尾合并推送。

---

## 本轮交付终态（P0–P3 完成）

> **P0–P3 完成，待实习JAVA_AI 审计后再定 P4/P5。**

- 最新 main commit SHA：见下方「审计材料」
- 全部验收命令输出摘要：见各阶段「验收证据」
- 已知缺口（仅限 P4/P5 范围项）：CI（GitHub Actions）、bench.md 压测数字、测试矩阵归档（当前集成测试已覆盖 P1–P3 关键路径但未做 Testcontainers 化）、简历条目、自定义短码/CSV/极简页/OpenAPI（P5）
- 明确未做（按约束）：前端、AI、自定义短码、云部署、Sa-Token、RocketMQ

## 自审迭代记录（P3 停轮后、移交审计前）

按审计人视角自查一轮，发现并修复 4 个真实缺陷、2 处文档问题；全部有测试或实测证据。

| # | 发现 | 严重度 | 修复与证据 |
|---|------|--------|-----------|
| F1 | X-Forwarded-For 被无条件信任，伪造该头可绕开注册/跳转两个按 IP 限流（无限开租户） | 高 | `LINKFORGE_TRUST_XFF` 默认 false 只取 socket 地址；RateLimitTest 加入伪造头用例（4th 仍 429）、新增 XffTrustTest（trust=true 时独立窗口） |
| F2 | RedisRateLimiter 的 EXPIRE 无降级保护：Redis 抖动时请求 500；「INCR 成功 EXPIRE 失败」留下永不过期键 → 达到阈值后永久 429 | 高 | EXPIRE 纳入 try，失败尽力删键自愈；degrade-open 语义补全 |
| F3 | ClickStatsService.consume 去重插入与聚合计数分属两事务，中间崩溃少计且对账器永久跳过 | 中 | 两语句并入同一短事务（MySQL 语句级回滚语义下安全）；ClickIdempotencyTest 复跑全绿 |
| F4 | 合成统计测试数据打穿对账等式：实测 detail=87 vs aggregate=118，审计命令第 6 条会当场失败 | 高 | 测试 @AfterEach 自清理；历史污染 TRUNCATE 后对账器从明细重建，实测 104=104=104、reconciler 日志 `reconciled 104 click rows`；随后冒烟再跑仍 106=106 |
| F5 | README 两处陈旧文案（stats 包注释仍写 stub 消费者；「下一期」表仍把幂等/限流/异步列为未做） | 低 | 已改为现状（P0–P3 已交付、P4/P5 待审计后另定） |
| F6 | 存量数据卷无迁移说明（lf_click_daily/lf_consumed_event 仅新卷自动建表） | 低 | README 增补迁移注记（CREATE TABLE IF NOT EXISTS 幂等可重跑） |

自审后全套验证：`mvn -B test` → **Tests run: 27, Failures: 0, Errors: 0**（新增 1 个 XFF 用例）；`bash scripts/smoke.sh` → SMOKE OK；`/actuator/health` 200；对账等式闭合。

---

## 追加迭代 R2–R4（运营者 2026-10-24 授权：审计前由开发侧继续功能迭代）

> 原计划「P3 后停轮待审」，运营者 2026-10-24 指示先在开发侧多轮迭代功能再交审计。P4 工程化项（CI、bench.md 压测数字、简历条目）**仍留待审计后**；AI 仍不做。审计基线：P0–P3 终态 103a199、自审终态 447dfdb，可单独核对。

### R2 生命周期补全 + 自定义短码（D6 的 P5 项提前，9307a19）

- `DELETE /api/links/{code}` 逻辑删除（短码因 uk_code 永久保留不可复用，已文档化）；`POST /{code}/enable` 与 disable 对称；`PATCH` 支持改 `expireAt`（仅未来时间）
- `customCode`：4-16 位 `[A-Za-z0-9_-]`、保留字黑名单（api/r/actuator/health/auth/export 等）、被占用（含跨用户、含已删除码）→ 409；随机码与自定义码共用唯一索引，并发安全
- 未鉴权访问管理接口从 403 改为统一 401 JSON
- 测试：CustomCodeTest×5（占用/非法/保留字/删除后保留/跨用户隔离）+ LinkLifecycle 新增 4 例 → **36/36 绿**

### R3 访问分析增强（391e5f2）

- `GET /api/links/{code}/clicks`：点击明细（时间/IP/UA/Referer，新到旧分页，仅链主）
- stats 增加精确 UV：按天 `COUNT(DISTINCT ip)` 与窗口总 UV，全部来自 D7 同步明细（不需要近似算法；README 已注明关同步后该能力仅覆盖历史数据）
- `GET /api/links/export`：本人短链 CSV（UTF-8 BOM、逗号/引号转义、上限 1 万行）
- 测试：StatsApiTest 新增 UV+明细用例、CsvExportTest×2（隔离与转义）→ **39/39 绿**（R3 时点）

### R4 可观测 + OpenAPI + 极简控制台（c1ecd46）

- Micrometer 业务计数器：`linkforge.links.created`、`linkforge.redirects`、`linkforge.ratelimit.rejected{entry=register|create|redirect}`；`/actuator/metrics/**` 需 Bearer（不扩大匿名面）
- springdoc 2.6.0：`/v3/api-docs` + `/swagger-ui/index.html`（放行文档路径）
- D4 允许的**纯静态极简控制台**（单文件 vanilla JS，非 SPA）：登录/注册、建链（自定义码+过期时间）、分页列表、启停/删除、点击与 UV 统计、CSV 入口；`http://localhost:8080/`
- 测试：ObservabilityTest×2（计数器值断言、api-docs/swagger/静态页可达）→ **41/41 绿**
- 真机验证：SMOKE OK；`/` 返回控制台；swagger-ui 200 且 api-docs 列出全部端点；`linkforge.redirects` 计数与冒烟跳转数一致

### 追加迭代后的对账与终态

- 对账等式持续成立（R4 冒烟后实测 detail=188 == aggregate=188）
- 全套测试 **41/41 绿**（Docker JDK17）；冒烟 SMOKE OK；应用运行中

### 审计材料（P4 后更新）

- 仓库：https://github.com/NorthAdb/LinkForge（分支 main，**P5 终态合并：4ce9a0b**）
- **CI**：main 最新 run **success** — https://github.com/NorthAdb/LinkForge/actions/runs/40058854727（63 测试全绿）
- 审计分层：103a199（P0–P3）→ 447dfdb（自审）→ c1ecd46（R2–R4）→ 7e8b9fe（hotfix）→ **880f0a6（P4）**
- 复跑清单（P4 版）：
  1. 本机任何有 Docker 的 Linux：`mvn -B verify`（Testcontainers 全量，参照 README「Linux 复现」或直接看 CI）
  2. Windows 开发机（Docker Desktop 29 与 Testcontainers 不兼容，见 P4 执行记录）：`mvn -B verify -DskipITs`（16 单测绿）
  3. `bash scripts/smoke.sh` → SMOKE OK；`bash scripts/mq-drill.sh` → DRILL OK（若刚跑过压测先 `rabbitmqctl purge_queue linkforge.click.queue` 并等消费端清空）
  4. `bash scripts/bench.sh` → 复现 bench.md（约 5 分钟）
  5. 对账 SQL：`SELECT (SELECT COUNT(*) FROM lf_click_log) detail, (SELECT IFNULL(SUM(clicks),0) FROM lf_click_daily) aggregate;` — P4 收尾时已 TRUNCATE 并由 Reconciler 全量重建（本机速率 ~1.7 行/s，从 3460 行起收敛约 35 分钟；若暂不相等，等待后复查即闭合；机制见前文多次实证）
- 文档入口：README「架构」节（mermaid + 时序 + Demo 五步 + Linux）；docs/bench.md（压测）；docs/resume.md（简历条目）

**P4 完成，待实习JAVA_AI 审计后再定 P5 收尾。**

---

## P5 抛光收尾（feat/p5-polish，发车基线 main@880f0a6 / f27d4bc，运营者 2026-10-24 授权开跑）

范围纪律：不做 AI/向量/LLM；不换栈；无独立 SPA；云部署非硬验收（D5）。R2–R4 已落地能力视为完成，只盘点、补洞、文档收口。发车词四项退出条件逐项勾选如下。

### (a) 功能核对——已完成项「仍绿」证据（无真实缺口，未改业务代码）

| 能力 | 证据 |
|------|------|
| 自定义短码（D6：字符集/保留字/占用 409/删除不可复用/跨用户隔离） | `CustomCodeIT` ×5（CI success run 40056206371） |
| CSV 导出（仅链主、转义、鉴权） | `CsvExportIT` ×2 + 本轮手工：无 Token → 401；有效 Token → CSV 首行 `code,original_url,...` 且含本人码 |
| 极简静态控制台（非 SPA） | `ObservabilityIT.apiDocsSwaggerUiAndStaticPageAreServed`（GET / 200）+ 本轮手工：页面含 create/disable/enable/delete/统计/CSV 入口与幂等头注入；`id="exportLink"` 实测指向 /api/links/export |
| Swagger（dev 开放/prod 关闭） | 路由一致性核对（本轮实测）：`/v3/api-docs` 列 11 条路径，与控制器、README 接口表逐一吻合，无幽灵路由；prod 侧 `ProdProfileIT` 401 |
| 点击明细 / 精确 UV / enable / delete | `StatsApiIT.uvIsExactDistinctIpAndClicksDetailIsOwnerScoped`、`LinkLifecycleIT.deleteRemovesLinkEverywhere` / `enableRestoresDisabledLink` |

可选加分（已做）：`scripts/migrate.sh`（幂等迁移，实测对存量卷重放 schema.sql 成功，对应审计 L2）；README 补限流覆盖说明（自定义码共用创建限流；CSV 属管理 API 未单独限流、1 万行上限）。

### (b) 文档收口

README 新增「核心路径 vs 可选特性」表：核心 = 注册/登录、建链、302、stats、限流、双写+对账、CI/bench；可选 = 自定义短码、CSV、Swagger、控制台、UV/明细（「演示可用，面试可讲，主路径不强制」）。Demo 五步改为纯核心路径，控制台标注为可选入口。同步清偿 P4 文档债：测试计数统一 63（16+47，与 @Test 计数及 CI 一致）、公网收紧改为 `SPRING_PROFILES_ACTIVE=prod` 一句话、progress 文首边界改为「P0–P4 已交付，本轮 P5」。

### (c) 方案 P5 四项勾选（对照《实施与验收方案》P5 节）

- [x] 自定义短码（字符集/保留字/唯一冲突）——R2 提前完成，本轮复核仍绿（见上表）
- [x] CSV 导出（鉴权）——R3 提前完成，复核仍绿
- [x] 极简管理页（登录、建链、列表、统计；无 SPA）——R4 提前完成，复核仍绿
- [x] OpenAPI（springdoc /swagger-ui，与真实行为一致）——R4 提前完成，本轮路由核对一致

### (d) 回归

- 本机 `mvn -B verify -DskipITs` → BUILD SUCCESS（16 单测绿；Windows 与 Testcontainers 不兼容见 P4 记录，63 全量以 CI 为权威）
- `bash scripts/smoke.sh` → **SMOKE OK**；`bash scripts/mq-drill.sh` → **DRILL OK**（15 基线 / 断 MQ 全 302 / 追平 25）
- 对账等式闭合：detail=3487 == aggregate=3487
- CI：见文末审计材料的 run 链接

### (e) 明确不做复查

grep 复查确认：仓库无 AI/向量/LLM/pgvector/Supabase 依赖（pom 无相关 artifact，源码无相关 import）；无独立 SPA（唯一前端为单文件静态控制台）；无云部署硬依赖（D5=仅本地 Compose，prod profile 仅收紧暴露面）。

**P5 完成，完整项目交付，待实习JAVA_AI 终审。**（P0–P5 全部阶段闭环；审计分层：103a199 → 447dfdb → c1ecd46 → 7e8b9fe → 880f0a6 → 4ce9a0b）

---

## v1.0.0 发布（终审通过后，运营者 2026-10-24 授权）

终审结论：通过。发布动作：

- 全文档终审：README 去 MVP 化（标题/技术栈/边界段重写，状态表标注 v1.0.0）、`docker/README.md` 充实（原仅一句话）、jar 名引用全量联动（README×2、bench.sh×2）；progress 内历史记录中的 MVP 字样与旧 jar 名按历史存档保留
- 版本：pom `0.0.1-SNAPSHOT` → **`1.0.0`**，产出 `target/linkforge-1.0.0.jar`
- 验证：`mvn -B verify -DskipITs` BUILD SUCCESS（16 单测）；1.0.0 jar 实机启动 + `smoke.sh` **SMOKE OK**；CI 见最新 main run
- 发布标识：git tag **`v1.0.0`** 推送远端

**v1.0.0 发布完成。后续迭代方向另行讨论（AI 模块、云公网部署等仍不在本仓库范围）。**

自审中评估后**不改**的两点（记录供审计人参断）：
- 跳转路径每次仍读 DB 行做禁用/过期判定（缓存只省 URL 字段）：这是**主动选择**——只信缓存会让禁用/过期在 TTL 内放行 302，正确性优先于该读优化；P4 压测时再量化。（审计 L8 同判，留 P4 bench 量化。）
- 未鉴权访问管理接口返回 403（Spring Security 默认）而非 401：**R2 已改为统一 401 JSON**（见追加迭代），本条已过时。

---

## 深化 A2/A3/B12（feat/deepen-a2-a3-b12，发车基线 main@0189e2a，2026-10-24）

范围：交接单 `记录追踪/05-阶段交接/LinkForge-phase-A2A3B12-handoff.md`。A2 保留字补齐、A3 HEAD 探测不计点击、B12 OpenAPI/progress 卫生。范围外（A6 Flyway、A7 Outbox、A8–A10、UV 实现改造、上云）一律未动。本段 diff（`0189e2a..2d3e070`，不含 .gitignore 杂务）＝ **13 文件 / +244 / -10**。

### 改动清单（分支内三单元提交）

| 单元 | 提交 | 内容 |
|------|------|------|
| A2 | `91bd433` | `LinkValidations.RESERVED_CODES` += admin, console, metrics, assets, error, favicon（api/r 不入集：<4 位被长度规则先拦，避免死条目——沿袭 L7 判例）；`LinkValidationsTest`（admin/ADMIN/favicon 拒、docs2 过）；`CustomCodeIT` bad 列表 + admin/ADMIN；README 保留字句重写 |
| A3 | `caf292f` | `ShortLinkService` 抽 `resolveTarget`（限流+缓存+行状态/过期+缓存回填，GET/HEAD 共用、一次 DB 读），`resolveAndRecord` 记点击/计数，新增 `resolveForProbe` 只解析；`RedirectController` 加 `@RequestMapping(HEAD)` 返回 302+Location（与 GET 同构）；`SecurityConfig` 放行 `HEAD /r/**`；`AbstractIntegrationTest.head()`；新 `RedirectHeadIT` 4 例 |
| B12 | `a844908` | pom spring-boot-plugin 加 `build-info` goal；新 `OpenApiConfig`（`info.title=LinkForge`、version 取 `BuildProperties`=pom 1.0.0，无 build-info 时回退 "dev"）；新 `OpenApiInfoIT`（dev/test 断言 title+version）；progress 文首「当前 tip + 历史快照说明」 |

### 决策记录

| # | 决策 | 理由 |
|---|------|------|
| D-A3-1 | HEAD 状态码约定选 **302+Location** | 交接单推荐项之一；与 GET 完全同构，`curl -I` 直读目标，全文统一 |
| D-A3-2 | HEAD 共用按 IP 的 redirect 限流桶 | HEAD 走同一 Redis+DB 读路径，不限流等于开放无节制读旁路 |
| D-A3-3 | `linkforge.redirects` 计数器仅 GET 递增 | 探测不是一次对外跳转服务；指标语义不变，bench 可比性保持 |
| D-A3-4 | 更新路径无自定义码入口（`UpdateShortLinkRequest` 无 customCode 字段），A2 的「更新也走同一校验」自然满足 | 代码事实，无需改动 |
| D-B12-1 | OpenAPI version 走 BuildProperties（build-info）而非硬编码 | 与 pom 版本单一事实源；IDE 裸跑回退 dev 不炸 |
| 深化-1 | 子代理通道两次被服务端拦截（unusual activity），按 P1 决策 #4 先例转总控内联实现 | 通道不可用不阻塞主线；实现按单元自查自测 |

### 验收勾选（对照交接单验收表）

| # | 标准 | 证据 |
|---|------|------|
| A2-1 | admin（任意大小写）创建被拒 | 实机：`customCode=admin` → `{"code":400,"message":"customCode is reserved"}`，`ADMIN` 同；IT `CustomCodeIT.invalidCustomCodesRejected`（bad 列表含 admin/ADMIN） |
| A2-2 | 原有保留字不回归 | `LinkValidationsTest.reservedWordsRejectedCaseInsensitively` 绿（stats/DOCS/favicon 拒、docs2 过）；`verify -DskipITs` 16/16 |
| A2-3 | 合法自定义码仍可建 | 实机：`docs67302794` → 200 `code=docs67302794`；`CustomCodeIT.customCodeIsUsedAndRedirects` |
| A2-4 | 惯用验证命令绿 | 本机 `D:/tools/apache-maven-3.9.9/bin/mvn -B verify -DskipITs` → **Tests run: 16, Failures: 0, Errors: 0, BUILD SUCCESS**；IT 全量看 CI（本机 Desktop 29.9.2 与 Testcontainers 不兼容，P4 已记录） |
| A3-1 | HEAD 不再 403 | 实机 `curl -I -X HEAD /r/{code}` → `HTTP/1.1 302`（旧版 403 已成历史）；IT `RedirectHeadIT` |
| A3-2 | 有效码 HEAD 可探测成功（统一 302） | 实机：302 + `Location: https://example.com/head-probe`；IT 断言 302+Location |
| A3-3 | HEAD 无新增点击，GET 才 +1 | 实机 SQL：该码 `lf_click_log` HEAD 后 **0 行**、GET 后 **1 行**；`lf_click_daily` 消费追平到 1（队列 0 积压）；IT `headProbesValidLinkWithoutCountingClicks`（stats 前后对比 + GET+1）。注：stats `totalClicks` 读异步聚合表，实机首查 0 属消费延迟（实测 6s 内追平），明细表即硬证据 |
| A3-4 | 无效码 HEAD 与 GET 同档 | 实机：未知码 HEAD → 404；IT 另覆盖禁用 404、过期 410 双方法对齐 |
| A3-5 | 相关测例 + 全量 verify 绿 | 本机 16 单测绿 + SMOKE OK；全量 `mvn -B verify`（63+8 新例）由 CI ubuntu 实证，链接见下「CI」 |
| B12-1 | api-docs info 非 springdoc 默认 | 实机：`"info":{"title":"LinkForge","description":"…","version":"1.0.0"}`；IT `OpenApiInfoIT` |
| B12-2 | prod 文档仍关闭 | `ProdProfileIT.prodHidesHealthDetailsAndDocs` 未动，CI 回归 |
| B12-3 | progress 文首有当前 tip；历史不伪装 | 见文首两行 blockquote（开工 `0189e2a`，收工见下）；历史段落已标「历史快照」 |
| B12-4 | progress 有本阶段验收记录 | 本节即是 |

### 验证命令与输出摘要（本机，2026-10-24）

- `D:/tools/apache-maven-3.9.9/bin/mvn -B verify -DskipITs` → `Tests run: 16, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`；`target/classes/META-INF/build-info.properties` 生成（`build.version=1.0.0`）
- 实机（停旧进程解锁 jar → 新 jar `target/linkforge-1.0.0.jar` 启动，compose 三容器 healthy）：A2/A3/B12 逐项 curl 与 SQL 见上表；`bash scripts/smoke.sh` → **SMOKE OK**（8 PASS）
- CI（全量 68 例 = 16 单测 + 52 IT，0 失败）：分支首跑抓到本阶段唯一缺陷——`RedirectHeadIT` 在 GET 后同步断言异步聚合值（`totalClicks` 读 `lf_click_daily`，消费延迟致 0），修为断言同步明细端点 `/clicks`（`e49acc8`）；终态分支 run [40072588187](https://github.com/NorthAdb/LinkForge/actions/runs/40072588187) ✅、main run [40072759677](https://github.com/NorthAdb/LinkForge/actions/runs/40072759677) ✅（`Tests run: 52` IT + `Tests run: 16` 单测）

---

## 深化 A6 Flyway（feat/deepen-a6-flyway，发车基线 main@a781b55，2026-10-24）

范围：交接单 `记录追踪/05-阶段交接/LinkForge-phase-A6-flyway-handoff.md`。把「schema.sql + 幂等 migrate.sh 灌文件」换成 **Flyway 版本化 migration**；顺带收口上轮审计 nit（N1 必做 + N2/N3）。范围外（A7 Outbox、A8 维度、A9/A10、UV、上云、破坏性 migration、第二套迁移工具）未动。

### 上轮 nit 收口（分支首个提交）

- **N1** 文首 tip 改精确 `a781b55`（`git rev-parse HEAD` 可对，本节收工 tip 见文末更新）
- **N2** 深化 A2/A3/B12 段落补真实口径 diff：`0189e2a..2d3e070`（不含 .gitignore）＝13 文件 / +244 / -10（`git diff --stat` 实测与审计一致）
- **N3** `LinkValidationsTest` 新增 `@ParameterizedTest`（7 词逐例拒绝），单测 16→23

### 改动清单

| 单元 | 内容 |
|------|------|
| 依赖/配置 | pom 加 `flyway-core` + `flyway-mysql`（版本随 Boot 3.3.5 BOM＝**flyway 10.10.0**）；`application.yml` 基础段 `spring.flyway`（enabled/locations=classpath:db/migration/**baseline-on-migrate=true/baseline-version=1**，dev/test/prod 同生效）；未关 validateOnMigrate，无 clean |
| V1 基线 | `src/main/resources/db/migration/V1__baseline_schema.sql`＝现网 6 表 DDL 原样迁入，去掉 CREATE DATABASE/USE 与 IF NOT EXISTS（空库才会跑到它，误跑应炸不应默静） |
| Compose（选 A） | 删 `schema.sql → /docker-entrypoint-initdb.d` 挂载；新卷空启 MySQL，**表由应用启动时 Flyway 建** |
| IT | `AbstractIntegrationTest` 去掉 `withInitScript`（每个 IT 上下文即「空库→V1」实测）；新增 `FlywayBaselineIT`（独立自起 legacy MySQL（schema.sql 作夹具，表齐无 history）+ Redis；rabbit 经 test profile listener/async 关闭不接触），断言 history 恰一行 BASELINE@1、V1 未重放、注册/建链/302 全通 |
| 脚本 | `migrate.sh` 重写为 `mvn flyway-maven-plugin:migrate` 包装（插件版本 `${flyway.version}` 与 BOM 同源；连接参数来自 .env；失败非 0 退出） |
| 文档 | README「存量库迁移」重写（新卷/存量卷/pre-P2 老卷三条路径）、「配置要点」加 Flyway 行；schema.sql 文首标注 REFERENCE ONLY |

### 决策记录

| # | 决策 | 理由 |
|---|------|------|
| D-A6-1 | baseline-version=1 与 V1 对齐 | 「表已在、无 history」的存量卷记一笔 baseline@1 后**跳过** V1，不重放 DDL、不要求删卷；空库不受 baseline 影响照跑 V1 |
| D-A6-2 | Compose 选交接单推荐 A | 单一 schema 事实源（db/migration），杜绝 initdb 与 Flyway 双源打架 |
| D-A6-3 | schema.sql 保留原地，标注 REFERENCE ONLY + 兼 FlywayBaselineIT legacy 夹具 | 不再被执行于任何生产路径（compose/IT/migrate.sh 均已切走）；pre-P2 老卷补两表仍按它手工补（见下条） |
| D-A6-4 | pre-P2 部分表老卷：先按 schema.sql 补两表、再启动/跑 migrate.sh 完成 baseline | baseline@1 会跳过 V1，补不了缺表；本阶段交接单禁止写 V2（留给 A7/A8 的正规路径） |
| D-A6-5 | migrate.sh 走 flyway-maven-plugin 而非 CLI 镜像 | 版本 `${flyway.version}` 继承 Boot BOM，与应用内 Flyway 同版本同配置，无镜像漂移 |
| D-A6-6 | FlywayBaselineIT 独立容器不进 AbstractIntegrationTest 单例 | 套件共享 MySQL 已被首上下文迁移，baseline 场景必须无 history 的独立库 |

### 验收勾选（对照交接单验收表）

| # | 标准 | 证据 |
|---|------|------|
| A6-1 | 依赖/配置齐全启用 | pom（flyway-core/mysql 10.10.0）；实机启动日志 `Successfully validated ... DbMigrate`；`flyway_schema_history` 有行（两条实机路径均查到） |
| A6-2 | 版本化 V1 与业务表对齐 | `V1__baseline_schema.sql` 6 表与 schema.sql 逐列一致（去掉 DB/USE/IF NOT EXISTS）；空库实测建出 7 表（6 业务 + history） |
| A6-3 | 新库仅靠 Flyway 建库可用 | 一次性容器 mysql:8.0@3307（0 表）→ 新 jar 启动 → 日志 `Migrating schema linkforge to version "1 - baseline schema"` → `information_schema` 7 表 → 登录（bootstrap 在 Flyway 后由 ApplicationRunner 落库）/建链 `yRq28jZ`/302 全 PASS |
| A6-4 | 存量库 baseline 不删卷 | 实机（本机 compose 卷，871 条链路数据在）：`MVN=… bash scripts/migrate.sh` → `Successfully baselined schema with version: 1`（exit 0）；history 恰一行 `1 / << Flyway Baseline >> / BASELINE / success=1`；数据保留（`lf_short_link` 871 行不变） |
| A6-5 | migrate.sh 走 Flyway 不灌旧 schema | 脚本全量重写（读脚本可见）；上述实机跑通记录 |
| A6-6 | IT 无双轨 | `AbstractIntegrationTest` 已无 withInitScript；`FlywayBaselineIT` 覆盖 baseline 路；CI 全量实证 |
| A6-7 | README/progress 更新、N1 已做 | 本节 + README 三处；N1 于分支首提交完成 |
| A6-8 | verify 全绿、CI 绿 | 本机 `mvn -B test` → **23 单测绿**；CI 全量 **77 例（23 单测 + 54 IT）0 失败**：分支终态 run [40075155191](https://github.com/NorthAdb/LinkForge/actions/runs/40075155191) ✅（FlywayBaselineIT 2/2）、main run [40075330083](https://github.com/NorthAdb/LinkForge/actions/runs/40075330083) ✅ |

### 执行记录（含 CI 首跑红的两处修复）

- 分支首跑 run 40074874592 红 1 例：`FlywayBaselineIT` 对 `flyway_schema_history.success` 按 "1" 断言，而 MySQL 下该列是 `bit(1)`、JDBC 读出为 Boolean（本机 mysql CLI 把 bit 渲染成 1，掩盖了类型差）——修为 `assertEquals(Boolean.TRUE, …)`。
- 同一次 CI 有非测试线程崩：`NoSuchMethodError SystemProperties.getUserName`（Thread-3）。定位为 Testcontainers 2.0.5 需要比 Boot 3.3.5 BOM（commons-lang3 3.14.0）更新的 lang3；pom 覆盖 `commons-lang3.version=3.18.0`（lang3 向上二进制兼容，单一覆盖对齐所有消费方）。终态 CI 无该异常。
- 存量卷实测细节（A6-4 证据补充）：baseline 前后 `information_schema.tables` 6→7（新增 history 表），`lf_short_link` 871 行数据原样保留。

### 验证命令与输出摘要（本机，2026-10-24）

- `D:/tools/apache-maven-3.9.9/bin/mvn -B test` → `Tests run: 23, Failures: 0, Errors: 0` + BUILD SUCCESS（本机 Desktop 29.9.2 与 Testcontainers 不兼容，IT 看 CI，P4 已记录）
- 存量卷：`bash scripts/migrate.sh`（输出见 A6-4）→ 新 jar 启动 → `Current version of schema linkforge: 1 / Schema linkforge is up to date. No migration necessary.` → `bash scripts/smoke.sh` → **SMOKE OK**（8 PASS）
- 空库：一次性 `mysql:8.0` 容器@3307（`fresh_tables=0`）→ 新 jar → Flyway V1 建表（输出见 A6-3）→ 登录/建链/302 PASS → 容器已删除
- 面试口径：**「版本化 migration + 存量 baseline，后续加表不靠 IF NOT EXISTS 堆脚本」**

---

## 深化 A7 Transactional Outbox（feat/deepen-a7-outbox，发车基线 main@68cbcc6，2026-10-24）

范围：交接单 `记录追踪/05-阶段交接/LinkForge-phase-A7-outbox-handoff.md`。点击管道升级为**同事务 Outbox + Relay 投递**，硬验收＝关 sync + 断 MQ 恢复后追平（`scripts/outbox-drill.sh`）；顺带收口 A6 审计 N1/N2/N3。范围外（Debezium/CDC、换 MQ、Seata、A8–A10、UV 算法改造、drop、改 V1）未动。

### 上轮 nit 收口（分支首个提交）

- **N1** 文首 tip 改精确开工 `68cbcc6`（收工见本节末尾）
- **N2** `docker/README.md` initdb 句改 A6 路径 A 口径（空卷 + 应用 Flyway 建表）
- **N3** 文首「本轮边界」对齐 A7

### 决策记录（D-A7-1…6）

| # | 决策 | 理由 |
|---|------|------|
| D-A7-1 | `lf_outbox`：id PK、event_id **UK**、payload JSON、status PENDING/SENT、attempts、next_attempt_at、created_at/sent_at；索引 (status, next_attempt_at, id) | UK 兜底同事件只投一次；索引即 Relay 扫描路径 |
| D-A7-2 | `recordClick` 加 `@Transactional`：sync 开时 `lf_click_log` + PENDING outbox **同一事务**；**热路径删除直接 publish**（原 RabbitTemplate 调用移除） | 跳转不再被 MQ 延迟/故障影响；两行要么都在要么都不在 |
| D-A7-3 | Relay＝应用内 `@Scheduled` 轮询（默认 2s、批量 100、`2^n` 秒退避上限 60s），逐行 publish→markSent / markRetry | 不上 Debezium（交接单禁区）；开关逐 tick 读 bean，演练/测试可运行时切换 |
| D-A7-4 | **消费端为 UUID 事件回填 `lf_click_log`**（行打 `event_id` 标记） | 关 sync 后明细/UV 从「丢失」变「最终一致」（延迟≈Relay+消费），而非只聚合追平 |
| D-A7-5 | 旧 `ClickLogReconciler` 降级为 **sync 开启时的安全网**；`sync-click-enabled=false` 时**不运行**；扫描跳过 `event_id IS NOT NULL` 的回填行 | sync 关时追平由 outbox 独占承担，不假装还能靠明细扫描（实跑曾暴露双计，见执行记录） |
| D-A7-6 | eventId：sync 开＝`log-{明细行id}`（确定性）；sync 关＝稳定 UUID **持久在 outbox.event_id**，publish/消费同一 id | 与既有幂等/DLQ 完全兼容，消费端 `lf_consumed_event` 去重键不变 |

### 改动清单

| 单元 | 内容 |
|------|------|
| V2 迁移 | `V2__outbox.sql`：建 `lf_outbox` + `ALTER lf_click_log ADD event_id`（回填行标记）；存量库（A6 已 baseline）migrate 即得，实测演练实例首启自动应用 |
| 热路径 | `ClickRecordService`：`@Transactional`（明细可选 + outbox 必写），ObjectMapper 序列化 `ClickEvent` 为 JSON payload；删直连 publish |
| Relay | 新 `ClickOutboxRelay`（`OutboxMapper.selectPendingBatch/markSent/markRetry` 注解 SQL）；逐 tick 校验 relay+async 开关 |
| 消费端 | `ClickStatsService.consume`：UUID 事件在同一幂等短事务内回填明细（带 event_id 标记）再累加聚合 |
| 对账器 | `ClickLogReconciler` 加 sync 门禁 + 扫描排除回填行 + javadoc 重写 |
| 配置 | `linkforge.mq.relay-*` 四项（`LINKFORGE_MQ_RELAY_ENABLED/_BATCH_SIZE/_FIXED_DELAY_MS/_INITIAL_DELAY_MS`，默认 true/100/2000/3000） |
| IT | 新 `OutboxDrillIT` 2 例（同事务+relay 开关收敛、关 sync 收敛含 UV）；`MqEndToEndIT` 加 relay 快节奏；`StatsApiIT`/`ClickIdempotencyIT` 清理扩到消费端回填行；`AbstractIntegrationTest` 容器单例转 protected。**断链重试段（A7-3）不放共享 DB 的 IT**：套件多个缓存上下文各有健康 relay 在同一 MySQL 上竞争抢单，注入式失败 publisher 必被抢跑；真停 Rabbit 容器又污染后续上下文——按交接单「脚本优先」由 `outbox-drill.sh` 承担（断链段 SQL 断言 attempts≥1 + relay deferred 日志行数≥1） |
| 脚本 | 新 `scripts/outbox-drill.sh`（自带 8082 sync-off 实例：断 MQ→302×M→PENDING=M→恢复→聚合=明细=SENT=M、UV≥1；失败非 0 退出） |
| 文档 | README 管道节整段重写（旧「何时可关同步 ⚠️」警告删除，被 outbox 语义取代）、演练双入口、配置要点、模块/核心路径表措辞 |

### 验收勾选（对照交接单）

| # | 标准 | 证据 |
|---|------|------|
| A7-1 | V2 建 outbox 表、migrate 绿 | CI 全量（空库 V1+V2）；实机：演练实例首启自动 V2、本机存量库 3506=3506 等式保持 |
| A7-2 | 热路径同事务写 outbox；跳转不因 MQ 失败失败 | IT `sameTransactionWritesDetailAndPendingOutboxWithRelayOff`（relay 关：3×302、明细 3、PENDING 3）；实机断 MQ 段 10×302 |
| A7-3 | Relay PENDING→SENT、失败可重试 | 实机 `outbox-drill.sh` 断链段：SQL `attempts≥1` + relay deferred 日志行数≥1、行保持 PENDING；恢复段 SENT=10（该段不进共享 DB 的 IT，见「改动清单·IT」栏原因）；IT `OutboxDrillIT` 两例证投递与收敛 |
| A7-4 | 消费幂等 + DLQ 不回归 | `MqEndToEndIT`/`ClickIdempotencyIT`/`StatsApiIT` 全绿（CI）；旧 `mq-drill.sh` 实机 **DRILL OK**（收敛 25） |
| A7-5 | **sync=false 断 MQ 恢复后可追平** | 实机 `bash scripts/outbox-drill.sh` → **OUTBOX DRILL OK**（PASS outage: 10×302/PENDING=10/热路径明细=0；PASS recovery: aggregate=10, consumer-written detail=10, SENT=10, UV=1）；IT `syncOffBrokerDownStill302AndConvergesWithDetailAndUv` |
| A7-6 | 关 sync 明细/UV 语义文档化且行为一致 | README 管道节「关 sync 后的语义」；drill/IT 断言明细=10、UV=1 与文档一致 |
| A7-7 | 旧 Reconciler/开关无谎言 | 代码：sync 关时 reconcile 直接 return；README 同口径 |
| A7-8 | N1+N2 已做、文档完整 | 本节 + 分支首提交 + README/docker README |
| A7-9 | verify 全绿 + CI 绿 | 本机 23 单测绿；CI 全量 **79 例（23 单测 + 56 IT）0 失败**：分支终态 run [40079719914](https://github.com/NorthAdb/LinkForge/actions/runs/40079719914) ✅、main run [40079879455](https://github.com/NorthAdb/LinkForge/actions/runs/40079879455) ✅（`Tests run: 56` IT + `Tests run: 23` 单测） |

### 验证命令与输出摘要（本机，2026-10-24）

- `D:/tools/apache-maven-3.9.9/bin/mvn -B test` → `Tests run: 23, Failures: 0`（IT 看 CI，Desktop 29.9.2 限制照旧）
- `bash scripts/outbox-drill.sh` → `PASS outage: 10 redirects stayed 302, outbox PENDING=10, hot-path detail=0, relay attempts=3 (deferred log lines: 6)` / `PASS recovery: aggregate=10, consumer-written detail=10, outbox SENT=10, UV=1` / **OUTBOX DRILL OK**；对账等式 3506=3506、outbox SENT 37（drill 10 + mq-drill 25 + smoke）
- `bash scripts/smoke.sh` → **SMOKE OK**；`bash scripts/mq-drill.sh` → **DRILL OK**（旧验收回归）
- **B10 压测（wrk t2 c10 30s，本机 Windows）**：sync 关 + Outbox 单插入热路径（测量期 relay 关隔离）＝ **20.1 QPS / 605 requests / 30s，0 非 2xx/3xx**。对照 bench.md：默认（sync 开，含明细写）13.3 QPS；旧「关 sync 零 DB 写」276 QPS 但断链即丢。结论：**每跳一次持久化插入换断链零丢失**；正式数字以 Linux 复测为准（本机 MySQL 提交 ~600ms 支配，见 P4 记录）

### 执行记录

- **实机演练抓到一处真实缺陷（已修）**：首跑恢复段 aggregate=20（10 点击双倍）。根因＝消费端回填的明细行没有 `log-{id}` consumed 事件，旧 Reconciler 把它们当未追平行、以全新 `log-{新行id}` 重放。双修：回填行打 `event_id` 标记（V2 ALTER）+ 扫描排除 + Reconciler 加 sync 门禁。复跑 OUTBOX DRILL OK。这坐实了 D-A7-5 的「sync 关时旧对账器必须让位」不是文档洁癖而是正确性要求。
- 开发期本地库操作（如实记录）：V2 在演练首启按旧 checksum 应用过一次，修复 V2 后本地 `DELETE flyway_schema_history v2 + DROP lf_outbox` 重放（**V2 未推送前**的操作，远端/CI 无此历史）；双计脏数据按 code 定向清除后等式还原。
- 演练脚本坑：`. ./.env` 不导出时，java 继承到宿主全局环境里一个 13 字符的旧 `JWT_SECRET` → WeakKeyException 启动失败；脚本改 `set -a` 导出（本脚本会亲自拉起应用，与只 curl 的旧脚本不同）。

---

## 深化 A8 维度预聚合（feat/deepen-a8-dimensions，发车基线 main@b8d4b63，2026-10-24）

范围：交接单 `记录追踪/05-阶段交接/LinkForge-phase-A8-dimensions-handoff.md`。设备/来源维度预聚合 + breakdown API + 控制台展示，**不在 302 热路径加任何计算**；开头消化 A7 审计 S1/N1–N6。范围外（Geo/ES/ClickHouse/A9/A10/外网 GeoIP/UV 算法/全量回填）未动。

### A7 审计项处置（对照交接单 §0.1）

| # | 处置 | 落点 |
|---|------|------|
| **S1** | **完整落地**：correlated confirm + mandatory returns（dev/test yml 全开）；`publish()` 阻塞至 broker ack（超时 5s），nack/路由不可达/超时抛异常 → Relay 保持 PENDING 退避重试；ack 后才 markSent | `RabbitClickEventPublisher` 重写；回归实证＝confirm 开启下 SMOKE OK + **OUTBOX DRILL OK**（投递链路无回归） |
| N1 | A7-3 证据行改指 drill 断链段与现存 IT 名 | progress A7 表已改 |
| N2 | 文首 tip 钉精确 `b8d4b63` | progress 文首已改 |
| N3 | **实现**：`relay-max-attempts`（默认 30，`LINKFORGE_MQ_RELAY_MAX_ATTEMPTS`）超限标 FAILED + 指标 `linkforge.outbox.failed`；README 写明手工重放 SQL | `ClickOutboxRelay` / `OutboxMapper.markFailed` |
| N4 | **文档登记**：多实例 Relay at-least-once（无 SKIP LOCKED），消费端 eventId 幂等兜底 | README 管道节 |
| N5 | **登记**：SENT 行永久保留，自动清理未实现（运维项，README 附定期清理 SQL） | README 管道节 |
| N6 | publisher 接口注释去 RocketMQ 字样，改中性「可替换实现 + 必须确认后返回」 | `ClickEventPublisher` |

### 决策记录（D-A8-1…8）

| # | 决策 | 理由 |
|---|------|------|
| D-A8-1 | 首版维度＝`device`（mobile/desktop/tablet/bot/unknown）+ `referer_host`（归一化 host，空→direct）；os/browser 不做 | 交接单最小闭环；加分项在验收满足前不碰（Laziness） |
| D-A8-2 | 解析只在消费成功路径（`consume` 幂等事务内）；热路径 `recordClick` 零改动 | 302 吞吐不受维度影响 |
| D-A8-3 | `lf_click_dim_daily`：UK `(link_id, stat_date, dim_type, dim_value)` 计数表；upsert 用 `ON DUPLICATE KEY UPDATE clicks=clicks+1`（计数器场景，与决策 #11 否决的「重复探测」不同用例，交接单 §2.3.1 明示） | V3 迁移；重放不双计（与 `lf_consumed_event` 同事务） |
| D-A8-4 | `GET /api/links/{code}/stats/breakdown?dim=device\|referer_host&days=1..90`，仅链主（复用 `detail` 404 语义），非法 dim 400 | 与既有 stats 同鉴权口径 |
| D-A8-5 | Geo 本阶段不做 | 交接单默认延期 |
| D-A8-6 | UA 解析＝**自写轻量确定性规则**（零依赖）：bot 优先（含 curl/wget/python-requests/java/okhttp 等 14 词）→ tablet → mobile → desktop → unknown；解析永不抛（null/垃圾串→unknown） | 桶粗但可控可测；yauaa/uap 引入大依赖不匹配项目体量；规则全部单测钉死 |
| D-A8-7 | 历史明细不回填；新点击自消费起进维度表 | 交接单推荐；progress/README 双登记 |
| D-A8-8 | 控制台 `showStats` 并行拉 stats+两维 breakdown，统计区渲染「设备/来源」两行（Top5 文本条） | 无图表库工程 |

### 改动清单

| 单元 | 内容 |
|------|------|
| V3 | `V3__click_dim_daily.sql` 建维度表；存量库 migrate 实测即得（本机启动日志 `Migrating schema … to version "3 - click dim daily"`）；V1/V2 未动 |
| 解析 | `stats/support/ClickDimensions`（纯函数，`device()/refererHost()/of()`）；单测 7 例钉死桶规则（bot 优先、www 剥离、垃圾输入 direct/unknown、超长截断 128） |
| 消费 | `ClickStatsService.consume` 幂等事务内追加维度 upsert；`ClickDimDailyMapper`（incrementClick / selectBreakdown） |
| API | `StatsController.breakdown`（dim 白名单 400、days 1..90、链主 404）；`DimBreakdown` DTO |
| 控制台 | `static/index.html` 统计区加设备/来源两行（esc 转义） |
| 清理扩展 | `StatsApiIT`/`ClickIdempotencyIT` 合成数据清理扩到 `lf_click_dim_daily`（按 linkId） |
| S1 等 | 见上表 |

### 验收勾选（对照交接单）

| # | 标准 | 证据 |
|---|------|------|
| A8-1 | V3 建表、migrate 绿、V1/V2 未改 | 实机启动日志（上表 V3 行）；CI 空库 V1→V2→V3 全跑 |
| A8-2 | 新点击消费后入维度、eventId 幂等 | IT `DimBreakdownIT.breakdownBucketsDeviceAndRefererHost`（5 类请求 → 桶值精确断言）；`ClickIdempotencyIT` 回归（重放不双计，清理扩展后仍绿） |
| A8-3 | breakdown 返回合理分桶、鉴权正确 | 实机：device=`bot 3/mobile 2/desktop 1`、referer_host=`direct 4/example.com 1/news.ycombinator.com 1`（与真实请求逐一吻合）；非链主 404、无 token 401、`dim=os` 400；IT 同断言 |
| A8-4 | 热路径零新计算、解析不炸消费 | `recordClick` 无改动（diff 可查）；`ClickDimensions` 永不抛（单测 null/控制字符/垃圾串） |
| A8-5 | 控制台可见两类维度 | 统计按钮渲染「设备/来源」两行（`showStats` 三路并行 fetch；实机同源接口手验） |
| A8-6 | README/progress/OpenAPI 完整、D-A8 表齐 | 本节 + README「维度分析」节 + 接口表新行；springdoc 自动收录新 endpoint |
| A8-7 | A7 N1+N2 已做、S1 已做、N3–N5 登记 | 见「A7 审计项处置」表 |
| A8-8 | verify 全绿 + CI 绿 | 本机 `mvn -B test` → **30 单测绿**（+7）；CI 全量 **88 例（30 单测 + 58 IT）0 失败**：分支 run [40085174839](https://github.com/NorthAdb/LinkForge/actions/runs/40085174839) ✅、main 合并 run [40085344679](https://github.com/NorthAdb/LinkForge/actions/runs/40085344679) ✅、两次竞态修复后终态 main run [40085979192](https://github.com/NorthAdb/LinkForge/actions/runs/40085979192) ✅ |
| A8-9 | 范围干净 | diff 复查：无 ES/外网 Geo/A9/A10；唯一新依赖＝无 |

### 验证命令与输出摘要（本机，2026-10-24）

- `D:/tools/apache-maven-3.9.9/bin/mvn -B test` → `Tests run: 30, Failures: 0, Errors: 0` + BUILD SUCCESS
- 实机（A8 jar + 存量库自动 V3）：注册/建链（`dimdemo24388`）→ 6 次带不同 UA/Referer 的跳转 → `totalClicks=6` → breakdown 输出见 A8-3 行（桶与请求逐一吻合）；`dim=os` → 400；无 token → 401
- 回归：`bash scripts/smoke.sh` → **SMOKE OK**；`bash scripts/outbox-drill.sh`（confirm 开启下）→ **OUTBOX DRILL OK**（PASS outage: attempts=3/deferred 7 行；PASS recovery: aggregate=10, detail=10, SENT=10, UV=1）

### 执行记录

- 收口提交后的 main CI 连续抓到两次**跨上下文竞态抖动**（同族三面相：兄弟上下文的健康 relay 抢标 SENT、健康消费者抢先回填明细），都在 `OutboxDrillIT` 的**时敏中间态断言**上（PENDING 恰等、热路径明细恰 0）。修法＝中间态断言改状态无关总行数或删除（确定性证据由 drill 承担，其独立实例无兄弟上下文），终态收敛由 await 证明。已在 main 直接修复（测试稳定性小改，保持 main 绿纪律）。

---

## 深化 A9 Bloom 防非法短码穿透（feat/deepen-a9-bloom，发车基线 main@9dd60df，2026-10-24）

范围：交接单 `记录追踪/05-阶段交接/LinkForge-phase-A9-bloom-handoff.md`。跳转热路径在限流后、DB 前加 Guava 进程内 Bloom（只存短码），负答权威 → 404 不查库；**禁用/过期/删除仍强读 DB**（决策 #8 语义不动）。开头消化 A8 审计 N1/N2/N3。范围外（A10 singleflight / B7 空值缓存 / B8 Caffeine / RedisBloom / 长链去重 Bloom / ES）未动。

### A8 审计项处置（对照交接单 §0.1）

| # | 处置 | 落点 |
|---|------|------|
| **A8-N1** | 文首 tip 补钉精确 `9dd60dfb24bf3204b47b98230b2e66166e43b09e` | 开工提交 `f6b7dbe` |
| **A8-N2** | 文首「本轮边界」改为「A8 已收口、本轮 A9 Bloom」 | 同上 |
| **A8-N3** | `FlywayBaselineIT` 遗留卷断言补 V3（一行 `anyMatch version=3`，顺手做掉而非书面延期） | 同上 |

### 决策记录（D-A9-1…8）

| # | 决策 | 理由 |
|---|------|------|
| D-A9-1 | Bloom **只存短码 `code`**；口径「不存在一定不存在；存在/误判 → 回源 DB」；与「长链去重 Bloom」（C159）明确是两个结构，禁混用 | 交接单 D-A9-1；README/本节双写 |
| D-A9-2 | **Guava `BloomFilter<String>`**（`Funnels.stringFunnel(UTF_8)`）进程内；`expectedInsertions=100000` + `fpp=1e-4` 可配；`ApplicationReadyEvent` 全量 `SELECT code` 装载；创建成功 `put`；多实例各自装载（Demo 姿态，跨实例新建码的假阴性限制与 RedisBloom 后置路线登记 README） | 交接单推荐默认；RedisBloom 需 Redis Stack 模块，不为本阶段引入 |
| D-A9-3 | 插入点＝`resolveTarget` **限流之后、缓存与 DB 之前**；`mightContain==false` → 404 + 指标 `linkforge.bloom.rejected`；true → 维持既有 Redis+DB 路径 | 交接单 D-A9-3；放在缓存读之前连 Redis GET 一并省掉（强于「仅不打 MySQL」） |
| D-A9-4 | 假阳性 → 一次 DB miss → 404，正确性不变；用 Guava 标准实现（不自造 bitset，杜绝假阴性） | 交接单 D-A9-4 |
| D-A9-5 | 禁用：行仍在，Bloom 保持正答 → 回源 DB → 404；删除是**逻辑删除**（行保留、码位不可复用），已删码仍装进 Bloom（播种 SQL 绕过 @TableLogic）→ 回源 DB（deleted=0 过滤）→ 404；经典 Bloom 不可回收（rebuild 后置）；**不**为删除引入会假阴性的粗糙结构（A10 起按审计 A9-N4 更正措辞） | 交接单 D-A9-5 |
| D-A9-6 | `linkforge.bloom.enabled`（`LINKFORGE_BLOOM_ENABLED`）开关；未就绪/装载失败 **fail-open** 走旧路径（warn + 指标 `linkforge.bloom.loadfailed`），Bloom 故障不产生 5xx | 交接单 D-A9-6 |
| D-A9-7 | `LINKFORGE_BLOOM_EXPECTED_INSERTIONS=100000` / `LINKFORGE_BLOOM_FPP=0.0001`；超预期插入只会**劣化误判率（多回源，不会漏）**写进 README；启动日志打 seeded 数与 approxSize | 交接单 D-A9-7 |
| D-A9-8 | Bloom 不替代 URL Redis 缓存、不存 status/expire；A10/B7/B8 本阶段不做（仅在「下一步」提名） | 交接单 D-A9-8 |

### 改动清单

| 单元 | 内容 |
|------|------|
| 依赖 | pom 新增 `com.google.guava:guava:33.4.8-jre`（Boot BOM 不管理，显式版本属性）——本轮唯一新依赖 |
| 组件 | `link/support/CodeBloomFilter`：`load()`（ApplicationReadyEvent 全量装载，异常 fail-open 计数）、`mightContain()`（disabled/未装载/装载失败一律 true）、`put()`、`approximateElementCount()` |
| 热路径 | `ShortLinkService.resolveTarget` 限流后短路（rejected 指标）；`doCreate` 插入成功后 `put(code)`；`recordClick` / Outbox / 维度消费**零改动** |
| 查询 | `ShortLinkMapper.selectAllCodes()`（`@Select` 仅 code 列） |
| 配置 | `LinkForgeProperties.Bloom`（enabled / expectedInsertions / fpp）+ application.yml 三个 env 透传 |
| 测试 | `CodeBloomFilterTest` 5 例（装载正/负答与 loaded 计数、创建后 put、关闭不装载且恒真、装载失败 fail-open、未装载 fail-open）；`BloomRedirectIT` 2 例（未知码 rejected++ 且 HEAD 同档、建链 302、禁用后 404；开关关闭＝A8 行为且不计数） |
| 文档 | README 新「防穿透（A9 Bloom）」节；接口表 GET `/r/{code}` 行、跳转时序（①限流 → ②Bloom 门 → ③缓存 → ④DB）、核心路径表、配置要点、指标清单同步 |

### 验收勾选（对照交接单）

| # | 标准 | 证据 |
|---|------|------|
| A9-1 | DB 前 Bloom 短路；否定 → 404 不查库 | 代码（resolveTarget 短路在 selectOne 前）；实机 `/actuator/metrics/linkforge.bloom.rejected`＝**1.0**，恰对应手验中唯一一次随机码 GET；IT 断言 rejected 计数随未知码请求增长 |
| A9-2 | 创建后入 Bloom；正常/禁用/过期语义与 A8 一致 | 实机建链 `WdODzWG` → 302、disable 后 → 404；`smoke.sh` 8/8（含 create→redirect 与 disable→404）；过期语义由既有 `LinkLifecycleIT` 经同一 resolveTarget 回归；IT 同断言 |
| A9-3 | 误判与删除限制写清、口径自洽 | README「防穿透（A9 Bloom）」节（负答权威/误判回源/删除不回收/多实例限制/容量劣化方向）+ 本节 D-A9-3/4/5 |
| A9-4 | `enabled=false` / 装载失败 fail-open 不拖垮跳转 | `CodeBloomFilterTest` 两例（关闭、装载失败均恒 true）+ `BloomRedirectIT.bloomDisabledFallsThroughToUnchangedDbPath`（404 照旧但不计数、建链照常 302） |
| A9-5 | 未与长链去重混淆；status 不进 Bloom | 组件只收 code 一个维度（代码可查）；README 与 D-A9-1/D-A9-8 声明 |
| A9-6 | A8-N1+N2 已做；N3 已做 | 开工提交 `f6b7dbe`（见「A8 审计项处置」表） |
| A9-7 | README/progress 完整；D-A9 决策表齐 | 本节 + README 五处同步 |
| A9-8 | verify 全绿 + CI 绿 | 本机 `mvn -B test` → **35 单测绿**（+5）；CI 全量 **95 例（35 单测 + 60 IT）0 失败**：分支 run [40088373910](https://github.com/NorthAdb/LinkForge/actions/runs/40088373910) ✅、main 合并 run [40088526167](https://github.com/NorthAdb/LinkForge/actions/runs/40088526167) ✅ |
| A9-9 | 范围干净 | diff 复查：无 A10/Caffeine/空值缓存主路径/ES/长链去重 Bloom；唯一新依赖 guava；无新 migration（Flyway 仅 validate）；UV/Outbox/维度语义未动；`recordClick` 未改 |

### 验证命令与输出摘要（本机，2026-10-24）

- `D:/tools/apache-maven-3.9.9/bin/mvn -B test` → `Tests run: 35, Failures: 0, Errors: 0` + BUILD SUCCESS
- 实机（kill 旧 A8 进程 → `mvn -B package -DskipTests` → 新 jar 重启）：启动日志 `bloom seeded with 886 codes (expectedInsertions=100000, fpp=1.0E-4, approxSize=885)`；Flyway `Successfully validated 4 migrations`（无新版本，纯 validate）
- 手验：随机码 GET → 404；建链 `WdODzWG` → 302；disable 后 GET → 404；`linkforge.bloom.rejected`＝1.0（恰等于一次未知码请求）、`linkforge.bloom.loaded`＝1.0
- 回归：`bash scripts/smoke.sh` → **SMOKE OK**（health/login/create/redirect/redis/register/lifecycle/disable→404 全过）

### 执行记录

- 开工提交先消化 A8 三条 nit（含 N3 从「可书面延期」升级为直接补断言），再按单元推进：核心（Guava+组件+接线，编译绿）→ 测试（本地 35 绿）→ 实机四态手验 + 指标核对 → 文档。
- **分支 CI 一红一绿**：首跑 40088052132 在 `RegisterRateLimitIT` 上下文加载失败（`Too many connections`）——共享 Testcontainers MySQL 默认 `max_connections=151`，套件每多一个独立上下文（本轮新增 `BloomRedirectIT`）就多一份按需 Hikari 池，A8 末已贴边，本轮被推过线。修法＝共享容器加 `--max-connections=500`（一行，不动池语义），重跑 40088373910 绿。新测试本身全绿（BloomRedirectIT 2/2 首跑即过）。**教训入册**：与「跨上下文竞态」同族——共享 Testcontainers MySQL 是套件的公共容量池，新增 IT 上下文前要算连接预算。
- 实机重启时旧后台启动任务随 kill 退出（预期）；A9 jar 启动一次成功，无回滚无重试。

### 下一步（不混入本轮）

- A10 singleflight（防击穿）、B7 空值缓存 + TTL 抖动、B8 Caffeine L1；Bloom 共享化（RedisBloom）与删除回收（定期 rebuild）后置，均需萧北指令再开。

---

## 深化 A10 singleflight 防热点击穿（feat/deepen-a10-singleflight，发车基线 main@ccff802，2026-10-24）

范围：交接单 `记录追踪/05-阶段交接/LinkForge-phase-A10-singleflight-handoff.md`。同码并发 DB 行读合并为一次在途读（进程内 singleflight），**禁用/过期/删除仍以 DB 行为准**（决策 #8 不动）；开工消化 A9 审计 S1/S2/N1–N4，其中 S2 按萧北口径落 V4（`utf8mb4_0900_bin`）。范围外（B7 空值缓存 + TTL 抖动 / B8 Caffeine / 分布式锁 / XFetch 实现 / RedisBloom / Bloom rebuild / 长链去重 Bloom / ES）未动。

### A9 审计项处置（对照交接单 §0.1）

| # | 处置 | 落点 |
|---|------|------|
| **A9-S1** | **完整落地**：`load()` 改为「先 `filter = fresh`（ready 仍 false → mightContain 照样 fail-open）→ 再 `selectAllCodes()` 逐条 put → 最后 `ready = true`」，装载窗口内的创建 `put` 直落 fresh（Guava 位数组并发安全）；`loadfailed` 分支补 `filter = null`，不留半装 filter | `CodeBloomFilter`；单测 `putsDuringTheLoadWindowAreNotLost`（mock answer 内 put → 装载后正答）。时序说明：filter 先发布、ready 后置，两段 volatile 写保证窗口内「要么 fail-open、要么已包含新 put」 |
| **A9-S2** | **V4 落地**（萧北已定口径）：`V4__short_code_case_sensitive.sql` 把 `lf_short_link.code` 改为 `utf8mb4_0900_bin`（NO PAD，非 `utf8mb4_bin` 的 PAD SPACE）；关联表不改（冗余快照、零比较场景）；文件头写明选型理由与「跨表关联一律 link_id」规则；README 自定义短码段 + `/r/{code}` 行 + OpenAPI（`/r/{code}` 参数与 `customCode`）加「区分大小写」；保留字检查仍不区分大小写 | V4 + IT（Bloom 开/关两上下文都 404）+ `FlywayBaselineIT` 断言 V4 + collation |
| A9-N1 | README「已知限制」多实例句改为审计建议原文（真阴性表述） | README 防穿透节 |
| A9-N2 | `doCreate` 改为「insert 成功 → `codeBloom.put` → `cacheUrl`」，先落库再登记存在性 | `ShortLinkService.doCreate` |
| A9-N3 | 新增 `BloomDisabledAtStartupIT`（`properties = "linkforge.bloom.enabled=false"`）：断言 `isReady()==false`、`loaded`/`rejected` 未注册、未知码 404、建链 302；同上下文承载 S2 的 Bloom 关大小写用例 | 新 IT |
| A9-N4 | README 防穿透节与 progress D-A9-5「物理删除」全部更正为「逻辑删除（行保留、码位不可复用）；播种 SQL 故意绕过 @TableLogic，已删码保持正答 → 回源 DB（deleted=0 过滤）→ 404」；`ShortLinkMapper.selectAllCodes` 注释写明绕过是故意的 | README + progress + mapper 注释 |

### 决策记录（D-A10-1…10）

| # | 决策 | 理由 |
|---|------|------|
| D-A10-1 | 只合并 `resolveTarget` 第 ④ 步「code → 行」；**Redis 命中与否都走合并**（命中时也读库，§0.0）；限流/Bloom/Redis GET/状态判定/回填/`recordClick` 留在各请求线程；过期判定用本请求 `now()`，BizException 结论不共享 | 交接单 D-A10-1；本项目不是经典击穿形状，合并对象是每请求读行 |
| D-A10-2 | `link/support/SingleFlight<K,V>`：`ConcurrentHashMap` + `putIfAbsent`（**禁用** computeIfAbsent 包阻塞调用）；领头在自己线程同步执行 loader，`complete/completeExceptionally` 交结果，`finally remove(key, future)` 两参摘除；完成即摘除，**不是结果缓存**（无 TTL、无最近值、null 不缓存） | 交接单 D-A10-2；JDK 并发包，零新依赖 |
| D-A10-3 | key＝原样 code（区分大小写）；**先 V4 后 singleflight**，无「大小写两路 flight」中间态 | 交接单 D-A10-3；与 V4/Bloom 口径一致 |
| D-A10-4 | 跟车超时 `linkforge.singleflight.wait-timeout-ms`（`LINKFORGE_SINGLEFLIGHT_WAIT_TIMEOUT_MS`，默认 500）→ **自读退化** + `timeout` 计数；领头异常原样传给同批跟车（与各自读库失败等价）、条目摘除下轮重新领头；不自动重试；中断恢复标志后退化为自读 | 交接单 D-A10-4 |
| D-A10-5 | 强一致口径保留：每请求拿到一次真实 DB 读，跟车共享的是一次**在途**读（窗口毫秒级 < 1s 双删）；`update`/`setStatus`/`delete` 写后 `forget(code)`；合并器不跨请求缓存行 | 交接单 D-A10-5；写后 forget 有 IT 钉住 |
| D-A10-6 | Bloom 门在合并器之前；误判/已删码的并发 miss 也合并但 null 不缓存（B7 后置） | 交接单 D-A10-6 |
| D-A10-7 | Redis 回填保持每请求自行「cached 空才 SET」（同值幂等）；不做领头回填（需传递领头标志，收益为零重复 SET） | 交接单 D-A10-7 可选项的取舍 |
| D-A10-8 | `linkforge.singleflight.enabled`（默认 true）+ 嵌套配置类 + yml env 透传；指标 `loads`（领头尝试）/`shared`（跟车成功复用）/`timeout`（退化自读）；关闭时不注册计数器 | 交接单 D-A10-8 |
| D-A10-9 | S2＝V4 `utf8mb4_0900_bin`（NO PAD，萧北 2026-10-24 已定）；只改 `lf_short_link.code`；跨表按 link_id 关联规则写进 V4 头注释 | 交接单 D-A10-9（§1.1 四条理由） |
| D-A10-10 | 进程内合并，多实例最坏「每实例每窗口 1 次」；不引入分布式锁（只会串行化读、拉长尾延迟）；README 一句话写明取舍 | 交接单 D-A10-10 |

### 改动清单

| 单元 | 内容 |
|------|------|
| V4 | `V4__short_code_case_sensitive.sql`（ALTER MODIFY collation；存量库实测 `Migrating schema to version "4 - short code case sensitive"`）；V1–V3 未动 |
| Bloom 收口 | `CodeBloomFilter.load()` 顺序调换（S1）+ `loadfailed` 清 filter；`doCreate` put 前置（N2）；`selectAllCodes` 注释（N4） |
| 合并器 | `link/support/SingleFlight`（泛型组件，约 90 行）+ `link/support/ShortLinkRowLoader`（薄 Bean 包 selectOne，供测试 spy） |
| 接线 | `ShortLinkService.resolveTarget` ④ 改 `singleFlight.run(code, () -> rowLoader.load(code))`；`update`/`setStatus`/`delete` 后 `forget(code)` |
| 配置 | `LinkForgeProperties.SingleFlight`（enabled/waitTimeoutMs）+ application.yml 两个 env |
| 测试 | `SingleFlightTest` 5 例（单测，CountDownLatch 卡领头 + 线程态 await 定等）；`SingleFlightRedirectIT` 3 例（@SpyBean 延迟 300ms 制造重叠：24 并发恰 1 次读 + loads/shared 增量、关闭对照 12 次全读且不计数、disable 在途窗口后的 GET 必 404）；`BloomRedirectIT` 大小写用例 + `BloomDisabledAtStartupIT` 2 例；`FlywayBaselineIT` V4 + collation 断言；`CodeBloomFilterTest` +1（S1） |
| 文档 | README 新「防击穿（A10 singleflight）」节（真实形状/窗口/forget/退化/多实例/无分布式锁/mutex-singleflight-XFetch 对照）+ 跳转时序 ④ + 配置要点 + 指标清单 + 自定义短码「区分大小写」 |

### 验收勾选（对照交接单）

| # | 标准 | 证据 |
|---|------|------|
| A10-1 | DB 行读经合并器、命中与否都合并、其余步骤各请求自理 | 代码（resolveTarget ④ = `singleFlight.run`，其余步骤原位）；`recordClick` 零 diff |
| A10-2 | 并发 IT：N≥20 并发同码全 302、DB 读恰 1 次、loads=1/shared=N−1；关闭对照 N 次 | `SingleFlightRedirectIT.concurrentRedirectsShareExactlyOneRowRead`（24 并发 + spy 300ms 延迟；rowReads=1、loads 增量 1、shared 增量 23）；`mergeDisabledEveryRequestReadsItsOwnRow`（12 并发 rowReads=12、loads 不涨） |
| A10-3 | 合并器单测五态 | `SingleFlightTest`：恰 1 次 loader+计数、异常传播同批跟车且下轮重新领头、超时自读+timeout 计数、完成后无残留、forget 生效 |
| A10-4 | 降级路径不产生额外 5xx/404 | 超时自读（单测）；领头异常传播（单测）；关闭=A9 行为（单测 + 实机 `LINKFORGE_SINGLEFLIGHT_ENABLED=false` 重启：valid 302/unknown 404、loads 未注册） |
| A10-5 | 禁用/过期/删除语义不变；写路径 forget；窗口口径成文；无行缓存 | `LinkLifecycleIT`/`BloomRedirectIT` 回归绿（disable→404、enable→302、过期→410）；实机 disable/enable 手验；`disableDuringInFlightReadIsNotRiddenByLaterRequests` 钉住写后不跟车；组件无 TTL/无最近值（代码审） |
| A10-6 | A9-S1/N1/N2/N4 已修、N3 有 IT | 见「A9 审计项处置」表 |
| A10-7 | S2：V4 + collation + 双上下文 404 + 大小写共存 + 保留字 + 基线断言 + 文档 | 实机 information_schema 三列核对（short_link=0900_bin，click_log/click_daily=0900_ai_ci）；建 `A10x…` → 302、小写变体 → 404（Bloom 开 rejected、Bloom 关 DB miss 两 IT）；`FlywayBaselineIT` 断 V4 + collation；`LinkValidationsTest` 保留字用例保持绿；README/OpenAPI/progress 成文 |
| A10-8 | README/progress 完整、连接预算一行 | 本节 + README；预算：既有 ~21 上下文 + 本轮 2 个新上下文（BloomDisabledAtStartupIT、SingleFlightRedirectIT，均设 `hikari.maximum-pool-size=4`）→ 峰值连接 ≈ 21×10+2×4=218 < 500，未动 `--max-connections` |
| A10-9 | verify 全绿 + CI 绿、测例增量 | 本机 `mvn -B test` → **41 单测绿**（+6：SingleFlightTest 5 + S1 用例 1）；CI 全量 **107 例（41 单测 + 66 IT）0 失败**（+12 vs A9 的 95）：分支 run [40092974948](https://github.com/NorthAdb/LinkForge/actions/runs/40092974948) ✅（一次绿）、main 合并 run [40093152270](https://github.com/NorthAdb/LinkForge/actions/runs/40093152270) ✅（head `fc02f5a`） |
| A10-10 | 范围干净 | diff 复查：无空值缓存/TTL 抖动/Caffeine/分布式锁/XFetch 实现/RedisBloom/长链 Bloom；V1–V3 未改；`recordClick`/Outbox/维度/UV 零 diff；**零新依赖**（JDK 并发包） |

### 验证命令与输出摘要（本机，2026-10-24）

- `D:/tools/apache-maven-3.9.9/bin/mvn -B test` → `Tests run: 41, Failures: 0, Errors: 0` + BUILD SUCCESS
- 实机（kill 旧进程 → 新 jar 重启）：`Migrating schema to version "4 - short code case sensitive"`；`bloom seeded with 889 codes`；Flyway validate 5 migrations
- information_schema：`lf_short_link.code=utf8mb4_0900_bin`，`lf_click_log/lf_click_daily.code=utf8mb4_0900_ai_ci`（关联表未动，实锤）
- 大小写：建 `A10x1792843534` → 302；小写变体 → 404（Bloom 拒）；`LINKFORGE_SINGLEFLIGHT_ENABLED=false` 重启后小写变体仍 404（V4 是 DB 级语义，与开关无关）
- 并发：xargs -P 50 同码 GET → **50/50 全 302**，`loads` 1→38、`shared` 0→13、`timeout` 未注册（无 5xx；未加延迟时重叠量天然不稳定，确定性证据归 IT 的 spy 延迟窗口）
- disable → 404、enable → 302；`bash scripts/smoke.sh` → **SMOKE OK**（8/8）
- 回归：`bash scripts/mq-drill.sh` → **DRILL OK**；`bash scripts/outbox-drill.sh` → **OUTBOX DRILL OK**（PENDING=10/hot-path detail=0/attempts=2 → recovery aggregate=10, detail=10, SENT=10, UV=1）

### 执行记录

- 开工按交接单顺序：A9 收口（S1/N1/N2/N4）→ V4（S2 + N3 上下文）→ singleflight 组件与接线 → 测试 → 实机 → 文档。每单元本地编译/单测绿后提交。
- 本地单测两轮红均为测试自身断言写错（`v0/v1` 计数错位、无残留断言期望值写反），组件行为正确；修正后 41/41 绿。`Thread.ofPlatform()`（JDK 21 API）替换为 JDK 17 兼容写法一次。
- 手验过程重启应用三次（正常 / singleflight-off / 正常），V4 只在首次启动迁移，后续 validate。
- **CI 分支一次绿**（连接预算生效，无 A9 那轮的容量事故）；main 合并与 docs 提交全绿。本轮无竞态抖动、无回滚。
- 代码合并点 `fc02f5a`；收口内容提交（本节所在的 docs 提交）由文首钉子提交钉住完整 SHA，审计以 `git rev-parse HEAD` 为准，钉点必为其祖先（A8-N1 家族的结构化解法，A9 起沿用）。

### 下一步（不混入本轮）

- B7 空值缓存 + TTL 抖动、B8 Caffeine L1、XFetch 实现、RedisBloom 共享、Bloom 定期 rebuild、长链去重 Bloom、B10 全量 bench，均需萧北指令再开。

---

## B 档波 1：B7 空值缓存+TTL 抖动 / B8 Caffeine L1（feat/deepen-b-wave1-b7b8，发车基线 main@f8130cf，2026-10-24）

范围：交接单 `记录追踪/05-阶段交接/LinkForge-phase-B-wave1-b7b8-handoff.md`。热路径补齐「穿透负缓存（B7）+ TTL 抖动 + 进程内 L1（B8）」，开工消化 A10 审计 N1。**跳转仍是每请求读 DB 行判状态**（决策 #8/A10 叙事不动），L1 与空值缓存都不构成「跳过 DB 的行缓存」。范围外（波 2–5 / 行级 L1 / absent 进 Caffeine / 失效广播 / RedisBloom / V5）未动。

### A10 审计项处置（对照交接单 §0.1）

| # | 处置 | 落点 |
|---|------|------|
| **A10-N1** | **本波默认修**：`SingleFlight` 新增 `peek(key)`（写前只读窥视）+ `forget(key, priorSnapshot)`（两参 `remove`，快照不符即 no-op，null 快照 no-op）；写路径 `update`/`setStatus`/`delete` 改为「写前快照 → 提交 → 按快照摘除」，**不会误摘写后新领头**。残留窗口（快照与 commit 之间才领头的 flight 仍读旧行并被摘除）与 A10 已接受的毫秒窗口同阶，单测钉住两个方向 | `SingleFlight` + `ShortLinkService` 三处写路径；单测 `forgetWithStaleSnapshotSparesThePostWriteFlight`（新 flight 存活且跟车拿到新行）、`forgetWithNullSnapshotIsANoOp` |

### 决策记录（D-Bw1-1…12）

| # | 决策 | 理由 |
|---|------|------|
| D-Bw1-1 | 热路径终序：①限流 → ②Bloom → ③absent 探查 → ④L1 → ⑤Redis L2 → ⑥singleflight 行读 → ⑦状态/过期判定（本请求时钟）→ ⑧选 URL → ⑨回填 L1+L2；DB null 写 absent 后 404。HEAD/GET 共用 | 交接单 D-Bw1-1 |
| D-Bw1-2 | absent 只缓存「DB 行读取结果为 null」；禁用 404 / 过期 410 / URL 不可渲染 410 **不写** absent | 行仍在的码启用/改期须立即生效（IT 钉住） |
| D-Bw1-3 | 独立 key `linkforge:absent:{code}`（哨兵 `"1"`），不污染 `linkforge:code:{code}`；absent 不做 Caffeine 层 | 交接单推荐默认 |
| D-Bw1-4 | Bloom 负答不读不写 absent；DB null 经 singleflight 返回后写 absent（计 put）再 404；`doCreate` 成功 DEL absent；`delete` 后下次回源 null 自然再写 | 交接单 D-Bw1-4 |
| D-Bw1-5 | TTL 公式 `base + uniform[0, jitter]`（只向上抖，`jitteredTtlSeconds` 静态函数）：正缓存 `3600+300`（`LINKFORGE_CACHE_TTL_JITTER_SECONDS`）、空值 `60+30`；jitter≤0 退化为裸 base（单测钉死） | 交接单 D-Bw1-5 |
| D-Bw1-6 | L1 **只缓存 URL 字符串**（与 L2 同形状），命中**不跳过** singleflight/DB；禁「行级 L1 跳过 DB」 | 交接单 D-Bw1-6；接 A10 强一致叙事 |
| D-Bw1-7 | 手写 `link/support/UrlLocalCache`（`Caffeine.newBuilder().maximumSize().expireAfterWrite()`），不走全局 `@Cacheable`（避免扫到管理 API）；`maximum-size=10000`、`ttl-seconds=10`（≪ Redis TTL）；cacheUrl 写穿 L1+L2、evictCache/延迟双删同触 L1 | 交接单 D-Bw1-7 推荐手写小组件 |
| D-Bw1-8 | L1 进程内不同步（无 pub/sub）：跨实例旧 URL 窗口 ≤ L1 TTL（10s），与 Redis 双删窗口叠加取更长者；absent 在 Redis 多实例共享；README 写明 | 交接单 D-Bw1-8 诚实口径 |
| D-Bw1-9 | 指标：`linkforge.cache.absent.hit` / `.put`、`linkforge.l1.hit` / `.miss`（L1 关闭时不计数）；README 清单同步 | 交接单 D-Bw1-9 |
| D-Bw1-10 | A10-N1 默认修（见上表） | 交接单 §0.1 |
| D-Bw1-11 | **零新 migration**（V1–V4 零改）；pom 新增 `com.github.ben-manes.caffeine:caffeine`（**Boot BOM 管理版本，不手钉**）；Guava 保留给 Bloom | 交接单 D-Bw1-11 |
| D-Bw1-12 | fail-open：absent 探查异常当 miss、写入异常吞掉 warn、L1 内部不抛（getIfPresent/put/invalidate 全守卫）；与 Bloom/singleflight 口径一致 | 交接单 D-Bw1-12 |

### 改动清单

| 单元 | 内容 |
|------|------|
| A10-N1 | `SingleFlight.peek/forget(两参)` + `ShortLinkService` 三处写路径 + 2 单测 |
| B7 | `ABSENT_KEY_PREFIX`/哨兵常量；`absentKnown`/`markAbsent`/`clearAbsent`（fail-open）；`resolveTarget` ③探查（hit 计数→404）与 ⑥null→markAbsent→404（与 status!=1 拆分）；`doCreate` 成功后 `clearAbsent` |
| 抖动 | `ShortLinkService.jitteredTtlSeconds`（static 包私有）+ `cacheUrl` 改抖动 TTL；`ShortLinkTtlTest` 3 例 |
| B8 | pom caffeine（BOM 版本）；`LinkForgeProperties.L1`（enabled/maximumSize/ttlSeconds）；`UrlLocalCache`；`resolveTarget` L1→L2 两级读；`cacheUrl`/`evictCache` 接 L1 |
| 配置 | `ShortLink` 三字段 + `L1` 块 + application.yml 五个 env |
| 测试 | `BloomDisabledAtStartupIT` +@SpyBean+absent 命中跳 DB 用例；`BloomRedirectIT` +6（Bloom 负答不写 absent、disable 不写+enable 立即 302、过期不写、delete 写 absent+重复 miss 跳 DB、L1 命中+update 写穿、L1 关闭对照） |
| 文档 | README 新「多级缓存」节 + 跳转时序 ①–⑨ + 配置要点 3 行 + 指标清单；顺手修 A10 遗留错字「并赃合并」 |

### 验收勾选（对照交接单 Bw1-1…11）

| # | 标准 | 证据 |
|---|------|------|
| Bw1-1 | 顺序符合 D-Bw1-1；HEAD/GET 共用 | 代码（resolveTarget ①–⑨ 顺序可读）；resolveForProbe/resolveAndRecord 共用 |
| Bw1-2 | DB null 才写 absent；禁用/过期不写；enable 立即 302 | IT `disableDoesNotEnterAbsentAndEnableRecoversImmediately`（put 增量 0 + enable 立即 302）、`expiredDoesNotEnterAbsent`（410 后 put 增量 0）；`deletedCodeWritesAbsentAndRepeatMissesSkipTheDb`（delete → put=1） |
| Bw1-3 | 独立 key 不污染正缓存；Bloom 负答不依赖 absent | `bloomNegativePathNeverWritesTheAbsentCache`（put 增量 0）+ 代码（bloom 门在 absent 探查前 return） |
| Bw1-4 | TTL 公式 base+uniform[0,jitter]；jitter=0=base | `ShortLinkServiceTtlTest` 3 例（0/负退化、5000 次范围+离散度） |
| Bw1-5 | L1 只存 URL；命中不跳过 DB/singleflight；cacheUrl/evict/双删同触 | `UrlLocalCache` 泛型即 String；resolveTarget L1 命中后仍 singleflight.run；cacheUrl/evictCache 双写 |
| Bw1-6 | update 写穿可见；disable/delete 后 L1 无旧正缓存生效 | IT `localCacheServesRepeatReadsAndInvalidatesOnUpdate`（update 后 Location=v2）；disable 路径 evictCache→L1 invalidate（LinkLifecycleIT 回归绿） |
| Bw1-7 | A10-N1 两参/快照语义 + 单测 | 见「A10 审计项处置」表 |
| Bw1-8 | 开关与 fail-open | `localCacheDisabledFallsBackToRedisOnlyPath`（关闭不计数、走 Redis/DB）；absent TTL≤0 关闭（代码）；absent 探查/写入 fail-open（代码 + 单测层面无异常泄漏） |
| Bw1-9 | README/progress 完整、无夸大 | 本节 + README「多级缓存」边界声明（仍每请求读行、不称防雪崩到分布式） |
| Bw1-10 | verify 全绿 + CI 绿、钉子口径 | 本机 `mvn -B test` → **46 单测绿**（+5：SingleFlightTest 2 + ShortLinkServiceTtlTest 3）；CI 全量 **119 例（46 单测 + 73 IT）0 失败**（+12 vs A10 的 107）：分支 run [40098112881](https://github.com/NorthAdb/LinkForge/actions/runs/40098112881) ✅（首跑 [40097755976](https://github.com/NorthAdb/LinkForge/actions/runs/40097755976) 红，见执行记录）；main 合并 [40098700470](https://github.com/NorthAdb/LinkForge/actions/runs/40098700470) ✅（其前的合并 run [40098318604](https://github.com/NorthAdb/LinkForge/actions/runs/40098318604) 红＝过期用例定时竞态，已 de-flake 直修 main `5f610ee`） |
| Bw1-11 | 范围干净 | diff 复查：无行级 L1/RedisBloom/广播/波 2+；V1–V4 SQL 零改、无 V5；`stats/**` 零 diff（除无）；新依赖仅 caffeine（BOM 版本） |

### 验证命令与输出摘要（本机，2026-10-24）

- `D:/tools/apache-maven-3.9.9/bin/mvn -B test` → `Tests run: 49, Failures: 0` + BUILD SUCCESS
- 实机手验（见执行记录）；`bash scripts/smoke.sh` → **SMOKE OK**；`bash scripts/mq-drill.sh` / `outbox-drill.sh` → 收口补记
- 连接预算：**零新增上下文**（absent/L1 用例全部挂在既有 `BloomRedirectIT` / `BloomDisabledAtStartupIT` 上下文内），峰值连接与 A10 持平 ≈218 < 500

### 执行记录

- 提交序：A10-N1（3689cf5）→ B7（a692128）→ B8（ff76d50）→ IT（2f00d70）→ docs。每单元本地编译/单测绿后提交。单测总数实测 46（41 → +5）。
- 本地单测红两次：A10-N1 测试缺 `CompletableFuture` import（已 amend 到绿提交）、IT 缺 `assertTrue` import（补后绿）。
- **分支 CI 一红一绿**：首跑 40097755976 在 `LinkLifecycleIT.invalidLegacyTargetReturns410WithoutCountingClick` 红（期望 410 得 302）——B8 后 L1 仍持旧合法 URL，而该测试绕过服务直改 DB 行为脏 URL 且只清了 Redis，L1 命中把脏行遮蔽成 302。这是 B8 引入的真实语义缺口：修法＝**行级 URL 校验门**（`resolveTarget` 中行的 `original_url` 不可渲染一律 410，与缓存无关，`134301a`），README「多级缓存」登记为 B8 护栏。重跑 40098112881 绿。
- **main 合并后又一红**：`BloomRedirectIT.expiredDoesNotEnterAbsent` 期望 410 得 302——MySQL DATETIME(0) 对小数秒**四舍五入**，测试以 `now()+1s` 建 expireAt 时带小数会被入库成最多 +1s，`sleep(1300)` 不保证跨过舍入边界（分支跑赢、主跑输的定时竞态，与跨上下文竞态同族「时敏断言」教训）。修法＝测试直接改行 `expire_at` 为过去（`5f610ee`，main 直修），消除定时依赖后 main 绿。
- 单测例数 41 → 46：SingleFlightTest +2（快照 forget 两态）、ShortLinkServiceTtlTest +3（抖动）。IT 66 → 73：BloomDisabledAtStartupIT +1（absent 命中跳 DB）、BloomRedirectIT +6。

### 下一步（不混入本波）

- 波 2+：B4/B5/B2/B6/B1/B10/B3/B9，均需萧北指令再开。

---
## B 档波 2：B4 API Key + 配额 / B5 点击 Webhook（feat/deepen-b-wave2-b4b5，发车基线 main@05f6738，2026-10-24）

范围：交接单 `记录追踪/05-阶段交接/LinkForge-phase-B-wave2-b4b5-handoff.md`。多租户从「数据隔离」到「可集成」：只存哈希的 API Key + Key 级配额；点击事件经 A7 Outbox 链以 HMAC 签名推送到租户 Webhook，302 热路径零新增。Q1（AES-GCM+env 主密钥）/ Q2（逐次点击）/ Q3（Key 绑创建者）已由萧北拍板。开工消化 B 波 1 审计 N1/N2。范围外（Resilience4j / OAuth / 管理 UI / 聚合推送 / 租户级 Key / 波 3–5 / V1–V4 改动）未动。

### B 波 1 审计项处置（对照交接单 §0.1）

| # | 处置 | 落点 |
|---|------|------|
| **Bw1-N1** | **已修**：`resolveTarget` 在行判定全部通过（含行级 URL 校验门）之后，Redis-only 命中即 `urlLocalCache.put` 回填 L1——不重写 Redis、不改 TTL、**仍不跳过** singleflight/DB 行读 | `ShortLinkService` + IT `redisOnlyHitBackfillsTheLocalCache`（invalidate L1 后一次 GET → getIfPresent 非空） |
| **Bw1-N2** | **已修**：`SingleFlight` 类注释改为现状（负缓存在 `ShortLinkService` 的 `linkforge:absent:{code}`，本组件不缓存结果；写路径用 peek 快照 + 两参 forget） | `SingleFlight` 头注释 |

### 决策记录（D-Bw2-1…21）

| # | 决策 | 理由 |
|---|------|------|
| D-Bw2-1 | Key 明文 `lfk_{prefix8}_{secret32}`；库只存 `key_hash=hex(SHA-256(明文))`（CHAR(64) UK）+ `key_prefix` 展示；明文仅创建响应一次；高熵随机串用 SHA-256（不做 bcrypt）；HMAC+pepper 加分未做 | 交接单推荐默认 |
| D-Bw2-2 | 头名 `X-API-Key`（独立于 Bearer）；`ApiKeyAuthFilter` 只处理 `/api/**`（shouldNotFilter），`addFilterAfter(JwtAuthFilter)`；JWT+Key 同带 → 400；无效/吊销 → 401 JSON 同形；两个过滤器沿用 @Component 自动注册（OncePerRequestFilter 去重） | 交接单 D-Bw2-2 |
| D-Bw2-3 | Key 绑 `(tenant_id, user_id)`，认证成功 `UserContext.set(AuthUser(userId, tenantId, "apikey:"+prefix))` + `ROLE_API_KEY`（**不给** ROLE_USER）——现有 per-user 归属/限流/幂等原样生效 | Q3 拍板 |
| D-Bw2-4 | SecurityConfig 显式白名单：`/api/links/**` → hasAnyRole(USER, API_KEY)；`/api/keys/**`、`/api/webhooks/**`、`/actuator/**`（health 除外）→ hasRole(USER)；`anyRequest()` 收紧为 hasRole(USER)；新增 accessDeniedHandler → 403 JSON | 交接单 D-Bw2-4 |
| D-Bw2-5 | 管理 API（JWT only）：POST /api/keys（明文一次）/ GET /api/keys（无 key/hash）/ DELETE /api/keys/{id}（幂等吊销、他人 id 404）；每用户活跃上限 10（`LINKFORGE_APIKEY_MAX_ACTIVE`） | 交接单 D-Bw2-5 |
| D-Bw2-6 | 吊销即时：每请求按 key_hash 点查（UK 索引），`revoked_at IS NULL` 才放行；**不做认证缓存**；`last_used_at` best-effort（`SET NX linkforge:apikey:touch:{id} EX 60` 抢到才 UPDATE，失败吞掉） | 交接单 D-Bw2-6 |
| D-Bw2-7 | 配额复用 `RedisRateLimiter`：`{rl前缀}apikey:min:{keyId}`（60s）/ `apikey:day:{keyId}:{yyyyMMdd}`（Asia/Shanghai 自然日，86400s 兜底）；NULL 额度回落默认 120/10000；先分钟后日；超限 429 + `Retry-After`（分钟固定 60、日窗给到次日 0 点秒数）；**只对 Key 请求计数**；fail-open（Redis 故障配额不生效）；诚实登记：固定窗口边界 2×、分钟拒时日计数已 +1 | 交接单 D-Bw2-7 |
| D-Bw2-8 | `V5__api_key.sql`（§1.2 表结构）；V1–V4 零改 | 交接单 |
| D-Bw2-9 | **投递来源＝第二跳 outbox**：`consume` dedup 事务内（lf_consumed_event 插入成功后、return true 前）查本租户启用 endpoint，有则 INSERT `lf_webhook_delivery(PENDING)`；`recordClick`/`resolveTarget`/`ClickOutboxRelay` 零改动；`lf_outbox` 不加事件类型；副作用＝依赖 async-click-enabled=true（dev 默认开） | 交接单 D-Bw2-9（关键决策） |
| D-Bw2-10 | 逐次点击 `link.clicked`；payload `{id, type, createdAt, data:{linkId, code, clickedAt, device, refererHost}}`（复用 A8 `ClickDimensions`）；**不推原始 IP/完整 UA/Referer** | Q2 拍板 + 隐私最小化 |
| D-Bw2-11 | 头三件套 `X-LinkForge-Event-Id/Timestamp/Signature`；签名 `v1=HMAC-SHA256(secret, ts + "." + body)`（发送时时间戳，重试更新）；secret `whsec_`+64hex，创建/轮换明文一次；at-rest **AES-256-GCM**（`v1:`+Base64(nonce‖ct‖tag)），主密钥 env `LINKFORGE_WEBHOOK_SECRET_KEY`（Base64 32 字节）；**主密钥缺失/非 32 字节 → webhook 整体降级关（warn），endpoint 保存 503、不产生投递、不签名**；轮换后 PENDING 重试用当前 secret 签名 | Q1 拍板；README 写轮换/丢失运维边界 |
| D-Bw2-12 | 投递 event id = `evt_` + ClickEvent.eventId；`UNIQUE(endpoint_id, event_id)` 兜底；同一点击重放/重试 id 稳定 | 交接单 |
| D-Bw2-13 | 2xx=SENT；网络错/超时/408/429/5xx → attempts+1，`next_attempt_at=now+min(1h, 10s·2^attempts)`；其它 4xx 首败 FAILED；attempts≥8（`LINKFORGE_WEBHOOK_MAX_ATTEMPTS`）→ FAILED；手工重放 SQL 写 README；endpoint 禁用/删除 → PENDING 行下轮标 FAILED('endpoint disabled') | 交接单 D-Bw2-13 |
| D-Bw2-14 | Relay `@Scheduled` fixedDelay 2000ms 批 50；HTTP 用 JDK `java.net.http.HttpClient`（零新依赖，connect 2s/request 5s/**NEVER 跟随重定向**）；**调度隔离**＝`spring.task.scheduling.pool.size: 4`（基础 yml，`LINKFORGE_SCHEDULING_POOL_SIZE` 可覆盖），webhook 慢不拖点击追平；多实例 at-least-once（接收方按 event id 去重） | 交接单 D-Bw2-14 二选一取 pool.size |
| D-Bw2-15 | SSRF 双校验（保存 + 发送前）：scheme http/https、拒 userinfo、解析出的**所有** IP 不得为 loopback/any-local/multicast/link-local（含 169.254.169.254）/site-local/CGNAT 100.64/10/IPv6 ULA fc00::/7/IPv4-mapped 回落 v4 规则；不跟随重定向；`allow-private-targets` 默认 false 仅 IT 开；README 写明 DNS rebinding/端口白名单/出口代理三项不做 | 交接单 D-Bw2-15 |
| D-Bw2-16 | 每租户一个 endpoint（uk tenant）：PUT /api/webhooks/endpoint（创建返明文 secret 一次，更新不换 secret）、GET（掩码）、POST rotate-secret（新明文一次）、DELETE（物理删，存量 PENDING 行下轮 FAILED）；JWT only | 交接单 D-Bw2-16 |
| D-Bw2-17 | 测试桩＝JDK `com.sun.net.httpserver.HttpServer`（零新依赖，随机端口，脚本化状态码，记录头/体）；未用 mockwebserver | 交接单推荐默认 |
| D-Bw2-18 | `V6__webhook.sql`（endpoint + delivery 两表，§1.2 列名钉死）；`lf_outbox`/`lf_consumed_event` 结构不动 | 交接单 |
| D-Bw2-19 | 指标：`linkforge.apikey.auth{result=ok|invalid|ambiguous}`（吊销与无效共用 invalid，progress 钉死）+ `linkforge.apikey.quota.rejected{window=minute|day}` + `linkforge.webhook.delivery{result=success|retry|failed}` + `linkforge.webhook.enqueued` + `linkforge.webhook.blocked`；RedisRateLimiter fail-open 不打点是既有行为 | 交接单允许微调，此为钉死口径 |
| D-Bw2-20 | OpenAPI components 增加 `bearerAuth`（http bearer JWT）与 `apiKeyAuth`（header X-API-Key）两 scheme；描述注明「二选一、同带 400」与 webhook 签名头 | 交接单 D-Bw2-20 |
| D-Bw2-21 | 热路径零行为改动（仅 Bw1-N1 的 L1 回填）；`consume` 只追加投递行插入；V1–V4 零改 | 交接单 D-Bw2-21 |

### 改动清单

| 单元 | 内容 |
|------|------|
| V5/V6 | `V5__api_key.sql`、`V6__webhook.sql`；`FlywayBaselineIT` 断言补 V5/V6；V1–V4 零改 |
| B4 | `apikey` 包（entity/mapper/dto/service/controller）；`ApiKeyAuthFilter`（认证+配额+touch）；SecurityConfig 白名单 + 403 handler；`LinkForgeProperties.ApiKey`；OpenAPI 两 scheme |
| B5 | `webhook` 包（entity/mapper×2/service/controller/relay/cipher/signer/targetGuard）；`ClickStatsService.consume` 事务内追加 `recordClickDelivery`（仅此一处）；`spring.task.scheduling.pool.size: 4` |
| 测试 | `ApiKeyIT` 6 例；`WebhookDeliveryIT` 6 例（JDK HttpServer 桩：真链签名/退避重试/4xx 首败/max-attempts/幂等单行/可达性解耦）；单测 +11（Signer 2、Cipher 5、TargetGuard 4）；`BloomRedirectIT` +1（N1 回填） |
| 文档 | README「开放能力（B4/B5）」节（含全部诚实边界 + curl 示例）+ 配置要点 2 行 + 指标清单 |

### 验收勾选（对照交接单 Bw2-1…16）

| # | 标准 | 证据 |
|---|------|------|
| Bw2-1 | Bw1-N1 回填有断言、N2 注释一致 | 见处置表 |
| Bw2-2 | 只存 hash；明文仅一次；无明文/hash 泄漏 | `ApiKeyIT.keyLifecycleHashOnlyStorageAndCreatorIdentity`（DB key_hash=SHA-256(明文)、list 无明文无 hash）+ grep 代码审 |
| Bw2-3 | X-API-Key 认证、401/400 语义、/r/** 不经 Key 过滤 | ApiKeyIT 吊销/伪造/双带头用例；`shouldNotFilter` 只处理 /api |
| Bw2-4 | Key 可调 links 面、不可调 keys/webhooks/actuator→403 JSON；JWT 行为不变 | `keySurfaceIsForbiddenToKeysButHealthStaysOpen`（health 仍 200）；`ObservabilityIT`/`TenantIsolationIT` 回归绿 |
| Bw2-5 | 吊销下一请求即 401 | `revokedKeyFailsOnTheVeryNextRequestAndForgedKeysAreInvalid`（无认证缓存） |
| Bw2-6 | 分钟/日配额 429+Retry-After、只计 Key、fail-open | `perKeyMinuteQuotaReturns429WithRetryAfterAndSpareOthers`；键名 `{rl前缀}apikey:min|day:{keyId}` 代码可查；fail-open=RedisRateLimiter 既有口径（README 登记） |
| Bw2-7 | consume 事务内落投递行；热路径/relay/lf_outbox 未改；同 eventId 只 1 行 | 代码（唯一接线点）；`sameEventConsumedTwiceYieldsExactlyOneDelivery`；stats 语义零变化（仅追加） |
| Bw2-8 | 签名头齐全、桩侧重算一致、README ±300s | `signedDeliveryFlowsThroughTheRealOutboxChain`（真 MQ 链 + 独立重算） |
| Bw2-9 | 退避/FAILED/首败即败/重放方式 | `retryableFailuresBackOffThenSentWithStableEventId`（event id 三次相同）、`permanent4xxFailsImmediatelyWithoutRetry`、`maxAttemptsEndsInFailed`（attempts=8） |
| Bw2-10 | SSRF 规则 + 双校验 + 不跟随重定向 + 开关 | `WebhookTargetGuardTest` 6 例（无 DNS 依赖）；发送前 `isBlocked`；HTTP client NEVER |
| Bw2-11 | 调度隔离 + 不可达不拖跳转 | yml pool.size=4；`redirectDoesNotDependOnEndpointReachability` |
| Bw2-12 | 指标齐 + README 同步 | README 指标清单（apikey/webhook 计数器）；手验 actuator |
| Bw2-13 | V5/V6 新增、V1–V4 零改、Baseline 覆盖 V6、存量卷 Migrating to "5"/"6" | FlywayBaselineIT + 实机启动日志 |
| Bw2-14 | README/progress 完整 + 连接预算 | 本节 + README；预算：21 个常规上下文（hikari 默认 10）+ 4 个收紧上下文（ApiKeyIT / WebhookDeliveryIT / BloomDisabledAtStartupIT / SingleFlightRedirectIT 各 4）→ 峰值 ≈ 21×10 + 4×4 = **226** < 500（Bw2fix-N2 改口，原 24×10+2×4≈248 误算） |
| Bw2-15 | verify 全绿 + CI 绿 + 钉子口径 | 本机 `mvn -B test` → **59 单测绿**（46+13：SignerTest 2 + WebhookSecretCipherTest 5 + WebhookTargetGuardTest 6）；CI 全量 **145 例（59 单测 + 86 IT）0 失败**（+26 vs B 波 1 的 119）：分支终态 run [40108332225](https://github.com/NorthAdb/LinkForge/actions/runs/40108332225) ✅（分支 CI 红史六条，见执行记录）、main 合并 run [40108536357](https://github.com/NorthAdb/LinkForge/actions/runs/40108536357) ✅（head `d73aa80`） |
| Bw2-16 | 范围干净 | diff 复查：无 Resilience4j/OAuth/UI/波 3+；`resolveTarget` 除 N1 外零改；零新依赖（桩走 JDK 模块）；V1–V4 零改 |

### 验证命令与输出摘要（本机，2026-10-24）

- `D:/tools/apache-maven-3.9.9/bin/mvn -B test` → `Tests run: 56, Failures: 0` + BUILD SUCCESS（当时 56；后补 TargetGuard 用例 → 59，见 Bw2fix-N11 改口）
- 实机（kill 旧进程 → 新 jar 重启，.env 补 `LINKFORGE_WEBHOOK_SECRET_KEY`）：启动日志见 `Migrating schema to version "5 - api key"`、`"6 - webhook"`；手验与回归见执行记录
- 连接预算：21 个常规上下文（hikari 默认 10）+ 4 个收紧上下文（ApiKeyIT / WebhookDeliveryIT / BloomDisabledAtStartupIT / SingleFlightRedirectIT，各 4）→ 峰值 ≈ 21×10 + 4×4 = 226 < 500，未动 `--max-connections`

### 执行记录

- 提交序：Bw1-N1/N2（aac251a）→ B4（f97ddf4）→ B5（b9393eb）→ docs。每单元编译/单测绿后提交。
- 单测迭代红三次均为测试自身问题：Cipher 测试主密钥常量 33/30 字节非 32（AES-256 拒收，改精确 32）；Guard 测试桩把所有 host 解析成同一 IP（按 host 前缀区分公网/私网答案）；一次草稿转义笔误（改用 Pattern.quote）。
- `.env`（本机，gitignore）补 `LINKFORGE_WEBHOOK_SECRET_KEY`；`.env.example` 同步说明；`application-test.yml` 配固定 32 字节测试主密钥；yml 中 `spring.task.scheduling` 放基础文档（首个 spring 键内，避免同文档重复键）。
- **分支 CI 六红一绿（webhook IT 在共享套件里的四段修复史，跨上下文竞态家族新成员）**：①run [40104801106](https://github.com/NorthAdb/LinkForge/actions/runs/40104801106) 红（@ad6189b docs）＝ApiKeyIT 期望 actuator 403 实得 401（过滤器没覆盖，手验同步抓到，8e770a2 修）；②run [40105717696](https://github.com/NorthAdb/LinkForge/actions/runs/40105717696) 红（@3c74452 docs，与③同因、修之前）；③run [40105127938](https://github.com/NorthAdb/LinkForge/actions/runs/40105127938) 红（@8e770a2）＝三个链式用例超时且两段式取证报出 `last_error=blocked target`——**兄弟缓存上下文的 webhook relay 抢跑共享 `lf_webhook_delivery`**，其 allow-private-targets=false 的守卫把本上下文（开=true）的投递行标 FAILED；修＝`webhook.relay-enabled` 开关（eca1295），test yml 默认 false、仅 WebhookDeliveryIT 开（同 MqEndToEndIT 的 async/listener 模式）；④run [40106585876](https://github.com/NorthAdb/LinkForge/actions/runs/40106585876) 红（@ca81c60）＝行未落库时 `queryForMap` 抛 EmptyResult 使 await 直接中止 + 桩脚本类级静态队列被并发投递互相消耗；修＝空安全 latestDeliveryFor + **每测试独立桩路径**（/hook-retry、/hook-max…，0ed64e0）；⑤run [40107163544](https://github.com/NorthAdb/LinkForge/actions/runs/40107163544) 红（@eca1295，同④残留）；⑥run [40107773960](https://github.com/NorthAdb/LinkForge/actions/runs/40107773960) 红（@0ed64e0）＝maxAttempts 脚本只放一个 500（第二次尝试轮空得 200 → SENT attempts=2），「末位重复」注释从未实现；修＝脚本放满 8 个 500 + RECEIVED 断言只数本 event id（4df861f）。终态 run [40108332225](https://github.com/NorthAdb/LinkForge/actions/runs/40108332225) 绿。
- **实机手验抓到并修掉一个真缺口（`8e770a2`）**：`/actuator/metrics` 带 Key 得 401 而非 403——`shouldNotFilter` 原本把整个 actuator 都跳过，Key 请求没进过滤器而是匿名 401。修后 actuator（health 除外）也过 Key 过滤器，Key 得到干净 403（metrics/keys 实测 403，health 200）。分支首跑 CI 预期红在 ApiKeyIT 该用例，修复后重跑绿（见 CI 链）。
- 实机手验（A9 起惯例，证据）：B4＝建 Key（prefix `lfk_iNfLfHm3`，list 无明文）→ Key 建链 302 → metrics/keys 403 → `quotaPerMinute=5`：第 6 次请求起 429 + `Retry-After: 60`（前 5 次含一次被 403 的 /api/keys 请求——配额在授权检查前计数）；B5＝PUT endpoint（secret 明文一次、库列 `v1:OGKt…` 密文、GET 掩码）→ 跳转 → 本机桩收投递：**独立重算 HMAC 签名一致**、event id `evt_log-3955`、payload 无原始 IP、device=bot/refererHost=direct；首投 IOException（手验桩自身路径问题）→ 重试 SENT(attempts=2, 200)——重试路径真机实证；SSRF 关闭态 3 个私网/元数据/ftp 目标全 400。
- 实机回归：`smoke.sh` **SMOKE OK**；`outbox-drill.sh` **OUTBOX DRILL OK**；`mq-drill.sh` **DRILL OK**（首跑 429 系本机注册限流窗口被手验打满，清本机 Redis 计数键后重跑绿，非产品缺陷）。

### 下一步（不混入本波）

- 波 3+：B2/B6/B1/B10/B3/B9 及 backlog（Bw2-F1 聚合推送、Bw2-F2 租户级 Key），均需萧北指令再开。

---
## B 波 2 修复轮（Bw2-M1/S1/S2 + nit，fix/bw2-audit，发车基线 main@1b0a10b，2026-10-25）

范围：交接单 `记录追踪/05-阶段交接/LinkForge-phase-B-wave2-fix-handoff.md`（来源审计 FAIL，1 must-fix）。只修波 2 审计项：M1 全必修、S1/S2 应修全做、必修 nit N1/N2/N6/N10/N11 全做、可选 nit 做 N3/N4/N7/N8、跳过 N5/N9/N12（见下）。V1–V6 文本零改、零新依赖、302 热路径语义零改。

### 决策记录（D-Bw2fix-1）

| # | 决策 | 理由 |
|---|------|------|
| D-Bw2fix-1 | **时钟规则**：调度列（只被 SQL 拿来与 NOW() 比较、不对外展示）＝**DB 时钟**（`lf_webhook_delivery.next_attempt_at`、A7 `lf_outbox.next_attempt_at` 已是）；业务列（对外 API 返回或给人看）＝**JVM 时钟**（`lf_api_key.created_at/revoked_at/last_used_at`、`lf_webhook_delivery.created_at/sent_at`、`lf_webhook_endpoint.*_at`）。落地：`markRetry` 只收 `backoffSeconds`，SQL `DATE_ADD(NOW(), INTERVAL #{backoffSeconds} SECOND)`（与 OutboxMapper 同式）；`markSent`/`touchLastUsed` 改收 `#{now}` 由 Java 传入。内部审计列沿用 **DB 时钟**：SQL 直接写 `NOW()` 的列（如 `OutboxMapper.markSent` 的 `sent_at`）不属业务列，保持 SQL 写法（Bw2fix-N11 归类） | 审计 M1/S2；混用两种时钟时（box：MySQL UTC、JVM +8）重试被推迟 ~8h、API 出现「先使用后创建」 |

### 必修/应修处置

| ID | 处置 | 证据 |
|----|------|------|
| **M1** | `markRetry` 改 DB 时钟（上表）；Relay 不再用 `LocalDateTime.now()` 算调度时间；回归 IT＝新建 `WebhookRetryClockIT`，**独立 failsafe execution `webhook-tz-it` 以 `-Duser.timezone=Asia/Shanghai` fork**（Testcontainers MySQL 仍 UTC，精确复现错配；默认 execution 排除该类，上下文数与连接预算不变）。断言①：`TIMESTAMPDIFF(SECOND, NOW(), next_attempt_at) ∈ [0, 20]`（首次退避 10·2^1=20s）；断言②：60s 内 retry 真正 fire 到 SENT | **旧实现下该断言红的证明**：本地对 Compose MySQL（UTC）实测——新式列值 `TIMESTAMPDIFF(SECOND, NOW(), DATE_ADD(NOW(), INTERVAL 20 SECOND))` = **20**（绿）；旧式（JVM+8 时钟字面值）`= 28820`（红，≈8h）。断言直接比对该差值，旧实现必红 |
| **S1-a** | `webhook.enabled=false` 维持不落行；README 写明「关闭期间的点击不补投」 | `recordClickDelivery` 入口 featureOff 早退 |
| **S1-b** | `enabled=true` 且主密钥缺失/无效：**照常落 PENDING 行**（入队只看 endpoint 存在且启用）；启动 WARN 保留、**不 fail-fast**（萧北确认） | `WebhookService.recordClickDelivery` 去掉 `!cipher.isAvailable()` 早退；IT `missingMasterKeyStillEnqueuesAndRelaySkipsWithoutConsumingAttempts`（@SpyBean cipher 置 false → 行落、attempts=0、`relay.skipped` 上涨 → reset 后自然 SENT） |
| **S1-c** | 发送侧主密钥不可用：**drain 入口整批跳过**（不扫表、不耗 attempts、不改行），计 `linkforge.webhook.relay.skipped{reason=master_key_unavailable}` | `WebhookDeliveryRelay.drain` |
| **S1-d** | 入队插入异常：计 `linkforge.webhook.enqueue.failed{reason=<简名>}` 后**向上抛**，随 consume 事务回滚（MQ 语义重放/DLQ，统计与回调意图同生共死，萧北确认回滚方案）；**唯一例外** `DuplicateKeyException` 计 `reason=duplicate` 后正常返回 | `recordClickDelivery` catch 重构；IT `enqueueFailureRollsBackTheConsumeTransaction`（@SpyBean mapper 抛异常 → consume 抛出、`lf_consumed_event` 0 行、`enqueue.failed` +1）、IT `duplicateEnqueueIsCountedAndHarmless` |
| **S2** | `ApiKeyMapper.touch` → `touchLastUsed(id, #{now})`、`markSent(id, code, #{now})`，Java 传 `LocalDateTime.now()`；表结构零改 | IT `businessTimestampsFollowTheJvmClockEvenWithSkewedJvm`（+8 fork 下 `last_used_at >= created_at`、`sent_at >= created_at`；旧 NOW() 写法下 sent_at 落后 created_at ~8h → 红） |

### 必修 nit 处置

| ID | 处置 |
|----|------|
| **N1** | L1 回填挪到「行 URL 校验门 + 渲染校验」**两道之后**；`BloomRedirectIT.dirtyRedisValueNeverBackfillsTheLocalCache`：Redis 植入脏 URL + 行合法 → 410 且 L1 无该 code |
| **N2** | `.env.example` 占位改空值 + `# openssl rand -base64 32（必须解码为 32 字节）`；照抄后走「未配置」WARN 而非「解码失败」（空值路径本来就不进解码分支，实测文案一致）；README 同步 |
| **N6** | 守卫补拦 `0.0.0.0/8`、`198.18.0.0/15`、`240.0.0.0/4`（含 255.255.255.255）、IPv6 `::/96`（compatible）、`64:ff9b::/96`（NAT64）、`2002::/16`（6to4）——后三种先取内嵌 IPv4 再走 v4 规则；400 文案统一 `webhook target not allowed`（不回显解析 IP）。`WebhookTargetGuardTest` +4 例：0.1.2.3/198.18.0.1/198.19.255.255/240.0.0.1/255.255.255.255 全拦、NAT64/6to4/compat 包私网全拦、**公网 NAT64 `64:ff9b::808:808` 放行**、文案无 IP |
| **N10** | `permanent4xx` 的 `RECEIVED.size()` 改为本 event id 过滤计数（顺序无关）；无用的 `@BeforeEach` import 已删；本地曾临时加 `@TestMethodOrder(Random)` 复核（不提交）——用例间已无共享可变状态依赖（每测试独立租户/桩路径，RECEIVED 按 event id 过滤） |
| **N11** | progress 改口六处：「五红一绿」→**六红一绿**（补 40105717696/@3c74452 与 40106585876/@ca81c60 两跳）；run id `40104801146`→`40104801106`；「+11/TargetGuard 4」统一为 **+13/TargetGuard 6**；「56 单测」→**59**；连接预算 `24×10+2×4≈248`→**`21×10+4×4≈226`**；配额手验改「第 6 次请求起 429（前 5 次含一次被 403 的 /api/keys 请求——配额在授权检查前计数）」 |

### 可选 nit 处置（N3/N4/N7/N8 做；N5/N9/N12 登记）

| ID | 处置 |
|----|------|
| N3 | **做**：hash 命中但已吊销 → `result=revoked`（对外仍 401 同形）；`authenticate` 返回行、吊销判定移到过滤器 |
| N4 | **做**：`linkforge.api-key.max-quota-per-minute/day`（默认 1000/100000），创建时超限 400，防自助提额 |
| N7 | **做**：drain 的 catch 对当前行 `scheduleRetry`（按 attempts 走退避或 FAILED），毒行不再每 2s 热循环。**单 relay 顺序投递、慢接收方拖慢全体**：README 已有「诚实限制」口径，保持 |
| N8 | **做（改文档口径）**：README 明确公式 `min(1h, 10s·2^attempts)`、attempts 从 1 起计（首次重试 20s）、**attempts 计入最终那一次** |
| N5 | **不做（登记）**：活跃 Key 上限计数存在并发窗口，短暂突破可接受（演示规模）；`SELECT … FOR UPDATE` 需要事务改造，收益不成比例 |
| N9 | **不做（登记）**：payload 时间戳维持 Jackson 默认 ISO（无偏移）；改 OffsetDateTime 是对外契约变更，等真实接收方需求出现再做 |
| N12 | **不做（登记）**：429 的 `Retry-After` 维持分钟窗固定 60s（日窗已精确到次日 0 点秒数）；读窗口剩余 TTL 的增益小 |

### 验证与手验

- 本机 `mvn -B test` → **63 单测绿**（59+4：TargetGuard 扩面）；failsafe 双 execution：默认 86+3（S1 三例进 WebhookDeliveryIT）+ N1 一例（BloomRedirectIT 91）＋ `webhook-tz-it` fork 2 例 = **共 92 IT**；全量合计 **155 例**（CI 为准；Bw2fix-N1 改口：以「修复后例数」行 90+2 为准）
- 连接预算：`WebhookRetryClockIT` 复用 WebhookDeliveryIT 的属性集（每 fork 各自起容器、不并存）→ 峰值仍 ≈ **226 < 500**
- **时区说明（交接单 §5）**：Windows 实机 JVM 默认时区＝**Asia/Shanghai（+8，China Standard Time）**；本机 Compose MySQL＝**UTC**（`@@global.time_zone=SYSTEM` 且 `NOW() == UTC_TIMESTAMP`，docker-compose 未设 TZ）。B 波 2 手验「首投 IOException → 重试 SENT(attempts=2, 200)」之所以秒级成功，是因为当时的手验用 `UPDATE lf_webhook_delivery SET next_attempt_at=NULL` 做了重放——**NULL 视为立即可投，恰好把被 +8 时钟写歪的调度值清掉了**；bug 一直存在，真实触发的退避重试在本机会推迟 ~28820s（SQL 实证，与 box 审计的 28761s 同阶）
- **Bw2fix-8 手验（Compose 默认栈：MySQL UTC + JVM +8）**：新建租户配 endpoint → 桩首个请求 500 → 跳转 302 → 3s 后查库 `status=PENDING, attempts=1, TIMESTAMPDIFF(SECOND, NOW(), next_attempt_at)=18`（∈[0,20]，旧实现同位置实测口径 ≈28820）→ **重定向后 22s SENT（attempts=2）**——退避与重试全程 DB 时钟，无 8h 拉长
- **Bw2fix-9 手验**：带主密钥配 endpoint → `unset LINKFORGE_WEBHOOK_SECRET_KEY` 重启（WARN 文案＝「master key unavailable」，N2 口径）→ 点击 302 → 投递行 **PENDING / attempts=0**，relay 跳过 9 轮（`relay.skipped`=9.0）→ 恢复主密钥重启 → 该行 **SENT / attempts=1**，无需重放。附带取证：无主密钥时 PUT 新 endpoint 返回 503（secret 落库必须加密，属 B 波 2 既定降级，S1-b 针对的是已配置 endpoint 的投递意图）
- 期间踩坑一次：重启验证时 8080 上残留了无密钥的旧进程，PUT 打到旧进程得 503——杀干净后即 200；不影响结论
- 三回归：`scripts/smoke.sh` **SMOKE OK**；`scripts/outbox-drill.sh` **OUTBOX DRILL OK**；`scripts/mq-drill.sh` **DRILL OK**（本机注册限流窗口按惯例先清本机 Redis 计数键）
- **分支 CI 三红一绿（修复轮自己的教训）**：①run [40114155914](https://github.com/NorthAdb/LinkForge/actions/runs/40114155914) 红（@4e0ec6b；e5bb964/5a92cd2/4e0ec6b 同推，仅末个触发 run）＝四件事：pom execution id 起名 default-it 没顶掉隐式 default 执行 → **整套 IT 跑了两遍**、时钟 IT 混进 UTC fork；queryForMap 的 DATETIME 返回 LocalDateTime、强转 Timestamp 抛 ClassCastException；脏值 `javascript:alert(1)` 其实过得了渲染校验（isRenderableUrl 只拦控制字符/解析失败，CRLF 注入串才是已知不可渲染形态）；duplicate 计数断言没按 tag 取（find 命中同名的另一个计数器）。修＝execution id 改 `default` + asLocalDateTime 兼容两种返回类型 + 脏值改 CRLF 串 + taggedCounter（08e3797）。②run [40114667946](https://github.com/NorthAdb/LinkForge/actions/runs/40114667946) 红＝转义笔误把 `\n` 写成真实换行，字符串字面量断裂（42d7ef9 修）。③run [40114741958](https://github.com/NorthAdb/LinkForge/actions/runs/40114741958) 红＝asLocalDateTime 助手的 python 替换静默未命中、调用点已改导致缺方法（2248085 修）。终态 run [40114874266](https://github.com/NorthAdb/LinkForge/actions/runs/40114874266) 绿。教训：python 批量改代码必须 assert 替换发生；`&&` 链守提交（本轮起恢复，禁止 `;\)。
- **修复后例数**：surefire **63**（59+4）＋ failsafe 默认 execution **90**（86+3 S1 例 +1 N1 例）＋ `webhook-tz-it` fork **2**（M1/S2 回归）＝ **合计 155 例全绿**；连接预算不变（时钟 IT 复用 WebhookDeliveryIT 属性集，fork 内容器不并存）≈ 226 < 500

---

## B 档波 3+4+5 合并轮：B6 滑窗限流 / B2 非人点击不计 / B9 钓鱼门禁 / B3 密码短链 / B1 Prometheus+Grafana（feat/deepen-b-wave345，发车基线 main@3fe1fb1，2026-10-25）

范围：交接单 `记录追踪/05-阶段交接/LinkForge-phase-B-wave345-handoff.md`（Q1–Q14 全部拍板：t93u Q1–Q6、t94u B3=密码短链+B10 移出、t95u Q7–Q14 全 A）。开工提交收 Bw2fix-N1…N12（+可选 N7/N8/Bw345-pre-1）；B10 压测不在本轮（Bw345-F2，放到最后、将来审计侧 box 跑）。分支提交序：kickoff `059af1a` → B6 红 `09c9107`/绿 `3a80d2c`+测试侧修复 `6381ed0`/`82919d2` → B2 红 `473f334`/绿 `d1edf5b` → B9 红 `98cbf15`/`479a88b`/`af98c9c`/绿（amend）`4f7e24a`+`a8d4c4a`+`42c422c` → B3 红 `29ca919`/绿 `240ecf9`+`0d3f97c` → B1 红 `53fa8cf`/绿 `63d7cfd`+`9908824`+`bfa0498`+`8da89e0`+（编译修）`76d24a3`/`f3fbf9d`+`0c3420b`。红先提交只含测试、其 CI run 即红证据；绿提交含实现+文档改口。

### 拍板与关键决策落地（D-Bw345 摘要；全文见交接单 §1.0/§1.1）

| 决策 | 落地 |
|----|------|
| D-Bw345-9…19（B6） | ZSET+Lua 滑窗日志（`redis/sliding_window.lua`，Redis `TIME` µs 时钟，只放行才 ZADD，成员 `now_us-nonce`）；新命名空间 `{prefix}sw:`（`RateLimitKeys`）；`acquire()`→`RateLimitDecision{allowed,retryAfterSeconds,count}`，`tryAcquire` 委托；429 全带 `Retry-After`（`RateLimitedException`+handler，Q3=A）；fail-open 保留 + `linkforge.ratelimit.failopen{reason=error\|null}`；**API Key 日配额保持上海自然日固定计数（Q2=A）→ 保留 `tryAcquireFixedWindow`（INCR/EXPIRE）仅供日窗**，分钟窗改滑窗（修 Bw2-N12 恒 60）；Q5a=A 无开关、Q5b 本轮不重跑 bench（三处时期注） |
| D-Bw345-1…8（B2） | `link/support/ClickFilter`：`Sec-Purpose`(token 精确)/`Purpose`/`X-Purpose`/`X-Moz` + 21 个具名预览 bot 小写子串；判定点在 `resolveTarget` 后 `recordClick` 前；跳过=同 302 同 Location、全链路不计、`linkforge.click.skipped{reason=prefetch\|preview_bot}`；HEAD 不经过（Q6=A）；`enabled=false`/prefetch 开关/env 整表覆盖（D-Bw345-5） |
| D-Bw345-20…29（B9） | `TargetPolicy` 纯函数：IDN 规范化 + label 边界后缀匹配 + BLOCK>WARN + userinfo 规则（建链 BLOCK/存量 WARN，Q10=A）；建链/改链 400（不回显名单项）+ 跳转复查（**行权威**——评的是 `link.getOriginalUrl()`，缓存旧值不遮蔽）；`PhishingPolicy` volatile 快照 + 文件 mtime 热加载（坏文件保旧 + `reload{result=failed}`）；`GatePages` 转义+CSP/nosniff/no-store/no-referrer；BLOCK=403 拦截页无目标无 Location、WARN=200 中间页 + `?lf_continue=1` 只跳 WARN 且 302 才计（Q9=A）；不做 DNS/SSRF、不复用 WebhookTargetGuard（对照表在 README）；指标 `phishing.gate{action,stage}`/`continue`/`reload{result}`/`rules{level}` |
| D-Bw345-30…40（B3） | V7 `access_password_hash`（BCrypt 复用 bean）；Detail 只加 `passwordProtected`、哈希/明文不出 API/CSV/日志；4–64 校验、`accessPassword`+`clearAccessPassword` 互斥 400；**清除走显式 `LambdaUpdateWrapper.set(null)`**（MP NOT_NULL 坑，IT 钉住 DB 列 NULL）；判定顺序 D-Bw345-51：BLOCK → 密码门（`Locked` outcome，GET 200 解锁页/HEAD 401）→ WARN → B2 → 302；`POST /r/{code}/unlock` 先两级限流（同 IP 5/300s + 同码 50/300s，`sw:` 键）**后 BCrypt**（IT 用「第 6 次提交正确密码仍 429」钉顺序）；cookie `lf_ac_{code}` = `{exp}.{HMAC}`，签名绑 linkId+code+exp+**pwdVer**（当前哈希 SHA-256 前 8 字节）——改/清密码旧 cookie 即废；HMAC key 从 JWT_SECRET 域分离派生（无新 env） |
| D-Bw345-41…50（B1） | `micrometer-registry-prometheus`（Boot BOM）；exposure 加 `prometheus` + 公共 tag `application=linkforge` + `http.server.requests` 直方图+SLO 桶；`/actuator/prometheus` 由 `MetricsScrapeTokenFilter` 守（常量时间比较、**空令牌 fail-closed**、JwtAuthFilter 对该路径 shouldNotFilter、SecurityConfig `hasRole(METRICS)` 先于 `/actuator/**`、API Key 仍 403）；新指标 `cache.l2{result}`/`outbox.sent`/`BacklogGauges`（15s 定时采样 OUTBOX PENDING + WEBHOOK PENDING/FAILED，不在 scrape 线程查库）；**uri 标签 MeterFilter 白名单**（非模板一律压 UNKNOWN——见执行记录第 5 条）；compose `profiles:["observability"]`（prometheus v2.55.1 + grafana-oss 11.3.0 写死、端口 127.0.0.1、host-gateway、令牌文件 gitignore）；看板 JSON 入库（datasource uid `linkforge-prom` 固定）+ `DashboardMetricsIT`（看板名 ⊆ 真实 scrape ∪ 有因 CONTEXT_LIMITED 集）+ 截图 3 张（Q13=A）；调度池 4→6（D-Bw345-54） |
| 交接偏离登记 | ①`LINKFORGE_PHISHING_RELOAD_INTERVAL` 用**毫秒**（交接建议 `30s`——`@Scheduled` 占位符只认毫秒/ISO-8601，run 40157637874 红）；②warn 页转义用例用 `'`/`&`（交接的 `<script>` 在 `isRenderableUrl` 的 URI 解析下不可达 410，run 40156726448 红证实）；③B3 互斥校验在 PATCH（D-Bw345-32 原文即 update 语义，红 IT 误放 create 已修）；④交接 §4 手验脚本「201」应为 200（create 实际契约，RateLimitIT 既证） |

### 红先证据（Bw345-33；每项：红 run @ 提交 = 预期断言红，绿 run @ 提交）

| 项 | 红证据 | 绿证据 |
|----|--------|--------|
| B6 | run [40153960882](https://github.com/NorthAdb/LinkForge/actions/runs/40153960882) @ `09c9107`＝91 IT 中 5 断言红：`SlidingWindowRateLimiterIT.boundaryBurstDoesNotDoubleTheWindow`（**expected 1 but was 3**，固定窗口 2× 突发实证）+ RateLimitIT/RegisterRateLimitIT 429 无 Retry-After ×3 + ApiKeyIT Retry-After=60 | run [40154955119](https://github.com/NorthAdb/LinkForge/actions/runs/40154955119) @ `82919d2` |
| B2 | run [40155274015](https://github.com/NorthAdb/LinkForge/actions/runs/40155274015) @ `473f334`＝`BotPrefetchClickIT`：prefetch+Slackbot 计点击 **expected 0 but was 2** | run [40155643671](https://github.com/NorthAdb/LinkForge/actions/runs/40155643671) @ `d1edf5b` |
| B9 | run [40156726448](https://github.com/NorthAdb/LinkForge/actions/runs/40156726448) @ `af98c9c`＝6 断言红：blocked 建链 400 expected got 200、userinfo 400 got 200、blocked 跳转 403 got 302 ×2（GET/HEAD）、warn 页 200 got 302、bot 访问 blocked 403 got 302 | run [40158461826](https://github.com/NorthAdb/LinkForge/actions/runs/40158461826) @ `42c422c` |
| B3 | run [40158722790](https://github.com/NorthAdb/LinkForge/actions/runs/40158722790) @ `29ca919`＝7 断言红：`passwordProtected` 缺失、解锁端点 401×2、无长度校验 200 got 400、受保护 GET 302 got 解锁页、爆破第 6 次 401 got（应 429） | run [40159419082](https://github.com/NorthAdb/LinkForge/actions/runs/40159419082) @ `0d3f97c` |
| B1 | run [40159657579](https://github.com/NorthAdb/LinkForge/actions/runs/40159657579) @ `53fa8cf`＝4 红：令牌 scrape **401 got（应 200）**、`linkforge_redirects_total` 缺、积压 gauge 缺、看板 JSON 缺 | run [40164727414](https://github.com/NorthAdb/LinkForge/actions/runs/40164727414) @ `0c3420b` |

审计可对任一项临时还原实现复现红（还原对应绿提交的实现文件、保留红测试提交的测试文件即可）。

### 分支 CI 红史（修复轮教训的延续；全部如实记录）

- **B6 两红**：[40154398901](https://github.com/NorthAdb/LinkForge/actions/runs/40154398901)＝新 IT 两处测试 bug（`redis.type()` 返回 DataType 枚举大小写；Retry-After 睡眠锚点错 1s）；[40154799476](https://github.com/NorthAdb/LinkForge/actions/runs/40154799476)（`6381ed0`）＝**mvn 退出码被管道吞掉导致带编译错推送**（redis.type 返回 DataType 非 String）——教训已在仓内记忆/progress 双重固化：`mvn -B test -q > log; MVN=$?; [ $MVN -eq 0 ] && git…`
- **B9 五红**：40156008312（红 IT 两处测试 bug：create 契约 200 非 201、DB 直插行 Bloom 不可见 404）→ 40156332051（warn 用例 `<script>` 撞 isRenderableUrl 410）→ 40157134151（**B9 提交混入未提交的 B3 引用**——ShortLinkService/RedirectController 为 B9/B3 共享文件，amend `4f7e24a` 剥离并 stash 验证快照可编译；LinkAccess 配置键无害随 B9 提交先行）→ 40157637874（`@Scheduled` 不认 `30s`）→ 40157980534（**门禁评估了缓存生效 URL 而非行 URL**——缓存旧值遮蔽改写后的行，改 `link.getOriginalUrl()` 行权威）
- **B3 一红**：40159000648＝set+clear 互斥误放 create（D-Bw345-32 是 update 语义，IT 改 PATCH）
- **B1 五红**：40159945710（`addFilterBefore(metrics, JwtAuthFilter.class)` 在 JWT 注册位置前调用→无注册序）→ 40160274568（**根因级发现：`@SpringBootTest` 默认经 `DisableObservabilityContextCustomizer` 关闭 metrics 导出，Prometheus registry/scrape 端点 bean 在所有测试上下文不存在**——修=`@AutoConfigureObservability`）→ 40162469539（API Key 403 被抓取过滤器 401 抢答 + 计数器未触发不导出）→ 40163153381（L1 命中遮蔽 l2 计数器；看板一致性需触发后比对；**gauge 样本解析**）→ 40164056098（`Meter.Id` 无 replaceTag、getTag 返回 String——同管道吞退出码教训第二次触发，改为显式 `$?` 判定）

### 例数、连接预算与 CI

- **修复后例数**：surefire **86**（63+23：ClickFilterTest 6、TargetPolicyTest 7、LinkAccessTokenTest 6、RedisRateLimiterTest 重写 +4）＋ failsafe 默认 execution **119**（90+29：SlidingWindowRateLimiterIT 5、BotPrefetchClickIT 1、PhishingGateIT 10、PasswordLinkIT 7、PrometheusScrapeIT 5、DashboardMetricsIT 1）＋ `webhook-tz-it` fork **2** ＝ **合计 207 例全绿**（+52 vs 基线 155）；分支终态 run [40164727414](https://github.com/NorthAdb/LinkForge/actions/runs/40164727414) ✅
- **连接预算**：新增 3 个上下文（默认组合×1＝SlidingWindowRateLimiterIT+PasswordLinkIT 共用；PhishingGateIT×1；PrometheusScrapeIT+DashboardMetricsIT 共用×1）→ 24 个常规（hikari 默认 10）+ 4 个收紧（各 4）→ 峰值 ≈ 24×10+4×4 = **256**。**口径澄清（v2.0 发布轮审计 #4）**：256 是**推导值**——按「每个上下文的 Hikari 同时满池」这一最坏情形纸面相加，实际各上下文懒建连接、极少同时满载；真实天花板是共享 Testcontainers MySQL 的 `--max-connections=500`（`AbstractIntegrationTest`），需求侧 256 vs 配置侧 500，余量 244；全绿即 `Too many connections`=0
- **CI**：分支终态 run [40166329511](https://github.com/NorthAdb/LinkForge/actions/runs/40166329511) ✅（head `5407fa4`，docs 收口）；main 合并 run [40166609931](https://github.com/NorthAdb/LinkForge/actions/runs/40166609931) ✅（head＝合并提交 `b700992`）；其上钉子 docs 提交 `bdda9a1`＝合并后的 main tip，run [40166917264](https://github.com/NorthAdb/LinkForge/actions/runs/40166917264) ✅（v2.0 发布轮审计 #1 改口：原「tip＝合并提交、tip run＝合并 run 同 SHA」漏了钉子自身的 run）
- **观测栈手验（本机 Compose，2026-10-25）**：`docker compose --profile observability up -d` → Prometheus `/api/v1/targets`＝`host.docker.internal:8080 **up**`；Grafana 看板自动 provision（uid `linkforge-slo`）、datasource `linkforge-prom` health OK；手工流量后三区截图入库：`docs/img/grafana-redirect-slo.png`（187KB）/`grafana-singleflight-outbox.png`（114KB）/`grafana-keys-gates-jvm.png`（137KB），全部 ≤300KB（Q13=A）。本机 3000 端口被无关进程占用，截图容器临时用 13000（compose 配置未动）；Grafana 挂载再次踩 MSYS 路径转换坑（`MSYS_NO_PATHCONV=1` 解）。截图为手工流量、非压测数字，图注=看板标题内嵌「Demo targets, not a production promise」
- **快速手验对照**（交接 §4 命令）：prefetch/Slackbot 302 不计、curl 计 ✓（IT 同断言）；滑窗 Retry-After 递减 ✓（ApiKeyIT <60）；blocked 403/warn 中间页/continue 计数 ✓；密码解锁+清除立即生效+爆破 429 ✓；Prometheus 令牌三态+API Key 403+tag 有界 ✓——IT 全覆盖，交接 §4 为人工复验路径

### Backlog（不混入本轮）

- **Bw345-F1**：B3 备选 QR+UTM（萧北 t94u 不做；将来另开一轮，差异表见交接单 §1.9）
- **Bw345-F2**：B10 Linux 复跑 bench 并刷新数字（放到最后、审计侧 box；`docs/bench.md` 页头已注明现有数字测于 `df904f8` 固定窗口时期）

---

## 审计修复轮（依据《LinkForge-P0-P3-内审报告》，2026-10-24）

审计结论：P0–P3 基线带条件通过；建议开 P4 前先修 M1。本轮处置如下。

### 已修（feat 分支 fix/audit-p0-p3）

| ID | 审计问题 | 修复 | 证据 |
|----|----------|------|------|
| M1 | URL 校验过弱：CRLF URL 创建 200、跳转 500、先记账后炸 | create/update 增加控制字符拒绝 + `URI` 严格解析（400）；跳转对存量脏目标先校验后记账，非法 → 410 且不写 click_log 不进缓存 | `LinkLifecycleTest` 新增 3 例（CRLF/空格/NUL 创建 400、PATCH 非法 400、脏行 410+零点击+零缓存）；**审计攻击串原样重放实测 400**（原 200/500） |
| M2 | 注册非事务，可能留孤儿租户占名 | `register` 加 `@Transactional`（租户+用户同事务） | `RegisterFlowTest.concurrentSameTenantNameLeavesSingleTenantAndUser`：并发同名注册恰一 200 一 409，库中 1 租户 1 用户 |
| M5 | 幂等 pending 超时后强行二建（同键两码） | 轮询未 settle → 503「retry later」，不再 doCreate | `stalePendingKeyReturns503InsteadOfSecondLink`：预置 pending 键 → 503 + 0 新链 |
| L6 | progress 陈旧句（403 已被 R2 改 401） | 上文已加注 | — |
| L7 | 保留字 `api`/`r` 死代码（长度 <4 永远 pattern 先拦） | 从 RESERVED_CODES 移除，集合只留 ≥4 字符 | CustomCodeTest 回归绿 |
| L3/L4 | health 细节/Swagger/控制台匿名可达（本地 Demo 取向） | README 配置要点新增「公网部署前须收紧」清单 | 文档 |
| L5 | Linux 复现缺 host 网络说明 | README 新增「Linux 复现提示」（--network host + 127.0.0.1 / 172.17.0.1），注明审计环境已按此复现 | 文档 |
| L9 | IPv4/IPv6 双限流桶 | README 限流段注明为预期现象 | 文档 |
| M3 | 关 sync 后发布失败不可追平 | README 关同步段改为 ⚠️ 醒目警告（无 outbox、必须先对账、接受降级边界） | 文档 |

### 搁置到 P4（按运营者指示，与审计优先级建议一致）

- L1 GitHub Actions CI（JDK17 + mvn test）
- M4 Testcontainers 或等价测试隔离（当前集成测试绑定共享 Compose）
- L2 Flyway/Liquibase 或 scripts/migrate.sh
- L8 跳转缓存不省 DB 读的 bench 量化（决定是否版本号缓存）
- L11 CI 中用 secrets 覆盖测试默认口令
- bench.md 压测数字、架构图、简历条目（P4 主体工作）

### 验证

- `mvn -B test` → **Tests run: 46, Failures: 0, Errors: 0**（41 + 新增 5）
- `bash scripts/smoke.sh` → SMOKE OK；审计 M1 攻击串真机重放 → **400**；对账 211=211 闭合

---

## P4 工程化（feat/p4-engineering，发车基线 main@7e8b9fe）

范围：Testcontainers 测试隔离、GitHub Actions CI、bench.md 压测数字、架构 README、resume.md、prod profile 收紧。不加新功能；R2–R4 的 P5 项保持原样。

### 退出条件勾选与证据

- [x] (a) 测试隔离 + 单测分层 + 总数不减
  - 集成测试改 **Testcontainers 2.0.5**（MySQL 8.0 / Redis 7-alpine / RabbitMQ 3.13-management，静态单例 + @DynamicPropertySource），不再依赖本机 Compose；`*IT` 归 **failsafe**（绑 verify），纯单测 16 个归 surefire（LinkValidations/CsvFormat/ShortCodes/RedisRateLimiter——纯函数从 Service 抽到 `link/support` 工具类）
  - **本机限制（如实记录）**：本开发机 Docker Desktop 29.9.2 与 docker-java（TC 1.19.8/1.21.3/1.21.4/2.0.5 全试过）不兼容——npipe 与挂载 socket 均 400/EOF，CLI 正常；已定位到 Desktop 29 的 API 代理层。本机用 `mvn -B verify -DskipITs`（16 单测绿，15s），**63 个测试的完整绿由 CI 的 ubuntu runner（只有 Docker、无 Compose）实证**，正合验收要求的机器形态
  - CI 实测：ubuntu-latest 上 47 个 IT 全跑（LinkLifecycleIT 15 例 5.6s），Testcontainers 原生工作
- [x] (b) CI：`.github/workflows/ci.yml`（ubuntu-latest + Temurin 17 + m2 缓存 + `mvn -B verify`），push 到 main 与 feat/** 触发；无真实口令（测试口令仅 test profile 假值）。main 绿 run 链接见下「审计材料」
- [x] (c) 压测：`scripts/bench.sh`（wrk，镜像 skandyla/wrk=wrk 4.x）+ `docs/bench.md`
  - 关键数字（本机 Windows 全实测）：health 基线 **888.8 QPS / P50 9.9ms**；跳转 default **13.3 QPS / P50 678ms**；**关同步明细 276.1 QPS / P50 31.4ms（约 21 倍）**；关 MQ 发布 11.6 QPS（≈无差）；正式 c50 14.9 QPS（364 次 >2s 超时=写排队）；创建 **13.5 QPS / P50 1.25s / 0 非 2xx**；**缓存命中率 100%**（hits +497 / misses +0）
  - 限流压测期间临时调高（LINKFORGE_*_RATE_MAX=1000000），bench.md 已写明；机器规格/命令/原始输出齐备
  - 诚实结论：本机每次 MySQL 提交约 600ms（Windows 卷存储路径；CI Linux 上同类写操作毫秒级），同步明细写是跳转吞吐支配项——这正是「双写切纯异步需 outbox」论断的量化证据
- [x] (d) 架构 README：mermaid 组件图（含 Reconciler 追平边）、跳转 5 步时序说明、「本地 Demo 五步」、Linux 复现小节（--network host / 172.17.0.1，附命令）
- [x] (e) `docs/resume.md`：5 条中文 bullet，数字全部引用 bench.md（13→276 QPS、P50 678→31ms、命中率 100%、63 测试 CI 绿），限流如实写「Redis 固定窗口」
- [x] (f) prod profile：`show-details: when_authorized`、springdoc 关闭、静态资源关闭；文档/控制台路径在 prod 由 SecurityFilterChain **denyAll（401）**（`linkforge.docs-enabled=false`）——比依赖 springdoc 关闭行为（实测会 500）更干净；dev/test 行为不变（ObservabilityIT 证全开放）
- [x] (g) 收尾验证：本机 `mvn -B verify -DskipITs` 绿（16 单测）；`bash scripts/smoke.sh` → **SMOKE OK**；`bash scripts/mq-drill.sh` → **DRILL OK**（15/10 基线-断链-追平到 25）；CI 绿；合并推送

### P4 执行记录（含两次外部审计建议的同类发现）

- Testcontainers 兼容性排查耗时最长：本机 Desktop 29.9.2 + docker-java 全系 400/EOF（含 mounted socket 与 DOCKER_HOST=npipe 真引擎管道），CLI 一切正常——判定为 Desktop 29 与 docker-java 生态的兼容断层，CI Linux 为标准执行环境。已记录给审计人。
- RabbitMQ Testcontainers：TC 2.0 忽略 `RABBITMQ_DEFAULT_USER` 环境变量，必须用 `withAdminUser` API（CI 首跑三个 rabbit 相关 IT 401 暴露）。
- wrk 三坑实录：williamyeh/wrk 是 2012 年 wrk 1.x（-s 语义不同）；Windows bind mount 对新建文件有陈旧目录缓存（-s 挂载不可靠 → lua 走 stdin 管道）；wrk 的 `request` 函数一旦定义必须返回请求串（return nil = "incomplete request"）→ 官方全局变量写法。
- 压测副产物：no-sync 场景 4150 次跳转仍异步发布 UUID 事件，Windows 卷消费速率 ~1.7 条/s 造成队列积压 8 千条，把 mq-drill 基线淹没——purge 队列 + 等消费端清空在途消息后 DRILL OK。这本身量化了「消费端吞吐受存储提交约束」的事实。
- 对账基线重置：压测产生的历史数据（UUID 事件已消费 + 明细表）造成 aggregate 虚高（4019 vs 3420），已 TRUNCATE 两表由 Reconciler 从 3420 条明细全量重建（本机速率 ~1.7 行/s，收敛需约 35 分钟；机制此前已多次实证：104=104、188=188、drill 25=25）。终态见「审计材料」的复查说明。

### 审计材料（hotfix 轮，历史存档）
- 仓库：https://github.com/NorthAdb/LinkForge（分支 main，**自审后终态 commit：447dfdb**；P0–P3 首轮终态合并 103a199）
- 复现环境：Windows + Docker Desktop + Git Bash；`cp .env.example .env && docker compose up -d`（等三容器 healthy，mysql 首次初始化约 5 分钟）
- 复现命令清单（按序执行，全部应绿）：
  1. `MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD":/app -v linkforge_m2:/root/.m2 -w/app -e SPRING_DATASOURCE_URL="jdbc:mysql://host.docker.internal:3306/linkforge?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false" -e SPRING_DATASOURCE_PASSWORD=linkforge_pass -e SPRING_DATA_REDIS_HOST=host.docker.internal -e SPRING_RABBITMQ_HOST=host.docker.internal -e SPRING_RABBITMQ_PASSWORD=linkforge_pass -e JWT_SECRET=test-secret-at-least-32-characters-long!! maven:3.9-eclipse-temurin-17 mvn -B test`（期望 41/41 绿；447dfdb 时点为 27/27）
  2. 构建并启动：同上镜像 `mvn -B -DskipTests package` → `set -a && . ./.env && set +a && java -jar target/linkforge-0.0.1-SNAPSHOT.jar`
  3. `bash scripts/smoke.sh`（期望 SMOKE OK）
  4. `bash scripts/mq-drill.sh`（期望 DRILL OK：断 MQ 302 不丢、恢复追平）
  5. `curl -s http://localhost:8080/actuator/health`（db/redis/rabbit 组件可见；停 rabbitmq 后 503）
  6. 对账：`SELECT (SELECT COUNT(*) FROM lf_click_log) detail, (SELECT IFNULL(SUM(clicks),0) FROM lf_click_daily) aggregate;`（应相等）
- 审计关注点对照：正确性与租户隔离（P1 全套测试）、D7 双写与幂等（P2 全套 + mq-drill）、限流/缓存失效/创建幂等/并发（P3 全套）、有无越界做前端或 AI（无；本文件决策日志可查）

---

## v2.0.0 发布轮（审计 4 小项修复 + 文档重写 + 发版；feat/v2-release，发车基线 main@bdda9a1，2026-10-25）

> 触发：bw345 外部审计通过，留 4 个文档/残余风险小项；运营者指令「修完后同步文档防不一致、集中记录待办、README 模块化重写（设计/使用/上手+美化）、发布 2.0」。**范围**：无新功能代码；B10（Linux bench 复跑）与 QR+UTM 按拍板继续挂起，集中登记到 `docs/backlog.md`；开新轮仍需运营者指令。

### 审计 4 小项处置

| # | 审计意见 | 处置 | 证据 |
|---|---------|------|------|
| 1 | progress CI 段写「tip＝合并提交」，漏钉子 run id | B 波 3+4+5 章 CI 行改口：钉子 `bdda9a1`、run [40166917264](https://github.com/NorthAdb/LinkForge/actions/runs/40166917264) | 本文件「B 档波 3+4+5」章 CI 行 |
| 2 | README 未写 scrape-token 须对 Prometheus 进程可读（踩过权限坑） | README「快速上手·常见坑」+「可观测」节：容器内进程（nobody）须可读 `/etc/linkforge/scrape-token`，Linux 下 `chmod 644`，`printf '%s'` 写入避免尾随换行；Windows/Docker Desktop 不校验权限位 | README 两处 |
| 3 | 警告页尖括号转义被 URL 校验前置拦截，IT 只能间接证明 | 新增**纯单测** `GatePagesTest`（4 例）直接断言 warn/unlock/block 页对 `<`/`>`/`&`/引号的转义——纯 JUnit 不走 HTTP，URL 校验不构成障碍 | `src/test/java/com/linkforge/link/support/GatePagesTest.java` |
| 4 | 连接预算 256 是推导值，上限实际是 500 | B 波 3+4+5 章预算行注明口径：256＝「每上下文 Hikari 同时满池」最坏情形纸面推导（实际懒建连接、极少同时满载），配置侧天花板是共享容器 `--max-connections=500`，余量 244 | 本文件「B 档波 3+4+5」章预算行 |

### 文档与发版

- **README 全量重写**（v2.0.0）：徽章、目录、功能总览表、快速上手（前置表 + 五步 + 常见坑）、架构图更新（观测栈与门禁入图）、「跳转判定总顺序」12 步、模块详解 12 节（设计/使用/边界/指标四要素）、配置参考按域分组、指标参考表、测试与质量、版本历史表。**纪律**：既有审计核对过的事实、边界声明与数字原样迁移，未新增任何未实测数字；压测节继续声明「数字测于固定窗口时期，B10 复测为准」
- **`docs/backlog.md` 新建**：集中登记 8 项功能待办（B10、QR+UTM、Bloom 共享化/回收、L1 广播、维度扩展、AI 延期、云部署）+ 3 项运维项 + 本轮 4 小项闭环状态，每项带来源
- **版本**：pom `1.0.0→2.0.0`；`OpenApiInfoIT` 版本断言联动 `2.0.0`（build-info 随 pom，漏改必红）；pom description 去「(MVP)」；README jar 名引用 `target/linkforge-2.0.0.jar`
- **例数**：207→**211**（surefire 86→90，+4＝GatePagesTest；failsafe 119 与 `webhook-tz-it` fork 2 不变）；连接预算**零新增上下文**（GatePagesTest 纯单测不起 Spring）
- 本地验证：宿主 Maven `mvn -B test`（退出码显式判定）**90 单测全绿**，`GatePagesTest` 4/4

### CI 与发布

- 分支终态 run [40172677345](https://github.com/NorthAdb/LinkForge/actions/runs/40172677345) ✅（全部提交单次推送；CI 日志逐 suite 求和核实 **211 例**，`Tests run` 50 行零失败）；main 合并 run [40172956164](https://github.com/NorthAdb/LinkForge/actions/runs/40172956164) ✅（head＝合并提交 `5e776fd`）；钉子提交自身的 run 记录于 GitHub Release `v2.0.0` 说明与本地审计轨迹，不再追加仓库提交（一轮一钉纪律）
- 发布标识：git tag **`v2.0.0`**（annotated，打在钉子提交上）+ GitHub Release 附变更摘要；CI 工作流只监听分支 push，tag 推送不触发流水线

### 复审与 tag 重切

- **跨模型复审**（general-purpose 子代理，复核轨迹对仓库与 Actions 逐条验证）：211 例构成独立对账通过（90=83 方法+7 参数化；119=121 IT−2 时区 fork）；2 flags——①`scripts/bench.sh` 与 `scripts/outbox-drill.sh` 仍硬编码 `target/linkforge-1.0.0.jar`，v2.0.0 checkout 上直接 fail（正是「同步文档防不一致」的漏网点）；②README「tests 211 green」为静态徽章，例数变化会腐化（登记 backlog 运维项）
- ①修复＝两脚本改 **`ls target/linkforge-*.jar | sort -V | tail -1`** 取最新构建（`279c32e`，run [40174344584](https://github.com/NorthAdb/LinkForge/actions/runs/40174344584) ✅）——版本无关，后续升版不再破；progress 中两处 `linkforge-1.0.0.jar` 为历史快照，保留
- **tag `v2.0.0` 重切**：首打于钉子 `623d1e1`，复审发现 scripts 问题时 Release 已发布。为让 tag 快照自带修复，删除 tag 与 Release、重打于本提交并重建 Release（发布 15 分钟内、受众为运营者与审计，重切成本小于带病快照；此后「审计以 tag 为准」指向重切后的 tag）
- 轮内 runs 全景：分支 40172677345 ✅ → main 合并 40172956164 ✅ → 钉子 40173268350 ✅ → 复审修正 40174344584 ✅ → 本收口提交 run 记录于 Release 说明（一次复审重切，留档说明动因）
- 发布后 docs：`resume.md` 从 P4 时代口径（63 例/旧叙事）刷新至 **v2.0.0 口径**（211 例构成、滑窗限流、Outbox 修正语义、安全与开放能力、面试防线清单；bench 数字保留并保留时代免责），不改发布物与 tag
