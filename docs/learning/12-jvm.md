# JVM：内存区域、垃圾回收与排查工具

优先级 P0，目标层级 L2（读懂运行时数据区、看懂 `java` 启动参数、说出 OOM 排查思路）；GC 算法内部实现为 P2，不深入。

## 先说人话（初学者第一遍从这里读）

线上的 Java 程序半夜崩了，日志只有一行 `java.lang.OutOfMemoryError: Java heap space`。没人改代码，堆为什么会满？满的时候里面装的是什么？这一章回答的就是这些问题：对象住在哪里、谁在清理它们、清理失败时你按什么顺序查。

写 Java 时你从没调用过"释放内存"，因为清理由 JVM（Java 虚拟机，运行字节码的程序）代劳。`javac` 把 `.java` 编译成 `.class` 字节码，JVM 执行字节码；JDK 里除了 JVM 和类库，还带一套诊断工具，本章用到的 `jps`、`jstat`、`jmap`、`jstack` 都在其中。

第一遍带走三件事：

1. 进程内存分两半：**每个线程私有的栈**（方法调用在这里进行，调用结束就弹出）和**全体线程共享的堆**（所有 `new` 出来的对象住在这里，垃圾回收的主战场）。
2. 回收的判定标准是"从根出发还找不找得到"，不是"还有没有人用"。断了最后一条引用路径，对象才变成垃圾。
3. 出事有现成工具：`jps` 找进程，`jstat` 看 GC 频率，`jmap` 看堆里有什么，`jstack` 看线程在干什么。四个都是 JDK 自带，Windows 直接可用。

G1 怎么组织内存、回收器怎么分工，放在本章后半段。第一遍只读前半段，也能完成 OOM 排查。

## 学习目标

- 画出运行时数据区，说清哪些线程共享、哪些线程私有，并指出哪些是 JVM 规范的要求、哪些只是 HotSpot 的实现选择。
- 说出 `StackOverflowError` 与各类 `OutOfMemoryError` 何时出现，走完一遍 OOM 从发现到归因的完整流程。
- 看懂 `java -Xms1g -Xmx2g -Xss512k -XX:+HeapDumpOnOutOfMemoryError -jar app.jar` 里每个参数的含义。
- 在本机用 `jps`、`jstat`、`jmap` 观察一个运行中的 JVM 进程，并读出关键列的含义。
- 说清 LinkForge 把运行时指标暴露在哪里，以及这个入口和 JVM 的关系。

## 前置知识

[类型与引用](java/10-01-types-and-references.md)：引用是什么、"变量在栈上、对象在堆上"这句话的准确边界（那里已经说过：这句是 HotSpot 的实现事实，不是规范条文，本章反复用到这条分界线）。Error 与 Exception 的分层见 [异常](java/10-06-exceptions.md)。

## 核心概念

### 规范画地图，HotSpot 决定布局

读任何 JVM 资料都要先分清两个来源，否则背到的东西会互相打架：

- **JVM 规范**（Java Virtual Machine Specification）规定"有哪些内存区域、各归谁管"。它不规定堆里怎么分区，不规定用什么算法回收。任何实现了规范的虚拟机都长这样。
- **HotSpot** 是 Oracle/OpenJDK 默认的虚拟机实现。平时说的分代、Eden、Survivor、TLAB、G1，全是 HotSpot 的实现细节。换一个实现（比如 OpenJ9）这些词可能根本不存在。

版本事实先钉死一条：JDK 7 及以前，HotSpot 用"永久代"（PermGen）这块堆内区域实现方法区；JDK 8 起（JEP 122）永久代被移除，方法区改用"元空间"（Metaspace）实现，元空间用的是本地内存，不再从堆里划。你会在旧资料里看到 `-XX:PermSize`，在 JDK 8+ 上这个参数没有效果，遇到直接判定资料过时。

### 运行时数据区：一个 Java 进程的内存地图

先看问题：一个 Java 进程启动后，它占用的内存到底分成了哪几块？不知道这个地图，后面所有的报错你都不知道发生在哪个区域。

规范层面的地图是五块，共享性是规范直接规定的：

```
JVM 进程
├─ 线程共享 ──────────────────────────────────────────
│   堆（Heap）          对象主体；垃圾回收的主战场
│                       【HotSpot：内部分新生代/老年代；G1 下再切成 region】
│   方法区              类元信息（类的字段、方法、常量池）
│                       【HotSpot：JDK 8 起由元空间实现，用本地内存】
├─ 每线程一份 ────────────────────────────────────────
│   虚拟机栈            方法调用的空间
│                       【HotSpot：大小由 -Xss 控制】
│   本地方法栈          为 native 方法（C/C++ 实现，如 Object.wait）
│   程序计数器          记录当前线程执行到哪条字节码
└─────────────────────────────────────────────────────
```

几个区域逐个说清职责和边界：

- **程序计数器**：每个线程记录自己执行到哪条字节码。它必须每线程一份，因为 CPU 在线程间来回切换，切回来时必须恢复到正确的位置。它是唯一一块不存在 `OutOfMemoryError` 可能的区域（规范如此规定）。
- **虚拟机栈**：每调用一个方法，栈上压入一个**栈帧**；方法返回，栈帧弹出。栈深超出限制抛 `StackOverflowError`，栈空间申请不到抛 `OutOfMemoryError`。
- **本地方法栈**：给 `native` 修饰的方法用。`native` 方法指由 C/C++ 实现的方法，比如 `Object.wait()` 的底层等待逻辑。初学只需要知道它的存在。
- **堆**：所有 `new` 出来的对象主体住在这里（HotSpot 对不逃逸对象有优化，见本章误区一节）。堆是所有线程共享的，所以多线程操作同一个对象才有并发问题（[13-concurrency.md](13-concurrency.md)）。
- **方法区**：存类的元信息。误解最多的一块：它不存对象，存的是"类长什么样"。JDK 8 起在 HotSpot 里由元空间实现，用本地内存，所以堆OutOfMemoryError 不代表方法区也没空间，两块要分开看。

### 栈帧与局部变量表：方法调用在栈上发生了什么

[10-01](java/10-01-types-and-references.md) 讲过"变量在栈、对象在堆"，现在把"栈"说精确：变量住的其实是**栈帧里的局部变量表**。每次方法调用，JVM 压入一个栈帧，栈帧至少包含三部分：

- **局部变量表**：本方法的参数和局部变量。存的是值——基本类型直接存值，引用类型存"指向堆对象的线索"（引用本身）。
- **操作数栈**：字节码计算的暂存区。`int c = a + b` 在字节码里是"把 a 压栈、把 b 压栈、相加、结果存回局部变量表"，操作数栈就是中间的暂存位置。初学只需知道它存在。
- **返回地址**：方法结束后回到哪个调用点继续执行。

方法返回时栈帧整个弹出，局部变量表随之消失，栈帧里的引用也就断了——这正是"局部变量不用手动置 null"的原因：出了作用域，帧没了，引用自动断。

栈的总深度有限。把一个没有出口的递归跑起来，栈帧会一层层压下去，直到超限：

```java
public class StackOverflowDemo {

    // 没有出口的递归：每调用一次，栈上多压一个栈帧，永不弹出
    static void recurse() {
        recurse();
    }

    public static void main(String[] args) {
        recurse();
    }
}
```

```text
运行异常：
Exception in thread "main" java.lang.StackOverflowError
	at StackOverflowDemo.recurse(StackOverflowDemo.java:5)
	at StackOverflowDemo.recurse(StackOverflowDemo.java:5)
```

读这个异常：类型是 `StackOverflowError`，说明是栈深超限而不是堆满。堆栈第一行是事发点，后面成百上千行全是 `StackOverflowDemo.recurse` 这一个方法——同一段堆栈大量重复，就是递归失控的指纹。栈上没有任何业务代码的痕迹，因为溢出恰好发生在压入新帧的那一刻。

栈深度由 `-Xss` 控制。下面这个变体捕获了错误并打印递归深度，用不同 `-Xss` 各跑一次（深度数值每次运行、每台机器都不同，所以只作示意）：

```java
public class DepthDemo {

    static int depth = 0;

    static void recurse() {
        depth++;
        recurse();
    }

    public static void main(String[] args) {
        try {
            recurse();
        } catch (StackOverflowError e) {
            System.out.println("overflow at depth " + depth);
        }
    }
}
```

```text
输出（示意）：
overflow at depth 22026
```

```bash
javac --release 17 -encoding UTF-8 DepthDemo.java
java DepthDemo          # 某次实跑：overflow at depth 22026
java -Xss256k DepthDemo # 某次实跑：overflow at depth 2711
java -Xss512k DepthDemo # 某次实跑：overflow at depth 9445
```

栈变小，能压的帧就变少，递归深度按比例缩。注意两点边界：一是 `StackOverflowError` 只影响当前线程，另一个线程的递归失控不会把你正在处理的请求拖下水（每线程一份栈）；二是 `Error` 也可以被 `catch` 捕获（上面代码演示了），但捕获后线程状态已不可信，生产代码不该这么做，这里只是为了观察。

### 堆与对象分配：new 出来的东西住哪

规范层面，堆就是"所有线程共享、存放对象实例"的一整块，规范没有说堆内部必须分代。分代是 HotSpot 的实现选择，而且不同收集器下连分代的长相都不一样。

HotSpot 经典分代布局（Parallel 这类收集器）：堆分成**新生代**和**老年代**。新生代再分一块 **Eden**（伊甸园）和两块 **Survivor**（幸存者）。新对象优先在 Eden 分配；Eden 满了触发一次回收，活下来的对象挪进 Survivor；每熬过一次回收，"年龄"加一；年龄达到阈值（HotSpot 默认 15，是个实现参数）就晋升到老年代。很大的对象可以跳过新生代直接进老年代。

对象分配快的关键是 **TLAB**（Thread Local Allocation Buffer，线程本地分配缓冲）：每个线程在 Eden 里预先划一小块私有区域，线程在自己的 TLAB 里分配对象时只移动一个指针，不需要和其他线程抢锁。锁竞争发生在"TLAB 用完了去申请新 TLAB"的时候，频率低得多。这是 HotSpot 的实现，解释了"Java new 一个对象为什么便宜"。注意比喻的失效边界：TLAB 只加速分配，不改变"对象都在堆上"这个事实。

大对象在 G1 下有专门待遇：超过 region（下一节讲）一半大小的对象叫 humongous 对象，直接放进连续的老年代 region，不经过新生代。串行/ParNew 收集器时代控制"多大算大对象"的参数是 `-XX:PretenureSizeThreshold`，它在 G1 下不生效——背参数前先确认自己用的是哪个收集器。

### 垃圾判定：可达性分析，不是"没人用"

先看问题：GC 怎么知道一个对象该回收？直觉答案是"没有人引用它了"，但"没有人引用"要数清所有引用才敢下结论，引用还可能成环——A 引用 B、B 引用 A，两个都没人用了，数引用数却都大于零。所以 HotSpot 不用数引用的办法。

实际做法叫**可达性分析**：从一组确定的起点（GC Roots）出发，顺着引用一条条走。能走到的对象活着；一个对象如果从所有根出发都走不到它，不管引用环多复杂，它就是垃圾。这个判定思路是所有主流 GC 共用的；GC Roots 具体包含哪些，规范没有列全，HotSpot 里至少包括：

- 各线程栈帧局部变量表里的引用（正在执行的方法摸得到的对象）；
- 静态字段引用的对象（类还活着，静态引用就一直够得着）；
- 被 native 代码持有的对象（JNI 引用）。

由第二条能推出一个重要结论：**静态集合（如 `static final Map`）只放不取，里面的对象永远可达，永远不是垃圾**——这就是 Java 也能内存泄漏的机制根源。"把引用置 null"本身不是回收开关，只有"断开从根出发的可达路径"这个动作才改变判定结果。

语言和类库层面还规定了四种引用强度（定义在 `java.lang.ref` 包的 API 文档里，属于规范行为，不是某个实现的私货）：

- **强引用**：`Object o = new Object()` 这种普通赋值。只要强引用链在，宁可 `OutOfMemoryError` 也不回收。
- **软引用**（`SoftReference`）：内存不足时才被清，适合做可有可无的缓存。
- **弱引用**（`WeakReference`）：下次 GC 必清。`ThreadLocalMap` 的 key 就是弱引用，泄漏细节见 [13-concurrency.md](13-concurrency.md)。
- **虚引用**（`PhantomReference`）：摸不到对象本身，只在对象被回收后收到通知，用于配合堆外资源的清理。

### 分代假设与回收器：为什么 GC 要分家

分代不是凭空设计，来自一个经验观察：**绝大多数对象朝生夕死**。一次 HTTP 请求里创建的 DTO、临时集合，请求结束就没人用了；只有少数对象（单例 Bean、缓存）长期存活。这个观察通常叫弱分代假设——"假设"二字要记住，它是经验规律，不是定理。

既然两类对象寿命天差地别，就分开管：新生代里死得快，就用高频、便宜的方式回收；老年代里都是老住户，低频、彻底地回收。存活率低时复制算法便宜（只复制活的对象），存活率高时复制反而贵——这就是"为什么分代"的完整答案，面试追问到这一层才算懂。

回收器重点讲 G1（Garbage First），因为从 JDK 9 起（JEP 248）它就是 HotSpot 的默认收集器，JDK 17、21 依然默认。设计思路：把堆切成大小相等的 **region**（区域，1 到 32MB，2 的幂），每个 region 临时扮演一个角色——Eden、Survivor、老年代都是逻辑标签，不是物理上连续的块。回收时 G1 优先清理"垃圾占比高"的 region，用最少的代价腾出最多的空间，Garbage First（垃圾优先）由此得名。你可以给它设一个停顿目标 `-XX:MaxGCPauseMillis=200`（默认 200 毫秒），G1 会按目标挑选每次清理多少 region——它是目标不是承诺，设得过小会逼 GC 频繁开工，反而拖慢整体。

两条真实证据说明"region 是逻辑分代"，来自本章后文 `jstat -gc` 的实跑输出：G1 进程的 `S0C`/`S1C`（两块 Survivor 的容量）是 0，因为 G1 没有固定两块 Survivor，Survivor 是一组动态挑选的 region；`OU`（老年代用量）在进程没发生过任何 GC 时就不为零，因为 10MB 的大数组作为 humongous 对象直接住进了老年代 region。

其他收集器一段带过，知道分工即可：

| 收集器 | 一句话定位 | 版本事实 |
|--------|-----------|---------|
| Serial | 单线程回收，停顿全部业务 | 始终可用，小内存工具场景 |
| Parallel | 多线程整段回收，重整体效率 | JDK 8 的默认收集器 |
| CMS | 与业务并发的老收集器 | JDK 9 弃用（JEP 291），JDK 14 移除（JEP 363） |
| **G1** | region 化、按停顿目标选择性回收 | JDK 9 起默认，17/21 仍是默认 |
| ZGC | 停顿极短且与堆大小基本无关 | JDK 15 转正（JEP 377），JDK 21 引入分代 ZGC（JEP 439） |

说清一个关系：Parallel 追求单位时间回收最多东西，单次停顿可以很长；G1 牺牲一部分整体效率换稳定的停顿上限；ZGC 把停顿压到更低，代价是更多的并发维护开销。小堆、常规 Web 应用（比如本项目）默认 G1 即可，先量测再谈调参。

### 启动参数：读懂 java 命令行

先说问题：接手一个项目，启动脚本里写着 `java -Xms2g -Xmx2g -Xss512k -XX:+HeapDumpOnOutOfMemoryError -jar app.jar`，每个参数你得能说出含义，否则出问题连"该不该改它"都判断不了。

规则先讲清：`-classpath` 这类标准参数由规范保证；`-X` 和 `-XX` 开头的是 HotSpot 扩展，规范不保证，其他 JVM（如 OpenJ9）不认识。下表每个参数单独解释：

- `-Xms`（initial，初始堆）与 `-Xmx`（maximum，最大堆）：堆从小到大伸缩有开销，生产上常把两个设成一样，让堆容量固定，容量可预期也便于和机器内存做预算。
- `-Xss`：每根线程的栈大小。调大允许更深的递归，但总内存 = 栈大小 × 线程数，盲目调大会让线程创建变难。
- `-XX:+HeapDumpOnOutOfMemoryError`：发生 OOM 时自动把堆转储（heap dump，堆的完整快照文件，`.hprof` 格式）写到磁盘。OOM 是事后难以复现的事故，这个参数等于"事故现场自动拍照"，必配。
- `-XX:HeapDumpPath=<路径>`：指定转储写到哪里，指向持久盘而不是易失目录。
- `-XX:MaxMetaspaceSize=<大小>`：元空间上限。不设时元空间默认只受物理内存约束，动态生成类的应用（大量反射、CGLIB）需要显式上限防止吃光内存。
- `-XX:MaxGCPauseMillis=<毫秒>`：G1 的停顿目标。
- `-Xlog:gc*`：打开 GC 日志（JDK 9 起的统一日志语法，替代 JDK 8 的 `-XX:+PrintGCDetails`）。
- `-D<名字>=<值>`：设置系统属性，代码里用 `System.getProperty("名字")` 读取。属性作用于整个 JVM 进程。

不传任何参数时堆多大？HotSpot 按机器自动估算，`PrintFlagsFinal` 能把估算结果打出来：

```bash
java -XX:+PrintFlagsFinal -version 2>&1 | grep -E "MaxHeapSize|InitialHeapSize"
```

```text
输出（示意）：
   size_t InitialHeapSize                          = 402653184                                 {product} {ergonomic}
   size_t MaxHeapSize                              = 6400507904                                {product} {ergonomic}
```

`{ergonomic}` 标记表示这个值不是谁显式设置的，是 JVM 自己算的：初始堆约为物理内存的六十四分之一，最大堆约为四分之一（本机约 24GB，算出最大堆约 6.1GB）。容器里 JDK 10+ 默认开启容器感知（`UseContainerSupport`），按容器限额而不是宿主机内存来算，细节见 [61-docker-deploy.md](61-docker-deploy.md)。

LinkForge 项目里 JVM 参数的三个真实落点：

- `pom.xml` 的 failsafe 插件有一个 id 为 `webhook-tz-it` 的 execution，`<argLine>-Duser.timezone=Asia/Shanghai</argLine>` 单独 fork 一个 JVM 进程跑 `WebhookRetryClockIT`（`src/test/java/com/linkforge/webhook/WebhookRetryClockIT.java`）。一个进程级参数制造出 JVM(+8) 与 MySQL(UTC) 的时钟错配，从而复现真实故障 Bw2-M1（[82-linkforge-decisions.md](82-linkforge-decisions.md) 的 D9 节）。这是"参数不是玄学"的实例：一行参数改变了整个进程的默认时区语义。
- `.github/workflows/ci.yml` 用 Temurin 17 跑 `mvn -B verify`，没有配任何内存参数，CI 上的 JVM 全部走上面那套默认启发式。多数项目确实如此，默认够用是常态，别把"会调 GC"当成入职第一天的事。
- `docker-compose.yml` 里没有任何 JVM 参数，原因是应用本体不容器化（compose 注释写明 Prometheus 抓的是宿主机上的应用，容器里只有 MySQL、Redis、RabbitMQ 这些中间件）。将来若把应用容器化，`-Xmx` 要和容器内存限额一起定。

### 堆 OOM：复现一次完整的崩溃

先复现。一个 `ArrayList` 一直往里塞 1MB 的数组，只进不出：

```java
import java.util.ArrayList;
import java.util.List;

public class HeapOomDemo {

    public static void main(String[] args) {
        List<byte[]> keep = new ArrayList<>();
        while (true) {
            // 每轮分配 1MB，list 一直持有，GC 谁也回收不掉
            keep.add(new byte[1024 * 1024]);
        }
    }
}
```

完整命令（Git Bash 可用；先 cd 到一个临时目录，转储文件会写到当前目录）：

```bash
javac --release 17 -encoding UTF-8 HeapOomDemo.java
java -Xmx32m -XX:+HeapDumpOnOutOfMemoryError HeapOomDemo
```

实跑输出：

```text
输出（示意）：
java.lang.OutOfMemoryError: Java heap space
Dumping heap to java_pid4760.hprof ...
Heap dump file created [18735498 bytes in 0.018 secs]
Exception in thread "main" java.lang.OutOfMemoryError: Java heap space
	at HeapOomDemo.main(HeapOomDemo.java:10)
```

说"示意"是因为其中会变的部分：进程号（4760）、转储文件大小与耗时每次都不同。固定不变的是两行 `OutOfMemoryError: Java heap space` 和异常帧 `HeapOomDemo.main(HeapOomDemo.java:10)`，第 10 行正是 `keep.add(...)` 分配点。

逐步推导：每个 1MB 数组都被 `keep` 强引用着，从栈（`keep` 变量）到数组全程可达，可达性分析判它们全部存活，GC 一个都收不走。堆上限被 `-Xmx32m` 压到 32MB，塞满后新的分配请求无地可放，抛错退出。`-XX:+HeapDumpOnOutOfMemoryError` 在抛错前把堆快照写成了 `.hprof` 文件，文件里 `keep` 指向的十几个 1MB 数组就是"案发现场"，用 Eclipse MAT 之类的工具打开即可看到。

分清常见 OOM 类型，方向完全不同：

| 异常信息 | 发生区域 | 典型原因 |
|---------|---------|---------|
| `Java heap space` | 堆 | 泄漏或堆太小 |
| `GC overhead limit exceeded` | 堆 | 时间几乎全花在 GC 上却腾不出空间（HotSpot 有判定阈值） |
| `Metaspace` | 元空间 | 动态生成类失控（反射、CGLIB 滥用） |
| `unable to create new native thread` | 线程栈 | 线程数失控或触到系统限制 |
| `Direct buffer memory` | 堆外直接内存 | NIO 堆外内存超限 |

OOM 排查完整思路，按这个顺序走：

1. **定性**：读异常类型。`Java heap space` 查堆，`Metaspace` 查类加载，`unable to create new native thread` 查线程数。方向错了后面全白做。
2. **看分配点**：异常栈帧第一行指出分配发生在哪个类哪一行。直接指向业务代码，可能是一次性大任务；指向框架深处，更可能是泄漏早已把空间占满，这里只是最后一根稻草。
3. **取证**：最好在事前就配上 `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/持久盘路径`。事后来不及就手动抓：`jmap -dump:live,format=b,file=heap.hprof <pid>`，注意它会先触发一次 Full GC 并暂停应用，生产环境要挑时机。
4. **分析**：用 Eclipse MAT 打开 `.hprof`，看支配树找占内存最大的对象，沿引用链找到"谁持有着它"。
5. **归因**：二选一。泄漏（该死不死：静态集合只增不减、`ThreadLocal` 未清理、缓存无淘汰机制、监听器未注销）还是容量（堆确实太小、一次性任务太大）。泄漏改代码，容量调参数，反过来做都是白费。
6. **验证**：修复后复现同样场景，用 `jstat -gc` 观察 GC 后老年代用量是否回落到稳定水位，而不是"感觉快了"。

### 工具四件套：jps、jstat、jmap、jstack

四个 JDK 自带工具，各自回答一个问题，按"先找到进程，再看不同侧面"的顺序用：

- `jps`：本机现在跑着哪些 JVM 进程？回答"拿 pid"。类似 `ps`，但只列 Java 进程，且直接给 pid。
- `jstat`：某个进程的 GC 统计怎么样？回答"GC 干得累不累"。可以定时间隔连续观测。
- `jmap`：某个进程的堆里有什么？回答"内存被谁占了"，直方图或完整转储。
- `jstack`：某个进程的线程在干什么？回答"卡在哪"，死锁会直接标注。

先造一个长驻的小进程做观察对象（Git Bash 操作，编译在临时目录进行）：

```java
import java.util.ArrayList;
import java.util.List;

public class SleepDemo {

    public static void main(String[] args) throws InterruptedException {
        List<String> keep = new ArrayList<>();
        for (int i = 0; i < 100_000; i++) {
            keep.add("item-" + i); // 制造一批活下来的对象
        }
        byte[] blob = new byte[10 * 1024 * 1024]; // 一个 10MB 的大数组
        System.out.println("alive, list=" + keep.size() + ", blob=" + blob.length);
        while (true) {
            // 让进程一直活着，方便用工具观察
            Thread.sleep(10000);
        }
    }
}
```

```bash
javac --release 17 -encoding UTF-8 SleepDemo.java
java SleepDemo &
```

`jps -l`（`-l` 显示完整主类名）列出本机全部 JVM 进程。实跑输出（pid 每次不同；顺带能看到这台机器上正开着的 IDE 语言服务和一个 LinkForge 应用进程）：

```text
输出（示意）：
30352 c:\Users\1\.cursor\extensions\redhat.java-1.58.0-win32-x64\server\plugins\org.eclipse.equinox.launcher_1.8.0.v20260804-1928.jar
24436 c:\Users\1\.cursor\extensions\vmware.vscode-spring-boot-2.4.1-universal\language-server\spring-boot-language-server-2.4.1-SNAPSHOT-exec.jar
34756 jdk.jcmd/sun.tools.jps.Jps
18920 target/linkforge-2.0.0.jar
34972 SleepDemo
```

对拿到的 pid（这里是 34972）做 `jstat -gc`：

```bash
jstat -gc 34972
```

```text
输出（示意）：
    S0C         S1C         S0U         S1U          EC           EU           OC           OU          MC         MU       CCSC      CCSU     YGC     YGCT     FGC    FGCT     CGC    CGCT       GCT
        0.0         0.0         0.0         0.0      16384.0       8192.0     376832.0      12288.0      448.0      256.6     128.0      12.6      0     0.000     0     0.000     0     0.000     0.000
```

单位都是 KB（容量与用量）和秒（耗时）。读懂关键列就够：`EC`/`EU` 是 Eden 的容量与已用；`OC`/`OU` 是老年代；`MC`/`MU` 是元空间；`YGC`/`YGCT` 是新生代回收的次数与累计耗时；`FGC`/`FGCT` 是整堆回收（Full GC）的次数与累计耗时；`GCT` 是 GC 总耗时。这份数据有两条值得嚼的细节：`YGC` 是 0 但 `OU` 已有约 12MB，那个 10MB 大数组按 humongous 规则直接进了老年代 region（据 G1 的实现规则推断它就在其中）；`S0C`/`S1C` 是 0，印证了 G1 没有固定 Survivor 块。诊断套路：`jstat -gc <pid> 1000 5` 连看 5 秒，如果 `FGC` 在涨、`FGCT` 快速增加，说明整堆回收频繁，堆多半有问题。

`jmap -histo` 按类汇总堆里的对象（取前 12 行）：

```bash
jmap -histo 34972 | head -12
```

```text
输出（示意）：
 num     #instances         #bytes  class name (module)
-------------------------------------------------------
   1:        103687       13924320  [B (java.base@21.0.10)
   2:        103012        2472288  java.lang.String (java.base@21.0.10)
   3:            26        1659608  [Ljdk.internal.vm.FillerElement; (java.base@21.0.10)
   4:          1492        1369640  [Ljava.lang.Object; (java.base@21.0.10)
   5:          271         194304  [C (java.base@21.0.10)
   6:          723          90216  java.lang.Class (java.base@21.0.10)
   7:          812          45472  jdk.internal.org.objectweb.asm.SymbolTable$Entry (java.base@21.0.10)
   8:         1116          35712  java.util.concurrent.ConcurrentHashMap$Node (java.base@21.0.10)
   9:         1029          32928  java.util.HashMap$Node (java.base@21.0.10)
  10:          282          27184  [Ljava.util.HashMap$Node; (java.base@21.0.10)
```

`[B` 是 `byte[]`，`[C` 是 `char[]`。第一名 `byte[]` 约 13.9MB、约 10 万个实例，对上代码里的 10MB 大数组（据代码推断），第二名 `String` 的 10 万实例对上那 10 万条 "item-N"。这就是直方图的用法：异常庞大的类名就是下一步要追的线索。`jmap -histo:live` 和 `jmap -dump` 会先触发一次 Full GC，生产大堆上要挑业务低峰。

`jstack <pid>` 打印全部线程的调用栈；死锁场景它会直接输出 `Found one Java-level deadlock` 并指出互相等待的两个线程。CPU 飙高的排查链路用它收尾：Linux 上 `top -H -p <pid>` 找到最耗 CPU 的线程号，转成十六进制，去 `jstack` 输出里对 `nid=0x...` 找到对应栈，看是业务死循环还是名字带 GC 的线程在烧 CPU；想确认是 GC，用 `jstat -gcutil <pid> 1000` 看各区域占比和回收频率。Windows 本机没有 `top -H`，用任务管理器详细信息页或 Process Explorer 等价替代，见 [21-os.md](21-os.md) 的"Windows 读者注意"。

### 运行时可观测：LinkForge 怎么看见运行中的 JVM

工具要登机器才能用，持续运行的服务的另一种观测入口是把 JVM 指标暴露成 HTTP 端点。LinkForge 的做法（本项目业务逻辑与框架行为分开说）：

**框架行为**：Spring Boot Actuator 加 Micrometer 自动把应用进程的 JVM 指标做成可抓取的格式，不需要写任何代码，指标名以 `jvm_` 开头，比如 `jvm_memory_used_bytes`（各内存区已用量）、`jvm_gc_pause_seconds`（GC 停顿分布）。`application.yml` 里 `management.endpoints.web.exposure.include` 配了 `health,info,metrics,prometheus`，其中 `/actuator/prometheus` 就是抓取端点，并且用 `linkforge.metrics.scrape-token` 配置做了令牌门禁（`application.yml` 原注释：empty = fail-closed 401）。

**项目业务逻辑**：`src/main/java/com/linkforge/observability/BacklogGauges.java` 在同一个端点上挂了三个业务指标（outbox 积压、webhook 积压、webhook 失败数）。核心几行：

```java
private final AtomicLong outboxPending = new AtomicLong();

@PostConstruct
void register() {
    Gauge.builder("linkforge.outbox.pending", outboxPending, AtomicLong::get).register(meterRegistry);
}
```

一个 `@Scheduled` 定时任务每 15 秒（配置项默认值）跑一次 `COUNT(*)`，把结果 `set` 进 `AtomicLong`；Gauge 注册时挂的正是这个 `AtomicLong`，抓取端点被访问时只读现值。类注释写明了设计原因：不在抓取路径上跑 SQL，把数据库压力和 Prometheus 的抓取频率解耦。

和本章主题的连接（据代码推断）：这三个 `AtomicLong` 字段由一个单例 `@Component` 持有，进程活着它们就一直从根可达，所以是标准的"常驻老年代"对象；Gauge 持有它们的强引用，它们永远不会被回收。这正是前面可达性分析的活例子：不是所有常驻对象都是泄漏，合理设计的长命对象本来就该活着。

`src/main/java/com/linkforge/observability/HttpUriTagAllowList.java` 则防的是另一种堆上慢性增长。Micrometer 会给每个 HTTP 指标打上 `uri` 标签，标签的每个新取值都会生成一套新的指标对象。如果不设限，任何请求路径（包括真实的短码路径）都会成为新标签值，指标对象只增不减，在堆上越积越多，这就是指标基数失控导致的内存泄漏。该类用一个 `MeterFilter` 把白名单之外的 `uri` 统一折叠成 `UNKNOWN`，把取值集合钉死。类注释的原话："New endpoints must be added here or their metrics silently merge into UNKNOWN, which is the safe direction."（新端点不进白名单，指标静默并入 UNKNOWN，这是安全的失败方向。）逻辑推论：指标基数不受控和静态集合只增不减是同一类问题，都是"只进不出的强引用链"。

## 常见错误与误区

- **"Java 不会内存泄漏"**：可达性分析只负责"不可达就收"。静态集合、`ThreadLocal` 未清理、无淘汰机制的缓存、未注销的监听器，都会造成"可达但没用"的堆积，堆照样满，照样 `OutOfMemoryError`。
- **"引用置 null 是回收开关"**：局部变量随栈帧弹出自动断引用，手动置 null 几乎总是多余。有意义的动作是"把对象从静态集合里 remove"，因为那断开了从 GC Root（静态字段）出发的路径。
- **"`-Xmx` 越大越好"**：堆越大，单次整堆回收要扫描的对象越多，停顿更长；容器里还会挤占其他进程的内存配额。按真实容量需求给，先测量后调参。
- **把 `StackOverflowError` 当 `OutOfMemoryError` 处理**：前者查递归和 `-Xss`，后者查堆。两者都不是 Exception 层的普通异常，捕获它们通常没有意义。
- **"所有对象都在堆上，HotSpot 还有栈上分配"**：前半句是 HotSpot 实现的常态。后半句是流传很广的误传——HotSpot 的逃逸分析对不逃逸的对象做的是标量替换（把字段拆散成局部变量，对象根本不创建），并没有真正把对象放到栈上的机制。这是 JIT 的尽力而为优化，不是规范保证。
- **背 `-XX:PretenureSizeThreshold` 当通用知识**：它只在串行/ParNew 收集器下生效；G1 下大对象走 humongous region。说参数前先说清收集器。

## 自测

1. 哪些运行时数据区线程共享、哪些线程私有？为什么程序计数器必须私有？
   答：堆与方法区共享；虚拟机栈、本地方法栈、程序计数器每线程一份。线程被切下 CPU 后，程序计数器记着它执行到哪条字节码，切回来才能从正确位置继续，所以只能每线程一份。
2. `-Xms`、`-Xmx`、`-Xss` 分别管什么？把 `-Xmx` 调到 8g，其他内存区域跟着变大吗？
   答：`-Xms` 是堆初始大小，`-Xmx` 是堆最大大小，`-Xss` 是每根线程的栈大小。不跟着变。堆只是内存地图的一块，元空间用本地内存、线程栈由 `-Xss` 决定，它们各有各的账。
3. 为什么"把引用置 null"通常没有意义，"从静态集合里 remove"才有意义？
   答：局部变量随栈帧弹出自动消失；而静态字段是 GC Root，集合里的元素经静态字段永远可达。remove 真正断开的是"从根出发的可达路径"，这才是可达性分析判定回收的依据。
4. `StackOverflowError` 和 `OutOfMemoryError: Java heap space` 的区别？各举一个触发场景。
   答：前者是线程栈深度超限，如无出口递归；后者是堆放不下新对象，如只用不删的静态集合把对象攒到堆满。区域不同、排查方向不同。
5. `jps`、`jstat`、`jmap`、`jstack` 分别回答什么问题？OOM 排查第一步先用哪个？
   答：`jps` 回答"有哪些 JVM 进程"，`jstat` 回答"GC 干得累不累"，`jmap` 回答"堆里什么对象最大"，`jstack` 回答"线程卡在哪"。OOM 排查先用 `jps` 拿到 pid，再用 `jmap` 取证；事前的 `-XX:+HeapDumpOnOutOfMemoryError` 能省掉这一步。
6. G1 名字里的 "Garbage First" 是什么意思？
   答：G1 把堆切成 region，每次回收优先挑垃圾占比最高、腾出空间最多的 region，用有限停顿换最大回收收益。region 上的分代角色只是逻辑标签，这是它与老式收集器布局的根本差别。

## 面试追问

- **对象什么时候进老年代？** 答：年龄达到阈值（HotSpot 默认 15，实现参数）、大对象直接进入（G1 下 humongous 对象）、动态年龄判断（Survivor 里同龄对象超过一半就整体提前晋升，HotSpot 实现细节）。追问"为什么这么设计"要回到弱分代假设：朝生夕死的对象留在高频回收的新生代，复制活对象的代价才便宜。
- **CPU 100% 怎么排查？** 答：`top -H -p <pid>` 找最耗 CPU 的线程号，转十六进制，去 `jstack` 输出对 `nid=0x...` 找到栈；栈是业务代码则是死循环，线程名带 GC 则用 `jstat -gcutil` 确认 GC 频率。Windows 上线程级观测用 Process Explorer 等价替代（[21-os.md](21-os.md)）。
- **四种引用怎么用一句话区分？`ThreadLocal` 为什么会泄漏？** 答：强引用宁 OOM 不回收，软引用内存不足才清，弱引用下次 GC 必清，虚引用只收回收通知。`ThreadLocalMap` 的 key 是弱引用（会被清），value 是强引用（残留），线程池线程不死 value 就一直挂着，用完必须 `remove()`，细节见 [13-concurrency.md](13-concurrency.md)。

## 下一步

- 引用的生命周期与 `ThreadLocal` 泄漏的完整机制见 [13-concurrency.md](13-concurrency.md)。
- 时区 fork 参数如何复现真实故障见 [82-linkforge-decisions.md](82-linkforge-decisions.md) 的 D9 节，fork 的成本账见 [60-testing-ci.md](60-testing-ci.md)。
- 容器化后的内存限额与 JVM 参数配合见 [61-docker-deploy.md](61-docker-deploy.md)；Linux/Windows 命令对照见 [21-os.md](21-os.md)。
- 慢的常见元凶是 SQL 而非 GC，先用 EXPLAIN 排除（[40-mysql.md](40-mysql.md)）。
