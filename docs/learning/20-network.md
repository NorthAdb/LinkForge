# 计算机网络与 HTTP：从一次短链跳转说起

优先级 P0，目标层级 L2：TCP/HTTP 机制讲清、状态码与方法语义零犹豫；TLS 细节到 L3（讲清防什么、密钥怎么换即可）。

## 学习目标

读完这篇，你应该能做三件事。

1. 从浏览器地址栏输入 `http://localhost:8080/r/abc` 开始，一路讲到 302 响应离开服务器：数据被怎样一层层打包（封装）、TCP 连接怎样建立、HTTP 报文长什么样、`Location` 头怎样把浏览器引向长链。
2. 解释三次握手为什么恰好是三次、四次挥手为什么恰好是四次、TIME_WAIT 在等什么。
3. 读懂 LinkForge 网络层的四处代码：`RedirectController` 的 302 与 Cookie、`ClientIps` 的 XFF 解析、`GatePages` 的安全响应头。

贯穿全文的一个纪律：分清三件事。协议规范怎么写（RFC 定的）、浏览器和服务器通常怎么做（实现习惯）、LinkForge 自己选了什么（业务决定）。三者的正确性来源不同，混为一谈是初学者最常见的错。

前置知识：无硬前置。端口与进程的关系见 [21-os.md](21-os.md)。

## 从一个问题开始

打开浏览器，地址栏输入 `http://localhost:8080/r/abc`，回车，页面跳到了长链。中间发生了什么？

把这个问题拆成五个子问题，它们正好对应网络的分层：

1. 这台机器怎么找到"对面是谁"，地址怎么表示？（网络层）
2. 数据在网线上跑，丢了、乱了、慢了怎么办？（传输层，TCP）
3. 到达之后，对方怎么知道这一串字节是"请求跳转"而不是别的东西？（应用层，HTTP）
4. 服务器怎么回答"去别处拿"？（HTTP 302 加 `Location` 头）
5. 浏览器拿到 302 之后做什么？（浏览器实现）

全文按这个顺序展开，最后的"全过程串讲"把它们串成一趟完整的旅行。

## 分层模型：每一层回答一个问题

### 问题：一段数据的旅程，一个人管不来

假设让一个程序直接负责"把这段话送到对面电脑"。它要同时处理：网卡上的电信号怎么编码、数据走哪条路、半路丢了怎么办、内容格式怎么约定。任何一处要改，整块代码都得动。

网络的做法是分四层，每层只回答一个问题：

| 层 | 回答的问题 | 代表协议 |
|---|---|---|
| 应用层 | 说的内容是什么意思 | HTTP、DNS |
| 传输层 | 两个进程之间怎么传、传丢了怎么办 | TCP、UDP |
| 网络层 | 怎么跨过一串路由器把数据送到那台主机 | IP |
| 链路层 | 相邻两台设备之间的这一跳怎么传 | 以太网、Wi-Fi |

发送数据时逐层加头。应用层把 HTTP 报文交给 TCP，TCP 加上自己的头（序号、端口），交给 IP，IP 再加头（源地址、目标地址），链路层最后加帧头帧尾。接收方逐层拆头，每层只读本层的头，把剩下的载荷交给上层。这个过程叫**封装**（encapsulation）：

```text
应用层 HTTP 报文:   GET /r/abc HTTP/1.1 + 头 + 体
传输层 TCP 段:      [TCP头 | HTTP报文]
网络层 IP 包:       [IP头 | TCP头 | HTTP报文]
链路层 帧:          [帧头 | IP头 | TCP头 | HTTP报文 | 帧尾]
```

术语对齐：传输层的单位叫**段**（segment），网络层的叫**包**（packet），链路层的叫**帧**（frame）。

可以比喻成寄快递：包裹里是信（HTTP 报文），快递单写着收件地址（IP 头），内部分拣编号（TCP 头）。这个比喻的边界：现实快递单写错整件退回，而网络每一层各自独立工作，TCP 也不知道 IP 中途换了几条路线；快递也不会在每站被拆开重贴一层单子，而封装在每一跳真的会发生（链路层帧头每一跳都换新的）。

分工的关键三句话：IP 负责"送到哪台主机"；端口负责"送到主机上的哪个进程"；TCP 在这两者之上提供**可靠字节流**（数据不丢、不重、按序到达）。IP 地址加端口的组合，唯一确定互联网上一条连接的一端。

UDP 与 TCP 是兄弟，同样跑在 IP 上，但不握手、不重传、不保证顺序，换来低开销。DNS 查询、实时音视频常用 UDP。要不要可靠，由上层应用自己解决。

面试常提 OSI 七层模型：把上面的四层拆细（链路层拆成物理层与数据链路层，应用层附近拆出会话层与表示层）。工程上用四层模型思考，能说出七层名字和大致对应即可。

## TCP：先握手，再谈可靠

### 为什么恰好三次握手

问题：TCP 承诺可靠。可靠的起点是双方都确认"路是通的、对面准备好了"。隔着一张随时丢包的网络，怎么达成这个共识？

三次握手的报文流：

```text
客户端                                      服务端
  | ---- SYN (seq=x) ------------------->  |  我要连接，我的初始序号是 x
  | <--- SYN+ACK (seq=y, ack=x+1) ------  |  收到 x。我要连接，我的初始序号是 y
  | ---- ACK (ack=y+1) ---------------->  |  收到 y
```

SYN（synchronize）与 ACK（acknowledge）是 TCP 头里的两个标志位，seq 与 ack 是序号与确认号。每一步之后，双方分别确认了什么（这是面试答案的核心）：

| 时刻 | 服务端确认了 | 客户端确认了 |
|---|---|---|
| 第一次后（SYN 到达服务端） | 对方能发、自己能收 | 什么都没确认，不知道报文是否存活 |
| 第二次后（SYN+ACK 到达客户端） | 同上 | 自己能发能收、对方能发能收 |
| 第三次后（ACK 到达服务端） | 对方能发能收、自己能发能收 | 同上 |

三次之后，"我能发、我能收、对方能发、对方能收"四种组合才全部验证完。这就是恰好三次的原因：一次不够（客户端得不到任何回复）；两次不够（服务端还不知道客户端能不能收）。

两次握手的具体危险：网络里滞留着一个很旧的 SYN。它是早先某次连接的重复报文，绕远路迟到才到。如果两次握手就建连，服务端会回复这个旧 SYN 并分配连接资源，傻等一个永远不会来的数据。三次握手下，客户端对这个"不知从哪来的 SYN+ACK"回 RST（重置），服务端据此丢弃连接。第三次握手给了客户端否决权。

第三次握手的 ACK 段可以顺便携带第一批数据，比如 HTTP 请求的开头几个字节。所以"三次握手"并不等于先空跑三个来回才能说话。

### 四次挥手与 TIME_WAIT

挥手要四次，因为连接是两条方向独立的字节流。一方发 FIN（finish）说"我发完了"，另一方回 ACK；但另一方可能还有数据没发完，等它也发完，再发自己的 FIN，对方回 ACK。FIN、ACK、FIN、ACK，共四段。中间两步合并不了：收到 FIN 只代表对方发完了，我可能还在发。（实现上若恰无数据，后两段会被合并发送，看起来像三次，那属于实现的优化。）

**TIME_WAIT**：主动关闭的一方发出最后一个 ACK 后不立刻销毁连接，停留 2MSL。MSL（maximum segment lifetime）是一段报文在网络里允许存活的最长时间。等两个原因：一，最后的 ACK 若丢了，对端会重发 FIN，处于 TIME_WAIT 的一端还能补发 ACK；二，让这条连接的旧报文在网络里自然死亡，避免污染复用同一对端口的新连接。

高并发短连接的服务端会堆积大量 TIME_WAIT，占端口占内存。这就是 HTTP 要复用连接的原因之一（见后文 keep-alive），也是服务器调优的常见话题。

### 亲手触发一次握手：Java echo 演示

Java 标准库的 `ServerSocket` 和 `Socket` 就是 TCP 的编程接口。握手被封装在 `new Socket(host, port)` 里：构造函数返回时，三次握手已完成。下面是自包含程序，一个线程当 echo 服务端（收到什么回什么），主线程当客户端。整个文件复制下来就能编译运行。

```java
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class EchoDemo {
    public static void main(String[] args) throws Exception {
        try (ServerSocket server = new ServerSocket(0)) { // port 0 = OS picks a free port
            final int port = server.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket conn = server.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(
                             conn.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter out = new PrintWriter(new OutputStreamWriter(
                             conn.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        System.out.println("[server] got: " + line);
                        out.println(line);
                    }
                } catch (IOException e) {
                    // demo ends
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();

            try (Socket client = new Socket("127.0.0.1", port);
                 PrintWriter out = new PrintWriter(new OutputStreamWriter(
                         client.getOutputStream(), StandardCharsets.UTF_8), true);
                 BufferedReader in = new BufferedReader(new InputStreamReader(
                         client.getInputStream(), StandardCharsets.UTF_8))) {
                System.out.println("[client] connected to 127.0.0.1:" + port);
                out.println("hello tcp");
                System.out.println("[client] reply: " + in.readLine());
            }
            serverThread.join(1000);
        }
    }
}
```

```text
输出（示意）：
[client] connected to 127.0.0.1:55798
[server] got: hello tcp
[client] reply: hello tcp
```

端口每次运行都不同（`ServerSocket(0)` 让操作系统挑空闲端口），其余行每次一致。逐步解释：

1. `new ServerSocket(0)`：创建监听 socket，绑定一个随机端口，只负责"接电话"。
2. `server.accept()`：阻塞等待。三次握手由操作系统内核完成：客户端 connect 发 SYN，内核回 SYN+ACK，收到 ACK 后连接进入就绪队列，accept 才把连接取出来。所以 accept 返回的每条连接，握手都已完成。
3. `new Socket("127.0.0.1", port)`：构造函数内部执行 connect，三次握手就发生在这一行。失败则抛异常（见下一个演示）。
4. 之后的 `out.println` 与 `in.readLine` 就是往字节流写、从字节流读。TCP 保证字节按序、不丢不重地到达对端。回声从服务端原样返回。
5. try 块退出时 `client.close()` 触发四次挥手：客户端先发 FIN。主动先关的客户端随后进入 TIME_WAIT。
6. 服务端 `readLine` 返回 null（TCP 语义：对端关闭，读到流末尾），循环结束，服务端也关闭连接。

### 反例：连接被拒长什么样

把端口换成几乎没有服务监听的 1 号端口：

```java
import java.net.Socket;

public class RefusedDemo {
    public static void main(String[] args) throws Exception {
        new Socket("127.0.0.1", 1);
    }
}
```

```text
运行异常：
Exception in thread "main" java.net.ConnectException: Connection refused: connect
    at RefusedDemo.main(RefusedDemo.java:5)
```

逐步解释：`new Socket` 向 127.0.0.1 的 1 号端口发 SYN。操作系统发现没有任何进程监听这个端口，回一个 RST 拒绝。内核把这次失败翻译成 `ConnectException` 从构造函数抛出。关键字 refused：连接被明确拒绝，握手第一步就没通过。它与"发出去石沉大海直到超时"是两类不同的失败，含义不同，重试策略也不同（见后文超时与重试节）。

代码层面：`Socket` 的行为属于 Java 标准库（`java.net` 包），握手本身由 TCP 协议规范（RFC 9293）规定。`IOException` 是受检异常，机制见 [java/10-06-exceptions.md](java/10-06-exceptions.md)。

### 可靠传输靠什么（概念级）

三个机制，各能说一句话即可：

- **确认重传**。TCP 给每个字节编号。接收方每收到一段就回 ACK（确认号是想要的下一个字节号）。发送方在超时前等不到 ACK 就重发。
- **滑动窗口**。接收方在 ACK 里通告"我还能收多少字节"（接收窗口），发送方不得超过，防止快的发送方淹死慢的接收方。这管的是两个端点之间。
- **拥塞控制**。管网络整体。发送方维护拥塞窗口：慢启动阶段每过一个往返翻倍；到达阈值后线性加一；收到三个重复 ACK 判定丢包，立即重传（快重传）并把窗口减半后线性增长（快恢复）；彻底超时则回到慢启动。下载"一开始慢后来快"就是这个增长曲线。Nagle 算法攒小包、延迟确认攒 ACK，两者相遇会造成几十毫秒的小包延迟，实时场景会关闭 Nagle，了解即可。

前三条是协议规范要求的行为（RFC 9293），具体参数（初始窗口多大、用 CUBIC 还是 BBR 算法）由操作系统实现决定。

## HTTP：TCP 之上约定说什么

### 问题：字节流没有含义

TCP 只保证"字节按序到达"，不解释字节的意思。上一节给 echo 服务端发 `hello tcp`，它原样收下，根本不知道这是问候还是指令。两个程序要通信，必须先约定格式：哪些字节表达"我想要什么"，哪些是数据本身。HTTP 就是浏览器与 Web 服务器之间的这份约定。

### 请求与响应报文

HTTP/1.1 是文本协议（HTTP/2 改为二进制，见后文）。一份请求报文：

```text
GET /r/abc HTTP/1.1              请求行：方法、路径、版本
Host: localhost:8080             头，一行一个，名字: 值
Cookie: lf_ac_abc=1769           头
                                 空行，标志头结束
                                 （GET 通常无体；POST 的参数放这里）
```

响应报文同构：

```text
HTTP/1.1 302                     状态行：版本、状态码、原因短语
Location: https://example.com/p  头
Content-length: 0                头
                                 空行
                                 （302 没有体）
```

RFC 9112 规定：行与行之间用 CRLF（回车加换行，`\r\n`）分隔，头结束的标志是一个空行。上面的示意图里空行看不见，但它真实存在，下一节的演示会看到。初学者手写 TCP 客户端发 HTTP 时最常见的错误就是漏掉最后的空行，服务器会一直等下去直到超时。

### 方法：请求行第一个词

方法表达"我想做什么"。常用五个：

| 方法 | 语义 |
|---|---|
| GET | 读取资源，不产生副作用 |
| HEAD | 同 GET，但只要头、不要体 |
| POST | 提交数据，通常产生副作用 |
| PUT | 整体替换资源 |
| DELETE | 删除资源 |

**幂等**（idempotent）：同一请求执行一次与执行一百次，服务器状态相同，就叫幂等。GET、HEAD、PUT、DELETE 幂等，POST 不幂等（提交两次可能建两条记录）。幂等是规范建议的语义；服务器是否遵守是它自己的实现问题（写坏的 GET 同样可能产生副作用），但遵守约定的系统才能安全重试。

HEAD 与本项目直接相关：链接预览、健康探测只想知道"这个短链活着吗、什么状态"，不值得传整个页面。LinkForge 为 `/r/{code}` 同时注册了 GET 与 HEAD 两个处理器（`RedirectController` 里的 `@GetMapping` 与 `method = RequestMethod.HEAD`）。

### 状态码：响应行中间的数字

三位数，首位分类，背族谱不背清单：

| 前缀 | 含义 | 常见成员 |
|---|---|---|
| 1xx | 收到了，继续 | 101（切换协议） |
| 2xx | 成功 | 200、204（无体） |
| 3xx | 去别处、用缓存 | 301、302、303、304、307、308 |
| 4xx | 请求方的错 | 400、401、403、404、410、429 |
| 5xx | 服务方的错 | 500、502、503 |

四个高频组合值得单独记。

401 与 403。401 = 未认证（unauthenticated），补上凭证可以再来；403 = 禁止（forbidden），换什么凭证都没用。LinkForge 有真实对照：带密码的短链在 HEAD 探测且无凭证时回 401（`RedirectOutcome.Locked` 分支渲染为 `HttpStatus.UNAUTHORIZED`），命中拦截名单的链接回 403（`RedirectOutcome.Blocked` 分支渲染为 `HttpStatus.FORBIDDEN`）。前者你可以输密码解锁后重试，后者无路可走。

429 与 `Retry-After`。429 = 请求太频繁（RFC 6585 定义），规范建议配 `Retry-After` 头告知"等多少秒再试"。LinkForge 的 `GlobalExceptionHandler.handleRateLimited` 就是这么写的：状态 429，`Retry-After` 的值取自滑动窗口的剩余时间。算法见 [50-redis.md](50-redis.md)。

410 与 404。404 = 找不到；410 = 明确的"曾经存在，已删除"。LinkForge 短链过期回 410、不存在回 404（`ShortLinkService` 的判定抛 `BizException(410, ...)` 或 `BizException(404, ...)`）。

301 与 302 的完整讨论见项目案例一。

### 常见头

头是键值对，名字大小写不敏感（规范规定，所以真实报文里能看到各种大小写）。高频的几个：

- `Host`：访问的域名。HTTP/1.1 规范要求每个请求必须带 Host，缺了服务器应回 400。一台服务器一个 IP 托管几百个网站，靠它区分请求该交给谁。
- `Content-Type`：体是什么类型（`text/html`、`application/json`）与编码。
- `Content-Length`：体多少字节。长度未知时改用 `Transfer-Encoding: chunked` 分块发送。
- `Location`：3xx 的跳转目标。
- `Set-Cookie` 与 `Cookie`：服务端下发、客户端回传，见 Cookie 节。
- `Cache-Control`：缓存策略，见安全头节的 no-store。
- `Connection`：连接管理，见连接复用节。

### 演示：亲手发一份 HTTP 报文

这一步把"HTTP 跑在 TCP 上"钉死。下面的自包含程序用 JDK 内置的 `com.sun.net.httpserver.HttpServer` 起一个模仿短链的本地服务（对 `/r/abc` 回 302 加 Location），然后不用任何 HTTP 客户端库，直接用上一节的 `Socket` 发送手写的报文文本，打印对端原样返回的字节。

```java
import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class HttpRawDemo {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/r/abc", exchange -> {
            exchange.getResponseHeaders().set("Location", "https://example.com/page");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();

        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(("GET /r/abc HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: close\r\n"
                    + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();

            InputStream in = socket.getInputStream();
            StringBuilder response = new StringBuilder();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) {
                response.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
            }
            System.out.print(response);
        }
        server.stop(0);
    }
}
```

```text
输出（示意）：
HTTP/1.1 302 Temporary Redirect
Date: Wed, 28 Oct 2026 16:30:53 GMT
Content-length: 0
Location: https://example.com/page
Cache-control: no-store

```

Date 每次运行都变，其余一致。想亲眼看见行尾的回车符，用 `java HttpRawDemo | cat -A` 跑一次，Git Bash 会把 CR 显示成 `^M`。逐行讲解：

- 程序手写的请求是三行加一个空行：`GET /r/abc HTTP/1.1`、`Host: localhost`、`Connection: close`，末尾的 `\r\n\r\n` 补出那个空行。这就是一份完整的 HTTP/1.1 请求，没有任何隐藏机制。
- 状态行 `HTTP/1.1 302 Temporary Redirect`：数字 302 才是规范定义的。RFC 9110 给 302 建议的短语是 Found，JDK 内置服务器却发出 Temporary Redirect。两个实现说法不一，正说明客户端应读数字、短语只给人看。
- 头名大小写不敏感：程序 set 的是 `Cache-Control`，服务器发出的是 `Cache-control`，客户端必须两者都认。
- `Content-length: 0`：302 没有体，长度为零。
- `Location: https://example.com/page`：浏览器读到 302 就对这个地址发起新请求。浏览器这一步是常见实现行为，规范描述的是语义。
- `Connection: close` 是请求里声明的，服务器照办后关闭连接，客户端的 `read` 才会返回 -1（流末尾）。不声明的话，服务器会保持连接等下一个请求。

结论：HTTP/1.1 没有魔法，就是双方按格式书写的文本，跑在 TCP 字节流上。浏览器、Tomcat、curl 之间交换的同样是这样的文本（HTTPS 只是把文本加密，格式不变）。

## 亲眼所见：curl -v 看一次真实请求

命令与完整输出（本机 Windows Git Bash，curl 8.19，2026-10-28 实测）：

```bash
curl -sv --noproxy "*" https://example.com -o /dev/null
```

参数解释：`-s` 关进度条（`-v` 的输出更清爽）；`--noproxy "*"` 忽略代理环境变量（我这台机器配了本地代理，直连才能看到完整的 DNS 与 TCP 过程，无代理的机器加不加都行）；`-o /dev/null` 把正文丢弃，只看握手与头。

```text
* Host example.com:443 was resolved.
* IPv6: 2606:4700:10::ac42:93f3, 2606:4700:10::6814:179a
* IPv4: 104.20.23.154, 172.66.147.243
*   Trying [2606:4700:10::ac42:93f3]:443...
*   Trying 104.20.23.154:443...
* schannel: disabled automatic use of client certificate
* ALPN: curl offers http/1.1
* ALPN: server accepted http/1.1
* Established connection to example.com (2606:4700:10::ac42:93f3 port 443) from fd22:a2b5:b398:3300:8cbf:dd34:a9e9:a965 port 61711 
* using HTTP/1.x
> GET / HTTP/1.1
> Host: example.com
> User-Agent: curl/8.19.0
> Accept: */*
> 
* Request completely sent off
* schannel: remote party requests renegotiation
* schannel: renegotiating SSL/TLS connection
* schannel: SSL/TLS connection renegotiated
< HTTP/1.1 200 OK
< Date: Wed, 28 Oct 2026 16:31:05 GMT
< Content-Type: text/html; charset=utf-8
< Transfer-Encoding: chunked
< Connection: keep-alive
< Server: cloudflare
< last-modified: Sat, 24 Oct 2026 20:05:47 GMT
< allow: GET, HEAD
< Accept-Ranges: bytes
< Age: 2
< cf-cache-status: HIT
< CF-RAY: a51b542beec2fda5-LAX
< alt-svc: h3=":443"; ma=86400
< 
{ [589 bytes data]
* Connection #0 to host example.com:443 left intact
```

不同时间运行，Date、Age、端口会变，结构不变。逐段讲解：

- 前三行是 DNS。`resolved` 表示查到了地址：两个 IPv6 加两个 IPv4。example.com 的 IP 随时可能变，这正是用域名而不是 IP 的理由（见全过程串讲）。
- 两个 `Trying` 是 TCP 连接尝试：先 IPv6 后 IPv4，IPv6 成功。端口 443 是 HTTPS 的约定端口。
- `schannel` 是 Windows 系统自带的 TLS 库，这几行是它的内部日志（renegotiation 是服务器要求重新协商 TLS 参数），不属于任何报文。OpenSSL 版的 curl 会打印更多握手细节，比如协商出的 TLS 版本与加密套件。
- 两行 ALPN。ALPN（应用层协议协商）是 TLS 握手里顺带的一问一答："加密之后跑 HTTP/1.1 还是 HTTP/2"。这台 curl 只提供 http/1.1，服务器接受，所以连接跑 HTTP/1.1。
- `Established connection ... port 61711`：连接建立完成，TCP 握手与 TLS 握手都结束了。末尾是本机地址与临时端口，客户端同样要占一个端口（操作系统随机分配），连接由四元组（源 IP、源端口、目标 IP、目标端口）唯一确定。
- `>` 开头的行是 curl 发出的请求字节，和上一节手写的报文一样：请求行加三个头。`Accept: */*` 表示什么类型都收。
- `<` 开头的行是响应。状态行 200 OK；`Content-Type` 声明体是 HTML；`Transfer-Encoding: chunked` 表示体分块发来（发送时总长未知）。
- `Connection: keep-alive` 与最后一行 `left intact` 连起来看：请求结束后 curl 没有关闭连接，留给后续请求复用，这就是 keep-alive（下一节）。
- `Server: cloudflare`、`cf-cache-status: HIT`、`Age: 2`：example.com 前面有 Cloudflare CDN（内容分发网络，把内容缓存到全球各节点）。HIT 表示这份响应是缓存副本，源站根本没被访问；`Age: 2` 表示副本已在缓存里放了 2 秒。HTTP 缓存是 304 之外另一套"少走路"的机制。
- `last-modified`（小写，头名大小写不敏感的现场例子）、`allow: GET, HEAD`、`Accept-Ranges: bytes`：体最后修改时间（协商缓存会用到）、允许的方法、支持断点续传。
- `alt-svc: h3=":443"`：服务器广告"我也支持 HTTP/3"，客户端下次可换协议（见后文）。
- `{ [589 bytes data]` 是进度显示，正文已被 `-o` 丢弃。

抓包工具（Wireshark、tcpdump）看到的也是这些：HTTPS 之下正文是密文，抓包看得见 DNS、握手与时序，看不见内容。排查 HTTPS 接口问题靠日志与响应头，不靠抓正文。

## 连接复用：keep-alive 与多路复用

问题：一次 HTTPS 请求有两笔固定开销，TCP 三次握手一个往返，TLS 握手一到两个往返。一个页面几十个资源，每次都重付一遍太贵。

HTTP/1.1 keep-alive：规范把持久连接设为默认，一条 TCP 连接上可以连续多对请求与响应。但同一连接上的请求必须排队，前一个响应没传完，下一个只能等，这叫队头阻塞。

HTTP/2：把报文拆成二进制帧，多个"流"在一条连接里交错传输。响应之间不再排队，一个慢响应只堵自己的流。ALPN 协商到 h2 用的就是它（上面 curl 只提供 http/1.1，所以没用上）。

HTTP/3：HTTP/2 的多流仍跑在一条 TCP 连接上，TCP 层丢包会堵住所有流（传输层队头阻塞）。HTTP/3 改用 QUIC（基于 UDP 自行实现可靠与加密），连接在网络切换时还能不断。curl 输出里的 `alt-svc` 头就是换协议的通道。

面试口径：每代解决什么，一句话一个。本文对这三代的深度到此为止。

## HTTPS/TLS：加密、完整、身份

### 问题：明文 HTTP 是明信片

上一节手写的报文是纯文本。咖啡馆 Wi-Fi 上的任何设备都能读到你发了什么、收到了什么，还能改掉（比如把响应里的长链换成钓鱼站），甚至冒充服务器。HTTPS = HTTP over TLS：在 TCP 与 HTTP 之间插一层 TLS（Transport Layer Security，传输层安全协议）。

明信片比喻只覆盖窃听这一件事，TLS 要做三件：

| 威胁 | TLS 对策 |
|---|---|
| 窃听（看得见） | 加密 |
| 篡改（改得了） | 完整性校验，改动后解密直接失败 |
| 冒充（装得像） | 证书，CA 背书域名与公钥的绑定 |

### 握手概览：非对称换钥匙，对称送快递

TLS 握手（TLS 1.3，RFC 8446）概念级四步：

1. 客户端发 ClientHello：支持的加密套件、随机数、自己的密钥交换参数。
2. 服务器回 ServerHello：选定套件、证书、自己的密钥交换参数。
3. 客户端验证证书（下一小节）。双方用 ECDHE 各自算出同一把对称会话密钥。ECDHE 是一类非对称算法：双方交换公开参数，各自能算出同一个秘密，而偷看了全部报文的第三方算不出来。
4. 之后所有 HTTP 报文用这把对称密钥加密。

为什么这样搭配：非对称算法慢得多，只负责在握手时"协商"出一次性密钥；对称算法快，负责加密之后的全部数据。非对称解决密钥分发，对称解决批量加密，是标准的分工。

证书：一份声明"example.com 的公钥是某某"，由证书颁发机构（CA）用自己的私钥签名。浏览器内置一列受信 CA。验证三件事：签名链能追溯到一个受信根、证书里的域名与访问域名匹配、在有效期内。冒充者拿不出有效证书，中间人攻击在证书这一步露馅。浏览器在证书无效时会警告，用户可以强行继续，这个逃生门是实现给的，规范没有义务放行。

TLS 1.2 握手要两次往返，1.3 压到一次并砍掉不安全的老算法；1.3 的 0-RTT（重连时第一轮就带数据）有被重放的风险（重放：攻击者把你这段数据原样再发一遍，服务器误以为是新请求），只适合幂等请求。了解即可。

对照 curl 输出：ALPN 两行就是握手里的一步，schannel 的日志行属于握手过程；握手内容本身（证书、密钥交换）在 `-v` 输出里不展开。

## 项目案例一：302 还是 301，短链的统计正确性

### 需求与问题

短链服务的核心动作：把 `/r/abc` 映射成长链，并让浏览器去长链。HTTP 给的机制是 3xx 状态码加 `Location` 头。301（永久移动）与 302（临时移动）的规范语义差别只有两个字：永久、临时。

为什么短链必须 302？推演 301 会发生什么（据规范语义推断）：浏览器把 301 当"永久事实"缓存下来，之后同一用户再点这个短链，直接去长链，请求不再到达短链服务。后果三条：点击统计断流（不回服务就记不到数）；改目标无效（运营把短链指向新地址，缓存过的用户还在去旧地址）；门禁失效（拦截、警示、解锁全部被绕过）。

短链的每一次跳转都需要回到服务：记一次点击、查一次状态（禁用、过期、门禁）。所以跳转必须"临时"。这是 LinkForge 的业务选择，依据正是 301 与 302 的规范语义差别。

规范与实现的补充：RFC 7231 列出的可启发式缓存状态码里有 301、没有 302。主流浏览器对 301 长期缓存；对不带缓存头的 302 基本每次回源（常见实现行为）。所以 302 不需要额外声明"别缓存"，而 301 想反悔只能换 URL。

### 现在的代码

`src/main/java/com/linkforge/link/controller/RedirectController.java` 的 `render` 方法（第 85 行起）按判定结果渲染响应，放行分支：

```java
if (outcome instanceof RedirectOutcome.Redirect redirect) {
    return ResponseEntity.status(HttpStatus.FOUND)
            .location(URI.create(redirect.url()))
            .build();
}
```

逐行讲：

- `outcome instanceof RedirectOutcome.Redirect redirect`：判定结果是密封接口（sealed interface）的一个分支类型，`Redirect` 代表放行。模式匹配是 Java 16 正式特性，背景见 [java/10-01-types-and-references.md](java/10-01-types-and-references.md)，四种判定定义在 `src/main/java/com/linkforge/link/service/RedirectOutcome.java`。
- `ResponseEntity.status(HttpStatus.FOUND)`：状态码 302。FOUND 是 Spring 沿用规范建议短语起的常量名。
- `.location(URI.create(redirect.url()))`：写 `Location` 头，值是长链。
- `.build()`：无体。302 的职责是指路，不是送内容。浏览器收到后对 Location 发起新请求，新请求重新走一遍全过程。

### 同一个控制器里的 303

解锁成功走的是另一个状态码（`unlock` 方法，第 66 行起）：

```java
return ResponseEntity.status(HttpStatus.SEE_OTHER)
        .header(HttpHeaders.SET_COOKIE, cookie.toString())
        .location(URI.create("/r/" + code))
        .build();
```

303（See Other）明确告诉浏览器"用 GET 去访问 Location"。用户刚提交的是 POST 表单（密码），303 把后续导航转成 GET：刷新页面不会重复提交密码，浏览器还能带上刚下发的解锁 Cookie 重访 `/r/{code}`。这套写法叫 POST-Redirect-GET。

### 如何自己验证

本地把应用跑起来之后，`curl -i http://localhost:8080/r/某个码` 能看到 `HTTP/1.1 302` 与 `Location:` 头。再用 `curl -I`（发 HEAD）：状态相同但无体，且这条探测不记点击（`ShortLinkService.resolveForProbe`，第 358 行，不分类不记录；GET 走的 `resolveForGet` 在第 348 行记录点击）。

## 项目案例二：Cookie，让无状态协议记住"你解锁过"

### 问题

HTTP 是无状态的：每个请求互相独立，服务器不记得上一秒给这个浏览器发过什么。带密码的短链解锁一次之后，下一次 GET 怎么还放行？

机制：服务器在响应里加 `Set-Cookie` 头，浏览器把键值存起来，之后对"匹配的"请求自动带上 `Cookie` 头，服务器据此认出"这是解锁过的浏览器"。存不存、带不带由浏览器实现按规范执行；服务器只能下发与读取。

### 现在的代码

`RedirectController.unlock`（第 59 行起）下发解锁 Cookie：

```java
ResponseCookie cookie = ResponseCookie.from(linkAccessTokens.cookieName(code), result.cookieValue())
        .path("/r/" + code)
        .httpOnly(true)
        .sameSite("Lax")
        .maxAge(Duration.ofSeconds(linkAccessTokens.ttlSeconds()))
        .secure(request.isSecure())
        .build();
```

属性逐个讲。属性是规范（RFC 6265）定义的指令，浏览器按指令执行：

- 名字与值：名字是 `lf_ac_` 加短码（`LinkAccessTokens.cookieName`），值是带签名的令牌（过期时间加 HMAC 签名，HMAC 是带密钥的哈希认证码，持有密钥才能伪造；服务器无状态可验，不存会话表，见 `src/main/java/com/linkforge/link/support/LinkAccessTokens.java`）。安全细节见 [32-spring-security.md](32-spring-security.md)。
- `Path=/r/{code}`：浏览器只在访问该路径（及子路径）的请求上带这条 Cookie。作用域最小化：别的路径拿不到它。注意 Path 只是暴露面控制，不是安全边界：同源脚本换个路径仍可能覆盖同名 Cookie。
- `HttpOnly`：脚本（JS 的 `document.cookie`）读不到这条 Cookie。防的是 XSS（跨站脚本）偷令牌：页面即使被注入脚本也带不走它。防不住请求本身：浏览器发送时照样带上。也不改变 Cookie 在传输里是否加密（那由 HTTPS 决定）。
- `SameSite=Lax`：浏览器只在"同站请求"或"跨站顶级导航的 GET"上带它；跨站的 POST、iframe、fetch 不带。防的是 CSRF（跨站请求伪造）的大部分场景。
- `maxAge`：有效期秒数（配置 `linkforge.link-access.ttl-seconds`，默认 3600），过期后浏览器不再发送。
- `secure`：只在 HTTPS 连接上回传。值来自 `request.isSecure()`，即本连接是否走了 TLS：本地 `http://localhost` 调试时为 false，生产 HTTPS 下为 true。

四个属性的分工：Path 管发到哪，HttpOnly 管谁能读，SameSite 管跨站带不带，Secure 管明文带不带。

### 一次解锁的完整往返

POST `/r/{code}/unlock`（表单体带密码）到服务端校验（先两层限流再 BCrypt 比对密码哈希，BCrypt 是单向的密码哈希算法，数据库只存哈希不存明文，见 `ShortLinkService.unlock` 第 499 行起）返回 303 加 Set-Cookie 加 Location `/r/{code}`，浏览器 GET `/r/{code}` 并自动带上 Cookie，验签通过后 302 到长链。

## 项目案例三：X-Forwarded-For，请求头是输入不是事实

### 问题

限流按 IP 计数（跳转限流键形如 `redirect:ip:{ip}`，见 `ShortLinkService.resolveTarget`）。IP 从哪来？Servlet 规范给的是 `request.getRemoteAddr()`：这条 TCP 连接对端的地址。连接是谁建立的，这个值就是谁，无法伪造。

麻烦出现在反向代理（reverse proxy，替真实服务器接收请求的前置服务，如 nginx）部署时：所有浏览器的连接都先到 nginx，应用看到的 `remoteAddr` 全是 nginx 的地址，全站用户共享同一个限流计数，一人超限全员遭殃。

行业约定：代理把"这个请求我从哪个地址收到"追加进 `X-Forwarded-For` 头传给下一跳，多级代理形成链条：

```text
浏览器 1.2.3.4 -> 代理A -> 代理B -> 应用
应用收到的 X-Forwarded-For: 1.2.3.4, A的IP
应用收到的 remoteAddr: B的IP
```

问题：请求头是请求报文的一部分，谁都能写。攻击者直接发 `X-Forwarded-For: 8.8.8.8`，代理追加后链条变成 `8.8.8.8, 1.2.3.4, A`，最左边可能是伪造的。正确的姿势是从右往左数，只信可信代理追加的条目。还要知道：XFF 不是 RFC 标准，只是事实约定（标准化版本是 RFC 7239 的 `Forwarded` 头），格式与信任模型全靠约定。

### 现在的代码

`src/main/java/com/linkforge/common/util/ClientIps.java` 全文只有一个方法：

```java
public static String resolve(HttpServletRequest request, boolean trustXff) {
    if (trustXff) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
    }
    return request.getRemoteAddr();
}
```

逐行讲：

- 参数 `trustXff` 是信任开关。false 时根本不看这个头，直接用连接对端地址。类注释写明原因：默认客户端可以伪造该头，绕过按 IP 的限流。
- true 时取头里的第一段。取第一段成立的业务前提：前面恰好有一层可信反代，且它处理了这个头（例如 nginx 配置 `proxy_set_header X-Forwarded-For $remote_addr`，用直接客户端地址覆盖，而不是无脑追加）。据代码推断，项目假设部署方保证这一前提；若反代只是追加，第一段仍可被伪造。
- 配置在 `src/main/resources/application.yml`：`trust-xff` 默认 false，注释写着 "Honor X-Forwarded-For only when a trusted reverse proxy fronts the app"。默认值就是防线。

信任边界一句话：`remoteAddr` 是网络层事实，请求头是应用层输入。把输入当事实，是安全漏洞的常见起点。同样的坑在 Cookie 一节出现过：`request.isSecure()` 在 TLS 由反代终结时也为 false，处理思路一致，只有可信来源的声明才采信。

## 项目案例四：安全响应头，门禁页的三道闸

### 问题

短链服务会把用户提交的目标 URL 渲染进 HTML（警示页显示长链），还要渲染拦截页、解锁页。这类页面有两个风险：内容被浏览器误判执行；页面被缓存污染后发给错误的人。HTTP 响应头是服务端可下的指令，浏览器按指令收紧行为。

### CSP 常量逐指令

`src/main/java/com/linkforge/link/support/GatePages.java` 第 13 行：

```java
public static final String CSP = "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'";
```

CSP（Content-Security-Policy，内容安全策略）是一份"本页面允许做什么"的白名单，由浏览器执行。逐个指令讲：

- `default-src 'none'`：所有资源类别（脚本、图片、字体、脚本发起的数据请求等）默认一个都不许加载，无任何例外来源。
- `style-src 'unsafe-inline'`：唯一放宽的一项，允许内联 style 属性。门禁页用 `style="..."` 排版，没有外部样式表。
- `form-action 'self'`：表单只能提交到本站。解锁表单 POST `/r/{code}/unlock` 因此放行，把密码偷传到外部站点的表单被禁止。
- `base-uri 'none'`：禁止 `<base>` 标签改写相对地址基准，防止表单 action 这类相对路径被悄悄指到别处。
- `frame-ancestors 'none'`：禁止任何站点把本页放进 iframe。防点击劫持（攻击者用透明 iframe 盖住自己的页面骗用户点击）。

`RedirectController.render` 给每个门禁分支都加这个头（Blocked、Locked、Warn 三个分支各写一遍，第 93 到 111 行）。

### 配套的头

`X-Content-Type-Options: nosniff`：历史上浏览器会"嗅探"响应内容，哪怕声明 text/plain，猜着像脚本也可能当脚本执行。nosniff 禁止嗅探，浏览器必须严格按 `Content-Type` 处理。与 CSP 组成双保险：即使有人注入了脚本标签，`default-src 'none'` 让它加载不了外部脚本，nosniff 保证响应不会被误判执行。

`Cache-Control: no-store`：禁止任何环节（浏览器、代理、CDN）把这份响应存下来。为什么门禁页需要它：警示页含动态内容（转义后的目标 URL），任何一层缓存副本都可能把 A 看到的页面发给 B，或让已解除的拦截页复活。这是项目的设计意图（据代码与注释推断）。三个常见值辨析：

| 值 | 语义 |
|---|---|
| no-store | 完全不存，请求与响应都不落缓存 |
| no-cache | 可以存，但每次用前必须回源验证 |
| max-age=N | N 秒内直接用本地副本，不发请求 |

`Referrer-Policy: no-referrer`（Warn 分支，第 109 行）：用户点"仍要继续"跳去目标站时，浏览器默认带 Referer 头（来源页面地址，这个头的历史拼写就是少个 r）。no-referrer 让浏览器不带，短链的中间地址不暴露给目标站。

纵深防御小结：`GatePages` 的注释写明每个动态值都过 `HtmlUtils.htmlEscape` 转义（第一道；转义是把 `<`、`&` 这类字符换成不会被当作标签执行的等价写法），CSP 与 nosniff 兜底（第二道）。安全头是浏览器执行的服务端指令：发出去没人执行就白搭，所以它们依赖实现遵守规范。

## 全过程串讲：浏览器访问 /r/abc

把所有概念按顺序串起来。每步标注属别：规范（协议规定）、实现（浏览器与服务器常见行为）、项目（LinkForge 代码）。

1. 浏览器解析 URL：协议 http、主机 localhost、端口 8080、路径 `/r/abc`。端口省略时用协议默认值（http 80、https 443）。实现。
2. DNS 查询：localhost 是特殊名字不用查；真实域名按"浏览器缓存、操作系统缓存与 hosts 文件、递归解析器（运营商或公共 DNS）、根服务器、顶级域服务器、权威服务器"的顺序找到 IP。规范加实现。
3. TCP 连接：浏览器发起三次握手（对应 Java 的 `new Socket`）。规范。
4. 若是 https：TLS 握手，协商密钥并验证证书。规范加实现。
5. 浏览器写 HTTP 请求报文：`GET /r/abc HTTP/1.1`、`Host`、该路径已有的 Cookie（若有）。规范。
6. 操作系统按端口 8080 把字节流交给监听进程 Tomcat；Tomcat 解析报文、构造 `HttpServletRequest`，经安全过滤器链与 Spring MVC 路由进入 `RedirectController.redirect`。实现（Tomcat 与 Spring 框架行为）。
7. 项目判定链：限流（Redis 上的滑动时间窗口计数，与 TCP 的滑动窗口是两回事；键含 `ClientIps.resolve` 的 IP）到 Bloom 前置过滤（用概率结构快速判断短码从未存在过，从未存在直接 404，不查库）到缓存读（Redis 加本地内存缓存 Caffeine）到 MySQL 行状态与过期检查，得出放行、拦截、警示、锁定四种判定。项目逻辑，细节见 [50-redis.md](50-redis.md) 与 [31-spring-boot-mvc.md](31-spring-boot-mvc.md)。
8. 放行分支记录点击（预取请求除外，见 `resolveForGet`），返回 `ResponseEntity`：302 加 `Location` 长链。项目。
9. Tomcat 把响应对象序列化成 HTTP 报文写回 TCP 连接。实现。
10. 浏览器读到 302，读 `Location`，对长链重新走第 2 到第 6 步（新域名重新 DNS、重新握手）。实现。
11. 长链响应 200，浏览器解析 HTML、加载子资源、渲染页面；连接按 keep-alive 复用。实现。

带密码的短链多一个往返：第 8 步判定为 Locked 时，GET 返回 200 与解锁页（HEAD 返回 401），用户提交 POST 解锁，走 Cookie 节的 303 往返后再进入第 10 步。

面试口述这份清单时，每一层准备一个可追问的深度点（三次握手为什么三次、TLS 怎么防冒充、302 为什么不能换 301），本文各节都讲过。

## 超时与重试：出站调用的纪律

前面都在讲"别人连我"。项目里也有"我连别人"：webhook 通知把点击事件 POST 到用户注册的地址。出站调用必须设超时，`src/main/resources/application.yml` 的 webhook 段有两项：`connect-timeout-ms` 默认 2000（建立 TCP 连接最多等 2 秒）与 `request-timeout-ms` 默认 5000（发出请求后等响应最多 5 秒）。不设超时的调用是悬着的剑：对端不回，线程永远挂着，批处理调度被慢慢拖死。

三种"断"要分清，重试含义完全不同：

- 连接被拒：`ConnectException`，对端没监听或被拒。快速失败，立刻重试大概率还是拒。
- 读超时：请求发出去了，对端没在时限内回。结果未知：可能没收到，也可能处理完了只是回程慢。盲目重试可能造成重复。
- 连接被重置：中途收到 RST，同样结果未知。

重试前先问三件事：操作幂等吗（GET 重试安全，POST 要配幂等键，即一个唯一的请求号，服务端据它识别并拒绝重复请求）；上游还有余量吗（各家等一个随机化的、逐次加长的时长再重试，避免在同一时刻齐发，形成重试风暴）；重试会让故障更糟吗。答不上来就先别重试。这套纪律与 429 加 `Retry-After` 是一体两面：限流方告诉你等多久，调用方据此决定退避时长。

## 常见错误与误区

- "HTTPS 了所以 URL 也看不见"。TLS 加密报文，但域名在 DNS 查询与 TLS 握手的 SNI 字段里仍是明文（SNI，server name indication，客户端在加密开始前就得告诉服务器要访问哪个域名，否则服务器不知道出示哪张证书）。路径上的设备看得到你访问了哪个站；路径与查询串在加密的报文里。
- "302 和 301 差不多"。见项目案例一：301 被缓存后统计断流、改目标无效。
- "keep-alive 的连接不会断"。空闲超时、中间设备（负载均衡器、网关）回收都会断，客户端要有重连逻辑。
- "超时设长一点保险"。超时是止损线不是保险丝，长超时只是推迟故障暴露，还占着线程。
- "XFF 第一段就是客户端"。整条头都可能被伪造，只有可信代理追加的部分才可信，见项目案例三。
- "接口慢就重试"。见超时与重试节的三问。
- 手写 HTTP 客户端漏掉头后的空行。服务器一直等空行等到超时，见 HTTP 报文节。

## 自测

1. 三次握手中，第三次 ACK 之后服务端才确认了什么？
   答：确认客户端能收（它收到了我发的 SYN+ACK 并回应了）。加上前两次已确认的"对方能发、我能收"，此时双方才算把收发四种组合全部验证完。两次握手时服务端缺这条确认，也无法否决迟到的旧 SYN。
2. 把 `RedirectController` 的 `HttpStatus.FOUND` 改成 `MOVED_PERMANENTLY`，功能看起来还正常，坏在哪？
   答：浏览器缓存 301 后，下次直接去长链，不再请求短链服务。点击统计断流，修改目标对老用户无效，门禁（拦截、警示、解锁）被绕过。
3. 解锁 Cookie 的 `HttpOnly` 防什么、不防什么？
   答：防 XSS 脚本用 `document.cookie` 读走令牌；不防请求本身（浏览器发送时照样带上），也不改变传输是否加密（那由 HTTPS 决定）。
4. `trust-xff` 默认 false 时，限流键里的 IP 从哪来？为什么这个默认值是安全的？
   答：来自 `request.getRemoteAddr()`，即 TCP 连接对端地址，无法伪造。XFF 是请求头，属于可伪造输入，只有确认前面有可信反代时才应采信。
5. 主动关闭连接的一方为什么要在 TIME_WAIT 停 2MSL？
   答：一是最后的 ACK 若丢失，对方重发的 FIN 需要这个状态来补发 ACK；二是让本连接的旧报文在网络里自然消亡，不污染复用同端口的新连接。
6. `curl -v` 输出里 `>` 与 `<` 开头的行分别是什么？
   答：`>` 是客户端发出的报文行（请求行与请求头），`<` 是服务端返回的报文行（状态行与响应头）；带 `*` 的是 curl 自己的过程注释，不属于任何报文。

## 面试追问

- 三次握手为什么不能是两次？先答"确认矩阵缺一格"：两次只让客户端确认了双方收发，服务端还不知道客户端能不能收。再答历史 SYN：旧连接请求迟到，两次握手下服务端会白白建连接占资源，第三次握手的 ACK 给了客户端否决权。追问"四次挥手为什么不能三次"：收到 FIN 只代表对方发完了，我方可能还有数据，半关闭状态下 ACK 与 FIN 无法总是合并。
- 301、302、303、307、308 各是什么语义？301 永久、302 临时；303 让后续用 GET（POST-Redirect-GET）；307 与 308 是保持原方法的临时、永久版本（302 历史上被实现改成改用 GET 重发 POST，307/308 修正了这一点）。追问"短链为什么 302"：统计正确性，每次跳转必须回服务。
- HTTPS 握手交换了什么？证书（身份）与密钥交换参数（ECDHE 协商出对称会话密钥）。追问"为什么之后不用非对称加密数据"：非对称慢几个数量级，只用于协商；追问"TLS 1.3 快在哪"：一次往返，0-RTT 的代价是可重放，只适合幂等请求。

## 下一步

- 状态码与方法在 Spring MVC 的落地：[31-spring-boot-mvc.md](31-spring-boot-mvc.md)。
- 安全头背后的攻击模型与防御落地：[32-spring-security.md](32-spring-security.md)。
- 限流与 `Retry-After` 的 Redis 实现：[50-redis.md](50-redis.md)。
- 连接池在数据库侧的预算账：[40-mysql.md](40-mysql.md) 与 [60-testing-ci.md](60-testing-ci.md)。
- 端口、进程与文件描述符的操作系统视角：[21-os.md](21-os.md)。
- 本文示例里的受检异常与 try-with-resources：[java/10-06-exceptions.md](java/10-06-exceptions.md)。
