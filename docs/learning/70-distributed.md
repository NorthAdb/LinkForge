# 分布式系统：单机之外的世界

优先级 P2 定向拓展，目标层级 L1–L2：每个概念由问题引出、配本项目的对应物或反例，一段话讲清。共识算法（Raft、Paxos）与 Spring Cloud 实操不在本章范围。

## 先说人话（初学者第一遍从这里读）

分布式系统指部署在多台机器上、靠网络协作的多个进程。把 LinkForge 从一个 `java -jar` 变成两个 `java -jar`，麻烦不是"机器多了"，而是三个物理事实开始生效：**网络会失败**，调用另一台机器随时可能超时，而超时意味着你不知道对方做没做完；**时钟不可信**，每台机器的钟各走各的；**没有共享内存**，两个 JVM 的堆不相连，一切共享都要绕道网络。

第一遍带走三件事：

1. **CAP 的准确说法**：网络分区是分布式系统的前提，不是你挑的选项。分区发生的那一刻，你只能在「拒绝服务保一致」和「继续服务但可能读到旧数据」里选一个；没有分区时，一致和可用都要。
2. **幂等是刚需，不是加分项**：跨网络就必然有重试，重试必然把同一件事做两次。写操作必须设计成"做两次也无害"。本项目消费幂等、创建幂等键、webhook 接收方去重，全是这一条的具体形态。
3. **本章独有资产是多实例边界表**：把 LinkForge 跑成两个进程，哪些机制会坏、为什么坏、怎么缓解。判断标准一句话：状态放在 JVM 里就各自为政，放在 Redis 或数据库里就天然共享。

## 学习目标

读完本章，你应该能：

- 判断一个系统在分区时选了一致还是可用，并用本项目实例佐证（限流 fail-open 选可用，Outbox 统计选最终一致）。
- 用一句话准确表述 CAP，指出「三选二」错在哪。
- 给出最终一致性的定义，说清「窗口」与「收敛」，并用本项目点击统计说明窗口由什么决定。
- 解释为什么 webhook 的重试排程必须用数据库时钟，复述 D9 时钟事故。
- 逐条说出本项目多实例部署会破坏哪些机制，以及每条的缓解方向。

## 前置知识

- [50-redis.md](50-redis.md)：Redis 键值模型、限流、缓存分层、SingleFlight 与分布式锁的边界。
- [51-rabbitmq-outbox.md](51-rabbitmq-outbox.md)：MQ 投递语义、Outbox 模式、幂等消费、对账。
- [13-concurrency.md](13-concurrency.md)：单机并发，synchronized 锁的是什么，SingleFlight 案例逐行讲。
- [14-algorithms.md](14-algorithms.md)：布隆过滤器的算法原理与参数。

## 从单机到两台机器：三个物理事实

### 网络调用不是本地调用：超时意味着不知道

先遇到问题。单机里调用一个方法，结果只有两种：返回，或抛异常。没收到返回值，就确信这段代码没产生效果。换成调用另一台机器上的服务，多出第三种结果：**超时**。请求发出去，5 秒没有响应。这 5 秒里对方可能根本没收到（网络断了），可能正在处理，也可能早就处理完、只是响应在回程丢了。调用方对这三种情况**无法区分**。

（LinkForge 业务逻辑）本项目 webhook 出站调用的超时是连接 2 秒、请求 5 秒（`LinkForgeProperties` 默认值，`WebhookDeliveryRelay` 构造时设置给 HttpClient）。一次 POST 超时后，`WebhookDeliveryRelay` 排下一次重试。对方可能已经收到并处理了这次投递，重试就造成第二次投递。这个"重复"如何兜底，见幂等一节。

记住这句话，它是本章半数概念的根：**网络调用的失败语义是"不知道"，不是"没做"**。

### 每台机器的钟各走各的

（Java 语言行为）`System.currentTimeMillis()` 返回"运行这段代码的机器"自己的时钟。两台机器的时钟读数可以差任意多：晶振有漂移，校时协议（NTP，网络时间协议）只能缩小误差不能消除误差，时区还会让"同一时刻"在不同机器上有不同的本地表示。跨机器比较时间戳推断先后顺序，因此不可靠。本项目为此修过真实事故（[82-linkforge-decisions.md](82-linkforge-decisions.md) D9：20 秒退避被拉成 8 小时），后面专节讲。

### 没有共享内存

（Java 语言行为）同一个 JVM 里，两个线程共享堆，往 `ConcurrentHashMap` 里一放一取就完成了共享。两个 JVM 没有共享堆：实例 A 在自己内存里记下的任何东西，实例 B 看不见。要让多台机器协作，共享状态必须放在一个大家都连得上的地方，数据库或 Redis。

（LinkForge 业务逻辑）本项目把"跨实例共享"的状态放在两处：MySQL（权威数据）和 Redis（限流计数、负缓存、二级缓存）。后面多实例专节会看到：多实例下不坏的机制，状态都在这两个地方；会坏的，状态都在 JVM 里。

## CAP：分区那一刻，一致性还是可用性

### 先遇到问题

Redis 挂了（准确说：应用与 Redis 之间网络不通，或 Redis 进程死了，对应用效果一样）。此刻一个跳转请求进来，限流器要查 Redis 计数才能判断"这个 IP 窗口内超没超量"。查不到。放行，还是拒绝？

这个处境的本质是：应用和 Redis 之间出现了**网络分区**（network partition，两台机器之间的消息可以丢失或延迟的状态）。CAP 定理说的就是分区发生时的选择。

### 三个字母的准确定义

- **C（Consistency，一致性）**：每次读取都返回最近一次写入的值。
- **A（Availability，可用性）**：收到请求的节点给出响应，不是错误，也不是永远等待。
- **P（Partition tolerance，分区容忍性）**：网络分区发生时系统仍能继续运行。

定理的准确表述：**分布式系统无法排除 P**（网络总会出问题），所以设计真正要回答的是：**分区发生的瞬间，C 和 A 保留哪一个**。

### 「三选二」错在哪

流行说法"CAP 三者选其二"有两处误导。第一，它把 P 说成可选项。P 不是选项：只要有多台机器、靠网络连接，分区就可能发生；单机系统才"没有 P"，因为它没有网络这个失败面。第二，它暗示平时系统也缺一样东西。分区不发生时，C 和 A 可以同时满足；此时的真实代价是**延迟**（每次写要同步到足够的副本才能返回）。选择只在分区发生的那一刻才被逼出来。

### 用项目的三条路径练判断

判断口诀：问三个问题。谁和谁分区了？这条业务路径要的是"最新数据"还是"一直有响应"？答错的代价是什么？

（LinkForge 业务逻辑，均已读码核实）

1. **限流**：应用与 Redis 分区。限流是保护措施，不是业务数据。`RedisRateLimiter.acquire` 捕获所有异常，返回"允许"（`failOpen` 方法，构造 `RateLimitDecision(true, 0, 0)`），同时打 `linkforge.ratelimit.failopen` 计数保住可见性。**选 A**：业务继续，代价是限流暂时失效。
2. **点击统计**：MQ 不可达。点击事件先落 Outbox 表（本地事务内），Relay 每 2 秒重试发布。**选最终一致**：暂时读不到新统计，但一条不丢。
3. **跳转读路径**：同一分区下不同步骤选择不同。限流 fail-open；absent 负缓存探测 fail-open（内部捕获异常，返回"未标记"）；**L2 缓存读没有降级捕获**，据代码推断，Redis 连接失败会让这次请求走到 `GlobalExceptionHandler` 的兜底 500，除非 L1 命中绕过了 Redis（热码在 L1 里时不碰 Redis）。这条是诚实清单：CAP 选择不是整个系统一个大开关，是每个交互各自的决定，矩阵见 [82-linkforge-decisions.md](82-linkforge-decisions.md) D11。

还有一条底线：数据库挂了，跳转无法服务。行数据是正确性仲裁（状态、过期、目标都由它说了算），任何"继续服务"都意味着返回未经验证的旧值，项目明确不这么做。**C 的底线在哪，A 的上限就在哪。**

## 最终一致性：窗口与收敛

先遇到问题。点击统计页面的数字，为什么可以比真实点击慢一拍？因为这个数字不走同步写，走一条管道：点击 → Outbox 行（PENDING）→ Relay 批量发布 → MQ → 消费者幂等落库 → 聚合表。每一环都有延迟。

**最终一致性（eventual consistency）的准确定义**：如果没有新的更新，那么在经过一段时间之后，所有后续读取都返回最后一次写入的值。两个词拆开：

- **窗口（window）**：从更新发生到所有读取都能看见它的时间段。窗口内的读可能返回旧值，这是被设计接受的。
- **收敛（convergence）**：副本之间从"不一致"走向"一致"的过程。最终一致承诺收敛一定发生，不承诺多快。

（LinkForge 业务逻辑）本项目的统计窗口由管道各环节延迟决定：Relay 调度周期默认 2 秒（`relay-fixed-delay-ms`）、MQ 转发、消费速度。窗口可观测：积压 gauge 和对账（`ClickLogReconciler` 安全网，见 [51-rabbitmq-outbox.md](51-rabbitmq-outbox.md)）。故障演练可重复执行（`scripts/outbox-drill.sh`、`scripts/mq-drill.sh`）。

两个边界。第一，最终一致不等于"随便不一致"：**无界的窗口是事故**，秒级窗口配上积压观测才是设计。第二，收敛要有安全网：对账发现"管道漏了"就补，这是"最终"二字真正的保险。

## BASE：一句话就够

BASE 是分区时选 A 之后的工程方法论，一句话：**基本可用（Basically Available）、软状态（Soft state）、最终一致（Eventually consistent）**。允许降级服务（限流放行、直查库），允许中间的不一致状态（统计慢一拍），承诺最终收敛。本项目跳转路径在 Redis 故障时的行为就是 BASE 式的。这个缩写记住就够，不用展开。

## 幂等：重试时代的入场券

先运行一段代码。它模拟一次 webhook 投递：请求被接收方处理了，但响应在回程丢失，调用方按重试逻辑再发一次。

```java
import java.util.HashSet;
import java.util.Set;

public class RetryDuplicateDemo {

    // 接收方：模拟"接收方数据库里的两张表"
    static class Receiver {
        int clickCount;
        final Set<String> seenEvents = new HashSet<>(); // idempotency table
        final boolean idempotent;

        Receiver(boolean idempotent) { this.idempotent = idempotent; }

        void receive(String eventId) {
            if (idempotent && !seenEvents.add(eventId)) {
                System.out.println("    receiver: " + eventId + " duplicate, ignored");
                return;
            }
            clickCount++;
            System.out.println("    receiver: " + eventId + " applied, count=" + clickCount);
        }
    }

    // 模拟"响应在回程丢失"：请求已被接收方处理，但调用方永远等不到响应
    static class ResponseLostException extends RuntimeException {}

    static void deliver(Receiver receiver, String eventId) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                System.out.println("  attempt " + attempt + ": send");
                receiver.receive(eventId);
                throw new ResponseLostException();
            } catch (ResponseLostException ex) {
                System.out.println("  attempt " + attempt + ": timeout, response lost");
            }
        }
    }

    public static void main(String[] args) {
        Receiver plain = new Receiver(false);
        System.out.println("== receiver without idempotency ==");
        deliver(plain, "evt_1");
        System.out.println("  final click count = " + plain.clickCount);

        Receiver idem = new Receiver(true);
        System.out.println("== receiver with idempotency table ==");
        deliver(idem, "evt_1");
        System.out.println("  final click count = " + idem.clickCount);
    }
}
```

```text
== receiver without idempotency ==
  attempt 1: send
    receiver: evt_1 applied, count=1
  attempt 1: timeout, response lost
  attempt 2: send
    receiver: evt_1 applied, count=2
  attempt 2: timeout, response lost
  final click count = 2
== receiver with idempotency table ==
  attempt 1: send
    receiver: evt_1 applied, count=1
  attempt 1: timeout, response lost
  attempt 2: send
    receiver: evt_1 duplicate, ignored
  attempt 2: timeout, response lost
  final click count = 1
```

逐行推导。两次运行的区别只在接收方有没有一张"已处理事件"表。上半场没有：两次 `receive` 都把 `clickCount` 加一，一次投递被计成两次点击。下半场有：第二次 `receive` 尝试把 `evt_1` 放进 `seenEvents`，`Set.add` 对已存在的元素返回 false（Java 语言行为，集合语义见 [11-java-collections.md](11-java-collections.md)），于是跳过副作用。重复投递被无害化。

**幂等（idempotency）的定义**：同一操作执行一次和执行多次，对系统的最终效果相同。它为什么在分布式下是刚需？回看"超时意味着不知道"：调用方区分不了"对方没收到"和"对方做了但响应丢了"，重试是唯一能做的恢复动作，而重试必然制造重复。**不做幂等的重试是事故制造机；先有幂等，重试才安全。**

（LinkForge 业务逻辑）项目里的幂等落点：

- **消费幂等**：消费者先抢着往 `lf_consumed_event` 插入 eventId，唯一键冲突（`DuplicateKeyException`）说明已消费过，直接跳过（`ClickStatsService` 的 dedup 事务）。
- **创建幂等**：创建接口接受 `Idempotency-Key` 头做重试去重（[81-linkforge-flows.md](81-linkforge-flows.md) 链 3）。
- **出站投递**：`WebhookDeliveryRelay` 注释明说 at-least-once、接收方按 event id 去重。幂等责任交给了接收方。
- **天然幂等**：`OutboxMapper.markSent` 是 `UPDATE ... SET status='SENT' WHERE id=#{id}`，重复执行结果相同。`UPDATE`、按 id 的 `DELETE`、`SET` 天然幂等；"计数加一"这类不是，必须配幂等键。

## 时间的麻烦：为什么调度要用一个钟

先遇到问题。webhook 第一次投递失败，想让它 60 秒后再试。"60 秒后"由谁测量？如果排程时用一个钟算出时刻，到期判断用另一个钟比对，会发生什么？运行下面的演示。两台"机器"：数据库所在的机器和应用所在的机器，应用的钟慢 90 秒。

```java
public class ClockSkewDemo {

    // two "machines": the DB machine and the app machine, seconds
    static long dbClock = 10_000;
    static long jvmClock = 10_000 - 90; // app machine clock lags 90s

    static long scheduleOnJvm(long backoffSeconds) {
        long next = jvmClock + backoffSeconds;
        System.out.println("  next_attempt_at = " + next + " (jvm " + jvmClock + " + " + backoffSeconds + "s)");
        return next;
    }

    static long scheduleOnDb(long backoffSeconds) {
        long next = dbClock + backoffSeconds;
        System.out.println("  next_attempt_at = " + next + " (db " + dbClock + " + " + backoffSeconds + "s)");
        return next;
    }

    // relay checks due-ness with the DB clock (NOW())
    static boolean dueNow(long nextAttemptAt) {
        return nextAttemptAt <= dbClock;
    }

    public static void main(String[] args) {
        System.out.println("db clock = " + dbClock + ", jvm clock = " + jvmClock + " (jvm 90s behind)");
        System.out.println("== scheduled on jvm clock, checked on db clock ==");
        long next = scheduleOnJvm(60);
        System.out.println("  due now? " + dueNow(next) + "   <- 60s backoff shrank to 0");

        System.out.println("== scheduled and checked on db clock ==");
        next = scheduleOnDb(60);
        System.out.println("  due now? " + dueNow(next));
    }
}
```

```text
db clock = 10000, jvm clock = 9910 (jvm 90s behind)
== scheduled on jvm clock, checked on db clock ==
  next_attempt_at = 9970 (jvm 9910 + 60s)
  due now? true   <- 60s backoff shrank to 0
== scheduled and checked on db clock ==
  next_attempt_at = 10060 (db 10000 + 60s)
  due now? false
```

逐行推导。上半场：排程用应用的钟（9910 + 60 = 9970），到期判断用数据库的钟（9970 <= 10000 成立）。应用的钟慢 90 秒，把 60 秒退避整个吞掉，Relay 下一轮就立刻重试。钟差方向反过来则是另一种事故：退避被拉长。**钟差可能来自时钟漂移（clock skew，两台机器的钟各自走偏），也可能来自时区（同一时刻的不同本地表示）。**

这不是理论推演，是本项目修过的真实事故。[82-linkforge-decisions.md](82-linkforge-decisions.md) D9 记录：webhook 重试的 `next_attempt_at` 最初用 JVM 算好的字面值写入。JVM 在 +8 时区、MySQL 在 UTC，`DATE_ADD(NOW())` 与字面值混用把 20 秒退避拉成 8 小时，重试看起来"死了"。

（LinkForge 业务逻辑）现在的规则写在 `src/main/java/com/linkforge/webhook/mapper/WebhookDeliveryMapper.java` 的类注释里：**调度列用数据库时钟写、也用数据库时钟比**。写是 `DATE_ADD(NOW(), INTERVAL #{backoffSeconds} SECOND)`，比是 `next_attempt_at <= NOW()`，两端都在数据库内部，只有一个钟。业务列（`sent_at`、`created_at`）才是 JVM 时钟，由应用写入（`LocalDateTime` 的时间语义见 [java/10-09-enum-datetime-bigdecimal.md](java/10-09-enum-datetime-bigdecimal.md)）。Outbox 的调度列是同一规则（[51-rabbitmq-outbox.md](51-rabbitmq-outbox.md)）。判断规则一句话：**同一段"等待"的起点和终点必须用同一个钟**。顺带一条日常推论：跨机器的日志时间戳不能直接排序。

## Saga：本地事务序列加补偿

先遇到问题。本地事务能"整体回滚"，是因为所有改动落在一个数据库里，数据库用撤销日志保证要么全做、要么全不算。一旦流程跨到第二个服务、第二个数据库，第一个库的本地事务已经提交，它回滚不了第二个库里发生的事。本地事务的原子性在跨服务边界处失效。

**Saga 的定义**：把一个跨服务的长流程拆成一串本地事务；每一步配一个**补偿动作（compensation）**，即语义上的"撤销"，例如"已扣的库存加回去"，它不是数据库回滚；某步失败时，按相反顺序执行前面各步的补偿。（更早的方案 2PC 用一个协调者让所有参与者"先预备、再提交"换取强一致，但协调者单点、过程阻塞，工程上少用；知道名字即可。）

（LinkForge 业务逻辑，以代码为准）本项目没有跨服务流程，但 webhook 投递链的结构可以对照着看：

1. **主动作序列**：消费者赢得 `lf_consumed_event` 幂等插入后，在同一事务内写统计、插入 `lf_webhook_delivery`（PENDING）；insert 失败则整个消费事务回滚，统计与投递意图同生共死（`WebhookService.recordClickDelivery` 注释明确写了这条契约）。这两步是标准的本地事务。
2. **跨出进程的第三步**：Relay 向外部 HTTP 端点投递。这里没有事务可回滚，用的是**重试加终态**：2xx 记 SENT；网络错误、超时、408/429/5xx 按指数退避重试；其余 4xx 立即失败；重试到上限（`maxAttempts`）记 FAILED（`WebhookDeliveryMapper.markFailed`，注释称它是本仓库的死信等价物），等人工介入或 SQL 回放。
3. **诚实的对应关系**：这套"重试到顶进 FAILED、人工补偿"与教科书 Saga 的"自动反向补偿"不同。项目没有需要自动撤销的已提交副作用，所以不需要完整的补偿链。Saga 这个名字在这里的价值，是给三个词一个坐标：本地事务序列（第 1 步）、跨步失败不回滚而补偿（第 2 步的 FAILED 与回放）、Outbox 是 Saga 的第一环（保证"业务完成"与"事件发出"原子，[51-rabbitmq-outbox.md](51-rabbitmq-outbox.md)）。将来真跨库，Outbox 每库一个加编排，或升级为 Saga 框架，概念层级即可。

## 分布式锁与单机锁的边界

先遇到问题。[13-concurrency.md](13-concurrency.md) 讲过 `synchronized` 锁的是对象监视器，线程靠它互斥。监视器只存在于一个 JVM 内。两个进程（两个 JVM）各有一个对象、各锁各的，互斥等于没发生。

跨进程互斥需要所有进程都认可的"占坑标记"，最小形态是 Redis 分布式锁：`SET lock:key uniqueValue NX EX 30`。`NX` 表示键不存在才设置（抢锁）；过期时间保证持有者崩溃后锁能自动释放；唯一值保证只删自己抢的锁（防止 A 的锁过期后误删 B 抢到的锁）。看起来完备，但有两个经典坑：业务没做完锁先过期，需要续期机制（Redisson 的"看门狗"就是自动续期）；Redis 主从切换可能丢锁。所以分布式锁的正确性永远弱于单机锁，更弱于唯一约束。

（LinkForge 业务逻辑）本项目**有意不用**分布式锁。跳转热路径的目标是"N 个相同读合并成一次行读"，`SingleFlight`（[13-concurrency.md](13-concurrency.md) 案例一逐行讲过）用进程内 `ConcurrentHashMap` 就做到了。它的注释原话：*"Multi-instance deployments merge per process only; a distributed lock would just serialise the reads without removing them"*。分布式锁把读排队，不能消除读；跨实例多一两次行读可以接受。写路径靠数据库唯一约束（`uk_code` 判撞码、消费表唯一键判重复）。

判断口诀：**能靠唯一约束解决的绝不上锁**。唯一约束在存储引擎内原子判定，没有锁单点、没有持锁崩溃的中间态、没有续期复杂度。锁留给约束表达不了的互斥，比如"全系统同一时刻只允许一个实例跑清理任务"。

## Redis：多实例的共享协调点

前面几节反复出现同一个角色。限流计数要跨实例准确，需要所有实例写同一个计数；负缓存要跨实例生效，需要所有实例读同一个标记。这些"放在 JVM 里就各自为政"的状态，本项目统一放进了 Redis。可以把 Redis 理解成"多台机器的共享内存"这个比喻的技术实体：所有实例连同一个 Redis，读写同一份键值。**比喻的失效处**：Redis 的读写仍走网络，它自己也会挂，而且上面所有机制的正确性都押在它身上，它成了新的关键依赖。

读 `src/main/java/com/linkforge/common/ratelimit/RedisRateLimiter.java` 看这个角色怎么落地。滑动窗口的记录存在 Redis 的 ZSET（有序集合，按分数排序的成员结构）里；"读窗口、判超量、记本次"三步在一段 Lua 脚本里原子执行（Lua 脚本在 Redis 内单线程跑完，中间不会插入别的命令）；计时用 Redis 自己的 `TIME` 而不是应用的钟，又见上一节的时钟规则。跨进程限流的正确性完全依赖这个共享协调点可用；它不可用时 fail-open（选 A），并打 `linkforge.ratelimit.failopen` 计数。五层防线的完整降级链见 [50-redis.md](50-redis.md)。

## 多实例边界：把 LinkForge 跑成两份会发生什么

本节是本章的核心资产。假设 `java -jar` 起两个进程，负载均衡各分一半流量。逐条过：现象、根因、缓解。除特别标注外，多实例行为是**据代码推断**（单实例是当前已验证状态），每条的代码事实都已读码核实。

### Bloom 过滤器：播种是每个实例自己的事

先钉死播种时机。`src/main/java/com/linkforge/bootstrap/BootstrapDataLoader.java` 与此**无关**，它只创建演示租户和管理员账号。真正播种的是 `CodeBloomFilter.load()`：监听 `ApplicationReadyEvent`（Spring 框架事件，应用完全就绪后发布），从数据库 `selectAllCodes()` 全量装载；装载失败回到 fail-open（不挡任何请求）。

多实例下的事故链：实例 B 重启，全量播种完成（此时数据库里的码它都有）。之后实例 A 创建新码 X，`ShortLinkService` 创建成功后 `codeBloom.put(code)`，这只改了 **A 进程里**的过滤器。码 X 的流量被负载均衡分到 B：B 的过滤器没有 X，`mightContain` 返回 false，跳转路径直接 404、不碰数据库（`ShortLinkService` 第 382 行起）。X 要等 B 下一次重启重新播种才可见。

三个边界。第一，风险窗口是"实例完成播种之后其他实例新建的码"；播种失败反而是安全的（fail-open 放行）。第二，经典布隆不能删除，删除的码在所有实例上保持阳性，代价只是多一次数据库 miss（[14-algorithms.md](14-algorithms.md)）。第三，README 明确这是 Demo 姿态，缓解方向：共享过滤器（RedisBloom）、创建时广播、或定期重播。

### SingleFlight：只合并本进程的读

`SingleFlight` 的 `inFlight` 是进程内 `ConcurrentHashMap`。两个实例各合并各的：N 个实例最坏 N 次行读，而不是 1 次。注释原话 *"merge per process only"*。评估：每实例一次行读通常完全可接受，不值得为消除它引入分布式协调。这是"进程内优化跨实例失效但代价可容忍"的典型。

### UrlLocalCache：各持一份，TTL 是不一致上界

L1 是进程内 Caffeine 缓存（默认容量 1 万条、写后 10 秒过期，`LinkForgeProperties` 默认值），只在处理请求的实例上生效。事故链：实例 A 更新了某码的目标地址，A 的写路径 `evictCache` 清掉**自己的** L1 和**共享的** Redis L2；实例 B 的 L1 里还留着旧 URL，没有任何机制通知它，用户在 B 上读到旧地址。失效边界（`UrlLocalCache` 注释原话）：*另一个实例的写入不广播，陈旧窗口就是本 TTL（默认 10 秒）*。10 秒是**被声明的不一致上界**，业务接受。对照：absent 负缓存放 Redis，天然跨实例一致。同一个系统里两种选择，差别只在状态放在哪。

### @Scheduled 轮询：两个实例捞同一批

`ClickOutboxRelay.relay()` 和 `WebhookDeliveryRelay.drain()` 都挂着 `@Scheduled`（Spring 框架的定时调度，在本进程内周期执行）。批次查询 `selectPendingBatch` 的 SQL 是"查 PENDING、时间到期、按 id 取 N 条"，**没有实例认领机制**（没有 `FOR UPDATE SKIP LOCKED` 这类抢占式加锁，读 SQL 核实）。两个实例同一秒轮询，会捞到同一批行。

后果逐条看代码。Outbox：两实例都向 MQ 发布同一事件，消费端幂等（`lf_consumed_event`）兜底，业务无损，代价是重复消息。Webhook：两实例都向接收方 POST 同一投递，注释明说 at-least-once、由接收方按 event id 去重。还有一个细节：webhook 的 `markSent` 带 `WHERE status='PENDING'` 守卫，第二个实例的状态更新落空，但 HTTP POST 已经发出去了，守卫拦不住双投递。缓解方向：`FOR UPDATE SKIP LOCKED` 认领、单实例开关、或分布式锁（这里是锁的正当场景）。

### 指标：每个实例一份计数器

Micrometer 计数器（`meterRegistry.counter(...)`）存在 JVM 内存里（框架行为）。指标端点暴露的是本进程的数字：`linkforge.*` 在实例之间互不相加。多实例部署时，抓取端要为每个实例配一个 target，再按实例维度聚合；`failopen` 这类告警指标散在各实例，漏配一个抓取目标就漏警。

### 判断标准：状态放在哪

| 机制 | 状态放在哪 | 多实例表现 |
|------|-----------|-----------|
| 限流计数、负缓存、L2 缓存 | Redis | 天然共享，不坏 |
| Outbox/Webhook 行、唯一键 | MySQL | 天然共享，需防双拉 |
| Bloom 过滤器 | JVM | 各自播种，假阴性 404 |
| SingleFlight | JVM | 各自合并，N 实例 N 读 |
| L1 缓存 | JVM | 各自为政，10 秒上界 |
| @Scheduled 轮询 | JVM | 重复拉取，靠下游幂等兜底 |
| Micrometer 指标 | JVM | 每实例一份，需多 target |

一句话总结：**状态在共享存储里的机制，多实例天然安全；状态在 JVM 里的机制，多实例各自为政**。改造清单从这张表读出来：要么把状态搬出去（Bloom 搬 RedisBloom），要么加协调（轮询加认领），要么接受代价（SingleFlight、L1）。

## 模块化单体：项目现状与演进路径

先遇到问题。上一节列了这么多坑，为什么项目不干脆拆成微服务，让每个域独立部署、独立扩容？

先定义三个词。**单体**：一个进程一个部署单元，所有代码在一个 JVM 里。**模块化单体**：仍是单进程，但包按业务域切分（本项目 `link`、`stats`、`user`、`webhook`、`apikey` 按域分包，`common`、`security` 横切），依赖方向受控，域之间不随便互摸（[80-linkforge-architecture.md](80-linkforge-architecture.md)）。**微服务**：每个域独立进程、独立部署、独立数据库，域之间靠网络调用。

取舍用本章的语言算。微服务的收益：独立部署与扩容、故障域隔离。成本：每次跨域调用走网络，"超时意味着不知道"生效，幂等、重试、补偿全要补齐；跨域一致性从本地事务降级为 Saga 式方案；运维面（部署、监控、排障）按服务数翻倍。判断标准是团队规模与故障域需求，不是"大厂用什么"。**单体够用时上微服务是负优化**：成本真实发生，收益并不存在。

（LinkForge 业务逻辑）本项目的诚实姿态：模块化单体，README「明确不做」清单冻结 Spring Cloud。演进路径的关键问题是**先单体能拆什么**：本项目已经把多实例的安全前提预演完了（Redis 共享协调、幂等消费、Outbox、故障演练），将来若真要拆，第一个候选是边界清晰、读写独立的域（如统计），而且拆出去的第一天就要面对本章的每一个问题。这比"要不要上微服务"是更有价值的面试答案。

P3 认知级，组件图谱每个词一句话：服务发现（解决"服务在哪"，引入注册与心跳）；配置中心（集中配置加动态刷新，引入配置一致性问题）；API 网关（入口路由、认证、限流聚合，引入自身成为单点）；熔断器（持续故障时快速失败保护调用方，引入状态维护；本项目无熔断组件，pom 里没有 Resilience4j 或 Sentinel，超时加 fail-open 承担了最简形态）。不要求实操，不为此上 Spring Cloud（[01-job-market.md](01-job-market.md)）。

## 常见错误与误区

- **「CAP 三选二」**。P 是前提不是选项；选择只在分区发生时；平时 C 和 A 兼得，代价是延迟。
- **「分区是常态」说过头**。分区不是分分秒秒都在发生，而是随时可能发生且无法排除。定理问的是发生那一刻。
- **「加了重试更可靠」**。无幂等的重试把一次故障放大成两次副作用。先幂等，再重试，退避只是节奏。
- **「最终一致=随便不一致」**。窗口要声明、要可观测、要有对账兜底。无界的窗口是事故。
- **「synchronized 能锁住一切」**。监视器只在本 JVM 内有效，两个进程各锁各的。
- **「两台机器的日志按时间戳排一下序就行」**。跨机器时间戳不可比，钟差随时让你把因果排反。

## 自测：六问六答

1. 一句话准确表述 CAP，并指出「三选二」错在哪。
   答：分区无法排除，分区发生时一致与可用不可兼得，平时两者都要。错处：把 P 说成可选项；暗示平时也缺一样。
2. 给出最终一致性的定义。「窗口」和「收敛」各指什么？本项目的统计窗口由什么决定？
   答：无新更新时，经过一段时间后所有读取返回最后一次写入的值。窗口是更新到全体可见的时间段，收敛是副本走向一致的过程。本项目窗口由 Relay 周期（默认 2 秒）、MQ 转发、消费速度决定，有积压 gauge 与对账兜底。
3. 为什么 webhook 的 `next_attempt_at` 必须用数据库时钟写和比？
   答：排程和到期判断必须用同一个钟。JVM 算好的字面值混用 `DATE_ADD(NOW())` 曾把 20 秒退避拉成 8 小时（D9 事故）；现在两端都在数据库内部，钟差与时区差不再进入计算。
4. 多实例下 Bloom 假阴性的完整链路？播种时机在哪？
   答：实例 B 完成启动全量播种后，实例 A 新建码 X 只 `put` 进 A 的过滤器；X 的流量到 B，`mightContain` 返回 false，404 且不碰数据库，直到 B 下次重启。播种在 `ApplicationReadyEvent` 时由 `CodeBloomFilter.load()` 全量执行，`BootstrapDataLoader` 只建租户与账号、与此无关。
5. 判断一个机制多实例下会不会坏，标准是什么？各举一个不会坏与会坏的例子。
   答：状态放在哪。在 Redis/MySQL 的天然共享（限流计数、负缓存、唯一键）；在 JVM 的各自为政（Bloom、L1、SingleFlight、指标）。
6. Saga 与 Outbox 是什么关系？本项目停在 Saga 的哪一环？
   答：Outbox 保证"本地事务完成"与"事件发出"原子，是 Saga 的第一环（可靠事件）；Saga 把跨服务流程拆成本地事务序列加补偿。本项目单库内用 Outbox，webhook 侧以"重试到顶进 FAILED、人工回放"充当补偿，无自动反向补偿链。

## 面试追问：三个主题

**CAP 的准确表述**。追问链：三选二错在哪？分区时本项目哪条路径选了 A、哪条选了最终一致？（限流 fail-open 选 A；统计走 Outbox 最终一致；数据库是 C 的底线。）同一分区下选择为什么可以不同？（选择主体是每个交互，不是整个系统，见 D11 矩阵。）非分区时的代价是什么？（延迟。）

**最终一致 vs 强一致**。追问链：定义与差别？（强一致每次读必返回最近写入；最终一致允许窗口，承诺收敛。）什么时候必须强一致？（余额、库存这类"错了直接损失"的路径。）本项目为什么统计可以最终一致？（数字慢一拍无业务损失，窗口可观测，对账兜底。）

**多实例改造清单**。追问链：跑三个实例要改什么？（按状态放哪过一遍：Bloom 共享化或广播、轮询加认领、抓取多 target；L1 接受 10 秒上界、SingleFlight 接受每实例一次。）Idempotency-Key 和消费幂等多实例下还成立吗？（成立，状态在 MySQL/Redis，这正是把它们做成共享幂等的意义。）哪些可以不改？（SingleFlight、L1，代价可容忍。）

## 本章总结

本章所有概念都从三个物理事实长出来。网络会失败，长出超时语义、幂等（第一个示例）、Saga 的补偿；时钟不可信，长出"同一段等待用同一个钟"与 D9 事故（第二个示例）；没有共享内存，长出 Redis 共享协调点、CAP 的分区取舍、最终一致的窗口与收敛、多实例边界表。判断一个新系统时依次问：分区时每条路径选 C 还是 A？重试都有幂等兜底吗？调度用了几个钟？状态放在哪？四个答案拼起来，就是你对一个分布式系统的判断。

## 下一步

- 幂等、Outbox、对账的实现层：[51-rabbitmq-outbox.md](51-rabbitmq-outbox.md)。
- 限流、缓存分层与分布式锁边界：[50-redis.md](50-redis.md)。
- 跳转五层防线的调用链：[81-linkforge-flows.md](81-linkforge-flows.md)。
- 设计决策与故障案例（D9、D11 原文）：[82-linkforge-decisions.md](82-linkforge-decisions.md)。
- 简历上如何诚实表述分布式经验：[91-interview-project.md](91-interview-project.md)。
