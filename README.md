# 自适应限流与临时封禁防护（zero-trust-rate-limiter-java）

面向**入口请求**的自适应限流、临时封禁与存储降级防护服务。基于 Spring Boot 3 / Java 21，
纯内存计数 + 本地 JSON 文件持久化，**无需任何外部服务或真实凭据**即可本地启动与验证。

判定结果满足：

- **一致**：并发与窗口切换下计数准确；一次请求只有一个自洽结论，不互相矛盾。
- **可解释**：每个结论带原因码（`ALLOWED` / `RATE_LIMITED` / `BANNED` / `FAIL_OPEN` / `FAIL_CLOSED`）、
  触发维度、窗口、计数、策略版本、封禁级别与降级原因（`STORE_UNAVAILABLE` / `STORE_TIMEOUT`）。
- **可复现**：每个请求带 `X-Request-Id`；判定输入与最终结果打印审计日志；策略以不可变快照 +
  单调版本发布，热更新只对之后的新窗口/新请求生效，旧策略的历史判定不被静默改写。

---

## 1. 架构与请求处理流程

```
HTTP 请求
  │
  ▼
RateLimitFilter（/admin/** 跳过）
  │  构造 RequestContext（requestId / ip / user / path / method）
  ▼
RateLimitService.decide()
  1. 取当前不可变 RateLimitPolicy 快照（PolicyHolder 原子引用，版本单调递增）
  2. 若启用封禁：先查 BanService.checkActiveBan
       ├─ 封禁中 → 直接 BANNED（不触碰限流计数）
       └─ 顺带推进“到期解除 / 观察期满重置”
  3. 按配置维度顺序做固定窗口原子计数（InMemoryRateLimitStateStore）
       ├─ count <= limit → 下一维度 / 最终 ALLOWED
       └─ count >  limit → RATE_LIMITED（首个超限维度生效）
             └─ 若该维度 == 封禁维度：recordLimitedWindow 驱动升级
  4. 任一存储调用抛错或超过 ratelimit.storage.timeout
       └─ FAIL_CLOSED（503 拒绝）或 FAIL_OPEN（放行但显式标记降级）
  5. DecisionAuditLog：结构化日志 + 内存环形缓冲（只追加，不改写）
  ▼
响应：状态码 + 响应头 + JSON 错误体（原因可区分）
```

关键组件：

| 组件 | 职责 |
| --- | --- |
| `RateLimitFilter` | 入口过滤、请求标识、原因到状态码/响应头/JSON 体的映射 |
| `RateLimitService` | 判定编排、策略快照、存储调用超时保护、降级结论、热更新 |
| `PolicyHolder` / `RateLimitPolicy` | 原子发布的不可变策略快照与单调版本 |
| `InMemoryRateLimitStateStore` | 并发安全固定窗口计数（键含版本+维度+窗口起点），支持故障注入 |
| `FileBanStateStore` | 封禁状态内存 + JSON 文件原子持久化，启动加载，支持故障注入 |
| `BanService` | 封禁升级、到期解除、观察期升级/重置、人工解除 |
| `KeyExtractor` | 各限流维度的键提取 |
| `DecisionAuditLog` | 请求标识 + 判定输入 + 最终结果的结构化审计日志 |
| `AdminController` | 策略热更新、封禁查询、人工解除（不受限流约束） |

---

## 2. 限流规则

### 2.1 限流维度

按 `ratelimit.policy.dimensions` 配置，**按顺序判定，首个超限维度生效**：

| 维度 | 键来源 | 说明 |
| --- | --- | --- |
| `IP` | 对端 IP；有 `X-Forwarded-For` 时取首个 | 默认维度 |
| `USER` | 请求头 `X-User-Id` | 未携带时**跳过**该维度（不把匿名用户聚为同键） |
| `PATH` | 请求路径 | 不同路径分别计数 |
| `GLOBAL` | 单一全局键 | 全局限流 |

可配置多个维度，例如 `dimensions=IP,USER`：每个维度独立拥有一份计数，
任一维度超限即拒绝，错误体 `limitedBy` 标明命中维度。

### 2.2 固定窗口与计数准确性

- 窗口起点 `windowStart = floor(now / window)`，窗口切换即新键，互不影响。
- 计数基于 `ConcurrentHashMap` + `AtomicLong` 的原子自增，
  `Test: RateLimitServiceTest#concurrentRequestsInOneWindowCountExactly` 验证
  16 线程 × 10 请求、阈值 50 时**恰好** 50 放行 / 110 拒绝。
- 计数器键含**策略版本号**：热更新后新版本窗口从 0 开始，旧版本计数保留但不再被引用，
  历史判定不会被静默改写。

### 2.3 拒绝响应

- HTTP `429 Too Many Requests`，含 `Retry-After`（到下一窗口的秒数）。
- 响应头：`X-Request-Id`、`X-RateLimit-Decision=RATE_LIMITED`、`X-RateLimit-Policy-Version`。
- JSON 体：`requestId / reason / detail / policyVersion / windowStart / currentCount /
  limitedBy / retryAfterMillis`。

---

## 3. 临时封禁规则

封禁只统计 `ratelimit.ban.dimension`（默认 `IP`）这一维度的超限事件。

**升级（条件明确、确定）：**

1. 每个限流窗口内，来源第一次超限时 `consecutiveLimitedWindows += 1`（同窗口重复超限不重复计数）。
2. 连续超限窗口数达到 `ratelimit.ban.threshold-windows` → 封禁级别 `+1`，
   按级别取 `ratelimit.ban.level-duration[i]` 得到封禁截止时间；超过级别表长度时取最后一档。
3. “连续”要求窗口**相邻**：若两次超限之间间隔超过一个窗口（中间存在正常窗口），计数链中断并从 1 重新开始——
   因此正常请求不会被误封。

**解除 / 恢复（语义稳定）：**

- **到期自动解除**：`now >= bannedUntil` 后第一次请求原子解除，进入观察期（保留级别）。
- **观察期内再犯**：再次连续超限达阈值时立即重新封禁，级别再 `+1`（时长递增）。
- **观察期满未再犯**：级别与违规计数清零（`ratelimit.ban.reset-after-observe=true`）。
- **人工解除**：`POST /admin/bans/{key}/unban`，立即清零并持久化，重启后仍为已解除。
- 封禁时长在封禁生成时**固化进记录**（`banDurationMillis`、`policyVersionAtBan`），
  之后策略热更新不改变既有封禁的到期时间，保证策略调整后语义稳定。
- 封禁状态持久化到 `ratelimit.storage.ban-state-file`（临时文件 + 原子 rename），
  进程重启后加载，封禁/解封语义保持一致；置空则仅内存。
- 封禁中的请求直接返回 `403 Forbidden`（`X-RateLimit-Decision=BANNED`，带 `Retry-After`），
  不参与限流计数。

---

## 4. 存储降级规则

所有存储访问（封禁读写、限流计数）都通过单线程守护执行器加超时保护：

| 触发条件 | 区分原因 | FAIL_CLOSED（默认） | FAIL_OPEN |
| --- | --- | --- | --- |
| 存储抛异常 / 不可用 | `STORE_UNAVAILABLE` | HTTP `503` 拒绝 | 放行 |
| 调用耗时超过 `ratelimit.storage.timeout` | `STORE_TIMEOUT` | HTTP `503` 拒绝 | 放行 |

- 降级时**一次请求只产出一个结论**（在 `RateLimitService` 统一捕获并翻译为 `StoreException`），
  不会出现既计数又拒绝等互相矛盾的结果。
- 失败开放**不静默放行**：响应头带 `X-RateLimit-Decision=FAIL_OPEN`、
  `X-RateLimit-Degraded=true`、`X-RateLimit-Degraded-Cause=STORE_TIMEOUT|STORE_UNAVAILABLE`，
  同时写入审计日志。
- 失败关闭返回 503、`Retry-After: 1` 与 JSON 错误体；`/admin/**` 不经过限流过滤器，
  即使在降级状态下人工解除通道仍可用。

---

## 5. 配置项（`application.properties`）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `ratelimit.policy.window` | `1s` | 固定窗口长度 |
| `ratelimit.policy.limit` | `10` | 每窗口每维度最大请求数 |
| `ratelimit.policy.dimensions` | `IP` | 限流维度（有序）：`IP`/`USER`/`PATH`/`GLOBAL` |
| `ratelimit.ban.enabled` | `true` | 是否启用封禁 |
| `ratelimit.ban.threshold-windows` | `3` | 连续超限窗口阈值，达到即升级 |
| `ratelimit.ban.level-duration[0..n]` | `1m,5m,30m` | 各级封禁时长 |
| `ratelimit.ban.observe-window` | `10m` | 解封后观察期 |
| `ratelimit.ban.reset-after-observe` | `true` | 观察期满未再犯是否清零 |
| `ratelimit.ban.dimension` | `IP` | 封禁判定维度 |
| `ratelimit.storage.ban-state-file` | `./data/ban-state.json` | 封禁持久化文件；空=仅内存 |
| `ratelimit.storage.timeout` | `100ms` | 存储调用超时阈值，超过即降级 |
| `ratelimit.storage.fail-mode` | `FAIL_CLOSED` | `FAIL_CLOSED` / `FAIL_OPEN` |

时长支持 Spring Boot 简写（`500ms`、`30s`、`5m`）与 ISO-8601（`PT0.5S`）。

---

## 6. HTTP 接口

业务示例端点（受限流保护）：

- `GET /api/hello` → 200 `{"message":"ok"}`

管理端（`/admin/**` 不经过限流过滤器）：

| 方法与路径 | 作用 |
| --- | --- |
| `GET  /admin/policy/version` | 查看当前策略版本 |
| `POST /admin/policy/reload` | 提交整份配置热更新，原子发布新版本 |
| `GET  /admin/bans` | 查看全部来源封禁状态 |
| `POST /admin/bans/{key}/unban` | 人工解除（立即生效并持久化） |

热更新请求体与配置一一对应，时长接受 `"500ms"` 简写，例如：

```json
{
  "policy": {"window": "500ms", "limit": 20, "dimensions": ["IP", "USER"]},
  "ban": {"enabled": true, "thresholdWindows": 3,
          "levelDuration": ["1m", "5m", "30m"], "observeWindow": "10m",
          "resetAfterObserve": true, "dimension": "IP"},
  "storage": {"banStateFile": "./data/ban-state.json",
              "timeout": "100ms", "failMode": "FAIL_CLOSED"}
}
```

响应头速查：`X-Request-Id`、`X-RateLimit-Decision`、`X-RateLimit-Policy-Version`、
`Retry-After`，失败开放时另有 `X-RateLimit-Degraded` / `X-RateLimit-Degraded-Cause`。

---

## 7. 本地验证

### 7.1 构建与测试（离线可用）

```bash
mvn -o test          # 18 个测试：窗口切换/并发计数/热更新边界/封禁升级解除/两类降级原因
mvn -o package -DskipTests
```

测试覆盖：

- `RateLimitServiceTest`：固定窗口准确放行/拒绝、窗口切换重置、16 线程并发计数精确、
  热更新只影响新窗口且历史判定版本不改写、`STORE_UNAVAILABLE`/`STORE_TIMEOUT` ×
  FAIL_OPEN/FAIL_CLOSED 四象限、结论自洽性。
- `BanServiceTest`：连续超限升级、同窗口不重复计数、观察期再犯级别 +1、观察期满清零、
  人工立即解除、正常流量永不被封。
- `BanIntegrationTest`：经真实服务编排验证升级→封禁中不计数→到期解除→人工解除。
- `WebIntegrationTest`（MockMvc）：429/403/503 状态码与响应头、失败开放标记头、
  ISO 简写热更新、管理端在存储故障时仍可用。

单元测试日志由 `DefaultDecisionAuditLog` 打印，形如：

```
rate-limit decision requestId=c-3-7 input=[ip=2.2.2.2, user=null, method=GET, path=/api/hello]
  result=[allowed=false, reason=RATE_LIMITED, detail=rate limit exceeded on dimension IP ...,
  policyVersion=1, windowStart=10000, currentCount=51, limitedBy=IP, ...]
```

### 7.2 启动与手工验证

```bash
# 内存态快速验证（端口随机，见启动日志；以下用固定端口演示）
java -jar target/zero-trust-rate-limiter-java-1.0-SNAPSHOT.jar \
     --server.port=18080 \
     --ratelimit.policy.limit=3 \
     --ratelimit.policy.window=1s \
     --ratelimit.storage.ban-state-file=/tmp/ban-state.json
```

另开终端：

```bash
# 1) 前 3 个 200，第 4 个 429 RATE_LIMITED
for i in $(seq 1 4); do
  curl -s -o /dev/null -w "%{http_code} %header{X-RateLimit-Decision}\n" \
       http://127.0.0.1:18080/api/hello
done

# 2) 连续 3 个窗口各打超一次（默认 threshold-windows=3）-> 后续 403 BANNED
curl -s http://127.0.0.1:18080/admin/bans

# 3) 人工解除立即生效
curl -s -X POST http://127.0.0.1:18080/admin/bans/127.0.0.1/unban

# 4) 热更新（新窗口生效，版本号 +1）
curl -s -X POST http://127.0.0.1:18080/admin/policy/reload \
  -H 'Content-Type: application/json' -d '{"policy":{"window":"1s","limit":100,"dimensions":["IP"]},
    "ban":{"enabled":true,"thresholdWindows":3,"levelDuration":["1m","5m","30m"],
           "observeWindow":"10m","resetAfterObserve":true,"dimension":"IP"},
    "storage":{"banStateFile":"/tmp/ban-state.json","timeout":"100ms","failMode":"FAIL_CLOSED"}}'

# 5) 重启进程后封禁仍生效（bannedUntil 未到）：再次启动后请求 /api/hello 返回 403
```

指定用户维度验证：请求带 `X-User-Id: alice` 即可按用户计数（需在 dimensions 中加入 `USER`）。

---

## 8. 设计取舍说明

- **固定窗口**而非滑动窗口：实现简单、边界确定、可复现；窗口临界点可能有 2 倍突发，
  对本防护场景可接受，窗口长度可调。
- **限流计数仅内存**：计数天然是短期数据，进程重启意味着攻击窗口重置，属明确语义；
  **封禁状态持久化**到文件以保证跨重启一致。文件存储适合本地/单实例验证，不依赖外部服务。
- **版本化计数命名空间**：以少量内存换取热更新边界的确定性与历史判定不可变性。
- **降级在编排层统一裁决**：存储层只抛 `StoreException(Kind)`，由服务层按策略给出唯一结论，
  保证可观测（日志 + 响应头）且不静默。
