# MySQL：索引、事务与锁

优先级 P0，目标层级 L3（索引设计讲清依据、事务边界与隔离级别应对追问、EXPLAIN 会读）。

读完本章你应具备三个能力：

1. 读懂 LinkForge 数据库迁移文件（V1 到 V7）里每一个索引的设计依据，能回答「为什么这么建」。
2. 给定一条查询，先预测它走不走索引，再用 `EXPLAIN` 验证预测。
3. 把事务隔离级别与 MVCC 讲到机制层：ReadView 的四个字段、可见性判断的每一步、RC 与 RR 的唯一差别。

前置：[Spring 核心](30-spring-core.md)（`@Transactional` 的 Java 侧在那篇，本章只讲数据库侧）；本章的锁与幂等设计和 [RabbitMQ 与 Outbox](51-rabbitmq-outbox.md) 互相引用。

运行说明：本机没有可连接的 MySQL（Docker 不可用）。文中所有 SQL 的执行结果都标注「示意，未实测」，依据是 MySQL 官方文档的语义，不是本机跑出来的数字。文中的两个 Java 演示是纯 JDK 程序，可以本机复现，输出已实测。

## 从问题开始：没有索引的一次查询要付多少钱

场景：`lf_click_log` 记录每一次短链点击。项目上线一年后它有 2000 万行。有人打开统计页，后端执行：

```sql
SELECT clicked_at FROM lf_click_log WHERE link_id = 42;
-- 在 2000 万行里找出属于链接 42 的那几百行。
```

没有索引时，MySQL 只有一条路：把表从头到尾读一遍，逐行判断 `link_id` 是不是 42。这叫全表扫描。

要看清它的代价，先认识 InnoDB 读写磁盘的最小单位：**页**（page），默认 16KB。行数据存放在页里，读一行也是读一整页。假设一页存 100 行，2000 万行就是 20 万页。全表扫描意味着把这 20 万页从磁盘搬到内存。磁盘随机读一次是毫秒级，顺序读快得多，但不管怎么读，都是几十万次页访问的量级。查询单次几百毫秒甚至几秒。

索引是拿什么换什么：**额外占一份磁盘空间、每次写入多维护一份数据，换来某些查询不必碰绝大多数页**。它不是免费的加速器。后面会反复看到「这个索引为什么值得建」和「这个查询为什么用不上这个索引」两个方向的判断。

## B+ 树：为磁盘设计的矮胖树

### 为什么不是别的结构

把候选结构一个个放到「16KB 页、磁盘慢」这个约束下检验，B+ 树是剩下的那个。

- **有序数组**：二分查找只要 O(log n) 次，但插入一个元素要把后面全部后移。数据库每秒都在插入，直接排除。
- **哈希表**：等值查询 O(1)，看起来无敌。但哈希把顺序完全打散，`WHERE clicked_at > x`、`ORDER BY id` 这类范围和排序操作全部退化成全表扫描。InnoDB 的表只支持 B+ 树索引（引擎行为）；MEMORY 引擎支持哈希索引，InnoDB 内部还有一种自动生成的自适应哈希做热点等值加速，它们都帮不了范围查询。
- **二叉搜索树 / 红黑树**：每个节点只放一个键。2000 万个节点，树高约 24（2 的 24 次方约 1677 万）。树在磁盘上意味着每下降一层就是一次页读取，24 次 IO 起步。二叉结构是为内存设计的：内存访问便宜，节点瘦一点没关系；磁盘上每页只装一个键是巨大浪费。
- **B 树**：已经是多叉，但它的非叶节点也存整行数据。整行很宽，一页装不了几个键，扇出（一个节点能有多少个分叉）变小，树变高。而且范围查询要在树上反复横跳。

B+ 树做了两个决定：**非叶节点只放路标（键 + 子页指针），数据全部下沉到叶子层**，于是路标页能装尽可能多的键，扇出极大；**叶子层之间用双向链表串起来**，范围查询扫到起点后顺着链表顺序读即可，不用回到树上。

### 页、扇出与树高：算一遍就知道为什么 2000 万行只要 3 层

「树高即磁盘 IO 次数」这句话值得算一遍。下面的 Java 程序用一个简化模型推算：一个页 16KB；非叶节点每个路标按主键 8 字节加子页指针 6 字节加 2 字节开销算 16 字节，一页能装 1024 个路标，这就是扇出；叶子页放整行，按一页 100 行估算。

```java
/** FanoutAndHeight: 用"页"模型算 B+ 树需要几层。纯算术演示，不是 MySQL 实测。 */
public class FanoutAndHeight {
    public static void main(String[] args) {
        // 模型：一个页 16KB。内节点每个路标 = 主键 8B + 子页指针 6B + 2B 开销 = 16B，
        // 一页放 16384 / 16 = 1024 个路标，这就是扇出。
        // 叶子页放整行数据，按一页 100 行估算（行宽约 160B）。
        int fanout = 16384 / 16;
        int rowsPerLeafPage = 100;
        System.out.println("fanout=" + fanout + " rowsPerLeafPage=" + rowsPerLeafPage);
        for (long rows : new long[] {20_000_000L, 1_000_000_000L}) {
            long leafPages = (rows + rowsPerLeafPage - 1) / rowsPerLeafPage; // 向上取整
            long pages = leafPages;
            int height = 1; // 第 1 层是叶子层
            while (pages > 1) {
                pages = (pages + fanout - 1) / fanout; // 上一层需要多少页
                height++;
            }
            System.out.println("rows=" + rows + " leafPages=" + leafPages
                    + " height=" + height);
        }
    }
}
```

```text
fanout=1024 rowsPerLeafPage=100
rows=20000000 leafPages=200000 height=3
rows=1000000000 leafPages=10000000 height=4
```

读一遍这个输出。2000 万行 = 20 万个叶子页。上一层只需要 20 万除以 1024，向上取整 196 页。再上一层 196 除以 1024，1 页，这就是根。总共 3 层。10 亿行也只要 4 层。这就是「矮胖」的量化含义：扇出上千，行数再涨一个数量级，树高只涨 1。

这个模型的假设（每路标 16 字节、每叶子页 100 行）是估算，真实 InnoDB 受页填充率、变长字段影响，数字会浮动，但量级结论稳定：千万级到亿级的表，主键等值查询的页访问次数是个位数。再考虑到 InnoDB 的缓冲池（Buffer Pool，一块缓存热点数据页的内存区域，读写都先经过它），上层路标页几乎常驻内存，实际磁盘 IO 往往只有叶子层那一次。

### 聚簇索引与二级索引：叶子里放的东西不一样

InnoDB 的表本身就是一棵按主键组织的 B+ 树，这叫**聚簇索引**（clustered index）：叶子页存的是完整一行。主键查询顺着树走到叶子，直接拿到整行，一步到位。表没有显式主键时，InnoDB 会生成一个隐藏的自增列当聚簇键（引擎行为）。

**二级索引**（secondary index）是另建的 B+ 树，叶子存的是「索引列的值 + 主键值」，不是整行。用 `idx_link_tenant(tenant_id)` 查 `tenant_id = 1`，走到叶子只拿到一串主键 id，要拿整行还得拿这些 id 回聚簇索引再查一遍。这个过程叫**回表**。回表次数等于命中行数，命中 1 万行就是 1 万次聚簇索引查找，比扫描更糟的时机就来了：优化器可能干脆放弃索引走全表扫描。

如果查询要的列恰好都在二级索引里，就不必回表，这叫**覆盖索引**。它不是一种索引类型，是一种命中状态。比如二级索引的叶子本来就带主键，所以 `SELECT id FROM lf_short_link WHERE tenant_id = 1` 天然覆盖，`EXPLAIN` 的 Extra 列会显示 `Using index`（示意，未实测）。设计时「让查询只摸索引」是常用的优化手法。

顺带回答「为什么主键建议自增」：聚簇索引按主键有序，自增主键总是追加到最右边的叶子页；随机主键（比如 UUID）会命中页中间，页写满了还要把新行硬塞进去，这个动作叫**页分裂**：新申请一个页，把约一半的行挪过去，父节点同步改路标。随机插入造成大量页分裂、页碎片和写放大。项目的所有表主键都是 `BIGINT AUTO_INCREMENT`（V1 建表语句），就是这个理由。

## 联合索引与最左前缀：排序的字典序

### 从两个查询模式说起

`lf_click_log` 有两个高频查询：「某个链接某段时间的点击」「某个租户某段时间的点击」。给 `(link_id)` 和 `(clicked_at)` 各建一个单列索引行不行？查「链接 42 在 10 月的点击」时，`(link_id)` 索引把范围缩小到链接 42 的所有行，然后逐行回表过滤时间。链接 42 有一百万次点击时，回表一百万次。

V1 的答案是联合索引 `KEY idx_click_link_time (link_id, clicked_at)`：**两个列合成一棵有序的树，先按 `link_id` 排，`link_id` 相同时再按 `clicked_at` 排**。等值锁定 `link_id = 42` 之后，剩余部分就是「42 名下按时间有序」的连续区段，范围条件 `clicked_at BETWEEN a AND b` 直接在这个区段上走。回表只发生在真正命中的几百行上。

### 最左前缀：为什么 (a, b) 服务 a 和 a+b，不服务 b

把联合索引想成电话簿：先按省排，省内按市排。类比精确的部分：查「广东省」连续成段，查「广东省深圳市」更精确；类比失效的部分要记住：真实电话簿不能从「市」倒着查全省，索引也一样，`WHERE b = x` 无法用 `(a, b)` 定位，因为脱离了 `a`，`b` 在整棵树里是乱序的。这就是**最左前缀原则**：查询条件必须从索引最左列开始连续命中。

还有一个容易漏的细节：**范围列会断链**。`(a, b)` 下，`a = 1 AND b > 5` 两列都能用（`a` 定位到区段，区段内 `b` 有序）；但 `a > 1 AND b = 5` 只有 `a` 能定位，因为 `a` 是范围时命中了多个 `a` 值，`b` 只在各个 `a` 内部各自有序，整体无序。所以设计口诀是**等值列在前、范围列在后**。补充一个排序因素：**区分度**（列的不同值数量除以总行数，越接近 1 越能缩小范围）高的列更适合放前面，但等值/范围的顺序优先级更高。

### 项目里四个现成的例子

| 索引 | 列 | 服务的查询 |
|------|----|-----------|
| `idx_click_link_time` | `link_id, clicked_at` | 某链接某时间段（等值在前范围在后） |
| `idx_click_tenant_time` | `tenant_id, clicked_at` | 某租户某时间段 |
| `idx_outbox_pending` | `status, next_attempt_at, id` | relay 扫描待发事件 |
| `uk_tenant_username` | `tenant_id, username` | 唯一性按「租户内用户名」判 |

`idx_outbox_pending` 值得多说两句。发件箱表的中继任务（relay）每隔一会儿取一批待发事件，真实 SQL 在 `src/main/java/com/linkforge/stats/mapper/OutboxMapper.java` 13 到 16 行：`WHERE status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= NOW()) ORDER BY id ASC LIMIT #{limit}`。`status` 是等值条件，先把它钉死成小集合；`next_attempt_at` 在剩余集合里过滤；末尾的 `id` 让排序直接沿索引顺序完成，不必额外排序。这不是「看起来有用的列就排进去」，是**对着一条已知的 SQL 逐列设计的**。relay 每次取的 PENDING 行只占总行数很小一部分，这个索引让每次扫描几乎不碰无关数据。

反过来记一条纪律：索引不是越多越好。每多一个索引，每次 INSERT/UPDATE 都要多维护一棵有序树，写入成本和磁盘占用同步上涨。**为已知查询建索引，不为「可能有用」建索引。**

## EXPLAIN：给查询拍 X 光

写完一条 SQL，怎么知道它走没走索引？在语句前加 `EXPLAIN`。它不真执行查询，只让优化器（负责从多种执行路径里挑一种的组件）报出它计划怎么访问数据。优化器依据统计信息估算，结果可能因数据分布变化，所以下文所有 `EXPLAIN` 结论都标注示意。

重点看四列：

| 列 | 看什么 |
|----|--------|
| `type` | 访问方式等级，从好到坏：`const`/`eq_ref`/`ref`/`range`/`index`/`ALL` |
| `key` | 实际选用的索引；NULL 表示没走索引 |
| `rows` | 预估要检查的行数，越接近真实结果集越小 |
| `Extra` | `Using index` 覆盖索引免回表；`Using index condition` 索引条件下推；`Using filesort` 需要额外排序；`Using temporary` 需要临时表 |

`type` 的等级不用死背，理解每个档位的含义即可：`const` 是主键或唯一索引等值命中，最多一行；`eq_ref` 是连接时被驱动表按唯一键命中一行；`ref` 是普通索引等值命中；`range` 是索引范围扫描；`index` 是扫整棵索引树（比全表好一点，因为索引树比表小）；`ALL` 是全表扫描，看到它先问一句能不能加索引或改写。

两条示例（结果示意，未实测；实际数值随数据分布变化）：

```sql
EXPLAIN SELECT id, code, original_url FROM lf_short_link WHERE code = 'aB3xK9';
-- 预期：type=const，key=uk_code，rows=1。唯一索引等值命中，直接定位单行。

EXPLAIN SELECT * FROM lf_click_log WHERE clicked_at > '2026-10-01';
-- 预期：type=ALL 或 index。两个可用索引的最左列都不是 clicked_at，
-- 最左前缀不满足，范围条件无处安放。
```

### 三种常见失效写法，各自坏在哪里

索引存的是「列的原始值」的有序序列。任何让「比较时用的不再是原始值」的写法都会破坏有序性。

- **函数包列**：`WHERE DATE(created_at) = '2026-10-01'`。索引里没有 `DATE(created_at)` 这个计算结果的有序序列，`type` 退化成 `ALL`（示意）。改写成范围条件：`WHERE created_at >= '2026-10-01' AND created_at < '2026-10-02'`，索引恢复工作。初学者这么写是因为它和需求直觉一一对应「查这一天的」，改写只需要意识到「这一天」等价于「半开区间」。
- **隐式转换**：`code` 是 `VARCHAR(16)`，写 `WHERE code = 12345678`（数字字面量）时，按 MySQL 文档的比较规则，字符串和数字比较会把两边都转成浮点数再比。转换发生在**列**这一侧，等于对每行的 code 套了 `CAST`，有序性作废，索引失效。反过来，`id` 是 `BIGINT`，写 `WHERE id = '42'` 时被转换的是常量 `'42'`，列没被动过，索引不受影响。一句话：**转换落在列上才失效**。初学者手滑的根本原因是「看起来等值就行」，而比较规则在语义层面并不对称。这是 MySQL 的规定，别的数据库不同（PostgreSQL 里 `varchar = int` 直接报错），不要跨库迁移这条记忆。
- **前导通配符**：`WHERE code LIKE '%abc'`。B+ 树按前缀有序，「不知道前缀」意味着全部索引都可能是答案，只能全扫。`LIKE 'abc%'` 则可以用上索引前缀定位（示意：`type=range`）。

### 拿三条真实查询走一遍预测流程

第一遍预测，再看示意答案。这条训练的是看到 SQL 先想「最左列有没有、是不是等值」。

```sql
-- 1. 管理端按租户查短链
SELECT * FROM lf_short_link WHERE tenant_id = 1;
-- 预测：有 idx_link_tenant(tenant_id)，等值命中最左列。
-- 示意：type=ref，key=idx_link_tenant；SELECT * 需要回表。

-- 2. 同表按创建时间过滤
SELECT * FROM lf_short_link WHERE tenant_id = 1 AND created_at > '2026-01-01' ORDER BY id;
-- 预测：idx_link_tenant 只帮到 tenant_id；created_at 不在任何索引里，回表后逐行过滤；
-- 好在 ORDER BY id 恰好是主键序，免排序。
-- 示意：type=ref，key=idx_link_tenant，Extra 可能有 Using where，无 Using filesort。

-- 3. 出码接口查重（并发场景，见后文锁一节）
SELECT id FROM lf_short_link WHERE code = 'aB3xK9';
-- 预测：uk_code 等值命中；查询列 id 就是主键，二级索引叶子自带主键，覆盖索引。
-- 示意：type=const，key=uk_code，Extra=Using index。
```

排查慢 SQL 的完整链路：慢查询日志（`slow_query_log` 参数打开后记录超过阈值的语句）或监控发现嫌疑犯，`EXPLAIN` 看 `type/key/rows/Extra`，加索引或改写，再 `EXPLAIN` 复测。这个循环是所有线上慢 SQL 处理的骨架。

## 事务：把两条 INSERT 绑成一条命

问题从项目里来：用户点一次短链，`ClickRecordService.recordClick` 要写一条点击明细（`lf_click_log`），同时往发件箱表（`lf_outbox`）写一条待发事件（`src/main/java/com/linkforge/stats/service/ClickRecordService.java` 40 到 74 行）。两条 INSERT 必须同生共死：明细写了、事件没写，统计就丢了这条点击；反过来，消费端就拿到一条对不上账的事件。`@Transactional` 怎么把两个方法调用打包这件事在 [Spring 核心](30-spring-core.md)讲过，这里回答数据库侧的问题：**MySQL 靠什么兑现「要么全做、要么全没做」和「提交了就断电不丢」**。

ACID 四个字母各自有机制背书：

- **原子性**靠 undo log（回滚日志）。修改任何一行之前，先把「怎么撤销这个修改」记进 undo log。回滚时倒着执行这些反操作：INSERT 的反操作是 DELETE，UPDATE 的反操作是把旧值写回。事务中途任何一条语句失败，从 undo log 把已做的部分一条条擦掉。
- **持久性**靠 redo log（重做日志）。如果每次提交都把数据页直接刷盘，随机写遍布整个磁盘，慢。InnoDB 的做法是 WAL（Write-Ahead Logging，先写日志）：提交时只保证 redo log 落盘，它是顺序追加写，非常快；脏页（改过但还没写回磁盘的数据页）之后由后台慢慢刷。崩溃重启时，重放 redo log 把已提交的修改补回到页上。比喻：会计先在流水账上记一行（快），总账本（数据页）之后再誊写；边界：redo log 记录的是物理页级的修改，不是业务语义的流水。另外服务层还有一份 binlog（归档日志，用于主从复制和按时间点恢复），它与 redo log 的两阶段提交是进阶话题，本章不展开。
- **隔离性**靠锁和 MVCC，本章后面两节专门讲。
- **一致性**不是某个机制给的，是原子性、隔离性、持久性加上约束和应用逻辑共同达到的**结果**。常见误解是把一致性当成数据库白送的属性：数据库能拦住违反唯一约束的写入，拦不住「把订单金额减成负数」这种业务规则的破坏。

一条配套纪律：事务要短。事务进行中，连接被占用、持有的锁不释放，undo log 不能清理。事务里调 HTTP 或发消息是把最慢的环节塞进了锁窗口。项目的 outbox 模式正是把「发消息」挪出事务的方案，见 [RabbitMQ 与 Outbox](51-rabbitmq-outbox.md)。

## 隔离级别：三种读现象与四档对策

并发事务各自执行时，「我能看见别人改到什么程度」就是隔离性要回答的问题。先把三种异常现象各演一遍（A、B 是两个并发事务）：

- **脏读**：B 改了一行还没提交，A 读到了这个值，随后 B 回滚。A 拿着一个从未存在过的数算账。脏在「读了别人没兑现的承诺」。
- **不可重复读**：A 先读一行得 100，B 把它改成 200 并提交，A 再读同一行得 200。同一事务里同一行两次读结果不同。
- **幻读**：A 按条件 `clicked_at > '2026-10-01'` 查得 10 行，B 插入一条符合条件的新行并提交，A 用同样条件再查得 11 行。多出来的像幻觉。它和不可重复读的区别：一个是**已存在的行的值**变了，一个是**结果集的行数**变了。

四种隔离级别按「挡住哪些现象」分层：

| 级别 | 脏读 | 不可重复读 | 幻读 | 默认于 |
|------|------|-----------|------|--------|
| 读未提交 | 有 | 有 | 有 | — |
| 读已提交（RC） | 无 | 有 | 有 | Oracle、PostgreSQL |
| 可重复读（RR） | 无 | 无 | 基本无 | **MySQL InnoDB**（5.x 与 8.0 相同） |
| 串行化 | 无 | 无 | 无 | — |

「基本无」三个字是 MySQL 的特色，值得展开。RR 挡幻读靠两套机制：普通 SELECT 是**快照读**，走 MVCC，看不见事务开始后新提交的行，天然没有幻读；`UPDATE/DELETE/SELECT ... FOR UPDATE` 是**当前读**，必须读最新版本并加锁，靠临键锁挡住并发插入。但一个事务里先快照读、后当前读时，两类读的口径不同，仍可能观察到幻读现象。下一节讲快照读的机制，锁一节讲当前读的机制。

## MVCC：同一行怎么同时存在多个版本

### 问题：加读锁会堵死并发

RR 要求事务内多次读结果一致。最直接的实现是 A 读过的行，B 就不许改（读写互斥加锁），代价是读堵写、写堵读，报表跑起来业务全卡住。InnoDB 的答案是不堵：**写的人保留旧版本，读的人按自己的口径挑一个版本**。这就是 MVCC（Multi-Version Concurrency Control，多版本并发控制）。它只服务于普通 SELECT；加锁的当前读不走它。

### 版本链：undo log 把旧版本串起来

InnoDB 每行有两个隐藏列（引擎内部，SELECT 查不到）：`DB_TRX_ID` 记最后修改它的事务 id，`DB_ROLL_PTR` 指向 undo log 里的上一个版本。一行被反复修改，undo log 把历史版本串成链：

```text
最新行 (trx_id=105) --roll_ptr--> 旧版本 (trx_id=101) --> 更旧 (trx_id=98) --> null
```

### ReadView：一张判断「谁可见」的名单

事务执行普通 SELECT 时生成一个 **ReadView**（读视图），本质是那一刻的事务进度快照。四个字段：

| 字段 | 含义 |
|------|------|
| `m_ids` | 生成视图时仍活跃（已启动未提交）的事务 id 集合 |
| `min_trx_id` | `m_ids` 中最小值 |
| `max_trx_id` | InnoDB 将要分配给下一个事务的 id（不是 m_ids 的最大值） |
| `creator_trx_id` | 创建本视图的事务自己 |

拿版本链上某个版本的 `trx_id` 逐条判断（每条给为什么）：

1. `trx_id == creator_trx_id`：这是我自己改的，可见。
2. `trx_id < min_trx_id`：生成视图时它已经结束（不在活跃集里，又比最小活跃 id 还小），只能已提交，可见。
3. `trx_id >= max_trx_id`：视图生成之后才开启的事务，我的快照里根本没有它，不可见。
4. `min_trx_id <= trx_id < max_trx_id`：看它在不在 `m_ids` 里。在，说明视图生成时它还没提交，不可见；不在，说明它已经提交，可见。

版本不可见就沿 `DB_ROLL_PTR` 回退一个版本重新判断，直到找到可见版本或链条尽头。所以「快照」不是复制了一份表，而是「一份名单 + 沿链回溯」，代价远小于复制。

**RC 与 RR 的全部差别浓缩成一句：RC 每次普通 SELECT 都新建 ReadView，所以能读到别人新提交的值；RR 只在事务第一次快照读时建一次并全程复用，所以多次读结果一致。** 把这句答出来，隔离级别的题就从背表格升级到讲机制。

两个配套追问：

- **快照读与当前读混用仍见幻读**：事务 A 先 SELECT（建视图），事务 B 插入新行并提交，A 再执行 `UPDATE`。UPDATE 是当前读，能看到 B 的新行并修改它，修改后 A 再 SELECT 时该行 `trx_id` 已是 A 自己，可见。同一个事务里「看不见又改得着」，机制层的原因就是两类读的口径不同。
- **为什么不要长事务**：ReadView 还活着，它可能引用的旧版本就不能被清理（purge），undo log 不断堆积，磁盘上涨、回滚变慢。这是「长事务危害」的机制级答案，比「锁持有时间长」更深一层。

### 放回项目里

`ClickRecordService.recordClick` 的事务里只有两条 INSERT（写新行，不读不改旧行），MVCC 在这条热路径上几乎不参与，它的价值在别处：统计、报表这类长查询跑在同一个库上时，靠快照读不阻塞业务写入。`ClickStatsService` 的消费事务会在一个事务里写明细、改日聚合（`src/main/java/com/linkforge/stats/service/ClickStatsService.java` 66 到 107 行），写写冲突走锁，见下一节。以上两句对 MVCC 参与度的判断是据代码推断。

## 锁：写写冲突、间隙与唯一索引兜底

### 行锁挂在索引上

MVCC 让读不堵写，但两个事务同时改同一行时必须分出先后，这就是写锁的职责。InnoDB 的行锁加在**索引记录**上，不是逻辑意义上的「行」上。这带来一个关键推论：`UPDATE lf_click_log SET ip = '' WHERE user_agent = 'bot'`，`user_agent` 没有索引，执行时全表扫描，RR 下扫过的每一行都被加上临键锁，等效锁全表。这就是「UPDATE 条件必须走索引」的锁层面依据：不走索引不只是慢，还会把并发全部堵死。

### 间隙锁与临键锁：RR 的当前读为什么锁「空隙」

RR 要求当前读的结果可重复，光锁住已存在的行不够：别人插入一条满足条件的新行，第二次读就多出东西。所以 InnoDB 在 RR 下把锁的单位做成**临键锁**（next-key lock）：记录锁加上记录**之前**那段空隙的**间隙锁**（gap lock），区间形式是前开后闭，比如 `(10, 20]`。间隙锁锁的是「往这段空隙里插东西」这个动作本身。

三条加锁规则（丁奇《MySQL 实战 45 讲》的口径，面试可直接引用）：

1. 加锁基本单位是 next-key lock。
2. **唯一索引等值命中**时退化为只加记录锁：等值命中说明那个值已经存在，不存在「防它再插入一次」的需求，空隙不用守。等值**未命中**则加间隙锁。
3. 等值查询向右遍历到第一个不满足条件的值时，该值的 next-key lock 退化为间隙锁。

间隙锁的副作用是死锁高发：两个事务可以同时持有同一个间隙的间隙锁（彼此不冲突），但都挡对方的插入。A 拿着间隙准备插入，B 也拿着同一间隙准备插入，A 等 B 放锁，B 等 A 放锁，谁也插不进去。定位手段：`SHOW ENGINE INNODB STATUS` 输出里找 `LATEST DETECTED DEADLOCK` 段，能看到两边的锁等待；把 `innodb_print_all_deadlocks` 打开后所有死锁都会写进错误日志。悲观锁（`SELECT ... FOR UPDATE`）适合冲突激烈的短事务；乐观锁（版本号列，UPDATE 时带上 `WHERE version = ?`）适合冲突少的场景，本项目没有用到乐观锁列。

### 项目核心案例：uk_code 是并发兜底

需求：创建短链时要得到一个全局唯一的码。直觉写法是先查一下库里有没有，没有再插入。把这段时序单独演出来。下面的程序不用真线程（真并发的输出顺序不可复现，而本文档要求输出确定），它按两个请求真实的交错顺序，单线程重放每一步：

```java
import java.util.ArrayList;
import java.util.List;

/**
 * 模拟"先 SELECT 再 INSERT"的漏判与唯一索引兜底。
 * 不用真线程：按两个请求的真实交错时序，单线程重放每一步，输出确定。
 */
public class CheckThenActRace {
    static List<String> table = new ArrayList<>();

    static String select(String code) {
        return table.contains(code) ? "FOUND" : "EMPTY";
    }

    // 模拟没有唯一约束的插入：来者不拒。
    static void insertNoKey(String req, String code) {
        table.add(code);
        System.out.println(req + " insert ok, rows=" + table.size());
    }

    // 模拟有唯一索引的插入：撞码被存储引擎拒绝，等价 Java 侧的 DuplicateKeyException。
    static void insertWithUniqueKey(String req, String code) {
        if (table.contains(code)) {
            System.out.println(req + " insert rejected: duplicate key " + code);
            return;
        }
        table.add(code);
        System.out.println(req + " insert ok, rows=" + table.size());
    }

    public static void main(String[] args) {
        String code = "abc";

        table.clear();
        System.out.println("-- no unique key --");
        System.out.println("A select: " + select(code)); // 请求 A 查，没有
        System.out.println("B select: " + select(code)); // 请求 B 查，A 还没插，也没有
        insertNoKey("A", code);
        insertNoKey("B", code);                          // 重复行出现

        table.clear();
        System.out.println("-- with unique key --");
        System.out.println("A select: " + select(code));
        System.out.println("B select: " + select(code));
        insertWithUniqueKey("A", code);
        insertWithUniqueKey("B", code);                  // 被约束拦下
    }
}
```

```text
-- no unique key --
A select: EMPTY
B select: EMPTY
A insert ok, rows=1
B insert ok, rows=2
-- with unique key --
A select: EMPTY
B select: EMPTY
A insert ok, rows=1
B insert rejected: duplicate key abc
```

前半段就是漏判：A 和 B 的 SELECT 都在对方 INSERT 之前执行，都得到「不存在」，双双插入成功，表里出现两个 `abc`。这不是程序 bug，是「检查」和「动作」之间天生有缝隙。模拟的边界也要说清：真实数据库里，「检查唯一性 + 插入」是存储引擎内部靠锁和约束序列化的原子序列，Java 层模拟不出它的内部细节；正因为应用层怎么写都堵不住这个缝隙，防线必须建在数据库层。**唯一索引是并发下唯一可靠的「查重」**：第二个 INSERT 直接失败，失败方收到错误去处理。

现在读项目的真实实现。`src/main/java/com/linkforge/link/service/ShortLinkService.java` 的 `doCreate` 方法（144 到 203 行）没有「先查后插」，生成码之后直接 INSERT，撞了就处理失败：

- 168 到 169 行的注释原话：`uk_code is the concurrency backstop`（uk_code 是并发兜底）。
- 自定义码分支（171 到 178 行）：`shortLinkMapper.insert(link)` 抛 `DuplicateKeyException` 时翻译成 `BizException(409, "code already exists")`。用户自己起的码撞了是业务冲突，让用户换一个。
- 随机码分支（180 到 189 行）：循环最多 `CODE_ATTEMPTS`（62 行定义，值为 16）次，每次生成新随机码再 INSERT，撞了记一条日志换下一个。随机码空间大，撞码是低概率事件，重试几次必然成功；16 次全撞说明码空间快满了，190 到 192 行抛「failed to allocate short code」。
- 依赖的框架行为：MyBatis 抛出的 SQL 异常经过 Spring 的异常翻译层，转成 `DuplicateKeyException`（`org.springframework.dao` 包的 `DataAccessException` 子类）。业务代码捕的是 Spring 的抽象异常，不耦合 MySQL 的错误码。
- 代价与边界：撞唯一索引的插入做了一次无用功（还要回滚那条失败语句），但随机码场景撞码率极低，这点代价换来「任何并发强度下都不会出现重复码」，值得。它适用的前提是失败路径 cheap：如果撞码是常态（比如用递增序列当码），重试就是错误设计。

同样的兜底模式在消费端还有一处。`ClickStatsService.consume`（`src/main/java/com/linkforge/stats/service/ClickStatsService.java` 66 到 107 行）做消费幂等：`lf_consumed_event` 表用 `event_id` 当**主键**，处理事件前先插这行，撞主键说明事件处理过，直接返回 false 跳过（70 到 78 行）。方法注释点明了语义：「A duplicate key inside the callback is a plain skip: MySQL rolls back only the failed statement」，撞键时 MySQL 只回滚失败的那条语句，不影响同事务里已成功的部分。

第三个例子是计数。日聚合表 `lf_click_daily` 的唯一键 `uk_daily_link_date(link_id, stat_date)` 保证「一个链接一天一行」，累加用的是 `ON DUPLICATE KEY UPDATE clicks = clicks + 1`（`src/main/java/com/linkforge/stats/mapper/ClickDailyMapper.java` 17 到 19 行）。为什么不用「先 SELECT 得到 clicks，再 UPDATE 写回」：两个并发消费者都读到 5，都写回 6，丢一次计数，这是典型的丢更新；而且「先查」是快照读，读到旧值再回写必然丢。`ON DUPLICATE KEY UPDATE` 把「读最新值 + 加一」压进一条语句，当前读加行锁，并发排队发生在单行的锁上，既不丢数也不锁别的行。**把「读最新 + 改」交给一条 SQL，等于把锁交给了数据库的行锁，比应用层加锁便宜且正确。**

## 字段类型与字符集：项目里两笔真实的账

### DATETIME 与 TIMESTAMP：字面值与时区

两者都存日期时间，行为完全不同。`DATETIME` 存字面值，写进去是几点读出来就是几点，与时区无关。`TIMESTAMP` 内部按 UTC 存，读写时按会话的 `time_zone` 参数转换，同一个值换一个时区的连接读出来就变。应用与数据库时区配置不一致时，混用两者的经典症状就是「时间差了 8 小时」。

项目所有表的时间列统一用 `DATETIME`（V1 到 V7 全部核验过，没有一列 TIMESTAMP），JDBC URL 显式带 `serverTimezone=Asia/Shanghai`（`src/main/resources/application.yml` 51 行），数据库容器版本是 MySQL 8.0（`docker-compose.yml`）。配套纪律：「调度时间字段用数据库时钟，业务时间戳用 JVM 时钟」，事故案例见 [RabbitMQ 与 Outbox](51-rabbitmq-outbox.md)。

### 字符集与排序规则：utf8mb4 与那场 _bin 决策

先分清两层。**字符集**（charset）规定字符怎么编码成字节：MySQL 的 `utf8` 是历史遗留的残缺实现，一个字符最多 3 字节，装不下 emoji 和很多生僻字，真正的 UTF-8 在 MySQL 里叫 `utf8mb4`（最多 4 字节）。**排序规则**（collation）是同一字符集下的比较与排序约定：两个字符串相不相等、谁排在前面，由它说了算。`utf8mb4_0900_ai_ci` 的后缀含义：`0900` 指 Unicode 9.0 的排序标准，`ai` 忽略重音，`ci` 忽略大小写。这套 `0900` 规则和「NO PAD」语义都是 MySQL 8.0 引入的（5.7 的所有排序规则都是 PAD SPACE），5.7 上找不到等价替代，只能应用层处理或接受 pad 行为。

`_ci` 忽略大小写意味着 `'AbC'` 和 `'abc'` 在比较时相等。对短链码这是致命的：码是区分大小写的业务语义。

V4 迁移（`src/main/resources/db/migration/V4__short_code_case_sensitive.sql`）完整记录了这轮决策，它的注释值得当技术文档范文读：

- **问题**：V1 建表时 `code` 列继承了库默认排序规则 `utf8mb4_0900_ai_ci`（不区分大小写）。后果不是报错，而是行为随开关漂移：项目有一道 Bloom 过滤器挡「从未创建过的码」（见 51 篇），Bloom 开着时 `/r/abC` 被内存里的精确匹配拒绝，Bloom 关掉时数据库的 ci 比较却放行。同一个 URL，行为取决于一个运维开关，这类问题最难排查。
- **为什么不用 `utf8mb4_bin`**：它是二进制比较、区分大小写，但属于 PAD SPACE 语义（比较前补齐尾部空格），`'abc'` 与 `'abc '` 相等。短链 `/r/abc%20`（带空格）会被 Bloom 的精确比较拒绝，却能在数据库里 pad 成 `abc` 命中，「行为随开关漂移」换个姿势复发。
- **选择**：`utf8mb4_0900_bin`，NO PAD 且区分大小写，语义与 Bloom 的精确字符串比较完全一致。
- **两条纪律**：`lf_click_log.code`、`lf_click_daily.code` 这些冗余快照列不改（它们从不参与比较和 join，改它们要重建持续增长的明细大表）；跨表关联必须走 `link_id` 不许走 `code`，两列排序规则不同时直接比较会报 ERROR 1267（illegal mix of collations）。

这轮决策的完整备选与代价记录在 [决策记录](82-linkforge-decisions.md) 的 D12 条，迁移注释原文是第一手材料。

### 逻辑删除与唯一键的共存：占着码位的「已删除」行

项目用逻辑删除：`ShortLink` 实体的 `deleted` 字段标了 `@TableLogic`（`src/main/java/com/linkforge/link/entity/ShortLink.java` 26 到 27 行），MyBatis-Plus 把 `DELETE` 翻译成 `UPDATE deleted = 1`（框架行为，细节见 [MyBatis-Plus](41-mybatis-plus.md)）。行还在表里，`uk_code` 上这个码还被占着。

于是得到一个别处花钱都买不到的特性：**删除的短码永久不可复用**。`ShortLinkService.delete` 的注释（`src/main/java/com/linkforge/link/service/ShortLinkService.java` 325 行）写明这是有意的：码位保留，防止「旧码指向新目标」的混淆。这个特性是「逻辑删除」和「唯一索引」两个决定合力的免费副产品。

它也带来两个必须知道的代价。其一，列表查询会被自动追加 `deleted = 0` 条件。其二，MyBatis-Plus 的 `updateById` 默认更新策略跳过 null 字段，想把访问密码清空、传 `accessPasswordHash = null` 等于什么都没改。项目在 `update` 方法里用 `LambdaUpdateWrapper.set(col, null)` 显式写 `SET NULL`（`ShortLinkService.java` 289 到 296 行，注释引用决策 D-Bw345-32）。初学者在这一步的典型困惑是「我明明传了 null」，原因在框架的默认策略而不是数据库。

## 迁移纪律：Flyway 与 V1 到 V7

问题：三个环境（本机、测试、生产）的表结构怎么保持一致？靠人手敲 SQL 迟早漂移。项目的答案是 Flyway：每个结构变更写成带版本号的 SQL 文件，应用启动时按序执行，执行记录存进 `flyway_schema_history` 表。

`src/main/resources/db/migration/` 下的 V1 到 V7：

| 版本 | 文件 | 内容 |
|------|------|------|
| V1 | `V1__baseline_schema.sql` | 六张基线表：租户、用户、短链、点击明细、日聚合、消费幂等 |
| V2 | `V2__outbox.sql` | `lf_outbox` 发件箱表；`lf_click_log` 加 `event_id` 列 |
| V3 | `V3__click_dim_daily.sql` | `lf_click_dim_daily` 维度聚合表（设备/来源域名） |
| V4 | `V4__short_code_case_sensitive.sql` | `code` 列排序规则改 `utf8mb4_0900_bin` |
| V5 | `V5__api_key.sql` | `lf_api_key`，唯一键在 `key_hash` 上 |
| V6 | `V6__webhook.sql` | Webhook 端点与投递两表，投递幂等靠 `(endpoint_id, event_id)` 唯一键 |
| V7 | `V7__link_access_password.sql` | `lf_short_link` 加 `access_password_hash` 列 |

baseline 机制解决存量库问题。V1 头注释写明：V1 只在空库执行；已经有表但没有 Flyway 历史的存量库，由 `baseline-on-migrate` 在版本 1 打基线后跳过 V1。对应配置在 `src/main/resources/application.yml` 13 到 19 行：`enabled: true`、`locations: classpath:db/migration`、`baseline-on-migrate: true`、`baseline-version: 1`。

两条纪律：**已发布的迁移文件永不修改**，新的结构变更写 V8（改历史文件会让已执行该版本的环境与未执行的Environment结构对不上）；仓库根下的 `src/main/resources/db/schema.sql` 头注释标明 REFERENCE ONLY，它只是给集成测试用的形状参考，真实 schema 以 migration 目录为准，别在这里加 DDL。

## 常见错误与误区

- 「加了索引就快」：不满足最左前缀、区分度太低、回表行数太多，都会让索引白建。判断标准是 `EXPLAIN`，不是感觉。
- 「先查后插没问题」：并发下必漏，演示见上文。唯一索引才是防线，应用层只负责处理失败。
- 「RR 就完全没有幻读」：快照读没有，当前读靠临键锁；一个事务里两类读混用，仍能观察到幻读现象。能讲清机制才算懂。
- 大事务：事务里调 HTTP 或发消息，连接被长占、锁窗口拉长、undo 无法清理。事务里只做数据库操作，外部调用挪出去（outbox 模式就是标准解法）。
- `SELECT *`：逼着回表拿全部列，网络传输也翻倍。列表查询按需选列，能覆盖索引的查询明确只查索引列。

## 面试常见问题与追问

- **为什么 B+ 树，不用哈希/红黑树/B 树？** 统一口径是磁盘 IO 贵、按页读：哈希不支持范围和排序；红黑树二叉太深（2000 万行约 24 层）；B 树非叶也存数据，扇出小树更高。B+ 树非叶只放路标扇出上千，2000 万行 3 层，叶子串链表利于范围扫描。追问「2000 万行为什么还是 3 层」，用本文的页模型推导：20 万叶子页，除以扇出 1024 两轮就到根。
- **RR 怎么实现的？RC 和 RR 差在哪？** 版本链（undo log）+ ReadView 四字段 + 可见性四规则；差别只有一句「何时建视图」：RC 每次快照读新建，RR 第一次建后复用。追问「当前读呢」答加锁（记录锁/间隙锁/临键锁）；追问「长事务危害」答旧版本无法 purge，undo 堆积。
- **唯一索引在并发里扮演什么角色？** 「检查-然后-动作」在并发下有缝隙，唯一约束把判定下沉到存储引擎的原子序列，失败方收到 `DuplicateKeyException`。追问「唯一索引的代价」答插入要做唯一性检查、写入变慢，但换来并发正确性；追问项目实例答 `uk_code` 撞码重试、`lf_consumed_event` 主键幂等、`uk_daily_link_date` 配合 `ON DUPLICATE KEY UPDATE` 原子累加。

## 本章总结

索引侧：B+ 树矮胖的本质是扇出大（一页上路标多），树高即页访问次数；聚簇索引叶子是整行，二级索引叶子是主键，回表是常态，覆盖索引是优化目标；联合索引的物理事实是「先按第一列排，相同时按第二列排」，最左前缀和范围断链都从这里推出。事务侧：原子性靠 undo log，持久性靠 redo log（WAL），隔离性靠锁加 MVCC，一致性是结果不是机制。MVCC 的核心是版本链加 ReadView，RC 与 RR 差在「何时建视图」。锁的核心事实是行锁挂在索引上，无索引更新等效锁全表，唯一索引等值命中退化为记录锁。项目侧必记四件事：`uk_code` 撞码重试是并发兜底的范式；`idx_outbox_pending` 是「为已知查询建索引」的范式；V4 的 `_bin` 决策说明排序规则是业务语义；逻辑删除与唯一键合力白送码位保留，但清空字段必须显式 `SET NULL`。

新代码里怎么判断该不该建索引：先问这条查询的等值列、范围列各是谁，按「等值在前、范围在后」排联合索引的列序，再问写入频率是否承受得起，最后 `EXPLAIN` 验证。

## 自测

1. 为什么「先 SELECT 再 INSERT」挡不住并发重复？
   答：两个请求的 SELECT 都在对方 INSERT 之前执行，都得到「不存在」，双双插入。唯一索引把唯一性判定下沉到存储引擎的原子序列，失败方收到 `DuplicateKeyException`，由应用决定重试还是报错。
2. `idx_click_link_time(link_id, clicked_at)` 存在时，`WHERE clicked_at > '2026-01-01'` 会用它定位吗？
   答：不会。最左列 `link_id` 缺失，`clicked_at` 在整棵索引树里不是全局有序的。统计全表某时段应走 `idx_click_tenant_time(tenant_id, clicked_at)` 并带 tenant 条件。
3. ReadView 的四个字段是什么？RC 和 RR 的差别一句话怎么说？
   答：`m_ids`（活跃事务集合）、`min_trx_id`、`max_trx_id`（下一个将分配的事务 id）、`creator_trx_id`。差别：RC 每次快照读新建 ReadView，RR 第一次快照读建一次并复用，所以 RR 事务内多次读结果一致。
4. 删除一条短链后，这个码还能被别人创建吗？为什么？
   答：不能。逻辑删除只把 `deleted` 置 1，行还在，`uk_code` 上码仍被占用。这是有意设计的码位保留，防止旧码被复用后指向新目标。
5. 清空短链的访问密码为什么不能直接 `updateById` 传 null？
   答：MyBatis-Plus 默认更新策略（NOT_NULL）跳过 null 字段，传 null 等于不改这个字段。必须用 `LambdaUpdateWrapper.set(col, null)` 显式生成 `SET access_password_hash = NULL`。

## 下一步

- 表上的 CRUD 怎么由 MyBatis-Plus 生成与拦截、逻辑删除的实现细节，见 [41-mybatis-plus.md](41-mybatis-plus.md)。
- `lf_outbox` 在消息链路里的角色、relay 与消费幂等的全链路，见 [51-rabbitmq-outbox.md](51-rabbitmq-outbox.md)。
- `@Transactional` 的代理机制、传播行为与事务失效，见 [30-spring-core.md](30-spring-core.md)。
- V4 排序规则决策的备选与代价全记录，见 [82-linkforge-decisions.md](82-linkforge-decisions.md)。
- 全部表结构的原始 SQL 从 `src/main/resources/db/migration/V1__baseline_schema.sql` 起共七个版本。
