# LinkForge 简历条目（C5 功能冻结 · 春招粘贴稿 · 2026-11-01）

> **对齐**：能力基线是 C5 收口 `f955313`（合并 `e9ffff4`；跳转/记账代码在祖先 `e0c567c`）。写作时 main 上多一个 `d1e94b2`，只回填审计 nit（CI tip 行、PASS 计数），**相对 `f955313` 零 Java diff**。简历按这条冻结线写，不把文档提交说成新功能。
>
> **版本**：`pom.xml` 仍是 **2.0.0**。v2.0.0 发布时是 **211** 例；C1–C5 是未发版深化，简历不写 v2.1、不改版本号叙事。
>
> **数字纪律**：测试只报 **366 = 161 UT + 203 IT + 2 tz**（surefire 161 + failsafe 203 + `webhook-tz-it` 独立 fork 2）。压测只引用 [`docs/bench.md`](bench.md) 的 **Windows 历史窗**（2026-10-24，`df904f8`，固定窗口限流时期，早于 A7–B8、B6 滑窗和 C1–C5），**不是当前 Linux 性能；B10 Linux bench 仍未跑**，本文不发明新 QPS。不写未实测的词（令牌桶 / 漏桶 / 万级 QPS）。**不做 Geo**。每条 bullet 都能指到测试、`scripts/fault-drill.sh` 或指标。

## 项目栏（可直接贴进简历）

**LinkForge｜多租户短链与点击分析** · 个人项目 · 自研骨架（不是开源短链换皮）

技术栈：Spring Boot 3.3.5 / Java 17 / MyBatis-Plus / MySQL 8 / Redis 7 / RabbitMQ 3.13 / Flyway V1–V12 / Testcontainers / Prometheus + Grafana

### 项目描述

自研多租户短链：创建短码，公开访问走 **302**。读路径是限流 → Bloom → 空值缓存 → Caffeine L1 + Redis L2 → singleflight，**每次仍读库行**，禁用和过期不会被旧缓存放成 302。点击明细和 outbox 行同一事务落库，热路径不直接碰 MQ。开放面做到租户 API Key、点击 Webhook、QR + UTM、**A/B 权重分流**、**批量 CSV 导入**。Webhook 出站把 DNS rebinding 的 TOCTOU 关上：投递时 **一次解析、全部地址校验、只连已校验地址**（httpclient5 + 自定义 DnsResolver）。Redis 宕、点击写库失败、Webhook 5xx 都不挡 302，`fault-drill.sh` 分三段演。测试 **366** 例；C5 审计后功能冻结，2026-11 经运营者指令解冻两批（外部审计修复 + 认证重构与团队协作），压测数字保持 Windows 历史窗，Geo 不做。

### 职责与结果

- **多租户短链与鉴权**：JWT 登录；跨用户访问一律 **404**（集成测试钉住，不靠 403 泄露「码存在」）。注册创办人与 bootstrap 用户是租户 **ADMIN**（`lf_user.tenant_role`）。分钟级限流是 **Redis ZSET + Lua 滑动窗口日志**：用 Redis `TIME` 避免多实例时钟漂移，只有放行的请求占名额，429 都带 `Retry-After`；Redis 故障 **fail-open** 且有指标。短码唯一索引兜住并发创建，短码区分大小写，保留字挡住 `admin` 这类路径词。简历上写「滑窗」不写「令牌桶」。

- **会话与团队协作（解冻批次 2）**：会话从「24h JWT 无撤销」重构为 access 1h + refresh 30 天：服务端只存 refresh 的 SHA-256，family 链轮换、已轮换 token 超宽限重用即判泄露整链撤销；`token_version` + 过滤器每请求回查用户行，禁用/删除/改密让旧凭据下一请求失效；登录双桶限流（IP + 账号）且失败路径恒走一次 BCrypt，时序不泄露账号存在性。多租户从「隔离标签」落成真实协作：链接承载型邀请（单次、7 天、只存哈希、四种失败同一 401 防枚举，建号与认领同事务）、**ADMIN / MEMBER** 两级成员管理、最后 ADMIN 护栏（租户行 `SELECT ... FOR UPDATE` 串行化并发互降）、无邮箱条件下管理员重置 + 强制改密拦截找回密码。

- **跳转读路径五层**：限流 → Bloom（从未出现过的码直接 404，不碰缓存和库）→ 空值缓存 → Caffeine L1 + Redis L2 → 同码并发 **singleflight**（领头读库、跟车共享、等待超时后退化成自己读）。坚持每请求读 DB 行，禁用/过期判定不被旧缓存遮蔽；另有行级 URL 校验，脏行不能借旧缓存放行。缓存 **写穿 + 延迟双删**，最终一致窗口收敛到约 1 秒；TTL 向上抖动，避免同批一起过期。**Bloom / L1 仍是进程内**（Guava / Caffeine，没有 RedisBloom，也没有分布式 L1）。C2 起跨实例靠 Redis pub/sub（`CacheBus`）：创建后广播 Bloom `put`，改链/启停/删除广播 L1 `invalidate`（订阅端跳过自己的 origin，只剔 L1，不碰 L2）；publish **fail-open**。Bloom 另有 24h 旁路 rebuild 和 ADMIN 手动触发，重建仍会装入已逻辑删除的码——经典 Bloom **不能删位**。L1/Bloom 指标是本进程计数，不是集群命中率。

- **点击统计（Transactional Outbox）**：点击明细和 outbox 行同事务落库，热路径不发 MQ → RabbitMQ correlated confirm + mandatory（broker ack 才标 SENT）→ 消费端用确定性 eventId 唯一索引做幂等（同一事件重放，计数只 +1）→ 按天聚合，并预聚合 **device / referer_host / os / browser**（C2：轻量 UA 规则，**Edge 先于 Chrome**；**Geo 不做**，breakdown 白名单也不含 `geo`）。断 MQ 的演练是「跳转 302 不丢、恢复后自动追平」；关掉同步明细后，明细改由消费端回填，不丢点击。非人点击（预取、名单内预览 bot）照常 302，全链路不计。HEAD 探测不记点击。

- **物料与开放面（C1）**：QR 用 ZXing，码面永远是 `{public-base}/r/{code}`，扫出来仍走解锁、门禁和计数；短时分享链是 HMAC + `ROLE_QR_SHARE`，**没有** permitAll 的公开 PNG。建链/改链把 UTM **写时合并**进 `original_url`，跳转路径零改动，本服务的统计桶不含 utm。API Key 明文只在创建时出现一次，库存 SHA-256；分钟滑窗 + 自然日双配额。**USER / TENANT** 双 scope：TENANT 只有租户 ADMIN 的 JWT 能签发，读侧租户可见，写侧仍记创建者。点击 Webhook：HMAC-SHA256、时间窗 ±300s，secret 用 AES-256-GCM 落库；**PER_CLICK / AGGREGATE** 两种模式，聚合默认 60s 或 100 次封窗，单篇最多 500 条 link，续篇 `-partN`，迟到标 `late:true`，至少一次投递按 eventId 去重。

- **A/B 权重分流 + 批量 CSV（C3）**：一码多目标，每链最多 10 条 variant（V10 `lf_link_variant`，key 区分大小写，weight 是正整数相对权重，不必凑满 100）。**门禁、密码、UTM 都在链层 `original_url` 上评估**；全部门禁通过之后才按权重抽签。有 variants 时，最终 `Location` **以抽签为准**，不把 L1/L2 里的默认 URL 当成跳转结果；没有 variants 时行为和旧链一致。点击按抽中的 key 进 `dim=variant` 桶，没有 variant 就不写这一维；`dim=geo` 仍是 400。批量导入 `POST /api/links/import` 是同步 multipart，最多 100 行，**部分成功**：失败行带文件行号和原因，成功行留下。行号从 1 计，表头是 1，第一条数据是 2。整次请求走专用 import 限流，计一次，不占创建配额；成功行和单链创建同一条 `doCreate`（C4 起建链和 variants **同一事务**，失败整单回滚）。Bloom put 和 L1 广播跟多实例对齐。解析器是自写的 RFC4180 风格，零新依赖。缺列、超行数、引号不闭合这类结构问题整单 400，一行都不写。`Idempotency-Key` 对导入**故意不生效**。

- **SSRF 钉死 + 故障演练（C4）+ 点击记账 fail-open（C5）**：保存和发送都会做目标校验，但以前发送时 JDK HttpClient 可能再解析一次 DNS，校验通过的名字会被换成私网地址。现在投递侧 **只解析一次 → 每个解析地址都过违规段校验 → 连接只落到这次校验过的地址**（Boot BOM 里的 httpclient5 + 自定义 DnsResolver）。Host、TLS SNI、证书校验仍按**原 hostname**；重定向不跟随。端口白名单和出口代理还没做。`scripts/fault-drill.sh` 三段：Redis 停服时跳转仍 302、创建幂等降级可观测、恢复后自愈并且 L2 能写回；Webhook 目标 5xx/超时会退避重试、最终 FAILED，不堵 302；MySQL 只读时写 API 失败，**GET `/r/{code}` 仍 302**。C5 的落点是 `resolveForGet` **调用点** catch `DataAccessException`：`recordClick` 的事务已经回滚才进 catch，没有「事务里吞异常再提交」；指标 `linkforge.click.record.failopen`（无 tag），`linkforge.redirects` 照常 +1。只读库下 GET 302 的 Compose 证据在 [`docs/progress.md`](progress.md) C5 章（实跑 tip `e0c567c`，2026-11-01：fault-drill **7** 行 PASS，无 SKIP）。口播收成一句：**主链路 302 不依赖 Redis、点击写库、Webhook 任何一侧写成功。**

- **安全边界（按做到的讲）**：密码短链存 BCrypt，**先两级限流再 BCrypt**，避免校验本身被打成 CPU DoS；解锁 cookie 是 HMAC，绑密码版本，改密后旧 cookie 立刻失效。域名名单门禁是 BLOCK/WARN，跳转时对行上的 URL 再查一次，名单变更对存量链立即生效；这是「开放重定向 + 本地名单」，不是外部信誉库。Webhook SSRF 拒环回、私网、元数据和 CGNAT/ULA；**DNS rebinding 已按上面的方式钉死**。还没做的：端口白名单、出口代理、Geo、自定义域名、ES。

- **压测定位（带时期，不当成当前成绩）**：wrk 写路径分解，数字来自 Windows 历史窗（2026-10-24，固定窗口时期，**早于当前 tip，B10 未跑**）。当时同步明细提交是吞吐支配项——关掉之后跳转 **13→276 QPS，P50 678→31ms（约 21 倍）**；预热后缓存命中率 100%、该段零回源；全链路错误率 0。health 基线约 888 QPS，只说明那次环境的框架底，不代表现在的 `/r` 。v2.0.0 之后的密码、域名门禁、滑窗 ZSET 都在热路径上，当前 tip 的吞吐等以后的 Linux 实测，简历口述时主动把时期说全。

- **工程化**：发布 tag `v2.0.0` 时 **211** 例；C5 冻结线 **319** 例 = 纯单测 **151** + Testcontainers 集成 **166** + 时区 fork **2**（Asia/Shanghai 钉住 Webhook 退避所用的 DB 时钟）。Flyway 停在 V1–V10，C5 没有新 migration。解冻两批后 **366** 例（161 + 203 + 2），Flyway 续到 **V12**（V11 刷新令牌/版本列，V12 邀请表）。`/actuator/prometheus` 用令牌守卫，令牌没配则 **fail-closed 401**。Grafana 看板自动 provision（P50/P95/P99、Outbox/Webhook 积压）；看板上的 SLO 阈值是 Demo 目标，不是生产承诺；L1/Bloom 面板标明本进程。prod profile 用来收紧暴露面。本地依赖是 Compose 的 MySQL / Redis / RabbitMQ，jar 名是 `target/linkforge-2.0.0.jar`。

## 简历上不要加的句子

- 不写当前 Linux QPS，不写「万级」「高并发已压测通过」。被问到就说 Windows 历史窗 +「B10 没跑」。
- 不写 Geo、地区分流、自定义域名、已上云、集群 Bloom、出口代理已隔离。
- 不把 C1–C5 写成 v2.1 发版；版本栏保持 **2.0.0**。
- 不把 `fault-drill` 的 SKIP 说成失败，也不把某次没跑脚本说成「我刚刚又跑绿了」。C5 手验是 progress 里那 7 行 PASS。

## 面试防线（一句话；展开话术在 [`demo-talk.md`](demo-talk.md)）

跳转判定能按这个顺序讲：限流 → Bloom → absent → L1/L2 → 行读 → 404/410 → BLOCK → 密码门 → WARN → 非人跳过 →（有 variants 才抽签）→ 302。下面每题都有书面取舍（README「诚实边界」+ `docs/progress.md` 决策表）：

| 被问到 | 先答这句 |
|--------|----------|
| 为什么是滑窗不是令牌桶 | 实现就是 ZSET + Lua + Redis `TIME`；没写令牌桶，简历上也不写。 |
| Outbox 和双写差在哪 | 明细和 outbox 同事务；热路径不发 MQ；confirm 之后才标 SENT；消费端 eventId 幂等。 |
| Bloom 假阴/假阳 | 假阳落到缓存和库；假阴靠创建广播 `put`、24h rebuild、ADMIN 手动重建。位删不掉。 |
| L1 为什么 origin-skip | 本进程刚写过，订阅端跳过自己，只剔 L1。 |
| TENANT Key 谁能签发 | 只有租户 ADMIN 的 JWT；MEMBER 403。写仍记创建者，读按租户。 |
| 聚合 digest | 60s 或 100 次封窗；超 500 条续篇；迟到 `late:true`。 |
| UTM 为什么写时合并 | 跳转零改动，目标站自己的分析能看见；本服务维度桶不含 utm。 |
| 密码链为什么限流在 BCrypt 前 | BCrypt 几十毫秒，不限流会被打成 CPU DoS；IT 用「第 6 次正确密码仍 429」钉顺序。 |
| SSRF rebinding 怎么关、还缺什么 | 一次解析、全 IP 校验、DnsResolver 只返回已校验地址；缺端口白名单和出口代理。 |
| 为什么不用 JDK HttpClient 钉死 | Host 是受限头；连 IP 字面量时证书不好按原 hostname 校验。所以用 httpclient5，URI 仍是原域名。 |
| A/B 和缓存 | 门禁在链层；抽签在门禁之后；有 variants 时 Location 以抽签为准。 |
| 导入为什么同步、怎么部分成功 | 面试要当场看见行号；逐行 `doCreate`，一行坏不影响已经成功的行；结构错误整单 400。 |
| 点击记账失败会不会挡 302 | 不会。调用点 catch `DataAccessException`，打 `linkforge.click.record.failopen`，然后仍 302。 |
| Redis 宕了幂等怎么办 | L2 和幂等 fail-open。代价是宕的时候同一个 Idempotency-Key 可能重复建链；Redis 健康时合同不变。 |
| 为什么不做 ES / Geo / B10 | 春招这条线把主链路、开放面和故障语义做实；这三项还在 backlog，没有指令就不做。 |
