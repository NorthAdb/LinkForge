# 15.2 Maven 与 CI：surefire/failsafe 分工、时区 fork 与 GitHub Actions

> 15.1 讲了测试怎么写，这一课讲测试怎么被编排。四个主题：Maven 生命周期里两套测试插件的分工与 `pom.xml:219-249` 的 failsafe 双执行（含把 JVM 钉到 Asia/Shanghai 的时区 fork）；`build-info` goal 如何让 OpenAPI 版本以 pom 为单一事实源；一段真实的依赖仲裁案例（commons-lang3 强升与 Testcontainers 2.0.5）；以及 24 行的 GitHub Actions 工作流与它的缓存键设计。读完你应能解释「为什么本地 `-DskipITs` 不算撒谎」。

## 1. 本节学习目标

1. 画出 `mvn verify` 的时间线：surefire 在 `test` 阶段跑 `*Test`，failsafe 在 `integration-test`/`verify` 阶段跑 `*IT`，并说出 failsafe 不在中间阶段快速失败的原因。
2. 逐行读懂 failsafe 双执行：默认组排除谁、`webhook-tz-it` fork 用什么参数只跑谁。
3. 复述 WebhookRetryClockIT 类头注释里的事故：JVM(+8) 与 DB(UTC) 的偏差曾把重试拖到约 8 小时，以及 fork 如何把事故现场带进回归。
4. 解释 `build-info` goal 与 `OpenApiConfig` 的关系：pom version 生成 `build-info.properties`，OpenAPI info 读它，测试钉住它。
5. 说出 CI 工作流五步与缓存键 `runner.os-m2-<pom hash>` 的设计意图，以及「IT 真值看 CI」这条口径的由来。

## 2. 前置知识

- **Maven 生命周期（框架行为）**：`mvn verify` 依次执行 default 生命周期的各阶段。与测试相关的三段是 `test`（单测）、`integration-test`（集成测试）、`verify`（质量门）。插件把 goal 绑定到阶段上执行。
- **surefire 与 failsafe（框架行为）**：同为 Maven 官方测试插件，默认规则不同。surefire 匹配 `*Test`、`Test*`、`*Tests`、`*TestCase`；failsafe 匹配 `*IT`。failsafe 的名字是「it」的变位梗，也提醒你它的语义：在 `integration-test` 阶段不立刻失败，留到 `verify` 阶段才判死，好让后处理（如容器清理）有机会跑。
- **fork（Maven 术语）**：插件为测试另起一个 JVM 进程。`argLine` 是传给这个 JVM 的参数串，能设置系统属性、堆大小、时区等。
- **Spring Boot parent 与 BOM（框架行为）**：本项目 `pom.xml:7-12` 把 parent 设为 `spring-boot-starter-parent:3.3.5`，它带一份依赖版本表（BOM）。凡 BOM 管理的依赖，声明时不写版本号；要改版本，改 BOM 约定的属性名或在自己的 `dependencyManagement` 里覆盖。
- **GitHub Actions（框架行为）**：GitHub 的 CI 服务，工作流是 YAML 文件放在 `.github/workflows/`，由 `on:` 触发条件驱动，`jobs.build.steps` 逐步执行。

## 3. 本节涉及的源码

| 文件 | 行数 | 职责 | 阅读顺序 |
|----|----|----|----|
| `pom.xml` | 281 | 版本属性、依赖仲裁、build-info、failsafe 双执行、flyway 插件 | 1 |
| `.github/workflows/ci.yml` | 24 | CI 工作流全文（就一个 job 五步） | 2 |
| `src/test/java/com/linkforge/webhook/WebhookRetryClockIT.java` | 199 | 时区 fork 的主角，类头注释是事故档案 | 3 |
| `src/main/java/com/linkforge/config/OpenApiConfig.java` | 41 | 从 BuildProperties 读版本 | 4 |
| `src/test/java/com/linkforge/OpenApiInfoIT.java` | 31 | 钉住 OpenAPI 版本值的测试 | 5 |
| `src/main/java/com/linkforge/config/OpenApiConfig.java` 旁证 `src/main/resources/application.yml` | 266 | springdoc 在 dev/test 开、prod 关 | 6 |

## 4. 类的定位（构建脚本即编排层）

这一课没有「类」可定位，定位的是三份配置文件。`pom.xml` 是全项目唯一的构建事实源：版本、依赖、测试路由、时区 fork、迁移入口都在这 281 行里。`ci.yml` 是它在云端的一次复述：不引入任何 pom 里没有的构建步骤，`mvn -B verify` 一条命令跑完全部。`OpenApiConfig` 则是 pom 的一个下游消费者，把构建期信息（版本号）接进运行期接口（OpenAPI 文档）。

## 5. 核心源码详解

### 5.1 Maven 生命周期与 surefire/failsafe 分工

先看 pom 里有什么、没有什么。pom 没有任何 surefire 配置，单测路由完全靠 Boot parent 的默认值：`src/test/java` 下 27 个 `*Test` 类在 `test` 阶段由 surefire 执行。failsafe 则显式声明（`pom.xml:219-249`），两个执行块都绑定 `integration-test` 与 `verify` 两个 goal。为什么 failsafe 要绑两个 goal：`integration-test` 阶段跑测试但不判生死，`verify` 阶段才检查结果并把红变成构建失败。这样设计的好处是：某个 IT 红了，后续执行块与时区 fork 仍会跑完，一次构建能看到全部红点，而不是撞见第一个红就断流。

类名的分工是刻意约定：`*Test` 免 Docker（27 类，Redis 交互用 Mockito 或直接 new），`*IT` 必须有 Docker daemon（42 类）。本地快速回归跑 `mvn -B verify -DskipITs` 只付 surefire 的时间；全量证据只在 CI 上产生（5.5）。

### 5.2 failsafe 双执行与 -Duser.timezone fork

```xml
            <!-- *Test = pure units (surefire, no Docker); *IT = Testcontainers
                 integration (failsafe, bound to verify). WebhookRetryClockIT runs in
                 its own fork pinned to Asia/Shanghai so the JVM(+8) vs DB(UTC) clock
                 mismatch behind Bw2-M1 is exercised for real. -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-failsafe-plugin</artifactId>
                <executions>
                    <execution>
                        <id>default</id>
                        <goals>
                            <goal>integration-test</goal>
                            <goal>verify</goal>
                        </goals>
                        <configuration>
                            <excludes>
                                <exclude>**/WebhookRetryClockIT.java</exclude>
                            </excludes>
                        </configuration>
                    </execution>
                    <execution>
                        <id>webhook-tz-it</id>
                        <goals>
                            <goal>integration-test</goal>
                            <goal>verify</goal>
                        </goals>
                        <configuration>
                            <argLine>-Duser.timezone=Asia/Shanghai</argLine>
                            <includes>
                                <include>**/WebhookRetryClockIT.java</include>
                            </includes>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
```

（逐字摘自 `pom.xml:215-249`，注释在 `:215-218`。）

两个 execution 各管一半：`default` 跑全部 IT 但排除 `WebhookRetryClockIT`（`pom.xml:231`）；`webhook-tz-it` 用 `argLine` 把测试 JVM 的时区钉成 `Asia/Shanghai`（`pom.xml:242`），只 include 这一个类（`:243-245`）。每个 execution 各 fork 自己的测试 JVM，所以时区设置互不污染。

为什么这么绕？看事故档案。`WebhookRetryClockIT.java:27-38` 的类头注释：这个测试跑在钉死 Asia/Shanghai 的独立 fork 里，而 Testcontainers MySQL 保持 UTC，精确复现当年 JVM(+8) 对 DB(UTC) 的时钟偏差。那场事故（Bw2-M1）里，重试退避按 JVM 时钟排期，偏差被加进退避时长，原本秒级的重试被拖到约 28800 秒（8 小时）。回归测试钉两条断言：`WebhookRetryClockIT.java:145-151` 用 `TIMESTAMPDIFF(SECOND, NOW(), next_attempt_at)` 验证下次重试时刻落在 DB 时钟的 [0, 20] 秒窗口内（旧实现下这个差是约 28800）；`:194-197` 验证 `sent_at >= created_at`（业务时间戳归 JVM 时钟，旧实现用 `NOW()` 写出的 sent_at 会比 created_at 早 8 小时）。退避归 DB 时钟、业务时间戳归 JVM 时钟的双口径，出自 13.2；这个 fork 让「口径错了会怎样」永远有一道活的回归。

### 5.3 build-info：OpenAPI 版本的单一事实源

```xml
                    <execution>
                        <goals>
                            <!-- feeds BuildProperties so OpenAPI info.version tracks pom -->
                            <goal>build-info</goal>
                        </goals>
                    </execution>
```

（逐字摘自 `pom.xml:198-204`。）

`spring-boot-maven-plugin` 的 `build-info` goal 在构建时生成 `META-INF/build-info.properties`，Spring Boot 把它暴露为 `BuildProperties` Bean。消费端是 `OpenApiConfig.java:18-24`：

```java
    @Bean
    public OpenAPI linkForgeOpenApi(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();
        return new OpenAPI()
                .info(new Info()
                        .title("LinkForge")
                        .version(build != null ? build.getVersion() : "dev")
```

（摘自 `OpenApiConfig.java:18-24`，有省略，完整方法到 `:40`。）

数据流一条线：`pom.xml:16` 的 `<version>2.0.0</version>` → build-info.properties → `OpenApiConfig` 的 `info.version` → `/v3/api-docs` 响应。没有第二条路，改版本只改 pom 一处。`ObjectProvider` 处理「IDE 里直接跑、没经过 Maven 构建」的情况，拿不到就回退 `"dev"`（`OpenApiConfig.java:12-14` 注释）。`OpenApiInfoIT.java:28` 把这条链钉死：`assertEquals("2.0.0", body(resp).path("info").path("version").asText())`。pom 版本若改而测试没跟着改，CI 直接红。

### 5.4 一段真实的依赖仲裁案例

`pom.xml:24-29` 的两个属性注释是教科书级的仲裁现场，逐字读：

```xml
        <!-- Testcontainers 2.0.5 expects newer commons-lang3 than the Boot 3.3.5 BOM's
             3.14.0 (NoSuchMethodError SystemProperties.getUserName on CI); lang3 is
             binary-compatible upward, so one override aligns every consumer. -->
        <commons-lang3.version>3.18.0</commons-lang3.version>
        <!-- Testcontainers 2.0.x: Boot-managed 1.x docker-java is rejected by Docker Desktop 29. -->
        <testcontainers.version>2.0.5</testcontainers.version>
```

（逐字摘自 `pom.xml:24-29`。）

两个冲突、两种解法，机制都是「属性覆盖」：Boot parent 用属性表管理依赖版本，pom 里重声明同名属性即可全局改写。第一案，commons-lang3：Boot 3.3.5 BOM 定 3.14.0，Testcontainers 2.0.5 要更新版，CI 上抛 `NoSuchMethodError`。解法是把 `commons-lang3.version` 提到 3.18.0，依据是 lang3 向上二进制兼容，所有消费者一起升无副作用。第二案，Testcontainers：Boot BOM 管的 1.x 系列，其 docker-java 驱动被 Docker Desktop 29 拒绝，解法是 `dependencyManagement` 导入 Testcontainers 2.0.5 的 BOM（`pom.xml:36-46`）整体替换。区分这两案的意义：前者是「同一依赖，多方版本诉求」，后者是「Boot 管理的版本整体过期」。

### 5.5 CI 工作流五步与缓存键

```yaml
name: ci

on:
  push:
    branches: [main, 'feat/**', 'fix/**']
  pull_request:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - name: Cache Maven repo
        uses: actions/cache@v4
        with:
          path: ~/.m2/repository
          key: ${{ runner.os }}-m2-${{ hashFiles('pom.xml') }}
          restore-keys: ${{ runner.os }}-m2-
      - name: Build, unit and Testcontainers integration tests
        run: mvn -B verify
```

（逐字摘自 `.github/workflows/ci.yml:1-24`，全文。）

触发条件（`:3-6`）：push 到 main 或 `feat/**`、`fix/**` 分支，加所有 PR。五步里值得停一步的是缓存。`key` 由 `hashFiles('pom.xml')` 参与组成：依赖表一变（升级一个版本号也算），键就变，CI 从冷缓存重建，绝不复用旧依赖树；`restore-keys` 前缀兜底让键未命中时仍能捞最近一次旧缓存（部分命中，增量下载）。这是「依赖一变就全量重建」与「没变就全量复用」的平衡，键里放 pom 哈希而不是分支名，原因在多分支共享同一份依赖树。

`mvn -B verify`（`:24`）的 `-B` 是批处理模式，关掉下载进度条这类交互输出。这 24 行与 15.1 的口径闭环：main 恒绿（`README.md:625`），时区 fork 也在云端跑。

**Windows 开发机口径**：这个项目的开发机是 Windows（`scripts/fault-drill.sh:60` 里给 Windows 进程清理写的 `taskkill` 是旁证）。`docs/progress.md:315` 记录了口径：Windows 开发机上 Docker Desktop 29 与 Testcontainers 不兼容，本地回归用 `mvn -B verify -DskipITs`；`docs/progress.md:354` 写明「全量以 CI 为权威」。所以 15.1 的 42 个 IT 的真值看 CI 的绿 run，本地红/绿不构成集成层的证据。这条口径延续到最后一轮（`docs/progress.md:1655`），本地每提交仍是 `-DskipITs`。

### 5.6 application-test.yml 的测试专用姿态

测试 profile 的完整配置见 15.1 的 5.4 与 5.7，这里只收三处「为编排而设」的值：

- `spring.rabbitmq.listener.simple.auto-startup: false`（`application-test.yml:11-14`）：消费者默认不启动，只有显式声明 `auto-startup=true` 的 IT 才有消费，防止所有上下文的消费者抢同一条队列。
- `linkforge.webhook.relay-enabled: false`（`application-test.yml:58-60`）：relay 默认关，注释写明「只有 WebhookDeliveryIT 开 relay，兄弟上下文不许用更严的 SSRF 守卫抢抽共享投递表」。这是 `@DirtiesContext` 三兄弟之外的另一半防线。
- `linkforge.retention.cleanup-initial-delay-ms: 3600000`（`application-test.yml:49-53`）：清理任务延迟 1 小时，注释写明「共享 IT 库不能让清理 tick 与 SENT 断言赛跑」。

三处的共同姿态：测试 profile 的默认值是「最安静」的，需要哪个后台组件，哪个测试类自己打开，用完即拆（AFTER_CLASS）。

## 6. 技术原理补充

- **failsafe 为什么要存在（是什么/不用会怎样）**：surefire 在 `test` 阶段一红就终止构建。集成测试通常还要收尾（关容器、归档日志），中途死掉会留下脏环境。failsafe 把判定挪到 `verify`，让 `integration-test` 阶段的红不阻断后续 goal。本项目两个执行块 + 时区 fork 能在一次红构建里全部执行，就是这个语义的直接受益。
- **fork 与 argLine（是什么/为什么需要）**：测试 JVM 与构建 JVM 分离，`argLine` 是唯一的 JVM 参数入口。`-Duser.timezone=Asia/Shanghai` 能生效，前提是它作用在一个新 fork 的 JVM 上。不用 fork 就只能改全局环境变量，CI 上等于改 runner 时区，污染面不可控。
- **build-info（是什么/不用会怎样）**：构建期生成、运行期只读的版本档案。不用它，版本号就得硬编码在 `OpenApiConfig` 里，pom 与代码两处维护，迟早漂移。本项目用 `ObjectProvider` 兜底「无构建直跑 IDE」的场景。
- **Maven 属性覆盖与 BOM import（是什么）**：Boot parent 的属性表是第一优先级的版本源，子 pom 重写属性即全局生效；`dependencyManagement` + `import` scope 的 BOM 则是成批接管一组依赖的版本。5.4 两案分别示范了这两条杠杆。
- **GitHub Actions 缓存（是什么/键怎么设计）**：按 key 精确命中、按 restore-keys 前缀部分命中。键放 pom 哈希的含义是「缓存的有效性以依赖表为界」，比按分支或按时间戳更精确。

## 7. 可视化

仍看 [09-test-ci-pipeline.html](../../diagrams/archify/html/09-test-ci-pipeline.html)（测试与 CI 流程图）。本课与图的对应关系：

1. CI 五步节点逐一对上 `ci.yml:12-24` 的五个 step，缓存节点标注的键结构与 5.5 一致。
2. surefire/failsafe 分支节点对应 `pom.xml:215-249` 的双执行，图上的例数 151/166 出自 15.1 的 5.1 口径。
3. 时区 fork 分支节点对应 `webhook-tz-it` 执行块，图内卡片注明例数口径出处。
4. 图是流程骨架，本课补充的是每条边上的「为什么」（排除谁、钉哪个时区、键怎么变）。

## 8. 项目中的真实执行过程

一次完整 CI（按 `ci.yml` 与 pom 逐行推演）：

1. 推送 `feat/**` 分支触发工作流。checkout 代码，装 Temurin 17（`ci.yml:12-16`）。
2. 缓存步骤按 `ubuntu-latest-m2-<pom哈希>` 找键。pom 未变则全量命中 ~/.m2，几秒；pom 变了则走 restore-keys 前缀拿旧缓存，增量补下载（`ci.yml:17-22`）。
3. `mvn -B verify`：`test` 阶段 surefire 跑 27 个 UT 类，151 例执行实例；`integration-test` 阶段 failsafe `default` 执行块起 Testcontainers 跑 41 个 IT 类（166 例），`webhook-tz-it` 执行块另 fork 一个 Asia/Shanghai JVM 跑 `WebhookRetryClockIT`（2 例）；`verify` 阶段统一判死。
4. 任何一例红，构建失败但三个执行块都已跑完，PR 页一次看到全部红点。
5. 本地同期：开发机只跑 `mvn -B verify -DskipITs`（surefire 部分），IT 真值等 CI。

## 9. 为什么这样设计

- **文件名做路由，不用 @Tag**：`*Test`/`*IT` 的划分在文件名层完成，Maven 默认规则直接生效，不需要注解标记与自定义过滤器。代价是命名必须守纪律，收益是零配置。
- **时区事故用 fork 复现，不用容器时区**：把测试 JVM 钉到 +8 而保持 MySQL UTC，才测得出「应用代码对双时钟的处理是否正确」。反过来把容器也改成 +8，事故现场就消失了，测试恒绿但没有信息量。
- **版本号单点维护**：build-info 把 pom 变成 OpenAPI 版本的唯一上游，测试钉住具体值，漂移在 CI 即暴露。
- **依赖仲裁写进注释**：`pom.xml:24-29` 每条 override 都写明「谁与谁冲突、报什么错、为什么敢这么改」。半年后没人记得 CI 上的 NoSuchMethodError，注释就是裁决书。
- **本地/CI 分层出证据链**：本地付不起 Docker 的时间与不兼容的代价，就只付 surefire；CI 是全量真值。口径写死在 progress.md，避免「我本地是绿的」这种模糊话。

## 10. 本节总结

- `mvn verify` 的时间线：surefire 在 `test` 跑 `*Test`（151 例），failsafe 在 `integration-test`/`verify` 跑 `*IT`（166 例 + fork 2 例），failsafe 的红在 `verify` 才判死。
- `webhook-tz-it` 执行块用 `argLine=-Duser.timezone=Asia/Shanghai` 的独立 fork 复现 JVM(+8)/DB(UTC) 偏差，守卫 DB 时钟退避的双口径。
- `build-info` 让 pom version 流经 build-info.properties 进 OpenAPI，`OpenApiInfoIT` 钉住 "2.0.0"，单一事实源加测试闭环。
- 依赖仲裁两案：commons-lang3 属性覆盖解决「多方版本诉求」，Testcontainers BOM import 解决「Boot 管理版本过期」，注释即裁决书。
- CI 五步里缓存键是 `runner.os-m2-<pom哈希>` 加前缀兜底；Windows 开发机 `-DskipITs`，IT 真值以 CI 为权威。

## 11. 课后练习

**练习 1（概念理解）** failsafe 的 `default` 执行块已经绑定了 `integration-test` 与 `verify` 两个 goal，如果只绑 `integration-test` 不绑 `verify`，红测试会导致构建失败吗？

答案：不会立即体现为失败。`integration-test` 阶段只执行测试并记录结果，判定与置红发生在 `verify` 阶段的 verify goal。少了它，IT 全红构建也可能是绿的。这正是 `pom.xml:225-228` 与 `:237-240` 两个执行块都写两个 goal 的原因。

**练习 2（源码定位）** 在 pom 里找到让 `WebhookRetryClockIT` 从默认组里消失的一行，以及让它单独跑在一个 +8 时区 JVM 里的一行。

答案：排除在 `pom.xml:231`（`<exclude>**/WebhookRetryClockIT.java</exclude>`，属 `default` 执行块）；时区在 `pom.xml:242`（`<argLine>-Duser.timezone=Asia/Shanghai</argLine>`，属 `webhook-tz-it` 执行块，配套 `:243-245` 只 include 这一个类）。动机写在 `pom.xml:215-218` 注释与 `WebhookRetryClockIT.java:27-38` 类头注释。

**练习 3（执行过程）** `WebhookRetryClockIT.java:149-151` 断言 `TIMESTAMPDIFF(SECOND, NOW(), next_attempt_at)` 在 [0, 20] 之间。在旧实现（退避按 JVM 时钟排期）下，这个差值会约是多少？为什么 fork 里的 JVM 是 +8 而容器是 UTC？

答案：约 28800 秒（8 小时），即 JVM(+8) 与 DB(UTC) 的偏差被加进重试等待（`WebhookRetryClockIT.java:33-35` 注释与 `:151` 的失败消息都写了 28800）。fork 的设计目标是复现事故现场：应用代码运行在 +8 的 JVM 里，而 MySQL 容器保持 UTC，这两个时钟源同时存在时，退避是否正确落在 DB 时钟上才有区分度。若把容器时区也改成 +8，偏差消失，断言恒真，回归失效。

**练习 4（调用链）** 从 `pom.xml:16` 的 `<version>2.0.0</version>` 出发，写出版本号到 `/v3/api-docs` 响应的完整链路，并指出哪一环没有 pom 参与时会退化。

答案：pom version → `spring-boot-maven-plugin` 的 build-info goal 生成 `META-INF/build-info.properties`（`pom.xml:199-204`）→ Spring Boot 暴露为 `BuildProperties` Bean → `OpenApiConfig.linkForgeOpenApi` 读它写进 `Info.version`（`OpenApiConfig.java:19-24`）→ springdoc 渲染到 `/v3/api-docs` → `OpenApiInfoIT.java:28` 断言 `"2.0.0"`。退化点在第二环：IDE 里不经 Maven 构建就没有 build-info.properties，`ObjectProvider` 拿不到 Bean，`OpenApiConfig.java:24` 回退显示 `"dev"`。

**练习 5（设计分析）** CI 缓存键为什么用 `hashFiles('pom.xml')` 而不是固定字符串 `m2-cache`？两个方案各自什么时候吃亏？

答案：固定键的缓存永远命中，但依赖表变了也不重建，CI 会一直用旧依赖树，pom 里新声明的依赖直到缓存被手动清掉才生效，这类「CI 绿但依赖没更新」最骗人。pom 哈希键在依赖变化时立即换新键、冷启动重建（多花几分钟下载），未变化时全量复用；`restore-keys` 前缀（`ci.yml:22`）保证新键未命中时仍可部分复用旧缓存。吃亏点对比：哈希键吃「无关改动（改代码不改依赖）也不破坏缓存」的便宜，固定键吃「依赖变更不生效」的亏。

## 12. 与后续课程的联系

15.3 是全课程收官：离开 CI 的虚拟机，落到开发者自己的机器上。Compose 的五个 service 是这些 IT 容器的「常驻版」，四个演练脚本把 15.1 里 IT 测过的降级行为（MQ 断链、Redis fail-open、MySQL 只读）变成现场可跑的命令，prod profile 的三层收紧则对应 15.2 见过的 `application.yml` 多文档结构。构建脚本之外想继续深挖依赖仲裁与 BOM 的通用规律，回顾 [../01-spring-boot/01-maven-pom.md](../01-spring-boot/01-maven-pom.md)；时区双口径的业务侧全貌在 [../13-webhook/02-delivery-signing-retry.md](../13-webhook/02-delivery-signing-retry.md)，综合专题「Webhook 投递与重试」（`docs/source-learning/advanced/11-webhook-delivery-retry.md）会再走一遍。
