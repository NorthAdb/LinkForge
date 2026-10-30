# Java 语言基础：系列总览与学习导航

优先级 P0。这一章从原来的单篇拆成了十篇，按依赖顺序编号。本篇是导航：告诉你每篇解决什么问题、按什么顺序读、学到什么程度算过关。

## 为什么拆成十篇

旧版把基本类型、面向对象、String、泛型、异常、注解、反射、代理、Lambda、Stream、日期时间、Java 17 塞在一篇里，每个概念只有几行结论。结论对零基础读者毫无用处：你需要的是**每个概念从问题出发、看到可运行的代码、看到真实输出、看到反例**。现在每个主题独立成篇，所有示例代码都经过真实编译运行验证（仓库里的 `verify_examples.py` 会逐一编译运行并比对文档声称的输出），项目引用全部核验过真实路径。

## 学习路径

严格按编号顺序读。每篇开头都有"从一个问题开始"，先别跳。

| 篇 | 主题 | 一句话 | 关键产出 |
|----|------|--------|---------|
| [10-01](java/10-01-types-and-references.md) | 类型与引用 | 变量里装的是什么：值还是线索 | 解释 ==、NPE、参数传递的一切现象 |
| [10-02](java/10-02-oop.md) | 类与多态 | 对象怎么定义、怎么多态 | 读懂任何 Service/Entity 的结构 |
| [10-03](java/10-03-equality-and-strings.md) | 相等性与 String | "相同"的两种含义与 hashCode 契约 | 解释 HashMap 键失踪、字符串池 |
| [10-04](java/10-04-wrappers-and-boxing.md) | 包装类型 | 当"没有值"也要能装 | 拆箱 NPE、缓存区间全部看穿 |
| [10-05](java/10-05-generics.md) | 泛型 | 把类型错误挡在编译期 | 擦除与它的一串"不能"都能推导 |
| [10-06](java/10-06-exceptions.md) | 异常 | 失败是正常业务的一部分 | checked/unchecked 是工程判断不是口诀 |
| [10-07](java/10-07-annotations-reflection-proxy.md) | 注解反射代理 | 框架魔法的三个零件 | 手写动态代理，看懂 Spring 的根 |
| [10-08](java/10-08-lambda-stream-optional.md) | Lambda 与 Stream | 行为当值传，管道替代循环 | 惰性求值、Optional 边界 |
| [10-09](java/10-09-enum-datetime-bigdecimal.md) | 枚举与日期 | 标准库高频件 | 枚举带属性、java.time 选型、BigDecimal 陷阱 |
| [10-10](java/10-10-java17.md) | Java 17 语法 | record、sealed、文本块、模式匹配 | 版本地图背下来，避免预览特性陷阱 |

依赖最重的三条主线：

1. **引用模型**（10-01）→ 相等性（10-03）→ 集合（[11](11-java-collections.md)）。
2. **类与多态**（10-02）→ 异常继承链（10-06）→ sealed/record（10-10）。
3. **注解/反射/代理**（10-07）→ Lambda（10-08）→ Spring 全家（[30](30-spring-core.md)）。

## 十条必带走的结论

每篇一个，读完应能不看书复述：

1. **赋值永远复制变量里的内容**。基本类型复制值，引用类型复制引用。方法里"沿引用改对象"外部可见，"重新赋引用"不可见。（[10-01](java/10-01-types-and-references.md)）
2. **多态的判定分两个时刻**：编译期按声明类型检查方法存在，运行期按实际对象分派实现；字段不参与多态。（[10-02](java/10-02-oop.md)）
3. **equals 相等则 hashCode 必须相等**，违反它 HashMap 直接算错；String 不可变是字符串池、hashCode 缓存、键安全、线程安全四件事的共同前提。（[10-03](java/10-03-equality-and-strings.md)）
4. **装箱 = valueOf（有缓存），拆箱 = intValue()（null 即 NPE）**。-128..127 的缓存是规范保证，区间外是实现自由。（[10-04](java/10-04-wrappers-and-boxing.md)）
5. **泛型是编译期检查加运行期擦除**。运行时 List<String> 和 List<Integer> 是同一个 Class，new T()、泛型数组、instanceof 参数化类型全是擦除的利息。（[10-05](java/10-05-generics.md)）
6. **业务异常走 unchecked 是工程惯例不是语言规则**：checked 污染每层签名且 lambda 不兼容；翻译成 HTTP 响应的活交给全局处理器一处完成。（[10-06](java/10-06-exceptions.md)）
7. **注解是元数据，反射是运行时读信息，代理是运行时造替身**。三者可独立工作，框架把它们组合起来；同类自调用不走代理是事务失效第一大原因。（[10-07](java/10-07-annotations-reflection-proxy.md)）
8. **Stream 是惰性管道**：中间操作只登记，终结操作才执行且逐元素流完全程；Optional 只用于返回值。（[10-08](java/10-08-lambda-stream-optional.md)）
9. **枚举是实例个数固定的类**，可带字段方法；日期选型"存库用 LocalDateTime、对时用 Instant、展示才用 ZonedDateTime"；金额用 BigDecimal 且比较用 compareTo。（[10-09](java/10-09-enum-datetime-bigdecimal.md)）
10. **record/sealed/文本块/instanceof 模式在 17 全部正式，switch 模式匹配 21 才转正**。LinkForge 的 if 链加兜底 throw 正是这个版本边界留下的写法。（[10-10](java/10-10-java17.md)）

## 症状速查

写代码遇到这些现象，按表索骥回炉：

| 症状 | 大概率原因 | 回哪篇 |
|------|-----------|--------|
| NullPointerException | null 引用被解引用（拆箱、Map 取值、链式调用） | [10-01](java/10-01-types-and-references.md)、[10-04](java/10-04-wrappers-and-boxing.md) |
| `==` 比字符串有时对有时错 | 比了对象同一性而非内容 | [10-03](java/10-03-equality-and-strings.md) |
| 放进 Set/Map 的对象"找不到" | equals/hashCode 契约破坏或键被修改 | [10-03](java/10-03-equality-and-strings.md) |
| 修改 String "不生效" | 方法返回新对象，返回值没接住 | [10-03](java/10-03-equality-and-strings.md) |
| `new T()`、`new T[]` 编译不过 | 类型擦除，运行时无 T | [10-05](java/10-05-generics.md) |
| `@Transactional` 方法自调用不回滚 | 没经过代理对象 | [10-07](java/10-07-annotations-reflection-proxy.md) |
| 贴了注解没反应 | 注解无人读：Retention 档位、扫描范围、生效条件 | [10-07](java/10-07-annotations-reflection-proxy.md) |
| Stream 构建后什么都没发生 | 忘了终结操作 | [10-08](java/10-08-lambda-stream-optional.md) |
| switch 模式匹配编译报错 | Java 17 没这特性，21 才转正 | [10-10](java/10-10-java17.md) |
| 0.1+0.2 不等于 0.3 | 二进制浮点精度 | [10-09](java/10-09-enum-datetime-bigdecimal.md) |

## 常见误解澄清

这五句话流传很广，每一句都有一处关键错误，系列正文里有完整推导：

1. "引用就是内存地址。" 引用指向对象这一点像地址，但你不能运算它、读不出数值、对象还会被 GC 搬家。说"引用是 JVM 解读的指向"才准确。（[10-01](java/10-01-types-and-references.md)）
2. "Java 是引用传递。" Java 只有值传递；传引用类型时复制的是引用这个值。C++ 的 `int&` 那种引用传递 Java 没有。（[10-01](java/10-01-types-and-references.md)）
3. "泛型只是语法糖。" 对使用者的确省了强转，但它有真实的类型检查语义（不变性）与真实的运行期后果（擦除）。数组协变 vs 泛型不变的对照说明了这不是"糖"能解释的。（[10-05](java/10-05-generics.md)）
4. "注解就是靠反射生效的。" @Override 靠编译器，Lombok 靠编译期注解处理器，只有 RUNTIME 档注解才走反射。（[10-07](java/10-07-annotations-reflection-proxy.md)）
5. "checked 异常是失败设计。" 它在"调用方能针对性恢复"的场景（重试、换路径）依然是好设计；业务代码选 unchecked 是分层结构下的工程惯例，不是无条件规则。（[10-06](java/10-06-exceptions.md)）

## 三种读法

- **第一遍（建立模型）**：按编号顺序精读 10-01 到 10-07，每篇的示例亲手跑一遍（复制进 `Xxx.java`，`javac --release 17 -encoding UTF-8 Xxx.java && java Xxx`）。10-08 到 10-10 可以先速读建立印象。
- **读项目前（临阵磨枪）**：只读每篇的"LinkForge 里的真实代码"一节，把提到的源文件打开对照。
- **面试前**：读每篇末尾的"面试追问"，再过一遍上面的"十条必带走的结论"。

## 学习节奏建议

按 [00-roadmap.md](00-roadmap.md) 的周计划，本系列的合理节奏是两周：

- **第一周**：10-01、10-02、10-03、10-04（引用模型与对象世界）。每篇的示例全部亲手跑过，自测全对再前进。
- **第二周**：10-05 到 10-10（泛型到 Java 17）。10-07 的手写动态代理示例务必逐行敲一遍，它直接决定你读 30 号 Spring 文档时是"看懂"还是"看晕"。

每周对照一次"通关标准"的四问。跑不动的示例优先怀疑环境（Windows 编码、JDK 版本），环境排查见 [22-tools.md](22-tools.md)。

## 通关标准

读完整个系列后，自问四个问题，答不上就回对应篇：

1. `Integer a=128, b=128; a==b` 为什么是 false？（10-01、10-04）
2. 重写 equals 不重写 hashCode，HashMap 里会发生什么？为什么？（10-03）
3. Spring 的 `@Transactional` 用了哪三个语言机制？同类自调用为什么失效？（10-07）
4. `sealed` + `switch` 比 `if` 链强在哪？为什么 LinkForge 用的是 if 链？（10-10）

## 与其他文档的关系

- 向下依赖：无。本系列是全库的起点。
- 向上支撑：[集合](11-java-collections.md)、[JVM](12-jvm.md)、[并发](13-concurrency.md)、[Spring](30-spring-core.md) 全部建立在本系列之上。
- 项目对照：每篇都有"LinkForge 里的真实代码"一节，路径引用全部经过核验；读完某篇后去读对应源文件是最佳巩固方式。

## 自测

1. 本系列的示例代码怎么验证是真的？ 答：`python docs/learning/verify_examples.py` 会编译运行每个配对示例并比对文档声称的输出。
2. 系列的主线依赖是哪三条？ 答：引用模型→相等性→集合；类与多态→异常→sealed/record；注解反射代理→Lambda→Spring。
3. 遇到没讲过的术语先去哪查？ 答：[术语表](93-glossary-review.md)，然后回到对应篇的"准确定义"小节。

## 下一步

按顺序开始 [10-01 类型与引用](java/10-01-types-and-references.md)。
