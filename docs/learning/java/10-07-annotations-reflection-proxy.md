# 注解、反射与动态代理：框架魔法的三个零件

优先级 P0，目标层级 L3。前置：[类与多态](10-02-oop.md)（接口与多态）、[Lambda 与 Stream](10-08-lambda-stream-optional.md) 建议先读或后读（本章的代理处理器用到了 Lambda）。这是整个系列里离 Spring 最近的一篇：Spring 的依赖注入、事务、`@MapperScan` 生成的 Mapper，全部由这三个机制组装。先把三个机制**分开**讲透各自的职责和边界，再看你手写把它们拼起来的最小组合，最后回到项目。

## 先纠正一个流传的说法

"注解靠反射和动态代理起作用"这句话常被压缩成"注解就是靠反射生效的"，它是错的。三个机制职责不同，互相独立：

- **注解**是贴在代码上的元数据（标签）。它自己**不执行任何代码**，标签贴上去之后有没有人读、谁来读，是另一回事。
- **反射**是程序在**运行时**读取和操作类结构的 API。它能读到 RUNTIME 保留的注解，也能读字段、调方法、绕过 private。
- **动态代理**是运行时**生成一个实现某接口的新类**，把调用拦截到你的代码里再转发。

组合关系是"可以但不必须"：`@Override` 靠编译器读（编译期检查），根本不用反射；Lombok 的 `@Data` 靠编译期注解处理器改字节码生成（也不是反射）；AOP 的切点可以不依赖任何注解。反过来说，反射和代理离开注解照样工作。这篇按"每个机制单独讲清 → 最小组合 → 框架怎么组合它们"的顺序走。

## 注解：标签本身不干活

```java
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public class AnnoDemo {
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface Trace {
        String value();
    }

    @Trace("greet")
    static String greet(String who) {
        return "hi " + who;
    }

    public static void main(String[] args) {
        System.out.println(greet("alice"));
    }
}
```

```text
hi alice
```

`@Trace("greet")` 贴在方法上，程序的输出和没贴它时一模一样——**注解没有执行任何逻辑**。它只是把一条元数据写进了 class 文件。注解用 `@interface` 定义（这个写法容易误导，它不是接口的"接口"，是 Java 对"注解类型"的语法）；带参数就用无参方法的形式声明（`String value();`），使用时 `@Trace("greet")` 赋值。

标签要"被人读"，先要活到读它的那一刻。`@Retention` 决定注解活多久，三个档位：

| RetentionPolicy | 活到什么时候 | 谁会读到它 | 例子 |
|-----------------|-------------|-----------|------|
| `SOURCE` | 只在源码里，编译后丢弃 | 编译器、编译期工具 | `@Override` |
| `CLASS`（默认） | 进 class 文件，运行时不可读 | 字节码工具 | 少用 |
| `RUNTIME` | 运行时仍可读 | 反射 | Spring 的 `@Service`、`@Transactional` |

`@Target` 限制能贴在哪儿（METHOD、TYPE、FIELD、PARAMETER）。`@Override` 是 SOURCE 档的典型：它的唯一工作是让编译器检查"父类里真的有这个方法"（[类与多态](10-02-oop.md)讲过），编译完成后它就没有存在价值了，也不需要任何运行时机制。**一个注解有没有"作用"，取决于有没有程序读它：编译器、编译期处理器、或运行时的框架代码**。

你已经在用的注解可以按这个表格归位：`@Override`、`@FunctionalInterface` 编译器读；Lombok 的 `@Data`、`@Getter`、`@RequiredArgsConstructor` 由 Lombok 的**注解处理器**在编译期读并生成代码（这是一个独立机制，叫 APT，运行期 Lombok 不在场——所以用 Lombok 的类生成的 class 文件里根本没有这些注解的痕迹，也没有生成的 getter 源码，字节码里全是编译期就生成好的方法）；Spring 的 `@Service`、`@RestControllerAdvice`、MyBatis-Plus 的 `@TableName`、`@TableId` 全部 RUNTIME，等框架启动时反射来读。

## 反射：运行时打开类文件看

**反射（reflection）**是标准库提供的一组 API，让程序在运行时拿到"类"本身的信息：类有什么字段、什么方法、什么注解，并且能调用方法、读写字段。普通代码在编译期就固定了要调谁（`user.getName()` 写死），反射把这个决定推迟到运行时。

拿到类的"说明书"对象有三种方式，同一个 `Class` 实例：

```java
public class ClassOf {
    public static void main(String[] args) throws Exception {
        Class<?> a = String.class;
        Class<?> b = "hi".getClass();
        Class<?> c = Class.forName("java.lang.String");
        System.out.println(a == b && b == c);
    }
}
```

```text
true
```

反射最出格的能力是**绕过 private**：

```java
public class ReflectPrivate {
    static class Secret {
        private String text = "hidden";
    }

    public static void main(String[] args) throws Exception {
        Secret s = new Secret();
        java.lang.reflect.Field f = Secret.class.getDeclaredField("text");
        f.setAccessible(true);
        System.out.println(f.get(s));
        f.set(s, "changed");
        System.out.println(f.get(s));
    }
}
```

```text
hidden
changed
```

封装（[类与多态](10-02-oop.md)）的 private 检查是编译期的，反射在运行时工作，`setAccessible(true)` 显式申请绕过检查。演示它不是为了用，而是让你看清一件事：**Java 的封装是编译期约定，不是运行时铜墙铁壁**，框架（Jackson 反序列化、MyBatis 填充实体字段）正是靠这套 API 在不动你的代码的前提下把数据灌进对象的。

反射的能力清单：`getDeclaredMethods()` 拿全部方法、`method.invoke(obj, args)` 调用、`field.get/set` 读写、`isAnnotationPresent`/`getAnnotation` 读注解、`getDeclaredConstructor().newInstance()` 调构造器（[泛型](10-05-generics.md)里 new T() 的绕法就是它）。

边界要说透：

1. **性能**：方法查找有开销、JIT 对反射调用的优化不如直接调用。别背"反射慢十倍"，那是无根据的数字；理解为"有可测量的开销，框架都做了缓存"即可。日常业务代码不该手写反射，性能自然与你无关。
2. **类型安全让位**：编译器帮反射代码查不了类型错，`method.invoke` 的参数错了是运行期才炸。
3. **模块系统的限制**：Java 9 起，跨模块反射未被对方开放（`opens`）时 `setAccessible` 会抛异常。JDK 核心类库对自己加了锁，这就是你偶尔在日志里见到的 `InaccessibleObjectException`。

## 动态代理：运行时造一个"替身"

现在解决一个新问题：给一批方法统一加日志/计时，不想在每个方法里复制粘贴同样的前后逻辑。普通写法是每个方法手写三行"记开始、干活、记结束"。重复出现在几十个方法里，删除任何一处都要找几十个地方。

**动态代理（dynamic proxy）**的方案：不改编写原类，在运行时**生成一个新类**，它实现和原类相同的接口，每个方法的调用都被转交给一段你写的代码（处理器），你在处理器里决定"拦截后干什么、要不要转发给真对象"：

```java
public class ProxyDemo {
    interface Counter {
        int next(String tag);
    }

    static class RealCounter implements Counter {
        int calls = 0;

        public int next(String tag) {
            calls++;
            return calls;
        }
    }

    public static void main(String[] args) {
        RealCounter real = new RealCounter();
        Counter proxy = (Counter) java.lang.reflect.Proxy.newProxyInstance(
                Counter.class.getClassLoader(),
                new Class<?>[] {Counter.class},
                (p, method, margs) -> {
                    System.out.println("before " + method.getName() + " " + java.util.Arrays.toString(margs));
                    Object result = method.invoke(real, margs);
                    System.out.println("after " + method.getName() + " -> " + result);
                    return result;
                });
        System.out.println(proxy.next("a"));
        System.out.println(proxy.next("b"));
    }
}
```

```text
before next [a]
after next -> 1
1
before next [b]
after next -> 2
2
```

逐个拆开这段代码的零件：

- `Proxy.newProxyInstance(类加载器, 接口数组, 处理器)` 让 JVM **现场生成**一个新类（类名形如 `$Proxy0`，包名细节随 JDK 版本不同，不用记），它实现了你给的所有接口。你写的代码里没有任何名为 `$Proxy0` 的类，它是字节码在运行时生成的——和 `new` 一个编译期写好的类不同，这就是"动态"的含义。
- 第三个参数是 `InvocationHandler`。它是只有一个 `invoke` 方法的接口（函数式接口，所以能写成 Lambda，机制见[Lambda 与 Stream](10-08-lambda-stream-optional.md)）。**代理对象的每个方法调用都会变成对 handler.invoke 的一次调用**，参数依次是：代理对象自己、被调的方法（`Method` 对象，反射 API）、实参数组。
- `method.invoke(real, margs)` 用反射把调用转发给真对象。转发前后就是你插入横切逻辑的位置。
- 返回值从 handler 返回给调用方。调用方 `proxy.next("a")` 拿到的值和直接调 `real.next("a")` 一致，但它全程经过了你的拦截代码。

这就是 AOP（面向切面编程）的地基：**业务类保持纯粹，"调用前后要做什么"由代理统一执行**。横切逻辑（日志、事务、限流、鉴权）写一处，作用于一批方法。

边界一：**JDK 动态代理只能代理接口**。生成的类 `extends Proxy implements 你的接口`，Java 单继承把 extends 的名额占了，代理不了"没有接口的普通类"。
边界二：CGLIB 库用"生成子类"的方式代理没有接口的类，代价是 final 类和 final 方法代理不了（子类没法重写它们）。
Spring AOP 两把都用：目标有接口时可以走 JDK 代理，没有接口走 CGLIB；Spring Boot 从 2.x 起默认 `proxyTargetClass=true`，也就是默认 CGLIB。这是框架配置事实，记住结论即可，原理层面两种代理是同一件事：**运行时生成替身 + 拦截转发**。

## 三个零件的最小组合

现在把三者拼起来：**注解当配置，反射读配置，代理执行增强**。一个方法贴上 `@Trace` 就自动获得调用前打印标签的能力，业务方法零改动：

```java
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public class AnnoProxy {
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface Trace {
        String value();
    }

    interface Service {
        @Trace("compute")
        String compute(String input);
    }

    static class RealService implements Service {
        public String compute(String input) {
            return input.toUpperCase();
        }
    }

    public static void main(String[] args) {
        Service real = new RealService();
        Service proxied = (Service) java.lang.reflect.Proxy.newProxyInstance(
                Service.class.getClassLoader(),
                new Class<?>[] {Service.class},
                (p, method, margs) -> {
                    Trace t = method.getAnnotation(Trace.class);
                    if (t != null) {
                        System.out.println("[trace] " + t.value());
                    }
                    return method.invoke(real, margs);
                });
        System.out.println(proxied.compute("hello"));
    }
}
```

```text
[trace] compute
HELLO
```

发生顺序：main 创建代理 → `proxied.compute("hello")` 触发 handler.invoke → 处理器用**反射**从 `Method` 对象上读出**注解** → 有注解就执行增强逻辑 → `method.invoke` 转发给 RealService → 返回 HELLO。

三个机制在这一次调用里各司其职：注解提供"要做什么"的声明（元数据）；反射提供"读到这份声明"的能力（运行时信息）；代理提供"在正确时机执行"的位置（拦截点）。去掉任何一个，另外两个仍能工作——注解不贴代理照样能转发（ProxyDemo 就没有注解），没有注解的反射照样读方法。**"注解 → 反射 → 代理"不是必然因果链，是三个可独立使用的工具，框架选择了把它们组合起来**，组合的方式由框架自己定义。

一个实现细节值得注意：处理器里读的是 `method.getAnnotation`，这个 `method` 来自**接口**，所以注解要贴在接口方法上才能读到。贴在实现类方法上时要用实现类的 Method 才读得到。框架对这种细节都有明确约定，这也是"贴错位置注解不生效"这类问题的根源。

## LinkForge 里的真实代码

### Mapper 接口为什么"没有实现类却能干活"

`src/main/java/com/linkforge/link/mapper/ShortLinkMapper.java`：

```java
@Mapper
public interface ShortLinkMapper extends BaseMapper<ShortLink> {
    @Select("SELECT code FROM lf_short_link")
    List<String> selectAllCodes();
}
```

它是个接口，全项目**没有任何类 implements 它**。可 `shortLinkMapper.selectOne(...)` 每天在跑。真相是：MyBatis 在启动时用 **JDK 动态代理**为这个接口生成了替身对象，替身的 InvocationHandler 里写的逻辑是"从方法签名和注解解析出 SQL → 执行 → 把结果集映射成 List<String> 返回"。你调用的 `shortLinkMapper` 从来不是你写的类，是框架造的 `$Proxy` 对象。`@Select` 注解（RUNTIME 档）就是给这段解析逻辑读的配置；连注解都不写时，MyBatis 去读 XML 里的同名 statement。注解、反射、代理三件套在这里完整登场，装配者是 MyBatis。

### @MapperScan：扫包、逐个造代理

`src/main/java/com/linkforge/LinkForgeApplication.java`：

```java
@SpringBootApplication
// Explicit mapper packages: a broad "com.linkforge" scan also proxied plain
// interfaces like ClickEventPublisher into mapper beans.
@MapperScan({"com.linkforge.user.mapper", "com.linkforge.link.mapper", "com.linkforge.stats.mapper", "com.linkforge.apikey.mapper", "com.linkforge.webhook.mapper"})
public class LinkForgeApplication {
```

`@MapperScan` 让 Spring 在启动时扫描这五个包，**为每个接口生成一个 MyBatis 代理并注册成 Bean**。注释记录了一次真实事故：曾经把扫描范围放宽到整个 `com.linkforge`，结果项目里**任何**接口（比如发消息用的 `ClickEventPublisher`，它根本不是 Mapper）都被套上 Mapper 代理注册成了 Bean，运行时行为错乱。教训是代理和注册是"对扫描到的每个类型无差别执行"的机制——**机制不理解你的业务意图，扫描范围的精确性就是正确性**。这是把"反射 + 动态代理"用出事故的真实案例，比任何正面例子都长记性。

### @Transactional：事务增强怎么发生

`src/main/java/com/linkforge/stats/service/ClickRecordService.java` 和 `src/main/java/com/linkforge/user/service/AuthService.java` 使用了 `@Transactional`。执行时的分工：

- **Spring**（框架行为）在启动时为带 `@Transactional` 的 Bean 生成 AOP 代理（有接口走 JDK 代理，默认配置走 CGLIB 子类代理）；
- 调用方拿到的 Bean 实际是代理对象，方法调用先进代理：**开启事务 → 转发真实方法 → 正常返回就提交、抛 RuntimeException 默认回滚**（默认回滚规则是 Spring 的行为，可配置）；
- **你的代码**只有方法本身和那个注解，一行事务管理代码都不存在。

这就是"注解声明、代理执行"在业务里最值钱的落点：事务边界这种横切逻辑集中由框架管理，业务方法保持纯粹。代价也要知道：代理是"从外部拦截"，**同一个类内部自调用（this.methodB()）不经过代理**，methodB 上的 @Transactional 不生效——这是事务注解失效的第一大原因，根源恰恰在动态代理的拦截位置。

### @ExceptionHandler：注解 + 反射的路由

`GlobalExceptionHandler`（[异常](10-06-exceptions.md)讲过它的职责）里 `@RestControllerAdvice` + `@ExceptionHandler(BizException.class)` 的组合，是"反射读注解 + 反射调方法"的组合：Spring 启动时扫描 Advice 类，反射读取每个方法的 @ExceptionHandler 参数（记录它声明处理哪个异常类型），异常发生时按类型查表找到方法，再反射调用它。没有代理，纯反射路由——又一次说明三件套是自由组合的。

## 常见错误清单

| 错误 | 实际发生什么 | 修正 |
|------|-------------|------|
| 以为贴上注解就自动生效 | 注解只是元数据，要有读者 | 确认框架在扫它（依赖、包路径、生效条件） |
| @Retention 忘了 RUNTIME | 运行时反射读不到，框架"看不见" | 框架读的注解一律 RUNTIME |
| 期待 JDK 代理代理无接口的类 | Proxy.newProxyInstance 抛 IllegalArgumentException | 用 CGLIB（Spring 默认）或抽接口 |
| 同类内部自调用 @Transactional | 不经过代理，事务失效 | 拆到另一个 Bean，或经代理调用 |
| 反射绕过 private 改字段 | 编译期封装失效，隐患埋雷 | 业务代码不写反射，信任框架 |
| @MapperScan 范围过大 | 非业务接口被代理注册，启动或运行错乱（项目真实事故） | 精确列出 mapper 包 |

## 面试追问

**第一层：注解的本质是什么？**
一种元数据机制：@interface 声明的类型，编译后写进 class 文件的属性表。本身不执行逻辑；SOURCE 档由编译器消费，RUNTIME 档由反射消费，CLASS 档给字节码工具。

**第二层：Spring 的依赖注入用到反射吗？怎么用？**
用到。启动时扫描带 @Component/@Service 的类（读 RUNTIME 注解），反射调用构造器创建对象，按类型/名字反射给字段和 setter 赋值。"注入"的本质就是框架在运行时用反射组装对象图。

**第三层：@Transactional 的实现原理？**
Spring AOP 代理。启动时为相关 Bean 生成代理（Spring Boot 默认 CGLIB），调用先进代理，由事务管理器开启事务、转发目标方法、按返回/异常决定提交回滚。因此自调用不经过代理、注解不生效；事务传播、回滚规则都是 Spring 的行为约定。

**第四层：MyBatis 的 Mapper 接口没有实现类，为什么能注入和调用？**
@MapperScan 扫包后为每个接口建 JDK 动态代理注册为 Bean。调用进 InvocationHandler，由它把方法签名 + 注解/XML 翻译成 SQL 执行并映射结果。项目里曾因扫描范围过宽把非 Mapper 接口也代理成 Bean（LinkForgeApplication 注释记录），说明这套机制对扫描到的接口是无差别装配的。

**第五层：反射有什么代价，框架如何缓解？**
方法查找与访问检查有开销、JIT 优化受限、编译期类型检查失效。框架的缓解：启动时一次性解析并缓存（Method/Field 对象、注解元数据），运行期查缓存；必要时用字节码生成（如 CGLIB、LambdaMetafactory）替代逐次反射。业务代码不必手写反射，也就不必付这些代价。

## 自测

1. `@Override` 和 Spring 的 `@Service` 分别由谁在什么时候读？
   答：@Override 是 SOURCE 档，编译器在编译期读（检查重写）；@Service 是 RUNTIME 档，Spring 启动时反射读。
2. 注解不配合任何机制会生效吗？
   答：不会，它只是元数据。生效 = 有程序读它（编译器/注解处理器/反射）。
3. JDK 动态代理的三个参数各是什么？InvocationHandler 的 invoke 在什么时候被调？
   答：类加载器、接口数组、处理器。代理对象的每个方法调用都会转成一次 invoke。
4. 为什么同一个类里 `this.methodB()` 不会触发 methodB 上的 @Transactional？
   答：this 是原始对象不是代理对象，调用没经过拦截逻辑。
5. Lombok 的 @Data 是靠反射工作的吗？
   答：不是。Lombok 是编译期注解处理器，编译时直接生成字节码，运行期它不在场。

## 下一步

- 代理处理器里 `(p, method, margs) -> {...}` 这个 Lambda 的完整语法与函数式接口：[Lambda 与 Stream](10-08-lambda-stream-optional.md)。
- Spring 如何用这些机制组织 Bean 与事务：[Spring 核心](../30-spring-core.md)。
- Mapper 代理背后的 SQL 与结果映射：[MyBatis 与 MyBatis-Plus](../41-mybatis-plus.md)。
