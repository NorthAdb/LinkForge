# 6.3 生命周期与 CSV：list、detail、update、启停、delete 与批量导入

> 本课回答一个问题：一条短链被改、被停、被删时，数据库、单飞锁、三级缓存、广播各自发生什么？一万行 CSV 为什么能安全地灌进同一个入口？

## 1. 本节学习目标

学完本课，你应该能：

1. 说出 list/detail/update/disable/enable/delete 六个方法的实现差异，以及它们共用哪三个私有方法。
2. 解释「写穿加 1 秒延迟双删加广播」三件套在每次写路径上的完整配合。
3. 复述 `singleFlight.peek` 与两参 `forget` 的时序纪律，说出它防的是什么竞争。
4. 解释 CSV 导入的专属限流、体积上限、行数上限与「逐行走 doCreate」的防漂移意义。
5. 说出导入响应的两种行结果，以及 `access_password` 列为什么永不回显。

## 2. 前置知识

- 模块 6.1 的三态语义（null 不动、空数组清空、clearAccessPassword 显式表态）。
- 模块 6.2 的 doCreate 校验链：本课的 update 与导入会反复引用它。
- MyBatis-Plus 的 `updateById` 与 `LambdaUpdateWrapper`：前者按实体非空字段更新，后者手工拼 SET 子句。本课有一个两者必须混用的场景。
- Caffeine 本地缓存与 `SingleFlight` 并发合并：模块 8 会深讲，本课只讲它们在写路径上的正确配合方式。
- `PageResponse`：通用分页包装，模块 2.1 已讲。

## 3. 本节涉及的源码

| 文件 | 位置 | 职责 | 阅读顺序 |
|----|----|----|----|
| `ShortLinkService.java` | :285-330 | list/pageOwn/exportOwnCsv | 1 |
| 同上 | :339-444 | importCsv 与行映射 | 2 |
| 同上 | :446-521 | detail 与 update | 3 |
| 同上 | :523-558 | disable/enable/delete | 4 |
| 同上 | :966-999 | cacheUrl/evictCache/scheduleStaleClear | 5 |
| `ShortLinkController.java` | :50-97 | 六个端点 | 6 |
| `CsvFormat.java` | 18 行 | RFC4180 转义 | 7 |
| `CsvImportParser.java` | 102 行 | 逐字符 CSV 状态机 | 8 |
| `ShortLinkMapper.java` | :13-23 | selectAllCodes 绕过逻辑删 | 9 |

## 4. 类的定位（在整个项目中的位置）

如果说 6.2 的 `create` 是这个域的「进水口」，本课的六个方法就是「日常水位调节」。它们共享一条纪律：任何改变跳转行为的写，都要把缓存里的旧值处理干净。这条纪律具体化为一个固定收尾序列，出现在 update、setStatus、delete 三处（`ShortLinkService.java:512-519、537-540、554-557`）：

```java
singleFlight.forget(code, priorFlight);
... 写库 ...
cacheUrl 或 evictCache
scheduleStaleClear(code);
cacheBus.publishL1Invalidate(code);
```

本课的核心不是六个 CRUD 的语法，而是这段收尾序列为什么长这样。

## 5. 核心源码详解

### 5.1 list 与 pageOwn：一套查询两种视野

```java
public PageResponse<ShortLinkDetail> list(int page, int size) {
    AuthUser user = requireUser();
    return pageOwn(user, Math.max(1, page), size < 1 || size > 100 ? 20 : size);
}

private PageResponse<ShortLinkDetail> pageOwn(AuthUser user, long current, long pageSize) {
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
（`src/main/java/com/linkforge/link/service/ShortLinkService.java:285-301`）

视野由 `isTenantWide`（:906-908）决定：JWT 用户与 USER scope 的 API Key 只见自己创建的行（按 `createdBy` 过滤），TENANT scope 的 API Key 见整个租户（按 `tenantId` 过滤）。两种身份的细节在模块 12.1。分页参数在 :287 收口：page 最小 1，size 限 1..100，默认 20。列表行走 `toDetail`，variants 恒为 null（6.1 课讲过的查询成本取舍）。

越权访问在这里就已经消失：条件拼进 WHERE，别人的行根本不会从数据库出来。而 `getOwnedLink`（:890-903）对查不到的行统一抛 404，不区分「不存在」与「不属于你」，不给枚举者任何信息。

### 5.2 detail：唯一填充 variants 的读路径

`detail`（:446-448）只有一行有效代码：`toDetailWithVariants(getOwnedLink(code))`。归属检查用 `getOwnedLink`，转换用 6.1 课讲过的 `toDetailWithVariants`（:1010-1017），额外发一次 variant 表查询。它是三个 stats 端点的前置归属检查（模块 11.3 复用）。

### 5.3 update：本课最长的写方法

```java
public ShortLinkDetail update(String code, UpdateShortLinkRequest request) {
    rejectSmuggledAttribution(request.getUnknown());
    boolean hasUrl = request.getOriginalUrl() != null && !request.getOriginalUrl().isBlank();
    boolean clearPassword = Boolean.TRUE.equals(request.getClearAccessPassword());
    boolean setPassword = request.getAccessPassword() != null && !request.getAccessPassword().isBlank();
    Map<String, String> utm = UtmMerger.canonical(request.getUtmSource(), request.getUtmMedium(),
            request.getUtmCampaign(), request.getUtmTerm(), request.getUtmContent());
    if (!hasUrl && request.getTitle() == null && request.getExpireAt() == null
            && !clearPassword && !setPassword && utm.isEmpty() && request.getVariants() == null) {
        throw new BizException("nothing to update");
    }
```
（`src/main/java/com/linkforge/link/service/ShortLinkService.java:450-460`）

方法分四段：意图收集、合法性互斥检查、字段应用、缓存收尾。

**合法性段**（:461-471）做四件事，全部在任何读写之前：clearPassword 与 setPassword 互斥、expireAt 必须未来、variants 整组校验（null 则 replacement 也为 null）、取行。注意取行发生在校验之后，校验失败连数据库都不碰。

**字段应用段**（:476-504）有三个容易踩坑的细节。

细节一：URL 更新是「合并后再验证」。只发 utm 不发 URL 也是合法更新（D-C1-6），此时从存量 URL 出发做合并（:477）。合并结果必须过 `requireHttpUrl` 与 `rejectBlockedTarget`，与创建同规格（:478-479）。

细节二：清密码必须显式 SET NULL。`updateById` 默认跳过 null 字段，直接把 hash 置 null 再 update 什么都不会发生。所以走 `LambdaUpdateWrapper`：

```java
if (clearPassword) {
    // MyBatis-Plus's default NOT_NULL update strategy skips null fields in
    // updateById — the clear must be an explicit SET NULL (D-Bw345-32).
    shortLinkMapper.update(null, new LambdaUpdateWrapper<ShortLink>()
            .eq(ShortLink::getId, link.getId())
            .set(ShortLink::getAccessPasswordHash, null));
    link.setAccessPasswordHash(null);
}
shortLinkMapper.updateById(link);
```
（`src/main/java/com/linkforge/link/service/ShortLinkService.java:496-504`）

细节三：variants 整组替换（:505-511）在小事务里先 `DELETE` 再 `insertVariants`，6.1 课的整组语义在这里落地。主行 updateById 不在这个事务里，因为主行更新失败时 variants 根本不会被触碰。

**缓存收尾段**（:512-519）是本课主线，5.6 节专讲。

### 5.4 disable/enable/delete：两个轻写与一个双删

```java
private void setStatus(String code, int status) {
    ShortLink link = getOwnedLink(code);
    link.setStatus(status);
    link.setUpdatedAt(LocalDateTime.now());
    CompletableFuture<ShortLink> priorFlight = singleFlight.peek(code);
    shortLinkMapper.updateById(link);
    singleFlight.forget(code, priorFlight);
    evictCache(code);
    scheduleStaleClear(code);
    cacheBus.publishL1Invalidate(code);
}

/** Logical delete: the code stays reserved (uk_code) and cannot be reused.
 * C3-N3: variants are hard rows (no logical-delete column) — they must not outlive
 * the link, so both deletes commit as one unit; cache evictions stay outside. */
public void delete(String code) {
    ShortLink link = getOwnedLink(code);
    CompletableFuture<ShortLink> priorFlight = singleFlight.peek(code);
    transactionTemplate.executeWithoutResult(status -> {
        shortLinkMapper.deleteById(link.getId());
        variantMapper.delete(new LambdaQueryWrapper<LinkVariant>()
                .eq(LinkVariant::getLinkId, link.getId()));
    });
    singleFlight.forget(code, priorFlight);
    evictCache(code);
    scheduleStaleClear(code);
    cacheBus.publishL1Invalidate(code);
}
```
（`src/main/java/com/linkforge/link/service/ShortLinkService.java:531-558`）

setStatus 与 delete 的收尾序列完全一致，差别在中间：delete 是事务内双删（主行逻辑删加 variants 硬删同事务，C3-N3），并且用 `evictCache` 主动删除而不是 `cacheUrl` 写穿。停用后的短链不该再有任何缓存可命中，删干净比预热更符合语义。

delete 之后短码永久保留的机制在 6.1 课讲过：逻辑删不释放 `uk_code`。这里补上 Bloom 侧的另一半：Bloom 过滤器无法撤销已放入的元素（`CodeBloomFilter.java:34-35` javadoc 原文「Deleted codes stay positive (classic Bloom cannot retract)」），已删短码会一直 Bloom 阳性，然后落到行读环节由 `deleted=0` 过滤后 404。`ShortLinkMapper.selectAllCodes`（`ShortLinkMapper.java:13-23`）的注释把这件事讲反了方向，值得原文引用：

```java
/**
 * A9: startup seed for the process-local short-code Bloom filter. Native SQL
 * bypasses @TableLogic on purpose: logically-deleted rows still hold their
 * uk_code, so they must stay Bloom-positive and be rejected by the DB
 * (deleted=0 filter) instead of turning into pre-DB 404s.
 */
@Select("SELECT code FROM lf_short_link")
List<String> selectAllCodes();
```
（`src/main/java/com/linkforge/link/mapper/ShortLinkMapper.java:13-23`）

绕过逻辑删是刻意的：已删行的码必须继续 Bloom 阳性，让请求走到数据库、由唯一索引与 deleted 过滤给出一致的 404。如果播种时跳过已删行，Bloom 会对这些码给出阴性，直接 404，看起来更快。但「Bloom 开关切换会改变已删码的响应路径」正是 V4 迁移注释里批判过的「行为取决于开关」反模式。

### 5.5 peek/forget：两参删除的时序纪律

update/setStatus/delete 都在写之前先 `peek`、提交后 `forget(code, priorFlight)`（:495/:512、:535/:537、:548/:554）。要理解这组配合，先知道 `SingleFlight` 是什么：同码并发读的合并器，第一个到的人执行查询，后来的人搭车等结果。模块 8.4 深讲它的实现，本课只讲写路径必须遵守的纪律。

`peek` 返回「此刻正在飞行中的那个读任务」（`SingleFlight.java:103-106`）。`forget(code, priorFlight)` 只删除「仍然是 priorFlight 那一个」的飞行记录（:115-119）。`SingleFlight.java:111-113` 的注释承认了一个残留窗口：peek 之后、提交之前新起飞的读不会被 forget 掉。update 的注释（:492-494）解释了为什么这个窗口可接受：

```java
// Snapshot the in-flight row read BEFORE the commit so the post-write forget
// can never kill a flight that started after it and reads the fresh row
// (A10 audit N1).
```
（`src/main/java/com/linkforge/link/service/ShortLinkService.java:492-494`）

纪律的两面：提交**前** snapshot，保证 snapshot 捕到的飞行读的是旧行，值得清掉。提交**后** forget，保证绝不清掉一个「提交之后才起飞、必然读到新行」的飞行。用无参 forget 会误杀新飞行，让读到新行的请求白白重查一次库，错的是纪律而不是结果。

### 5.6 写穿、延迟双删、广播的三层配合

update 收尾的三行（:513-519）是缓存一致性的全部现场：

```java
// Write-through now, then a delayed delete clears any stale write-back a racing
// reader may have cached from the pre-commit row. Final consistency window ~1s.
cacheUrl(code, effectiveUrl);
scheduleStaleClear(code);
// D-C2-16: one broadcast per resolution-changing write, at the write itself —
// never from scheduleStaleClear's delayed pass (that would publish twice).
cacheBus.publishL1Invalidate(code);
```
（`src/main/java/com/linkforge/link/service/ShortLinkService.java:513-519`）

三个动作各防一种竞态。

写穿（cacheUrl，:966-977）：把新值立即写进 L1 与 L2，让紧跟其后的读直接命中新值。

延迟双删（scheduleStaleClear，:991-999）：1 秒后再删一次。防的是「读请求在写穿前一刻拿旧值回填缓存」的竞态。竞态存在是因为读路径可能把查到的旧 URL 写回缓存（读路径的回填见 `ShortLinkService.java:675-680`），写穿发生在回填之前的话，旧值就会盖住新值存活到 TTL。1 秒后再删一次兜住它。删除方法 `evictCache`（:979-989）对 L1 无条件删、对 L2 失败仅打点，注释 :984-985 明确「漏删的过期条目会被 TTL 自然淘汰，绝不能让删除失败毁掉业务写」。

广播（publishL1Invalidate）：告诉同进程之外的所有实例「这个码的 L1 作废」。`CacheBus` 用 Redis pub/sub 双频道（bloom put 与 l1 invalidate），载荷是 `code|实例ID`。本进程订阅时会跳过自己发的失效（防自伤），Bloom 频道则刻意不跳（put 幂等，重复播种无害，漏播才有害）。注释 :517-518 强调广播只在写方法本体发一次，延迟双删的那一遍绝不发，否则每写一次广播两次，订阅端做两倍无效功。

### 5.7 CSV 导出：BOM、转义、1 万行封顶

```java
/** CSV of every own link, capped at 10k rows; BOM keeps Excel happy with UTF-8. */
public String exportOwnCsv() {
    AuthUser user = requireUser();
    StringBuilder sb = new StringBuilder("\uFEFFcode,original_url,title,status,expire_at,created_at\n");
    int exported = 0;
    long page = 1;
    while (exported < 10000) {
        PageResponse<ShortLinkDetail> chunk = pageOwn(user, page++, 500);
        for (ShortLinkDetail link : chunk.getRecords()) {
            sb.append(csvRow(link));
            exported++;
        }
        if (chunk.getRecords().isEmpty() || chunk.getCurrent() >= chunk.getPages()) {
            break;
        }
    }
    return sb.toString();
}
```
（`src/main/java/com/linkforge/link/service/ShortLinkService.java:304-320`）

三个实现要点。表头前的 `\uFEFF` 是 UTF-8 BOM，Excel 看见它才按 UTF-8 解码中文 title。分页复用 `pageOwn`（每页 500），与列表接口同一套视野与过滤，导出的永远是「我能看见的」。10000 行封顶（:309）是内存保护，`StringBuilder` 全量拼完才返回。每行经 `csvRow`（:322-330）逐字段调 `CsvFormat.escape`（`CsvFormat.java:9-17`）：含逗号、引号、换行任一字符的值加引号包裹，内部引号翻倍，这是 RFC4180 的最小转义集。

### 5.8 CSV 导入：一个限流桶、两个上限、一条复用纪律

```java
public ImportResponse importCsv(MultipartFile file) {
    AuthUser user = requireUser();
    LinkForgeProperties.RateLimit rl = properties.getRateLimit();
    RateLimitDecision decision = rateLimiter.acquire(
            RateLimitKeys.sw(rl.getKeyPrefix(), "import:user:" + user.getUserId()),
            rl.getImportMax(), rl.getImportWindowSeconds());
    if (!decision.allowed()) {
        meterRegistry.counter("linkforge.ratelimit.rejected", "entry", "import").increment();
        throw new RateLimitedException(decision.retryAfterSeconds());
    }
    if (file == null || file.isEmpty()) {
        throw new BizException("file is required");
    }
    if (file.getSize() > MAX_IMPORT_BYTES) {
        throw new BizException("file must be at most 256 KiB");
    }
```
（`src/main/java/com/linkforge/link/service/ShortLinkService.java:339-354`）

方法 javadoc（:332-338）概括了全部设计：专属 `import:user` 限流桶（默认 5 次/60 秒，`application.yml:196-199`），绝不占 create 桶；结构性拒绝先于任何写；然后每条记录独立走 doCreate。三个数字各有出处：256 KiB 是 `MAX_IMPORT_BYTES`（:96），100 行是 `MAX_IMPORT_ROWS`（:97），都写在常量区并带决策编号 D-C3-25/16。

行循环（:375-385）是部分成功语义的现场：

```java
for (CsvImportParser.CsvRow row : parsed.rows()) {
    if (row.cells().stream().allMatch(c -> c == null || c.isBlank())) {
        continue;
    }
    try {
        CreateShortLinkResponse created = doCreate(importRowRequest(row, columns), user, null);
        successes.add(new ImportResponse.RowSuccess(row.fileLine(), created.getCode()));
    } catch (BizException | DataIntegrityViolationException ex) {
        failures.add(new ImportResponse.RowFailure(row.fileLine(), rowReason(ex)));
    }
}
return new ImportResponse(successes.size(), failures.size(), successes, failures);
```
（`src/main/java/com/linkforge/link/service/ShortLinkService.java:375-386`）

一条坏行只产生一个 `RowFailure` 条目，邻居行照常落库。`rowReason`（:439-444）把数据库约束异常翻译成固定短句「row violates a database constraint」，防止 Spring 原生约束消息把 SQL 细节漏给客户端。

**防漂移**是导入设计的核心词。`importRowRequest`（:392-417）把 CSV 行映射成 `CreateShortLinkRequest`，然后**逐行调用 doCreate**，而不是重写一套校验。javadoc :336-337 写明理由：Bloom 播种、广播、缓存预热全部来自 doCreate 的提交后尾部，若导入另写一套，两条入口的行为迟早漂移。坏处也直白：单行失败无法回滚别的行（本来也不打算回滚），且导入不支持幂等键，Controller 注释（`ShortLinkController.java:63-64`）写明这是刻意的，响应本身就是逐行报告。

**密码列永不回显**有两处防线。`importRowRequest` 把 `access_password` 列读进请求（:402）交给 doCreate 正常加密。成功行只回 `row` 与 `code` 两个字段（`ImportResponse.java:13-14`），失败行的 reason 是固定短句，javadoc（:389-391）写明「Values are never logged (the access_password column especially, D-C3-25)」。单元格内容在任何路径上都不会回到客户端或日志。

### 5.9 CsvImportParser：为行号而生的状态机

导入响应里的 `row.fileLine()` 是 1 起算的**文件行号**（表头是 1，第一条数据行是 2），用户拿它对着 Excel 排错。手写逐字符状态机（`CsvImportParser.java:27-101`）而不用 OpenCSV，progress 报告钉了理由：要精确追踪「记录起始行号」与「引号起始行号」，通用库给不了。状态机支持引号内逗号换行、双引号转义、CRLF/LF/孤立 CR 三种行尾、一个前导 BOM（:28）。结尾再补一个合成换行（:30-31）让无尾换行的最后一行也走同一收尾路径。引号未闭合抛 400（:97-99），在任何写之前失败。

## 6. 技术原理补充

**写穿（write-through）**。是什么：写数据库的同时把新值写进缓存。为什么需要：读多写少的数据，写后立刻有读的概率高，预热省一次未命中。不用会怎样：每次写后第一个读都打库。缺点：若存在并发回填竞态，写穿值可能被旧值覆盖，所以必须有下一招。

**延迟双删**。是什么：写后延迟一小段时间再删一次缓存。为什么需要：读回填竞态的时间窗极小但存在，1 秒后的第二次删除清扫掉竞态残留。不用会怎样：旧 URL 存活到 TTL（最长 1 小时加抖动），改了链接的用户以为生效了，跳转还在去旧地址。1 秒窗口是最终一致性窗口，代码注释明写「~1s」（:514）。

**pub/sub 广播失效**。是什么：写操作通过 Redis 频道通知所有实例删本地缓存。为什么需要：L1 是进程内的，多实例部署下别的进程看不见本进程的写。不用会怎样：用户在实例 A 改链，跳转打到实例 B，B 的 L1 还在用旧值直到 TTL。

**部分成功语义**。是什么：批量操作逐项独立提交，失败项进入报告而不是拖垮全批。为什么需要：100 行的导入第 87 行 URL 非法，前 86 行已合法，全部回滚等于惩罚正确的行。不用会怎样：要么整批成功要么整批失败，用户为一条错行重传整个文件，且重传的行全部重建（无幂等键）。代价：客户端必须解析逐行报告，这正是 `ImportResponse` 存在的原因。

## 7. 可视化

1. [02-function-map](../../diagrams/archify/html/02-function-map.html)：看 link 包服务层的方法清单，确认 update/setStatus/delete 三处收尾序列是同一组函数调用。
2. [05-database-erd](../../diagrams/archify/html/05-database-erd.html)：delete 的事务双删落在 `lf_short_link` 与 `lf_link_variant` 两张表上，注意 variant 表没有 deleted 列。
3. [03-package-map](../../diagrams/archify/html/03-package-map.html)：CsvFormat 与 CsvImportParser 是 support 包里无依赖的纯函数，与 Service 的关系只是被调用。

## 8. 项目中的真实执行过程

走一遍「改一条 A/B 链的目标 URL 并停用它」的组合操作，看竞态窗口如何被收尾序列关掉。

1. PATCH `{"originalUrl": "https://new.example.com"}` 到达。update 收集意图，校验合并结果，取行，`peek` 捕获此刻可能存在的飞行读 F1。
2. `updateById` 落库，variants 为 null 不触碰。`forget(code, F1)` 清掉 F1。
3. `cacheUrl` 把新 URL 写进 L1 与 L2。
4. 与此同时一个跳转请求正在读旧数据：它在步骤 2 之前起飞，singleflight 让它读到的还是旧行，并且它可能在步骤 3 之前把旧 URL 回填进缓存。这枚竞态炸弹在第 5 步拆除。
5. 1 秒后 `scheduleStaleClear` 的定时任务执行 `evictCache`，把步骤 4 可能回填的旧值删掉。一致性窗口关闭。
6. `publishL1Invalidate` 广播，其他实例的 L1 同步作废。
7. 用户接着 POST `/disable`。setStatus 走同一序列，这次用 `evictCache` 直接删而不是写穿，因为停用后不该有任何可命中的缓存。
8. 数据库视角：`lf_short_link` 该行 status=0，deleted 仍为 0；跳转返回 404，detail 仍可见，物料导出仍含此行。

## 9. 为什么这样设计

**收尾序列出现在写路径本体**（注释 :517-518 的 D-C2-16）：一致性动作与业务写在同一个方法里肉眼可见，比藏在事务拦截器或事件监听里更好审计。代价是三处重复，收益是任何人改写路径时躲不开这段代码。

**delete 用事务双删而 update 的 variants 替换单独开小事务**（:506-511）：update 主行的 updateById 不放进 variants 事务，因为两者没有原子性依赖（主行失败时不会走到 variants 块）。delete 则必须双删同事务，否则会出现「主行删了 variants 还在」的孤儿。注释 :544-545 直接陈述「they must not outlive the link」。

**导出复用 pageOwn 而不是写全量查询**：视野规则（按 createdBy 或 tenantId 过滤）只定义一次。导出与列表口径不一致是数据类产品的经典事故，复用查询方法从结构上杜绝它（推断，依据：代码形状与项目「防漂移」的一贯注释风格）。

**导入逐行走 doCreate**（javadoc :332-338 的 D-C3-19/20/21）：复用即防漂移。校验规则、Bloom 尾部、缓存预热、计数埋点全部只有一份实现。

## 10. 本节总结

- 六个生命周期方法共享一条收尾纪律：peek 在提交前、forget 在提交后、缓存三件套（写穿或删除、延迟双删、广播一次）。
- update 的三个坑：URL 是合并后再验证、清密码必须显式 SET NULL、variants 是整组替换。
- delete 是事务内双删，逻辑删保证短码永久保留，selectAllCodes 刻意绕过 @TableLogic 让已删码保持 Bloom 阳性。
- CSV 导出走专属限流桶，256 KiB 与 100 行双上限，逐行 doCreate 防漂移，部分成功以逐行报告返回。
- 密码列在导入路径上有三重保护：只进请求对象、成功行只回码、失败行只回固定短句。

## 11. 课后练习

1. 【概念理解】为什么 forget 要带 `priorFlight` 参数？无参 forget 会怎样？
   答案：`forget(code, priorFlight)` 只移除与 snapshot 相同的那个飞行（`SingleFlight` peek/forget 契约，调用点 :495/:512、:535/:537、:548/:554）。无参 forget 会无差别清空该码的飞行记录，包括提交后才起飞、读到新行的飞行，那些请求被误杀后重查一次库。纪律是「提交前 snapshot，提交后 forget」。
2. 【源码定位】找到绕过 `@TableLogic` 的那条 SQL，并解释跳过已删行播种反而错在哪里。
   答案：`ShortLinkMapper.java:22-23` 的 `SELECT code FROM lf_short_link`（javadoc :13-21）。若排除已删行，Bloom 对已删码给阴性，请求在 Bloom 门直接 404；Bloom 开关一关，这些码改走数据库路径。同一码的响应路径取决于一个部署开关，正是 V4 注释批判的反模式。
3. 【执行过程】PATCH 请求只发 `{"utmSource": "spring"}`，update 内部如何得到被验证的 URL？存量 originalUrl 是 `https://shop.example.com/promo?utm_source=winter#top`。
   答案：hasUrl 为 false 但 utm 非空，进入 :476-481 分支，从存量 URL 出发 `mergeUtm`，canonical 映射后待合并键是 `utm_source=spring`。UtmMerger 不覆盖已有同名参数（`UtmMerger.java:61`），查询串里已有 `utm_source=winter`，所以什么都不追加，`appended` 为 false，返回 byte-identical 原串，fragment `#top` 原样保留。合并结果过 requireHttpUrl 与门禁后原值写回。
4. 【调用链】CSV 里第 10 行 `access_password` 列填了 `leak123`，该行 URL 合法创建成功。响应与日志里能找到 `leak123` 吗？
   答案：找不到。成功行是 `RowSuccess(row, code)` 两个字段（`ImportResponse.java:13-14`）；密码进 `importRowRequest`（:402）后经 `encodeAccessPassword` 变 BCrypt 哈希入库；javadoc :389-391 明示值从不进日志。
5. 【设计分析】为什么导入的限流键是 `import:user` 而不是复用 `create:user`？
   答案：一次导入最多含 100 行、每行一次完整创建。若共用 create 桶（30/60s），一次导入即占满配额，正常用户随后无法手动创建。专属桶默认 5 次/60 秒（`application.yml:196-199`）按「一次导入」计量，Controller 注释（:63-64）与 javadoc :334-335 都点明 D-C3-22 这个决策。

## 12. 与后续课程的联系

- 模块 7 将从跳转视角消费本课埋下的状态：status=0 是 404、expireAt 过期是 410、密码非空进密码门。
- 模块 8 深讲 `SingleFlight` 与 `UrlLocalCache` 的内部结构，本课的 peek/forget 纪律在那边有完整实现与残留窗口注释。
- 模块 11 的统计端点复用 `getOwnedLink` 做归属检查，本课 5.1 的视野规则在那里再次生效。
- 模块 9 将展开滑动窗口限流器，`import:user` 桶的 Lua 实现与 Retry-After 语义在那边落地。
