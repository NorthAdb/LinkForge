# 10.3 消费幂等与死信：lf_consumed_event、手工短事务与 DLQ

> 本课回答一个问题：10.2 课留下的 at-least-once 重复投递，消费端凭什么保证一次点击只计一次数？同一次点击的三种到达路径（MQ 实时、relay 重发、Reconciler 重放）是怎么收敛成一次的？看完本课，幂等三要素「少一个会怎样」你应该能自己推出来。

## 1. 本节学习目标

学完本课，你应该能：

1. 背出消费幂等三要素：去重表主键 INSERT、确定性 eventId、回填行 event_id 标记，并分别推演出「删掉它」后的双重计数路径。
2. 逐行解释 `ClickStatsService.consume` 的 47 行：手工 `TransactionTemplate` 短事务、DuplicateKey 早退、UUID 事件回填、日表与维度累加、webhook 钩子。
3. 说清为什么去重插入和聚合必须在**同一个**短事务里（防中间崩溃）。
4. 描述异常消息进 DLQ 的完整路径（容器配置→basic.reject→死信参数→click.dlq），以及「毒消息」在本项目的处置方式。
5. 说出 `ClickLogReconciler` 的门禁条件、扫描 SQL 与重放构造，解释它和幂等体系互为前提。

## 2. 前置知识

- **at-least-once 的含义**（10.2 课）：投递可能重复。重复的三个来源：MQ 自身重投、relay 在 markSent 前崩溃重发、多实例 relay 并发拉批。消费端必须幂等，这是本课存在的理由。
- **主键即锁**（模块 4 知识）：`INSERT` 一个已存在的主键值触发唯一约束冲突，MySQL 报 duplicate key 并**只回滚这一条语句**，事务里之前和之后的语句照常。这个 SQL 语义是「先占坑再干活」模式的地基。
- **DuplicateKeyException**（框架行为）：Spring 的数据访问层把 MySQL 的 duplicate key 错误（错误码 1062）翻译成 `org.springframework.dao.DuplicateKeyException`，它是 `DataAccessException` 的子类，catch 它不会连带 catch 住其他 DB 故障。
- **TransactionTemplate**（框架行为，4.3 课见过 `@Transactional`）：编程式事务，`execute(callback)` 的回调体就是事务边界。与注解式的差别是边界由代码显式划出，不依赖代理。
- **basic.reject 与 requeue**（10.1 课）：容器捕获消费者异常后发 `basic.reject`；`requeue=false` 时消息按队列的死信参数改道。本项目把默认 requeue 关掉（`application.yml:81`），异常消息一律进 DLQ。
- **重定向热路径**（7.1 课）：`resolveForGet` 过完判定链后调 `recordClick`，本课的消费是那条链的下游终点。

## 3. 本节涉及的源码

| 文件 | 行数 | 本课关注点 | 阅读顺序 |
|----|----|----|----|
| `src/main/java/com/linkforge/stats/mq/ClickEventConsumer.java` | 28 | 监听入口全文 :10-27 | 1 |
| `src/main/resources/db/migration/V1__baseline_schema.sql` | - | lf_consumed_event DDL :70-73 | 2 |
| `src/main/java/com/linkforge/stats/entity/ConsumedEvent.java` | 17 | 主键映射 :14-15 | 3 |
| `src/main/java/com/linkforge/stats/mapper/ConsumedEventMapper.java` | 9 | 空壳 BaseMapper :8 | 4 |
| `src/main/java/com/linkforge/stats/service/ClickStatsService.java` | 154 | 构造器 :42-54、consume :56-113 | 5 |
| `src/main/java/com/linkforge/stats/service/ClickLogReconciler.java` | 55 | 门禁 :33-35、重放 :37-47 | 6 |
| `src/main/java/com/linkforge/stats/entity/ClickLog.java` | 28 | event_id 注释 :26-27 | 7 |
| `src/main/java/com/linkforge/stats/mapper/ClickLogMapper.java` | 32 | selectUnconsumed :14-18 | 8 |
| `src/test/java/com/linkforge/stats/ClickIdempotencyIT.java` | 120 | 两个用例 :71-114 | 9 |

阅读顺序的理由：先看消息从 broker 进来的门（consumer），再看幂等的那张表（DDL+实体+mapper），然后进本课主菜 `consume`，再看对账安全网怎么复用它，最后用测试收口。`ClickStatsService` 还有 40 行查询方法（stats/breakdown/clicks），属于 11.3 课，本课只碰消费半边。

## 4. 类的定位

- `ClickEventConsumer` 是全项目**唯一**的 `@RabbitListener`，28 行薄壳：解析消息、调 `consume`、打 debug 日志，没有任何业务逻辑。它的 javadoc 是消费语义的权威表述（:10-14）。
- `ClickStatsService` 双职责：消费（`consume`，被 consumer 和 reconciler 两个入口调）+ 查询（stats/breakdown/clicks，被 `StatsController` 调）。本课讲消费，查询在 11.3。
- `ConsumedEvent` 是全项目最小的实体（两列）+ `ConsumedEventMapper` 是空壳 `BaseMapper`（无任何自定义 SQL，:8），insert/delete 走 MyBatis-Plus 通用方法。Mapper 的存在靠启动类 `@MapperScan` 扫到 `stats.mapper` 包（`LinkForgeApplication.java:10`）。
- `ClickLogReconciler` 是安全网：扫描「同步写了但从未被消费」的明细行重放。它与 relay 是互补关系（javadoc :15-16 明说 "live delivery belongs to ClickOutboxRelay; this loop is skipped entirely while the sync write is off"）。
- 表关系见 [../../diagrams/archify/html/05-database-erd.html](../../diagrams/archify/html/05-database-erd.html)：`lf_consumed_event` 独立无外键，`lf_click_log.event_id` 对它是逻辑引用（ERD 图备注了这一点）。

## 5. 核心源码详解

### 5.1 ClickEventConsumer：一扇薄门

```java
/**
 * Real consumer. A clean return acks; an exception rejects without requeue and the
 * message lands in the dead-letter queue for inspection. Duplicate eventIds are acked
 * without counting again.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClickEventConsumer {

    private final ClickStatsService clickStatsService;

    @RabbitListener(queues = RabbitMqConfig.CLICK_QUEUE)
    public void onMessage(ClickEvent event) {
        boolean counted = clickStatsService.consume(event);
        log.debug("click event consumed code={} eventId={} counted={}",
                event.getCode(), event.getEventId(), counted);
    }
}
```
`ClickEventConsumer.java:10-27`

javadoc 三句话就是消费端契约：干净返回=ack；异常=拒绝不重回队列进 DLQ；重复 eventId=ack 但不再计数。第三句的实现细节全在 `consume` 里，薄门自己只透传一个 boolean。

`@RabbitListener(queues = ...)` 引用 10.1 课的队列常量（:22）。方法参数直接声明为 `ClickEvent` 类型，容器用 10.1 课配的 `Jackson2JsonMessageConverter` 把消息体反序列化成对象（框架行为：`__TypeId__` 消息头指明目标类）。若 JSON 坏了或类不匹配，异常发生在进入方法**之前**，效果等同 `onMessage` 抛异常，一样进 DLQ。

### 5.2 lf_consumed_event：主键即幂等锁

```sql
CREATE TABLE lf_consumed_event (
    event_id      VARCHAR(64) PRIMARY KEY,
    consumed_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```
`V1__baseline_schema.sql:70-73`

全项目最简单的表：主键就是去重键，没有第二个索引、没有外键、没有状态列。V1 就建了它，说明「消费幂等」从 MVP 第一天就是设计前提，不是补丁。

```java
    @TableId(value = "event_id", type = IdType.INPUT)
    private String eventId;
    private LocalDateTime consumedAt;
```
`ConsumedEvent.java:14-16`

`IdType.INPUT` 值得一停（MyBatis-Plus 语言点）：主键值由代码**手动提供**（`consume` 里 `row.setEventId(event.getEventId())`），数据库不自增、框架不生成。对比 `Outbox` 实体的 `IdType.AUTO`（`Outbox.java:14-15`）。`consumed_at` 不设值时走列默认值 `CURRENT_TIMESTAMP`。

### 5.3 consume：手工短事务逐行

先看构造器里的事务模板：

```java
    public ClickStatsService(ConsumedEventMapper consumedEventMapper,
                             ClickDailyMapper clickDailyMapper,
                             ClickDimDailyMapper clickDimDailyMapper,
                             ClickLogMapper clickLogMapper,
                             com.linkforge.webhook.service.WebhookService webhookService,
                             PlatformTransactionManager transactionManager) {
        this.consumedEventMapper = consumedEventMapper;
        this.clickDailyMapper = clickDailyMapper;
        this.clickDimDailyMapper = clickDimDailyMapper;
        this.clickLogMapper = clickLogMapper;
        this.webhookService = webhookService;
        this.dedupTx = new TransactionTemplate(transactionManager);
    }
```
`ClickStatsService.java:42-54`

这个类**没用** `@Transactional`，而是注入 `PlatformTransactionManager` 自己 new 了一个 `TransactionTemplate`（:53）。为什么手工（javadoc :57-60 给了主线，补完整推演）：回调体内部要捕获 `DuplicateKeyException` 然后正常返回 false。手工模板下，捕获发生在回调内部，异常没有越过任何事务边界，事务干净提交（里面已经没有未提交的写）。若用 `@Transactional` 标注方法、在方法体内 catch，行为也能对，但事务边界就固定成「整个 consume 方法」，而手工模板把边界收在 lambda 里，强调「去重+聚合是一个不可再分的短单元」；另外 reconciler 逐行循环调用 `consume`，手工模板让每行各自成短事务，长事务风险更小（推断：边界选择的附加理由，代码注释只写了 MySQL 语句级回滚）。

短事务的「短」是刻意的：事务里只有一次去重 INSERT、最多一次回填 INSERT、两三个 upsert、一次 webhook 入队，全在几毫秒量级。它是每个消息独享的，不与任何 HTTP 事务重叠。

```java
    public boolean consume(ClickEvent event) {
        LocalDate date = event.getClickedAt() != null
                ? event.getClickedAt().toLocalDate()
                : LocalDate.now();
        return Boolean.TRUE.equals(dedupTx.execute(status -> {
            try {
                ConsumedEvent row = new ConsumedEvent();
                row.setEventId(event.getEventId());
                row.setConsumedAt(LocalDateTime.now());
                consumedEventMapper.insert(row);
            } catch (DuplicateKeyException ex) {
                return false;
            }
```
`ClickStatsService.java:66-78`

三段结构的第一段：**占坑**。:67-69 先定聚合日期，`clickedAt` 兜底为今天（防毒消息 NPE）。:70 进事务。:72-75 向 `lf_consumed_event` 插主键。插成功=这个 eventId 归我处理；撞主键=别人处理过，`return false` 干净早退（:76-78）。MySQL 对 duplicate key 只回滚失败语句（javadoc :59-60 原话："A duplicate key inside the callback is a plain skip: MySQL rolls back only the failed statement"），此时事务里还没有其他写，事务以空操作提交。

注意返回值语义：false 不是失败，是「重复投递已确认」，consumer 会打 debug 日志后正常 ack（10.1 课练习 5 的路径）。真失败（比如 DB 断了）抛异常，走 DLQ 路径。

```java
            if (!event.getEventId().startsWith("log-")) {
                ClickLog detail = new ClickLog();
                detail.setTenantId(event.getTenantId());
                detail.setLinkId(event.getLinkId());
                detail.setCode(event.getCode());
                detail.setIp(event.getIp());
                detail.setUserAgent(event.getUserAgent());
                detail.setReferer(event.getReferer());
                detail.setClickedAt(event.getClickedAt() != null ? event.getClickedAt() : LocalDateTime.now());
                detail.setVariant(event.getVariant());
                // marker: this row is already counted under the UUID eventId, so the
                // reconciler (which replays implicit log-{id} rows) must skip it
                detail.setEventId(event.getEventId());
                clickLogMapper.insert(detail);
            }
```
`ClickStatsService.java:79-93`

第二段：**回填**。条件 `!startsWith("log-")` 把事件分成两族：`log-{id}` 事件的明细行在热路径已经写好（10.2 课的 sync 分支），消费端只管聚合；UUID 事件（sync 关闭模式）没有明细行，消费端按事件字段重建一行（:80-88）。重建行的 `clickedAt` 兜底逻辑与 ：67-69 一致。

:91 是幂等三要素的第三要素：**回填行打上自己的 eventId**。没有这行标记，对账重放会把这条已计数的明细再算一遍（5.4 演推）。实体侧注释互证：`ClickLog.java:26-27` 写着 "NULL = hot-path sync row (implicit "log-{id}" eventId); set on consumer backfill"，V2 迁移的注释（`V2__outbox.sql:18-21`）同样强调 "already counted, so ClickLogReconciler must skip it"。

```java
            clickDailyMapper.incrementClick(event.getTenantId(), event.getLinkId(), event.getCode(), date);
            // A8: dimension buckets share the dedup transaction, so one eventId can
            // never double count a bucket; parsing never throws (see ClickDimensions)
            for (ClickDimensions.Dimension dim : ClickDimensions.of(event.getUserAgent(), event.getReferer())) {
                clickDimDailyMapper.incrementClick(event.getTenantId(), event.getLinkId(),
                        date, dim.type(), dim.value());
            }
            // C3 (D-C3-11): the drawn A/B bucket is its own dimension; no variant, no row.
            if (event.getVariant() != null) {
                clickDimDailyMapper.incrementClick(event.getTenantId(), event.getLinkId(),
                        date, ClickDimensions.TYPE_VARIANT, event.getVariant());
            }
```
`ClickStatsService.java:94-105`

第三段：**聚合**。`incrementClick` 是 `INSERT ... ON DUPLICATE KEY UPDATE clicks = clicks + 1`（`ClickDailyMapper.java:16-18`），桶不存在就建、存在就加，SQL 级原子。维度循环从 UA/Referer 解析出 device/os/browser/referer_host 桶（`ClickDimensions` 是纯函数，绝不抛异常，11.2 课逐桶讲），variant 桶只有 A/B 链接才有（:102-105）。注释 ：95-96 点明：维度桶共享去重事务，一个 eventId 不可能给同一个桶加两次。

```java
            // B-wave2 B5: a click that won the dedup insert leaves one PENDING webhook
            // delivery in the SAME transaction — replayed events early-return above,
            // so one eventId can never enqueue twice (D-Bw2-9). A non-duplicate failure
            // propagates up and rolls the whole consume transaction into the DLQ (S1).
            webhookService.recordClickDelivery(event);
            return true;
        }));
    }
```
`ClickStatsService.java:106-112`

第四段：**webhook 钩子**。注释三句话是本段全部逻辑：赢了去重插入的点击才会在同一事务里落一条 PENDING webhook 投递行；重放事件在上面的 ：76-78 早退，永远走不到这里，所以一个 eventId 不可能入队两次；入队抛出的非重复异常向上传播，整个 consume 事务回滚，消息进 DLQ。webhook 侧做了什么（签名、SSRF 防护、独立 relay）是模块 13 的内容，本课只需记住它在事务内这一点。

### 5.4 幂等三要素：少一个会怎样

同一次点击有三条到达路径：MQ 实时投递（relay 首发成功）、relay 重发（at-least-once）、Reconciler 重放（安全网）。三要素各堵一条双重计数的路：

**要素一：去重表主键 INSERT（:72-75）。** 堵 MQ 层的重复。删掉它：relay 在 publish 成功后、markSent 前崩溃（10.2 课 5.6 的窗口），行还是 PENDING，重启后重发；没有去重表，第二次消费把 `lf_click_daily` 又加 1。多实例 relay 并发拉批同理。测试 `ClickIdempotencyIT.sameEventIdCountsOnceAcrossReplays`（:71-87）直接在服务层连喂三次同一事件，断言只计一次（:86 `assertEquals(1, ...sumClicks)`）。

**要素二：确定性 eventId `log-{id}`（10.2 课 `ClickRecordService.java:61-63`，契约注释 `ClickEvent.java:10-12`）。** 堵「实时消费 vs 对账重放」的重复。删掉它（sync 开着时改成随机 UUID）：UUID 虽然也存在 outbox 里不变（MQ 重发仍可去重），但 Reconciler 的扫描是按 `CONCAT('log-', l.id)` 找去重表（`ClickLogMapper.java:14-18`），找不到这条 UUID 的去重行，于是把同步明细行当成「从未消费」重放，二次计数。确定性让**两个独立系统对同一次点击算出同一个键**，去重表才能对上账。

**要素三：回填行 event_id 标记（:91）。** 堵「sync 关闭模式下消费回填 vs 对账重放」的重复。删掉它（回填行 event_id 留 NULL）：Reconciler 扫描条件是 `c.event_id IS NULL AND l.event_id IS NULL`（`ClickLogMapper.java:16`），回填行的去重键是 UUID 而扫描探针是 `log-{id}`，第一条件为真；回填行 event_id 没打，第二条件也为真，这条行被当作未消费重放成 `log-{id}` 事件，二次计数。打了标记，第二条件即假，扫描直接跳过（Reconciler javadoc :18-19 原话："Detail rows the consumer backfilled carry their UUID eventId and are excluded by the scan"）。

三要素的分工记成一句话：**要素一管「MQ 层重复」，要素二管「实时与对账的键对齐」，要素三管「回填行别被对账错认」。** 三条到达路径两两之间都有可能撞车，少任何一个都会在某条路径组合下计两次。

### 5.5 异常路径与 DLQ：毒消息的处置

消费抛异常的完整链条（10.1 课已看过拓扑，这里走事件）：

1. `consume` 或更早的转换抛异常（如 `recordClickDelivery` 非重复失败、DB 断连、坏 JSON）。
2. 容器捕获，按 `default-requeue-rejected: false`（`application.yml:81`）发 `basic.reject(requeue=false)`。
3. broker 按 `clickQueue` 的死信参数（`RabbitMqConfig.java:31-34`）投给 `linkforge.click.dlx`，路由键 `click.dead`，落 `linkforge.click.dlq`（:47-50）。

**毒消息（poison message）**：必然失败的消息。典型来源：payload JSON 损坏、`ClickEvent` 加字段后旧消息反序列化失败、某行数据触发的必然 NPE。如果没有 `requeue-rejected=false`，容器会 requeue，毒消息回到队头立刻再消费再抛，无限热循环，队列被一颗老鼠屎堵死。进 DLQ 后它离开主链，人从 DLQ 检查、修 bug、再手工把消息搬回主队列（RabbitMQ management 插件或 shovel）。

本项目消费端**没有**重试计数器：消息要么成功 ack，要么一次失败就进 DLQ。对照同仓库的 webhook 投递（attempts 上限 8、退避重试，13.2 课），差异的根源是失败性质：webhook 的失败多是远端抖动（值得退避重试），消费失败多是代码或数据问题（重试无意义，快速隔离进 DLQ 换人工）更有利（推断：代码未注释此取舍，是从失败模式分布推的）。

`lf_consumed_event` 没有清理任务：`SentRowCleanup` 只删 outbox 与 webhook 投递的 SENT 行（`SentRowCleanup.java:30-31`、:64-65），去重表永久增长。这是「永远去重」换来的存储成本：每点击一行约 64 字节主键+时间戳（推断：按列定义估的行宽，未实测）。若未来要清理，必须保留一个窗口大于任何可能的重复投递间隔，且清理与对账窗口要一起设计。

### 5.6 ClickLogReconciler：复用 consume 的安全网

```java
    @Scheduled(fixedDelayString = "${linkforge.mq.reconcile-fixed-delay-ms:30000}",
            initialDelayString = "${linkforge.mq.reconcile-initial-delay-ms:15000}")
    public void reconcile() {
        if (!properties.getMq().isAsyncClickEnabled() || !properties.getMq().isSyncClickEnabled()) {
            return;
        }
        try {
            List<ClickLog> pending = clickLogMapper.selectUnconsumed(500);
            for (ClickLog row : pending) {
                try {
                    clickStatsService.consume(new ClickEvent(
                            "log-" + row.getId(), row.getTenantId(), row.getLinkId(), row.getCode(),
                            null, row.getIp(), row.getUserAgent(), row.getReferer(), row.getClickedAt(),
                            row.getVariant()));
                } catch (Exception ex) {
                    log.warn("reconcile failed for click log {}: {}", row.getId(), ex.getMessage());
                }
            }
```
`ClickLogReconciler.java:30-47`

四个设计点。**门禁**（:33-35）：`async && sync` 都开才跑——这个循环存在的意义是兜「MQ 链路失败导致同步明细没人聚合」的场景；sync 关着时没有明细行可扫，async 关着时根本不该有这功能。**扫描**（:37，SQL 在 `ClickLogMapper.java:14-18`）：`LEFT JOIN lf_consumed_event ON c.event_id = CONCAT('log-', l.id) WHERE c.event_id IS NULL AND l.event_id IS NULL`，一次 500 行，正是 5.4 三要素里两个「NULL」条件的出处。**重放构造**（:40-43）：手工 new `ClickEvent`，eventId 就是 `log-{id}`，与实时链路同一键。**行级隔离**（:44-46）：单行失败 warn 后继续下一行，不让一行毒数据废掉整轮。它已经计过的行（MQ 正常时）会在 ：76-78 撞主键早退，一行不漏也一行不多。

## 6. 技术原理补充

**幂等消费模式（通用模式）。** 是什么：为每条消息定义唯一键，处理前先占键，占到的才处理。为什么需要：at-least-once 下重复不可避免。不用会怎样：统计翻倍、webhook 重复推送。本项目怎么用：`lf_consumed_event` 主键 + `DuplicateKeyException` 早退。优缺点：DB 主键是进程崩溃、多实例、乱序都打不穿的防线（比内存去重、Redis SETNX 强在「与业务数据同一个存储、同一个事务」）；成本是每次消费多一条 INSERT。

**编程式短事务（框架行为）。** `TransactionTemplate.execute` 把 lambda 包进事务，异常抛出即回滚，正常返回即提交。本项目怎么用：`dedupTx`（:53、:70）。为什么短：事务内是毫秒级几个写，锁持有时间极短，不阻塞统计查询；每条消息独立事务，崩溃影响面单条。

**语句级回滚（MySQL 语义）。** duplicate key 只回滚出错语句，事务可继续（javadoc :59-60 的依据）。对比 PostgreSQL：同场景整个事务进入 aborted 状态，必须回滚重来。这个差异决定了「catch 后早退」在 MySQL 上是干净的 plain skip，在 PG 上需要 savepoint 或两段式（先查后插）。（框架/DB 行为，通用知识）

**ON DUPLICATE KEY UPDATE（SQL 行为）。** `incrementClick` 的 upsert（`ClickDailyMapper.java:15-17`）：桶行不存在则 INSERT 初始 1，存在则 clicks+1。唯一键 `(link_id, stat_date)`（`uk_daily_link_date`，`V1__baseline_schema.sql:66`）是「幂等落桶承重墙」。与去重表配合的妙处：即使聚合本身重复执行（理论上不会，因为同事务），唯一键也把同一桶的重复 INSERT 合并成加法。细节在 11.2 课。

**死信队列模式（EIP，框架行为）。** 是什么：把失败消息路由到专用队列隔离处理。为什么需要：毒消息不能堵主链，也不能无声消失。不用会怎样：见 5.5 的热循环。本项目怎么用：队列参数声明 DLX（`RabbitMqConfig.java:31-34`）+ 容器 requeue-rejected=false（`application.yml:81`）。DLQ 里的消息没有自动重试，人工是唯一出口。

## 7. 可视化

按顺序看两图一备用：

1. Mermaid 备用 [../../diagrams/mermaid/click-pipeline.md](../../diagrams/mermaid/click-pipeline.md)：CONSUME 段的分支图，`lf_consumed_event INSERT 去重` 菱形的「重复→ACK 丢弃」「首次→回填→聚合」两条边就是 5.3 的 ：76-78 与 ：79-110。
2. [../../diagrams/archify/html/07-rabbitmq-topology.html](../../diagrams/archify/html/07-rabbitmq-topology.html)：异常路径的几何：`click.queue` → `click.dlx` → `click.dlq` 的改道边。
3. [../../diagrams/archify/html/05-database-erd.html](../../diagrams/archify/html/05-database-erd.html)：`lf_consumed_event` 与 `lf_click_log.event_id` 的逻辑引用关系（无物理外键，靠命名约定连接）。

## 8. 项目中的真实执行过程

**剧本一：同一条消息被投两次（relay 在 markSent 前崩溃过）。**

1. 第一次投递：`onMessage` 拿到 `ClickEvent`，`consume` 进事务，:75 去重 INSERT 成功，聚合 +1，webhook 入队，:111 返回 true。容器 ack，消息删除。
2. 第二次投递（同一 eventId）：:75 撞 `PRIMARY KEY`，:76-78 捕获返回 false。事务以空操作提交，`onMessage` 打 debug 日志 `counted=false` 后干净返回，容器 ack。`lf_click_daily` 纹丝没动。
3. `ClickIdempotencyIT.java:83-86` 把这三步压成三个断言：第一次 true、两次重放 false、聚合恒为 1。

**剧本二：sync 关闭模式的 UUID 事件。**

1. 热路径只写 outbox 行（10.2 剧本），eventId 是稳定 UUID。
2. relay 发布，`consume` 占住 UUID 键（:75），:79 条件成立，回填明细行并打上 `detail.setEventId(uuid)`（:91），聚合照常。
3. 30 秒后 Reconciler 醒来：扫描条件两个都是 NULL 才重放；这条回填行 `event_id` 非 NULL，被 ：16 的条件排除；它的明细与聚合一次不重、一次不少（`OutboxDrillIT.java:128-130` 断言回填收敛到 4 行，:132 断言 UV 收敛）。

**剧本三：webhook 入队失败，消息进 DLQ。**

1. `consume` 走到 ：110，`recordClickDelivery` 因非重复原因抛异常（比如 DB 连接池耗尽）。
2. 异常穿过 lambda 边界，`dedupTx` 回滚：去重行、回填行、日表 +1、维度桶、webhook 投递行全部消失，数据库回到消费前状态。
3. 异常继续穿出 `onMessage`，容器 reject(requeue=false)，broker 死信改道，消息躺进 `linkforge.click.dlq`。
4. DB 恢复后，这条消息不会自动重活：它已在 DLQ。人工从 DLQ 取出重投主队列（或修 bug 后重发），`consume` 这次从占坑开始完整走一遍。去重行也回滚了，所以重投能正常计数，不丢也不重。

## 9. 为什么这样设计

**幂等放在消费端而不是发布端。** 发布端无论怎么努力（confirm、事务、分布式锁）都消不掉「发布成功、确认丢失」这个窗口；重复是 at-least-once 的固有属性。唯一成本可控的位置是消费端一次主键 INSERT。README 口径：「多实例 at-least-once，消费端 eventId 幂等兜底（`lf_consumed_event` 唯一索引 + 按天累加 `lf_click_daily`）」（`README.md:412`）。

**确定性 eventId 是跨系统对账的键。** 类注释（`ClickEvent.java:10-12`）把理由写在契约上：实时消费与对账重放对同一次点击必须算出同一个键。这个键从热路径的自增 id 生成（10.2 课 ：61-63），穿过 outbox payload、MQ 消息、去重表、回填标记四站不变。设计上是「把幂等键的生成时机尽量提前、来源尽量原始」。

**去重与聚合必须同事务。** javadoc（:57-59）原话："Dedup insert, detail backfill and daily increment share one short transaction, so a crash between them can neither double count nor silently drop a click"。拆开事务的两种死法：去重提交了、聚合没提交（崩溃）→ 这条点击永远被去重表挡住，静默丢失；聚合提交了、去重没提交 → 重复投递再次完整聚合，双重计数。同事务让崩溃回滚到「还没消费」状态，DLQ 重投后重来。

**webhook 入队搭幂等便车。** 注释（:106-109）引用审计项 D-Bw2-9：重放事件在 ：76-78 早退，天然不会二次入队；webhook 若独立判断「投过没有」就得再建一张去重表。复用 `consume` 的事务边界，webhook 的去重是免费的。

**毒消息快速进 DLQ 而不是原地重试。** 一次性失败即隔离（`application.yml:81`），换来主链吞吐稳定与失败现场保存。同仓库 webhook 投递选择退避重试（attempts 上限 8），因为远端 HTTP 失败多为瞬时。两类失败性质、两套处置策略，同一套 DLX 机制（推断：取舍理由为推断，机制本身有代码与配置实证）。

## 10. 本节总结

- 消费幂等三要素：去重表主键 INSERT（:72-75，堵 MQ 重复）、确定性 `log-{id}`（:61-63 + `ClickEvent.java:10-12`，堵实时与对账撞车）、回填行 event_id 标记（:91，堵 sync-off 模式重放）。三要素缺一会怎样见 5.4 的推演。
- `consume` 是手工 `TransactionTemplate` 短事务（:53、:70）：占坑、回填、聚合、webhook 入队四段一体，崩溃回滚到未消费态，DLQ 重投后可完整重来。
- 重复事件走 ：76-78 早退返回 false，consumer ack 不计数；这是对 at-least-once 的正面消化，不是异常。
- 异常消费的路径：容器 reject(requeue=false)（`application.yml:81`）→ 队列死信参数（`RabbitMqConfig.java:31-34`）→ `linkforge.click.dlq`。毒消息一次失败即隔离，人工是唯一出口。
- `ClickLogReconciler`（30 秒一轮，async&&sync 才跑，:30-35）复用 `consume`，扫描 SQL 的两个 NULL 条件正是三要素的反面（`ClickLogMapper.java:14-18`）。

## 11. 课后练习

**练习 1（设计分析）**：三要素各删一个，分别找出一条具体的双重计数路径（谁、在哪一行、怎么算两次）。

答案解析：删要素一（去重 INSERT）：relay publish 成功后 markSent 前崩溃，行留 PENDING 重发，两次都完整聚合，`lf_click_daily` +2。删要素二（eventId 改随机）：实时消费占了 UUID 键，Reconciler 按 `CONCAT('log-', l.id)`（`ClickLogMapper.java:15`）找不到去重行，同步明细行被重放成 `log-{id}` 事件二次聚合。删要素三（回填不打标记）：sync 关闭时 UUID 事件回填的明细行 `event_id` 为 NULL，Reconciler 的 `l.event_id IS NULL` 条件放行，探针 `log-{id}` 查不到 UUID 去重行，重放二次计数。三条路径互不重叠，正因如此三个要素一个都不能省。

**练习 2（概念理解）**：`DuplicateKeyException` 被 catch 后，这个事务里**已经执行过的写**会怎样？如果同样的代码跑在 PostgreSQL 上呢？

答案解析：MySQL 对 duplicate key 只回滚失败语句（`ClickStatsService.java:59-60` 注释的依据），此前没有其他写（去重 INSERT 是事务第一个语句），事务正常提交空操作。PostgreSQL 上唯一约束冲突会让整个事务进入 aborted 状态，后续语句全部报错，`return false` 也提交不了，必须用 savepoint 或改为「先 SELECT 后 INSERT」的两段式。这解释了为什么这套写法是 MySQL 项目的惯用法（DB 语义差异，通用知识）。

**练习 3（执行过程）**：`consume` 在 webhook 入队时抛异常，此时 `lf_click_daily` 已经加过 1。这条点击最终会被计几次？追着事务边界说。

答案解析：一次（在人工重投之后）。异常穿出 lambda，`dedupTx.execute` 回滚整个事务（:70 的边界）：去重行、日表 +1、维度桶、投递行全部消失。消息进 DLQ。人工重投后 `consume` 从占坑重新开始（去重行已回滚，能重新插入），这次成功则计一次。若去重与聚合不同事务，日表 +1 已提交而去重行没提交，重投就会计两次。

**练习 4（源码定位）**：Reconciler 为什么把 `userId` 硬编码为 null（`ClickLogReconciler.java:41`）？这个字段在实时链路里是谁填的（10.2 课哪一行）？

答案解析：明细行 `lf_click_log` 没有 user_id 列（表结构见 4.1 课），重放时无从恢复；`ClickEvent.userId` 只影响事件字段完整性，聚合与维度都不用它。实时链路里它取自登录态：`ClickRecordService.java:64` 的 `UserContext.get() == null ? null : UserContext.get().getUserId()`，匿名跳转本来就是 null。所以重放时置 null 不是丢信息，是与「匿名点击」的实时事件天然一致。

**练习 5（调用链）**：`ClickIdempotencyIT` 与 `MqEndToEndIT` 各自证明什么、不证明什么？说清两条测试的分界线。

答案解析：`ClickIdempotencyIT`（:71-114）直接调 `clickStatsService.consume`，证明服务层幂等语义（重放不计数、不同事件按天分桶），不经过 MQ，所以不证明投递链路；它还亲手清理合成数据（:51-69）。`MqEndToEndIT`（:26-43）走真实链路：302 跳转 → outbox → relay → 真实 RabbitMQ → consumer → stats API 读回 3 与 5，轮询窗口 15 秒（:45-56）吸收异步延迟；它不证明精确的单事件幂等（没有喂重复消息）。分界线：服务层语义归 ClickIdempotencyIT，端到端链路归 MqEndToEndIT，可靠性极限（broker 停机）归 `outbox-drill.sh`。

## 12. 与后续课程的联系

- 11.1 课补全写侧全景：`recordClick` 的 fail-open、B2 过滤与双写的关系，与本课消费侧合成完整的点击管道。
- 11.2 课拆 `incrementClick` 与 `ClickDimensions` 的分桶规则（本课 :94-105 只看了骨架）。
- 11.3 课讲 `ClickLogReconciler` 的运维面（对账 SQL、缺口窗口）与统计 API，本课 5.6 已看过它的幂等配合。
- 13.1-13.3 课展开 webhook：`recordClickDelivery`（本课 :110）落下的 PENDING 行由 `WebhookDeliveryRelay` 接手，退避、时钟、SSRF 防护都是 10.2/10.3 模式的进阶版。
- 15.1 课的测试金字塔里，`ClickIdempotencyIT`/`MqEndToEndIT`/`OutboxDrillIT` 是「同步断言打在确定性表、异步收敛用轮询」纪律的范例（`AbstractIntegrationTest` 单例容器含 RabbitMQ 3.13）。
