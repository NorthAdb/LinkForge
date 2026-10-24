# LinkForge 开发进度与验收记录

> 执行者：GLM（zcode + pstack poteto-mode），按《LinkForge 实施与验收方案》顺序推进。
> 本文件是审计核对的主要证据：每阶段验收勾选、真实执行的命令与输出摘要、决策记录。
> **当前 main tip**：`ccff8026fa491d529d2f06e26d91bc6d30d4a939`（2026-10-24，深化 A9 终态＝合并 `d6c97e0` + docs 收口 `ee86f01` + 纯钉子提交 `ccff802`，`git rev-parse HEAD` 可对）。
> **历史快照说明**：下文 P0–P5 / R2–R4 各节及其引用的 SHA 是**当时的历史快照，非当前 tip**；当前轮次证据以「深化」小节为准。
> 本轮边界：P0–P5、v1.0.0 与深化 A2/A3/B12、A6、A7、A8、A9 均已交付并通过审计（A9 审计 PASS_WITH_NITS @ `ccff802`，无 must-fix）；本轮为**深化 A10 singleflight 防热点击穿**（+ A9 审计收口 + V4 短码区分大小写），交接单 `记录追踪/05-阶段交接/LinkForge-phase-A10-singleflight-handoff.md`。

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
