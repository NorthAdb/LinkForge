# 类覆盖矩阵：120 个主源码类 → 教材去向

> 口径：2026-11-03 以 `find src/main/java -name '*.java'` 实测 120 个类为准，与五份调查报告（`D:\LinkForge\.audit\inventory\` 下 link.md、security-user-apikey.md、stats-webhook.md、common-config.md、schema-tests.md）逐类核对后填写。基线 911640c（docs 侧 01f0b5a）。
> 结论先行：120 类全部有明确去向，**无任何去向的类为 0**（见文末统计）。

## 怎么读这张表

- **类名**为全路径（`com.linkforge.` 前缀逐行写全，方便 Ctrl+F 定位）。
- **教材去向**精确到课号（如 `6.2` = `modules/06-short-link/02-create-flow.md`），`advanced/N` 指 `docs/source-learning/advanced/` 的综合专题。主去向写深讲或合并讲所在课；括号内是复用该类的其他课，供交叉查阅。
- **讲解方式**四档定义：
  - **独立深讲**：该类在对应课里有专属于它的成段精讲（类声明、字段、核心方法逐段拆）。
  - **合并讲**：与同族类一起讲，不单独成段。
  - **带过**：在正文里点到为止，只交代它是什么、被谁调。
  - **索引**：正文不讲，仅出现在本表与代码导航索引中。
- 深讲/合并讲的判定依据：五份调查报告「教材建议」列优先（link.md 与 security-user-apikey.md 给了显式档位）；stats-webhook.md 与 common-config.md 只给课号，这两包的档位由课内主角地位判定，属整理者判断，已逐行核过课表。
- 配图引用格式：`<NN>-<slug>`，对应 `docs/source-learning/diagrams/archify/html/<NN>-<slug>.html`。

---

## com.linkforge（根包，1 类）

| 类名 | 所属包 | 一句话职责 | 教材去向 | 讲解方式 |
|---|---|---|---|---|
| com.linkforge.LinkForgeApplication | 根包 | 启动类，@SpringBootApplication 组件扫描起点 | 1.2 深讲 | 独立深讲 |

## com.linkforge.apikey（5 类）

| 类名 | 所属包 | 一句话职责 | 教材去向 | 讲解方式 |
|---|---|---|---|---|
| com.linkforge.apikey.controller.ApiKeyController | apikey.controller | /api/keys 创建/列表/撤销入口（仅 JWT 用户） | 12.1 合并讲（5.1 角色矩阵带过） | 合并讲 |
| com.linkforge.apikey.service.ApiKeyService | apikey.service | 造钥只存 SHA-256、幂等撤销、authenticate 哈希点查、scope 校验 | 12.1 深讲（advanced/10） | 独立深讲 |
| com.linkforge.apikey.entity.ApiKey | apikey.entity | lf_api_key 行：scope/keyPrefix/keyHash/双配额/revokedAt | 12.1 合并讲（4.1 实体盘点） | 合并讲 |
| com.linkforge.apikey.mapper.ApiKeyMapper | apikey.mapper | BaseMapper + touchLastUsed UPDATE | 12.1 合并讲 | 合并讲 |
| com.linkforge.apikey.dto.ApiKeyDtos | apikey.dto | CreateRequest/Created/View 三件套，明文只在 Created | 12.1 合并讲 | 合并讲 |

## com.linkforge.bootstrap（1 类）

| 类名 | 所属包 | 一句话职责 | 教材去向 | 讲解方式 |
|---|---|---|---|---|
| com.linkforge.bootstrap.BootstrapDataLoader | bootstrap | ApplicationRunner，启动建 demo 租户与 admin | 1.2 合并讲 | 合并讲 |

## com.linkforge.common（9 类）

| 类名 | 所属包 | 一句话职责 | 教材去向 | 讲解方式 |
|---|---|---|---|---|
| com.linkforge.common.result.ApiResponse | common.result | 统一响应壳 {code,message,data}，code=0 为成功 | 2.1 合并讲 | 合并讲 |
| com.linkforge.common.result.PageResponse | common.result | 分页响应壳 {records,total,current,size,pages} | 2.1 合并讲 | 合并讲 |
| com.linkforge.common.exception.BizException | common.exception | 业务异常基类，携带业务码+HTTP 码 | 2.1 合并讲 | 合并讲 |
| com.linkforge.common.exception.RateLimitedException | common.exception | BizException 子类，固定 429 + retryAfterSeconds | 2.1 合并讲（9.1 复讲） | 合并讲 |
| com.linkforge.common.exception.GlobalExceptionHandler | common.exception | @RestControllerAdvice，七类异常到 HTTP 全映射 | 2.1 深讲 | 独立深讲 |
| com.linkforge.common.ratelimit.RateLimitDecision | common.ratelimit | record (allowed, retryAfterSeconds, count) | 9.1 合并讲 | 合并讲 |
| com.linkforge.common.ratelimit.RateLimitKeys | common.ratelimit | 限流键命名空间 {prefix}sw:{logical} | 9.1 合并讲 | 合并讲 |
| com.linkforge.common.ratelimit.RedisRateLimiter | common.ratelimit | 滑动窗口日志(Lua)+固定窗口+fail-open 打点 | 9.1 深讲（advanced/12 故障语义） | 独立深讲 |
| com.linkforge.common.util.ClientIps | common.util | trustXff 门控下的客户端 IP 解析 | 2.2 深讲 | 独立深讲 |

## com.linkforge.config（6 类）

| 类名 | 所属包 | 一句话职责 | 教材去向 | 讲解方式 |
|---|---|---|---|---|
| com.linkforge.config.LinkForgeProperties | config | linkforge.* 全部业务配置（14 个内部类） | 1.3 深讲 | 独立深讲 |
| com.linkforge.config.RedisConfig | config | StringRedisTemplate + RedisMessageListenerContainer 两 Bean | 8.1 合并讲（1.3 带过） | 合并讲 |
| com.linkforge.config.MybatisPlusConfig | config | MybatisPlusInterceptor + MySQL 分页插件 | 4.1 合并讲 | 合并讲 |
| com.linkforge.config.OpenApiConfig | config | springdoc info + bearer/X-API-Key 双安全方案 | 15.2 合并讲 | 合并讲 |
| com.linkforge.config.RabbitMqConfig | config | Exchange/Queue/DLX/binding/Jackson converter 六 Bean | 10.1 深讲 | 独立深讲 |
| com.linkforge.config.SchedulingConfig | config | 仅 @EnableScheduling（容量在 yml） | 14.3 带过 | 带过 |

## com.linkforge.link（37 类）

### link.controller（4 类）

| 类名 | 所属包 | 一句话职责 | 教材去向 | 讲解方式 |
|---|---|---|---|---|
| com.linkforge.link.controller.ShortLinkController | link.controller | /api/links CRUD+CSV+QR 入口 | 3.1 合并讲（6.2/6.3 入口复现） | 合并讲 |
| com.linkforge.link.controller.RedirectController | link.controller | /r/{code} GET/HEAD/unlock，渲染 302/403/200 HTML | 7.1 深讲（3.1 盘点；advanced/04） | 独立深讲 |
| com.linkforge.link.controller.HealthController | link.controller | GET /api/health 固定 UP | 3.1 带过 | 带过 |
| com.linkforge.link.controller.BloomAdminController | link.controller | POST /api/admin/bloom/rebuild（ADMIN 校验） | 8.3 合并讲（5.3 ADMIN 判定带过） | 合并讲 |

### link.dto（8 类）

| 类名 | 所属包 | 一句话职责 | 教材去向 | 讲解方式 |
|---|---|---|---|---|
| com.linkforge.link.dto.CreateShortLinkRequest | link.dto | 创建入参，@JsonAnySetter 捕获未知属性防夹带 | 6.1 合并讲（6.2 校验链复讲） | 合并讲 |
| com.linkforge.link.dto.CreateShortLinkResponse | link.dto | 创建出参（code/URL/过期等） | 6.1 合并讲 | 合并讲 |
| com.linkforge.link.dto.ShortLinkDetail | link.dto | 详情出参，stats 三端点归属校验也复用 | 6.1 合并讲（6.3/11.3 复用） | 合并讲 |
| com.linkforge.link.dto.UpdateShortLinkRequest | link.dto | 更新入参：clearAccessPassword 与 variants 整组语义 | 6.3 合并讲 | 合并讲 |
| com.linkforge.link.dto.ImportResponse | link.dto | 导入结果：成功数与逐行失败行号+原因 | 6.3 合并讲 | 合并讲 |
| com.linkforge.link.dto.VariantSpec | link.dto | variant 子结构（key/url/weight） | 6.1 合并讲（6.3/7.3 复用） | 合并讲 |
| com.linkforge.link.dto.QrShareRequest | link.dto | 分享链接入参 | 6.4 带过 | 带过 |
| com.linkforge.link.dto.QrShareResponse | link.dto | 分享链接出参 | 6.4 带过 | 带过 |

### link.entity（2 类）

| 类名 | 所属包 | 一句话职责 | 教材去向 | 讲解方式 |
|---|---|---|---|---|
| com.linkforge.link.entity.ShortLink | link.entity | lf_short_link 行：@TableLogic、BCrypt hash、code bin collation | 6.1 合并讲（4.1 逻辑删与唯一索引复讲） | 合并讲 |
| com.linkforge.link.entity.LinkVariant | link.entity | lf_link_variant 行：硬删、variantKey bin 排序 | 6.1 合并讲（7.3 分流复讲） | 合并讲 |

### link.mapper（2 类）

| 类名 | 所属包 | 一句话职责 | 教材去向 | 讲解方式 |
|---|---|---|---|---|
| com.linkforge.link.mapper.ShortLinkMapper | link.mapper | BaseMapper + selectAllCodes 故意绕过 @TableLogic（Bloom 播种） | 8.3 合并讲 | 合并讲 |
| com.linkforge.link.mapper.LinkVariantMapper | link.mapper | BaseMapper + selectByLinkId（ORDER BY id） | 7.3 带过（6.1 顺带） | 带过 |

### link.service（3 类）

| 类名 | 所属包 | 一句话职责 | 教材去向 | 讲解方式 |
|---|---|---|---|---|
| com.linkforge.link.service.ShortLinkService | link.service | 域服务核心（1025 行，17 个依赖），创建/管理/跳转/解锁全在这 | 6.2 深讲主线（6.3/7.1/8.2/8.3/8.4/9.2 分课复讲；advanced/02/03/04/05/06/12） | 独立深讲 |
| com.linkforge.link.service.RedirectOutcome | link.service | sealed interface：Redirect/Blocked/Locked/Warn | 7.1 合并讲 | 合并讲 |
| com.linkforge.link.service.QrMaterialService | link.service | QR PNG（内容恒 {base}/r/{code}）+ 签名分享 URL | 6.4 合并讲 | 合并讲 |

### link.support（18 类）

| 类名 | 所属包 | 一句话职责 | 教材去向 | 讲解方式 |
|---|---|---|---|---|
| com.linkforge.link.support.CacheBus | link.support | pub/sub 双频道广播失效，载荷 `<code>\|<origin>` | 8.2 深讲（advanced/05） | 独立深讲 |
| com.linkforge.link.support.ClickFilter | link.support | B2 识别 prefetch/预览 bot，永不抛永不拦 | 11.1 合并讲（7.1 调用点带过） | 合并讲 |
| com.linkforge.link.support.CodeBloomFilter | link.support | 进程内 Guava Bloom：播种/重建/订阅失效 | 8.3 深讲 | 独立深讲 |
| com.linkforge.link.support.CsvFormat | link.support | RFC4180 转义纯函数 | 2.2 带过（6.3 导出复用） | 带过 |
| com.linkforge.link.support.CsvImportParser | link.support | 逐字符 CSV 状态机（BOM/CR/CRLF/引号/行号） | 6.3 合并讲 | 合并讲 |
| com.linkforge.link.support.GatePages | link.support | 拦截/告警/解锁三张 HTML（CSP default-src 'none'） | 7.2 合并讲 | 合并讲 |
| com.linkforge.link.support.LinkAccessTokens | link.support | 解锁 cookie：HMAC(linkId\|code\|exp\|pwdVer) | 7.2 合并讲（advanced/06） | 合并讲 |
| com.linkforge.link.support.LinkValidations | link.support | URL/自定义码/保留字/可渲染校验 | 6.2 合并讲 | 合并讲 |
| com.linkforge.link.support.PhishingPolicy | link.support | 不可变名单快照 + mtime 30s 热载（坏文件保旧） | 7.2 合并讲 | 合并讲 |
| com.linkforge.link.support.QrParams | link.support | qr.png 参数解析（默认 256/1/M） | 6.4 带过 | 带过 |
| com.linkforge.link.support.ShareTokenSigner | link.support | HMAC-SHA256(code\|exp)，缺省密钥由 JWT secret 派生 | 6.4 带过（5.1 过滤器链顺带） | 带过 |
| com.linkforge.link.support.ShortCodes | link.support | 62 字符 SecureRandom 随机码 | 6.2 带过 | 带过 |
| com.linkforge.link.support.ShortLinkRowLoader | link.support | code→行查询适配 bean（singleflight 的取数口） | 8.4 合并讲 | 合并讲 |
| com.linkforge.link.support.SingleFlight | link.support | 同码并发合并：Leader/Follower/超时跟车/forget | 8.4 深讲（advanced/05） | 独立深讲 |
| com.linkforge.link.support.TargetPolicy | link.support | B9 纯判定：标签边界后缀+IDN+BLOCK>WARN+userinfo | 7.2 合并讲 | 合并讲 |
| com.linkforge.link.support.UrlLocalCache | link.support | Caffeine L1（仅 URL 字符串）+ 订阅失效 | 8.2 深讲（advanced/05） | 独立深讲 |
| com.linkforge.link.support.UtmMerger | link.support | 写时 UTM 合并：不覆盖、fragment 保留、超 2048 返 null | 6.2 合并讲（6.4 复讲） | 合并讲 |
| com.linkforge.link.support.VariantPicker | link.support | 权重抽签：threshold=r·total 半开区间 | 7.3 合并讲 | 合并讲 |

## com.linkforge.observability（2 类）

| 类名 | 所属包 | 一句话职责 | 教材去向 | 讲解方式 |
|---|---|---|---|---|
| com.linkforge.observability.BacklogGauges | observability | outbox/webhook 积压 3 个 Gauge，定时采样与抓取解耦 | 14.2 合并讲 | 合并讲 |
| com.linkforge.observability.HttpUriTagAllowList | observability | http.server.requests 的 uri tag 白名单 MeterFilter | 14.1 合并讲 | 合并讲 |

## com.linkforge.retention（1 类）

| 类名 | 所属包 | 一句话职责 | 教材去向 | 讲解方式 |
|---|---|---|---|---|
| com.linkforge.retention.SentRowCleanup | retention | 定时批量删 SENT 的 outbox/webhook 行（有界批次） | 14.3 合并讲 | 合并讲 |

## com.linkforge.security（9 类）

| 类名 | 所属包 | 一句话职责 | 教材去向 | 讲解方式 |
|---|---|---|---|---|
| com.linkforge.security.SecurityConfig | security | 装配过滤器链、路径-角色矩阵、401/403 JSON、BCrypt Bean | 5.1 深讲 | 独立深讲 |
| com.linkforge.security.JwtAuthFilter | security | 解析 Bearer JWT，置 ROLE_USER + UserContext | 5.2 深讲 | 独立深讲 |
| com.linkforge.security.JwtService | security | HMAC 签发/校验 JWT | 5.2 深讲 | 独立深讲 |
| com.linkforge.security.ApiKeyAuthFilter | security | X-API-Key 认证、双配额、touchLastUsed、ROLE_API_KEY | 12.1 深讲（9.2 配额复讲；5.1 链上定位；advanced/10） | 独立深讲 |
| com.linkforge.security.MetricsScrapeTokenFilter | security | 恒时比较守卫 /actuator/prometheus，授 ROLE_METRICS | 14.1 合并讲（5.1 链上带过） | 合并讲 |
| com.linkforge.security.QrShareAuthFilter | security | exp+sig 签名放行 GET qr.png，授 ROLE_QR_SHARE | 6.4 合并讲（5.1 链上带过） | 合并讲 |
| com.linkforge.security.QrSharePrincipal | security | 只携带 code 的 record principal | 6.4 带过 | 带过 |
| com.linkforge.security.AuthUser | security | 认证主体：userId/tenantId/username/apiKeyScope | 5.3 合并讲 | 合并讲 |
| com.linkforge.security.UserContext | security | 静态 ThreadLocal<AuthUser> 载体，两 set 两 clear | 5.3 深讲 | 独立深讲 |

## com.linkforge.stats（25 类）

### stats.controller / stats.dto（6 类）

| 类名 | 所属包 | 一句话职责 | 教材去向 | 讲解方式 |
|---|---|---|---|---|
| com.linkforge.stats.controller.StatsController | stats.controller | stats/breakdown/clicks 三端点 + dim 白名单 | 11.3 深讲（advanced/09） | 独立深讲 |
| com.linkforge.stats.dto.LinkStatsResponse | stats.dto | stats 出参：total/UV/按日序列（形状由端点定，推断） | 11.3 带过 | 带过 |
| com.linkforge.stats.dto.DailyClicks | stats.dto | 按日点击数出参〔推断：由 stats 端点日序列定〕 | 11.3 带过 | 带过 |
| com.linkforge.stats.dto.DailyUv | stats.dto | 按日 UV 出参〔推断：由 stats 端点日序列定〕 | 11.3 带过 | 带过 |
| com.linkforge.stats.dto.DimBreakdown | stats.dto | 维度分布出参（dim_value+clicks）〔推断：由 breakdown 端点定〕 | 11.3 带过 | 带过 |
| com.linkforge.stats.dto.ClickDetail | stats.dto | 明细分页行，只回 4 字段〔推断：由 clicks 端点定〕 | 11.3 带过 | 带过 |

### stats.entity（5 类）

| 类名 | 所属包 | 一句话职责 | 教材去向 | 讲解方式 |
|---|---|---|---|---|
| com.linkforge.stats.entity.ClickLog | stats.entity | lf_click_log 行：快照 code 列、event_id 回填标记 | 4.1 合并讲（11.1 复讲） | 合并讲 |
| com.linkforge.stats.entity.Outbox | stats.entity | lf_outbox 行：PENDING/SENT/FAILED，重放=UPDATE 回 PENDING | 4.1 合并讲（10.2 复讲） | 合并讲 |
| com.linkforge.stats.entity.ConsumedEvent | stats.entity | lf_consumed_event 行：主键即幂等锁 | 4.1 合并讲（10.3 复讲） | 合并讲 |
| com.linkforge.stats.entity.ClickDaily | stats.entity | lf_click_daily 行：uk(link_id,stat_date) 承重墙 | 4.1 合并讲（11.2 复讲） | 合并讲 |
| com.linkforge.stats.entity.ClickDimDaily | stats.entity | lf_click_dim_daily 行：四元组唯一键 | 4.1 合并讲（11.2 复讲） | 合并讲 |

### stats.mapper（5 类）

| 类名 | 所属包 | 一句话职责 | 教材去向 | 讲解方式 |
|---|---|---|---|---|
| com.linkforge.stats.mapper.ClickLogMapper | stats.mapper | 未消费扫描、UV COUNT(DISTINCT ip)、按日 UV 三段 SQL | 11.3 合并讲（11.1 UV 复用） | 合并讲 |
| com.linkforge.stats.mapper.OutboxMapper | stats.mapper | selectPendingBatch / markSent / markRetry（DB 时钟）/ markFailed / 清理 | 10.2 合并讲 | 合并讲 |
| com.linkforge.stats.mapper.ConsumedEventMapper | stats.mapper | 空壳 BaseMapper | 10.3 带过（4.1 盘点） | 带过 |
| com.linkforge.stats.mapper.ClickDailyMapper | stats.mapper | ON DUPLICATE KEY 计数 upsert + SUM | 11.2 合并讲 | 合并讲 |
| com.linkforge.stats.mapper.ClickDimDailyMapper | stats.mapper | 维度 upsert（计数 vs 查重决策注释）+ GROUP BY | 11.2 合并讲 | 合并讲 |

### stats.mq（4 类）

| 类名 | 所属包 | 一句话职责 | 教材去向 | 讲解方式 |
|---|---|---|---|---|
| com.linkforge.stats.mq.ClickEvent | stats.mq | MQ 契约 10 字段（eventId 语义注释） | 10.1 合并讲 | 合并讲 |
| com.linkforge.stats.mq.ClickEventPublisher | stats.mq | 发布抽象接口契约 | 10.1 带过 | 带过 |
| com.linkforge.stats.mq.RabbitClickEventPublisher | stats.mq | correlated confirm + mandatory returns 同步发布 | 10.1 深讲 | 独立深讲 |
| com.linkforge.stats.mq.ClickEventConsumer | stats.mq | @RabbitListener 入口，抛异常不重排进 DLQ | 10.3 合并讲（advanced/08） | 合并讲 |

### stats.service / stats.support（6 类）

| 类名 | 所属包 | 一句话职责 | 教材去向 | 讲解方式 |
|---|---|---|---|---|
| com.linkforge.stats.service.ClickRecordService | stats.service | 热路径：同事务写 lf_click_log + PENDING outbox | 11.1 深讲（advanced/07；advanced/12 fail-open） | 独立深讲 |
| com.linkforge.stats.service.ClickOutboxRelay | stats.service | 定时抽水 outbox→MQ：批量/退避/FAILED/指标 | 10.2 深讲（advanced/07） | 独立深讲 |
| com.linkforge.stats.service.ClickStatsService | stats.service | 幂等消费 consume + 三类统计查询（手工 TransactionTemplate） | 10.3 深讲（11.2/11.3 查询复讲；advanced/08/09） | 独立深讲 |
| com.linkforge.stats.service.ClickLogReconciler | stats.service | 双写全开时重放未消费明细（30s 定时） | 11.3 合并讲（advanced/09） | 合并讲 |
| com.linkforge.stats.support.ClickDimensions | stats.support | 纯字符串 UA/Referer 分桶，绝不抛 | 11.2 合并讲（13.2 复用） | 合并讲 |

## com.linkforge.user（9 类）

| 类名 | 所属包 | 一句话职责 | 教材去向 | 讲解方式 |
|---|---|---|---|---|
| com.linkforge.user.controller.AuthController | user.controller | /api/auth/register、/login 入口 | 5.2 合并讲（advanced/01） | 合并讲 |
| com.linkforge.user.service.AuthService | user.service | 注册（事务建租户+首用户 ADMIN+限流）与登录 | 5.2 深讲（advanced/01） | 独立深讲 |
| com.linkforge.user.dto.RegisterRequest | user.dto | 注册入参（用户名 3-64、密码 8-64 正则） | 5.2 带过 | 带过 |
| com.linkforge.user.dto.LoginRequest | user.dto | 登录入参 | 5.2 带过 | 带过 |
| com.linkforge.user.dto.LoginResponse | user.dto | 登录出参（token） | 5.2 带过 | 带过 |
| com.linkforge.user.entity.Tenant | user.entity | lf_tenant 行（@TableLogic、uk_tenant_name） | 4.1 合并讲（5.2 复用） | 合并讲 |
| com.linkforge.user.entity.UserAccount | user.entity | lf_user 行（tenant_role MEMBER/ADMIN） | 4.1 合并讲（5.2/5.3 复用） | 合并讲 |
| com.linkforge.user.mapper.TenantMapper | user.mapper | BaseMapper | 4.1 带过 | 带过 |
| com.linkforge.user.mapper.UserAccountMapper | user.mapper | BaseMapper | 4.1 带过 | 带过 |

## com.linkforge.webhook（15 类）

### webhook.controller / webhook.entity（4 类）

| 类名 | 所属包 | 一句话职责 | 教材去向 | 讲解方式 |
|---|---|---|---|---|
| com.linkforge.webhook.controller.WebhookController | webhook.controller | /api/webhooks endpoint CRUD（每租户一个） | 13.1 合并讲 | 合并讲 |
| com.linkforge.webhook.entity.WebhookEndpoint | webhook.entity | lf_webhook_endpoint 行：AES 密文 secret、delivery_mode | 4.1 合并讲（13.1 复讲） | 合并讲 |
| com.linkforge.webhook.entity.WebhookDelivery | webhook.entity | lf_webhook_delivery 行：PENDING/SENT/FAILED 重试状态机 | 4.1 合并讲（13.2 复讲） | 合并讲 |
| com.linkforge.webhook.entity.WebhookAggregateBuffer | webhook.entity | lf_webhook_agg_buffer 行：窗口桶（sample 保留首击） | 4.1 合并讲（13.2 复讲） | 合并讲 |

### webhook.mapper（3 类）

| 类名 | 所属包 | 一句话职责 | 教材去向 | 讲解方式 |
|---|---|---|---|---|
| com.linkforge.webhook.mapper.WebhookEndpointMapper | webhook.mapper | BaseMapper | 13.1 带过 | 带过 |
| com.linkforge.webhook.mapper.WebhookDeliveryMapper | webhook.mapper | 三个 mark 均带 WHERE status='PENDING' 状态守卫 | 13.2 合并讲 | 合并讲 |
| com.linkforge.webhook.mapper.WebhookAggregateBufferMapper | webhook.mapper | 窗口 upsert（只加 clicks，sample 保持首击）+ 过期窗扫描 | 13.2 合并讲 | 合并讲 |

### webhook.service（3 类）

| 类名 | 所属包 | 一句话职责 | 教材去向 | 讲解方式 |
|---|---|---|---|---|
| com.linkforge.webhook.service.WebhookService | webhook.service | endpoint 配置 + recordClickDelivery 分流（逐次/聚合）+ 入桶 | 13.1 深讲（13.2 投递分流复讲） | 独立深讲 |
| com.linkforge.webhook.service.WebhookAggregateFlusher | webhook.service | sweep 过期窗 → digest 分片投递（每片≤500 桶，≤10 片） | 13.2 深讲 | 独立深讲 |
| com.linkforge.webhook.service.WebhookDeliveryRelay | webhook.service | 定时投递 + 退避状态机 + master key 不可用整批跳过 | 13.2 深讲（advanced/11） | 独立深讲 |

### webhook.support（5 类）

| 类名 | 所属包 | 一句话职责 | 教材去向 | 讲解方式 |
|---|---|---|---|---|
| com.linkforge.webhook.support.AggregateWindows | webhook.support | epoch 秒网格 floorDiv 定窗（默认 60s） | 13.2 合并讲 | 合并讲 |
| com.linkforge.webhook.support.WebhookSigner | webhook.support | HmacSHA256(unixSeconds+"."+rawBody)，前缀 v1= | 13.2 合并讲 | 合并讲 |
| com.linkforge.webhook.support.WebhookSecretCipher | webhook.support | AES-256-GCM："v1:"+Base64(nonce‖ct‖tag) | 13.1 深讲 | 独立深讲 |
| com.linkforge.webhook.support.WebhookTargetGuard | webhook.support | 保存时 check + 发送时 resolveForSend 钉死地址 | 13.3 深讲 | 独立深讲 |
| com.linkforge.webhook.support.PinnedHttpSender | webhook.support | PinnedDnsResolver + 禁重定向的 HttpClient5 封装 | 13.3 合并讲 | 合并讲 |

---

## 统计与核对结论

### 分档计数

| 讲解方式 | 类数 | 占比 |
|---|---|---|
| 独立深讲 | 29 | 24.2% |
| 合并讲 | 68 | 56.7% |
| 带过 | 23 | 19.1% |
| 索引（未单讲-仅索引） | 0 | 0% |
| **合计** | **120** | 100% |

分档计数已与各包小表逐行相加核对：apikey 1+4、bootstrap 0+1、common 3+6、config 2+3+1、link 6+23+8、observability 0+2、retention 0+1、security 5+3+1、stats 5+13+7、user 1+3+5、webhook 5+9+1、根包 1+0，深讲/合并/带过分别为 29/68/23，与 120 吻合。

### 去向为零的类

**0 个。** 每个类至少有「带过」级去处。最小的几个类（9 行的 TenantMapper/UserAccountMapper/ConsumedEventMapper、10 行的 ClickEventPublisher 接口、9 行的 QrSharePrincipal record）也都落到了具体课的「带过」格：三个空壳 Mapper 进 4.1 的 BaseMapper 盘点或 10.3/13.1 的带过，接口与 record 在 10.1/6.4 顺带交代。因此索引档计数为 0：本表未启用「未单讲-仅索引」档，不是漏标。

### 与上游材料的一致性说明

- link.md 给出显式档位的类（RedirectController 独立深讲、HealthController 带过、ShortCodes/QrParams/CsvFormat/LinkVariantMapper 带过等）全部照录。
- security-user-apikey.md 给出显式档位的类（SecurityConfig/JwtAuthFilter/JwtService/ApiKeyAuthFilter/UserContext/AuthService 独立深讲）全部照录。
- stats-webhook.md 与 common-config.md 只给课号：stats/stats.dto 五个 DTO 未在其类清单表逐条列出，职责按 StatsController 三端点行为反推，已标〔推断〕；这两包的深讲/合并档由整理者按课内主角地位判定。
- webhook 包 15 个文件（实测）与 stats-webhook.md 开头「webhook/ 实为 14 文件」差 1，差的 1 个是 webhook/controller/WebhookController（该报告按 service/support 口径清点）。以 find 实测 15 为准，不影响任何档位结论。
