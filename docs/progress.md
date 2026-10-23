# LinkForge 开发进度与验收记录

> 执行者：GLM（zcode + pstack poteto-mode），按《LinkForge 实施与验收方案》P0→P3 顺序推进。
> 本文件是审计核对的主要证据：每阶段验收勾选、真实执行的命令与输出摘要、决策记录。
> 本轮边界：只做 P0–P3；P3 完成后停下等「实习JAVA_AI」审计，不开 P4/P5。

## 状态总览

| 阶段 | 主题 | 分支 | 状态 |
|------|------|------|------|
| P0 | 落地卫生 | feat/p0-hygiene | ✅ 完成（见下） |
| P1 | 短链闭环 | feat/p1-links | 未开始 |
| P2 | 异步分析 | feat/p2-async-stats | 未开始 |
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
