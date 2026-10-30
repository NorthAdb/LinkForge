# Docker、部署与可观测性

优先级 P1，目标层级 L2（读懂并操作本项目的部署与观测栈），指标卫生与失败模式到 L3。

> 实测声明：本文所有 Docker 与 HTTP 输出都是本机真实执行的结果（写作环境：Docker Desktop 29.9.2，引擎可用）。唯一例外是 Dockerfile 示例：项目里没有这个文件，第 4 节那段标了「示意，未实测」。本机 Docker 与项目 Testcontainers 的组合不兼容，本机跑不了依赖 Testcontainers 的集成测试；CI 的 ubuntu 原生 Docker 没有这个问题，两边差别的来龙去脉见 [60-testing-ci.md](60-testing-ci.md)。

## 学习目标

- 讲清「项目从 jar 到可访问的服务」中间发生了什么：谁起进程、进程读什么配置、依赖从哪来、端口谁在听。
- 逐行读懂 `docker-compose.yml`：镜像、端口、卷、环境变量、健康检查、profile。
- 看懂应用暴露的观测端点：`/actuator/health` 和 `/actuator/prometheus` 各自给什么数据、谁有权限读。
- 解释指标基数为什么是监控系统最大的坑，以及项目用什么手段把它按住。

## 前置知识

- [21-os.md](21-os.md)：进程、线程、namespace 与 cgroup。容器就是靠这两层内核机制做出来的，本文只借用结论。
- [12-jvm.md](12-jvm.md)：JVM 内存分区。容器内存限额与 JVM 参数的关系本文只讲一句，细节在那边。
- [50-redis.md](50-redis.md)、[51-rabbitmq-outbox.md](51-rabbitmq-outbox.md)：Redis 和 RabbitMQ 本身各有完整一章。本文只把它们当作「容器装着的两个中间件」。

## 先遇到问题：从 jar 到能访问的服务，中间发生了什么

场景：你刚执行完 `mvn -DskipTests package`，`target/` 下躺着一个 jar。浏览器打开 `http://localhost:8080/r/abc` 就能跳转。中间发生了什么？一步步拆。

第一步，Maven 打包。`pom.xml` 里配了 `spring-boot-maven-plugin`。打包产物分两种形态，本机 `target/` 里恰好两种都有，可以直接对比：

```text
$ ls -la target/*.jar   （截取）
-rw-r--r-- 1 1 197609 61216409 Oct 25 12:43 target/linkforge-1.0.0.jar
-rw-r--r-- 1 1 197609   262100 Oct 29 01:09 target/linkforge-2.0.0.jar
```

61MB 和 262KB，差两百多倍。用 python 看两个 jar 的内部结构（只读操作）：

```text
target/linkforge-1.0.0.jar | BOOT-INF/lib jars: 114
  Main-Class: org.springframework.boot.loader.launch.JarLauncher
  Start-Class: com.linkforge.LinkForgeApplication
target/linkforge-2.0.0.jar | BOOT-INF/lib jars: 0 | （无 Main-Class）
```

大的那个是 Spring Boot 打包插件 repackage 之后的「fat jar」：项目自己的 class 加上全部 114 个依赖 jar（Spring、Tomcat、MySQL 驱动……）一起装进 `BOOT-INF/lib/`，manifest 里的 `Main-Class` 指向 Spring Boot 的启动器 `JarLauncher`，它负责从 jar 里面再加载那些嵌套的依赖。小的那个是没走 repackage 的普通 jar：没有 `Main-Class`，`java -jar` 会直接拒绝启动它，因为 JVM 找不到入口。所以「打出一个能跑的 jar」本身就是打包插件做的工作，不是 `javac` 白送的。

第二步，`java -jar target/linkforge-1.0.0.jar`。JVM 读 manifest 找到 `JarLauncher`，Spring Boot 启动，起一个**内嵌的 Tomcat**。你不需要单独安装 Tomcat：Web 服务器本身就是一个依赖包，装在 jar 里随应用一起启动。

第三步，进程读配置。来源是 `src/main/resources/application.yml` 加进程环境变量，环境变量优先。yml 里到处是这种写法：

```yaml
# 片段（src/main/resources/application.yml）
server:
  port: ${SERVER_PORT:8080}
```

`${SERVER_PORT:8080}` 的意思是：环境变量 `SERVER_PORT` 有值就用它，没有就用冒号后面的默认值 8080。

第四步，连依赖。MySQL 在 `localhost:3306`，Redis 在 `localhost:6379`，RabbitMQ 在 `localhost:5672`。这三个端口是三个 Docker 容器**发布到宿主机**的（第 5 节讲怎么发布的）。对宿主机上的 Java 进程来说，它们看起来就像装在本机的三个普通软件。

第五步，Flyway 建表。`application.yml` 里 `flyway.enabled: true`，应用启动时检查数据库里有没有表、缺哪段迁移就补哪段。表结构跟着代码走，不靠手工执行 SQL 脚本。

第六步，就绪。Tomcat 开始监听 8080 端口，`src/main/java/com/linkforge/link/controller/HealthController.java` 声明的 `/api/health` 能回应，服务才算「可访问」。本文写作时实测：

```text
$ curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/health
200
```

把六步收成一句话：**jar 是打包形态，进程是运行形态，服务是进程连上依赖并监听端口之后的状态**。Docker 在这个故事里负责第四步的三个依赖，不负责应用本身——为什么这么选，第 6 节如实分析。

## 镜像与容器：分层模板和受限进程

### 先遇到问题：在我机器上是好的，到测试机上就不行

没有容器之前，换一台机器跑同一个应用要重新装 JDK、MySQL、Redis，版本对不上、路径不一样、环境变量忘配，每一项都能让「在我机器上是好的」变成别人的三天排查。Docker 的解法是把「应用加依赖加运行环境」整体打成标准件，到哪台装了 Docker 的机器上都以同样的方式跑起来。

### 镜像是什么：分层的只读模板

**镜像**（image）是一个只读的打包产物，内容是文件系统快照加启动配置。它由一层层「层」（layer）叠出来，每一层对应 Dockerfile 里的一条指令。本机实测（`docker history` 列出一个镜像的层）：

```text
$ docker history redis:7-alpine   （截取前几行）
IMAGE          CREATED       CREATED BY                                       SIZE
858f009f9709   5 weeks ago   CMD ["redis-server"]                             0B
<missing>      5 weeks ago   EXPOSE map[6379/tcp:{}]                          0B
<missing>      5 weeks ago   ENTRYPOINT ["docker-entrypoint.sh"]              0B
<missing>      5 weeks ago   COPY docker-entrypoint.sh /usr/local/bin/ # …   20.5kB
<missing>      5 weeks ago   WORKDIR /data                                    4.1kB
<missing>      5 weeks ago   RUN |2 REDIS_DOWNLOAD_URL=https://github.com…   8.19kB
<missing>      5 weeks ago   RUN |2 REDIS_DOWNLOAD_URL=https://github.com…   30.8MB
```

每一行就是一条历史指令产生的层。`CMD`、`EXPOSE` 这种只写元数据的指令产生 0 字节的层；`RUN`、`COPY` 这种改文件系统的指令产生带内容的层。

比喻：镜像像 Java 里的类定义，容器像用这个类 new 出来的对象。比喻的失效处：对象之间不共享内存结构，而多个容器**共享**同一套镜像层（只读），各自只在最上面挂一个很薄的可写层；删掉容器，可写层没了，镜像原封不动。

### 容器是什么：镜像加可写层的受限进程

**容器**（container）是「从镜像启动起来的一个运行实例」。本质上是宿主机上的一个普通 Linux 进程，只是被内核套了两层限制（[21-os.md](21-os.md) 讲过）：**namespace** 让它只看见自己的进程号、网络栈和挂载点；**cgroup** 限制它能用多少 CPU 和内存。一句话记住「镜像不是虚拟机」：虚拟机带一整个客户操作系统，容器只隔离一个进程的视图，所有容器共享宿主机内核，所以镜像里不需要内核、启动按毫秒算。

容器和 Java 进程还有一层关系值得记：JVM 在容器里算默认堆大小时，读的是 cgroup 限额而不是宿主机总内存（JDK 10 起默认开启 `UseContainerSupport`）。本项目应用跑在宿主机上没踩过这个坑；哪天把应用塞进容器，第一件要查的就是它。见 [12-jvm.md](12-jvm.md)。

### 层缓存：指令顺序为什么影响构建速度

分层不只是存储结构，还是构建缓存单位。Docker 构建时逐条指令执行，每条指令的结果（层）可以复用：本条指令和它之前的所有指令都没变，就直接用上一次的缓存层，跳过执行。本机镜像列表里能看到两个只有几 MB 的基础镜像（真实输出，截取）：

```text
$ docker images   （截取）
IMAGE                               DISK USAGE
alpine:latest                       13MB
mysql:8.0                           1.1GB
maven:3.9-eclipse-temurin-17        775MB
```

其中多个 alpine 变体镜像共享同一批底层，实际磁盘占用远小于逐个累加。反过来，把「经常变的」放在「很少变的」前面，会把后面所有层的缓存全部打穿——这是第 4 节 Dockerfile 排指令顺序的核心依据。

## Dockerfile：把「怎么跑」写成文本

### 先遇到问题：口头约定不可靠

「先装 JDK 17，再解压 jar，最后 `java -jar` 加这几个参数」——这种步骤写在 wiki 里会过期，写在人脑子里会离职。Dockerfile 把它写成一份可执行的文本，`docker build` 一次，产物处处一致。

### 六条核心指令，各自负责什么

- `FROM`：以哪个镜像为起点。所有 Dockerfile 的第一条。起点可以是裸系统（`alpine`），也可以是带 JDK 的现成镜像（`eclipse-temurin:17-jre`）。
- `COPY`：把宿主机的文件复制进镜像层。产生带内容的层。
- `RUN`：在镜像里执行一条命令（装软件、编译、解压），结果固化成一层。
- `EXPOSE`：声明「这个容器打算监听哪个端口」。它本身**不**发布端口，不写映射就访问不到；作用是给读的人和我下面讲的工具看。真正的端口发布是 compose 或 `docker run -p` 的事。
- `ENTRYPOINT`：容器启动时执行的主命令。写 `ENTRYPOINT ["java", "-jar", "app.jar"]` 后，容器的一生就是这一个进程的一生。
- `CMD`：主命令的默认参数，或没有 `ENTRYPOINT` 时就是主命令本身。关键区别：`docker run` 时命令行上追加的参数会**替换** `CMD`，但只是作为参数传给 `ENTRYPOINT`。所以惯例是 `ENTRYPOINT` 写死程序、`CMD` 写默认参数，`docker run image --verbose` 这种覆盖才有意义。

看本机 `docker history` 的输出对得上：redis 镜像最顶层就是 `ENTRYPOINT ["docker-entrypoint.sh"]` 加 `CMD ["redis-server"]`，所以 `docker run redis:7-alpine` 直接起 Redis 服务，而 `docker run redis:7-alpine redis-cli` 会把 `CMD` 换成 `redis-cli`，起一个客户端。

### multi-stage build：编译环境不进制品

编译一个 Spring Boot 应用需要 Maven 和完整 JDK（本机实测 `maven:3.9-eclipse-temurin-17` 镜像 775MB），但运行只需要一个 JRE 加 fat jar。multi-stage build 在一个 Dockerfile 里写两个 `FROM`：第一阶段用 Maven 镜像编译，第二阶段从零开始只拷贝最终 jar。产物镜像不携带编译器、源码和 Maven 本地仓库，体积和攻击面都小一个量级。

### 本项目的诚实边界：没有 Dockerfile

用 `find . -iname "Dockerfile*"` 全仓库搜过：没有。这是项目的显式选择，不是遗漏（第 6 节分析原因）。如果哪天要容器化应用，最小形态长这样（示意，未实测——本机禁止构建操作，这段只用于读懂结构）：

```dockerfile
# 示意，未实测：项目当前不存在此文件
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/target/linkforge-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

逐条看缓存设计：先只 `COPY pom.xml` 并预拉依赖，再 `COPY src`。改代码不动依赖时，前四层缓存全命中，重新构建只重跑编译。`COPY --from=build` 就是跨阶段拷贝，第二个 `FROM` 之后的镜像里没有 Maven。注意本项目可运行的 jar 就是第 3 节实测的 `JarLauncher` 形态 fat jar（`pom.xml` 的 parent 是 `spring-boot-starter-parent`，打包阶段自动做 repackage），`java -jar` 即可启动。

## Compose：一台机器上的多容器编排

### 先遇到问题：三条 `docker run` 谁记得住

第 3 节的 jar 想连上数据库，数据库本身要一条 `docker run`：端口映射、环境变量、密码、数据卷、字符集参数，七八个选项。再加 Redis 和 RabbitMQ，就是三条几十个选项的命令，每台机器手敲一遍，错一个字母就是一次「在我机器上是好的」。**Compose** 的解法：把「要起哪些容器、各自什么配置」写进一个 yml（`docker-compose.yml`），`docker compose up -d` 一条命令全部拉起，`docker compose down` 全部停掉。声明式的意思是：文件描述的是「最终要有什么」，不是「按什么步骤敲」。

### 网络：容器之间用服务名互访

`docker compose up` 会自动创建一个网络（本机实测存在名为 `linkforge_default` 的网络，项目名取自目录名），所有服务都接进去，并且每个服务可以用**服务名**当域名互相访问。项目里的真实用法在 Grafana 的数据源配置 `ops/observability/grafana/provisioning/datasources/prometheus.yml` 里：

```yaml
datasources:
  - name: Prometheus
    uid: linkforge-prom
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
    editable: false
```

Grafana 容器里访问 `http://prometheus:9090`，`prometheus` 是 compose 文件里的服务名，容器内 DNS 把它解析成那个容器的 IP。宿主机上访问不走服务名，走端口发布。

### docker-compose.yml 逐段读

全文五个服务加一个卷声明块。按段讲。

**mysql 服务**：

```yaml
# 片段（docker-compose.yml）
  mysql:
    image: mysql:8.0
    container_name: linkforge-mysql
    restart: unless-stopped
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:?set MYSQL_ROOT_PASSWORD}
      MYSQL_DATABASE: ${MYSQL_DATABASE:-linkforge}
      MYSQL_USER: ${MYSQL_USER:-linkforge}
      MYSQL_PASSWORD: ${MYSQL_PASSWORD:?set MYSQL_PASSWORD}
    ports:
      - "${MYSQL_PORT:-3306}:3306"
    volumes:
      - linkforge_mysql_data:/var/lib/mysql
```

`image` 指定从哪个镜像起。`container_name` 给容器起固定名，`docker logs linkforge-mysql` 这类命令好打。`restart: unless-stopped` 表示容器崩溃后自动重启，除非你手动停过它。`environment` 是注入容器的环境变量，值里 `${VAR:?...}` 和 `${VAR:-...}` 两种语法的区别下一节专门讲。`ports` 的 `"宿主端口:容器端口"` 把容器端口发布到宿主机——第 3 节里 Java 进程连 `localhost:3306` 的前提。`volumes` 挂命名卷，后面讲。

```yaml
# 片段（docker-compose.yml）
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_unicode_ci
      - --default-authentication-plugin=caching_sha2_password
```

`command` 覆盖镜像的启动参数，这里是给 mysqld 追加三个启动开关：字符集 utf8mb4（与 [40-mysql.md](40-mysql.md) 的排序规则决策呼应），认证插件 `caching_sha2_password`。第三个开关解释了 `application.yml` 里 JDBC URL 末尾那个 `allowPublicKeyRetrieval=true`：MySQL 8 默认的这种认证方式要求客户端额外取一次服务器公钥，JDBC 驱动默认不取，不配这个参数连接会失败。这是「compose 配置与 Java 代码隔着两层还在互相咬合」的一个真实例子。

**redis 和 rabbitmq 服务**：结构相同，各有一个健康检查。RabbitMQ 用 `3.13-management` 这个 tag 的镜像，比普通版多一个 15672 管理界面端口，所以 `ports` 发布了两条（5672 是 AMQP 协议端口，15672 是网页管理台）。Redis 选 `7-alpine` 这种 alpine 变体是为了镜像小（本机实测 57.8MB）。

```yaml
# 片段（docker-compose.yml）
  prometheus:
    image: prom/prometheus:v2.55.1
    profiles: ["observability"]
    extra_hosts:
      - "host.docker.internal:host-gateway"
    ports:
      - "127.0.0.1:9090:9090"
    volumes:
      - ./ops/observability/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - ./ops/observability/scrape-token:/etc/linkforge/scrape-token:ro
      - linkforge_prometheus_data:/prometheus
```

`profiles: ["observability"]` 表示这个服务默认**不启动**，要 `docker compose --profile observability up -d` 才拉起。日常开发不付观测栈的内存开销，要看面板时再开。`extra_hosts` 往容器里写一条 hosts 记录：`host.docker.internal` 指向宿主网关，让容器能访问宿主机上的服务——本容器靠它抓宿主机上跑的 Java 应用，实测验证过配置确实生效：

```text
$ docker inspect linkforge-prometheus --format '{{.HostConfig.ExtraHosts}}'
[host.docker.internal:host-gateway]
```

三个挂载是两种卷的对比教材：前两条是**绑定挂载**（bind mount，宿主机路径冒号容器路径，`:ro` 只读），把仓库里的配置文件原样喂给容器；第三条是**命名卷**，存 Prometheus 自己抓来的时序数据。实测：

```text
$ docker inspect linkforge-prometheus --format '{{range .Mounts}}{{.Source}} -> {{.Destination}}{{"\n"}}{{end}}'
/var/lib/docker/volumes/linkforge_linkforge_prometheus_data/_data -> /prometheus
D:\LinkForge\ops\observability\scrape-token -> /etc/linkforge/scrape-token
D:\LinkForge\ops\observability\prometheus.yml -> /etc/prometheus/prometheus.yml
```

grafana 服务同属 observability profile，管理口令走 `${GRAFANA_ADMIN_PASSWORD:-linkforge-local-admin}`（compose 注释译过来是：这是本机演示默认值，对外暴露前必须覆盖），端口发布成 `127.0.0.1:3001:3000`（宿主 3001 映射容器里的 Grafana 默认端口 3000），并且 `depends_on: [prometheus]`——这是下一节要讲的两种形态里的「列表形态」。

### 健康检查：端口开了不等于服务就绪

先遇到问题：MySQL 容器启动后，3306 端口很快就通了，但 mysqld 还在做初始化，这时候应用连上去就是连接拒绝。进程活着（端口开了）不等于服务就绪。**健康检查**（healthcheck）让 Docker 定期在容器里执行一条命令，命令成功才算 healthy。

三个参数加一个宽限期，作用各不相同：`interval` 每隔多久检查一次；`timeout` 单次检查多久没回应算失败；`retries` 连续失败几次才把状态打成 unhealthy（偶发一次抖动不判死）；`start_period` 是刚启动后的宽限期，宽限期内的失败不计入 retries——给慢启动的进程留时间。

mysql 服务的检查配置实测（用 `docker inspect` 读正在运行的容器，口令字段已脱敏）：

```text
$ docker inspect linkforge-mysql --format '{{json .Config.Healthcheck}}'
{"Test":["CMD","mysqladmin","ping","-h","127.0.0.1","-uroot","-p********"],
 "Interval":10000000000,"Timeout":5000000000,"StartPeriod":30000000000,"Retries":10}
```

Interval 单位是纳秒：10 秒查一次，5 秒超时，30 秒宽限期，连挂 10 次才判 unhealthy。对比 redis 的 `start_period: 5s`：Redis 是单线程内存数据库，秒级就绪；MySQL 要初始化表空间和系统库，慢得多，所以宽限期给得长。**应用启动越慢，start_period 和 retries 越要放宽**，否则容器刚起就被判死、被 restart 策略反复重启，永远到不了就绪。

检查命令本身是 `mysqladmin ping`，即「问 mysqld 一句你活着吗」。真实运行记录里能看到两条信息（截取第一条）：

```text
$ docker inspect linkforge-mysql --format '{{json .State.Health}}'   （截取）
{"Status":"healthy","FailingStreak":0,"Log":[
  {"Start":"2026-10-28T18:24:03.279579672Z","End":"2026-10-28T18:24:03.340380007Z",
   "ExitCode":0,"Output":"mysqladmin: [Warning] Using a password on the command line interface can be insecure.\nmysqld is alive\n"}]}
```

`mysqld is alive` 是检查通过；同时 mysqladmin 抱怨「命令行上带口令不安全」——compose 在检查命令里插值进了 root 口令，代价是口令出现在进程命令行和 inspect 输出里。据配置与实测推断，这是 demo 口径下的已知取舍：检查必须能通过认证，而本机口令本来就是 `.env` 里的占位值。

健康检查配置与否，`docker compose ps`（或 `docker ps`）一眼可见。本机实测：

```text
$ docker ps --format '{{.Names}}  |  {{.Status}}  |  {{.Ports}}'   （截取）
linkforge-mysql      |  Up 2 days (healthy)  |  0.0.0.0:3306->3306/tcp
linkforge-redis      |  Up 2 days (healthy)  |  0.0.0.0:6379->6379/tcp
linkforge-rabbitmq   |  Up 2 days (healthy)  |  0.0.0.0:5672->5672/tcp, 0.0.0.0:15672->15672/tcp
linkforge-prometheus |  Up 2 days            |  127.0.0.1:9090->9090/tcp
linkforge-grafana    |  Up 40 hours          |  127.0.0.1:3001->3000/tcp
```

三个中间件有 `(healthy)`，因为 compose 给它们配了 healthcheck；Prometheus 和 Grafana 只有 `Up`，因为这两个镜像没定义健康检查——`Up` 只说明进程活着，`healthy` 才说明服务就绪。这就是「假活」问题的可见答案。

还有个容易被忽略的对照藏在同一份输出里：中间件端口发布成 `0.0.0.0:3306`（监听所有网卡，局域网能访问），观测端口发布成 `127.0.0.1:9090`（只监听回环，只有本机能访问）。指标端点和面板不该对局域网开放，compose 用发布地址直接把暴露面收住了。

### depends_on 的两种形态

`depends_on` 声明启动顺序，有两种写法。列表形态只保证「先创建对方的容器」，不保证对方**就绪**；映射形态能把条件钉到健康状态上（示意，本项目的 compose 里没有这种用法）：

```yaml
# 示意：若应用也放进 compose，通常这样等依赖真正就绪
  app:
    depends_on:
      mysql:
        condition: service_healthy
```

`condition: service_healthy` 表示：mysql 容器健康检查通过之后才创建 app 容器。这要求被依赖方**配置了健康检查**。本项目 compose 里只有 grafana 用了列表形态的 `depends_on: [prometheus]`；三个中间件之间没有互相依赖，也管不到应用——因为应用根本不在这个 compose 里（第 6 节）。所以健康检查在本项目的作用主要是给人看（`docker compose ps` 等 healthy 再起应用）和给重启策略用，不是给启动顺序用的。

### 环境变量注入与 .env：让配置错误在启动瞬间爆炸

Compose 读同目录的 `.env` 文件来填 `${...}` 占位符。两种语法必须分清：`${VAR:-default}` 在变量缺失时**静默**用默认值；`${VAR:?msg}` 在变量缺失时**报错并拒绝启动**。本项目对三个密码全部用 `:?`。实测（`.env` 确实在本机，为了演示缺失场景把 env 文件临时指到空设备；`config` 子命令只做配置解析不启动任何容器，实测退出码 1）：

```text
$ docker compose --env-file /dev/null config --quiet   （截取）
error while interpolating services.mysql.environment.MYSQL_PASSWORD: required variable MYSQL_PASSWORD is missing a value: set MYSQL_PASSWORD
error while interpolating services.mysql.environment.MYSQL_ROOT_PASSWORD: required variable MYSQL_ROOT_PASSWORD is missing a value: set MYSQL_ROOT_PASSWORD
error while interpolating services.rabbitmq.environment.RABBITMQ_DEFAULT_PASS: required variable RABBITMQ_PASSWORD is missing a value: set RABBITMQ_PASSWORD
```

错误信息里那句 `set MYSQL_PASSWORD` 就是 yml 里写的提示语。如果当初写的是 `:-`，缺失的密码会静默变成空字符串，容器照样起来，问题推迟到应用连库失败才暴露——错误发现得越晚，排查绕得越远。**凭证类配置必须 `:?`，让它在启动瞬间爆炸。**

一个边界要说清：`.env` 只被 Compose 读取，**宿主机上的 `java -jar` 不读它**。那应用怎么拿到同一套密码？两条路：手工把变量导入进程环境；或者像 `scripts/bench.sh` 里那样在脚本内导一遍（真实原句）：

```bash
# 片段（scripts/bench.sh）
set -a; . ./.env; set +a
```

`set -a` 让后续 source 进来的变量自动导出为环境变量，`. ./.env` 把文件内容执行一遍，`set +a` 关掉自动导出。同一个 `.env`，容器走 Compose 的路，宿主进程走 shell 的路。

### 命名卷：数据活得比容器久

容器删掉，可写层就没了。数据库文件写进容器内部等于住在可写层里，`docker compose down` 一敲数据全飞。**命名卷**（named volume）是 Docker 管理的持久化目录，容器挂载它，数据写进卷而不是可写层。mysql 服务把 `/var/lib/mysql` 挂到 `linkforge_mysql_data` 卷上，数据就与容器生死解耦。文件顶部的卷声明块一共五个：三个中间件的数据加 Prometheus、Grafana 的数据。本机实测：

```text
$ docker volume ls   （截取）
local     linkforge_linkforge_grafana_data
local     linkforge_linkforge_mysql_data
local     linkforge_linkforge_prometheus_data
local     linkforge_linkforge_rabbitmq_data
local     linkforge_linkforge_redis_data
```

名字是「项目名加卷名」拼出来的：目录名 `linkforge` 加上卷名 `linkforge_mysql_data`，所以出现了双前缀。行为规则：`docker compose down` 停容器删容器，**卷保留**；`docker compose down -v` 连卷一起删，数据库真被清空。删卷操作只对着装满测试数据的库做。

## 应用不在容器里：本项目的部署形态

到此为止读者可能已经发现：这个项目容器化了 MySQL、Redis、RabbitMQ、Prometheus、Grafana 五个东西，唯独没有容器化应用本体。这是事实，而且是 compose 文件注释里写明的设计（原话：Prometheus 抓的是宿主机上的应用）。为什么这么选？据 compose、`scripts/bench.sh` 与仓库文档综合推断，理由是：

- 演示形态少一环：不用写 Dockerfile、不用管 JVM 在容器里的内存参数，`mvn package` 加 `java -jar` 就是全部。
- 数据库 schema 由应用的 Flyway 在启动时创建（`docker/README.md` 注明 Compose 不挂任何数据库初始化脚本），容器只负责存数据。
- 压测脚本 `scripts/bench.sh` 的拓扑自述是「wrk(容器) -> host.docker.internal:8080 -> app(host JDK 21) -> deps(compose, named volumes)」：连压测客户端都放进容器，唯独应用留在宿主。

代价也要说清楚：**「镜像即制品」的部署纪律没有演练**——生产部署的产物应该是镜像，从镜像到滚动更新、回滚这条路本项目没走过（这是 [82-linkforge-decisions.md](82-linkforge-decisions.md) 里的冻结决策：本地 Compose，不做云上部署）。生产化方向就是应用也容器化（第 4 节的示意 Dockerfile），配合 `application.yml` 里已经备好的 prod profile：健康详情从 `always` 收成 `when_authorized`，暴露面收紧。被面试问到部署时，这个边界值得主动讲，比假装容器化过强得多。

## 观测端点：应用把什么暴露出去

### 三支柱：日志、指标、链路

服务跑起来之后，下一个问题是「它现在正常吗」。可观测性三支柱各答一个问题。

| 支柱 | 回答 | 形态 |
|------|------|------|
| 日志 | 发生了什么（事后查案） | 一行行文本，带时间、级别、内容 |
| 指标 | 现在水位如何（实时把脉） | Counter/Gauge/Histogram 数值序列 |
| 链路 | 一个请求经过了谁 | Trace 跨服务串联（本项目未做，如实说） |

**结构化日志**指每行日志用统一字段组织（时间、级别、logger、消息，或更进一步的 JSON 键值对），让机器能按字段过滤聚合。本项目的现状要如实说：`application.yml` 的 logging 段只配了级别（`com.linkforge: INFO` 等），日志是 Spring Boot 默认的文本格式，没有上 JSON——结构化日志属于生产化改造方向，不是现状。

指标部分是本项目的重头。先看暴露了哪些端点：

```yaml
# 片段（src/main/resources/application.yml）
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus   # B1: scrape endpoint, token-gated (D-Bw345-42)
  endpoint:
    health:
      show-details: always
      show-components: always
```

`include` 是白名单：只开放 `health`、`info`、`metrics`、`prometheus` 四个端点，其余 actuator 端点一律不暴露。`show-details: always` 让 `/actuator/health` 连同各组件（数据库、Redis 连接）的健康细节一起返回。这是 dev 口径；prod profile 里同一项是 `when_authorized`，只有带授权的调用者才看得到细节。

### 令牌门禁：MetricsScrapeTokenFilter 逐段

`/actuator/prometheus` 是给机器抓取用的指标导出端点，谁都能读等于把应用内部数值公开。项目的门禁在 `src/main/java/com/linkforge/security/MetricsScrapeTokenFilter.java`。它是 Spring Security 过滤器链里的一环（过滤器链见 [32-spring-security.md](32-spring-security.md)），核心三段：

第一段，缩小管辖范围：

```java
// 片段（MetricsScrapeTokenFilter.java）
return !request.getRequestURI().equals("/actuator/prometheus");
```

只拦截 `/actuator/prometheus` 一个路径，其余请求原样放行给链上的下一个过滤器。

第二段，fail-closed 校验——配错了宁可全部拒绝：

```java
// 片段（MetricsScrapeTokenFilter.java）
if (configured == null || configured.isBlank() || provided == null
        || !MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8))) {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
```

应用侧期待的令牌来自配置项 `linkforge.metrics.scrape-token`（`application.yml` 里映射环境变量 `LINKFORGE_METRICS_SCRAPE_TOKEN`，注释原话：empty = fail-closed 401）。令牌没配、为空、请求没带 `Authorization: Bearer ...`、带的值对不上，四种情况全部返回 401。特别留意两个细节：令牌未配置时不是「放行」而是「全拒」，因为指标泄露比抓不到指标更糟；比对用 `MessageDigest.isEqual` 而不是 `equals`，前者是常量时间比较，不因为「第几个字符开始不同」在耗时上留下线索。

第三段，验证通过后授予专用角色 `ROLE_METRICS` 放行，并在 finally 里清空安全上下文，不把抓取者的身份泄给后续请求。

### 抓取侧与暴露面：三层配合

应用等令牌，Prometheus 就必须带令牌。`ops/observability/prometheus.yml` 全文：

```yaml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: linkforge
    metrics_path: /actuator/prometheus
    # the token file holds exactly the value of LINKFORGE_METRICS_SCRAPE_TOKEN;
    # it lives outside version control (see .gitignore)
    authorization:
      type: Bearer
      credentials_file: /etc/linkforge/scrape-token
    static_configs:
      - targets: ["host.docker.internal:8080"]
```

逐行读：`scrape_interval: 15s`，Prometheus 每 15 秒来抓一次（拉模型：监控系统主动来拿，不是应用往上报；拉模型的好处是抓取端自己挂了不产生假数据，恢复即续上）。`metrics_path` 指到令牌门禁的那个端点。`credentials_file` 从容器内 `/etc/linkforge/scrape-token` 读令牌——第 5 节的 inspect 输出证实这个文件就是仓库里 `ops/observability/scrape-token` 以只读方式挂进去的，内容必须与应用环境变量 `LINKFORGE_METRICS_SCRAPE_TOKEN` 一致（yml 注释原话）。文件被 `.gitignore` 排除在版本控制之外，仓库里提交的只有 `ops/observability/scrape-token.example` 模板。`targets` 是 `host.docker.internal:8080`：容器里这个名字被 `extra_hosts` 钉到宿主网关，于是容器内的 Prometheus 抓到了宿主机上的 Java 进程。

至此三层防护齐了：应用过滤器拦未授权读取（401），抓取侧用文件挂载的令牌对齐，网络暴露面用 `127.0.0.1` 端口发布收口。任何一层单独失效，另外两层还兜着。

### up 指标：抓取器的自我健康

Prometheus 每抓一个目标，就自己记一个名为 `up` 的指标：抓取成功为 1，失败为 0。这是排障的第一入口。本文写作时的真实读数：

```text
$ curl -s "http://localhost:9090/api/v1/query?query=up"
{"status":"success","data":{"resultType":"vector","result":[
  {"metric":{"__name__":"up","instance":"host.docker.internal:8080","job":"linkforge"},
   "value":[1793212276.996,"0"]}]}}
```

`up` 等于 0：最近一次抓取失败了。而同一时刻 `curl http://localhost:8080/api/health` 返回 200，应用进程明明活着。失败的可能原因包括抓取令牌不一致、指标端点暂时不可达、网络路径问题，具体是哪个要看 Prometheus 的 targets 页和应用日志，这里不下结论。这个真实案例的价值在于示范排查顺序：**面板全空或指标断流，先查 `up`，再查应用，最后才怀疑指标本身**。

## 指标长什么样：一次抓取的文本

`/actuator/prometheus` 导出的是 Prometheus 文本格式：一行一个时间序列样本，`指标名{标签} 值`，`# TYPE` 行声明类型。三种类型各有性格：**Counter** 只增不减（请求数、错误数），看它要看速率；**Gauge** 是瞬时值可升可降（队列积压、连接数），直接读；**Histogram** 是分桶计数的延迟分布，后面单独讲。下面的 Java 程序模拟一次导出（自包含，可直接编译运行；它演示格式与标签归一逻辑，不是项目代码的复制）：

```java
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模拟 Prometheus 文本暴露格式（text format 0.0.4）的一次抓取输出。
 * counter 导出为 name_total；gauge 原名导出瞬时值；
 * histogram 导出累积桶（le = 小于等于上界的累计计数）+ _sum + _count。
 * uri 标签走白名单归一：不在白名单里的原始路径折成 UNKNOWN（基数治理）。
 */
public class MetricsWriter {

    static String normalizeUri(String raw) {
        if (raw.equals("/r/{code}") || raw.equals("/api/health")) {
            return raw;
        }
        return raw.startsWith("/r/") ? "/r/{code}" : "UNKNOWN";
    }

    static void inc(Map<String, Long> counter, String outcome, String rawUri) {
        String labels = "outcome=\"" + outcome + "\",uri=\"" + normalizeUri(rawUri) + "\"";
        counter.merge(labels, 1L, Long::sum);
    }

    static List<String> counterLines(Map<String, Long> counter) {
        List<String> out = new ArrayList<>();
        out.add("# TYPE linkforge_redirects counter");
        counter.forEach((labels, v) -> out.add("linkforge_redirects_total{" + labels + "} " + v));
        return out;
    }

    static List<String> gaugeLine(String name, long value) {
        List<String> out = new ArrayList<>();
        out.add("# TYPE " + name + " gauge");
        out.add(name + " " + value);
        return out;
    }

    static List<String> histogramLines(String name, double[] bounds, long[] cumulative, double sum) {
        List<String> out = new ArrayList<>();
        out.add("# TYPE " + name + " histogram");
        for (int i = 0; i < bounds.length; i++) {
            out.add(name + "_bucket{uri=\"/r/{code}\",le=\"" + bounds[i] + "\"} " + cumulative[i]);
        }
        out.add(name + "_bucket{uri=\"/r/{code}\",le=\"+Inf\"} " + cumulative[cumulative.length - 1]);
        out.add(name + "_sum{uri=\"/r/{code}\"} " + sum);
        out.add(name + "_count{uri=\"/r/{code}\"} " + cumulative[cumulative.length - 1]);
        return out;
    }

    /** 朴素分位估计：累计计数第一次达到 q * 总数的那个桶，用桶上界做近似值。 */
    static double naiveQuantileUpperBound(double[] bounds, long[] cumulative, double q) {
        long target = (long) Math.ceil(q * cumulative[cumulative.length - 1]);
        for (int i = 0; i < cumulative.length; i++) {
            if (cumulative[i] >= target) {
                return bounds[i];
            }
        }
        return bounds[bounds.length - 1];
    }

    public static void main(String[] args) {
        Map<String, Long> counter = new LinkedHashMap<>();
        inc(counter, "hit", "/r/7f3kq2");    // 真实短码，归一为模板
        inc(counter, "hit", "/r/x91mm4");    // 又一个短码，并入同一条时间序列
        inc(counter, "hit", "/api/health");  // 白名单内的探活端点
        inc(counter, "miss", "/r/zzzzzz");   // 不存在的码，仍归一为模板
        counterLines(counter).forEach(System.out::println);

        gaugeLine("linkforge_outbox_pending", 12).forEach(System.out::println);
        gaugeLine("linkforge_webhook_failed", 3).forEach(System.out::println);

        double[] bounds = {0.005, 0.01, 0.05, 0.1, 0.5};
        long[] cumulative = {58, 91, 98, 99, 100};
        histogramLines("http_server_request_duration_seconds", bounds, cumulative, 0.642)
                .forEach(System.out::println);
        System.out.println("estimated_p95_upper_bound_seconds "
                + naiveQuantileUpperBound(bounds, cumulative, 0.95));
    }
}
```

```text
# TYPE linkforge_redirects counter
linkforge_redirects_total{outcome="hit",uri="/r/{code}"} 2
linkforge_redirects_total{outcome="hit",uri="/api/health"} 1
linkforge_redirects_total{outcome="miss",uri="/r/{code}"} 1
# TYPE linkforge_outbox_pending gauge
linkforge_outbox_pending 12
# TYPE linkforge_webhook_failed gauge
linkforge_webhook_failed 3
# TYPE http_server_request_duration_seconds histogram
http_server_request_duration_seconds_bucket{uri="/r/{code}",le="0.005"} 58
http_server_request_duration_seconds_bucket{uri="/r/{code}",le="0.01"} 91
http_server_request_duration_seconds_bucket{uri="/r/{code}",le="0.05"} 98
http_server_request_duration_seconds_bucket{uri="/r/{code}",le="0.1"} 99
http_server_request_duration_seconds_bucket{uri="/r/{code}",le="0.5"} 100
http_server_request_duration_seconds_bucket{uri="/r/{code}",le="+Inf"} 100
http_server_request_duration_seconds_sum{uri="/r/{code}"} 0.642
http_server_request_duration_seconds_count{uri="/r/{code}"} 100
estimated_p95_upper_bound_seconds 0.05
```

逐段解释发生了什么：

- 四次 `inc` 里出现了三个不同的真实短码，导出时却只有两条 `uri="/r/{code}"` 序列——`normalizeUri` 把它们并进了同一个模板标签。`counter.merge(key, 1L, Long::sum)` 表示键不存在则放入 1，存在则旧值加 1（`merge` 与方法引用见 [java/10-08-lambda-stream-optional.md](java/10-08-lambda-stream-optional.md)）。这个归一就是下一节的基数治理，先在这里看见效果。
- gauge 直接导出瞬时值。`linkforge_outbox_pending` 这个名字不是虚构：项目真有同名指标，下一节讲它为什么这么采样。
- histogram 的桶是**累积**的：`le="0.01"` 的 91 表示「延迟不超过 10 毫秒的有 91 个请求」，它已经包含 `le="0.005"` 的 58。最后一行 `estimated_p95_upper_bound_seconds 0.05` 是程序自己算的：把目标数量（总量 100 的九成五取整）和累积计数比对，第一个达到目标的桶是上界 0.05 的那个。Prometheus 的 `histogram_quantile` 函数在同一份数据上做插值，估得更细；本文示例只找桶上界，两者都说明同一件事：**分位数不是逐个记录请求算出来的，是从桶里估出来的**。

## 指标基数治理：HttpUriTagAllowList

### 先遇到问题：一个短码一条时间序列

标签（tag/label）的每个取值组合都是一条独立的时间序列。跳转接口的原始路径长什么样？`/r/7f3kq2`、`/r/x91mm4`……每个短码都不一样，而短码是应用自己发的、长期积累、数量无上界。如果 uri 标签直接记原始路径，每发一个短码 Prometheus 就多一条序列，监控系统的时间序列数量随业务量线性爆炸，内存和查询全会被拖垮。这叫**基数**（cardinality）问题：标签取值组合数决定序列数，无界值进标签是拉模型监控的第一大坑。

### 项目的解法：白名单归一

`src/main/java/com/linkforge/observability/HttpUriTagAllowList.java` 全文五十行，逐段读。

```java
// 片段（HttpUriTagAllowList.java）
private static final Set<String> ALLOWED = Set.of(
        "/r/{code}", "/r/{code}/unlock",
        "/api/links", "/api/links/{code}", "/api/links/{code}/stats",
        ...
        "/api/health",
        "/actuator/health", "/actuator/prometheus", "/actuator/metrics", "/actuator/info");
```

白名单是 `Set.of` 建的**不可变集合**，装的全是控制器模板路径——花括号里的 `{code}` 是 Spring MVC 的路径变量占位符，不是真实短码。集合元素数量固定，这就是「有界」：无论业务发多少短码，uri 标签的取值永远只有这十几个加一个 UNKNOWN。

```java
// 片段（HttpUriTagAllowList.java）
@Bean
MeterFilter httpUriTagFilter() {
    return new MeterFilter() {
        @Override
        public Meter.Id map(Meter.Id id) {
            if (!"http.server.requests".equals(id.getName())) {
                return id;
            }
            String uri = id.getTag("uri");
            if (uri == null || ALLOWED.contains(uri)) {
                return id;
            }
            return id.withTag(Tag.of("uri", "UNKNOWN"));
        }
    };
}
```

`MeterFilter` 是 Micrometer（Spring Boot 的指标门面库）的拦截机制：每个指标注册进系统前都会经过 filter 的 `map` 方法，可以改名字、改标签甚至丢弃。这段逻辑：只处理名为 `http.server.requests` 的指标（Spring MVC 自动为每个 HTTP 请求记的那个）；uri 标签在白名单里就原样放行；不在，就把标签值改成 `UNKNOWN`。类注释点明了失败方向的设计（译）：不在白名单的请求会折叠成 UNKNOWN。新端点必须加进白名单，否则它的指标会静默并入 UNKNOWN——这是安全的方向。安全的方向指宁可丢分辨率（所有未知路径挤在一条 UNKNOWN 序列里，你能看出「有不明流量」）也不能爆序列数。代价是：新增接口忘了登记，它的指标就会混进 UNKNOWN——这是一个显式的、有意的登记税。

四类事实分清：`MeterFilter` 的拦截时机是框架（Micrometer）行为；白名单内容是 LinkForge 项目逻辑；「容器层拒绝的请求会记下原始路径」来自类 javadoc 原话，属于项目作者的陈述；「短码级基数爆炸会拖垮 Prometheus」是监控系统的通用原理。

## gauge 的正确采样：BacklogGauges 预采样

### 先遇到问题：抓取线程里跑 COUNT 会怎样

Gauge 的值在**被抓取那一刻**读取。最直觉的写法是把 `SELECT COUNT(*)` 直接写进 gauge 的取值函数：Prometheus 每 15 秒来抓一次，每次抓取都触发对 MySQL 的全表计数查询。后果有二：抓取延迟和数据库延迟被耦合在一起（数据库一慢，抓取超时，指标断流）；多个抓取方并发时，查询次数随之放大。积压 gauge 恰恰在系统最忙、最需要观测的时候最容易失效。

### 项目的解法：后台预采样，抓取只读内存

`src/main/java/com/linkforge/observability/BacklogGauges.java` 的类注释原话就是这句话的英文版：「a COUNT inside a scrape would couple Prometheus latency to the DB and amplify with concurrent scrapes」。代码分四段：

```java
// 片段（BacklogGauges.java）
private final AtomicLong outboxPending = new AtomicLong();
private final AtomicLong webhookPending = new AtomicLong();
private final AtomicLong webhookFailed = new AtomicLong();
```

三个 `AtomicLong` 是内存里的原子长整数（`Atomic` 指读写操作不被并发打断，见 [13-concurrency.md](13-concurrency.md)），充当「最近一次采样结果」的快照。

```java
// 片段（BacklogGauges.java）
@PostConstruct
void register() {
    Gauge.builder("linkforge.outbox.pending", outboxPending, AtomicLong::get).register(meterRegistry);
    ...
}
```

`@PostConstruct` 让这段在 Bean 初始化后执行：向 MeterRegistry 注册三个 gauge，取值函数是 `AtomicLong::get`——注意它只是读内存整数，不碰数据库。方法引用的语法见 [java/10-08-lambda-stream-optional.md](java/10-08-lambda-stream-optional.md)。

```java
// 片段（BacklogGauges.java）
@Scheduled(fixedDelayString = "${linkforge.metrics.backlog-sample-interval-ms:15000}", ...)
public void sample() {
    sample(outboxPending, "SELECT COUNT(*) FROM lf_outbox WHERE status = 'PENDING'");
    ...
}

private void sample(AtomicLong target, String sql) {
    try {
        Long count = jdbc.queryForObject(sql, Long.class);
        if (count != null) {
            target.set(count);
        }
    } catch (Exception ex) {
        log.warn("backlog sample failed: {}", ex.getMessage());
    }
}
```

`@Scheduled` 按固定延迟在后台线程池里跑采样，间隔来自配置项、默认 15 秒（和抓取间隔一致但互相独立）。真正的 COUNT 在这里发生，与抓取路径完全解耦：抓取方再多、数据库再慢，gauge 读取的只是上次快照。采样失败时保留旧值并打 WARN——指标曲线出现平台而不是空洞，而失败本身在日志里有迹可查。

两个决策合起来是一句可迁移的原则：**抓取路径上只允许有内存读，任何带 IO 的计算都挪到后台按固定频率做**。代价是数据最多滞后一个采样周期；对「队列积压」这种秒级粒度的水位来说，15 秒延迟完全可以接受。

## SLO 与分位数：P95 怎么来的、桶怎么设计

**SLO**（Service Level Objective，服务等级目标）是对服务质量的显式承诺，典型形式是「多数请求的延迟要低于某个界限」。它建立在分位数上：把一段时间内所有请求的延迟从小到大排队，站在中点的是 P50，站在靠后位置、前面压着绝大多数请求的是 P95 和 P99。用 P95 而不用平均值，是因为平均值会被少数离谱请求拉走，而用户体验由「大多数请求有多快」决定。

分位数怎么算？不可能把每个请求的耗时都存下来（存储无界）。办法就是上一节的 histogram：只维护十几个累积桶。项目的桶边界在 `application.yml` 里（真实配置）：

```yaml
# 片段（src/main/resources/application.yml）
    distribution:
      percentiles-histogram:
        # only this meter gets buckets — the redirect SLO panel needs the latency distribution
        http.server.requests: true
      slo:
        http.server.requests: 5ms,10ms,25ms,50ms,100ms,250ms,500ms,1s
```

逐个读：`percentiles-histogram` 只对 `http.server.requests` 这一个指标开直方图——只有需要延迟分布的面板才付直方图的内存与序列开销，其他指标不开。`slo` 行定义桶边界，从几毫秒到一秒，刻意对齐「跳转延迟」这类接口的预算刻度：跳转该在几十毫秒级，所以密的部分放在低延迟区；桶的疏密决定分位数估计的精度，**桶要对着你的 SLO 目标设计**。同一段配置里还有一条卫生规则：`http.client.requests` 被显式关掉，注释原话说明原因——应用里唯一被插桩的客户端是测试用的 RestTemplate，开着会把测试请求的原始路径写进导出，既脏又是泄露面。

这套配置喂给 Grafana 面板，面板标题自称「SLO demo targets, not a production promise」——目标数字是演示用的，不是对外承诺。这是诚实口径：**SLO 的价值先在于「有没有一个写下来的目标」，其次才是目标定得多准**。指标体系的最小集是 RED：Rate（每秒请求数）、Errors（错误率）、Duration（延迟分布），`ops/observability/grafana/dashboards/linkforge-slo.json` 面板的三块主体正是这三样。

### 压测口径：scripts/bench.sh 讲纪律不讲数字

SLO 数字从哪来？压测。`scripts/bench.sh` 是项目的压测脚本，本文不给它跑出的任何具体数值（性能数字与机器强相关，脱离环境谈数值没有意义），只讲它的口径纪律，这些纪律本身值得学：

- 拓扑固定且写明：压测客户端 wrk 跑在容器里，通过 `host.docker.internal:8080` 打宿主机上的应用，应用再连 compose 中间件——把「谁压谁」钉死，结果才可比较。
- 脚本自己起停应用，并在启动时把限流阈值用环境变量调到极大：压测想测的是服务本身的容量，不是限流器的容量；但阈值没有关掉限流功能，请求仍走完整代码路径。
- 先预热（打一次跳转让缓存热起来）再正式压：首请求带着冷缓存的延迟会污染整体分布。
- 场景对照而非单点数值：同一跳转接口在「默认双写」「关异步写」「关同步写」三种开关下各跑一轮，看的是**差值**——异步写和同步写各自贡献了多少成本。
- 缓存贡献单独拎出来：用 `redis-cli INFO stats` 的命中计数差值算命中率，和延迟数据并列打印，不混在一个数里。

这套纪律迁移成一句话：**性能结论只从受控对照里来，环境、预热、对照变量、归因路径都要写在报告里。**

### 看板是声明式的：provisioning 与 DashboardMetricsIT

Grafana 的数据源和看板都不靠手工点出来：`ops/observability/grafana/provisioning/` 下的两个 yml 声明数据源（只读，`editable: false`）和看板目录，看板 JSON 以只读方式挂载进容器。重启观测栈，面板原样回来——没有「手工配的面板」这种会漂移的状态。看板引用的指标与代码是否同步，由 `src/test/java/com/linkforge/observability/DashboardMetricsIT.java` 在 CI 里把关：javadoc 原话是「提交的 Grafana 看板只准引用应用真的声明过的指标——看板烂掉是经典死法」。测试先触发一批真实业务动作，再解析看板 JSON 里的指标名逐一核对；导出了但看板没用的指标只是打印不报错。告警规则目前未配置（`prometheus.yml` 里没有 rule_files，事实），按 Demo 口径如实说。

## 常见错误与误区

| 错误 | 后果 | 正确姿势 |
|------|------|----------|
| 密码用 `${VAR:-default}` | 缺失时静默用默认值，问题推迟到运行期 | 凭证用 `${VAR:?msg}`，启动即失败 |
| 数据写进容器不进卷 | 删容器数据全丢 | 数据目录挂命名卷，`down -v` 才是删除 |
| 无界值进指标 tag | 时间序列数爆炸拖垮监控 | 白名单归一为模板或 UNKNOWN |
| gauge 在抓取线程里查库 | 抓取延迟耦合 DB，并发抓取放大查询 | 后台定时预采样进 `AtomicLong`，抓取只读内存 |
| 观测端口绑 `0.0.0.0` | 指标与面板对局域网裸奔 | 发布成 `127.0.0.1`，或加认证后再暴露 |
| 慢应用配短 `start_period` | 刚启动就被判死，重启循环 | 宽限期与 retries 覆盖最慢启动场景 |
| 「起来就行」不看 healthy | 端口通但服务未就绪的假活 | 配 healthcheck，`docker compose ps` 等 healthy |
| 指标断流先怀疑应用 | 绕远路 | 先查 `up` 指标，再查抓取认证，最后看应用 |

## 自测

1. `${MYSQL_PASSWORD:?set MYSQL_PASSWORD}` 和 `${MYSQL_PASSWORD:-}` 的区别是什么？哪个用于密码？
   答：`:?` 在变量缺失或为空时报错并中止启动，把配置错误提前到启动瞬间；`:-` 静默用默认值。密码必须用 `:?`，否则空密码会悄悄上线。
2. 健康检查的 `interval`、`timeout`、`retries`、`start_period` 各管什么？为什么 MySQL 的 `start_period` 比 Redis 长得多？
   答：interval 是检查间隔，timeout 是单次判失败时限，retries 是连续失败几次才判 unhealthy，start_period 是启动宽限期、期内失败不计入 retries。MySQL 启动要初始化表空间和系统库，比秒级就绪的 Redis 慢得多，宽限期必须盖住最慢启动。
3. 为什么 gauge 不能在抓取线程里执行 COUNT 查询？`BacklogGauges` 是怎么解的？
   答：抓取时查库会把数据库延迟耦合进抓取路径，数据库一慢指标断流，且查询量随并发抓取放大。项目改为 `@Scheduled` 后台每 15 秒采样进 `AtomicLong`，gauge 只读内存快照；采样失败保留旧值并打 WARN。
4. 把短码或用户 ID 直接放进指标 tag 会发生什么？项目用哪两层手段按住 uri 标签？
   答：标签取值组合数（基数）随业务量无界增长，时间序列爆炸拖垮 Prometheus 存储。第一层是 `HttpUriTagAllowList` 的 `MeterFilter` 把白名单外的原始路径归一为 UNKNOWN；第二层是约定新端点必须登记进白名单，否则指标并入 UNKNOWN（分辨率损失但序列数有界）。
5. `/actuator/prometheus` 的访问控制有哪三层？分别由谁执行？
   答：应用侧 `MetricsScrapeTokenFilter` 校验 Bearer 令牌，未配置即 fail-closed 全 401；抓取侧 Prometheus 从只读挂载的令牌文件读取同一令牌；网络侧端口发布绑定 `127.0.0.1`，观测面不对局域网开放。
6. 面板上指标突然全空，第一步查什么？
   答：先查 Prometheus 的 `up` 指标（本文实测到过它等于 0 而应用 `/api/health` 返回 200 的真实案例）。`up` 为 0 说明抓取失败，去看抓取令牌与网络；`up` 为 1 才轮到怀疑应用或指标本身。

## 面试追问

- **镜像分层与构建缓存**。镜像由指令层叠成，层只读可共享；追问「为什么分层」答共享底层省磁盘与传输、按层缓存加速构建、增量分发。追问「Dockerfile 怎么排指令」答不变的在前（依赖、基础包），常变的在后（源码），否则一处小改打穿全部缓存；再追问就到 multi-stage——编译环境留在第一阶段，制品镜像只有 JRE 加 jar。
- **健康检查与就绪设计**。`Up` 不等于 `healthy`；追问「三参数」答 interval、timeout、retries 加 start_period 宽限期；追问「慢启动应用怎么办」答放宽宽限期与重试、编排层用 `depends_on` 的 `condition: service_healthy` 钉住启动顺序，应用侧同时要有连不上依赖时的重试。本项目健康检查的读者是人与重启策略，因为应用不在 compose 里。
- **可观测性三件套与指标卫生**。日志答「发生了什么」，指标答「现在正常吗」，链路答「请求经过了谁」，本项目有前两样。追问「指标怎么防爆炸」答基数治理：无界值进白名单归一、只有需要的指标开直方图、测试客户端的指标显式关闭；追问「分位数哪来的」答 Histogram 累积桶加 `histogram_quantile` 插值，桶边界对齐 SLO 目标；再追问「怎么知道监控本身活着」答抓取器的 `up` 指标，本文有真实读数为 0 的案例。

## 本章总结

从 jar 到可访问的服务，中间是「打包形态、运行进程、依赖连接、端口监听」四步；Docker 用分层镜像与受限进程把依赖的环境不确定性消灭，Compose 把多容器的编排写进一份声明式文件，健康检查把「假活」变成可见状态，环境变量的 `:?` 语法把配置错误提前到启动瞬间。观测侧，应用暴露四个 actuator 端点，指标端点由专用令牌 fail-closed 把守，Prometheus 每 15 秒拉一次，指标卫生靠三条纪律撑着：tag 有界（`HttpUriTagAllowList`）、直方图只给需要的指标、gauge 预采样不碰抓取路径。项目的诚实边界同样要记：应用未容器化、无告警规则、无链路追踪，都是显式声明而非遗漏。

## 下一步

- 这些指标在跳转链路上的产生点见 [81-linkforge-flows.md](81-linkforge-flows.md)；五步跑通本项目的完整操作见 [80-linkforge-architecture.md](80-linkforge-architecture.md)。
- 容器的进程隔离与资源限额的底层机制见 [21-os.md](21-os.md)；容器内存限额与 JVM 参数的配合见 [12-jvm.md](12-jvm.md)。
- 容器里跑的 Redis 与 RabbitMQ 各自的原理见 [50-redis.md](50-redis.md) 与 [51-rabbitmq-outbox.md](51-rabbitmq-outbox.md)。
- Testcontainers 在测试里怎么起容器、与 CI 环境的关系见 [60-testing-ci.md](60-testing-ci.md)。
- SLO 与错误预算在分布式语境下的延伸见 [70-distributed.md](70-distributed.md)；部署形态的决策记录见 [82-linkforge-decisions.md](82-linkforge-decisions.md)。
