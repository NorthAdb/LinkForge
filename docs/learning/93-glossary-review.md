# 术语表与复习清单

面试前 48 小时的最后一遍材料 + 日常复习节奏的载体。术语按域分组，每个术语「一句话定义 + 项目锚点」；答不出的术语回对应文档。

## Java 语言基础（[java/ 系列](10-java-basics.md)）

| 术语 | 一句话 | 锚点 |
|------|--------|------|
| 引用/值语义 | 基本类型变量装值，引用变量装指向对象的线索 | 10-01 |
| 值传递 | 参数永远是实参的拷贝；引用类型拷的是引用本身 | 10-01 |
| 多态/动态绑定 | 编译按声明类型查方法，运行按实际对象分派实现 | 10-02 |
| equals/hashCode 契约 | equals 相等则 hashCode 必须相等；违反则 HashMap 算错 | 10-03 |
| 字符串池 | 字面量去重共享；成立前提是 String 不可变 | 10-03 |
| 自动装箱/拆箱 | 编译器插 valueOf/intValue；null 拆箱即 NPE | 10-04 |
| 缓存区间 | -128..127 装箱同对象是 JLS 保证，区间外是实现自由 | 10-04 |
| 类型擦除 | 泛型编译期检查后从字节码移除；List<String> 与 List<Integer> 运行时同类 | 10-05 |
| PECS | 生产者 extends 只读，消费者 super 只写 | 10-05 |
| checked/unchecked | 编译器是否强制处理；分界线是 RuntimeException 子类与否 | 10-06 |
| 注解 | 元数据标签，本身不执行；SOURCE 编译器读 / RUNTIME 反射读 | 10-07 |
| 反射 | 运行时读类结构、调方法、绕 private 的 API | 10-07 |
| 动态代理 | 运行时生成实现接口的替身，调用拦截进 InvocationHandler | 10-07 |
| SAM/函数式接口 | 恰好一个抽象方法的接口，Lambda 是它的实例 | 10-08 |
| Stream 惰性 | 中间操作只登记，终结操作触发逐元素流动 | 10-08 |
| Optional | 把"可能没有"写进返回类型；不做字段和参数 | 10-08 |
| 枚举 | 实例个数固定的类，可带字段方法，类加载时创建 | 10-09 |
| record | 不可变数据载体：全参构造器 + x() 访问器 + 自动 equals/hashCode | 10-10 |
| sealed | 子类型封闭 + 编译期穷尽检查；不写 permits 时同编译单元或嵌套自动许可 | 10-10 |
| 预览特性 | --enable-preview 才能用的未转正语法；switch 模式匹配 21 才正式 | 10-10 |

## 缓存与一致性（[50-redis.md](50-redis.md)）

| 术语 | 一句话 | 项目锚点 |
|------|--------|----------|
| Cache-Aside / 写穿 | 读回源回填 / 写时同写缓存 | `cacheUrl` 写穿 |
| 延迟双删 | 写后 1s 再删，清并发读的脏回填 | `scheduleStaleClear` |
| 空值缓存 | DB 确认不存在也缓存短 TTL | `linkforge:absent:{code}` 60s |
| 穿透/击穿/雪崩 | 不存在键 / 热键过期 / 批量过期 | Bloom+absent / singleflight / TTL 抖动 |
| TTL 抖动 | base + uniform[0,jitter] 只向上 | `jitteredTtlSeconds` |
| Bloom 假阳性/假阴性 | 说有可能错（无害）/ 说无必须准（致命） | fpp=1e-4；多实例假阴性是边界 |
| singleflight | 同 key 并发合并为一次执行 | `SingleFlight`（去重器非缓存） |

## 消息与可靠性（[51-rabbitmq-outbox.md](51-rabbitmq-outbox.md)）

| 术语 | 一句话 | 项目锚点 |
|------|--------|----------|
| at-least-once | 至少送达一次，可能重复 | 重复靠消费幂等吸收 |
| 幂等 | N 次执行效果等于 1 次 | `lf_consumed_event` 主键 |
| Outbox | 业务与事件同事务落库+独立投递 | `ClickRecordService`+Relay |
| publisher confirm / mandatory | broker ack 才算成 / 路由不到退回 | 同步等 ack 5s 超时 |
| 死信 DLX/DLQ | 失败不回队热循环 | `linkforge.click.dlq` |
| 指数退避 | 2^n 封顶重试 | min(60, 2^n)；DB 时钟计算 |
| 对账 | 周期比对明细与聚合 | `ClickLogReconciler`（sync 开时） |
| exactly-once | 端到端恰好一次不可证 | at-least-once+幂等=效果等价 |

## 安全（[32-spring-security.md](32-spring-security.md)）

| 术语 | 一句话 | 项目锚点 |
|------|--------|----------|
| BCrypt/cost/salt | 慢哈希/调慢度/盐内嵌防彩虹表 | `BCryptPasswordEncoder` |
| HMAC | 带密钥的哈希=签名 | webhook `ts+"."+body` |
| AES-GCM/nonce | AEAD 加密+完整一体 / nonce 必须唯一 | `v1:`+Base64(nonce‖ct‖tag) |
| 常量时间比较 | 防耗时差逐字节猜 | `MessageDigest.isEqual` |
| SSRF/DNS rebinding | 诱导服务器打内网 / 解析后连接前换 IP | `WebhookTargetGuard`（rebinding 未防） |
| 水平越权 | 访问同级他人数据 | 归属条件+404 |
| pwdVer | 密码哈希指纹绑进签名 | 改密即作废全部解锁 cookie |
| fail-open/closed | 故障放行/拒绝 | 限流 open vs 指标 closed |

## 限流与观测（[50-redis.md](50-redis.md)、[61-docker-deploy.md](61-docker-deploy.md)）

| 术语 | 一句话 | 项目锚点 |
|------|--------|----------|
| 滑窗日志/固定窗/令牌桶/漏桶 | 精确次数/日历窗/速率+突发/整流 | 滑窗五入口+日配额固定窗 |
| Retry-After | 窗口内最老条目出窗时间 | Lua 计算，钳制 ≤ 窗口 |
| Counter/Gauge/Histogram | 只增/瞬时/分布 | redirects / outbox.pending / SLO 桶 |
| 指标基数 | tag 组合=时间序列，必须有界 | URI 白名单归一 |
| 预采样 gauge | 不在抓取线程查库 | `BacklogGauges` 15s AtomicLong |

## 测试与工程（[60-testing-ci.md](60-testing-ci.md)、[22-tools.md](22-tools.md)）

| 术语 | 一句话 | 项目锚点 |
|------|--------|----------|
| Testcontainers | 测试里起真容器不 mock 依赖 | 三容器静态单例 |
| 上下文缓存 | 同配置复用，异配置新池 | 连接预算 256<500 |
| 红先 | 先提交会红的测试再修绿 | progress.md 红史 |
| 逻辑删除 | DELETE→UPDATE deleted=1 | 码位永久不可复用 |
| Flyway baseline | 存量库打基线跳过 V1 | `baseline-on-migrate` |
| MVCC | 版本链快照读 | RR 可重复读 |

## 分布式（[70-distributed.md](70-distributed.md)）

| 术语 | 一句话 | 项目锚点 |
|------|--------|----------|
| CAP | 分区时选一致或可用 | 限流 fail-open=选 A |
| 最终一致 | 一致有窗口且可观测 | 统计秒级窗口+gauge |
| Saga | 本地事务序列+补偿 | webhook 重试/FAILED |
| Idempotency-Key | 客户端幂等键的 HTTP 化 | 创建接口 |
| 模块化单体 | 单进程+强制模块边界 | 包域+依赖方向 |

## 场景速答卡（一分钟题）

面试里的「如果…怎么办」场景题，先给结论再给两步动作：

- **缓存突然全挂？** 限流 fail-open 照常服务，行读兜底语义正确；看 `ratelimit.failopen` 指标确认，恢复后 absent/正常缓存自动回填。
- **MQ 断了用户会感知吗？** 不会——302 照常，点击停在 outbox；恢复后 relay 追平（drill 实证过）。
- **某接口突然变慢？** 先看指标定层（DB？外部调用？GC？），再 EXPLAIN/查日志；报出「先测量后动刀」。
- **出现重复扣款类投诉怎么排查？** 幂等键覆盖了吗→去重表有唯一约束吗→重试策略有退避吗；对照本项目的 eventId 设计答。
- **线上 OOM？** 堆转储参数平时就要配；MAT 看支配树；本项目静态集合/ThreadLocal 是前两个嫌疑。
- **被问到不会的？** 「没做过，我的理解是 X，依据是 Y；不确定 Z」——比编造强一百倍。

## 复习节奏（间隔重复）

每学完一个文档/一批题，登记到下表（自己维护行）：

| 登记日 | 内容 | D+1 | D+3 | D+7 | D+21 |
|--------|------|-----|-----|-----|------|
| （示例）10-28 | [51-rabbitmq-outbox.md](51-rabbitmq-outbox.md) 三张表 | 口述一遍 | 默画时序 | 自测 5 题 | 面试口径复述 |
| | | | | | |

执行规则：每格完成后打勾；D+3 仍未过的项进「薄弱清单」，面试冲刺期（w16+）每日优先清薄弱清单；算法错题同样按此节奏（[14-algorithms.md](14-algorithms.md)）。

## 面试前 48 小时清单

1. 本档术语表全量过一遍，答不出的回文档只读「项目锚点」列。
2. [91-interview-project.md](91-interview-project.md) 的 30 秒定位语与 5 分钟演示脚本各演练 2 遍。
3. 数字白名单默写（[82-linkforge-decisions.md](82-linkforge-decisions.md)）：能说的 6 项 + 不能说的清单。
4. [90-interview-knowledge.md](90-interview-knowledge.md) 薄弱域的「必答点」列重读。
5. [92-interview-coding.md](92-interview-coding.md) 错题卡各重写一遍。
6. 睡前：把跳转十二站判定门在白纸上默画一次——它是你项目的名片。

## 自测

1. 随机抽本档 10 个术语，口述「定义+锚点」双要素，低于 8 个回炉对应域。
2. 用本档检验 [02-capability-matrix.md](02-capability-matrix.md)：术语答不出对应知识点的状态不应是「已掌握」。
