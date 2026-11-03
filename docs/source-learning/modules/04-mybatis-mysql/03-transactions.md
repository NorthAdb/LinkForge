# 4.3 事务

## 1. 本节学习目标

1. 掌握 Spring 事务的两种写法：`@Transactional` 与 `TransactionTemplate`，说出本项目各自的使用场景。
2. 读懂同事务双写：recordClick 在一个事务里写 lf_click_log 与 lf_outbox。
3. 理解 MySQL（InnoDB）语句级回滚，以及本项目三处「撞唯一索引当控制流」的设计。
4. 记住 fail-open catch 必须放在事务外的完整因果链。
5. 会用 PROPAGATION_REQUIRED 的视角读 WebhookAggregateFlusher 的双触发设计。

## 2. 前置知识

- [4.1 Entity、Mapper 与 CRUD](01-entities-mappers-crud.md)：BaseMapper 的 insert/deleteById、`ON DUPLICATE KEY UPDATE`、唯一索引。
- [4.2 Flyway 与 schema](02-flyway-migrations.md)：lf_outbox（V2）与 lf_consumed_event（V1）的表结构。
- ACID 常识（框架无关常识）：事务原子性 = 组内语句要么全提交要么全不见。InnoDB 默认隔离级别 REPEATABLE READ。
- （框架行为）Spring 事务代理：`@Transactional` 方法被调用时，实际先进入 Spring 生成的代理对象，由它开启/提交/回滚连接。

## 3. 本节涉及的源码

| 文件 | 职责 | 阅读顺序 | 为什么读它 |
|------|------|----------|------------|
| `user/service/AuthService.java`（看 :59-100） | @Transactional 注册 | 第 1 | 注解事务的最小完整样本 |
| `stats/service/ClickRecordService.java`（91 行） | 同事务双写 | 第 2 | 本课主角 |
| `link/service/ShortLinkService.java`（看 :237-264, :505-511, :546-558） | 模板事务三例 | 第 3 | 语句级回滚的重试用法 |
| `stats/service/ClickStatsService.java`（看 :42-113） | 幂等消费事务 | 第 4 | 手工 TransactionTemplate 构造 |
| `webhook/service/WebhookAggregateFlusher.java`（看 :32-36, :100-109） | 传播行为的活教材 | 第 5 | 一个方法两种事务边界 |
| `link/service/ShortLinkService.java`（看 :573-581） | 事务外的 fail-open catch | 第 6 | 边界纪律 |

## 4. 类的定位

本项目的事务全部发生在 Service/Mapper 层，Controller 层零事务注解。两种 API 分工明确：`@Transactional` 用在「整个方法就是一件事」的场合（注册、记点击），`TransactionTemplate` 用在「方法内某个片段是件事」的场合（创建中的插链+插变体、删除中的双删）。位置见[包结构图](../../diagrams/archify/html/03-package-map.html)：三个事务主力分属 user/stats/link/webhook 四包。

## 5. 核心源码详解

### 5.1 @Transactional 基础：注册建租户（AuthService.java:59-100）

javadoc 先给业务动机（AuthService.java:59-63）：

```java
    /**
     * Open registration: each request creates a brand-new tenant and its first user in
     * one transaction, so a failed user insert cannot leave an orphan tenant squatting
     * on the unique name. The unique index is the 409 backstop under races.
     */
    @Transactional
    public LoginResponse register(RegisterRequest request, HttpServletRequest httpRequest) {
```

场景：注册要插两行，lf_tenant（占住租户名）和 lf_user。若 user 插入失败（比如 BCrypt 后列超长这类意外）而 tenant 已提交，租户名就被一个残废租户永久占住（uk_tenant_name 唯一索引）。事务把两行绑成一个单位：user 失败，tenant 回滚，名字释放。

```java
        try {
            tenantMapper.insert(tenant);
        } catch (DuplicateKeyException ex) {
            throw new BizException(409, "tenant name already exists");
        }
```

（AuthService.java:80-84）

（框架行为）`@Transactional` 默认传播 REQUIRED（没有事务则新开，有则加入）、遇 RuntimeException 回滚。`BizException` 继承 RuntimeException（2.1 课），所以 throw 即回滚——409 的租户插入也被回滚（虽然它本来就没成功，这里回滚的是事务中已有的任何状态）。限流检查（:66-73）放在事务内：被限流的请求白开一个事务，成本可忽略（本项目取舍，推断）。

### 5.2 同事务双写：recordClick（ClickRecordService.java:33-75）

本课主角。一次跳转要留两份数据：明细行（lf_click_log，供 UV 与明细页）和投递凭证（lf_outbox PENDING，供异步走 MQ）。

```java
    /**
     * A7: the only writers are this transaction. The sync lf_click_log row (when
     * enabled) and the PENDING outbox row commit atomically; the hot path never
     * touches RabbitMQ, delivery is ClickOutboxRelay's job. eventId stays
     * "log-{id}" while the sync write is on so replays dedupe, and becomes a
     * stable UUID persisted in the outbox when it is off.
     */
    @Transactional
    public void recordClick(ShortLink link, HttpServletRequest request, String variant) {
```

（ClickRecordService.java:33-41）

方法体（:42-75）分两段：sync 开则插明细行（:56-58），async 开则组装 ClickEvent JSON 并插 outbox 行（:60-74）。关键在两点。第一，`logRow.getId()`（:61-62）在 insert 后即可用——4.1 课讲过的主键回填；eventId 用 "log-{自增id}"，两个写入靠这个稳定 ID 在下游对上账。第二，注释里的「the hot path never touches RabbitMQ」：事务内绝不碰网络 IO，发 MQ 是另一个进程的调度任务（ClickOutboxRelay）的事。事务里的每毫秒都占着行锁与连接，MQ 的秒级抖动不能进来。（框架行为）这就是事务性 outbox 模式的核心：用同库两行的原子性替代「写库+发消息」的分布式事务。

### 5.3 TransactionTemplate 三例：片段级原子

（框架行为）`TransactionTemplate.executeWithoutResult(callback)` 把传入的 lambda 包进事务：进入开事务，lambda 正常返回则提交，抛异常则回滚并向上抛。它不用代理，就没有自调用失效问题（同类内 `this.method()` 绕过代理的经典坑）。

**例 1：创建短链的主体**（ShortLinkService.java:231-264）：

```java
        // uk_code is the concurrency backstop. A taken custom code is a 409; random
        // codes regenerate on collision instead of failing the request.
        // C3-S1: the link row and its variants commit atomically — a variants failure
        // rolls the link back too, and the Bloom/cache/idem tail below only runs after
        // the commit. The DuplicateKeyException catch is safe inside the transaction:
        // InnoDB rolls back only the failed statement, so the retry loop can continue.
        transactionTemplate.executeWithoutResult(status -> {
            boolean inserted = false;
            if (customCode != null && !customCode.isEmpty()) {
                link.setCode(customCode);
                try {
                    shortLinkMapper.insert(link);
                    inserted = true;
                } catch (DuplicateKeyException ex) {
                    throw new BizException(409, "code already exists");
                }
            } else {
                for (int attempt = 0; attempt < CODE_ATTEMPTS && !inserted; attempt++) {
                    link.setCode(ShortCodes.randomCode(properties.getShortLink().getCodeLength()));
                    try {
                        shortLinkMapper.insert(link);
                        inserted = true;
                    } catch (DuplicateKeyException ex) {
                        log.info("short code collision on {}, retrying (attempt {})", link.getCode(), attempt + 1);
                    }
                }
            }
            if (!inserted) {
                throw new BizException("failed to allocate short code");
            }
            if (!variants.isEmpty()) {
                insertVariants(link, variants);
            }
        });
```

这里有三层设计值得逐行读。

语句级回滚的利用（:235-236 注释 + :248-256）：随机码撞 uk_code 时，catch 住 DuplicateKeyException 换一个码重插。这在 InnoDB 里是安全的——失败的只有那一条 INSERT 语句，事务没有整体作废，lambda 继续跑。而自定义码分支撞车直接 409：用户指定的码被占是业务结果，不是可重试的技术错误。

顺序纪律：注释说「Bloom/cache/idem tail below only runs after the commit」。lambda 外的 `codeBloom.put`（:266）等操作都在事务提交之后才执行——把它们放 lambda 里，一旦后续语句失败回滚，Bloom 里就多了一个幽灵码。

校验前置：`validateVariants`（:212-213，4.1 课读过）在进入事务前完成，注释 :211「validate before ANY write so a bad variant cannot leave a minted link」——事务保证原子，但最好的回滚是不需要回滚。

**例 2：更新中的变体整表替换**（ShortLinkService.java:505-511）：

```java
        if (replacement != null) {
            transactionTemplate.executeWithoutResult(status -> {
                variantMapper.delete(new LambdaQueryWrapper<LinkVariant>()
                        .eq(LinkVariant::getLinkId, link.getId()));
                insertVariants(link, replacement);
            });
        }
```

「删除全部旧行再插入新行」必须原子：删了没插上，A/B 分流凭空消失。注意主行 update（:504 的 updateById）在这个事务之外——它单语句本身原子，不需要陪着变体进事务。（本项目实现）刻意收窄事务边界：包得越少，持锁越短。

**例 3：删除**（ShortLinkService.java:543-558）：

```java
    /** Logical delete: the code stays reserved (uk_code) and cannot be reused.
     * C3-N3: variants are hard rows (no logical-delete column) — they must not outlive
     * the link, so both deletes commit as one unit; cache evictions stay outside. */
    public void delete(String code) {
        ShortLink link = getOwnedLink(code);
        CompletableFuture<ShortLink> priorFlight = singleFlight.peek(code);
        transactionTemplate.executeWithoutResult(status -> {
            shortLinkMapper.deleteById(link.getId());
            variantMapper.delete(new LambdaQueryWrapper<LinkVariant>()
                    .eq(LinkVariant::getLinkId, link.getId()));
        });
        singleFlight.forget(code, priorFlight);
        evictCache(code);
        scheduleStaleClear(code);
        cacheBus.publishL1Invalidate(code);
    }
```

javadoc 点名两种删除的语义差：link 逻辑删（deleted=1，码占位），variants 物理删（表没有 deleted 列，硬删）。两行必须同生共死——variants 残留会成为「孤儿变体」，抽签可能抽到一个已删链接的目标。事务之外是缓存清理四连：forget、evict、延迟双删、广播。注释明确「cache evictions stay outside」：Redis 操作不回滚，混进事务只会白占连接。

### 5.4 幂等消费事务：dedupTx（ClickStatsService.java:42-113）

```java
    public ClickStatsService(ConsumedEventMapper consumedEventMapper,
                             ...
                             PlatformTransactionManager transactionManager) {
        ...
        this.dedupTx = new TransactionTemplate(transactionManager);
    }
```

（ClickStatsService.java:42-54，节选）

（框架行为）直接 new TransactionTemplate(平台事务管理器) 与注入 Spring 预置的 TransactionTemplate Bean 等价，只是显式命名 dedupTx 让调用处可读。消费 MQ 事件的整个 handle 在一个事务里完成五件事（consume，:66-113）：

```java
        return Boolean.TRUE.equals(dedupTx.execute(status -> {
            try {
                ConsumedEvent row = new ConsumedEvent();
                row.setEventId(event.getEventId());
                row.setConsumedAt(LocalDateTime.now());
                consumedEventMapper.insert(row);
            } catch (DuplicateKeyException ex) {
                return false;
            }
            ...
            clickDailyMapper.incrementClick(event.getTenantId(), event.getLinkId(), event.getCode(), date);
            ...
            webhookService.recordClickDelivery(event);
            return true;
        }));
```

（ClickStatsService.java:70-111，节选）

第一件事是往 lf_consumed_event 插 eventId 主键行（IdType.INPUT，4.1 课）。撞主键说明这个事件被消费过，`return false` 直接跳出——注释（:59-60）解释这为什么安全：

```java
     * A duplicate key inside the callback is a plain skip: MySQL rolls
     * back only the failed statement.
```

又是语句级回滚。整个消费的幂等链条是：dedup 插入抢锁（撞键即已处理）→ 明细回填（UUID 事件才有，:79-93）→ 日计数 upsert → 维度 upsert（:97-105）→ webhook 入队（:110）。任何一步抛非重复异常，整个事务回滚，MQ 那边消息 nack 进 DLQ 等重放（模块 10）。注释（:106-109）把 webhook 入队也算进同一事务：「one eventId can never enqueue twice」。

WebhookService.recordClickDelivery 自己再分一次流（WebhookService.java:270-276）：DuplicateKeyException（并发路径已入队）打点吞掉；其他异常重新抛出，把整个 consume 事务带回滚。catch 的取舍粒度精确到异常类型。

### 5.5 一个方法两种事务边界：WebhookAggregateFlusher（WebhookAggregateFlusher.java:32-36, 100-109）

类 javadoc 是传播行为的活教材：

```java
 * PROPAGATION_REQUIRED on {@link TransactionTemplate}: the threshold call joins the
 * consume transaction (digest rows commit or roll back with the statistics), the
 * sweep opens its own. Re-flush is idempotent — a DuplicateKeyException on a part
 * insert means that part already shipped and is swallowed, so a concurrent
 * sweep/threshold race converges to one digest per part (D-C1-23).
```

（WebhookAggregateFlusher.java:32-36）

flushWindow 有两个触发者：计数达到阈值（从 consume 事务内部调用）和定时扫窗（sweep，无事务环境）。同一个 `tx.executeWithoutResult`（:103-104）在两种环境下的行为不同：

- REQUIRED 传播：已有事务就加入。阈值触发时，digest 行与统计行同生共死——统计提交但 digest 丢了（或反之）都会造成「消费了却没通知」。
- 无事务环境（sweep）就新开一个。每个窗口独立事务，注释（:112-114）说明中途崩溃只损失当前窗口，其余窗口下轮补上。

并发收口靠唯一索引：sweep 与阈值触发同时 flush 同一窗口，两边的 digest 插入只有一方成功，另一方撞 `uk_webhook_endpoint_event` 吞 DuplicateKey 收敛。三个幂等设计（本节 5.3/5.4/5.5）用的是同一件武器。

### 5.6 fail-open catch 为什么必须在事务外（ShortLinkService.java:573-581）

```java
            try {
                clickRecordService.recordClick(redirect.link(), request, redirect.variantKey());
            } catch (DataAccessException ex) {
                // fail-open (D-C5-1/6): the 302 must not depend on the click write; the tx
                // inside recordClick has already rolled back when we get here, so the catch
                // sits outside any transaction and the jump still counts as a redirect.
                meterRegistry.counter("linkforge.click.record.failopen").increment();
                log.warn("click record failed for {}: {}", code, ex.getClass().getSimpleName());
            }
```

因果链完整读一遍。MySQL 宕机或连接池耗尽时，recordClick 的 `@Transactional` 代理回滚事务并向上抛 DataAccessException。异常穿出代理的瞬间，事务已经结束（回滚完毕），调用方 resolveForGet 不在任何事务里——所以这个 catch 是「事务外的 catch」。它做的事只有：打 fail-open 指标、warn 日志、继续返回 302。跳转的可用性不押在统计写入上。

反过来想（推断）：若 catch 写进 recordClick 的事务 lambda 内部，异常被吞则事务正常提交「半截数据」；若 catch 在事务注解方法内部，Spring 看到的是「方法正常返回」，同样提交半截。fail-open 的前提是「底层已经干净地回滚」，这只有在事务边界之外才成立。还有一层：只捕 DataAccessException（Spring 的数据访问异常体系），其余异常照常上抛变 500——fail-open 限定于「数据层故障」这一种被授权的失败。

## 6. 技术原理补充

**@Transactional 的自调用陷阱**（框架行为）。注解靠代理实现，同类里 `this.doCreate()` 不会经过代理，注解失效。本项目的规避法是把事务边界放在 lambda 上（TransactionTemplate 没有代理问题），或让事务方法只被其他类调用（AuthService.register 只被 AuthController 调）。

**REQUIRED 传播**（框架行为）。加入现有事务时，内层代码无法单独提交或回滚——内层抛异常标记整个事务 rollback-only，外层即使 catch 也救不回来（提交时报 UnexpectedRollbackException）。WebhookAggregateFlusher 的注释正是依赖这个语义：阈值路径的 digest 失败会连统计一起回滚，这是特性不是缺陷。

**InnoDB 的语句级回滚**（框架行为）。一条语句失败（如唯一键冲突）只回滚该语句，事务继续可用。这是 SQL 标准之外的实用行为（PostgreSQL 会让整个事务进入 aborted 状态），本项目三处设计都踩在这个差异上。跨数据库移植时要重估。

**事务与连接池**（框架行为）。事务从开始到提交独占一个池连接（Hikari 默认池 10-20）。本项目把 Redis、MQ、BCrypt 全部挡在事务外（recordClick 注释、delete 注释），就是为了缩短持连接时间——事务内的每一次网络往返都在放大连接耗尽的风险。

## 7. 可视化

- [点击管道 Mermaid 备用图](../../diagrams/mermaid/click-pipeline.md)：recordClick 双写与 outbox 下游的全部节点。
- [创建流程 Mermaid 备用图](../../diagrams/mermaid/create-flow.md)：doCreate 事务段与提交后尾巴的边界。
- [数据库 ERD](../../diagrams/archify/html/05-database-erd.html)：lf_click_log 与 lf_outbox 的 event_id 逻辑引用线，就是同事务双写在 schema 上的投影。

阅读顺序：先 Mermaid 点击管道建立全局，再回 5.2 与 5.4 对照事务边界在图上的两段。

## 8. 项目中的真实执行过程

走一遍一次完整跳转的点击落账（全链路成功）。

1. `GET /r/AbC1234` 走完 Filter 链与门禁，拿到 Redirect（3.2 课第 8 步之后）。
2. resolveForGet 调 `clickRecordService.recordClick(...)`（ShortLinkService.java:574）。
3. 代理开启事务。插 lf_click_log 行（sync 开，ClickRecordService.java:56-58），拿到自增 id=880。
4. 组装 eventId="log-880" 的 ClickEvent，序列化成 JSON，插 lf_outbox 行 PENDING（:60-74）。
5. 方法正常返回，代理提交。两行同生死。
6. 两秒内 ClickOutboxRelay 扫到 PENDING 行，发布 RabbitMQ，markSent（OutboxMapper.java:19-20）。
7. 消费端 ClickEventConsumer 收到事件，进 ClickStatsService.consume 的 dedupTx：lf_consumed_event 插 "log-880" 成功 → lf_click_daily +1 → 维度桶 +1 → webhook 入队，提交。

故障对照一（第 5 步前 MySQL 挂）：两行都没落，事务回滚，resolveForGet 的 catch（5.6）打 fail-open 指标，用户照常拿到 302。这笔点击丢明细不丢跳转。

故障对照二（第 7 步消费后、提交前崩溃）：整个 dedupTx 回滚，MQ 消息进 DLQ；重放时 lf_consumed_event 插入成功，幂等保证计数恰好一次。

## 9. 为什么这样设计

- 事务短而少：全项目只有 4 个注解事务位与 5 个模板事务段，每个都圈定在「多行同生死」的最小集合上。缓存、Redis、MQ、BCrypt 一律在外。（推断：从注释反复强调 tail 在 commit 之后反推，作者对连接占用敏感。）
- 幂等交给唯一索引而不是分布式锁：lf_consumed_event 主键、uk_code、uk_webhook_endpoint_event 都是无外部依赖的互斥器，撞键即「已处理」，天然容忍崩溃重放（Make Operations Idempotent 的教科书落法）。
- 双写 eventId 的设计：sync 行隐式 "log-{id}"、outbox 行显式持同 ID，让「明细与事件」在两个管道里可对账；sync 关闭时退化为 UUID，Reconciler 按标记补明细（ClickLog.eventId 非 NULL 即已计数）。
- fail-open 只授权给数据层：限流器的 Redis 故障有自己的 fail-open（RedisRateLimiter 内部），各依赖的降级各管各的，catch 类型就是权限边界。

## 10. 本节总结

- `@Transactional` 用于整方法原子（register、recordClick）；TransactionTemplate 用于方法内片段（doCreate、update 变体、delete、consume、flushWindow）。
- 事务性 outbox：明细行与 PENDING 凭证同事务提交，MQ 出事务，relay 异步搬运。
- InnoDB 语句级回滚被三处利用：随机码撞车重试、consume 撞键即 skip、flush 并发撞键收敛。
- REQUIRED 传播让 flushWindow 在阈值路径并入 consume 事务、在 sweep 路径自开——一个调用点两种边界。
- fail-open catch 必须在事务外：回滚已完成是「继续放行」的前提；catch 类型（DataAccessException）圈定授权范围。

## 11. 课后练习

**练习 1（概念理解）**：register 里 userAccountMapper.insert 抛异常，tenantMapper.insert 的租户行会怎样？若两个 mapper 都没有事务注解呢？

答案解析：@Transactional（AuthService.java:64）让两行同事务，user 插入失败抛 RuntimeException，代理回滚整个事务，tenant 行消失，租户名释放（:59-63 注释的孤儿问题不发生）。无注解则两条 insert 各自自动提交，tenant 已落库，孤儿租户占名——这就是「failed user insert cannot leave an orphan tenant squatting on the unique name」防的事故。

**练习 2（源码定位）**：doCreate 的随机码重试环最多试 16 次（CODE_ATTEMPTS），为什么这个循环安全？找出支撑它的注释与涉及的异常类型。

答案解析：支撑注释在 ShortLinkService.java:235-236：「The DuplicateKeyException catch is safe inside the transaction: InnoDB rolls back only the failed statement」。失败的 INSERT 语句回滚后事务仍可用，lambda 继续下一轮换码重插。异常类型 org.springframework.dao.DuplicateKeyException（:253），是 Spring 把 MySQL 1062 翻译后的数据访问异常。

**练习 3（执行过程）**：MQ 把同一个 eventId="log-880" 的事件投递了两次。第二次 consume 的每一步发生什么？

答案解析：dedupTx 开启；lf_consumed_event 插入 "log-880" 撞主键，DuplicateKeyException 被 catch，`return false`（ClickStatsService.java:70-78）——日计数、维度、webhook 入队一步都没执行。提交（此时事务里只有失败的语句，无实际变更）。返回 false，消费端正常 ack。两次投递、恰好一次计数。

**练习 4（调用链）**：假设有人把 resolveForGet 的 try/catch 移进 ClickRecordService.recordClick 内部（包住两个 insert），会发生什么？

答案解析：catch 在事务内，吞掉 DataAccessException 后方法正常返回，代理视为成功并提交——可能提交只剩一条或零条有效行的「半截事务」。更糟的是 Redis/MQ 侧的后续动作（Bloom、缓存写穿在 doCreate 是提交后；这里虽无尾巴）语义不再有「回滚已完成」的保证。正确位置是调用方 resolveForGet（ShortLinkService.java:575-581），注释原文解释了 tx already rolled back 才 catch。

**练习 5（设计分析）**：delete 的 javadoc 说「cache evictions stay outside」。若把 evictCache/scheduleStaleClear 挪进事务 lambda，说出两种坏结局。

答案解析：其一，回滚失配：若 variants 删除等后续语句失败回滚，链接其实还在，但缓存已被清掉并广播——接下来一段时间读请求回源 DB，无正确性问题只有无谓回源（轻微）。其二更危险的是顺序反例：事务还没提交，广播已发出，其他实例收到失效后重读 DB 读到的仍是旧行并回填缓存，延迟双删窗口反而放大脏读。缓存动作没有回滚语义，放事务里只可能错配（对照 :543-545 注释与 5.3 例 3 的顺序）。

## 12. 与后续课程的联系

outbox 行被谁搬运、FAILED 后怎么重放，是模块 10（RabbitMQ 与 Outbox）的完整剧本；consume 的 DLQ 语义与 default-requeue-rejected=false 的配合也在那里。recordClick 里的 ClickFilter 分类（哪些跳转不记）在 [3.2](../03-spring-mvc/02-request-lifecycle-filters.md) 露过面，模块 11.1 会拆 B2 过滤的 21 个 token。WebhookService.recordClickDelivery 的入队细节（PER_CLICK/AGGREGATE 分流）在模块 13 展开，flushWindow 的 digest 分片规则（每片 500 桶、最多 10 片）也是。
