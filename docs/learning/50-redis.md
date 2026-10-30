# Redis 与缓存设计

优先级 P0，目标层级 L3（缓存三问讲透、能解释本项目每层防线的取舍）。

前置知识：[13-concurrency.md](13-concurrency.md)（SingleFlight 与在途读合并）、[14-algorithms.md](14-algorithms.md)（布隆过滤器算法原理）、[31-spring-boot-mvc.md](31-spring-boot-mvc.md)（配置绑定）。

写作环境说明：本机没有 Redis（Docker 不可用）。所有 `redis-cli` 输出都标注"示意，未实测"，语义按 Redis 官方命令文档推写；Lua 脚本的行为描述同样基于官方文档。两个 Java 示例是纯进程内代码，已真实编译运行。

## 学习目标

读完本章你应该能做到：

- 用"内存 + 命令执行单线程 + IO 多路复用"三个机制讲清 Redis 为什么快，并说清 Redis 6 的多线程改了什么、没改什么。
- 说出五种基础结构各自适合什么，以及 LinkForge 为什么只用 STRING 和 ZSET。
- 对缓存穿透、击穿、雪崩各讲两套方案（每套的机制与代价），并解释本项目各选了什么、为什么。
- 推演"先删后写、先写后删、延迟双删"三种写模式的竞态时序，解释项目为什么选"写穿 + 1 秒延迟再删 + 短 TTL 兜底"。
- 逐行读懂 `src/main/resources/redis/sliding_window.lua`，说出每一步在干什么、为什么必须用 Lua。
- 背下跳转读路径的五层防线：每层防什么、失效时降级到哪一层。

## 先遇到问题：每个跳转都查一次数据库会怎样

短链服务的跳转入口 `/r/{code}` 是全系统最热的路径。每个 GET 都要回答同一个问题：这个码指向哪个 URL。最朴素的实现是每请求查一次 MySQL。

想象这个实现上线后跑起来：每一次跳转都要拿一个数据库连接、发一条 `SELECT`、等 MySQL 从磁盘（或缓冲池）把行读出来。跳转是公开接口，任何人都能刷。数据库连接池是有限的（项目默认十来个），连接被跳转请求占满后，登录、创建短链这些管理功能全部排队。数据库每秒能扛的查询数还会先撞上 CPU 或磁盘的墙。

痛点不在"查不到"，在"明明是同一行，被查了一万遍"。这行数据读得多、变得少：一个短码创建后，URL 几乎不改，但被点击几万次。对这种数据，正确做法是把查询结果复制一份放到一个读取快得多的地方，这就是缓存（cache，"把贵的计算或读取结果存起来，下次直接用"）。

## Redis 是什么：给数据库挡流量的内存键值库

准确定义：Redis（Remote Dictionary Server，远程字典服务器）是一个把数据放在内存里的键值数据库。客户端按"键 -> 值"的方式读写：键是字符串，值是五种结构之一（下节讲）。它负责的是"高频读写的小数据"，不负责的是"全量持久化与复杂查询"——那是 MySQL 的职责。

比喻：MySQL 是库房里的档案柜，Redis 是手边的便签。比喻的边界：便签是"你一个人"的副本，写错了只骗你自己；Redis 是所有请求、所有实例共享的副本，它和档案柜之间迟早会有不一致的瞬间。本章后面大量篇幅都在算这笔账：不一致窗口有多长、由什么保证、超了怎么办。

在 LinkForge 里，Redis 的身份是跳转热路径的挡板。数据库行永远是真相（authority，"最终说了算的数据源"），Redis 只缓存两类派生数据：URL 字符串（正缓存）和"确认过不存在"的标记（负缓存）。

## Redis 为什么快：三个机制，一个修正

### 机制一：数据在内存

MySQL 读一行，即使命中缓冲池也要走若干层函数与锁；不命中要等磁盘寻道，毫秒级。Redis 的数据本体就在内存里，一次读写是内存随机访问，量级在亚微秒到微秒。这是"快"的最大头。

### 机制二：命令执行单线程

Redis 在一条线程里逐条执行客户端发来的命令。这不是缺点，是特性：

- 没有锁。一条命令执行时不存在"另一个线程同时改同一份数据"，单条命令天然原子（atomic，"要么整个完成、要么整个没发生，旁观者看不到中间状态"）。
- 没有线程上下文切换。切换指 CPU 保存当前线程状态、换下一个线程，每次切换都有代价；单线程没有这个代价。

单线程也划出了能力边界：单条命令原子，但**多条命令的组合**不原子。两个客户端交错发命令，`A1 A2 B1 B2` 的执行顺序完全可以变成 `A1 B1 A2 B2`。复合操作要原子，就得把多条命令下沉成一段脚本（本章滑动窗口限流就是这么做的），或者加分布式锁（本章末尾讲）。

### 机制三：IO 多路复用

一条线程怎么同时伺候成千上万个连接？答案是 IO 多路复用（I/O multiplexing，Linux 上由 epoll 系统调用实现，"一次调用监听一批 socket，哪个就绪了就处理哪个"）。没有它，一条线程伺候一个阻塞连接，其他连接全饿死。有了它，一条线程在事件循环里反复问内核："这批连接里有谁可读可写了？"谁就绪处理谁，闲着的连接不占线程。

比喻：多路复用像一位前台盯着几十个房间的门铃，谁按铃接待谁；不是雇几十个前台一人守一门。比喻失效处：接待动作本身还是这一位前台做的，所以"处理"仍然串行——这正是下一条修正要讲清楚的。

### 修正：Redis 6 的多线程改了什么

"Redis 是单线程"这句话要精确化。Redis 6.0 起，网络 IO（读 socket、写响应、解析请求协议）可以交给多个 IO 线程并行做（`io-threads` 配置，默认关闭）。但**命令执行仍然只有一条线程**。所以：

- 原子性结论不变：单条命令、一段 Lua 脚本，依然原子。
- 吞吐瓶颈的解法变了：多实例、集群分片分摊命令执行，IO 线程分摊网络开销。

面试里防住"Redis 不是多线程了吗"的追问，就答这一句：多线程 IO 不改命令单线程语义。

## 五种基础结构：项目为什么只用两种

Redis 的值不是只有字符串，而是五种结构。选型的原则不是"功能越多越好"，是"键的数据形状与要做的操作匹配"。

| 结构 | 模型 | 典型场景 | 本项目使用 |
|------|------|----------|-----------|
| STRING | 字节串，可当数字自增 | 缓存、计数器、锁、标记 | URL 缓存、absent 标记、幂等键、日配额 |
| LIST | 双端链表 | 时间线、简单队列 | 未用（队列走 RabbitMQ，见 [51-rabbitmq-outbox.md](51-rabbitmq-outbox.md)） |
| HASH | 字段-值表 | 对象的字段级局部更新 | 未用（值都是整存整取的小字符串） |
| SET | 无序去重集合 | 共同关注、标签交集 | 未用（没有集合运算需求） |
| ZSET | 成员 + 分数，按分数有序 | 排行榜、时间窗 | **滑动窗口限流** |

逐个展开一句话：

- **STRING**：存任意字节串。`INCR` 对整数值原子自增，适合计数器；`SET key value NX PX 10000` 是"不存在才写、带过期"，是锁和幂等标记的原料。项目的 `linkforge:code:{code}` 存 URL，`linkforge:absent:{code}` 存标记 "1"，幂等键存短码，API Key 日配额用 INCR 计数。
- **LIST**：两头插删。项目没有队列需求——点击事件走 MQ（消息队列），不在 Redis 排队。
- **HASH**：一个键下面挂多个字段，`HSET user:1 name x` 只改一个字段。项目的值都是"一个短码对应一个 URL 字符串"，整存整取，HASH 只会多一层间接。
- **SET**：无序、成员唯一，`SINTER` 求交集。项目没有"共同关注"这类需求。
- **ZSET**：每个成员带一个分数（score），按分数排序，`ZRANGE` 按分数段取。项目用它做滑动窗口：score 放微秒时间戳，成员放请求标识，"窗口内的请求数"就是一次 `ZCARD`。

五种结构的操作可以在任何 Redis 环境里练，输出示意，未实测：

```text
（示意，未实测——本机无 Redis，以下按 Redis 命令文档语义推写）
127.0.0.1:6379> SET greet hello
OK
127.0.0.1:6379> INCR hits
(integer) 1
127.0.0.1:6379> RPUSH queue a
(integer) 1
127.0.0.1:6379> HSET u:1 name ann
(integer) 1
127.0.0.1:6379> SADD tags java
(integer) 1
127.0.0.1:6379> ZADD board 100 ann
(integer) 1
127.0.0.1:6379> ZRANGE board 0 -1 WITHSCORES
1) "ann"
2) "100"
```

## 缓存三问：每个问题两套方案，项目各选了什么

"缓存三问"指穿透、击穿、雪崩。三个词描述的是同一条链路（缓存 -> 数据库）上三种过载形态。先记一句话谱系，再逐个拆：

- 穿透：查**根本不存在**的键，每次都白跑一趟数据库。
- 击穿：**一个热点键**失效的瞬间，并发洪峰同时回源。
- 雪崩：**一大批键**同时失效，整体洪峰砸向数据库。

回源（fallback to origin）指缓存未命中后去数据库查并把结果填回缓存。

### 穿透：查不存在的键，每次都白跑

场景：攻击者拿着随机生成的短码刷 `/r/{code}`。这些码从没创建过，缓存永远未命中，每个请求都打穿到 MySQL。缓存对"不存在"无能为力——你没法缓存一个"什么都没有"。

**方案一：空值缓存（负缓存）。** 数据库确认"这个键不存在"之后，往 Redis 写一个占位值（sentinel value，标记用的固定字符串），配短 TTL。后续同样查询直接命中占位值返回 404。代价有两个：不存在的键可以有无限多个，每个占位值都占内存，攻击者换个键就绕过一层；数据库里后来真的创建了这个键，占位值会造成短时间的错误 404，所以创建成功后必须清掉占位值。

**方案二：布隆过滤器。** 算法原理（位数组加 k 个哈希函数，"答没有是权威的，答可能有含误报"）在 [14-algorithms.md](14-algorithms.md) 已逐行讲过，这里只讲缓存视角：把所有存在的键播种进过滤器，挡在读路径最前面，"肯定不存在"的请求在碰数据库之前就被 404。代价：误报无害（多走一次正常流程）；经典布隆**不能删除**（删掉的码仍然答"可能有"）；进程内过滤器在多实例间不同步，每个实例都要播种。

项目两个都用了，各管一段：Guava 布隆（`CodeBloomFilter`，进程内）挡第一道，`mightContain == false` 是权威否定，直接 404 不碰 MySQL；空值缓存（`linkforge:absent:{code}`）兜布隆的误报、布隆被禁用、以及逻辑删除后的码。第三层（空值缓存）的选型细节在五层防线一节展开。

### 击穿：热点键失效瞬间，洪峰同时回源

场景：某个码突然爆了（社交平台转发）。它的缓存过期那一刻，一万个并发请求同时未命中、同时回源、同时回填。击穿与穿透的区别：穿透查的是不存在的键，击穿查的是**存在且很热**的键，只是缓存恰好过期了。

**方案一：互斥重建。** 缓存未命中后先抢一把锁（通常是 Redis 锁，见本章末尾），抢到的查库回填，其余的等待或先返回旧值。代价：读被串行化，等待的请求堆积占资源；锁自己还要防死锁（必须带 TTL）和误删（必须带唯一值），复杂度实打实。

**方案二：逻辑过期。** 缓存值永不设 TTL，但在值里带一个"逻辑过期时间"字段。读到已逻辑过期的值，不阻塞，由一个线程异步去重建，其他请求继续用旧值。代价：总有一段旧值窗口；实现复杂；热点键常驻内存。

项目的选择是第三条路：**合并行读**。`resolveTarget` 里第 410 行 `singleFlight.run(code, () -> rowLoader.load(code))`——同一个码的 N 个并发请求合并成一次数据库行读，其余请求"跟车"。机制细节（领导者/跟随者、500 毫秒超时自读、两参 remove）在 [13-concurrency.md](13-concurrency.md) 逐行讲过，这里只讲缓存视角的取舍：击穿的本质是"重复劳动"，SingleFlight 直接消掉重复劳动本身；而互斥锁只是把重复劳动排队，一次劳动还是要做 N 次。项目的 javadoc 原话：分布式锁"只会串行化读取，并不消除读取"（a distributed lock would just serialise the reads without removing them，见 `src/main/java/com/linkforge/link/support/SingleFlight.java` 类注释）。

### 雪崩：一大批键同时失效

场景：一批键在同一秒被写入（批量导入），TTL 全是 3600 秒，于是 3600 秒后它们手拉手一起过期，下一瞬间的洪峰整体砸向数据库。击穿是一个键的极端，雪崩是一批键的常态。

**方案一：TTL 随机抖动（jitter，"在基准值上加一个随机量"）。** 把每个键的 TTL 从固定值变成 `base + uniform[0, jitter]`，失效时间点被摊开，数据库收到的回源变成细水长流。代价几乎为零：TTL 略微变长，仅此而已。这是默认该做的动作。

**方案二：多级缓存。** 进程内缓存（L1）挡在 Redis（L2）前面：热点键先打 L1，命中就省一次 Redis 往返（RTT，round-trip time，一次网络来回的耗时）；L1 未命中再打 L2。代价：L1 是每个实例各一份，实例之间不共享失效通知，不一致窗口等于 L1 的 TTL；写路径清缓存时必须逐级清，漏一级就脏一级。

项目两个都用了：正缓存 TTL = `3600 + uniform[0, 300]` 秒（`cache-ttl-seconds`/`cache-ttl-jitter-seconds`，`src/main/resources/application.yml` 第 102 到 104 行）；L1 是 Caffeine（`UrlLocalCache`，10000 条 / 10 秒），L2 是 Redis。抖动公式下一节用可运行的代码讲。

## 缓存一致性：三种写模式的时序推演

新问题：缓存的值和数据库的值不一致怎么办？先立一条纪律——**本项目不追求消灭不一致，追求把不一致限制在可说明的窗口里，并且关键状态永远以数据库行为准**。在这个前提下比较三种写模式。

推演的背景是一对并发角色：

- 写线程 W：要把短码 abc 的 URL 从 U1 改成 U2。
- 读线程 R：正在读短码 abc，读到一半。

### 模式一：先删后写（删缓存 -> 写数据库）

1. W 先删缓存键。
2. R 未命中缓存，去数据库查，**在 W 提交前**读到旧值 U1。
3. W 提交数据库。
4. R 把 U1 回填进缓存。

结果：缓存里躺着 U1，而数据库是 U2。这个脏值没有任何写路径会再来清它，要脏到 TTL 自然过期为止。窗口 = 整个 TTL，最差的一种。初学者选这个顺序通常出于直觉"先清旧的再写新的"，但删除和提交之间的空档正好被读请求钻了空子。

### 模式二：先写后删（写数据库 -> 删缓存，Cache-Aside 的标准写法）

正常时序：W 提交数据库，再删缓存；之后的读未命中、回填新值，一致。它剩余的竞态窗口是这样：

1. R 未命中缓存（此刻缓存为空或刚被别人删过），去数据库查，在 W 提交前读到 U1。
2. W 提交数据库。
3. W 删缓存（删了个寂寞，R 还没回填）。
4. R 把 U1 回填进缓存。

结果同样是脏到 TTL。但注意窗口宽度：这个竞态要求 R 的"查数据库"这一步恰好横跨 W 的"提交 + 删除"两步，发生概率远低于模式一。先写后删成为最常用的默认写法。

### 模式三：延迟双删（写完，延迟 d 秒后再删一次）

在模式二的基础上加第二次删除。若第二次删除发生在竞态回填（上面第 4 步）之后，脏值被清掉；若回填更晚，仍会残留。所以 d 必须大于"一次读的完整生命周期"（查库 + 回填），它是用一次延时把脏窗口从"TTL 那么长"压缩到"d 那么长"。代价：写路径多一次异步删除、多一个定时器；d 拍小了覆盖不住竞态。

### 项目选择：写穿 + 1 秒延迟再删 + 短 TTL 兜底

`src/main/java/com/linkforge/link/service/ShortLinkService.java` 的写路径把三个动作叠在一起：

```java
// ShortLinkService.java 第 63 行
private static final long STALE_WRITEBACK_CLEAR_MS = 1000;
```

改 URL（`update`，第 288 到 303 行）的完整时序：

1. 第 288 行 `singleFlight.peek(code)`：快照当前在途的行读（flight，"正在飞行中的一次合并读"）。
2. 第 297 行 `shortLinkMapper.updateById(link)`：提交数据库。
3. 第 298 行 `singleFlight.forget(code, priorFlight)`：把快照的那个飞行摘除。目的：提交前已在途的读不能让后来的请求"跟车"读到旧行。摘除后，下一个请求重新起飞行，读到新行。残留窗口（javadoc 写明）：在快照与提交之间起飞行的读仍会读到旧行，量级是单次查询延迟。
4. 第 301 行 `cacheUrl(code, effectiveUrl)`：写穿。新值是已知的（改后的 URL），所以直接写进 L1 和 L2，写完立刻读到的就是新值。
5. 第 302 行 `scheduleStaleClear(code)`：1 秒后再删一次，清掉并发读的旧值回填（模式二第 4 步那种）。

停用/删除（`setStatus` 第 314 到 323 行、`delete` 第 326 到 333 行）不同：新值是"应该没有缓存"，所以立即 `evictCache`（第 321 行，L1 失效 + Redis 删除），再同样 `scheduleStaleClear` 延迟清一次。

三个兜底让"1 秒不够"也不致命：

- **TTL 上界**：正缓存最多活 3600 + 300 秒，脏值不可能永生。
- **行是真相**：跳转路径每个请求都读数据库行（第 410 行），禁用、过期、密码、恶意域名这些判定全部基于行，缓存不遮蔽它们。缓存里脏一个 URL，最坏后果是"跳到旧 URL 最多一秒"（注释原文 ~1s double-delete window）。
- **延迟删挂在专用线程上**：第 80 到 84 行建了一个单线程的 daemon 调度器（`cache-evictor`），第 642 到 650 行用它延迟执行再次删除。单线程够用（延迟删没有并发需求），daemon 不阻止进程退出，`@PreDestroy` 关闭它。线程设计纪律见 [13-concurrency.md](13-concurrency.md)。

为什么接受 1 秒不一致？算账：把强一致做出来，要么放弃缓存（性能归零），要么读写都加锁（复杂度归无穷）。项目的业务里"URL 变了但旧 URL 还能用 1 秒"的代价接近零，所以这笔账选了第三条路。

## TTL：你声明的不一致上界

TTL（time-to-live，键的存活时间，到期自动消失）在缓存语境下的语义就是一句话：**TTL 设多长，等于公开声明"我允许缓存比数据库旧多久"**。项目正缓存声明的是约 1 小时（可容忍 URL 变更后旧值最多活一小时，靠写路径主动清理把实际窗口压到 1 秒）；absent 负缓存声明的是 60 秒（一个误判的"不存在"最多骗人 60 秒）。

过期键怎么被删：惰性删除（访问时发现过期才删）加定期删除（后台周期性抽样删除一部分过期键）的混合。只靠惰性会让从不被访问的过期键永远占内存；只靠定期会漏。两种混合是 Redis 的默认行为（基于 Redis 官方文档，本机未实测）。

### 抖动公式：base + uniform[0, jitter]

项目的公式在 `ShortLinkService` 第 584 到 590 行，javadoc 原话 "TTL = base + uniform [0, jitter] (upward only)；jitter<=0 degenerates to base"：

```java
// 片段：ShortLinkService.java 第 584 到 590 行
static long jitteredTtlSeconds(long baseSeconds, long jitterSeconds) {
    if (jitterSeconds <= 0) {
        return baseSeconds;
    }
    return baseSeconds + ThreadLocalRandom.current().nextLong(jitterSeconds + 1);
}
```

`nextLong(jitterSeconds + 1)` 产生 0 到 jitter 的均匀随机整数（Java 语言行为：`nextLong(bound)` 半开区间 [0, bound)，传 jitter+1 恰好含两端）。为什么只向上抖：向下抖会缩短声明的不一致上界之外的保护时长；向上抖只是让键多活一点，兜底（行读）不受影响。生产代码用 `ThreadLocalRandom`（不可播种，每次不同）；下面的演示换用可播种的 `Random` 让输出可复现：

```java
import java.util.Random;

public class TtlJitterDemo {
    // LinkForge 公式（ShortLinkService.jitteredTtlSeconds 的可复现版）：
    // TTL = base + uniform [0, jitter]，只向上抖。
    static long jitteredTtl(long baseSeconds, long jitterSeconds, Random rnd) {
        if (jitterSeconds <= 0) {
            return baseSeconds;
        }
        return baseSeconds + rnd.nextLong(jitterSeconds + 1);
    }

    public static void main(String[] args) {
        System.out.println("base=3600 jitter=300 seed=42 -> " + jitteredTtl(3600, 300, new Random(42)));
        System.out.println("base=3600 jitter=300 seed=43 -> " + jitteredTtl(3600, 300, new Random(43)));
        System.out.println("base=3600 jitter=0   seed=42 -> " + jitteredTtl(3600, 0, new Random(42)));
        System.out.println("base=3600 jitter=-5  seed=42 -> " + jitteredTtl(3600, -5, new Random(42)));
    }
}
```

```text
base=3600 jitter=300 seed=42 -> 3653
base=3600 jitter=300 seed=43 -> 3823
base=3600 jitter=0   seed=42 -> 3600
base=3600 jitter=-5  seed=42 -> 3600
```

推导：前两行是同一批键换种子（模拟不同请求各自随机），一个 3653、一个 3823，失效时刻被摊开在 3600 到 3900 秒之间；后两行是退化分支，jitter 不大于 0 就原样返回 base。读者可自己改种子复跑，输出稳定可复现。

## 滑动窗口限流：一段 Lua 脚本的五步

限流（rate limiting，"单位时间内最多放行 N 个请求"）是跳转路径的第一层。项目用的算法是滑动窗口日志（sliding window log，"记录每个放行请求的时间戳，窗口内的条数就是当前计数"），实现在 Redis ZSET 上，用 Lua 脚本保证原子。

### 为什么需要脚本：复合操作的竞态

不用脚本，Java 侧要发四条命令：`ZREMRANGEBYSCORE` 清出窗成员、`ZCARD` 数窗口内条数、比较是否超限、`ZADD` 记账。命令执行单线程保证的是**每条**命令原子，四条之间可以插入其他客户端的命令。推演超发：

1. 限额 max=100，窗口内已有 99 条。
2. 请求 A 执行 `ZCARD` 得 99，未超，准备 `ZADD`。
3. 请求 B 也执行 `ZCARD` 得 99（A 还没 ZADD），未超。
4. A、B 都 `ZADD`，窗口内变成 101 条。

超发了 1 条。请求越并发，超发越多。把四条命令合成一段脚本交给 Redis，脚本执行期间服务器不处理任何其他客户端命令，竞态从根上消失。

### EVAL 的原子性从哪来

`EVAL script numkeys key... arg...` 是执行脚本的命令：第一参数是 Lua 源码，第二参数声明后面有几个键名，键名进脚本里的 `KEYS` 表，其余参数进 `ARGV` 表；脚本里用 `redis.call('命令', ...)` 调 Redis 命令。原子性的来源是官方文档写明的语义：脚本执行期间，服务器的一切其他活动被阻塞（Redis 文档原话 "all server activities are blocked during its entire runtime"，本文标注基于官方文档，未实测）。也就是单线程命令执行把"整段脚本"当成"一条命令"。

两个工程细节：

- **脚本缓存**：`EVAL` 每次传全文浪费带宽。Redis 把执行过的脚本按 SHA1 缓存，`EVALSHA` 按摘要执行，未缓存时报 `NOSCRIPT`，客户端回退 `EVAL`。Spring Data Redis 的 `RedisTemplate.execute(script, ...)` 默认就是这个"先 EVALSHA、NOSCRIPT 再 EVAL"的流程（框架行为，官方文档写明）。
- **TIME 的许可**：早期 Redis 要求脚本"确定性"（同样输入产生同样写入），`TIME` 这类非确定性命令之后不允许再写数据。Redis 5.0 起默认按"效果复制"（把脚本产生的写命令本身发给副本和 AOF，而不是重放整段脚本），这个限制随之解除（Redis 文档）。所以本项目的脚本才能先 `TIME` 再 `ZADD`。

### Java 侧：RedisRateLimiter

`src/main/java/com/linkforge/common/ratelimit/RedisRateLimiter.java` 三件事：

```java
// 片段：RedisRateLimiter.java 第 27 到 32 行（静态初始化，进程内只做一次）
private static final DefaultRedisScript<List> SLIDING_WINDOW_SCRIPT = new DefaultRedisScript<>();

static {
    SLIDING_WINDOW_SCRIPT.setLocation(new ClassPathResource("redis/sliding_window.lua"));
    SLIDING_WINDOW_SCRIPT.setResultType(List.class);
}
```

脚本从 classpath 加载，返回类型声明为 List（对应 Lua 返回的 `{allowed, count, retry_ms}` 数组，Lua 的表数组会被客户端转成 Java List）。

```java
// 片段：RedisRateLimiter.java 第 44 到 57 行
result = stringRedisTemplate.execute(SLIDING_WINDOW_SCRIPT, List.of(key),
        String.valueOf(max), String.valueOf(windowSeconds * 1000L),
        String.valueOf(ThreadLocalRandom.current().nextLong()));
// ...判空后：
boolean allowed = result.get(0) == 1L;
long retryMs = Math.max(1L, result.get(2));
int retryAfter = (int) Math.min(windowSeconds, (retryMs + 999) / 1000);
return new RateLimitDecision(allowed, retryAfter, result.get(1));
```

三个参数依次是 max（限额）、window_ms（窗口毫秒）、nonce（随机数，给 ZADD 当成员后缀）。`(retryMs + 999) / 1000` 是向上取整。`Math.min(windowSeconds, ...)` 的原因：窗口里最老的条目最迟在窗口结束时出窗，等待不可能超过窗口长度。

异常处理是 fail-open（fail-open，"组件故障时放弃限制、保持可用"）：`execute` 抛异常或返回 null（第 48 到 53 行）都进 `failOpen`，打 warn 日志、给指标 `linkforge.ratelimit.failopen` 计数、返回"放行"。javadoc 的理由：限流是保护措施不是安全边界，Redis 挂了宁可全放行，也不能把跳转打挂；每次降级都计数，让"静默失效的限流"始终可见。

429 的下游：调用方拿到 `decision.retryAfterSeconds()` 抛 `RateLimitedException`（`src/main/java/com/linkforge/common/exception/RateLimitedException.java`），`GlobalExceptionHandler` 第 18 到 23 行把它转成 429 响应并写入 `Retry-After` 响应头。

### sliding_window.lua 逐行

`src/main/resources/redis/sliding_window.lua` 全文 18 行，五步。语义描述基于 Redis 命令文档。

```lua
-- 片段：sliding_window.lua 第 1 到 7 行
-- Sliding window log (B6): KEYS[1] = ZSET key; ARGV = max, window_ms, nonce.
-- The clock is Redis TIME (µs) — the JVM never passes a timestamp, so clock
-- drift between instances cannot skew the window.
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000000 + tonumber(t[2])
local max = tonumber(ARGV[1])
local win = tonumber(ARGV[2]) * 1000
```

第 4 到 5 行是第一步：`TIME` 返回"Unix 秒 + 本秒内的微秒"两个字段，脚本拼成微秒时间戳。时钟选 Redis 的而不是 JVM 传参的，原因是脚本头注释的原话：多实例部署时各 JVM 时钟会漂移（drift，"钟走得快慢不一"），用 Redis 单一时钟，窗口不会被实例间的钟差扭曲。

```lua
-- 片段：sliding_window.lua 第 8 到 9 行
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - win)
local n = redis.call('ZCARD', KEYS[1])
```

第二步左缩（第 8 行）：删掉分数不大于 `now - win` 的成员，即出窗的旧请求。`-inf` 到 `now - win` 是闭区间，恰好压线出窗的成员被删。第三步（第 9 行）：`ZCARD` 数剩余成员，这就是"窗口内的放行数"。

```lua
-- 片段：sliding_window.lua 第 10 到 14 行
if n < max then
  redis.call('ZADD', KEYS[1], now, now .. '-' .. ARGV[3])
  redis.call('PEXPIRE', KEYS[1], ARGV[2])
  return {1, n + 1, 0}
end
```

第四步（第 10 到 14 行）：没满就记账并放行。`ZADD` 的分数是 now，成员是 `now-nonce`——成员必须唯一：ZSET 里同成员再 ZADD 是覆盖分数不是新增，两个请求落在同一微秒时，没有 nonce 就会挤成一个成员，少记一次、超发。`PEXPIRE` 给整个键设毫秒级 TTL（`ARGV[2]` 就是 window_ms）：ZSET 没有成员级 TTL，只能整键过期；窗口里最老成员的出窗时刻不晚于"最后一次 ZADD 时刻 + win"，而 TTL 恰好从最后一次 ZADD 起算、长度为 win，覆盖得住所有成员的出窗。只有放行才 `ZADD`：被拒请求不占名额，攻击者刷请求不能把自己永久锁死在窗口里。

```lua
-- 片段：sliding_window.lua 第 15 到 18 行
local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
local retry_ms = math.ceil((tonumber(oldest[2]) + win - now) / 1000)
if retry_ms < 1 then retry_ms = 1 end
return {0, n, retry_ms}
```

第五步（第 15 到 18 行）：满了就拒绝，并算出诚实的 `Retry-After`。`ZRANGE key 0 0 WITHSCORES` 取分数最小的成员，即最老的放行记录；它出窗还需要 `oldest + win - now` 微秒，除以 1000 向上取整成毫秒，下限 1。返回 `{0, n, retry_ms}`，Java 侧再钳制到窗口秒数（上文 `Math.min`）。

窗口行为用 Java 进程内模拟（ZSet 用 `TreeMap` 按分数有序这一性质复刻，`firstKey()` 就是最老成员）：

```java
import java.util.NavigableMap;
import java.util.TreeMap;

public class SlidingWindowSim {
    static final int MAX = 3;
    static final long WINDOW_MS = 1000;
    // ZSET 的 Java 替身：score（微秒时间戳） -> 成员串。TreeMap 按 score 有序，
    // firstKey() 就是最老成员，等价于 ZRANGE key 0 0。
    static final NavigableMap<Long, String> window = new TreeMap<>();
    static long nonce = 0;

    static String tryAcquire(long nowMs, String caller) {
        long nowUs = nowMs * 1000;
        long cutoff = nowUs - WINDOW_MS * 1000;
        // 步骤 1：ZREMRANGEBYSCORE key -inf (now-win)，删掉出窗成员
        window.headMap(cutoff, true).clear();
        // 步骤 2：ZCARD 数窗口内剩余成员
        int n = window.size();
        if (n < MAX) {
            nonce++;
            // 步骤 4：ZADD，成员 = now-nonce 保证唯一，score = now
            window.put(nowUs, nowUs + "-" + nonce);
            return "ALLOW   " + caller;
        }
        // 步骤 5：窗口满，取最老成员算 Retry-After（ceil，模仿 Lua 的 math.ceil）
        long oldest = window.firstKey();
        long retryMs = (oldest + WINDOW_MS * 1000 - nowUs + 999) / 1000;
        return "REJECT  " + caller + " retryAfter=" + Math.max(1, retryMs) + "ms";
    }

    public static void main(String[] args) {
        long[] times = {0, 100, 200, 300, 900, 1300};
        String[] names = {"req1", "req2", "req3", "req4", "req5", "req6"};
        for (int i = 0; i < times.length; i++) {
            System.out.println("t=" + times[i] + "ms  " + tryAcquire(times[i], names[i]));
        }
    }
}
```

```text
t=0ms  ALLOW   req1
t=100ms  ALLOW   req2
t=200ms  ALLOW   req3
t=300ms  REJECT  req4 retryAfter=700ms
t=900ms  REJECT  req5 retryAfter=100ms
t=1300ms  ALLOW   req6
```

推导：限额 3 次 / 1000 毫秒。req1 到 req3 放行，窗口里有 0、100、200 三条。req4 在 300ms 到达，满员；最老成员 0ms 出窗还差 700ms，所以 `Retry-After=700ms`。req5 在 900ms 仍满员，0ms 的成员出窗只剩 100ms。req6 在 1300ms 到达：三条成员的分数都小于等于 300000（`now - win` 的微秒值），第一步左缩把它们全删光，窗口空了，放行。对照 Lua 脚本：五步一一对应，被拒的 req4、req5 都没有 ZADD，不占名额。

### 键命名与结果对象：RateLimitKeys 与 RateLimitDecision

`src/main/java/com/linkforge/common/ratelimit/RateLimitKeys.java` 全文只有一个方法：

```java
// 片段：RateLimitKeys.java 第 13 到 15 行
public static String sw(String keyPrefix, String logicalKey) {
    return keyPrefix + "sw:" + logicalKey;
}
```

所有滑窗键挂在 `{prefix}sw:` 命名空间下。javadoc 记录了一次事故（D-Bw345-10）：固定窗口时代的 STRING 计数器键会残留，ZSET 命令打在 STRING 键上，Redis 报 WRONGTYPE 错误，limiter 异常、fail-open 静默放行。教训：**不同数据结构的键用命名空间隔离**，一个前缀只放一种结构。

`src/main/java/com/linkforge/common/ratelimit/RateLimitDecision.java` 是一个 record（Java 16 特性，见 [java/10-10-java17.md](java/10-10-java17.md)）：

```java
// 片段：RateLimitDecision.java 第 7 行
public record RateLimitDecision(boolean allowed, int retryAfterSeconds, long count) {
}
```

三个字段：放行与否、被拒时的建议等待秒数（javadoc 写明只在 `allowed == false` 时有意义）、窗口内当前计数（供指标用）。

### 限流的三个配置入口与固定窗口的去处

`src/main/resources/application.yml` 第 168 到 178 行的 `linkforge.rate-limit` 配了三个入口，对应 `LinkForgeProperties.RateLimit`（`src/main/java/com/linkforge/config/LinkForgeProperties.java` 第 181 到 196 行）：

| 入口 | 键（逻辑部分） | 默认限额 | 调用处 |
|------|----------------|----------|--------|
| 开放注册 | `register:{ip}` | 5 次 / 600 秒 | `AuthService.register` 第 64 到 67 行 |
| 创建短链 | `create:user:{userId}` | 30 次 / 60 秒 | `ShortLinkService.create` 第 94 到 96 行 |
| 公开跳转 | `redirect:ip:{ip}` | 300 次 / 60 秒 | `ShortLinkService.resolveTarget` 第 371 到 373 行 |

另有两组走各自配置：密码解锁是"每 IP + 每码"两级（`LinkForgeProperties.LinkAccess`），API Key 是"每分钟 + 每天"两级。其中每天那一级用的是固定窗口版本 `tryAcquireFixedWindow`（`RedisRateLimiter.java` 第 71 到 96 行，`INCR` + 首次 `EXPIRE`，配额键挂日期，如 `...day:1:20261028`）：自然日本来就是日历边界，整点重置是业务语义的一部分，边界突刺无所谓。滑窗版每请求要维护成员，日配额一天一万条也没问题，但日历重置滑窗反而表达不了。这就是"算法跟着语义选"：精确滚动次数用滑窗日志，日历配额用固定窗。与 Redis 无关的 MQ 链路（outbox 等）见 [51-rabbitmq-outbox.md](51-rabbitmq-outbox.md)，本章不展开。

## 分布式锁（概念级）与 SingleFlight 的边界

分布式锁（distributed lock，"用共享存储实现跨进程互斥"）解决的是另一个问题：多个 JVM 实例都要做"同一时刻只能做一次"的事。进程内的 `synchronized` 管不了别的进程，需要一个所有进程都看得见的裁判，Redis 就是常选。

概念级的最小用法三件套（命令输出示意，未实测）：

1. **加锁**：`SET lock:key uniqueValue NX PX 10000`。`NX` 保证只有一个客户端写成功（写成功的持有锁），`PX 10000` 给锁 10 秒 TTL，持有者崩溃后锁自动过期，不会死锁。`uniqueValue` 是每个持有者的唯一标识（如 UUID）。
2. **解锁**：不能只 `DEL`。推演误删：A 的锁到期自动释放，B 抢到锁；A 回来执行 `DEL`，删掉的是 B 的锁。所以解锁必须"确认是自己的才删"：比较值相等再删。`GET` 和 `DEL` 是两条命令，之间锁可能易主，同样有竞态——正确做法是又一段 Lua 脚本，"值相等才 DEL"原子完成。
3. **续期/重入**等是工程化加项，本篇不展开；真要互斥写操作时再引入。

```text
（示意，未实测——按 SET 命令文档语义推写）
127.0.0.1:6379> SET lock:rebuild uuid-A NX PX 10000
OK
127.0.0.1:6379> SET lock:rebuild uuid-B NX PX 10000
(nil)
```

与项目的 SingleFlight 对比，差别在"互斥"与"合并"：

| | 分布式锁 | SingleFlight（进程内） |
|---|---|---|
| 作用域 | 跨进程 | 单个 JVM 内 |
| N 个并发请求的结果 | 排队串行执行 N 次 | 合并成 1 次执行，其余拿同一结果 |
| 适用 | 必须互斥的**写**操作 | 可共享的**读**操作 |
| 失败模式 | 忘 TTL 死锁、误删他人锁 | 跟随者超时自读（fail-open） |

项目跳转热路径需要的是合并读，不是互斥：锁只会把读串行化，不消除读。所以读路径用 SingleFlight，而项目里唯一接近锁的用法是幂等键的 `setIfAbsent(idemKey, "pending", Duration.ofSeconds(5))`（`ShortLinkService.java` 第 108 到 109 行，Spring 语义等价于 `SET key value NX PX 5000`，框架行为）——防的是同一幂等键并发创建两个链接，那才是"只能做一次"的写。

## 五层防线总览：每层防什么，失效降级到哪

把前面所有机制接回一条路径。`resolveTarget`（`ShortLinkService.java` 第 368 到 466 行）从上到下过五道门：

| 层 | 防什么 | 失效（组件坏/未命中）时降级到 |
|----|--------|------------------------------|
| 1 滑窗限流 | 流量洪峰打挂服务（保护措施，非安全边界） | Redis 挂 -> fail-open 放行 + 计数，继续第 2 层 |
| 2 布隆过滤器 | 从未创建的码穿到 MySQL | 禁用/未就绪/播种失败 -> `mightContain` 恒 true，继续第 3 层 |
| 3 空值缓存 | DB 确认过不存在的码反复查库 | 探测异常 -> fail-open 视为未命中，继续第 4 层 |
| 4 L1/L2 缓存 | 每请求付 Redis 往返 | L1 miss -> 查 L2；L2 miss -> 第 5 层 |
| 5 singleflight + 行读 | 行状态错判（禁用/过期/密码/脏行） | 跟车超 500ms -> 自己读；**行永远读 DB，无降级** |

读路径逐行（行号均指 `ShortLinkService.java`）：第 371 到 377 行限流，超限抛 429；第 382 到 385 行布隆，`false` 直接 404（指标 `linkforge.bloom.rejected`）；第 390 到 393 行查 absent 键（指标 `linkforge.cache.absent.hit`），命中 404；第 398 到 405 行 L1（Caffeine）再 L2（Redis GET，指标 `linkforge.cache.l2`）；第 410 行 singleflight 合并行读；第 411 到 416 行行不存在则 `markAbsent`（写 absent 键，TTL 60 + 抖动 30 秒）并 404；第 417 到 428 行按行判状态（`status != 1` 404、过期 410、行内 URL 不可渲染 410）；第 429 行决定跳转目标：缓存有值用缓存值，否则用行值，且第 436 到 441 行在两个校验都通过后回填 L1 或写穿 L2；第 445 到 456 行 B9 域名判定和密码门都基于**行**的 URL 与行内哈希，旧缓存不能遮蔽；第 466 行返回 302。

写路径三件套（同文件）：

```java
// 片段：ShortLinkService.java 第 630 到 650 行
private void cacheUrl(String code, String url) {
    long ttl = jitteredTtlSeconds(properties.getShortLink().getCacheTtlSeconds(),
            properties.getShortLink().getCacheTtlJitterSeconds());
    urlLocalCache.put(code, url);
    stringRedisTemplate.opsForValue().set(CACHE_KEY_PREFIX + code, url, ttl, TimeUnit.SECONDS);
}

private void evictCache(String code) {
    urlLocalCache.invalidate(code);
    stringRedisTemplate.delete(CACHE_KEY_PREFIX + code);
}

private void scheduleStaleClear(String code) {
    evictor.schedule(() -> {
        try {
            evictCache(code);
        } catch (Exception ex) {
            log.warn("delayed cache clear failed for {}: {}", code, ex.getMessage());
        }
    }, STALE_WRITEBACK_CLEAR_MS, TimeUnit.MILLISECONDS);
}
```

`cacheUrl` 写穿两级，TTL 走抖动公式；`evictCache` 逐级清（多级缓存的纪律：漏一级脏一级）；`scheduleStaleClear` 延迟 1 秒再清一次。absent 键的三个方法（第 592 到 628 行）都是 fail-open：`absentKnown` 探测异常当未命中，`markAbsent`/`clearAbsent` 写失败只打 warn，绝不因为缓存坏了而 500。

### RedisConfig 与 application.yml

`src/main/java/com/linkforge/config/RedisConfig.java` 全文 15 行：声明一个 `StringRedisTemplate` Bean，注入 Spring Boot 自动配置好的 `RedisConnectionFactory`（框架行为：`spring.data.redis.host/port/password` 三个配置项，`src/main/resources/application.yml` 第 56 到 59 行，由 Boot 自动装配成连接工厂）。项目用 `StringRedisTemplate` 而不是泛型模板的原因：所有值都是纯字符串（URL、占位值 "1"、计数、幂等键），字符串序列化在 `redis-cli` 里直接可读，也没有 JDK 序列化的类版本兼容问题（这是 LinkForge 的选型逻辑，不是框架强制）。

## 进阶速览：结构、持久化、复制（面试二面深度）

以下全部是概念层描述（按 Redis 官方文档与公开资料，本机未实测），每条两三句，够面试开口即可。

- **SDS（简单动态字符串）**：Redis 的字符串结构带头部（长度）与预分配空间，`strlen` O(1)、追加均摊 O(1)、二进制安全（存什么字节都行）。对比 Java `String` 的不可变设计：SDS 是可变缓冲，目标不同。
- **字典渐进式 rehash**：扩容时新旧两张表并存，后续每次增删改查顺手搬一个桶，搬完释放旧表——把一次 O(n) 停顿摊到每次操作上。单线程服务能扛大字典靠的就是它，Redis 没有集中 rehash 的长停顿。
- **ZSET 为什么是跳表**：ZSET 同时持有跳表（按分数有序，范围查询沿层指针下扫）和哈希表（成员到分数 O(1) 查）。跳表实现简单、免旋转，范围操作天然。对比 [40-mysql.md](40-mysql.md) 的 B+ 树：B+ 树为磁盘页设计，跳表为内存设计，"为什么不用 X"的答案还是场景。
- **小对象编码**：同一逻辑结构底层编码随大小自动切换（如 int/embstr/ziplist），`OBJECT ENCODING key` 可查，是省内存手段。
- **RDB**：`bgsave` 由主进程 `fork()` 出子进程写快照。fork 瞬间父子共享物理页（写时复制 COW，"写到哪页才复制哪页"），主进程继续接写，增量落在原进程内存、下次快照才包含；fork 本身阻塞主进程，耗时与页表大小成正比，大实例是风险点。
- **AOF**：命令追加文件，三种刷盘：`always`（每命令，最安全最慢）、`everysec`（每秒后台刷，最多丢 1 秒，默认）、`no`（交给操作系统）。膨胀靠 AOF 重写（按当前内存状态生成最小命令集，同样是 fork + COW）。
- **选型口径**：纯缓存场景 RDB 足够（丢了回源数据库）；当准持久层用则 RDB + AOF 混合（4.0+，重启先载 RDB 再补 AOF 增量）。本项目是缓存语义，部署配置未配持久化策略，如实说。
- **主从复制**：首次全量（主库 bgsave 传 RDB 加复制缓冲补增量），断线重连走增量（`repl_backlog` 环形缓冲加偏移量，缓冲被冲掉退化全量）。读写分离的代价：复制延迟下从库读旧值。
- **哨兵（Sentinel）**：每秒 ping 主库，超时为主观下线；quorum 个哨兵都确认才客观下线；哨兵间用类 Raft 选举选出 leader 执行故障转移（挑从库、提升、其余改挂新主、通知客户端）。与前文"空值占位值"无关，重名纯属历史巧合。
- **集群**：`CRC16(key) % 16384` 定槽，槽分布到各主节点，客户端遇 MOVED/ASK 重定向。16384 这个数的来历是作者的解释：心跳包里的槽位图 16384 位约 2KB，65536 位太浪费。哈希标签 `{user1000}` 强制相关键同槽，是跨槽事务/脚本的前提——Lua 脚本只能直接访问同一槽的键（集群限制，见 Redis 文档）。

## 常见错误与误区

| 错误 | 后果 | 正确姿势 |
|------|------|----------|
| 缓存整行（含状态） | 禁用/过期被旧缓存遮蔽，语义错 | 缓存只放派生读（URL），行是真相 |
| TTL 一刀切无抖动 | 同时失效雪崩 | base + uniform [0, jitter]，只向上抖 |
| 被拒请求也记入窗口 | 攻击者自我锁定延续 | 只放行才记账（脚本已如此） |
| 用分布式锁解决热 key 回源 | 把读串行化，没消除读 | SingleFlight 合并行读 |
| 布隆过滤器想删元素 | 结构不支持，越删越不准 | 接受多查一次 DB，或换可删除变体 |
| Redis 当持久库用 | 掉电/重启丢数据 | Redis 挡流量，MySQL 是真相 |
| fail-open 无指标 | 限流静默失效无人知 | 每次降级计数（`linkforge.ratelimit.failopen`） |
| 不同结构的键混用一个前缀 | WRONGTYPE 错误 | 按结构分命名空间（`sw:` 前缀的由来） |

## 面试常见问题与追问

**主题一：穿透/击穿/雪崩，项目怎么选的？** 先给两套通用方案（穿透：空值缓存/布隆；击穿：互斥/逻辑过期；雪崩：抖动/多级），每套说清机制和代价，再落到项目：布隆 + absent 双层挡穿透（布隆答否定是权威的，absent 兜误报）；SingleFlight 合并行读对付击穿（消掉重复劳动，而不是排队）；TTL 向上抖动 + L1/L2 两级缓存防雪崩。追问"为什么不加锁重建"：锁串行化读不消除读，SingleFlight 才消除；追问"L1 会不会脏"：会，窗口即 L1 TTL（10 秒），写路径逐级清。

**主题二：缓存与数据库双写怎么保证一致？** 先立前提"不强求强一致"，再推演三种写模式的时序（先删后写脏到 TTL、先写后删残留低概率竞态回填、延迟双删把窗口压到延迟长度），给项目的组合拳：写穿 + 1 秒延迟再删 + 短 TTL + 行是真相。追问"为什么延迟 1 秒"：要覆盖一次"查库 + 回填"的完整生命周期，行读被 singleflight 包着，毫秒级，1 秒余量充足；追问"1 秒之后还脏怎么办"：TTL 有上界，且状态判定永远读行，最坏后果被限定在 URL 字段本身。

**主题三：Lua 脚本的原子性边界在哪？** 会答"整段脚本当一条命令、执行期间阻塞其他一切"之后，还要答得出边界：脚本必须短（慢脚本阻塞所有客户端）；集群下脚本只应访问同一槽的键（所以要传 KEYS 而不是在脚本里拼键名）；脚本缓存重启即空，客户端要处理 NOSCRIPT 回退；`SCRIPT KILL` 只能杀没写过数据的脚本。追问"为什么脚本里能用 TIME"：Redis 5.0 起默认效果复制，非确定性命令解除限制（Redis 官方文档）。

## 本章自测

1. 为什么禁用的链绝不能进 absent 缓存？
   答：absent 只存"数据库确认不存在"；禁用的行还在数据库里，需要 enable 后立即重新生效。把它标成"不存在"会把 404 拖到 TTL 结束，禁用功能等于失灵。
2. L1 命中为什么还读数据库行？
   答：L1 只存 URL 字符串。状态、过期、密码、恶意域名判定全在行上且必须行权威（第 410 行每请求行读），旧缓存不能遮蔽它们；L1 的收益只是省一次 Redis 往返。
3. 布隆过滤器的 fpp 调大/调小各付出什么？
   答：调大省内存但误报变多，误报无害（多走一次缓存 + 数据库）；调小误报变少但位数组内存成倍增长。fpp=0.0001 是"数据库兜底很便宜"前提下的经济选择。
4. `Retry-After` 为什么一定不大于窗口秒数？
   答：窗口内最老条目最迟在窗口结束时出窗，等待不可能超过窗口长。Lua 算出真实毫秒（最老成员出窗还差多少），Java 侧 `Math.min(windowSeconds, ceil(retryMs/1000))` 显式钳制。
5. 被拒的请求为什么不能 ZADD 进窗口？
   答：记账即占名额，攻击者可以靠持续刷请求把自己永久锁死在窗口里（自我延续的拒绝）。只有放行才 ZADD，拒绝不改变窗口内容。
6. 写路径里 `singleFlight.peek` + `forget` 防的是什么？
   答：防"提交前已在途的行读"被后续请求跟车。提交后 forget 把快照的那个飞行摘除，下一个请求重新起飞读新行；残留窗口是快照与提交之间起飞行的读，量级为单次查询延迟（javadoc 写明）。

## 下一步

- 跳转读路径在代码里的逐行追踪（含五层防线的测试与验证方法）：[81-linkforge-flows.md](81-linkforge-flows.md)。
- 限流键的 IP 解析与 X-Forwarded-For 信任问题：[32-spring-security.md](32-spring-security.md)。
- 点击数据离开跳转请求后去哪（outbox 与 MQ，与 Redis 无关）：[51-rabbitmq-outbox.md](51-rabbitmq-outbox.md)。
- 多实例部署下的锁与一致性全景：[70-distributed.md](70-distributed.md)。
- 决策记录（为什么缓存只存 URL、为什么 fail-open）：[82-linkforge-decisions.md](82-linkforge-decisions.md)。
