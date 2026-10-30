# LinkForge 设计决策与故障案例

这是面试追问的弹药库。面试官问「为什么不用 X」的时候，答得上来靠的不是背结论，而是知道每个选择放弃了什么。本章把项目的关键决策写成统一格式，把修过的故障写成完整故事。目标层级 L3：每个决策能五段作答，每个故障能讲出排查链。

证据来源：源码注释、`docs/progress.md`（每轮决策表与红史）、`docs/backlog.md`（后置项与理由）、`docs/bench.md`（压测数字）。正文区分四类事实：Java 语言行为、Spring/MyBatis 等框架行为、LinkForge 业务逻辑、推断（标注「据代码推断」或「估算」）。

## 本章怎么读：五段格式与两套编号

每个决策按五段回答，缺一段就还没想清楚：

1. **问题**：不解决会怎样，用具体场景。
2. **备选**：更简单的办法存在吗，为什么不用。
3. **选择与理由**：选了什么，理由是什么。
4. **代价**：放弃了什么。只会说好处不会说代价，一追问就露馅。
5. **验证**：怎么证明有效，代码在哪。

本章有两套编号，不要混：

- **本章编号**：D1–D18 是决策，F1–F7 是故障案例。这是本章自己的序号。
- **仓库轮次编号**：`docs/progress.md` 每轮有一张决策记录表，编号格式是 `D-<轮次>-<序号>`。A 段是深化轮（`D-A9-5` 读作「深化 A9 Bloom 轮第 5 条决策」），B 段是 B 档波次（`D-Bw2-13` 是「B 档波 2 第 13 条」），修复轮用 `Bw2fix`，波 3+4+5 合并轮用 `Bw345`。正文引用仓库决策时写完整编号，引用本章时写 D/F 短号。

行号会随版本漂移。引用以「哪个类哪个方法里的哪一步」为主，行号为辅。读者应自己打开文件核对，这也是读源码的训练。

## 先带走三件事

1. **每个技术选择都有代价**。缓存换来不一致（所以要写穿加双删）、消息队列换来重复（所以要消费幂等）、Bloom 换来误判（所以 DB 兜底）。五段格式里的「代价」段就是为此存在的。
2. **七个真实故障比十八个决策更值钱**。时区错配把 20 秒退避放大成 8 小时、Jackson 少一个模块让全量消息进死信、宽扫描把普通接口变成 mapper、连接池被测试上下文吃光。每个故障教一类通用教训，而且故障有排查过程，能讲出推理链的人才是真修过。
3. **数字纪律是红线**。能说的数字带时期口径，不能说的（万级 QPS、「防钓鱼系统」）永远不说。这不是谦虚，是简历的生死线，见下文「数字纪律」。

第一遍先读 D4（行是真相）、D7（Outbox 三选一）、F1（时区故障）。这三个是面试的胜负手。其余按需查。

## 决策清单（上）：跳转热路径 D1–D6

### D1 跳转为什么 302 不是 301

**问题**：短链要统计点击，点击统计依赖每次点击都回到服务端。选错状态码，统计会断流。

**备选**：301 是永久重定向。浏览器和中间代理会缓存它，之后同一个码的请求直接从缓存跳转，不再回源。结果：点击统计断流，改目标、禁用、过期都无法即时生效。307/308 保请求方法（POST 保持 POST），短链场景是 GET 导航用不上；308 同样是永久语义，一样被缓存。

**选择与理由**：302（临时重定向）。浏览器每次都回服务端，限流、Bloom、门禁、计数全部在回源路径上生效。`src/main/java/com/linkforge/link/controller/RedirectController.java` 的 `render` 方法用 `HttpStatus.FOUND` 加 Location 头返回。

**代价**：302 不被缓存意味着每次跳转都打服务器，CDN 和浏览器帮不了忙。这正是业务要的（门禁和计数必须回源），Demo 场景没有加速需求，可接受。

**验证**：同一思想贯穿状态码语义：不存在/禁用 404、过期 410、目标损坏 410、拦截 403。`LinkLifecycleIT` 断言禁用后 404、过期后 410。深讲见 [81-linkforge-flows.md](81-linkforge-flows.md) 的跳转链与 [32-spring-security.md](32-spring-security.md) 的门禁部分。

### D2 短码为什么随机 Base62，不是发号器

**问题**：短码要短（放得进 URL）、不可预测（防止枚举遍历别人的短码）、可自定义（运营要语义码）。

**备选**：发号器（Snowflake、号段模式，美团 Leaf 是代表）。发号器给的是严格递增的 ID，可预测，外部用户能顺着号段猜码；而且数字 ID 偏长。它适合做内部主键，不适合做对外暴露的短码。

**选择与理由**：`src/main/java/com/linkforge/link/support/ShortCodes.java` 用 `SecureRandom` 在 62 字符表（0-9、a-z、A-Z）上随机取码，默认 7 位，配置可调。`SecureRandom` 是密码学强随机源，输出不可预测（与 `Random` 的区别深讲见 [13-concurrency.md](13-concurrency.md)）。自定义码走同一张表和同一个唯一索引。

**代价**：随机必然概率撞码。项目不做「查了再插」（为什么见 D3），撞码由 `uk_code` 唯一索引兜底：随机码撞了换一个重试，最多 16 次（`ShortLinkService` 的 `CODE_ATTEMPTS`）；自定义码撞了直接 409。诚实账：62 的 7 次方约 3.5 万亿个码位，按生日悖论估算，千万级码量下期望碰撞次数仍远小于 1（估算，不是实测；本项目量级离这个数字还有几个数量级）。

**验证**：`src/test/java/com/linkforge/link/CreateIdempotencyAndConcurrencyIT.java` 里 10 线程 30 次并发无键创建，30 个码互异。

### D3 唯一索引为什么是并发兜底

**问题**：并发下「先 SELECT 确认不存在，再 INSERT」是坏的。两个请求都能通过「不存在」检查，然后双双插入，数据重复。检查和插入不是原子的。

**备选**：应用内锁或分布式锁，把检查加插入包成临界区。为了防一次重复插入引入锁基础设施，锁本身还会故障、还会死锁，不成比例。

**选择与理由**：把唯一性判定下沉到存储引擎的原子层。数据库的唯一索引保证第二个插入直接收到 `DuplicateKeyException`，应用按语义分支处理：随机码换码重试、自定义码 409、注册租户重名 409（`src/main/java/com/linkforge/user/service/AuthService.java` 的 register 方法）。这条原则贯穿全项目：`lf_consumed_event` 主键去重是消费幂等，`uk_daily_link_date` 加 `ON DUPLICATE KEY UPDATE` 是原子累加，`lf_outbox` 的 event_id 唯一键兜底同事件只投一次。唯一索引的原理深讲见 [40-mysql.md](40-mysql.md)。

**代价**：用异常当控制流有性能争议。冲突是罕见路径，try/catch 只在冲突真的发生时才付栈展开的开销（据代码推断，未单独压测过这条路径的开销）。

**验证**：`CreateIdempotencyAndConcurrencyIT` 同时钉住三个语义：并发同键幂等、并发无键不撞码、无键两次创建得两码。

### D4 缓存为什么只存 URL，行状态每请求读 DB

**问题**：禁用、过期、密码门、钓鱼门全是「行状态」。如果缓存整行，TTL（缓存存活期）内的旧缓存会遮蔽这些语义：禁用的链还在跳，改了密码的链还按旧密码放行。安全语义不能等 TTL 过期。

**备选**：一是缓存整行加写时失效。多实例下失效要广播，广播丢了、慢了，窗口不可控。二是缓存整行加接受 TTL 内脏读。禁用一个链之后它还能跳最多一小时，不行。

**选择与理由**：缓存只放派生读，也就是 URL 字符串。L1 是进程内 Caffeine 缓存（TTL 10 秒，`src/main/java/com/linkforge/link/support/UrlLocalCache.java`），L2 是 Redis（TTL 3600 秒加抖动）。行本身永远每请求读 DB：`ShortLinkService.resolveTarget` 里无论 L1/L2 是否命中，都会走 singleflight 读一次行（合并成本见 D6），状态、过期、密码哈希、钓鱼判定全部在这次行读的结果上做。一句话：**缓存省字段，行是真相**。

**代价**：每请求一次 DB 行读。靠热路径主序把成本压住：限流、Bloom、absent 负缓存、L1、L2 挡掉大部分流量，singleflight 合并剩余行读（十二站判定门逐步走一遍见 [81-linkforge-flows.md](81-linkforge-flows.md)，多级缓存见 [50-redis.md](50-redis.md)）。另一层代价：L1 跨实例不同步，多实例下实例 A 改了 URL，实例 B 的 L1 在 10 秒 TTL 内可能还跳旧值（README 诚实口径，广播失效挂 backlog #5）。

**验证**：`src/test/java/com/linkforge/link/LinkLifecycleIT.java` 的 `invalidLegacyTargetReturns410WithoutCountingClick`：直改 DB 行注入 CRLF 脏 URL，只清 Redis 不清 L1，跳转仍是 410 且零计数。缓存旧值遮蔽不了行。

### D5 Bloom 为什么是进程内 Guava，不是 RedisBloom

**问题**：不存在的码（用户手滑、爬虫乱拼）每次都穿透到 DB：缓存未命中、行读返回 null、404。枚举流量能把 DB 打满。

**备选**：RedisBloom，过滤器共享、多实例一致，但需要 Redis Stack 模块，为一层防御引入新基础设施。只用 absent 空值缓存（见 D6 之后的组合），每个被探测的码都要占一个 Redis 键、付一次网络往返，枚举流量直接打 Redis。

**选择与理由**：进程内 Guava `BloomFilter<String>`（`src/main/java/com/linkforge/link/support/CodeBloomFilter.java`），期望插入 10 万、误判率 fpp=1e-4，都可配。启动时 `ApplicationReadyEvent` 全量 `SELECT code` 播种；创建成功后 `put`。播种 SQL 故意绕过 `@TableLogic`（MyBatis-Plus 的逻辑删除注解，深讲见 [41-mybatis-plus.md](41-mybatis-plus.md)）：逻辑删除的行还占着 `uk_code` 码位，必须保持 Bloom 正答，让 DB 的 `deleted=0` 过滤去拒绝它，而不是让 Bloom 提前说「没有」造成假阴性。装载失败或未就绪一律 fail-open（当「可能存在」处理，回源老路径）。

播种窗口有个并发修正（A9 审计 S1）：装载时先发布空过滤器、再灌码、最后置 ready 标志。顺序反过来（灌完再发布）会让装载期间创建的码丢在旧过滤器外面，直到下次重启才被认识。Guava 的位数组并发安全，装载中的 `put` 直落新过滤器。

**代价**：一，多实例假阴性。实例 B 新建的码，实例 A 重启前不认识，会误 404（backlog #3 登记，RedisBloom 是后置路线）。二，不能删除。经典 Bloom 无法回收条目，已删码保持阳性，代价一次 DB miss（定期重建挂 backlog #4）。三，误判 fpp=1e-4，误判的码多回源一次 DB，不会错杀真码。

**验证**：`src/test/java/com/linkforge/link/BloomRedirectIT.java`（未知码拒绝计数、建链 302、禁用 404）与 `BloomDisabledAtStartupIT.java`（开关关闭时行为与 Bloom 引入前一致）。

### D6 singleflight 为什么不是分布式锁，为什么用 putIfAbsent

**问题**：D4 保证了每请求读行。热点码被 N 个并发请求同时命中时，就是 N 次一模一样的行读。DB 压力被放大 N 倍。

**备选**：一是 `synchronized`，把所有读全部串行化，不同码之间本来互不相干，粒度完全错误。二是 Redis 分布式锁，跨进程有效，但读还是读：锁只是把 N 个读排队成一次加 N-1 个等锁，拉长尾延迟，不消除读。D4 的语义决定了锁帮不上忙。

**选择与理由**：进程内并发合并（singleflight，同键并发共享一次在途执行）。`src/main/java/com/linkforge/link/support/SingleFlight.java` 用 `ConcurrentHashMap<K, CompletableFuture<V>>`，`putIfAbsent` 抢领头。

为什么是 `putIfAbsent` 不是 `computeIfAbsent`：`computeIfAbsent` 的映射函数在 ConcurrentHashMap 的桶锁内执行（框架行为，桶级锁原理深讲见 [13-concurrency.md](13-concurrency.md)）。一次几百毫秒的阻塞 DB 读放进桶锁，会把哈希到同一个桶的其他无关键的全部读写卡住。`putIfAbsent` 只做一次原子插入，锁立刻释放；领头在自己请求线程上同步执行 loader，不加线程池，完成后 `complete` 交结果，`finally` 里两参 `remove(key, future)` 摘除条目。跟随者 `get(500ms)` 等领头，超时就自己读（fail-open，计 timeout 指标）；领头抛的异常原样传给同批跟随者，和他们各自读失败等价。

**代价**：一，跨实例不合并，各合并各的，多实例最坏「每实例每窗口 1 次」，可接受。二，写路径要配合：update、disable、delete 先 `peek` 快照在途条目，提交后用两参 `forget` 只摘自己快照的那个，不误杀提交后新领头的读（A10 审计 N1 修的正是误摘问题）。三，这个组件不是缓存：flight 结束即摘除，无 TTL、无最近值、null 不缓存。负缓存在别处（D5 的 absent），这一条被注释和测试同时钉住。

**验证**：`SingleFlightTest` 五个单测钉住领头/跟随/超时/异常/摘除；`src/test/java/com/linkforge/link/SingleFlightRedirectIT.java` 用 `@SpyBean` 加 300ms 延迟制造重叠：24 并发同码行读恰 1 次、loads=1、shared=23；关闭对照 12 并发 12 次全读。

## 决策清单（中）：消息、时钟、限流与安全 D7–D12

### D7 Outbox 为什么不是事务消息 / CDC / 直接发 MQ

**问题**：点击统计要「业务落库」和「发消息」两个动作，分属 MySQL 和 RabbitMQ 两个系统，没有跨系统事务。先落库后发 MQ：发失败丢事件。先发 MQ 后落库：业务回滚了消息已出去，留下幽灵事件。这就是双写不一致，靠「小心写」解决不了。

**备选**：一是 RocketMQ 事务消息，半消息加回查机制能解决，但绑定 RocketMQ 品牌，项目没有它。二是 CDC（变更数据捕获，订阅 binlog 的 Debezium 一类），基础设施重，交接单明确列为禁区。三是直接发 MQ，就是问题段里那两个洞。

**选择与理由**：Transactional Outbox（事务性发件箱）。`src/main/java/com/linkforge/stats/service/ClickRecordService.java` 的 `recordClick` 加 `@Transactional`：sync 开启时 `lf_click_log` 明细行与 `lf_outbox` PENDING 行同一事务提交，热路径完全不碰 RabbitMQ。`src/main/java/com/linkforge/stats/service/ClickOutboxRelay.java` 定时（默认 2 秒）扫 PENDING 批量投递，失败按 2 的幂退避（上限 60 秒）保持 PENDING，超过最大尝试次数标 FAILED 留查。投递是否成功以 broker 确认为准：`src/main/java/com/linkforge/stats/mq/RabbitClickEventPublisher.java` 用 publisher confirm（broker 确认回执）加 mandatory returns（不可路由退回），nack、不可路由、超时都抛异常，Relay 保持 PENDING 重试。

**代价**：秒级投递延迟；多一张表加一个 Relay 组件；SENT 行永久保留（自动清理挂 backlog #9）；超限 FAILED 行要人工 SQL 重放。

**验证**：`src/test/java/com/linkforge/stats/OutboxDrillIT.java` 加 `scripts/outbox-drill.sh`：脚本自带一个 sync 关闭的 8082 实例，断 MQ 期间跳转照常 302、outbox 攒 PENDING，恢复 MQ 后聚合、明细、SENT 三者追平。深讲见 [51-rabbitmq-outbox.md](51-rabbitmq-outbox.md)。

### D8 eventId 为什么是 `log-{自增id}`

**问题**：消费幂等需要稳定唯一键：同一次点击重放 N 次，只计一次。

**备选**：随机 UUID。每次重放生成新 ID，`lf_consumed_event` 的去重完全失效，重放全被当成新事件。

**选择与理由**：sync 开启时确定性生成 `log-{明细行id}`（`ClickRecordService.recordClick`）。同一明细行重放 N 次得到同一个 eventId，消费端 `lf_consumed_event` 主键撞键即跳过。对账器重放也复用同款键。sync 关闭时没有明细行可挂，退化为 UUID，持久存在 outbox 的 event_id 列；消费端发现 eventId 不带 `log-` 前缀，就在同一幂等事务里补写明细行，行上打 event_id 标记（`ClickStatsService.consume`）。

**代价**：两种模式两套语义，消费端多一个前缀分支。依赖自增 id 已生成（sync 开时明细先插，id 已就绪）。这里踩过真坑：A7 演练时旧对账器把回填行当成「未追平的明细」，用全新的 `log-{新行id}` 重放，聚合翻倍。修复是回填行打标记加对账器扫描排除，见 [51-rabbitmq-outbox.md](51-rabbitmq-outbox.md)。

**验证**：`src/test/java/com/linkforge/stats/ClickIdempotencyIT.java`，同一 eventId 投递三次，聚合计数恒为 1。

### D9 退避时间为什么用 DB 时钟

**问题**：webhook 投递失败要退避重试。`next_attempt_at` 这个字段「什么时候该重试」，谁来定时钟？最初实现用 JVM 算好时间字面值写进去，比较端却是 SQL 的 `next_attempt_at <= NOW()`，DB 时钟。两个时钟混用直接出了事故（F1 完整复盘）：JVM 在 +8 时区、MySQL 在 UTC，20 秒退避被拉成约 8 小时，重试看起来死了。

**选择与理由**：时钟规则三分法。调度列（只被 SQL 拿来与 `NOW()` 比较、不对外展示）一律 DB 时钟：`src/main/java/com/linkforge/webhook/mapper/WebhookDeliveryMapper.java` 和 `src/main/java/com/linkforge/stats/mapper/OutboxMapper.java` 的 `markRetry` 都写 `DATE_ADD(NOW(), INTERVAL ? SECOND)`，写入与比较同源。业务列（对外 API 返回或给人看的 `created_at`、`sent_at`、`last_used_at`）一律 JVM 时钟，由 Java 传参写入。第三类是滑窗限流，用 Redis 的 TIME 做时钟（D10）。这条规则是 `WebhookDeliveryMapper` 的 javadoc 原文。

**代价**：SQL 里算时间不如 Java 里直观，退避公式出现在 mapper 注解里；两种时钟的分工要靠注释和测试维持，新人容易再混。

**验证**：`src/test/java/com/linkforge/webhook/WebhookRetryClockIT.java` 在独立 fork 里跑（pom 的 `webhook-tz-it` execution，`-Duser.timezone=Asia/Shanghai`），Testcontainers 的 MySQL 保持 UTC，精确复现错配环境。断言 `TIMESTAMPDIFF(SECOND, NOW(), next_attempt_at)` 落在 [0, 20] 秒内，旧实现在同一 fork 下量出约 28820 秒，必红。fork 机制的成本账见 [60-testing-ci.md](60-testing-ci.md)，时钟语义深讲见 [70-distributed.md](70-distributed.md)。

### D10 限流为什么滑窗日志，日配额却用固定窗

**问题**：注册按 IP、创建按用户、跳转按 IP、解锁按 IP 加按码两级、API Key 分钟配额，都要「窗口内最多 N 次」的精确语义。

**备选**：一是固定窗口计数（INCR 加 EXPIRE），实现最简单，但边界有突刺：59 秒放 N 个，1 秒后新窗口又放 N 个，瞬时 2N。这不是推想，B6 轮的红测试跑出过「期望 1 实得 3」的边界双倍。二是令牌桶，语义是「平均速率加突发容量」，不是「窗口内精确 N 次」，对不上需求；而且项目没有实现它，简历和面试不说，外部追问清单里这是陷阱题。

**选择与理由**：ZSET 滑窗日志。`src/main/resources/redis/sliding_window.lua`：先 `ZREMRANGEBYSCORE` 清掉窗口外成员，`ZCARD` 数窗口内数量，放行才 `ZADD`（成员是 `时间戳us-随机nonce`，保证同微秒不撞成员），最后 `PEXPIRE`。三个细节都有理由。**被拒不占名额**：如果被拒也记账，攻击者刷请求能把自己永久锁在窗口里。**Retry-After 是真实等待**：取最老条目的出窗时间，不是猜一个 60。**时钟用 Redis TIME**（微秒），Java 不传时间戳，Lua 脚本单线程原子执行，多实例时钟漂移不影响判定。失败 fail-open 加计数器（D11）。

日配额刻意反选固定窗：`src/main/java/com/linkforge/security/ApiKeyAuthFilter.java` 的分钟窗走滑窗，日窗走 `tryAcquireFixedWindow`。自然日本来就是日历边界（Asia/Shanghai 零点重置），滑窗的「滚动 24 小时」反而是错的语义；`src/main/java/com/linkforge/common/ratelimit/RedisRateLimiter.java` 里这个方法只保留给日历语义的计数器，Retry-After 给到次日零点的秒数。

**代价**：滑窗内存等于窗口内放行数，每个放行一个 ZSET 成员；被拒请求也付一次 Lua 往返。固定窗的边界突刺在日窗上存在但无害（重置点是日历零点，突刺没有攻击意义）。

**验证**：`src/test/java/com/linkforge/common/ratelimit/RedisRateLimiterTest.java`（单测）与 `src/test/java/com/linkforge/common/ratelimit/SlidingWindowRateLimiterIT.java`（边界突刺红转绿、Retry-After 语义、旧键不污染）。深讲见 [50-redis.md](50-redis.md)。

### D11 fail-open / fail-closed 矩阵

故障时元件该怎么表现？项目有一张明确的矩阵，每一行都是一个决策。

| 组件 | 策略 | 依据 |
|------|------|------|
| 限流（Redis 挂） | fail-open + 计数 | 保护措施不是安全边界，不能把业务打死 |
| Bloom / absent 探测 | fail-open | DB 兜底在后面 |
| 指标抓取令牌 | fail-closed | 指标泄露比缺监控更糟（javadoc 原话） |
| webhook 主密钥不可用 | 功能整体降级关 | 加密前提不成立就不假装加密 |
| singleflight 跟车超时 | 自读降级 | 有界延迟上界，代价一次行读 |

选择口诀：**业务保护 fail-open，安全边界 fail-closed**。限流、Bloom、absent 都是保护措施，它们自己故障时不该拖着业务陪葬，且每个都有 DB 兜底。指标抓取令牌（`src/main/java/com/linkforge/security/MetricsScrapeTokenFilter.java`）相反：令牌未配置或比对失败，端点对所有人 401，一个请求都不放。javadoc 写明理由：指标泄露比缺监控更糟。比对用 `MessageDigest.isEqual`，常量时间比较，不给时序侧信道留缝。

webhook 主密钥的降级最细腻（事实链在 `src/main/java/com/linkforge/webhook/support/WebhookSecretCipher.java` 与 `WebhookDeliveryRelay`）：密钥不可用时，endpoint 保存直接 503（secret 落库必须加密），投递发送侧整批跳过（不能签名就不发，不耗 attempts），但入队照常落 PENDING 行。拆开的原因：投递意图不能丢（入队），不能签名的事实也不能装作没看见（发送）。主密钥恢复后 PENDING 行自然发出，不需要重放。

fail-open 的另一半是**可见性**：每次降级都打点（`linkforge.ratelimit.failopen{reason}`、`linkforge.bloom.loadfailed`、`linkforge.webhook.relay.skipped{reason}`）。没有计数的 fail-open 不是降级设计，是静默故障。深讲见 [70-distributed.md](70-distributed.md) 的降级语境与 [61-docker-deploy.md](61-docker-deploy.md) 的可观测部分。

### D12 排序规则为什么是 `utf8mb4_0900_bin`（V4）

**问题**：短码要求区分大小写，但 V1 建表继承了库默认排序规则 `utf8mb4_0900_ai_ci`（ai=口音不敏感，ci=大小写不敏感）。后果不是「小写也能跳」这么简单，而是**行为随 Bloom 开关漂移**：Bloom 开着，小写变体被精确串比较拒绝，404；Bloom 关了，请求落到 DB，ci 排序规则命中大写码，302。同一个码，切一个开关，语义就变。测试无法钉住这种语义。

**备选**：一是应用层统一转小写，改的是产品语义（`A10x` 和 `a10x` 该是两个码）。二是 `utf8mb4_bin`，区分大小写但它是 PAD SPACE 规则：比较前补空格，`'abc'` 与 `'abc '` 相等。`/r/abc%20`（URL 里的空格）绕过 Bloom 的精确串比较，却在 DB 命中 `abc`。同一个洞换了个姿势。

**选择与理由**：`src/main/resources/db/migration/V4__short_code_case_sensitive.sql` 把 `lf_short_link.code` 改为 `utf8mb4_0900_bin`（NO PAD 加区分大小写），与 Bloom 的精确串比较语义完全一致。`uk_code` 随列重建而名字不变；已有的 ci 唯一索引排除过仅大小写不同的码，更严的 collation 不会引出新冲突，迁移必然成功（注释原文推导）。

**代价与纪律**：快照列（`lf_click_log.code`、`lf_click_daily.code`）不改。它们是从 DB 行复制的冗余快照，从不被比较或 join，改它们要重建持续增长的明细大表，收益为零。同时立规：跨表关联走 `link_id` 不走 `code`，混用不同排序规则的列直接比较会报 ERROR 1267。这四段理由全部写在迁移注释里，是第一手材料。

**验证**：Bloom 开关两个测试上下文里小写变体都 404（`BloomRedirectIT` 与 `BloomDisabledAtStartupIT`），证明语义不再随开关漂移；`FlywayBaselineIT` 断言 V4 存在且 collation 落库。深讲见 [40-mysql.md](40-mysql.md) 的排序规则部分。

## 决策清单（下）：隔离、一致性、幂等与代码形态 D13–D18

### D13 多租户隔离为什么是「查询带归属」，不是「查到再判断」

**问题**：管理接口要保证「只能操作自己的链」。最常见的写法是先按码查出链接，再比对 `createdBy` 是不是当前用户。

**备选**：「查到再判断」的问题在数据已经出库：日志、异常消息、序列化路径都可能泄露这条链接的存在。而且比对逻辑散在每个调用点，漏一处就是越权。

**选择与理由**：`ShortLinkService.getOwnedLink` 的查询条件永远带 `createdBy = 当前用户`。他人的行根本查不到，统一抛 404。404 而不是 403 也是同型决策：403 会向枚举者确认「这个码存在但不是你的」，404 让「不存在」与「不是你的」不可区分。同类做法：登录失败统一 401 文案「invalid credentials」，不区分租户不存在、用户不存在与密码错误（`AuthService.login`）。

**代价**：拿不到「存在但无权」的精确报错，对管理后台是可接受的模糊。查询多一个 WHERE 条件，索引下开销可忽略（据代码推断，未单独测）。

**验证**：`src/test/java/com/linkforge/link/TenantIsolationIT.java`：用户 B 读、改、禁用户 A 的码全部 404，B 的列表不含 A 的码。深讲见 [32-spring-security.md](32-spring-security.md) 与 [40-mysql.md](40-mysql.md)。

### D14 每次演进都留测试与审计痕迹（工程流程决策）

**问题**：项目一天多轮迭代，怎么保证每个改动是对的、出了问题能定位到哪一轮、审计能复核？

**备选**：只靠最终回归，行为坏了能发现，但「哪个行为是哪轮引入的」无从查起。只靠人眼 review，没有可复现的证据。

**选择与理由**：四个机制叠起来。一，红先：每轮先提交失败的测试，再提交实现（Bw345 轮在 `docs/progress.md` 留了完整的红绿 run 对照表）。二，progress.md 每轮决策表加红史留档，包括六红一绿的完整 CI 链。三，外部审计轮次，问题分 M/S/N 级登记处置。四，决策编号即 ID（`D-A9-5` 这类），每个决策可以单独引用和质疑。

**代价**：文档维护成本高；CI run 链接会腐化，需要钉子提交机制把每轮终点钉住。这些成本在个人项目里更明显，但它换来的是「每个行为有测试钉着，每次修复有可复现的红色证据」这句能在面试里站住的话。

**验证**：任一红先证据可临时还原复现：还原对应绿提交的实现文件、保留红测试提交的测试文件，CI 必红。深讲见 [60-testing-ci.md](60-testing-ci.md)。

### D15 缓存一致性组合：写穿 + 1 秒延迟双删 + 短 TTL

**问题**：D4 说缓存只存 URL。那 update、禁用之后，缓存怎么跟 DB 保持一致？并发读会在「删除缓存」和「DB 提交」之间穿插，单靠删除不够。

**备选**：一是只删除（失效后等回源）。竞态：读方在 DB 提交前读到旧行，在删除后回填旧值进缓存，旧值就驻留到 TTL。二是只写穿（提交后立即写新值进缓存）。同样的竞态：旧的读在写穿之后又把旧值写回去。三是版本号或分布式锁，为一次 URL 更新引入新机制，重。

**选择与理由**：三件套叠加（`ShortLinkService` 的 update/setStatus/delete 写路径，`src/main/java/com/linkforge/link/service/ShortLinkService.java`）。**写穿**：提交后立即 `cacheUrl` 写新值进 L1 和 L2，正常读者马上拿到新值。**1 秒延迟双删**：调度线程 1 秒后再删一次（`STALE_WRITEBACK_CLEAR_MS = 1000`），专清竞态窗口内回填的旧值。1 秒大于一次「读 DB 加回写缓存」竞态的典型耗时，这是工程参数不是精确证明。**TTL 兜底加抖动**：正缓存 TTL 3600 秒加 [0, 300] 均匀抖动（`jitteredTtlSeconds`，只向上抖），抖动防大量键同一时刻建立后同一时刻集体过期回源（雪崩）；TTL 本身是双删漏网时的最终一致性期限。三层各管一段：写穿管正常路径，双删管竞态，TTL 管漏网。

**代价**：最终一致窗口约 1 秒，明确可测；一个常驻的单线程 evictor；L1 的 invalidate 与 Redis 删除要同触（`evictCache` 一处删两层），漏一层就白删。

**验证**：`LinkLifecycleIT` 的 update 用例：PATCH 后跳转 Location 立即变新值（写穿生效），1.3 秒后（双删已触发）再跳仍是新值且缓存重建为新值。深讲见 [50-redis.md](50-redis.md)。

### D16 创建幂等：Idempotency-Key 的 pending 到 code 状态机

**问题**：调用方超时重试创建接口，同一业务意图会建出两条链。HTTP 语义上创建不是幂等的，需要显式机制。

**备选**：数据库唯一约束兜不住，Idempotency-Key 不是业务列。`ON DUPLICATE KEY` 探测也不行：progress 决策 #11 实测 MySQL Connector/J 默认返回 matched rows，重复键探测永远把重放当新事件（`ClickIdempotencyTest` 当年抓到的）。

**选择与理由**：Redis 两段式状态机（`ShortLinkService.create`）。第一步 `SET NX`（不存在才设置）键 `idem:{userId}:{key}` 为 `pending`，5 秒过期，这是领头方的租约。第二步建链成功后把值覆盖为终值 code，TTL 按配置保留（progress 记录默认 24 小时），这是重放窗口。重试方读到 code 就返回既有链；读到 pending 就轮询（最多 10 次、每次 200ms）；轮询尽仍是 pending 就 503「retry later」。503 那步是审计 M5 修出来的：原实现 pending 超时后强行二建，会一键两码。宁可让调用方重试，绝不冒两码的险。

**代价**：幂等状态存在 Redis 里，Redis 故障时创建路径受影响（据代码推断：`setIfAbsent` 异常会上抛为 500）；pending 窗口内的失败要靠调用方重试；幂等键空间按用户隔离，跨用户同键互不干扰是特性也是约束。

**验证**：`CreateIdempotencyAndConcurrencyIT`：串行重放同码且仅 1 行、4 线程并发同键全部同码且仅 1 行、预置 stale pending 键得 503 且 0 新链。深讲见 [50-redis.md](50-redis.md) 的 SET NX 语义；消费侧的幂等（另一端）见 D8 与 [51-rabbitmq-outbox.md](51-rabbitmq-outbox.md)。

### D17 record 与 sealed 的内外分工

**问题**：跳转门禁的结果不是一个布尔。302 跳转、403 拦截页、200 警告页、200 解锁页、HEAD 下 401，控制器要按结果渲染完全不同的响应，而且每个结果携带的数据不同：警告页要码、目标 URL、ASCII 域名；解锁页要整行。

**备选**：一是返回枚举加一堆可空字段，类型不携带数据，调用方靠判空拼装。二是把非 302 全用异常表达，把正常业务分支塞进异常机制，栈开销加语义污染（异常应该留给异常路径）。

**选择与理由**：`src/main/java/com/linkforge/link/service/RedirectOutcome.java` 是 sealed interface（密封接口，只允许列出的子类型实现）加四个 record 分支：`Redirect`（跳转）、`Blocked`（拦截）、`Locked`（要密码）、`Warn`（警告页）。404、410、429 留给异常，那才是真正异常的路径。分工逻辑：sealed 保证控制器按类型分发时编译器能检查穷尽性，新加一个分支而控制器忘了渲染，是编译错不是线上事故（sealed 与 record 深讲见 java/10-10-java17.md）。record 承载每个分支的数据且不可变。可变的实体 `ShortLink`（`@Data` 注解的普通类）留在服务层内部，出参一律不可变形态。`ShortLinkService.UnlockResult` 同理：record 加两个派生布尔方法。

**代价**：多一层类型；每加一个门禁要同时动 sealed 层级与控制器渲染。但穷尽性检查把「忘渲染」从线上事故变成编译错，这个代价是买保险。

**验证**：编译期穷尽性加 `PhishingGateIT`、`PasswordLinkIT` 的渲染断言。深讲见 [81-linkforge-flows.md](81-linkforge-flows.md) 的门禁段。

### D18 BizException 为什么是 unchecked 加全局处理器

**问题**：项目有几十个业务失败点（参数错、码占用、过期、越权、限流），每个都要映射到正确的 HTTP 状态码和统一 JSON 信封。没有统一机制的话，每个控制器方法都在 try/catch，重复且必然漏。

**备选**：checked 异常（受检异常，调用方必须声明或捕获）在这条链上不成立：业务方法层层向上抛，每层签名都要 `throws` 声明，污染所有中间层；而且 Spring 事务默认只对 RuntimeException（及其子类）自动回滚，checked 异常不回滚，语义还要额外配置。深讲见 java/10-06-exceptions.md 与 [30-spring-core.md](30-spring-core.md) 的事务部分。

**选择与理由**：`src/main/java/com/linkforge/common/exception/BizException.java` 继承 RuntimeException（unchecked，编译器不强制捕获），带一个 `int code` 字段。`src/main/java/com/linkforge/common/exception/GlobalExceptionHandler.java` 用 `@RestControllerAdvice` 全局接住：code 夹在 [400, 599] 内用之，否则回落 400（防业务代码手滑把状态码塞成 200 或 999）；`RateLimitedException` 继承 BizException 特化出 429 加 `Retry-After` 头；兜底 `Exception` 处理器记全栈日志、对外只回「internal error」，不泄内部信息。

**代价**：unchecked 异常编译器不强制处理，漏写抛出点编译期不报，靠全局处理器兜底加测试钉状态码。所有业务错误共用一个类型，类型本身不区分语义，区分靠 code 字段和消息。

**验证**：`RateLimitIT`、`RegisterFlowIT` 等全链状态码与 body code 双断言。深讲见 [31-spring-boot-mvc.md](31-spring-boot-mvc.md) 的异常解析。

## 故障案例：现象、排查、根因、修复、防复发

七个故障，每个按五段讲。排查段写成可跟随的推理链，一步一个观察。

### F1 20 秒退避变成 8 小时（Bw2-M1）

**现象**：webhook 投递失败进入退避后，重试长时间不发生。接收方几秒就恢复了，`lf_webhook_delivery` 里的 PENDING 行却像死了一样。

**排查**：第一步，查行。`status=PENDING`、`attempts=1`，`next_attempt_at` 在遥远的未来。第二步，量化距离。`SELECT TIMESTAMPDIFF(SECOND, NOW(), next_attempt_at)` 量出约 28820 秒，接近 8 小时；而配置的首次退避是 `10s·2^1=20s`。第三步，看差值结构。28820 不是随机数：约等于 8 小时加 20 秒。8 小时恰好是 Asia/Shanghai 与 UTC 的时区差，20 秒恰好是应有退避。到这一步可以直接提出假设：写入端用了 +8 时钟，比较端用 UTC 时钟。第四步，验证假设。旧实现里 Java 先用本地时钟算好 `LocalDateTime` 字面值，SQL 把它写进 `next_attempt_at`；比较端是 `next_attempt_at <= NOW()`，`NOW()` 是 DB 时钟。第五步，确认环境。本机 JVM 默认时区 Asia/Shanghai，Docker 里的 MySQL 是 UTC，假设完全成立：写入的是「+8 时钟下的 20 秒后」，读取比较的是「UTC 的现在」，中间差 8 小时。

**根因**：同一个调度字段，写入用 JVM 时钟，读取用 DB 时钟。时钟是系统配置，不是格式化细节。两套时钟混用，退避被放大一个时区差。

**修复**：`markRetry` 只收退避秒数，SQL 写 `DATE_ADD(NOW(), INTERVAL ? SECOND)`，写入与比较同用 DB 时钟；业务时间戳反向统一 JVM 时钟传参（`markSent`、`touchLastUsed` 改收 `#{now}`）。规则三分法见 D9。

**防复发**：`WebhookRetryClockIT` 在 pom 的独立 fork（`-Duser.timezone=Asia/Shanghai`）里跑，MySQL 容器保持 UTC，环境与故障同构。修复的有效性必须能在复现故障条件的环境里证明：普通环境 JVM 与 DB 同时区，这个 bug 根本复现不出来。断言直接比对差值，旧实现必红。

### F2 「Too many connections」（测试套件增长期）

**现象**：A9 轮给套件加了 `BloomRedirectIT`，CI 首跑红的却是一个与 Bloom 无关的老测试 `RegisterRateLimitIT`，报错是 Spring 上下文加载失败，底层数据库报 Too many connections。

**排查**：第一步，注意到报错的测试不是新测试，说明问题不在新测试的逻辑，在套件整体的环境容量。第二步，数上下文。Spring 测试框架按配置组合缓存应用上下文：`@SpringBootTest` 的 properties、`@ActiveProfiles`、`@SpyBean` 任一不同就是新上下文。第三步，数连接。每个上下文有自己的 Hikari 连接池（默认池大小 10），连的都是同一个共享的 Testcontainers MySQL 容器。第四步，查容器的天花板。MySQL 默认 `max_connections=151`。A8 轮结束时上下文数已经贴边，A9 新增一个上下文就推过了线。第五步，对账：约 21 个上下文乘 10 连接，远超 151。

**根因**：共享 MySQL 是测试套件的公共容量池。连接预算等于上下文数乘池大小，超了就 Too many connections。哪个测试先炸取决于哪个上下文后启动，与它自己的代码无关，所以现象有迷惑性。

**修复**：共享容器启动参数加 `--max-connections=500`（`src/test/java/com/linkforge/AbstractIntegrationTest.java` 的注释保留了原话和教训）。一行，不动任何池语义。

**防复发**：连接预算心算成为每轮惯例：Bw2 轮记 21×10 加 4×4 等于 226，Bw345 轮记 24×10 加 4×4 等于 256。v2.0 审计澄清口径：256 是「每池同时满载」的最坏纸面推导（各上下文懒建连接，极少同时满载），真实天花板是 500，余量 244。新上下文尽量复用既有属性集，`WebhookRetryClockIT` 复用 `WebhookDeliveryIT` 的配置且 fork 不并存，预算不变。

### F3 宽扫描把普通接口变成 mapper

**现象**：P2 轮接上真实消费者后，`ClickEventPublisher.publish()` 运行时报 Invalid bound statement，MyBatis 找不到这条语句的映射。

**排查**：第一步，注意矛盾。`publish()` 是普通 Java 接口方法，不是 mapper 方法，为什么会有人去找它的「bound statement」？第二步，查 Bean 定义。`@MapperScan("com.linkforge")` 扫了整个项目包：这个注解的语义是「这个包下的接口都是 MyBatis mapper」，于是它给 `ClickEventPublisher` 这样的普通接口也生成了 mapper 代理 Bean，`publish()` 被当成一条没有 XML 或注解映射的 SQL。第三步，解释为什么 P1 没炸。当时消费者还是 stub（占位实现），异步开关恒关，`publish()` 从未被调用；坏的代理 Bean 一直存在，只是没人踩。

**根因**：扫描范围大于语义边界。把整包交给 `@MapperScan`，等于宣称「全项目接口都是 mapper」。方便的宽默认值在规模下都是雷。

**修复**：`src/main/java/com/linkforge/LinkForgeApplication.java` 显式列出五个 mapper 包（user、link、stats、apikey、webhook），注释保留事故原话。

**防复发**：新模块加 mapper 包时必须显式加入这个列表；对一切「方便的宽默认值」（组件扫描、自动配置同理）保持警惕。mapper 代理的机制深讲见 [41-mybatis-plus.md](41-mybatis-plus.md)，动态代理的语言层原理见 java/10-07-annotations-reflection-proxy.md。

### F4 本机压测 600ms 一次提交是环境不是代码

**现象**：P4 本机压测，跳转只有 13 QPS、P50 678ms。按请求数换算，每次 MySQL 提交约 600ms。对一条 INSERT 加一次提交来说，这个数字离谱。

**排查**：第一步，排除框架与网络。health 基线（无 DB 写）888.8 QPS、P50 9.9ms，wrk 到容器 NAT 到 Spring MVC 这条链足够快。第二步，隔离变量。关掉 MQ 异步发布（no-mq 场景）11.6 QPS，与 default 的 13.3 在波动内持平，MQ 不背锅。第三步，关掉同步明细写（no-sync 场景）276.1 QPS、P50 31.4ms，约 21 倍差，瓶颈锁定在 `lf_click_log` 的同步 INSERT 加提交。第四步，交叉验证环境。同一仓库在 GitHub Actions 的 Linux runner 上，15 个大量写库的集成测试 5.6 秒跑完，单次提交毫秒级。第五步，下结论：同一份代码两个环境差 21 倍，差异只能在环境，即 Windows Docker Desktop 的卷存储路径（WSL2 文件系统跨界）。

**根因**：环境，不是代码。600ms 一次提交是本机存储路径特性，不是设计缺陷的证据。反过来它也量化了一个设计事实：同步明细写确实是跳转吞吐的支配项。

**处置**：数字如实记录进 `docs/bench.md`，带时期注（`df904f8`，2026-10-24，固定窗口限流时期，Windows 10 加 Docker Desktop/WSL2）；复测挂 backlog B10（Linux 上用 wrk 复跑）。这条实践与 [14-algorithms.md](14-algorithms.md) 的 explain-the-number 同源：先解释数字，再相信数字。

**防复发**：性能数字入库强制带「时期注加环境注」；未复测前，引用旧数字必须注明不代表当前版本。

### F5 少一个 Jackson 模块，全量消息进死信

**现象**：P2 轮首次端到端测试：事件发布成功，聚合计数却是零。查队列，消息全部躺在死信队列 `linkforge.click.dlq`。

**排查**：第一步，看死信里的消息内容，确认每条都在消费端反序列化阶段失败。第二步，看 `ClickEvent` 的字段，`clickedAt` 是 `LocalDateTime`。第三步，看发布用的转换器。`Jackson2JsonMessageConverter` 是 `new` 出来的，它自建一个默认 `ObjectMapper`（Jackson 的 JSON 读写器）。第四步，查 Jackson 的默认行为：不注册 `JavaTimeModule`（处理 `java.time` 类型的扩展模块）的 `ObjectMapper`，遇到 `LocalDateTime` 直接抛 InvalidDefinitionException。第五步，接上消费端行为：监听容器对抛异常的消息拒绝且不回队，路由到死信交换机。于是每条含时间字段的消息都在反序列化时炸掉，全量死信。

**根因**：框架行为。`Jackson2JsonMessageConverter` 的默认构造不共享 Spring 上下文的 `ObjectMapper`；Boot 自动配置的那个注册了 `JavaTimeModule`，转换器自建的那个没有。两个 mapper 看似同类，能力不同。

**修复**：`src/main/java/com/linkforge/config/RabbitMqConfig.java` 的转换器 Bean 改为注入 Spring 的 `ObjectMapper`，注释保留原话：「Spring's mapper has JavaTimeModule; the converter's default one cannot round-trip LocalDateTime and dead-letters every ClickEvent.」

**防复发**：任何要序列化 `java.time` 类型的组件，一律用 Boot 装配的 `ObjectMapper`，不要自己 new。`MqEndToEndIT` 常驻套件，转换器再退化会当场红。`java.time` 类型深讲见 java/10-09-enum-datetime-bigdecimal.md，死信路径见 [51-rabbitmq-outbox.md](51-rabbitmq-outbox.md)。

### F6 Windows 上打包失败：运行中的进程锁住了 jar

**现象**：P1 期间改完代码重新打包，`mvn package` 失败，报错指向无法写入 `target` 下的 jar。v1.0.0 发布构建时再次出现。

**排查**：第一步，看报错对象，正是 `target/linkforge-*.jar`。第二步，查谁在用它：用 `java -jar` 启动的应用进程还在后台跑。第三步，对出操作系统行为差异：Windows 对进程打开的可执行文件实施强制锁，其他进程不能写、不能删；Linux 允许删除或替换打开中的文件（旧 inode 由句柄持有到关闭）。所以「边跑边打包」在 Linux 能过，在 Windows 必炸。

**根因**：Windows 强制文件锁，加打包目标正是运行中进程持有的文件。这是操作系统行为差异，不是构建配置错误。

**修复**：流程修正为先停进程再打包。progress 多轮记录同一动作（「重打包时旧 app 进程锁 jar（先停进程再打包）」、v1.0.0 的「停旧进程解锁 jar」）。Bw2fix 期间还踩到变体：8080 端口残留一个没配主密钥的旧进程，新配置的请求打到旧进程，得出误导性的 503，杀干净后一切正常。

**防复发**：重启验证的固定序列是停旧进程、打包、启动、验证；验证前先确认目标端口上没有残留进程。Windows 部署注意点见 [61-docker-deploy.md](61-docker-deploy.md)。

### F7 WRONGTYPE：STRING 键上跑 ZSET 命令（sw 前缀的由来）

**现象**：B6 轮把限流从固定窗计数（Redis STRING 键上的 INCR）换成滑窗（ZSET 有序集合）。如果沿用旧键名，风险立刻成立：旧版本留下的 STRING 计数器键在 TTL 内还没过期，ZSET 命令打上去，Redis 报 WRONGTYPE。

**排查与推演**：第一步，Redis 的键名不承载类型。同一个键名可以先后是 STRING 和 ZSET，但对已有键执行类型不匹配的命令会报 WRONGTYPE。第二步，算共存期。旧键的 TTL 是窗口长度（秒级），升级切换的瞬间新旧实现共存，残留键必然存在一段时间。第三步，追后果链。Lua 脚本抛错，`RedisRateLimiter` 捕获，走 fail-open 放行。也就是说最坏结果是限流器静默失效：保护措施悄悄消失，而不是请求报错。这与 D11 的可见性纪律正好互相印证，fail-open 必须打点，否则这类故障无法被看见。

**根因**：不同数据结构的键共用一个命名空间。Redis 的命名空间只是字符串前缀，不表达类型。

**修复**：滑窗键全部挪进 `{prefix}sw:` 子命名空间（`src/main/java/com/linkforge/common/ratelimit/RateLimitKeys.java`，javadoc 记录为 D-Bw345-10）。日配额固定窗键继续用原前缀，但那本来就是 STRING 语义，自洽。严格说：仓库证据是 javadoc 加钉子测试，javadoc 用虚拟语气描述后果，它更像升级前预判并规避的记录；是否真的在某个环境触发过，代码与 progress 没有日志级证据（据代码推断）。

**防复发**：`src/test/java/com/linkforge/common/ratelimit/SlidingWindowRateLimiterIT.java` 的 `legacyStringKeyDoesNotBreakTheSlidingNamespace`：预置旧名 STRING 残键，断言 sw 键照常以 ZSET 工作、fail-open 计数不涨。规则入册：一个前缀只放一种结构。深讲见 [50-redis.md](50-redis.md)。

## 数字纪律（对外口径白名单）

简历和面试里能说的数字，每一条都要带出处与时期注：

- 211 例测试构成（90 单测加 119 IT 加 2 时区 fork，v2.0.0 发布口径）。
- Bloom fpp=1e-4；迁移 V1–V7。
- 滑窗限流入口与 Retry-After 语义（来自真实窗口计算）。
- 13→276 QPS、P50 678→31ms（注明：2026-10-24，固定窗口限流时期，Windows 本机 Docker Desktop，不代表 v2.0.0 现状，复测挂 backlog B10）。
- health 基线 888.8 QPS（同一时期同一环境，用于排除框架与网络）。
- Redis 预热命中率 100%（压测场景内，预热后纯命中）。
- 连接预算 256（最坏推导）对 500（容器天花板）。

不能说的：万级 QPS、令牌桶或漏桶实现（项目没做）、「防钓鱼系统」（B9 是规则门禁不是钓鱼检测）、任何未实测数字、端到端 exactly-once（Outbox 是 at-least-once 加消费幂等，语义上等价于「不丢不重」，但 exactly-once 是投递语义的专有名词，不冒领）。

未验证的事如实说：B10 复测没跑、多实例行为没测、告警实践没有。承认边界比被抓包可信。

## 常见追问

- **「这些设计哪些是过度设计？」** 诚实候选是 Bloom：Demo 量级下穿透流量根本不存在，它防的是未来增长与面试考点，成本一个类加一个依赖（backlog 也这么记录）。能自评过度设计，比辩护所有设计可信。
- **「如果重新设计会改什么？」** 保留五层防线与 Outbox。简化方向：Bloom 可以后置（先上 absent 缓存，B7 的数据证明它覆盖了大部分负答流量）；webhook 二跳可以并入 MQ 投递语义。但每项都要说「现在做的理由」，不是全面推翻。
- **「数据量涨十倍先看什么？」** 先 EXPLAIN 明细查询与聚合路径，看索引是否还成立；然后分区或归档（backlog 已有方向）；然后读写分离；最后才谈分表。顺序的逻辑是先免费的、后昂贵的、最后不可逆的。

## 自测

1. D4 与 D6 的关系是什么？为什么「每请求读行」不会杀死性能？
   答：D4 定语义（行是真相，缓存只省 URL 字段），D6 定成本（行读合并）。热路径前几层（限流、Bloom、absent、L1、L2）挡掉大部分流量，singleflight 把剩余的行读压到每码每时刻一次。
2. 时区修复后为什么还要保留时区 fork 测试？
   答：普通环境里 JVM 与 DB 同时区，这个 bug 复现不出来。修复的有效性必须在与故障同构的环境里证明，所以 fork 固定 JVM 在 +8、MySQL 在 UTC。测试环境要能重现故障条件。
3. 「被拒不占名额」对应哪个决策？如果被拒也记账会怎样？
   答：D10 滑窗日志（只有放行才 ZADD）。若被拒也记账，攻击者可以用刷请求把自己（或别人）永久锁在窗口里，限流从保护变成武器。
4. V4 为什么不顺手把 click_log 的 code 列也改成 bin 排序规则？
   答：快照列从不被比较或 join，改它要重建持续增长的明细大表，收益为零；迁移注释同时立规：跨表关联走 link_id 不走 code，混用排序规则比较会报 ERROR 1267。
5. F7 里 fail-open 为什么让 WRONGTYPE 变得更危险，而不是更安全？
   答：ZSET 命令打在 STRING 键上报 WRONGTYPE，limiter 捕获后 fail-open 放行。故障的表现不是报错而是保护静默消失。这解释了 D11 为什么要求 fail-open 必须带计数器：没有计数的降级是看不见的故障。
6. bench 的 13→276 QPS 在简历上怎么写才不翻车？
   答：写「同步明细写是跳转吞吐支配项，关闭后 13→276 QPS（2026-10-24 固定窗口时期，Windows Docker Desktop 本机实测，不代表 v2.0.0 现状，Linux 复测中）」。环境、时期、结论三要素齐全。

## 下一步

- 决策对应的实现细节回各专题档：缓存与限流见 [50-redis.md](50-redis.md)，Outbox 与幂等见 [51-rabbitmq-outbox.md](51-rabbitmq-outbox.md)，索引与排序规则见 [40-mysql.md](40-mysql.md)，事务与异常见 [30-spring-core.md](30-spring-core.md)。
- 十二站跳转判定门的逐步代码走读见 [81-linkforge-flows.md](81-linkforge-flows.md)。
- 把决策讲成项目经历的方法见 [91-interview-project.md](91-interview-project.md)。
- 仓库原始决策档案：`docs/progress.md`（每轮决策表）、`docs/bench.md`（压测）、`docs/backlog.md`（后置项）。
