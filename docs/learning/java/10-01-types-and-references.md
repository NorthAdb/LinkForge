# 类型与引用：变量里到底装的是什么

优先级 P0。这篇是整个 Java 系列的地基，后续每一篇都建立在它上面。

## 从一个问题开始

先看两段几乎一样的代码。

第一段，两个 `int`：

```java
public class Puzzle1 {
    public static void main(String[] args) {
        int a = 10;
        int b = a;
        b = 20;
        System.out.println(a);
    }
}
```

```text
10
```

第二段，两个数组：

```java
public class Puzzle2 {
    public static void main(String[] args) {
        int[] box1 = {1, 2, 3};
        int[] box2 = box1;
        box2[0] = 99;
        System.out.println(box1[0]);
    }
}
```

```text
99
```

两段代码都是"复制一个变量，然后修改新变量"。第一段里 `a` 完全不受影响，输出 10。第二段里 `box1` 却被改掉了，输出 99。

区别不在赋值语句，赋值语句永远是"把右边复制给左边"。区别在于**复制的东西不一样**：`int` 变量里装的就是数值本身，复制的是数值；数组变量里装的不是数组本身，复制的是另一个东西。这篇文档就讲清楚这个"另一个东西"是什么，以及它引发的一系列现象：两个变量为什么会"共享"一个对象、`null` 是什么、为什么方法里改参数有时生效有时不生效、`==` 到底比的是什么。

## 变量、值、类型

程序要记住数据，就要在内存里划出一块地方存它，再给这块地方起个名字，这个名字就是**变量**（variable）。

```java
int age = 25;
```

这一行做了三件事：申请一块能装下一个 `int` 的内存；把 25 写进去；把这块内存和名字 `age` 绑定。之后每次读写 `age`，访问的都是这块内存。

变量有**类型**（type）。类型决定两件事：这块内存多大，里面的二进制按什么规则解读。`int` 固定 32 位，按补码整数解读，范围是 -2147483648 到 2147483647。同样 32 位的 `float` 就按浮点数规则解读。类型不对，读出来的值就毫无意义，所以编译器从第一行起就盯着每个变量的类型。

Java 把类型分成两大类。这个分类是理解后面一切的关键。

**基本类型**（primitive type）只有 8 种，变量里直接装数值本身：

| 类型 | 大小 | 装什么 | 字面量示例 |
|------|------|--------|-----------|
| `byte` | 8 位 | 整数，-128 到 127 | `byte b = 100;` |
| `short` | 16 位 | 整数 | `short s = 30000;` |
| `int` | 32 位 | 整数，日常默认 | `int n = 100000;` |
| `long` | 64 位 | 大整数，字面量加 `L` | `long id = 123456789012L;` |
| `float` | 32 位 | 单精度浮点，字面量加 `F` | `float f = 1.5F;` |
| `double` | 64 位 | 双精度浮点，默认 | `double d = 3.14;` |
| `char` | 16 位 | 一个 UTF-16 字符 | `char c = 'A';` |
| `boolean` | 未规定大小 | 只有 `true` 和 `false` | `boolean ok = true;` |

**引用类型**（reference type）是其余所有类型：数组、`String`、你自己写的类、标准库里的类，都是。引用类型的变量里装的不是对象，是"找到这个对象的线索"，详见下一节。

先强调一个本篇反复出现的结论：**赋值永远是复制变量里的内容**。基本类型变量装的是值，所以复制值。引用类型变量装的是引用，所以复制引用。两段 Puzzle 代码的差别，全部源于此。

## 引用类型：变量装的是"通往对象的线索"

看这一行：

```java
int[] box1 = {1, 2, 3};
```

`new`（这里是 `{1, 2, 3}` 的简写形式）在**堆**（heap）上创建了一个数组对象。堆是 JVM 管理的一整块内存，所有对象都住在这里，可以粗略理解为"一个大仓库"。变量 `box1` 本身不住在堆里，它装的是一个**引用**（reference）：一个指向仓库里那个数组对象位置的值。

你可以把引用想象成**门牌号**：对象是房间，变量是写着房间号的小纸条。把 `box1` 赋给 `box2`，复制的不是房间，是纸条上抄了一份房间号。于是两张纸条指向同一个房间，从任何一张纸条进去改房间里的东西，从另一张纸条看都是改过的。这就是 Puzzle2 输出 99 的完整解释。

一步一步拆：

1. `{1, 2, 3}` 创建数组对象，假设它在堆里的位置记为 #1001。
2. `int[] box1 = ...` 把 #1001 写进变量 `box1`。
3. `int[] box2 = box1` 把 `box1` 里的 #1001 复制进 `box2`。现在有两个变量、一个对象。
4. `box2[0] = 99` 沿着 `box2` 里的 #1001 找到那个唯一数组，把第 0 个元素改成 99。
5. 读 `box1[0]` 时沿着同一个 #1001 找到同一个数组，自然是 99。

### 这个比喻的边界

"门牌号"比喻好用，但有三处会在后面误导你，现在就说清楚。

第一，Java 引用**不是** C 语言里能运算的指针。你不能对引用做加减、不能读出它的数值、不能把它转成整数看"地址是多少"。引用是一个只有 JVM 能解读的值，把它打印出来只会得到 `@[类型]@[一串字符]` 这类格式化结果（比如 `[I@1b6d3586`），这串字符不承诺任何语义，不同 JVM 还不一样。所以面试里说"引用就是内存地址"只在"它指向对象"这个层面成立，你不能像 C 程序员那样拿地址做任何事。

第二，对象是会被 JVM 搬家的。垃圾回收器整理内存时会移动对象，引用跟着更新。你在语言层面完全感知不到，这正是 JVM 设计引用的目的：给你"指向对象"的能力，把地址管理的脏活全部收走。

第三，"堆"这个说法要分清来源。Java 语言规范只规定了基本类型按值、引用按"指向对象"的语义；"局部变量在栈上、对象在堆上"是 HotSpot 这类主流 JVM 的具体实现方式，是事实但不是规范条文。初学阶段按"对象在堆上"理解完全没问题，只是要知道这是实现细节。

## 引用只装"线索"，也可能什么都不指：null

引用变量可以赋一个特殊值 `null`，表示"不指向任何对象"。一张空白纸条。

```java
int[] box = null;
```

问题在读的时候：你拿着一张空白纸条进仓库找东西，找不到任何房间。运行时发生这件事就是 `NullPointerException`（常缩写 NPE），它是 Java 里最常见的异常，没有之一。

```java
public class NpeDemo {
    public static void main(String[] args) {
        int[] box = null;
        System.out.println(box.length);
    }
}
```

```text
运行异常：
java.lang.NullPointerException
at NpeDemo.main(NpeDemo.java:4)
```

读这个堆栈：第一行是异常类型，意思是"对一个 null 引用做了操作"。第三行 `at NpeDemo.main(NpeDemo.java:4)` 是出事的位置，NpeDemo 类的 main 方法的第 4 行，正是 `box.length`。堆栈从下往上是调用顺序，这里只有一层。预防和定位 NPE 是后面好几篇的主题（[包装类型与拆箱](10-04-wrappers-and-boxing.md)、[异常](10-06-exceptions.md)）。

现在先记住一条铁律：**对值为 null 的引用做任何"进入对象"的操作（读字段、调方法、取数组元素）都会抛 NPE**。而给引用重新赋值（`box = new int[3];`）永远不抛，因为那只是在改纸条。

## 方法与参数传递：Java 只有值传递

现在把上面的模型带到方法调用里。这是引用问题里最容易错的地方。

先看基本类型。方法参数就是把实参的值复制给形参，形参是方法自己的局部变量，改它不影响外面：

```java
public class SwapFail {
    static void swap(int x, int y) {
        int tmp = x;
        x = y;
        y = tmp;
    }

    public static void main(String[] args) {
        int a = 1;
        int b = 2;
        swap(a, b);
        System.out.println(a + " " + b);
    }
}
```

```text
1 2
```

`swap` 内部确实交换了，但交换的是 `x`、`y` 这两块方法私有的内存。`a`、`b` 从头到尾没被碰到。想让外部看到交换结果，只能把新值返回出来重新赋值。

引用类型的参数也走同样的复制，复制的是引用。这产生两个看起来矛盾的的现象，其实是同一条规则。

现象一：方法内**修改**参数指向的对象，外面看得见：

```java
public class MutateVisible {
    static void setFirst(int[] arr) {
        arr[0] = 100;
    }

    public static void main(String[] args) {
        int[] nums = {1, 2, 3};
        setFirst(nums);
        System.out.println(nums[0]);
    }
}
```

```text
100
```

过程：`nums` 里存着 #1001，调用时把 #1001 复制给 `arr`。`arr[0] = 100` 沿着 #1001 改了那个唯一的对象。方法结束后 `arr` 消失，但对象上的修改留下来了。`nums` 去看，当然是 100。

现象二：方法内给参数**重新赋值**，外面看不见：

```java
public class ReplaceInvisible {
    static void replace(int[] arr) {
        arr = new int[] {7, 8, 9};
    }

    public static void main(String[] args) {
        int[] nums = {1, 2, 3};
        replace(nums);
        System.out.println(nums[0]);
    }
}
```

```text
1
```

过程：#1001 复制给 `arr`，然后 `arr = new int[]...` 创建新对象 #2002，把 `arr` 改指向它。改的只是方法自己的那张纸条，`nums` 里的 #1001 纹丝不动。

两个现象合并成一句话：**参数是引用的拷贝，"沿引用改对象"对外可见，"重新赋引用"对外不可见**。面试标准说法是"Java 只有值传递"，值的具体内容对于引用类型就是引用本身。C++ 有引用传递（`int&`），C# 有 `ref`，Java 没有。讨论"Java 是值传递还是引用传递"时，能说出"传引用类型时复制的是引用这个值"才算答完整。

`String` 是最能检验这句话的例子。字符串看着"传进去改一下"很合理，但 `String` 对象一旦创建内容就不可变（为什么，[String 与相等性](10-03-equality-and-strings.md)细讲），方法里只能重新赋值，于是永远走现象二：

```java
public class StringArg {
    static void addBang(String s) {
        s = s + "!";
    }

    public static void main(String[] args) {
        String greeting = "hello";
        addBang(greeting);
        System.out.println(greeting);
    }
}
```

```text
hello
```

`s + "!"` 造了一个新对象 `"hello!"`，让方法私有的纸条 `s` 指向它。`greeting` 还指着原来的 `"hello"`。

## == 的两副面孔

`==` 对两类类型做的事完全不同，这是初学者 Bug 高发区。

对基本类型，`==` 比较值。对引用类型，`==` 比较**两个引用是否指向同一个对象**，不比较对象内容。

```java
public class EqDemo {
    public static void main(String[] args) {
        int a = 1000;
        int b = 1000;
        System.out.println(a == b);

        int[] x = {1};
        int[] y = {1};
        System.out.println(x == y);

        int[] z = x;
        System.out.println(x == z);
    }
}
```

```text
true
false
true
```

- `a == b`：两个变量各存一份 1000，值相等，true。
- `x == y`：`new` 出了两个不同对象，#1001 和 #2002，内容一样但不是同一个房间，false。
- `x == z`：`z` 是抄来的同一张纸条，同一个对象，true。

"内容相等"的判断要交给 `equals` 方法，那是 [String 与相等性](10-03-equality-and-strings.md)的主题。这里先立一条纪律：**引用类型（尤其 String 和包装类型）比较内容一律用** `equals`**，**`==` **只用来问"是不是同一个对象"**。

## char：一个容易被当成字符串的单字符类型

`char` 是 16 位无符号整数，装一个 UTF-16 代码单元，字面量用单引号。它本质上是数字，所以能比较大小：

```java
public class CharDemo {
    static boolean hasControlChar(String url) {
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return true;
            }
        }
        return false;
    }

    public static void main(String[] args) {
        System.out.println(hasControlChar("https://linkforge.dev/a1"));
        System.out.println(hasControlChar("bad\nurl"));
    }
}
```

```text
false
true
```

`url.charAt(i)` 取出下标 i 处的 `char`。`0x20` 是 32，ASCII 表里 32 之前是换行、制表符这类控制字符，`0x7f` 是 DEL。第二个字符串里的 `\n` 是换行符，数值 10，小于 32，所以返回 true。这段逻辑来自项目里真实的方法（下一节细讲）。

注意 `char` 和 `String` 的区别：`char c = 'A'` 是一个 16 位数值；`String s = "A"` 是一个不可变对象，里面装着至少一个字符。两者不能直接互转赋值，`charAt` 返回的是前者。

`boolean` 只有 `true` 和 `false` 两个值，不能和数字互转。`if (1)` 这种 C 写法在 Java 里直接编译错误。

## final：给变量上锁

`final` 修饰局部变量或参数，意思是这个变量赋值之后不能再指向别处：

```java
final int[] arr = {1};
arr[0] = 5;
arr = new int[] {2};
```

第三行编译失败，报 `cannot assign a value to final variable arr`。注意第二行没问题：`final` 锁的是"纸条不能再写别的房间号"，**不锁房间里的家具**。对象内容仍可改。这条区别在讲不可变对象（[String 与相等性](10-03-equality-and-strings.md)）时还会出现。

## LinkForge 里的真实代码

上面每一个机制都能在项目里找到正在工作的实例。

### 基本类型运算：LinkValidations.hasControlChar

你刚才在 `CharDemo` 里看到的就是它的原型，真实代码在 `src/main/java/com/linkforge/link/support/LinkValidations.java`：

```java
private static boolean hasControlChar(String url) {
    for (int i = 0; i < url.length(); i++) {
        char c = url.charAt(i);
        if (c < 0x20 || c == 0x7f) {
            return true;
        }
    }
    return false;
}
```

它防的是攻击者把控制字符（比如换行）塞进目标 URL，用来欺骗日志或下游系统。`String url` 是引用，传进来的开销只是复制一个引用，不复制字符串内容；循环里每个 `char c` 是基本类型，赋值即复制，`c` 怎么变都不影响原字符串（也影响不了，String 根本不可变）。这是一段把"引用进、基本类型出、逐个复制"用得干干净净的代码。

### 基本类型参数：ShortLinkService.setStatus

`src/main/java/com/linkforge/link/service/ShortLinkService.java` 里有：

```java
private void setStatus(String code, int status) {
    ShortLink link = getOwnedLink(code);
    link.setStatus(status);
    ...
}
```

参数 `status` 是 `int`。调用方写 `setStatus(code, 0)` 停用、`setStatus(code, 1)` 启用。因为 `int` 是值传递，方法内对 `status` 的任何赋值都不可能传回去，所以这个方法靠**副作用**（修改 `link` 对象并写库）向外传递结果，而不是靠参数。反过来，如果这里把 `status` 声明成 `int status` 的包装类型 `Integer`，调用方传 `null` 时 `link.setStatus(status)` 会触发自动拆箱抛 NPE，这是 [包装类型](10-04-wrappers-and-boxing.md)要展开的坑。

### 一个对象、多处引用：TargetPolicy.Config.EMPTY

`src/main/java/com/linkforge/link/support/TargetPolicy.java` 里有一个类常量：

```java
public record Config(Set<String> blockDomains, Set<String> warnDomains) {
    public static final Config EMPTY = new Config(Set.of(), Set.of());
}
```

`EMPTY` 是 `static final` 的：`static` 表示它属于 `Config` 这个类本身而不是某个实例，整个 JVM 只初始化一次；`final` 表示这个变量不能改指向。于是全项目任何地方拿到 `EMPTY`，拿到的都是对**同一个对象**的引用，`==` 比较任何两个持有方都返回 true。这是"一个对象、许多引用"的刻意用法：常量只建一份，大家共享。代价是：只要 `EMPTY` 指向的对象是可变的，任何一个人改了它，所有人都"看到"被改。所以 `Set.of()` 创建的是不可修改集合，从根上杜绝了这种事故。可变性控制是共享的前提，这个主题在 [String 与相等性](10-03-equality-and-strings.md)和 [集合](../11-java-collections.md)里继续。

## 常见错误清单

| 错误 | 实际发生什么 | 修正 |
|------|-------------|------|
| 以为方法能通过参数交换外部变量 | 参数是复制品，交换只发生在方法内 | 返回新值或用返回对象承载结果 |
| 在方法里给参数 `new` 一个新对象，期望外面看到 | 重新赋值只改方法私有那张"纸条" | 返回新对象，调用方接收 |
| 用 `==` 比较 String 或数组内容 | 比的是"是否同一对象"，恰好同对象才 true | 内容比较用 `equals` |
| 对可能为 null 的引用直接取元素 | NullPointerException | 先判 null，或保证来源不返回 null |
| 以为 `final` 让对象不可变 | `final` 只锁引用不改指向，对象内容还能改 | 不可变要靠不可变类（String、record） |

## 面试追问

**第一层：Java 的参数传递是什么机制？**
只有值传递。基本类型复制值，引用类型复制引用（引用也是值）。没有引用传递。

**第二层：那为什么方法里改对象属性能影响外面？**
因为复制的引用指向同一个对象。方法内沿引用修改对象，修改发生在这个唯一的对象上，任何持有它的变量再去读都能看到。

**第三层：那怎么解释方法里** `arr = new int[...]` **后外面没变？**
重新赋值改的是方法私有的那份引用拷贝，不影响调用方的引用。可见与不可见的分界线是"改对象"还是"改引用"。

**第四层：这个机制带来什么设计约束？**
想通过参数"带出"结果，要么用返回值，要么传入一个容器对象往里塞。JDK 自己的选择是返回值优先，例如 `Optional`、各种 `computeIfAbsent` 返回值。这也是为什么不可变对象（String、record）能放心共享：它们根本没有"沿引用改对象"这条路。

## 自测

1. `int[] a = {1}; int[] b = a; b[0] = 2;` 之后 `a[0]` 是多少？

   答：2。`a`、`b` 存的是同一个对象的引用，改的是同一个数组。
2. `String s = "hi";` 里 `s` 变量本身存的是什么？

   答：一个指向堆上 String 对象的引用，不是字符串内容本身。
3. 方法参数 `void f(StringBuilder sb)` 里执行 `sb.append("x")` 和 `sb = new StringBuilder()`，对外的影响分别是什么？

   答：append 沿引用改了外部那个对象，外部可见；重新赋值只改方法内拷贝，外部不可见。
4. `==` 对基本类型和引用类型分别比较什么？

   答：基本类型比值，引用类型比是否指向同一个对象。
5. `final int[] arr = {1};` 之后执行 `arr[0] = 9;` 合法吗？`arr = new int[2];` 呢？

   答：前者合法，`final` 不锁对象内容；后者编译错误，`final` 变量不能再赋值。

## 下一步

- 变量装引用这个模型，正是面向对象的入口：[类、封装、继承与多态](10-02-oop.md)。
- 引用比较 `==` 与内容比较 `equals` 的完整规则：[String 与相等性](10-03-equality-and-strings.md)。
- 基本类型没有 null，包装类型有，这组差异是 NPE 重灾区：[包装类型与自动拆箱](10-04-wrappers-and-boxing.md)。
