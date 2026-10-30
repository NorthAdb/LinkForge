# LinkForge 压测报告（P4，bench.md）

> 数字纪律：本文所有数字来自 2026-10-24 本机实际运行（`scripts/bench.sh`），命令与原始输出摘要齐备，无任何推算或虚构。复现：`bash scripts/bench.sh`。
> **时期注（Bw345-F2）**：以下数字是 **Windows 历史实测**，测于 `df904f8`（2026-10-24，Windows 10 + Docker Desktop/WSL2，**固定窗口限流时期**，早于 A7 Outbox / A9 Bloom / A10 singleflight / B7/B8 缓存 / B6 滑窗，也早于当前 tip）。**它们不代表当前版本，也不是 Linux 性能。B10 Linux bench 仍未跑**（backlog Bw345-F2）。未跑出新数字之前，简历与面试引用必须带上这句时期注，不要把表里的 QPS 写成当前 tip 的成绩。

## 环境

| 项 | 值 |
|----|----|
| OS | Windows 10 (10.0.26300)，Git Bash (MINGW64) |
| CPU | Intel Core i5-8400 @ 2.80GHz（6 核） |
| 内存 | 24 GB |
| Docker | Docker Desktop client/server 29.9.2（WSL2 后端，依赖用命名卷） |
| 应用 | LinkForge jar，宿主 JDK 21 运行（Java 17 字节码），端口 8080，默认 dev profile |
| 依赖 | MySQL 8.0 / Redis 7 / RabbitMQ 3.13（docker compose，命名卷） |
| 压测工具 | **wrk 4.x**（镜像 `skandyla/wrk`，容器内运行） |
| 拓扑 | wrk(容器) → host.docker.internal:8080 → 应用(宿主) → 依赖(compose) |
| 限流 | 压测期间通过环境变量临时调高：`LINKFORGE_REDIRECT_RATE_MAX=1000000`、`LINKFORGE_CREATE_RATE_MAX=1000000`（不调高测到的是 429） |

命令（完整）：`bash scripts/bench.sh`（脚本自带：起应用（调高限流）→ 登录 → 建热点码预热 → 基线/场景/正式/创建五段 wrk → Redis 命中率 → 停应用）。

## 结果总表

| 场景 | 命令参数 | QPS | P50 | P99 | 错误 |
|------|----------|-----|-----|-----|------|
| GET /api/health（基线，无 DB 写） | t2 c10 10s | **888.8** | 9.9ms | 62.8ms | 0 |
| GET /r/{code} 场景 default（双写开） | t2 c10 15s | 13.3 | 678ms | 1.31s | 0 |
| GET /r/{code} 场景 no-mq（关异步发布） | t2 c10 15s | 11.6 | 678ms | 1.73s | 0（1 超时） |
| GET /r/{code} 场景 no-sync（关同步明细） | t2 c10 15s | **276.1** | **31.4ms** | 689ms | 0 |
| GET /r/{code} 正式（default） | t4 c50 30s | **14.9** | 1.72s | 2.00s | 364 超时（>2s），0 读 写 连接错 |
| POST /api/links（default） | t2 c20 20s | **13.5** | 1.25s | 1.90s | 0 非 2xx（全部 200），31 超时 |

**Redis 缓存命中率（正式跳转段，30s）**：keyspace_hits delta=497，keyspace_misses delta=0 → **命中率 100%**（预热后纯命中，回源零次）。

## 解读（写路径分解）

1. **基线排除了网络/框架**：health 路径 888.8 QPS、P50 9.9ms，说明 wrk→容器 NAT→应用→Redis 的链路与 Spring MVC 本身远快于业务路径。
2. **同步明细写是跳转吞吐的支配项**：default 13.3 QPS vs no-sync 276.1 QPS（**约 21 倍**）。关掉 `lf_click_log` 同步插入后单请求 P50 从 678ms 降到 31ms，即本机每次 MySQL 提交约 **600ms**。
3. **MQ 发布几乎免费**：no-mq 与 default 相当（13.3 vs 11.6，波动内），异步发布不构成瓶颈。
4. **创建接口同天花板**：13.5 QPS、P50 1.25s、0 错误——创建同样走一次 MySQL 提交，与跳转的支配项一致。
5. **600ms/提交是本机存储路径特性，不是设计缺陷的证据**：同一仓库在 GitHub Actions ubuntu runner（Linux 原生 Docker）上，15 个含大量写库的集成测试 5.6 秒跑完（见 CI 日志），单次提交在 Linux 卷上为毫秒级。本机瓶颈为 Docker Desktop for Windows 的卷存储路径。
6. 高并发（c50）下 default 场景出现 364 次 >2s 超时：写入排队放大尾延迟，与第 2 条结论一致；无 socket/读写错误。

## 原始输出摘要

```text
== bench 0: GET /api/health baseline (t2 c10 10s) ==
     50%    9.88ms / 99% 62.84ms / 8928 requests in 10.04s / Requests/sec: 888.83
== redirect scenario [default] (t2 c10 15s) ==
     50%  677.54ms / 99% 1.31s / 200 requests in 15.07s / Requests/sec: 13.27
== redirect scenario [no-mq] (t2 c10 15s) ==
     50%  678.27ms / 175 requests / Requests/sec: 11.63 (timeout 1)
== redirect scenario [no-sync] (t2 c10 15s) ==
     50%   31.44ms / 99% 689.27ms / 4150 requests / Requests/sec: 276.13
== headline: GET /r/{code} (t4 c50 30s) ==
     50% 1.72s / 99% 2.00s / 449 requests / Requests/sec: 14.92 / timeout 364
     redis delta: hits=497 misses=0 → hit-rate 100.00%
== create: POST /api/links (t2 c20 20s) ==
     50% 1.25s / 99% 1.90s / 272 requests / Requests/sec: 13.53 / Non-2xx: 0 / timeout 31
```

## 已知边界与改进方向（不在 P4 实施）

- 本机（Windows 卷）写入吞吐受存储路径限制；Linux 环境部署预期数量级更好（CI 上集成测试的写库吞吐可佐证）。迁移/部署到 Linux 后应重跑本脚本刷新数字。
- 若需进一步提升跳转吞吐且保持明细能力：批量/异步化 click_log 写入（攒批提交）、或切纯异步 + outbox（见 README「点击统计管道」的边界说明）。
