# LinkForge 简历条目（post-C3 口径 · tip `d23a7c2`，2026-10-31）

> 数字纪律：压测数字引用 `docs/bench.md` 的 **Windows 历史实测**（2026-10-24，固定窗口限流时期，早于 A7–B8、B6 滑窗与 C1–C3）。**不是当前 Linux 性能；B10 Linux bench 仍未跑**，本文不发明新数字。限流如实写「Redis ZSET + Lua 滑动窗口日志」；不写未实测的词（令牌桶/漏桶/万级 QPS）；不接任何信誉服务的边界照实讲。每条 bullet 都有测试/演练/指标兜底，面试可展开。

- **多租户短链服务**（Spring Boot 3 / MyBatis-Plus / MySQL 8 / Redis 7 / RabbitMQ 3.13）：JWT 鉴权 + 租户级数据隔离（跨用户访问一律 404，集成测试钉住）；注册创办人与 bootstrap 用户为租户 **ADMIN**（`lf_user.tenant_role`）。分钟级限流为 **Redis ZSET + Lua 滑动窗口日志**——Redis `TIME` 时钟免疫多实例时钟漂移、只有放行的请求占名额、429 全带 `Retry-After`、Redis 故障 fail-open 且有指标可见；短码唯一索引兜底并发创建零重复，短码区分大小写、保留字防 `ADMIN` 绕过

- **跳转读路径五层防护**：限流 → Bloom 防穿透（从未存在的码直接 404，不碰缓存与库）→ 空值缓存 → Caffeine L1 + Redis L2 → 同码并发行读 **singleflight** 合并（领头读库、跟车共享、超时退化自读）；坚持每请求读 DB 行保强一致——禁用/过期判定永不被旧缓存遮蔽，另有行级 URL 校验门防脏行借旧缓存放行；缓存**写穿 + 延迟双删**把最终一致窗口收敛到约 1 秒，TTL 向上抖动防同批过期雪崩。**Bloom / L1 仍为进程内结构**（Guava / Caffeine，**没有** RedisBloom / 分布式 L1）；C2 起跨实例靠 **Redis pub/sub（`CacheBus`）**：创建后广播 Bloom `put`、改链/启停/删除广播 L1 `invalidate`（订阅端 origin 跳过自己、只剔 L1 不碰 L2）；publish **fail-open**；Bloom 另有 24h 旁路 rebuild + ADMIN 手动触发（仍装入逻辑删码——经典 Bloom 不可删位）。L1/Bloom 指标是本进程计数，不是集群命中率

- **点击统计管道**：**Transactional Outbox**——点击明细与 outbox 行同事务落库、热路径不触碰 MQ → RabbitMQ correlated confirm + mandatory 投递（broker ack 才标 SENT）→ 消费端确定性 eventId 唯一索引幂等（重放 N 次计数 +1）→ 按天聚合 + **device / referer_host / os / browser** 维度预聚合（C2：轻量 UA 规则，**Edge 先于 Chrome**；Geo 不做）；断 MQ 演练实证「跳转 302 不丢、恢复后自动追平」，关同步明细后明细由消费端回填不丢失。非人点击（预取 / 名单内预览 bot）照常 302 但全链路不计

- **物料与开放能力（C1）**：QR 物料（ZXing，码面永远是 `{public-base}/r/{code}`）+ 短时 HMAC 分享链（`ROLE_QR_SHARE`，无 permitAll）；建链/改链 **UTM 写时合并**进 `original_url`（跳转零改动）。API Key 明文仅创建一次、库存 SHA-256；分钟滑窗 + 自然日双配额；**USER / TENANT** 双 scope——TENANT 仅租户 ADMIN 的 JWT 可签发，读侧租户可见、写侧仍记创建者。点击 Webhook：HMAC-SHA256 + ±300s、secret AES-256-GCM at-rest、SSRF 保存/发送双校验；**PER_CLICK / AGGREGATE** 双模式——聚合双触发（默认 60s 或 100 次）封窗 digest（≤500 link/篇，续篇 `-partN`，迟到 `late:true`），at-least-once 按 eventId 去重

- **A/B 权重分流 + 批量导入（C3）**：**一码多目标**（每链 ≤10 条加权 variant，V10 `lf_link_variant`，key 区分大小写）——**门禁/密码/UTM 全部留在链层**，跳转在全部门禁通过后按权重抽签，**有 variants 时 `Location` 以抽签为准**（无 variants 行为逐字不变）；点击按所抽 variant 进 `dim=variant` 分桶（统计可拆桶，无 variant 不写该维）。批量 CSV 导入 `POST /api/links/import` **同步 multipart** ≤100 行、**部分成功**（失败行返回文件行号+原因，成功行保留）——**专用 import 限流**整次一次、不占创建配额，成功行走单链创建同路径，**Bloom put + L1 广播对齐多实例**；自写 RFC4180 解析器零新依赖，结构性拒绝先于任何写入

- **安全边界（诚实讲）**：BCrypt 密码短链（**先两级限流后 BCrypt** 防 CPU 打满、HMAC cookie 绑密码版本）；本地域名名单门禁（BLOCK/WARN，跳转行级复查即时热生效——「开放重定向 + 本地名单」）；Webhook SSRF 拦私网/元数据，**DNS rebinding 仍是诚实缺口**（未做解析钉死/二次比对，后置加深）

- **压测定位与量化**（wrk 写路径分解；**Windows 历史 / 固定窗口时期，早于当前 tip，B10 Linux bench 仍未跑，不是当前 Linux 性能**）：定位同步明细提交为吞吐支配项——关闭后跳转 **13→276 QPS、P50 678→31ms（约 21 倍）**；缓存预热后命中率 100%、零回源；全链路错误率 0

- **工程化**：v2.0.0 发布 **211** 例；当前 tip（C3 后）**307** 例——纯单测 **144** + Testcontainers 集成 **161** + 时区 fork **2**（Asia/Shanghai 钉死 Webhook 退避时钟）。Flyway V1–V10；令牌守卫的 `/actuator/prometheus`（fail-closed）+ Grafana 自动 provision（P50/95/99 + Outbox/Webhook 积压；L1/Bloom 面板标明本进程）；prod profile 一键收敛暴露面

> 面试防线：跳转判定链（限流→Bloom→absent→L1/L2→行读→404/410→BLOCK→密码门→WARN→非人跳过→302）能默画；「为什么滑窗不是令牌桶」「Outbox vs 双写」「Bloom 假阴/假阳与广播补齐」「L1 origin-skip」「TENANT Key 谁能签发」「聚合 digest late/truncated」「UTM 为何写时合并」「密码链为何限流先于 BCrypt」「SSRF 还缺什么」「为何不做 ES / Geo / B10」「一码多目标怎么按权重抽签且统计可拆桶」「批量导入怎么在专用限流与 Bloom 广播下部分成功」——每题有书面取舍（README「诚实边界」+ `docs/progress.md` 决策表 + 轮 P 面试包）。
