# MyBatis 与 MyBatis-Plus：SQL 从手写到不写

优先级 P0，目标层级 L2（读懂并改写项目里的 Mapper 与 Wrapper 用法），逻辑删除边界与 SQL 注入分界到 L3。

前置：[Spring 核心](30-spring-core.md)（Mapper 怎么被注册成 Bean、事务怎么从代理层开始）、[MySQL](40-mysql.md)（索引与逻辑删除的数据库侧）、[泛型](java/10-05-generics.md)、[注解、反射与动态代理](java/10-07-annotations-reflection-proxy.md)、[Lambda 与 Stream](java/10-08-lambda-stream-optional.md)。

## 学习目标

- 一句话说清 JDBC、MyBatis、MyBatis-Plus 三层各贡献什么：谁定协议、谁管映射、谁生成 SQL。
- 看懂项目里每个 Mapper 与 Wrapper 调用会生成什么形态的 SQL，说出 `deleted=0`、`LIMIT` 各是哪一层加的。
- 说出每个注解（`@MapperScan`、`@TableName`、`@TableId`、`@TableLogic`、`@Select`）被谁在什么时机读取。
- 分清两条 null 规则：`updateById` 跳过 null 字段（更新侧），`eq(col, null)` 一行都查不到（查询侧），并写出两种正确改法。

## 从问题开始：用 JDBC 查一条短链要付多少样板

项目要按短码查一条短链。假设没有 MyBatis，用最原始的 JDBC（Java 访问数据库的标准协议，JDK 的 java.sql 包）写，长这样：

```java
// 片段：需要 MySQL 才能跑，本机不运行，不配输出
String sql = "SELECT id, tenant_id, code, original_url, title, status, expire_at, "
        + "created_by, created_at, updated_at, deleted, access_password_hash "
        + "FROM lf_short_link WHERE code = ? AND deleted = 0";
try (Connection conn = DriverManager.getConnection(url, user, password);
     PreparedStatement ps = conn.prepareStatement(sql)) {
    ps.setString(1, code);                              // 参数按位置绑定
    try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
            ShortLink link = new ShortLink();
            link.setId(rs.getLong("id"));               // 结果逐列手搬
            link.setCode(rs.getString("code"));
            link.setOriginalUrl(rs.getString("original_url"));
            link.setStatus(rs.getInt("status"));
            // ...... 还有 8 列要搬
        }
    }
}
```

业务逻辑只有一句：按 code 查一行，搬进对象。其余全是管道代码，而且每个查询都要重复：

1. **连接管理**。拿连接、用完关闭。池化（连接复用）、事务从哪条连接走，都得自己管。
2. **参数绑定**。`setString(1, code)` 按位置绑定，下标数错没有编译错误，运行时才炸或查错。
3. **结果映射**。`rs.getXxx("列名")` 逐列手搬进对象。12 列的表 × N 个查询，纯体力活，改一列要改所有查询。
4. **SQL 混在 Java 里**。想看一个查询的完整 SQL，得在字符串拼接里找。

## MyBatis：接走执行与映射，SQL 留给你

MyBatis 的核心主张：SQL 本身别动，管道全归我。你把 SQL 写在独立的地方（XML 文件或注解），参数用 `#{}` 按名声明，结果映射交给框架。执行一次查询时 MyBatis 做（框架行为）：

- 从数据源拿一条连接（Spring Boot 项目里连接由 HikariCP 连接池借出与归还）；
- 把 `#{}` 替换成 JDBC 的占位符 `?`，把参数装进 `PreparedStatement`；
- 执行 SQL，拿到 `ResultSet`，按"列名对应属性名"的规则把每行填进对象；
- 还回连接。

`#{}` 是**预编译参数**：SQL 结构先发给数据库编译，参数后到，参数永远只当数据、不会被当成 SQL 语法执行。这是防 SQL 注入的根基，本篇最后还会回到它。

### 结果映射的本质：一行 Map 变一个对象

"结果映射"听起来神秘，本质就是：数据库给框架的是一行"列名到值"的对应关系，框架按规则把这堆值塞进对象字段。下面的程序不连数据库，用一个 `Map` 模拟 `ResultSet` 的一行，手写这个映射过程：

```java
import java.util.LinkedHashMap;
import java.util.Map;

public class RowMapDemo {
    record LinkRow(Long id, String code, String originalUrl, String title) {
    }

    // 手写的"结果映射"：一行（列名 -> 值）进，一个对象出
    static LinkRow mapRow(Map<String, Object> row) {
        return new LinkRow(
                (Long) row.get("id"),
                (String) row.get("code"),
                (String) row.get("original_url"),
                (String) row.get("title"));
    }

    // 模拟 map-underscore-to-camel-case 配置做的转换
    static String camel(String column) {
        String[] parts = column.split("_");
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1L);
        row.put("code", "aB3xK9z");
        row.put("original_url", "https://example.com/a");
        row.put("title", null);

        LinkRow link = mapRow(row);
        System.out.println(link);
        System.out.println(camel("original_url"));
        System.out.println(camel("created_by"));
    }
}
```

```text
LinkRow[id=1, code=aB3xK9z, originalUrl=https://example.com/a, title=null]
originalUrl
createdBy
```

逐行解释：

- `row` 模拟 `ResultSet` 的一行：键是列名，值是这一列的内容。`title` 在库里是 NULL，映射进对象就是 `title=null`，**NULL 变成 null 引用**，这正是实体类可空字段要用包装类型的原因（[包装类型](java/10-04-wrappers-and-boxing.md)讲过 `ShortLink.expireAt` 同样的道理）。
- `mapRow` 就是 MyBatis 干的事的最小版本：按列名取值、按目标类型装入构造参数。MyBatis 的真实版本不用你手写，它按约定自动来：**配置了 `map-underscore-to-camel-case: true` 后，`original_url` 自动映射到 `originalUrl`**（MyBatis 行为），`camel` 方法演示的就是这个转换方向。约定对不上的列才需要你手工指定（XML 的 `resultMap` 或注解）。
- 比喻与边界：把 MyBatis 想成"自动搬列员"是准确的，但它只搬"这一行"，不负责"拿哪几行"，那由 SQL 决定。

## MyBatis-Plus：在 MyBatis 上补"单表 CRUD 的 SQL 生成"

MyBatis 留给你两件事：写 SQL，以及声明 SQL 和字段的对应关系。单表的增删改查（insert、按 id 查、按条件列表、按 id 改、按 id 删）写起来全是重复劳动。MyBatis-Plus（下称 MP）的主张：**单表 SQL 让框架生成，你只描述条件和实体**。

三层关系一句讲清：**JDBC 是协议层，规定 Java 怎么跟数据库说话；MyBatis 是映射层，管 SQL 执行、参数绑定、行到对象的映射；MP 是增强层，在 MyBatis 上补齐"单表 CRUD 的 SQL 生成"。**

一条调用走完三层，拿项目里真实的这行（`ShortLinkService.getOwnedLink`）举例：

```java
// 片段：src/main/java/com/linkforge/link/service/ShortLinkService.java 第 569 行
ShortLink link = shortLinkMapper.selectOne(new LambdaQueryWrapper<ShortLink>().eq(ShortLink::getCode, code));
```

发生顺序（每步都是框架行为）：

1. `shortLinkMapper` 是代理对象，拦下 `selectOne` 调用（MyBatis 的代理，下一节讲它怎么来的）。
2. MP 把 `LambdaQueryWrapper` 里登记的条件拼成 WHERE 片段，并根据实体元数据自动追加 `deleted=0`（MP 的 SQL 生成）。
3. MyBatis 找到这条语句的模板，把条件片段与参数装进去，`#{}` 变 `?`（MyBatis 的执行与参数绑定）。
4. JDBC 驱动通过 `PreparedStatement` 发给 MySQL（协议层）。
5. `ResultSet` 按驼峰映射填进一个新 `ShortLink`（MyBatis 的映射）。

预期 SQL 形态（本机无 MySQL 未实跑，据 MP 3.5.9 行为推断，下同）：

```sql
SELECT id,tenant_id,code,original_url,title,status,expire_at,created_by,created_at,updated_at,deleted,access_password_hash
FROM lf_short_link
WHERE (code = ?) AND deleted=0
```

列清单与顺序取自实体元数据（示意）。 WHERE 里两个条件来自不同的层：括号里的 `code = ?` 来自你写的 Wrapper，`deleted=0` 是 MP 看到实体上有 `@TableLogic` 字段后自动加的，你一行代码都没写。

### BaseMapper&lt;T&gt;：泛型换来的类型安全 CRUD

`ShortLinkMapper` 全文只有一行接口声明加一个手写方法：

```java
// 片段：src/main/java/com/linkforge/link/mapper/ShortLinkMapper.java
@Mapper
public interface ShortLinkMapper extends BaseMapper<ShortLink> {
    @Select("SELECT code FROM lf_short_link")
    List<String> selectAllCodes();
}
```

你从没写过 `insert`、`selectById`、`updateById`、`deleteById` 的 SQL，它们全部定义在 MP 的泛型接口 `BaseMapper<T>` 上。`extends BaseMapper<ShortLink>` 把 T 固定成 `ShortLink`，这套方法就特化成了 `insert(ShortLink)`、`updateById(ShortLink)`（[泛型](java/10-05-generics.md)讲过这个机制）。常用方法速查：

| 方法 | 干什么 |
|------|--------|
| `insert(T)` | INSERT，自增主键回填实体 |
| `selectById(id)` | 按主键查一条 |
| `selectOne(wrapper)` | 按条件查一条，多于一行抛异常 |
| `selectList(wrapper)` | 按条件查列表 |
| `selectPage(page, wrapper)` | 分页查询（要装插件，见下） |
| `updateById(entity)` | 按主键更新非 null 字段 |
| `deleteById(id)` | 删除（逻辑删除时变 UPDATE） |

`selectOne` 敢用是因为 `lf_short_link.code` 上有唯一索引 `uk_code`，按 code 查最多一行（数据库事实，`src/main/resources/db/schema.sql`）。没有唯一性保证的列用 `selectOne`，多于一行时 MP 直接抛异常（框架行为），这是它逼你处理歧义而不是悄悄拿第一条的设计。

MP 还带一个代码生成器（MyBatis-Plus Generator）：读表结构，生成实体、Mapper、Service 的骨架文件。LinkForge 没有用它，实体全是手写的（项目事实），知道有这个能力即可。

## Mapper 接口怎么变成能注入的 Bean

先遇一次痛：`ShortLinkMapper` 是个接口，全项目没有任何类实现它，可 `ShortLinkService` 的构造器参数里就有 `ShortLinkMapper`，Spring 还能正常注入。如果接口不能实例化，这个对象从哪来？

答案分两步，都在启动期完成（框架行为，机制详见[注解、反射与动态代理](java/10-07-annotations-reflection-proxy.md)与[Spring 核心](30-spring-core.md)，这里只说与 MyBatis 相关的部分）：

1. **注册**。`@MapperScan` 让 Spring 扫描指定包里的每个 Mapper 接口，把它们登记成特殊的 Bean 定义（FactoryBean，即"容器来取对象时才现场制造"的 Bean）。制造出来的对象是 MyBatis 用 **JDK 动态代理**现场生成的替身：替身的处理器里写的逻辑是"从方法签名和注解解析出 SQL，执行，把结果集映射成返回值"。
2. **注入**。`ShortLinkService` 拿到的 `shortLinkMapper` 就是这个代理对象。你调用的每个方法都先经过代理，代理再去走上面"三层"的流程。

项目里两个注解各司其职（项目事实）：`@MapperScan` 在 `LinkForgeApplication` 上精确列出五个 mapper 包（`user`、`link`、`stats`、`apikey`、`webhook`）；接口上再贴 `@Mapper` 作为标记。注释记录了一次真实事故：曾经把扫描范围放宽到整个 `com.linkforge`，结果 `ClickEventPublisher` 这种普通接口也被套上 Mapper 代理注册成 Bean，运行时行为错乱。教训与细节 30 号文档已经讲过，不重复。运行时序上记住一条就够：**注册与代理生成都发生在 Spring 启动期，一次；之后每次调用走的是同一个代理对象。**

## 实体注解：启动时解析成元数据，之后每次拼 SQL 都要用

`ShortLink` 实体（`src/main/java/com/linkforge/link/entity/ShortLink.java`）全文 20 行，四个注解各管一件事：

```java
@Data
@TableName("lf_short_link")
public class ShortLink {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String code;
    private String originalUrl;
    private Integer status;
    private LocalDateTime expireAt;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
    private String accessPasswordHash;
}
```

逐个讲，先说清楚一个总机制：**这些注解不是每次执行 SQL 时反射读取的**。MP 启动时（对应 Mapper 注册后首次用到实体时）把实体类解析成一份"表信息"元数据：表名、主键、每个属性对应哪一列、哪个是逻辑删除字段。之后每次生成 SQL 都查这份缓存（框架行为）。所以注解的读取时机是**启动期一次**，影响的是**此后每一次 SQL 生成**。

| 注解 | 谁读 | 时机 | 影响 |
|------|------|------|------|
| `@MapperScan` | Spring + mybatis-spring | 启动期 | 哪些接口生成 Mapper 代理 Bean |
| `@TableName` | MP 元数据解析 | 启动期 | 类对应哪张表 |
| `@TableId(type=AUTO)` | MP 元数据解析 + JDBC 回填 | 启动期解析，插入时回填 | 主键列与生成策略 |
| `@TableLogic` | MP 每次 SQL 生成 | 启动期解析，逐次生效 | 查询加条件、删除改 UPDATE |
| `@TableField` | MP 元数据解析 | 启动期 | 属性与列的对应细则 |
| `@Select` 等 | MyBatis | 启动期解析成语句对象 | 提供手写 SQL |

**`@TableName("lf_short_link")`**：类到表的映射。没有它 MP 会按类名推断表名 `short_link`，而项目的表统一带 `lf_` 前缀，推断必错（据 MP 命名规则推断），所以必须显式声明。

**`@TableId(type = IdType.AUTO)`**：主键映射，`AUTO` 表示主键交给数据库自增（表定义里 `id BIGINT PRIMARY KEY AUTO_INCREMENT`，见 `schema.sql`）。效果在插入后：INSERT 不带 id 列，JDBC 拿回数据库生成的自增值，MP 把它填回实体的 `id` 字段（框架行为）。项目两处依赖这个回填：`doCreate` 插入后直接用 `link.getId()` 拼响应；`ClickRecordService.recordClick`（`src/main/java/com/linkforge/stats/service/ClickRecordService.java`）插入点击日志后用 `"log-" + logRow.getId()` 拼去重键 `eventId`。如果实体 id 是 `long`，"插入前还没有 id"只能用 0 谎报，`Long` 装下"还没有"这个状态（[包装类型](java/10-04-wrappers-and-boxing.md)）。

**`@TableLogic`**：逻辑删除标记，`deleted` 字段，0 未删 1 已删，具体取值来自 `application.yml` 的三行配置（见下文"逻辑删除的全链路"）。它是 MP 行为里渗透最深的一个，专门有一节讲。

**没有 `@TableField`**：项目一个都没写，因为配置了 `map-underscore-to-camel-case: true`，`createdAt` 与 `created_at` 这类对应自动完成。什么时候才需要它：列名不符合驼峰转换规则、字段根本不对应表列（`exist = false`）、或者要调整某个字段参与 insert/update 的策略（框架行为，概念）。约定能解决的别加注解，加了的每个都是一处显式知识。

## 条件构造器 Wrapper：把 WHERE 写成 Java

问题：条件查询的 WHERE 子句怎么来？硬拼字符串（`"... WHERE code = '" + code + "'"`）既有注入风险，列名写错也没人管；全走手写注解 SQL，单表查询又要重复大量模板。MP 的答案是 **Wrapper**：条件构造器，用 Java 方法链描述 WHERE，框架负责拼进 SQL 并把值安全地送进参数。

`LambdaQueryWrapper` 用于查询，`LambdaUpdateWrapper` 用于更新。它们有两个关键性质：

1. **惰性登记**。链式调用每个方法都返回自身、只往内部列表登记一个片段，不执行任何 SQL；到 mapper 方法执行时才拼装。和 [Stream 的惰性](java/10-08-lambda-stream-optional.md)同一个思路：构建与执行分离。
2. **方法引用指定列**。`eq(ShortLink::getCode, code)` 里的 `ShortLink::getCode` 不是调用 getter，而是把"取 code 属性"这个行为本身交给框架，框架从中解析出列名。

### 方法引用怎么变成列名

`ShortLink::getCode` 到手后只是一段行为，没有"code"这三个字。MP 拿到它的第一步是**序列化**：`SFunction`（MP 的函数式接口，定义是 `Function` 加 `Serializable`）的实例可以被序列化，序列化数据里带着"哪个类的哪个方法"这条信息。下面这个可运行程序模拟 MP 解析的主干（MP 真实实现还要加缓存与校验，主干一致，据 MP 源码行为推断）：

```java
import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;

public class LambdaNameDemo {
    static class ShortLink {
        public String getCode() { return "x"; }
        public Long getCreatedBy() { return 1L; }
    }

    // MP 的真实接口叫 SFunction，定义是 Function + Serializable
    interface Getter<T> extends java.util.function.Function<T, Object>, Serializable {
    }

    public static void main(String[] args) throws Exception {
        resolve(ShortLink::getCode);
        resolve(ShortLink::getCreatedBy);
    }

    // 模拟 MP 把方法引用解析成列名的主干步骤
    static void resolve(Getter<ShortLink> ref) throws Exception {
        Method writeReplace = ref.getClass().getDeclaredMethod("writeReplace");
        writeReplace.setAccessible(true);
        SerializedLambda sl = (SerializedLambda) writeReplace.invoke(ref);
        String name = sl.getImplMethodName();
        String prop = Character.toLowerCase(name.charAt(3)) + name.substring(4);
        String column = prop.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
        System.out.println(name + " -> " + prop + " -> " + column);
    }
}
```

```text
getCode -> code -> code
getCreatedBy -> createdBy -> created_by
```

三步链条：

1. 反射调用方法引用对象上的 `writeReplace`，拿到 `SerializedLambda`，里面有"实现类、方法名、实例化类型"。MP 还会用实例化类型校验"这个方法引用挂在哪个实体上"，传错实体的方法引用在这里现形（框架行为）。
2. 方法名 `getCreatedBy` 去掉 `get`、首字母小写，得到属性名 `createdBy`。
3. 驼峰转下划线，得到列名 `created_by`（同 `map-underscore-to-camel-case` 的逆操作）。

所以手写列名字符串的两种错误都被消掉了：拼错列名编译不过（方法引用只存在真实的方法）；类型不匹配（`eq(ShortLink::getId, "abc")`）编译器直接报错，因为泛型把方法引用的返回类型传给了 `eq` 的参数类型（[泛型](java/10-05-generics.md)、[Lambda 与 Stream](java/10-08-lambda-stream-optional.md)的"第四层追问"讲的正是这条链）。

### eq 传 null 不是"跳过条件"：两种 null 规则必须分清

这是 MP 最著名的坑，本项目真实踩过（清空短链密码，代码注释引用决策 D-Bw345-32）。用另一个可运行程序演示（模拟 Wrapper 的登记与拼装，确定性输出）：

```java
import java.util.ArrayList;
import java.util.List;

public class WrapperDemo {
    static class Where {
        private final List<String> conditions = new ArrayList<>();
        private final List<String> orders = new ArrayList<>();
        private final List<Object> params = new ArrayList<>();

        Where eq(boolean cond, String column, Object val) {
            if (!cond) {
                return this;
            }
            conditions.add(column + " = ?");
            params.add(val);
            return this;
        }

        Where isNull(String column) {
            conditions.add(column + " IS NULL");
            return this;
        }

        Where orderByDesc(String column) {
            orders.add(column + " DESC");
            return this;
        }

        String sql() {
            String where = String.join(" AND ", conditions);
            if (!orders.isEmpty()) {
                where = where + " ORDER BY " + String.join(", ", orders);
            }
            return where;
        }

        String params() {
            return params.toString();
        }
    }

    public static void main(String[] args) {
        String code = "aB3xK9z";
        Integer status = null;

        Where w1 = new Where().eq(true, "code", code);
        System.out.println(w1.sql());

        Where w2 = new Where().eq(status != null, "status", status);
        System.out.println("[" + w2.sql() + "]");

        Where w3 = new Where().eq(true, "status", status);
        System.out.println(w3.sql());
        System.out.println(w3.params());

        Where w4 = new Where().isNull("expire_at");
        System.out.println(w4.sql());

        Where w5 = new Where().eq(true, "created_by", 7L).orderByDesc("id");
        System.out.println(w5.sql());
    }
}
```

```text
code = ?
[]
status = ?
[null]
expire_at IS NULL
created_by = ? ORDER BY id DESC
```

六行输出对应五种情形：

- `w1`：正常等值条件，`?` 是占位符，值进参数。
- `w2`：**布尔参数重载** `eq(condition, column, val)`，条件表达式为 false 时整个条件不登记，拼出的 WHERE 是空的。这是"值没传就不管这列"的正确写法。
- `w3`：**直接给 `eq` 传 null**。条件照常登记，SQL 是 `status = ?`，参数是 null。到数据库里就是 `status = NULL`：SQL 的三值逻辑里，NULL 参与 `=` 比较，结果不是 TRUE 而是 UNKNOWN，任何行都不满足，**一行都查不出来**（MyBatis-Plus 与 MySQL 的框架/数据库行为；`eq(col, null)` 生成 `col = NULL` 查不到行这条结论与 [包装类型](java/10-04-wrappers-and-boxing.md)的纠错一致）。
- `w4`：想查"这一列没有值"，用的从来不是 `=`，是 `IS NULL`。MP 对应 `isNull(col)`。
- `w5`：条件与排序共存，`ORDER BY` 不参与 AND 拼接。

初学者为什么会写出 `w3`？两个来源：把 MP 当 `Map.get` 那类"传 null 表示没这个键"的约定；或者从 `updateById` 的行为错误外推。`updateById` 的规则确实是"null 字段跳过"（见下一小节），但那是**更新语句 SET 子句**的规则，不是查询条件的规则。两条规则并排放：

| 场景 | null 的含义 |
|------|------------|
| `updateById` 的实体字段为 null | SET 子句跳过该字段，不更新 |
| `update` 的 wrapper 里 `set(col, null)` | 显式 `SET col = NULL`，能清空 |
| `eq(col, null)` | 生成 `col = ?` 且参数 null，一行都查不到 |
| `eq(cond, col, val)` 的 cond 为 false | 该条件整个不出现 |

正确写法按意图选：值没传就不过滤，用布尔重载；明确要匹配 NULL，用 `isNull`；明确要清空列（更新侧），用 `set(col, null)`。

### updateById 与 update(null, wrapper)：更新侧的两个入口

`updateById(entity)` 按 `entity.id` 更新，**SET 子句只包含非 null 字段**（MP 默认字段策略 NOT_NULL，框架行为）。这让"部分更新"很方便：只改过的字段会被写入。代价是它天然表达不了"把某列清成 NULL"，因为 null 被解释成"不改"。此时用另一个入口：`update(entity, wrapper)`，第一个参数传 null（SET 全部来自 wrapper），wrapper 里 `set(列, null)` 显式生成 `SET col = NULL`。

### 项目案例：ShortLinkService 三处调用逐行读

需求背景：管理端要查自己的短链、分页列出、以及清空访问密码。看 `src/main/java/com/linkforge/link/service/ShortLinkService.java` 里三种典型用法。

第一处，按 code 查一条（`getOwnedLink`，第 569 行）：

```java
// 片段
ShortLink link = shortLinkMapper.selectOne(new LambdaQueryWrapper<ShortLink>().eq(ShortLink::getCode, code));
if (link == null || link.getCreatedBy() == null || !link.getCreatedBy().equals(user.getUserId())) {
    throw new BizException(404, "short link not found");
}
```

预期 SQL 形态（推断）：

```sql
SELECT id,tenant_id,code,original_url,title,status,expire_at,created_by,created_at,updated_at,deleted,access_password_hash
FROM lf_short_link
WHERE (code = ?) AND deleted=0
```

归属权检查（是不是本人创建）放在 Java 里做，SQL 只负责按 code 取行（项目逻辑）。注意查不到或非本人统一返回 404，不区分"不存在"与"不是你的"，这是安全上不泄露存在性的常见做法。

第二处，分页列出本人链接（`pageOwn`，第 211 行）：

```java
// 片段
Page<ShortLink> result = shortLinkMapper.selectPage(new Page<>(current, pageSize),
        new LambdaQueryWrapper<ShortLink>()
                .eq(ShortLink::getCreatedBy, userId)
                .orderByDesc(ShortLink::getId));
```

预期 SQL 形态（推断，分页插件装好时是两条）：

```sql
SELECT COUNT(*) FROM lf_short_link WHERE (created_by = ?) AND deleted=0

SELECT id,tenant_id,...,access_password_hash
FROM lf_short_link
WHERE (created_by = ?) AND deleted=0
ORDER BY id DESC LIMIT ?,?
```

先 COUNT 算总数（简单查询会去掉 ORDER BY 再数，据 MP 的 count 优化推断），再带 LIMIT 取当页。`LIMIT ?,?` 的两个参数由分页插件按页码与页大小算出。`new Page<>(current, pageSize)` 作为第一个参数传入是 MP 分页的固定姿势；返回的 `result` 上取 `getRecords()`、`getTotal()`、`getPages()`，再转成项目统一的 `PageResponse`（[泛型](java/10-05-generics.md)讲过这个 DTO）。

第三处，显式 SET NULL 清空密码（`update`，第 292 行）：

```java
// 片段
shortLinkMapper.update(null, new LambdaUpdateWrapper<ShortLink>()
        .eq(ShortLink::getId, link.getId())
        .set(ShortLink::getAccessPasswordHash, null));
```

预期 SQL 形态（推断）：

```sql
UPDATE lf_short_link SET access_password_hash=? WHERE (id = ?) AND deleted=0
```

`access_password_hash=?` 的参数是 null，落库即 `SET access_password_hash = NULL`，清空成功。同一方法稍后还有一行 `shortLinkMapper.updateById(link)`（第 297 行）负责更新其余字段，它跳过刚被置 null 的密码字段，恰好不打架。顺序是刻意安排的：先显式清空，再整体更新（项目逻辑，见该处注释）。

## 分页：插件改写 SQL，不是 MySQL 关键字

先遇痛：`selectPage` 听起来是 MP 内置能力，装没装插件效果天差地别。**MP 的分页靠拦截器实现**：MyBatis 允许插件在 SQL 执行链路的固定位置插入自己的处理（Interceptor 机制），分页插件 `PaginationInnerInterceptor` 就在 SQL 发往数据库前把一条查询改写成两条（先 count 后取页）。项目的配置（`src/main/java/com/linkforge/config/MybatisPlusConfig.java` 全文）：

```java
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
```

`MybatisPlusInterceptor` 是 MP 插件的容器，可装多个内部插件；`DbType.MYSQL` 告诉它按 MySQL 方言拼 LIMIT。**没装这个 Bean 时，`selectPage` 不会拼 LIMIT**：records 拿到全量、total 停在 0（据 MP 实现推断），这就是"分页不生效"的第一排查项。`new Page<>(current, pageSize)` 里两个参数怎么算 LIMIT、count 结果怎么填回 `total`，全是插件干的（框架行为）。分页解决不了慢：页码越深扫描越多，深分页要换游标写法 `WHERE id > lastId LIMIT n`（EXPLAIN 验证见 [MySQL](40-mysql.md)）。

## 逻辑删除的全链路

问题：删除一条短链后，这个码还能被别人创建吗？项目的答案是**不能**，而实现它的不是数据库，是 MP 的逻辑删除机制加唯一索引。

`application.yml` 里的三行全局配置（项目事实）：

```yaml
mybatis-plus:
  global-config:
    db-config:
      logic-delete-field: deleted
      logic-delete-value: 1
      logic-not-delete-value: 0
```

配合 `ShortLink` 实体上 `@TableLogic private Integer deleted`，MP 对所有它生成的 SQL 做统一改写（框架行为）：

- **查询**：任何 BaseMapper 方法和 Wrapper 查询自动追加 `AND deleted=0`，已删行对业务查询隐身。
- **删除**：`deleteById(id)` 不再是 DELETE，翻译成 UPDATE：

  ```sql
  UPDATE lf_short_link SET deleted=1 WHERE id=? AND deleted=0
  ```

- **更新**：UPDATE 的 WHERE 同样追加 `AND deleted=0`，不能顺手改已删的行。

`ShortLinkService.delete`（第 329 行）调的就是这个 `deleteById`，javadoc 写明意图：**Logical delete: the code stays reserved (uk_code) and cannot be reused**（项目逻辑）。链路合起来看：逻辑删除让行还在，行还在则 `uk_code` 唯一索引还占着这个码，于是删除过的短码永远无法被重新创建，重新创建同一 code 会撞唯一键抛 `DuplicateKeyException` 变 409。这是"逻辑删除 + 唯一索引"两个机制叠加出的业务特性，不是巧合（[MySQL](40-mysql.md)的"逻辑删除与码位保留"一节讲过数据库侧）。

代价要心里有数：所有 MP 查询都多一个条件（索引设计要配合）；想查全量（包括已删行）必须绕过 MP，这正是下一节的事。

## 原生 SQL：什么时候绕开 MP 的自动行为

MP 的自动改写（逻辑删除、字段策略）不是永远正确。项目有两个真实案例说明"我就是需要全量/需要方言 SQL"时怎么写。

### selectAllCodes：绕过逻辑删除给 Bloom 播种

需求：跳转路径用一个进程内的布隆过滤器（Bloom filter，一个"可能存在/一定不存在"的位判断结构）挡掉从未创建过的短码，不让它们打到 MySQL。应用启动时要把**所有**历史上出现过的码播种进过滤器，包括逻辑删除的行。为什么包括已删行？它们的 `uk_code` 还占着，如果过滤器不含这些码，用户访问一个已删短码会在 Bloom 层被误判"从未存在"直接 404，跟数据库的行为（deleted=0 过滤后 404）碰巧一致；但危险的是反方向的认知，绕过数据库的统一路径会让排查变难，且一旦 Bloom 层做出与 DB 不同的判断，行为就取决于开关状态。项目的正确分工是：**播种全量（连已删行），判定仍由数据库层的 deleted=0 过滤兜底**。

`ShortLinkMapper` 里这个方法用注解 SQL 实现（`src/main/java/com/linkforge/link/mapper/ShortLinkMapper.java`，javadoc 标注 A9）：

```java
@Select("SELECT code FROM lf_short_link")
List<String> selectAllCodes();
```

生成的 SQL 就是字面这一条，**没有** `AND deleted=0`。原因：`@Select` 手写 SQL 是 MyBatis 的语句，MP 的自动改写只作用于它自己生成的语句，不会去动你手写的 SQL（框架行为）。javadoc 原文写明了"故意绕过 @TableLogic"。教训：MP 的自动行为是默认值不是真理，需要全量时用原生 SQL 明确表达意图，并且注释为什么。

### ClickLogMapper：聚合与多表是手写 SQL 的地盘

`src/main/java/com/linkforge/stats/mapper/ClickLogMapper.java` 三个方法全是 `@Select` 手写：

```java
// 片段
@Select("SELECT COUNT(DISTINCT ip) FROM lf_click_log "
        + "WHERE link_id = #{linkId} AND clicked_at >= #{from} AND clicked_at < #{toExclusive}")
long countDistinctIps(@Param("linkId") Long linkId, ...);

@Select("SELECT DATE(clicked_at) stat_date, COUNT(DISTINCT ip) uv FROM lf_click_log "
        + "WHERE link_id = #{linkId} AND clicked_at >= #{from} AND clicked_at < #{toExclusive} "
        + "GROUP BY DATE(clicked_at)")
List<com.linkforge.stats.dto.DailyUv> selectDailyUv(...);

@Select("SELECT l.* FROM lf_click_log l "
        + "LEFT JOIN lf_consumed_event c ON c.event_id = CONCAT('log-', l.id) "
        + "WHERE c.event_id IS NULL AND l.event_id IS NULL "
        + "ORDER BY l.id LIMIT #{limit}")
List<ClickLog> selectUnconsumed(@Param("limit") int limit);
```

三个方法三种"Wrapper 不适合"的理由：

1. **聚合**（`COUNT(DISTINCT ip)`、`GROUP BY`）：Wrapper 拼得出查询条件但表达聚合很别扭，SQL 一眼能看懂。调用方是 `ClickStatsService.stats` 的 UV 统计（项目逻辑）。
2. **多表**：`selectUnconsumed` 是反连接（LEFT JOIN 后筛选没有匹配上的行）：找出"还没有对应消费记录"的同步点击行，交给对账兜底任务补录。`CONCAT('log-', l.id)` 拼出的正是 `ClickRecordService` 回填 eventId 的同一套键。join 写在 Wrapper 里可读性崩坏。
3. **返回形状**：`selectDailyUv` 返回的不是实体而是自定义 DTO `DailyUv`，列别名 `stat_date`、`uv` 靠驼峰映射对上 DTO 字段，这超出了 `BaseMapper<T>` 的实体假设。

注意 `l.*` 与 `deleted`：`lf_click_log` 表没有逻辑删除列，`ClickLog` 实体也没有 `@TableLogic` 字段，所以这里不存在"要不要 deleted=0"的问题（项目事实）。MP 与手写 SQL 的分界判据：**单表 CRUD 给 BaseMapper 与 Wrapper；多表 join、聚合、数据库方言（`ON DUPLICATE KEY UPDATE` 之类）、需要绕过自动行为的场景，手写注解 SQL。** 同类的方言例子在 `ClickDailyMapper.incrementClick`（`src/main/java/com/linkforge/stats/mapper/ClickDailyMapper.java`）：`INSERT ... ON DUPLICATE KEY UPDATE clicks = clicks + 1` 把"不存在则插入、存在则累加"压成一条原子语句，靠 `uk_daily_link_date` 唯一索引兜底并发（[MySQL](40-mysql.md)的唯一索引兜底），调用方（幂等消费链路）见 [RabbitMQ 与 Outbox](51-rabbitmq-outbox.md)。

### #{} 与 ${}：参数化的分界

上面所有 SQL 的参数都是 `#{}`。`#{}` 走预编译：SQL 结构先编译，参数后到，只当数据；`${}` 是字符串替换，直接把内容嵌进 SQL 文本，用户输入经过它就可能改变 SQL 语义（SQL 注入）。两条纪律：注解 SQL 的所有用户输入一律 `#{}`；`${}` 只用于表名、列名这类不可能来自用户的标识符，且必须白名单校验（比如排序字段让用户选枚举、代码里映射成列名，不直接拼）。Wrapper 的条件值同样走 `#{}` 占位（框架行为），所以本项目的用户输入没有注入面。

## 事务：Mapper 调用怎么加入 Spring 事务

问题：一个业务方法要写两张表，怎么保证同生共死？`ClickRecordService.recordClick`（`src/main/java/com/linkforge/stats/service/ClickRecordService.java`）方法上有 `@Transactional`，方法体里两次 mapper 调用：`clickLogMapper.insert(logRow)` 与 `outboxMapper.insert(outbox)`。它们天然在同一个事务里，原因在 Spring 与 MyBatis 的衔接层（mybatis-spring，框架行为）：

- 事务开始时，Spring 的事务管理器拿到一条数据库连接、关掉自动提交，并把它**绑定到当前线程**（代理层怎么走到这一步，[Spring 核心](30-spring-core.md)的事务一节讲过）。
- MyBatis 侧，Spring 管理的 mapper 调用每次先看当前线程上有没有绑定的会话：有就复用，SQL 就走那条绑定连接；没有（不在事务里）就新建一个短命会话，用完即关、每条 SQL 各自提交。
- 于是同一个 `@Transactional` 方法里的两次 `insert` 落在同一条连接、同一个事务，一起提交或一起回滚。

记住分工即可：**事务边界是 Spring 的代理管，连接绑定是事务管理器管，MyBatis 负责让自己的会话加入这条绑定连接**。没有 `@Transactional` 时，每个 mapper 调用是独立事务，这就是"两条 INSERT 必须同生共死"的业务必须贴注解的原因。

## 怎么看到真实执行的 SQL

调试数据层的永远第一步：看真实 SQL。`application.yml` 里已配 `log-impl: org.apache.ibatis.logging.slf4j.Slf4jImpl`，MyBatis 会把每条执行的 SQL 打进日志（框架行为）。日志的记录器名是 Mapper 接口的全名，本地临时把对应包调到 DEBUG：

```yaml
logging:
  level:
    com.linkforge.link.mapper: DEBUG   # 本地调试临时开，生产别开
```

之后每次调用会看到两行（示意，非实跑输出）：

```text
==>  Preparing: SELECT id,tenant_id,... FROM lf_short_link WHERE (code = ?) AND deleted=0
==> Parameters: aB3xK9z(String)
```

Preparing 是编译后的 SQL 结构（`?` 待填），Parameters 是实际参数与类型。两行拼起来就是完整语义。改了 Mapper 不确定生成什么 SQL，开日志一看便知；要进一步判断走没走索引，把抄出来的 SQL 放到 MySQL 里 EXPLAIN（[MySQL](40-mysql.md)的 EXPLAIN 实战）。

## 常见错误与误区

| 错误 | 后果 | 正确姿势 |
|------|------|----------|
| `eq(col, null)` 当"跳过条件" | `col = NULL` 一行都查不到 | `eq(cond, col, val)` 或 `isNull(col)` |
| `updateById` 传 null 想清字段 | SET 跳过该字段 | `LambdaUpdateWrapper.set(col, null)` |
| 忘装分页插件 | `selectPage` 全量返回、total 为 0 | 检查 `MybatisPlusInterceptor` Bean |
| `${}` 接用户输入 | SQL 注入 | 一律 `#{}` |
| 假设逻辑删除对注解 SQL 生效 | 手写 SQL 不带 `deleted=0` | 显式加，或注释清楚为什么绕过 |
| Wrapper 写多表 join | 可读性崩坏 | 单表给 MP，复杂查询原生 SQL |
| 循环里逐条 insert | 每条一次网络往返 | 批量插入或 `ON DUPLICATE KEY UPDATE` |

## 面试常见问题与追问

**主题一：Mapper 接口没有实现类，为什么能注入？**
`@MapperScan` 扫描指定的包，把每个接口登记成特殊的 Bean 定义；真正要这个 Bean 时，MyBatis 用 JDK 动态代理现场生成替身，替身的处理器负责"解析出 SQL，执行，映射结果"。注册与生成都在启动期完成。追问"如果扫描范围太宽会怎样"：普通接口也会被套上 Mapper 代理注册成 Bean，本项目 `LinkForgeApplication` 的注释记录过这次事故，修法是精确列五个 mapper 包。再追问"代理的机制是什么"：运行时生成实现相同接口的新类，方法调用全部转发给 InvocationHandler（[注解、反射与动态代理](java/10-07-annotations-reflection-proxy.md)）。

**主题二：Wrapper 传 null 会发生什么？**
分两层答。查询侧：`eq(col, null)` 生成 `col = ?` 且参数为 null，`col = NULL` 对任何行都不成立，一行都查不出；想要"没传就不过滤"用布尔参数重载 `eq(condition, col, val)`，想匹配 NULL 用 `isNull`。更新侧：`updateById` 默认 NOT_NULL 策略，SET 子句跳过 null 字段，所以它表达不了"清空某列"，要用 `LambdaUpdateWrapper.set(col, null)` 显式生成 `SET col = NULL`。追问"这两个规则为什么不同"：一个是查询条件的求值语义（SQL 三值逻辑），一个是更新语句的字段组装策略，根本不是同一层的东西。

**主题三：逻辑删除和唯一索引撞在一起怎么办？**
冲突：删除改成 UPDATE 后行还在，`uk_code` 这类唯一索引继续占用，业务上"被删的码不能复用"。本项目把它从代价变成特性：删除过的短码永久保留，防止旧链接被新主人抢注。追问"想查包括已删行的全量怎么办"：MP 的自动改写只作用于它生成的 SQL，手写注解 SQL（`@Select`）可绕过，项目里 `selectAllCodes` 给 Bloom 播种就是这么做的，并注释了绕过原因。再追问"这样删下去表会不会无限大"：会，逻辑删除换来可恢复与审计能力，代价是存储与索引膨胀，归档策略要另外设计（如实说即可）。

## 本章总结

三层各司其职：JDBC 定协议，MyBatis 管执行与映射（你写 SQL，`#{}` 预编译，驼峰映射），MP 补 SQL 生成（`BaseMapper<T>` 泛型 CRUD、Wrapper 条件、逻辑删除改写、分页插件改写）。MP 的注解在启动期解析成元数据，之后影响每一次 SQL 生成；自动行为（`deleted=0`、跳过 null 字段）是默认值，需要全量或方言时用 `@Select` 手写并注释原因。新代码的判断顺序：单表 CRUD 用 BaseMapper 加 Wrapper（动态条件记得布尔重载）；多表、聚合、方言、绕过自动行为，写注解 SQL；清空字段用 `set(col, null)`；分页前确认插件装了。和前后的章节的关系：实体字段类型的选择在[包装类型](java/10-04-wrappers-and-boxing.md)，泛型机制在[泛型](java/10-05-generics.md)，代理与事务的原理在[注解、反射与动态代理](java/10-07-annotations-reflection-proxy.md)和[Spring 核心](30-spring-core.md)，SQL 质量与索引在[MySQL](40-mysql.md)。

## 自测

1. `eq(ShortLink::getStatus, null)` 会查出什么？想表达"状态没传就不加这个条件"该怎么写？
   答：生成 `status = ?` 且参数 null，`status = NULL` 对任何行不成立，一行都查不到。正确写法是布尔重载 `eq(status != null, ShortLink::getStatus, status)`。
2. `updateById` 传 null 字段和 `eq` 传 null 参数，行为差在哪？
   答：前者是更新侧规则，SET 子句跳过 null 字段（不更新）；后者是查询侧规则，条件照常生成，`col = NULL` 查不到行。两者不是同一层机制。
3. `deleteById(id)` 实际执行什么 SQL？为什么删除过的短码永远不能被重新创建？
   答：`UPDATE lf_short_link SET deleted=1 WHERE id=? AND deleted=0`。行还在，`uk_code` 唯一索引还占着这个码，重新插入同一 code 会撞唯一键抛 `DuplicateKeyException`。
4. `selectAllCodes` 为什么不用 `selectList(null)` 或 Wrapper 实现？
   答：BaseMapper 与 Wrapper 生成的 SQL 会被自动追加 `deleted=0`，播种 Bloom 需要连已删行一起进过滤器（它们的 uk_code 还占着），必须用 `@Select` 手写全量 SQL 绕过逻辑删除。
5. 忘记配置 `MybatisPlusConfig` 里的插件 Bean，`selectPage` 会返回什么？
   答：没有任何拦截器去拼 LIMIT 和执行 count，records 是全量数据、total 停在 0，分页等于没生效。
6. `ClickRecordService.recordClick` 里两次 insert 为什么保证同生共死？
   答：方法有 `@Transactional`，Spring 事务管理器把连接绑定到当前线程，mybatis-spring 让两个 mapper 调用复用这条连接，同属一个事务。

## 下一步

- 这些 Mapper 所在的完整业务链路（创建、跳转、统计）见 [81-linkforge-flows.md](81-linkforge-flows.md)。
- `incrementClick` 的调用方与幂等消费见 [51-rabbitmq-outbox.md](51-rabbitmq-outbox.md)。
- 索引设计与 EXPLAIN 验证见 [40-mysql.md](40-mysql.md)。
- 方法引用解析的 Java 机制细节见 [java/10-08-lambda-stream-optional.md](java/10-08-lambda-stream-optional.md)。
