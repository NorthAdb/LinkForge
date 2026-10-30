# 泛型与类型擦除：把类型错误挡在编译期

优先级 P0，目标层级 L3。前置：[类与多态](10-02-oop.md)、[String 与相等性](10-03-equality-and-strings.md)。这篇回答四个问题：泛型到底检查了什么；`List<String>` 和 `List<Integer>` 运行时还剩什么区别（答案是没有）；为什么因此有一串"不能"；通配符 `? extends`/`? super` 在解决什么问题。

## 从一个问题开始

Java 5 之前集合不认识类型，装什么都是 Object：

```java
import java.util.ArrayList;
import java.util.List;

public class BeforeGenerics {
    public static void main(String[] args) {
        List names = new ArrayList();
        names.add("alice");
        names.add(42);
        String second = (String) names.get(1);
        System.out.println(second);
    }
}
```

```text
运行异常：
java.lang.ClassCastException: class java.lang.Integer cannot be cast to class java.lang.String
at BeforeGenerics.main(BeforeGenerics.java:9)
```

如果先取第一个元素，`alice` 会正常出来；灾难在取第二个元素时才发生，而根因在第 6 行埋下。肇事现场和爆炸现场隔了两步，真实项目里隔的是几个类、几百行。`names.add(42)` 编译器一声不吭，错误在离肇事现场很远的地方才爆。**泛型（generics）要解决的就是把这类错误从运行期提前到编译期**：让集合带着"我里面装的是什么类型"这个信息，编译器据此拒绝塞错的东西。

## 泛型类与泛型方法

```java
public class BoxDemo {
    static class Box<T> {
        private T value;

        void put(T v) {
            this.value = v;
        }

        T get() {
            return value;
        }
    }

    public static void main(String[] args) {
        Box<String> name = new Box<>();
        name.put("alice");
        String s = name.get();
        System.out.println(s);

        Box<Integer> count = new Box<>();
        count.put(1);
        System.out.println(count.get() + 1);
    }
}
```

```text
alice
2
```

`Box<T>` 的 `T` 是**类型参数**：一个待填的空位。`Box<String>` 把 T 填成 String，于是编译器把 `put(T v)` 当成 `put(String v)` 检查。`name.put(42)` 会怎样？实测：

```java
public class BoxFail {
    static class Box<T> {
        void put(T v) {
        }
    }

    public static void main(String[] args) {
        Box<String> name = new Box<>();
        name.put(42);
    }
}
```

```text
编译错误：
error: incompatible types: int cannot be converted to String
```

这正是泛型的核心价值：**错误发生在写错的那一行**，而不是遥远的强转现场。`new Box<>()` 尾部的 `<>` 是钻石操作符，让编译器从左边变量类型推断 T，不用重复写。类型参数按惯例单字母：`T`（Type）、`E`（Element，集合元素）、`K`/`V`（键值）、`R`（返回值）。

类型参数还能加**边界**，约束"必须是什么或它的子类"：

```java
public class BoundDemo {
    static double sum(java.util.List<? extends Number> nums) {
        double total = 0;
        for (Number n : nums) {
            total += n.doubleValue();
        }
        return total;
    }

    public static void main(String[] args) {
        System.out.println(sum(java.util.List.of(1, 2, 3)));
        System.out.println(sum(java.util.List.of(1.5, 2.5)));
    }
}
```

```text
6.0
4.0
```

`? extends Number` 表示"Number 或它的任何子类"，`List<Integer>`、`List<Double>` 都能传进来。方法里能安全**读**：任何元素至少是 Number，`doubleValue()` 一定存在。这就是通配符的第一个用途，下一节展开。

泛型不只在类上。**泛型方法**自己声明类型参数，项目里的真实例子 `ApiResponse`（`src/main/java/com/linkforge/common/result/ApiResponse.java`）：

```java
public class ApiResponse<T> {
    private int code;
    private String message;
    private T data;

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }
}
```

注意两个 T 的位置：类名旁的 `ApiResponse<T>` 是类的类型参数；静态方法不能访问类的类型参数（静态成员不属于实例，类型参数跟着对象走），所以 `ok` 方法**自己**声明了 `<T>`，写在返回类型前面。调用 `ApiResponse.ok(detail)` 时编译器从实参推断出 T，不需要手写。

## List 不是 List：不变性

一个反直觉的事实先摆出来：`String` 是 `Object` 的子类，但 `List<String>` **不是** `List<Object>` 的子类型：

```java
import java.util.ArrayList;
import java.util.List;

public class NotAssignable {
    public static void main(String[] args) {
        List<String> ls = new ArrayList<>();
        List<Object> lo = ls;
    }
}
```

```text
编译错误：
error: incompatible types: List<String> cannot be converted to List<Object>
```

为什么禁止？假设放行，看能闯出什么祸：

```java
List<String> ls = new ArrayList<>();
List<Object> lo = ls;          // 假设这行合法
lo.add(42);                    // lo 是 List<Object>，装 Integer 天经地义
String s = ls.get(0);          // ls 承诺里面全是 String，取出来却是 Integer，炸
```

`lo` 和 `ls` 是同一个对象的两个引用（[类型与引用](10-01-types-and-references.md)）。通过 `lo` 的口子塞进一个 Integer，`ls` 的类型承诺就被击穿了。编译器干脆掐断第一步，让错误在最前面暴露。**这种"即使元素有继承关系，容器类型也不互相兼容"的性质叫不变性（invariance）**。

数组恰好相反，是协变的（covariant）：

```java
public class ArrayCovariant {
    public static void main(String[] args) {
        Object[] objs = new String[] {"a"};
        objs[0] = 42;
    }
}
```

```text
运行异常：
java.lang.ArrayStoreException: java.lang.Integer
at ArrayCovariant.main(ArrayCovariant.java:4)
```

`String[]` 能赋给 `Object[]`（数组协变，Java 1.0 的设计决定），于是同样的祸甩给**运行时**去拦：每次往数组写元素都做一次类型检查，塞错了抛 ArrayStoreException。对照一下两条路线：

- 数组：编译放行，运行拦截（ArrayStoreException）。
- 泛型：编译拦截（incompatible types），运行无需再查。

泛型选择了更便宜的方案：问题在编译期报。数组因为协变必须运行期自检，这是历史包袱。理解了这组对照，"泛型为什么不允许协变"和下一节"泛型数组为什么被禁"就是同一件事。

## 通配符：只读与只写的口子

一刀切不变性太严格：写 `sum(List<Integer>)` 之后 `List<Double>` 就用不了了（上面 BoundDemo 用 `? extends Number` 解决）。通配符是给"泛型不变性"开的两个**带限制**的口子。

`? extends Number`（上限通配符）：某种**具体的但未知**的 Number 子类型。能读（当 Number 读），**不能写**：

```java
import java.util.ArrayList;
import java.util.List;

public class ExtendsReadOnly {
    static void addOne(java.util.List<? extends Number> nums) {
        nums.add(Integer.valueOf(1));
    }

    public static void main(String[] args) {
        System.out.println("not reached");
    }
}
```

```text
编译错误：
error: incompatible types: Integer cannot be converted to CAP#1
where CAP#1 is a fresh type-variable:
  CAP#1 extends Number from capture of ? extends Number
```

报错里的 `CAP#1` 是编译器给"那个未知类型"起的代号：它代表某种 Number 子类，但没人知道具体是哪种。往里放 Integer？万一这个 list 实际是 `List<Double>` 呢——编译器拒绝赌。所以口诀的含义是：**extends 型通配符的 list 是"生产者"，你只从它读数据**。

`? super Integer`（下限通配符）：某种 Integer 的**祖先**类型。能写（放 Integer 一定合法，因为它符合任何祖先的承诺），读出来只能当 Object：

```java
import java.util.ArrayList;
import java.util.List;

public class SuperWrite {
    static void fill(java.util.List<? super Integer> sink) {
        sink.add(1);
        sink.add(2);
    }

    public static void main(String[] args) {
        java.util.List<Number> nums = new ArrayList<>();
        fill(nums);
        System.out.println(nums);
    }
}
```

```text
[1, 2]
```

`fill` 能收 `List<Number>`、`List<Object>`，因为往"Integer 的某种祖先"里放 Integer 永远安全。这就是面试口诀 **PECS（Producer Extends, Consumer Super）**的完整含义：这个类型参数在产生数据给你读，用 `extends`；你在往它写数据，用 `super`。口诀本身没有信息量，有用的是两条推导：extends 只能读，super 只能放心写。

`List<?>` 是 `? extends Object` 的缩写：某种未知类型，同样只读（严格说只能 add null）。它和 `List<Object>` 的区别：`List<Object>` 是"我明确要一个装 Object 的 list，什么都能塞"；`List<?>` 是"我知道这是个 list，但不知道也不关心装什么"，**用来读**。判断泛型时用得上它，下一节结合擦除讲。

## 类型擦除：运行时剩下什么

现在回答这篇最重要的问题：`List<String>` 和 `List<Integer>` 在运行时还有区别吗？

```java
import java.util.ArrayList;
import java.util.List;

public class Erasure {
    public static void main(String[] args) {
        List<String> a = new ArrayList<>();
        List<Integer> b = new ArrayList<>();
        System.out.println(a.getClass() == b.getClass());
        System.out.println(a.getClass().getName());
    }
}
```

```text
true
java.util.ArrayList
```

`getClass()` 返回完全相同的 Class 对象，类名就是 ArrayList，没有任何 String 痕迹。**泛型信息在编译完成后被擦除（type erasure）**：编译器用泛型做全套检查，检查通过后，生成的字节码里 `List<String>` 和 `List<Integer>` 都是原始的 `ArrayList`，类型参数被替换成它的上界（无界就是 Object），取元素的地方由编译器自动插入强转。

Java 5 引入泛型时必须让旧代码（没有泛型的 List）和新代码能互操作，选择了"编译期检查 + 运行期擦除"的方案。这是一笔兼容性换来的债，下面这些"不能"全是它的利息。每一条都能从"运行时已无类型信息"推导出来：

**不能** `new T()`**。** T 只是编译期的占位符，运行时不知道 T 是谁，`new` 什么类都无从谈起：

```java
public class NoNewT {
    static class Box<T> {
        T make() {
            return new T();
        }
    }
}
```

```text
编译错误：
error: unexpected type
  required: class
  found:    type parameter T
```

（报错的意思：new 后面要一个具体类名，T 是个类型参数不是类。）工程解法是**把"怎么创建"作为参数传进来**：传 `Supplier<T>` 或 `Class<T>` 进去，用 `supplier.get()` 或 `clazz.getDeclaredConstructor().newInstance()`，运行时就知道造谁了。

**不能** `new T[]`**、**`new ArrayList<String>[10]`**。** 数组如果装着"宣称 List 的元素"，运行时却检查不了它是 List（擦除了），协变数组的运行期自检（上一节 ArrayStoreException）对泛型类型根本做不了，干脆禁止创建。数组只能用"可检查"的类型：`new String[10]`、`new List[10]`（原始类型）合法。

**不能** `instanceof List<String>`，同样是擦除——运行时没有 String 信息可比：

```java
import java.util.ArrayList;

public class NoInstanceOf {
    public static void main(String[] args) {
        Object o = new ArrayList<String>();
        if (o instanceof java.util.List<String>) {
        }
    }
}
```

```text
编译错误：
error: Object cannot be safely cast to List<String>
```

变通：`instanceof List<?>` 合法（无界通配符不需要类型信息，只判断"是不是 List"），元素类型交给逐个 instanceof 或者不查。

**静态成员不能用类的类型参数。** 类型参数跟着对象实例走，静态成员属于类。`static T cached;` 编译错误，要缓存就用独立的泛型方法或 Class 对象做键。

最后补一个经常被问的点：既然运行时都擦掉了，那 `@SuppressWarnings("unchecked")` 的强转 `(List<String>) obj` 是怎么回事？编译器信你一次，不检查也不插运行时检查（插不了），后果自负。这是泛型和遗留代码边界的逃生门，业务代码里应该极少出现。

## LinkForge 里的真实代码

### 泛型接口的继承：BaseMapper

`src/main/java/com/linkforge/link/mapper/ShortLinkMapper.java` 全文的核心是一行：

```java
@Mapper
public interface ShortLinkMapper extends BaseMapper<ShortLink> {
    @Select("SELECT code FROM lf_short_link")
    List<String> selectAllCodes();
}
```

`BaseMapper<T>` 是 MyBatis-Plus 的泛型接口，`insert(T)`、`selectById(Serializable)`、`updateById(T)`、`deleteById(Serializable)` 全套 CRUD 方法定义在它身上。`extends BaseMapper<ShortLink>` 把 T 固定成 ShortLink，编译器于是把这套方法全部特化成 `insert(ShortLink)`、`updateById(ShortLink)`。你没有写过一行 SQL 的增删改查，类型安全完全由泛型接口的参数化提供。项目里五个模块的 Mapper（`user`、`link`、`stats`、`apikey`、`webhook`）都是这一个模式。注意 `selectAllCodes()` 返回 `List<String>`——泛型让"查出来是字符串列表"成为编译器可检查的承诺。

### 泛型分页 DTO

`src/main/java/com/linkforge/common/result/PageResponse.java`：

```java
public class PageResponse<T> {
    private List<T> records;
    private long total;
    private long current;
    private long size;
    private long pages;
}
```

分页的元信息（总数、页码）对任何实体都一样，变的只有"装的是什么记录"。一个 `PageResponse<T>` 覆盖短链分页、点击分页、API Key 分页。同一个思想也出现在 Spring 的 `ResponseEntity<T>` 和项目自己的 `ApiResponse<T>` 上：**骨架复用 + 数据类型参数化**，这就是泛型最常见的工程用途。

### 方法引用交给泛型条件构造器

`src/main/java/com/linkforge/link/service/ShortLinkService.java`：

```java
ShortLink link = shortLinkMapper.selectOne(new LambdaQueryWrapper<ShortLink>().eq(ShortLink::getCode, code));
```

`LambdaQueryWrapper<ShortLink>` 把 T 定为 ShortLink，于是 `ShortLink::getCode` 这个方法引用的返回类型 String 被编译器用来核对列类型。`eq(ShortLink::getTitle, x)` 传错类型会在编译期报错。这一行同时踩着三个机制：泛型（本篇）、方法引用（[Lambda 与 Stream](10-08-lambda-stream-optional.md)）、MyBatis-Plus 的框架实现（框架行为，不是语言行为）。

## 常见错误清单

| 错误 | 实际发生什么 | 修正 |
|------|-------------|------|
| 期待 `List<String>` 能赋给 `List<Object>` | 编译错误，泛型不变 | 用 `List<?>` 读，或 `List<? super T>` 写 |
| `new T()` / `new T[n]` | 编译错误，运行时无类型信息 | 传 Supplier/Class 参数来创建 |
| `instanceof List<String>` | 编译错误 | `instanceof List<?>`，元素另查 |
| 静态字段用类的 T | 编译错误 | 方法级类型参数，或 Class 做键 |
| 往 `? extends` 的集合里 add | 编译错误（CAP#1） | extends 口子只读 |
| 原始类型 `List list = new ArrayList()` | 绕过检查，Java 5 前写法 | 永远写全泛型参数 |
| `(List<String>) obj` 强转加 @SuppressWarnings | 检查被跳过，坑留给运行期 | 重新设计边界，让泛型自然流动 |

## 面试追问

**第一层：什么是类型擦除？**
编译器用泛型做编译期检查，检查后从字节码里移除类型参数：无界参数替换为 Object、有界替换为上界，使用处自动插入强转。`List<String>` 和 `List<Integer>` 运行时是同一个 Class。

**第二层：为什么这么设计？**
Java 5 要与海量存量非泛型代码二进制兼容，编译期检查加运行期擦除是代价最小的迁移方案。代价是运行期拿不到泛型信息，派生出 new T()、泛型数组、instanceof 参数化类型这一串限制。

**第三层：List 为什么不能赋给 List？说清楚不加这个限制会发生什么。**
赋值成功后两个引用指向同一对象，通过 List 口子 add 一个 Integer，List 承诺被击穿，读出 Integer 强转 String 抛 ClassCastException。泛型选择在编译期掐断；数组是反例（协变，运行期 ArrayStoreException）。

**第四层：PECS 是什么，举例什么时候用。**
Producer Extends Consumer Super。从集合读数据（集合是生产者）用 `? extends T`；往集合写（集合是消费者）用 `? super T`。例：`Collections.copy` 的签名 `copy(List<? super T> dest, List<? extends T> src)`，src 只读 dest 只写，一处签名同时覆盖 List 拷进 List 这类组合。

**第五层：擦除下怎么拿到 T 的 Class？**
传参：构造器或方法收 `Class<T>`，存成字段（这也是很多框架的做法）；或用超类型令牌等技巧。Spring 的 `ParameterizedTypeAware` 系列能从父类签名恢复泛型参数，因为**类声明上的泛型**（如 `BaseMapper<ShortLink>`）保留在字节码的签名属性里，能反射读取——擦除擦的是使用处，不是声明处。这条也是 [反射](10-07-annotations-reflection-proxy.md)篇伏笔。

## 自测

1. `List<String>` 能装一个 Integer 吗？在哪一步被拦？

   答：不能，编译期报 incompatible types。
2. `new ArrayList<String>().getClass() == new ArrayList<Integer>().getClass()` 结果和原因？

   答：true。类型擦除，运行时都是 ArrayList。
3. 为什么不能 `new T()`？工程上怎么绕？

   答：运行时不知道 T 是哪个类；传 Supplier 或 Class 进来。
4. `List<? extends Number>` 能 add 吗？为什么？

   答：不能。它代表某种未知的具体子类型，add 任何类型都可能击穿那个未知承诺。
5. `instanceof List<String>` 和 `instanceof List<?>` 哪个合法，为什么？

   答：后者。擦除后运行时只知道"是不是 List"，不知道元素类型。

## 下一步

- `ShortLink::getCode` 这种方法引用和 Lambda 的完整机制：[Lambda 与 Stream](10-08-lambda-stream-optional.md)。
- 泛型信息如何在运行时被框架反射读回：[注解、反射与动态代理](10-07-annotations-reflection-proxy.md)。
- 集合 API 与泛型结合的全景：[集合框架](../11-java-collections.md)。
