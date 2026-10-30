# 集合框架与 HashMap 原理

优先级 P0，目标层级 L2（选型正确、遍历修改安全），HashMap 与 ConcurrentHashMap 原理到 L3（能画结构、说清扩容与并发陷阱）。

## 学习目标

读完本章，你应该能：

- 说清为什么需要集合：数组哪里不够用，`Collection` 与 `Map` 两大族各管什么。
- 讲出 `ArrayList` 的底层数组、1.5 倍扩容、随机访问快的原因，以及 `LinkedList` 的真实定位。
- 把 `HashMap` 的 put/get 流程讲到面试深度：hash 扰动、容量 2 的幂、负载因子 0.75、扩容 rehash、链表转红黑树与退化阈值（按 JDK 17 的 HashMap 实现讲）。
- 讲清 `HashSet` 与 `HashMap` 的关系、`List.of` 与 `Arrays.asList` 的区别、fail-fast 迭代器抛 `ConcurrentModificationException` 的机制。
- 说明 `ConcurrentHashMap` 怎么做到并发安全，并在 LinkForge 里指出四处真实集合用法。

## 前置知识

- [java/10-01-types-and-references.md](java/10-01-types-and-references.md)：引用与对象的关系，`==` 和 `equals` 的区别。本章反复用到"同一个对象"和"内容相等"的区分。
- [java/10-05-generics.md](java/10-05-generics.md)：泛型。集合类都是泛型类，`List<String>` 的 `<String>` 是编译期约束。
- `equals` 与 `hashCode` 的完整契约在 [java/10-03-equality-and-strings.md](java/10-03-equality-and-strings.md)，本章"HashMap 的键"一节依赖它。

## 为什么需要集合

先看没有集合的日子。要存 100 个短码，你用数组：

```java
String[] codes = new String[100];  // 定长，写死 100（片段）
```

三个痛点立刻出现。

1. 长度写死。第 101 个短码来了，只能新建一个更大的数组，手工把旧数组复制过去。
2. 没有现成操作。在中间插一个、删一个，都要自己写 for 循环挪元素，写错一个下标就是 bug。
3. 找东西慢。"这个短码存过没有"只能从头到尾逐个 `equals`。

集合框架就是 `java.util` 包里一组现成的容器接口和实现类。增删、扩容、查找这些通用逻辑，标准库替你写好了、测好了。

### Collection 与 Map 两大族

按"装什么"分两族。for-each 是 `for (String s : list)` 这种遍历写法，凡是 `Iterable` 的都能这么遍历：

```
Iterable（可被 for-each 遍历）
 └─ Collection（装单个元素）
     ├─ List      有序、可重复、有下标
     ├─ Set       不允许重复，没有下标
     └─ Queue     队列，一头进一头出（栈、优先级队列也在这族）

Map（独立一族，不继承 Collection）
 └─ 装键值对：每个键最多映射一个值
     ├─ HashMap        哈希表，无序
     ├─ LinkedHashMap  哈希表 + 记住插入顺序
     └─ TreeMap        红黑树，按键排序
```

Queue 族里还有 `PriorityQueue`：底层二叉堆，默认小顶堆，每次出队拿最小元素。

选容器先问一个问题：我打算怎么查这个数据？

- 按位置查（第 3 个是什么）→ `List`。
- 按内容查（存没存过这个东西）→ `Set`。
- 按键查值（这个日期的点击数）→ `Map`。
- 按先后处理（排队）→ `Queue`。

泛型在这里的作用：容器只管装，装什么类型由尖括号声明，编译器把关。`List<String>` 里放不进 `Integer`，取出来也不用强转。

线程安全的容器（`ConcurrentHashMap`、`CopyOnWriteArrayList`、`BlockingQueue` 系列）在 [13-concurrency.md](13-concurrency.md) 展开，本章最后一节讲其中最常用的 `ConcurrentHashMap`。

## ArrayList：会自动扩容的数组

### 问题：数组定长

数组的三个痛点里，`ArrayList` 先解决前两个。它对外提供 `add`、`remove`、`get`，让你感觉数组"能长大"；内部仍然是一个普通数组，源码里叫 `elementData`，类型是 `Object[]`。

两个容易混的字段：

- `size`：现在装了几个元素，`size()` 方法返回它。
- 容量：底层数组当前多长。装满了才扩容，是内部细节，没有 getter。

### 随机访问为什么快：内存连续

`get(2)` 是一次数组寻址：首地址加 2 乘每个元素的宽度，一条算式直达，不用遍历。耗时与元素个数无关，所以文档说 `ArrayList` 随机访问是 O(1)：上界不随数据量增长。

内存连续还有第二层好处，叫缓存局部性。CPU 从内存取数不是按字节取，按 64 字节的连续块（缓存行）取。你读下标 2，下标 3、4、5 的数据大概率已经顺带进了缓存，接着访问它们几乎免费。

打个比方：`ArrayList` 像一排编号连续的信箱，拿到 3 号就能推算 7 号在哪。比喻失效的地方：这排信箱不能加长，只能整体搬去更大的小区（扩容），也不能在中间塞一个新信箱而不把后面的全部右移一格。

### 扩容：1.5 倍（JDK 17 的 ArrayList 实现）

装不下时触发扩容，源码 `grow` 方法的关键一行：

```java
int newCapacity = oldCapacity + (oldCapacity >> 1);  // 旧容量 + 旧容量的一半（片段）
```

新容量是旧容量的 1.5 倍，然后 `System.arraycopy` 把旧数组整体复制过去。默认构造的列表第一次 `add` 才分配数组，容量 10。

1.5 倍是实现选择，不是语言规范：每次只加一点（扩容太频繁）复制开销大，直接翻倍又可能浪费近一半内存，1.5 是折中。

单次扩容 O(n)，但 n 次 `add` 总共只复制 O(n) 个元素，摊到每次 `add` 是 O(1)。这种"偶尔贵、摊下来便宜"叫均摊复杂度。想完全避开复制，预估大小直接传构造器：`new ArrayList<>(10000)`。

### 示例：中间插入与按下标访问

```java
import java.util.ArrayList;
import java.util.List;

public class ArrayListDemo {
    public static void main(String[] args) {
        List<String> codes = new ArrayList<>();
        codes.add("ab3x");
        codes.add("k9pq");
        codes.add("zz12");
        codes.add(1, "new");  // middle insert: index 1 and later all shift right
        System.out.println(codes);
        System.out.println(codes.get(2));
        codes.remove("zz12");
        System.out.println(codes);
        System.out.println(codes.size());
    }
}
```

```text
[ab3x, new, k9pq, zz12]
k9pq
[ab3x, new, k9pq]
3
```

逐步看：

1. 三次尾插后列表是 `[ab3x, k9pq, zz12]`。
2. `add(1, "new")` 在下标 1 插入：下标 1 及之后的元素整体右移一格，新元素落位。列表变 `[ab3x, new, k9pq, zz12]`。中间插入要挪元素，是 O(n)。
3. `get(2)` 直接寻址返回 `k9pq`，O(1)。
4. `remove("zz12")` 按内容删：从下标 0 开始逐个 `equals` 找到它（O(n)），删除后右边元素左移补位（又是 O(n)）。
5. `size()` 返回 3。

初学者常见反例：拿 `ArrayList` 当队列，循环 `remove(0)`。每次删头都要把后面全部左移，n 次删头是 O(n²)。要队列语义用 `ArrayDeque`，它底层是循环数组（首尾相接的数组，下标到头绕回 0），两头操作 O(1)。

### 示例：预估容量省下的复制

下面的程序把 500 万个整数分别装进"边加边扩"和"预分配"的两个列表，各计一次耗时。先跑一轮热身再测，输出是毫秒数。

```java
import java.util.ArrayList;
import java.util.List;

public class GrowDemo {
    static final int N = 5_000_000;

    static long timed(List<Integer> list) {
        long t0 = System.nanoTime();
        for (int i = 0; i < N; i++) {
            list.add(i);
        }
        return (System.nanoTime() - t0) / 1_000_000;
    }

    public static void main(String[] args) {
        timed(new ArrayList<>());  // warm-up, result discarded
        System.out.println("grow-as-you-go: " + timed(new ArrayList<>()) + " ms");
        System.out.println("pre-allocated:  " + timed(new ArrayList<>(N)) + " ms");
    }
}
```

```text
输出（示意）：
grow-as-you-go: 232 ms
pre-allocated:  141 ms
```

标注示意是因为时间数字每次运行都不同（本机多轮实测在 200 到 480 毫秒与 135 到 150 毫秒之间波动）。差距来自扩容复制：逐步扩容要多复制约 2 倍于最终数组的数据量，还多次分配随即废弃的中间数组。数据量只有几百个时差距可以忽略，不必处处预分配。

## LinkedList：真实定位是很少用

### 问题：它到底好在哪

`LinkedList` 是双向链表：每个节点存元素和前后两个引用，节点散布在堆内存各处。教科书说它"插入删除快"，这句话有个大前提。

- 已经持有要操作的节点引用时，插删只改四个指针，O(1)，这是真的。
- 按下标插入时，得先从链头（或链尾）一步步走过去，走是 O(n)，总代价还是 O(n)。
- 走链表对缓存不友好：每个节点是一次独立的内存访问（追逐一个指针跳到另一个内存位置），节点之间物理位置无关，缓存行命中率低。`ArrayList` 同样扫一遍，相邻元素早就在缓存里了。

`get(i)` 在 `LinkedList` 里是 O(n/2)：JDK 实现从更近的一端走，但复杂度量级不变。`ArrayList` 是 O(1)。

结论：业务代码里 `ArrayList` 几乎总是更好的选择。`LinkedList` 真正的适用场景要"既是 List 又是双端队列"，这种需求现实中很少；要栈或队列，用 `ArrayDeque`，连续内存，两头 O(1)。面试被问"什么时候用 LinkedList"，稳妥回答是：几乎不用，除非已经持有节点引用做高频插删，而这个前提本身就罕见。

## HashMap：按键查找的格子柜

### 问题：List 找值是 O(n)

需求：给日期查点击数、给短码查目标 URL。用 `List` 装就得每次遍历逐个比，n 大了受不了。`HashMap` 把查找变成一次地址计算加最多几次比较。

### 直觉与技术实体

比喻：一格一格的柜子，每件东西按编号放柜，取的时候算出编号直接开柜。对应的技术实体：编号是"键的 hash 映射后的桶下标"，柜子是 `Node<K,V>[] table` 数组。比喻失效的地方：两件东西可能算出同一个编号（哈希碰撞），这时一个柜子里要挂一串东西，取的时候得挨个对暗号（equals）。

JDK 17 的 HashMap 结构（JDK 8 引入这套结构后沿用至今）：

```
table：数组，每个格子叫一个桶（bin）
 [0]  -> null
 [1]  -> Node(k1,v1) -> Node(k2,v2) -> Node(k3,v3)   // 碰撞的键串成单链表
 [2]  -> TreeNode(...)                                // 长到阈值后整棵转成红黑树
 [3]  -> null
 ...
```

- 每个节点存四个字段：`hash`、`key`、`value`、`next`。
- 红黑树是一种自平衡二叉搜索树：节点有序，查找 O(log k)，插入时的旋转次数比严格平衡的 AVL 树少，读写更均衡。树化只发生在很长的桶里，正常数据几乎遇不到。

### put 的完整流程（JDK 17 的 HashMap 实现）

以下六步按 JDK 17 的 `putVal` 源码走。这些是实现细节而不是语言规范，面试常按这个粒度问。

1. 表没建就先建表。`new HashMap<>()` 不分配数组（懒初始化），第一次 put 分配容量 16 的表，阈值 12。
2. 算扰动 hash：`h = key.hashCode()`，然后 `hash = h ^ (h >>> 16)`，把 h 的高 16 位异或进低 16 位。key 为 null 时 hash 记 0，固定落桶 0（HashMap 允许一个 null 键）。
3. 定桶：桶下标 = `(n - 1) & hash`，n 是表长。n 是 2 的幂时，这个与运算等价于 `hash % n`。
4. 桶空：直接放一个新节点。
5. 桶非空：第一个节点若 hash 相同且键 `equals` 成立，覆盖旧 value；是树节点就走红黑树插入；否则沿 `next` 遍历链表逐个比较（先比 `hash` 字段再调 `equals`，hash 不同直接跳过），走完没命中就尾插。链上已有 8 个节点（`TREEIFY_THRESHOLD`）还要再插时触发 `treeifyBin`：表长不足 64（`MIN_TREEIFY_CAPACITY`）就先扩容，够 64 才真正转红黑树。
6. `++size` 超过阈值（容量乘负载因子 0.75）触发 `resize`：容量翻倍。

### get 的流程

同样的扰动和 `(n - 1) & hash` 定桶，然后桶内查找：第一个节点命中直接返回；树桶 O(log k)；链表逐个比 hash 加 equals，O(k)，k 是桶长。没找到返回 null。

### 三个必考"为什么"

**为什么容量恒为 2 的幂。** 两个原因。一是 `(n-1) & hash` 在 n 为 2 的幂时等价于取模，与运算比除法快；二是扩容后重新分布便宜，见下条。

**扩容为什么是 2 倍、为什么不用重算 hash。** 容量翻倍后，新下标 = `(新n - 1) & hash`，比旧下标多看 hash 的一个二进制位。源码遍历旧桶的链表，按 `hash & 旧容量` 是 0 还是 1 拆成两条：0 的留在原下标 j，1 的去 j 加旧容量。每个元素只有两种去向，链表内相对顺序保持，所以不用重算任何 hash。举例：容量 16 时下标 5 的元素，扩到 32 时看 hash 里值为 16 的那个二进制位，是 0 就还在 5，是 1 就去 21。

顺带一个历史结论：JDK 7 扩容用头插，并发扩容能把链表接成环，之后 get 落进环里 CPU 100%；JDK 8 改尾插且拆分保序，成环消失。但 HashMap 从来不是线程安全的，见 ConcurrentHashMap 一节。

**树化阈值为什么是 8，退化阈值为什么是 6。** JDK 源码注释的解释：理想随机散列下，桶长度服从泊松分布（描述"单位内随机事件发生 k 次"的概率分布）。桶长到 8 的概率约 0.00000006，即亿分之六。正常情况根本长不到 8，树化是给"hashCode 质量差或被恶意构造"准备的兜底，不是常规路径。退化阈值 6（`UNTREEIFY_THRESHOLD`）比树化的 8 低两档，是防抖动缓冲：若都是 8，桶长在 8 附近反复增删时会在树和链表之间来回切换，白白开销。扩容把树桶拆成链后，链长不超过 6 就退回链表；红黑树上删除节点使树变小时，也会整体退回链表（`removeTreeNode` 按结构判断，不精确数 6）。

**负载因子为什么是 0.75。** 空间与时间的经验折中：太大会碰撞多、链表长、查找慢；太小内存浪费、扩容频繁。0.75 是 JDK 选的默认经验值，不是数学定理，构造时可改但一般不动。想放 n 个元素不触发扩容，初始容量要传到 n 除以 0.75 再向上取整到 2 的幂：放 100 个传 134，取整成 256，阈值 192。

### 示例：同值不同对象的键

HashMap 找键先比 hash 再比 equals。下面的 Point 类故意不重写这两个方法：

```java
import java.util.HashMap;
import java.util.Map;

public class MapKeyDemo {
    static class Point {
        final int x;
        final int y;

        Point(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public String toString() {
            return "(" + x + "," + y + ")";
        }
    }

    public static void main(String[] args) {
        Map<Point, String> points = new HashMap<>();
        points.put(new Point(1, 2), "first");
        System.out.println(points.get(new Point(1, 2)));
        points.put(new Point(1, 2), "second");
        System.out.println(points.size());
        System.out.println(points.containsKey(new Point(1, 2)));

        Map<String, String> codes = new HashMap<>();
        codes.put(new String("ab3x"), "first");
        System.out.println(codes.get(new String("ab3x")));
    }
}
```

```text
null
2
false
first
```

逐步解释：

1. `get(new Point(1, 2))` 返回 null。两个 `(1,2)` 的 Point 是两个对象。Point 没重写 `equals` 和 `hashCode`，用的是 Object 的版本：`equals` 比引用（是不是同一个对象）；`hashCode` 的默认实现在 HotSpot 上按对象身份算，同一对象每次调用值相同，不同对象一般不同。新对象的 hash 大概率落进另一个桶，即使碰巧同桶，`equals` 也不等，找不到。
2. 再 put 一个 `(1,2)`，size 变 2 而不是覆盖成 1：HashMap 没认出它们是"同一个键"。
3. `containsKey` 同理返回 false。
4. `new String("ab3x")` 造的两个对象却能互相找到：String 重写了 `hashCode`（相同字符序列得到相同值）和 `equals`（比内容）。put 和 get 的字符串内容一样，hash 一样落同一个桶，equals 成立，找到。

结论：自定义类做 HashMap 或 HashSet 的键，必须同时重写 `equals` 和 `hashCode`，且两者一致（equals 相等的对象 hashCode 必须相等，否则同样的键会落进不同的桶，永远找不到）。完整契约见 [java/10-03-equality-and-strings.md](java/10-03-equality-and-strings.md)。

### 反例：可变对象做键

初学者有时拿字段能被改的对象当键，put 之后又改它的字段。发生什么：元素已经在按旧 hash 算出的桶里，字段一改 hash 就变了，get 按新 hash 去别的桶找，元素"失踪"，还在 map 里占着 `size`，但按改过的键永远取不出来。怎么改：键用不可变类型，String、Integer、LocalDate、record 都行，字段定死，hash 永不漂移。局限：如果业务确实要求"键可变之后仍能找到"，HashMap 不适合这个需求，要换结构或自己承担搬移成本。

## HashSet：包着 HashMap 的壳

### 问题：去重要自己写吗

需求：判断一个短码存没存过，或者把列表里的重复项去掉。自己写"遍历比对"每次 O(n)，存 1 万个就要比 1 万次。

### 准确定义

`HashSet` 的 JDK 17 源码核心就两行：

```java
private transient HashMap<E,Object> map;              // 片段
private static final Object PRESENT = new Object();   // 片段
```

（片段：摘自 `java.util.HashSet` 源码，不可单独运行。）

元素当 key，value 永远是同一个占位对象 PRESENT。`add(e)` 就是 `map.put(e, PRESENT)`：

- key 是新的，put 返回 null，add 返回 true。
- key 已存在，put 返回旧值，add 返回 false，集合不变。

`contains`、`remove`、`size` 全部转发给内部 map。所以 HashSet 的性能特征、对 equals/hashCode 的要求、全部实现细节，都和 HashMap 一模一样。去重 O(1) 的代价是元素必须能算 hash。

### 示例：add 的返回值与大小写

```java
import java.util.HashSet;
import java.util.Set;

public class SetDemo {
    public static void main(String[] args) {
        Set<String> reserved = new HashSet<>();
        System.out.println(reserved.add("admin"));
        System.out.println(reserved.add("admin"));
        System.out.println(reserved.size());
        System.out.println(reserved.contains("ADMIN"));
    }
}
```

```text
true
false
1
false
```

1. 第一次 add 成功，true。
2. 第二次 add 同一个内容，false，集合没变。
3. size 是 1，重复的没有进。
4. `contains("ADMIN")` 是 false：Set 按内容比较，`"ADMIN"` 和 `"admin"` 是不同元素。大小写归一要自己做，本章项目案例里 `LinkValidations` 就是先 `toLowerCase` 再查。

### Set 家族三兄弟与 Map 的两个变体

- `HashSet`：无序，最快。
- `LinkedHashSet`：HashMap 加一条双向链表把插入顺序串起来，遍历按插入序，比 HashSet 多一点内存。
- `TreeSet`：底层是 TreeMap（红黑树），按比较器排序，操作 O(log n)。

选型：只为去重和快速包含判断用 `HashSet`；要按顺序输出才用后两个。为去重付 O(log n) 或额外链表是常见的小浪费。

Map 侧对应两个变体：`LinkedHashMap` 按插入序遍历；把构造参数 `accessOrder` 设为 true 后每次 get 会把节点移到尾部，配合重写 `removeEldestEntry` 能手写 LRU（最近最少使用）缓存。LinkForge 没这么做：`src/main/java/com/linkforge/link/support/UrlLocalCache.java` 用的是 Caffeine 库，要的是"LRU 加 TTL 加容量上限加命中统计"的组合，自研 LinkedHashMap 只能得到 LRU 一件事。

## 不可变集合与 Arrays.asList 的定长坑

### 问题：给出去的集合被改了

你写一个方法返回系统的保留字列表，调用方顺手 `add` 了一个元素，你的"常量"从此不再是你以为的内容。需要一种"给出去就不能改"的集合。

### List.of / Set.of / Map.of（Java 9 起的正式特性）

行为清单：

- 任何修改方法（`add`、`remove`、`set`、`put`、`clear`）都抛 `UnsupportedOperationException`。
- 不允许 null 元素，`List.of(null)` 直接抛 `NullPointerException`。
- `Set.of`、`Map.of` 遇到重复元素（键）在创建那一刻就抛 `IllegalArgumentException`，不等运行到一半才炸。

"不可变"的边界：是这个集合对象不能增删改，不是它装的对象也不能改。`List.of(builder)` 里的对象照样能被改，装进不可变集合救不了它。

### 示例：List.of 不可变

```java
import java.util.List;

public class ImmutableDemo {
    public static void main(String[] args) {
        List<String> reserved = List.of("admin", "health", "links");
        reserved.add("metrics");
    }
}
```

```text
运行异常：
Exception in thread "main" java.lang.UnsupportedOperationException
	at ImmutableDemo.main(ImmutableDemo.java:6)
```

堆栈只有一层业务帧：第 6 行的 `add` 直接抛出。`List.of` 返回的实现类重写了 `add` 并让它永远抛这个异常。这不是"暂时不支持"，是设计如此。

### Arrays.asList：定长视图，不是不可变

`Arrays.asList("a", "b")` 返回的是 `java.util.Arrays` 的内部类 ArrayList（名字和 `java.util.ArrayList` 撞了，别混）。它包着原数组：

- 长度固定：`add`、`remove` 抛 `UnsupportedOperationException`。
- `set(下标, 元素)` 合法，而且写回原数组。

两个坑：一是把它当普通 List 传给下游方法，下游一 add 就炸；二是不知道 `set` 会改到外面的原数组，出现"我改的是自己拿到的列表，怎么原数组也变了"的意外。

需要可变列表时包一层：`new ArrayList<>(Arrays.asList(...))` 或 `new ArrayList<>(List.of(...))`。

## fail-fast：迭代时改集合会发生什么

### 问题：遍历中删除

需求：删掉列表里等于某个值的元素。最直觉的写法：

```java
import java.util.ArrayList;
import java.util.List;

public class FailFastDemo {
    public static void main(String[] args) {
        List<String> codes = new ArrayList<>();
        codes.add("a1");
        codes.add("a2");
        codes.add("a3");
        for (String code : codes) {
            if (code.equals("a1")) {
                codes.remove(code);  // modify the list while iterating it
            }
        }
    }
}
```

```text
运行异常：
Exception in thread "main" java.util.ConcurrentModificationException
	at FailFastDemo.main(FailFastDemo.java:10)
```

### 逐步推导

for-each 是编译器的语法糖，实际改写成迭代器循环：`while (it.hasNext()) { code = it.next(); ... }`。迭代器是"按顺序逐个给你元素"的对象，集合的 `iterator()` 方法返回它。堆栈里 `FailFastDemo.main(FailFastDemo.java:10)` 指向 for 那一行，实际是 `it.next()` 在抛。

异常类名虽然带 Concurrent，却和线程无关，单线程也会抛。机制（JDK 17 的 ArrayList 实现）：

1. `ArrayList` 内部有个计数器 `modCount`，每次结构性修改加 1。结构性修改指改变元素个数的操作（add、remove、clear），`set(下标, 值)` 这种替换不算。
2. 迭代器创建时记下快照 `expectedModCount`。
3. 每次 `next()` 先比对两者，不一致立刻抛 `ConcurrentModificationException`。

上面程序里：删掉 "a1" 后 `modCount` 加了 1，列表剩 2 个元素；下一次 `next()` 一比对，快照对不上，抛异常。这个检测的目的是尽快暴露"一边遍历一边改"的 bug，所以叫 fail-fast（坏了就立刻失败）。

### 为什么迭代器的 remove 合法

`it.remove()` 删完会把 `expectedModCount` 同步到最新 `modCount`，下一次 next 比对仍然相等。列表自己的 `remove` 只改 `modCount`，不同步快照。同一个删除动作，走哪条路决定了合不合法。

### 三种安全删除

1. `codes.removeIf(code -> code.equals("a1"))`（Java 8 起，内部保证一致，最推荐）。
2. 迭代器的 `remove`：`Iterator<String> it = codes.iterator(); while (it.hasNext()) { if (...) it.remove(); }`。
3. 倒序按下标 for 循环删。正序删有另一个坑：删掉下标 i 后右边元素左移，`i++` 会跳过紧跟的那个元素。

HashMap 的迭代器同机制（记录同一个 `modCount`），迭代中 put 或 remove 一样抛。

### fail-fast 是探测器，不是保证

`modCount` 比对是尽力而为：两个线程交替修改可能侥幸不抛，标准库文档明确说不可依赖它保证正确。它不是并发安全机制，并发下要么用并发容器，要么自己加锁。

对立面看 `ConcurrentHashMap`：它的迭代器是弱一致的，不抛异常，但看到的是"迭代开始时的内容"加上"过程中部分修改"的混合，不保证包含迭代开始后的修改。需要强一致快照就先复制一份再遍历。

## ConcurrentHashMap：并发下的 Map

### 问题：HashMap 并发写会怎样

两个线程同时往 HashMap 尾插，可能互相覆盖丢元素；`++size` 不是原子操作（一条指令做不成"读加写"三步），size 会数错；扩容期间的读写更乱。历史上最惊险的是 JDK 7 头插成环死循环（上一节讲过）。JDK 8 修了成环，没修线程不安全。结论：并发场景换容器，不是给 HashMap 套 `synchronized`（整表一把锁等于所有读写排队，性能全无）。

### JDK 8 起的设计：CAS 加桶级 synchronized

JDK 7 的 ConcurrentHashMap 用分段锁：整表先切 16 段，每段一把锁，并发度固定 16。JDK 8 起放弃分段（Segment 类只是兼容保留的空壳），改成更细的桶级方案，JDK 17 沿用：

- 桶为空：用 CAS 占位。CAS（compare-and-swap，比较并交换）是 CPU 提供的原子指令：把内存位置的值从期望值换成新值，若那一刻值已经变了就失败。失败方重试或放弃，全程不加锁。
- 桶非空：`synchronized` 锁住桶的头节点。锁粒度是一个桶，不同桶的写互不阻塞。
- `get` 全程无锁：节点的 `val` 和 `next` 字段声明为 volatile，写线程的修改立刻对读线程可见，读不用加锁。
- `size()` 用 `baseCount` 加 `CounterCell[]` 数组分散计数：写时先 CAS `baseCount`，冲突了记到自己的格子里，读时求和。所以 size 是弱一致的近似值。
- 初始化和扩容是多线程协作的：put 时发现正在扩容，当前线程帮忙搬桶（源码方法 `helpTransfer`），不是干等。内部字段 `sizeCtl` 一个数当三个意思：-1 表示正在初始化，其他负数编码了参与扩容的线程数，正数是下一次扩容的阈值（JDK 17 的 ConcurrentHashMap 实现细节）。

### 一个附带规则：key 和 value 都不允许 null

HashMap 允许一个 null 键和任意 null 值，ConcurrentHashMap 两个都不允许。原因：并发下 `get` 返回 null 没法区分"键不存在"和"值就是 null"。单线程可以再调 `containsKey` 补一刀，但并发下两步之间值可能已被别的线程改掉，语义无法自洽。

### CopyOnWriteArrayList 一段带过

并发 List 用 `CopyOnWriteArrayList`：每次写（add、remove、set）都复制整个新数组，再原子替换数组引用。读和迭代完全无锁，迭代器是创建那一刻的快照，永不抛 `ConcurrentModificationException`。代价是写 O(n) 复制，读极多、写极少才划算，典型用途是事件监听器列表。其余并发容器在 [13-concurrency.md](13-concurrency.md)。

## 项目案例：LinkForge 里的四处集合用法

### TargetPolicy.Config：Set.of 定格空配置，Set.copyOf 定格热更新

需求：短链平台要拦截恶意目标域名。域名清单来自两个地方：配置项和一个可热重载的清单文件，运行中会整体替换。

先看类型定义，`src/main/java/com/linkforge/link/support/TargetPolicy.java` 第 41 行：

```java
public record Config(Set<String> blockDomains, Set<String> warnDomains) {
    public static final Config EMPTY = new Config(Set.of(), Set.of());
}
```

（片段：record 定义，评估逻辑在同类 `evaluate` 方法里。）

`Config` 是 record：只声明字段、自动生成构造器和访问器的数据类（Java 17 正式特性，见 [java/10-10-java17.md](java/10-10-java17.md)）。字段类型用 `Set<String>` 而不是 `List`，表达"域名集合，判断包含，与顺序无关"。

如果字段装的是 `HashSet` 会怎样：任何拿到 Config 的代码都能 add 或 remove；重载线程在改清单，跳转线程正在 `matchesAny` 里 for 遍历（第 121 到 128 行），一边改一边读，轻则读到半新半旧的规则，重则上一节讲的 CME。

项目的实际做法在 `src/main/java/com/linkforge/link/support/PhishingPolicy.java` 第 136 到 140 行：

```java
private static Set<String> union(Set<String> a, Set<String> b) {
    Set<String> merged = new LinkedHashSet<>(a);
    merged.addAll(b);
    return Set.copyOf(merged);
}
```

（片段：私有工具方法，被同类 `merge` 与 `parseFile` 调用。）

组装时用可变的 `LinkedHashSet`，装完 `Set.copyOf` 一次性定格成不可变副本。换清单时构建一个全新的 Config，往声明为 volatile 的 `snapshot` 字段（第 34 行）整体替换。`evaluate` 方法把检查委托给 `TargetPolicy.evaluate(url, snapshot, forCreate)`，读侧拿到的永远是一个完整、不可变的规则集。

依赖的机制：`Set.of()` 建不可变空集、`Set.copyOf` 做防御性拷贝并返回不可变实现、volatile 保证引用替换对其他线程立即可见。这是并发代码里常用的"不可变数据加引用替换"模式。

好处：读侧零锁、零防御性复制，规则对象可以随便传。代价：每次重载全量重建、全量复制。边界：清单大且改动频繁时不划算，那种场景用并发容器。

自己验证：在测试里对 `config.blockDomains()` 调 `add`，会抛 `UnsupportedOperationException`，与本章 ImmutableDemo 同类行为。

### LinkValidations.RESERVED_CODES：Set.contains 背后的 equals/hashCode

需求：用户自定义短码不能占用系统路由。`/admin`、`/health` 这些路径已被框架占用，用户注册短码 "admin" 会把跳转路径顶掉。

`src/main/java/com/linkforge/link/support/LinkValidations.java` 第 18 到 21 行：

```java
private static final Set<String> RESERVED_CODES = Set.of(
        "actuator", "health", "auth", "login", "register", "links",
        "export", "stats", "swagger", "docs", "static", "index",
        "admin", "console", "metrics", "assets", "error", "favicon");
```

（片段：类常量，查询发生在下面 `requireValidCustomCode` 方法里。）

第 44 行的查询：

```java
if (RESERVED_CODES.contains(code.toLowerCase())) {
```

（片段。）

逐段看：

- `Set.of` 字面量建 18 个保留词，静态 final，类加载时建一次。用 Set 而不是 List 的原因：`contains` 是唯一操作，Set 走 hash 定位 O(1)，List 要逐个 equals 扫描。
- `contains` 能工作的前提是 String 的 `hashCode` 与 `equals` 契约：相同字符序列得到相同 hash，equals 成立。这正是 MapKeyDemo 里 Point 缺的东西。
- `code.toLowerCase()` 先归一：Set 区分大小写（SetDemo 里 `contains("ADMIN")` 是 false），不归一的话用户注册 "Admin" 就绕过了。这里用无参 `toLowerCase`，按系统默认 locale 转换；同一份代码库里 `TargetPolicy` 归一化域名时特意用了 `Locale.ROOT`，因为默认 locale 在某些语言环境下行为不同（经典例子是土耳其语的 i）。短码限定在 ASCII 字母数字，两种写法结果一致；域名可能含非 ASCII，必须用 `Locale.ROOT`。
- 源码注释交代了一个设计：集合里全是 4 字符以上的词。更短的词（"api"、"r"）过不了同方法的正则长度规则（4 到 16 位），不收进集合，免得躺着永远用不上的死条目。

好处：O(1)，语义直白，静态常量一次构建。代价：18 个字符串常驻内存，可忽略。边界：适合"成员编译期已知的少量值"；成员来自运行期数据就用 HashSet 动态装。

自己验证：写个 main 调 `LinkValidations.requireValidCustomCode("Admin")`，会抛 `BizException("customCode is reserved")`；换成 `ADMIN1`（6 位、非保留）则通过。

### ClickStatsService.stats：Map<LocalDate, Long> 补齐日期轴

需求：曲线图要求"窗口内每天一个点"，没点击的日子也要画 0。但数据库聚合表只在有点击的日期才有行（业务事实：写入方只对发生的点击做增量，所以"没点击的日期不产生行"是据代码推断）。数据库返回的行数小于等于天数，中间有洞。

`src/main/java/com/linkforge/stats/service/ClickStatsService.java` 第 127 到 134 行：

```java
Map<LocalDate, Long> byDate = clickDailyMapper.selectRange(linkId, fromDate, today).stream()
        .collect(Collectors.toMap(ClickDaily::getStatDate, ClickDaily::getClicks));
Map<LocalDate, Long> uvByDate = clickLogMapper.selectDailyUv(linkId, from, toExclusive).stream()
        .collect(Collectors.toMap(DailyUv::getStatDate, DailyUv::getUv));
List<DailyClicks> daily = new ArrayList<>();
for (LocalDate d = fromDate; !d.isAfter(today); d = d.plusDays(1)) {
    daily.add(new DailyClicks(d, byDate.getOrDefault(d, 0L), uvByDate.getOrDefault(d, 0L)));
}
```

（片段：`stats` 方法的中段，依赖 mapper 与 DTO。）

逐段看：

- `Collectors.toMap(键提取器, 值提取器)` 把行的流聚成 Map：键是 `getStatDate()` 返回的 LocalDate，值是当日点击数。`ClickDaily::getStatDate` 是方法引用，等价于 lambda `row -> row.getStatDate()`（匿名函数，见 [java/10-08-lambda-stream-optional.md](java/10-08-lambda-stream-optional.md)）。
- LocalDate 是不可变值对象，`equals` 与 `hashCode` 按字段值比较。正是"好键"的标准长相，呼应上一节的反例。
- 循环从窗口起点 `fromDate` 一天天走到 `today`，每天生成一个点；`byDate.getOrDefault(d, 0L)` 在 Map 没有这一天时给 0，把洞补上。`getOrDefault(key, default)` 等价于"有就取，没有给默认"，比 `containsKey` 加 `get` 省一次查找。
- 另一个 `ArrayList` 出场点在 `daily`：点的个数只有编译后才知道（取决于窗口天数），只能边遍历边 add，这正是"不定长"该用 `ArrayList` 的场景。

如果不用 Map：对窗口里每一天去 List 里线性找对应行，O(天数乘行数)。数据量小也能跑，Map 版把"按日期查"从遍历降成一次 hash 定位，代码意图也更直白。

代价与边界：`Collectors.toMap` 遇到重复键直接抛 `IllegalStateException`。这里不炸的前提是同一日期只有一行，据代码推断 mapper 的 `selectRange` 按日聚合返回（MyBatis mapper 行为，本文不展开）。你在别处复用 `toMap` 时，先确认键真的唯一，或用三参重载给出合并函数。

自己验证：构造两行 `ClickDaily`（10-01 和 10-03 两天）装进 List，用同样代码 collect 后打印 `byDate`，再跑那个循环看 10-02 被 `getOrDefault` 补成 0。

### SingleFlight：ConcurrentHashMap 上的请求合并

需求（项目决策 A10）：短链跳转是热路径，一个短码被瞬时并发访问时（比如社交平台爆点一个链接），N 个请求会各读一次同一行 DB。SingleFlight 把并发同 key 的调用合并成一次真实读取：第一个到的去读库，其余的等它的结果。

直觉：挂号排队，第一个挂这个号的人进诊室，后面挂同一个号的在门口等他出来传话。技术实体：`CompletableFuture` 是一个"将来才会有值"的盒子，生产者调 `complete(v)` 装值，消费者调 `get()` 阻塞等值。

`src/main/java/com/linkforge/link/support/SingleFlight.java` 第 51 行是核心数据结构：

```java
private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();
```

（片段：类字段。）

键是短码，值是"这次飞行"的结果盒子。`run` 方法第 62 到 76 行：

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

（片段：`run` 的领导者分支；跟随者分支在后面第 78 行起。）

逐段看：

- `putIfAbsent(key, mine)`：键不存在才放。返回 null 说明放成功了，我是领导者；返回已有盒子说明有人在飞，我是跟随者，拿人家的盒子等结果。这是 ConcurrentHashMap 的原子方法，"判断没有"和"放入"一步完成，两个线程并发执行时恰好有一个返回 null。
- 领导者执行 `loader.get()`（真实的 DB 行读），成功就 `complete(value)` 唤醒所有等待者。异常则 `completeExceptionally(ex)` 把异常传给等待者，然后原样重抛。等待者在 `get` 处收到 `ExecutionException`，源码第 90 到 98 行解包后重抛原始异常：DB 故障原样传给每个跟随者，不放大、不自动重试。`meterRegistry.counter(...).increment()` 是向监控计数器加一（Micrometer 用法），不影响主逻辑。
- `finally` 里的 `remove(key, mine)` 把飞行条目摘掉，下一次同码请求重新开飞。

跟随者分支（第 78 到 99 行）：`existing.get(waitTimeoutMs)` 最多等 500 毫秒（默认值来自 `src/main/java/com/linkforge/config/LinkForgeProperties.java` 第 120 行），超时就自己读库。这叫 fail-open：等不到就降级自干，不让跟随者无限等。

三个设计决定是 `ConcurrentHashMap` 用法的精髓：

1. **`putIfAbsent` 而不是 `computeIfAbsent`。** `computeIfAbsent(key, f)` 语义更顺手："没有就现场算一个放进去"。但按 JDK 8 起 ConcurrentHashMap 的实现（JDK 17 沿用），f 在持有桶锁的状态下执行。这里的 f 是一次几十毫秒的同步 DB 读，放进去等于锁着整个桶读库，同桶的其他无关 key 全被卡住。`putIfAbsent` 的临界区只有一次哈希定位和写入本身，业务逻辑全部在锁外。
2. **两参 `remove(key, value)`。** 它只在"当前值仍等于 mine"时才删。防的竞态在写路径：`src/main/java/com/linkforge/link/service/ShortLinkService.java` 第 285 到 298 行，更新链接前先 `peek(code)` 快照当前飞行，`updateById` 提交后调 `forget(code, priorFlight)` 摘掉旧飞行，免得跳转读到即将过期的旧行。竞态窗口：快照之后旧飞行自己结束了（条目已删），新飞行已经 `putIfAbsent` 了新盒子；这时 `forget` 若用单参 `remove(key)`，会误删新飞行的条目，新飞行进行中到达的请求失去合并。后果据代码推断是去重失效、DB 读放大，而不是死等，因为跟随者手里有盒子引用，仍能等到结果。两参版本保证只摘除自己快照过的那一个飞行。注意一个常见误解：跟随者从不向 map 注册条目（`putIfAbsent` 失败后只等待），所以不存在"误删跟随者"的场景，防护对象始终是"不属于本次操作的飞行"。
3. **这是去重器，不是缓存。** 条目在飞行结束即删，无 TTL、无旧值记忆。空结果（布隆过滤器误判、码已删）只在同一飞行队内共享；持久化的空值缓存在 `ShortLinkService` 的 `linkforge:absent:{code}` 键（Redis，项目决策 B7）；热点 URL 字符串另有 `UrlLocalCache` 的 Caffeine L1。三者职责边界写在 SingleFlight 的 javadoc 里。职责单一的好处是失效分析简单：它只合并"正在发生"的读，不承诺任何历史值。

好处：热码风暴下 DB 读从 N 次降到 1 次。代价：多一层 future 编排与快照协议的复杂度。边界：按进程合并，多实例部署时每个 JVM 各自合并；javadoc 说明了为什么不上分布式锁，它只会串行化读取，不消除读取。

自己验证：读 `run` 全文对照本文三步；并发视角的全景在 [13-concurrency.md](13-concurrency.md)，它在跳转链路里的位置在 [81-linkforge-flows.md](81-linkforge-flows.md)。

## 常见错误与误区

| 错误 | 后果 | 改法 |
|------|------|------|
| 遍历时调集合自己的 `remove` | `ConcurrentModificationException` | `removeIf` 或迭代器 `remove` |
| `Arrays.asList(...)` 后 add/remove | `UnsupportedOperationException` | `new ArrayList<>(...)` 包一层 |
| 没重写 `equals`/`hashCode` 的类做 Map 键 | 键"失踪"，`size` 对但取不出 | 键用 String、record、LocalDate 等不可变类型 |
| 键是可变对象，put 后又改字段 | 同上，按新 hash 找不到旧桶 | 键字段定死，不放可变对象 |
| 并发写 `HashMap` | 丢元素、size 不准 | `ConcurrentHashMap` |
| 只为去重用 `TreeSet`/`TreeMap` | 多付 O(log n) | 去重用 `HashSet`，要排序才用 Tree |
| 大批量插入不传初始容量 | 反复扩容复制 | `new ArrayList<>(n)`；HashMap 传约 `n / 0.75 + 1` |

## 面试追问

**HashMap 的 put 全流程？** 扰动 hash（高 16 位异或低 16 位）到 `(n-1) & hash` 定桶，空桶直插、链表逐个比 hash 与 equals 后尾插、树桶走红黑树插入，命中则覆盖，size 超过容量乘 0.75 触发扩容。追问"扩容为什么 2 倍"：保持 2 的幂，rehash 只看新增的一个 hash 位，元素要么留原位要么去"原位加旧容量"。追问"树化条件"：链长到 8 还要再插，且表长至少 64，否则先扩容。以上是 JDK 17 的 HashMap 实现，JDK 8 起一致。

**ConcurrentHashMap 怎么做到并发安全？** JDK 7 分段锁是历史；JDK 8 起 CAS 占空桶加 `synchronized` 锁非空桶头节点，锁粒度是桶；`get` 无锁靠 volatile 读；size 用 `baseCount` 加 CounterCell 分散计数，弱一致。追问"为什么 key/value 不允许 null"：并发下 get 返回 null 无法区分键不存在与值就是 null。

**fail-fast 的原理与安全删除？** 迭代器创建时记 `modCount` 快照，每次 `next()` 比对，不一致抛 `ConcurrentModificationException`；它是尽力而为的探测器，不是并发保证。追问"怎么安全删"：`removeIf` 或迭代器 `remove`。追问"并发容器迭代为什么不抛"：ConcurrentHashMap 迭代器弱一致，CopyOnWriteArrayList 迭代器是快照。

## 本章总结

- 两大族：`Collection`（List、Set、Queue）装单个元素，`Map` 装键值对；选型先问"怎么查"。
- `ArrayList` 底层数组、扩容 1.5 倍、随机访问 O(1) 靠内存连续；`LinkedList` 几乎不用，栈与队列用 `ArrayDeque`。
- `HashMap`（JDK 17 实现）：扰动 hash、2 的幂容量、负载因子 0.75、扩容按 hash 新增位二分、链长 8 且表长 64 才树化、6 退化。`HashSet` 就是 HashMap 的 key。键必须正确实现 `equals` 与 `hashCode`，且最好不可变。
- `List.of`/`Set.of`/`Map.of` 给出去就不许改；`Arrays.asList` 是定长视图，`set` 还会写回原数组。
- 迭代中改集合抛 `ConcurrentModificationException`，安全删除用 `removeIf`；并发容器迭代弱一致不抛。
- `ConcurrentHashMap` JDK 8 起用 CAS 加桶级 `synchronized`，`get` 无锁；`CopyOnWriteArrayList` 写时复制、读零锁。
- 项目四处用法各代表一个模式：不可变集合定格配置（`TargetPolicy`）、Set 常量与 hash 契约（`LinkValidations`）、Map 聚合补轴（`ClickStatsService`）、并发去重器（`SingleFlight`）。

新代码里怎么判断该不该用某个集合：先问查询方式（按位置、按内容、按键），再问会不会并发访问，再问要不要不可变边界，最后才考虑顺序与排序特性。

## 自测

1. `ArrayList` 的 `get(i)` 为什么是 O(1)？
   答：底层是连续数组，地址 = 首地址 + i × 元素宽度，一条算式直达，不需要遍历。
2. `HashSet` 内部是什么？`add` 一个已存在的元素返回什么？
   答：内部是一个 HashMap，元素当 key，value 是共享的占位对象。add 返回 false，集合不变。
3. `HashMap` 树化为什么要求表长至少 64？退化阈值为什么是 6 不是 8？
   答：小表先扩容更便宜，扩容本身就能摊散链表，树化是哈希退化的兜底。退化阈值定 6 与树化的 8 之间留缓冲，防止桶长在阈值附近反复增删时树与链表来回切换（JDK 17 的 HashMap 实现）。
4. `Arrays.asList` 和 `List.of` 有什么区别？
   答：`Arrays.asList` 返回定长视图，`set` 合法且写回原数组，add/remove 抛异常；`List.of` 完全不可变，任何修改都抛异常，且不接受 null 元素。
5. `SingleFlight` 为什么用 `putIfAbsent` 而不是 `computeIfAbsent`？
   答：`computeIfAbsent` 的函数在桶锁内执行，同步 DB 读会长时间锁住整个桶、卡住同桶无关 key；`putIfAbsent` 的临界区只有 map 操作本身，loader 在锁外执行。
6. 迭代 `ConcurrentHashMap` 时另一个线程 put，会发生什么？
   答：不抛异常（弱一致迭代），结果不保证包含并发期间的修改；要强一致快照就先复制再遍历。

## 下一步

- 线程安全与并发容器全图：[13-concurrency.md](13-concurrency.md)。
- `SingleFlight` 在跳转读路径中的位置：[81-linkforge-flows.md](81-linkforge-flows.md)。
- `linkforge:absent:{code}` 空值缓存的分工：[50-redis.md](50-redis.md)。
- `equals` 与 `hashCode` 契约的完整推导：[java/10-03-equality-and-strings.md](java/10-03-equality-and-strings.md)。
