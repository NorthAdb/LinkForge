# 1.1 Maven 工程与 pom

## 1. 本节学习目标

读完本节你能做到下面四件事。

1. 说出 `pom.xml` 每一段的作用：坐标、parent、properties、依赖、插件。
2. 解释五个自定义版本属性各自的来由，特别是 commons-lang3 为什么从 Boot 默认的 3.14.0 强升到 3.18.0。
3. 讲清 `build-info` 这一个小配置如何一路传到 Swagger 页面的版本号。
4. 说清 failsafe 插件为什么配置了两次执行、时区 fork 是为了复现哪个 bug。本节不写 Java 代码，把 pom 当成一份「项目说明书」读。

## 2. 前置知识

- XML 基础。`pom.xml` 是 XML 文件，标签成对出现，缩进只影响可读性。
- 依赖是什么。你的代码要调用别人写好的 jar 包，每个 jar 包由 `groupId:artifactId:version` 三个字段唯一定位，这三个字段合称「坐标」。
- 传递依赖。你引入 A，A 自己又依赖 B，B 也进入你的 classpath。这既是便利也是版本冲突的来源，后面讲 BOM 就是为了管住它。

Maven 本身的安装与命令（`mvn clean package` 等）属于工程操作，仓库根目录的 [01-project-overview.md](../../01-project-overview.md) 给了运行入口，本节聚焦「这份 pom 为什么长这样」。

## 3. 本节涉及的源码

| 文件 | 职责 | 阅读顺序 | 为什么读它 |
|------|------|----------|------------|
| `pom.xml`（281 行） | 整个项目的依赖清单与构建配方 | 第 1，逐段读 | 本节唯一主角 |
| `src/main/java/com/linkforge/config/OpenApiConfig.java` | build-info 的消费端 | 第 2 | 看 pom 里一行配置的下游效果 |
| `src/test/java/com/linkforge/webhook/WebhookRetryClockIT.java` | 时区 fork 的服务对象 | 第 3 | 理解双执行的存在理由 |

## 4. 类的定位

pom 不是类，它是 Maven 的输入文件，Maven 按它生成构建过程。但它决定了一件对学源码最要紧的事：你的 classpath 上有哪些库、什么版本。第 1.2 课讲 Spring 容器装配对象，本课讲的是「装配之前，东西从哪来」。整个项目 120 个主源码类用到的第三方库，全部由这份 pom 声明。

## 5. 核心源码详解

### 5.1 坐标与 parent（pom.xml:7-18）

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>
    <relativePath/>
</parent>

<groupId>com.linkforge</groupId>
<artifactId>linkforge</artifactId>
<version>2.0.0</version>
<name>linkforge</name>
<description>LinkForge — multi-tenant short link + click analytics platform</description>
```

（框架行为）声明 parent 之后继承两样东西。一是两百多个常用依赖的版本托管，引用时不写 version 就用它钦定的；二是编译插件默认参数，`<java.version>` 属性（pom.xml:21）写 17 就是用 Java 17 编译。`<relativePath/>` 空标签表示不从本地文件系统找 parent，去仓库取。（本项目实现）坐标 `com.linkforge:linkforge:2.0.0`，2.0.0 在 5.4 还会出现一次。

### 5.2 properties：五个自定义版本的来由（pom.xml:20-34）

```xml
<!-- Testcontainers 2.0.5 expects newer commons-lang3 than the Boot 3.3.5 BOM's
     3.14.0 (NoSuchMethodError SystemProperties.getUserName on CI); lang3 is
     binary-compatible upward, so one override aligns every consumer. -->
<commons-lang3.version>3.18.0</commons-lang3.version>
<!-- Testcontainers 2.0.x: Boot-managed 1.x docker-java is rejected by Docker Desktop 29. -->
<testcontainers.version>2.0.5</testcontainers.version>
```

（pom.xml:24-29，注释原文逐字。每条升级都有注释记录动机，这是本项目的好习惯。）

1. `commons-lang3.version=3.18.0`（pom.xml:27）。测试框架 Testcontainers 2.0.5 调用了 `SystemProperties.getUserName` 这个方法，Boot 3.3.5 默认给的 lang3 3.14.0 里没有它，CI 上直接 `NoSuchMethodError`。lang3 官方保证向上二进制兼容，所以一个属性抬高，所有用到 lang3 的组件统一对齐。
2. `testcontainers.version=2.0.5`（pom.xml:29）。Boot 管理的 testcontainers 1.x 自带的 docker-java 版本太老，Docker Desktop 29 拒绝它。
3. `guava.version=33.4.8-jre`（pom.xml:31）。注释写明「Boot BOM does not manage Guava」，Boot 不管 Guava 的版本，而跳转热路径的 Bloom 过滤器（A9）依赖它，只能自己定版本。
4. `zxing.version=3.5.3`（pom.xml:33）二维码渲染（C1 wave B）用；`mybatis-plus.version=3.5.9` 与 `jjwt.version=0.12.6`（pom.xml:22-23）都不在 Boot 管理范围，自定。

规则一句话：Boot 管的不写版本，Boot 不管的自定属性集中声明。判断「Boot 管不管」不用背，看 pom 里哪些依赖没写 version 就知道了。

### 5.3 dependencyManagement 与 BOM（pom.xml:36-46）

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-bom</artifactId>
            <version>${testcontainers.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

（框架行为）BOM（Bill of Materials）是一份只含版本号的清单。`import` 一个 BOM 等于说「这一家的成员版本号，听它的」。Testcontainers 家族有 junit-jupiter、mysql、rabbitmq 三个成员（pom.xml:173-190），版本全由这份 BOM 统一到 2.0.5。你在 dependencies 里引用它们时仍然要写 `${testcontainers.version}`，因为 Boot 的 BOM 管不到 2.0.5 这个大版本。

（框架行为）版本仲裁规则：离你的 pom 越近的声明越赢。parent 管的版本可以被本项目 dependencyManagement 覆盖，再被 dependencies 里显式写的 version 覆盖。commons-lang3 的强升走的是第二层。

### 5.4 依赖清单怎么读（pom.xml:48-191）

27 个依赖不用背，按 scope 和用途分组记。

启动器组（都是 compile scope，写业务直接用）：web、validation、actuator、data-redis、amqp、security（pom.xml:49-82）。starter 是「打包好的依赖集合」，引一个等于引一串。

观测与文档（pom.xml:62-70）：`micrometer-registry-prometheus` 供 Prometheus 抓取（B1），`springdoc-openapi-starter-webmvc-ui` 2.6.0 生成 Swagger 页面。

数据层（pom.xml:83-103）：Flyway 10 把核心与数据库方言拆成两个模块，所以 `flyway-core` 加 `flyway-mysql` 成对出现，后者 scope 是 runtime（编译期不用它的类，运行期要）。MyBatis-Plus 的 starter 之外还单独引了 `mybatis-plus-jsqlparser`，分页插件解析 SQL 需要。

业务依赖（pom.xml:104-137）：guava（Bloom）、caffeine（进程内 L1 缓存）、httpclient5（Webhook 出站投递，注释写明选它是因为 HC5 的可插拔 DnsResolver 能配合 SSRF 防护钉死目标地址）、zxing core 加 javase（矩阵计算与 PNG 渲染分家）、mysql-connector-j（runtime）。

JWT 三件套（pom.xml:139-155）：`jjwt-api` 是 compile，`jjwt-impl` 和 `jjwt-jackson` 是 runtime。（Java 语言知识）这是典型的 API/实现分离：业务代码只 import api 包的接口，实现包在运行期由 SPI 装配，换实现不用改业务代码。lombok 是 `optional=true`（pom.xml:157-161），编译期生成代码用，不传递给依赖你的项目。测试组（pom.xml:163-190）：starter-test（JUnit 5、Mockito、AssertJ）、spring-security-test、Testcontainers 三个模块。

### 5.5 spring-boot-maven-plugin 与 build-info（pom.xml:195-214）

```xml
<executions>
    <execution>
        <goals>
            <!-- feeds BuildProperties so OpenAPI info.version tracks pom -->
            <goal>build-info</goal>
        </goals>
    </execution>
</executions>
```

（pom.xml:198-205，注释原文）

（框架行为）`build-info` 目标在编译时生成 `target/classes/META-INF/build-info.properties`，里面记录 artifact、version、构建时间。Spring Boot 自动配置探测到这个文件就注册一个 `BuildProperties` Bean。

（本项目实现）消费端在 OpenApiConfig.java:18-24：

```java
    @Bean
    public OpenAPI linkForgeOpenApi(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();
        return new OpenAPI()
                .info(new Info()
                        .title("LinkForge")
                        .version(build != null ? build.getVersion() : "dev")
```

链路完整走一遍：pom 的 version 2.0.0 → build-info 写入属性文件 → `BuildProperties` Bean → Swagger 页面 info.version 显示 2.0.0。`ObjectProvider` 是「可选注入」，从 IDE 里直接跑 main 时没有 build-info 文件，这个 Bean 不存在，`getIfAvailable()` 返回 null，页面显示 "dev"。如果这里写普通注入 `BuildProperties buildProperties`，IDE 裸跑会启动失败。1.2 课会把这个注入方式作为范例细讲。

插件还有一段 excludes（pom.xml:206-213）把 lombok 从最终 fat jar 里剔掉，运行期用不到它，jar 能小一点。

### 5.6 failsafe 双执行与时区 fork（pom.xml:215-249）

先说分工。（框架行为）Maven 的 `test` 阶段跑 surefire 插件，`verify` 阶段跑 failsafe 插件。本项目靠类名区分：`*Test` 是纯单元测试，不起 Spring 容器，由 surefire 跑；`*IT` 是集成测试，用 Testcontainers 起真实 MySQL/Redis/RabbitMQ，由 failsafe 跑。两端加起来 319 个执行实例（151 单测 + 166 IT + 2 时区 fork，口径见 [progress.md](../../reference/progress.md)）。

```xml
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
```

（pom.xml:223-247，节选自双执行声明）

（本项目实现）除了 id 和 include/exclude，两个执行完全一样。default 执行跑全部 IT 但排除 `WebhookRetryClockIT`；webhook-tz-it 执行只跑它，并且给 JVM 加 `-Duser.timezone=Asia/Shanghai`。

为什么要这样绕？看测试自己的 javadoc（WebhookRetryClockIT.java:27-34）：

```java
/**
 * B-wave2-fix M1 + S2 regression, run in a dedicated failsafe fork pinned to
 * Asia/Shanghai (pom, execution webhook-tz-it) while the Testcontainers MySQL
 * stays on UTC — the exact JVM(+8)/DB(UTC) skew that made the old JVM-clock
 * backoff stretch to ~8h. Asserts:
```

它要复现的 bug 是：JVM 在东八区、MySQL 容器在 UTC，两者时钟差 8 小时，旧实现的 webhook 重试时间按 JVM 时钟写库后，重试被拖后约 8 小时。要长期防止回归，就得让测试每次都在「JVM +8、DB UTC」的错位下运行。CI 的 JVM 时区不确定，所以 fork 参数把 JVM 钉死在 Asia/Shanghai，MySQL 容器保持默认 UTC，错位就是确定性的。普通开发者机器在东八区时看似多余，在 UTC 机器上没有这个 fork 就复现不了 bug。

### 5.7 flyway-maven-plugin（pom.xml:250-278）

（本项目实现）这段不是构建流程的一部分，是给 `scripts/migrate.sh` 用的命令行工具。脚本以 `-Dflyway.url=... -Dflyway.user=...` 传入连接参数（插件 configuration 里 `${flyway.url}` 就是这么取值的）。`${flyway.version}` 和 `${mysql.version}` 两个属性没有在本 pom 定义，它们由 parent 预定义（框架行为），保证插件与运行时 Flyway、驱动版本一致。迁移内容本身在模块 04 讲。

## 6. 技术原理补充

**Maven 生命周期**（框架行为）。Maven 定义了有序阶段：compile → test → package → verify → install。执行后面的阶段会先执行前面的。`mvn verify` 实际跑了编译、单测（surefire）、打包、集成测试（failsafe）四件事。这也是 CI 脚本（.github/workflows/ci.yml）只写一行 `mvn -B verify` 的原因。

**BOM 与版本仲裁**（框架行为）。见 5.3。记住「近者优先」就能推断任何版本冲突的结果。

**scope 的三种常用值**（框架行为）。compile 默认，编译运行都要；runtime 编译不参与、运行期在；test 只在测试编译与运行。判断标准是「业务代码有没有 import 它」。fork 与 argLine 的机制 5.6 已顺带讲过：failsafe 每个执行默认在独立 JVM 里跑测试，argLine 就是传给那个 JVM 的参数。

## 7. 可视化

- [运行时与部署图](../../diagrams/archify/html/01-runtime-deployment.html)（由并行图表任务产出）。看构建产物 linkforge-2.0.0.jar 与五个容器的部署关系；Testcontainers 在 CI 里临时扮演 MySQL/Redis/RabbitMQ 的角色。

阅读顺序：先 5.6 的双执行，再看部署图确认「测试容器是部署容器的替身」。1.2 课会引用[系统架构图](../../diagrams/archify/html/00-system-architecture.html)。

## 8. 项目中的真实执行过程

以 CI 上一次 `mvn -B verify` 为时间线。

1. Maven 读 pom，解析 parent 与 BOM，确定全部依赖版本。lang3 此时是 3.18.0，testcontainers 是 2.0.5。
2. 编译主代码，lombok 在编译期展开 `@Data` 等注解（1.2 课细讲）；`build-info` 写入 META-INF/build-info.properties，version 为 2.0.0。
3. surefire 在构建 JVM 里跑 151 个 `*Test`。
4. failsafe 的 default 执行 fork 一个 JVM 跑 165 个 `*IT`（166 减去被排除的 WebhookRetryClockIT），Testcontainers 拉起三个容器。
5. failsafe 的 webhook-tz-it 执行再 fork 一个 JVM，时区 Asia/Shanghai，只跑 WebhookRetryClockIT 的 2 个用例。
6. 全绿后 verify 阶段结束。（第 4、5 步的数量按 151+166+2=319 的口径，实际拆分以测试报告为准。）

## 9. 为什么这样设计

- 版本属性全部集中在 properties 且带动机注释（pom.xml:24-33）。半年后没人记得 3.18.0 是为什么，注释就是决策记录。推断：作者按「一次事故一条注释」的纪律维护。
- 双执行而不是两个 profile。时区 fork 每次构建都要跑，做成 profile 就容易忘开。推断：把「必须发生的验证」编码进默认构建，能防回归。
- jjwt 拆三件、lombok optional、mysql 驱动 runtime。全部是「编译期最小可见」原则的落地：业务代码见到的 API 越窄，替换与裁剪越容易。

## 10. 本节总结

- parent 提供版本托管与插件默认值；`java.version=17` 是给 parent 看的。
- 五个自定义版本属性各有事故或特性背景，Boot 管的不写版本，Boot 不管的自定。
- build-info 把 pom 版本送进 `BuildProperties` Bean，OpenApiConfig 用 `ObjectProvider` 容忍它缺席。
- failsafe 双执行让一个时区敏感测试固定在「JVM 东八区、DB UTC」下运行，复现并锁死一个真实 bug。
- flyway-maven-plugin 是运维脚本的后端，不参与日常构建。

## 11. 课后练习

**练习 1（概念理解）**：把 `<parent>` 整段删掉，本项目会在哪些地方坏？至少说出三类。

答案解析：第一，所有 starter 与 Boot 管理的依赖（caffeine、mysql-connector-j、micrometer 等）没有 version，直接构建失败；第二，`<java.version>` 属性无人识别，需要自己配 maven-compiler-plugin；第三，spring-boot-maven-plugin 的 repackage 等目标失去默认配置，jar 不再是可执行 fat jar。parent 的价值就是这三件事的默认值集合。

**练习 2（源码定位）**：commons-lang3 强升的完整动机写在 pom 哪几行？错误类型与方法名是什么？

答案解析：pom.xml:24-27 的注释。错误是 `NoSuchMethodError`，方法来自 Testcontainers 2.0.5 调用的 `SystemProperties.getUserName`，Boot 3.3.5 BOM 的 3.14.0 没有该方法。

**练习 3（执行过程）**：`mvn verify` 时 WebhookRetryClockIT 一共在几个 JVM 里运行？两个 fork 的时区分别由什么决定？

答案解析：只在 1 个 JVM 里运行。default 执行把它排除（pom.xml:230-233），webhook-tz-it 执行 include 它并给该 fork 传 `-Duser.timezone=Asia/Shanghai`（pom.xml:242）。MySQL 容器时区与 JVM 无关，保持镜像默认 UTC，错位由此而来。

**练习 4（调用链）**：从 pom 到 Swagger 页面，版本号 2.0.0 经过哪几站？

答案解析：pom.xml:16 `<version>2.0.0` → build-info 目标写入 `META-INF/build-info.properties` → Spring Boot 自动配置注册 `BuildProperties` Bean → OpenApiConfig.java:20 `getIfAvailable()` 取到 → OpenApiConfig.java:24 `build.getVersion()` 填进 Info → springdoc 渲染到 /swagger-ui 页面。IDE 裸跑时第二站缺失，页面显示 "dev"。

**练习 5（设计分析）**：`jjwt-impl` 为什么可以是 runtime，改成 compile 会怎样？

答案解析：业务代码只 import jjwt-api 的接口（如 `Jwts.parserBuilder()` 所在层），实现细节不进编译期。改成 compile 也能跑，但 impl 的类全部进入编译期可见范围，等于给「绕过 API 直接用实现」开了门，换实现时改动面变大。runtime scope 是编译期最小可见的机械保证。

## 12. 与后续课程的联系

下一课 [1.2 启动类、IoC 与依赖注入](02-bootstrap-ioc-di.md) 用 pom 引来的这些库开始装配对象，`ObjectProvider` 会在那里再次出现。[1.3 配置体系](03-configuration-profiles.md) 讲这些依赖如何被配置驱动。Maven 与 CI 的完整流程（缓存、workflow 逐行）在模块 15 展开，那里默认你已经懂今天这 281 行。
