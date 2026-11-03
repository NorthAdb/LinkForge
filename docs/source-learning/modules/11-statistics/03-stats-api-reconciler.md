# 11.3 统计 API 与对账：StatsController 三端点、缺日补零与 ClickLogReconciler

> 本课回答两个问题：看板上的每个数字是从哪张表读出来的？MQ 丢过的事件靠什么补回来？前半段拆 `StatsController` 的 66 行（三端点、days 钳制、dim 白名单、404 归属语义），后半段拆 `ClickLogReconciler` 的 55 行（扫描 SQL 的两个排除条件、确定性 eventId 的配合、行级 catch），最后把 `mq-drill.sh` 的三幕剧本对到代码上。

## 1. 本节学习目标

学完本课，你应该能：

1. 画出三端点各自「Controller → Service → Mapper SQL」的完整分层，指出每个数字的来源表。
2. 解释 UV 为什么依赖 sync 双写开着、async-only 模式下如何退化；说清 days/size 钳制（回退默认值）与 dim 白名单（400）两种参数策略的分界。
3. 逐条件拆 `selectUnconsumed` 的 SQL，说出两个排除条件各挡住什么。
4. 描述对账器与 Relay 的双通道汇合点，并把 `mq-drill.sh` 的恢复幕对到代码。

## 2. 前置知识

- **REST 参数绑定与默认值**（3.1 课）。`@RequestParam(defaultValue = "7")` 在参数缺席时取默认；类型转换失败（days=abc）由框架返回 400。业务报错走 **BizException 的全局映射**（2.1 课）：`BizException(404, ...)` 被 `GlobalExceptionHandler`（`GlobalExceptionHandler.java:27-30`）转成对应状态码的统一 JSON 信封。
- **MyBatis-Plus 分页**（4.1 课）。`Page<T>` 传给 `selectPage`，插件拼 LIMIT 并回填 total。
- **COUNT(DISTINCT col)**（SQL 聚合）。按列去重计数，本项目的 UV 就是「窗口内不同 IP 数」，不是 PV 除以什么系数，也不是 cookie 口径。**反连接（anti-join）**（SQL 惯用法）：`LEFT JOIN b ON ... WHERE b.key IS NULL` 找出「在 a 里但没有对应 b 行」的行，对账扫描用它找「明细有、消费记录没有」的点击。**@Scheduled 的 fixedDelayString**（10.2 课讲过 Relay 的同款）上一轮结束后再计时，值写成 `${...}` 属性引用，可从配置覆盖。

## 3. 本节涉及的源码

| 文件 | 行数 | 本课关注点 | 阅读顺序 |
|----|----|----|----|
| `src/main/java/com/linkforge/stats/controller/StatsController.java` | 66 | 三端点 :33-65、白名单 :26-28 | 1 |
| `src/main/java/com/linkforge/stats/service/ClickStatsService.java` | 154 | 查询半边 :115-153 | 2 |
| `src/main/java/com/linkforge/stats/mapper/ClickLogMapper.java` | 32 | 扫描 :14-18、UV :20-24、按日 UV :26-31 | 3 |
| `src/main/java/com/linkforge/stats/mapper/ClickDailyMapper.java` | 32 | sumClicks :24-25、selectRange :27-31 | 4 |
| `src/main/java/com/linkforge/stats/mapper/ClickDimDailyMapper.java` | 40 | selectBreakdown :32-39 | 5 |
| `src/main/java/com/linkforge/stats/service/ClickLogReconciler.java` | 55 | 全文 | 6 |
| `src/main/java/com/linkforge/stats/dto/` 全部 5 文件 | 12-15 | 三个响应形状（LinkStatsResponse/DailyClicks/ClickDetail）+ 两个 SQL 投影行（DailyUv/DimBreakdown） | 7 |
| `src/main/java/com/linkforge/stats/mapper/OutboxMapper.java` | 36 | 状态 SQL 的守卫对照 | 8 |
| `src/test/java/com/linkforge/stats/StatsApiIT.java` | 164 | 两个验收用例 | 9 |
| `scripts/mq-drill.sh` | 75 | 三幕剧本 | 10 |

注：OutboxMapper 属于模块 10 的主课，本课借它做一处口径对照。

阅读顺序的理由：先自顶向下走读三端点（请求到 SQL），再横向对照 DTO 投影，然后掉头看写侧漏斗的另一端（对账器），最后用 IT 与 drill 脚本验收。

## 4. 类的定位

- `StatsController` 是统计读面的**唯一入口**，三端点全挂 `/api/links/{code}` 前缀下，鉴权口径复用短链管理侧：先 `shortLinkService.detail(code)` 拿链接（`StatsController.java:36,:52,:61`），不存在的或不属于当前用户的 code 直接 404。
- `ClickStatsService` 一身两职：10.3 课的写半边（consume）与本课的读半边（stats/breakdown/clicks，`:115-153`），读半边无事务、无状态，方法即 SQL 编排。五个 DTO 里 `DailyUv`/`DimBreakdown` 被 MyBatis 当作 SQL 投影目标（列别名对字段名），其余三个是纯响应形状。
- `ClickLogReconciler` 是同步明细行的**安全网**，也是 MQ 故障期的第二聚合通道。类注释（`ClickLogReconciler.java:15-19`）自述分工："live delivery belongs to ClickOutboxRelay; this loop is skipped entirely while the sync write is off"。与模块 10 的分工界线：Relay 抽 outbox 行是「主通道」，对账器是「备通道」，两者汇合在 `consume` 的 dedup 锁上。

## 5. 核心源码详解

### 5.1 三端点分层

**GET /api/links/{code}/stats**（`StatsController.java:33-39`）：

```java
    @GetMapping("/api/links/{code}/stats")
    public ApiResponse<LinkStatsResponse> stats(@PathVariable String code,
                                                @RequestParam(defaultValue = "7") int days) {
        ShortLinkDetail link = shortLinkService.detail(code);
        int window = days < 1 ? 7 : Math.min(days, 90);
        return ApiResponse.ok(clickStatsService.stats(link.getId(), code, window));
    }
```

Service 侧 `stats`（`ClickStatsService.java:126-142`）：

```java
    public LinkStatsResponse stats(Long linkId, String code, int days) {
        long total = clickDailyMapper.sumClicks(linkId);
        LocalDate today = LocalDate.now();
        LocalDate fromDate = today.minusDays(days - 1L);
        LocalDateTime from = fromDate.atStartOfDay();
        LocalDateTime toExclusive = today.plusDays(1).atStartOfDay();
        long totalUv = clickLogMapper.countDistinctIps(linkId, from, toExclusive);
        ... // byDate/uvByDate 两个 Map：selectRange :81-82 与 selectDailyUv :83-84 各转一个
        List<DailyClicks> daily = new ArrayList<>();
        for (LocalDate d = fromDate; !d.isAfter(today); d = d.plusDays(1)) {
            daily.add(new DailyClicks(d, byDate.getOrDefault(d, 0L), uvByDate.getOrDefault(d, 0L)));
        }
        return new LinkStatsResponse(code, total, totalUv, daily);
    }
```

（`ClickStatsService.java:126-142`，两个 Map 的流式转换收进省略号。）

- **totalClicks**：`sumClicks` = `SELECT IFNULL(SUM(clicks), 0) FROM lf_click_daily WHERE link_id = ?`（`ClickDailyMapper.java:24-25`）。来自聚合表，MQ 链路的最终一致值，窗口无关（全历史）。
- **totalUv**：`countDistinctIps` = `SELECT COUNT(DISTINCT ip) FROM lf_click_log WHERE link_id = ? AND clicked_at >= ? AND clicked_at < ?`（`ClickLogMapper.java:20-24`）。窗口是 `[from 当日 0 点, 明日 0 点)`（`:130-131`），左闭右开。来自明细表。
- **按日两个 Map**：clicks 来自 `selectRange`（`ClickDailyMapper.java:27-31`），uv 来自 `selectDailyUv` = `SELECT DATE(clicked_at) stat_date, COUNT(DISTINCT ip) uv ... GROUP BY DATE(clicked_at)`（`ClickLogMapper.java:26-31`）。

`:137-140` 的循环是**缺日补零**：SQL 只回有数据的日子，循环从 from 逐日走到 today，`getOrDefault(d, 0L)` 把没数据的日子填 0。返回的 daily 长度恒等于窗口天数（`StatsApiIT.java:117` 断言 days=7 时 daily.size()==7）。两个 Map 先行的写法避免了「每天两次 SQL」的 N+1。

**GET /api/links/{code}/stats/breakdown**（`StatsController.java:45-55`）：

```java
    /** A8: per-bucket click counts (device / referer_host / os / browser). Dimension clicks are not UV. */
    @GetMapping("/api/links/{code}/stats/breakdown")
    public ApiResponse<List<DimBreakdown>> breakdown(@PathVariable String code,
                                                     @RequestParam(required = false) String dim, ...) {
        if (dim == null || dim.isBlank() || !DIM_TYPES.contains(dim)) {
            throw new BizException(DIM_REQUIRED);
        }
        ...
        return ApiResponse.ok(clickStatsService.breakdown(link.getId(), dim, window));
    }
```

- 白名单 `DIM_TYPES`（`:26-28`）是 11.2 课五个 TYPE 常量的 Set：device、referer_host、os、browser、variant。dim 缺失、空白、不在白名单都抛 `BizException(DIM_REQUIRED)`（`:41-42`），映射为 400。javadoc（`:44`）顺带划清口径："Dimension clicks are not UV"——device 桶之和可以大于 UV，因为一个 UV 可以既点又换设备。
- Service 的 `breakdown`（`ClickStatsService.java:116-120`）一行：窗口起点是 `today.minusDays(days - 1)`（含今天共 days 天），SQL 在 `ClickDimDailyMapper.java:32-39`：`SELECT dim_value AS dimValue, SUM(clicks) AS clicks ... WHERE link_id=? AND dim_type=? AND stat_date BETWEEN ... GROUP BY dim_value ORDER BY clicks DESC, dim_value ASC`。排序双键：点击数降序，同数按字典序，结果稳定可测。

**GET /api/links/{code}/clicks**（`StatsController.java:57-65`）与 stats 同构：先 detail，再钳制 `p = Math.max(1, page)`、`s = size < 1 || size > 100 ? 20 : size`（`:62-63`）。Service 的 `clicks`（`ClickStatsService.java:144-153`）用 MyBatis-Plus 的 `Page` + `LambdaQueryWrapper`：`eq(linkId)` + `orderByDesc(clickedAt)`。明细行只投影 4 个字段（clickedAt、ip、userAgent、referer，`:150`），tenant/link/code 等不外泄。

### 5.2 参数钳制：为什么 days=-1 返回 7 而 dim=geo 返回 400

两种策略的分界：**有合理默认值的区间参数回退默认，无默认语义的可枚举参数报错**。days 是连续区间（`days < 1 ? 7 : Math.min(days, 90)`，`StatsController.java:37`），0 或负数无法知道用户想要什么，回退到默认 7 最不意外；超出 90 钳到 90（保护明细表上的 COUNT DISTINCT 扫描范围）。size 同理（`:63`，1..100 之外回 20）。dim 相反：它是 11.2 课五维的枚举，传 geo 要么是前端 bug 要么是探测，猜测只会掩盖问题，400 加一条固定报错文案（`DIM_REQUIRED`）才是契约。`BreakdownDimValidationTest`（`:23-27` javadoc）记录了修过的坑：dim 缺失曾绕过 BizException 映射落到兜底处理器变 500，现在缺失与非法都稳定 400 且响应体无堆栈（`:51-66`）。

### 5.3 归属校验复用 detail 的 404 语义

三个端点的第一步都是 `shortLinkService.detail(code)`。`detail`（`ShortLinkService.java:446-448`）内部走 `getOwnedLink`（`:890-901`）：链接不存在、或 tenant 不符、或非 tenant 级用户的创建人不符，一律抛 `BizException(404, "short link not found")`。用 404 而不是 403 是整个管理 API 的既定语义（5.3 课）：不向越权者确认资源存在。统计端点直接继承这层校验，自己不写一行权限代码。`StatsApiIT` 两个用例各断言了一次：另一个用户查 stats 得 404（`:126-127`）、查 clicks 得 404（`:161-162`）。

### 5.4 ClickLogReconciler 逐行

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
            ... // :48-50 收尾日志
        } catch (Exception ex) {
            log.warn("click reconcile pass failed: {}", ex.getMessage());
        }
    }
```

（`ClickLogReconciler.java:30-53`，逐字对照源文件，:48-50 收尾日志收进省略号。）

- **门禁（:33-35）**：async 与 sync 必须**同时**开着才干活。sync 关了没有明细行可扫；async 关了没有消费路径可走（此时明细有行但聚合本来就不该有）。两个条件任一不满足，整个循环直接 return。
- **扫描 SQL（`ClickLogMapper.java:14-18`）**：

```java
    @Select("SELECT l.* FROM lf_click_log l "
            + "LEFT JOIN lf_consumed_event c ON c.event_id = CONCAT('log-', l.id) "
            + "WHERE c.event_id IS NULL AND l.event_id IS NULL "
            + "ORDER BY l.id LIMIT #{limit}")
    List<ClickLog> selectUnconsumed(@Param("limit") int limit);
```

两个排除条件各挡一路重复：

| 条件 | 挡住谁 | 依据 |
|----|----|----|
| `c.event_id IS NULL` | 已被任何通道消费过的行：live 消费与上轮对账都写过 `log-{id}` 的消费记录 | 反连接：消费记录存在则 join 不上 |
| `l.event_id IS NULL` | 消费者**回填**的明细行 | 回填行自带 UUID eventId 标记（`ClickLog.java:26-27`、`V2__outbox.sql:18-20` 注释），它们对应的消费记录是 UUID 而不是 `log-{id}`，第一个条件挡不住，靠这行补刀 |

`ORDER BY l.id LIMIT 500`（调用点 `ClickLogReconciler.java:37`）保证按主键序、每轮最多 500 行，故障积压时多轮追平。

- **确定性 eventId 与消费端去重的配合（:40-43）**：重放构造的 ClickEvent 用 `"log-" + row.getId()`，与热路径 outbox 事件用的 eventId **算法相同**。于是四种情形都收敛到恰好计一次：live 已消费则 dedup 撞主键早退；live 未消费则本轮正常计；本轮计失败行留在扫描结果里下轮再来；重放与 live 并发赛跑则两者只会有一个插进 `lf_consumed_event`（主键即锁）。唯一不可复原的字段是 userId（明细表没存），重放置 null，代价只是 webhook payload 里少一个字段。
- **行级 catch（:44-46）与外层 catch（:51-53）**：单行消费失败（比如这条触发了一个 webhook 侧异常）只 warn 不中断，后面的行继续，下一轮 30 秒后失败的行还在扫描结果里自然重试；外层兜扫描 SQL 本身的失败（比如 MySQL 抖动），整轮放弃等下轮。双层 catch 的含义：对账循环自己永远不该死，也不该把一批坏行变成死循环。

### 5.5 双通道汇合与对账在 fault-drill 中的角色

同一次点击（双开关全开）有两张「存根」：明细行（`lf_click_log`）和 outbox 行（`lf_outbox`）。从存根到聚合表有两条通道：主通道是 Relay 每 2 秒抽 outbox 行 → RabbitMQ → consume（10.2/10.3）；备通道是对账器每 30 秒扫未消费明细行 → 直接 consume。两条通道汇合在 `consume` 的 dedup 锁，谁先到谁计数，后到的早退。这个结构正是 `scripts/mq-drill.sh` 三幕剧本的依据（脚本头注释 `mq-drill.sh:2-6`）：

1. **基线幕（:40-46）**：MQ 健康时 N 次跳转经主通道收敛进 stats，脚本轮询 totalClicks 直到等于 N。
2. **故障幕（:48-56）**：`docker compose stop rabbitmq` 后再跳 M 次，断言全部仍然 302（11.1 课的 fail-open 与同事务双写保证了这一点），outbox 行留在表里退避，明细行也在。
3. **恢复幕（:58-74）**：重启 MQ 等健康后，断言 stats 收敛到 N+M。收尾日志的措辞是 "reconcile caught up"——恢复期 Relay 抽走积压 outbox 行、对账器补掉任何 Relay 最终没送成的行，两条通道共同把账追平。

### 5.6 一处口径对照：OutboxMapper 的 mark 语句没有 status 守卫

| | OutboxMapper（outbox 抽水，`stats/mapper/`） | WebhookDeliveryMapper（webhook 投递，`webhook/mapper/`） |
|----|----|---|
| 读侧扫描 | `WHERE status='PENDING' AND (next_attempt_at IS NULL OR <= NOW())`（:14-17） | 同款守卫（:23-25） |
| markSent | `... WHERE id = #{id}`（:19-20） | `... WHERE id = #{id} AND status = 'PENDING'`（:27-29） |
| markRetry | `... WHERE id = #{id}`（:25-27） | 同款带守卫（:31-35） |
| markFailed | `... WHERE id = #{id}`（:22-23） | 同款带守卫（:37-39） |

事实（实测源码）：OutboxMapper 的三条 mark **都没有** `WHERE status='PENDING'` 守卫，状态过滤只存在于读侧扫描；WebhookDeliveryMapper 的三条 mark 全带。后果评估（推断）：mark 只被 Relay 的单轮循环调用，行刚被读侧过滤为 PENDING，正常单实例下守卫不改变行为；双实例竞争时两条通道可能各 publish 一次（重复消息），但那靠消费端幂等消化，status 守卫本来也挡不住「都读到了 PENDING 才 mark」的窗口。webhook 侧带守卫是多一层防御（deliberate，`WebhookDeliveryMapper.java:27-29` 的 markSent 还顺带把 attempts+1 收进同一原子语句）。教材口径按实测记录，模块 10 的简报曾在这一点上写反，已由 10.2 课交付时纠正。

## 6. 技术原理补充

**反连接找未消费**。是什么：LEFT JOIN + IS NULL 找「左表有、右表没有」的行。为什么需要：没有它就要逐行 SELECT 消费记录（N+1）或维护内存集合。不用会怎样：对账只能全表重放，靠 dedup 早退兜住，浪费但正确；反连接把重放范围缩到真正的缺口。本项目怎么用：join 键是表达式 `CONCAT('log-', l.id)`，因为消费记录里存的是 eventId 不是外键。缺点：join 键上无法直接走索引（函数表达式），明细表大时这是全扫；500 行限量与 30 秒节奏把它控制在可接受范围（推断：作者用 LIMIT 而非索引优化，是拿「积压量有限」换实现简单）。

**COUNT(DISTINCT) 的精确与分页 total 的代价**。UV 用精确去重（不走近似数据结构），代价是窗口内扫描，90 天钳制（`:37`）就是给这个代价封顶；若是「全站亿级」的 UV 需求就该换 HyperLogLog，单链接单租户的规模下精确值更对。`selectPage` 会先发一条 COUNT 拿 total 再发 LIMIT 查询；明细只增不改，COUNT 走 `idx_click_link_time` 前缀索引代价可控，表更大时可换游标分页（本项目未做）。调度面（14.3 课的全景）里 Relay 2 秒、对账 30 秒共享调度线程池，对账器把单轮上限压在 500 行正是为了不占住调度线程。

## 7. 可视化

- [08-scheduled-tasks](../../diagrams/archify/html/08-scheduled-tasks.html)：看 ClickLogReconciler 的 30s/15s 节点与 ClickOutboxRelay 的 2s/3s 节点并列，两个搬运工共享调度线程池。
- [14-click-pipeline-dataflow](../../diagrams/archify/html/14-click-pipeline-dataflow.html)：看「consume」节点同时接 live 与 reconcile 两条入边，dedup 锁是汇合点。（该图由并行任务产出，若尚未就位，先用下方 Mermaid 备用图。）
- Mermaid 备用：[click-pipeline](../../diagrams/mermaid/click-pipeline.md)。[05-database-erd](../../diagrams/archify/html/05-database-erd.html)：核对 stats 查询涉及的四张表与索引。

阅读顺序：先 08 对上「谁在什么时候跑」，再 14 看双通道汇合，最后 05 对表。节点对应：「stats API」节点 = `StatsController` 三端点；「reconcile」节点 = `ClickLogReconciler.reconcile`；「dedup 锁」节点 = `lf_consumed_event` 主键插入。

## 8. 项目中的真实执行过程

场景 A：管理员打开看板，请求 `GET /api/links/Aa1Bb2/stats?days=30`。

1. JWT 过滤器验签，UserContext 就位（5.2 课）；`detail("Aa1Bb2")` 行存在且 createdBy 是当前用户，通过，越权则 404（5.3）。
2. window = 30；`sumClicks` 聚合表 SUM 一次点查；`countDistinctIps` 明细表 30 天窗口 COUNT DISTINCT（idx_click_link_time 前缀）。
3. `selectRange` + `selectDailyUv` 各一条范围查询，30 天逐日补零循环，`LinkStatsResponse` 返回：total（全历史聚合）、totalUv（30 天精确去重）、daily 数组恒 30 项。

场景 B：MQ 停机 10 分钟内发生了 40 次点击，MQ 恢复。

1. 故障期：每次点击照常 302，明细与 outbox 各积 40 行（11.1）；Relay 每 2 秒醒来发一轮回退退避（10.2）。对账器已经在干活：每 30 秒扫出未消费明细行直接 consume，聚合表在 MQ 恢复**之前**就开始增长。
2. MQ 恢复：Relay 把积压 outbox 行发出去，`consume` 里 dedup 撞主键的早退（live 或对账已计），不会双计；Relay 没送成的行（30 次重试耗尽标 FAILED）由对账器继续每轮扫，直到全部消费。
3. 脚本口径：`totalClicks` 收敛到 N+M，`mq-drill.sh:74` 打 PASS。

## 9. 为什么这样设计

- **为什么 totalClicks 与 totalUv 来源不同表**（结合 `ClickStatsService.java:122-125` 注释推断其含义）：点击数来自异步聚合（经 MQ，扛写压），UV 需要精确去重，聚合表存不了「窗口内 IP 集合」，只能扫明细。注释原文说明 UV "available while the D7 double write is on"——sync 关掉时明细行改由消费者回填（`:79-93`），UV 退化为最终一致但仍可用；sync-only（async 关）时反过来，UV 与明细正常而 totalClicks 恒 0（11.1 的矩阵在读侧重现）。
- **为什么对账器复用 consume 而不另写一条聚合 SQL、更不直接 UPDATE 统计表**：consume 里聚合、维度、webhook 入队已被同一个事务和 dedup 锁焊成一个原子单元，另写 SQL 等于把幂等逻辑复制一份，两处将来必然漂移；直接 UPDATE 统计表则绕过 dedup、维度桶和 webhook 投递的一致性。安全网的正确姿势是重放主路径：构造同 eventId 的事件 + 调同一扇门。
- **为什么门禁要求双开关全开**：这是扫描 SQL 的适用性边界而非保险。sync 关了扫描对象不存在；async 关了 consume 的聚合结果没有意义（11.1 矩阵第二行）。把边界写进门禁，避免「跑空轮询」与「写无人读的数据」两种浪费。

## 10. 本节总结

- 三端点先做 detail 归属校验（404 语义），再进 Service；stats 四个数来自两张表，缺日补零保证 daily 数组长度恒等于窗口。
- 参数处理两分法：区间参数钳制回退默认（days 1..90、size 1..100），枚举参数白名单 400（dim）。UV = 明细表精确 COUNT(DISTINCT ip)，依赖 sync 双写开着；关掉后由回填补，最终一致。
- 对账器每 30 秒反连接扫未消费明细行，确定性 eventId `log-{id}` 让重放与 live 在 dedup 锁下互斥，行级 catch 保证单行失败不拖垮整批。
- 明细行与 outbox 行是同一点击的两个存根，Relay 与对账是两条消费通道，mq-drill 三幕剧本就是这三层结构的验收。

## 11. 课后练习

1.（概念理解）`/stats` 返回的 totalClicks 在 sync-only 部署下恒为 0，但 totalUv 有值。两个数字分别读哪张表？为什么这一组合下会出现这种割裂？
   <br>答案：totalClicks 读 `lf_click_daily`（`ClickDailyMapper.java:24-25`），totalUv 读 `lf_click_log`（`ClickLogMapper.java:20-24`）。sync-only 时明细实时写入（UV 有数据），但聚合表唯一写入者在 consume（消费路径），async 关着就没有消费者，聚合表永远空。

2.（源码定位）在 selectUnconsumed 的 SQL 里指出「排除消费者回填行」的条件，并解释为什么第一个条件（消费记录 join 不上）挡不住回填行。
   <br>答案：条件是 `l.event_id IS NULL`（`ClickLogMapper.java:16`）。回填行（async-only 模式下消费者插入的明细）的 event_id 列存的是 UUID eventId（`ClickStatsService.java:91`），它对应的消费记录主键也是这个 UUID，与扫描 join 用的 `CONCAT('log-', l.id)` 永远不相等，第一个条件的 LEFT JOIN 对它照样 join 不上，所以必须用行内标记直接排除。

3.（执行过程）一次点击的 outbox 行重试 30 次后被标 FAILED，同一次点击的明细行还在。这行点击最终进聚合表吗？经过哪条路径？
   <br>答案：进。FAILED 是 outbox 侧的终态（10.2 课），不影响明细行。对账器每 30 秒的扫描按明细行与消费记录反连接，`log-{id}` 事件从未被消费（FAILED 意味着没发出去过），行被扫出后直接 consume，聚合在备通道完成。若 outbox 行被人工 UPDATE 回 PENDING 由 Relay 重发成功，live 通道先到也一样，dedup 保证只计一次。

4.（调用链）days=91、days=0、dim=geo 三个请求各返回什么？写出每个值的产生代码行。
   <br>答案：days=91 钳到 90（`StatsController.java:37` 的 Math.min）；days=0 回退 7（同行的 `days < 1 ? 7`）；dim=geo 抛 `BizException(DIM_REQUIRED)`（`:49-51`），GlobalExceptionHandler 映射为 400（`GlobalExceptionHandler.java:27-30`）。前两者是区间参数钳制，后者是枚举白名单拒绝。

5.（设计分析）假设明细表涨到十亿行，selectUnconsumed 的反连接会出现什么问题？给出一个不改变「重放主路径」原则的改造方向。
   <br>答案：join 键 `CONCAT('log-', l.id)` 是函数表达式，`lf_consumed_event.event_id` 上的主键索引帮不上这种不等值前缀匹配，扫描成本随明细表膨胀；未消费的行又永远排在 id 低位之外时，每轮 500 行都在无效扫。改造方向（保持重放原则）：给 `lf_click_log` 增加「已消费」游标或按 id 分片的低水位标记（利用 ORDER BY l.id 的单调性），让扫描从上轮断点开始；或把 join 改为对候选窗口（最近 N 天）内的行做 EXISTS。dedup 锁仍兜正确性，改动只在扫描范围。

## 12. 与后续课程的联系

- 模块 14（未出）会把 Relay 与对账器放进全部 @Scheduled 任务地图与调度线程池的全景，`BacklogGauges` 采集 outbox 积压深度也依赖本课的表；模块 15（未出）的 15.3 课展开 compose 演练脚本族，mq-drill 是其中之一。
- 13.2 课（未出）讲 `recordClickDelivery`，即 consume 事务末尾那次 webhook 入队；本课 5.4 提到的「重放置 null 的 userId」会出现在它的 payload 里。
- 复习推荐：10.3 课（consume 幂等）、7.1 课（302 判定链）、5.3 课（404 越权语义）。
