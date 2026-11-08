# 14.2 业务指标与 Grafana：指标全表、采样解耦的 Gauge 与自动装配的看板

> 上一课解决了「导出端点与安全」。本课回答「导出里有什么、图怎么画」。先给一张按域组织的指标全表，每个名字都落到 `类:行`；再精读 observability 包里唯一的采样类 BacklogGauges，理解「采样与抓取解耦」；最后看 ops/observability/ 怎么让 Prometheus 与 Grafana 免手工配置跑起来，以及看板上两处诚实口径（本进程、Demo 目标）。

## 1. 本节学习目标

1. 把 README 指标参考的每个域逐个对到打点行：说出任意一个 `linkforge.*` 指标在哪个类哪一行 `increment()`。
2. 逐行读懂 BacklogGauges：15 秒采样、不在抓取线程查库、失败保留上次值。
3. 说出 Grafana 自动 provision 的三层文件：datasource（uid）、dashboard provider、看板 JSON，以及 datasource uid 在看板里的角色。
4. 解释看板的两处诚实口径：L1/Bloom 面板是本进程口径，SLO 阈值是 Demo 目标。
5. 说出两个 IT 如何防止「看板与代码漂移」：PrometheusScrapeIT 断言导出内容，DashboardMetricsIT 断言看板引用的指标真实存在。

## 2. 前置知识

- **Counter 与 Gauge（14.1 已铺垫）**：Counter 只增不减，Prometheus 导出时自动加 `_total` 后缀（`linkforge.redirects` 导出为 `linkforge_redirects_total`）；Gauge 是当前值快照，可升可降，名字原样导出。看板查询里 Counter 一律先包 `rate(...)` 变成每秒速率，Gauge 直接画。
- **直方图查询**：`http_server_requests_seconds_bucket{le="..."}` 是累积计数，P99 用 `histogram_quantile(0.99, sum by (le) (rate(...)))` 近似，SLO 达标率用 `le="0.1"` 桶除以总数。桶从哪来见 14.1 的 5.2。
- **Dashboard as code**：Grafana 支持把 datasource 与看板声明成文件，启动时自动导入，这叫 provisioning。看板 JSON 进仓库，代码评审能看图改动。
- **多实例口径**：LinkForge 的设计目标包含多实例部署（Bloom 广播、L1 失效广播都是为此）。进程内组件（L1 缓存、Bloom）的指标每个实例各有一份且互不相同，汇总没有意义；Redis 侧组件（L2、空值缓存）所有实例共享一份。

## 3. 本节涉及的源码

| 文件 | 行数 | 职责 | 阅读顺序 |
|----|----|----|----|
| `src/main/java/com/linkforge/link/service/ShortLinkService.java` | 1025 | 跳转/限流/缓存/门禁/密码的打点主战场 | 1 |
| `src/main/java/com/linkforge/common/ratelimit/RedisRateLimiter.java` | 105 | fail-open 计数（`:102`） | 2 |
| `src/main/java/com/linkforge/user/service/AuthService.java` | 101 | 注册限流拒绝（`:71`） | 3 |
| `src/main/java/com/linkforge/link/support/CodeBloomFilter.java` | 210 | Bloom 全套计数（`:71`、`:76`、`:101`、`:110`、`:141`、`:161`、`:170`） | 4 |
| `src/main/java/com/linkforge/link/support/CacheBus.java` | 85 | Bloom 与 L1 的失效广播计数（`:45-65`） | 5 |
| `src/main/java/com/linkforge/link/support/UrlLocalCache.java` | 96 | L1 hit/miss（`:64-81`） | 6 |
| `src/main/java/com/linkforge/link/support/SingleFlight.java` | 120 | 行读合流计数（`:66-88`） | 7 |
| `src/main/java/com/linkforge/link/support/PhishingPolicy.java` | 165 | 门禁 reload 与 rules gauge（`:78-119`、`:158-164`） | 8 |
| `src/main/java/com/linkforge/security/ApiKeyAuthFilter.java` | 162 | API Key 认证与配额（`:75-129`） | 9 |
| `src/main/java/com/linkforge/webhook/service/WebhookService.java` | 327 | 入队计数（`:271-320`） | 10 |
| `src/main/java/com/linkforge/stats/service/ClickOutboxRelay.java` | 79 | outbox sent/failed（`:50`、`:60`） | 11 |
| `src/main/java/com/linkforge/webhook/service/WebhookDeliveryRelay.java` | 167 | 投递结果计数（`:74-165`） | 12 |
| `src/main/java/com/linkforge/webhook/service/WebhookAggregateFlusher.java` | 237 | 聚合冲刷计数（`:48`、`:106`） | 13 |
| `src/main/java/com/linkforge/observability/BacklogGauges.java` | 58 | 积压 gauge（本课主角） | 14 |
| `ops/observability/prometheus.yml` | 13 | 抓取 job | 15 |
| `ops/observability/grafana/provisioning/datasources/prometheus.yml` | 10 | datasource，uid=linkforge-prom | 16 |
| `ops/observability/grafana/provisioning/dashboards/dashboards.yml` | 10 | 看板 provider | 17 |
| `ops/observability/grafana/dashboards/linkforge-slo.json` | 268 | SLO 看板 24 面板 | 18 |
| `src/test/java/com/linkforge/observability/PrometheusScrapeIT.java` | 157 | 导出内容回归 | 19 |
| `src/test/java/com/linkforge/observability/DashboardMetricsIT.java` | 183 | 看板防漂移回归 | 20 |

## 4. 类的定位

打点的第一原则是「谁的行为谁打点」。这张表里 14 个业务类自己在动作语句旁边 `meterRegistry.counter(...).increment()`，没有指标门面层，没有 AOP。集中式的 observability 包只收两类例外：BacklogGauges 管跨表 COUNT（不属于任何单一业务类），HttpUriTagAllowList 管导出形状（14.1 讲过）。ops/observability/ 是第三层：抓取与展示，纯配置，零 Java。

这三层对应三个读法：读业务流程时顺路看到打点（模块 7、9、13 都已 encounters 过），读 observability 包时只看到跨域采样，读 ops/ 时只看到抓取与看板。Grafana 看板 JSON 与 Java 类之间唯一的耦合是指标名字符串，DashboardMetricsIT 就是把这个耦合变成测试。

## 5. 核心源码详解

### 5.1 指标全表：README 每个域对到打点行

| 域 | 指标与 tag | 打点位置（类） |
|----|----|----|
| 跳转/点击 | `linkforge.links.created` | ShortLinkService |
| | `linkforge.redirects` | ShortLinkService |
| | `linkforge.click.skipped{reason=prefetch\|preview_bot}` | ShortLinkService |
| | `linkforge.click.record.failopen` | ShortLinkService |
| 限流 | `linkforge.ratelimit.rejected{entry=register\|login\|login_account\|refresh\|logout\|accept_invitation\|create\|import\|redirect}` | AuthService、SessionService、TeamService、ShortLinkService（批次 1/2 新增五个 entry） |
| | `linkforge.ratelimit.failopen{reason=error\|null}` | RedisRateLimiter |
| 会话（批次 2/S4） | `linkforge.refresh.reuse_kill` | SessionService（重用升级击杀时） |
| Bloom | `linkforge.bloom.rejected` | ShortLinkService.java:616 |
| | `linkforge.bloom.loaded` / `.loadfailed` | CodeBloomFilter.java:101 / :110 |
| | `linkforge.bloom.publish{result=ok\|error}` / `.subscribe.put` / `.subscribe.malformed` | CacheBus.java:45-47、CodeBloomFilter.java:76 / :71 |
| | `linkforge.bloom.rebuild{result=ok\|busy\|error}` | CodeBloomFilter.java:161 / :141 / :170 |
| singleflight | `linkforge.singleflight.loads` / `.shared` / `.timeout` | SingleFlight.java:66 / :81 / :84,:88 |
| 缓存 | `linkforge.l1.hit` / `.miss` | UrlLocalCache.java:81 |
| | `linkforge.l1.invalidate.publish{result=ok\|error}` / `.remote` / `.malformed` | CacheBus.java:63-65、UrlLocalCache.java:73 / :64 |
| | `linkforge.cache.l2{result=hit\|miss}` | ShortLinkService.java:642 |
| | `linkforge.cache.absent.hit` / `.put` | ShortLinkService.java:624 / :947 |
| | `linkforge.cache.failopen{op=l2_get\|l2_set\|l2_delete\|idem_get\|idem_acquire\|idem_settle\|idem_import_lease\|idem_import_lease_peek\|absent_mark\|absent_clear}` | ShortLinkService（导入租约的两个 op 是 M5 新增） |
| 门禁 | `linkforge.phishing.gate{action=block\|warn,stage=create\|redirect}` | ShortLinkService.java:686、:700、:801、:805 |
| | `linkforge.phishing.continue` | ShortLinkService.java:698 |
| | `linkforge.phishing.reload{result=ok\|unchanged\|failed}` | PhishingPolicy.java:90 / :78 / :119 |
| | gauge `linkforge.phishing.rules{level=block\|warn}` | PhishingPolicy.java:160-163 |
| 密码 | `linkforge.link.unlock{result=ok\|bad_password\|rate_limited}` | ShortLinkService.java:789 / :786 / :771,:778 |
| | `linkforge.link.locked` | ShortLinkService.java:693 |
| API Key | `linkforge.apikey.auth{result=ok\|invalid\|revoked\|ambiguous\|forced_change\|owner_inactive\|db_error}` | ApiKeyAuthFilter（后三个是批次 2a/M7/S4-5 的新结果值） |
| | `linkforge.apikey.quota.rejected{window=minute\|day}` | ApiKeyAuthFilter |
| Webhook | `linkforge.webhook.enqueued` | WebhookService.java:304、:320 |
| | `linkforge.webhook.enqueue.failed{reason=duplicate\|异常类名}` | WebhookService.java:271、:273 |
| | `linkforge.webhook.delivery{result=success\|retry\|failed}` | WebhookDeliveryRelay.java:137 / :165 / :94,:103,:145,:159 |
| | `linkforge.webhook.blocked` | WebhookDeliveryRelay.java:102 |
| | `linkforge.webhook.relay.skipped{reason=master_key_unavailable}` | WebhookDeliveryRelay.java:74 |
| | `linkforge.webhook.aggregate.flush{result=ok\|error}` | WebhookAggregateFlusher（M3 后 partial 消失：drain 全有或全无） |
| 恶意 feed（批次 3b/S3） | `linkforge.malicious.feed.sync{result=ok\|failed\|apply_failed}` | MaliciousHostSyncService |
| | gauge `linkforge.malicious.feed.hosts` / `linkforge.malicious.feed.last_success` | MaliciousHostSnapshot / MaliciousHostSyncService |
| Outbox | `linkforge.outbox.sent` / `.failed` | ClickOutboxRelay.java:60 / :50 |
| 积压 gauge | `linkforge.outbox.pending`、`linkforge.webhook.pending`、`linkforge.webhook.failed` | BacklogGauges.java:35-37 |
| HTTP/JVM | `http.server.requests`（直方图+SLO 桶）、Hikari、JVM 系 | Boot 自带（14.1）；配置在 `application.yml:43-48` |

表里没有的域就是没有打点的域；每行的「打点位置」都已逐个 grep 核对过源码。`linkforge.phishing.reload` 有第四个 result 值 `unchanged`（mtime 未变早退，`PhishingPolicy.java:77-79`），README 的表只写了 ok/failed 两态，这是 README 的简写不是缺陷。

### 5.2 BacklogGauges：采样与抓取解耦

三个积压 gauge 的数据源是三个 SQL COUNT。如果让 Prometheus 抓取时现场执行 COUNT，抓取延迟就耦合了数据库负载，并发抓取还会放大查询次数。BacklogGauges 的类注释（`BacklogGauges.java:15-19`）把这一点写成设计决策：「a COUNT inside a scrape would couple Prometheus latency to the DB and amplify with concurrent scrapes」。

实现分两半。第一半是注册：

```java
@PostConstruct
void register() {
    Gauge.builder("linkforge.outbox.pending", outboxPending, AtomicLong::get).register(meterRegistry);
    Gauge.builder("linkforge.webhook.pending", webhookPending, AtomicLong::get).register(meterRegistry);
    Gauge.builder("linkforge.webhook.failed", webhookFailed, AtomicLong::get).register(meterRegistry);
}
```

（逐字摘自 `BacklogGauges.java:33-38`，字段声明在 `:29-31`，三个 `AtomicLong` 各供一个 gauge。）

Gauge 绑定的是 `AtomicLong` 的 getter。抓取线程渲染导出时读的只是这个内存原子量，纳秒级。第二半是采样：

```java
@Scheduled(fixedDelayString = "${linkforge.metrics.backlog-sample-interval-ms:15000}",
        initialDelayString = "${linkforge.metrics.backlog-sample-interval-ms:15000}")
public void sample() {
    sample(outboxPending, "SELECT COUNT(*) FROM lf_outbox WHERE status = 'PENDING'");
    sample(webhookPending, "SELECT COUNT(*) FROM lf_webhook_delivery WHERE status = 'PENDING'");
    sample(webhookFailed, "SELECT COUNT(*) FROM lf_webhook_delivery WHERE status = 'FAILED'");
}

private void sample(AtomicLong target, String sql) {
    try {
        Long count = jdbc.queryForObject(sql, Long.class);
        if (count != null) {
            target.set(count);
        }
    } catch (Exception ex) {
        log.warn("backlog sample failed: {}", ex.getMessage());
    }
}
```

（逐字摘自 `BacklogGauges.java:40-57`。）

四个值得咀嚼的细节：

1. **节奏可配**：默认 15 秒（`LinkForgeProperties.java:58`），与 Prometheus 的抓取间隔（`ops/observability/prometheus.yml:2` `scrape_interval: 15s`）同量级，采样比抓取稍早或交错，抓到的永远是最近一次采样值。
2. **不在抓取线程查库**：COUNT 跑在共享调度池（14.3）的线程上，抓取线程只读内存。抓取成本从「三次 COUNT」降到「三次 AtomicLong.get」。
3. **失败保留上次值**：catch 里只有 WARN，没有 `target.set(0)`。数据库抖一下，图上是平线不是假零。假零会触发积压清零的假警报；平线配合 WARN 日志是更诚实的降级。异常路径连 `count != null` 守卫都有（`:51`）：queryForObject 空结果返回 null，null 也不覆盖。
4. **gauge 无 tag**：三个名字三个 gauge，不用 `table=outbox` 这种 tag 合一个 gauge。名字直查，tag 又少一组。

### 5.3 Grafana 自动 provision：三层文件

第一层 datasource（`ops/observability/grafana/provisioning/datasources/prometheus.yml`）：

```yaml
apiVersion: 1
datasources:
  - name: Prometheus
    uid: linkforge-prom
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
    editable: false
```

（逐字摘自该文件，全文 9 行。）

uid 是关键：看板 JSON 里的每个面板都用 `uid: linkforge-prom` 引用 datasource。有了固定 uid，看板 JSON 里不会出现「先手工配 datasource 再导出」产生的 `${DS_...}` 悬空引用，导入即用。`DashboardMetricsIT` 在 `:94` 显式断言看板 JSON 不含 `${DS_`。

第二层 provider（`provisioning/dashboards/dashboards.yml`）：告诉 Grafana 每 30 秒扫 `/var/lib/grafana/dashboards` 目录（`:7` `updateIntervalSeconds: 30`），JSON 改了自动热加载。

第三层是看板本体 `linkforge-slo.json`：24 个面板、9 个 row 分组（13 timeseries + 2 stat + 9 row），uid `linkforge-slo`，标题「LinkForge SLO (Demo targets, not a production promise)」。Compose 把两个 provisioning 目录与 dashboards 目录只读挂载进 grafana 容器（`docker-compose.yml:90-91`），`--profile observability up -d` 一条命令起全套。

### 5.4 看板结构速览与两处诚实口径

9 个 row 与代表面板（查询取自 `linkforge-slo.json`）：

| Row | 代表面板与查询 |
|----|----|
| Redirect（SLO demo targets） | QPS：`sum by (status) (rate(http_server_requests_seconds_count{uri="/r/{code}"}[1m]))` |
| | P50/P95/P99：`histogram_quantile(0.5, sum by (le) (rate(http_server_requests_seconds_bucket{uri="/r/{code}"}[5m])))` |
| | SLO stat：`le="0.1"` 桶增速除以总增速；5xx 率：`status=~"5.."` 除以总数 |
| Caches | L1 hit/miss 与 L2 hit/miss 同图；row 标题写明「L1 and Bloom are this process only; L2 and absent are shared Redis」 |
| Singleflight | loads / shared / timeouts 三速率 |
| Rate limiting | fail-open：`sum by (reason) (rate(linkforge_ratelimit_failopen_total[5m]))` |
| Outbox | 积压 gauge 直画 + sent/failed 速率 |
| Webhook | 积压 + delivery 结果 + blocked/skipped |
| API keys | 认证结果速率与配额拒绝 |
| Gates | phishing gate/continue/规则数、unlock 结果与 locked 渲染 |
| JVM / Hikari | Heap / GC pause / Hikari connections |

两处诚实口径都在标题里：

- **本进程口径**：`linkforge_l1_*` 与 `linkforge_bloom_*` 只计本 JVM。单实例 demo 里图就是全貌；多实例时每个实例的曲线不同，row 标题与 README（`:398`「仍 per-process……看板曲线不是集群汇总」）都写明了。Redis 侧的 `linkforge_cache_l2` 与 absent 是共享口径，可汇总。
- **Demo 目标口径**：看板标题与 Redirect row 标题都带「not a production promise」。P99 ≤ 100ms 的 SLO 是项目给演示环境定的目标，README `:529` 同口径。依据是 bench 数字全部是 Windows 历史口径（README `:15` 与 `:632` 两处明确 B10 Linux bench 未跑），把它们标成生产承诺就是虚构。

### 5.5 两个 IT：把「看板与代码不漂移」变成测试

`PrometheusScrapeIT`（`:34-157`）验证导出本身：401/200 三态（`:65-73`）、API Key 403（`:75-85`）、关键指标存在于导出（`:87-105`：redirects、cache_l2、三个 gauge、直方图桶、`uri="/r/{code}"` 模板、`application="linkforge"` 公共 tag）、导出全文不含短码（`:107-120`）、积压 gauge 真的反映行数（`:133-145`：插 3 行 PENDING outbox，Awaitility 等 500ms 采样周期后断言 gauge ≥ 3）。

`DashboardMetricsIT`（`:37-183`）验证看板：解析 `linkforge-slo.json` 里出现的全部 `linkforge_*` 名字，逐一在真实抓取导出里找，找不到就失败。少数指标在测试上下文里根本无法触发（webhook relay 关闭、phishing 名单为空），这批名字列在 `CONTEXT_LIMITED` 常量（`:50-65`）里放行，注释要求每个名字仍可用 grep 源码验证。反向不设限：导出里有但看板没用的指标只打印不失败（`:128-131`，「a metric nobody looks at is not a defect」）。

## 6. 技术原理补充

- **Gauge 的两种用法（框架行为）**：回调式（Gauge.builder 绑定读函数，抓取时调用）与推送式（本项目这种，采样线程 set 原子量，抓取读值）。回调式更省事，但读函数在抓取线程执行，查库的读函数会把本课开头的问题原样带回来。本项目选推送式，正是为了让读函数便宜。
- **Provisioning（工具行为）**：声明式初始化，区别于 UI 手工配置。不用会怎样：换环境要重建 datasource、重导 JSON，看板与代码漂移无人发现。本项目怎么用：三层文件进仓库，uid 固定，IT 防漂移。缺点：JSON 是 Grafana 私有格式，评审可读性一般，所以才有 DashboardMetricsIT 兜底。
- **白盒指标（通用工程）**：应用自己暴露内部状态，区别于黑盒拨测。本项目怎么用：全部指标白盒，每个业务域自己打点。缺点：指标名与代码强耦合，改名即断图，所以名字要有纪律（见 5.1 表）。

## 7. 可视化

- [01-runtime-deployment.html](../../diagrams/archify/html/01-runtime-deployment.html)：Prometheus 与 Grafana 容器、挂载的 `ops/observability/` 文件、抓取路径。对应本课 5.3 的三层文件与 14.1 的抓取流程。
- [00-system-architecture.html](../../diagrams/archify/html/00-system-architecture.html)：业务域（跳转/缓存/MQ/Webhook）到 observability 域的数据流：每个域的类打点 → MeterRegistry → 抓取 → 看板。图中各业务域节点就是 5.1 表格的打点类所在。

## 8. 项目中的真实执行过程

一次点击在指标世界的旅程（时间线交错两条线程）：

1. 用户请求 `/r/XyZ1234`，ShortLinkService 的跳转链命中缓存，`linkforge.l1.hit` 在 `UrlLocalCache.java:81` 加一，`linkforge.redirects` 在 `ShortLinkService.java:582` 加一。这两次 increment 是纳秒级的 map 操作，业务线程立即继续。
2. 与此同时（比如第 7 秒），调度线程跑 `BacklogGauges.sample`，三条 COUNT 进 MySQL，结果写进三个 AtomicLong。
3. 第 15 秒，Prometheus 发起抓取。渲染线程把 MeterRegistry 读成文本：counter 值、`linkforge_outbox_pending` 的当前 AtomicLong 值、直方图桶。期间没有一条 SQL。
4. Grafana 用户打开看板，查询由 Grafana 转发给 Prometheus（datasource uid `linkforge-prom`），Prometheus 用自己存的样本算 `rate` 与 `histogram_quantile`，返回面板数据。

异常分支：第 2 步 MySQL 抖动，COUNT 抛异常，WARN 日志，AtomicLong 保持第 -15 秒的值，第 3 步抓到的积压是旧值。看板平线，运维查日志看到 sample failed。

## 9. 为什么这样设计

- **为什么采样 15 秒而不是 1 秒或 60 秒**：与抓取间隔对齐（推断依据是两处默认值同为 15s，`LinkForgeProperties.java:58` 与 `prometheus.yml:2`）。1 秒是给 DB 三条 COUNT 白付 15 倍负载；60 秒让积压峰值最多晚 45 秒可见，对告警太钝。
- **为什么业务打点不集中**：打点靠近动作，tag 变量（reason、result、op、stage）就地取材，不需要把业务事实抄送到中央类。集中式的唯一合理候选是「跨表状态」（积压），于是只有它进了 observability 包。
- **为什么看板进仓库**：SLO 面板的 PromQL 是精心调过的，丢在 UI 里就是不可版本化的资产；进仓库后 DashboardMetricsIT 还能拿它做防漂移测试。
- **为什么 CONTEXT_LIMITED 是放行清单而不是测试跳过**：类注释（`DashboardMetricsIT.java:46-49`）要求每个放行名字「仍可用 grep 源码验证」。测试诚实面对「这个上下文触发不了的功能」，而不是假装全都能触发。

## 10. 本节总结

- 全表 40+ 指标、11 个域，全部落到打点行；打点者在业务类内部，observability 包只收跨表采样。
- BacklogGauges = @PostConstruct 注册三个绑定 AtomicLong 的 gauge + @Scheduled 每 15 秒三条 COUNT；抓取线程零 SQL；失败保留上次值。
- Grafana 三层 provision（datasource uid → provider → 看板 JSON）全部进仓库，`--profile observability` 一键起。
- 两处诚实口径：L1/Bloom 是本进程，SLO 是 Demo 目标。
- 两道 IT：PrometheusScrapeIT 钉导出形状与安全，DashboardMetricsIT 钉看板引用的指标真实存在。

## 11. 课后练习

**1.（概念理解）** 把 `BacklogGauges.sample` 的 catch 改成 `target.set(0)` 再继续，看板上会出现什么错误信号？为什么「平线 + WARN」更好？

**答案**：数据库一次抖动后积压瞬间归零，恢复后跳回，看板出现假「积压清零」与回弹尖峰，任何以 `linkforge_outbox_pending == 0` 为条件的告警误触发。平线表示「数据陈旧」，配合 WARN 可查；告警可以配「样本时间戳过旧」而不是「值归零」。

**2.（源码定位）** 手工 rebuild 已在进行时，再来一次手工 rebuild，哪个指标加一？抛什么异常？在哪几行？

**答案**：`linkforge.bloom.rebuild{result="busy"}` 加一（`CodeBloomFilter.java:141`），抛 `BizException(409, "bloom rebuild already in progress")`（`:143`）。守卫是 `rebuilding.compareAndSet(false, true)`（`:140`）。定时 rebuild 撞 busy 则只计数不抛（`:144-145` 返回 0）。

**3.（执行过程）** 写出「第 3 秒插 3 行 PENDING outbox、采样正常、抓取正常」时序下，第 16 秒抓取样本里 `linkforge_outbox_pending` 的值，并说出该值经过了哪两次写入、哪两次读取。

**答案**：值为 3（假设此前积压为 0）。写入①业务侧 INSERT（这不是 gauge 的写入，gauge 不感知）；gauge 相关写入是②第 15 秒（首次 initialDelay 也是 15s，实际首采样在第 15 秒）调度线程 `target.set(3)`。读取①第 15 秒后任意时刻的抓取渲染线程 `AtomicLong::get`，读取②Prometheus 存储。若抓取发生在第 15 秒采样完成前，值是上次采样（0 或应用启动后的首次值）——所以该 IT 用 Awaitility 等到值出现（`PrometheusScrapeIT.java:139-144`）。

**4.（设计分析）** `DashboardMetricsIT` 为什么不直接断言「看板引用的每个指标都能在导出里找到」，而要加 CONTEXT_LIMITED 放行清单？放行的代价是什么，怎么控制？

**答案**：测试上下文有明确的功能裁剪：webhook relay 关闭、phishing 名单为空、配额很高（类注释 `:46-49`），delivery/blocked/phishing_gate 等计数器在该上下文物理上无法产生时间序列，硬断言必然红。代价是放行清单本身会腐化（真断了没人发现）；控制手段是注释强制每个放行名字可 grep 验证（`:47-48`），加上「反向不设限」保持测试聚焦。

**5.（设计分析）** 多实例部署三副本时，Grafana 上「L1 hit/miss（this process）」面板会画成什么样？哪两个面板不受影响？

**答案**：三副本各自上报 `linkforge_l1_hit_total` 等序列（带 instance 标签），默认查询 `rate(linkforge_l1_hit_total[5m])` 会把三条速率加总画一条线，但这不是「L1 命中率」，只是三条命中率不同且缓存内容不同的进程的速率和，无业务含义。正确做法是按 instance 分面或只看单实例。不受影响的是 Redis 共享口径的两个：`linkforge_cache_l2{result=hit|miss}` 与 absent 命中，任何实例看到的都是同一份 Redis 状态（row 标题「L2 and absent are shared Redis」）。

## 12. 与后续课程的联系

BacklogGauges 的 sample 是 14.3 调度地图 8 个任务之一，那里解释它跑在哪个线程池、为什么池子要 6 个线程。14.1 的令牌与 5.3 的挂载在 15.3 compose 部署时合龙：`LINKFORGE_METRICS_SCRAPE_TOKEN` 与 `ops/observability/scrape-token` 文件必须同值。指标全表是 15.1 测试盘点里两个 observability IT 的断言依据，也是 advanced/13 性能专题读 bench 数字时的坐标系。
