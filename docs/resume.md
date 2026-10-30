# LinkForge 简历条目（v2.0.0 口径，2026-10-25）

> 数字纪律：压测数字引用 `docs/bench.md` 的 **Windows 历史实测**（2026-10-24，固定窗口限流时期，早于 A7–B8、B6 滑窗和当前 tip）。**不是当前 Linux 性能；B10 Linux bench 仍未跑**，本文不发明新数字。限流如实写「Redis ZSET + Lua 滑动窗口日志」；不写未实测的词（令牌桶/漏桶/万级 QPS）；不接任何信誉服务的边界照实讲。每条 bullet 都有测试/演练/指标兜底，面试可展开。

- **多租户短链服务**（Spring Boot 3 / MyBatis-Plus / MySQL 8 / Redis 7 / RabbitMQ 3.13）：JWT 鉴权 + 租户级数据隔离（跨用户访问一律 404，集成测试钉住）；分钟级限流为 **Redis ZSET + Lua 滑动窗口日志**——Redis `TIME` 时钟免疫多实例时钟漂移、只有放行的请求占名额、429 全带 `Retry-After`、Redis 故障 fail-open 且有指标可见；短码唯一索引兜底并发创建零重复，短码区分大小写、保留字防 `ADMIN` 绕过
- **跳转读路径五层防护**：限流 → Bloom 防穿透（从未存在的码直接 404，不碰缓存与库；**进程内，多实例不共享，会有假阴性**）→ 空值缓存 → Caffeine L1（**同样进程内，默认 10 秒内其他实例可能看到旧 URL**）+ Redis L2 → 同码并发行读 **singleflight** 合并（领头读库、跟车共享、超时退化自读）；坚持每请求读 DB 行保强一致——禁用/过期判定永不被旧缓存遮蔽，另有行级 URL 校验门防脏行借旧缓存放行；缓存**写穿 + 延迟双删**把最终一致窗口收敛到约 1 秒，TTL 向上抖动防同批过期雪崩。L1/Bloom 指标是本进程计数，不是集群命中率
- **点击统计管道**：**Transactional Outbox**——点击明细与 outbox 行同事务落库、热路径不触碰 MQ → RabbitMQ correlated confirm + mandatory 投递（broker ack 才标 SENT）→ 消费端确定性 eventId 唯一索引幂等（重放 N 次计数 +1）→ 按天聚合 + 设备/来源维度预聚合；断 MQ 演练实证「跳转 302 不丢、恢复后自动追平」，关同步明细后明细由消费端回填不丢失（旧叙事「关 sync 即丢明细」已被演练证伪）
- **压测定位与量化**（wrk 写路径分解；**Windows 历史 / 固定窗口时期，早于当前 tip，B10 Linux bench 仍未跑，不是当前 Linux 性能**）：定位同步明细提交为吞吐支配项——关闭后跳转 **13→276 QPS、P50 678→31ms（约 21 倍）**；缓存预热后命中率 100%、零回源；全链路错误率 0
- **安全与开放能力**：BCrypt 密码短链（**先两级限流后 BCrypt** 防 CPU 打满、HMAC-SHA256 签名 cookie 绑定密码版本、改/清密码旧 cookie 即刻失效）；本地域名名单门禁（BLOCK/WARN 纯函数判定、跳转时行级复查即时热生效，如实讲「开放重定向 + 本地名单」边界）；API Key（明文仅创建响应一次、库存 SHA-256 哈希、分钟滑窗 + 自然日固定双配额、吊销下一请求即 401）；点击 Webhook（HMAC-SHA256 签名 + ±300s 容差防重放、secret AES-256-GCM at-rest、SSRF 保存/发送双校验、DB 时钟退避免疫 JVM-DB 时区错配）
- **工程化**：v2.0.0 发布 **211 例**全绿；当前 tip **220 例**（纯单测 99 + Testcontainers 集成 121，其中 2 例独立 fork 到 Asia/Shanghai 钉死时区错配回归；多出的 9 例是门禁 Content-Type、breakdown 400、SENT 清理）。Flyway V1–V7 版本化迁移；令牌守卫的 `/actuator/prometheus`（fail-closed）+ Grafana 自动 provision 看板（P50/95/99 直方图 + SLO 桶 + Outbox/Webhook 积压 gauge）；prod profile 一键收敛暴露面

> 面试防线：跳转判定链（限流→Bloom→absent→L1/L2→行读→404/410→BLOCK→密码门→WARN→B2→302）能默画；「为什么滑窗不是令牌桶」「为什么不用分布式锁」「Bloom 假阴性 vs 假阳性」「Outbox vs 事务消息」「多实例下 Bloom/L1 的限制与后置路线」每题都有书面取舍（README 各模块「诚实边界」+ `docs/progress.md` 决策表）。
