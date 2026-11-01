# LinkForge · Demo 讲稿（10–15 分钟 · C5 功能冻结）

> **用途**：春招现场照着说。默认 **12 分钟**，卡点可以收到 10 分钟，追问多就用到 15 分钟。  
> **对齐**：能力是 C5 收口 `f955313`（合并 `e9ffff4`，跳转代码 `e0c567c`）。`d1e94b2` 只补了 progress 里的 CI / PASS 计数，没有新行为。命令以仓库 README「五步跑通」和「面试 Demo 五步」为准。简历口径见 [`docs/resume.md`](resume.md)。  
> **数字**：只报 **319 = 151 UT + 166 IT + 2 tz**。pom **2.0.0**。  
> **本文件没有新的 Compose 实测。** `SMOKE OK` / `FAULT DRILL OK` 的原文在 [`docs/progress.md`](progress.md) C5 章，实跑日 2026-11-01 Asia/Shanghai，代码 tip **`e0c567c`**（smoke **8** PASS，fault-drill **7** PASS，无 SKIP）。现场要跑就现场跑；没跑就说「证据在 progress，我今天不临时改口」。

---

## 时间轴（默认 12 分钟）

| 钟点 | 段 | 做什么 | 可砍 |
|------|----|--------|------|
| 0:00–1:00 | 电梯稿 | 站着说，先不敲命令 | 不砍 |
| 1:00–1:45 | 环境 | `docker compose ps`，应用已在 8080 | 已起就指一下，20 秒 |
| 1:45–3:15 | 步 1 | 建链 + `302` + `Location` | 不砍 |
| 3:15–5:45 | 步 2 | A/B 的 `Location` 变化 + `dim=variant` | 不砍 |
| 5:45–7:15 | 步 3 | CSV 两行成功、一行失败 | 不砍 |
| 7:15–9:30 | 步 4 | fault-drill，或口播三段 | 时间紧改口播 40 秒 |
| 9:30–10:30 | 步 5 | SSRF 只口播 | 不砍 |
| 10:30–11:15 | 收尾 | 五步对上能力，主动说没做的 | 可压到 20 秒 |
| 11:15–12:00 | 缓冲 | 哪步卡住就用下面的「失败了」 | 没用到就停 |
| +3 分钟 | 可选 | 观测栈或一道追问 | 没起 Grafana 就别现场起 |

只剩 8 分钟：电梯稿 40 秒 → 步 1 → 步 2 → 步 3 → 步 5 口播 30 秒。故障演练改成一句「主链路 302 不依赖 Redis / 点击写 / Webhook」，并指 progress 里那 7 行 PASS。

---

## 开场就守住的禁区

下面这些句子现场不要说：

- 「现在 Linux 上能到 xxx QPS」「万级」「压测已经复现过当前 tip」。B10 没跑。被问再翻 [`docs/bench.md`](bench.md)，并声明那是 2026-10-24 Windows、固定窗口时期：health 约 888 QPS，关同步明细后跳转 13→276 QPS、P50 678→31ms。那不是今天这个 jar 的成绩。
- 「做了 Geo / 按地区分流 / 自定义域名 / 已上云 / 集群 Bloom / 出口代理」。
- 「版本 2.1」。pom 是 **2.0.0**，C1–C5 没另发版。
- 「我刚才又跑了一遍 Compose，全绿」。除非你本场确实看到终行 `SMOKE OK` 或 `FAULT DRILL OK`。
- 现场改 hosts、配恶意 DNS、扫内网来「证明 SSRF」。只讲设计和测试名。

测试句可以背下来：**319，151 个单测，166 个 Testcontainers 集成，2 个时区 fork。**

---

## 60 秒电梯稿（进门先说完再动手）

我做的是 LinkForge，自研的多租户短链，不是拿开源项目换皮。技术栈是 Spring Boot 3、MySQL、Redis、RabbitMQ，仓库版本停在 2.0.0。

跳转是 302。读的时候先限流，再 Bloom，再空值缓存，然后 Caffeine 本地缓存加 Redis，并发用 singleflight 合并。即便缓存命中，每个请求还是读库里的那一行，所以禁用和过期不会被旧缓存放出去。

点击不在热路径上发 MQ。明细和 outbox 行是同一个事务写进去的，后台再投递。断了消息队列，302 还在，恢复以后自己追平。

开放面上我做了租户 API Key、Webhook、二维码和 UTM、A/B 权重分流，还有批量 CSV 导入。Webhook 这边把 DNS rebinding 关掉了：投递时只解析一次，每个 IP 都校验，连接只打到校验过的地址，用的是 httpclient5 的 DnsResolver。Redis 挂了、点击那次写库失败了、Webhook 对方 5xx，用户这边仍然是 302。`fault-drill.sh` 能把这三段演出来。

当前这条线 319 个测试是绿的，C5 之后功能冻结。我先按 README 的五步 Demo，细节你们随时打断，我先把主链路走完。

**不要在电梯稿里报 QPS。**

---

## 上场前检查（开摄像头之前做完）

- [ ] 仓库根目录有 `.env`（从 `.env.example` 来，口令不进 git）
- [ ] `docker compose ps` 里 mysql、redis、rabbitmq 都是 healthy。MySQL 第一次初始化可能要几分钟，别在面试官看着的时候等初始化
- [ ] `java -jar target/linkforge-2.0.0.jar` 已经在 **8080** 前台或另一个终端里跑着。应用不读 `.env` 文件，启动那个窗口要先 `source .env`
- [ ] 终端字号够大。Windows 上请求用 `curl.exe`，bash 脚本用 Git Bash
- [ ] 下面的 `/tmp/links-demo.csv` 可以现场 `cat`，也可以提前放好
- [ ] 默念：319 · pom 2.0.0 · 不报 B10 · 不说 Geo · SSRF 只口播
- [ ] fault-drill **要么开场前自己跑通过，要么改口播**。不要在面试里第一次调试这个脚本
- [ ] 若准备实跑 fault-drill：机器上要有 `powershell.exe`（Webhook 的 5xx 桩）和 `.env` 里的 `MYSQL_ROOT_PASSWORD`。Linux 上常常没有 PowerShell，脚本会打印 `SKIP webhook`，那是设计，不是失败

---

## 0. 开场 20 秒（电梯稿说完，手放到键盘上）

**你说：**

环境是本机 Docker Compose，MySQL、Redis、RabbitMQ 三个依赖。应用是 `linkforge-2.0.0.jar`，端口 8080。口令在 `.env`，没有提交。我按 README 的面试五步走：建链 302、A/B、CSV 部分成功、故障、然后用嘴把 SSRF 讲清楚。冒烟脚本的期望终行是 `SMOKE OK`；C5 收口时在 progress 里留过一次实跑，今天以你们眼前的输出为准。

**指：** 终端提示符在仓库根目录。可选打开 `http://localhost:8080/`，说明这是可选控制台，底下就是同一套 API。

---

## 1. 环境就绪（约 45 秒；已经起来就只指）

README 的 bash 是这三行。PowerShell 用 `Copy-Item .env.example .env`，compose 命令相同。

```bash
test -f .env || cp .env.example .env
docker compose up -d
docker compose ps
```

**你说：** 三行都是 healthy 就可以往后走。应用不是 compose 起来的，是宿主机上的 jar。看 README 第 3 步，这个窗口要先把 `.env` 导进环境再启动：

```bash
set -a
source .env
set +a
java -jar target/linkforge-2.0.0.jar
```

已经在跑就不要再起一个，8080 会被占。

健康检查（README 第 4 步）期望正文里有 `"status":"UP"`：

```bash
curl -s http://localhost:8080/actuator/health
```

冒烟（README 第 5 步）。时间紧就跳过，说「开场前已对过 progress 里的 8 行 PASS」：

```bash
bash scripts/smoke.sh
```

期望最后一行是 `SMOKE OK`，退出码 0。脚本自己会登录、建链、看 302、看 Redis、再走一遍禁用后 404。它每轮还会注册用户；注册限流默认 5 次 / 600 秒，**不要连跑**。

**登录，后面的 curl 都用这个 TOKEN**（README 第 4 步；没有 `python3` 就改成 `python`）：

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"tenantName":"'"${LINKFORGE_BOOTSTRAP_TENANT:-demo}"'","username":"'"${LINKFORGE_BOOTSTRAP_USERNAME:-admin}"'","password":"'"${LINKFORGE_BOOTSTRAP_PASSWORD}"'"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["token"])')
echo "${#TOKEN}"
```

`echo` 的长度应该是一大截，不要把 token 本身投到共享屏幕上停留太久。Windows PowerShell 用 README 里的 `curl.exe` + `ConvertFrom-Json`，不要粘贴 `source` 和行末反斜杠。

**失败了：**

| 现象 | 你怎么处理、怎么说 |
|------|-------------------|
| `TOKEN` 为空 | 把登录 JSON 打出来看，不要念密码。多半是这个窗口没 `source .env`，或 bootstrap 口令不对。 |
| compose 不是 healthy | 不要现拉镜像。改口播，并说依赖没就绪，Demo 改讲设计和 progress 里的证据。 |
| 8080 拒绝连接 | jar 没起来，或起在了别的端口。`fault-drill.sh` 自己占用的是 **8083**，不要跟 8080 搞混。 |
| `SMOKE FAIL` | 读它打印的那一行原因。连跑注册会 429。不要说「测试套件挂了」——这是冒烟脚本，和 319 个 `mvn verify` 不是同一次。 |
| MySQL `Access denied` | README 常见坑：口令写在数据卷里，只改 `.env` 不会改已初始化的账号。现场不要 `docker compose down -v`。 |

---

## 2. 步 1 · 建链 + 跳转 302（约 90 秒）

**操作**（在 README 第 4 步的 curl 上把短码接出来，避免手抄错）：

```bash
CREATE=$(curl -s -X POST http://localhost:8080/api/links \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"originalUrl":"https://example.com"}')
echo "$CREATE"
CODE=$(echo "$CREATE" | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["code"])')
echo "CODE=$CODE"

curl -s -D - -o /dev/null "http://localhost:8080/r/$CODE"
```

**你说：**

先看创建。HTTP 是 200，包体是 `{code, message, data}`，业务 `code` 为 0 才是成功。短码在 `data.code`，另外还有 `data.shortPath`。这不是裸 JSON 顶上就一个 code。

再看跳转。我用的是 GET，带响应头，丢掉 body。第一行应是 `HTTP/1.1 302`，`Location` 是 `https://example.com`。不要用 `curl -I`，那是 HEAD。HEAD 可以探测，但**不记点击**；等下讲 A/B 统计时如果用了 HEAD，桶是空的，不是 bug。

这条读路径我按顺序做：滑动窗口限流，Bloom 挡从没出现过的码，空值缓存挡确认不存在的码，然后 L1/L2，同时来的请求用 singleflight 合成一次读库。缓存里可以有 URL，但判定禁用和过期以这一次读到的行为准，所以旧缓存不能把已禁用的码放成 302。

**指：** 状态行里的 `302`，以及 `Location: https://example.com`。有控制台的话，指列表里刚出来的那一行，说明 UI 打的是同一个 `POST /api/links`。

**失败了：**

| 现象 | 你怎么处理、怎么说 |
|------|-------------------|
| 401 | TOKEN 过期或没带上。重新登录，不要改代码。 |
| 400，URL 不合法 | 必须 `http://` 或 `https://`。`example.com` 不加协议会失败，这是校验，不是服务挂了。 |
| 404 | 短码抄错，或抄进了保留字。用上面的 `CODE=` 那一行。 |
| 429 | 创建默认 30 次/60 秒，跳转默认 300 次/60 秒。停一下，说限流是 ZSET 滑窗，响应带 `Retry-After`。 |
| 500 | 不要现场翻栈改代码。看 jar 那个终端的最后一屏，多半是 MySQL/Redis 没连上。改口播步 1 的设计。 |

**别说：** 不要把这一下说成「缓存命中所以没读库」。口径是每请求仍读行。

---

## 3. 步 2 · A/B 权重分流（约 2 分 30 秒，必做）

**操作：**

```bash
AB=$(curl -s -X POST http://localhost:8080/api/links \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{
    "originalUrl":"https://example.com/default",
    "variants":[
      {"key":"a","url":"https://example.com/a","weight":1},
      {"key":"b","url":"https://example.com/b","weight":3}
    ]
  }')
echo "$AB"
AB_CODE=$(echo "$AB" | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["code"])')
echo "AB_CODE=$AB_CODE"

for i in 1 2 3 4 5 6; do
  curl -s -D - -o /dev/null "http://localhost:8080/r/$AB_CODE" | grep -i '^Location:'
done
```

**你说：**

`originalUrl` 仍然必填。密码、域名门禁、UTM 都评估这条链上的 URL，不评估某一个 variant。这些门都过了，才按权重抽签。weight 1 和 3 是相对权重，不是百分之 1 和百分之 3，加总也不必是 100。抽签是随机的，不是轮询。六次里面 b 通常更多，但不是精确的 1.5 比 4.5；若六次碰巧全是 b，我会再说一句「再来一轮」，不会把它说成故障。

有 variants 的时候，最终 Location 以抽中的那个 URL 为准。L1/L2 仍然可以缓存链上的默认 URL，当加速提示，不能盲信缓存里的 URL 当跳转结果。没有 variants 的旧链，行为跟刚才那条一样，还是跳 `originalUrl`。

每条链最多 10 个 variant，key 区分大小写。改 variants 会走原来的 CacheBus，把各进程的 L1 废掉。

**然后等统计。** 302 当时写的是点击明细和 outbox；`dim=variant` 这一桶是消费端写的。outbox relay 默认初始延迟 3 秒、之后每 2 秒一轮。先停 5 秒再查，不要指着空数组说「统计坏了」。

```bash
sleep 5
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/links/$AB_CODE/stats/breakdown?dim=variant"
```

**你说：**

响应还是包在 `data` 里。有点击进来之后，应能看到 `a` 和 `b` 两行，至少能看到你刷到过的那个 key。没有抽中的 key 不会为了「展示完整」去补一行零。没挂 variant 的链不会写这个维度。

非法维度直接 400，例如 Geo。白名单只有 `device`、`referer_host`、`os`、`browser`、`variant`。

```bash
curl -s -D - -o /dev/null -w 'HTTP %{http_code}\n' \
  -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/links/$AB_CODE/stats/breakdown?dim=geo"
```

**你说：** 这是 400。消息大意是 dim 必须是那五个之一。我没有做 Geo，所以 allowlist 里故意没有它。

**指：** 几行不同的 `Location`；breakdown JSON 里的 `dimValue` / 计数；`dim=geo` 那次的 `HTTP 400`。

**失败了：**

| 现象 | 你怎么处理、怎么说 |
|------|-------------------|
| 六次 Location 完全一样 | 权重随机允许这样。再跑一轮 for 循环。仍只有一个，看创建 JSON 里 `variants` 是否真的写上了。 |
| Location 一直是 `/default` | 多半是这条链没有 variants，打成了步 1 的 `CODE`。看 `AB_CODE`。 |
| breakdown 是空数组 | 再等 5 秒查一次。确认刚才是 GET 不是 HEAD。RabbitMQ 不 healthy 时，302 仍在，桶会晚到——这正好说明统计不在跳转的返回路径上。 |
| `dim=geo` 不是 400 | 把 URL 念出来。参数名是 `dim`，不是 `dimension`。 |

**别说：** 不要说「按地区分流」或「实验平台、显著性」。这是权重抽签。不要承诺六次一定是 1:3。

---

## 4. 步 3 · CSV 导入部分成功（约 90 秒）

**操作**（仓库外的临时文件即可）：

```bash
cat > /tmp/links-demo.csv <<'CSV'
original_url,title
https://example.com/ok-1,one
https://example.com/ok-2,two
not-a-url,bad
CSV

curl -s -w '\nHTTP %{http_code}\n' -H "Authorization: Bearer $TOKEN" \
  -F file=@/tmp/links-demo.csv \
  http://localhost:8080/api/links/import
```

文件内容是四行：表头、两条合法 URL、一条 `not-a-url`。

**你说：**

这是同步 multipart，字段名 `file`。上限 100 行，为的是当面把结果看完，不是异步任务，也没有进度查询。解析是仓库里自写的，没有再引一个 CSV 库。

逐行走的是单链创建同一条路径，所以一行坏掉，不会把已经成功的行回滚掉。这次应是 HTTP 200，`data.successCount` 为 2，`data.failureCount` 为 1。失败在 `data.failures` 里：`row` 是**文件行号**，表头算第 1 行，第一条数据是第 2 行，`not-a-url` 在第 **4** 行。不要把它说成「第 3 条数据所以 row=3」。`reason` 应是 `originalUrl must start with http:// or https://`。成功行在 `data.successes` 里带着各自的 `code`，可以再跳一次 302。

结构性问题不一样：缺 `original_url` 列、超过 100 条数据、引号没闭合、文件太大，整单 400，**一行都不写**。专用 import 限流默认 5 次/60 秒，整次请求计一次，不占创建接口那 30 次/60 秒的配额。单链创建用的 `Idempotency-Key`，导入这边故意不认——结果本身就是逐行回报的。

控制台若有「导入 CSV」，打的是同一个接口。时间够可以指一下，不够就停在 curl。

**失败了：**

| 现象 | 你怎么处理、怎么说 |
|------|-------------------|
| 整单 400 | 看 message。常见是列名不是 `original_url`，或文件没传到（字段必须叫 `file`）。 |
| `row` 不是 4 | 把 cat 出来的文件念一遍行号。多了空行，行号会变。 |
| 429 | import 限流。说「这是专用窗口，不是创建配额被 100 行打爆」。等窗口，不要改配置。 |
| 成功 0 失败 0 | 看是不是传成了空文件。curl 的 `@` 不能丢。 |

**别说：** 不要说「导入是队列，稍后一致」。它是同步的。不要说 Idempotency-Key 能对 CSV 去重。

---

## 5. 步 4 · 故障演练（约 2 分钟；可实跑，也可口播）

### 5.1 优先：有准备就实跑

```bash
bash scripts/fault-drill.sh
```

**你说（跑之前先讲清楚，避免面试官以为你在打 8080）：**

这个脚本会自己再起一个应用，默认端口 **8083**，用的是 `target/linkforge-*.jar` 里最新的那个，也就是 2.0.0。它会停 Redis、把 MySQL 打成只读，Webhook 那段要用 PowerShell 在本机起一个 5xx 桩。8080 上这个 Demo 进程连的是同一套 MySQL 和 Redis，所以脚本动依赖时，8080 也会感觉到。脚本用 trap 把 Redis 和 `read_only` 拉回来。我不会在脚本跑到一半 Ctrl+C 然后不管。

期望是逐行 `PASS …`，最后一行 **`FAULT DRILL OK`**，退出码 0。C5 手验（progress，tip `e0c567c`，2026-11-01）是 **7** 行 PASS，没有 SKIP：

1. `PASS redis: redirect 302 with redis down (L2 fail-open)`
2. `PASS redis: create survives redis down (idempotency fail-open)`
3. `PASS redis: self-heals after redis returns`
4. `PASS webhook: redirect stays 302 while delivery retries`
5. `PASS webhook: delivery ends FAILED after max attempts (3x500)`
6. `PASS mysql-ro: writes fail while reads/redirect survive (GET 302, click fail-open)`
7. `PASS mysql-ro: writes recover after read_only off`

对着这三组说：

1. **Redis 停了**，新的跳转仍然 302，这是 L2 fail-open。创建也还在，幂等在 Redis 宕的时候降级，指标上能看见。Redis 回来以后自己写回 L2。
2. **Webhook 对方 5xx**，投递会重试，最后 FAILED。重试的整段时间里，`/r` 仍然 302。投递失败不堵跳转。
3. **MySQL 只读**，写接口会失败，但 GET `/r/{code}` 仍然 302。这是 C5 的点击记账 fail-open：`recordClick` 抛 `DataAccessException` 时，调用点记下 `linkforge.click.record.failopen`，`linkforge.redirects` 照常加 1，然后把 Location 返回去。HEAD 仍然只是探针，不记点击。

收口就这一句：**主链路 302 不依赖 Redis、点击写库、Webhook 任何一侧写成功。**

**失败了：**

| 现象 | 你怎么处理、怎么说 |
|------|-------------------|
| `SKIP webhook: no powershell for the 5xx stub` | 这是诚实跳过，不是装绿。Redis 和 mysql-ro 仍可以 PASS。完整 7 行以 progress 那次（有 PowerShell）为准。不要把 SKIP 说成 FAIL。 |
| `SKIP mysql-ro: no MYSQL_ROOT_PASSWORD` | 只读段需要 root 口令。没有就不要临时猜密码。口播第 3 段，并说明 C5 的 UT 叫 `redirectSurvivesClickRecordFailure`。 |
| `jar missing` | 先 `mvn -q -DskipTests package`，产物名 `target/linkforge-2.0.0.jar`。现场若编译超过一分钟，放弃实跑改口播。 |
| 脚本中途失败，Redis 还停着 | 看终端最后一行。立刻 `docker compose start redis`。若它把库留在只读，用你自己的 root 口令 `SET GLOBAL read_only=0`，不要在讲稿里写死密码。日志在 `/tmp/linkforge-fault-drill.log`。 |
| 和 8080 的 Demo 打架 | 等脚本结束再回到步 1 的码上补一次 302。不要两套一起解释。 |

### 5.2 时间不够：只口播（推荐卡点时用）

**你说：**

我不在这儿第一次跑脚本。C5 收口时实跑过，progress 里有 7 行 PASS 的原文，日期 2026-11-01，代码是 `e0c567c`，终行 `FAULT DRILL OK`。三段的产品语义是：Redis 停了跳转还在；Webhook 5xx 不堵跳转；MySQL 只读时 GET 也是 302，点击那次写库失败只打指标。主链路不依赖这三侧写成功。

### 5.3 可选的 20 秒替代（只演 Redis，演完必须拉起来）

只在 8080 的应用已经起来、而且你确定能马上 `start` 的时候用：

```bash
docker compose stop redis
curl -s -D - -o /dev/null -w 'HTTP %{http_code}\n' "http://localhost:8080/r/$CODE"
docker compose start redis
docker compose ps
```

**你说：** 停掉 Redis 之后这一下仍然应是 302。我马上把它拉回来，后面的统计和限流还要它。这只覆盖 L2 fail-open，没有覆盖 Webhook 和只读库。

**别说：** 不要说「只读库下 GET 会 500」。那是 C5 之前的行为，已经改掉。不要把 fault-drill 说成 8 行 PASS；smoke 才是 8 行，fault-drill 是 7 行。

---

## 6. 步 5 · SSRF 口播（约 60 秒，不搭攻击环境）

**你说：**

Webhook 发出去之前有两道校验：保存的时候一道，每次发送之前一道。只允许 http/https，拒绝 userinfo，解析出来的地址里只要有环回、私网、链路本地（包括云元数据那条）、CGNAT 或 IPv6 ULA，就拒绝。重定向不跟随。

以前的洞在于：校验用的是那一次解析，真正 `send` 的时候客户端可能再问一次 DNS。中间把名字改成内网地址，校验就白做了，这是 rebinding 的 TOCTOU。

现在投递侧改成：这次发送只解析一次；解析出来的每一个地址都要过同一套违规段检查；然后用 httpclient5，自定义 DnsResolver **只返回刚才校验过的那组地址**，别的 host 直接按未知主机处理。所以连接上去的 IP 是这组地址的子集。Host 头、TLS 的 SNI、证书校验仍按原来的 hostname，因为连的是 IP，但虚拟主机和证书认的是域名。

选型上试过 JDK 自带的 HttpClient：Host 在受限头里，不好覆盖；连到 IP 字面量时，证书也难按原 hostname 校验。httpclient5 保住原始 URI，这三件事自然成立。版本跟 Spring Boot 的 BOM，没有在 pom 里另写一个版本号。每次投递新建 client，钉死的地址集不在线程之间共享。

**主动把边界说出来：** 端口白名单没有做，出口代理没有做。Bloom 仍然是进程内的，位不能删。B10 的 Linux 压测没有跑。Geo 不做。

**指：** 可不指代码。若面试官要看，再说类名 `PinnedHttpSender`、测试 `WebhookRebindingIT`，Demo 结束后打开，不要在这一分钟里翻 IDE。

**别说：** 不要现场改 DNS、打云厂商元数据地址、或声称「已经防住所有 SSRF」。防的是「校验之后再解析一次」这一类，加上地址段。端口和代理还在。

---

## 7. 可选 · 指标（最多 60 秒；没起就跳过）

只有观测栈**已经**在跑时才指。不要在 Demo 中途第一次执行：

```bash
docker compose --profile observability up -d
```

| 指哪里 | 说什么 |
|--------|--------|
| Grafana `http://127.0.0.1:3001`，看板「LinkForge SLO」 | 跳转、延迟、Outbox 和 Webhook 积压。面板上的阈值是 Demo 目标，不是生产 SLO。截图在 `docs/img/grafana-redirect-slo.png`、`grafana-singleflight-outbox.png`、`grafana-keys-gates-jvm.png`。 |
| `/actuator/prometheus` | 要带 scrape 令牌。令牌没配置时所有请求 **401**，这是 fail-closed，不是忘了放开。 |
| 三个名字 | `linkforge.redirects` 是用户确实被 302 了；`linkforge.cache.failopen` 是 Redis 侧写失败仍继续；`linkforge.click.record.failopen` 是点击写库失败仍继续。 |

没起观测栈就说：本地可以按 README 用 observability profile 起来，今天不临时拉镜像。

---

## 8. 收尾 30 秒

**你说：**

五步对应五件事：跳转是稳定的 302，并且读行，不靠旧缓存；A/B 在门禁之后抽签，统计能按 variant 拆开；CSV 可以部分成功，失败带文件行号；Redis、点击写库、Webhook 任何一侧写失败，302 还在；Webhook 出站把 DNS 再绑定这一下钉死了。

代码能力停在 C5，`f955313`。测试 319。pom 2.0.0。功能已经冻结，我后面准备的是把这些取舍讲清楚，不是再加一条产品线。可以问 Outbox、Bloom 广播、API Key 的两种 scope，或者 rebinding 为什么用 httpclient5。

---

## 9. 中途被打断：先延期，Demo 完再答

先把这一句说完，再决定要不要展开。不要在第 2 步停下来讲 5 分钟。

| 对方插话 | 你先说 |
|----------|--------|
| 深挖某一段源码 | 「这个我等五步走完，按调用链讲。先让你看完 302 和失败时长什么样。」 |
| QPS / 压测 | 「B10 的 Linux bench 没跑。简历上那组是 2026-10-24 的 Windows 历史窗，固定窗口时期，我不当成当前数字。」 |
| Geo / 自定义域名 / ES | 「没做。统计维度白名单里没有 geo，刚才那个 400 就是这个决定。」 |
| 为什么是 httpclient5 | 「JDK 那个客户端盖不了 Host，连 IP 时证书也难按原域名校验。Demo 完我可以打开 DnsResolver。」 |
| 多实例 Bloom | 「Bloom 和 L1 都在进程里。别的实例靠 CacheBus 的 pub/sub 补 put 和作废 L1。发布失败不影响写请求。假阴靠广播和 24 小时重建。」 |
| 只读库以前是不是 500 | 「C5 之前 GET 会 500。现在调用点接住写库异常，仍然 302。HEAD 一直不记点击。」 |

---

## 10. Demo 之后的追问（每题 30–45 秒）

这些不在 12 分钟里念。对方问到哪题，就说哪题。事实以当前代码为准：点击记账 fail-open **已经做了**，不是「准备做」。

**1. A/B 怎么抽，和缓存什么关系？**  
门禁、密码、UTM 都看链上的 `original_url`。BLOCK、未解锁、WARN 的页面路径不查 variants。都通过之后，`VariantPicker` 按权重做一次累积抽签，用的是相对权重。L1/L2 可以缓存默认 URL，有 variants 时 Location 以抽签为准。variants 变更走 CacheBus 的 L1 invalidate。

**2. 统计怎么进 variant 桶？**  
跳转时把抽中的 key 交给 `recordClick`。事件上这个字段可空。消费端只有非空才写 `dim_type=variant`。breakdown 的合法 dim 是 device、referer_host、os、browser、variant。

**3. 导入的幂等和限额？**  
同步，最多 100 行。每一行调用和单链创建相同的路径，一行失败只影响自己。整次请求打在 `import:user:{userId}` 这个滑窗上，API Key 也是按这一次请求计，不是按行计。`Idempotency-Key` 只对单链创建有意义，导入不看它。

**4. 为什么 CSV 不异步？**  
Demo 和调用方都要当场看到哪一行成功、哪一行的文件行号失败。异步任务还要进度查询和「当时到底写了几行」的对账，这条面试线没有做。

**5. SSRF 以前缺什么，现在怎么关？**  
缺的是发送时的第二次 DNS。现在 `resolveForSend` 一次解析，全部地址做 `violationOf`，`PinnedHttpSender` 只连这组地址，重定向关闭。测试名是 `WebhookRebindingIT`。

**6. 为什么不用 JDK HttpClient？**  
Host 是受限头。把 URI 换成 IP 字面量之后，证书校验对不上原来的 hostname。httpclient5 保留原 URI，Host、SNI、证书还是域名，连接地址由 DnsResolver 限制。

**7. 连的是 IP，为什么还要原 hostname？**  
虚拟主机和证书认的是域名。只拿 IP 去连，会打到错的站点或证书校验失败。所以钉的是地址，名字仍留给 TLS。

**8. Redis fail-open 的代价？**  
L2 的读写删、空值标记、幂等这几处把连接失败接住，打 `linkforge.cache.failopen`。Redis 不在的时候，同一个幂等键挡不住并发的两次创建。Redis 正常时，幂等合同不变。选择是跳转和创建优先，不让缓存故障变成 500。

**9. 点击写库失败会不会把 302 变成 500？**  
不会。`recordClick` 仍是 `@Transactional`，里面同时写明细和 outbox。catch 在 `resolveForGet` 的调用点外面，所以是事务回滚之后才记 `linkforge.click.record.failopen`，没有半提交。skip 的 bot 流量不进这个 try。HEAD 不记点击。UT 是 `redirectSurvivesClickRecordFailure`。

**10. `@DirtiesContext` 那段是什么？**  
三个会开 relay 的集成测试共用 Testcontainers 里的投递表。Spring 把上下文缓存下来之后，旧测试类的定时 relay 还活着，用自己的重试上限去改新类插进去的行。修法是这三个 IT 在类结束时 `@DirtiesContext`。中间还错过程序包名。这是测试隔离，不是生产逻辑。

**11. fault-drill 怎么设计？**  
跟 outbox 演练一样：每段自己 PASS，全文最后 `FAULT DRILL OK`，trap 负责恢复。缺 PowerShell 或缺 MySQL root 口令就 SKIP 那一段，不会因此打印 OK 来装全绿。mysql-ro 的主探针是 GET 期望 302。

**12. 你还没做的？**  
端口白名单、出口代理、B10、Geo、自定义域名、把 Bloom 换成可删除的结构。这些在 backlog 里，冻结之后没有单独指令就不做。

---

## 11. 现场可复制的最短命令串

依赖和应用已经按 README 起来之后，从登录到 CSV 用这一串。fault-drill 和 SSRF 不要塞进同一串里自动跑。

```bash
# 窗口里已经 set -a && source .env && set +a
curl -s http://localhost:8080/actuator/health

TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"tenantName":"'"${LINKFORGE_BOOTSTRAP_TENANT:-demo}"'","username":"'"${LINKFORGE_BOOTSTRAP_USERNAME:-admin}"'","password":"'"${LINKFORGE_BOOTSTRAP_PASSWORD}"'"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["token"])')

CREATE=$(curl -s -X POST http://localhost:8080/api/links \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"originalUrl":"https://example.com"}')
CODE=$(echo "$CREATE" | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["code"])')
curl -s -D - -o /dev/null "http://localhost:8080/r/$CODE"

AB=$(curl -s -X POST http://localhost:8080/api/links \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"originalUrl":"https://example.com/default","variants":[{"key":"a","url":"https://example.com/a","weight":1},{"key":"b","url":"https://example.com/b","weight":3}]}')
AB_CODE=$(echo "$AB" | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["code"])')
for i in 1 2 3 4 5 6; do curl -s -D - -o /dev/null "http://localhost:8080/r/$AB_CODE" | grep -i '^Location:'; done
sleep 5
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/links/$AB_CODE/stats/breakdown?dim=variant"

cat > /tmp/links-demo.csv <<'CSV'
original_url,title
https://example.com/ok-1,one
https://example.com/ok-2,two
not-a-url,bad
CSV
curl -s -H "Authorization: Bearer $TOKEN" -F file=@/tmp/links-demo.csv http://localhost:8080/api/links/import
```

冒烟和故障演练仍用 README 原文：

```bash
bash scripts/smoke.sh
bash scripts/fault-drill.sh
```
