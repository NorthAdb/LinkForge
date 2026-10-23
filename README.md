# LinkForge

多租户短链 + 访问分析后端（MVP 脚手架）。

面向 2027 春招实习简历项目：用真实路径练 **MySQL / Redis / RabbitMQ / JWT**，业务壳是短链创建与 302 跳转。  
本仓库为**自研骨架**，不是对任何开源短链项目的换皮。

## 技术栈（MVP）

| 层级 | 选型 |
|------|------|
| 语言 / 运行时 | Java 17 |
| 框架 | Spring Boot 3.3、Spring Security、Validation、Actuator |
| ORM | MyBatis-Plus |
| 主库 | MySQL 8 |
| 缓存 | Redis 7 |
| MQ | RabbitMQ 3.13（`ClickEventPublisher` 接口，便于以后换 RocketMQ） |
| 鉴权 | JWT（Bearer）；可后续换 Sa-Token |
| 构建 | Maven |

**MVP 明确不做**：AI / PostgreSQL / Supabase、Spring Cloud、Seata、完整压测、前端 SPA。

## 鉴权策略

**bootstrap 演示账号 + 开放注册共存**（D3）。两条路径：

1. **bootstrap**：启动时由 `BootstrapDataLoader` 幂等写入演示租户。口令不写在源码里，只从环境变量读取：
   - `LINKFORGE_BOOTSTRAP_TENANT`（默认 `demo`）
   - `LINKFORGE_BOOTSTRAP_USERNAME`（默认 `admin`）
   - `LINKFORGE_BOOTSTRAP_PASSWORD`（必填，无默认值）
2. **开放注册** `POST /api/auth/register`：自助创建一个全新租户及其首个用户，成功即返回 JWT。邀请码**未启用**（无邀请码逻辑）。注册按 IP 固定窗口限流，默认 5 次 / 10 分钟，超限 429；阈值可用 `LINKFORGE_REGISTER_MAX` / `LINKFORGE_REGISTER_WINDOW_SECONDS` 调整。

本地示例值只在 `.env.example`。

## 模块（包）结构

单 Maven 模块，按包划分边界：

```
com.linkforge
├── common      # 统一响应、异常
├── config      # MyBatis / Redis / RabbitMQ / 配置属性
├── security    # JWT、SecurityFilterChain
├── bootstrap   # 演示租户种子数据
├── user        # 租户 / 用户 / 登录
├── link        # 短链创建、302 跳转、健康检查
└── stats       # 点击落库 + RabbitMQ 发布/消费/幂等聚合 + 对账追平 + stats API
```

## 快速开始

前置：JDK 17、Maven 3.9+、Docker Desktop。没有本地 Maven 或 JDK17 时，编译可走下方 Docker 镜像路径。

### 1. 启动依赖（Docker Compose）

在仓库根目录执行。Compose 不再内置数据库或 MQ 口令，必须先有 `.env`：

```bash
cp .env.example .env   # 本地示例；不要提交 .env
docker compose up -d
docker compose ps      # 确认 mysql / redis / rabbitmq healthy
```

Compose 读取 `MYSQL_ROOT_PASSWORD`、`MYSQL_PASSWORD`、`RABBITMQ_PASSWORD`（以及可选的库名、用户名、端口）。用户名默认 `linkforge`，口令只来自 `.env`。

### 2. 编译

```bash
mvn -q -DskipTests package
```

无本地 Maven / JDK17 时，用 Docker 里的 JDK17 Maven 构建（Windows PowerShell 把 `$PWD` 换成 `${PWD}`）：

```bash
docker run --rm -v "$PWD":/app -v linkforge_m2:/root/.m2 -w /app \
  maven:3.9-eclipse-temurin-17 mvn -B package
```

### 3. 运行应用

应用不会自动加载 `.env`。先导出变量再启动：

```bash
set -a
source .env
set +a
mvn spring-boot:run
# 或
java -jar target/linkforge-0.0.1-SNAPSHOT.jar
```

默认端口 `8080`，profile `dev`。未设置下列必填变量时，Spring 占位符无法解析，进程起不来：

| 变量 | 作用 |
|------|------|
| `LINKFORGE_BOOTSTRAP_PASSWORD` | bootstrap 用户口令 |
| `SPRING_DATASOURCE_PASSWORD` | MySQL 用户口令 |
| `SPRING_RABBITMQ_PASSWORD` | RabbitMQ 口令 |
| `JWT_SECRET` | JWT HMAC 密钥，至少 32 字符 |

可选、源码里有非敏感默认值：`LINKFORGE_BOOTSTRAP_TENANT`、`LINKFORGE_BOOTSTRAP_USERNAME`、`SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATA_REDIS_HOST`、`SPRING_DATA_REDIS_PORT`、`SPRING_RABBITMQ_HOST`、`SPRING_RABBITMQ_PORT`、`SPRING_RABBITMQ_USERNAME`。Redis 口令默认空（`SPRING_DATA_REDIS_PASSWORD`）。

### 4. 冒烟

```bash
# 健康检查
curl -s http://localhost:8080/api/health
curl -s http://localhost:8080/actuator/health

# 登录
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"tenantName":"'"${LINKFORGE_BOOTSTRAP_TENANT:-demo}"'","username":"'"${LINKFORGE_BOOTSTRAP_USERNAME:-admin}"'","password":"'"${LINKFORGE_BOOTSTRAP_PASSWORD}"'"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["token"])')

# 创建短链
curl -s -X POST http://localhost:8080/api/links \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"originalUrl":"https://example.com","title":"demo"}'

# 302 跳转（把 CODE 换成返回的 code；用 GET，`-I` 发 HEAD 会被安全链按未鉴权拒绝 403）
curl -s -D - -o /dev/null http://localhost:8080/r/CODE
```

一键冒烟（仓库根目录执行，自动做 health → 登录 → 建链 → 302 → Redis 校验 → 注册 → 生命周期，口令读 `.env`）：

```bash
bash scripts/smoke.sh
```

MQ 宕机演练（基线聚合 → 停 Rabbit 全部 302 → 恢复后追平）：

```bash
bash scripts/mq-drill.sh
```

## 已实现接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/health` | 应用健康（无需鉴权） |
| GET | `/actuator/health` | Actuator |
| POST | `/api/auth/login` | JWT 登录 |
| POST | `/api/auth/register` | 开放注册（新建租户+用户，返回 JWT；按 IP 限流） |
| POST | `/api/links` | 创建短链（需 Bearer Token；可选 `expireAt`，必须为未来时间；可选 `Idempotency-Key` 请求头实现幂等重放） |
| GET | `/api/links?page=&size=` | 分页列表（仅本人创建的链，size 1..100 默认 20） |
| GET | `/api/links/{code}` | 详情：目标 URL、状态、过期时间、创建/更新时间 |
| PATCH | `/api/links/{code}` | 改目标 URL / 备注；成功后删除 Redis 缓存 |
| POST | `/api/links/{code}/disable` | 禁用；成功后删除 Redis 缓存 |
| GET | `/api/links/{code}/stats?days=7` | 点击统计（仅链主）：总量 + 按天序列，days 1..90 |
| GET | `/r/{code}` | 302 跳转；Redis 缓存优先，未命中回源 MySQL；同步写 `lf_click_log` |

**错误码语义（已写死）**：跳转遇 **禁用或不存在 → 404**，**过期 → 410**；管理接口的越权访问统一 **404**（不泄露他人短码存在性）；注册租户名冲突 **409**；触发限流 **429**。

**限流（P3）**：注册按 IP 5 次/10 分钟；创建按用户 30 次/分钟；公开跳转按 IP 300 次/分钟。全部为 Redis 固定窗口，超限 429，阈值见下方环境变量。限流器在 Redis 故障时放行（degrade open）。客户端 IP 默认取 socket 地址、**不信任** `X-Forwarded-For`（该头可伪造、会被用来绕限流）；仅在可信反向代理后部署时设 `LINKFORGE_TRUST_XFF=true`。

**缓存一致性（P3）**：更新短链时**写穿**（立即写入新值）并安排 1 秒后的延迟双删，清除并发读旧库行回填的脏值；禁用同理（先删 + 延迟双删）。最终一致窗口约 1 秒，上限为 TTL（默认 3600 秒）。过期链路不依赖缓存（每次跳转都校验 DB 行状态）。

**创建幂等与并发（P3）**：携带 `Idempotency-Key` 头的重复提交返回同一短码（Redis `pending→code` 两段式，并发同键只建一条，映射保留 24h）；短码生成靠 `uk_code` 唯一索引兜底，撞码自动重试新码，并发创建不会产生重复短码。

跳转路径缓存键：`linkforge:code:{code}`。更新/禁用会主动删除该键，下一次跳转回源后重建。  

## 点击统计管道（D7 双写）

跳转产生一次点击后会**双写**：

1. **同步明细**：插入 `lf_click_log`（开关 `LINKFORGE_SYNC_CLICK_ENABLED`，默认开）。
2. **异步聚合**：发布 `ClickEvent` 到 RabbitMQ（开关 `LINKFORGE_ASYNC_CLICK_ENABLED`，默认开），真实消费者按 `eventId` 幂等写入 `lf_consumed_event` + 按天累加 `lf_click_daily`。

幂等设计：`eventId = "log-{click_log.id}"`（确定性），实时消费与对账重放共用同一键，同一次点击无论被投递多少次只计一次。消费失败的消息进死信队列 `linkforge.click.dlq`（不回队热循环）。

**对账与追平**：`ClickLogReconciler` 定时（`LINKFORGE_MQ_RECONCILE_FIXED_DELAY_MS`，默认 30s）扫描 `lf_click_log` 中尚未出现在 `lf_consumed_event` 的行并重放聚合——MQ 宕机期间发布失败的点击由它自动补齐（`scripts/mq-drill.sh` 演练此场景）。手工对账：

```sql
-- 明细与聚合应一致（sync 开关开着时；库中不应有测试注入的合成事件）
SELECT (SELECT COUNT(*) FROM lf_click_log) AS detail,
       (SELECT IFNULL(SUM(clicks),0) FROM lf_click_daily) AS aggregate;
```

**何时可关同步**：连续观察 `detail == aggregate` 且 MQ 稳定后，设 `LINKFORGE_SYNC_CLICK_ENABLED=false` 切纯异步。注意：关同步后新点击的 eventId 退化为随机 UUID（不再有 click_log id），对账器只负责收敛历史行。MQ：`linkforge.click.exchange` → `linkforge.click.queue`（DLX：`linkforge.click.dlx` / `linkforge.click.dlq`）。

**存量库迁移注记**：`lf_click_daily` / `lf_consumed_event` 两张表只会在**全新数据卷**上由 schema.sql 自动创建；已有旧卷的库需手动执行 `src/main/resources/db/schema.sql` 中对应的两段 `CREATE TABLE IF NOT EXISTS`（幂等，可重复跑）。

## 当前状态 vs 后续

| 阶段 | 内容 | 状态 |
|------|------|------|
| **P0–P3（当前 main）** | Compose 三件套、登录/注册（限流）、短链 CRUD/禁用/过期、缓存跳转（写穿+双删）、MQ 异步聚合（幂等/对账/断链追平）、创建幂等、并发不撞码、限流、Actuator 组件健康 | ✅ 已交付（见 docs/progress.md） |
| **P4** | CI、bench.md 压测数字、测试矩阵归档、简历条目 | 待外部审计后另定 |
| **P5** | 自定义短码、CSV 导出、极简静态管理页、OpenAPI | 待外部审计后另定 |

## 配置要点

见 `src/main/resources/application.yml`。口令与 `JWT_SECRET` 只有环境变量，没有写在 yml 或 Java 默认值里。本地示例见 `.env.example`。

- `linkforge.mq.async-click-enabled` 控制是否发 MQ
- `LINKFORGE_REGISTER_MAX` / `LINKFORGE_REGISTER_WINDOW_SECONDS` 注册限流阈值（默认 5 / 600 秒）
- `LINKFORGE_CREATE_RATE_MAX` / `LINKFORGE_CREATE_RATE_WINDOW_SECONDS` 创建限流（默认 30 / 60 秒，按用户）
- `LINKFORGE_REDIRECT_RATE_MAX` / `LINKFORGE_REDIRECT_RATE_WINDOW_SECONDS` 跳转限流（默认 300 / 60 秒，按 IP）
- `LINKFORGE_IDEMPOTENCY_TTL_SECONDS` 创建幂等键保留时长（默认 86400 秒）
- `LINKFORGE_TRUST_XFF` 是否信任 X-Forwarded-For（默认 false，仅在可信反代后开启）
- `/actuator/health` 展示组件细节（db / redis / rabbit / diskSpace），依赖 DOWN 时整体 503

## 许可证

个人学习 / 面试项目，未设开源许可证条款时可按私有仓库使用。
