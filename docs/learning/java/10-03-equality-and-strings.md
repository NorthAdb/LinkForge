# String、相等性与 hashCode：对象"相同"到底指什么

优先级 P0，目标层级 L3（能讲清契约和取舍）。前置：[类型与引用](10-01-types-and-references.md)、[类与多态](10-02-oop.md)。这篇回答三个纠缠在一起的问题：`==` 和 `equals` 的区别从哪来；String 为什么设计成不可变；为什么重写 `equals` 必须同时重写 `hashCode`。

## 从一个问题开始

把两把"看起来一模一样"的钥匙放进 HashMap，一把能取回值，另一把取回 null：

```java
public class Mystery {
    static class Key {
        private final String value;

        Key(String value) {
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key)) {
                return false;
            }
            Key other = (Key) o;
            return value.equals(other.value);
        }
    }

    public static void main(String[] args) {
        java.util.Map<Key, String> map = new java.util.HashMap<>();
        map.put(new Key("a1"), "first");
        System.out.println(map.get(new Key("a1")));
        System.out.println(map.size());
    }
}
```

```text
输出（示意）：
null
2
```

注意上面标了"示意"。放进去一把键，`size()` 却是 2，说明 HashMap 认为存进去的是**两把不同的键**。但这个输出并不稳定：你跑一遍可能得到 `first` 和 `1`，因为两个内容相同的对象有没有恰好落进同一个桶，取决于默认 hashCode 给它们分配的编号运气。**这个不确定性正是问题本身**：重写 `equals` 却保留默认 `hashCode`，"两个相等的键算不算同一个键"就变成了概率事件，代码的行为不可预测。原因藏在另一个方法里：这个类**没有重写 `hashCode`**。读完这篇，你能完整解释这个现象，并能预测任何"对象当键"的代码行为。

## 先分清两件事：同一对象 与 内容相同

[类型与引用](10-01-types-and-references.md)讲过，`==` 对引用类型比较"是否指向同一个对象"。但很多时候我们想问的不是"是不是同一个对象"，而是"内容是否相同"。两个 `new` 出来的 String `"ab"`，内容一样，对象不同。

Java 把这两个问题分给两个操作：`==` 问同一性（identity），`equals` 方法问相等性（equality）。`equals` 定义在所有类的祖先 `Object` 里，默认实现就是 `==`：

```java
public boolean equals(Object obj) {
    return (this == obj);
}
```

也就是说，一个类不重写 `equals`，它的"相等"就只有一种：同一个对象。String、Integer 等标准库类都重写了它，按内容比较。String 先看：

```java
public class StringEq {
    public static void main(String[] args) {
        String a = "ab";
        String b = "ab";
        String c = new String("ab");
        System.out.println(a == b);
        System.out.println(a.equals(c));
        System.out.println(a == c);
    }
}
```

```text
true
true
false
```

- `a == b` 是 true，两个写法相同的字面量竟然是同一个对象。这不是巧合，是**字符串池（string pool）**在起作用，下面马上讲。
- `a.equals(c)` 是 true，内容相等。
- `a == c` 是 false，`new` 一定创建新对象，`c` 指向堆里另一个 String。

规则一句话：**String 比内容永远用 `equals`，`==` 只在两种场合有意义——故意判断"是不是同一对象"（比如 `null` 检查 `s == null`），以及明确知道两边都来自字符串池的比较**。第二种场合太少见，当成"内容比较一律 equals"即可。

`equals` 参数类型是 `Object`，所以任何对象都能传进来，类型不对就返回 false，不会报错。`"ab".equals(null)` 返回 false。反过来 `null.equals("ab")` 是 NPE，所以有一种防御写法是把常量放在前面调用：`"ab".equals(x)`。

## 字符串池：为什么字面量是同一个对象

字符串是程序里出现最频繁的数据。如果每写一个 `"ab"` 就在堆上造一个新对象，同样的字面量会重复占用大量内存。JVM 的做法是：**类加载时，把代码里出现的每个字符串字面量放进一个共享的池子；再次遇到相同内容的字面量，直接复用池里已有的对象**。所以 `a` 和 `b` 拿到的是同一个对象的引用，`==` 为 true。

这个机制成立的前提恰恰是 String **不可变**。假如 String 可变，A 代码拿到池里的 `"ab"` 改成 `"xy"`，所有持有这个字面量的代码全部被暗中篡改，字符串池就变成全局污染源。不可变对象可以放心共享，这是 String 被设计成不可变的第一个原因。

**不可变（immutable）**指对象创建之后，它的内容永远不变。String 的实现方式：内容存在一个 `final` 字段引用的内部数组里（Java 9 起是 `byte[]`，早期版本是 `char[]`），这个数组没有任何对外暴露的写入口。所有看起来"修改"String 的方法，`replace`、`toUpperCase`、`substring`，返回的都是**新对象**：

```java
public class Immut {
    public static void main(String[] args) {
        String s = "hello";
        s.replace("l", "L");
        System.out.println(s);
        String t = s.replace("l", "L");
        System.out.println(t);
        s = s.replace("l", "L");
        System.out.println(s);
    }
}
```

```text
hello
heLLo
heLLo
```

第一行 `s.replace(...)` 的返回值被丢掉了，`s` 纹丝不动，还是 hello。必须用 `s = s.replace(...)` 接住新对象。"String 方法不生效"是初学阶段的经典 bug，根源就在没理解"修改"其实产生了新对象。

不可变带来的全部好处，串起来是四条：

1. **字符串池可以共享**，如上。
2. **hashCode 可以缓存**。hashCode 在对象生命周期里会被 HashMap 反复取用，String 第一次计算后存起来复用。内容不可变，hashCode 才可能恒定。
3. **作为 HashMap 的键是安全的**。键进 map 后内容不会被别人改掉，后面细讲。
4. **天然线程安全**。多个线程读同一个 String，永远读到同样的内容，不存在竞争。[并发](../13-concurrency.md)篇会解释为什么"只读"就没有并发问题。

代价也要说清楚：任何"修改"都是复制。循环里反复拼接字符串会不断创建新对象、复制旧内容，代价随长度增长，解法见本文末尾的 StringBuilder。

## equals 与 hashCode 的契约

回到开头的 Mystery。HashMap 不是把所有键值对平铺存放的，它先用 `hashCode()` 把键**分组**（术语叫分桶），同一个桶内再用 `equals` 逐个确认。所以一次 `get` 的完整过程是：

1. 调用键对象的 `hashCode()` 得到一个 int，决定去哪个桶找。
2. 在那个桶里用 `equals` 找到内容相同的键。
3. 返回它挂着的值。

现在 Mystery 的现象完全可解释：Key 类重写了 `equals` 但没重写 `hashCode`，于是用的还是 `Object` 的默认 `hashCode`。默认实现给每个对象一个基本不重复的"身份编号"（JLS 没规定它怎么来，你不能当它是内存地址，只能当它是同一对象内恒定、不同对象几乎不同的值）。两个内容相同的 Key 对象，身份编号不同，被分进不同桶。`get(new Key("a1"))` 在新对象的桶里找，找不到任何东西，返回 null；`put` 的第二个 Key 也因为进了不同的桶没触发"键已存在"，于是 size 变成 2。

修复方法是同时重写 `hashCode`，让它从和 equals 相同的字段算出来：

```java
public class EqFixed {
    static class Key {
        private final String value;

        Key(String value) {
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key)) {
                return false;
            }
            Key other = (Key) o;
            return value.equals(other.value);
        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }
    }

    public static void main(String[] args) {
        java.util.Map<Key, String> map = new java.util.HashMap<>();
        map.put(new Key("a1"), "first");
        System.out.println(map.get(new Key("a1")));
        map.put(new Key("a1"), "second");
        System.out.println(map.size());
    }
}
```

```text
first
1
```

修好了：能取回值，重复 put 覆盖旧值，size 保持 1。完整契约是两条，都来自 `Object.hashCode` 的规范：

1. **equals 相等的两个对象，hashCode 必须相等**。这是硬性要求，违反它 HashSet/HashMap 直接算错（Mystery 就是违反它的下场）。
2. **hashCode 相等的两个对象，equals 不必相等**。这叫哈希冲突，是完全允许的正常现象，HashMap 用桶+链表结构处理它，只是查找会慢一点。所以 hashCode 返回常量 1 不违反契约，只是所有对象挤进一个桶，性能退化成逐个 equals。

实现 hashCode 的可靠姿势：用参与 equals 的**同一组字段**计算。IntelliJ 的生成器、Lombok 的 `@EqualsAndHashCode`、以及下一系列的 `record`（见 [Java 17 新语法](10-10-java17.md)）生成的都是合规实现。手写时最常见的事故是 equals 用了 5 个字段、hashCode 只用了 2 个，两个对象 equals 相等但 hashCode 不同，直接违反第一条。

### 反例：把可变对象当键

还有一个更隐蔽的坑：**键对象放进 map 之后又被修改了**。

```java
public class MutableKey {
    static class Bag {
        int v;

        Bag(int v) {
            this.v = v;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Bag && ((Bag) o).v == v;
        }

        @Override
        public int hashCode() {
            return v;
        }
    }

    public static void main(String[] args) {
        java.util.Map<Bag, String> map = new java.util.HashMap<>();
        Bag key = new Bag(1);
        map.put(key, "x");
        key.v = 2;
        System.out.println(map.get(new Bag(1)));
        System.out.println(map.get(new Bag(2)));
        System.out.println(map.containsKey(key));
    }
}
```

```text
null
null
false
```

三个查询全部落空。过程：put 时按 `v=1` 算哈希进了 1 号桶；之后 `key.v = 2` 改了对象内容；再查时按当前值算哈希，去 2 号桶找，可键值对实际挂在 1 号桶里。**键从此"查无此人"，而且没有任何报错**。这就是为什么 JDK 文档强烈建议键用不可变对象：String、包装类型、record 都不可变，天然免疫。如果必须用可变对象，规矩是"放进集合后不再修改参与 equals/hashCode 的字段"。

## 把相等性用对：先归一化，再比较

equals 是大小写敏感的：`"Api".equals("api")` 是 false。所以"内容相等"前常常要先**归一化**（normalize），把不同写法折叠成同一种写法。LinkForge 里有现成的两段。

第一段，`src/main/java/com/linkforge/link/support/LinkValidations.java`，校验自定义短码不能占用保留字：

```java
private static final Set<String> RESERVED_CODES = Set.of(
        "actuator", "health", "auth", "login", "register", "links", ...);

public static void requireValidCustomCode(String code) {
    if (!CUSTOM_CODE_PATTERN.matcher(code).matches()) {
        throw new BizException("customCode must be 4-16 chars of letters, digits, underscore or hyphen");
    }
    if (RESERVED_CODES.contains(code.toLowerCase())) {
        throw new BizException("customCode is reserved");
    }
}
```

`RESERVED_CODES` 是 `Set.of(...)` 建的不可变集合，`contains` 内部就是"先 hashCode 定位、再 equals 确认"（和 HashMap 同一套机制，HashSet 本来就是空值的 HashMap）。用户输入 `API`，`code.toLowerCase()` 先归一化成 `api`，`equals` 才能命中。**先归一化再比较**是字符串相等性的固定套路：比较的双方必须处于同一种规范形态，equals 才表达你想问的问题。漏掉 `toLowerCase()`，用户注册个 `LOGIN` 就绕过了保留字检查。

第二段，同目录 `TargetPolicy.java` 的域名匹配（B9 钓鱼域拦截）：

```java
private static boolean matchesAny(String host, Set<String> entries) {
    for (String entry : entries) {
        if (host.equals(entry) || host.endsWith("." + entry)) {
            return true;
        }
    }
    return false;
}
```

`host.equals(entry)` 处理精确匹配；`host.endsWith("." + entry)` 处理子域：`a.evil.com` 以 `.evil.com` 结尾所以命中。注意前缀里那个点是刻意的：没有点的话 `notevil.com` 也会以 `evil.com` 结尾而误伤。这里的 host 在更上游已经过小写化和国际化域名（IDN）归一化（`normalizeEntry` 方法），保证 punycode 形式（`xn--...`）和中文域名形式能命中同一个名单条目。调用方传入的集合和 URL 双方都被归一化过，equals 才可靠，这和 javadoc 里"Both sets hold normalized hosts"的注释是同一件事。

还有一段真实代码能说明不可变设计在工程里的位置：`src/main/java/com/linkforge/link/support/GatePages.java` 的门禁页对动态值统一先过 `HtmlUtils.htmlEscape(targetUrl)` 再拼进 HTML。htmlEscape 不修改传入的字符串（它做不到，String 不可变），而是**返回新字符串**。不可变模型下"清洗数据"天然是"产出一个新的、干净的对象"，原对象原封不动，不会污染其他持有方。

## 循环拼接：O(n²) 的代价

最后把"修改字符串就是复制"落到性能上。循环里写 `text = text + line;` 会发生什么？每一轮都创建一个 StringBuilder、把旧内容整个复制进去、追加一行、再转回 String。内容越长复制越贵，n 轮总复制量是平方级的。解法是**在循环外建一个 StringBuilder，循环内只 append**，它内部维护一个可扩容的数组，追加是摊销 O(1)：

```java
public class SbDemo {
    public static void main(String[] args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            sb.append("row").append(i).append('\n');
        }
        System.out.print(sb);
        System.out.println(sb.length());
    }
}
```

```text
row1
row2
row3
row4
row5
25
```

`append` 返回 `this`（StringBuilder 自己），所以能连着写。`sb.length()` 是 25：每行 `rowN` 加一个换行符 5 个字符，五行共 25。日常的 `"a" + b + c` 少量拼接完全没问题，编译器本来就翻译成一次 StringBuilder 调用；**只有循环体里的 `+=` 必须手动提出来**。

项目里真实用例，`src/main/java/com/linkforge/link/service/ShortLinkService.java` 的 CSV 导出：

```java
public String exportOwnCsv() {
    AuthUser user = requireUser();
    StringBuilder sb = new StringBuilder("\uFEFFcode,original_url,title,status,expire_at,created_at\n");
    int exported = 0;
    long page = 1;
    while (exported < 10000) {
        PageResponse<ShortLinkDetail> chunk = pageOwn(user.getUserId(), page++, 500);
        for (ShortLinkDetail link : chunk.getRecords()) {
            sb.append(csvRow(link));
            exported++;
        }
        ...
    }
    return sb.toString();
}
```

导出最多一万行。开头那个 `"\uFEFF"` 是 BOM 字符，让 Excel 按 UTF-8 打开，属于 CSV 的工程细节。这里用 StringBuilder 的原因正是循环：一万次 append 只维护一个缓冲区。最后 `toString()` 一次性产出不可变的 String 对外返回，顺便把"内部缓冲区从此不会再被改"这个保证交给了调用方。

## 常见错误清单

| 错误 | 实际发生什么 | 修正 |
|------|-------------|------|
| 用 `==` 比较 String 内容 | 字面量恰好同池才 true，运行期拼出来就 false | 内容比较一律 `equals` |
| 只重写 `equals` 不重写 `hashCode` | 对象进 HashMap/HashSet 后找不到 | 两个字段组一致，成对重写 |
| equals 参数写成自己的类型 `equals(Key o)` | 不再重写 Object.equals，多态分派失效 | 签名必须是 `equals(Object o)` |
| 可变对象当 map 键且放入后修改 | 键查无此人，无任何报错 | 键用不可变对象 |
| `s.replace(...)` 不接返回值 | 原字符串纹丝不动 | `s = s.replace(...)` |
| 循环里 `text += x` | 每轮新建缓冲区整体复制，平方级 | 循环外 StringBuilder，循环内 append |
| 归一化前后不一致 | `API` 匹配不上 `api`，大小写绕过检查 | 比较双方过同一条归一化管道 |

第二行的"参数写错类型"值得单独解释一下。`equals` 的重写要求签名和 `Object.equals(Object)` 完全一致。写成 `public boolean equals(Key o)` 时，那是一个**重载**而不是重写：`Object.equals` 还是默认的 `==`，HashMap 走的恰恰是 `Object.equals`，于是 equals 自认为生效了、集合里却不生效，极难排查。`@Override` 注解能当场拦住这个错误（见[类与多态](10-02-oop.md)），这也是"重写必标 `@Override`"的另一个理由。

## 面试追问

**第一层：String 为什么设计成不可变？**
四个收益：字符串池安全共享、hashCode 可缓存、做 HashMap 键稳定、天然线程安全。代价是修改即复制，高频拼接需要 StringBuilder。这是"用不可变性换取共享安全性"的典型设计。

**第二层：字符串池在哪里，`intern()` 是什么？**
池是 JVM 里存放字符串字面量引用的去重结构（HotSpot 从 7 起放在堆里）。字面量自动入池；运行期构造的字符串调 `intern()` 可手动入池并返回池内引用。业务代码几乎不需要手动 intern，知道字面量去重机制即可。

**第三层：为什么重写 equals 必须重写 hashCode？说违反后的具体后果。**
HashMap 查找分两步：先按 hashCode 定位桶，再在桶内 equals。equals 相等而 hashCode 不同会落进不同桶，get 找不到、put 不去重。举例：两个内容相同的 Key，get 返回 null、size 翻倍。

**第四层：hashCode 返回常量 1 违反契约吗？**
不违反。契约只要求 equals 相等则 hashCode 相等，常量 1 满足。代价是全部对象一个桶，查找退化为线性 equals，是"合法但性能灾难"的边界案例。

**第五层：哪些类型适合当 HashMap 的键？为什么？**
不可变且 equals/hashCode 合规的类型：String、包装类型、record、枚举。不可变保证放入后 hashCode 恒定；枚举额外有 EnumMap 这类针对小范围键的专用实现。

## 自测

1. `String a = "ab"; String b = "a" + "b";` 问 `a == b`，为什么？
   答：true。`"a" + "b"` 是编译期常量表达式，编译时折叠成字面量 `"ab"`，与 `a` 同池同对象。
2. `new String("ab")` 创建了几个对象？
   答：至少一个新堆对象（字面量 `"ab"` 若池中已有则复用）。这也是"new 一律产生新对象"的体现。
3. equals 相等、hashCode 不相等，HashMap 会出什么现象？
   答：两个"相等"的键进不同桶，get 返回 null，put 不去重，size 偏大。
4. 为什么键必须是不可变对象？
   答：放入后若参与 hashCode 的字段被改，键所在桶与当前哈希不再对应，键查无此人且无报错。
5. `"hello"` 执行 `toUpperCase()` 后原对象变了吗？
   答：没变，返回的是新对象；不接返回值等于什么都没做。

## 下一步

- 基本类型没有 null，包装类型有，`==` 与缓存的更多坑：[包装类型与自动拆箱](10-04-wrappers-and-boxing.md)。
- record 一行顶你手写的 equals/hashCode/toString：[Java 17 新语法](10-10-java17.md)。
- Set/Map 内部如何用这套契约组织数据：[集合框架](../11-java-collections.md)。
