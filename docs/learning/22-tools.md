# Maven、Git 与 Linux：命令行工具链

优先级 P0（Maven 与 Git）/ P1（Linux 命令），目标层级 L2（独立完成构建、提交、排障）。

## 先遇到问题：没有工具链的一周

假设你只装了 JDK，其余什么都没有，现在要把 LinkForge 这个项目跑起来。

第一件事是编译。`src/main/java` 下有上百个 `.java` 文件，互相引用。手工编译要把每个文件列进 `javac` 的命令行，还要用 `-cp` 拼出所有外部 jar 的路径——`UserAccount` 用了 Lombok 和 MyBatis-Plus 的注解，光这两个 jar 的路径你就要查半天，漏一个就是 `cannot find symbol`。

第二件事是拿 jar。项目要连 MySQL、发 RabbitMQ 消息、发 HTTP 请求。这些能力不在 JDK 里，要自己下载几十个 jar，还要下对版本：Jackson 的 2.16 和 2.17 接口有差异，拿错版本运行时才炸。

第三件事是交付。编译出来的 `.class` 文件怎么给别人？散着拷几千个文件，还是打成一个 jar？打完 jar，对方的机器上又没有这些第三方 jar，怎么办？

第四件事是改代码。改坏了一个文件想退回昨天的版本，你昨天没有备份，退不回去。

第五件事是排查。程序在服务器上卡住、变慢、报错，你既看不到界面也没有 IDE，只剩一个黑终端。

Maven 管前三件（编译、依赖、打包），Git 管第四件（历史与协作），Linux 命令管第五件（服务器排障）。本篇的目标不是背命令，而是讲清每个工具解决的问题模型，以及每条命令的真实样子。前置知识见 [12-jvm.md](12-jvm.md)（编译与运行分离）；本仓库本身就是练习场。

## Maven：先管依赖，再管构建

### 没有它你要手工做三件事

**编译**。javac 每次都要完整的 classpath（一组"去哪里找类"的路径）。项目依赖 24 个直接依赖，解析后实际参与编译的 jar 远不止这些（下面会看到真实数字）。手工维护这份路径列表，每加一个依赖改一次脚本。

**找依赖**。你要知道 spring-web 6.1.14 又依赖 spring-core、spring-context 等哪些 jar，逐个去下载网站找、核对版本。这件事 Maven 叫"传递性依赖"，人肉做一遍就会理解它的价值。

**打包**。`jar` 命令可以把 `.class` 打成 jar，但不会把第三方 jar 一起放进去，对方机器上没有依赖，`java -jar` 直接失败。

Maven 把这三件事变成一个 `pom.xml` 描述加一两条命令。以下所有输出都是 2026-10 本机实测（Windows 11，Maven 3.9.9，JDK 21 跑 Maven、项目按 17 编译）。

### 坐标与仓库：jar 是怎么被找到的

Maven 用三个字段定位一个构件：`groupId`（组织，倒写域名）、`artifactId`（构件名）、`version`。三者合称坐标，例如 `com.mysql:mysql-connector-j:8.3.0`。坐标像"收货地址"，仓库按这个地址存放和查找 jar。

比喻：本地仓库像你书架上已经买回来的书，远程仓库（中央仓库是最大那个）像书店。Maven 先查书架，没有才去书店买一本回来放着。比喻失效的地方：书店的"书"只会下载一次且永不主动更新，版本对不对完全取决于你 pom 里写了什么。

本机的"书架"位置由 `settings.xml` 决定。这台机器的用户级 `settings.xml` 全文只有三行：

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
  <localRepository>D:/Java/m2/repository</localRepository>
</settings>
```

用命令核实（路径要用完整路径调用，原因见后文 mvnw 一节）：

```bash
D:/tools/apache-maven-3.9.9/bin/mvn.cmd -q help:evaluate -Dexpression=settings.localRepository -DforceStdout
```

```text
D:\Java\m2\repository
```

去 `D:\Java\m2\repository` 看一眼：里面是 `org\springframework\spring-core\6.1.14\spring-core-6.1.14.jar` 这样的目录树，和坐标一一对应。第一次构建慢，是因为要把依赖从远程仓库下载到本地；第二次起就快了。

### 依赖树与冲突仲裁：24 个依赖怎么变成 168 个 jar

LinkForge 的 `pom.xml` 在 dependencies 段直接声明了 24 个依赖。用 `mvn dependency:tree` 看真实解析结果（输出 349 行，非重复的 jar 节点 168 个，下为节选，原样粘贴）：

```text
[INFO] --- dependency:3.6.1:tree (default-cli) @ linkforge ---
[INFO] com.linkforge:linkforge:jar:2.0.0
[INFO] +- org.springframework.boot:spring-boot-starter-web:jar:3.3.5:compile
[INFO] |  +- org.springframework.boot:spring-boot-starter:jar:3.3.5:compile (version managed from 3.3.5; scope not updated to compile)
[INFO] |  |  +- org.springframework.boot:spring-boot:jar:3.3.5:compile (version managed from 3.3.5; scope not updated to compile)
[INFO] |  |  |  +- (org.springframework:spring-core:jar:6.1.14:compile - version managed from 6.1.14; omitted for duplicate)
[INFO] +- com.google.guava:guava:jar:33.4.8-jre:compile
[INFO] |  +- com.google.guava:failureaccess:jar:1.0.3:compile
[INFO] |  +- com.google.errorprone:error_prone_annotations:jar:2.36.0:compile
[INFO] +- com.github.ben-manes.caffeine:caffeine:jar:3.1.8:compile
[INFO] |  +- org.checkerframework:checker-qual:jar:3.37.0:compile
[INFO] |  \- (com.google.errorprone:error_prone_annotations:jar:2.21.1:compile - omitted for conflict with 2.36.0)
```

怎么读：

- 缩进表达层级。`+-` 是同级依赖，`|` 是上一层的竖线延续，`\-` 是最后一个孩子。根节点是项目自己。
- `spring-boot-starter-web` 只有 1 行声明，展开后有整整一棵子树。传递性依赖指的就是这些"你没写、但它带来"的 jar。
- 括号里的注解是调解记录。`omitted for duplicate` 表示同一构件在树里出现多次，只保留一处。`version managed from X` 表示有依赖本来想要版本 X，被"版本管理"（下节讲）改写成了现在的值。

重点看 `error_prone_annotations` 这个真实冲突：guava 带来 2.36.0，caffeine 带来 2.21.1，同一个 jar 两个版本，不能都放进 classpath。Maven 的调解规则是 nearest-wins（路径最短者胜）：谁离树根的层级浅，用谁的。两个都在第 2 层时，先被声明的赢。本例中 guava 在 `pom.xml` 里声明在 caffeine 之前，所以 2.36.0 胜出，caffeine 那份被标记 `omitted for conflict`。

nearest-wins 的代价：赢的版本未必是"两个依赖都兼容"的版本，只是"路径上更近"的版本。所以升级依赖后，规范动作是跑一遍 `dependency:tree` 确认没有意外版本混进来，而不是假定一切正常。

### parent、BOM 与版本管理：为什么大部分依赖不写版本号

看 `pom.xml` 的开头（项目真实文件，节选）：

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>
    <relativePath/>
</parent>
```

`parent` 表示继承。`spring-boot-starter-parent` 3.3.5 是一个"管家的 pom"：它自己继承自 `spring-boot-dependencies`，后者声明了几百个常用构件的推荐版本清单。这种"只管版本、不带代码"的 pom 叫 BOM（Bill of Materials，物料清单）。

继承它之后，三件事自动发生：

1. 你的依赖不写 `<version>`，就从 BOM 拿版本。`spring-boot-starter-web` 不写版本，解析成 3.3.5 一家的组合，Spring 官方保证这些版本互相兼容。
2. `java.version` 属性被映射成编译参数。parent 里有一行 `<maven.compiler.release>${java.version}</maven.compiler.release>`，本项目 `<java.version>17</java.version>`，于是编译按 Java 17 的语言级别进行，哪怕跑 Maven 的 JDK 是 21。
3. 插件版本与基础配置被托管。编译时看到的 `compiler:3.13.0`、`surefire:3.2.5` 这些版本号都不是本项目写的，来自 parent。

需要偏离 BOM 时，用属性覆盖。`pom.xml` 里的真实例子（含原作者注释）：

```xml
<!-- Testcontainers 2.0.5 expects newer commons-lang3 than the Boot 3.3.5 BOM's
     3.14.0 (NoSuchMethodError SystemProperties.getUserName on CI); lang3 is
     binary-compatible upward, so one override aligns every consumer. -->
<commons-lang3.version>3.18.0</commons-lang3.version>
```

翻译：Testcontainers 2.0.5 调用了 `SystemProperties.getUserName` 这个方法，而 Boot 3.3.5 BOM 托管的 commons-lang3 3.14.0 里还没有它，CI 上运行时报 `NoSuchMethodError`。覆盖成 3.18.0 解决。注释还回答了"为什么敢覆盖"：lang3 向上二进制兼容，旧调用方在 3.18.0 上照常工作。

覆盖是否生效，不要靠相信，靠验证。依赖树里有一行（真实输出）：

```text
[INFO] |  |  |     +- (org.apache.commons:commons-lang3:jar:3.18.0:compile - version managed from 3.14.0; omitted for duplicate)
```

`version managed from 3.14.0` 的意思是：某依赖本想要 3.14.0（BOM 托管值），版本管理把它改写成了 3.18.0。这行就是覆盖生效的证据。

`dependencyManagement` 段是另一处版本管理入口，用于引入第三方 BOM。本项目用它导入了 `testcontainers-bom` 2.0.5（`<type>pom</type><scope>import</scope>`），把 Testcontainers 一家模块的版本统一交给那张清单管。

经验法则：**先问"这个版本是谁管的"再动手升级**。BOM 管的不写版本；自己 properties 里覆盖的要写理由；BOM 也不管的（如本项目 Guava，注释写明"Boot BOM does not manage Guava"）才自己定版本。

### scope：依赖在哪些时刻可见

scope 决定依赖出现在哪个类路径（classpath，编译和运行时查找类的路径集合）。四种常用值，全部能在本项目找到实例：

| scope | 可见时刻 | 本项目实例 |
|-------|---------|-----------|
| compile（默认） | 编译期和运行期都要 | `spring-boot-starter-web`，代码里直接 import 它的类 |
| runtime | 编译期不需要，运行期要 | `mysql-connector-j`、`jjwt-impl`、`flyway-mysql` |
| test | 只在测试源集 | `spring-boot-starter-test`、`testcontainers-*` |
| provided | 编译要，运行环境自己提供 | 本项目未用（容器部署下常见于 Servlet API） |

`runtime` 值得展开。为什么 `jjwt-impl` 是 runtime？项目代码只 import `jjwt-api` 里的接口，实现 jar 在编译期不可见也不需要；运行期 JVM 真正干活时才加载实现。好处是实现可整体替换，编译却不会因此重跑。`mysql-connector-j` 同理：代码只使用 JDBC 的标准接口（JDK 自带的 java.sql 包）和连接串，驱动类是运行时按配置加载的。

scope 还有传递规则：你的 compile 依赖会把对方的 compile 依赖也带进来，test 依赖不会传染给使用你 jar 的人。初学阶段记住"test 的依赖不会跑进最终包"这一条就够日常使用了。

### 生命周期：`mvn package` 里的 package 到底是什么

Maven 有三套独立生命周期，最常用的是 default（构建主线）和 clean（清理）。default 由一串有序的 phase（阶段）组成，核心的几个：

| phase | 做什么 | 产物 |
|-------|--------|------|
| compile | 编译主代码 | `target/classes` 下的 `.class` |
| test | 跑单元测试（Surefire 插件，匹配 `*Test` 类） | `target/surefire-reports` |
| package | 把编译结果打包 | `target` 下的 jar |
| verify | 跑集成测试并校验（Failsafe 插件，匹配 `*IT` 类） | 测试报告 + 判定 |
| install | 把包安装进本地仓库，供本机其他项目引用 | `settings.xml` 指向的仓库里多一个构件 |
| deploy | 发布到远程仓库（团队共享） | 远程仓库新版本 |

两个规则决定你敲的命令实际干了什么。规则一：**执行某个 phase 时，它之前的 phase 依次全跑**。`mvn package` 会先 compile、test，再打包；所以"package 跳过了测试"这种说法不对，除非你显式加 `-DskipTests`。规则二：phase 本身不干活，活由绑定到它身上的插件目标（goal）干。日志里 `surefire:3.2.5:test` 的读法是"surefire 插件 3.2.5 版的 test 目标"，绑定到 test phase。

以下是真实执行记录。在 `D:\LinkForge` 之外的一个仓库副本目录里跑 `mvn -DskipTests package`（为什么用副本：原仓库 `target` 下的 jar 正被一个运行中的应用占用，这个真实故障下节细讲），截取关键段，原样粘贴：

```text
[INFO] --- compiler:3.13.0:compile (default-compile) @ linkforge ---
[INFO] Nothing to compile - all classes are up to date.
[INFO] --- surefire:3.2.5:test (default-test) @ linkforge ---
[INFO] Tests are skipped.
[INFO] --- jar:3.4.2:jar (default-jar) @ linkforge ---
[INFO] Building jar: D:\tmp-lfbuild\target\linkforge-2.0.0.jar
[INFO] --- spring-boot:3.3.5:repackage (repackage) @ linkforge ---
[INFO] Replacing main artifact D:\tmp-lfbuild\target\linkforge-2.0.0.jar with repackaged archive, adding nested dependencies in BOOT-INF/.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  4.929 s
```

逐段解释：

- `compiler:3.13.0:compile` 绑定在 compile phase。"Nothing to compile"是因为上次构建后源码没变，增量编译直接跳过。想强制全量，用 `mvn clean package`，clean 生命周期先删掉 `target`。
- `Tests are skipped.` 是 `-DskipTests` 的效果，test phase 还是走了（surefire 被调用），只是不执行测试。这是它和"跳过 test phase"的细微区别。
- `jar:3.4.2:jar` 打出 262 KB 的瘦 jar：只有本项目自己的 class。
- `repackage` 是 Spring Boot 插件的目标，把瘦 jar 替换成可执行 fat jar：你的类进 `BOOT-INF/classes/`，全部依赖 jar 进 `BOOT-INF/lib/`。原瘦 jar 被改名为 `.jar.original` 留底。本仓库 `target` 里能看到实物证据：`linkforge-1.0.0.jar` 约 61 MB（fat），同名的 `.original` 只有 261 KB（瘦）。

`mvn verify` 与 `mvn package` 的差别就在 verify 这个 phase：本项目把 Failsafe（跑 `*IT` 集成测试，需要 Testcontainers 起 Docker 容器）绑定在 verify 上。所以 CI 跑 `mvn -B verify` 而本地日常用 package，两者之间隔着整个集成测试环节。`-B` 是 batch 模式，关闭下载进度条，CI 日志更干净。

### 一个真实构建失败：报错要读到最后

在同一台机器的原仓库目录直接跑 `mvn -q -DskipTests package`，真实输出（原样粘贴）：

```text
[ERROR] Failed to execute goal org.springframework.boot:spring-boot-maven-plugin:3.3.5:repackage (repackage) on project linkforge: Execution repackage of goal org.springframework.boot:spring-boot-maven-plugin:3.3.5:repackage failed: Unable to rename 'D:\LinkForge\target\linkforge-2.0.0.jar' to 'D:\LinkForge\target\linkforge-2.0.0.jar.original' -> [Help 1]
```

这次运行还揭示了 `-q`（quiet）的语义：`-q` 下 INFO 全部静默，成功时一个字都不打印，只有 WARN 和 ERROR 会出来。想看过程就别加 `-q`，想要干净的"只有出错才出声"就加。

读这条报错：goal 是 repackage，动作是 rename，失败对象是 `target\linkforge-2.0.0.jar`。在 Windows 上，一个文件被进程打开时，改名会失败。谁打开着它？用 JDK 自带的 jps 查（真实输出）：

```text
$ jps -l
30352 c:\Users\1\.cursor\...\org.eclipse.equinox.launcher_...jar
24436 c:\Users\1\.cursor\...\spring-boot-language-server-...jar
18920 target/linkforge-2.0.0.jar
36600 -- process information unavailable
7500 jdk.jcmd/sun.tools.jps.Jps
```

PID 18920 正运行着 `target/linkforge-2.0.0.jar`：应用还在跑，jar 被它锁着，repackage 自然改不了名。处理办法是先停掉应用再构建。（诊断视角比命令本身更重要：报错说"改名失败"，第一反应应是"谁占着这个文件"，而不是重试或删 target。）

### Lombok：编译期多出来的一道工序

`src/main/java/com/linkforge/user/entity/UserAccount.java` 的源码里没有任何 getter，只有一个类级注解 `@Data`。但用 javap 反编译编译产物（真实输出）：

```text
$ javap -cp target/classes com.linkforge.user.entity.UserAccount
Compiled from "UserAccount.java"
public class com.linkforge.user.entity.UserAccount {
  public java.lang.Long getId();
  public java.lang.String getUsername();
  public void setUsername(java.lang.String);
  ...
}
```

字节码里有完整的一套 getter 和 setter。生成发生在编译期：javac 编译时除了逐个处理注解，还会调用 classpath 上声明的"注解处理器"（实现特定接口、在编译期运行的程序）。Lombok 就是一个注解处理器，它在 javac 解析出语法树之后直接修改语法树，把 getter、setter、`equals`、`toString` 的定义插进去，再走后续的生成字节码流程。所以源文件里看不到这些方法，class 文件里全都有。它与 Spring 无关，纯编译期行为；注解与反射的系统讲法见 `10-07-annotations-reflection-proxy.md`。

一个必须知道的配置陷阱。本项目没有写过 `annotationProcessorPaths`（实测：Boot 3.3.5 parent 全文没有这个词），Lombok 能生效是因为 javac 默认会扫描编译 classpath 上的处理器。一旦你在 `maven-compiler-plugin` 里配置了 `<annotationProcessorPaths>`（典型场景是引入 MapStruct 这类处理器），classpath 扫描就被关闭，**必须在里面同时列出 lombok**，否则所有 `@Data` 静默失效，编译报 `cannot find symbol: method getUsername()`，而你会盯着明明存在的注解百思不解。

### mvnw 与本机环境：命令从哪来

`mvnw`（Maven Wrapper）是随仓库分发的一对启动脚本（`mvnw`、`mvnw.cmd`）加 `.mvn/wrapper/` 下的引导 jar。它第一次运行时按 `maven-wrapper.properties` 里固定的版本号下载 Maven 本体。目的：所有人（包括 CI）用同一个 Maven 版本，新成员 clone 后不需要预先安装 Maven。

本项目**没有**使用 wrapper：仓库根目录没有 `mvnw` 也没有 `.mvn` 目录（`.gitignore` 里那条 `!.mvn/wrapper/maven-wrapper.jar` 是历史遗留的规则，对应的目录并不存在）。所以本机构建依赖安装好的 Maven。`mvn -v` 的真实输出：

```text
Apache Maven 3.9.9 (8e8579a9e76f7d015ee5ec7bfcdc97d260186937)
Maven home: D:\tools\apache-maven-3.9.9
Java version: 21.0.10, vendor: Oracle Corporation, runtime: D:\Java\jdk-21
Default locale: zh_CN, platform encoding: UTF-8
OS name: "windows 11", version: "10.0", arch: "amd64", family: "windows"
```

两个信息：Maven 装在 `D:\tools\apache-maven-3.9.9`；它自己跑在 JDK 21 上（Maven 需要一个 JDK 才能启动），但项目编译仍按 17 走——上面讲过的 `maven.compiler.release` 映射在起作用。如果终端里敲 `mvn` 提示找不到命令，说明它不在 PATH，用完整路径调用 `D:/tools/apache-maven-3.9.9/bin/mvn.cmd`（Git Bash 里正斜杠写法可直接用）。本仓库的 `scripts/migrate.sh` 也预留了同样的口子：`MVN="${MVN:-mvn}"`，即用环境变量 `MVN` 指定 Maven 可执行文件。

## Git：三棵树与提交链

### 核心模型：任何命令都在动三棵树之一

先遇到问题：你改了三个文件，其中两个想提交，一个想丢弃。`git commit -a` 一刀切，做不到。

Git 内部有三份数据，俗称三棵树：

1. **工作区**（working tree）：你眼睛看到的、编辑器里打开的文件，就是磁盘上的那个目录。
2. **暂存区**（index / stage）：一个中间清单，记录"下一次提交准备放进哪些内容"。`git add` 做的事就是把工作区的当前内容登记进去。
3. **历史区**（HEAD 及其链上的提交）：已经固化的一次次快照。HEAD 是"当前所在提交"的指针。

日常命令全部可以翻译成"在树之间搬东西"：

| 命令 | 动作 | 搬运方向 |
|------|------|---------|
| `git add <file>` | 登记改动，准备提交 | 工作区 → 暂存区 |
| `git commit` | 把暂存区整体固化为一个提交 | 暂存区 → 历史区 |
| `git restore <file>`（旧写法 `git checkout -- <file>`） | 丢弃工作区未提交改动 | 暂存区覆盖工作区，改动不可恢复 |
| `git restore --staged <file>` | 取消暂存但保留工作区内容 | HEAD 覆盖暂存区 |
| `git diff` | 看差异 | 工作区 vs 暂存区 |
| `git diff --staged` | 看差异 | 暂存区 vs HEAD |

有了这张表，回到开头的问题：挑两个文件 `git add`，另一个不动，`git commit` 只会收进前两个。暂存区存在的意义就是"提交可以挑选内容，而不是整目录快进"。

本仓库当前的真实状态（`git status`，原样粘贴）：

```text
On branch main
Your branch is up to date with 'origin/main'.

Changes not staged for commit:
  (use "git add <file>..." to update what will be committed)
  (use "git restore <file>..." to discard changes in working directory)
	modified:   .gitignore
	modified:   README.md
	modified:   docker-compose.yml

Untracked files:
  (use "git add <file>..." to include in what will be committed)
	docs/learning/

no changes added to commit (use "git add" and/or "git commit -a")
```

用三棵树读它：status 输出的每个分区就是一次树与树的比较。"Changes not staged"是工作区对暂存区说"这三个文件我比你新"；"Untracked"是"这些路径你在暂存区里根本没有"。当前没有任何已暂存内容，所以没有 "Changes to be committed" 分区。

`git diff --stat` 看工作区相对暂存区改了多少（真实输出）：

```text
 .gitignore         |   3 ++
 README.md          | 126 ++++++++++++++++++++++++++++++++++++++++++-----------
 docker-compose.yml |   2 +-
 3 files changed, 104 insertions(+), 27 deletions(-)
```

每行是"文件名 | 变更可视化（+ 的个数正比于新增行数） | 增删汇总"。

### 提交到底是什么：对象模型一瞥

commit 不存"差异"，存快照。每个提交对象包含：指向一个根目录树（tree）的引用、零或多个父提交、作者、时间、消息。用 `git cat-file -p HEAD` 直接看当前提交的原始内容（真实输出）：

```text
tree 36d3dc31d9d9f8aa62df9b282a0ec056b4e01886
parent 49fe6af5ab0f6763ec548727bdfa3ee05319663d
author NorthAdb <1328704940@qq.com> 1792915796 +0800
committer NorthAdb <1328704940@qq.com> 1792915796 +0800

docs(v2): refresh resume.md to the 2.0.0 story
```

tree 指向的又是一层层的子 tree 和 blob（blob 就是文件内容本体）。`git ls-tree HEAD pom.xml` 证明 pom.xml 在这个提交里对应一个 blob（真实输出）：

```text
100644 blob 1fecbdce4b196b9dfad0cedea1a5bee491c69530	pom.xml
```

把这些概念串起来：**提交 = 一棵完整快照树 + 父指针 + 说明**；提交链就是靠 parent 字段串起来的单链表；**分支只是一个指向某次提交的、可以移动的指针**，`git branch feat/x` 的成本是写一个 41 字节的文件，所以"开分支很贵"是 SVN 时代的记忆，在 Git 上不成立。tag 是不可动的指针。

merge 与 rebase 是把两条提交链汇合的两种方式。merge 造一个有两个父提交的新提交，两条链的原始历史都保留；rebase 把自己分支上的提交逐个"重放"到目标分支末梢，生成内容相同但散列值不同的新提交，历史变成一条直线。代价因此而来：rebase 改写了原提交的身份，别人若已基于旧提交工作，同步时会撞车，所以**共享分支禁止 rebase**。本项目分支合并用 merge，`git log --graph --oneline -8` 的真实输出能直接看到形状（`|\` 那段是一个 merge 提交分出的叉）：

```text
* fe0a704 docs(v2): refresh resume.md to the 2.0.0 story
* 49fe6af docs(v2): re-cut record — review flags, scripts fix run, header tip points at re-tagged v2.0.0
* 279c32e fix(v2): scripts resolve the newest built jar instead of hardcoding 1.0.0
* 623d1e1 docs(v2): pin final tip — merge 5e776fd, 211 green, branch 40172677345 + merge 40172956164; release tag v2.0.0
*   5e776fd Merge feat/v2-release: v2.0.0 — audit 4 nits fixed (pin run id / budget wording / GatePagesTest / scrape-token note), README rewritten, backlog consolidated, pom 2.0.0 (211 green, run 40172677345)
|\
| * 6cfb9d3 release(v2): 2.0.0 — pom bump, OpenAPI version IT, README rewritten
| * 319d1d4 docs(v2): audit #1/#4 wording fixes, consolidated backlog, v2.0.0 round chapter
| * 92afdbf test(v2): GatePagesTest — direct escaping proof for warn/unlock/block pages
|/
```

读法：每行一个提交，短串是散列值前缀；`*` 在同一条竖线上表示同一分支的连续提交；`|\` 到 `|/` 之间是分支 `feat/v2-release` 上的工作，最后被 merge 汇回主线。

### 本项目的协作纪律：约定与 git 规则要分开

先看事实。`git branch -a` 节选（真实输出，本地分支就有 20 多个）：

```text
  feat/deepen-a6-flyway
  feat/deepen-a7-outbox
  feat/deepen-a9-bloom
  feat/p0-hygiene
  feat/p1-links
  feat/p2-async-stats
  feat/rev2-lifecycle
* main
  release/v1.0.0
  remotes/origin/main
```

规律：功能在 `feat/*` 分支上开发，阶段完成后 merge 回 main（上面的 `5e776fd Merge feat/v2-release`）。

要如实区分两类规则。**git 本身只有机制**：分支、指针、合并，它不禁止你直接往 main 提交，也不强制开 PR（pull request 是 GitHub 等托管平台的评审功能，不是 git 命令）。**"分支开发、main 恒绿、提交讲为什么"是团队约定**，约定的执行者是 CI。`.github/workflows/ci.yml` 的触发段（真实配置）：

```yaml
on:
  push:
    branches: [main, 'feat/**', 'fix/**']
  pull_request:
```

即 main 直推和分支 push、PR 三种入口都会触发构建。main 一旦红了，约定要求立即修复或回滚。这就是"直推 + CI 守绿"模式：git 给了你能力，CI 给了你反馈，纪律让人对反馈负责。

提交信息的约定同样如此。看真实提交 `279c32e` 的全文（`git show --stat`，原样粘贴）：

```text
commit 279c32e4d93f9356115270dcf5c363d1cf3e8a4d
Author: NorthAdb <1328704940@qq.com>
Date:   Sun Oct 25 15:51:00 2026 +0800

    fix(v2): scripts resolve the newest built jar instead of hardcoding 1.0.0

    Cross-model review of the release round caught bench.sh and
    outbox-drill.sh still pointing at target/linkforge-1.0.0.jar — both
    fail on a v2.0.0 checkout (bench.sh's guard aborts outright). Both now
    glob target/linkforge-*.jar and pick the version-sort newest, so the
    next bump cannot break them again. progress.md keeps its two
    historical mentions of the 1.0.0 jar as snapshots.

 scripts/bench.sh        | 6 ++++--
 scripts/outbox-drill.sh | 4 ++--
 2 files changed, 6 insertions(+), 4 deletions(-)
```

标题说"改了什么"，正文说"为什么改、为什么这样改是对的、边界在哪"。`--stat` 的 diff 摘要只告诉你两个文件变了 6 行 4 行，半年后能帮你判断要不要回溯这次改动的，是正文那几句话。

### .gitignore：忽略规则是仓库的一部分

`.gitignore` 的语义：匹配到的**未跟踪**路径，git 不再提示你提交。两条边界必须记住：

1. 它管不住已经跟踪的文件。文件先被提交、后被 ignore，它照样跟着你走。要真移除，用 `git rm --cached <file>`（从暂存区删除但保留磁盘文件）再提交。
2. ignore 是提交进仓库的公共文件，对所有人生效；个人想本地忽略某文件，用 `.git/info/exclude`，不污染公共规则。

本项目 `.gitignore` 的真实内容（节选，含项目自定义段）：

```text
target/
*.class
*.log
.env
*.pem
*.p12
*.jks
记录追踪/
.audit/
```

四类永不入库的东西：构建产物（`target/`，可随时重新生成）、密钥（`.env`、证书）、日志、以及协作者的本地工作目录（`记录追踪/`、`.audit/`）。密钥那条最没有商量余地：文件删了，历史里还在，翻旧提交就能拿到，等于永久泄露；正确处理是立刻换密钥，再考虑重写历史。

### 后悔药：按"想撤销什么"选命令

| 你想撤销的事 | 命令 | 按三棵树解释 |
|-------------|------|-------------|
| 丢弃工作区某个文件的未提交改动 | `git restore <file>` | 暂存区内容覆盖工作区，改动找不回 |
| 撤销最近一次提交，但想留着改动重新提交 | `git reset --soft HEAD~1` | 只把分支指针后移一格，暂存区和工作区不动 |
| 提交完了才发现该提交不该存在，且已 push | 不要 rebase 共享历史；用 `git revert` 生成一个反向提交 | 历史区追加，不改写 |
| 找回"删没了"的提交 | `git reflog` 查到散列后 `git reset --hard <hash>` | reflog 记录指针的所有移动，提交本体其实还在 |

最后一行的原理值得说一句：`git reset --hard` 或删分支只是移动指针，提交对象和 blob 还在对象库里，靠 reflog 能按图索骥，直到过期被垃圾回收。所以"git 删掉的提交"大多没真丢，慌的时候先跑 reflog。

## Linux 排查：每条命令回答一个问题

背命令清单没有用，有用的是"遇到什么症状，问什么问题，哪条命令回答它"。下面的"本机等价"列针对 Windows 读者：Git Bash 自带其中一部分，其余给出 Windows 原生替代。

| 问题 | Linux 命令 | 本机等价（Windows / Git Bash） |
|------|-----------|------------------------------|
| 谁在吃 CPU 和内存？ | `top`（P 按内存排序进程） | 无直接等价，用任务管理器；Git Bash 的 `ps` 只能看到本会话进程 |
| 进程活着吗、启动参数是什么？ | `ps -ef \| grep java` | `tasklist`；Java 进程用 `jps -l` 更准 |
| 失控进程怎么杀？ | `kill -9 <pid>` | `taskkill //PID <pid> //F`（Git Bash 双斜杠转义） |
| 这个字符串在哪些文件哪些行？ | `grep -rn "关键词" 目录` | Git Bash 自带 grep，命令相同 |
| 这个端口被谁监听？ | `ss -tlnp`（或旧式 `netstat -tlnp`） | `netstat -ano` 再拿 PID 查 `tasklist` |
| 这个 HTTP 请求到底发了什么、回了什么？ | `curl -v` | Git Bash 是真 curl；PowerShell 里 `curl` 是 `Invoke-WebRequest` 的别名，要写 `curl.exe` |
| 日志文件新增了什么？ | `tail -f app.log` | Git Bash 自带 tail |
| 服务为什么没起来？ | `systemctl status <名>`，日志看 `journalctl -u <名> -f` | Windows 服务用 `sc query <名>`；journalctl 无等价，读事件查看器 |
| 磁盘还剩多少、目录多大？ | `df -h`、`du -sh 目录` | 无直接等价，用资源管理器属性 |

两列展开讲。

**grep 是查代码的主力**。`-r` 递归目录，`-n` 带行号。在仓库根目录真实执行：

```bash
$ grep -rn "class GlobalExceptionHandler" src/main/java
src/main/java\com\linkforge\common\exception\GlobalExceptionHandler.java:16:public class GlobalExceptionHandler {
```

一条命令回答了三个问题：文件在哪、第几行、那一行原文是什么。（输出里路径分隔符是反斜杠，这是 Git Bash 在 Windows 上调用 grep 打印路径的原生行为。）

**curl -v 是 HTTP 的透视镜**。对运行中的本机应用真实执行 `curl -sv http://localhost:8080/api/health`（节选，`*` 开头是 curl 的过程注释，`>` 是发出的请求，`<` 是收到的响应）：

```text
*   Trying [::1]:8080...
* Established connection to localhost (::1 port 8080) from ::1 port 64413
> GET /api/health HTTP/1.1
> Host: localhost:8080
> User-Agent: curl/8.19.0
< HTTP/1.1 200
< Content-Type: application/json
< Transfer-Encoding: chunked
```

它一次回答了：连上了没、发了什么方法与路径、回的状态码与头。项目里 `scripts/smoke.sh` 第 44 行用它拿响应头做断言：`curl -s -D - -o /dev/null URL`，`-D -` 把响应头打到标准输出，`-o /dev/null` 丢弃响应体，然后 grep 302 和 Location。

**sed 与 awk 一句级够用**。真实用例在 `scripts/smoke.sh` 第 18 行，从一个 JSON 响应里抠出 token 字符串：

```bash
json_str() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | sed "s/\"$1\":\"//;s/\"$//"; }
```

三段流水线：`grep -o` 只输出匹配片段（字段名加值的原文），`head -1` 取第一条，`sed` 两步替换把前缀 `"字段名":"` 和结尾引号削掉。sed 管单行替换，awk 管按列取数（如 `ps | awk '{print $2}'` 取第二列），各学一句就覆盖日常。

**systemctl 与 journalctl 是生产环境的服务入口**。Linux 服务器上 Java 应用通常注册成 systemd 服务：`systemctl status linkforge` 看它活没活、最近为什么退出；`journalctl -u linkforge -f` 等价于对应用日志做 `tail -f`。本机 Windows 没有它们，但读生产日志、答面试都绕不开，记住"status 看状态、journalctl 看日志"的分工即可。

## JDK 自带诊断工具：先知道何时想到它

JDK 的 bin 目录下有一组免安装的诊断工具，排查线上问题时最先想到的应该是它们而不是日志：

- `jps -l`：列出本机所有 Java 进程及其主类或 jar 名。何时想到：想确认"应用到底起没起来"、"哪个 jar 正被哪个进程运行"（上一节 Maven 报错就是用它定位的）。
- `jstat -gcutil <pid> 1000`：每秒打印一次各代内存与 GC 占比。何时想到：接口间歇性变慢，怀疑 GC 停顿。
- `jstack <pid>`：抓线程栈快照。何时想到：请求卡住不返回，怀疑死锁或线程池耗尽，看栈里线程卡在哪个方法。
- `jmap`：堆直方图（`-histo`）或完整堆转储。何时想到：内存持续上涨不回落，找哪类对象在膨胀。

这四个工具的原理、输出怎么读、与内存模型的关系，集中在 [12-jvm.md](12-jvm.md)，这里只建立"症状到工具"的映射。

## 项目案例：CI 与 scripts 的构建流程

### CI 每一步在干什么

`.github/workflows/ci.yml` 全文很短，逐条讲（真实文件）：

```yaml
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

- `actions/checkout@v4`：runner 是一台干净的虚拟机，这步把仓库代码拉下来。
- `setup-java` 装 Temurin 发行版的 JDK 17，与 `pom.xml` 的 `java.version=17` 对齐。本地你用 JDK 21 跑 Maven 没关系，CI 钉死 17，消除"本地能跑 CI 挂"的一个变量。
- `cache` 缓存本地仓库目录 `~/.m2/repository`，缓存键含 `pom.xml` 的散列值：pom 变了键就变，自动重建缓存。没有它，每次构建都重新下载 168 个 jar。
- `mvn -B verify`：单测 + 打包 + Failsafe 集成测试。Testcontainers 要起 Docker 容器，runner 是 Linux 自带 Docker，所以集成测试在 CI 能跑全。

### scripts 目录：五件套与一条工程教训

`scripts/` 下五个脚本（均已读原文，概述其职责）：

- `scripts/smoke.sh`：冒烟测试。health → 登录拿 token → 建链 → 302 跳转断言 Location → Redis 缓存行 → 开放注册 → 建链改链禁用全生命周期，最后打 `SMOKE OK`。只用 bash、curl、grep、sed，不依赖 jq。
- `scripts/mq-drill.sh`：MQ 故障演练。健康基线 → 停 RabbitMQ，断言跳转仍是 302 → 恢复，断言统计追平，最后 `DRILL OK`。
- `scripts/outbox-drill.sh`：同步双写关闭加 MQ 中断，断言点击明细经 outbox 表 PENDING 后补齐。证明"MQ 断了数据不丢"这个口头断言。
- `scripts/bench.sh`：用 wrk 压测跳转路径的三个场景（全开、关异步、关同步），脚本自己起停应用。
- `scripts/migrate.sh`：对既有数据库驱动 Flyway 迁移。从 `.env` 读连接参数（要求先 `cp .env.example .env`），然后调用 `mvn ... flyway:migrate` 并把参数通过 `-Dflyway.url` 等传给插件。构建工具的属性机制在这里被当成了运维入口。

工程教训藏在 jar 的引用方式里（`scripts/bench.sh` 第 18 行，真实代码）：

```bash
JAR="$(ls -1 target/linkforge-*.jar 2>/dev/null | sort -V | tail -1)"
```

脚本不写死 `linkforge-1.0.0.jar`，而是按版本号排序取最新的那个。它对应的正是上文提交 `279c32e` 的修复：早期脚本写死了 1.0.0，pom 升到 2.0.0 后脚本静默失灵。原则：**脚本引用派生物时，引用查找规则而不是具体值**。你可以在自己机器上验证这条规则的效果——当前 `target/` 里同时躺着 0.0.1-SNAPSHOT、1.0.0、2.0.0 三代 jar，脚本每次都能挑对。

## 常见错误与误区

- **"`mvn package` 就等于 CI"**。package 只到打包为止，verify 里的 Failsafe 集成测试一步没跑。CI 跑 verify，本地就该跑 verify，否则集成测试的问题留给 CI 帮你发现，反馈周期翻倍。
- **"依赖升级就是改个版本号"**。版本可能被 BOM 或 dependencyManagement 管着，改了属性却不生效；也可能像 commons-lang3 一样牵动别的消费方。动手前看 `dependency:tree`，动手后再看一遍。
- **"`git status` 很干净就安全"**。干净只说明三棵树一致，不代表没把密钥提交过。`.gitignore` 对已跟踪文件无效，被提交过的 `.env` 删掉后历史里仍在。
- **"rebase 冲突是灾难"**。冲突标记只是把两边的改动并排摆出来，读懂两边意图再选。真正让冲突变少的是小步提交，而不是躲开 rebase；同时守住"共享分支不 rebase"。
- **"报错先重试"**。Maven 的 rename 失败、端口占用、文件锁，重试十次也是同一个错。先读错误信息里的动作和对象，再查"谁占着它"。

## 自测

1. `mvn test` 与 `mvn verify` 差在哪？为什么 CI 用后者？
   答：Maven 按 phase 顺序执行，verify 在 package 之后，多了绑定在该 phase 的 Failsafe 集成测试（Testcontainers 起真容器）与结果校验；`mvn -B verify` 才等价于 CI 的完整验证。
2. 依赖树里 guava 带来 `error_prone_annotations:2.36.0`，caffeine 带来 `2.21.1`，最终用哪个？依据什么规则？
   答：用 2.36.0。Maven 按 nearest-wins 调解：两者深度相同（都在第 2 层）时，先被声明的 guava 胜出，caffeine 那份被标记 `omitted for conflict`。
3. `git status` 里 "Changes not staged for commit" 比较的是哪两棵树？"Untracked files" 呢？
   答：前者是工作区与暂存区的差异；后者是工作区与暂存区比、暂存区里完全没有登记的路径。status 的每个分区就是一次树间比较。
4. 本项目的 Lombok 没配 `annotationProcessorPaths`，为什么 getter 还是生成了？什么时候必须配？
   答：javac 默认扫描编译 classpath 上的注解处理器，Lombok 就在依赖里，所以生效；一旦你为别的处理器（如 MapStruct）显式配置 `annotationProcessorPaths`，classpath 扫描被关闭，必须把 lombok 一并列入，否则 `@Data` 静默失效报 `cannot find symbol`。
5. `.env` 已经写进 `.gitignore`，但它上个月被提交过一次，现在还安全吗？
   答：不安全。ignore 只对未跟踪文件生效，历史提交里仍有完整内容，翻历史即可取得密钥；正确做法是立刻更换全部凭据，必要时重写历史，而不是只删文件。
6. `mvn package` 报 `Unable to rename ...linkforge-2.0.0.jar`，第一条排查命令是什么？
   答：`jps -l`。Windows 上运行中的 jar 被进程锁定，repackage 无法改名；jps 列出 Java 进程即可看到是谁在跑这个 jar，停掉它再构建。

## 下一步

- 测试分层、Testcontainers 与红先纪律的完整体系见 [60-testing-ci.md](60-testing-ci.md)。
- Docker、Compose 与部署见 [61-docker-deploy.md](61-docker-deploy.md)。
- JVM 结构、GC 与诊断工具的深讲见 [12-jvm.md](12-jvm.md)；注解处理器的语言机制见 `10-07-annotations-reflection-proxy.md`。
- 依赖注入在 Spring 里如何装配见 [30-spring-core.md](30-spring-core.md)。
- 操作系统层面的进程、内存与网络基础见 [21-os.md](21-os.md)。
