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

## 鉴权策略（已写死在代码注释里）

采用 **固定 bootstrap 租户 + JWT 登录**（不做公开注册）：

- 租户名：`demo`
- 用户名：`admin`
- 密码：`admin123`

启动时由 `BootstrapDataLoader` 幂等写入。可用配置 `linkforge.bootstrap.*` 修改。

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
└── stats       # 点击落库 + MQ 抽象与 stub 消费者
```

## 快速开始

### 1. 启动依赖（Docker Compose）

```bash
cd /workspace/linkforge
cp .env.example .env   # 可选
docker compose up -d
docker compose ps      # 确认 mysql / redis / rabbitmq healthy
```

默认账号见 `.env.example`（MySQL `linkforge/linkforge_pass`，RabbitMQ 同理）。

### 2. 编译

```bash
mvn -q -DskipTests package
```

### 3. 运行应用

```bash
mvn spring-boot:run
# 或
java -jar target/linkforge-0.0.1-SNAPSHOT.jar
```

默认端口 `8080`，profile `dev`。

### 4. 冒烟

```bash
# 健康检查
curl -s http://localhost:8080/api/health
curl -s http://localhost:8080/actuator/health

# 登录
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"tenantName":"demo","username":"admin","password":"admin123"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["token"])')

# 创建短链
curl -s -X POST http://localhost:8080/api/links \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"originalUrl":"https://example.com","title":"demo"}'

# 302 跳转（把 CODE 换成返回的 code）
curl -sI http://localhost:8080/r/CODE
```

## MVP 已实现接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/health` | 应用健康（无需鉴权） |
| GET | `/actuator/health` | Actuator |
| POST | `/api/auth/login` | JWT 登录 |
| POST | `/api/links` | 创建短链（需 Bearer Token） |
| GET | `/r/{code}` | 302 跳转；Redis 缓存优先，未命中回源 MySQL；同步写 `lf_click_log` |

跳转路径缓存键：`linkforge:code:{code}`。  
MQ：默认 `linkforge.mq.async-click-enabled=false`（只同步写库）。打开后会额外投递到 RabbitMQ，消费者目前为 **stub 打日志**。

## MVP vs 后续

| 阶段 | 内容 |
|------|------|
| **MVP（当前）** | Compose 三件套、登录、创建短链、缓存跳转、同步点击日志、MQ 接口 + stub |
| **下一期** | MQ 异步聚合统计、幂等、限流、短链过期管理 API、压测量化 |
| **再后** | AI 模块（独立 PostgreSQL/pgvector，可用 Supabase 仅托管 PG）、自然语言查统计 |

## 配置要点

见 `src/main/resources/application.yml`：

- 数据源 / Redis / RabbitMQ 均可用环境变量覆盖
- `JWT_SECRET` 至少 32 字符
- `linkforge.mq.async-click-enabled` 控制是否发 MQ

## 许可证

个人学习 / 面试项目，未设开源许可证条款时可按私有仓库使用。
