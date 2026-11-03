# 2.2 日志实践、ClientIps 与 HttpUriTagAllowList

## 1. 本节学习目标

1. 会用 Lombok 的 `@Slf4j` 写日志，并从本项目真实代码里归纳出四条日志纪律。
2. 理解 `ClientIps.resolve` 的信任门控：为什么默认不信任 `X-Forwarded-For`，以及它如何被四处调用方共享。
3. 理解 `HttpUriTagAllowList`：指标 uri 标签的白名单塌缩，为什么塌缩方向是安全的。
4. 认识 `CsvFormat` 这个静态工具类范本（深入拆解在模块 06）。

## 2. 前置知识

- 1.2 课的组件扫描与依赖注入。本节两个主角一个在 common/util（纯静态工具），一个在 observability 包（一个 Bean）。
- HTTP 请求头概念：请求头是客户端随请求附带的键值对，服务端可读但不可信。
- 日志级别常识：debug、info、warn、error 严重度递增。

## 3. 本节涉及的源码

| 文件 | 职责 | 阅读顺序 | 为什么读它 |
|------|------|----------|------------|
| `src/main/java/com/linkforge/common/ratelimit/RedisRateLimiter.java`（看 :98-104） | 限流器 fail-open | 第 1 | warn 与指标并用的范本 |
| `src/main/java/com/linkforge/stats/service/ClickOutboxRelay.java`（看 :61-71） | outbox 投递循环 | 第 2 | info 与 warn 的分工范本 |
| `src/main/java/com/linkforge/common/util/ClientIps.java`（24 行） | 客户端 IP 解析 | 第 3 | 本节主角 |
| `src/main/java/com/linkforge/observability/HttpUriTagAllowList.java`（51 行） | 指标 uri 标签白名单 | 第 4 | 本节主角 |
| `src/main/java/com/linkforge/link/support/CsvFormat.java`（18 行） | CSV 转义 | 第 5 | 静态工具类范本 |
| `src/main/resources/application.yml`（看 :241-244） | 日志级别配置 | 第 6 | 级别收口 |

## 4. 类的定位

三样东西都是横切关注点：日志看所有类，IP 解析被限流和点击归因共用，uri 白名单管全部 HTTP 指标。ClientIps 在 common/util，HttpUriTagAllowList 在 observability 包（该包还有 BacklogGauges，模块 14 讲）。主源码里 22 个类贴了 `@Slf4j`（实测 grep 计数），集中在 service、filter、relay 这类「出事要留痕」的层。

## 5. 核心源码详解

### 5.1 @Slf4j：一行注解换来一个 log 字段（Java 语言知识）

```java ignore-check
// Lombok 在编译期为 @Slf4j 类生成下面这行（示意）：
private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RedisRateLimiter.class);
```

之后类里直接写 `log.warn(...)`。（框架行为）SLF4J 是门面接口，具体输出由 classpath 上的实现完成，Spring Boot 默认带 Logback。本项目的日志因此有两个收口：代码统一调 SLF4J，级别统一在 yml 配（5.2 末尾）。

### 5.2 四条日志纪律（全部来自真实代码）

**纪律一：降级 warn 不打全栈，打关键标识。** RedisRateLimiter.java:98-104：

```java
    private RateLimitDecision failOpen(String key, String reason, Exception ex) {
        if (ex != null) {
            log.warn("rate limiter degraded open for {}: {}", key, ex.getMessage());
        }
        meterRegistry.counter("linkforge.ratelimit.failopen", "reason", reason).increment();
        return new RateLimitDecision(true, 0, 0);
    }
```

Redis 挂掉时限流器放行（fail-open），每个失败的键都 warn 一条。注意第二个 `{}` 填的是 `ex.getMessage()` 而不是整个异常对象，日志里只有一行原因没有堆栈。（推断）理由：Redis 故障时每个请求都打全栈，日志被同一堆栈刷爆，一行原因加一个可告警的计数器更够用。计数器 `linkforge.ratelimit.failopen{reason=error}` 让「限流器静默失效」从日志问题升级成可告警的指标问题，日志会滚动消失，指标不会。

**纪律二：批量动作记 info 且只记数量。** ClickOutboxRelay.java:61-71：

```java
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
```

对照着看三层。单条投递失败：warn，带 event 标识与第几次尝试，可定位某一条的反复失败。整轮成功：info，只报数量 "delivered N events"，不逐条流水。整轮崩了：warn 一行。同一个类里 info 与 warn 的边界是「这次结果需不需要人看一眼」。

**纪律三：终态 error 用结构化字段而不是堆栈。** outbox 重试超限进入 FAILED 时（ClickOutboxRelay.java:50-52）记 error，内容是 "outbox event={} exceeded {} attempts, marked FAILED"，字段齐整可检索。真正打全栈的 error 只有一处，即 2.1 课讲过的未知异常兜底（GlobalExceptionHandler.java:75），因为那时你不知道错在哪，必须留现场。

**纪律四：级别在配置收口，框架噪音压到 WARN。** application.yml:241-244：

```yaml
logging:
  level:
    com.linkforge: INFO
    com.baomidou.mybatisplus: WARN
```

业务代码 INFO，MyBatis-Plus 内部压到 WARN，否则它会把每条 SQL 都打出来。测试 profile 更进一步，全项目压到 WARN（application-test.yml:67-69），测试输出只关心断言结果。

### 5.3 ClientIps：带信任门控的 IP 解析（ClientIps.java:5-24）

```java
public final class ClientIps {

    private ClientIps() {
    }

    /**
     * X-Forwarded-For is only honored behind a trusted reverse proxy (trustXff=true);
     * by default clients can spoof that header and bypass per-IP limits, so the
     * socket address wins.
     */
    public static String resolve(HttpServletRequest request, boolean trustXff) {
        if (trustXff) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
```

逐点讲。类是 final 加私有构造（Java 语言知识），典型的静态工具类写法，不注册成 Bean，因为它无状态且每个调用方的信任开关不同。

XFF（X-Forwarded-For）是代理链逐跳追加的请求头：客户端声称的 IP 在最左，每经过一层代理向右追加一个。问题在它的第一个值由客户端随便写。默认 `trustXff=false`（LinkForgeProperties.java:236，yml :204 同为 false），代码走最后一行 `request.getRemoteAddr()`，即 TCP 连接的 socket 地址，客户端伪造不了。只有确认前面有一层可信反代时，运维才把 `LINKFORGE_TRUST_XFF` 打开，这时取 XFF 最左值（`split(",")[0].trim()`）。

（本项目实现）信任开关是参数而不是字段，信任与否由调用方传入。四个调用方全部传同一个开关 `rl.isTrustXff()`：跳转限流（ShortLinkService.java:605）、密码解锁限流（:766）、点击归因（ClickRecordService.java:43）、注册限流（AuthService.java:68）。这是刻意的一致性：如果限流用 socket 地址而点击归因用 XFF，攻击者可以通过伪造 XFF 让点击统计记到假 IP，而限流却挡不住他本人的真实连接。同一个语义（「客户端是谁」）全项目只有一个实现。

看一个调用点（ShortLinkService.java:764-769，密码解锁的按 IP 限流）：

```java
        LinkForgeProperties.LinkAccess cfg = properties.getLinkAccess();
        LinkForgeProperties.RateLimit rl = properties.getRateLimit();
        String ip = ClientIps.resolve(request, rl.isTrustXff());
        RateLimitDecision perIp = rateLimiter.acquire(
                RateLimitKeys.sw(rl.getKeyPrefix(), "unlock:code:" + code + ":ip:" + ip),
                cfg.getUnlockIpMax(), cfg.getUnlockWindowSeconds());
```

解析出的 ip 直接进限流键。若这里可被伪造，暴力破解密码的攻击者每试 5 次换一个假 IP，两层防爆破就失效了。javadoc 里 "bypass per-IP limits" 说的就是这个后果。

### 5.4 HttpUriTagAllowList：指标标签的白名单塌缩（HttpUriTagAllowList.java:19-50）

先理解问题。（框架行为）Spring Boot Actuator 给每个 HTTP 请求记一条 `http.server.requests` 指标，标签之一是 uri。正常情况下 uri 是 controller 模板如 `/r/{code}`。但 MVC 分发之前就失败的请求（连接中断、容器级拒绝）拿不到模板，Micrometer 会记原始路径。短链项目的原始路径是 `/r/AbC123x`，每个短码一个新标签，标签基数无上限，时序数据库会被这种标签拖垮。

解法是一个 Micrometer `MeterFilter` Bean（框架行为：容器里存在 MeterFilter Bean 时，Micrometer 自动配置会应用它）：

```java
    private static final Set<String> ALLOWED = Set.of(
            "/r/{code}", "/r/{code}/unlock",
            "/api/links", "/api/links/{code}", "/api/links/{code}/stats",
            "/api/links/{code}/stats/breakdown", "/api/links/{code}/clicks",
            "/api/links/export",
            // C1 wave B: qr.png and qr-share must not collapse into UNKNOWN (javadoc above)
            "/api/links/{code}/qr.png", "/api/links/{code}/qr-share",
            "/api/auth/login", "/api/auth/register",
            "/api/keys", "/api/keys/{id}",
            "/api/webhooks/endpoint", "/api/webhooks/endpoint/{id}",
            "/api/health",
            "/actuator/health", "/actuator/prometheus", "/actuator/metrics", "/actuator/info");
```

（HttpUriTagAllowList.java:22-33，21 个模板）

```java
    @Bean
    MeterFilter httpUriTagFilter() {
        return new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                if (!"http.server.requests".equals(id.getName())) {
                    return id;
                }
                String uri = id.getTag("uri");
                if (uri == null || ALLOWED.contains(uri)) {
                    return id;
                }
                return id.withTag(Tag.of("uri", "UNKNOWN"));
            }
        };
    }
```

（HttpUriTagAllowList.java:35-50）

过滤逻辑只动 `http.server.requests` 这一个指标。uri 不在白名单就改写成 `UNKNOWN`，所有异常路径的请求塌缩进同一个桶。javadoc（:11-18）给出两个关键判断。其一，塌缩方向是安全的：新增端点忘了登记白名单，它的指标只是并进 UNKNOWN，不会报错也不会炸库，你需要付出的代价只是看板上看不到它的独立曲线。其二，先记 UNKNOWN 再补白名单比反过来（先放行原始路径）好，因为标签基数一旦涨上去就降不回来。白名单本身就是一张「全部对外端点」清单，读它就能速览 API 面。

### 5.5 CsvFormat：静态工具类范本（CsvFormat.java:4-17）

```java
    public static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
```

（CsvFormat.java:9-17，javadoc 自述 RFC4180 风格加「单测无 Spring」）

逻辑三段：空值返回空串；含逗号、双引号或换行才加引号包裹，包裹前把内部双引号翻倍转义；其余原样返回。它与 ClientIps 同一风格：final 类、私有构造、全静态方法、无依赖、纯函数（输入决定输出，不碰任何可变状态），因此不需要成为 Bean。CSV 导入导出的完整流程在模块 06 展开。

## 6. 技术原理补充

**日志级别怎么选**（框架行为加本项目实践）。info 给「系统按预期运转」的里程碑（一轮投递完成 N 条）；warn 给「功能在降级但没死」的状态（Redis 挂了限流放行、单条投递退避重试）；error 给「需要人介入」的终态（重试超限 FAILED、未知异常）。本项目没有 debug 级业务日志，排障靠指标与异常路径的 warn。

**XFF 与多级代理**（框架行为）。多级代理时 XFF 形如 `client, proxy1, proxy2`。本项目只取最左值，等于信任「整条代理链都无害」。（推断）单层反代部署下这是对的；若链上存在不可信代理，最左值仍可被其上游伪造，信任模型要按真实部署核对。这也是该开关默认关闭、交给运维显式打开的原因。

**指标标签基数**（框架行为）。时序数据按「指标名加全部标签值组合」存时间序列。uri 这样的高基数标签若不加约束，序列数随短码数量线性增长，抓取与存储都会劣化。白名单塌缩是把基数钉死在 21 加 1（UNKNOWN）。

**MeterFilter 的挂载方式**（框架行为）。`@Bean` 返回 MeterFilter 即被自动配置拾取，无需注册表引用。这利用了 1.2 课讲的「自动配置兜底」：Micrometer 的自动配置会收集容器里全部 MeterFilter。

## 7. 可视化

- [跳转判定链 Mermaid 备用图](../../diagrams/mermaid/redirect-decision-chain.md)：第一步「限流」的键就来自 ClientIps.resolve（ShortLinkService.java:604-606），对照图上第一格阅读 5.3。
- [运行时与部署图](../../diagrams/archify/html/01-runtime-deployment.html)（由并行图表任务产出）：Prometheus 抓取 /actuator/prometheus 的链路，被抓的指标正是经过 uri 白名单过滤后的那一批。
- [包结构图](../../diagrams/archify/html/03-package-map.html)（由并行图表任务产出）：common/util 与 observability 两个横切包的位置。

阅读顺序：先看跳转链图找到限流入口，再回 5.3 看键的构成，最后看部署图理解指标去向。

## 8. 项目中的真实执行过程

**一次跳转请求的 IP 解析。** 匿名用户请求 `GET /r/AbC123x`。请求先过限流（ShortLinkService.java:604-606）：

```java
        RateLimitDecision decision = rateLimiter.acquire(
                RateLimitKeys.sw(rl.getKeyPrefix(), "redirect:ip:" + ClientIps.resolve(request, rl.isTrustXff())),
                rl.getRedirectMax(), rl.getRedirectWindowSeconds());
```

默认配置下 `trustXff=false`，`resolve` 返回 socket 地址。攻击者带着伪造的 `X-Forwarded-For: 8.8.8.8` 来，键里仍是他的真实地址，每分钟 300 次（redirect-max，application.yml:200）的上限躲不掉。运维在可信反代后打开 `LINKFORGE_TRUST_XFF=true` 后，同一行代码改取 XFF 最左值，限流对象才变成真实客户端。

**一次 Prometheus 抓取的标签过滤。** 抓取器请求 `/actuator/prometheus`。此前某个客户端在 TLS 握手后立刻断连，这条失败请求的 uri 不在 21 个模板里。`httpUriTagFilter` 把它的 uri 改写成 UNKNOWN，抓取到的指标里它并入 `http_server_requests_seconds_count{uri="UNKNOWN"}`。Grafana 跳转 SLO 面板（slo 桶配置见 application.yml:43-48）读到的每个 uri 都干净可聚合。

## 9. 为什么这样设计

- fail-open 加计数器加 warn 三件套。限流器是「挡在业务前面的组件」，它自己故障时打死业务比放行的代价高，所以选择放行，但用指标保证「放行」这件事可见可告警（RedisRateLimiter.java:15-21 的类注释写明了这个立场）。
- IP 语义单点实现。四处调用方共用 ClientIps 加同一个开关，杜绝「限流一个口径、归因另一个口径」。工具类不加状态、不做 Bean，让调用方显式表达信任判断。
- 白名单塌缩到 UNKNOWN。安全方向的失败模式是设计目标之一：忘记登记的代价是「指标少一条曲线」，而不是「时序库被打爆」或「应用报错」。
- CsvFormat 保持纯函数无依赖。CSV 转义规则独立于数据库与框架，纯静态让它的单元测试不需要起 Spring（javadoc 自述），测试成本最低。

## 10. 本节总结

- `@Slf4j` 生成日志字段，SLF4J 门面加 Logback 实现，级别在 yml 收口。
- 四条纪律：warn 一行带标识不打全栈；info 记里程碑只记数量；error 留给终态与未知异常（唯一全栈点是全局兜底）；框架噪音压 WARN。
- `ClientIps.resolve` 默认不信 XFF（伪造可绕过按 IP 限流），信任由调用方传参；四个调用方口径一致。
- `HttpUriTagAllowList` 用 MeterFilter 把白名单外的 uri 塌缩成 UNKNOWN，防指标基数爆炸，塌缩方向安全。
- `CsvFormat` 是纯静态工具范本，转义规则三段式。

## 11. 课后练习

**练习 1（执行过程）**：`trustXff=false` 时，攻击者请求头带 `X-Forwarded-For: 8.8.8.8` 注册账号，注册限流键里算的是什么 IP？若运维误开 `LINKFORGE_TRUST_XFF=true` 且前面没有反代，又会怎样？

答案解析：false 时 `resolve` 跳过 XFF 分支返回 `request.getRemoteAddr()`（ClientIps.java:22），伪造头无效，键用攻击者真实地址（AuthService.java:68 的调用点）。误开且无反代时，XFF 是攻击者自己写的值，`split(",")[0]` 取到 8.8.8.8，他可以每次请求换一个假 IP，注册限流被绕过。这就是默认关闭的依据（LinkForgeProperties.java:235 注释 "spoofable otherwise"）。

**练习 2（概念理解）**：把下面三条日志按本项目纪律指定级别并说明理由。A. 单条 outbox 投递超时进入退避。B. relay 一轮投递完成 12 条。C. 同一条 outbox 第 30 次尝试失败，标记 FAILED。

答案解析：A 用 warn，带 event 与 attempt 字段不打栈（对应 ClickOutboxRelay.java:63-64 的实际写法）。B 用 info 且只记数量（:68）。C 用 error 结构化字段（:51-52），因为 FAILED 是需要人介入的终态，继续 warn 会被淹没。

**练习 3（源码定位）**：请求 `GET /r/AbC123x` 在 MVC 分发前被容器拒绝，这条请求的 http.server.requests 指标里 uri 是什么？哪几行代码做的改写？

答案解析：UNKNOWN。原始路径 /r/AbC123x 不在 21 个白名单模板内（HttpUriTagAllowList.java:22-33），`httpUriTagFilter` 的 map 方法（:39-48）执行 `id.withTag(Tag.of("uri", "UNKNOWN"))`（:47）。

**练习 4（设计分析）**：新增端点 `GET /api/foo` 的开发者忘了登记白名单，用户报「接口正常但 Grafana 没有它的曲线」。这是什么故障？为什么说白名单的失败方向是安全的？

答案解析：指标的 uri 被塌缩进 UNKNOWN，功能本身无任何影响，只是可观测性缺失（javadoc :16-18 写明 "silently merge into UNKNOWN, which is the safe direction"）。对比失败方向若反过来（未知路径原样记标签），每个短码都会制造新序列，故障形态是抓取与存储劣化。安全方向意味着遗漏只损失可见性，不损失稳定性；补救是把模板加进 ALLOWED（:27-28 的 qr.png 注释就是一次这样的补充记录）。

**练习 5（调用链）**：手算 `CsvFormat.escape("a,\"b\"")` 的返回值，逐步写出转换过程。

答案解析：值含双引号，进入包裹分支（CsvFormat.java:13）。先做 `replace("\"", "\"\"")`，值变为 `a,""b""`，再包一层双引号得 `"a,""b"""`。校验要点：包裹与翻倍是同一次操作完成，顺序是先翻倍后包裹；不含逗号、引号、换行的值走 :17 原样返回，这是「最小转义」。

## 12. 与后续课程的联系

- `ClientIps` 的四个调用方各归属后续模块：跳转限流在模块 07 与 09，点击归因在模块 11，注册限流在模块 05。
- `HttpUriTagAllowList` 所在的 observability 包与 Actuator、Grafana 面板在模块 14 深入，那里会解释 SLO 桶配置（application.yml:43-48）如何被看板消费。
- `RedisRateLimiter` 的 fail-open 只是它的一面，滑动窗口 Lua 算法在模块 09 逐行拆。
- `CsvFormat` 的使用现场（导入导出全流程）在模块 06。
- 本模块到此收尾。回到 1.2 课的 Bean 清单你会发现，common 与 observability 的类大多以「被别人注入」为生，业务模块从下一模块开始。
