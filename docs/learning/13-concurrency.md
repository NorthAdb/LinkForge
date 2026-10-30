# 并发与多线程

优先级 P0，目标层级 L3（能分析竞态、选对工具、解释线程池参数与拒绝策略）。

一个 LinkForge 的真实场景：某个短链突然被社交平台引爆，同一秒有几百个 HTTP 请求带着同一个短码打进来。Spring 会为每个请求分配一个线程，几百个线程同时跑你的 Java 代码。它们读同一行数据库、写同一份缓存、更新同一个计数器。**多个执行流同时访问同一份数据**，这就是并发。这一章讲三件事：并发为什么出错、语言给了哪些工具、LinkForge 的代码怎么用这些工具。

## 学习目标

- 说清线程和进程的区别，会创建线程，知道为什么日常改用线程池。
- 面对一段操作共享变量的代码，能回答"线程安全吗"，指出它丢在哪一步、为什么丢。
- 分清 `synchronized` 和 `volatile` 各自保证什么、不保证什么，不再混用。
- 会手写 `ThreadPoolExecutor` 的七个参数，能逐个解释拒绝策略的后果。
- 能逐行读懂 `SingleFlight`，解释它怎么把 N 次数据库读合并成 1 次。
- 能说清这条分界线：进程内的并发靠 JVM 机制，跨进程的并发靠 Redis/Lua。

## 前置知识

- [java/10-02-oop.md](java/10-02-oop.md)：对象与类。`synchronized` 锁的就是对象，本篇会反复回到"对象"上。
- [java/10-08-lambda-stream-optional.md](java/10-08-lambda-stream-optional.md)：lambda 与函数式接口。`Runnable`、`Supplier` 都靠它们落地。
- [11-java-collections.md](11-java-collections.md)：`ConcurrentHashMap` 的桶级锁与原子方法。
- [12-jvm.md](12-jvm.md)：虚拟机栈是线程私有的，堆是所有线程共享的。本篇所有问题都出在"共享"两个字上。

## 线程与进程：共享堆的代价

先区分两个词。**进程**是操作系统分配资源的单位。你双击打开一个 `.exe`，操作系统就启动一个进程，给它划一块独立的内存、一批文件句柄。进程 A 的内存进程 B 读不到，这是操作系统强制的隔离。

**线程**是进程内部的执行单元。一个进程至少有一个线程（执行 `main` 的那个），可以再开更多。同一个进程的所有线程共享进程的内存，尤其是堆：任何一个线程 `new` 出来的对象，其他线程立刻能拿到引用去读。

| | 进程 | 线程 |
|---|------|------|
| 内存 | 独立，互不可见 | 共享所在进程的堆 |
| 创建开销 | 大（要复制整套资源） | 小（复用进程资源） |
| 通信 | 管道、网络、共享文件等额外机制 | 直接读写同一个对象 |

对比第 3 行：线程之间通信"免费"，因为堆是共享的。但免费的东西有账单，账单就是本章的主题。

[12-jvm.md](12-jvm.md) 讲过：虚拟机栈和程序计数器是每线程一份，堆是共享的。每个方法调用的局部变量在栈上，只有当前线程碰得到；对象的字段在堆里，所有线程碰得到。**结论先放在这里：局部变量天然安全，对象的字段才需要并发控制。**

## 创建与启动线程：Thread、Runnable、Callable

### 最小示例：start() 才会开新线程

把一段代码交给新线程，最直接的方式是 new 一个 `Thread` 再调 `start()`。

```java
public class HelloThread {
    public static void main(String[] args) {
        Thread worker = new Thread(
                () -> System.out.println("hello from " + Thread.currentThread().getName()));
        worker.start();
        System.out.println("hello from " + Thread.currentThread().getName());
    }
}
```

```text
输出（示意）：
hello from main
hello from Thread-0
```

`Thread.currentThread()` 返回当前正在执行这段代码的线程。`getName()` 返回线程名，`main` 线程的名字就叫 `main`，新线程默认叫 `Thread-0`、`Thread-1`。

逐行推导：`main` 线程执行到 `worker.start()`，向操作系统申请一个新线程，然后**不等它**，继续往下打印自己的那行。新线程被调度到 CPU 后执行 lambda 里的代码，打印 `Thread-0`。两行谁先谁后取决于操作系统调度，所以这个输出是示意。本机连跑四次都是 `main` 在前，但这不是语言规范的保证，不能依赖。

第一反例：把 `worker.start()` 写成 `worker.run()`。程序照样打印两行，但 `run()` 是普通方法调用，lambda 在 `main` 线程里执行，输出永远只有 `hello from main`，没有任何并发。初学者这么写是因为直觉上"调用 run 方法执行任务"没错，漏掉的是：开新线程这个动作在 `start()` 里，`start()` 内部才去调 `run()`。

### Runnable 与 Callable：两种任务

`Thread` 只是执行载体，任务本身用两种接口描述（lambda 对应它们的函数式接口）：

```java
Runnable task = () -> System.out.println("no result");   // 无返回值，不能抛受检异常
Callable<Long> job = () -> {                              // 有返回值，可以抛异常
    Thread.sleep(10);
    return 42L;
};
```

（片段：两个接口的 lambda 写法，不能独立运行。）

- `Runnable`：`void run()`。干完就完，不产出结果。
- `Callable<V>`：`V call()`。产出一个 `V` 类型的结果。取结果要配 `Future`：把任务提交给线程池，池子立刻返回一个 `Future<V>` 对象，它代表"将来才会有值"，调 `get()` 时如果还没算完就阻塞等待。

### 日常不用裸线程

`new Thread()` 每次都现场找操作系统要线程，用完就扔。两个成本：创建和销毁要经历系统调用，慢；线程数量没有上限，每个线程要预留一块栈内存（量级 1MB，`-Xss` 可调），请求一多内存先崩。

日常做法是**线程池**：启动时先创建固定数量的线程放着，任务来了从池里领一个线程去干，干完线程归还，继续接下一个任务。线程复用、数量可控。LinkForge 里的所有异步工作都走线程池，没有一处裸 `new Thread()`（据全项目 `grep` 核实）。线程池怎么配，下面"线程池"一节专门讲。

## 为什么并发会出错：拆开 i++

### 竞态条件：读、改、写之间会插队

两个线程同时对同一个计数器各加 10 万次，结果是多少？先跑再看。

```java
public class RaceDemo {
    static int count = 0;

    public static void main(String[] args) throws InterruptedException {
        Runnable work = () -> {
            for (int i = 0; i < 100_000; i++) {
                count++;
            }
        };
        Thread a = new Thread(work);
        Thread b = new Thread(work);
        a.start();
        b.start();
        a.join();
        b.join();
        System.out.println("count = " + count);
    }
}
```

```text
输出（示意）：
count = 128200
```

你的结果几乎一定和上面不同，而且每次运行都不同。本机跑 6 次：`200000`、`128200`、`159325`、`122169`、`115706`、`107726`。多数明显小于 20 万。这个"不确定"不是 bug，它本身就是并发要学的第一课。

逐步推导。`join()` 的意思是"当前线程（这里是 `main`）阻塞，直到目标线程执行完"。所以打印时两个加法线程都已结束，不存在"还没加完就读了"。少的次数丢在了**执行过程中**。

关键事实：Java 语言里，`count++` 看着是一句，执行时是三步。它等价于：

```java
int temp = count;   // 第 1 步：读，把字段值拷一份
temp = temp + 1;    // 第 2 步：加
count = temp;       // 第 3 步：写回
```

（片段：`count++` 的语义分解。）

两个线程在这三步之间任意交错。看一个丢失更新（术语叫**竞态条件**：结果取决于线程执行的相对时序）的具体交错：

```text
时刻    线程 A                 线程 B                 count
t1     读 count，得 100                              100
t2                            读 count，得 100        100
t3     加 1，写回 101                                 101
t4                            加 1，写回 101          101
```

两次加法，只涨了 1。线程 B 在 t2 读到的是 t1 之后的旧值，它不知道 t3 即将发生。每一次这样的交错丢一次更新，10 万次循环里交错随时发生，最终就少了。第一次跑出 `200000` 也讲得通：那一轮两个线程恰好先后串行执行（或全程没在关键点交错），交错是概率事件，丢失也是概率事件。

### 可见性：另一个线程可能长期看不见你的写

丢失更新还有一个更隐蔽的来源。你可能在别处见过这样的解释："每个线程有自己的 CPU 缓存，写不马上刷出去"。这个说法方向对，细节错在把规范说成了物理结构。Java 用的是 **JMM**（Java Memory Model，Java 内存模型），它不是描述物理内存的图纸，而是一份**契约**，约束"一个线程的写，什么时候保证对另一个线程可见"。

最小模型三句话：

1. 所有共享变量在**主内存**里（对应 [12-jvm.md](12-jvm.md) 的堆）。
2. 每个线程操作变量时，用的是**工作内存**里的副本。工作内存是 CPU 缓存、写缓冲、寄存器这些真实硬件的抽象，不是"每个线程真有一块内存"。
3. 不加任何同步手段时，副本什么时候写回主内存、什么时候重新读主内存，规范不保证。编译器和 CPU 为了性能还可以调整指令顺序（术语叫重排序）。

所以两件事会出问题：三步之间被插队（**原子性**丢了），写完了别人看不见（**可见性**丢了）。加上重排序（**有序性**丢了），这就是常说的并发三属性。它们不是三份独立的灾祸，是同一份"性能自由"在三个方向上的表现。

### happens-before：一句话级的判断工具

怎么判断"我的写对方看得见"？JMM 给了一个工具：**happens-before**。一句话：

> 如果操作 A happens-before 操作 B，那么 A 对共享变量的写，B 一定看得见，且 A 之前发生的写不会对 B 露出一半。

不需要背完整规则表，先记五条常用规则：

1. 单线程内，前面的操作 happens-before 后面的操作（程序顺序规则）。
2. 对同一把锁，解锁 happens-before 后一次加锁。
3. 对同一个 volatile 变量，写 happens-before 后一次读。
4. `start()` 调用前的代码 happens-before 新线程里的所有代码；子线程的所有操作 happens-before `join()` 返回。
5. 传递性：A before B、B before C，则 A before C。

`RaceDemo` 为什么丢数据？`count++` 不带任何同步手段，五条规则一条都套不上，两个线程的读写之间没有任何 happens-before 边，规范层面就允许看到旧值。下面两节，就是往代码里放进 happens-before 边的两件武器。

## synchronized：锁的是对象

### 修复计数器

问题：怎么把"读-加-写"三步焊成不可分割的一步？答案：让同一时刻只有一个线程能进这段代码。

```java
public class SyncDemo {
    static int count = 0;
    static final Object lock = new Object();

    public static void main(String[] args) throws InterruptedException {
        Runnable work = () -> {
            for (int i = 0; i < 100_000; i++) {
                synchronized (lock) {
                    count++;
                }
            }
        };
        Thread a = new Thread(work);
        Thread b = new Thread(work);
        a.start();
        b.start();
        a.join();
        b.join();
        System.out.println("count = " + count);
    }
}
```

```text
输出（示意）：
count = 200000
```

这个结果是确定的，每次运行都是 200000。

`synchronized (lock) { ... }` 的语义：进入大括号前，必须先获得 `lock` 这个对象的锁；同一时刻只有一个线程能持有它。线程 B 想进的时候锁被 A 拿着，B 就**阻塞**（挂起，不占 CPU）等 A 出来。A 出来释放锁，B 才进。三步从此不会被拆开。锁内代码包围的范围术语叫**临界区**。

`synchronized` 的完整保证，语言规范层面就两条：

1. **互斥**：同一时刻，一个对象的锁只有一个持有者。
2. **可见性**：就是 happens-before 规则 2。解锁前的所有写，对下一个拿到这把锁的线程可见。所以进临界区时读到的共享变量一定是最新值，不存在"锁内还看旧缓存"的问题。

另外它是**可重入**的：线程已经持有某对象的锁，可以再次进入同一个对象的其他 `synchronized` 块，不会把自己锁死。这个性质让"synchronized 方法内部再调另一个 synchronized 方法"合法。

### 锁对象怎么选：反例

`synchronized` 锁的从来不是代码，是**对象**。`synchronized (lock)` 锁 `lock` 指向的那个对象；`synchronized` 修饰实例方法，锁的是 `this`；修饰 static 方法，锁的是这个类的 Class 对象。两个线程要互斥，前提是**拿到同一个对象**。看反例：

```java
public class WrongLock {
    static int count = 0;

    void add() {
        synchronized (new Object()) {   // 每次进来都换一把新锁
            count++;
        }
    }
}
```

（片段：错误示范，不能独立运行。）

每次调用 `new Object()`，两个线程拿的是两个不同对象的锁，互斥完全不成立，`count++` 照样丢更新。编译不报错，运行不抛异常，数据悄悄错。初学者这么写，是因为把 `synchronized` 关键字本身当成了锁，没意识到锁是那个括号里的对象。改法：用一个所有线程都够得着的固定对象（上例的 `static final Object lock`），或者直接锁包含这份状态的对象本身。

回连 [java/10-02-oop.md](java/10-02-oop.md)：对象在堆里，是"字段 + 方法 + 身份"的聚合体。这里补上它天生的第三样东西：每个 Java 对象都关联一把监视器锁（术语叫 monitor，监视器），不管你用不用。这就是"锁的是对象"的完整含义。

### 规范保证与 HotSpot 实现，别混着说

面试常问"偏向锁、轻量级锁、锁升级"，注意分类：

- **语言规范保证的**：互斥、可见性（解锁先于加锁）、可重入。任何合法的 JVM 都必须做到这三条，换个 JVM 实现也一样。
- **HotSpot 的实现手段**：锁状态记录在对象头里（[12-jvm.md](12-jvm.md) 讲过对象头的概念，实现细节）。无竞争时走 CAS（一种 CPU 级的比较并交换指令，后面线程池一节还会遇到）的轻量路径，竞争加剧才膨胀为操作系统级的互斥量。"偏向锁 → 轻量级锁 → 重量级锁"的升级路径是 HotSpot 的性能策略，不是 Java 规范的内容。偏向锁在 JDK 15 起被禁用并标记废弃（JEP 374）。

回答面试时先给规范保证，再补一句"锁升级是 HotSpot 实现"，这个分层本身就是加分项。日常写代码，把 `synchronized` 当"互斥加可见性"用即可，够快也够安全。

## volatile：保证看得见，不保证改得完

### volatile 救不了 count++

问题：有一类共享数据是"一个线程写、其他线程读"，比如配置快照、开关标志。为它加锁有点重。`volatile` 就是给这种场景的轻量工具。它保证两件事：

1. **可见性**：写一个 volatile 变量后，其他线程随后读它，一定看到最新值（happens-before 规则 3）。
2. **禁止重排序**：编译器和 CPU 不能把 volatile 写之后的操作挪到它前面，也不能把它前面的写挪到后面。配合规则 3 和传递性，可以做到：写线程先准备好一批数据，最后写 volatile 标志；读线程一看到标志变了，前面那批数据必定全部可见。

它**不保证原子性**。不信就把 `RaceDemo` 的字段加上 `volatile` 再跑。

```java
public class VolatileDemo {
    static volatile int count = 0;

    public static void main(String[] args) throws InterruptedException {
        Runnable work = () -> {
            for (int i = 0; i < 100_000; i++) {
                count++;
            }
        };
        Thread a = new Thread(work);
        Thread b = new Thread(work);
        a.start();
        b.start();
        a.join();
        b.join();
        System.out.println("count = " + count);
    }
}
```

```text
输出（示意）：
count = 138159
```

还是不到 20 万。推导：volatile 让每一次读和写都"看得见"（读必读最新值，写必立即对别人可见），但 `count++` 仍然是三步，`t1 读 100 → t3 写 101` 之间的空隙还在，线程 B 的读照样插进这个空隙，照样丢更新。volatile 把"看不见"这个丢法堵上了，堵不上"插队"这个丢法。

结论拿去就能用：**读-改-写要用锁（或原子类），volatile 只配"单写多读、一次一步"的场景。** LinkForge 里就有标准用法。`src/main/java/com/linkforge/link/support/PhishingPolicy.java` 第 34 到 35 行：

```java
private volatile TargetPolicy.Config snapshot = TargetPolicy.Config.EMPTY;
private volatile LocalDateTime fileMtime;
```

（片段：项目源码，`src/main/java/com/linkforge/link/support/PhishingPolicy.java`。）

`TargetPolicy.Config` 是不可变对象（[11-java-collections.md](11-java-collections.md) 讲过它的 `List.of` 定格写法）。重载线程配置变更时**整只替换** snapshot 引用，跳转线程每次读到的是完整的旧版或完整的新版，永远不会看到半新半旧。这就是"volatile 引用 + 不可变对象"的安全热更新套路。

### 顺手认识原子类

"读-改-写"有时就一步加法，为它上锁显得重。`java.util.concurrent.atomic` 包的 `AtomicLong` 等类把常用的读-改-写封装成一个原子方法：`new AtomicLong().incrementAndGet()` 内部用 CAS 循环（读旧值、算新值、CAS 替换、失败重来）实现免锁累加。它和 `synchronized` 都能救 `count++`，选哪个：竞争不激烈时原子类通常更快，逻辑复杂（要同时改几个变量）时锁更直白。本章项目案例里原子类出场不多，读者知道有这个工具即可。

## 线程池：七个参数与拒绝策略

### 七个参数，每个管一件事

`ThreadPoolExecutor` 的构造器：

```java
ThreadPoolExecutor pool = new ThreadPoolExecutor(
        corePoolSize,      // 核心线程数：常驻员工的编制数
        maximumPoolSize,   // 最大线程数：忙不过来时最多能扩到多少人
        keepAliveTime,     // 非核心线程闲置多久后裁掉
        unit,              // keepAliveTime 的时间单位
        workQueue,         // 任务队列：核心线程忙不过来时，任务先在这里排队
        threadFactory,     // 线程工厂：造线程用的，主要用来起名字
        handler);          // 拒绝策略：连队列都满了、线程也到顶了，新任务怎么办
```

（片段：七个参数的位置与职责。）

### 提交一个任务时发生了什么

用 `pool.execute(task)` 提交任务，池子按固定顺序做四步判断：

1. 线程数还没到 corePoolSize，直接建一个新线程执行任务。
2. 核心线程满了，任务进队列排队。
3. 队列也满了，且线程数还没到 maximumPoolSize，再建新线程（非核心线程）。
4. 队列满、线程也到顶，执行拒绝策略。

注意第 2 步和第 3 步的顺序：**先排队，队伍排满才扩线程**。很多人觉得反直觉（"人手不够了不该先加人吗"），但它对突发流量的行为是：先让突发在队列里缓冲，而不是瞬间暴涨一堆线程。要"快速扩线程"的池子（如 `newCachedThreadPool`）会把队列换成不存任务的 `SynchronousQueue`，逼着池子跳过排队直接建线程。

跑一个能看清全过程的例子。编制 1 人、最多 2 人、队伍 2 格，提交 8 个慢任务：

```java
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class PoolDemo {
    public static void main(String[] args) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 2, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2),
                new ThreadPoolExecutor.AbortPolicy());
        for (int i = 1; i <= 8; i++) {
            final int id = i;
            try {
                pool.execute(() -> {
                    System.out.println("task " + id + " runs on "
                            + Thread.currentThread().getName());
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                System.out.println("task " + id + " accepted");
            } catch (RejectedExecutionException ex) {
                System.out.println("task " + id + " REJECTED");
            }
        }
        pool.shutdown();
    }
}
```

```text
输出（示意）：
task 1 accepted
task 2 accepted
task 3 accepted
task 4 accepted
task 5 REJECTED
task 6 REJECTED
task 7 REJECTED
task 8 REJECTED
task 4 runs on pool-1-thread-2
task 1 runs on pool-1-thread-1
task 2 runs on pool-1-thread-1
task 3 runs on pool-1-thread-2
```

前 8 行是主线程提交时的结果，后 4 行是两个工作线程陆续领任务打印的。逐个任务推导：

- task 1：线程数 0 < core 1，建 `pool-1-thread-1` 执行它。
- task 2、3：核心满，进队列（占 2 格）。
- task 4：队列满，线程数 1 < max 2，建 `pool-1-thread-2` 执行它。这一步就是"队伍满才扩人"的现场。
- task 5 到 8：队列满、线程到顶，默认拒绝策略 `AbortPolicy` 抛 `RejectedExecutionException`，被 `catch` 住打印 `REJECTED`。

后半段的任务执行顺序每次运行都不同（`pool-1-thread-2` 常常先打印，因为它创建得晚、启动要时间，却领的是先到的 task 4），这正是多线程调度不确定的又一例。`shutdown()` 让池子不再收新任务，已收的任务跑完后线程退出。

### 四种拒绝策略

| 策略 | 行为 |
|------|------|
| `AbortPolicy`（默认） | 抛 `RejectedExecutionException`，提交方自己处理 |
| `CallerRunsPolicy` | 谁提交谁自己执行，提交方被拖慢，形成天然反压 |
| `DiscardPolicy` | 静默丢掉新任务，无任何痕迹 |
| `DiscardOldestPolicy` | 丢掉队列里最老的任务，把位置让给新任务 |

`DiscardPolicy` 和 `DiscardOldestPolicy` 默默吞任务，生产环境用了等于埋雷。要吞吐平滑选 `CallerRunsPolicy`，要快速失败、能被监控捕获选默认的 `AbortPolicy`，总之丢弃必须留痕（记日志、打指标）。

### 为什么禁用 Executors 的两个工厂方法

`Executors` 是 JDK 提供的快捷工厂。初学者爱用它，因为参数太多、工厂一行搞定。省掉的恰恰是两个最要命的决定：队列多满算满、满了怎么办。

- `Executors.newFixedThreadPool(n)`：`maximumPoolSize` 就是 `n`，队列是**无界**的 `LinkedBlockingQueue`（默认容量 `Integer.MAX_VALUE`）。任务只会堆积、永远不会触发拒绝，上游失速时队列吃光堆内存，抛 `OutOfMemoryError`。原文档说它"OOM 有点无声"，准确讲是无声在**错误出现的位置离原因很远**：内存崩在队列堆积处，日志里看到的是 OOM，看不到是哪个池子堆的。
- `Executors.newCachedThreadPool(n)`：`maximumPoolSize` 是 `Integer.MAX_VALUE`，队列是不存任务的 `SynchronousQueue`。每个新任务都能立刻领到新线程，请求洪峰下线程数没有上限，内存和上下文切换（CPU 在线程间来回切换时保存恢复现场的开销）双双崩盘。

正确姿势就是 `PoolDemo` 那样手写：有界队列（明确"能排多少"）、显式拒绝策略（明确"满了怎么办"）、自定义 `threadFactory` 给线程起可辨认的名字。

一个必须澄清的边界：`Executors` 工厂不是一律有毒，毒在**无界增长遇上外部输入**。LinkForge 的 `ShortLinkService` 就用了 `Executors.newSingleThreadScheduledExecutor`（项目案例二细讲），它的任务队列同样无界，但任务由代码自己按秒投放，速率可控，不接外部请求洪峰。判断标准是"谁往队列里灌东西、速率谁定"。

### 参数怎么定

corePoolSize 不是公式题。CPU 密集型任务（纯计算）约等于核数，多了只会增加切换；IO 密集型任务（等数据库、等网络）可以高一些，因为线程大部分时间在等而不是占着 CPU。但真正可靠的答案是压测：队列长度、拒绝动作、监控埋点，比线程数本身更容易被忽视，也更容易出事。

## ThreadLocal：线程的私人储物柜

### 原理：Thread 对象里藏着一张表

问题：请求处理代码需要"当前登录用户"。这个信息在过滤器里解析，却在 Service、Mapper 的深处要用。一层层把 `user` 当参数传下去？方法签名全被污染。

`ThreadLocal` 的思路：数据挂在**线程**身上。每个 `Thread` 对象内部有一张表（类型叫 `ThreadLocalMap`，可以当成"键值对字典"理解），`ThreadLocal` 对象自己是键，你存的值是值。调 `threadLocal.get()`，本质是"拿当前线程，查它自己那张表里这个键对应的值"。每个线程查自己的表，互不干扰，也没有并发问题，因为根本不共享。

比喻与边界：像给每个线程发一个带编号的私人储物柜，存取只动自己的柜子。比喻失效处：柜子随线程生灭。**线程池的线程不退出、反复复用**，柜子里的东西也就一直留着，下一个任务领到同一个线程，会读到上一个任务留下的值。这是下一节泄漏问题的根源。

### 弱引用与泄漏：为什么必须 remove

`ThreadLocalMap` 的 Entry 有个特殊设计：键（`ThreadLocal` 对象）是**弱引用**（[12-jvm.md](12-jvm.md) 讲过：弱引用持有的对象，下次垃圾回收必被清掉），值是普通强引用。后果链：

1. 外部代码不再持有某个 `ThreadLocal` 的强引用，下次 GC 把它清掉。
2. Entry 变成"键为 null、值还挂着"的过期条目。Map 在后续操作探测到过期条目时会顺手清理一部分，但不保证及时、更不保证清完。
3. 线程池的线程不死，Map 就一直活着，值就一直挂着。这就是泄漏。

比内存泄漏更常咬人的是**脏数据**：线程 T 处理完用户甲的请求，`ThreadLocal` 里留着甲的身份；下一个请求复用线程 T，代码直接 `get()`，读到的是甲的身份，却当成乙的用。生产事故级别的问题，成因只是一行忘掉的清理。

纪律只有一条：**用完必须 `remove()`，放 finally 里**。

### 项目用法：UserContext

LinkForge 的用户上下文就是标准实现。`src/main/java/com/linkforge/security/UserContext.java` 全文 21 行：

```java
public final class UserContext {

    private static final ThreadLocal<AuthUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(AuthUser user) {
        HOLDER.set(user);
    }

    public static AuthUser get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
```

三个方法对应三步：`set` 存、`get` 取、`clear` 清。静态字段 `HOLDER` 是全进程唯一的键，每个线程各自的表里各有自己的值。谁负责 set 和 clear？JWT 过滤器（校验请求头里登录令牌的那一层），`src/main/java/com/linkforge/security/JwtAuthFilter.java`，第 34 到 57 行的结构是：

```java
try {
    // 解析 Authorization 头里的 JWT，取出用户
    UserContext.set(authUser);          // 第 45 行
    filterChain.doFilter(request, response);   // 后续所有处理都能 UserContext.get()
} catch (JwtException ex) {
    // 返回 401
} finally {
    UserContext.clear();                // 第 56 行：请求结束，必清
}
```

（片段：摘录自 `src/main/java/com/linkforge/security/JwtAuthFilter.java`，省略了解析细节。）

一个请求一个线程，入口 set、finally clear，值随请求生灭，线程复用也不会串。Service 层（如 `ShortLinkService` 第 577 行的 `UserContext.get()`）不需要任何传参就拿到用户。这套"过滤器 set + finally clear"是 Web 项目用 `ThreadLocal` 的标准姿势。

## ConcurrentHashMap：单方法原子，复合操作要自己负责

[11-java-collections.md](11-java-collections.md) 讲过它的并发底盘：JDK 8 起用 CAS 占空桶、`synchronized` 锁非空桶头节点，锁粒度是桶，`get` 无锁。本篇补一个更容易踩的坑：**方法的原子性不等于操作序列的原子性**。

反例，check-then-act（先检查再行动）：

```java
// 反例：两步之间会被插队
if (!map.containsKey(k)) {       // 第 1 步：查
    map.put(k, v);               // 第 2 步：放
}
```

（片段：错误示范。）

两个线程同时执行：都通过第 1 步（都看到"没有"），都执行第 2 步，后放的覆盖先放的。单看 `containsKey` 和 `put` 各自都原子，但两步**之间**没有原子性， happens-before 边接不上。

改法是用 ConcurrentHashMap 提供的**复合原子方法**，把两步焊成一步：

- `map.putIfAbsent(k, v)`：没有才放，有就返回现有值。判断和放入一步完成。
- `map.computeIfAbsent(k, key -> buildValue(key))`：没有就调用函数算一个放进去，有就直接返回。
- `map.compute(k, (key, old) -> ...)`：读旧值算新值一步完成，取代"get 再 put"。

`computeIfAbsent` 有个必须知道的坑：按 JDK 8 起 ConcurrentHashMap 的实现（JDK 17 沿用），你传的函数在**持有该桶锁的状态下执行**。函数是纯内存计算（纳秒级）没问题；函数里放一个几十毫秒的同步数据库读，就等于锁着整个桶去读库，同桶的其他无关 key 的读写全部被卡住。LinkForge 的 `SingleFlight` 正是为此放弃了 `computeIfAbsent`，改用 `putIfAbsent` + 锁外执行，案例一逐行讲。

## CompletableFuture：把"等结果"变成编排

### 链式转换与合并

`Future.get()` 只会干等，拿到结果后想做进一步加工，只能阻塞着等完再写后续代码。`CompletableFuture` 把"结果将来才有"升级成可以编排的对象：任务完成时自动执行你挂上去的后续步骤。

最小上手四件套：

- `CompletableFuture.supplyAsync(supplier)`：把任务交给默认线程池异步执行，立刻返回一个 future。
- `complete(value)` / `completeExceptionally(ex)`：手工把结果（或异常）装进 future。这是理解 `SingleFlight` 的关键：它不用 `supplyAsync`，而是自己造空盒子、由执行者手工装填。
- `thenApply(fn)`：结果就绪后对它做转换，返回新的 future，像 Stream 的 `map`。
- `thenCombine(other, fn)`：两个 future 都就绪后合并结果。

```java
import java.util.concurrent.CompletableFuture;

public class CfDemo {
    public static void main(String[] args) throws Exception {
        CompletableFuture<String> base = CompletableFuture.supplyAsync(() -> "hello");
        CompletableFuture<String> chained = base
                .thenApply(s -> s + " world")
                .thenApply(s -> "[" + s + "]");
        System.out.println(chained.get());

        CompletableFuture<String> a = CompletableFuture.supplyAsync(() -> "12");
        CompletableFuture<String> b = CompletableFuture.supplyAsync(() -> "34");
        System.out.println(a.thenCombine(b, (x, y) -> x + y).get());
    }
}
```

```text
输出：
[hello world]
1234
```

推导：`supplyAsync` 在另一个线程里执行 `() -> "hello"`，`main` 线程接着挂上两个 `thenApply`。`chained.get()` 阻塞到整条链完成，得到 `"hello"` 经两步加工后的 `"[hello world]"`。`thenCombine` 等 a、b 各自的 `"12"`、`"34"` 就绪后拼接出 `"1234"`。输出的确定性由 `get()` 保证：`get()` 不返回，`println` 不执行。

两条使用纪律。第一，`thenApply` 的回调执行在哪个线程上**不确定**：可能是完成任务的那个线程，也可能是恰好来挂回调的线程。回调里只放轻活，别放阻塞调用。`supplyAsync` 默认用 JVM 全局的 `ForkJoinPool.commonPool`（线程数与 CPU 核数挂钩的全局池），所有不讲规矩的代码都挤这一个池子，要跑阻塞任务就显式传入自己的线程池。第二，`get()` 永远带超时版本：`get(3, TimeUnit.SECONDS)`，不然一次任务卡死就是一次线程永久挂起。

### 异常传播：get() 时才爆出来

任务在异步线程里抛了异常，主线程怎么知道？`CompletableFuture` 把异常也存在盒子里，`get()` 时包在 `ExecutionException` 里抛出。跑一个：

```java
import java.util.concurrent.CompletableFuture;

public class CfErrorDemo {
    public static void main(String[] args) throws Exception {
        CompletableFuture<String> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("db down"));
        future.get();
    }
}
```

```text
运行异常：
Exception in thread "main" java.util.concurrent.ExecutionException: java.lang.RuntimeException: db down
	at CfErrorDemo.main(CfErrorDemo.java:7)
Caused by: java.lang.RuntimeException: db down
```

推导：第 6 行用 `completeExceptionally` 往盒子里装了一个异常，模拟"异步任务失败了"。第 7 行 `get()` 发现盒子里不是值而是异常，抛出 `ExecutionException`，原始异常挂在它的 `cause` 上（堆栈里的 `Caused by` 段）。`getCause()` 解包就能拿回原始异常。`SingleFlight` 的跟随者正是这么处理领导者失败的：解包后把原始异常重抛，语义等于"每个跟随者自己读、自己失败"。

## 项目案例一：SingleFlight，N 次读合并成 1 次

前九节的概念，在 LinkForge 的热路径上合成一件兵器。这是本章的核心案例，逐行读。

### 需求与问题

场景（LinkForge 业务）：一个短码被瞬时并发访问（社交平台爆点），N 个请求同时跳转。没有 `SingleFlight` 时会发生什么：缓存未命中的瞬间，N 个请求各自走到数据库读同一行，数据库压力被放大 N 倍。这种"热点缓存失效的瞬间，请求洪峰直接砸穿到数据库"的形态有个名字：缓存击穿。

朴素方案为什么不行：在方法上加 `synchronized`？锁住了整个方法的临界区，不同短码的请求互相拖累，DB 读也从并行变串行，热路径性能崩盘。需要的是**只对同一个 key 合并**：同 key 的 N 个调用共享一次执行，不同 key 互不影响。

### 逐行读 run()

`src/main/java/com/linkforge/link/support/SingleFlight.java`。核心数据结构第 51 行：

```java
private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();
```

（片段：`SingleFlight` 的类字段。）

键是短码，值是一个 `CompletableFuture` 盒子。"一次飞行"（flight）指一次进行中的读取：领导者放盒子，跟随者等盒子。

`run(K key, Supplier<V> loader)` 是全部核心，第 57 到 100 行。第 58 到 61 行先看配置开关，关闭就每次直接执行 `loader.get()`，不合并（LinkForge 业务逻辑，给压测和演练留后路）。第 62 行开始合并逻辑：

```java
CompletableFuture<V> mine = new CompletableFuture<>();
CompletableFuture<V> existing = inFlight.putIfAbsent(key, mine);
if (existing == null) {
    // leader: counts every lead attempt, successful or not
    meterRegistry.counter("linkforge.singleflight.loads").increment();
    try {
        V value = loader.get();
        mine.complete(value);
        return value;
    } catch (RuntimeException ex) {
        mine.completeExceptionally(ex);
        throw ex;
    } finally {
        inFlight.remove(key, mine);
    }
}
```

（片段：`run` 的领导者分支，摘自 `src/main/java/com/linkforge/link/support/SingleFlight.java` 第 62 到 77 行。）

每一行都在用前面讲过的机制：

1. `mine`：先造一个空盒子，不管自己是不是领导者都先造。
2. `putIfAbsent`：判断"没有"和"放入"一步完成。返回 null 说明放成功了，**我是领导者**；返回已有盒子说明有人在飞，我是跟随者。这就是"ConcurrentHashMap 复合原子方法"一节的应用：这里若写成 `get` 再 `put`，两个并发请求会同时判定自己是领导者，双重读库。
3. 领导者分支：在自己的请求线程上**同步执行** `loader.get()`（真实的数据库行读，javadoc 写明是刻意决定，不开额外线程池、不做异步移交）。成功则 `mine.complete(value)` 装值并返回。`meterRegistry.counter(...).increment()` 是往监控计数器加一（Micrometer 框架用法），每次领飞都计数，成功失败都算。
4. `catch (RuntimeException ex)`：读库失败，`completeExceptionally(ex)` 把异常装进盒子（所有等待者会收到），然后原样重抛（领导者自己的请求也按失败处理）。
5. `finally` 里的 `inFlight.remove(key, mine)`：飞行结束（成功或失败都算），把条目摘掉，下一个同码请求重新开飞。注意是**两参** `remove(key, mine)`，为什么不是单参，下面设计决定里讲。

跟随者分支（第 78 到 99 行）：

```java
try {
    V value = existing.get(cfg.getWaitTimeoutMs(), TimeUnit.MILLISECONDS);
    meterRegistry.counter("linkforge.singleflight.shared").increment();
    return value;
} catch (TimeoutException ex) {
    meterRegistry.counter("linkforge.singleflight.timeout").increment();
    return loader.get();
} catch (InterruptedException ex) {
    Thread.currentThread().interrupt();
    meterRegistry.counter("linkforge.singleflight.timeout").increment();
    return loader.get();
} catch (ExecutionException ex) {
    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
    if (cause instanceof RuntimeException runtime) {
        throw runtime;
    }
    if (cause instanceof Error error) {
        throw error;
    }
    throw new IllegalStateException(cause);
}
```

（片段：`run` 的跟随者分支，摘自 `src/main/java/com/linkforge/link/support/SingleFlight.java` 第 78 到 99 行。）

- `existing.get(waitTimeoutMs)`：拿到的是**别人**的盒子，最多等 500 毫秒（默认值来自 `src/main/java/com/linkforge/config/LinkForgeProperties.java` 第 120 行）。等到值，记一次 `shared` 计数，直接返回。共享成功的那次数据库读，N 个请求只发生了一次。
- 超时（`TimeoutException`）：不重试等待，**自己读库**。这叫 fail-open（等不到就降级自干）：等待的收益只是省一次读，代价可能是被领导者的慢拖死；自读给请求一个确定的延迟上界，代价是多一次读。对跳转热路径，后者划算。
- 被中断（`InterruptedException`）：先 `Thread.currentThread().interrupt()` 恢复中断标志（不吞掉别人的中断意图），然后同样降级自读。
- 领导者失败（`ExecutionException`，见上一节 `CfErrorDemo` 的原理）：解包 `cause` 后原样重抛。`RuntimeException` 和 `Error` 直接抛，其他类型包成 `IllegalStateException` 抛。效果：数据库故障原样传给每个跟随者，不放大、不自动重试（javadoc 写明，防止故障被去重器放大成风暴）。

### 三个设计决定

**决定一：`putIfAbsent`，不用 `computeIfAbsent`。** `computeIfAbsent` 语义更顺手（"没有就现场算一个"），但按 JDK 8 起 ConcurrentHashMap 的实现，函数在桶锁内执行。这里的函数是几十毫秒的同步 DB 读，等于锁着整个桶读库，同桶的其他无关短码全部被卡住。`putIfAbsent` 的临界区只有一次哈希定位和写入本身，业务逻辑全部在锁外。为什么 map 里存的值要先 `new` 一个空盒子再判断？因为合并的单位是"飞行"而不是"结果"：领导者执行期间，跟随者要等的是一个**活的**盒子。

**决定二：`finally` 里两参 `remove(key, mine)`。** 两参版本的语义：仅当"当前条目的值仍等于 mine"时才删。防的竞态在写路径：`ShortLinkService` 更新链接前会先 `peek(code)` 快照当前飞行，提交数据库更新后调 `forget(code, priorFlight)` 摘掉旧飞行，免得跳转读到即将过期的旧行。竞态窗口在这里：快照之后旧飞行自己结束了（条目已删），新飞行已经 `putIfAbsent` 了新盒子；这时 `forget` 若用单参 `remove(key)`，会把**别人的新飞行**误删，之后到达的请求失去合并。两参版本保证只摘除自己快照过的那一个。后果据代码推断是去重失效、DB 读放大，不是死等（跟随者手里有盒子引用，仍能等到结果）。

**决定三：这是去重器，不是缓存。** 条目飞行结束即删，没有 TTL（time-to-live，到期自动消失的时间限制），也没有旧值记忆。空结果（布隆过滤器的误报，它是项目里挡"从未创建过的短码"的快速判重结构；以及码已删除导致的 null）只在同一次飞行内共享。持久化的空值缓存、热点 URL 的本地缓存分别由 `ShortLinkService` 的 Redis 空值键和 `UrlLocalCache` 负责，职责边界写在 `SingleFlight` 的 javadoc 里。职责单一换来的好处：失效分析极简，它只合并"正在发生"的读，不承诺任何历史值。

### 好处、代价与边界

好处：热点短码风暴下，同 key 的 N 次数据库读合并为 1 次。代价：多一层 future 编排、一个快照协议（peek/forget）的认知成本。边界：**按进程合并**，多实例部署时每个 JVM 各自合并，N 个实例最多 N 次读；javadoc 说明了为什么不上分布式锁，它只会把读取串行化，不消除读取。读者自己验证：读 `run` 全文对照本节五个片段；并发视角它在 [81-linkforge-flows.md](81-linkforge-flows.md) 的跳转链里，容器视角在 [11-java-collections.md](11-java-collections.md)。

## 项目案例二：写后的延迟再删除（ScheduledExecutorService）

### 需求：为什么删一次缓存不够

场景（LinkForge 业务）：更新短链后要让它尽快生效。写路径的做法（`ShortLinkService.update` 第 297 到 302 行）：提交数据库更新，删缓存，写新值进缓存。问题在于时序：一个跳转请求可能恰好在"数据库提交前"读了旧行、又恰好在"缓存删除后"把旧值写回缓存（stale write-back，过期回写）。这样缓存里留了一份旧值， TTL 到期前一直是旧的。

方案是"延迟再删"：删除后隔一小段时间再删一次。第一次删除清掉旧值，第二次删除清掉那个慢跳转请求回写的旧值。实现用 `ScheduledExecutorService`（可调度延迟任务的线程池，`ExecutorService` 的子接口，多一个"延迟 X 后执行"的能力）。

### 逐段看代码

`src/main/java/com/linkforge/link/service/ShortLinkService.java` 第 63 行定义了延迟时长 `STALE_WRITEBACK_CLEAR_MS = 1000`（1 秒）。第 80 到 89 行是调度器本体：

```java
private final ScheduledExecutorService evictor = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "cache-evictor");
    t.setDaemon(true);
    return t;
});

@PreDestroy
void shutdownEvictor() {
    evictor.shutdown();
}
```

（片段：摘自 `src/main/java/com/linkforge/link/service/ShortLinkService.java` 第 80 到 89 行。）

- `newSingleThreadScheduledExecutor`：单线程的调度池。注意这里用了 `Executors` 工厂，接住前面一节的边界讨论：它内部队列同样无界，但任务由 `scheduleStaleClear` 自己投放，每次写路径最多 1 个、延迟 1 秒，速率完全可控，不存在外部洪峰灌队列的问题。
- lambda 是 `ThreadFactory`（造线程的回调）：给线程起名 `cache-evictor`，日志和线程转储里一眼认出是谁；`setDaemon(true)` 把它设为守护线程，JVM 退出时不被它拖住。
- `@PreDestroy` 是 Spring 的生命周期注解（框架行为）：容器关闭时回调这个方法，`shutdown()` 让调度池优雅停止。

第 642 到 650 行是调度本身：

```java
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

（片段：摘自 `src/main/java/com/linkforge/link/service/ShortLinkService.java` 第 642 到 650 行。）

`schedule(task, 1000, MILLISECONDS)`：1000 毫秒后执行一次。任务体里的 try/catch 不是多余：调度线程是长期复用的共享资源，未捕获异常虽不会杀死 `schedule` 的单次任务，但任何在调度线程上失控的代码都会让问题难以排查；包住并降级为 warn 日志，让故障可见、可统计。写路径在 `update`、`setStatus`（停用/启用）、`delete` 三处都调了 `scheduleStaleClear`（第 302、322、332 行），三个入口行为一致。

## 项目案例三：@Scheduled 定时任务与调度线程池

### SchedulingConfig：开关就 9 行

`src/main/java/com/linkforge/config/SchedulingConfig.java` 全文：

```java
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
```

`@Configuration` 声明这是配置类（Spring 框架行为）。`@EnableScheduling` 打开定时任务的总开关：Spring 启动时扫描所有 Bean 里带 `@Scheduled` 的方法，交给调度线程池定期执行。没有它，所有 `@Scheduled` 注解都是摆设。

### ClickOutboxRelay：轮询 outbox 表

`src/main/java/com/linkforge/stats/service/ClickOutboxRelay.java` 把"待发送的点击事件"从数据库 outbox 表搬到 RabbitMQ（独立部署的消息中间件）。outbox 指的是"先把要发的事件写成数据库行，再由后台任务搬运"的可靠投递模式，[51-rabbitmq-outbox.md](51-rabbitmq-outbox.md) 展开全链。核心是第 37 到 45 行：

```java
@Scheduled(fixedDelayString = "${linkforge.mq.relay-fixed-delay-ms:2000}",
        initialDelayString = "${linkforge.mq.relay-initial-delay-ms:3000}")
public void relay() {
    LinkForgeProperties.Mq mq = properties.getMq();
    if (!mq.isRelayEnabled() || !mq.isAsyncClickEnabled()) {
        return;
    }
    try {
        List<Outbox> batch = outboxMapper.selectPendingBatch(mq.getRelayBatchSize());
        // ... 逐行发送、标记 ...
    } catch (Exception ex) {
        log.warn("outbox relay pass failed: {}", ex.getMessage());
    }
}
```

（片段：摘自 `src/main/java/com/linkforge/stats/service/ClickOutboxRelay.java` 第 37 到 45 行，中间发送逻辑省略。）

- `fixedDelayString`：**固定间隔**调度，上一次执行**结束**到下一次开始之间隔指定毫秒。与它相对的是 `fixedRate`（按固定频率从开始时间排，上次没跑完下次照样到点）。轮询任务要 `fixedDelay`：上一批没搬完就自动顺延，任务不会叠加排队。`String` 后缀表示值来自配置占位符，冒号后是默认值（`2000` 毫秒轮询、`3000` 毫秒后才开始），不改配置就能跑。
- 开关每 tick 重读（`isRelayEnabled`）：演练和测试能在运行时切换行为，不用重启（LinkForge 业务设计，javadoc 写明）。
- 外层 try/catch 包住整轮：查询失败也只是一轮轮询失败，warn 日志留痕，下一轮照常。内层还有按行的 try/catch（第 55 到 65 行）：单行发送失败只影响那一行（退避重试，指数增长封顶 60 秒），不中断整批。

### 调度线程池的大小：故障域隔离

所有 `@Scheduled` 方法默认共享一个调度线程池，Spring Boot 默认**单线程**。单线程意味着：`ClickOutboxRelay` 轮询期间，其他定时任务全部排队；一个慢的下游能把整个项目的定时任务都拖住。LinkForge 在 `src/main/resources/application.yml` 第 6 到 12 行配置：

```yaml
spring:
  task:
    scheduling:
      # B5: relays must not share one thread — a slow webhook endpoint must never
      # delay click outbox catch-up. B9 adds the blocklist reload and B1 the backlog
      # sampler (D-Bw345-54: 4 → 6).
      pool:
        size: ${LINKFORGE_SCHEDULING_POOL_SIZE:6}
```

（片段：摘自 `src/main/resources/application.yml`，注释为项目原文。）

调度池 6 个线程，环境变量可调。注释原文给了理由（项目决策 B5）：各 relay 不能共享一个线程，慢的 webhook 端点绝不能拖住点击 outbox 的追平。这就是**按故障域隔离线程池**：不同业务域的慢故障，只拖垮自己域的时效。线程数不用大，定时任务就那么几个（outbox relay、webhook relay、名单重载、积压采样），按任务数留余量即可，慢下游的真正保护是批量与超时，不是堆线程。

## 项目案例四：跨进程的并发下沉到 Redis（RedisRateLimiter）

### 问题：JVM 的锁锁不住别的进程

场景（LinkForge 业务）：按 IP 限流跳转请求，"10 秒内最多 20 次"。限流判定有四步：清理窗口外的过期记录、数当前窗口内的次数、判断超没超、记账（记录本次）。前三节的所有工具（`synchronized`、原子类、ConcurrentHashMap）都只在一个 JVM 内有效。LinkForge 部署多实例时，两个 JVM 各自判"没超"，放行的总量就翻倍。锁的状态在 Redis 里，判定就必须在 Redis 里做。

### Lua 脚本：整段脚本当一条命令

`src/main/resources/redis/sliding_window.lua` 全文 18 行，核心逻辑：

```lua
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000000 + tonumber(t[2])
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - win)   -- 1. 清过期
local n = redis.call('ZCARD', KEYS[1])                        -- 2. 数当前
if n < max then
  redis.call('ZADD', KEYS[1], now, now .. '-' .. ARGV[3])    -- 3a. 记账
  redis.call('PEXPIRE', KEYS[1], ARGV[2])
  return {1, n + 1, 0}                                        -- 3b. 放行
end
local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
local retry_ms = math.ceil((tonumber(oldest[2]) + win - now) / 1000)
return {0, n, retry_ms}                                       -- 3c. 拒绝，算出重试等待
```

（片段：摘自 `src/main/resources/redis/sliding_window.lua`，注释为本文所加，`--` 后是 Lua 注释。）

原子性从哪来：Redis 执行命令是单线程的（一条命令执行完才执行下一条），而一段 Lua 脚本**作为一条命令**执行，脚本期间不会插入任何其他命令。四步之间因此不可能被别的请求插队，等价于天然的大临界区，却不用任何锁。细节：时钟用 Redis 自己的 `TIME` 而不是 JVM 传时间戳，多实例间应用机器时钟漂移就无法污染窗口（脚本头注释写明）。

### Java 侧：没有锁，只有降级

`src/main/java/com/linkforge/common/ratelimit/RedisRateLimiter.java` 第 27 到 32 行把脚本加载成 `DefaultRedisScript`，第 45 到 47 行执行：

```java
result = stringRedisTemplate.execute(SLIDING_WINDOW_SCRIPT, List.of(key),
        String.valueOf(max), String.valueOf(windowSeconds * 1000L),
        String.valueOf(ThreadLocalRandom.current().nextLong()));
```

（片段：摘自 `src/main/java/com/linkforge/common/ratelimit/RedisRateLimiter.java` 第 45 到 47 行。）

`execute` 把脚本和参数发给 Redis，拿回 `{allowed, 计数, retry_ms}` 三元组。第三个参数是随机数，`ThreadLocalRandom.current()` 是每线程一份实例的随机数生成器，连随机数都在避开多线程争用同一个源。Java 侧没有一行锁代码，原子性全部由 Redis 保证。第 48 到 49 行是另一半设计：Redis 报错时 `failOpen`（放行 + 打监控点），限流器故障不应升级成全站不可用，但静默失效也不行，所以每次降级都计数。

### 分界线

现在可以把整章的工具放进一张地图：

- **进程内并发**（同一个 JVM 的多线程）：`synchronized`、`volatile`、原子类、ConcurrentHashMap、线程池。JMM 和监视器是这套机制的地基。
- **跨进程并发**（多实例、多服务）：JVM 机制失效。要么把复合操作**下沉到单线程组件**（Redis 把四步限流做成一个原子脚本），要么引入分布式锁（更重，`SingleFlight` 的 javadoc 解释了它为何不用）。跨进程序列的一致性方案在 [50-redis.md](50-redis.md) 和 [70-distributed.md](70-distributed.md) 展开。

## 常见错误与误区

| 错误 | 后果 | 改法 |
|------|------|------|
| 到处 `new Thread()` 跑任务 | 数量失控，内存崩 | 线程池 |
| `start()` 写成 `run()` | 根本没有并发 | 调 `start()` |
| volatile 变量做计数 | 读-改-写照样丢更新 | `synchronized` 或原子类 |
| `synchronized (new Object())` | 每次换锁，白锁 | 固定的锁对象 |
| `Executors.newFixedThreadPool` 接外部请求 | 无界队列堆积，OOM | 手写 `ThreadPoolExecutor` |
| `ThreadLocal` 用完不 remove | 泄漏 + 线程复用读到脏数据 | finally 里 remove |
| `get` 再 `put` 做 check-then-act | 互相覆盖 | `putIfAbsent`/`computeIfAbsent` |
| 锁内调数据库、调远程 | 临界区超长，全员排队 | 锁外完成 IO |
| `future.get()` 不带超时 | 一次卡死，线程永久挂起 | `get(timeout, unit)` |

## 本章总结

判断一段并发代码，按顺序问四个问题：

1. **共享了吗？** 只有堆上的对象字段会出问题，栈上的局部变量天然安全。
2. **读-改-写了吗？** 是就需要 `synchronized`（顺带拿到可见性）或原子类；`volatile` 救不了它。
3. **只是一个线程写、别人读吗？** 用 `volatile`（配不可变对象更稳）。
4. **状态在进程外吗？** 在就把复合操作下沉到 Redis 脚本这类单线程组件，JVM 锁帮不上忙。

工程侧三条纪律：线程池手写七参数（有界队列、显式拒绝策略、线程命名）；`ThreadLocal` 进 finally 必 remove；`CompletableFuture.get()` 必带超时。项目侧五个活例对应五招：`SingleFlight` 用盒子合并读、`ShortLinkService` 用调度器延迟再删、调度池按故障域隔离、`ClickOutboxRelay` 用 fixedDelay 轮询加分层降级、`RedisRateLimiter` 把原子性下沉给 Lua。

## 自测

1. 两个线程不加锁各 `count++` 十万次，结果有没有可能恰好等于 200000？
   答：可能。交错丢失是概率事件，两线程恰好全程串行或没在关键点交错时就不丢。本章 `RaceDemo` 本机 6 次实测出过一次 200000。但正确代码不能依赖运气，多数运行会小于 200000。
2. `volatile int count` 为什么救不了 `count++`？
   答：volatile 保证每次读写都看见最新值，但 `count++` 是读、加、写三步，两步之间的空隙仍可被其他线程插队，丢更新照旧。要原子性用 `synchronized` 或原子类。
3. 线程池 core=1、max=2、有界队列 2，连续提交 8 个慢任务，每个任务的命运？
   答：task1 建核心线程执行；task2、3 进队列；task4 因队列满且线程未到 max 建第二个线程执行；task5 到 8 因队列满且线程到顶被拒绝策略处理。顺序是"先排队后扩线程"。
4. `ThreadLocal` 在线程池里为什么必须 `remove()`？
   答：两层后果。Entry 的键是弱引用会被 GC 清掉，值还挂在长期不死的线程上，泄漏内存；更危险的是脏数据，线程复用后下一个任务读到上一个任务留下的值。放 finally 里清。
5. `SingleFlight` 跟随者等 500 毫秒超时后为什么自己读库，而不是继续等？
   答：fail-open。继续等的收益只是省一次读，代价是被领导者的慢无限拖住；自读给请求一个确定的延迟上界，代价是多一次行读，对跳转热路径是划算的交换。
6. LinkForge 的限流为什么不用 `synchronized` 保护，而是把判定逻辑写成 Lua 脚本？
   答：限流状态在 Redis，多实例的请求来自不同 JVM，JVM 内的锁管不到别的进程。Redis 单线程执行命令，整段脚本作为一条命令执行天然原子，四步判定不可能被插队。

## 面试追问

**synchronized 和 volatile 怎么选？** 先各自报保证：synchronized 互斥加可见性、可重入；volatile 可见性加禁止重排序、不保证原子性。有读-改-写就锁或原子类，单写多读的标志与配置快照就 volatile。追问"synchronized 锁升级"：先声明这是 HotSpot 实现不是规范，无竞争走 CAS 轻量路径、竞争加剧膨胀为操作系统互斥量，偏向锁 JDK 15 起禁用废弃（JEP 374）。追问"双重检查锁为什么要 volatile"：`new` 分三步（分配内存、初始化、赋引用），后两步可被重排，另一线程可能拿到未初始化的引用，volatile 禁止该重排。

**线程池参数怎么定、为什么先排队再扩线程？** 七参数各管一件事（编制、上限、闲置回收、队列、工厂、拒绝）。提交流程四步：不足 core 建线程、满则入队、队满才建到 max、都满走拒绝。先排队是让突发流量缓冲而不是线程暴涨，要快速扩线程用 `SynchronousQueue`。追问"为什么不用 Executors 工厂"：fixedThreadPool 无界队列堆积吃内存、cachedThreadPool 线程数无上限，正确姿势是手写有界队列加显式拒绝策略加线程命名。追问"拒绝策略选哪个"：默认 Abort 快速失败可监控，CallerRuns 形成反压，两个 Discard 静默吞任务慎用。

**SingleFlight 的并发设计讲三个点。** 为什么 `putIfAbsent` 不用 `computeIfAbsent`：后者的函数在 ConcurrentHashMap 桶锁内执行，几十毫秒的 DB 读会卡住同桶无关 key，前者的临界区只有 map 操作本身。为什么两参 `remove(key, mine)`：写路径先 `peek` 快照再 `forget`，快照后旧飞行可能已结束、新飞行已占位，单参 remove 会误删新飞行，两参只删自己快照的那个。为什么跟随者超时自读：fail-open，给请求有界延迟，防止被领导者的慢拖死。追问"多实例部署怎么办"：按进程合并，各 JVM 各自合并，分布式锁只会串行化读取不消除读取，所以不用。

## 下一步

- `SingleFlight` 所在的完整跳转链（限流、布隆、缓存、singleflight、行读）：[81-linkforge-flows.md](81-linkforge-flows.md)。
- Redis 侧的原子性与限流算法谱系：[50-redis.md](50-redis.md)。
- outbox 轮询与消息全链：[51-rabbitmq-outbox.md](51-rabbitmq-outbox.md)。
- 并发测试下的连接预算（测试越多越容易爆连接）：[60-testing-ci.md](60-testing-ci.md)。
- 多实例带来的一致性问题：[70-distributed.md](70-distributed.md)。
