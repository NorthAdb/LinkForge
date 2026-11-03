# 15.3 Compose 与故障演练：五个 service、四个演练脚本与 prod 收紧

> 全课程收官课。这一课从测试转向运维现场：`docker-compose.yml` 的五个 service 各管什么、为什么观测栈的端口只绑 127.0.0.1；MySQL 数据卷第一次初始化埋下的口令坑；四个演练脚本（smoke、mq-drill、outbox-drill、fault-drill）如何把模块 8 到 13 的降级设计变成一条条可跑的断言；`migrate.sh` 如何包装 Flyway；prod profile 用哪三层开关一键收紧。读完你应能在自己机器上跑通一次 fault-drill 并读懂每一行 PASS。

## 1. 本节学习目标

1. 逐个说出五个 service 的镜像、端口、健康检查、卷与 profile，并解释观测栈端口绑 127.0.0.1 的区别对待。
2. 复述两个 MySQL 运维坑：首次初始化约 5 分钟的 healthy 等待，口令写进数据卷后改 `.env` 无效。
3. 读懂 Prometheus 抓宿主机应用的三件套：`host.docker.internal:host-gateway`、Bearer token 文件、`chmod 644`。
4. 逐段走通四个演练脚本，说出每段的 PASS 行在断言什么、SKIP 语义何时触发。
5. 说出 prod profile 的三层收紧开关及各层的位置（静态资源/springdoc/安全链），以及 `ProdProfileIT` 如何把收紧钉进回归。

## 2. 前置知识

- **docker compose（框架行为）**：用一份 `docker-compose.yml` 声明多容器应用。`services` 下每项是一个容器，`volumes` 顶层键是命名卷（数据独立于容器生命周期），`profiles` 让某些 service 只在被点名时启动。
- **`${VAR:?err}` 插值语法（框架行为）**：compose 读环境变量替换 `${...}`。`:-默认值` 给缺省，`:?` 表示缺失时直接报错拒启。这是 compose 层的必填校验。
- **healthcheck（框架行为）**：容器内定期执行的探活命令，`docker compose ps` 显示 `healthy`。它是容器层协议，与 Spring Actuator 的 health 端点无关；Actuator 侧本课只用到 `/actuator/prometheus` 的 token 门禁（模块 14 已讲）。
- **host.docker.internal（框架行为）**：容器内访问宿主机的特殊域名。Linux 原生 Docker 默认没有它，需要 `extra_hosts: host.docker.internal:host-gateway` 显式补上。
- **bash 的 trap（语言知识）**：`trap 函数 EXIT` 注册退出钩子，无论脚本成功、失败还是被中断都会执行，演练脚本用它保证「停掉的服务一定被拉回来」。

## 3. 本节涉及的源码

| 文件 | 行数 | 职责 | 阅读顺序 |
|----|----|----|----|
| `docker-compose.yml` | 101 | 5 个 service + 5 个命名卷，本课主角 | 1 |
| `ops/observability/prometheus.yml` | 13 | 抓取配置：目标、路径、token 文件 | 2 |
| `scripts/smoke.sh` | 94 | 冒烟八步断言 | 3 |
| `scripts/mq-drill.sh` | 76 | 停 MQ 与追平 | 4 |
| `scripts/outbox-drill.sh` | 100 | 关同步写后断链追平 | 5 |
| `scripts/fault-drill.sh` | 255 | redis/webhook/mysql 只读三段降级 | 6 |
| `scripts/migrate.sh` | 25 | flyway-maven-plugin 包装 | 7 |
| `scripts/bench.sh` | 107 | wrk 压测三场景（只读头注释与主流程） | 8 |
| `src/main/resources/application.yml` | 266 | `:247-267` prod 文档三层收紧 | 9 |
| `security/SecurityConfig.java` + `ProdProfileIT.java` | — | `:29-31` `@Value` 读 `docs-enabled`；收紧回归钉 | 10 |

## 4. 类的定位（宿主机 JVM 与编排层）

这套部署形态一句话：**应用进程永远跑在宿主机 JVM 上，仓库里没有 Dockerfile**。Compose 只装依赖（MySQL/Redis/RabbitMQ）与观测（Prometheus/Grafana）。因此 `docker-compose.yml` 定位是「依赖栈清单」而不是「应用部署单元」，六个脚本定位是「运维肌肉记忆」：每个脚本自带前置检查、fail-fast 断言与 trap 清理，任何一段红都指明下一步。这与 15.1/15.2 的分层一致：IT 在 CI 里证明降级逻辑，脚本在开发者机器上证明部署现场。[01-runtime-deployment.html](../../diagrams/archify/html/01-runtime-deployment.html) 画的正是这个形态：宿主机 JVM 居中，Compose 三依赖在侧，观测栈挂在外圈。

## 5. 核心源码详解

### 5.1 五个 service 总览与端口策略

| service | 镜像 | 端口映射 | profile | 卷 |
|----|----|----|----|----|
| mysql | mysql:8.0 | `${MYSQL_PORT:-3306}:3306` | 默认 | linkforge_mysql_data |
| redis | redis:7-alpine | `${REDIS_PORT:-6379}:6379` | 默认 | linkforge_redis_data |
| rabbitmq | rabbitmq:3.13-management-alpine | 5672 + 管理台 15672 | 默认 | linkforge_rabbitmq_data |
| prometheus | prom/prometheus:v2.55.1 | `127.0.0.1:9090:9090` | observability | linkforge_prometheus_data |
| grafana | grafana-oss:11.3.0 | `127.0.0.1:3001:3000` | observability | linkforge_grafana_data |

（归纳自 `docker-compose.yml:1-101`。）

注意端口策略的区别对待：三个依赖的端口映射到宿主机全部网卡（开发依赖，应用要连，且有口令保护）；两个观测 service 只绑 `127.0.0.1`（`docker-compose.yml:71`、`:88`）。理由是暴露面：Prometheus 与 Grafana 的信息（指标序列、仪表盘、默认口令）不该被局域网其他机器摸到，而本机浏览器访问不受影响。observability 是 opt-in profile：默认 `docker compose up -d` 只起三个依赖（`docker-compose.yml:67`、`:81`），README 快速上手明确说五个容器不是通过条件（`README.md:92`）。

### 5.2 mysql：健康检查、字符集与数据卷里的口令

```yaml
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:?set MYSQL_ROOT_PASSWORD}
      MYSQL_DATABASE: ${MYSQL_DATABASE:-linkforge}
      MYSQL_USER: ${MYSQL_USER:-linkforge}
      MYSQL_PASSWORD: ${MYSQL_PASSWORD:?set MYSQL_PASSWORD}
    ports:
      - "${MYSQL_PORT:-3306}:3306"
    volumes:
      - linkforge_mysql_data:/var/lib/mysql
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_unicode_ci
      - --default-authentication-plugin=caching_sha2_password
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "127.0.0.1", "-uroot", "-p${MYSQL_ROOT_PASSWORD}"]
      interval: 10s
      retries: 10
      start_period: 30s
```

（逐字摘自 `docker-compose.yml:2-24`，省略 `container_name`、`restart` 与 `timeout: 5s` 三处。）

三个点。第一，`:?` 校验：根口令与应用口令缺失直接拒启，可省的只有库名、用户名、端口。第二，启动命令把字符集钉成 utf8mb4（与 V1 迁移的表设计一致），认证插件钉成 `caching_sha2_password`（MySQL 8 默认，应用侧 URL 带 `allowPublicKeyRetrieval=true` 配合）。第三，健康检查用 `mysqladmin ping`，`start_period: 30s` 加 10 次重试给足初始化时间，因为**首次初始化约 5 分钟**（`README.md:94`）：数据卷从零建系统表加用户，这期间 `ps` 里 mysql 一直不是 healthy，应用提前启动只会看到数据源连接失败。

**口令写进数据卷的坑**（`README.md:269`）：`MYSQL_PASSWORD` 只在卷 `linkforge_mysql_data` 第一次初始化时创建账号。之后只改 `.env` 里的口令，MySQL 容器里的账号不变，应用会报 `Access denied for user 'linkforge'`（1045）。正确做法是让 `SPRING_DATASOURCE_PASSWORD` 与当初的口令保持一致；确认可清数据时 `docker compose down -v` 删卷重来。`docker compose stop` 只停容器，卷还在，所以改口令不生效。

### 5.3 redis 与 rabbitmq：最少配置的两个依赖

redis（`docker-compose.yml:26-39`）无口令、挂 `/data` 命名卷、健康检查 `redis-cli ping`。它是模块 8 的缓存与限流存储，fail-open 设计让它的存在是加速器而非依赖（15.1 的 IT 与本课 fault-drill 第一段都验证这点）。rabbitmq（`:41-58`）要求 `RABBITMQ_PASSWORD` 必填，暴露 AMQP 5672 与管理台 15672，健康检查 `rabbitmq-diagnostics -q ping`，`start_period: 20s`。它是模块 10/11 的点击管道主干，也是两个 MQ 演练脚本的操作对象。

### 5.4 prometheus：抓宿主机应用的三件套

```yaml
  # B1 observability stack: opt-in only (`docker compose --profile observability up -d`).
  # Prometheus scrapes the app on the HOST (the app is not containerized), so the
  # scrape token file must hold the same value as LINKFORGE_METRICS_SCRAPE_TOKEN.
  prometheus:
    image: prom/prometheus:v2.55.1
    profiles: ["observability"]
    extra_hosts:
      - "host.docker.internal:host-gateway"
    ports:
      - "127.0.0.1:9090:9090"
    volumes:
      - ./ops/observability/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - ./ops/observability/scrape-token:/etc/linkforge/scrape-token:ro
```

（逐字摘自 `docker-compose.yml:60-75`，省略 `container_name`、`restart` 与时序数据卷 `linkforge_prometheus_data` 三处；profile 已在 5.1 表。）

三个配置件协作。第一，抓取目标是宿主机应用：`prometheus.yml:13` 写 `targets: ["host.docker.internal:8080"]`，而 Linux 原生 Docker 没有这个域名，所以 `extra_hosts` 加 `host-gateway` 映射（`docker-compose.yml:68-69`）。第二，`/actuator/prometheus` 是 token 门禁的（空 token 即 fail-closed，模块 14），Prometheus 侧用 Bearer 文件认证：`prometheus.yml:11` 的 `credentials_file: /etc/linkforge/scrape-token`，文件由宿主机只读挂载进来。第三，**文件可读性坑**（`README.md:271`，Linux 踩坑实录）：容器内进程以 nobody 运行，宿主机上文件若是 `600` 且属主是登录用户，容器内读取被拒，target 永久 down。Linux 下 `chmod 644 ops/observability/scrape-token`；Windows/Docker Desktop 不校验 Unix 权限位，无此坑。文件内容必须与 `.env` 的 `LINKFORGE_METRICS_SCRAPE_TOKEN` 逐字一致且末尾不带换行。

grafana（`docker-compose.yml:77-94`）把宿主机的 provisioning 目录只读挂进去，数据源与仪表盘开机即配（模块 14 的看板），端口 `127.0.0.1:3001:3000`，`depends_on: prometheus`，管理口令给的是本地演示默认值并注释了覆盖方式（`:83-85`）。

### 5.5 smoke.sh：八步断言

头注释自述（`smoke.sh:1-6`）：health 到 login 到 create 到 302 到 redis 行，再走注册与生命周期；只需 bash + curl。八步（全部行号在 `smoke.sh`）：

1. `GET /api/health` 必须 200（`:21-23`）。
2. bootstrap 账号登录拿 token（`:25-31`），口令从 `.env` 读，没有就报错退出（`:12-15`）。
3. 创建短链拿 code（`:33-41`）。
4. 跳转断言：请求头里必须出现 302 且 `Location` 逐字等于目标 URL（`:43-48`）。`:43` 的注释值得背下来：用 GET 不用 HEAD，因为 HEAD 不记点击，后面第 7 步的统计断言需要这一笔点击。
5. Redis 缓存行存在且值正确（`:50-57`）。容器不在时打印 SKIP 继续，冒烟不依赖观测栈。
6. 开放注册新租户拿第二个 token（`:59-66`），验证注册通道与 bootstrap 共存。
7. 生命周期：建链、查详情 200、PATCH 改 URL、再跳转断言 `Location` 已是新 URL（`:68-85`）。这一步顺手证明了更新后的缓存失效：如果 L2 里还是旧值，Location 会泄露旧 URL。
8. disable 后跳转必须 404（`:87-91`）。八步全过打 `SMOKE OK`（`:93`），断言的是「部署现场的最小可用集」。

### 5.6 mq-drill.sh：停 MQ 全 302，追平 N+M

头注释（`mq-drill.sh:1-7`）写明三段式：健康基线 N 次跳转在统计里收敛；停 RabbitMQ 后 M 次跳转仍全 302（发布失败降级、同步落库保数据）；重启 RabbitMQ 后统计经对账循环收敛到 N+M。关键实现：

- 退出钩子兜底：`trap restore_rabbit EXIT`（`mq-drill.sh:21-28`），无论脚本怎么退出，只要 rabbit 不是脚本自己拉起的，清理函数就把它启动回来。演练脚本的第一纪律：现场必须比来时干净。
- 基线段：N=15 次跳转后轮询统计接口到 `totalClicks=15`（`:40-46`），等的是活 MQ 路径（落库、outbox、MQ、消费、聚合）。
- 故障段：`docker compose stop rabbitmq`（`:49`），M=10 次跳转逐次断言 302（`:52-55`）。这一步断言的是「跳转不依赖 MQ」，即模块 10 的发布失败降级。
- 恢复段：重启后先等容器 healthy（`:59-66`），再轮询 90×2 秒等 `totalClicks=N+M`（`:67-74`）。追平的执行者是 `ClickLogReconciler` 对账任务（14.3 的地图上有它的位置），这里从运维侧验证了它的存在价值。

### 5.7 outbox-drill.sh：自起 8082 实例的断链追平

这个脚本与 mq-drill 的区别在起点：它**自己启动一个专用应用实例**，默认端口 8082，环境变量 `LINKFORGE_SYNC_CLICK_ENABLED=false`（`outbox-drill.sh:44-51`），即「同步落库关掉，只走 outbox 中继」的最坏组合。头注释（`:1-8`）点题：A7 之前的旧故事是「同步写关了加 MQ 断了＝点击全丢」，这个脚本证明旧故事已死。

故障段断言四件事（`:62-83`）：M 次跳转全 302；`lf_outbox` 里按 payload 的 code 精确数出 PENDING=M（`:70-71`，SQL 里用 `JSON_EXTRACT` 定位）；`lf_click_log` 热路径明细必须 0 行（`:72-73`，同步写确实关了）；中继重试计数 `attempts>=1` 且日志里出现「outbox relay deferred」行（`:74-82`）。恢复段断言四件事（`:85-98`）：聚合收敛到 M、消费者回填的明细收敛到 M、outbox 状态收敛到 SENT=M、UV 查询非零。段间口径与 15.1 的断言纪律一致：行数打在同步明细表，聚合只等收敛。另有一处工程细节值得学：脚本 `:24-28` 特意用 `set -a; . ./.env; set +a` 让 `.env` 覆盖调用方 shell 里可能残留的旧变量，注释写明理由「这个脚本自己启动应用，.env 必须赢过 shell 里的陈年 JWT_SECRET」。

### 5.8 fault-drill.sh：三段降级与 SKIP 语义

C4 交付的故障演练（头注释 `fault-drill.sh:1-11`）：应用降级而不是陪葬。它同样自起专用实例（8083），并顺手自配 webhook 主密钥与抓取 token（`:41-48`）。三段：

**第一段 redis 停服**（`:97-142`）：`docker compose stop redis` 后，带 `Idempotency-Key` 的创建仍 200（幂等 fail-open，`:113-115`），跳转仍 302（L2 fail-open，`:116-117`），并用 `/actuator/prometheus` 的输出验证打点真的发生了：`linkforge_cache_failopen_total{op="l2_get"}` 与 `op="idem_(acquire|get|settle)"` 各 grep 一次（`:118-122`）。恢复后断言自愈：新链的 L2 缓存值回填、跳转 302（`:126-142`）。这一段是模块 8 fail-open 设计的现场复验，指标断言比状态码断言更深一层：不仅行为对，连「为什么对」的证据（failopen 计数）都在。

**第二段 webhook 5xx**（`:144-214`）：用 PowerShell 起 127.0.0.1:8099 的全 500 桩（`:146-172`），配 endpoint 后点击，断言投递行最终 `FAILED|3|500`（`:206-210`，3 次尝试、末次状态码 500，与应用的 `max-attempts=3` 环境变量对应 `:74`），且重试期间跳转始终 302（`:198-201`）。**SKIP 语义**：没有 PowerShell 时打印 `SKIP webhook` 继续下一段（`:146-148`），演练不因平台缺件而整体报废。

**第三段 mysql 只读**（`:216-252`）：`SET GLOBAL read_only=1` 后创建必须失败（`:228-232`），GET 跳转仍 302（`:233-235`，`:233` 注释点明 S1 fail-open：点击写失败被调用点捕获，302 不依赖记账）、HEAD 也 302、详情与统计读 200；`read_only=0` 后写入恢复（`:244-251`）。**SKIP 语义**：`.env` 没给 `MYSQL_ROOT_PASSWORD` 就整段跳过（`:217-219`），因为只读开关需要 root。

三段共享一个清理 trap（`:54-70`）：杀应用、按端口杀 5xx 桩（`:60` 的 `taskkill` 是给 Windows 进程补刀，端口范围限定保证不误伤）、拉回 redis、清掉 read_only。现场还原由脚本结构保证，不靠操作者自觉。

### 5.9 migrate.sh 与 bench.sh

`migrate.sh` 是 flyway-maven-plugin 的薄包装（`migrate.sh:20-23`）：从 `.env` 读连接串，调 `mvn org.flywaydb:flyway-maven-plugin:migrate -Dflyway.url=... -Dflyway.user=... -Dflyway.password=...`。它跑的是与应用启动完全相同的 `db/migration` 目录（插件配置在 `pom.xml:250-278`，`baselineOnMigrate=true`、`baselineVersion=1` 与应用侧一致），所以「存量库升级」既可以在应用启动时发生，也可以用这个脚本预演。头注释（`:1-7`）交代了适用边界：比 P2 还老的库缺统计表，要先手工应用一次 schema.sql 再跑本脚本。

`bench.sh`（只读头注释与主流程）用 Docker 里的 wrk 打宿主机应用，三个跳转场景隔离同步写的成本：default（同步写开+MQ 开）、no-mq、no-sync（`:64-78`），另有 health 基线与 t4c50 的 headline（`:82-86`）。注意口径：README 明确其数字是 Windows 历史实测，不代表当前版本（`README.md:15`），教材不引用任何「当前性能」。

### 5.10 prod profile 一键收紧：三层开关

prod 文档全文只有二十行（`application.yml:247-267`），收紧分三层：

```yaml
spring:
  web:
    resources:
      # no static console in prod
      add-mappings: false
springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false
linkforge:
  docs-enabled: false
```

（摘自 `application.yml:250-261` 的 prod 文档，省略 `on-profile: prod` 激活头；同文档 `:265-266` 还把健康详情收窄到 `show-details: when_authorized`。）

第一层 `add-mappings: false`（`:254`）关掉静态资源映射，根路径的极简控制台没了；第二层 springdoc 两项 `enabled: false`（`:256-259`）关掉文档生成；第三层 `linkforge.docs-enabled: false`（`:261`）由安全链读取，`SecurityConfig.java:29-31` 用 `@Value("${linkforge.docs-enabled:true}")` 直接注入字段（默认 true），false 时 `/v3/api-docs/**`、`/swagger-ui/**`、`/swagger-ui.html` 在过滤器链被拒（`SecurityConfig.java:28` 注释：docs are denied at the filter chain before reaching springdoc）。三层的意义是纵深防御：即使有人误开 springdoc，安全链仍然拒之门外。

`docs-enabled` 不是 `LinkForgeProperties` 的字段，是 `@Value` 读取的独立开关，这是本项目里刻意的例外（多数业务配置收口在 Properties 类，这个开关属于安全边界）。行为由 `ProdProfileIT` 钉进回归：`@ActiveProfiles({"test", "prod"})`（`ProdProfileIT.java:21`）叠加 profile，断言 health 不暴露 components（`:28`）、三个文档/控制台路径未登录访问都是 401（`:31-33`，注释解释了为什么是 401 不是 403：未认证请求先触发 401 入口点）、而跳转路径保持公开 404/302 语义（`:35-36`）。

## 6. 技术原理补充

- **compose profiles（是什么/为什么需要）**：给 service 打标签，不带该 profile 启动时整个 service 被跳过。观测栈放进 profile 的原因：依赖三件套每次开发都要，观测栈只在做指标联调时才开，默认少起两个容器省内存。
- **命名卷 vs 绑定挂载（是什么）**：`linkforge_mysql_data:/var/lib/mysql` 是命名卷，数据由 Docker 管理、容器删除仍在；`./ops/observability/prometheus.yml:/etc/...` 是绑定挂载，宿主机文件直接映射（都带 `:ro` 只读）。前者存状态，后者发配置。
- **健康检查的层次（是什么/不用会怎样）**：compose healthcheck 是容器内命令探活，脚本（`mq-drill.sh:62`）用 `docker inspect` 读它的结果来决定何时继续。没有它，脚本只能 sleep 硬等，慢且抖。
- **trap 清理（是什么）**：演练脚本都会「弄坏」环境，trap 保证中断也恢复。三个脚本清理的内容与破坏对称：mq-drill 只动 rabbit，outbox-drill 还杀自己起的应用，fault-drill 还管 redis、read_only 与 5xx 桩。另注意 prod 文档（5.10）故意不含 datasource/jdbc 段，连接信息从环境变量来，多文档 YAML 的配置面越小越不易泄密。

## 7. 可视化

- [01-runtime-deployment.html](../../diagrams/archify/html/01-runtime-deployment.html)（运行与部署图）：宿主机 JVM 与 Compose 的分工是本课 5.1-5.5 的图形版。阅读顺序：先看宿主机框里的应用进程，再看三个依赖容器，最后看外圈观测栈与 `host.docker.internal` 的抓取边。
- [08-scheduled-tasks.html](../../diagrams/archify/html/08-scheduled-tasks.html)（定时任务地图）：mq-drill/outbox-drill 恢复段的「追平」执行者是图中的 `ClickLogReconciler` 节点；outbox-drill 断链期的重试计数对应 `ClickOutboxRelay` 节点。看完演练脚本回看这张图，能对上「谁在后台把数据追平」。

## 8. 项目中的真实执行过程

走一遍 fault-drill 的 redis 段（全部行为可在 `fault-drill.sh` 对应行找到）：

1. 前置检查（`:27-37`）：`.env` 存在、jar 已构建、compose 的 mysql/redis/rabbitmq 都在跑；缺一即 fail。
2. 起专用实例（`:74-87`）：8083 端口、webhook 快重试参数、私有目标放行、自铸抓取 token，健康检查通过才继续。
3. 破坏（`:103-110`）：`docker compose stop redis`，并真等 ping 不通，确认破坏生效（预检基线在 `:98-101`：redis 正常时建链 A、跳转 302）。
4. 降级验证（`:113-124`）：带幂等键的创建返回正常；跳转 302；`/actuator/prometheus` 里两个 failopen 计数器各出现一次。三行 PASS 各对应一个断言。
5. 自愈验证（`:126-142`）：拉回 redis 等 PONG，新建链 C 的缓存值回填、跳转 302。
6. 无论第几步退出，trap（`:54-70`）杀应用、拉回 redis；第二、三段接着跑或按 SKIP 语义跳过。同一套「前置、破坏、降级验证、自愈验证、清理」的节奏在四个脚本里反复出现，这是可以迁移到你任何项目的演练模板。

## 9. 为什么这样设计

- **应用不进容器**：开发者机器与部署形态解耦，JVM 参数、日志、调试器都是宿主机原生体验；Compose 侧职责收窄为「有状态依赖」，卷管理简单。代价是应用高可用要自己管，本项目定位是学习与演示项目，这笔交易划算。
- **观测栈绑 127.0.0.1**：指标的暴露面收紧到本机。依赖端口不绑是因为应用必须跨网卡（容器桥接）访问它们，而观测访问只来自开发者浏览器。
- **演练脚本 fail-fast 且带自证**：每段先验证破坏生效（redis 真停了、rabbit 真停了、read_only 真开了），再验证降级行为。断言的不是「代码应该这样」，而是「这台机器上此刻确实这样」。
- **SKIP 语义**：演练是平台相关的（PowerShell、root 口令），缺件时跳段而不是失败，保证脚本在任何机器上都有产出。
- **prod 收紧分三层**：静态资源、文档生成、安全链各管一段，任何一层误配都有下一层兜底。`ProdProfileIT` 把收紧钉进 CI，防 prod 配置回归。

## 10. 本节总结

- 五个 service 分两组：三个有口令/无口令的依赖（全网卡暴露），两个观测（只绑 127.0.0.1，opt-in profile）。
- MySQL 两大坑：首初始化约 5 分钟等 healthy；口令在数据卷首建时固化，改 `.env` 无效，须 down -v 重建。
- Prometheus 抓宿主机应用三件套：`host.docker.internal:host-gateway`、Bearer token 文件挂载、Linux 下 `chmod 644` 且无尾换行。
- 四个演练脚本覆盖冒烟、MQ 断链、outbox 断链（关同步写的更坏情形）、三段降级（redis fail-open 与自愈、webhook 重试到 FAILED、mysql 只读读写分离），全部带 trap 清理与 SKIP 语义。
- migrate.sh 让存量库升级可以脱离应用启动预演；prod profile 三层开关加 `ProdProfileIT` 回归钉。

## 11. 课后练习

**练习 1（概念理解）** 为什么 prometheus 和 grafana 的端口写成 `127.0.0.1:9090:9090`，而 mysql 写成 `${MYSQL_PORT:-3306}:3306`？两种写法在暴露面上的差别是什么？

答案：`127.0.0.1:9090:9090`（`docker-compose.yml:71`）只把端口绑在宿主机回环地址，本机浏览器可达，局域网其他设备不可达，适合「指标、仪表盘、默认口令」这类敏感面。`"3306":3306`（`:11-12`）绑全部网卡，应用从容器桥接网络与宿主机访问都方便，风险靠口令与内网环境兜底。这是按「谁需要访问」划的暴露面，观测面收紧、依赖面放开。

**练习 2（执行过程）** 同学改了 `.env` 里的 `MYSQL_PASSWORD`，重启容器后应用报 `Access denied for user 'linkforge'`（1045）。写出根因与两种处置。

答案：根因是口令只在卷 `linkforge_mysql_data` 第一次初始化时建账号（`README.md:269`），此后卷里记录的是旧口令，`.env` 的新值只影响应用侧，两侧不一致即 1045。处置一（保留数据）：把 `SPRING_DATASOURCE_PASSWORD` 改回与当初建卷时一致。处置二（可弃数据）：`docker compose down -v` 删卷后重新 `up`，新口令随新卷生效。注意 `docker compose stop` 不删卷，只停容器，改口令同样无效。

**练习 3（调用链）** mq-drill.sh 恢复段把 `totalClicks` 等到 N+M 的那几十秒里，是哪个组件在干活？结合定时任务地图说出它的节奏与依据的表。

答案：`ClickLogReconciler` 对账任务（见 [08-scheduled-tasks.html](../../diagrams/archify/html/08-scheduled-tasks.html) 的节点，模块 11.3 与 14.3）。MQ 断链期间同步落库保住了 `lf_click_log` 明细（mq-drill 断言 M 次 302），但聚合表 `lf_click_daily` 缺这批增量；恢复后 Reconciler 按固定节奏（test profile 外的默认 30 秒，`application-test.yml:40` 是 IT 的 1 小时专用值）比对明细与聚合的差，补齐 `lf_click_daily`，统计接口读到 N+M。drill 头注释 `mq-drill.sh:5` 写的 "via the reconciliation loop" 即此。

**练习 4（设计分析）** fault-drill 第二段为什么要求「webhook 还在 PENDING 重试时至少采一次跳转 302 的样」（`:211` 的 NONBLOCK 检查），直接断言最终 `FAILED|3|500` 不够吗？

答案：`FAILED|3|500` 只证明「重试与上限语义正确」，不证明「投递故障不影响主链路」。C4 的设计目标是应用降级而不是陪葬，即 webhook 挂掉期间跳转必须持续 302。`:194-204` 的循环在看到 PENDING 行时先采样一次跳转状态（`:198-201`），`:211` 的检查反过来保证 relay 没有快到「还没采样就 FAILED」（那样降级窗口根本没被观察到）。两条断言合起来才覆盖「重试中不阻塞」与「最终终态正确」两件事。

**练习 5（源码定位）** prod 下访问 `/v3/api-docs` 返回 401 而不是 404。找到三层开关中真正执行「拒绝」的那一层，并解释为什么其他两层关了它还 401。

答案：执行拒绝的是安全链层：`SecurityConfig.java:29-31` 以 `@Value("${linkforge.docs-enabled:true}")` 读开关，prod 的 `application.yml:261` 置 false 后 `/v3/api-docs/**` 等路径在过滤器链被拒（`SecurityConfig.java:28` 注释说明拒绝发生在 springdoc 之前），未认证请求先走 401 入口点（`ProdProfileIT.java:31` 及注释）。另外两层是资源层：`add-mappings: false`（`application.yml:254`）只关静态控制台，springdoc 的 `enabled: false`（`:256-259`）只关文档生成端点的内容。三层是纵深防御，安全链层独立生效，这正是 `ProdProfileIT` 用 401 断言验收的原因。

## 12. 与后续课程的联系

模块 15 到此收官，42 节主线课程全部完成。测试（15.1）、编排（15.2）、部署与演练（本课）三层合起来是「独立修改项目」所需的最后一层能力：改代码前跑得起测试，改完后 CI 替你全量验证，出问题时有演练脚本定位到段。接下来的 `advanced/` 专题按业务链路纵向贯穿（注册登录、创建短链、跳转 302、点击管道、依赖故障），建议从专题「依赖故障处理（Redis/MySQL/RabbitMQ）」（[advanced/12-dependency-failure-handling.md](../../advanced/12-dependency-failure-handling.md)）读起，它与本课的 fault-drill 互为代码视角与运维视角；跳转主链路的完整判定见专题「匿名跳转 302 全链路」（[advanced/04-redirect-302.md](../../advanced/04-redirect-302.md)）。回顾单个组件的深讲，模块 8（Redis 缓存）、10（MQ 与 Outbox）、13（Webhook）与 14（可观测性）都有索引。
