# 6.1 数据模型：ShortLink、LinkVariant 与 DTO 族

> 本课回答一个问题：一条短链在 LinkForge 里由哪两张表、哪两类 Java 对象、哪些字段表达？为什么「删除」之后这个短码永远不能再被任何人注册？

## 1. 本节学习目标

学完本课，你应该能：

1. 逐字段说出 `ShortLink` 与 `LinkVariant` 两张表各自的列含义，并指出哪个字段是并发正确性的承重墙。
2. 解释 `@TableLogic` 逻辑删与唯一索引 `uk_code` 叠加后的必然结果：删除不释放短码。
3. 说出短码大小写敏感是哪一条 Flyway 迁移钉死的，以及为什么选 `utf8mb4_0900_bin` 而不是 `utf8mb4_bin`。
4. 分清 8 个 DTO 各自服务哪个接口，说出 `CreateShortLinkRequest` 如何捕获未知 JSON 属性。
5. 解释 `ShortLinkDetail.passwordProtected` 为什么是布尔而不是哈希字符串。

## 2. 前置知识

- Lombok `@Data`：编译期生成 getter/setter/equals/hashCode/toString 的注解。模块 1.2 已讲。
- record：Java 17 的不可变数据载体，字段即构造参数，自带访问器。DTO 出参大量使用它。
- 实体映射：MyBatis-Plus 用 `@TableName` 把类绑到表，用 `@TableId` 标主键。模块 4.1 已讲，本课只看字段本身。
- Bean Validation：`@NotBlank`、`@Size`、`@Pattern` 是 jakarta 标准校验注解，Controller 入参标 `@Valid` 后由框架触发。失败统一变 400，映射见模块 2.1。
- Jackson：Spring Boot 默认的 JSON 库，负责请求体到 DTO 的绑定。本课会用到它一个较少见的注解 `@JsonAnySetter`。

## 3. 本节涉及的源码

| 文件 | 行数 | 职责 | 阅读顺序 |
|----|----|----|----|
| `src/main/java/com/linkforge/link/entity/ShortLink.java` | 30 | `lf_short_link` 一行的 Java 映射 | 1 |
| `src/main/java/com/linkforge/link/entity/LinkVariant.java` | 26 | `lf_link_variant` 一行的 Java 映射 | 2 |
| `src/main/resources/db/migration/V1__baseline_schema.sql` | 89 | `lf_short_link` 的原始 DDL（第 28-42 行） | 3 |
| `src/main/resources/db/migration/V4__short_code_case_sensitive.sql` | 26 | 短码改大小写敏感（collation 迁移） | 4 |
| `src/main/resources/db/migration/V10__link_variant.sql` | 29 | A/B 分流表建表 | 5 |
| `src/main/java/com/linkforge/link/dto/CreateShortLinkRequest.java` | 78 | 创建入参，含未知属性捕获 | 6 |
| `src/main/java/com/linkforge/link/dto/UpdateShortLinkRequest.java` | 73 | 更新入参，整组语义 | 7 |
| `src/main/java/com/linkforge/link/dto/ShortLinkDetail.java` | 27 | 出参视图 | 8 |
| `src/main/java/com/linkforge/link/dto/` 其余 5 文件 | 13~5 | 出参/导入/QR 契约 | 9 |

为什么这个顺序读：先看「数据库里有什么」（实体 + DDL），再看「接口边界长什么样」（DTO）。实体是真相，DTO 是窗口。

## 4. 类的定位（在整个项目中的位置）

`ShortLink` 与 `LinkVariant` 是整个短链域的两个「名词」。其余所有 link 包的类（Service、Controller、support 下 23 个辅助类）都在围绕这两张表的行做动作：创建、改状态、抽签、跳转。它们位于 `link/entity` 包，共 2 个文件，是全项目 150 个主源码文件里最薄的一层。

DTO 在 `link/dto` 包，共 8 个文件。它们是 API 的「外交语言」：JSON 进出系统的形状由它们决定，表结构从不直接暴露。`ShortLinkService.toDetail`（`ShortLinkService.java:1020-1024`）是实体变 DTO 的唯一转换点，这一行代码就是本课要讲的边界。

包全貌见 [03-package-map](../../diagrams/archify/html/03-package-map.html) 的 link 分区。

## 5. 核心源码详解

### 5.1 ShortLink 实体逐字段

```java
@Data
@TableName("lf_short_link")
public class ShortLink {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String code;
    private String originalUrl;
    private String title;
    private Integer status;
    private LocalDateTime expireAt;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
    /** B3: BCrypt hash of the access password; null = the link is public. */
    private String accessPasswordHash;
    /** Batch 3a: canonical comma-joined tag set (LinkTags), V13. */
    private String tags;
}
```
（`src/main/java/com/linkforge/link/entity/ShortLink.java:11-31`）

逐字段对应 DDL（`V1__baseline_schema.sql:28-42`）：

| 字段 | 列 | 含义 | 备注 |
|----|----|----|----|
| id | id | 自增主键 | 统计表靠它做外键 |
| tenantId | tenant_id | 租户隔离键 | `idx_link_tenant` 索引 |
| code | code | 短码 | `uk_code` 唯一索引，全项目并发兜底 |
| originalUrl | original_url | 目标 URL | 上限 2048，UTM 合并也卡这条线 |
| title | title | 备注 | 可空，仅展示用 |
| status | status | 1=启用 0=停用 | 停用跳转 404，不是 410 |
| expireAt | expire_at | 过期时刻 | 可空，过期跳转 410 |
| createdBy | created_by | 创建人 user id | 普通用户只能看自己的行 |
| createdAt / updatedAt | 同名列 | 时间戳 | 代码里手工 set（`ShortLinkService.java:227-228`） |
| deleted | deleted | 逻辑删标记 | `@TableLogic` 的作用对象 |
| accessPasswordHash | access_password_hash | BCrypt 哈希 | V7 加列，null 表示公开 |
| tags | tags | 标签集的规范逗号串 | V13 加列；写入前经 LinkTags 规范化（小写、去重、排序），读侧 FIND_IN_SET 精确命中；为什么不用关联表见 4.2 的 V13 段 |

三个语言/框架点就地补课：

1. `@Data`（Java 语言）：一个注解生成全套样板方法，实体类因此只有字段没有方法。
2. `@TableName("lf_short_link")`（MyBatis-Plus 行为）：类名和表名对不上时显式绑定。本项目表名带 `lf_` 前缀，所以每个实体都要标。
3. `@TableLogic`（MyBatis-Plus 行为）：标了它，`deleteById` 不再发 `DELETE`，而是发 `UPDATE lf_short_link SET deleted=1 WHERE id=? AND deleted=0`；所有自动生成的查询会自动追加 `AND deleted=0`。这是框架行为，本项目直接受益。

### 5.2 @TableLogic 与 uk_code：逻辑删为什么等于短码永久保留

把两个事实放在一起看：

- `uk_code(code)` 是数据库唯一索引（`V1__baseline_schema.sql:40`），作用在**所有行**上，不管 deleted 是 0 还是 1。
- `@TableLogic` 的删除只是把 deleted 改成 1，行还在表里。

结论是必然的：一条短链被删后，它的 code 仍占据 `uk_code` 里的一个位置。任何人再创建同 code 的短链都会撞唯一索引。项目注释把这个契约写得很直白。

```java
/** Logical delete: the code stays reserved (uk_code) and cannot be reused.
 * C3-N3: variants are hard rows (no logical-delete column) — they must not outlive
 * the link, so both deletes commit as one unit; cache evictions stay outside. */
public void delete(String code) {
```
（`src/main/java/com/linkforge/link/service/ShortLinkService.java:1003-1005`）

这不是实现巧合，是设计决策。短码一旦印在海报、传单、物料上，被人抢注改指向就是安全事故。永久保留是唯一安全的语义。代价是码空间只出不进，靠 62 字符 7 位长度（约 3.5 万亿组合）撑住余量。

### 5.3 大小写敏感短码与 V4 迁移

V1 建表时 `code` 继承库默认 collation `utf8mb4_0900_ai_ci`（不区分大小写）。这造成过一个真实矛盾：Bloom 过滤器按 Java 字符串精确匹配，数据库按 collation 匹配。同一个 URL 用不同大小写访问，Bloom 开着时 404，关着时却能命中库。V4 把语义钉死。

```sql
ALTER TABLE lf_short_link
    MODIFY code VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL;
```
（`src/main/resources/db/migration/V4__short_code_case_sensitive.sql:25-26`）

迁移注释解释了为什么不选更常见的 `utf8mb4_bin`（`V4__short_code_case_sensitive.sql:7-11`）：`utf8mb4_bin` 是 PAD SPACE 排序规则，MySQL 会认为 `'abc'` 与 `'abc '` 相等。于是 `/r/abc%20` 走库能命中 code `abc`，Bloom 的精确字符串匹配却拒绝它，同样的「行为取决于开关」bug 换个形式重演。`utf8mb4_0900_bin` 是 NO PAD 且大小写敏感，两头行为一致。这个选型是萧北 2026-10-24 定的（注释 :12 原文）。

Java 侧的配套约束在 DTO 注释里（`CreateShortLinkRequest.java:26-28`）：`AbC123` 和 `abc123` 是两条不同的链，但保留字黑名单按小写比较，`ADMIN`、`Admin` 都会被拒（`LinkValidations.java:44`）。

### 5.4 LinkVariant：没有逻辑删列的实体

```java
/** One weighted target of an A/B split link (D-C3-2); lifecycle is hard
 * delete + insert, so unlike ShortLink there is no logical-delete column. */
@Data
@TableName("lf_link_variant")
public class LinkVariant {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long linkId;
    /** Case-sensitive (V10 pins utf8mb4_0900_bin); [A-Za-z0-9_-]{1,32}. */
    private String variantKey;
    private String targetUrl;
    private Integer weight;
    /** Batch 3a: dynamic-routing rule JSON (MatchRule); null = unconditional. */
    private String matchRule;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```
（`src/main/java/com/linkforge/link/entity/LinkVariant.java:10-27`）

它是 `ShortLink` 的镜像反例：刻意**没有** `@TableLogic` 字段。原因有两条，都写在代码注释里。

1. 生命周期上，variants 是主行的附属品。主链删了，variants 必须跟着消失，逻辑删会让死行长期滞留（`V10__link_variant.sql:3` 与 `ShortLinkService.java:544-545`）。
2. 业务上，改一组分流配置是「整组替换」：先 `DELETE` 全部旧行，再插入新行，永远不更新单行（`ShortLinkService.update` 的事务段）。替换语义下没有「标记删除后还能查到历史」的需求，硬删即可。

批次 3a 给变体行加了 `match_rule`（V13）：一段 JSON，描述这个变体在什么条件下才参与抽签（设备、系统、语言、带时区与星期的工作时窗）。它与 weight 是两个正交维度：match_rule 先把候选集收窄，weight 再在候选里抽签。规则的语法、校验与跨午夜语义在 7.3 课完整展开；这里先记住数据形状：**一行一个规则、规则随行整组替换、坏规则在读侧降级为无条件**（存量数据鲁棒性优先于严格性）。

`variantKey` 在建表时内联了 `utf8mb4_0900_bin`（`V10__link_variant.sql:11`），与 V4 同理由：A 和 a 是两个实验组。建表内联还省掉一次 V4 式的列重建。

### 5.5 DTO 族全景

8 个文件按用途分三组。

| 组 | 类 | 形状 | 服务接口 |
|----|----|----|----|
| 创建/更新入参 | `CreateShortLinkRequest` | @Data 类 | POST /api/links |
| | `UpdateShortLinkRequest` | @Data 类 | PATCH /api/links/{code} |
| | `VariantSpec` | @Data 类 | 随上两者内嵌 |
| 出参 | `CreateShortLinkResponse` | @Data 类 | 创建返回四字段 |
| | `ShortLinkDetail` | @Data 类 | 列表/详情/更新返回 |
| 导入 | `ImportResponse` | record | CSV 导入报告 |
| QR 物料 | `QrShareRequest` / `QrShareResponse` | record | 6.4 课讲 |

`CreateShortLinkResponse`（`CreateShortLinkResponse.java:6-13`）四个字段：id、code、originalUrl、shortPath。shortPath 恒为 `/r/{code}`，由 Service 拼好（`ShortLinkService.java:282`），客户端不必自己拼。

### 5.6 CreateShortLinkRequest 与 @JsonAnySetter

```java
@NotBlank(message = "originalUrl required")
@Size(max = 2048, message = "originalUrl too long")
private String originalUrl;

@Size(max = 16, message = "customCode too long")
private String customCode;

/** B3: 4-64 chars when present; stored as a BCrypt hash, never returned. */
@Size(max = 64, message = "accessPassword too long")
private String accessPassword;

private List<@Valid VariantSpec> variants;

@JsonIgnore
private final Map<String, Object> unknown = new LinkedHashMap<>();

@JsonAnySetter
void captureUnknown(String name, Object value) {
    unknown.put(name, value);
}
```
（`src/main/java/com/linkforge/link/dto/CreateShortLinkRequest.java:19-77`，有省略，省略处为 title/expireAt/五个 utm 字段，注解同型）

两个值得停下来讲的东西。

**@JsonAnySetter 是什么**（Jackson 行为）：标在某个方法上，反序列化时每遇到一个 DTO 没有声明过的 JSON 属性，Jackson 就调用这个方法，把属性名和值交出来。默认行为是直接丢弃。本项目用它把未知属性全部收进 `unknown` 这个 Map，再由 Service 决定怎么处理。

**为什么要收**：营销活动常把归因参数混进创建请求，比如 `utm_source` 或 `gclid`。如果悄悄忽略，调用方会以为归因生效了，实际短链里什么都没有。Service 在 `create` 一进门就检查这个 Map（`ShortLinkService.java:140`），`utm` 前缀和 9 个广告归因 id 一律 400（`ShortLinkService.java:871-878`），其余未知属性维持静默忽略的契约。细节在 6.4 课展开。

`List<@Valid VariantSpec>` 里的 `@Valid` 是级联校验：不光校验 List 本身，还下钻校验每个 `VariantSpec` 的 key/url/weight（`VariantSpec.java:15-27`）。

### 5.7 UpdateShortLinkRequest 的整组语义

更新请求和创建请求长得很像，但有两个关键差异，都是「三态」设计。

差异一：`variants` 字段用 null 和空数组表达两种不同意图（`UpdateShortLinkRequest.java:55-57` 注释原文：「whole-set replace semantics — a submitted array is the link's complete variant collection (empty clears back to single-target), null = untouched」）。批次 3a 的 `tags` 字段（:59-61）**照抄了同一套三态形状**（注释明说 "the same three-state shape as variants"）：接口语义一旦定型，新字段对齐旧形状而不是各发明一套——这是 API 设计里最便宜的一致性。

| 客户端发什么 | Service 怎么做 |
|----|----|
| 不带 variants 字段（null） | 分流配置原封不动 |
| `variants: []` | 清空全部 variant，回到单目标链 |
| `variants: [a, b]` | 整组替换：先删后插这两行 |

差异二：密码没有「发空串清除」这条路，因为 Jackson 很难区分「没发」和「发了空串」。所以专门加一个布尔字段 `clearAccessPassword`（`UpdateShortLinkRequest.java:31-32`），且 Service 强制它与 `accessPassword` 互斥（`ShortLinkService.java:461-463`）。

### 5.8 ShortLinkDetail：passwordProtected 布尔

```java
@Data
@AllArgsConstructor
public class ShortLinkDetail {
    private Long id;
    private String code;
    private String originalUrl;
    private String title;
    private Integer status;
    private LocalDateTime expireAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** B3: true when an access password is set; the hash itself never leaves the API. */
    private boolean passwordProtected;
    /** C3 (D-C3-13): the link's A/B variants, or null on single-target links and list rows. */
    private List<VariantView> variants;

    public record VariantView(String key, String url, Integer weight) {
    }
}
```
（`src/main/java/com/linkforge/link/dto/ShortLinkDetail.java:9-26`）

注意这里没有 `accessPasswordHash` 字段。实体里的 BCrypt 哈希在转换时被主动降级成一个布尔值（`ShortLinkService` 的 toDetail 转换处）：

```java
link.getAccessPasswordHash() != null, null);
```
（`src/main/java/com/linkforge/link/service/ShortLinkService.java:1023`）

BCrypt 哈希虽然不可逆，但可以离线暴力试密码。把它发给前端没有任何必要，只有「有没有密码」这一个比特有展示价值。V7 迁移的注释就是这层意思（`V7__link_access_password.sql:1`：「NULL = not protected; the hash never leaves the DB」）。

`variants` 字段也是视图级的取舍：列表页每行 20 条链，逐条去查 variants 是 20 次额外查询，所以列表行恒为 null，只有单链详情走 `toDetailWithVariants`。详情里的每个变体出参带 `matchRule` 的解析形态，与 tags 一起构成控制台编辑表单的数据源（M6 的「不丢规则」修复正是保住了这条往返链，见 6.3 与专题 16）。

## 6. 技术原理补充

**@TableLogic（MyBatis-Plus）**。是什么：逻辑删除注解，把 DELETE 翻译成对标记列的 UPDATE。为什么需要：业务数据通常不允许物理蒸发，删错了要能追。不用会怎样：本项目若去掉它，`deleteById` 变真删，短码释放，印出去的物料可被抢注，`selectAllCodes` 的 Bloom 播种语义也要重新设计。代价：所有自动查询多一个 `deleted=0` 条件，唯一索引无法靠删除释放。

**@JsonAnySetter（Jackson）**。是什么：未知属性回调。为什么需要：向前兼容的 API 通常「忽略未知字段」，但安全上又需要「特定前缀拒绝」，忽略之前得先看见。不用会怎样：utm 夹带会被静默吞掉，归因数据无声丢失。优点：零侵入，DTO 不需要知道调用方会塞什么。缺点：字段名拼错也不会报错，需要靠 Service 的白名单/黑名单兜住。

**Entity 与 DTO 分离**。是什么：数据库形状与 API 形状各自建模，中间一个显式转换。为什么需要：表结构随迁移演化（V7 加列、V4 改 collation），API 形状随产品演化，两者的变更节奏必须解耦。不用会怎样：直接把 `ShortLink` 序列化返回，`deleted`、`accessPasswordHash`、`tenantId` 全部泄漏，任何加列都要先审计会不会外泄。本项目所有出参都经过 `toDetail` 一个出口（`ShortLinkService.java:1020-1024`），审计面收敛到一个方法。

## 7. 可视化

按顺序看两幅图：

1. [05-database-erd](../../diagrams/archify/html/05-database-erd.html)：先找 `lf_short_link` 与 `lf_link_variant` 两张表，注意 `uk_code` 唯一索引标记，以及 variant 表通过 `link_id` 指回主表的形状。图中其余表（click、outbox、webhook）本模块不展开，属于模块 10/11/13。
2. [03-package-map](../../diagrams/archify/html/03-package-map.html)：看 link 包内 entity/dto/service/support 四个子包的依赖方向。entity 与 dto 是被依赖方，不依赖任何东西。

## 8. 项目中的真实执行过程

走一遍 `GET /api/links/abc12345`（查详情）中数据模型的完整旅程。

1. Controller 收到请求（`ShortLinkController.java:70-73`），只认识 DTO，此时还没有任何实体出场。
2. Service 的 `getOwnedLink`（`ShortLinkService.java:890-903`）用 MyBatis-Plus 构造 `code = 'abc12345'` 的查询。框架自动追加 `deleted=0`。已删的链在这里就查不到，直接 404。
3. 命中的行组装成 `ShortLink` 实体。此时 `accessPasswordHash` 字段里是真实的 BCrypt 串。
4. `toDetailWithVariants`（`ShortLinkService.java:1010-1017`）先调 `toDetail` 把实体折叠成 `ShortLinkDetail`，哈希变成布尔，再查一次 `lf_link_variant` 填充 variants。
5. Controller 把 DTO 交给 Jackson 序列化。JSON 里从头到尾没有出现过 hash、deleted、tenantId。

一次往返，两层形状各自完整。读者改代码时要养成一个反射：新增表列时，先问「这个字段该不该出现在 DTO 里」，再动手。

## 9. 为什么这样设计

**逻辑删 + 唯一索引的组合**（本项目实现）：短码是印在物理世界的东西，语义上必须「删除后作废但不释放」。用逻辑删实现这个契约，还顺带保住了统计表对历史行的 link_id 引用。

**V4 的 collation 迁移**（本项目实现）：教训是「数据库比较规则要与代码比较规则一致」。Bloom 过滤器是 Java 精确匹配，DB 若是宽松匹配，两个门的行为就会分叉。项目把这类教训固化成 RULE 注释（`V4__short_code_case_sensitive.sql:21-24`）：跨表 join 一律走 link_id，禁止直连 code 列。

**variants 硬删**（本项目实现）：附属数据不配拥有独立生命周期。这是推断之外的事实，依据是 V10 建表注释（`V10__link_variant.sql:3`）与删除方法注释（`ShortLinkService.java:543-545`）的原文。

**DTO 三态语义**（本项目实现）：PATCH 接口最大的坑是「没发」和「发空」分不清。本项目用「null 不动、空数组清空、clearAccessPassword 布尔显式表态」三招把歧义全部消除，且在 Service 入口把互斥组合直接 400。

## 10. 本节总结

- 两张表、两类 Java 对象：`ShortLink`/`LinkVariant` 是数据库形状，8 个 DTO 是 API 形状。
- `@TableLogic` 让删除变成标记，`uk_code` 让标记过的 code 仍然占坑。两者叠加就是「短码永久保留」。
- 短码大小写敏感由 V4 钉死，collation 选 `utf8mb4_0900_bin` 是为了 NO PAD 加精确匹配，与 Bloom 的行为对齐。
- `LinkVariant` 刻意没有逻辑删列，整组硬删加插入就是它的全部生命周期。
- `@JsonAnySetter` 让 Service 能看见未知 JSON 属性，归因夹带因此拦得住。
- 哈希永不出 API：出口只有 `toDetail` 一个，哈希在那里降级为布尔。

## 11. 课后练习

1. 【概念理解】用户删除了短码 `sale2027`，第二天另一个用户创建 `sale2027`。会发生什么？在哪一层被拦住？
   答案：报 409。逻辑删只把 deleted 置 1，行仍持有 `uk_code` 唯一索引位。新创建走 `doCreate` 的自定义码插入（`ShortLinkService.java:239-246`），MySQL 抛 `DuplicateKeyException`，被翻译成 `BizException(409, "code already exists")`。
2. 【源码定位】找出 `ShortLinkDetail` 里没有但 `ShortLink` 实体里有的全部字段，并说出每个被省略的理由。
   答案：`tenantId`（隔离内部键，前端从会话已知自己的租户）、`deleted`（逻辑删标记是存储细节）、`accessPasswordHash`（安全敏感，降级为 `passwordProtected` 布尔）。见 `ShortLinkService.java:1020-1024` 的转换。
3. 【执行过程】前端 PATCH 发送 `{"clearAccessPassword": true, "accessPassword": "newpass"}`，请求走到哪一步、返回什么？
   答案：400。`update` 在 `ShortLinkService.java:461-463` 检测到两者同时出现，抛「accessPassword and clearAccessPassword are mutually exclusive」，任何写都没发生。
4. 【调用链】`variants: []` 与不带 variants 字段，从 Controller 到数据库分别走了什么不同的路径？
   答案：null 时 `replacement` 为 null（`ShortLinkService.java:469-470`），跳过整个替换块。空数组经 `validateVariants` 得到空列表，`replacement != null` 成立，进入事务（:505-511），`DELETE` 该 link 全部 variant 后不再插入任何行，回到单目标链。
5. 【设计分析】如果把 `@TableLogic` 从 `ShortLink` 上删掉、改在每条 SQL 里手写 `deleted=0`，哪些行为会悄悄变化？
   答案（要点）：`deleteById` 变物理删，短码可被抢注；`selectAllCodes` 无需绕过逻辑删（`ShortLinkMapper.java:13-23` 的注释设计随之作废）；`replayIdempotentCreate` 与 `getOwnedLink` 的查询必须记得手写条件，漏一处就是越权读到已删行。这是推断题，合理答案的核心是「手工条件容易漏」。

## 12. 与后续课程的联系

- 6.2 课用这节课的字段表走完创建流程：`doCreate` 逐字段填一个新 `ShortLink` 对象，撞 `uk_code` 后的重试也发生在那张表上。
- 6.3 课讲 list/detail/update/delete 的方法级实现，是本课「三态语义」的代码现场。
- 6.4 课讲 `VariantSpec` 之外的字段（UTM）如何写时合并进 `originalUrl`。
- 模块 7 讲跳转时，`status`、`expireAt`、`accessPasswordHash` 三个字段会成为门禁链的三道关卡。
- 模块 8 讲缓存时，`originalUrl` 会离开数据库，进入 L1/L2 两级缓存。
