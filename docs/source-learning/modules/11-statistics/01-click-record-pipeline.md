# 11.1 点击记录管道：同事务双写、双开关矩阵与 fail-open

> 本课回答一个问题：一次 302 跳转发生之后，这次点击是怎么被记下来的？为什么记录失败的时候，用户看到的仍然是一个正常的 302？本课逐行拆 `ClickRecordService` 的 91 行，讲清 `sync`/`async` 两个开关的四种组合，并把 `eventId` 的出生规则（`log-{id}` 还是 UUID）讲透。运输（Outbox、Relay、RabbitMQ）是模块 10 的内容，本课只管「行怎么生出来」。

## 1. 本节学习目标

学完本课，你应该能：

1. 逐行解释 `recordClick`：两个开关分支各写哪张表、`eventId` 的两种取值、`userId` 为什么可为 null。
2. 画出 `sync`×`async` 四种组合的行为矩阵，并说出每种组合下「明细、聚合、UV」三个数据面的可用性。
3. 解释 fail-open catch 为什么必须写在调用方（事务外）才有意义，以及它只捕 `DataAccessException` 的取舍。
4. 说清 B2 过滤（`ClickFilter`）在调用侧的位置，以及「跳过点击」和「记录为 bot」两条路的分界。
5. 报出本管道的三个 Micrometer 指标名，并知道去哪里看它们。

## 2. 前置知识

- **@Transactional**（Spring 框架行为，4.3 课深讲过）。注解在方法上，Spring 用 AOP 代理在方法体开始前开启事务、正常返回时提交、抛运行时异常时回滚。关键点：事务的生死边界在**代理的方法边界**，不在方法体内部某一行。
- **HttpServletRequest 的 header 读取**（3.2 课）。`request.getHeader("User-Agent")` 返回请求头字符串，可能为 null（部分客户端会省略）。
- **ThreadLocal 与 UserContext**（5.3 课）。`UserContext.get()` 从 ThreadLocal 里取当前登录用户；匿名请求没有设置过这个值，返回 null。**UUID**（Java 标准库）生成随机唯一串，本课用它做「没有自增 id 可用时」的事件标识。
- **fail-open / fail-closed**（设计词汇）。fail-open 指依赖故障时放行主流程、牺牲副功能；fail-closed 指拒绝主流程。跳转是主流程，点击记录是副功能，这里选 fail-open。
- **Micrometer MeterRegistry**（14.1 课细讲，本课先会用）。`meterRegistry.counter("名字").increment()` 给计数器加一；带 `,"key","value"` 参数的是带 tag 的计数器。
- **MyBatis-Plus 的 `insert` 回填自增主键**（4.1 课）。实体主键标 `@TableId(type = IdType.AUTO)` 时，`insert` 之后 `getId()` 能拿到数据库生成的自增值。这个行为是 `eventId` 规则的前提。

## 3. 本节涉及的源码

| 文件 | 行数 | 本课关注点 | 阅读顺序 |
|----|----|----|----|
| `src/main/java/com/linkforge/stats/service/ClickRecordService.java` | 91 | recordClick :40-75、toJson :77-83、truncate :85-90 | 1 |
| `src/main/java/com/linkforge/stats/mq/ClickEvent.java` | 29 | MQ 契约十字段 :17-29、eventId 语义注释 :9-13 | 2 |
| `src/main/java/com/linkforge/stats/entity/ClickLog.java` | 28 | variant 列 :23-25、event_id 列 :26-27 | 3 |
| `src/main/java/com/linkforge/stats/entity/Outbox.java` | 29 | 状态常量 :25-28 | 4 |
| `src/main/java/com/linkforge/link/support/ClickFilter.java` | 111 | classify :51-69、prefetch 头 :74-88 | 5 |
| `src/main/java/com/linkforge/link/service/ShortLinkService.java` | 1025 | B2 过滤与 fail-open catch :565-585 | 6 |
| `src/main/java/com/linkforge/config/LinkForgeProperties.java` | 239 | Mq 开关 :115-127 | 7 |
| `src/main/resources/application.yml` | 266 | 生产默认 async=true、sync=true :174-182 | 8 |
| `src/main/resources/db/migration/V2__outbox.sql` | 21 | lf_outbox 表 :5-16、event_id 列 :18-21 | 9 |

阅读顺序的理由：先看写入方本体（谁在事务里写哪两行），再看写出去的两种行（明细实体、MQ 契约），然后看谁决定「写还是不写」（B2 过滤与 catch 在调用侧），最后看开关配置。幂等验收测试见 10.3 课的 ClickIdempotencyIT，本课不复述。

## 4. 类的定位

- `ClickRecordService` 是点击数据的**唯一写手**。类注释自称 "the only writers are this transaction"（`ClickRecordService.java:34`）。它只被 `ShortLinkService.resolveForGet` 调用（`ShortLinkService.java:574`），每次成功判定要 302 的 GET 跳转调一次。
- 它与 RabbitMQ **零接触**。注释写明 "the hot path never touches RabbitMQ, delivery is ClickOutboxRelay's job"（`ClickRecordService.java:35-36`）。热路径里没有网络调用，只有两条 INSERT。
- 它与 `ClickOutboxRelay` 之间**没有类级调用关系**，只通过 `lf_outbox` 表行解耦。`04-core-class-relations` 图纠正过这个口径：不是 record 通知 relay，是 relay 自己轮询表。
- 在 stats 包内部，它属于「写半边」；「读半边」在 11.3 课，聚合写入（`consume`）的幂等机制在 10.3 课、聚合内容在 11.2 课。与模块 10 的分工界线：模块 10 讲「行写出去之后」的运输与消费；本课讲「行怎么产生」，包括开关矩阵和 eventId 出生规则。

## 5. 核心源码详解

### 5.1 recordClick 逐行

先看方法本体（`ClickRecordService.java:40-58`，实体 setter 区块 :47-55 收进省略号）：

```java
    @Transactional
    public void recordClick(ShortLink link, HttpServletRequest request, String variant) {
        LocalDateTime now = LocalDateTime.now();
        String ip = ClientIps.resolve(request, properties.getRateLimit().isTrustXff());
        String ua = truncate(request.getHeader("User-Agent"), 512);
        String referer = truncate(request.getHeader("Referer"), 512);

        ClickLog logRow = new ClickLog();
        logRow.setTenantId(link.getTenantId());
        logRow.setLinkId(link.getId());
        ...
        logRow.setClickedAt(now);
        logRow.setVariant(variant);
        if (properties.getMq().isSyncClickEnabled()) {
            clickLogMapper.insert(logRow);
        }
```

逐行说：

- `:40` `@Transactional`。一个方法里最多两张表各写一行，两行必须同生共死（明细有了而 outbox 没有会丢事件，反过来会丢明细），所以整个方法是一个事务。方法返回时代理提交，抛 `RuntimeException` 时代理回滚。
- `:42` `now` 用 JVM 业务时钟取点击时间。对比：outbox 的 `next_attempt_at` 这类调度列用 DB 时钟（10.2 课的时钟规则）。
- `:43` IP 经 `ClientIps.resolve` 解析（`ClientIps.java:15`），默认不信任 `X-Forwarded-For`（2.2 课）。`:44-45` UA 和 Referer 各截断到 512 字符，与 `lf_click_log` 列宽一致（`V1__baseline_schema.sql:50-51`），`truncate`（`:85-90`）对 null 直接放行。
- `:50` `code` 是快照列，落库时抄一份短码。它**从不参与比较和 join**（V4 迁移的 RULE，4.2 课），join 一律走 `:49` 的 `link_id`。
- `:55` `variant` 记录这次抽中的 A/B 分桶键（7.3 课）。V10 才加的列（`V10__link_variant.sql:29`），单目标链接是 null。落这一列的原因在 V10 注释里：对账器要从明细行重建 ClickEvent，没有这列重放会把每次抽签都塌回默认桶。
- `:56-58` **同步分支**。开关开着就插明细行。插完 `logRow.getId()` 被 MyBatis-Plus 回填为自增值，这个值马上要用。

### 5.2 异步分支与 eventId 出生规则

```java
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
            ...
            outboxMapper.insert(outbox);
        }
```

（`ClickRecordService.java:60-74`，逐字对照源文件。）

- `:61-63` eventId 的两种取值。同步行插成功时自增 id 已回填，取 `"log-" + id`；同步写关着时 `logRow` 没插过库，id 为 null，取一次性生成的 UUID。分支注释在 `:33-39`：`log-{id}` 是**确定性**的，实时消费和对账重放对同一次点击算出同一个 eventId，去重天然成立；UUID 只保证「同一条 outbox 行内稳定」，它生成一次就持久化在 outbox 行里（`uk_outbox_event_id` 唯一键兜底，`V2__outbox.sql:14`）。
- `:64` userId 从 ThreadLocal 取，判空防御。跳转端点 `/r/**` 对 GET/HEAD 匿名放行（`SecurityConfig.java:43-44`），绝大多数点击时 `UserContext.get()` 就是 null。字段在 `lf_click_log` 表里也**不存在**（V1 DDL :44-55 没有 user_id 列），只进 MQ 事件，供 webhook payload 用。
- `:65-67` `ClickEvent` 十个字段一次构造齐（`ClickEvent.java:17-29`）：eventId、tenantId、linkId、code、userId、ip、userAgent、referer、clickedAt、variant。类注释（`:9-13`）明确 eventId 的确定性语义。
- `:68-73` outbox 行：eventId、JSON payload、状态 PENDING、attempts 从 0 起步，就是 10.2 课讲的「本地消息表」的一行。

### 5.3 payload 序列化失败为什么抛 IllegalStateException

```java
    private String toJson(ClickEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("click event serialization failed", ex);
        }
    }
```

（`ClickRecordService.java:77-83`。）

Jackson 序列化一个十个字段的简单 POJO 几乎不可能失败，真失败了只能是代码或配置 bug（比如忘了注册时间模块）。这种错误不是「环境抖动」，多试几次也没用，所以包装成 `IllegalStateException` 直接炸。注意它与 5.4 的 fail-open 是**不对称**的：DB 故障被吞、序列化故障被抛。这个差异是推断出的设计意图，源码注释没有明说，但两种异常的去向确实不同：`IllegalStateException` 不是 `DataAccessException`，会穿透调用方的 catch，让 302 变成 500。

### 5.4 fail-open catch 在事务外的原理

调用侧代码（`ShortLinkService.java:565-585`，:566 与 :583-584 略）：

```java
    public RedirectOutcome resolveForGet(String code, HttpServletRequest request) {
        RedirectOutcome outcome = resolveTarget(code, request, true);
        if (outcome instanceof RedirectOutcome.Redirect redirect) {
            Optional<ClickFilter.SkipReason> skip = clickFilter.classify(request);
            if (skip.isPresent()) {
                meterRegistry.counter("linkforge.click.skipped", "reason", skip.get().tag()).increment();
                return redirect;
            }
            try {
                clickRecordService.recordClick(redirect.link(), request, redirect.variantKey());
            } catch (DataAccessException ex) {
                // fail-open (D-C5-1/6): the 302 must not depend on the click write; the tx
                // inside recordClick has already rolled back when we get here, so the catch
                // sits outside any transaction and the jump still counts as a redirect.
                meterRegistry.counter("linkforge.click.record.failopen").increment();
                log.warn("click record failed for {}: {}", code, ex.getClass().getSimpleName());
            }
            meterRegistry.counter("linkforge.redirects").increment();
        }
        return outcome;
    }
```

原理分三步：

1. **事务在哪里结束**。`recordClick` 的事务由 Spring 代理管理，边界是方法边界。INSERT 失败时代理回滚事务并把异常抛出方法外。等执行流到达 `:575` 的 catch 时，事务已经回滚完毕、连接已归还，「在 catch 里继续用这个事务」根本不成立。注释 `:576-578` 说的 "the tx has already rolled back" 就是这个意思。
2. **catch 为什么写在调用方而不是 recordClick 内部**。写在 recordClick 内部、事务还没结束时吞异常，代理看到方法正常返回会去**提交**，可事务里已经没有可提交的东西（插入失败了），还可能拖出一个「提交已回滚事务」的误导性错误。放在调用方，catch 天然在事务外，语义干净。
3. **只捕 `DataAccessException`**。这是 Spring 把数据库异常翻译后的类型，覆盖「MySQL 挂了、锁超时、连接池枯竭」这类环境故障。它不捕 `IllegalStateException`（5.3），也不捕其他 RuntimeException。兜底的范围是「重试可能有意义的环境问题」，不是「一切异常」。兜底动作本身：`:579` 打 failopen 计数器，`:580` 打 warn 日志且只记异常类名，之后 `:582` 照常打 `linkforge.redirects`，302 照常返回。点击丢了，跳转没丢。

### 5.5 B2 过滤在调用侧的位置

B2 过滤发生在 `recordClick` **之前**（`ShortLinkService.java:568-571`）：`clickFilter.classify(request)` 返回非空 `Optional` 就直接 `return redirect`，根本不进 try 块。这个位置有三个含义：

- **被跳过的跳转拿到一模一样的 302**。`ClickFilter` 的 javadoc（`ClickFilter.java:12-16`）写明：prefetch 和预览机器人拿到与普通 GET 完全相同的 302，只是不记点击，对爬虫不暴露「我被识别了」。**跳过发生在事务外**，不进事务、不写任何表；过滤若放在 recordClick 里面，每次跳转都要先开事务再判断，白付一次事务开销。
- **跳过的口径很窄**。`ClickFilter.java:51-69`：开关关了全放行；prefetch 头识别（`Sec-Purpose`/`X-Moz`/`Purpose`/`X-Purpose`，`:74-88`）；配置清单里的预览爬虫 UA 词匹配。curl、App WebView、空 UA、未知爬虫**都算点击**，消费侧落进 `device=bot` 桶（11.2 课）。识别为 bot 不等于不计数，这是 B2 过滤与 ClickDimensions bot 桶的分界。

### 5.6 meter 族

本课出现的三个指标（都在 `ShortLinkService.java`）；outbox 侧的 `linkforge.outbox.sent/failed` 属于 10.2 课，消费侧指标属于 10.3 课：

| 指标 | 位置 | 含义 |
|----|----|----|
| `linkforge.redirects` | :582 | 成功跳转且未被 B2 跳过 |
| `linkforge.click.skipped{reason}` | :570 | B2 跳过，reason 只有 prefetch/preview_bot 两个值（`ClickFilter.java:30-33` 注释刻意约束 tag 取值有限） |
| `linkforge.click.record.failopen` | :579 | 点击写库失败但跳转放行 |

## 6. 技术原理补充

**同事务双写（Transactional Outbox 的写侧）**。是什么：业务写与消息意图写放进同一个 DB 事务。为什么需要：分别提交会有「明细有了事件没了」或反之的窗口。不用会怎样：只能靠事后对账猜哪些点击丢过。本项目怎么用：`:56-58` 与 `:60-74` 同在一个 `@Transactional` 方法，原子性由数据库保证。代价：每次跳转多一次 INSERT + 事务提交，P2 性能排查（`docs/learning/82-linkforge-decisions.md` 的 D16 段）确认同步明细写是热路径最重的一步。

**开关矩阵**。两个开关（`LinkForgeProperties.java:115-127`，代码默认 async=false、sync=true；`application.yml:177-178` 生产默认两者都开）组合出四种行为：

| sync | async | 行为 | 明细 | 聚合 | UV |
|----|----|----|----|----|----|
| 开 | 开（生产默认） | 明细行 + outbox 行同事务，eventId=`log-{id}` | 实时 | 经 MQ 最终一致 | 实时 |
| 开 | 关 | 只写明细行，无人消费 | 实时 | **永远是 0** | 实时 |
| 关 | 开 | 只写 outbox，eventId=UUID | 消费后回填（10.3） | 经 MQ 最终一致 | 最终一致 |
| 关 | 关 | 方法体什么都不写 | 无 | 0 | 0 |

第二行值得停一下：sync-only 时 `lf_click_daily` 没有任何写入者，`/stats` 的 totalClicks 恒为 0，但明细和 UV 正常。这不是 bug，是「读半边每个数据面依赖不同的写入者」的自然结果（11.3 课会从读侧再看一遍这张矩阵）。

**JSON 列与 ObjectMapper**。`lf_outbox.payload` 是 MySQL JSON 列（`V2__outbox.sql:8`），写入的是 `ObjectMapper` 序列化出的字符串。注入的 ObjectMapper 是 Spring 容器里配好的那个（带 JavaTimeModule，能序列化 `LocalDateTime`）。10.1 课讲过反面教训：RabbitMQ 自带转换器用自制 ObjectMapper，缺 JavaTimeModule 时事件全进死信。

## 7. 可视化

- 主图：[14-click-pipeline-dataflow](../../diagrams/archify/html/14-click-pipeline-dataflow.html)。看「一次点击」节点如何分叉成 lf_click_log 行与 lf_outbox 行，两条路在 lf_consumed_event 汇合。
- Mermaid 备用：[click-pipeline](../../diagrams/mermaid/click-pipeline.md)。
- [07-rabbitmq-topology](../../diagrams/archify/html/07-rabbitmq-topology.html)：看 outbox 行被 Relay 抽走后走哪个 exchange/queue，本课的 outbox 行是它的输入。
- [04-core-class-relations](../../diagrams/archify/html/04-core-class-relations.html)：确认 ClickRecordService 与 ClickOutboxRelay 之间没有箭头，只有表行解耦。

阅读顺序：先 14（或 Mermaid）建立管道全景，再用 07 对上 MQ 侧，用 04 校对类关系。

节点对应：图中「热路径写库」节点 = `ClickRecordService.recordClick`；「outbox」节点 = `lf_outbox` 表 + `Outbox` 实体；「B2 过滤」节点 = `ClickFilter.classify`（在图上位于 recordClick 之前）。

## 8. 项目中的真实执行过程

场景：一个匿名访客点了 `https://short.example.com/r/Aa1Bb2`（链接带 A/B 分流，抽中了 `red` 桶）。

1. `RedirectController.redirect` 调 `resolveForGet`（`RedirectController.java:40`）。判定链走完（7.1 课），返回 `RedirectOutcome.Redirect`，variantKey 为 `red`。
2. B2 分类（`:568`）：UA 是普通浏览器，prefetch 头没有，命中不了任何跳过规则，返回空 Optional。
3. 进入 try，`recordClick`（`:574`）开始，Spring 代理开事务：UA/Referer 截断进 `ClickLog`，sync 开着，插 `lf_click_log` 得到自增 id（比如 4821）。
4. async 也开着，eventId 算出 `log-4821`，userId 为 null（匿名），`ClickEvent` 十字段装满，序列化成 JSON，插 `lf_outbox` 一行 PENDING。方法正常返回，代理提交，两行同落库。
5. 回到 `resolveForGet`，catch 没触发，`linkforge.redirects` 加一，302 返回。
6. 2 秒内 `ClickOutboxRelay` 把这行抽走发进 RabbitMQ（10.2），`ClickEventConsumer` 消费、`lf_click_daily` 加一（11.2）。若第 3 步 MySQL 恰好挂了：DataAccessException 冒泡出 recordClick，事务已回滚，catch 打 failopen 指标，302 照常。

## 9. 为什么这样设计

- **为什么热路径不发 MQ**。发 MQ 是网络调用，RabbitMQ 一抖跳转就抖。Outbox 模式把「发」推迟到另一个进程，热路径只剩本地 DB 写。决策 #10（`docs/progress.md:55`）规定 eventId 取 `log-{click_log.id}`，实时消费与对账重放天然互斥。
- **为什么开关要两个**。四种组合对应四种部署现实：sync-only 适合「要明细和 UV、不要 MQ」的最小部署；async-only 适合「写压力大、可接受最终一致明细」；全关适合纯跳转；全开是完整形态。配置即架构，不用改代码。
- **为什么 B2 放调用侧**（结合 `ClickFilter.java:12-16` 注释推断）：过滤是「跳不跳过记录」的编排决策，属于调用方；recordClick 的职责是忠实记录传进来的东西。职责分离后 recordClick 保持 91 行的小类。
- **为什么明细要存 variant**：V10 注释（`V10__link_variant.sql:25-28`）给出硬理由，对账器从明细行重建事件，缺这列重放会把 A/B 归因塌掉。

## 10. 本节总结

- `recordClick` 在一个事务里写最多两行：sync 开写 `lf_click_log`，async 开写 `lf_outbox`，两行同生共死。
- eventId 出生规则：有自增 id 用 `log-{id}`（确定性，喂给去重），没有用 UUID（一次性，靠持久化保持稳定）。userId 可为 null，它只进 MQ 事件不进明细表。
- fail-open catch 在调用方，事务已回滚后才执行，只捕 DataAccessException；序列化失败抛 IllegalStateException，不兜底。
- B2 过滤在事务外、recordClick 之前，跳过者拿到同样的 302。
- 三个指标：redirects、click.skipped{reason}、click.record.failopen。

## 11. 课后练习

1.（概念理解）sync-only（async 关）部署下，某条链接被点了 10 次。`/api/links/{code}/stats` 的 totalClicks、`/api/links/{code}/clicks` 的明细条数、totalUv 各是多少？为什么？
   <br>答案：totalClicks 是 0（`lf_click_daily` 没有写入者，`ClickDailyMapper.incrementClick` 只被消费路径调用）；明细 10 条（sync 分支实时写入）；totalUv 按 IP 去重后最多 10（`countDistinctIps` 扫的就是明细表）。原因见第 6 节的矩阵：三个数据面各依赖不同写入者。

2.（源码定位）找到 eventId 变成 UUID 的确切条件，并解释为什么 sync 开着时永远不会走到 UUID 分支。
   <br>答案：`ClickRecordService.java:61-63`，条件是 `logRow.getId() == null`。sync 开着时 `:57` 已执行 insert，MyBatis-Plus 对 `@TableId(type = IdType.AUTO)` 的实体回填自增主键，id 不为 null，所以走 `log-{id}` 分支。只有 sync 关闭（没有 insert，id 保持 null）时才生成 UUID。

3.（执行过程）MySQL 正常、RabbitMQ 挂了，用户点击后看到什么？lf_click_log 和 lf_outbox 各几行？点击最终会进聚合表吗？
   <br>答案：正常 302。两表各一行（写库与 MQ 无关，MQ 故障影响的是 Relay 的 publish，行留在 outbox 里退避重试，10.2 课）。会进：MQ 恢复后 Relay 抽走积压行；即使 outbox 行重试 30 次标了 FAILED，`ClickLogReconciler` 也会从明细表扫出未消费的 `log-4821` 重放（11.3 课），消费端靠同一 eventId 去重，两条通路只计一次。

4.（调用链）把 `IllegalStateException`（序列化失败）换成也被 catch 住，会发生什么？说明为什么当前设计只捕 DataAccessException 更安全。
   <br>答案：302 照常返回，但每次点击静默丢事件，线上只有跳转正常、统计全空的假象（catch 里只打 warn）。当前设计让 bug 型异常穿透、打爆在请求路径上，问题立刻可见；环境型异常才走 fail-open。兜底范围按故障性质分，不是兜得越多越稳。

5.（设计分析）如果把 B2 过滤挪进 recordClick 方法体的第一行，会有哪两个直接代价？
   <br>答案：其一，被跳过的跳转也要开事务（方法有 @Transactional，进方法就开），而跳过者本来一行都不用写；其二，recordClick 要新增请求头语义判断，「记录」类从此依赖「分类」逻辑，调用侧 `linkforge.click.skipped` 指标与跳过即返回的编排也被拆散。

## 12. 与后续课程的联系

- 11.2 课接着讲这些事件在消费侧变成哪些聚合行：`lf_click_daily` 的计数 upsert 与 `ClickDimensions` 的四桶规则；11.3 课从读侧回看本课的开关矩阵：stats API 的每个数字来自哪张表、对账器如何补 MQ 的缺。
- 模块 10（10.1-10.3）已讲 outbox 行离手之后的旅程；13.2 课（未出）讲同一个 ClickEvent 如何再生成一条 webhook 投递行。
- 复习推荐：4.3 课的事务边界、10.2 课的退避与 FAILED 重放。
