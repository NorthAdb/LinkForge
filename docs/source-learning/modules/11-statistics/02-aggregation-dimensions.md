# 11.2 聚合与维度：lf_click_daily upsert、四桶规则与纯函数分桶

> 本课回答一个问题：一条点击事件进到消费者手里之后，怎么变成「总点击数」和「设备/来源/系统/浏览器」这些看板数字？本课拆两个东西：`lf_click_daily`/`lf_click_dim_daily` 两张聚合表的计数 upsert，以及 `ClickDimensions` 这个 136 行纯函数类的分桶规则。幂等机制本身（dedup 表、回填、DLQ）是 10.3 课的内容，本课只讲「去重通过之后写什么」。

## 1. 本节学习目标

学完本课，你应该能：

1. 逐行解释两条 `incrementClick` 的 upsert SQL，说清「计数场景」为什么可以用 `ON DUPLICATE KEY UPDATE`，而决策 #11 为什么否决了用它做「重复探测」。
2. 背出 `ClickDimensions` 四个分桶函数的优先级链，解释 device 有 bot 桶而 os 没有、browser 为什么必须先判 WeChat/Edge 再判 Chrome。
3. 解释「维度只在消费路径解析」（D-A8-2）给热路径省了什么，以及 variant 桶「仅当非空才写」避免的空桶。
4. 把 `ClickDimensionsTest` 与 `ClickDimensionsOsBrowserTest` 的 13 个用例对到具体规则上。

## 2. 前置知识

- **ON DUPLICATE KEY UPDATE**（MySQL 语法）。INSERT 遇到唯一键冲突时不报错，转成对旧行的 UPDATE。一条 SQL 完成「不存在则插入，存在则累加」，不需要先查后写。
- **唯一索引作聚合表的主心骨**（模块 4 知识）。`lf_click_daily` 的 `uk_daily_link_date(link_id, stat_date)`（`V1__baseline_schema.sql:66`）保证每链每天一行；`lf_click_dim_daily` 的 `uk_dim_link_date_value(link_id, stat_date, dim_type, dim_value)`（`V3__click_dim_daily.sql:15`）保证每链每天每维度每桶值一行。upsert 的「哪行是同一行」完全由这个键定义。
- **纯函数**（编程概念）。输出只由输入决定、不碰网络与数据库、不抛异常的函数。`ClickDimensions` 的输入是两个字符串，输出是桶值字符串。**record 类型**（Java 17 语言点）：`ClickDimensions.Dimension` 是个 record（`ClickDimensions.java:134-135`），type/value 两字段紧凑表示「一行维度写入」。决策编号出处是 `docs/progress.md` 的决策表，本课引用 #11 与 D-A8 系列。
- **User-Agent 的现实**（HTTP 常识）。UA 是自由文本，垃圾丛生：Android UA 自称 Linux、iPhone UA 自称 Mac OS X、微信内置浏览器自称 Chrome、Chromium 家族尾部都挂着 Safari。分桶规则的本质是**在有序的 if 链里编码这些冒充关系**。

## 3. 本节涉及的源码

| 文件 | 行数 | 本课关注点 | 阅读顺序 |
|----|----|----|----|
| `src/main/java/com/linkforge/stats/service/ClickStatsService.java` | 154 | consume 聚合段 :66-113 | 1 |
| `src/main/java/com/linkforge/stats/mapper/ClickDailyMapper.java` | 32 | incrementClick :16-22 | 2 |
| `src/main/java/com/linkforge/stats/mapper/ClickDimDailyMapper.java` | 40 | 注释 :17-22、incrementClick :23-30 | 3 |
| `src/main/java/com/linkforge/stats/support/ClickDimensions.java` | 136 | 全文，逐函数 | 4 |
| `src/main/java/com/linkforge/stats/entity/ClickDaily.java` / `ClickDimDaily.java` | 24/25 | 两张聚合表的实体 | 5 |
| `src/main/resources/db/migration/V1__baseline_schema.sql` / `V3__click_dim_daily.sql` | 73/17 | lf_click_daily :57-68；维度表全表 | 6 |
| `src/test/java/com/linkforge/stats/support/ClickDimensionsTest.java` | 72 | 7 个用例 | 8 |
| `src/test/java/com/linkforge/stats/support/ClickDimensionsOsBrowserTest.java` | 99 | 6 个用例 | 9 |

阅读顺序的理由：先看写入动作发生在哪个事务里（consume 聚合段），再看两条 upsert SQL 与表结构，然后进入本课主角 ClickDimensions 逐函数读，最后用测试把规则钉死。

## 4. 类的定位

- `ClickStatsService` 是消费侧的枢纽。10.3 课讲了它的幂等半边（dedupTx、回填、DLQ），本课讲 `:94-105` 的聚合半边：一次去重通过的点击写 1 行 daily 计数、4 行维度计数，外加可选的 1 行 variant 计数，全部在**同一个 dedup 事务**里。
- `ClickDimensions` 在 `support` 包，是**纯字符串规则**的静态工具类。类注释（`:7-11`）自述设计目标："Pure string rules on purpose: no parsing library, no network, never throws"。两个调用方：`ClickStatsService.java:97`（统计分桶）和 `WebhookService.java:260`（webhook payload 复用同一套桶，13.2 课）。
- 与 10.3 课的分工界线：10.3 回答「同一条事件来两次怎么办」（`lf_consumed_event` 主键锁 + DuplicateKey 早退）；本课回答「第一次来的时候到底写了哪些行」。两句注释连读：`ClickStatsService.java:95-96` 说维度桶共享 dedup 事务所以一个 eventId 不可能双计一个桶，`ClickDimDailyMapper.java:17-22` 说重复投递根本到不了这条 SQL 两次。`ClickDailyMapper`/`ClickDimDailyMapper` 是这两张表的全部门面，读 SQL 留给 11.3 课。

## 5. 核心源码详解

### 5.1 consume 的聚合段：一个事务里的六次写入

10.3 课拆过 `consume` 的骨架，本课聚焦 `:94-105`：

```java
            clickDailyMapper.incrementClick(event.getTenantId(), event.getLinkId(), event.getCode(), date);
            // A8: dimension buckets share the dedup transaction, so one eventId can
            // never double count a bucket; parsing never throws (see ClickDimensions)
            for (ClickDimensions.Dimension dim : ClickDimensions.of(event.getUserAgent(), event.getReferer())) {
                clickDimDailyMapper.incrementClick(event.getTenantId(), event.getLinkId(),
                        date, dim.type(), dim.value());
            }
            ... // variant 非空才写：C3 (D-C3-11) if 块 :102-105
```

（`ClickStatsService.java:94-105`，逐字对照源文件，variant 块收进省略号。）

- `:94` 总点击数 +1。`date` 在 `:67-69` 算好：`clickedAt.toLocalDate()`，clickedAt 为 null 时防御性地用当天（事件来自 MQ 反序列化，正常不为 null）。
- `:97-100` 四行维度。`ClickDimensions.of(ua, referer)` 固定返回 4 个 `Dimension`（device、referer_host、os、browser），循环逐个 upsert。日期口径与 daily 完全一致，都是事件里的 `date`。
- `:102-105` variant 桶是第五种 dim_type，但**只有抽中过分桶的点击才写**（D-C3-11 注释 "no variant, no row"）。没有 variants 的链接不产生 `dim_type=variant` 的行，breakdown 查询也就不会出现空桶。
- 这三段与 `:70-78` 的 dedup 插入、`:110` 的 webhook 入队共享 `dedupTx.execute` 的一个事务（`:70-112`）：这六次写入要么全发生要么全不发生，不存在「daily 加了、维度没加」的中间态。

### 5.2 两条计数 upsert 与决策 #11 的对照

```java
    @Insert("INSERT INTO lf_click_daily(tenant_id, link_id, code, stat_date, clicks) "
            + "VALUES(#{tenantId}, #{linkId}, #{code}, #{statDate}, 1) "
            + "ON DUPLICATE KEY UPDATE clicks = clicks + 1")
    int incrementClick(@Param("tenantId") Long tenantId, ... );
```

（`ClickDailyMapper.java:16-22`，参数列表节选。`ClickDimDailyMapper.java:23-30` 的维度版只多了 dim_type/dim_value 两列，SQL 骨架相同。）

决策 #11（`docs/progress.md:56`）否决了 `ON DUPLICATE KEY UPDATE` 的**另一种用法**：拿「影响行数」当重复信号。原话是「幂等键用独立事务 INSERT + 捕获 DuplicateKeyException，弃用 ON DUPLICATE KEY UPDATE 探测」，原因是实测 MySQL Connector/J 默认返回 matched rows，重复键探测永远把重放当新事件。两者不矛盾，区别在**你要不要看返回值**：

| | 探测场景（决策 #11 否决） | 计数场景（本项目采用） |
|----|----|----|
| 用法与返回值 | 靠返回行数判断「是新插入还是重复」，把它当信号；Connector/J matched rows 语义下不可靠 | `clicks = clicks + 1`，**根本不看** int 返回值 |
| 重复会发生什么 | 误判为新事件，幂等失效 | 同一 eventId 根本到不了第二条 SQL（dedup 早退），SQL 只负责原子累加 |

`ClickDimDailyMapper.java:17-22` 的注释把这个对照写在了 SQL 旁边："Counter upsert... the +1 is safe; ON DUPLICATE KEY UPDATE is the right tool for a counter, unlike the duplicate-DETECTION case rejected in decision #11."。同一份语法，两种用途，一弃一用，判断标准是返回值是否承担语义。另注意 `uk_daily_link_date` 唯一键（`V1__baseline_schema.sql:66`）在这里不是「兜底」而是**定义**——它定义了「同链同天是一行」，upsert 才有明确的冲突目标。

### 5.3 ClickDimensions：四个函数一条优先级链

入口 `of`（`ClickDimensions.java:31-37`）固定返回四个桶，顺序即 device、referer_host、os、browser。

**device（:39-57）**，优先级链最长（desktop 分支收进省略号）：

```java
    public static String device(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "unknown";
        }
        String ua = userAgent.toLowerCase();
        if (BOT_TOKENS.stream().anyMatch(ua::contains)) {
            return "bot";
        }
        if (ua.contains("ipad") || (ua.contains("android") && !ua.contains("mobile"))) {
            return "tablet";
        }
        if (ua.contains("iphone") || ua.contains("ipod") || ua.contains("mobile")) {
            return "mobile";
        }
        ... // desktop :53-55（windows/macintosh/x11/linux）
        return "unknown";
    }
```

- `:40-42` 空输入直接 unknown，不往下走。`:56` 兜底 unknown，垃圾 UA 有家可归，函数永不抛。
- `:44-46` bot 最优先。`BOT_TOKENS`（`:21-24`）14 个词，含 curl、wget、python-requests、okhttp、java/、headlesschrome 这些「脚本工具型 bot」。类注释（`:9-10`）给出排序理由："bot beats mobile-tablet heuristics (crawlers often claim mobile)"——Googlebot-Mobile 的 UA 同时含 android 和 mobile，先判 bot 才不会误进 mobile 桶。
- `:47-49` tablet 的判定是「iPad，或 Android 且不含 mobile」。安卓平板的 UA 不带 `Mobile` 后缀，安卓手机带，这是 UA 规范里的历史约定。`:50-52` mobile：iphone/ipod/含 mobile。`:53-55` desktop：windows/macintosh/x11/linux。

**os（:63-84）**，注意它**没有 bot 桶**（`:59-62` 注释自答了两个为什么）：

```java
    /**
     * No bot bucket: bots still resolve an os from their claimed UA. Android UAs
     * claim Linux and iPhone UAs claim Mac OS X, so those checks must come first.
     */
    public static String os(String userAgent) {
        ...
        if (ua.contains("iphone") || ua.contains("ipod") || ua.contains("ipad")) {
            return "ios";
        }
        if (ua.contains("android")) {
            return "android";
        }
        ... // windows :74-76、macos :77-79（mac os x / macintosh）
        if (ua.contains("linux") || ua.contains("x11")) {
            return "linux";
        }
        return "other";
    }
```

（`ClickDimensions.java:63-84` 节选，省略号处是 :64-67 的空输入判断，同 device。）

为什么没有 bot 桶：bot 也自称某个系统，爬虫的 os 分布照样有统计意义，大可不必折叠。为什么 ios/android 必须排在 linux/macos 前面：Android UA 里有 "Linux"、iPhone UA 里有 "Mac OS X"，顺序反了所有手机都会进 linux/macos。兜底是 other 不是 unknown，:80 的 x11 在 device 里算桌面、在 os 里算 linux 的别名。

**browser（:86-111）**，注释把顺序抬到契约高度："Order is the contract: WeChat and Edge embed Chrome/, and Safari/ trails every Chromium UA."。链条编码了三个冒充关系：微信内置浏览器内核里带 `Chrome/`，所以 micromessenger 必须先判（:95-97）；Edge 的 UA 里带 `Edg/` **和** `Chrome/`（Edge 是 Chromium 内核），所以 edg/ 先于 chrome/（:98-100）；每个 Chromium 家族 UA 尾部都挂 `Safari/` 兼容标记，所以 safari/ 只能垫底（:107-109）。chrome/ 分支顺带认 crios（:101-103），firefox/ 分支顺带认 fxios（:104-106），这两个是 iOS 版 Chrome/Firefox 的 token（iOS 强制所有浏览器用 WebKit，UA 长得像 Safari，必须靠它们认出真身）。bot 判断（:92-94）与 device 共用同一组 token。兜底 other（:110）。

**referer_host（:114-132）**，三个 direct 出口与归一化主体：

```java
    /** Normalized referer host: lowercase, "www." stripped, missing/broken -> "direct". */
    public static String refererHost(String referer) {
        if (referer == null || referer.isBlank()) {
            return "direct";
        }
        try {
            host = URI.create(referer.trim()).getHost();
        } catch (IllegalArgumentException ex) {
            return "direct";              // 解析炸（如 javascript:void(0)）
        }
        if (host == null || host.isBlank()) {
            return "direct";              // 不是 URL，取不出 host
        }
        ... // 小写 :127、剥 "www." :128-130、截 128 :131
    }
```

归一化三步：小写、剥 `www.`、截断 128（`MAX_VALUE_LENGTH` 在 `:25`，与 dim_value 列宽一致）。三个出口都归 direct：空、解析炸（`IllegalArgumentException`，比如 `javascript:void(0)`）、解析成功但取不出 host（非 URL 字符串，getHost() 为 null）。`sub.example.com` 保留子域，只有 `www.` 被剥。

### 5.4 维度只在消费路径解析（D-A8-2）

决策 D-A8-2（`docs/progress.md:578`）：「解析只在消费成功路径（consume 幂等事务内）；热路径 recordClick 零改动，理由是 302 吞吐不受维度影响」。落到代码上可验证：`ClickRecordService` 的 import 列表里没有 ClickDimensions，91 行里没有一次字符串分桶。解析的时机与成本全部后移到消费侧（MQ 消费线程），且被去重锁保护着只跑一次。

配套口径是 D-A8-7「历史明细不回填」（`docs/progress.md:583`）：维度表启用之前的存量点击不重算，新点击自消费起进维度表。为什么不回填（推断）：重算要把历史明细逐行重放解析，而分桶规则会演进（os/browser 就是后来 C2 波次加的），重算一次新旧口径就混染一次；明细表保留原始 UA/Referer，真要重算随时能做，先把口径守住。这不与对账冲突：`ClickLogReconciler` 重放的是「尚未消费」的新行（11.3 课），不是历史存量。

### 5.5 两个测试类与规则的对应

13 例全是纯函数单测（surefire，免 Docker），改任何一个 if 的顺序都至少炸一条。规则的可信度不靠注释，靠这里：

| 测试类 | 用例（行） | 钉住的规则 |
|----|----|----|
| ClickDimensionsTest | deviceBucketsByUserAgent（:10-18） | mobile/tablet/desktop 三桶的代表性 UA（含「Android+Mobile=mobile、无 Mobile=tablet」） |
| ClickDimensionsTest | botBeatsMobileHeuristics（:21-27） | bot 优先于一切：Googlebot-Mobile、curl、python-requests、Java、HeadlessChrome 全进 bot |
| ClickDimensionsTest | missingOrGarbageUserAgentFallsBackToUnknown（:30-35） | null、空白、控制字符、陌生素 UA 都是 unknown，且不抛 |
| ClickDimensionsTest | refererNormalizesHostCaseAndWww（:38-42） | `Www.Example.COM` 归一为 example.com，子域保留 |
| ClickDimensionsTest | refererMissingOrBrokenIsDirect（:45-50） | null、空、`not a url`、`javascript:void(0)` 全是 direct |
| ClickDimensionsTest | overlongHostIsTruncated（:53-57） | 200 字符 host 截到 128 |
| ClickDimensionsTest | ofProducesAllFourDimensions（:60-71） | of 固定 4 行且顺序固定；curl 的 os 是 other（无 bot 桶的直接证据） |
| ClickDimensionsOsBrowserTest | windowsEdgeIsEdgeNeverChrome（:24-30） | 真实 Edge UA（Chrome/…Edg/）出 edge 不出 chrome |
| ClickDimensionsOsBrowserTest | wechatInnerBrowserBeatsChrome（:33-39） | MicroMessenger UA 出 wechat，os 照出 android |
| ClickDimensionsOsBrowserTest | botMarkerWinsTheBrowserBucket（:42-47） | Googlebot 出 browser=bot 且 os=other |
| ClickDimensionsOsBrowserTest | chromeCriOSFirefoxAndSafariFollowThePriority（:50-74） | chrome/crios/fxios/safari 各归各；纯 Safari 必须无 Chrome 标记 |
| ClickDimensionsOsBrowserTest | blankAndForeignUserAgentsFallToUnknownOrOtherWithoutThrowing（:77-88） | 空 UA 双桶 unknown；陌生素 UA 双桶 other；全程不抛 |
| ClickDimensionsOsBrowserTest | osAndBrowserRideAlongTheExistingDimensions（:91-98） | os/browser 加入后 of 仍是 4 行，device/referer_host 不受影响 |

## 6. 技术原理补充

**ON DUPLICATE KEY UPDATE 的并发语义**。是什么：INSERT 遇唯一键冲突转 UPDATE，单条 SQL 原子完成。为什么需要：计数场景「先 SELECT 再 INSERT/UPDATE」在并发下会互相踩（两个消费者同时读到 5，各写 6，丢一次点击）。不用会怎样：要靠 SELECT ... FOR UPDATE 或应用层锁保护读改写。本项目怎么用：冲突行被 InnoDB 行锁串行化，加一永不丢；一次事件只碰 6 行，行锁竞争不构成问题。附带收益是单次往返，「行存在与否」由唯一键裁决而非应用判断，决策 #16（`docs/progress.md:61`，短码分配弃先查后插，6.2 课）是同一思想的另一处落地。

**为什么不用 UA 解析库、维度表为何是宽表**（D-A8-6，`docs/progress.md:582`）：yauaa/uap-java 这类库规则全、但依赖重、更新频繁，与项目体量不匹配；自写 14+6 个 token 的确定性规则，桶粗但**可控、可测、永不抛**，深浅桶的选择围绕「看板要趋势不要精度」。同理，`lf_click_dim_daily` 用 (link_id, stat_date, dim_type, dim_value) 四元组一把抓五种维度而不是每种维度一张表：读侧一条 SQL 按 dim_type 切片（11.3 的 breakdown），写入侧一个函数服务五种 dim_type，代价是查询必须带 dim_type 条件（`ClickDimDailyMapper.java:33-35` 正是这么写的）。

## 7. 可视化

- 主图：[14-click-pipeline-dataflow](../../diagrams/archify/html/14-click-pipeline-dataflow.html)。看「consume」节点向 lf_click_daily 与 lf_click_dim_daily 的出边，注意与 lf_consumed_event 的「先锁后写」顺序。（该图由并行任务产出，若尚未就位，先用下方 Mermaid 备用图。）
- Mermaid 备用：[click-pipeline](../../diagrams/mermaid/click-pipeline.md)。[05-database-erd](../../diagrams/archify/html/05-database-erd.html)：看两张聚合表的唯一键与索引。

阅读顺序：先 14（或 Mermaid）看写入时机，再 05 对上表结构与唯一键。节点对应：「consume」节点 = `ClickStatsService.consume`；「分桶」节点 = `ClickDimensions.of`；「daily 累加」「dim 累加」两节点 = 两个 mapper 的 `incrementClick`；「variant（可选）」出边 = `:102-105`。

## 8. 项目中的真实执行过程

场景：访客在微信里点开一条带 A/B 分流的短链，Referer 是 `https://Www.Example.COM/reader?id=1`，抽中 `red` 桶，UA 是微信内置浏览器（Android）。

1. 事件到达 `consume`（经过 10.3 的 dedup 插入成功），`date` 定为点击当天；`lf_click_daily` upsert：该链该天行不存在则插 clicks=1，存在则 +1。
2. `ClickDimensions.of(ua, referer)` 产出 4 桶：device=mobile（含 android 和 mobile，未中 14 个 bot token）；referer_host=example.com（URI 取出 `Www.Example.COM`，小写剥 www）；os=android（iphone/ipod/ipad 没中，android 中，再往下还有 linux 但已 return）；browser=wechat（micromessenger 中，UA 里其实也有 Chrome/ 但轮不到）。
3. 4 次 `lf_click_dim_daily` upsert，四个 (dim_type, dim_value) 各一行；variant 是 `red` 非空，第 5 次 upsert（dim_type=`variant`）。
4. `webhookService.recordClickDelivery` 在同一事务末尾执行（13.2 课），事务提交，六行原子落库。此时 `GET /stats` 的 totalClicks 已 +1；`/stats/breakdown?dim=device` 的 mobile 桶已 +1（读侧 11.3 课）。

同一条事件若因 MQ 重投来了第二次：`:70-78` 的 dedup 插入撞主键，返回 false，六行写入一次都不会执行。这就是「重复投递根本到不了第二条 SQL」的完整含义。

## 9. 为什么这样设计

- **为什么聚合表按天预计算而不是查询时 GROUP BY 明细**：明细表按链接+时间建索引（`V1__baseline_schema.sql:53-54`），按天聚合的查询随数据量线性变贵；预聚合后看板查询是常数成本的点查/短范围扫。代价是两张表和一条只能最终一致的数据链路（要 MQ）。D-A8-1（`docs/progress.md:577`）最初只做 device/referer_host 两维，os/browser 是验收通过后 C2 波次再加的，先闭环再加深。
- **为什么维度桶写进 dedup 事务**（`ClickStatsService.java:95-96` 注释）：维度写在事务外的话，崩溃窗口会造成「总点击 100、设备桶之和 99」的看板裂缝；同事务让所有数字共享同一个原子性边界。
- **为什么 variant 桶仅非空才写、bot 在 browser 里又出现**（D-C3-11，`:101`）：无 variant 的链接若写空串桶，breakdown 会出现无意义的「空桶」Top 条目。bot 在 device 桶是「这不是真人设备」，在 browser 桶是「这次访问不是浏览器行为」，两个看板问题各自需要答案；os 桶不需要，因为爬虫自称的系统仍是有效分布。

## 10. 本节总结

- 一次去重通过的点击在同一个事务里写：1 行 `lf_click_daily` + 4 行维度 + 可选 1 行 variant + 1 条 webhook 投递。
- 计数 upsert 与决策 #11 否决的探测式 upsert 用同一条语法，分界是返回值是否承担语义：计数不看返回值，探测把它当信号。
- ClickDimensions 四桶全部是 if 优先级链，顺序编码 UA 冒充关系：bot 压 mobile，ios/android 压 macos/linux，wechat/edge 压 chrome，safari 垫底。device 与 browser 有 bot 桶，os 没有。
- 维度解析只在消费路径（D-A8-2），热路径零改动；历史明细不回填（D-A8-7）。13 个纯函数单测是规则的权威注释。

## 11. 课后练习

1.（概念理解）决策 #11 否决「ON DUPLICATE KEY UPDATE 探测」，但 ClickDailyMapper 的 incrementClick 正是 ON DUPLICATE KEY UPDATE。矛盾吗？
   <br>答案：不矛盾。#11 否决的是把「影响行数」当重复信号：Connector/J 默认返回 matched rows，重复键时返回值无法区分新插入与更新，探测恒假。incrementClick 是计数场景，返回值被丢弃，正确性靠上游 `lf_consumed_event` 主键去重保证同一 eventId 只执行一次，SQL 只负责原子加一。同一语法，看返回值的是探测（弃），不看的是计数（用）。参考 `ClickDimDailyMapper.java:17-22` 注释。

2.（源码定位）iPhone Safari 的 UA 里同时有 "iPhone" 和 "Mac OS X"。分别在 os() 和 device() 里走一遍它的判定路径。
   <br>答案：os()：iphone 命中第一支（:68-70），返回 ios，Mac OS X 分支（:77-79）永远轮不到，所以 ios 排在 macos 前（注释 :60-62）。device()：BOT_TOKENS 不中，ipad/android 条件不中，iphone 命中 mobile（:50-51），返回 mobile，Macintosh 分支（:53）轮不到。

3.（执行过程）一条 UA 为 `curl/8.4.0`、无 Referer、无 variant 的点击被 consume，写出它触发的全部 upsert（dim_type 与 dim_value）。
   <br>答案：lf_click_daily +1；device=bot（curl 在 BOT_TOKENS :21-24）；referer_host=direct（空 Referer 走 :115-117）；os=other（os 无 bot 桶，curl 不含任何 os token，落到 :83 的 other）；browser=bot（:92-94）；variant 不写（null，:102 的 if 不进）。共 1 行 daily + 4 行 dim。

4.（设计分析）如果把分桶解析挪进 recordClick，把桶值随 ClickEvent 一起发 MQ，有什么收益与代价？项目为什么没这么做（引用决策编号）？
   <br>答案：收益是消费侧零解析成本、事件自描述。代价是热路径每跳多 4 次字符串扫描，且分桶规则升级后「已发出未消费」的事件带旧口径桶值。项目按 D-A8-2（docs/progress.md:578）把解析锁在消费路径，302 吞吐零受影响；D-A8-6 又把解析做成永不抛的纯函数，消费侧失败风险也不存在。

5.（调用链）分桶规则从 2 维扩到 4 维（C2 波次加 os/browser）时，BreakdownDimValidationTest 之外还需要改哪些地方才能让前端查到 os？列出改动点。
   <br>答案：至少四处：ClickDimensions 加 os()/browser() 两函数并把它们加进 of()（:31-37 从 2 行变 4 行）；StatsController 的 DIM_TYPES 白名单加 TYPE_OS/TYPE_BROWSER（StatsController.java:26-28）否则查询侧 400；DIM_REQUIRED 提示串同步改（:41-42）；ClickDimensionsOsBrowserTest 新增钉优先级（实际落在 :24-98）。表结构不用动：dim_type 是值不是列，四元组唯一键天然容纳新维度类型。

## 12. 与后续课程的联系

- 11.3 课讲读侧：breakdown 端点如何按本课写入的桶出报表，白名单 5 维与 `DIM_TYPES` 的对应；`ClickLogReconciler` 重放时同样走这套分桶，它会从明细行恢复 UA/Referer/variant 三个解析输入。
- 本课的 dim_value 直接进了 webhook 的 PER_CLICK payload（`WebhookService.java:260-264` 复用 ClickDimensions），13.2 课（未出）展开。复习推荐：10.3 课的 consume 幂等骨架、7.3 课的 variant 抽签。
