# 4.1 Entity、Mapper 与 CRUD

## 1. 本节学习目标

1. 认全项目的 16 个实体类与 17 张表（有一张表故意没有实体），说出每个注解（`@TableName`、`@TableId`、`@TableLogic`）的作用。
2. 掌握 `BaseMapper` 的用法边界：什么时候空壳够用，什么时候必须写自定义 SQL。
3. 读懂 `LambdaQueryWrapper` 的六个真实实例与 `LambdaUpdateWrapper` 的一个关键例外。
4. 理解逻辑删除在本项目的两个后果：唯一索引占位与 Bloom 播种语义。
5. 会配置并使用分页插件。

## 2. 前置知识

- [3.1 Controller 与 REST 设计](../03-spring-mvc/01-controllers-rest.md)：Service 层方法签名，本章拆解它们脚下的持久层。
- [1.3 配置体系与 Profile](../01-spring-boot/03-configuration-profiles.md)：`mybatis-plus:` 配置段在 dev 文档内，多文档作用域陷阱在那课讲过。
- MySQL 基础（框架无关常识）：主键、唯一索引、`INSERT ... ON DUPLICATE KEY UPDATE`。
- （Java 语言知识）方法引用：`ShortLink::getCode` 把 getter 变成可传的函数值，MyBatis-Plus 用它反推出列名。

## 3. 本节涉及的源码

| 文件 | 职责 | 阅读顺序 | 为什么读它 |
|------|------|----------|------------|
| `link/entity/ShortLink.java`（30 行） | 短链行 | 第 1 | 逻辑删除的标准样本 |
| `link/entity/LinkVariant.java`（26 行） | A/B 变体行 | 第 2 | 刻意无逻辑删的对照 |
| `stats/entity/ConsumedEvent.java`（17 行） | 幂等表行 | 第 3 | 唯一的 IdType.INPUT |
| `apikey/entity/ApiKey.java`（31 行） | API Key 行 | 第 4 | 哈希列注释的范例 |
| `user/entity/Tenant.java`、`UserAccount.java`（23/26 行） | 租户与用户 | 第 5 | 逻辑删 + 唯一索引交互 |
| `stats/entity/ClickLog.java` 等 4 个、`webhook/entity/` 3 个 | 统计与投递行 | 第 6 | 快照列与状态机列 |
| `link/mapper/ShortLinkMapper.java`（24 行） | 自定义 SQL 典型 | 第 7 | 绕过逻辑删的注释全文 |
| `stats/mapper/OutboxMapper.java`（40 行） | 状态机 SQL（mark 带 status 守卫） | 第 8 | 无 @Mapper 注解的对照 |
| `auth/mapper/RefreshTokenMapper.java`（44 行）、`user/mapper/UserAccountMapper.java`（45 行） | 锁读与原子自增 | 第 9 | FOR UPDATE / incrementTokenVersion（5.4 的地基） |
| `webhook/mapper/WebhookFlushStateMapper.java`（32 行） | 无实体表的 Mapper | 第 10 | ensureRow/selectSeqForUpdate/updateSeq 三件套 |
| `stats/mapper/ClickDailyMapper.java`（32 行） | 计数 upsert | 第 11 | ON DUPLICATE KEY 实例 |
| `config/MybatisPlusConfig.java`（18 行） | 分页插件 | 第 10 | 三行配置 |

## 4. 类的定位

Entity 是数据库行在 Java 里的像，Mapper 是对这张像做增删改查的接口。本项目 16 个实体全部用 Lombok `@Data`（自动 getter/setter），17 个 Mapper 全部继承 `BaseMapper<T>` 拿到几十个通用方法，自定义 SQL 只在通用方法表达不了时才写。唯一的例外是 `lf_webhook_flush_state`：它只有 endpoint_id 与一个长整型序号，没有实体类，`WebhookFlushStateMapper` 直接读写标量——形状简单到不值得建模，这本身是个判断样本。位置见[包结构图](../../diagrams/archify/html/03-package-map.html)：entity/mapper 子包在六个业务包里成对出现（批次 2 新增 auth 包）。表间关系见[数据库 ERD](../../diagrams/archify/html/05-database-erd.html)（17 表，逐列录自迁移文件）。

## 5. 核心源码详解

### 5.1 实体全表（16 实体 / 17 表）

| 实体 | 表 | 主键策略 | @TableLogic | 特别之处 |
|------|----|----------|-------------|----------|
| ShortLink | lf_short_link | AUTO | 有 | accessPasswordHash 永不出库；tags 单列（V13） |
| LinkVariant | lf_link_variant | AUTO | 无（硬删） | variantKey 大小写敏感（V10）；matchRule JSON 列（V13） |
| Tenant | lf_tenant | AUTO | 有 | uk_tenant_name 唯一 |
| UserAccount | lf_user | AUTO | 有 | tenantRole（V8）；tokenVersion、mustChangePassword（V11） |
| RefreshToken | lf_refresh_token | AUTO | 无 | 只存哈希；family 轮换链（V11）；实体在 auth 包根而非 entity 子包 |
| TenantInvitation | lf_tenant_invitation | AUTO | 无 | 只存哈希；accepted/revoked 标记定单次语义（V12） |
| MaliciousHost | lf_malicious_host | AUTO | 无 | feed 落表，判定不读它（V14） |
| （无实体） | lf_webhook_flush_state | endpoint_id 即主键 | — | 只有 flush_seq 一列状态，Mapper 直读写（V15） |
| ApiKey | lf_api_key | AUTO | 无 | key_hash 唯一索引承重 |
| ClickLog | lf_click_log | AUTO | 无 | code 是快照列，eventId 回填标记 |
| ClickDaily | lf_click_daily | AUTO | 无 | uk(link_id,stat_date) 幂等落桶 |
| ClickDimDaily | lf_click_dim_daily | AUTO | 无 | uk 四列幂等 |
| ConsumedEvent | lf_consumed_event | INPUT | 无 | 主键即幂等锁，仅两列 |
| Outbox | lf_outbox | AUTO | 无 | 三个状态常量内聚 |
| WebhookEndpoint | lf_webhook_endpoint | AUTO | 无 | secret 存 AES 密文 |
| WebhookDelivery | lf_webhook_delivery | AUTO | 无 | uk(endpoint_id,event_id) 幂等兜底 |
| WebhookAggregateBuffer | lf_webhook_agg_buffer | AUTO | 无 | 短命行，flush 即删 |

标准形态看 ShortLink（ShortLink.java:11-30）：

```java
@Data
@TableName("lf_short_link")
public class ShortLink {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String code;
    ...
    @TableLogic
    private Integer deleted;
    /** B3: BCrypt hash of the access password; null = the link is public. */
    private String accessPasswordHash;
}
```

（框架行为）`@TableName` 绑定表名（不写则按类名驼峰转下划线）；`@TableId(type = IdType.AUTO)` 表示主键由数据库自增，插入后 MyBatis-Plus 把生成值回填到对象（`link.getId()` 在 insert 后可用，4.3 课的 recordClick 依赖这一点）；`@TableLogic` 标记逻辑删列。字段名按驼峰自动映射下划线列（application.yml:89 `map-underscore-to-camel-case: true`）。

全局删除配置（application.yml:91-96）：

```yaml
  global-config:
    db-config:
      id-type: auto
      logic-delete-field: deleted
      logic-delete-value: 1
      logic-not-delete-value: 0
```

（框架行为）`logic-delete-field: deleted` 让所有名为 deleted 且带 `@TableLogic` 的字段生效：`selectById` 自动追加 `AND deleted = 0`，`deleteById` 变成 `UPDATE ... SET deleted = 1`。两个例外（本项目实现）都要靠自定义 SQL 绕过，见 5.4。

特殊的 ConsumedEvent（stats/entity/ConsumedEvent.java:12-17）：

```java
    @TableId(value = "event_id", type = IdType.INPUT)
    private String eventId;
    private LocalDateTime consumedAt;
```

（框架行为）`IdType.INPUT` 表示主键由代码赋值（eventId 是字符串 "log-{id}" 或 UUID），不用数据库自增。这张表的主键就是幂等锁：重复插入同一 eventId 会撞 DuplicateKeyException，消费方拿它当「已处理过」的信号（4.3 课展开）。

### 5.2 BaseMapper 与空壳 Mapper

（框架行为）`BaseMapper<T>` 内置 `insert / selectById / selectOne / selectList / updateById / deleteById / selectPage` 等方法，全部自动拼 SQL。最简的 Mapper 一行都不用写：

```java
@Mapper
public interface ConsumedEventMapper extends BaseMapper<ConsumedEvent> {
}
```

（stats/mapper/ConsumedEventMapper.java，全文 9 行）

对照组是 TenantMapper：它曾经也是这样的空壳，批次 2b 给它加了一条 `selectForUpdate`（租户行锁，5.4 的锁序起点）后就不再是空壳了——**空壳与否取决于这张表有没有「通用 CRUD 表达不了」的动作**，租户表在团队协作落地后有了。

Mapper 接口的代理由 `@MapperScan` 生成（LinkForgeApplication.java:8-10）：

```java
@SpringBootApplication
// Explicit mapper packages: a broad "com.linkforge" scan also proxied plain
// interfaces like ClickEventPublisher into mapper beans.
@MapperScan({"com.linkforge.user.mapper", "com.linkforge.link.mapper", "com.linkforge.stats.mapper", "com.linkforge.apikey.mapper", "com.linkforge.webhook.mapper", "com.linkforge.auth.mapper"})
```

（本项目实现）注释记录了踩坑：宽扫描会把非 Mapper 接口（如 ClickEventPublisher）也代理成 Bean。显式列包是修复；新包进来要手动登记——批次 2 新增的 `auth.mapper` 就是这样加进清单的（这也是「新 mapper 放已登记包可免登记、放新包必须改启动类」的由来）。（对照）多数 Mapper 同时标了 `@Mapper`，OutboxMapper 没有（stats/mapper/OutboxMapper.java）也不缺代理——`@MapperScan` 已覆盖，`@Mapper` 是冗余保险。两套机制并存，读懂任意一个即可。

### 5.3 LambdaQueryWrapper 六实例

（框架行为）`LambdaQueryWrapper<T>` 用方法引用声明列，编译期校验字段存在，重构改名不会静默断链。等价于手写 `WHERE` 子句。

**实例 1：唯一索引点查**（ApiKeyService.java:149-152）：

```java
    public ApiKey authenticate(String plaintextKey) {
        return apiKeyMapper.selectOne(new LambdaQueryWrapper<ApiKey>()
                .eq(ApiKey::getKeyHash, sha256Hex(plaintextKey)));
    }
```

明文 key 先做 SHA-256 再查 `uk_api_key_hash` 唯一索引。`selectOne` 查到多行会抛异常，唯一索引保证这里不会。

**实例 2：双条件组合**（AuthService.java:72-77，login 段）：

```java
        Tenant tenant = tenantMapper.selectOne(new LambdaQueryWrapper<Tenant>()
                .eq(Tenant::getName, request.getTenantName()));
        ...
        UserAccount user = userAccountMapper.selectOne(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getTenantId, tenant.getId())
                .eq(UserAccount::getUsername, request.getUsername()));
```

两个 `.eq` 默认 AND 连接，对应唯一索引 `uk_tenant_username(tenant_id, username)`。

**实例 3：条件分支 + 分页**（ShortLinkService.java:487-501）：

```java
    private PageResponse<ShortLinkDetail> pageOwn(AuthUser user, long current, long pageSize,
                                                  String keyword, String tag) {
        LambdaQueryWrapper<ShortLink> query = new LambdaQueryWrapper<ShortLink>()
                .orderByDesc(ShortLink::getId);
        if (isTenantWide(user)) {
            query.eq(ShortLink::getTenantId, user.getTenantId());
        } else {
            query.eq(ShortLink::getCreatedBy, user.getUserId());
        }
        Page<ShortLink> result = shortLinkMapper.selectPage(new Page<>(current, pageSize), query);
        List<ShortLinkDetail> records = result.getRecords().stream().map(ShortLinkService::toDetail).toList();
        return new PageResponse<>(records, result.getTotal(), result.getCurrent(), result.getSize(), result.getPages());
    }
```

（本项目实现）归属分支决定 WHERE 条件：TENANT scope 按 `tenant_id`，其余按 `created_by`。其后 `applyFilters`（`:510-528`）把批次 3a 的筛选叠进来：keyword 对 code/original_url/title 三列做转义后的 LIKE 子串匹配（`%`、`_`、`\` 先转义，搜 "50%" 就是字面 50%），tag 走 `query.apply("FIND_IN_SET({0}, tags)", normalized)` 整词匹配——参数占位 `{0}` 而不是字符串拼接，防注入的姿势与 wrapper 一脉。`selectPage` 返回的 `Page` 带总行数与页数，转换成 2.1 课的 `PageResponse` 五字段。排序写进 wrapper 而不是 SQL 字符串。

**实例 4：明细表同款**（ClickStatsService.java:330-338）：`eq(ClickLog::getLinkId, linkId)` 加 `orderByDesc(ClickLog::getClickedAt)` 再 selectPage，同一套 API 换一张表，明细页分页防止拖库。

**实例 5：更新定位**（ShortLinkService.java update 段的事务内）用到了 Update 变体：

```java
            shortLinkMapper.update(null, new LambdaUpdateWrapper<ShortLink>()
                    .eq(ShortLink::getId, link.getId())
                    .set(ShortLink::getAccessPasswordHash, null));
```

旁边的注释是本课最重要的警告：

```java
            // MyBatis-Plus's default NOT_NULL update strategy skips null fields in
            // updateById — the clear must be an explicit SET NULL (D-Bw345-32).
```

（框架行为）`updateById` 只更新非 null 字段，所以「把密码清空」无法用它表达——null 字段被跳过，等于没改。`LambdaUpdateWrapper.set(column, null)` 生成显式 `SET access_password_hash = NULL`。这是每个用 MyBatis-Plus 的人都迟早踩的坑，本项目用一条迁移注释（D-Bw345-32）留了证。

### 5.4 自定义 SQL：什么时候 BaseMapper 不够

判据（本项目实现）：需要绕过逻辑删、需要 MySQL 方言语法（upsert）、需要多表 join 或聚合时，写注解 SQL。

**绕过逻辑删**（ShortLinkMapper.java:13-23）：

```java
    /**
     * A9: startup seed for the process-local short-code Bloom filter. Native SQL
     * bypasses @TableLogic on purpose: logically-deleted rows still hold their
     * uk_code, so they must stay Bloom-positive and be rejected by the DB
     * (deleted=0 filter) instead of turning into pre-DB 404s.
     * ...
     */
    @Select("SELECT code FROM lf_short_link")
    List<String> selectAllCodes();
```

注释是本课必背段：逻辑删的行仍占着 `uk_code`，Bloom 过滤器必须把它们当「存在」（否则重启后已删码会穿透 Bloom 到库，再被 deleted=0 过滤，行为分裂）。`@Select` 原生 SQL 不经 MyBatis-Plus 的条件加工，这是绕过 `@TableLogic` 的唯一正路。（框架行为）`BaseMapper` 另有 `selectObjs` 加 wrapper 写 `isNotNull` 的变通，但语义不清，本项目直接选原生 SQL。

**MySQL 方言 upsert**（ClickDailyMapper.java:16-18）：

```java
    @Insert("INSERT INTO lf_click_daily(tenant_id, link_id, code, stat_date, clicks) "
            + "VALUES(#{tenantId}, #{linkId}, #{code}, #{statDate}, 1) "
            + "ON DUPLICATE KEY UPDATE clicks = clicks + 1")
    int incrementClick(@Param("tenantId") Long tenantId,
                       @Param("linkId") Long linkId,
                       @Param("code") String code,
                       @Param("statDate") LocalDate statDate);
```

（框架行为）`ON DUPLICATE KEY UPDATE` 是 MySQL 专有的原子 upsert：新键插入计数 1，已有键 clicks 加 1。命中 `uk_daily_link_date(link_id, stat_date)` 唯一索引。幂等计数的承重墙是这条 SQL 加唯一索引的组合，没有先 SELECT 再 UPDATE 的竞态窗口。

WebhookAggregateBufferMapper.upsertClick（WebhookAggregateBufferMapper.java:27-31）更进一步，用 `VALUES(late)` 引用本次插入值：

```java
            + "ON DUPLICATE KEY UPDATE clicks = clicks + 1, late = late | VALUES(late)")
```

撞键时 late 取「原值按位或新值」，样本三列不更新，javadoc（:24-26）写明保留首击语义。

**状态机条件更新**（OutboxMapper.java）：

```java
    @Update("UPDATE lf_outbox SET attempts = attempts + 1, next_attempt_at = "
            + "DATE_ADD(NOW(), INTERVAL #{backoffSeconds} SECOND) WHERE id = #{id} AND status = 'PENDING'")
    int markRetry(@Param("id") long id, @Param("backoffSeconds") long backoffSeconds);
```

`attempts = attempts + 1` 是数据库侧自增，两个 relay 线程同时跑到也不会丢计数。`DATE_ADD(NOW(), ...)` 用数据库时钟排重试时间，为什么不信任 JVM 时钟，4.3 课与 [4.2 Flyway](02-flyway-migrations.md) 的 V 系列注释会给出完整论证。`WHERE` 里的 `AND status = 'PENDING'` 是批次 1（T-M3）给三条 mark 统一加的 **CAS 守卫**：另一个 relay 实例（或陈旧批次）已经把行标成 SENT 后，本次 mark 更新 0 行、无声作废，绝不把 SENT 翻回 FAILED 再重放一次——与 WebhookDeliveryMapper 一直以来的标准对齐（10.2 对照讲）。

**锁读与原子自增**（批次 2/修复波新增的一类 SQL）：

```java
    @Select("SELECT * FROM lf_user WHERE id = #{id} FOR UPDATE")
    UserAccount selectForUpdate(@Param("id") Long id);

    @Update("UPDATE lf_user SET token_version = token_version + 1 WHERE id = #{id}")
    int incrementTokenVersion(@Param("id") Long id);
```

（`UserAccountMapper.java:24-34` 一带）

两个要点。其一，`FOR UPDATE` 是**锁定当前读**：绕过 MyBatis-Plus 逻辑删除（所以调用方必须自己判 deleted 标记），读到的是最新已提交的行并持有行锁到事务结束——5.4 全部锁序合同的地基。其二，`token_version` 的自增是**列内表达式**而不是「读出来 +1 再写回」：后者在并发下会丢更新，前者由数据库单语句原子完成。同类还有 `RefreshTokenMapper.rotate`（条件 UPDATE 当 CAS 用）与 `WebhookFlushStateMapper` 的 ensureRow/selectSeqForUpdate/updateSeq（序号的锁读-递增-写回必须在同一事务里，13.2）。**判据升级**：当一个字段的写语义是「基于当前值的状态迁移」而不是「赋一个新值」时，BaseMapper 就不够了，写注解 SQL。

**join 与聚合**（ClickLogMapper.java:20-24）：

```java
    @Select("SELECT COUNT(DISTINCT ip) FROM lf_click_log "
            + "WHERE link_id = #{linkId} AND clicked_at >= #{from} AND clicked_at < #{toExclusive}")
    long countDistinctIps(@Param("linkId") Long linkId, ...);
```

UV 是 `COUNT(DISTINCT ip)`，wrapper 表达不了，原生 SQL 一行解决。返回标量直接映射 long。

### 5.5 分页插件（MybatisPlusConfig.java:12-18）

```java
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
```

（框架行为）`selectPage` 会被这个拦截器改写：先发 `SELECT COUNT(*)` 拿总数，再给原查询追加 `LIMIT offset, size`。`DbType.MYSQL` 决定方言。没有它，`selectPage` 不报错但行为退化为「查全表后内存分页」，大表会拖垮服务。配套依赖 `mybatis-plus-jsqlparser`（pom.xml:101-102）提供 SQL 改写能力，版本与 mybatis-plus 同为 3.5.9（pom.xml:22）。

### 5.6 逻辑删除与唯一索引的交互

删除短链走逻辑删（ShortLinkService.java:1006-1018，4.3 课逐行讲），javadoc 第一句是结论：

```java
    /** Logical delete: the code stays reserved (uk_code) and cannot be reused. ... */
```

（ShortLinkService.java:1004-1005）

（本项目实现）三个连锁事实：
1. 逻辑删只改 `deleted=1`，行还在，`uk_code` 唯一索引照常约束，所以被删的码永远不能被新短链复用。
2. `deleted` 只有 0/1 两个值，若想复用码就必须物理删行或加「删除时间戳进唯一索引」之类的技巧；本项目选不复用，安全先于省码。
3. `lf_user` 的 `uk_tenant_username` 同理：删用户后同名不能重建（（推断）注册限流与租户名冲突 409 的提示都在防误踩）。

## 6. 技术原理补充

**MyBatis-Plus 与 MyBatis 的关系**（框架行为）。MyBatis 管「接口方法到 SQL 的映射」（动态代理 + XML/注解）；MyBatis-Plus 在其上加通用 CRUD（BaseMapper）、条件构造器（Wrapper）、插件（分页）。本项目 `mybatis-plus-spring-boot3-starter`（pom.xml:96-97）一次引入两者。写法纪律：通用操作走 wrapper，方言与聚合走注解 SQL，没有 XML。

**逻辑删除的通用代价**（框架行为）。所有自动查询都隐式 `AND deleted = 0`；统计「历史总量」必须用原生 SQL 绕过。本项目 ClickLog 等事实表干脆不设 deleted 列（append-only），只有租户/用户/短链三个业务实体才有。

**IdType 三态**（框架行为）。AUTO 数据库自增（本项目 15 个实体）；INPUT 代码赋值（ConsumedEvent）；ASSIGN_ID 雪花 ID（未用）。选 AUTO 的隐含要求：insert 后立刻需要 id 的代码必须把实体对象传进 mapper（MyBatis 回填），4.3 课 recordClick 用 `logRow.getId()` 拼 eventId 正是依赖回填。

**BaseMapper 的 selectOne 语义**（框架行为）。多于一行抛 `TooManyResultsException`。「业务上唯一」必须真的唯一：ApiKey 靠 key_hash 唯一索引，Tenant 靠 uk_tenant_name，代码层不用再判 list.size()。

## 7. 可视化

- [数据库 ERD](../../diagrams/archify/html/05-database-erd.html)：17 表与逻辑外键，本节实体表逐列对应。
- [包结构图](../../diagrams/archify/html/03-package-map.html)：六个业务包内 entity/mapper 的成对分布。

阅读顺序：先 ERD 找 lf_short_link，看它的 uk_code 与 lf_link_variant 的连线；再回包结构图确认 ShortLinkMapper 只依赖 ShortLink 一个实体。

## 8. 项目中的真实执行过程

走一遍 `GET /api/links?page=1&size=20`（普通 USER，已有 45 条链接）。

1. Controller 进来（ShortLinkController.java:50-54），调用 `shortLinkService.list(1, 20, keyword, tag)`。
2. list 规整参数（ShortLinkService.java:482-484）：page 至少 1，size 越界回 20，进 pageOwn。
3. wrapper 组装：`ORDER BY id DESC` 加 `WHERE created_by = ?`，再叠 applyFilters 的 keyword/tag 条件。注意不用显式加 `deleted = 0`，MyBatis-Plus 自动追加。
4. `selectPage` 触发分页拦截器（MybatisPlusConfig.java:15）：先 `SELECT COUNT(*) ... WHERE created_by = ? AND deleted = 0` 得 45，再原查询追加 `LIMIT 0, 20`。
5. 20 行 ShortLink 实体映射回 Java 对象，`toDetailWithVariants` 转 DTO。
6. 组装 PageResponse{records=20条, total=45, current=1, size=20, pages=3}，套 ApiResponse 返回。

再看删除后：第 45 条被 `deleteById` 软删（deleted=1），下一页查询自动排除它，但 `selectAllCodes`（Bloom 播种）仍会读出它的码，导致「重建 Bloom 后已删码依旧 404 在库前」——这正是 ShortLinkMapper.java:13-17 注释设计的行为。

## 9. 为什么这样设计

- 实体直接用 Lombok `@Data` 而不做 DTO 隔离层：表与实体近乎一一对应（唯一的例外是无实体的 flush_state 表），字段少且稳定，多一层 BeanUtils 复制只增加出错面。（推断：从 DTO 只在 API 边界存在的模式反推。）
- 自定义 SQL 用注解不用 XML：SQL 全部短（1-3 行）、集中在 Mapper 接口里肉眼可读，XML 的动态 SQL 能力本项目用不上。
- 逻辑删只给三个业务实体（租户/用户/短链）：它们的行有「保留痕迹」的业务诉求（码不可复用、用户可审计）；流水与投递行用状态机（status 列）表达生命周期，删除即物理删（retention 清理，模块 14）。
- 分页上限纪律（size<=100）：MyBatis-Plus 不会替你限制 size，应用层不设防就是全表下载。（StatsController.java:63 与 list 的 :287 两处都在防。）

## 10. 本节总结

- 16 实体 17 表：15 个 AUTO 自增主键，ConsumedEvent 用 INPUT，lf_webhook_flush_state 无实体；3 个实体带 `@TableLogic`。
- BaseMapper 覆盖通用 CRUD；绕逻辑删、upsert、join、聚合时写 `@Select/@Insert/@Update` 注解 SQL。
- `updateById` 跳过 null 字段，清空密码必须 `LambdaUpdateWrapper.set(col, null)`（D-Bw345-32）。
- 分页插件三行配置，`selectPage` 才真的发 LIMIT；依赖 mybatis-plus-jsqlparser。
- 逻辑删 + 唯一索引 = 被删码永久占位；Bloom 播种必须含已删行，否则行为分裂。

## 11. 课后练习

**练习 1（概念理解）**：`shortLinkMapper.deleteById(5)` 实际执行什么 SQL？行还在吗？

答案解析：`UPDATE lf_short_link SET deleted = 1 WHERE id = 5 AND deleted = 0`（框架行为：@TableLogic 改写 delete）。行还在，uk_code 照常占用，所以该码不可复用（ShortLinkService.java:543 注释）。若表没有 @TableLogic 字段才会生成真 DELETE——LinkVariant 就是这种情况。

**练习 2（源码定位）**：找出项目里「显式 SET NULL」的唯一一处，并解释为什么 `link.setAccessPasswordHash(null); shortLinkMapper.updateById(link);` 达不到目的。

答案解析：ShortLinkService.java:499-501 的 LambdaUpdateWrapper。updateById 的 NOT_NULL 更新策略跳过 null 字段（:497-498 注释），entity 上的 null 不生成 SET 子句，密码清不掉；必须用 wrapper 的 `.set(ShortLink::getAccessPasswordHash, null)` 显式写。

**练习 3（执行过程）**：同一 (linkId=7, stat_date=2026-01-01) 的 lf_click_daily 行已存在 clicks=42，`incrementClick` 执行后行内容与返回值是什么？若不存在呢？

答案解析：命中 uk_daily_link_date，执行 `UPDATE clicks = clicks + 1`，行变 43（ClickDailyMapper.java:16-18）。返回值是受影响行数 1。不存在时执行 INSERT 插入 clicks=1 的新行，返回同样为 1——调用方不能靠返回值区分插入与更新，本项目也不需要区分。

**练习 4（调用链）**：ApiKeyAuthFilter 每次认证都 `selectOne`，为什么不需要担心查到多行？

答案解析：WHERE 条件是 `key_hash = ?`，key_hash 上有 `uk_api_key_hash` 唯一索引（V5__api_key.sql:18），数据库层面就排除了重复。selectOne 遇多行会抛 TooManyResultsException，唯一索引让这个异常永远无路触发（ApiKeyService.java:119-122）。

**练习 5（设计分析）**：如果把逻辑删值从 1/0 改成「deleted = 删除时间戳」，uk_code 就能配合时间戳实现码复用。这个改动会破坏本项目的什么设计？

答案解析：至少三处。① `logic-delete-value: 1` 的全局配置与 ShortLinkMapper.selectAllCodes 的 Bloom 语义（已删行必须保持 Bloom 正）要重写；② wrapper 自动追加 `deleted = 0` 变成 `deleted IS NULL` 类比较，所有隐式行为都要复核；③ 「码不可复用」是刻意的安全选择（5.6 节），复用会让旧印刷物料（QR）指向新目标——破坏的不只是技术还有产品语义。

## 12. 与后续课程的联系

本章的 SQL 都很短，因为复杂逻辑在 Service 层拼装；这些表是怎么从零长出来的，是 [4.2 Flyway 与 schema](02-flyway-migrations.md) 的主题——包括 5.4 里绕逻辑删的那张表，它的 code 列为什么必须是 utf8mb4_0900_bin。`ON DUPLICATE KEY UPDATE` 的幂等语义与事务回滚边界，在 [4.3 事务](03-transactions.md) 与 recordClick 同事务双写里合流。
