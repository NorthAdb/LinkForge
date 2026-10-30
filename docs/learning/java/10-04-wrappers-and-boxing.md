# 包装类型、自动装箱与拆箱：当"没有值"也要能装

优先级 P0。前置：[类型与引用](10-01-types-and-references.md)。这篇讲基本类型的"对象化身"——8 个包装类，以及它们带来的两个著名陷阱：缓存区间和拆箱空指针。

## 从一个问题开始

数据库里的短链表有一列 `expire_at`（过期时间）。大部分链接永不过期，这一列是 NULL；少数链接设置了过期时间。Java 侧对应的实体类 `ShortLink`（`src/main/java/com/linkforge/link/entity/ShortLink.java`）里这行长这样：

```java
private LocalDateTime expireAt;
```

`LocalDateTime` 是引用类型，变量可以装 null，null 正好表达"这一列没有值"。现在换个字段想想：如果它是 `long`（基本类型），null 往哪放？`long` 只有 64 位的数，"没有值"不是一个数。用 0 当"没有"？可 0 也可能是一个合法的时间戳。**基本类型天生装不下"缺失"这个信息**。

另一个场景：`List<int>` 这种写法直接编译错误。集合的元素必须是引用类型，基本类型进不去。

这两件事逼出了**包装类型（wrapper class）**：每个基本类型都有一个对应的引用类型，把数值"包"在对象里。null 表示"没有对象"，也就表示"没有值"；对象可以进集合、可以当泛型参数。

| 基本类型 | 包装类型 | | 基本类型 | 包装类型 |
|---------|---------|-|---------|---------|
| `byte` | `Byte` | | `float` | `Float` |
| `short` | `Short` | | `double` | `Double` |
| `int` | `Integer` | | `char` | `Character` |
| `long` | `Long` | | `boolean` | `Boolean` |

八个类的名字只有一个要背：int 对应 **Integer**，其余都是首字母大写（char 对应 Character）。

包装类和 String 一样**不可变**：内部值是 final 的，`equals`/`hashCode` 按值实现，可以放心当 HashMap 的键（契约见 [String 与相等性](10-03-equality-and-strings.md)）。

## 自动装箱与拆箱：编译器帮你插的代码

手工在基本类型和包装类型之间转换很啰嗦，Java 5 起编译器自动帮你做，叫**自动装箱**（autoboxing）和**自动拆箱**（auto-unboxing）：

```java
public class BoxDemo {
    public static void main(String[] args) {
        java.util.List<Integer> list = new java.util.ArrayList<>();
        list.add(1);
        int first = list.get(0);
        System.out.println(first);
        System.out.println(list.get(0) + 1);
    }
}
```

```text
1
2
```

这两行简洁的代码，编译器实际生成的是：

- `list.add(1)`：1 是 int，集合要 Integer，编译器插了 `Integer.valueOf(1)`，装箱。
- `int first = list.get(0)`：拿到的是 Integer，要赋给 int，编译器插了 `.intValue()`，拆箱。
- `list.get(0) + 1`：Integer 和 int 做加法没有这种运算，先拆箱成 int 再加，结果再按需装箱。

**装箱 = Integer.valueOf(x)，拆箱 = 调用 intValue()**。记住这个等价关系，两个陷阱都能看穿了：valueOf 有缓存机制（下一节），intValue 是方法调用（null 调方法就是 NPE）。

## 缓存：为什么 127 相等而 128 不相等

先看现象：

```java
public class CacheDemo {
    public static void main(String[] args) {
        Integer a = 127;
        Integer b = 127;
        Integer c = 128;
        Integer d = 128;
        Integer e = 500;
        Integer f = 500;
        System.out.println(a == b);
        System.out.println(c == d);
        System.out.println(e == f);
    }
}
```

```text
true
false
false
```

三个 == 看起来是同一种比较，结果却是 true、false、false。原理在装箱的那一步：`Integer.valueOf(x)` 对小数值**直接返回缓存好的对象**，不新建。Java 语言规范（JLS 5.1.7）**保证**：对 -128 到 127 之间的值，任意两次装箱得到同一个对象。这是语言规范的硬性承诺，所有 JVM 都必须做到。

规范同时说：**这个区间之外的值，是否缓存由实现自行决定**。HotSpot 默认不缓存（所以 128、500 每次都是新对象），但允许通过 `-XX:AutoBoxCacheMax=N` 把缓存上限调大。换句话说：

- `-128..127` 内 `==` 为 true：**规范保证**，可靠。
- 区间外 `==` 为 false：**HotSpot 默认行为**，换了 JVM 参数就可能变。

所以两条铁律：**包装类型比较内容永远用 equals；`==` 的"缓存区间内恰好成立"只是侥幸，不是契约**。用 `==` 比较包装类型的代码在测试环境跑得好好的，上线后换个值区间就出错，这是真实事故的常见来源。

顺带一个考古知识：`new Integer(127)` 从 Java 9 起被标记为废弃，因为它永远新建对象、绕过缓存、没有任何好处。要对象用 `Integer.valueOf`，要数值用 `int`。

`Integer.parseInt(String)` 和 `Integer.valueOf(String)` 也常被混用。前者返回基本类型 int，后者返回包装类型（走缓存）。知道要数值就用 parseInt，要对象（比如放进集合）就用 valueOf：

```java
public class ParseVsValue {
    public static void main(String[] args) {
        System.out.println(Integer.valueOf("127") == Integer.valueOf("127"));
        int n = Integer.parseInt("127");
        System.out.println(n + 1);
    }
}
```

```text
true
128
```

## 拆箱空指针：最高频的 NPE 来源

拆箱是"对包装对象调 intValue()"。对象是 null 时，这个调用就是 NullPointerException。最容易踩的两个场景：

```java
public class UnboxNpe {
    public static void main(String[] args) {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        try {
            int n = counts.get("missing");
            System.out.println(n);
        } catch (NullPointerException ex) {
            System.out.println(ex.getClass().getSimpleName());
        }
        try {
            Integer x = null;
            int y = x + 1;
            System.out.println(y);
        } catch (NullPointerException ex) {
            System.out.println(ex.getClass().getSimpleName());
        }
    }
}
```

```text
NullPointerException
NullPointerException
```

- 第一个：`counts.get("missing")` 返回 null（键不存在），赋给 `int n` 触发拆箱，对 null 调 intValue，炸了。眼睛一扫 `int n = map.get(key)` 是再普通不过的一行，坑就埋在"键可能不存在"。
- 第二个：`x + 1` 是算术运算，Integer 参与 `+` 必须先拆箱，null 拆箱即炸。哪怕你只是想"加个 1"。

现代 JVM 对 NPE 的报错信息做了增强（Java 14 起的 Helpful NullPointerExceptions 特性），我这台机器上第一条的完整信息是：

```text
Cannot invoke "java.lang.Integer.intValue()" because the return value of "java.util.Map.get(Object)" is null
```

它直接告诉你哪个表达式是 null、当时正对它调用什么方法。示例代码里我只打印了异常类名，因为报错文案的具体措辞随 JDK 版本略有差异，但"对 null 调用了 intValue"这个本质不变。读 NPE 报错先看这句话，比猜快得多。

修复姿势按场景选：

- **键可能不存在**：用 `getOrDefault(key, 0)`，或先 `containsKey`，或拆成两步拿到 `Integer` 后自己决定 null 怎么办。
- **"没有值"是合法状态**：变量类型保持 `Integer`，把"有没有值"作为信息显式处理，而不是急着拆成 int 抹掉这个信息。
- **确定不可能为 null**：才用基本类型或直接拆箱。这个"确定"要来自设计，不来自侥幸。

## 混合运算的类型提升：丢人又不丢类型的加法

包装类型和基本类型混用还有一个安静的陷阱：**三元表达式会把两个分支的类型提升统一**。看一个只出现在输出里的惊喜：

```java
public class PromoDemo {
    public static void main(String[] args) {
        Object o = true ? 1 : 2.0;
        System.out.println(o.getClass().getName());
        System.out.println(o);
    }
}
```

```text
java.lang.Double
1.0
```

条件明明是 true，选中的是整数 1，出来的却是 Double 类型的 1.0。原因：`1`（int）和 `2.0`（double）参与同一个表达式时，按数值提升规则统一成 double，所以整个三元表达式类型是 double，整数 1 被提升为 1.0 再装箱。**分支的类型提升发生在"哪个分支被选中"之前**，条件判断救不了类型。

实际后果举例：把三元结果赋给 `Object` 或放进集合时，你以为装进去的是 Integer，实际是 Double；下游 `instanceof Integer` 判断或强转就会失败。写三元时让两个分支类型一致，是最省心的预防。

顺带纠正一个流传很广的说法："三元表达式 `cond ? 1 : nullableInteger` 即使条件为 true、选中的是 1，也会因为另一个分支要拆箱而抛 NPE。"在 Java 17 上实测**不是这样**：只有被选中的操作数才会求值，未被选中的分支不拆箱。这类"据说会炸"的结论一律跑一遍再信。

## LinkForge 里的真实代码

### 实体类的字段类型是一份设计文档

`src/main/java/com/linkforge/link/entity/ShortLink.java` 的字段类型几乎每个都有讲究：

```java
@TableId(type = IdType.AUTO)
private Long id;
private Long tenantId;
private Integer status;
@TableLogic
private Integer deleted;
private LocalDateTime expireAt;
```

- `Long id`：主键由数据库自增生成，**插入前还没有值**，必须是引用类型才能是 null；插入后 MyBatis-Plus（框架行为）把生成的 id 回填到这个字段。用 `long` 的话"还没有 id"只能用 0 谎报。
- `Integer status`：0 停用 1 启用。为什么不用 boolean？数据库列是整型，且未来可能扩展出第三种状态，Integer 留了余地。
- `@TableLogic Integer deleted`：MyBatis-Plus 的逻辑删除标记，0 未删 1 已删。逻辑删除是"删了但行还在"，所以这条数据永远存在，只是查询会自动加 `deleted = 0` 条件（框架行为）。
- 对比同一个信息在另一个类里的形态：`ShortLinkDetail`（`src/main/java/com/linkforge/link/dto/ShortLinkDetail.java`）有 `private boolean passwordProtected;`——基本类型。因为它是**算出来的**：`accessPasswordHash != null`，永远有 true/false，不存在"缺失"。**数据库来的可空字段用包装类型，代码算出来的布尔用基本类型**，这个区分在 DTO 设计里每天都在发生。

### record 里选择基本类型的设计

`src/main/java/com/linkforge/common/ratelimit/RateLimitDecision.java`：

```java
/**
 * Outcome of one acquire against the sliding window log. {@code retryAfterSeconds} is
 * meaningful only when {@code allowed} is false.
 */
public record RateLimitDecision(boolean allowed, int retryAfterSeconds, long count) {
}
```

`retryAfterSeconds` 是 `int` 而不是 `Integer`。javadoc 写明了它的语义："只在 allowed 为 false 时有意义"。如果做成 Integer，"允许放行时它是什么"就成了无解问题：null？0？都要靠约定。选基本类型加文档说明，把"不适用"变成"字段存在但别读"，配合 record 的紧凑语法。这是"基本类型 + 语义注释"与"包装类型 + null"两种方案间的取舍，据代码推断作者选了前者以避免一个含義不明的 null。

### 别被"传 null 就是不按这列过滤"误导

有一点必须纠正（旧版学习文档里写错了）：给 MyBatis-Plus 的查询条件直接传 null，**不会**变成"忽略这个条件"。`eq(ShortLink::getStatus, null)` 生成的是 `status = ?` 且参数为 null，而 SQL 里 `column = NULL` 对任何行都不成立，结果是**一条都查不出来**。想要"值为 null 时跳过这个条件"，要写动态条件（MyBatis 的 `<if test>` 或 MP 的布尔参数重载）。这是 MyBatis-Plus 的框架行为，不是 Java 语言行为；Java 这边只负责一件事：包装类型允许装 null，至于 null 传进 SQL 后是什么语义，由框架和数据库决定。

## 常见错误清单

| 错误 | 实际发生什么 | 修正 |
|------|-------------|------|
| 用 `==` 比较包装类型 | 127 内侥幸 true，128 起 false | 内容比较用 equals |
| `int n = map.get(key)` | 键不存在时拆箱 null，NPE | getOrDefault 或先判空 |
| `Integer x = null; x + 1` | 算术运算拆箱，NPE | 保持 Integer 处理，或保证非空 |
| 三元分支类型不一致 | 整数分支被提升成 double | 两分支写成同一类型 |
| `new Integer(1)` | 绕过缓存的新对象，Java 9 起废弃 | `Integer.valueOf(1)` |
| 可空字段用基本类型接 | "没有值"被迫伪装成 0 | 字段用包装类型 |
| 给 `eq(col, null)` 当"跳过条件" | 生成 `col = NULL`，一行都查不到 | 用动态条件布尔参数 |

## 面试追问

**第一层：为什么需要包装类型？**
三件事基本类型做不到：装 null（表达缺失）、进集合和泛型（List<int> 不存在）、参与需要对象的机制（反射、Optional）。包装类型是基本类型的对象化身。

**第二层：Integer 缓存的边界是谁定的？**
-128 到 127 的缓存同一性是 JLS 5.1.7 的规范保证；区间之外是否缓存是 JVM 实现自由，HotSpot 默认不缓存、可用 AutoBoxCacheMax 调整。所以"127 相等 128 不相等"中，前者是契约、后者只是常见实现的行为。

**第三层：拆箱 NPE 常见在哪几类代码里？**
Map 取值赋给基本类型、包装类型算术运算、三目/方法参数处的隐式拆箱。共同本质：对 null 调用 intValue()。防御靠 getOrDefault、判空和"可空信息别急着拆箱"。

**第四层：实体字段什么时候用包装类型、什么时候用基本类型？**
来自数据库、可能为 NULL 的字段用包装类型，null 有业务含义（主键未生成、过期时间未设置）；代码计算出的、必然有值的字段用基本类型（isXxx 这种布尔标志）。判据是"'缺失'是不是一个需要表达的状态"。

## 自测

1. `Integer a = 1000, b = 1000; a == b` 和 `a.equals(b)` 各是什么结果？
   答：`==` false（超出规范保证的缓存区间，HotSpot 默认各建对象），`equals` true（按值比较）。
2. `Map<String, Integer> m` 里没有键 "k"，`int n = m.get("k")` 会发生什么？
   答：get 返回 null，赋值触发拆箱，对 null 调 intValue 抛 NullPointerException。
3. 为什么实体类主键用 `Long` 而不是 `long`？
   答：插入前主键不存在，需要 null 表达"还没有"；基本类型只能用 0 谎报。
4. `true ? 1 : 2.0` 的类型和 true 时的值？
   答：类型被提升为 double，值是 1.0（Double）。类型统一发生在分支选择之前。
5. `Integer.valueOf("127") == Integer.valueOf("127")` 为什么是 true？换成 128 呢？
   答：valueOf 对 -128..127 返回缓存对象，同一对象。128 超出保证区间，HotSpot 默认 false，但这是实现行为不是规范。

## 下一步

- 集合的元素全是引用类型，包装类型的主战场：[集合框架](../11-java-collections.md)。
- 另一个"缺失"表达工具 Optional 与它的使用边界：[Lambda 与 Stream](10-08-lambda-stream-optional.md)。
- record 如何一行生成不可变值对象：[Java 17 新语法](10-10-java17.md)。
