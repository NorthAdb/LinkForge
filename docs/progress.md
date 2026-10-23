# LinkForge 开发进度与验收记录

> 执行者：GLM（zcode + pstack poteto-mode），按《LinkForge 实施与验收方案》P0→P3 顺序推进。
> 本文件是审计核对的主要证据：每阶段验收勾选、真实执行的命令与输出摘要、决策记录。
> 本轮边界：只做 P0–P3；P3 完成后停下等「实习JAVA_AI」审计，不开 P4/P5。

## 状态总览

| 阶段 | 主题 | 分支 | 状态 |
|------|------|------|------|
| P0 | 落地卫生 | feat/p0-hygiene | ✅ 完成（见下） |
| P1 | 短链闭环 | feat/p1-links | ✅ 完成（见下） |
| P2 | 异步分析 | feat/p2-async-stats | ✅ 完成（见下） |
| P3 | 可靠性 | feat/p3-reliability | 未开始 |

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
