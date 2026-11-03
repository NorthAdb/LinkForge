# 1.3 配置体系与 Profile

## 1. 本节学习目标

1. 读懂 `application.yml` 的三文档结构，知道哪段配置在哪些 profile 下生效。
2. 掌握 `@ConfigurationProperties` 的绑定树：yml 的 `linkforge.*` 如何长成 `LinkForgeProperties` 这个 Bean。
3. 记住四个不设默认值的必填变量，以及漏配时的表现。
4. 吃透两个真实陷阱：多文档作用域（mybatis-plus 和整个 linkforge 配置住在 dev 文档里）、async-click 默认值两处不一致。

## 2. 前置知识

- 1.2 课的 Bean 与依赖注入。`LinkForgeProperties` 就是一个 Bean，配置是它的字段值。
- YAML 语法：两个空格缩进表示层级，`key: value` 冒号后有空格，`#` 注释，`---` 分隔多个文档。
- 环境变量与 profile 的关系：profile 是一组配置的标签，环境变量是操作系统传给进程的值。

## 3. 本节涉及的源码

| 文件 | 职责 | 阅读顺序 | 为什么读它 |
|------|------|----------|------------|
| `src/main/resources/application.yml`（267 行） | 全部配置，默认加 dev 加 prod 三文档 | 第 1，对照 5.1 的地图读 | 配置总集 |
| `src/main/java/com/linkforge/config/LinkForgeProperties.java`（239 行） | `linkforge` 前缀的绑定树 | 第 2 | 17 个字段、16 个内部类 |
| `src/test/resources/application-test.yml`（70 行） | test profile 覆盖项 | 第 3 | 看测试如何改写默认行为 |

## 4. 类的定位

配置体系是所有 Bean 的输入。1.2 课的启动过程里，容器先读配置再实例化 Bean；ShortLinkService 注入的 `properties`（ShortLinkService.java:103）就是本课主角。它位于 config 包，被 `@Component` 扫描注册，是全项目业务开关的总闸：限流阈值、缓存 TTL、Bloom 开关、webhook 退避全部住在这一棵树上。

## 5. 核心源码详解

### 5.1 三文档地图（application.yml）

```yaml
spring:
  application:
    name: linkforge
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}
```

（application.yml:1-5）

（框架行为）`---` 把一个 yml 文件拆成多个文档：没有 `on-profile` 标记的第一个文档对所有 profile 生效，标了 `on-profile: dev` 的只在 dev 激活时生效，激活哪个 profile 由 :5 决定，默认 dev，可用环境变量改。先背下这张地图，后面所有讨论都基于它。

| 文档 | 行范围 | 生效条件 | 内容 |
|------|--------|----------|------|
| 默认 | :1-49 | 所有 profile | 调度池、上传上限、Flyway、management 指标 |
| dev | :51-244 | profile=dev | 数据源、Redis、MQ、jackson、mybatis-plus、server、linkforge.* 全树、logging |
| prod | :247-266 | profile=prod | 静态资源关闭、springdoc 关闭、health 收敛 |

默认文档里值得细看的一段（application.yml:6-12）：

```yaml
  task:
    scheduling:
      # B5: relays must not share one thread — a slow webhook endpoint must never
      # delay click outbox catch-up. B9 adds the blocklist reload and B1 the backlog
      # sampler (D-Bw345-54: 4 → 6).
      pool:
        size: ${LINKFORGE_SCHEDULING_POOL_SIZE:6}
```

注释记录了演进：调度线程池从 4 加到 6，因为 B5 的两个 relay、B9 的名单重载、B1 的积压采样不能共用线程，一个慢任务不得拖死别的。`${VAR:默认值}` 的意思是环境变量优先，没有就用冒号后的默认。这个写法全文件通用。

### 5.2 dev 文档主干（application.yml:51-244）

```yaml
spring:
  config:
    activate:
      on-profile: dev
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:mysql://localhost:3306/linkforge?...}
    username: ${SPRING_DATASOURCE_USERNAME:linkforge}
    password: ${SPRING_DATASOURCE_PASSWORD}
```

（application.yml:51-58，url 一行截断展示）

注意 :58 的 `password` 没有冒号默认值。这种写法是「必填」：环境变量不存在时，Spring 解析失败，应用启动直接报错（框架行为）。全文件共四个这样的必填项，都是敏感或环境相关数据：

| 变量 | 行号 | 用途 |
|------|------|------|
| `SPRING_DATASOURCE_PASSWORD` | :58 | MySQL 密码 |
| `SPRING_RABBITMQ_PASSWORD` | :69 | MQ 密码 |
| `JWT_SECRET` | :104 | JWT 签名密钥 |
| `LINKFORGE_BOOTSTRAP_PASSWORD` | :190 | 首个管理员密码 |

MQ 段（:70-81）有两个值得记住的开关：`publisher-confirm-type: correlated` 与 `template.mandatory: true`，注释说明 outbox relay 只在 broker 回执后才标 SENT（A7 审计的结论）；listener 的 `auto-startup` 与 `default-requeue-rejected` 做成了环境变量可覆盖。jackson 段（:82-85）把时区钉在 Asia/Shanghai、日期不写时间戳，与模块 11 的统计口径有关。

`mybatis-plus:` 段在 :87-96。`server.port` 在 :98-99。`linkforge:` 全树在 :102-239。`logging:` 在 :241-244。**这四段都住在 dev 文档里**，这是 5.5 第一个陷阱的伏笔。

### 5.3 绑定树：从 yml 到 Bean（LinkForgeProperties.java）

```java
@Data
@Component
@ConfigurationProperties(prefix = "linkforge")
public class LinkForgeProperties {

    private Jwt jwt = new Jwt();
    /** C1 wave B: origin scanners see in QR/share material; trailing slash trimmed on use. */
    private String publicBaseUrl = "http://localhost:8080";
```

（LinkForgeProperties.java:9-16）

（框架行为）`@ConfigurationProperties(prefix = "linkforge")` 把 yml 里 `linkforge.` 之后的键按名字对到字段：`linkforge.jwt.secret` 填进 `jwt.secret`。字段类型是内部类就继续往下钻，所以 16 个 `@Data` 内部类就是 16 棵子树。绑定规则叫宽松绑定（框架行为）：yml 的 `cache-ttl-seconds`、Java 的 `cacheTtlSeconds`、环境变量 `LINKFORGE_CACHE_TTL_SECONDS` 三种写法指同一个键。

看一棵具体子树（LinkForgeProperties.java:114-127，逐字引用。围栏标记 ignore-check，因为 Mq 是嵌套类而校验器按顶层文件名查类型）：

```java ignore-check
    @Data
    public static class Mq {
        /** When true, click events go through the outbox+relay instead of a hot-path publish. */
        private boolean asyncClickEnabled = false;
        /** D7=A: keep the sync lf_click_log write alongside the outbox path. */
        private boolean syncClickEnabled = true;
        /** A7 relay: drains PENDING outbox rows to RabbitMQ. */
        private boolean relayEnabled = true;
```

注意每个字段的注释都是行为说明，Java 侧默认值就在等号后。`asyncClickEnabled` 的 Java 默认是 false，这一行 :117 请记住，5.5 第二个陷阱的主角。

绑定优先级（框架行为，由低到高）：Java 字段默认值 < application.yml < 环境变量 < 命令行参数。yml 里写了 `${LINKFORGE_ASYNC_CLICK_ENABLED:true}`，等于在 yml 层又塞了一层默认。

### 5.4 test profile 与 dev 的对照（application-test.yml:37-44）

```yaml
  mq:
    async-click-enabled: false
    sync-click-enabled: true
    reconcile-fixed-delay-ms: 3600000
    reconcile-initial-delay-ms: 3600000
  bootstrap:
    enabled: false
    password: ${LINKFORGE_BOOTSTRAP_PASSWORD:it-not-used}
```

test profile 的风格是「关掉一切会漂移的东西」：async-click 关、bootstrap 关、退避压到 1-2 秒、限流阈值放大到 1000（application-test.yml:45-48）、调度延迟统一一小时防测试断言被定时任务打乱（:49-53）。它还把连接类配置整个留空，由 Testcontainers 在运行时注入（:1-3 的注释）。dev 追求「开箱能跑」，test 追求「结果确定」，这是两份配置的设计分歧。

### 5.5 两个真实陷阱

**陷阱一：多文档作用域。** dev 文档不止是「开发机的数据源」，它装着 mybatis-plus、server.port、logging 和整棵 linkforge 树（:87-244）。prod 文档（:247-266）只覆盖了四样东西：

```yaml
spring:
  config:
    activate:
      on-profile: prod
  web:
    resources:
      # no static console in prod
      add-mappings: false
springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false
linkforge:
  docs-enabled: false
management:
  endpoint:
    health:
      show-details: when_authorized
      show-components: when_authorized
```

（application.yml:247-266）

把 profile 切到 prod 时，mybatis-plus 的驼峰映射、逻辑删除配置，server.port，整棵 linkforge 业务树全部不生效（本项目实现的事实）。它们原本在 dev 文档里住着。把 MYBATIS_PLUS 路径的环境变量一个个设回去可以救（宽松绑定支持环境变量表达嵌套键），但项目没有这样配。这解释了为什么 docker-compose 与脚本都默认跑 dev profile，prod 目前更像一份「收紧项清单」而非完整可部署配置（推断：作者的重心在单机与 Compose 部署）。

顺带一个遗留问题：:261 的 `linkforge.docs-enabled` 在 `LinkForgeProperties` 里没有对应字段，绑定默认忽略未知键，所以它不报错也不起作用。改这棵树时新增键忘写字段，就是这种「静默无效」，排查手段是给绑定类补字段或在启动日志确认。

**陷阱二：async-click 默认值两处不一致。** 三处事实摆在一起：

1. Java 字段默认 false（LinkForgeProperties.java:117）。
2. dev yml 写 `async-click-enabled: ${LINKFORGE_ASYNC_CLICK_ENABLED:true}`（application.yml:177），dev 生效值 true。
3. test yml 显式写 false（application-test.yml:38）。

结论：profile 的 yml 有这个键，Java 默认就被覆盖，dev 下是 true；只有配置树里完全没有这个键的场合，false 才兜底。它解释了两个现象：本地开发点击事件走 outbox 异步链路（功能被默认打开），而集成测试确定性走同步（test 显式关）。读这种代码不能只看 Java 默认值，也不能只看 yml，要以「激活 profile 后的合成结果」为准。

### 5.6 一个绑定不了 duration 的角落（LinkForgeProperties.java:44-46）

```java
        private int sentDays = 7;
        /** @Scheduled reads millis (its placeholder parsing knows no Boot duration style). */
        private long cleanupIntervalMs = 3_600_000L;
```

注释解释了为什么字段名带 Ms 而不是直接用 Spring 的 `Duration` 风格配置：这些值最终喂给 `@Scheduled(fixedDelayString = "${...}")`，后者只解析毫秒数字。项目里所有定时类配置（relay、rebuild、cleanup）统一用毫秒 long，这是一条全局约定，你在 yml 里看不到 `30s` 这种写法。

## 6. 技术原理补充

**配置来源与优先级**（框架行为）。Spring Boot 按固定顺序合并配置，常用四级从低到高：application.yml、profile 专属文档、环境变量、命令行参数。高一级整体覆盖低一级的同名键。profile 文档对默认文档是「键级合并」：同名键覆盖，默认文档独有的键保留。

**宽松绑定**（框架行为）。`linkforge.linkAccess.ttlSeconds`、`link-access: ttl-seconds:`、`LINKFORGE_LINKACCESS_TTLSECONDS` 是同一个键的三种形态。环境变量里没有点号，层级用下划线表达。列表类型（如 `bot-ua-tokens`，application.yml:212 对 LinkForgeProperties.java:212-216 的 21 个 token）用逗号分隔的字符串绑定成 `List<String>`。

**必填检测的时机**（框架行为）。`${JWT_SECRET}` 在属性解析阶段处理，早于任何 Bean 使用，所以漏配是「启动失败加明确报错」，不会带病运行。对密钥类配置这是对的设计：宁可起不来，不可弱密钥上线。

**@ConfigurationProperties 与 @Value 的取舍**（框架行为）。前者聚合成树、有类型、可校验，适合几十个相关键；后者零散取值。本项目业务配置全部走前者，yml 里没有一处 `@Value` 风格的散用。

## 7. 可视化

- [运行时与部署图](../../diagrams/archify/html/01-runtime-deployment.html)（由并行图表任务产出）：环境变量在部署图上的注入点（compose 的 environment 段），对应本节的四必填变量与 LINKFORGE_* 覆盖变量。
- 结合 1.2 课的[系统架构图](../../diagrams/archify/html/00-system-architecture.html)：每个框的开关几乎都能在 `LinkForgeProperties` 的 17 个字段里找到同名的。

阅读顺序：先看 5.1 的文档地图，再对照架构图挑三个你感兴趣的开关注回 yml 行号。

## 8. 项目中的真实执行过程

以「创建短链被限流」为例走一遍配置的读法（代码见 ShortLinkService.java:131-134）。

1. 启动时容器绑定 `linkforge.*`，得到 `LinkForgeProperties` Bean。dev 下 `rateLimit.createMax` 为 30（application.yml:194）。
2. ShortLinkService 注入 `properties`（ShortLinkService.java:103）。
3. `create()` 方法开头取 `properties.getRateLimit()`，读出 createMax=30 与 windowSeconds=60，传给限流器。
4. 运维想临时放宽，不改代码，设 `LINKFORGE_CREATE_RATE_MAX=100` 重启即可（宽松绑定把变量映射回同一字段）。

再以「漏配 JWT_SECRET」为例：启动在属性解析阶段报错，错误信息会指名 `JWT_SECRET`，应用不进入监听状态。对比若把 JWT_SECRET 设成空串的默认值，应用会正常起来，签发的 token 全部无效，问题被推迟到登录请求。这就是四必填不设默认值的理由。

## 9. 为什么这样设计

- 配置集中一棵树而不是散在各 Bean。所有「能不能调」的问题只查 LinkForgeProperties 一处，239 行里每个字段带注释说明行为边界。
- 敏感项无默认、行为项全默认。漏配敏感项在启动时炸，漏配行为项给合理值，两类风险分开处理。
- 毫秒统一、不用 duration。服务于 `@Scheduled` 的解析能力（LinkForgeProperties.java:45 注释），一次约定省去逐处踩坑。
- dev 文档承载几乎全部默认值。推断：作者把 dev 当「事实上的默认 profile」，test 覆盖之，prod 只列收紧项。代价是 5.5 陷阱一，收益是本地与 Compose 零配置可跑。

## 10. 本节总结

- 三文档地图：默认 :1-49 全局生效，dev :51-244 承载主体，prod :247-266 只做收紧。
- `LinkForgeProperties` 用 16 个内部类把 `linkforge.*` 绑成树，宽松绑定串起 yml、环境变量、Java 三种写法。
- 四必填变量无默认，漏配启动即失败。
- 陷阱一：mybatis-plus、server、linkforge 树住在 dev 文档，切 prod 全部失效；`docs-enabled` 是无字段的静默遗留。
- 陷阱二：async-click 的 Java 默认 false（:117）与 dev yml 默认 true（:177）不一致，激活 profile 后以 yml 为准。
- 定时配置统一毫秒 long，因为 `@Scheduled` 只认毫秒。

## 11. 课后练习

**练习 1（概念理解）**：把 `linkforge.webhook.connect-timeout-ms` 翻译成等价的环境变量名。

答案解析：宽松绑定规则：层级加点改下划线，kebab 去掉横线转大写，得 `LINKFORGE_WEBHOOK_CONNECT_TIMEOUT_MS`。application.yml:166 正是这么写的，可以验证。

**练习 2（源码定位）**：profile=prod 时，`mybatis-plus.configuration.map-underscore-to-camel-case: true` 生效吗？依据哪几行？

答案解析：不生效。该键位于 dev 文档内（application.yml:87-89），prod 文档（:247-266）没有等价键，也没有别的来源提供。除非用环境变量显式提供，否则 prod 上下文里这项配置不存在。陷阱一的直接考法。

**练习 3（执行过程）**：dev profile 且 `LINKFORGE_ASYNC_CLICK_ENABLED` 未设置时，`asyncClickEnabled` 的最终值是多少？分别说出三层默认各是什么。

答案解析：最终 true。三层是：Java 字段默认 false（LinkForgeProperties.java:117），yml 键的 env 默认 true（application.yml:177），环境变量本身未设置所以取 yml 的默认。合成规则是高层覆盖低层，yml 有键，Java 默认出局。

**练习 4（调用链）**：`LINKFORGE_BOOTSTRAP_PASSWORD` 漏配与误配为空串，后果分别发生在什么时刻？

答案解析：漏配时属性解析失败，启动报错（application.yml:190 无默认值）。误配空串不会在启动期被拦截，会进入 bootstrap 流程由业务校验处理。前者 fail-fast，后者推迟到运行期，这正是必填项不写 `:默认值` 的意义。

**练习 5（设计分析）**：Retention.cleanupIntervalMs 为什么不用 `Duration` 类型加 `cleanup-interval: 1h` 写法？依据哪行注释？

答案解析：LinkForgeProperties.java:45 注释「@Scheduled reads millis (its placeholder parsing knows no Boot duration style)」。这些值最终进入 `@Scheduled` 的 fixedDelayString，只解析毫秒。用 Duration 绑定可行，但取出后还要转毫秒喂给注解，两层类型徒增转换点。项目统一毫秒 long 是简化。

## 12. 与后续课程的联系

- 这棵配置树会在后面每课反复出现：限流参数见模块 09，Bloom 与缓存见模块 08，webhook 退避见模块 13。
- prod 收紧项（静态资源、springdoc、health 详情）与 MVC 的关系见模块 03。
- 1.2 课的启动装配加本课的配置填充合起来，才是完整的「应用怎么跑起来」。此后任何一课说「配置项 X 默认 Y」，你都应能回到 yml 行号与 Java 默认值两处对账。本课发现的 `docs-enabled` 遗留与 async-click 不一致，建议登记到 [progress.md](../../reference/progress.md) 的差异候选区。
