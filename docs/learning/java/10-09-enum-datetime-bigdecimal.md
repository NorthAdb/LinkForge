# 枚举、日期时间与数值精度：标准库里的高频件

优先级 P1。前置：[类与多态](10-02-oop.md)、[String 与相等性](10-03-equality-and-strings.md)。这篇覆盖三件业务代码天天在用、面试常考、又经常被旧习惯带偏的东西：枚举、`java.time`、`BigDecimal`。

## 枚举：把"取值范围"写进类型

### 问题的起点：魔数

短链有"启用/停用"两种状态。用 `int` 存：

```java
private int status;   // 0 = 停用，1 = 启用？还是 1 = 停用？
```

代码里很快出现 `if (link.getStatus() == 2)` 这种魔数，谁也说不清 2 是什么、是不是合法值。更糟的是编译器毫无知觉：传个 99 编译照过，非法状态一路带到数据库。问题的本质：**这个变量的合法取值是一个封闭的小集合，而 int 是个开放的大集合**。

**枚举（enum）**把封闭集合直接写进类型：

```java
public class EnumDemo {
    enum Action { ALLOW, WARN, BLOCK }

    public static void main(String[] args) {
        for (Action a : Action.values()) {
            System.out.println(a.name() + " ordinal=" + a.ordinal());
        }
    }
}
```

```text
ALLOW ordinal=0
WARN ordinal=1
BLOCK ordinal=2
```

`Action` 类型的变量只有三种可能的值，再没有第四种，编译器保证。常用方法：`values()` 返回全部实例（每次调用返回新数组副本）、`valueOf("WARN")` 按名字查（查不到抛 `IllegalArgumentException`）、`name()` 返回名字、`ordinal()` 返回声明序号。**别用 ordinal 表达业务含义**（比如拿它当数据库存值），调整枚举声明顺序就全乱了，要存就存 name。

### 枚举是"实例个数固定的类"

枚举不是语法糖字段表，它就是类：可以有字段、构造器、方法。项目里的真实例子，`src/main/java/com/linkforge/link/support/TargetPolicy.java`：

```java
public enum Reason {
    BLOCK_LIST("block_list"), WARN_LIST("warn_list"), USERINFO("userinfo");

    private final String tag;

    Reason(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }
}
```

每个枚举常量是 Reason 类的一个**实例**，构造时带上自己的 tag。三行常量声明就是三次构造调用。tag 是给监控指标用的稳定标识（javadoc 说明它有界且固定）。这个模式叫"带属性的枚举"，是业务代码里最实用的形态——状态、类型、来源这种"名字 + 附加信息"的封闭集合全用它。

```java
public class EnumAttr {
    enum Reason {
        BLOCK_LIST("block_list"), WARN_LIST("warn_list"), USERINFO("userinfo");

        private final String tag;

        Reason(String tag) {
            this.tag = tag;
        }

        String tag() {
            return tag;
        }
    }

    public static void main(String[] args) {
        Reason r = Reason.valueOf("USERINFO");
        System.out.println(r.tag());
    }
}
```

```text
userinfo
```

三行常量声明就是三次构造调用：BLOCK_LIST 一个实例、WARN_LIST 一个实例、USERINFO 一个实例，此后整个 JVM 里 Reason 就只有这三个对象。

枚举实例有几个白送的性质：字段声明为 final 就不可变；实例个数固定所以天然**线程安全**（没有"创建中"的中间态可抢）；可以放进 switch（编译器还能对枚举 switch 做穷尽检查，配合 sealed 的进化版见 [Java 17 新语法](10-10-java17.md)）。`EnumMap`/`EnumSet` 是以 ordinal 为下标的专用实现，比 HashMap 更快更省，键是枚举时优先用。

## java.time：日期时间的正确打开方式

### 旧 API 为什么被弃用

`Date` 是 Java 1.0 的遗物，三宗罪：**可变**（setTime 能改，当字段传出去就可能被别人改，呼应[不可变设计](10-03-equality-and-strings.md)）；**时区语义混乱**（内部存 UTC 毫秒，toString 却用默认时区渲染，跨时区必出 bug）；**坑爹细节**（年份从 1900 起算、月份从 0 起算）。`Calendar` 没好到哪去。Java 8 推出 `java.time`（JSR-310），全类型**不可变**，一切"修改"返回新对象——和 String 的心智模型一致。

### 四个核心类型各管一段

| 类型 | 表示什么 | 典型用途 |
|------|---------|---------|
| `LocalDate` | 本地日期（无时间无时区） | 生日、统计日报的日期 |
| `LocalDateTime` | 本地日期+时间（无时区） | 业务表字段：过期时间、创建时间 |
| `Instant` | UTC 时间线上的一个点 | 跨系统时间戳、日志事件时刻 |
| `ZonedDateTime` | 带时区的完整时间 | 面向人的展示、跨时区换算 |

选型口诀：**存库和业务计算用 LocalDate/LocalDateTime，机器间对时用 Instant，给人看才用 ZonedDateTime**。常用操作：

```java
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public class TimeDemo {
    public static void main(String[] args) {
        LocalDate from = LocalDate.of(2027, 3, 1);
        LocalDate to = from.plusDays(31);
        System.out.println(to);
        System.out.println(to.format(DateTimeFormatter.ofPattern("yyyy/MM/dd")));
        System.out.println(from.isBefore(to));
    }
}
```

```text
2027-04-01
2027/04/01
true
```

`plusDays(31)` 从 3 月 1 日跳到 4 月 1 日，大小月闰年由库处理——这正是"别自己算日期"的意义。`plusDays` 返回**新对象**（不可变模型），不接返回值等于没调，和 `String.replace` 是同一种坑。格式化一律 `DateTimeFormatter`（线程安全，可以做成常量共享），别用线程不安全的 `SimpleDateFormat`。

### 项目里的真实用例

`src/main/java/com/linkforge/stats/service/ClickStatsService.java` 的统计区间计算：

```java
LocalDate today = LocalDate.now();
LocalDate fromDate = today.minusDays(days - 1L);
LocalDateTime from = fromDate.atStartOfDay();
LocalDateTime toExclusive = today.plusDays(1).atStartOfDay();
```

四行是一个完整的"最近 N 天"区间：今天（含）往前推 N-1 天得到起点；`atStartOfDay()` 把 LocalDate 提升成当天 00:00 的 LocalDateTime；终点用**明天的 00:00** 而不是"今天 23:59:59"——半开区间 `[from, to)` 查询时直接 `>= from AND < to`，不用纠结 59.999 秒的边界。这是日期区间查询的标准姿势。

`src/main/java/com/linkforge/link/service/ShortLinkService.java` 的 `setStatus` 里 `link.setUpdatedAt(LocalDateTime.now())` 则带来一个真实的设计问题：**用应用服务器时钟还是数据库时钟**。分布式下两者天然会漂，统计和排序的口径必须选一边并全项目统一。项目对此有明确的决策记录，见 [设计决策](../82-linkforge-decisions.md)。你只需要记住：时间从哪来是一个要显式决策的点，不是随手 `now()` 完事的细节。

## BigDecimal：金额与小数精度

double 是二进制浮点，按 IEEE 754 存储，**无法精确表示 0.1**，就像十进制写不出 1/3。误差平时看不见，一累加就现形：

```java
import java.math.BigDecimal;

public class MoneyDemo {
    public static void main(String[] args) {
        System.out.println(0.1 + 0.2);
        System.out.println(new BigDecimal("0.1").add(new BigDecimal("0.2")));
    }
}
```

```text
0.30000000000000004
0.3
```

第一行是 double 的算术结果，那个尾巴就是二进制表示误差。第二行 BigDecimal 按**十进制**精度计算，得 0.3。规则：**金额、费率、任何要求十进制精确的数值用 BigDecimal，且必须用字符串构造**——`new BigDecimal(0.1)` 把 double 的误差原样搬进来，等于没救。对既有 double 可以用 `BigDecimal.valueOf(0.1)`（它内部先转字符串再构造）。

两个高频陷阱：

**陷阱一：`equals` 连精度（scale）一起比。**

```java
new BigDecimal("0.30").equals(new BigDecimal("0.3"))   // false！
new BigDecimal("0.30").compareTo(new BigDecimal("0.3")) // 0，数值相等
```

`equals` 要求值和位数都相同。**数值比较一律 `compareTo` 返回 0**，把 equals 留给 HashSet 场景并想清楚要不要把 scale 算进去。

**陷阱二：除不尽不指定精度直接抛异常。** `new BigDecimal("1").divide(new BigDecimal("3"))` 抛 `ArithmeticException: Non-terminating decimal expansion`，因为 1/3 是无限小数而 BigDecimal 要精确。必须 `divide(divisor, 2, RoundingMode.HALF_UP)` 指定保留两位和舍入模式。

坦白说，LinkForge 的业务（短链、点击统计）没有金额场景，项目里没有 BigDecimal。但它仍是春招高频题：面试官用"0.1+0.2 等于几"验证你是否真理解浮点存储，用 equals/compareTo 陷阱验证你是否真用过。

## 常用工具方法速查

最后一组散件，每个都值得知道存在：

- `Objects.equals(a, b)`：null 安全的相等比较，`a.equals(b)` 先判空的替代。
- `Objects.requireNonNull(x, "msg")`：参数守卫，null 直接抛带信息的 NPE，快速失败。
- `String.join(", ", list)` / `String.format`：拼接与格式化。
- `Math.*`：`floorDiv`、`addExact`（溢出即抛异常，比静默溢出好排查）。
- `UUID.randomUUID().toString()`：生成全局唯一 ID 串。

LinkForge 里 `LinkAccessTokens`、`WebhookSigner` 等类用 `StringBuilder` 拼 hex 字符串、`SecureRandom` 生成随机数（[String 与相等性](10-03-equality-and-strings.md)和[类型与引用](10-01-types-and-references.md)分别讲过这两样的机制），属于"工具类 + 领域知识"的组合。

## 面试追问

**第一层：枚举的线程安全性从哪来？**
枚举实例由 JVM 在类加载时创建且全局唯一（枚举天然是单例），实例个数封闭、无中途状态暴露；字段声明 final 后整体不可变。双重检查锁、防反射攻击这些单例的麻烦事在枚举上都不存在。

**第二层：LocalDateTime 和 Instant 怎么选？**
LocalDateTime 是"墙上时间"，无时区语义，适合与业务字段、数据库列对应；Instant 是 UTC 时间线上的绝对时刻，适合系统间传输和事件排序。同一个 Instant 在不同时区渲染成不同的 LocalDateTime，选错类型等于把"是几点"和"是哪个时刻"混为一谈。

**第三层：数据库时间与应用时间不一致会怎样？**
排序口径漂移、区间统计漏数据或多数据。工程上要么全用数据库时钟（NOW()），要么全用应用时钟并保证 NTP 同步，关键是全项目一个口径。LinkForge 在统计口径上有明确决策（见 82 号文档）。

**第四层：0.1 + 0.2 为什么不等于 0.3？**
IEEE 754 二进制浮点无法精确表示 0.1（二进制循环小数），两次表示误差累加出 0.30000000000000004。要十进制精确用 BigDecimal（字符串构造），比较用 compareTo 不用 equals（scale 参与 equals）。

**第五层：枚举和常量类（public static final int）比好在哪？**
类型安全：方法参数是枚举类型时非法值编译不过；有命名空间和方法（可带属性、行为）；switch 可穷尽检查；打印可读。常量类只是 int 的别名，编译器分不清"合法常量"和"任意 int"。

## 自测

1. `values()`、`valueOf("X")`、`ordinal()` 各做什么？哪个不该用于业务存储？
   答：values 返回全部实例；valueOf 按名查找（不存在抛 IllegalArgumentException）；ordinal 返回声明序号。ordinal 不该入库，顺序会变。
2. `LocalDate.of(2027,1,31).plusMonths(1)` 大概会得到什么？这说明了什么设计原则？
   答：2027-02-28，库自动处理月末溢出。日期运算交给库，不手写。
3. 为什么 `new BigDecimal("0.30")` 不 equals `new BigDecimal("0.3")`？该用什么比？
   答：equals 连 scale 一起比；数值比较用 compareTo == 0。
4. `new BigDecimal(0.1)` 和 `new BigDecimal("0.1")` 区别？
   答：前者把 double 的二进制误差带进来；后者按字面十进制精确。金额一律字符串构造。
5. 半开区间 `[from, to)` 查"最近 N 天"怎么构造？
   答：from = 今天减 N-1 天的 atStartOfDay；to = 明天 atStartOfDay；查询 >= from AND < to，边界不重不漏。

## 下一步

- 枚举 + sealed + record 如何组成"穷尽的状态建模"：[Java 17 新语法](10-10-java17.md)。
- 枚举实例在项目里的真实位置：TargetPolicy 的 Action/Reason（[类型与引用](10-01-types-and-references.md)、[类与多态](10-02-oop.md)都引用过它）。
- 统计代码的完整阅读路径：[调用链追踪](../81-linkforge-flows.md)。
