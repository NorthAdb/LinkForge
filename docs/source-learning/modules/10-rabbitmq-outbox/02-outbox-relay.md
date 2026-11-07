# 10.2 Outbox 与 Relay：同事务双写、定时抽水与指数退避

> 本课回答一个问题：跳转事务里多写的那行 `lf_outbox` 是干嘛的？为什么有了它，RabbitMQ 停机一小时，跳转依旧 302、点击一行不丢？本课从「双写问题」讲起，逐行拆 `ClickOutboxRelay` 的 79 行，并把 `outbox-drill.sh` 的故障剧本对到代码上。

## 1. 本节学习目标

学完本课，你应该能：

1. 画出双写问题的两种丢法（先发后滚、先滚后发），并说清 Transactional Outbox 为什么两个都治。
2. 逐行解释 `ClickRecordService.recordClick`：同步分支、异步分支、`eventId` 的两种取值决策（:61-63）。
3. 背出 `OutboxMapper` 的四条 SQL，说出 `selectPendingBatch` 的三个过滤条件与扫描序索引。
4. 逐行拆 `ClickOutboxRelay.relay`：每 tick 重读开关的原因、FAILED 判定、退避公式 `min(60, 2^n)`、三个 meter。
5. 解释「多实例 at-least-once」从哪来、为什么消费端幂等是唯一兜底，以及 FAILED 行的手工重放 SQL 与清理任务。

## 2. 前置知识

- **双写问题（dual write）**（通用分布式问题）。业务要同时改数据库和发消息，两步不在一个事务里：先发消息后提交 DB，DB 回滚则消息是幽灵数据；先提交 DB 后发消息，MQ 挂了则消息永久丢失。
- **本地消息表 / Transactional Outbox**（模式名）。把「发消息」变成「在同一个 DB 事务里插一行待发记录」，再用独立进程轮询这行记录去真正发送。DB 事务保证「业务写入」与「消息意图」同生共死，发送失败就留在表里重试。本项目的 `lf_outbox` 就是这张本地消息表。
- **@Scheduled 的 fixedDelay 与 initialDelay**（框架行为，1.2 与 14.3 课有全景）。`fixedDelay` 是上一轮**结束后**再计时，任务不会叠跑；`initialDelay` 是启动后先等多久。两者写成 `fixedDelayString` 的属性引用，从配置读毫秒数，便于测试调快（`ClickOutboxRelay.java:37-38`）。
- **指数退避（exponential backoff）**。失败后等待时间按倍数增长（1、2、4、8...），避免对故障方高频重试；通常设上限封顶。
- **at-least-once 投递语义**（通用概念）。重试机制保证消息至少送达一次，代价是可能重复；重复必须由接收方幂等消化（10.3 课）。
- **唯一索引作并发防线**（模块 4 知识）。`uk_outbox_event_id` 保证同一 eventId 的 outbox 行最多一条，是重复写入的 DB 兜底。

## 3. 本节涉及的源码

| 文件 | 行数 | 本课关注点 | 阅读顺序 |
|----|----|----|----|
| `src/main/java/com/linkforge/stats/service/ClickRecordService.java` | 91 | recordClick 全文 :40-75、toJson :77-83 | 1 |
| `src/main/resources/db/migration/V2__outbox.sql` | 21 | lf_outbox DDL :5-16、event_id 注释 :18-20 | 2 |
| `src/main/java/com/linkforge/stats/entity/Outbox.java` | 29 | 三个状态常量 :25-28 | 3 |
| `src/main/java/com/linkforge/stats/mapper/OutboxMapper.java` | 36 | 四条状态 SQL :14-35 | 4 |
| `src/main/java/com/linkforge/stats/service/ClickOutboxRelay.java` | 79 | relay() :39-73、backoffSeconds :75-78 | 5 |
| `src/main/java/com/linkforge/link/service/ShortLinkService.java` | 1025 | recordClick 调用与 fail-open :565-585 | 6 |
| `src/main/java/com/linkforge/retention/SentRowCleanup.java` | 73 | outbox 清理分支 :52-72 | 7 |
| `src/test/java/com/linkforge/stats/OutboxDrillIT.java` | 134 | 两个验收用例 :75-133 | 8 |
| `scripts/outbox-drill.sh` | 99 | 停 MQ 剧本 :62-99 | 9 |

阅读顺序的理由：先看谁在事务里写行（写入方），再看行长什么样（DDL+实体），然后看谁把行抽走（mapper+relay），最后看可靠性如何被测试和演练证明。`ClickLogReconciler` 是另一条安全网，留给 10.3 与 11.3 课。

## 4. 类的定位

- `ClickRecordService` 是 `lf_outbox` 与 `lf_click_log` 的**唯一写手**（类注释自称 "the only writers are this transaction"，`ClickRecordService.java:34`）。它被 `ShortLinkService.resolveForGet` 在每次成功跳转后调用（`ShortLinkService.java:574`）。
- `ClickOutboxRelay` 是唯一的抽水机：`@Scheduled` 每 2 秒把 PENDING 行搬进 RabbitMQ（10.1 课的 `publish` 由它调用）。它与 `ClickRecordService` 之间**没有类级调用关系**，只通过 `lf_outbox` 表行解耦（`04-core-class-relations` 图特意纠正过这一点）。
- `Outbox` 实体 + `OutboxMapper` 是这张表的全部门面：状态常量在实体（:25-28），状态迁移 SQL 在 mapper（:14-35）。`SentRowCleanup` 和 `ClickLogReconciler` 也要用它们，但都只调不另写 SQL。
- `SentRowCleanup` 在 `retention` 包，管「发完的行留 7 天后删除」，是 outbox 的保洁员（14.3 课入调度地图）。
- `OutboxDrillIT` 与 `outbox-drill.sh` 是可靠性的两组证人：前者在 Testcontainers 里断言事务与收敛，后者在真实 compose 栈上停 broker 演练。

## 5. 核心源码详解

### 5.1 双写问题与本项目写法

先用示意代码看反面教材（示意，非项目代码）：

```java ignore-check
// 反面教材：两步不在一个事务里
public void badRecord(ClickEvent e) {
    jdbc.update("INSERT INTO lf_click_log ...", e);   // 第 1 步：DB 写
    rabbitTemplate.convertAndSend("ex", "k", e);      // 第 2 步：MQ 发
}
```

第 1 步成功、第 2 步失败：明细在、消息没，统计永远少这一下。第 2 步成功、第 1 步回滚（比如同事务里还有别的写触发了约束冲突）：消息在、明细无，消费端聚合出幽灵点击。把第 2 步塞进 DB 事务也不行，MQ 不支持参与 MySQL 的事务。治法就是 Outbox：**把「发消息」降级成「插一行」，插行与业务写天然同事务**。发送从热路径剥离，交给 10.1 课那个「必须等 broker 确认」的 publisher，由 relay 慢慢做。

### 5.2 recordClick：同事务双写

```java
    @Transactional
    public void recordClick(ShortLink link, HttpServletRequest request, String variant) {
        LocalDateTime now = LocalDateTime.now();
        String ip = ClientIps.resolve(request, properties.getRateLimit().isTrustXff());
        String ua = truncate(request.getHeader("User-Agent"), 512);
        String referer = truncate(request.getHeader("Referer"), 512);
```
`ClickRecordService.java:40-45`

`@Transactional`（:40）罩住整个方法。三个提取动作先行：`ClientIps.resolve` 按 XFF 信任开关取真实 IP（2.2 课讲过它为什么不默认信任代理头），UA 与 Referer 截断到 512 字符对齐 `lf_click_log` 列宽（表结构见 4.1 课）。

```java
        if (properties.getMq().isSyncClickEnabled()) {
            clickLogMapper.insert(logRow);
        }

        if (properties.getMq().isAsyncClickEnabled()) {
            String eventId = logRow.getId() != null
                    ? "log-" + logRow.getId()
                    : UUID.randomUUID().toString();
            Long userId = UserContext.get() == null ? null : UserContext.get().getUserId();
            ClickEvent event = new ClickEvent(
                    eventId, link.getTenantId(), link.getId(), link.getCode(),
                    userId, ip, ua, referer, now, variant);
            Outbox outbox = new Outbox();
            outbox.setEventId(eventId);
            outbox.setPayload(toJson(event));
            outbox.setStatus(Outbox.STATUS_PENDING);
            outbox.setAttempts(0);
            outboxMapper.insert(outbox);
        }
    }
```
`ClickRecordService.java:56-75`（logRow 组装在 ：47-55，10 个 setter 与 ClickEvent 字段一一对应）

两个开关四种组合，本课先记住主线：**sync 开 = 写明细行；async 开 = 写 outbox 行**。

`eventId` 决策（:61-63）是幂等体系的根。sync 开着时 `logRow.getId()` 已经拿到（MyBatis-Plus 回填自增主键），eventId 定为 `log-{id}`，与 10.3 课对账重放用的 `CONCAT('log-', l.id)` 严格同构。sync 关着时没有明细行可挂靠，eventId 用 `UUID.randomUUID()`，一次性生成后**存在 outbox 的 payload 里**，重试、重发、重放都是同一串，消费端照样只计一次。`UserContext.get()` 允许为 null（:64）：匿名跳转没有登录态。

`toJson`（:77-83）失败抛 `IllegalStateException`。这个异常发生在 `@Transactional` 内，整件事（明细+outbox）一起回滚，调用方 `ShortLinkService` 在事务外 catch 住 DataAccessException，跳转照常 302（:575-580，fail-open 语义 11.1 课展开）。

### 5.3 lf_outbox 表与状态机

```sql
CREATE TABLE lf_outbox (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id        VARCHAR(64)  NOT NULL,
    payload         JSON         NOT NULL,
    status          VARCHAR(16)  NOT NULL COMMENT 'PENDING / SENT',
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at DATETIME     NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at         DATETIME     NULL,
    UNIQUE KEY uk_outbox_event_id (event_id),
    KEY idx_outbox_pending (status, next_attempt_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```
`V2__outbox.sql:5-16`

两个索引各有故事。`uk_outbox_event_id` 是重复写入的 DB 兜底：就算代码 bug 导致同 eventId 插两次，第二条撞唯一键失败。`idx_outbox_pending (status, next_attempt_at, id)` 正是 `selectPendingBatch` 的扫描序（等值列在前、范围/排序列在后），relay 的轮询永远走这个索引而不是全表。

状态机只有三态，常量钉在实体上：

```java
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SENT = "SENT";
    /** Terminal state after relay-max-attempts failed publishes; manual replay is a SQL UPDATE back to PENDING. */
    public static final String STATUS_FAILED = "FAILED";
```
`Outbox.java:25-28`

迁移文件 ：9 的注释只写了 `PENDING / SENT`，FAILED 是后来补的终态（`LinkForgeProperties.Mq.relayMaxAttempts` 的注释引用了审计项 A7 audit N3，`LinkForgeProperties.java:125-126`）。

### 5.4 OutboxMapper：四条状态 SQL

```java
    @Select("SELECT * FROM lf_outbox WHERE status = 'PENDING' "
            + "AND (next_attempt_at IS NULL OR next_attempt_at <= NOW()) "
            + "ORDER BY id ASC LIMIT #{limit}")
    List<Outbox> selectPendingBatch(@Param("limit") int limit);

    @Update("UPDATE lf_outbox SET status = 'SENT', sent_at = NOW() WHERE id = #{id}")
    int markSent(@Param("id") long id);

    @Update("UPDATE lf_outbox SET status = 'FAILED' WHERE id = #{id}")
    int markFailed(@Param("id") long id);

    @Update("UPDATE lf_outbox SET attempts = attempts + 1, next_attempt_at = "
            + "DATE_ADD(NOW(), INTERVAL #{backoffSeconds} SECOND) WHERE id = #{id}")
    int markRetry(@Param("id") long id, @Param("backoffSeconds") long backoffSeconds);
```
`OutboxMapper.java:14-27`

`selectPendingBatch` 三个条件逐个说：`status='PENDING'` 只看待发；`next_attempt_at IS NULL OR <= NOW()` 把「从未失败过的新行」与「退避到期的旧行」都放进来（新行不设 next_attempt_at，NULL 即立即可发）；`ORDER BY id ASC LIMIT #{limit}` 先来先发，天然公平。

注意一个与本课任务书说法相反的事实：**这三条 mark SQL 的 WHERE 条件只有 `id = #{id}`，没有 `AND status = 'PENDING'` 守卫**。对照 webhook 侧的同名方法，那里三条 mark 全部带 `AND status = 'PENDING'`（`WebhookDeliveryMapper.java:27-39`）。outbox 版不守卫是安全的，理由：relay 对某行的「拉取→发布→mark」在同一轮循环内同步完成，中间没有别的写者会改状态（多实例下两个 relay 拿到同一行的窗口见 5.6，markSent 重复执行只是把 SENT 写成 SENT，幂等无害）。webhook 版必须守卫，因为 HTTP 投递最长 7 秒（超时配置 `application.yml:166-167`），远超 2 秒 tick，同一行可能在两个 tick 里各有一份在途尝试，状态守卫让「先到的 markSent」赢（推断：以上差异原因是从 tick 周期与在途时长推出来的，代码注释未明说）。

`markRetry` 的两个细节：`attempts = attempts + 1` 在 DB 端自增，不吃内存旧值；`DATE_ADD(NOW(), ...)` 用 **DB 时钟**写调度列。这是全项目的时钟规则（webhook 侧注释总结过：调度列用 DB 时钟，业务时间戳用 JVM 时钟，`WebhookDeliveryMapper.java:14-19`），测试 `WebhookRetryClockIT` 曾用 JVM+8 与 DB UTC 的偏差抓过这条规则的违规。

第四条 SQL 留给清理：

```java
    @Delete("DELETE FROM lf_outbox WHERE status = 'SENT' AND sent_at IS NOT NULL "
            + "AND sent_at < DATE_SUB(NOW(), INTERVAL #{retentionSeconds} SECOND) LIMIT #{limit}")
    int deleteSentOlderThan(@Param("retentionSeconds") long retentionSeconds, @Param("limit") int limit);
```
`OutboxMapper.java:33-35`

javadoc（:29-32）写明两件事：cutoff 用 DB 时钟（因为 `sent_at` 是 `NOW()` 写的）；只删终态 SENT，PENDING/FAILED 永不删。

### 5.5 ClickOutboxRelay 逐行

```java
    @Scheduled(fixedDelayString = "${linkforge.mq.relay-fixed-delay-ms:2000}",
            initialDelayString = "${linkforge.mq.relay-initial-delay-ms:3000}")
    public void relay() {
        LinkForgeProperties.Mq mq = properties.getMq();
        if (!mq.isRelayEnabled() || !mq.isAsyncClickEnabled()) {
            return;
        }
```
`ClickOutboxRelay.java:37-43`

类注释（:17-23）三句话交代了全部设计：失败保持 PENDING 退避重试，broker 宕机不丢点击也不堵跳转；**开关每 tick 重读**，drills 与测试能在运行时拨动；超过上限标 FAILED 留查，重放是一条 UPDATE。为什么开关要每 tick 读？因为 `OutboxDrillIT` 在测试运行中直接改 `properties.getMq()` 的字段来开关 relay（`OutboxDrillIT.java:77` 关、:93 再开），Spring 的 `@Scheduled` 方法每次执行重新取值，这种拨动才生效。若把开关读进构造器或 `@PostConstruct`，测试就只能重启上下文。

```java
            List<Outbox> batch = outboxMapper.selectPendingBatch(mq.getRelayBatchSize());
            int sent = 0;
            for (Outbox row : batch) {
                if (row.getAttempts() != null && row.getAttempts() >= mq.getRelayMaxAttempts()) {
                    outboxMapper.markFailed(row.getId());
                    meterRegistry.counter("linkforge.outbox.failed").increment();
                    log.error("outbox event={} exceeded {} attempts, marked FAILED",
                            row.getEventId(), mq.getRelayMaxAttempts());
                    continue;
                }
                try {
                    ClickEvent event = objectMapper.readValue(row.getPayload(), ClickEvent.class);
                    clickEventPublisher.publish(event);
                    outboxMapper.markSent(row.getId());
                    sent++;
                    meterRegistry.counter("linkforge.outbox.sent").increment();
                } catch (Exception ex) {
                    outboxMapper.markRetry(row.getId(), backoffSeconds(row.getAttempts()));
                    log.warn("outbox relay deferred event={} attempt={}: {}",
                            row.getEventId(), row.getAttempts() + 1, ex.getMessage());
                }
            }
            if (sent > 0) {
                log.info("outbox relay delivered {} events", sent);
            }
        } catch (Exception ex) {
            log.warn("outbox relay pass failed: {}", ex.getMessage());
        }
    }
```
`ClickOutboxRelay.java:44-73`

单轮五个动作：

1. **拉一批**（:45）。批 100（`application.yml:180`）。
2. **毒行判定**（:48-54）。拉出来时先看 `attempts`：已失败 30 次（`relay-max-attempts`，`application.yml:183`）就 `markFailed` + 计数 `linkforge.outbox.failed` + error 日志，`continue` 跳过，不再尝试发布。FAILED 是终态，relay 永不再碰它。
3. **发布**（:55-60）。payload JSON 反序列化回 `ClickEvent`，调 10.1 课的 `publish`（含 confirm 等待），成功 `markSent` + 计数 `linkforge.outbox.sent`。
4. **退避**（:61-65）。任何异常（发布 nacked/超时/不可路由，甚至反序列化失败）都走 `markRetry`：attempts+1、`next_attempt_at = NOW()+退避秒`，warn 日志带 attempt 序号。行留在表里，本轮继续处理下一行。
5. **整轮兜底**（:70-72）。`selectPendingBatch` 本身也可能失败（DB 抖动），外层 catch 吞掉整轮只记 warn，2 秒后再来。没有这个 catch，调度器会记录异常栈，任务本身不会停（框架行为：`@Scheduled` 吞异常继续调度），但日志会刷屏且没有这么明确的一句话。

退避公式：

```java
    private static long backoffSeconds(Integer attempts) {
        int n = attempts == null ? 0 : attempts;
        return Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(n, 6));
    }
```
`ClickOutboxRelay.java:75-78`

`1L << min(n, 6)` 即 `2^n`，n 被钳到 6（`1<<6=64`），再被 `MAX_BACKOFF_SECONDS=60`（:29）压平。失败序列是 1、2、4、8、16、32，然后恒 60 秒。30 次失败的累计等待约 `1+2+4+8+16+32+60×24 = 1503 秒`，25 分钟量级（推断：按公式手算的算术，非实测）。注意参数语义：`attempts` 是**拉取时读到的已失败次数**，首次发布失败时传 0，退避 1 秒。

### 5.6 多实例 at-least-once 与消费端兜底

多实例部署时，两个 relay 同一毫秒各跑一次 `selectPendingBatch`：MySQL 默认隔离级别 REPEATABLE READ 下，两个事务都能读到同一批 PENDING 行，`LIMIT 100` 也不互斥（普通 SELECT 无锁）。于是同一行可能被发布两次。进程内还有更小的窗口：`publish` 成功之后、`markSent` 落库之前 relay 崩溃，行仍是 PENDING，重启后重发。两条路都通向**同一行至少发一次（at-least-once）**。

这不是 bug 是取舍：消除重复要分布式锁或 `SELECT ... FOR UPDATE` 跳过锁行，复杂度都进了热路径之外的关键区。项目选择让重复发生，把消重责任推给消费端 `lf_consumed_event` 主键（10.3 课）。README 把这层配合写成了口径：「多实例 at-least-once，消费端 eventId 幂等兜底」（`README.md:412`）。`markSent` 重复执行无副作用（`WHERE id = #{id}` 幂等改写同一终态），`markRetry` 重复执行最多多退避一次。

### 5.7 FAILED 的重放与 SENT 的清理

FAILED 行的救济是手工 SQL，README 给了标准答案（`README.md:412`）：

```sql
UPDATE lf_outbox SET status='PENDING', next_attempt_at=NULL WHERE status='FAILED';
```

`status` 拨回 PENDING 让 relay 重新捞起；`next_attempt_at` 置 NULL 让它跳过退避立即到期。可加 `AND event_id='log-123'` 之类条件精确重放单条。重放后消费端幂等会吸收任何已被算过的部分（10.3 课的三要素），所以重放不会把统计翻倍。

SENT 行的保洁是 `SentRowCleanup.purgeSent`（`SentRowCleanup.java:52-72`）：`retention.sent-days` 默认 7 天（`application.yml:235`），`<=0` 整个任务关闭；每 tick 最多 `cleanup-max-passes`(10) 轮、每轮 `cleanup-batch-size`(1000) 条地分批删（:63-69），删完 outbox 分支走 `deleteSentOlderThan`（:64）。默认每小时跑一次（`SentRowCleanup.java:34-35`），与 relay 共享 6 线程调度池但都很快，14.3 课讲池隔离时再看 webhook 慢任务为什么必须另立 relay。

### 5.8 演练与测试的对应

`OutboxDrillIT`（Testcontainers，随 CI 跑）两条腿：

- 用例 1（:75-100）：关 relay → 3 次跳转 → 断言明细 3 行、outbox 3 行（含「全部 `log-` 前缀」的事件 ID 断言 ：88-91）→ 开 relay → Awaitility 等 3 行 SENT、统计 3（:93-98）。
- 用例 2（:102-133）：关 sync 写 → 4 次跳转 → 断言 outbox 4 行且事件 ID 全为 UUID（:115-119，明细 0 行的确定性断言交给 drill 脚本，原因见 ：112-114 注释）→ 开 relay → 等统计 4、明细被消费端回填成 4（:123-130）、UV 收敛（:131-132）。

`outbox-drill.sh`（真实栈）的剧本是同一故事的运维版：关 sync 起独立实例（:44-51）→ 停 RabbitMQ（:63）→ M 次跳转全部 302（:66-69）→ 断言 PENDING=M（:70-71）、明细 0（:72-73）、attempts>=1（:74-80）、日志有 deferral（:81-82）→ 起 RabbitMQ（:86）→ 等聚合、明细、SENT、UV 全部收敛到 M（:88-98）。脚本 ：2-8 的注释一句话点题：证明「sync off + MQ down = clicks lost」的旧故事已死。

## 6. 技术原理补充

**Transactional Outbox（模式）。** 是什么：业务事务内写一张待发表，独立进程轮询发送。为什么需要：DB 与 MQ 是两个资源，跨二者的「分布式事务」要么不可用要么代价大；Outbox 把原子性收进 DB 一个事务。不用会怎样：见 5.1 的两种丢法。本项目怎么用：`lf_outbox` + `ClickOutboxRelay`。优缺点：可靠且实现短（本模块核心逻辑约 200 行），代价是轮询延迟（默认最多 2 秒 + 批 100）与对 DB 的持续轮询；CDC 类方案（Debezium 监听 binlog）能去掉轮询，但要引入额外组件，本项目规模不值得（推断）。

**指数退避与上限（通用手段）。** 本项目实现 `min(60, 2^n)`：起步快（1 秒，用户几乎无感）、封顶稳（60 秒，最多积 100 行×60 秒的吞吐压力）。上限的必要性：无上限的指数增长会让故障恢复后的第一行等几分钟才被重试；常量上限又让重试风暴可控。30 次上限（FAILED）则是给「必然失败的毒行」一个出口。

**DB 时钟调度（本项目规则）。** `next_attempt_at` 的写入（`DATE_ADD(NOW())`）与比较（`<= NOW()`）都在 MySQL 里，JVM 时区漂移不影响重试节奏。对照：`sent_at` 也是 `NOW()` 写的（`OutboxMapper.java:19-20`），所以清理比较也用 `DATE_SUB(NOW(), ...)`（:33-35）；webhook 的 `sent_at` 是 JVM 写的，清理就传 JVM cutoff。同一规则在两个 mapper 的注释里互相呼应（`OutboxMapper.java:29-32`、`WebhookDeliveryMapper.java:14-19`）。

**at-least-once 语义（通用概念）。** 本项目怎么达成：持久 outbox + 无限重试 + 确认式发布。代价：重复投递窗口（5.6 的两个来源）。为什么可接受：消费端有一条 64 字符主键的去重表，重复的成本是一次撞索引（10.3 课）。这是分布式系统「谁成本低谁兜底」的典型分派。

**保留期清理（运维手段）。** 只删终态、分批 LIMIT、循环上限，三个细节保证清理任务永远短平快，不会和 relay 抢锁抢出长事务。

## 7. 可视化

按顺序看两图一备用：

1. [../../diagrams/archify/html/15-outbox-lifecycle.html](../../diagrams/archify/html/15-outbox-lifecycle.html)：Outbox 行的完整生命周期，PENDING→SENT 与 PENDING→(retry)→FAILED 的每条边对应 5.5 的一个代码分支。
2. [../../diagrams/archify/html/08-scheduled-tasks.html](../../diagrams/archify/html/08-scheduled-tasks.html)：relay 与 SentRowCleanup 在共享调度池（size=6）里的位置，能看到它们与 webhook relay、reconciler 的隔离关系。
3. Mermaid 备用 [../../diagrams/mermaid/click-pipeline.md](../../diagrams/mermaid/click-pipeline.md)：HOT 段双写、RELAY 段抽水/退避/FAILED 三分支，与本课 5.2、5.5 逐行对应。

## 8. 项目中的真实执行过程

把最常见的一条链走完（时间都是配置默认值）：

1. **t=0，用户跳转。** `resolveForGet` 过完 12 步判定链（7.1 课）后调 `recordClick`（`ShortLinkService.java:574`）。同一事务内 `lf_click_log` 插 1 行（sync 开），`lf_outbox` 插 1 行（async 开），event_id=`log-{明细id}`、status=PENDING、attempts=0。事务提交，302 已在途中，MQ 一根汗毛没碰。
2. **t≤2 秒，relay 起身。** 调度线程每 2 秒跑一轮（:37-38），`selectPendingBatch(100)` 捞到这行，attempts=0<30，`readValue` 还原 `ClickEvent`，`publish` 走 10.1 课的五步，confirm ack 后 `markSent`：status=SENT，sent_at=NOW()。
3. **t+毫秒级，消费者聚合。** 监听容器收到消息进 `consume`（10.3 课），`lf_click_daily` 计数+1，`lf_consumed_event` 记一条。统计 API 从此可见这次点击（`MqEndToEndIT.java:26-43` 在真实 Rabbit 上验证了这条链，轮询窗口 15 秒吸收 relay 与消费延迟）。
4. **t+7 天，退场。** `SentRowCleanup` 把 SENT 且 sent_at 早于 7 天的行删掉（每小时一批）。
5. **异常剧本，broker 在 t=1 秒宕机。** `publish` 5 秒超时抛异常 → `markRetry`：attempts=1、next_attempt_at=+1 秒。此后每轮失败退避翻倍，1、2、4、8、16、32、60...秒；用户继续跳转，明细照写，outbox 行堆积但跳转永远 302（`outbox-drill.sh:66-73` 的断言原文）。broker 恢复后第一批到期行被发掉，几分钟内追平（脚本 ：85-98 验收）。
6. **毒行剧本，某行 payload 损坏。** 每轮 `readValue` 抛异常走退避，30 次后在某一轮被 ：48-54 标 FAILED。它不再消耗重试，躺在表里等 ：5.7 的手工 SQL；同一批其他行不受影响（异常被行级 catch 包住）。

## 9. 为什么这样设计

**热路径零 MQ 依赖。** README 的设计句：「热路径不直接触碰 RabbitMQ。跳转产生点击后在**同一 DB 事务**内写：① 同步明细...② Outbox...」（`README.md:410`）。302 是产品的生命线，任何 MQ 抖动都不许传染给它；Outbox 是能同时保住「不丢点击」与「不堵跳转」的最短实现。

**三个开关拼出部署矩阵。** sync/async/relay 三个开关（`application.yml:177-179`）组合出：全同步（审计用）、全异步、混合（默认）、纯 outbox 积压（drill 用）。每 tick 重读开关让演练不重启进程（:20-22 注释明说这是目的）。这是把「可测试性」当一等公民设计。

**FAILED 是出口不是失败。** 无限重试会让一行毒数据永久占用 relay 的注意力，还会把告警淹没在重复 warn 里。30 次上限 + FAILED 终态 + 手工重放 SQL（README:412）把「机器该干的」和「人该干的」切开。

**轮询而不是推送。** relay 用 `@Scheduled` 轮询而不是监听 DB binlog，换来零新组件。批 100/2 秒的组合给单实例 50 行/秒的保底吞吐，demo 量级绰绰有余（推断：吞吐是按配置参数算的纸面值，未压测）。

## 10. 本节总结

- 双写问题的两种丢法，Outbox 用「同事务插行」一并治掉；`recordClick` 的 `@Transactional`（:40）同时罩住明细行与 outbox 行。
- `eventId` 两种来源（:61-63）：sync 开是 `log-{id}`（对账同构），sync 关是稳定 UUID（存 payload）。
- `lf_outbox` 两个索引各有职责：唯一键防重复写，`(status,next_attempt_at,id)` 服务轮询扫描（V2__outbox.sql:14-15）。
- `relay` 单轮五动作：拉批、毒行判 FAILED、发布、退避、整轮兜底（:44-73）；退避 `min(60, 2^n)`（:75-78）；三个 meter 供 Grafana 与告警。
- at-least-once 是必然，消费端幂等是唯一兜底（README:412）；FAILED 行手工重放、SENT 行 7 天清理（`SentRowCleanup.java:64`）。
- 可靠性有两组证人：`OutboxDrillIT` 断言事务与收敛，`outbox-drill.sh` 在真实栈停 broker 验证全链。

## 11. 课后练习

**练习 1（概念理解）**：把 `recordClick` 的 outbox 插行挪到 `@Transactional` 方法外面（比如提交后再插），双写问题回来了吗？哪种丢法回来了，哪种还没回来？

答案解析：回来一半。明细写在事务内、outbox 行在事务外插：若事务回滚而 outbox 已插（插行成功但后续逻辑回滚，或提交前进程崩溃），消费端会为一条不存在的点击聚合出数字，幽灵点击回来了。反过来若事务提交、插行前崩溃，这次点击只有明细没有 outbox，丢消息也回来了。行内插行（:60-74）让两者同生共死，这正是 Transactional Outbox 的全部意义。

**练习 2（源码定位）**：第 7 次发布失败时（此前已 markRetry 6 次），`markRetry` 收到的 `backoffSeconds` 是多少？`next_attempt_at` 大约是几点几分？答出行号与公式。

答案解析：第 7 次失败时拉到的行 `attempts=6`，relay 传 `backoffSeconds(6)`（`ClickOutboxRelay.java:62`）。公式 `min(60, 1<<min(6,6))` = min(60, 64) = 60（:75-78，常量在 ：29）。`markRetry` 写 `DATE_ADD(NOW(), INTERVAL 60 SECOND)`（`OutboxMapper.java:25-27`）。所以序列是 1、2、4、8、16、32、60、60...，从第 7 次起封顶。

**练习 3（执行过程）**：`outbox-drill.sh` 停 RabbitMQ 期间，为什么 10 次跳转全部 302 而明细行为 0？把两个断言行号和对应代码分支写出来。

答案解析：302 的原因是 `recordClick` 根本不碰 RabbitMQ，写库成功事务提交后跳转照常（`ShortLinkService.java:574-582` 的调用与 meter）。明细为 0 是因为 drill 用 `LINKFORGE_SYNC_CLICK_ENABLED=false` 起实例（`outbox-drill.sh:45`），`recordClick` 跳过 ：56-58 的 sync 分支，只走 ：60-74 的 outbox 分支。脚本断言：跳转码 :66-69、`PENDING=M` :70-71、明细 0 :72-73。OutboxDrillIT 的对应用例是 `syncOffBrokerDownStill302AndConvergesWithDetailAndUv`（`OutboxDrillIT.java:102-133`，IT 不断言明细 0 的原因在其 ：112-114 注释）。

**练习 4（设计分析）**：给 `markSent` 补上 `AND status='PENDING'` 是更严谨还是更画蛇添足？对照 webhook 版（`WebhookDeliveryMapper.java:28-29`）说出两边差异的根源。

答案解析：outbox 版加守卫是无害但多余的：relay 的拉取-发布-标记是同一轮内的同步序列，多实例重复 markSent 也只是把 SENT 写成 SENT，守卫不改变任何可见行为。webhook 版守卫是必需的：HTTP 投递最长 connect 2s+request 5s（`application.yml:166-167`），跨过 2 秒 tick，同一 PENDING 行可能同时有两份在途尝试，守卫保证只有先完成的那次结算生效。根源差异是**在途时长与 tick 周期的比值**：outbox 的 confirm 等待封顶 5 秒且单线程循环内完成，webhook 的在途窗口天然跨 tick（推断：代码注释未明说，是从配置数值推出的）。

**练习 5（调用链）**：删除 `relay()` 最外层的 catch（:70-72）会发生什么？提示：分「任务会不会停」与「日志会变成什么样」两层答。

答案解析：任务不会停。Spring 的调度器捕获任务抛出的异常并记录后继续下次调度（框架行为）。变化在日志与可观测：原来是一行 `outbox relay pass failed: ...` 的 warn（:71），现在会带完整异常栈刷 ERROR 级别；Grafana 上没有对应 meter 的变化（本就没有整轮失败计数），但日志告警规则可能被噪声触发。这题的要点是「吞异常的 catch 不是为了防崩，是为了把失败降级成一句可控的 warn」。

## 12. 与后续课程的联系

- 10.3 课进入消费端：at-least-once 留下的重复投递由 `lf_consumed_event` 主键吸收，`consume` 的手工短事务把去重、回填、聚合成一个原子单元。
- 11.1 课把 `recordClick` 放回跳转全景：fail-open catch 为什么必须在事务外、B2 过滤在双写之前。
- 11.3 课讲 `ClickLogReconciler`：outbox 之外的另一条安全网，与 relay 是互补不是冗余。
- 13.1 课讲 webhook：投递行在 `consume` 事务内生成，outbox 链是它的上游地基；webhook 自己的 relay/退避/时钟规则是本课模式的复刻与对照。
- 14.3 课的调度地图把 relay、cleanup、reconciler 放进同一个 6 线程池里看隔离；15.3 课把 `outbox-drill.sh` 与另外三个 drill 并排讲。
