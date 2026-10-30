# 知识面试题库

按域组织的 57 道精选题，冲刺期按薄弱域查。**每题的参考答案按四段作答**：**结论**（一句话判断，先给面试官抓的东西）、**机制**（为什么会这样）、**边界/反例**（什么时候不成立、初学者错在哪）、**项目对照**（LinkForge 的真实落点）。答案口径与各出处文档一致，出处链接都指向重写后的分档；Java 基础题指向 `java/` 系列，不指向总览篇。

**用法**：先只看题目口述并录音，对照四段自评。结论说不出说明没记住主干；机制讲不出说明只背了表层；边界答不出说明没真用过；项目对照接不上说明知识与项目是两张皮。哪段卡住回哪份文档补。

## Java 基础

1. **Java 是值传递还是引用传递？** 考察：引用模型基本功。出处 [java/10-01-types-and-references.md](java/10-01-types-and-references.md)。
   - 结论：只有值传递。基本类型复制值；引用类型复制的值是引用本身（指向对象的线索），不存在 C++ `int&`、C# `ref` 那种引用传递。
   - 机制：调用方法时实参的值复制进形参，形参是方法私有的局部变量。传引用类型时，拷贝的引用与原引用指向同一个对象，所以方法内沿引用改对象（`arr[0] = 100`）外面看得见；方法内给参数重新赋值（`arr = new int[3]`）只改了方法私有那份拷贝，外面看不见。判断口诀：看方法改的是对象还是引用。
   - 边界/反例：`swap(int a, int b)` 交换失败是最经典反例，交换只发生在形参上。`String` 看着能"传进去改一下"，但它不可变，方法内只能重新赋值，永远走"改引用"那条看不见的路。
   - 项目对照：`ShortLinkService.setStatus(code, status)` 的 `status` 是 `int`，方法靠修改 `link` 对象并写库向外传递结果，从不指望通过参数把值带回去（`src/main/java/com/linkforge/link/service/ShortLinkService.java`）。

2. **重写 equals 不重写 hashCode 会怎样？契约是什么？** 出处 [java/10-03-equality-and-strings.md](java/10-03-equality-and-strings.md)、[11-java-collections.md](11-java-collections.md)。
   - 结论：对象放进 HashMap 或 HashSet 后"查无此人"：get 返回 null、put 不去重、size 虚高，全程无任何报错。
   - 机制：HashMap 查找分两步，先按 hashCode 定位桶，再在桶内 equals 确认。契约两条：equals 相等的对象 hashCode 必须相等（硬性要求，违反直接算错）；hashCode 相等 equals 不必相等（哈希冲突是正常现象，代价只是查找变慢）。只重写 equals 时，两个内容相同的对象用的是 Object 的默认 hashCode，几乎必然落进不同桶。
   - 边界/反例：hashCode 返回常量 1 不违反契约，但所有对象挤一个桶，查找退化成线性扫描。可变对象做键、放入后又改了参与哈希的字段，同样查无此人。初学者还常把签名写成 `equals(Key o)`，那是重载不是重写，`@Override` 注解能当场拦住。
   - 项目对照：record 的 equals/hashCode 由编译器按全部组件生成，天然合规；项目的内部结果对象（`RateLimitDecision`、`RedirectOutcome` 的四个分支）全用 record，键安全靠类型选择而不是自觉（`src/main/java/com/linkforge/common/ratelimit/RateLimitDecision.java`）。

3. **`Integer a = 127, b = 127` 与 `Integer c = 128, d = 128`，`==` 各是什么结果，依据是什么？** 出处 [java/10-04-wrappers-and-boxing.md](java/10-04-wrappers-and-boxing.md)。
   - 结论：前者 true，后者 false。但两者的依据地位不同：-128 到 127 的装箱同一性是 Java 语言规范（JLS 5.1.7）的保证，区间之外是否缓存是 JVM 实现自由，HotSpot 默认不缓存（可用 `-XX:AutoBoxCacheMax` 调大）。
   - 机制：装箱等价于调用 `Integer.valueOf(x)`，valueOf 对规范保证的区间直接返回缓存对象，不新建。所以包装类型比较内容永远用 equals，`==` 在区间内的 true 只是侥幸不是契约。
   - 边界/反例：用 `==` 比包装类型的代码测试环境跑得好好的，换个值区间就出错，是真实事故的常见来源。拆箱是另一坑：`int n = map.get(key)` 在键不存在时对 null 调 `intValue()` 抛 NPE；三元表达式两分支类型不一致时先提升再选分支（`true ? 1 : 2.0` 得到 Double 的 1.0）。
   - 项目对照：来自数据库、可能为 NULL 的字段用包装类型（`ShortLink` 的 `Long id` 装下"插入前还没有主键"、`LocalDateTime expireAt` 装下"永不过期"），代码算出来的布尔用基本类型（`ShortLinkDetail.passwordProtected`）。

4. **checked 与 unchecked 异常怎么取舍？** 出处 [java/10-06-exceptions.md](java/10-06-exceptions.md)。
   - 结论：区别只来自编译器：checked 强制调用方 catch 或 throws，unchecked 不强制，运行时行为完全一样。Web 项目的业务异常主流选择是 unchecked（继承 RuntimeException）加全局异常处理器统一翻译。
   - 机制：checked 的价值在"调用方能针对性恢复"（文件不存在可以提示重选）；代价是每层方法签名都被 throws 污染，且 Lambda 的函数式接口不允许抛 checked，结构性不兼容。unchecked 逃过编译器逃不过运行时，没人接住就炸穿 main 留下完整堆栈。
   - 边界/反例：空 catch 块把故障吃成无痕迹；finally 里 return 会吞掉异常；catch 后抛新异常不带 cause 会断根因链。底层 IO（文件、网络）标准库用 checked 依然合理，这不是"checked 是坏设计"。
   - 项目对照：`BizException` 继承 RuntimeException 并携带业务码，`GlobalExceptionHandler` 按异常类型多态匹配翻成 HTTP 响应；`RateLimitedException` 再特化一层，额外写 `Retry-After` 头（`src/main/java/com/linkforge/common/exception/BizException.java`）。

5. **泛型擦除擦掉了什么，带来哪些"不能"？** 出处 [java/10-05-generics.md](java/10-05-generics.md)。
   - 结论：编译器用泛型做完全套检查后，字节码里类型参数被替换成上界（无界是 Object），使用处自动插入强转。`List<String>` 和 `List<Integer>` 运行时是同一个 Class。
   - 机制：设计动机是 Java 5 与海量存量非泛型代码二进制兼容。由此推出一串"不能"：不能 `new T()`（运行时不知道 T 是谁，解法是传 `Supplier<T>` 或 `Class<T>`）、不能 `new T[]`（协变数组的运行期检查做不了）、不能 `instanceof List<String>`（可以 `instanceof List<?>`）、静态成员不能用类的类型参数。子类重写泛型方法时编译器会生成桥接方法保住多态。
   - 边界/反例：泛型是不变的，`List<String>` 不能赋给 `List<Object>`（否则经另一个口子塞进 Integer 击穿承诺）；数组是协变的，同类错误甩给运行期 `ArrayStoreException`。PECS 口诀：从集合读数据（集合是生产者）用 `? extends`，往集合写（消费者）用 `? super`。
   - 项目对照：五个模块的 Mapper 都 `extends BaseMapper<T>` 把 T 固定成实体，`insert`、`updateById` 全套方法自动特化；`LambdaQueryWrapper<ShortLink>` 里 `ShortLink::getCode` 的返回类型被编译器用来核对列值类型（`src/main/java/com/linkforge/link/mapper/ShortLinkMapper.java`）。

6. **Java 17 能用哪些新语法？sealed 加 if 链有穷举检查吗？** 出处 [java/10-10-java17.md](java/10-10-java17.md)。
   - 结论：17 正式可用的是 `var`（10）、文本块（15）、`instanceof` 模式匹配（16）、`record`（16）、`sealed`（17）。switch 模式匹配与 record 模式 Java 21 才转正，17 里只是预览（要 `--enable-preview`）。**17 的 if-instanceof 链没有穷尽检查**，穷尽检查是 switch 模式（21）对 sealed 类型才有的能力。
   - 机制：sealed 把子类型封闭在 permits 清单里；不写 permits 时，同编译单元或嵌套在声明体内的子类型自动获得许可，同包不同文件会被拒绝（已实测）。配合 switch 模式，新增子类型时所有处理点编译报错，漏分支从运行期 bug 变成编译错误。if 链编译器不检查覆盖度，只能手工加兜底 throw。
   - 边界/反例：不少资料把 switch 模式写成"Java 17 新特性"，这是高频版本陷阱题，答题先亮转正版本再谈取舍。record 的访问器叫 `x()` 不是 `getX()`，没有 setter 也没有无参构造器。
   - 项目对照：`RedirectOutcome` 是 sealed 接口，四个嵌套 record 表达放行、拦截、密码锁、警告四个判定；`RedirectController.render` 用 if-instanceof 链加一个兜底 `throw new IllegalStateException`，正是因为 17 没有穷尽 switch，升 21 后可换穷尽 switch 并删兜底（`src/main/java/com/linkforge/link/service/RedirectOutcome.java`）。

## 集合

7. **HashMap 的 put 流程与扩容？** 出处 [11-java-collections.md](11-java-collections.md)。
   - 结论：扰动 hash 定桶，空桶直插，碰撞串链表（长了树化），元素数超过容量乘 0.75 触发翻倍扩容。以下是 JDK 8 起的实现，JDK 17 沿用。
   - 机制：先做扰动 `h ^ (h >>> 16)` 让高位参与定位，再 `(n-1) & hash` 定桶，容量恒为 2 的幂所以位运算等价取模。非空桶先比首节点的 hash 与 equals，命中覆盖，否则尾插；链长到 8 还要再插、且表长至少 64 才树化（小表先扩容更便宜），退化阈值 6 是防抖动缓冲。扩容后新下标只多看 hash 的一个位，按该位 0/1 把每条链拆成两条，分别留原位与"原位加旧容量"，无需重算 hash。负载因子 0.75 是空间与时间的经验折中。
   - 边界/反例：背"数组加链表"就停是最低档回答。JDK 7 头插法并发扩容会成环导致 CPU 100%，JDK 8 改尾插修了成环，但 HashMap 从来不是线程安全的，并发丢数据与 size 不准仍在。树化是哈希质量差的兜底不是常规路径，理想散列下桶长到 8 的概率约亿分之六。
   - 项目对照：热路径的 Map 全用 `ConcurrentHashMap`（下一题）；普通 HashMap 只出现在无并发要求的聚合场景，比如 `ClickStatsService` 把数据库行转成按日期索引的 Map 再补零轴（`src/main/java/com/linkforge/stats/service/ClickStatsService.java`）。

8. **ConcurrentHashMap 1.8 的并发设计？为什么 key 和 value 不允许 null？** 出处 [11-java-collections.md](11-java-collections.md)、[13-concurrency.md](13-concurrency.md)。
   - 结论：空桶用 CAS 占位、非空桶用 `synchronized` 锁桶头节点，锁粒度到单桶；get 全程无锁靠 volatile 读；size 是弱一致的近似值。
   - 机制：1.7 的分段锁把并发度固定在段数，1.8 改桶级后冲突被桶数稀释。扩容是多线程协作，put 碰到迁移中的桶会 helpTransfer 协助搬运。size 用 baseCount 加 CounterCell 数组分散计写，读时求和。null 问题的根：并发下 get 返回 null 无法区分"键不存在"与"值就是 null"，再补一刀 containsKey 的两步之间值可能已被别的线程改掉，语义无法自洽；HashMap 单线程下可以补刀，所以允许一个 null 键。
   - 边界/反例：单个方法的原子性不等于操作序列的原子性，`containsKey` 再 `put` 的 check-then-act 会被插队，要用 `putIfAbsent`、`computeIfAbsent` 这类复合原子方法。注意 `computeIfAbsent` 的函数在持有桶锁的状态下执行，函数里放几十毫秒的 DB 读会卡住同桶所有无关 key。
   - 项目对照：`SingleFlight` 用 `putIfAbsent` 而不是 `computeIfAbsent`，就是绝不在持桶锁状态下做同步 DB 读；条目摘除用两参 `remove(key, mine)`，只删自己快照过的那个飞行（`src/main/java/com/linkforge/link/support/SingleFlight.java`）。

9. **遍历集合时删除元素会发生什么？怎么安全删？** 出处 [11-java-collections.md](11-java-collections.md)。
   - 结论：单线程下抛 `ConcurrentModificationException`，类名带 Concurrent 但与线程无关。这是 fail-fast 探测器，坏了立刻报，不是并发安全保证。
   - 机制：ArrayList 内部有计数器 modCount，每次结构性修改加 1；迭代器创建时记下快照 expectedModCount，每次 next() 比对，不一致立刻抛。迭代器自己的 remove 会同步快照，所以合法。
   - 边界/反例：两个线程交替修改可能侥幸不抛，标准库明确说不可依赖。正序按下标删会跳过紧跟的元素（删掉后右边整体左移）。安全删三种：`removeIf`（首选）、迭代器 remove、倒序下标循环。ConcurrentHashMap 的迭代器弱一致不抛，但看到的是混合状态，要强一致快照就先复制再遍历。
   - 项目对照：项目的规则热更新走的是另一条路：`PhishingPolicy` 用 `Set.copyOf` 把域名名单定格成不可变集合，读侧随便遍历永远不会 CME，换规则时整体替换 volatile 引用（`src/main/java/com/linkforge/link/support/PhishingPolicy.java`）。

## 并发

10. **线程池的工作流程与参数？为什么不用 Executors 工厂？** 出处 [13-concurrency.md](13-concurrency.md)。
    - 结论：七参数：核心数、最大数、保活时间、单位、工作队列、线程工厂、拒绝策略。提交流程固定四步：线程数不足核心直接建线程；核心满先入队；队列满且未到最大再建非核心线程；都满走拒绝策略。顺序是先排队、队满才扩线程。
    - 机制：先排队是让突发流量在队列里缓冲而不是线程暴涨；要快速扩线程的池（newCachedThreadPool）用不存任务的 SynchronousQueue 逼过排队。四种拒绝策略：Abort（默认，抛异常可监控）、CallerRuns（调用者自己跑，天然反压）、Discard 与 DiscardOldest（静默吞任务，生产慎用）。
    - 边界/反例：`newFixedThreadPool` 的队列无界，任务只堆不拒，上游失速时内存被吃穿，OOM 报错位置离原因很远；`newCachedThreadPool` 线程数无上限。正确姿势是手写 ThreadPoolExecutor：有界队列、显式拒绝策略、线程命名。但 Executors 不是一律有毒，任务由代码自己投放、速率可控时可以用。IO 密集线程数约等于核数乘（1 加等待计算比），可靠的是压测；队列长度与拒绝动作比线程数更常被忽视。
    - 项目对照：所有定时任务共享的 Spring 调度池配成 6 个线程，注释写明理由是故障域隔离：慢的 webhook 端点绝不能拖住点击 outbox 的追平（`src/main/resources/application.yml`，决策 B5）。

11. **volatile 能解决什么、不能解决什么？** 出处 [13-concurrency.md](13-concurrency.md)。
    - 结论：保证可见性、禁止重排序，不保证原子性。`i++` 是读、改、写三步，volatile 救不了，原子计数用 AtomicLong 或加锁。
    - 机制：按 happens-before 规则，volatile 写之前的所有普通写，对 volatile 读之后的操作可见；同时禁止编译器与 CPU 把它前后的访问重排。双重检查锁靠这条防半初始化对象：new 分分配内存、初始化、赋引用三步，后两步可被重排，另一线程可能拿到未初始化的引用。
    - 边界/反例：把两线程各加十万次的计数器加上 volatile，结果仍明显小于 20 万：volatile 堵上了"看不见"，堵不上"插队"。适用面只有"单写多读、一次一步"的标志与配置快照。
    - 项目对照：`PhishingPolicy` 用 volatile 引用加不可变 Config 做规则热更新，重载线程整只替换 snapshot 字段，读线程永远拿到完整旧版或完整新版，绝不半新半旧（`src/main/java/com/linkforge/link/support/PhishingPolicy.java` 第 34 行）。

12. **ThreadLocal 内存泄漏的完整链路？** 出处 [13-concurrency.md](13-concurrency.md)。
    - 结论：ThreadLocalMap 的 Entry key 是弱引用的 ThreadLocal、value 是强引用。外部不再持有 ThreadLocal 后 key 被 GC 清掉，value 还被长期不死的线程持有，形成 key 为 null 的残留条目。线程池放大风险，纪律是 finally 里 remove。
    - 机制：Map 只在后续 set/get 的探测路径顺带清理残留条目，不保证及时更不保证清完。比泄漏更常咬人的是脏数据：线程复用后，下一个请求直接 get，读到上一个请求留下的值。
    - 边界/反例：初学者以为"不用了 GC 会收"，漏掉的是线程不死这一环；或者只在正常路径清理、异常路径漏掉，所以清理必须放 finally。
    - 项目对照：`JwtAuthFilter` 在 finally 同时清 `UserContext`（项目自己的 ThreadLocal）和 `SecurityContextHolder`（Spring Security 的 ThreadLocal），两个都清是因为线程复用会把身份带给下一个请求（`src/main/java/com/linkforge/security/JwtAuthFilter.java`）。

13. **CAS 与 ABA？LongAdder 为什么高竞争下更优？** 出处 [13-concurrency.md](13-concurrency.md)。
    - 结论：CAS 是 CPU 提供的比较并交换原子指令，失败就重试（自旋），免锁；ABA 用版本号（如 `AtomicStampedReference`）解决；LongAdder 用分散计数把竞争摊开。
    - 机制：CAS 拿内存位置、期望值、新值三个操作数，期望不符就失败重来。ABA 指值从 A 改到 B 又改回 A，CAS 只比值就察觉不到中间变化，比"引用加状态"的场景要版本号。LongAdder 的思路与 ConcurrentHashMap 的 CounterCell 同源：写时 CAS baseCount 失败就记到自己的格子里，读时求和，用弱一致的读换高竞争下的写吞吐。
    - 边界/反例：CAS 自旋在竞争激烈时空转烧 CPU；它只能保证单个变量的原子性，要同时改几个变量就用锁，或封装成不可变对象整体替换。
    - 项目对照：项目的原子计数（`BacklogGauges` 采样进 AtomicLong、singleflight 的指标计数）都是低竞争场景，直接用 AtomicLong；没有引入 LongAdder 的必要，面试如实说（`src/main/java/com/linkforge/observability/BacklogGauges.java`）。

14. **死锁的四个必要条件与排查手段？** 出处 [13-concurrency.md](13-concurrency.md)、[40-mysql.md](40-mysql.md)。
    - 结论：互斥、持有并等待、不可剥夺、循环等待，四个同时成立才死锁。Java 侧 jstack 直接标注，数据库侧看 InnoDB 死锁日志。
    - 机制：jstack 输出 `Found one Java-level deadlock` 并指出互相等待的线程；MySQL 的 `SHOW ENGINE INNODB STATUS` 里找 `LATEST DETECTED DEADLOCK` 段看两边的锁等待。破坏任一条件可预防：统一加锁顺序破坏循环等待，tryLock 带超时破坏不可剥夺。
    - 边界/反例：RR 隔离级别下间隙锁允许两个事务同时持有同一空隙、又都挡对方插入，是数据库死锁高发区；锁内调远程服务把临界区拉长，同时放大死锁与阻塞概率。
    - 项目对照：项目刻意保持锁面极小：JVM 内没有嵌套锁，跨进程一致性交给数据库唯一约束与 Redis Lua 脚本（单线程执行天然串行），不存在需要管理加锁顺序的代码路径。

## JVM

15. **运行时数据区哪些共享、哪些私有？为什么程序计数器必须私有？** 出处 [12-jvm.md](12-jvm.md)。
    - 结论：堆与方法区线程共享（JDK 8 起方法区由元空间实现，用本地内存）；虚拟机栈、本地方法栈、程序计数器每线程私有。
    - 机制：程序计数器记录当前线程执行到哪条字节码，线程被切下 CPU 后靠它恢复执行位置，多线程争用一个计数器做不到。栈私有因为各线程的调用链互相独立。程序计数器是唯一规范规定不存在 OOM 的区域。
    - 边界/反例：把方法区与堆混为一谈是高频错误：元空间在本地内存，堆 OOM 不代表元空间也没空间；JDK 8 之前的 PermGen 与 `-XX:PermSize` 是过时知识。这条"共享区出可见性与竞态、私有区出栈溢出与递归深度"的二分法能串起 JVM 大半面试题。
    - 项目对照：排查问题时的第一张图就是进程地图：应用 JVM 跑在宿主机，MySQL、Redis、RabbitMQ 各自容器，四个进程各有各的内存账（`docker-compose.yml`）。

16. **对象什么时候进老年代？分代假设是什么？** 出处 [12-jvm.md](12-jvm.md)。
    - 结论：三条路：年龄达到阈值（HotSpot 默认 15，实现参数）、大对象直接进（G1 下超过 region 一半的 humongous 对象）、动态年龄判断（Survivor 里同龄对象超过一半就整体提前晋升）。
    - 机制：分代来自弱分代假设，绝大多数对象朝生夕死。新生代存活率低，复制活对象的代价便宜，用高频回收；老年代低频彻底回收。G1 把堆切成 region，Eden、Survivor、老年代都只是 region 的逻辑标签。
    - 边界/反例：`-XX:PretenureSizeThreshold` 只在串行与 ParNew 收集器下生效，G1 下不生效，说参数前先说清收集器。静态集合只放不取的对象从根可达、永远不会被回收，这是 Java 也能内存泄漏的机制根源。
    - 项目对照：`BacklogGauges` 的 AtomicLong 由单例 Bean 持有、Gauge 又持有它的强引用，是标准的常驻老年代对象；不是所有常驻对象都是泄漏，合理设计的长命对象本来就该活着（`src/main/java/com/linkforge/observability/BacklogGauges.java`）。

17. **CPU 100% 怎么排查？怎么确认是 GC？** 出处 [12-jvm.md](12-jvm.md)、[21-os.md](21-os.md)。
    - 结论：`top -H -p <pid>` 找最耗 CPU 的线程，线程号转十六进制，到 jstack 输出按 `nid` 对号看栈。栈顶在业务代码就是死循环或计算密集；栈顶是 GC 线程就转去查内存问题。
    - 机制：整套动作的核心是把进程级现象定位到线程级现场，再区分业务线程与虚拟机自身线程。确认 GC 用 `jstat -gcutil <pid> 1000` 连看，FGC 频繁、老年代占用不回落，就转去查泄漏或堆过小。
    - 边界/反例：jstack 里 RUNNABLE 不等于在烧 CPU，阻塞在 socket 读上的线程也是 RUNNABLE，要看栈顶是不是 `socketRead0` 一类的 native 帧。Windows 没有 `top -H`，用任务管理器详细信息页或 Process Explorer 替代，面试口径仍背 Linux 命令。
    - 项目对照：项目各线程都有可辨认的名字（Tomcat 的 `http-nio-8080-exec-*`、自建的 `cache-evictor`），线程转储里一眼认出是谁在烧 CPU，这就是线程工厂命名的全部意义。

18. **强、软、弱、虚四种引用怎么区分？** 出处 [12-jvm.md](12-jvm.md)。
    - 结论：强引用宁可 OOM 也不回收；软引用内存不足才清，适合可有可无的缓存；弱引用下次 GC 必清；虚引用摸不到对象本身，只在对象被回收后收到通知。
    - 机制：回收判定是可达性分析，从 GC Roots（各线程栈帧的局部变量、静态字段、native 持有）出发，走不到的才是垃圾。四种引用是 `java.lang.ref` 包定义的强度档位，改变的是"从根出发能不能走到"的判定结果。
    - 边界/反例：最常考的落点是 ThreadLocalMap 的 key 是弱引用、value 是强引用，构成泄漏链。另一高频推论：把引用置 null 通常没有意义，有意义的是从静态集合里 remove，那才真正断开从根出发的路径。
    - 项目对照：项目的缓存分层刻意不依赖软弱引用：L1 用 Caffeine 带容量与 TTL 上限，负缓存放 Redis 带 TTL，因为靠 GC 心情回收的缓存淘汰时机不可控，容量与过期必须自己声明（`src/main/java/com/linkforge/link/support/UrlLocalCache.java`）。

## Spring 与持久层

19. **Bean 生命周期？AOP 代理在哪一步生成？** 出处 [30-spring-core.md](30-spring-core.md)。
    - 结论：实例化（容器选构造器反射建对象）到属性填充（依赖注入发生在这里）到初始化回调（`@PostConstruct` 到 InitializingBean 到 init-method）到使用，最后销毁回调（`@PreDestroy`）。
    - 机制：BeanPostProcessor 在初始化前后插手；AOP 代理在初始化之后的后处理生成并替换原对象，所以容器里放的是代理，事务与切面才能生效。构造器注入在实例化这一步完成，字段注入靠属性填充。
    - 边界/反例：循环依赖是这一节的试金石：构造器注入的环无解（实例化就要依赖，连半成品都拿不出），字段注入能靠"先实例化空壳再互相填充"活下来，三级缓存保证代理只生成一次；Spring Boot 2.6 起默认拒绝循环依赖，框架态度是先改设计。
    - 项目对照：`ShortLinkService` 的 `@PreDestroy shutdownEvictor` 在容器关闭时停掉自己的缓存清理调度线程，`BacklogGauges` 用 `@PostConstruct` 在依赖齐全后注册 Gauge（`src/main/java/com/linkforge/link/service/ShortLinkService.java` 第 86 行）。

20. **Spring Boot 自动配置原理？怎么排除某个自动配置？** 出处 [30-spring-core.md](30-spring-core.md)、[31-spring-boot-mvc.md](31-spring-boot-mvc.md)。
    - 结论：两步：启动时读 classpath 上各 jar 清单文件（`META-INF/spring/` 下的 AutoConfiguration.imports）得到候选配置类名单，再用条件注解逐个筛选。一句话版本是"imports 清单加 @Conditional 条件装配"。
    - 机制：`@ConditionalOnClass` 类路径上有某个类才装配，`@ConditionalOnMissingBean` 你没定义同类型 Bean 才装配，`@ConditionalOnProperty` 受配置开关控制。选择器是 DeferredImportSelector，被刻意拖到所有用户配置解析完之后评估，所以用户定义的同类型 Bean 永远优先。排除用 `@SpringBootApplication(exclude = ...)`。
    - 边界/反例：自动配置"不生效"通常不是坏了，而是条件没满足：依赖没进 classpath、你定义了同类型 Bean、或开关关了，排查按这三问走。
    - 项目对照：引入 `spring-boot-starter-data-redis` 后一行配置不写，`StringRedisTemplate` 就能注入；`passwordEncoder` 是项目自己在 SecurityConfig 里 `@Bean` 的，Security 相关自动配置让位。

21. **构造器注入为什么优于字段注入？** 出处 [30-spring-core.md](30-spring-core.md)。
    - 结论：三个理由：字段可以是 final（构造完成后依赖不变，只读引用并发安全）、依赖显式（构造器签名就是依赖清单）、问题早暴露（造不齐依赖启动直接失败，而不是第一次请求才 NPE）。
    - 机制：字段注入脱离容器无法构造（手工 new 出来全是 null）、依赖藏在类内部、加依赖没有视觉警告，类会悄悄长成上帝类。模板成本用 Lombok 的 `@RequiredArgsConstructor` 消掉，它为所有没有初始化表达式的 final 字段生成构造器。
    - 边界/反例：循环依赖验证这条取舍：构造器注入画不出环，环在启动期直接失败，这是特性不是缺陷（Boot 2.6 起默认拒绝）。Setter 注入留给可选依赖。
    - 项目对照：`ShortLinkService` 有 14 个 final 依赖（第 15 个 `evictor` 有初始化表达式，Lombok 不收它），依赖图单向无环，字段列表按角色分四类可读（`src/main/java/com/linkforge/link/service/ShortLinkService.java`）。

22. **@Transactional 失效的场景？哪些是代理没参与，哪些是回滚规则问题？** 出处 [30-spring-core.md](30-spring-core.md)。
    - 结论：分两类，机理不同。代理没参与：同类自调用（this 直调走原始对象，拦截器没机会执行）、非 public 方法（默认只拦 public）、final 方法（CGLIB 子类无法重写）。回滚规则问题：异常被 catch 吞掉（代理看到的是正常返回，照常提交）、抛 checked 异常（默认只回滚 RuntimeException 和 Error，需要 `rollbackFor`）。
    - 机制：回滚的判定者是代理里的 TransactionInterceptor：开事务、转发方法、按返回与异常决定提交回滚，事务连接绑定在当前线程（所以方法里另起线程的 SQL 不在事务里）。任何让它"看不见异常"或"根本不经过它"的写法都失效。口径要准：只有第一类是"代理未参与"，修法是拆 Bean 或跨 Bean 调用；吞异常与 checked 是"代理参与了但规则没命中"，修法是显式抛 RuntimeException 或加 `rollbackFor`。
    - 边界/反例：传播行为 `REQUIRES_NEW` 的典型用法是操作日志独立于主业务提交，主事务回滚日志也留下。catch 后抛新异常会回滚，只要新异常是 RuntimeException，因为代理看的是方法最终抛出的类型。
    - 项目对照：全仓只有两处 `@Transactional`：`recordClick`（点击明细与 outbox 两条 INSERT 同生共死）与 `register`（租户与首个用户原子），两处都是 public 且无自调用；`ShortLinkService` 无事务是因为单语句加 Redis 状态机各有原子性方案，事务是工具不是习惯（`src/main/java/com/linkforge/stats/service/ClickRecordService.java`）。

23. **一次请求在 Spring MVC 里的完整路径？@RestController 与 @Controller 差别？** 出处 [31-spring-boot-mvc.md](31-spring-boot-mvc.md)。
    - 结论：内嵌 Tomcat 解析报文，到过滤器链（安全认证在这站，401/403 多数产生于此），到 DispatcherServlet，到 HandlerMapping 查表（查不到就 404，发生在你的代码执行前），到参数解析与 `@Valid` 校验，到 Controller 与 Service，返回值经 Jackson 序列化写回。任何一站的异常进异常解析器，按类型匹配 `@RestControllerAdvice`。
    - 机制：DispatcherServlet 的 doDispatch 用 try/catch 包住全流程。按站定位问题：404 是路由未命中（方法连日志都不会打）、400 是参数或校验失败、500 几乎都在业务层走兜底。`@RestController` 是 `@Controller` 加 `@ResponseBody`，返回值直接进消息转换器，不走视图解析。
    - 边界/反例：DTO 注解写得整整齐齐但参数忘写 `@Valid`，校验机制根本不启动，所有请求放行。500 响应回显异常栈会泄露表名与框架版本，正确做法是日志留全栈、响应给固定文案。
    - 项目对照：`GlobalExceptionHandler` 的翻译表覆盖 429 带 `Retry-After`、409、404 与 410、400、500 兜底（对内留全栈对外不泄露细节）；Controller 一律一行收参调 Service 包壳（`src/main/java/com/linkforge/common/exception/GlobalExceptionHandler.java`）。

24. **MyBatis 的 Mapper 接口没有实现类，为什么能注入和调用？** 出处 [41-mybatis-plus.md](41-mybatis-plus.md)、[java/10-07-annotations-reflection-proxy.md](java/10-07-annotations-reflection-proxy.md)。
    - 结论：`@MapperScan` 扫描指定包里的每个接口，MyBatis 启动时用 JDK 动态代理生成替身注册成 Bean。你调用的每个方法先进代理，由代理把"方法签名加注解或 XML"翻译成 SQL 执行并映射结果。注册与代理生成都在启动期一次完成。
    - 机制：JDK 动态代理只能代理接口（生成的类 extends Proxy，占掉了单继承名额）；没有接口的类走 CGLIB 子类代理，代价是 final 类与 final 方法代理不了。两种代理同构：运行时生成替身加拦截转发。
    - 边界/反例：扫描对扫到的类型是无差别装配的，机制不理解业务意图。项目真实事故：`@MapperScan` 曾宽扫整个 `com.linkforge`，连发消息用的普通接口 `ClickEventPublisher` 都被套上 Mapper 代理注册成 Bean，运行时行为错乱；修法是精确列出五个 mapper 包。
    - 项目对照：主类注释保留了事故记录，五个 mapper 包显式列出，这是"反射加动态代理"用出事故的第一手案例（`src/main/java/com/linkforge/LinkForgeApplication.java` 第 10 行）。

25. **MyBatis-Plus 的 Wrapper 传 null 会发生什么？两条 null 规则怎么分？** 出处 [41-mybatis-plus.md](41-mybatis-plus.md)。
    - 结论：查询侧：`eq(col, null)` 生成 `col = ?` 且参数为 null，SQL 三值逻辑里 `col = NULL` 对任何行都不成立，结果一行都查不到，绝不是"跳过条件"。更新侧：`updateById` 默认策略跳过 null 字段（想清空用 `LambdaUpdateWrapper.set(col, null)` 显式 SET NULL）。两条规则不是同一层机制：一个是查询条件的求值语义，一个是更新语句的字段组装策略。
    - 机制：想要"值没传就不过滤"，用布尔参数重载 `eq(condition, col, val)`；想匹配 NULL 用 `isNull(col)`。数据库侧的根是 NULL 参与 `=` 比较的结果是 UNKNOWN 而不是 TRUE。
    - 边界/反例：初学者的两个错误来源：把 MP 当 `Map.get` 那类"null 表示没这个键"的约定，或从 `updateById` 的行为错误外推到查询侧。
    - 项目对照：清空短链访问密码就踩过这个坑：`updateById` 传 null 等于什么都没改，项目改用 `LambdaUpdateWrapper.set(col, null)` 显式生成 `SET access_password_hash = NULL`（`src/main/java/com/linkforge/link/service/ShortLinkService.java` 第 292 行，决策 D-Bw345-32）。

## MySQL

26. **为什么索引用 B+ 树？对比哈希、红黑树、B 树？** 出处 [40-mysql.md](40-mysql.md)。
    - 结论：统一口径是磁盘 IO 贵、按 16KB 页读，B+ 树矮胖让"树高即 IO 次数"压到个位数：2000 万行约 3 层，10 亿行也只 4 层。
    - 机制：非叶节点只放路标（键加指针），一页能放约 1024 个路标（扇出极大），数据全部下沉到叶子层；叶子串成双向链表，范围查询沿链表扫。对比：哈希不支持范围与排序；红黑树二叉太深（2000 万行约 24 层）；B 树非叶也存整行，扇出小树更高且范围查询要中序回溯。聚簇索引叶子即整行，主键查询一步到位；二级索引叶子存主键值，查完要回表，覆盖索引可免。
    - 边界/反例：追问"主键为什么自增"：随机主键（如 UUID）命中页中间造成页分裂，自增主键总是追加到最右叶子。索引不是越多越好，每多一个索引每次写入就多维护一棵有序树。
    - 项目对照：所有表主键都是 `BIGINT AUTO_INCREMENT`；`idx_outbox_pending(status, next_attempt_at, id)` 是"为已知查询逐列设计"的范式（`src/main/resources/db/migration/V1__baseline_schema.sql`）。

27. **最左前缀原则与覆盖索引？哪些写法让索引失效？** 出处 [40-mysql.md](40-mysql.md)。
    - 结论：联合索引的物理事实是"先按第一列排，相同时按第二列排"的字典序，查询必须从最左列开始连续命中；范围列会断链，设计口诀是等值列在前、范围列在后。查询要的列全在索引里就不必回表，叫覆盖索引，EXPLAIN 的 Extra 显示 Using index。
    - 机制：`WHERE b = x` 用不了 `(a, b)`，因为脱离 a 后 b 全局无序；`a > 1 AND b = 5` 只有 a 能定位。失效三写法：函数包列（`DATE(created_at)` 要改写成半开区间）、隐式转换（varchar 列与数字比较，转换落在列上才失效）、前导通配符 `LIKE '%abc'`。
    - 边界/反例：判断标准是 EXPLAIN 而不是感觉：看 type（const、eq_ref、ref、range、index、ALL 从好到坏）、key、rows、Extra；rows 接近真实结果集小说明估算好。
    - 项目对照：`idx_click_link_time(link_id, clicked_at)` 服务"某链接某时间段"，等值在前范围在后；relay 的扫描 SQL 靠 `idx_outbox_pending` 几乎不碰无关数据（`src/main/java/com/linkforge/stats/mapper/OutboxMapper.java`）。

28. **RR 隔离级别怎么实现的？幻读防住了吗？** 出处 [40-mysql.md](40-mysql.md)。
    - 结论：普通 SELECT 是快照读，靠 MVCC：每行带事务 id 与回滚指针，undo log 把旧版本串成链，事务按 ReadView 判可见性。RC 与 RR 的全部差别浓缩成一句：RC 每次快照读新建 ReadView，RR 第一次建后全程复用。幻读：快照读天然没有，当前读靠临键锁防插入，但一个事务里两类读混用仍能观察到幻读现象。
    - 机制：ReadView 四字段是活跃事务集、最小与下一个事务 id、创建者自己，四条可见性规则判定，不可见就沿版本链回退。当前读（UPDATE、DELETE、FOR UPDATE）必须读最新版本并加锁，临键锁锁"记录加之前的空隙"挡住并发插入。混用场景：先快照读建视图，别的事务插入并提交，之后的 UPDATE 是当前读能看到新行，改完后该行事务 id 是自己，再 SELECT 就可见了。
    - 边界/反例：长事务的机制级危害：ReadView 活着，它可能引用的旧版本不能清理，undo log 不断堆积，磁盘上涨、回滚变慢。
    - 项目对照：`recordClick` 的事务里只有两条 INSERT，MVCC 在这条热路径几乎不参与；它的价值在统计报表这类长查询靠快照读不阻塞业务写入（`src/main/java/com/linkforge/stats/service/ClickRecordService.java`）。

29. **唯一索引和"先查后插"？** 出处 [40-mysql.md](40-mysql.md)。
    - 结论：并发下"先查后插"必然漏：两个请求都能通过"不存在"检查，双双插入。唯一索引把判定下沉到存储引擎的原子序列，失败方收到 `DuplicateKeyException` 后按语义处理。任何"防重复"问题在 DB 层的答案都是唯一约束而非应用层检查。
    - 机制：检查与动作是两步，两步之间有缝隙（TOCTOU：检查与行动之间的时间窗）；唯一索引的判定发生在写入那一刻，没有缝隙。撞键时 MySQL 只回滚失败的那条语句，不影响事务其他部分，所以"撞键即跳过"是安全的。
    - 边界/反例：先查后插单线程测试全绿，并发才炸，是幂等设计最经典的反例。代价：撞唯一索引的插入做了无用功，适用于失败路径 cheap 的场景；如果撞码是常态（比如用递增序列当码），重试就是错误设计。唯一性语义还取决于排序规则：code 列显式用 `utf8mb4_0900_bin`（区分大小写、NO PAD，这两个性质都是 MySQL 8.0 引入，5.7 找不到等价替代），否则 ci 排序规则下 AbC 与 abc 在唯一索引判定里相等，大小写变体互相顶替，行为还会随 Bloom 开关漂移；V4 迁移记录了完整决策。
    - 项目对照：三个同源落点：`uk_code` 撞码时随机码重试至多 16 次、自定义码 409；`lf_consumed_event` 主键即 eventId，撞键安静跳过；`uk_daily_link_date` 配合 `ON DUPLICATE KEY UPDATE clicks = clicks + 1` 原子累加（`src/main/java/com/linkforge/stats/service/ClickStatsService.java`）。

30. **慢 SQL 排查流程？** 出处 [40-mysql.md](40-mysql.md)。
    - 结论：慢查询日志发现嫌疑，EXPLAIN 看 type、key、rows、Extra，加索引或改写，再 EXPLAIN 复测。这个循环是所有线上慢 SQL 处理的骨架。
    - 机制：type 从好到坏是 const、eq_ref、ref、range、index、ALL，看到 ALL 先问能不能加索引或改写；Extra 里 Using index 是覆盖索引免回表，Using filesort 是额外排序，Using temporary 是临时表。
    - 边界/反例：索引建了没用上的常见原因：不满足最左前缀、隐式转换、函数包列、区分度太低、回表行数太多优化器干脆放弃走全表。
    - 项目对照：项目所有 SQL 结论都标注"示意，未实测"，因为本机没有 MySQL，机制依据官方文档；自己验证时把日志里抄出的真实 SQL 放进 EXPLAIN（`src/main/resources/application.yml` 已配 MyBatis SQL 日志输出）。

31. **DATETIME 与 TIMESTAMP 怎么选？** 出处 [40-mysql.md](40-mysql.md)、[51-rabbitmq-outbox.md](51-rabbitmq-outbox.md)。
    - 结论：DATETIME 存字面值，与时区无关；TIMESTAMP 内部按 UTC 存、读写按会话的 time_zone 参数转换。应用与数据库时区配置不一致时，混用两者的经典症状就是"时间差了 8 小时"。
    - 机制：项目所有表的时间列统一 DATETIME，JDBC URL 显式带 `serverTimezone=Asia/Shanghai`。更深一层的规则是"用谁的钟"：调度时间字段（如重试的 next_attempt_at）用数据库时钟算（`DATE_ADD(NOW(), ...)`），业务时间戳才用 JVM 时钟；同一段等待的起点与终点必须用同一个钟。
    - 边界/反例：真实故障 Bw2-M1：webhook 退避最初用 JVM 时钟算好字面值写入，JVM 在东八区、MySQL 容器在 UTC，20 秒的退避被拉成 8 小时，重试像死了一样。
    - 项目对照：修复被 `WebhookRetryClockIT` 钉住：测试用 `-Duser.timezone=Asia/Shanghai` 的独立 fork 人为制造时区错配，旧实现读出约 28800 秒、新实现在 20 秒内（`src/test/java/com/linkforge/webhook/WebhookRetryClockIT.java`）。

## Redis

32. **穿透、击穿、雪崩，各说两套方案再落项目选择？** 出处 [50-redis.md](50-redis.md)。
    - 结论：穿透是查不存在的 key 每次白跑数据库（空值缓存、布隆过滤器）；击穿是一个热点 key 过期瞬间并发全回源（互斥重建、逻辑过期、合并读）；雪崩是一大批 key 同时失效（TTL 抖动、多级缓存、限流降级）。
    - 机制：布隆过滤器"答没有是权威的，答可能有含误报"，误报无害；互斥锁只是把重复劳动排队，合并读直接消掉重复劳动本身；TTL 抖动把同时失效摊成细水长流。答题先给通用谱系，再落项目为什么选这个，只背通用答案是及格线以下。
    - 边界/反例：空值缓存会占内存且创建后必须清占位值；经典布隆不能删除；逻辑过期总有旧值窗口；互斥重建要防死锁（锁带 TTL）与误删（锁带唯一值）。
    - 项目对照：进程内 Bloom（fpp 0.0001）加 absent 哨兵 60 秒挡穿透；singleflight 合并行读对付击穿，因为每请求必须读行，锁只会串行化读；TTL 向上抖动 3600 加随机 0 到 300 秒、L1 Caffeine（1 万条、10 秒）加 L2 Redis 防雪崩（`src/main/java/com/linkforge/link/support/SingleFlight.java`）。

33. **缓存与 DB 双写一致性？** 出处 [50-redis.md](50-redis.md)。
    - 结论：不强求强一致，工程答案是"最终一致加窗口可说清"。项目组合拳：更新 DB 后写穿缓存，1 秒后延迟再删一次，短 TTL 兜底，行是真相。
    - 机制：先删后写最差（读请求在删除与提交之间查到旧值并回填，脏到 TTL 结束）；先写后删残留低概率的竞态回填；延迟双删用第二次删除清掉那个脏回填，延迟取"大于一次读请求的完整生命周期"。写穿让新值立即可用。
    - 边界/反例：把强一致真做出来，要么放弃缓存要么读写都加锁；项目的业务里"URL 变了旧 URL 还能用 1 秒"代价接近零。TTL 设多长，等于公开声明"允许缓存比数据库旧多久"。
    - 项目对照：写路径 update、setStatus、delete 三个入口统一走"提交后 peek 加 forget 退出合并窗口，evictCache，1 秒后 scheduleStaleClear"；跳转每请求读数据库行判状态，禁用与过期语义永不被旧缓存遮蔽（`src/main/java/com/linkforge/link/service/ShortLinkService.java`）。

34. **为什么命中缓存还要读数据库行？** 出处 [50-redis.md](50-redis.md)。
    - 结论：缓存里只有 URL 字符串这个派生值；行状态（禁用、过期、访问密码、恶意域名判定）必须强一致且以行为权威，所以每请求读行判状态，缓存只优化 URL 字段读与负答。
    - 机制：如果缓存整行，禁用后的链会被旧缓存遮蔽，重新启用前语义失灵；absent 负缓存只存"数据库确认不存在"，禁用的行绝不能进。
    - 边界/反例："缓存整行省一次查询"看起来合理，错在把状态当成了可缓存数据。L1 命中也一样要读行，L1 的收益只是省一次 Redis 往返。
    - 项目对照：跳转五层防线（限流、布隆、absent、L1/L2、singleflight 加行读）里只有第五层是权威判定；"行是真相"让缓存脏一秒的最坏后果被限定在 URL 字段本身（[81-linkforge-flows.md](81-linkforge-flows.md) 链 4）。

35. **Redis 为什么快？单线程为什么是优势？Redis 6 的多线程改了什么？** 出处 [50-redis.md](50-redis.md)。
    - 结论：三层原因：纯内存操作；IO 多路复用加单线程事件循环（没有锁竞争与上下文切换）；高效数据结构（SDS、跳表、listpack）。单线程是特性不只是限制：每条命令天然原子。**多线程 IO 是 Redis 6.0 的 io-threads 配置（默认关闭），只做网络读写与协议解析，命令执行仍是单线程，原子性结论不变。**
    - 机制：单条命令原子，但多条命令的组合不原子；复合操作要原子就把多条命令压进一段 Lua 脚本，Redis 把整段脚本当一条命令执行，脚本期间不处理任何其他客户端命令。
    - 边界/反例："Redis 6 之前就多线程 IO"是错的版本口径。脚本必须短，慢脚本阻塞所有客户端；集群下脚本只应访问同一槽的键。
    - 项目对照：滑动窗口限流就是把"ZREMRANGEBYSCORE 清窗、ZCARD 计数、ZADD 记账"压进一个脚本，还用 Redis TIME 取时钟，免多实例应用钟漂移污染窗口（`src/main/resources/redis/sliding_window.lua`）。

36. **限流算法对比？** 出处 [50-redis.md](50-redis.md)。
    - 结论：固定窗口实现简单但边界有突刺；滑动窗口日志精确到次数但每请求要记账；令牌桶控速率且允许突发；漏桶整流输出。算法跟着语义选。
    - 机制：固定窗的突刺来自窗口交界处可能通过两倍配额；滑窗日志把"窗口内放行数"变成对记账成员的计数，还能算出诚实的 Retry-After（最老成员出窗还差多久）。
    - 边界/反例：滑窗日志的存储代价在大流量下显著；"日历边界重置"这种语义滑窗表达不了，固定窗反而是对的。被拒请求绝不能记账，否则攻击者能把自己永久锁死在窗口里。故障时的开门与锁门按组件角色定：限流是保护措施不是安全边界，Redis 挂了 fail-open 放行并计数，让静默失效始终可见；对照抓取端点的令牌为空时 fail-closed 拒绝所有人，因为指标端点泄露信息比不可用更糟。
    - 项目对照：跳转、创建、注册三个入口用滑窗日志（Redis ZSET 加 Lua 加 Redis TIME，被拒不占名额）；API Key 日配额刻意用固定窗，因为自然日本来就是日历边界（`src/main/java/com/linkforge/common/ratelimit/RedisRateLimiter.java`）。

37. **Redis 持久化 RDB 与 AOF？** 出处 [50-redis.md](50-redis.md)。
    - 结论：RDB 是某一刻的全量快照，恢复快、可能丢最后一次快照之后的窗口；AOF 追加每条写命令，丢得少（everysec 最多丢 1 秒）、文件大、靠重写压缩。
    - 机制：RDB 由主进程 fork 子进程写，fork 瞬间父子共享物理页（写时复制），主进程继续接写；AOF 三档刷盘 always、everysec、no。选型按数据性质：纯缓存 RDB 足够（丢了回源数据库），当准持久层用 RDB 加 AOF 混合。
    - 边界/反例：fork 本身阻塞主进程且耗时与页表大小成正比，大实例是风险点；"Redis 挂了也不丢"对纯缓存是伪需求。
    - 项目对照：项目是缓存语义，部署配置未配持久化策略，如实说；Redis 挡流量，MySQL 是真相，把 Redis 当持久库用是设计错误（`docker-compose.yml`）。

## 消息队列

38. **消息丢失三个环节与补丁？** 出处 [51-rabbitmq-outbox.md](51-rabbitmq-outbox.md)。
    - 结论：生产端丢在"以为发成功其实没有"（补丁：publisher confirm 加 mandatory 与 returns）；broker 丢在宕机时内存消息蒸发（补丁：交换机、队列、消息三层持久化）；消费端丢在自动 ack 后处理崩了（补丁：手动 ack 加失败不重回队列进死信）。三环节之外最外层还有 Outbox，保证"事件与业务同生共死"。
    - 机制：confirm 只保证 broker 收到，mandatory 让不可路由的消息退回而不是静默丢弃；durable 只保证"重启后还在"，不等于高可用；`default-requeue-rejected: false` 让失败消息经死信交换机落 DLQ，否则毒消息回到队头以微秒级节奏空转，打满消费者并堵死队列。
    - 边界/反例：用返回值判断发送成功（confirm 缺失）是静默丢消息的根源；三层持久化缺任何一层，broker 重启消息就蒸发。
    - 项目对照：relay 同步等 ack（最多 5 秒），ack 前绝不标 SENT；不可路由的消息经 ReturnsCallback 检出；消费异常 reject 不 requeue 落 DLQ（`src/main/java/com/linkforge/stats/service/ClickOutboxRelay.java`）。

39. **重复消费怎么保证不重算？** 出处 [51-rabbitmq-outbox.md](51-rabbitmq-outbox.md)。
    - 结论：ack 本身也是网络消息会丢，broker 分不清"没处理"与"处理了但 ack 丢了"，所以 at-least-once 投递必然重复。工程答案是 at-least-once 加消费端幂等，效果等价 exactly-once；把 exactly-once 当机制去承诺是不可证的，轻率声称是减分项。
    - 机制：幂等的通用实现是确定性业务键加数据库唯一约束：消费前直接 INSERT 去重表，撞主键说明处理过，安静跳过。不用"先查后插"，查和插是两步（TOCTOU），并发下双双通过、双计。
    - 边界/反例：随机 UUID 做不到幂等，重放时拿不到上次那个随机数；键必须对同一逻辑事件的每次投递都相同。追问"去重表会不会无限大"，如实答按业务周期清理，或承认本项目未做清理挂 backlog。
    - 项目对照：eventId 确定性生成（同步写开着时是 log 加明细自增 id，同一明细重放 N 次得到同一 eventId），`lf_consumed_event` 主键即 eventId，撞键直接 return；MySQL 对撞键语句只回滚该语句，跳过是安全的（`src/main/java/com/linkforge/stats/service/ClickStatsService.java`）。

40. **DB 写成功但 MQ 发失败怎么办？** 出处 [51-rabbitmq-outbox.md](51-rabbitmq-outbox.md)。
    - 结论：业务落库与发 MQ 是两个系统，先库后发丢事件、先发后库出幽灵事件、事务里发 MQ 拉长事务且回滚后消息已出，双写无解。解法是 Outbox：同一数据库事务里写业务并插 outbox 行（PENDING），独立 relay 轮询投递，broker ack 后标 SENT，失败指数退避重试。
    - 机制：事件与业务同生共死靠本地事务的原子性；"宁可重发不可丢发"，markSent 放在 publish 之后，ack 后崩溃的重复由消费端幂等吸收。退避公式 min(60, 2 的 n 次方)，位移要封顶：long 位移距离只取低 6 位，不封顶第 64 次会跌回 1 秒重试风暴重来。
    - 边界/反例：对比 RocketMQ 事务消息（半消息加回查，可靠性由 MQ 品牌承担但绑定生态）与 CDC（canal 订阅 binlog，基础设施重、事件语义要从表变更反推）：Outbox 用现有 MySQL 加 RabbitMQ，代价是秒级延迟和一张表。
    - 项目对照：relay 每 2 秒一批 100 条，重试 30 次约 25 分钟后标 FAILED 留人工（payload 完整，一条 UPDATE 改回 PENDING 即重放）；发 MQ 的能力抽象在 `ClickEventPublisher` 接口上，换 MQ 只换 relay 的投递实现（`src/main/java/com/linkforge/stats/service/ClickOutboxRelay.java`）。

41. **消息积压怎么办？** 出处 [51-rabbitmq-outbox.md](51-rabbitmq-outbox.md)。
    - 结论：先观测（积压 gauge 看在涨还是平稳），再扩容（提批量或扩消费者），再评估降级，最后根治消费侧瓶颈。顺序错了会把治理做成玄学。
    - 机制：积压 gauge 不在 Prometheus 抓取路径上查库，而是后台任务每 15 秒采样进内存，把抓取延迟与数据库压力解耦；relay 每 2 秒一批天然构成投递节奏上限，洪峰时 outbox 表就是缓冲区。
    - 边界/反例：没观测就扩容，可能消费瓶颈根本在数据库写；提批量要看下游能不能吃下。
    - 项目对照：`BacklogGauges` 暴露 outbox 积压、webhook 积压、webhook 失败数三个指标；另配 `ClickLogReconciler` 每 30 秒对账，兜"事件已投出但消费没成"的尾巴（`src/main/java/com/linkforge/observability/BacklogGauges.java`）。

42. **为什么不用 Kafka？消息顺序怎么保证？** 出处 [51-rabbitmq-outbox.md](51-rabbitmq-outbox.md)。
    - 结论：Kafka 题答场景：本项目是业务事件广播加可靠性演练，RabbitMQ 的 confirm 与 DLX 语义完整且栈小；Kafka 的分区与回放优势在日志流场景，数据量用不到。顺序题答取舍：分区键路由到同一队列加单消费者可保序，代价是并行度；项目按天聚合不依赖顺序，如实说没做。
    - 机制：RabbitMQ 消费即删，与 outbox 行状态互为备份；真要迁 Kafka，消费端 eventId 幂等原样生效，可靠性设计不绑定 MQ 品牌。乱序无害的前提是消费逻辑对顺序不敏感（按天聚合加幂等去重）。
    - 边界/反例：硬编"Kafka 不好"不可信，承认边界（吞吐量级、回放能力）比硬编理由加分。
    - 项目对照：拓扑是 topic 交换机加一条字面量绑定，通配符退化成精确匹配但留了扩展余地；单队列内先进先出，乱序只发生在重投与多消费者（`src/main/java/com/linkforge/config/RabbitMqConfig.java`）。

## 网络与安全

43. **三次握手为什么不是两次？** 出处 [20-network.md](20-network.md)。
    - 结论：两个理由：确认矩阵缺一格，两次时服务端还不知道客户端能不能收；防历史连接，迟到的旧 SYN 会让服务端白白建连接傻等。
    - 机制：SYN、SYN+ACK、ACK 三步之后，"我能发、我能收、对方能发、对方能收"四种组合才全部验证完；第三次握手的 ACK 给了客户端否决权，对不知从哪来的 SYN+ACK 回 RST。第三次 ACK 可以顺便携带数据，所以三次不等于先空跑三个来回。
    - 边界/反例：四次挥手不能合并成三次，因为收到 FIN 只代表对方发完了，我可能还有数据（半关闭状态）。主动关闭方在 TIME_WAIT 停 2MSL：最后一个 ACK 丢了要补发，并让旧报文自然死亡不污染新连接。
    - 项目对照：跳转接口同时注册 GET 与 HEAD，HEAD 探测不记点击；服务器的 keep-alive 复用连接，省的正是握手与挥手的开销（`src/main/java/com/linkforge/link/controller/RedirectController.java`）。

44. **301 与 302 的差别？短链为什么必须 302？** 出处 [20-network.md](20-network.md)。
    - 结论：301 永久、302 临时。301 被浏览器长期缓存后，请求不再回到短链服务，三条后果：点击统计断流、改目标无效、门禁（拦截、警示、解锁）被绕过。短链每次跳转都要回服务记点击、查状态，所以必须 302。
    - 机制：规范语义只有"永久与临时"两个词的差别，差别由浏览器实现放大；可启发式缓存的状态码里有 301 没有 302，所以 302 不需要额外声明别缓存。同场加映：410 是"曾经存在已删除"（过期链），404 是"现在不存在"（禁用链可恢复），语义给对，缓存与爬虫的行为才对。
    - 边界/反例：把 302 换成 301 功能看起来正常，坏的是统计正确性，这类错误没有报错只有数据悄悄断流，最难排查。
    - 项目对照：放行走 302 加 Location；解锁成功走 303（让浏览器用 GET 访问 Location，POST-Redirect-GET 防刷新重复提交密码）（`src/main/java/com/linkforge/link/controller/RedirectController.java`）。

45. **JWT 的原理与登出问题？** 出处 [32-spring-security.md](32-spring-security.md)。
    - 结论：三段 Base64URL：头（算法）、载荷（claims）、签名（用只有服务端知道的密钥对前两段做 HMAC-SHA256）。签名防篡改不防泄露，载荷肉眼可解，敏感信息不放。无状态验签不查库、水平扩展友好；代价是签发后无法单方作废，登出只能短过期、换密钥、或把 Session 表加回来的黑名单。
    - 机制：载荷改一个字节，重算的签名就对不上，而重算需要密钥；用 Base64URL 而不是普通 Base64，是因为加号与斜杠在 HTTP 头和 URL 里会被转义。作废的能力来自状态，放弃状态就放弃了作废。
    - 边界/反例：把 JWT 当加密用是高频误解；过期时间设长泄露窗口大，设短用户频繁被登出，续期本身又要引入新的凭证管理。
    - 项目对照：项目把撤销做了变体：密码短链解锁 cookie 的签名材料里绑入 pwdVer（当前密码哈希的 SHA-256 指纹），改密码则所有旧 cookie 立即失效，撤销从 token 层挪到签名材料层（`src/main/java/com/linkforge/link/support/LinkAccessTokens.java`）。

46. **BCrypt 为什么慢是优点？** 出处 [32-spring-security.md](32-spring-security.md)。
    - 结论：口令存储要按"数据库终将泄露"设计。慢哈希把离线爆破"试一次"的成本抬到毫秒级，慢是特性不是缺陷；盐内嵌在哈希串里防彩虹表；cost 是可调旋钮，加 1 耗时翻倍。
    - 机制：BCrypt 用 2 的 cost 次方轮密钥扩展做出成本；60 字符的输出串自带版本、cost、22 字符盐、31 字符哈希，校验时从串里读出参数对输入再算一遍，登录方付多少成本爆破方就付多少，没有捷径。
    - 边界/反例：MD5、SHA-256 这类快哈希即使加盐也挡不住逐个试，预计算表（彩虹表）挡得住、逐个猜挡不住；"盐要保密"是误解，保密的是慢哈希的代价不是盐。
    - 项目对照：解锁接口的两级限流先于 BCrypt 执行，爆破请求买不到慢哈希的 CPU（测试钉住"第 6 次正确密码仍 429"）；校验顺序也是先便宜的过期检查后昂贵的 HMAC，把攻击者挡在昂贵操作之前（`src/main/java/com/linkforge/link/support/LinkAccessTokens.java`）。

47. **SSRF 防什么、怎么防？** 出处 [32-spring-security.md](32-spring-security.md)。
    - 结论：服务端把用户填的 URL 拿去请求，攻击者借服务器出手打内网：环回、私网、链路本地（云元数据 169.254.169.254，背后是临时访问凭证）。防法：限制 scheme 只许 http 与 https，URL 解析成结构后对 host 做 DNS 解析，校验解析出的每一个 IP，且不跟随重定向。
    - 机制：字符串黑名单没用：十进制写法、IPv6 缩写都能绕，更根本的是 URL 里写的是域名，真正要连的 IP 解析后才知道；`getAllByName` 返回数组是有原因的，一个域名可能多个地址，漏检一个就放行。保存时校验不够，域名指向可以随时改，每次发送前要再查。
    - 边界/反例：项目诚实承认未防 DNS rebinding（校验时解析与真正连接之间域名可以再解析，javadoc 原话），彻底解法要么连接后校验要么让出网流量走受控代理，本波次不做；命中黑名单时统一返回 blocked target，不回显解析出的地址，防止校验器被当内网探测仪。
    - 项目对照：`WebhookTargetGuard` 保存时抛 400 拒绝，且每次发送前 isBlocked 再查一遍（`src/main/java/com/linkforge/webhook/support/WebhookTargetGuard.java`）。

## 操作系统

48. **进程与线程的区别？上下文切换贵在哪？** 出处 [21-os.md](21-os.md)。
    - 结论：进程是资源分配单位（独立虚拟地址空间、文件描述符表），线程是 CPU 调度单位（共享所属进程的地址空间，各持栈与寄存器）。必须隔离时用多进程，高并发共享数据用多线程，代价是要处理竞态。
    - 机制：切换的直接成本是保存恢复寄存器与换内核栈（微秒级），间接成本是大头：CPU 缓存变冷，进程间切换还要换页表、TLB 大面积失效。后端服务的线程大部分时间阻塞在 IO 上主动让出 CPU。
    - 边界/反例：CPU 密集任务线程数超过核数只会增加切换不会更快；IO 密集可以远超核数，最终靠压测。阻塞在 socket 读上的线程在 jstack 里也是 RUNNABLE，要看栈顶判断是不是在等网络。
    - 项目对照：应用进程的线程清单可数：Tomcat 工作线程（默认上限 200）、`cache-evictor`、Spring 调度线程、GC 线程组；一个空 JVM 就自带十几个基建线程，算线程与内存预算别漏（`src/main/java/com/linkforge/link/service/ShortLinkService.java` 第 80 行）。

49. **BIO、多路复用与 Tomcat 的线程模型？** 出处 [21-os.md](21-os.md)。
    - 结论：BIO 一连接一线程，等待押上一整条栈；多路复用用少数线程经 epoll 盯全部连接，只处理就绪的。Tomcat（Spring Boot 默认 NIO connector）的准确说法是：传输层多路复用，处理层一个请求占一个线程直到结束，期间业务 IO 全部同步阻塞。
    - 机制：epoll 在内核维护注册表与就绪列表，开销正比于就绪数而不是总连接数；select 与 poll 每次全量拷贝 fd 集合加线性扫描，连接多时差距巨大。"Tomcat 是 BIO"这句口语指的是同步阻塞的处理模型，不是传输层；线程池上限（默认 200）是并发处理的硬顶，第 201 个并发请求排队。
    - 边界/反例："一个连接一个线程"是 Tomcat 8.5 之前 BIO connector 的过时描述；空闲 keep-alive 连接不占任何工作线程。想撑更高并发，方向是让单请求更快（缓存、合并）或换处理模型（Netty、虚拟线程）。
    - 项目对照：跳转热路径上每次 JDBC、Redis 调用都是阻塞 IO，都把线程押在原地；Redis 缓存与 singleflight 合并读正是在给线程减负（`src/main/java/com/linkforge/link/service/ShortLinkService.java`）。

50. **文件描述符是什么？"Too many open files" 怎么回事？零拷贝省了什么？** 出处 [21-os.md](21-os.md)。
    - 结论：fd 是进程打开文件的整数下标，Linux 一切皆文件，每条 TCP 连接也是一个 fd；表满了再打开就报 Too many open files。零拷贝（sendfile）省的是数据进出用户区的两次 CPU 拷贝，不是一次拷贝都没有。
    - 机制：fd 不 close 就一直占表位，Java 侧的防线是 try-with-resources。传统 read 加 write 是四次搬运两次系统调用，其中两次是 CPU 干的纯搬运；sendfile 让数据在内核内从页缓存直达 socket 缓冲，Java 对应 `FileChannel.transferTo`。
    - 边界/反例：连接数暴涨打爆 fd 表，看起来像"文件问题"其实是连接问题；内容需要在用户区加工（压缩、加密）时零拷贝用不上。Windows 的对应物是句柄，包含大量非文件对象，绝对值不能跨平台比较，看增量趋势。
    - 项目对照：MySQL、Redis、RabbitMQ 的每条连接都是 fd，连接池上限本质是给 fd 表设预算；测试套件的连接预算（上下文数乘池大小对 max_connections）是同一笔账的另一种形式（`src/test/java/com/linkforge/AbstractIntegrationTest.java`）。

## 工程与测试

51. **单测与集成测试的边界？为什么不 mock 自己的基础设施？** 出处 [60-testing-ci.md](60-testing-ci.md)。
    - 结论：单测覆盖纯函数与类内逻辑，毫秒级无依赖，类名以 Test 结尾；集成测试覆盖跨模块与真实依赖行为，用 Testcontainers 起真容器，类名以 IT 结尾。mock 的正确位置是外部第三方，不是自己的 MySQL、Redis、MQ：mock 测的是你对依赖行为的想象，容器测的是现实。
    - 机制：MySQL 的方言 SQL（ON DUPLICATE KEY UPDATE）、Redis 的 Lua 语义、MQ 的 confirm 时序，替身模拟不出来，测试绿了上线照样错。工程上要处理容器成本：静态单例容器全 JVM 共享，Spring 上下文按配置缓存，连接预算等于上下文数乘池大小对 max_connections。
    - 边界/反例：上下文缓存不释放，每个上下文一个 Hikari 池（默认 10），MySQL 默认 max_connections 等于 151 时约 16 个上下文就报 Too many connections，项目真实踩过（pom 注释记录）。
    - 项目对照：容器参数提到 500，心算 27 乘 10 加 5 乘 4 等于 290，预算成立；211 例的构成是单元 90、集成 119、时区 fork 2（`src/test/java/com/linkforge/AbstractIntegrationTest.java`）。

52. **怎么测异步与依赖时钟的行为？** 出处 [60-testing-ci.md](60-testing-ci.md)。
    - 结论：轮询加终值断言（Awaitility），不用 sleep：sleep 猜短了 flaky（时好时坏）、猜长了全套件变慢。测试环境必须能重现故障条件：默认环境复现不了的 bug（时区、时钟、字符集），把那个条件显式造出来钉进 CI。
    - 机制：Awaitility 最多等 N 秒，期间轮询断言，条件一成立立刻过，只花必要的时间，超时失败带着断言消息。时区 fork：给单个测试独立 JVM 加 `-Duser.timezone`，人为制造 JVM 与 MySQL 的时区错配，旧实现跑成红、新实现跑成绿。
    - 边界/反例：普通环境 JVM 与数据库时区同源，时钟 bug 复现不出来，新旧实现都绿；一个"怎么改都绿"的测试什么都证明不了。
    - 项目对照：`WebhookRetryClockIT` 在 Asia/Shanghai fork 里断言重试时间距数据库 NOW() 在 20 秒内，旧实现读出约 28800 秒（8 小时），一把把新旧实现分开（`src/test/java/com/linkforge/webhook/WebhookRetryClockIT.java`）。

53. **main 恒绿为什么是纪律？红先工作流防什么？** 出处 [60-testing-ci.md](60-testing-ci.md)。
    - 结论：push 即 CI（GitHub Actions 上 Temurin 17 跑 mvn -B verify，runner 自带 Docker 所以 Testcontainers 直接能跑），红了不许合。红先工作流：先写一个会红的测试证明 bug 存在，亲眼看它红，再修绿，红绿两个提交一起进 PR。
    - 机制：CI 在干净的机器上从零装环境，杜绝"我机器上是好的"；Maven 缓存键带 pom 哈希，依赖不变命中缓存。红提交是"bug 存在过"的证据，绿提交是"修复有效"的证据，评审者能看到缺陷与修复的完整对应。
    - 边界/反例：红先同时防住两种测试烂尾：永不绿（修不动立刻可见，不烂在分支里）、永不红（一开始就绿的测试要么测错行为要么没测到缺陷路径）。
    - 项目对照：ci.yml 全文 24 行，触发条件覆盖 push 与 PR；本机 Docker 与 Testcontainers 版本不兼容时集成测试跑不了不是代码错，跑单测、集成测试交给 CI（`.github/workflows/ci.yml`）。

54. **指标 tag 为什么禁止无界值？** 出处 [12-jvm.md](12-jvm.md)、[61-docker-deploy.md](61-docker-deploy.md)。
    - 结论：标签的每个新取值都会生成一套新的指标对象，基数（时间序列数）等于各标签取值数的乘积；无界值（原始 URI、用户 ID）让基数爆炸，拖垮指标存储，同时是堆上只增不减的泄漏。
    - 机制：治理方向是把取值集合钉死：白名单之外的值统一折叠成一个固定值，宁可丢维度不可丢指标本身。新端点必须显式加入白名单，漏加的静默并入固定值，这是安全的失败方向。
    - 边界/反例：这与静态集合只增不减退化为内存泄漏是同一类问题：只进不出的强引用链。
    - 项目对照：`HttpUriTagAllowList` 用 MeterFilter 把白名单外的 uri 折叠成 UNKNOWN，类注释原话是新端点不进白名单就静默并入 UNKNOWN，这是安全的方向（`src/main/java/com/linkforge/observability/HttpUriTagAllowList.java`）。

55. **数据库连接池为什么必要？Hikari 为什么快？** 出处 [60-testing-ci.md](60-testing-ci.md)、[40-mysql.md](40-mysql.md)。
    - 结论：建连贵（TCP 握手加认证加鉴权，毫秒级），池化复用让请求只付借还的成本；Hikari 快在字节码级优化与无锁设计，一句话即可，不必展开。
    - 机制：Spring Boot 默认 Hikari，池有 maximumPoolSize；每个 Spring 上下文有自己的池，多个上下文共享同一个数据库时要做连接预算。
    - 边界/反例：池不是越大越好，超过数据库的 max_connections 全部排队或报错；每条连接也是一个 fd，池上限同时是 fd 预算。
    - 项目对照：32 个集成测试类的连接预算（27 乘 10 加 5 乘 4 等于 290，小于 500）就是"每上下文一池"对出来的账；生产上每应用一池，预算要写进部署检查（`src/test/java/com/linkforge/AbstractIntegrationTest.java`）。

## 场景与分布式

56. **分布式锁怎么实现、有什么坑？什么时候该用？** 出处 [70-distributed.md](70-distributed.md)、[50-redis.md](50-redis.md)。
    - 结论：最小实现是 `SET lock:key uniqueValue NX PX 30000`（NX 抢锁、TTL 防持有者崩溃死锁、唯一值防误删他人锁），解锁必须"值相等才删"且用 Lua 原子完成。两个经典坑：业务没做完锁先过期（要续期，Redisson 的看门狗就是自动续期）、Redis 主从切换丢锁。判断口诀：能用唯一约束解决的绝不上锁。
    - 机制：唯一约束在存储引擎内原子判定，没有锁单点、没有持锁崩溃的中间态、没有续期复杂度，正确性永远强于分布式锁。锁留给约束表达不了的互斥，比如全系统同一时刻只允许一个实例跑清理任务。
    - 边界/反例：用分布式锁解决热点 key 回源是常见误用：锁把读串行化，不消除读，N 次劳动一次没少，只是排队。
    - 项目对照：项目有意不用分布式锁：读路径用 singleflight 合并（javadoc 原话：分布式锁只会串行化读取而不消除它们），写路径靠 `uk_code` 与去重表唯一键；跨进程的原子判定下沉给 Redis Lua 脚本（`src/main/java/com/linkforge/link/support/SingleFlight.java`）。

57. **设计一个短链系统（系统设计题）？** 出处 [81-linkforge-flows.md](81-linkforge-flows.md)、[80-linkforge-architecture.md](80-linkforge-architecture.md)。
    - 结论：答题框架六步：需求（读写比悬殊，码长数学：62 的 n 次方覆盖容量）；存储（随机 Base62 码加唯一索引兜底）；读路径（限流、防穿透、缓存、行是真相）；写路径（异步统计，主路只落库与 outbox）；可靠性（Outbox 加确定性幂等键加对账）；追问应对（热点合并读、多实例边界表）。
    - 机制：码为什么随机而不是发号器：支持用户自定义、无规律不可枚举，撞码靠 `uk_code` 重试。读多写少决定缓存是主战场，读路径五层防线每层有降级。统计不该拖累 302，点击事件同事务落 outbox 由 relay 异步搬。
    - 边界/反例：多实例的诚实清单：Bloom 各自播种（别的实例新建的码在本实例假阴性 404）、L1 缓存有 10 秒不一致上界、定时轮询会双拉（靠下游幂等兜底）。判断标准一句话：状态放在 JVM 里就各自为政，放在 Redis 或数据库里就天然共享。
    - 项目对照：这就是你的项目，按 81 号文档的链逐条讲即可：链 3 创建（幂等键加撞码重试）、链 4 跳转（五层门）、链 6 统计（outbox 全链）。系统设计题对你是主场，主动挂"这个在我项目里对应"是加分表达。

## 使用节奏

- 阶段 5 前每周末抽 10 题口述并录音（[00-roadmap.md](00-roadmap.md)）；冲刺期每日 5 题加回听。
- 四段里卡住的那一段就是薄弱点：结论卡住回出处文档记主干，机制卡住读到能自己推导，边界卡住找反例，项目对照卡住去 [81-linkforge-flows.md](81-linkforge-flows.md) 把对应链路走一遍。
- 与 [91-interview-project.md](91-interview-project.md) 的项目追问树配合：知识题答完主动挂一句"这个在我项目里对应"，是把知识面与项目深度焊在一起的加分表达。
- 追问答不上是信号不是失败：回出处文档把该点补到 L3，一周后重测（[93-glossary-review.md](93-glossary-review.md)）。

## 自测

1. 抽查本档任意 10 题：不看答案，能否按"结论、机制、边界、项目对照"四段口述？
   答：四段少任何一段都算不通过。结论段检验记忆，机制段检验理解，边界段检验使用经验，项目段检验知识与项目的缝合度。
2. 六条容易背错的口径各用一句话复述：值传递、Integer 缓存的规范边界、sealed 与穷举检查、事务失效的两类机理、MP 的 eq 传 null、Redis 6 的 io-threads。
   答：只有值传递；负 128 到 127 是 JLS 保证、区间外是实现自由；17 的 if 链没有穷尽检查，穷尽来自 21 的 switch 模式；自调用与非 public 是代理未参与，吞异常与 checked 是回滚规则；eq 传 null 生成 col 等于 NULL 查不到行；Redis 6.0 的 io-threads 只做多线程网络 IO，命令执行仍单线程。
3. 任选一题，以面试官视角连追三层，每层都有实质内容吗？
   答：追不下去的那层就是下周要补的点，回出处文档对应小节，一周后重测同一题。
4. 把 57 题标成"能答、半能答、不能答"三色，不能答的按域排进下周复习了吗？
   答：按域排而不是按题排，同域的题共用一份文档，补一片能带起一组题（[93-glossary-review.md](93-glossary-review.md)）。
