# 自适应限流与临时封禁防护（Zero-Trust Rate Limiter）

基于仓库已有技术栈（Spring Boot 3.4 / Java 21 / Spring MVC，无外部服务、无真实凭据）
实现的入口请求防护层。所有状态保存在**本地内存 + 本地文件**中，可离线完整启动与验证。

## 1. 能力总览

- **固定窗口限流**：按可配置维度计数，并发与窗口切换下计数精确；超额拒绝并给出可区分原因。
- **临时封禁**：对反复超限的来源按确定性阶梯升级封禁，到期/恢复条件明确；人工解封立即生效；
  封禁状态落盘，重启后语义一致。
- **策略热更新**：新版本带确定的生效时间边界（窗口对齐），旧版本及其历史计数永久保留、不被改写。
- **降级防护**：状态存储不可用或延迟超阈值时，按 `OPEN`（失败开放）或 `CLOSED`（失败关闭）
  处理；开放路径必定打降级标记且单独计量，绝不静默放行。
- **结论一致可复现**：同一 `X-Request-Id` 在去重 TTL 内只产生一个判定结论；每个判定携带
  策略版本、窗口起点、观测计数、规则名、来源、封禁级别等完整解释信息并打印日志。

## 2. 限流维度与规则

维度枚举 `Dimension`（一个规则选一个）：

| 维度 | 来源标识 |
| --- | --- |
| `IP` | 客户端 IP（优先取 `X-Forwarded-For` 首段） |
| `USER` | 请求头 `X-User-Id` |
| `TOKEN` | 请求头 `Authorization` |
| `PATH` | 请求路径 |
| `IP_PATH` | `客户端IP | 路径` |

规则结构 `RateLimitRule`：`name`、`dimension`、`maxRequests`、`windowSeconds`、
`pathPatterns`（支持 `*` glob；为空表示匹配全部路径）。一个请求按规则声明顺序逐条检查，
**第一条超限的规则决定拒绝结论**。

计数键为 `(policyVersion, windowStart, ruleName, source)`：

- `incrementAndGet` 基于 `ConcurrentHashMap + AtomicLong`，并发下原子自增，不丢不重。
- 窗口起点 `windowStart = floor(now / (windowSeconds*1000)) * ...`，按 epoch 对齐；
  窗口切换后是**全新计数器**，边界瞬时属于新窗口。
- 键内嵌策略版本，热更新后新旧版本计数互不干扰。

默认策略（`DefaultPolicyFactory`，首次启动播种到 `data/policies.jsonl`）：

- `per-ip-api`：`IP` 维度，`/api/*`，10 秒窗口 5 次；
- `per-ip-user`：`IP_PATH` 维度，`/api/orders*`，10 秒窗口 3 次。

## 3. 封禁与解除规则

判定为 `RATE_LIMITED` 的请求记一次“违规”（violation）。封禁状态机 `BanEvaluator`
只依赖时间戳，纯函数、可单测、重启后结果不变：

- **升级**：每累计 `violationsForBan`（默认 3）次违规，封禁级别 +1。
  级别 N 时长 = `baseBanSeconds * banMultiplier^(N-1)`（默认 30s × 2^(N-1)，
  即 30s/60s/120s），最高 `maxBanLevel`（默认 3），封顶后不再升级。
- **封禁中**：请求在计数之前直接拒绝为 `BANNED`，不增加违规、不动计数器。
- **封禁到期**：封禁窗口过去后请求可通过，但级别与违规数保留（观察期）。
- **完全恢复**：自最近一次违规起 `violationRecoverySeconds`（默认 60s）内无新违规，
  级别与违规计数清零，重新从 0 开始。
- **人工解封**：`POST /admin/bans/{source}/unban` 直接删除持久化状态并原子落盘，
  对后续请求立即生效（窗口计数器是独立的短期状态，到新窗口自然重置）。
- **持久化**：封禁状态写 `data/bans.json`，临时文件 + 原子 rename，每次变更即落盘；
  启动时加载。策略调整不改变既有封禁语义（封禁状态不依赖策略版本；时间到点判定不变）。

正常请求（未超限）永远不会产生违规，因此不会被误封。

## 4. 降级规则（存储不可用 / 延迟超阈值）

- 计数器与封禁写操作通过带超时的线程池调用；超过 `rate-limiter.store-timeout-millis`
  归类为 `FAIL_CLOSED_TIMEOUT`，抛错归类为 `FAIL_CLOSED_UNAVAILABLE`，两者原因码可区分。
- `rate-limiter.fail-strategy=CLOSED`（默认）：返回 **503**，原因码分别为
  `FAIL_CLOSED_UNAVAILABLE` / `FAIL_CLOSED_TIMEOUT`，不放行。
- `=OPEN`：放行但 `allowed=true, degraded=true`，原因码 `DEGRADED_OPEN`，
  响应头 `X-RateLimit-Degraded: 1`，并在指标中单独计 `degradedOpen`，绝不静默。
- 封禁检查为本地内存读取，不受存储写故障影响：故障期间被封来源依旧被拒。
- 同一 `X-Request-Id` 在 `dedup-ttl-millis` 内的重试直接回放原判定，
  即使中途存储故障，也不会对同一请求给出互相矛盾的结论。
- 异常链路以 ERROR 打印 requestId、判定输入、原因、策略与规则；`/admin/metrics`
  与 `/admin/decisions` 提供聚合计数与最近判定明细。

HTTP 状态映射：`RATE_LIMITED`/`BANNED` → 429；两类 fail-closed → 503；
响应头 `X-RateLimit-Reason` 给出可区分原因码，另带 `X-RateLimit-Policy`、
`X-RateLimit-Limit`、`X-RateLimit-Window-Start`、`X-Ban-Level`、`X-Ban-Until`。

## 5. 策略热更新

- `POST /admin/policies` 发布新版本，默认把 `effectiveAtMillis` 对齐到**首规则的
  下一个窗口起点**（`?alignToWindow=false` 可用请求体中的生效时间）。
- 生效边界语义：`effectiveAtMillis <= t` 时新版本生效，**边界瞬时本身属于新策略**；
- 策略以 append-only JSON Lines 持久化在 `data/policies.jsonl`，旧版本物理保留，
  `GET /admin/policies` 可见完整历史；`POST /admin/policies/reload` 可从文件重新加载。

## 6. 配置项（`src/main/resources/application.properties`）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `rate-limiter.data-dir` | `data` | 本地状态目录（`policies.jsonl`、`bans.json`） |
| `rate-limiter.fail-strategy` | `CLOSED` | 降级策略：`OPEN` / `CLOSED` |
| `rate-limiter.store-timeout-millis` | `200` | 单次状态操作延迟阈值（毫秒） |
| `rate-limiter.dedup-ttl-millis` | `30000` | 同 requestId 判定去重 TTL |
| `rate-limiter.decision-log-capacity` | `1024` | 进程内判定环形缓冲容量 |
| `server.port` | `8080` | 服务端口 |

## 7. 管理与观测接口（不限流）

| 方法与路径 | 作用 |
| --- | --- |
| `POST /admin/policies?alignToWindow=true` | 发布新策略版本（JSON body 为 `RateLimitPolicy`） |
| `POST /admin/policies/reload` | 从文件重载策略 |
| `GET  /admin/policies` | 全部策略版本历史 |
| `POST /admin/bans/{source}/unban` | 人工解封，立即生效 |
| `GET  /admin/bans` | 当前封禁状态快照 + 当前时间 |
| `POST /admin/faults?unavailable=true` | 注入存储不可用 |
| `POST /admin/faults?forcedDelayMillis=500` | 注入存储延迟 |
| `POST /admin/faults?reset=true` | 清除注入故障 |
| `GET  /admin/metrics` | 允许/降级/各原因拒绝计数 |
| `GET  /admin/decisions?limit=20` | 最近判定明细（可解释、可复盘） |

受保护演示接口：`GET /api/hello`、`GET /api/orders`。

## 8. 本地验证方法

### 8.1 构建与自动化测试

```bash
mvn test
```

覆盖 18 个用例（`src/test/.../tests`）：

- `BanEvaluatorTest`：升级阶梯、封顶、封禁到期保留级别、恢复期清零（纯状态机边界）。
- `RateLimitServiceTest`：精确边界拒绝、窗口切换重置、**20 线程 ×5 并发计数精确**
  （恰好 5 放行 / 95 拒绝）、**热更新在新窗口边界生效且旧版本计数不被改写**、
  三次违规升级封禁与人工解封立即生效。
- `DegradationTest`：不可用 fail-closed、延迟超阈值 fail-closed（且调用方不等满延迟）、
  fail-open 降级标记与计量、同 requestId 结论唯一不矛盾。
- `PersistenceRestartTest`：用同一文件重建全部组件模拟“进程重启”，封禁延续、
  策略历史不重复播种、解封后再次重启仍为解封态。
- `HttpIntegrationTest`：MockMvc 端到端验证 429/503 状态码与原因响应头。

单测日志中每个关键步骤都打印 `requestId`、判定输入（IP/路径等）与最终结果。

### 8.2 本地启动与手工冒烟

```bash
mvn -DskipTests package
java -jar target/zero-trust-rate-limiter-java-1.0-SNAPSHOT.jar
# 或：mvn spring-boot:run
```

另开终端：

```bash
# 1) 限流：前 5 次 200，第 6 次起 429 RATE_LIMITED
for i in $(seq 1 7); do
  curl -s -o /dev/null -w "%{http_code} %header{x-ratelimit-reason}\n" \
    -H "X-Forwarded-For: 192.168.0.10" http://localhost:8080/api/hello
done

# 2) 同 requestId 重试，结论一致（429 RATE_LIMITED，含完整解释 JSON）
curl -i -H "X-Forwarded-For: 192.168.0.10" -H "X-Request-Id: smoke-6" \
  http://localhost:8080/api/hello

# 3) 存储不可用 -> 503 FAIL_CLOSED_UNAVAILABLE
curl -s -X POST "http://localhost:8080/admin/faults?unavailable=true"
curl -i -H "X-Forwarded-For: 10.0.0.99" http://localhost:8080/api/hello

# 4) 延迟超阈值 -> 503 FAIL_CLOSED_TIMEOUT（约 200ms 即返回）
curl -s -X POST "http://localhost:8080/admin/faults?reset=true"
curl -s -X POST "http://localhost:8080/admin/faults?forcedDelayMillis=500"
curl -i -H "X-Forwarded-For: 10.0.0.98" http://localhost:8080/api/hello
curl -s -X POST "http://localhost:8080/admin/faults?reset=true"

# 5) 封禁升级：同一窗口连发，第 3 次违规升级，之后 BANNED
for i in $(seq 1 12); do
  curl -s -o /dev/null -w "%{http_code} %header{x-ratelimit-reason}\n" \
    -H "X-Forwarded-For: 172.16.5.5" http://localhost:8080/api/hello
done
curl http://localhost:8080/admin/bans                 # 查看封禁状态
curl -X POST http://localhost:8080/admin/bans/172.16.5.5/unban   # 立即解封

# 6) 热更新：发布 2 次/10s 的新策略，默认下一窗口边界生效
curl -s -X POST -H "Content-Type: application/json" \
  -d '{"version":"v2-hot","rules":[{"name":"per-ip-api","dimension":"IP","maxRequests":2,"windowSeconds":10,"pathPatterns":["/api/*"]}],"violationsForBan":3,"maxBanLevel":3,"baseBanSeconds":30,"banMultiplier":2,"violationRecoverySeconds":60}' \
  http://localhost:8080/admin/policies

# 7) 观测
curl http://localhost:8080/admin/metrics
curl "http://localhost:8080/admin/decisions?limit=5"
```

验证 fail-open：以 `-Drate-limiter.fail-strategy=OPEN` 启动后重复步骤 3/4，
请求为 200 且带 `X-RateLimit-Degraded: 1`，指标中 `degradedOpen` 增长。

重启一致性：运行中制造封禁后 `kill` 进程再用同一 `data/` 目录重启，
`GET /admin/policies` 版本仍在，被封 IP 依旧得到 `BANNED`（封禁到点前）。

## 9. 代码结构

```
model/    维度、规则、版本化策略、封禁状态、判定结论、原因码与降级策略枚举
store/    CounterStore(内存精确计数)、BanStore(文件原子持久化)、故障注入、可区分异常
policy/   PolicyStore(append-only 文件) 与 PolicyManager(版本历史/生效边界选择)
core/     时钟、窗口算法、封禁状态机、决策引擎(去重/超时/降级)、指标与判定日志
config/   配置属性、Spring 装配、默认策略
web/      入口 Filter、受保护演示接口、管理与故障注入接口
```
