# 自适应限流与临时封禁防护（zero-trust-rate-limiter-java）

面向**入口请求**的加权分层配额限流、临时封禁与存储降级防护服务。基于 Spring Boot 3 / Java 21，
内存计数 + 本地 JSON 文件持久化（封禁与当前窗口用量均可跨重启延续），
**无需任何外部服务或真实凭据**即可本地启动与验证。

判定结果满足：

- **一致**：并发与不同窗口长度交叉切换下各层计数准确；同一请求标识的重试拿到与首次
  完全一致的结论（含降级结论），且重放不访问状态存储；一次请求只有一个自洽结论。
- **贴近业务**：请求可按路径配置**权重**消耗配额（如导出=5），窗口按加权累加量判定；
  支持**分层配额**（如来源短窗 + 全局长窗），每层独立窗口与上限，拒绝明确给出
  层名/上限/已用/剩余；未配置权重与分层时与旧版逐条计数完全兼容。
- **可解释**：每个结论带原因码（`ALLOWED` / `RATE_LIMITED` / `BANNED` / `FAIL_OPEN` / `FAIL_CLOSED`）、
  拦下分层、层上限/已用/剩余、权重、窗口、策略版本、封禁级别与降级原因
  （`STORE_UNAVAILABLE` / `STORE_TIMEOUT`）；另有只读配额视图与判定同源。
- **可复现**：每个请求带 `X-Request-Id`；判定输入与最终结果打印审计日志；策略以不可变快照 +
  单调版本发布，热更新（含权重调整）只对之后新开始的窗口/新请求生效，历史判定不被改写。

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
  0. 幂等缓存：同一 X-Request-Id 在 TTL 内重复到达 → 直接返回首次结论（不访问状态存储）
  1. 取当前不可变 RateLimitPolicy 快照（PolicyHolder 原子引用，版本单调递增）
  2. 若启用封禁：先查 BanService.checkActiveBan
       ├─ 封禁中 → 直接 BANNED（不触碰配额计数）
       └─ 顺带推进“到期解除 / 观察期满重置”
  3. 按权重规则解析本请求权重（未命中规则 = 1）
  4. 对每一层分层配额独立做加权固定窗口累加（File/InMemoryRateLimitStateStore）：
       所有适用层都先计入消耗，再统一判定（某层先超限也不会让后续层漏计）
       ├─ 全部层 consumed <= limit → ALLOWED
       └─ 任一层超限 → RATE_LIMITED，按策略顺序取首个超限层
             拒绝信息含层名/维度/上限/已用量/剩余；drivesBan 的层驱动封禁升级
  5. 任一存储调用抛错或超过 ratelimit.storage.timeout
       └─ FAIL_CLOSED（503 拒绝）或 FAIL_OPEN（放行但显式标记降级；降级结论同样进幂等缓存）
  6. DecisionAuditLog：结构化日志 + 内存环形缓冲（只追加，不改写）
  ▼
响应：状态码 + 响应头 + JSON 错误体（原因可区分）
```

关键组件：

| 组件 | 职责 |
| --- | --- |
| `RateLimitFilter` | 入口过滤、请求标识、原因到状态码/响应头/JSON 体的映射 |
| `RateLimitService` | 判定编排、策略快照、存储调用超时保护、降级结论、热更新 |
| `PolicyHolder` / `RateLimitPolicy` | 原子发布的不可变策略快照与单调版本 |
| `AbstractRateLimitStateStore` | 加权分层计数器基类：分段锁原子读-改-写、按各层窗口长度独立过期 |
| `InMemoryRateLimitStateStore` | 纯内存计数器（rate-state-file 为空时使用），支持故障注入 |
| `FileRateLimitStateStore` | 计数器内存 + JSON 文件原子持久化，启动只加载未过窗口，重启后用量延续 |
| `FileBanStateStore` | 封禁状态内存 + JSON 文件原子持久化，启动加载，支持故障注入 |
| `WeightResolver` | 按路径规则解析单请求权重（精确优先、最长前缀、默认 1） |
| `DecisionReplayCache` | 按请求标识缓存首次结论，TTL 内重试直接重放且不访问状态存储 |
| `QuotaViewService` | 只读配额视图：各层用量/剩余/封禁状态，与判定共用同一份状态 |
| `BanService` | 封禁升级、到期解除、观察期升级/重置、人工解除 |
| `KeyExtractor` | 各限流维度的键提取 |
| `DecisionAuditLog` | 请求标识 + 判定输入 + 最终结果的结构化审计日志 |
| `AdminController` | 策略热更新、封禁查询与解除、配额只读视图（不受限流约束） |

---

## 2. 加权分层配额规则

### 2.1 维度与层（QuotaLayer）

配额的最小单位是“**层**”：一层 = 一个维度 + 一个窗口长度 + 一个加权消耗上限。
一次请求必须通过**所有适用层**才放行。

| 维度 | 键来源 | 说明 |
| --- | --- | --- |
| `IP` | 对端 IP；有 `X-Forwarded-For` 时取首个 | 默认维度 |
| `USER` | 请求头 `X-User-Id` | 未携带时**跳过**该维度的层（不把匿名用户聚为同键） |
| `PATH` | 请求路径 | 不同路径分别计数 |
| `GLOBAL` | 单一全局键 `global` | 全局总量 |

分层通过 `ratelimit.policy.layers` 配置，例如“IP 短窗 1s/10 + 全局长窗 10s/200”：

```properties
ratelimit.policy.layers[0].name=ip-short
ratelimit.policy.layers[0].dimension=IP
ratelimit.policy.layers[0].window=1s
ratelimit.policy.layers[0].limit=10
ratelimit.policy.layers[0].drives-ban=true
ratelimit.policy.layers[1].name=global-long
ratelimit.policy.layers[1].dimension=GLOBAL
ratelimit.policy.layers[1].window=10s
ratelimit.policy.layers[1].limit=200
ratelimit.policy.layers[1].drives-ban=false
```

- 各层独立计数：请求到达时**所有适用层都先累加本请求的消耗**，再统一判定。
  因此即使请求被 IP 短窗拦下，全局长窗仍然计入了这次消耗，不会因前层超限而漏计。
- 拒绝时按配置顺序取**首个超限层**，响应与审计明确给出层名 `layerName`、
  该层上限 `layerLimit`、当前窗口已用量 `used`（=`currentCount`）与 `remaining`。
- 只有 `drivesBan=true` 的层（缺省：维度等于 `ratelimit.ban.dimension` 的层）
  超限才驱动封禁升级；封禁的“相邻窗口”判断使用**触发层自己的窗口长度**。

### 2.2 请求权重（WeightRule）

真实业务中查询很轻、导出/批量写入很重。通过 `ratelimit.policy.weights` 给请求配置权重，
**窗口内按权重累加消耗量、用累加值与上限比较**：

```properties
ratelimit.policy.weights[0].path=/api/export
ratelimit.policy.weights[0].weight=5
```

匹配规则（确定、可解释）：

1. 请求路径与规则 `path` **完全相等** → 命中（最高优先级）；
2. 否则按**前缀**匹配，多条命中取**最长前缀**（如 `/api/export` 命中
   `/api/export/2026/report`；配置不覆盖的其它路径不受影响）；
3. 均未命中时权重 = **1**。

因此**未配置权重时行为与旧版“每请求一条”完全一致**。权重必须为正整数。

**热更新边界**：用量是“请求到达时按当时权重累加”的历史事实，且计数器键含策略版本——
调整权重（或任何层参数）后产生新版本命名空间，**只对之后新开始的窗口生效**；
既有窗口的已用量与已经做出的判定都不会被改写。

### 2.3 窗口、并发与版本

- 每层窗口起点 `windowStart = floor(now / layer.windowMillis)`；
  不同层窗口长度不同也互不串计数，长窗口计数不会在短窗口切换时被清掉。
- 计数在分段锁内原子读-改-写；`LayeredQuotaTest#concurrentLayeredConsumptionCountsExactly`
  验证 16 线程 × 5 请求、IP 层 40 / 全局层 60 时**恰好** 40 放行、两层用量分别精确为 80。
- 计数键 = `策略版本 + 层名 + 维度 + 维度键 + 窗口起点`。热更新后新版本窗口从 0 开始，
  旧版本计数保留但不再被引用（持久化文件中也会随窗口过期被清理）。
- 配额计数可持久化到 `ratelimit.storage.rate-state-file`：进程重启后加载**尚未结束窗口**
  的用量继续判定；已过窗口的计数在加载时即丢弃。置空则仅内存。

### 2.4 重试一致性（幂等结论缓存）

客户端重试很常见。入口以请求标识 `X-Request-Id` 作为幂等键：

- 同一请求标识在 `ratelimit.replay.ttl`（默认 5s）内重复到达时，**直接返回首次结论**：
  是否放行、被哪一层拦下、原因、当时用量、剩余额度与策略版本完全一致——
  不会出现第一次放行、重试反被拒。
- 重放响应带头 `X-RateLimit-Replay: true`，JSON 体 `"replay": true`。
- **重放不访问任何后端状态存储**（配额计数、封禁存储都不访问），不为重试放大存储压力；
  同一请求标识并发首次到达也会被串行化，只产生一次判定与一次计数。
- **降级结论同样被缓存**：首次若落在 STORE_UNAVAILABLE/STORE_TIMEOUT 降级上，
  TTL 内重试拿到相同的 FAIL_CLOSED/FAIL_OPEN 结论（即便存储此刻已恢复）。
- 热更新不清空缓存：TTL 内的重试仍锁定首次策略版本；TTL 过期后按当时窗口重新判定。
  `ratelimit.replay.enabled=false` 可关闭。

### 2.5 只读配额视图

`GET /admin/quota?dimension=IP&key=1.2.3.4`（全局层用 `dimension=GLOBAL&key=global`）返回：

```json
{
  "dimension": "IP", "key": "1.2.3.4",
  "banned": false, "bannedUntil": 0, "banLevel": 0,
  "layers": [
    {"name": "ip-short", "dimension": "IP", "windowStart": 50000,
     "windowMillis": 2000, "limit": 10, "used": 4, "remaining": 6}
  ]
}
```

视图直接读取判定所用的同一个计数存储与策略快照（只读，不推进任何状态、不产生消耗），
数值与入口判定同源；封禁标志来自同一份封禁存储，人工解除后、重启后均一致。

### 2.6 拒绝响应

- HTTP `429 Too Many Requests`，含 `Retry-After`（到拦下层下一窗口的秒数）。
- 响应头：`X-Request-Id`、`X-RateLimit-Decision=RATE_LIMITED`、
  `X-RateLimit-Policy-Version`、`X-RateLimit-Layer=<层名>`（重放另有 `X-RateLimit-Replay`）。
- JSON 体：`requestId / reason / detail / policyVersion / layerName / layerLimit /
  used(=currentCount) / remaining / weight / limitedBy / replay / retryAfterMillis`。

### 2.7 旧配置兼容范围

- **不配置 `layers`**：由 `window / limit / dimensions` 为每个维度自动派生一层
  （窗口=window、上限=limit、仅封禁维度层 drivesBan），与旧版逐条计数行为完全一致。
- **不配置 `weights`**：所有请求权重为 1。
- 旧的热更新请求体（只有 `window/limit/dimensions`）依旧合法并语义不变；
  新字段（`layers`、`weights`、`replay`、`storage.rateStateFile`）均为可选。

---

## 3. 临时封禁规则

分层模式下，只有标记 `drivesBan=true` 的配额层（缺省为维度等于
`ratelimit.ban.dimension`（默认 `IP`）的层）的超限事件才驱动封禁升级；
判断“相邻窗口”时使用触发层自身的窗口长度。未显式配置 layers 时语义与旧版一致。

**升级（条件明确、确定）：**

1. 每个（触发层）限流窗口内，来源第一次超限时 `consecutiveLimitedWindows += 1`
   （同窗口重复超限不重复计数）。
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
  不参与任何配额层的计数。

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
| `ratelimit.policy.window` | `1s` | 固定窗口长度（layers 缺省时的派生窗口） |
| `ratelimit.policy.limit` | `10` | 每窗口每维度最大加权消耗量（layers 缺省时的派生上限） |
| `ratelimit.policy.dimensions` | `IP` | 限流维度（有序）：`IP`/`USER`/`PATH`/`GLOBAL` |
| `ratelimit.policy.layers[n].name` | 维度名派生 | 分层名称（拒绝原因/视图标识） |
| `ratelimit.policy.layers[n].dimension` | — | 该层维度（必填） |
| `ratelimit.policy.layers[n].window` | `policy.window` | 该层窗口长度 |
| `ratelimit.policy.layers[n].limit` | — | 该层窗口加权消耗上限（必填，正整数） |
| `ratelimit.policy.layers[n].drives-ban` | 维度=封禁维度 | 该层超限是否驱动封禁升级 |
| `ratelimit.policy.weights[n].path` | — | 权重匹配路径（精确优先，最长前缀） |
| `ratelimit.policy.weights[n].weight` | `1` | 命中路径的单请求配额消耗量（正整数） |
| `ratelimit.ban.enabled` | `true` | 是否启用封禁 |
| `ratelimit.ban.threshold-windows` | `3` | 连续超限窗口阈值，达到即升级 |
| `ratelimit.ban.level-duration[0..n]` | `1m,5m,30m` | 各级封禁时长 |
| `ratelimit.ban.observe-window` | `10m` | 解封后观察期 |
| `ratelimit.ban.reset-after-observe` | `true` | 观察期满未再犯是否清零 |
| `ratelimit.ban.dimension` | `IP` | 封禁判定维度 |
| `ratelimit.storage.ban-state-file` | `./data/ban-state.json` | 封禁持久化文件；空=仅内存 |
| `ratelimit.storage.rate-state-file` | `./data/rate-state.json` | 配额计数持久化文件；空=仅内存 |
| `ratelimit.storage.timeout` | `100ms` | 存储调用超时阈值，超过即降级 |
| `ratelimit.storage.fail-mode` | `FAIL_CLOSED` | `FAIL_CLOSED` / `FAIL_OPEN` |
| `ratelimit.replay.enabled` | `true` | 是否启用重试结论幂等缓存 |
| `ratelimit.replay.ttl` | `5s` | 同一请求标识结论缓存有效期 |
| `ratelimit.replay.capacity` | `10000` | 结论缓存最大条目数（LRU） |

时长支持 Spring Boot 简写（`500ms`、`30s`、`5m`）与 ISO-8601（`PT0.5S`）。

---

## 6. HTTP 接口

业务示例端点（受限流保护）：

- `GET /api/hello` → 200 `{"message":"ok"}`（轻请求）
- `GET /api/export/report` → 200 `{"message":"exported"}`（导出类重请求，配合权重示例）

管理端（`/admin/**` 不经过限流过滤器）：

| 方法与路径 | 作用 |
| --- | --- |
| `GET  /admin/policy/version` | 查看当前策略版本 |
| `POST /admin/policy/reload` | 提交整份配置热更新，原子发布新版本 |
| `GET  /admin/bans` | 查看全部来源封禁状态 |
| `POST /admin/bans/{key}/unban` | 人工解除（立即生效并持久化） |
| `GET  /admin/quota?dimension=IP&key=1.2.3.4` | 只读配额视图：各层用量/剩余/封禁状态（全局用 `dimension=GLOBAL&key=global`） |

热更新请求体与配置一一对应，时长接受 `"500ms"` 简写，例如：

```json
{
  "policy": {
    "window": "500ms", "limit": 20, "dimensions": ["IP"],
    "layers": [
      {"name": "ip-short", "dimension": "IP", "window": "500ms", "limit": 20},
      {"name": "global-long", "dimension": "GLOBAL", "window": "10s", "limit": 500}
    ],
    "weights": [{"path": "/api/export", "weight": 5}]
  },
  "ban": {"enabled": true, "thresholdWindows": 3,
          "levelDuration": ["1m", "5m", "30m"], "observeWindow": "10m",
          "resetAfterObserve": true, "dimension": "IP"},
  "storage": {"banStateFile": "./data/ban-state.json",
              "rateStateFile": "./data/rate-state.json",
              "timeout": "100ms", "failMode": "FAIL_CLOSED"},
  "replay": {"enabled": true, "ttl": "5s", "capacity": 10000}
}
```

> 兼容性：`layers` / `weights` / `replay` / `rateStateFile` 全部可选。
> 只提交旧字段（`window`/`limit`/`dimensions`）时行为与旧版一致，旧格式请求体示例：

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
`Retry-After`、`X-RateLimit-Layer`（分层拒绝时）、`X-RateLimit-Replay`（重试命中时），
失败开放时另有 `X-RateLimit-Degraded` / `X-RateLimit-Degraded-Cause`。

---

## 7. 本地验证

### 7.1 构建与测试（离线可用）

```bash
mvn -o test          # 42 个测试：加权/分层/重试一致性/视图/重启延续 + 既有限流封禁降级回归
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
- `WeightResolverTest`：精确优先/最长前缀/默认 1 的权重匹配规则。
- `WeightedQuotaTest`：重请求按权重消耗、拒绝携带层上限/已用/剩余、权重热更新只影响
  之后新窗口且历史判定（版本+权重）不改写、未配置权重与旧行为一致。
- `LayeredQuotaTest`：多层全部满足才放行且拒绝点名拦下层、长短窗口交叉切换计数独立、
  16 线程并发下各层用量精确（每层都计入被前层拦下的请求）、热更新版本隔离。
- `ReplayConsistencyTest`：同一请求标识重复/并发到达结论逐字段一致（放行与拒绝两种情形）、
  降级结论也可重放且不访问存储、TTL 过期重判、热更新后仍锁定首次策略版本。
- `QuotaViewTest`：视图按层给出用量/上限/剩余与封禁标志、窗口切换后归零、只读不改状态。
- `PersistenceRestartTest`：文件持久化重建服务后当前窗口用量延续、过窗计数丢弃、
  封禁状态延续、人工解除后重启保持已解除。
- `QuotaFeatureWebIntegrationTest`（MockMvc）：加权请求端到端、重试 `X-RateLimit-Replay`、
  429 响应体的分层字段、`/admin/quota` 视图数值。

单元测试日志由 `DefaultDecisionAuditLog` 打印，形如：

```
rate-limit decision requestId=mix-over-1 input=[ip=1.1.1.1, user=null, method=GET, path=/api/hello]
  result=[allowed=false, reason=RATE_LIMITED,
  detail=rate limit exceeded on layer 'ip-short' dimension=IP key=1.1.1.1 used=5 limit=4
         remaining=0 weight=1 windowStart=10000 windowMillis=2000,
  policyVersion=1, layerName=ip-short, layerLimit=4, remaining=0, weight=1, replay=false, ...]
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

# 1b) 重试一致性：同一个 X-Request-Id 重复发送，第二次带头 X-RateLimit-Replay=true 且结论不变
curl -s -D- -o /dev/null -H 'X-Request-Id: fixed-1' http://127.0.0.1:18080/api/hello | grep -i replay
curl -s -D- -o /dev/null -H 'X-Request-Id: fixed-1' http://127.0.0.1:18080/api/hello | grep -i replay

# 1c) 只读配额视图（各层用量/剩余/封禁状态）
curl -s 'http://127.0.0.1:18080/admin/quota?dimension=IP&key=127.0.0.1'

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

# 5) 重启进程后封禁仍生效（bannedUntil 未到）：再次启动后请求 /api/hello 返回 403；
#    默认同时持久化配额计数到 ./data/rate-state.json，重启后当前窗口用量延续（可由 /admin/quota 观察）
```

指定用户维度验证：请求带 `X-User-Id: alice` 即可按用户计数（需在 dimensions 中加入 `USER`）。

---

## 8. 设计取舍说明

- **固定窗口（每层各自对齐）**而非滑动窗口：实现简单、边界确定、可复现；
  窗口临界点可能有 2 倍突发，对本防护场景可接受，各层窗口长度可独立配置。
- **加权消耗而不是单独的“重请求限额”**：一个累加模型同时表达条数与资源消耗，
  未配置权重即退化为条数；权重调整通过版本化命名空间只影响之后的新窗口，历史不可变。
- **所有层先记账、后统一判定**：保证被前层拦下的请求仍计入后层（如全局长窗），
  各层用量在任何判定结果下都准确；代价是被拒请求也产生计数，这正是配额语义所需。
- **幂等结论按请求标识缓存而非按“内容指纹”**：与入口已有的 `X-Request-Id` 约定一致，
  重试判定简单确定；TTL 限制内存占用与“误长期重放”的风险，重放完全不触达状态存储。
- **计数器与封禁均可文件持久化，但都保持无外部依赖**：默认写本地 JSON（原子 rename），
  重启只加载未过窗口的计数；置空 `*-state-file` 即退化为纯内存，适合不同环境选择。
  文件存储面向本地/单实例验证，不追求多实例一致性。
- **版本化计数命名空间**：以少量内存换取热更新边界的确定性与历史判定不可变性。
- **降级在编排层统一裁决**：存储层只抛 `StoreException(Kind)`，由服务层按策略给出唯一结论，
  且降级结论进入幂等缓存，保证重试期间也不矛盾；可观测（日志 + 响应头）且不静默。
