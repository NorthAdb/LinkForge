# Lambda、Stream 与 Optional：把"怎么做"换成"做什么"

优先级 P0。前置：[泛型](10-05-generics.md)（函数式接口全是泛型）、[异常](10-06-exceptions.md)（Optional 与异常的分工）。这篇解决三个递进的问题：怎么把"一段行为"当参数传；怎么用声明式管道替代过程式循环；怎么体面地表达"可能没有值"。

## 从一个问题开始

给一个字符串列表排序，Java 8 之前的写法：

```java
List<String> names = new ArrayList<>(List.of("carol", "alice", "bob"));
names.sort(new Comparator<String>() {
    @Override
    public int compare(String a, String b) {
        return a.compareTo(b);
    }
});
```

五个零件（new、匿名类、@Override、方法签名、return）只为传递一行真正的逻辑 `a.compareTo(b)`。**要传递的是行为，被迫造了一个对象**。更常见的过程式泥团是"过滤 + 变换 + 收集"三连：

```java
List<String> result = new ArrayList<>();
for (String n : names) {
    if (n.length() > 3) {
        result.add(n.toUpperCase());
    }
}
```

循环体把"怎么遍历、怎么判断、怎么装"全部摊开。任务其实是一句话："把长度大于 3 的名字变成大写收集起来"。这篇的三个机制都为了让代码回到这句话的形状。

## Lambda：行为终于可以当值传递

```java
import java.util.ArrayList;
import java.util.List;

public class LambdaDemo {
    public static void main(String[] args) {
        List<String> names = new ArrayList<>(List.of("carol", "alice", "bob"));
        names.sort((a, b) -> a.compareTo(b));
        System.out.println(names);
    }
}
```

```text
[alice, bob, carol]
```

`(a, b) -> a.compareTo(b)` 是一个 **Lambda 表达式**：箭头左边是参数，右边是结果。它没有类名、没有方法名，它的类型是"某个**函数式接口**"。

**函数式接口（functional interface）**= 恰好只有一个抽象方法的接口。`Comparator<T>` 只有 `compare` 一个抽象方法，所以"两个参数、返回 int"的 Lambda 能赋给它。编译器的推导逻辑：目标类型是 `Comparator<String>` → 唯一抽象方法是 `compare(String, String) → int` → Lambda 的形参 a、b 就是 String，返回值是 int，吻合。**Lambda 不是语法糖套着匿名类**（实现细节上它用 invokedynamic 生成，不是内部类，性能也更好），但从"谁接收它"的角度，把它理解成函数式接口的实例是准确的。

`@FunctionalInterface` 注解让编译器检查"这确实只有一个抽象方法"，给接口作者用。自己定义一个：

```java
public class SamDemo {
    @FunctionalInterface
    interface Transformer {
        String apply(String input);
    }

    static String transform(String s, Transformer t) {
        return t.apply(s);
    }

    public static void main(String[] args) {
        System.out.println(transform("hello", s -> s + "!"));
        System.out.println(transform("hello", String::toUpperCase));
    }
}
```

```text
hello!
HELLO
```

`transform` 的第二个参数类型是"某个行为"，调用方现场给行为。`String::toUpperCase` 是**方法引用**：Lambda 的简写，箭头右边只是"调这个现成方法"时可以写成 `类::方法`。四种形式：`String::toUpperCase`（实例方法的类名形式）、`System.out::println`、`Integer::parseInt`（静态方法）、`ShortLinkService::toDetail`（见下文项目案例）。看到 `A::b` 就读成"一个等价于 `x -> A.b(x)` 的行为"。

Lambda 能捕获外部的局部变量，但**变量必须是 final 或事实上不再被修改**（effectively final）：

```java
int limit = 3;
names.stream().filter(n -> n.length() > limit);   // 合法
limit = 5;                                        // 再赋值后，上面的 Lambda 编译报错
```

原因：Lambda 可能在另一个时刻甚至另一个线程执行，捕获的是值的副本；让副本和原变量保持一致的唯一可靠办法是原变量别再变。报错文案是 `local variables referenced from a lambda expression must be final or effectively final`。成员字段不受此限（访问的是对象），但那意味着共享可变状态，并发场景要小心（[并发](../13-concurrency.md)）。

标准库里最常用的四个函数式接口，认识它们的形状就行：`Function<T,R>`（T 进 R 出）、`Consumer<T>`（T 进无出）、`Predicate<T>`（T 进 boolean 出）、`Supplier<T>`（无进 T 出）。Stream 的方法签名几乎全由它们组成。

## Stream：描述一条数据处理管道

**Stream 不是集合，不是数据结构，不存数据**。它是"对一次数据加工过程的描述"，从数据源（集合、数组、生成器）接上，流经若干加工步骤，最后汇入一个结果。加工步骤分两类：

- **中间操作**（`filter`、`map`、`sorted`...）返回新的 Stream，**惰性**：只登记步骤，不执行。
- **终结操作**（`toList`、`forEach`、`count`、`collect`...）触发整条管道真正流动，每个元素**从源头一路穿到终点**，不是"先 filter 完再 map"分阶段跑。

```java
import java.util.List;

public class StreamDemo {
    public static void main(String[] args) {
        var lengths = List.of("carol", "alice", "bob").stream()
                .filter(n -> n.length() > 3)
                .map(String::toUpperCase)
                .toList();
        System.out.println(lengths);
    }
}
```

```text
[CAROL, ALICE]
```

`filter` 是 Predicate（留下判定为 true 的），`map` 是 Function（逐个变换），`toList` 终结收集。注意输出保持源顺序（carol 在前），Stream 顺序流天然保序。对照开头的 for 循环：同样的三件事，循环写的是"怎么一步步做"，管道写的是"做什么"。且管道能任意追加步骤而结构不变，循环里每加一步都多一层嵌套。

惰性是 Stream 的关键行为，直接观察它：

```java
import java.util.List;

public class LazyDemo {
    public static void main(String[] args) {
        var stream = List.of("a", "b", "c").stream()
                .peek(x -> System.out.println("visited " + x));
        System.out.println("pipeline built, nothing printed yet");
        var result = stream.map(String::toUpperCase).toList();
        System.out.println(result);
    }
}
```

```text
pipeline built, nothing printed yet
visited a
visited b
visited c
[A, B, C]
```

`peek` 只为观察用（调试管道时有用，别用来改状态）。第一行输出证明：只有中间操作的 stream 构建后**什么都没发生**，`toList()` 这个终结操作出现，元素才开始流动。由此两个推论：没有终结操作的管道一行代码都不会执行（低级错误：忘记终结）；执行是逐元素的（a 走完全程才轮到 b），这对"短路"操作（`findFirst`、`anyMatch`）意味着可能只处理前几个元素就结束。

`collect(Collectors.toMap(k, v))` 把流汇成 Map，注意它**不保留没有出现的键**，补全要靠 `getOrDefault`。这个组合在项目里就是"按天统计补零"的标准写法，见下面项目案例。其他常用终结操作：`forEach`（消费每个元素）、`count`、`sorted`、`distinct`、`Collectors.groupingBy`（分组）。

三条纪律（每条都有明确的"为什么"）：

1. **不在 filter/map 里写副作用**。这些操作该回答"这个元素要不要/变成什么"，往里塞打印、改外部集合、发请求，就把声明式管道拉回了过程式泥团，还埋下并行流的隐患。
2. **并行流不是加速开关**。`parallelStream()` 把任务拆到多线程，数据量小或操作有共享状态时反而更慢更危险。需要并发能力去[并发](../13-concurrency.md)，Stream 的本职是描述加工。
3. **Stream 一次性**。同一个 Stream 对象终结后再用抛 `IllegalStateException: stream has already been operated upon or closed`。要复用就重新从源头取。

## Optional：把"可能没有"写进类型

`map.get(key)` 取不到返回 null，调用方要么判空要么等 NPE（[包装类型](10-04-wrappers-and-boxing.md)）。null 的问题不是"能表示缺失"，而是**类型系统看不出来这个返回值可能没有**——`String get()` 的签名承诺给 String，null 是违约的暗门。Optional 的思路：**把"可能没有"提升为类型信息**：

```java
import java.util.Optional;

public class OptionalDemo {
    public static void main(String[] args) {
        Optional<String> found = Optional.of("value");
        Optional<String> missing = Optional.empty();
        System.out.println(found.orElse("default"));
        System.out.println(missing.orElse("default"));
        System.out.println(found.map(String::length).orElse(0));
        System.out.println(missing.map(String::length).orElse(0));
    }
}
```

```text
value
default
5
0
```

`Optional<String>` 类型的值要么装着一个 String 要么是 `Optional.empty()`，编译器强制你面对"没有"的可能。常用方法：`orElse(v)` 没有就给默认值；`orElseThrow(Supplier)` 没有就抛指定异常（把"缺失升级为失败"的决策点留给调用方）；`map`/`filter`/`flatMap` 在"有值"的前提下继续加工，没有就保持 empty——一串操作写下来**不需要出现一次 null 检查**。

使用边界同样重要：**Optional 设计为返回值类型，不要用在字段、方法参数和集合元素上**（官方建议，也是社区共识）。字段里的 Optional 本身可为 null，等于两层缺失嵌套；参数用 Optional 逼着每个调用方包装，不如提供两个重载方法。项目里的正确示范，`src/main/java/com/linkforge/link/support/ClickFilter.java`：

```java
public Optional<SkipReason> classify(HttpServletRequest request) {
    ...
    return Optional.empty();      // 不是爬虫/预取，正常记录
    ...
    return Optional.of(SkipReason.PREVIEW_BOT);   // 命中分类
}
```

调用方在 `ShortLinkService.resolveForGet`：

```java
Optional<ClickFilter.SkipReason> skip = clickFilter.classify(request);
if (skip.isPresent()) {
    meterRegistry.counter("linkforge.click.skipped", "reason", skip.get().tag()).increment();
    return redirect;
}
```

`classify` 返回 `Optional<SkipReason>`："这次点击是否属于该跳过的预取/预览机器人"确实**可能没有结论**，签名把它说清楚。调用方 `isPresent()` 分流，有值时 `get().tag()` 取出。这里用 if-isPresent 而不是 `ifPresent` 链，是因为两个分支差异大（记录指标并返回 vs 继续正常记录点击），命令式分流更清楚——**Optional 不强制链式，它强制的是"类型承认缺失"**。

## LinkForge 里的真实代码

### 方法引用 + Stream 的实体转换

`src/main/java/com/linkforge/link/service/ShortLinkService.java` 第 215 行附近：

```java
List<ShortLinkDetail> records = result.getRecords().stream().map(ShortLinkService::toDetail).toList();
```

一行完成"实体列表 → DTO 列表"：`getRecords()` 从分页对象拿到 `List<ShortLink>` 实体，`map(ShortLinkService::toDetail)` 用方法引用把转换逻辑命名引用出来（`toDetail` 是类里的私有静态方法），`toList()` 收集。没有循环变量、没有临时集合。`toList()` 是 Java 16 加的便捷终结操作，返回**不可修改**列表（想继续 add 会抛 UnsupportedOperationException，这本身是防止下游偷偷改数据的保护）。

### 按天聚合统计的完整管道

`src/main/java/com/linkforge/stats/service/ClickStatsService.java` 的 `stats` 方法，接口统计的骨干：

```java
Map<LocalDate, Long> byDate = clickDailyMapper.selectRange(linkId, fromDate, today).stream()
        .collect(Collectors.toMap(ClickDaily::getStatDate, ClickDaily::getClicks));
...
for (LocalDate d = fromDate; !d.isAfter(today); d = d.plusDays(1)) {
    daily.add(new DailyClicks(d, byDate.getOrDefault(d, 0L), uvByDate.getOrDefault(d, 0L)));
}
```

第一行把"某时间段每天的点击数"从数据库行流转成 `Map<日期, 数量>`，键取 `ClickDaily::getStatDate`，值取 `getClicks`。随后的 for 循环不是多此一举：`toMap` 只会装下**数据库里有记录的日期**，没有点击的日子在 Map 里根本没有键，图表却需要连续的每一天，所以用 `getOrDefault(d, 0L)` 补零。这段代码同时示范了两件事：Stream 负责"已有的数据怎么变换"，循环负责"按时间轴枚举补全"，**工具各干各的强项**，不追求把所有代码都写成链。

### MyBatis-Plus 条件里的方法引用

```java
new LambdaQueryWrapper<ClickLog>()
        .eq(ClickLog::getLinkId, linkId)
        .orderByDesc(ClickLog::getClickedAt)
```

[泛型](10-05-generics.md)讲过这行的泛型部分，现在补上方法引用部分：`ClickLog::getLinkId` 既是"取哪一列"的声明，也带着返回类型 Long 让框架核对值类型。方法引用在这里被框架反射解析成列名——函数式接口、方法引用、反射三个机制的又一次合流（[注解、反射与动态代理](10-07-annotations-reflection-proxy.md)）。

## 常见错误清单

| 错误 | 实际发生什么 | 修正 |
|------|-------------|------|
| 构建了 Stream 忘记终结操作 | 一行代码都不执行 | 每条管道必有终结操作 |
| 复用已终结的 Stream | IllegalStateException | 重新从源头取流 |
| filter/map 里改外部状态 | 隐蔽副作用，并行流下数据竞争 | 副作用放到 forEach 之后或终端处理 |
| Lambda 捕获非有效 final 变量 | 编译错误 | 拷贝进 final 局部变量 |
| `Optional.get()` 裸调 | empty 时 NoSuchElementException，换个姿势的 NPE | orElse/orElseThrow/ifPresent |
| Optional 当字段/参数类型 | 两层缺失嵌套，API 复杂化 | 只用于返回值 |
| 给 `List.of` 的列表 sort/add | UnsupportedOperationException（不可变） | 包一层 `new ArrayList<>(...)` |
| 小集合用 parallelStream | 拆分调度开销反噬 | 数据量大且无共享状态才考虑 |

## 面试追问

**第一层：Lambda 是什么？和匿名内部类什么关系？**
函数式接口的实例，编译器按目标接口的类型做参数与返回值推导。实现上用 invokedynamic 延迟到首次调用时生成（LambdaMetafactory），不是编译期生成内部类；语义上 `this` 指向外围实例而非 Lambda 自己，这点与匿名内部类不同。

**第二层：Stream 的惰性是什么，带来什么？**
中间操作只登记不执行，终结操作触发、逐元素流过整条管道。带来短路能力（findFirst 遇到即停）、管道可组合、以及"忘记终结操作就什么都不发生"这个典型坑。

**第三层：Optional 的设计意图和边界？**
把"可能没有值"写进返回类型，逼调用方显式处理，消灭裸 null 检查的遗漏。边界：只用于返回值；字段/参数/集合元素不用；性能敏感的热点路径上多一层包装有微小开销，但一般可忽略。

**第四层：方法引用被框架（如 MyBatis-Plus）拿到后怎么用？**
它实现了函数式接口，可以直接调用（当 getter 用）；同时它是可序列化的，框架能从序列化数据里解析出"哪个类哪个方法"，再反射读泛型签名映射成数据库列名。这是"行为当值传"和"反射读元信息"的组合应用。

**第五层：项目里哪里体现了 Stream 与循环的分工？**
ClickStatsService.stats：`Collectors.toMap` 负责把数据库行流转成按日期索引的 Map；随后普通循环沿时间轴逐日枚举并用 `getOrDefault` 补零，因为 toMap 不会为"没有点击的日子"造键。声明式处理变换，命令式处理枚举，按强项分工。

## 自测

1. `(a, b) -> a - b` 能赋给哪些接口？条件是什么？
   答：任何"两参一返回值且签名兼容"的函数式接口（如 Comparator<Integer>、ToIntBiFunction<Integer,Integer>）。条件：接口只有一个抽象方法。
2. 只有中间操作的 Stream 会执行吗？
   答：不会。没有终结操作，管道是惰性描述，一行代码都不跑。
3. Lambda 为什么要求捕获的局部变量是有效 final？
   答：Lambda 持有的是值的副本，可能延迟或在别的线程执行；原变量若再变，副本与原值不一致，语义不可靠。
4. `Optional.empty().map(String::length).orElse(0)` 结果和过程？
   答：0。empty 上 map 直接保持 empty，不执行 length，最后走 orElse 默认值。
5. `result.getRecords().stream().map(ShortLinkService::toDetail).toList()` 每段在做什么？
   答：实体列表接流；方法引用逐个转 DTO；toList 终结并返回不可修改列表。

## 下一步

- Lambda 与函数式接口的"行为参数化"如何支撑 AOP 处理器：[注解、反射与动态代理](10-07-annotations-reflection-proxy.md)。
- 枚举类型与 java.time 的 LocalDate/LocalDateTime（统计代码的另一半）：[枚举与日期时间](10-09-enum-datetime-bigdecimal.md)。
- ConcurrentHashMap 配合 computeIfAbsent 的并发版"行为参数化"：[并发](../13-concurrency.md)。
