# 10.1 拓扑与确认机制：六个 AMQP bean、correlated confirm 与 mandatory returns

> 本课回答一个问题：LinkForge 把一条点击事件从进程送进 RabbitMQ，靠什么保证「broker 说收了，才算发了」？为什么除了 confirm 还要配 mandatory returns 和 5 秒超时？看完本课，`RabbitMqConfig` 的 63 行你应该每一行都能说出用途。

## 1. 本节学习目标

学完本课，你应该能：

1. 画出 LinkForge 的 RabbitMQ 拓扑：1 个业务交换器、1 个业务队列、1 条绑定、1 组死信交换器/队列/绑定，并把每一件指到 `RabbitMqConfig.java` 的具体 bean。
2. 说清 `publisher-confirm-type: correlated`、`publisher-returns: true`、`template.mandatory: true` 三行配置各解决什么问题，缺一行会丢掉哪种失败信号。
3. 逐行解释 `RabbitClickEventPublisher.publish` 的 24 行：confirm future、nack 分支、不可路由分支、5 秒超时分支、中断分支。
4. 复述 `Jackson2JsonMessageConverter` 必须复用容器 `ObjectMapper` 的原因，并说出踩坑时最先出现的信号（消息全进 DLQ）。
5. 列出 `ClickEvent` 的 10 个字段，解释 `eventId` 的确定性语义注释为什么写在契约类上。

## 2. 前置知识

- **AMQP 与 RabbitMQ**（框架知识）。AMQP 0-9-1 是 RabbitMQ 说的协议。生产者不直接把消息投给队列，而是投给**交换器（Exchange）**；交换器按**绑定（Binding）**规则和**路由键（Routing Key）**决定消息去哪个**队列（Queue）**。没人接的消息就地丢弃。
- **Exchange 类型**。fanout 广播、direct 精确匹配、topic 通配匹配（`click.*`、`order.#`）。本项目用 topic，虽然当前只有一条精确路由键。
- **持久化（durable）**。`durable=true` 的交换器和队列在 broker 重启后还在；消息是否持久化由发送时另设的 flag 决定（Spring AMQP 默认随 `RabbitTemplate` 配置，本项目未改，消息本身是易失的，可靠性由 outbox 重发兜底，见 10.2）。
- **Publisher Confirm**（RabbitMQ 对 AMQP 0-9-1 的扩展）。broker 处理完一条消息后回给发送端一个 `basic.ack`（或失败时 `basic.nack`）。AMQP 原生协议里「发出去」和「broker 收到」之间没有任何确认，这个扩展补的就是这一段。
- **mandatory 与 basic.return**。发送时给消息打 mandatory 标记，broker 发现它**路由不到任何队列**时不丢弃，而是用 `basic.return` 把消息原文退回给发送端。注意次序：不可路由的消息同样会收到 ack（broker 确实「处理」了它），所以 confirm 证明收了、returns 才发现投不出去，两者缺一不可。
- **死信（Dead Lettering）**。队列可以声明两个参数：消息被拒绝且不重回队列、或过期、或队列超长时，broker 把它转发给参数指定的**死信交换器（DLX）**，用指定的死信路由键投递。
- **Jackson 与 JavaTimeModule**（框架知识）。Jackson 默认不认识 `java.time.LocalDateTime`，序列化直接抛 `InvalidDefinitionException`；注册 `jackson-datatype-jsr310` 提供的 `JavaTimeModule` 后才能写成 ISO 字符串。Spring Boot 自动装配的 `ObjectMapper` 已带这个模块。

## 3. 本节涉及的源码

| 文件 | 行数 | 本课关注点 | 阅读顺序 |
|----|----|----|----|
| `src/main/java/com/linkforge/config/RabbitMqConfig.java` | 63 | 6 常量 :17-22、6 个 bean :24-62 | 1 |
| `src/main/resources/application.yml` | 266 | rabbitmq 段 :65-81、mq 开关段 :174-185 | 2 |
| `src/main/java/com/linkforge/stats/mq/ClickEvent.java` | 29 | 10 字段契约 :17-28、eventId 语义 :10-12 | 3 |
| `src/main/java/com/linkforge/stats/mq/ClickEventPublisher.java` | 10 | 发布抽象接口 :8-10 | 4 |
| `src/main/java/com/linkforge/stats/mq/RabbitClickEventPublisher.java` | 68 | confirm+returns 发布 :43-67 | 5 |
| `src/main/java/com/linkforge/stats/mq/ClickEventConsumer.java` | 28 | 监听入口 :22-27（幂等细节在 10.3） | 6 |

阅读顺序的理由：先看拓扑（哪些容器存在），再看配置（哪些开关打开），然后顺着「消息长什么样 → 谁发 → 谁收」读四个 mq 类。消费者本课只看入口签名，它的 48 行（:66-113）核心逻辑 `consume` 是 10.3 的主角。

## 4. 类的定位

- `RabbitMqConfig` 是全项目**唯一**声明 AMQP bean 的地方，在 `config` 包（启动与配置类都在这，见 1.2 课）。别的类只引用它的 6 个字符串常量，不自己拼队列名。
- `stats/mq` 包共 4 个类，是「点击事件」这条消息的契约与两端：`ClickEvent`（消息体）、`ClickEventPublisher`（发送抽象）、`RabbitClickEventPublisher`（唯一实现）、`ClickEventConsumer`（唯一监听器）。包全景见 [../../diagrams/archify/html/03-package-map.html](../../diagrams/archify/html/03-package-map.html)。
- `RabbitClickEventPublisher` 不被任何业务类直接 new，它通过接口 `ClickEventPublisher` 被 `ClickOutboxRelay` 使用（实现边见 [../../diagrams/archify/html/04-core-class-relations.html](../../diagrams/archify/html/04-core-class-relations.html)）。接口存在的理由写在它的 javadoc 里：换 broker 客户端不动 relay（`ClickEventPublisher.java:4`）。
- `ClickEvent` 是全管道的公共语言：写它的 `ClickRecordService`（11.1 课）、发它的 relay（10.2 课）、收它的 `ClickStatsService`（10.3 课）都只认这 10 个字段。
- 拓扑逐 bean 对照见 [../../diagrams/archify/html/07-rabbitmq-topology.html](../../diagrams/archify/html/07-rabbitmq-topology.html)。

## 5. 核心源码详解

### 5.1 六个常量：拓扑的命名空间

```java
public static final String CLICK_EXCHANGE = "linkforge.click.exchange";
public static final String CLICK_QUEUE = "linkforge.click.queue";
public static final String CLICK_ROUTING_KEY = "click.recorded";
public static final String CLICK_DLX = "linkforge.click.dlx";
public static final String CLICK_DLQ = "linkforge.click.dlq";
public static final String CLICK_DLQ_KEY = "click.dead";
```
`RabbitMqConfig.java:17-22`

名字分两族：主链用 `.exchange`/`.queue` 后缀加 `click.recorded` 路由键；死信链加 `.dlx`/`.dlq` 后缀加 `click.dead`。声明成 `public static final` 是为了让发布端（`RabbitClickEventPublisher`）、消费端（`ClickEventConsumer`）和拓扑定义方共用同一组字符串，改一处全项目生效。

### 5.2 主链三个 bean

```java
@Bean
public TopicExchange clickExchange() {
    return new TopicExchange(CLICK_EXCHANGE, true, false);
}

@Bean
public Queue clickQueue() {
    return QueueBuilder.durable(CLICK_QUEUE)
            .deadLetterExchange(CLICK_DLX)
            .deadLetterRoutingKey(CLICK_DLQ_KEY)
            .build();
}

@Bean
public Binding clickBinding(Queue clickQueue, TopicExchange clickExchange) {
    return BindingBuilder.bind(clickQueue).to(clickExchange).with(CLICK_ROUTING_KEY);
}
```
`RabbitMqConfig.java:24-40`

`new TopicExchange(name, true, false)` 的两个布尔位是 `durable=true`（重启后交换器还在）和 `autoDelete=false`（没有消费者也不删）。`clickQueue` 用 `QueueBuilder.durable(...)` 而不是 `new Queue(...)`，因为要在队列上挂死信参数：`x-dead-letter-exchange` 和 `x-dead-letter-routing-key`。这两个参数在 broker 端生效，含义是「凡是被本队列拒绝且不重回队列的消息，改投 `linkforge.click.dlx`，路由键换成 `click.dead`」。注意死信路由不是消息自带的，是**队列**声明的，所以队列里所有死信走同一条路。

绑定把三者连起来：routing key `click.recorded` 精确匹配。topic 类型下 `click.recorded` 不含通配符，行为等同 direct，留 topic 是给未来扩展留余地（推断：比如 `click.recorded.webhook` 之类多队列分叉）。

### 5.3 死信链三个 bean

```java
@Bean
public TopicExchange clickDlx() {
    return new TopicExchange(CLICK_DLX, true, false);
}

@Bean
public Queue clickDlq() {
    return new Queue(CLICK_DLQ, true);
}

@Bean
public Binding clickDlqBinding(Queue clickDlq, TopicExchange clickDlx) {
    return BindingBuilder.bind(clickDlq).to(clickDlx).with(CLICK_DLQ_KEY);
}
```
`RabbitMqConfig.java:42-55`

死信链就是一份「最小可用」拷贝：同样 durable 的交换器、同样 durable 的队列、一条绑定。`clickDlq` 没有再挂 DLX，死信的死信就堆在队列里等人处理。 DLQ 不设消费者，它只是报错现场保存柜，10.3 课讲什么消息会进去、进去后怎么处理。

### 5.4 消息转换器：必须复用容器 ObjectMapper

```java
@Bean
public MessageConverter jacksonMessageConverter(ObjectMapper objectMapper) {
    // Spring's mapper has JavaTimeModule; the converter's default one cannot
    // round-trip LocalDateTime and dead-letters every ClickEvent.
    return new Jackson2JsonMessageConverter(objectMapper);
}
```
`RabbitMqConfig.java:57-62`

方法参数注入的是 Spring Boot 自动装配的 `ObjectMapper`（2.2 与 3.1 课都碰过它）。坑在注释第二句：`Jackson2JsonMessageConverter` 不传参数时会自己 `new` 一个裸 `ObjectMapper`，裸 mapper 没注册 `JavaTimeModule`，而 `ClickEvent.clickedAt` 是 `LocalDateTime`（`ClickEvent.java:26`）。后果不是发送失败这么简单：

1. 发送端序列化 `clickedAt` 抛异常，`publish` 把异常抛给 relay，outbox 行退避重试，30 次后整批标 FAILED（10.2 课）。这还算**能看见**的失败。
2. 若发送端 mapper 正常、消费端 mapper 裸奔，则消费端反序列化失败抛异常，消息被拒绝且不重回队列，全部进 DLQ。`RabbitClickEventPublisher` 的 confirm 照常 ack，发布端毫无感知，`lf_outbox` 全是 SENT，统计却永远为 0。这是这条注释把「dead-letters every ClickEvent」写进代码的真实恐惧。

复用容器 mapper 还带来第二层一致性：`application.yml:82-85` 对 Jackson 的定制（时区、`write-dates-as-timestamps: false`）同时作用于 HTTP 响应和 MQ 消息，两边格式永不分叉。

### 5.5 yml：三行确认配置与两行容器配置

```yaml
    # A7 audit S1: correlated confirms + mandatory returns; the outbox relay marks
    # SENT only after the broker acks.
    publisher-confirm-type: correlated
    publisher-returns: true
    template:
      mandatory: true
    listener:
      simple:
        # Consumer runs by default now that the real consumer is in place.
        auto-startup: ${LINKFORGE_MQ_LISTENER_AUTO_STARTUP:true}
        # Errored messages go to the dead-letter queue instead of hot-looping.
        default-requeue-rejected: ${LINKFORGE_MQ_DEFAULT_REQUEUE_REJECTED:false}
```
`application.yml:65-81`（节选 rabbitmq 键，行号对应 dev profile 段）

- `publisher-confirm-type: correlated`（:72）。打开 publisher confirm，且选 correlated 模式：每条消息关联一个 `CorrelationData`，broker 的 ack 会回填到它的 `Future` 里。Spring AMQP 另有 `simple` 模式（全局计数器），拿不到「哪一条确认了」，做不了逐条判断，所以这里必须是 correlated。
- `publisher-returns: true`（:73）让 `RabbitTemplate` 保留被退回的消息（回调可拿到原文）。
- `template.mandatory: true`（:74-75）给每条经模板发出的消息打 mandatory 标记。没有这行，不可路由的消息被 broker 直接丢弃，`publisher-returns` 配了也没有东西可退。
- `auto-startup`（:79）控制监听容器是否随应用启动消费者。默认 true，注释说明真实消费者就位后恢复默认；测试或演练可以关掉它（15.3 课的 drill 脚本用到）。
- `default-requeue-rejected: false`（:81）是 DLQ 链能生效的前提。监听容器捕获到消费者抛出的异常后，默认行为是 `basic.nack(requeue=true)`，消息回到队列头部被立刻再取，无限循环烧 CPU（英文里叫 hot-loop）。设为 false 后改为 `requeue=false`，配合 5.2 的队列死信参数，消息改道 DLQ。

这五行的分工记成一句话：**confirm 管「收没收到」，returns 管「投没投出去」，requeue-rejected 管「消费失败回不回来」，DLX 管「回不来的消息去哪」。**

`application.yml:174-185` 的 `linkforge.mq` 段（async/sync 开关、relay 批量、退避上限）是 10.2 课的主菜，本课只需知道它存在。

### 5.6 RabbitClickEventPublisher：三件套的完备写法

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class RabbitClickEventPublisher implements ClickEventPublisher {

    private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(5);
    /** correlationIds of messages the broker returned as unroutable. */
    private final Set<String> returnedIds = ConcurrentHashMap.newKeySet();

    private final RabbitTemplate rabbitTemplate;

    @PostConstruct
    void trackReturns() {
        rabbitTemplate.setReturnsCallback(returned -> {
            String id = returned.getMessage().getMessageProperties().getCorrelationId();
            if (id != null) {
                returnedIds.add(id);
            }
        });
    }
```
`RabbitClickEventPublisher.java:22-41`

类注释（:16-21）一句话交代了设计目标：ack 之前不返回；nack、不可路由、确认超时都抛异常，让 relay 保持 PENDING 重试而不是静默丢点击。两个字段值得停一下：

- `CONFIRM_TIMEOUT = 5 秒`（:27）。`Future.get` 不带超时会永远挂起，relay 是 `@Scheduled` 线程池（6 线程，`application.yml:11-12`）里的公共资源，一条消息挂死等于一个调度线程报废。
- `returnedIds`（:29）是并发安全的 String 集合。returns 回调在 AMQP 连接的 I/O 线程触发，publish 在调度线程等待，两个线程靠这个集合交棒。

`@PostConstruct` 的 `trackReturns`（:33-41）在 bean 就绪后给**共享的** `RabbitTemplate` 挂 returns 回调：把被退回消息的 correlationId（发布时设成了 eventId）记进集合。回调本身不抛异常、不直接处理，只记账，判断留给 publish。

```java
    @Override
    public void publish(ClickEvent event) {
        CorrelationData correlation = new CorrelationData(event.getEventId());
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.CLICK_EXCHANGE,
                RabbitMqConfig.CLICK_ROUTING_KEY,
                event,
                correlation);
        try {
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(CONFIRM_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (confirm == null || !confirm.isAck()) {
                throw new IllegalStateException("broker nacked event " + event.getEventId());
            }
            if (returnedIds.remove(correlation.getId())) {
                throw new IllegalStateException("unroutable event " + event.getEventId());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("confirm wait interrupted for " + event.getEventId(), ex);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ex) {
            throw new IllegalStateException("publisher confirm failed for " + event.getEventId(), ex);
        }
        log.debug("published click event code={} (confirmed)", event.getCode());
    }
```
`RabbitClickEventPublisher.java:43-67`

五步读法：

1. **:45** `new CorrelationData(event.getEventId())`。用 eventId 当关联 ID，一石三鸟：confirm 回填、returns 回调记账、日志报错都能对上同一条业务消息。
2. **:46-50** `convertAndSend` 发送。`Jackson2JsonMessageConverter` 在这一步把 `ClickEvent` 变成 JSON 字节；方法立刻返回，不等 broker。
3. **:52-56** 拿 ack。`getFuture().get(5, SECONDS)` 阻塞等待 broker 的确认。`confirm == null` 或 `isAck()==false` 即 nack，抛 `IllegalStateException`。nack 在实践中极罕见（broker 内部错误），但完备写法必须接住。
4. **:57-59** 查退件。ack 是好的，再看 `returnedIds` 里有没有自己的 ID：有，说明消息不可路由被退回，抛异常。`remove` 返回 true 同时完成「查询+清账」。
5. **:60-65** 两个收尾分支。被中断时先 `interrupt()` 恢复中断标志再抛（框架行为：吞掉中断标志会让线程池的停机协议失灵）；超时与执行异常统一包装抛出。

走到 ：66 的 debug 日志，才算「broker 确认收到且可路由」。任何一步抛异常，调用方 `ClickOutboxRelay` 都会把这行 outbox 留在 PENDING 退避重试（10.2 课）。

一个值得诚实的细节：returns 回调和 confirm ack 都从网络到达，理论时序由 broker 决定。RabbitMQ 对 mandatory 且不可路由的消息先发 `basic.return` 再发 ack（框架行为，官方文档口径），所以 :57 查集合时退件已记账。若网络乱序导致退件晚到，这条消息会被误判为已投递，但消息本身已丢在 broker，需要告警兜底。本项目未处理这个极端窗口（推断：概率极低，且 outbox 侧还有对账安全网，见 10.3 的 Reconciler）。

### 5.7 ClickEvent 契约与确定性 eventId

```java
/**
 * Click event contract. eventId is deterministic ("log-{clickLogId}") while the sync
 * click_log double-write is on, so the live consumer and the reconciliation replay of
 * the same click dedupe to a single count.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClickEvent {
    private String eventId;
    private Long tenantId;
    private Long linkId;
    private String code;
    private Long userId;
    private String ip;
    private String userAgent;
    private String referer;
    private LocalDateTime clickedAt;
    /** C3 (D-C3-11): the drawn A/B variant key; null when the link has no variants. */
    private String variant;
}
```
`ClickEvent.java:9-29`

10 个字段（:18-28）覆盖一条点击的全部原始信息：谁（userId）、哪条链（tenantId/linkId/code）、从哪来（ip/userAgent/referer）、何时（clickedAt）、抽了哪个 A/B 桶（variant）。注释 ：10-12 是全模块最重要的一句话：同步双写开着时，eventId 恒等于 `log-{lf_click_log 自增 id}`，这样「实时消费」和「对账重放」同一次点击算出同一个 eventId，幂等表一插就只剩一次计数。同步写关掉时 eventId 退化为稳定 UUID，存进 outbox 行，语义在 `ClickRecordService.java:33-39` 与 ：61-63（10.2 课逐行）。

Lombok 三件套各有用途（框架行为）：`@Data` 生成 getter/setter，Jackson 序列化要读；`@NoArgsConstructor` 供 Jackson 反序列化建空对象（缺它消费端抛「no suitable constructor」）；`@AllArgsConstructor` 供 `ClickRecordService.java:65-67` 一次构造全字段。

`ClickEventPublisher` 接口（`ClickEventPublisher.java:8-10`）只有一个 `void publish(ClickEvent)`，javadoc 把可靠性契约钉在接口上：「Implementations must only return after the broker accepted the message」。relay 只认接口，confirm 细节是实现的事。

## 6. 技术原理补充

**Publisher Confirm（框架行为）。** 是什么：broker 对每条消息回一个异步确认。为什么需要：AMQP 原生发送是即发即忘，TCP 连接活着不代表消息被接受（内存告警、内部错误都可能丢）。不用会怎样：relay 把消息扔给 socket 就标 SENT，broker 侧丢失无从得知。本项目用法：correlated 模式 + 5 秒同步等待，把「异步确认」变成「带超时的同步判断」。优缺点：完备，但每条消息多一次等待；本模块的量级（demo 级点击流）完全可接受，真要高吞吐应改批量确认（推断）。

**mandatory + returns（框架行为）。** 是什么：不可路由的消息退回发送端而非丢弃。为什么需要：confirm 的 ack 只表示 broker「处理了」，routing 失败也算处理完。不用会怎样：交换器被误删、绑定配置错时，消息无声消失，outbox 全 SENT、统计为 0，是最难排查的静默失败。本项目用法：三行配置（:72-75）加回调记账（:33-41），在 ack 之后查账（:57-59）。

**同步 5 秒超时（本项目实现）。** 是什么：`Future.get(5, SECONDS)`。为什么需要：调度线程是共享资源，无限等待会拖死整个调度池（跳转 outbox、webhook 投递、对账共用 6 个线程，`application.yml:6-12`）。不用会怎样：broker 半死不活（连接在、不回包）时 relay 线程逐个被占满。本项目用法：超时抛异常进退避路径。

**Jackson2JsonMessageConverter（框架行为+本项目实现）。** 是什么：Spring AMQP 的消息转换器，把对象与 `application/json` 字节互转，消息头 `__TypeId__` 记录类名供消费端反序列化。不用会怎样：默认 `SimpleMessageConverter` 走 JDK 序列化，消息不可读、跨语言不可用。本项目用法：传容器 mapper 进构造器，规避 JavaTimeModule 陷阱（5.4）。

**死信队列（框架行为）。** 是什么：队列拒绝的消息的下一站。为什么需要：给「必然失败的消息」一个终点，防止无限重试，也防止直接丢弃后无从排查。不用会怎样：消费 bug 会烧 CPU 热循环，或静默丢消息。本项目用法：DLX 挂在业务队列参数上（:31-34），DLQ 无消费者，人工处理。

## 7. 可视化

按顺序看两图一备用：

1. [../../diagrams/archify/html/07-rabbitmq-topology.html](../../diagrams/archify/html/07-rabbitmq-topology.html)：六个 bean 全部落图。节点对应：`clickExchange` ↔ `RabbitMqConfig.java:24`，`clickQueue` ↔ :29（卡片里收了 converter 陷阱），`clickBinding` ↔ :37，`clickDlx`/`clickDlq`/`clickDlqBinding` ↔ :42-55。
2. [../../diagrams/archify/html/04-core-class-relations.html](../../diagrams/archify/html/04-core-class-relations.html)：看 `RabbitClickEventPublisher ⇢ ClickEventPublisher` 的实现边，以及 `ClickOutboxRelay` 经接口依赖它（无类级直连）。
3. Mermaid 备用 [../../diagrams/mermaid/click-pipeline.md](../../diagrams/mermaid/click-pipeline.md)：「RELAY」段的发布/退避分支与本课 5.6 的五步一一对应。

## 8. 项目中的真实执行过程

场景：一条点击已写成 outbox 行，relay（10.2 课的主角，本课只看它调 `publish`）把它送进 RabbitMQ。

1. relay 反序列化出 `ClickEvent`，调 `publisher.publish(event)`（`ClickOutboxRelay.java:56-57`）。
2. `publish` 构造 `CorrelationData("log-123")`，`convertAndSend` 发出。converter（:58-62 复用的那个 mapper）把 `clickedAt` 写成 ISO 字符串，消息头 `__TypeId__=com.linkforge.stats.mq.ClickEvent`。
3. broker 收到，按绑定 ：37-40 把消息放进 `linkforge.click.queue`（路由键 `click.recorded` 精确匹配）。
4. broker 回 `basic.ack`，correlation 的 future 完成，:52-56 判定 ack 为真。
5. :57 查 `returnedIds`，空。:66 debug 日志。`publish` 返回，relay 调 `markSent` 把 outbox 行标 SENT（`OutboxMapper.java:19-20`）。
6. 另一头，监听容器（`auto-startup: true`，:79）从 `click.queue` 取到消息，converter 反序列化成 `ClickEvent`，进 `ClickEventConsumer.onMessage`（`ClickEventConsumer.java:23-27`）。
7. 异常分支走一遍：假设消费端 `consume` 抛异常。容器按 `default-requeue-rejected: false`（:81）拒绝且不重回队列；broker 按 `clickQueue` 的死信参数（:31-34）把消息投给 `click.dlx`、路由键 `click.dead`，落进 `click.dlq`（:47-50）。全程发布端 confirm 无感知，因为这条消息早就 ack 过了。

## 9. 为什么这样设计

**可靠性把关放在发送侧，宁慢勿丢。** 接口契约（`ClickEventPublisher.java:5-6`）和类注释（`RabbitClickEventPublisher.java:17-21`）都引用 A7 审计的 S1 结论：实现必须等 broker 接受后才返回。README 的口径一致：「publish 走 correlated confirm + mandatory returns，broker ack 后才标 SENT」（`README.md:412`）。设计动机是热路径（302 跳转）根本不碰 RabbitMQ（`README.md:410`），发送慢一点只影响后台追平，不影响用户跳转。

**三件套是完备性要求，不是配置炫技。** 只配 confirm：路由错误静默丢；只配 returns 不配 mandatory：退件机制整个不触发；不设超时：调度线程可能被占死。三行 yml 加五步 publish，每个失败模式都有一个对应的出口。

**转换器复用容器 mapper 是防双份配置漂移。** 注释（`RabbitMqConfig.java:59-60`）把后果写死了：converter 自带的裸 mapper 无法往返 `LocalDateTime`，事件全进 DLQ。复用容器实例，HTTP 与 MQ 的 JSON 格式由同一份 `application.yml:82-85` 决定。

**auto-startup 恢复 true 是有注释的决策。** `application.yml:78` 写明「Consumer runs by default now that the real consumer is in place」：消费者补齐之前这里是 false（防消息无人处理时堆积），`ClickEventConsumer` 落地后翻回 true，一行配置记录了开发顺序。

## 10. 本节总结

- 拓扑 6 常量（`RabbitMqConfig.java:17-22`）+ 6 bean：主链 exchange/queue/binding（:24-40），死信链 DLX/DLQ/binding（:42-55），转换器（:57-62）。死信参数声明在业务队列上。
- yml 三行确认配置（`application.yml:72-75`）+ 两行容器配置（:79-81）。confirm 管接收、returns 管路由、requeue-rejected 管失败去向。
- `RabbitClickEventPublisher.publish` 五步：关联 ID、发送、等 ack（5 秒）、查退件、异常收尾（:43-67）。抛异常=relay 保持 PENDING。
- `ClickEvent` 10 字段是全管道契约，`eventId` 的 `log-{id}` 确定性语义是幂等体系的根（:10-12）。
- converter 必须吃容器 `ObjectMapper`（:57-62），否则 `LocalDateTime` 让消息全进 DLQ。

## 11. 课后练习

**练习 1（概念理解）**：broker 对一条不可路由的消息回 ack 吗？哪两行配置配合哪段代码让这种消息「逃不掉」？

答案解析：回。mandatory 消息路由失败时 broker 先 `basic.return` 退回原文，再对该消息 ack（框架行为）。配置是 `publisher-returns: true` 与 `template.mandatory: true`（`application.yml:73-75`），代码是 returns 回调把 correlationId 记进 `returnedIds`（`RabbitClickEventPublisher.java:35-39`），publish 在拿到 ack 后查集合发现命中，抛「unroutable event」（:57-59）。只配 confirm 不配 returns，这条消息会被 broker 静默丢弃。

**练习 2（源码定位）**：从 `click.queue` 里的消息到 `click.dlq`，要经过哪些行？分别指出「谁声明这条通道」与「谁决定消息走这条路」。

答案解析：三段。第一段，队列声明死信参数：`RabbitMqConfig.java:31-34` 的 `deadLetterExchange(CLICK_DLX)` 与 `deadLetterRoutingKey(CLICK_DLQ_KEY)`，这决定「消息死后去哪」。第二段，容器决定消息「什么时候死」：`application.yml:81` 的 `default-requeue-rejected: false`，消费异常时容器发 `basic.reject(requeue=false)` 而不是重回队列。第三段，死信链落位：`RabbitMqConfig.java:42-55` 的 DLX、DLQ、绑定，broker 按 `click.dead` 投进 `linkforge.click.dlq`。注意 DLQ 没有消费者（全项目仅一个 `@RabbitListener`，队列是 `CLICK_QUEUE`，`ClickEventConsumer.java:22`）。

**练习 3（执行过程）**：broker 在 `convertAndSend` 返回之后、confirm 到达之前宕机。追行号说出 `publish` 的结局、outbox 行的状态变化、以及最终这条点击会丢吗。

答案解析：:46-50 发送调用正常返回（消息只是进了 TCP 缓冲）。:52-53 的 `getFuture().get(5, SECONDS)` 等不到 ack，5 秒后抛 `TimeoutException`，被 ：63-65 包装成 `IllegalStateException` 抛出。relay 的 catch（`ClickOutboxRelay.java:61-65`）调 `markRetry`，行回到 PENDING 且 attempts+1（`OutboxMapper.java:25-27`）。不丢：broker 恢复前该行持续退避重发，broker 恢复后投递成功。这条链路是 10.2 课「at-least-once」的证据之一。

**练习 4（设计分析）**：有人把 `jacksonMessageConverter` 改成 `new Jackson2JsonMessageConverter()`（不传 mapper）。分「改动后首次部署」与「线上运行一小时后」两个时间点，说出最先暴露的信号。

答案解析：取决于哪一侧用裸 mapper，但本例两个 bean 同容器，发布消费共用这一个 converter。首次部署，relay 一发布就在发送侧序列化失败（`clickedAt` 触发 `InvalidDefinitionException`），异常沿 `publish` 抛出，outbox 行退避重试，30 次后标 FAILED，meter `linkforge.outbox.failed` 开始计数（`ClickOutboxRelay.java:50`），log.error 出现。若假设只有消费端裸奔（发送端正常），信号更隐蔽：confirm 照常 ack、outbox 全 SENT、`lf_click_daily` 不再增长、DLQ 堆积。这正是 `RabbitMqConfig.java:59-60` 注释的「dead-letters every ClickEvent」。防法就是 ：61 传容器 mapper。

**练习 5（调用链）**：`ClickEventConsumer.onMessage` 干净返回和抛异常，分别在 AMQP 层对应什么动作？把动作和 `application.yml` 的哪一行、`RabbitMqConfig` 的哪几行连起来。

答案解析：干净返回对应 `basic.ack`（容器自动做），消息从 `click.queue` 移除。抛异常对应 `basic.reject(requeue=false)`：因为 ：81 配了 `default-requeue-rejected: false`；随后 broker 触发该队列的死信逻辑（`RabbitMqConfig.java:31-34` 声明），经 `click.dlx`（:42-45）以 `click.dead`（:52-55 绑定）落进 `click.dlq`（:47-50）。若 :81 是默认 true，则 reject 变重回队列，DLQ 链闲置，消息热循环。

## 12. 与后续课程的联系

- 10.2 课讲 `publish` 的调用方 `ClickOutboxRelay`：它如何挑行、如何用本课的异常做退避与 FAILED 判定、多实例下为什么注定 at-least-once。
- 10.3 课进消费端：`consume` 的幂等三要素与手工短事务，DLQ 收容的「毒消息」长什么样。
- 11.1 课回到写侧：`ClickRecordService.recordClick` 怎么在同事务里同时产出明细行和 outbox 行（本课 `eventId` 注释的另一半）。
- 13.1 课讲 webhook：webhook 投递行是在 `consume` 事务内生成的，本课的幂等直接决定 webhook 不会重复入队。
- 15.3 课的 `mq-drill.sh` 停掉 RabbitMQ 再恢复，验证的正是本课 confirm 超时路径与 10.2 退避的配合。
