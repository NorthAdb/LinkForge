# LinkForge 简历条目（数字全部来自 docs/bench.md 实测，2026-10-24）

> 纪律：所有数字引用 bench.md；限流如实写「Redis 固定窗口」；不写未实测的词（令牌桶/滑动窗口/万级 QPS）。

- 基于 Spring Boot 3 + MyBatis-Plus + MySQL/Redis/RabbitMQ 实现**多租户短链服务**：JWT 鉴权与租户级数据隔离（跨用户访问一律 404，含集成测试）、开放注册（按 IP 固定窗口限流 429）、短码唯一索引 + 撞码重试保证并发创建零重复
- 跳转链路 **Redis 缓存优先**（实测预热后命中率 100%、零回源），DB 行级禁用/过期校验防脏缓存放行；更新/禁用采用**写穿 + 延迟双删**把最终一致窗口收敛到约 1 秒；提供 `Idempotency-Key` 创建幂等（并发同键仅落一条）
- 点击分析管道：**同步明细 + RabbitMQ 异步聚合双写**，消费端以确定性 eventId 唯一索引幂等（重放 N 次计数 +1）；断 MQ 演练实证「跳转不丢 302、恢复后对账器自动追平」；按天聚合 + 精确 UV（DISTINCT IP）统计 API 仅链主可查
- 压测定位与量化：用 wrk 做写路径分解实验，发现同步明细提交是吞吐支配项（关闭后跳转 **13→276 QPS、P50 678→31ms**，约 21 倍），缓存命中路径单请求 P50 约 31ms；全链路错误率 0
- 工程化：**Testcontainers** 隔离的集成测试（62 个，GitHub Actions ubuntu CI 全绿）+ 纯单测分层（surefire/failsafe）；Actuator 组件健康（db/redis/rabbit，DOWN 可观测）+ Micrometer 业务计数器；prod profile 收敛暴露面
