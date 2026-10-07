# 自适应限流与临时封禁防护（zero-trust-rate-limiter-java）

面向**入口请求**的自适应限流、临时封禁与存储降级防护服务。基于 Spring Boot 3 / Java 21，
纯内存计数 + 本地 JSON 文件持久化，**无需任何外部服务或真实凭据**即可本地启动与验证。

判定结果满足：

- **一致**：并发与窗口切换下计数准确；一次请求只有一个自洽结论，不互相矛盾；同一逻辑请求
  （相同 `X-Request-Id`）在短时间内重复到达时结论完全一致，且不再访问后端状态存储。
- **可解释**：每个结论带原因码（`ALLOWED` / `RATE_LIMITED` / `BANNED` / `FAIL_OPEN` / `FAIL_CLOSED`）、
  触发维度、**命中的具体配额层（层序号/名称）、该层窗口、上限、当前已用量、剩余额度**、
  请求权重、策略版本、封禁级别与降级原因（`STORE_UNAVAILABLE` / `STORE_TIMEOUT`）。
- **可复现**：每个请求带 `X-Request-Id`；判定输入与最终结果打印审计日志；策略以不可变快照 +
  单调版本（跨重启延续）发布，热更新只对之后的新窗口/新请求生效，旧策略的历史判定不被静默改写。

配额模型（本轮新增）：

- **加权消耗**：单个请求按可配置的路径前缀权重消耗配额，窗口内按**实际累加的消耗量**判定；
  未配置/未匹配权重的请求权重为 1，与旧行为一致。
- **分层配额**：同一请求可同时受多层约束（如来源 1s 短窗口、来源 10s 长窗口、全局 1s 总量），
  每层独立窗口与上限，**所有层都满足才放行**；被拒时明确给出被哪一层拦下及该层用量/剩余。

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
  0. 幂等：按 X-Request-Id 查 DecisionCache（不访问任何存储）
       └─ 命中且未过期 → 返回与首次完全一致的结论（标记 replayed=true）；同标识并发只评估一次
  1. 取当前不可变 RateLimitPolicy 快照（PolicyHolder 原子引用，版本单调递增且跨重启延续）
  2. 若启用封禁：先查 BanService.checkActiveBan
       ├─ 封禁中 → 直接 BANNED（不触碰配额计数）
       └─ 顺带推进“到期解除 / 观察期满重置”
  3. 解析请求权重（路径前缀最长匹配；未匹配=1），逐层做固定窗口“有余量才预留”的原子 CAS
       ├─ 所有层 used+weight <= limit → 全部预留成功，ALLOWED
       ├─ 任一层不足 → 回滚本请求已预留层（被拒不消耗任何配额），RATE_LIMITED，
       │     返回命中层 index/名称/上限/已用/剩余；若该层维度=封禁维度则 recordLimitedWindow
       └─ 存储抛错或超时 → FAIL_CLOSED（503）或 FAIL_OPEN（放行但显式标记降级），降级结论同样入幂等缓存
  4. DecisionAuditLog：结构化日志 + 内存环形缓冲（只追加，不改写）
  ▼
响应：状态码 + 响应头 + JSON 错误体（原因/命中层/用量/剩余/权重可区分）
```

关键组件：

| 组件 | 职责 |
| --- | --- |
| `RateLimitFilter` | 入口过滤、请求标识、原因到状态码/响应头/JSON 体的映射 |
| `RateLimitService` | 判定编排、幂等、策略快照、加权分层预留/回滚、存储超时保护、降级、热更新 |
| `DecisionCache` | 按请求标识短期复用首次结论（分段锁 + 双重检查 + TTL），重试不访问存储 |
| `WeightResolver` | 按策略快照中的路径前缀权重做最长前缀匹配，未匹配权重为 1 |
| `QuotaViewService` | 按来源/维度只读聚合各层用量/剩余/封禁状态（与判定同源） |
| `PolicyHolder` / `RateLimitPolicy` / `QuotaTier` | 原子发布的不可变策略快照、单调版本与分层配额定义 |
| `InMemoryRateLimitStateStore` | 并发安全的加权配额计数（键含版本+维度+窗口长度+窗口起点），CAS 预留/回滚、只读快照、可选 JSON 持久化与故障注入 |
| `FileBanStateStore` | 封禁状态内存 + JSON 文件原子持久化，启动加载，支持故障注入 |
| `BanService` | 封禁升级、到期解除、观察期升级/重置、人工解除 |
| `KeyExtractor` | 各限流维度的键提取 |
| `DecisionAuditLog` | 请求标识 + 判定输入 + 最终结果的结构化审计日志 |
| `AdminController` | 策略热更新、封禁查询、人工解除、**配额只读视图**（不受限流约束） |

---

## 2. 限流规则（加权 + 分层配额）

### 2.1 配额层与维度

每个**配额层（tier）**由「维度 + 窗口长度 + 窗口上限（消耗量）」组成。可用维度：

| 维度 | 键来源 | 说明 |
| --- | --- | --- |
| `IP` | 对端 IP；有 `X-Forwarded-For` 时取首个 | 默认维度 |
| `USER` | 请求头 `X-User-Id` | 未携带时**跳过**该层（不把匿名用户聚为同键） |
| `PATH` | 请求路径 | 不同路径分别计数 |
| `GLOBAL` | 单一全局键 `global` | 全局限流 |

通过 `ratelimit.policy.tiers[i]` 可配置任意多层，例如：

- `tier[0] = IP / 1s / 10`（来源短窗口）
- `tier[1] = IP / 10s / 50`（来源长窗口）
- `tier[2] = GLOBAL / 1s / 200`（全局总量）

**一个请求同时受所有层约束，只有所有层都满足才放行**；判定按层序号顺序进行，
首个容量不足的层生效。错误体里 `limitedByTier`（层序号）、`tierName`（如
`tier[0]:IP:1000ms`）明确指出被哪一层拦下，同时给出 `tierLimit / currentUsage / tierRemaining`。

> 兼容：**不配置 `tiers` 时**，自动由旧的 `window / limit / dimensions` 生成
> “每个维度一层、同窗口同上限”的单层配额，旧配置行为完全不变（见 2.6）。

### 2.2 加权消耗

- 由 `ratelimit.policy.weights.<路径前缀>=<权重>` 配置单次请求消耗的配额量，
  按**最长前缀匹配**：同时配置 `/api=1` 与 `/api/export=10` 时，`/api/export/big` 取权重 10。
- 窗口内按**权重累加的消耗量**与上限比较：普通查询权重 1，导出/批量写入可配成更大值。
- **未配置任何权重、或路径未命中任何前缀时，权重恒为 1**，与“按请求条数计数”的旧行为一致。
- 权重表固化在策略快照里。热更新权重后产生**新版本计数命名空间**，只对之后新开始的窗口生效；
  之前窗口已产生的用量与历史判定不会被改写。

### 2.3 预留 / 回滚与计数准确性（并发、窗口交叉、热更新）

- 每层计数键为「策略版本 + 维度 + 维度键 + **窗口长度** + 窗口起点」。加入窗口长度是为了让
  短窗口层与长窗口层即使窗口起点相同（如都对齐到整 10 秒）也互不串用。
- 计数采用 `ConcurrentHashMap` + `AtomicLong` 的 **CAS“有余量才预留”**：
  `used + weight <= limit` 才原子累加，否则不计入并返回拒绝。
- 编排层逐层预留，**任一层不足时回滚该请求此前所有成功预留层**——被拒请求不消耗任何一层配额，
  因此不会出现“短窗口拒绝了、长窗口却被偷偷占用”的不一致；并发下各层计数精确
  （`TieredQuotaTest#concurrentAcrossTiersCountsExactly` 验证 16 线程 × 10 请求、上限 50 时
  恰好 50 放行 / 110 拒绝）。
- 短/长窗口交叉切换时各自独立滚动（`crossingWindowLengthsCountIndependently`）；
  热更新后新版本从 0 开始、旧版本计数作为历史保留不被引用
  （`hotReloadTiersStartFreshAndOldNamespaceKept`）。

### 2.4 重试幂等（同一逻辑请求结论完全一致）

- 以请求标识 `X-Request-Id` 为键：在 `ratelimit.policy.idempotent-ttl`（默认 30s）内重复到达时，
  直接复用首次的完整结论——是否放行、被哪一层拦下、原因、当前用量、剩余额度、策略版本全部一致，
  响应/审计中以 `replayed=true` 标记为回放。
- 重试**不再访问配额/封禁状态存储**，避免给后端存储带来额外压力。
- 同一标识并发到达时按标识分段加锁 + 双重检查，**只有一个线程真正判定**，其余等待并复用同一结论，
  杜绝“第一次放行、重试被拒”。
- 该保证在**存储处于降级状态时同样成立**：降级结论（FAIL_OPEN / FAIL_CLOSED）也会被缓存复用；
  甚至首次发生在降级期间，存储恢复后的重试仍返回首次的降级结论（一致性优先）。

### 2.5 拒绝响应

- HTTP `429 Too Many Requests`，含 `Retry-After`（到命中层下一窗口的秒数）。
- 响应头：`X-Request-Id`、`X-RateLimit-Decision=RATE_LIMITED`、`X-RateLimit-Policy-Version`。
- JSON 体：`requestId / reason / detail / policyVersion / windowStart / limitedBy /
  limitedByTier / tierName / tierLimit / currentUsage / tierRemaining / requestWeight /
  replayed / retryAfterMillis`。

### 2.6 只读配额视图

`GET /admin/quota?dimension=IP&key=<来源键>`（位于 `/admin/**`，不受限流约束）返回该来源在
当前策略下相关各层的：窗口长度、当前窗口起点、上限、已用量、剩余额度，以及是否处于封禁中
（`banned / banLevel / banUntil`）和对应 `policyVersion`。视图与入口判定读取**同一份内存计数
与封禁状态**（`QuotaViewService` + `RateLimitStateStore#snapshot`），只读、不改变任何状态
（见 `QuotaViewTest`）。

### 2.7 旧配置兼容范围

| 旧配置 | 新模型下的含义 |
| --- | --- |
| 未配置 `tiers` | 用 `window/limit/dimensions` 为每个维度生成同窗口同上限的单层 |
| 未配置 `weights` | 所有请求权重 1，按请求条数计数（旧行为） |
| 未配置 `idempotent-ttl` | 默认 30s |
| 旧的拒绝字段 `currentCount / limitedBy` | 保留；新增 `currentUsage / limitedByTier / tierName / tierLimit / tierRemaining / requestWeight / replayed` |
| 旧计数语义“被拒也 +1” | 调整为“被拒不消耗配额”（分层预留/回滚所必需，视图用量因此不再虚高） |

---

## 3. 临时封禁规则

封禁只统计 `ratelimit.ban.dimension`（默认 `IP`）这一维度对应层的超限事件；
连续窗口的相邻判断使用封禁维度层的窗口长度。

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
  进程重启后加载，封禁/解封（含人工解除）语义保持一致，**重启后不会重新变回封禁**；置空则仅内存。
- 配额计数持久化到 `ratelimit.storage.quota-state-file`：每次计数变化在单线程守护执行器上
  异步合并写盘（同一时刻最多一个待写任务，临时文件 + 原子 rename），重启后加载，当前窗口的
  各层用量得以延续、不被清零；置空则仅内存。
- **策略版本号跨重启单调延续**：版本号持久化在配额文件同目录的 `policy-version.dat`，
  重启首发沿用上次版本，保证已持久化计数的命名空间不被复用；热更新才递增版本。
- **策略配置跨重启恢复**：最近一次生效的完整配置（分层 tiers、权重 weights、幂等 TTL、封禁与
  存储设置）原子持久化到同目录的 `policy.json`；进程重启时优先加载它，因此重启后仍是热更新后的
  分层/加权规则（而不是回到 application.properties 的默认单层）。无该文件时回退到启动配置。
  （未配置配额文件时策略也不持久化，版本随进程从 1 开始。）
- 封禁中的请求直接返回 `403 Forbidden`（`X-RateLimit-Decision=BANNED`，带 `Retry-After`），
  不参与限流计数。

---

## 4. 存储降级规则

所有存储访问（封禁读写、配额预留/读取）都通过单线程守护执行器加超时保护。
**命中重试幂等缓存的请求不访问任何存储，因此降级期间重试仍返回首次结论：**

| 触发条件 | 区分原因 | FAIL_CLOSED（默认） | FAIL_OPEN |
| --- | --- | --- | --- |
| 存储抛异常 / 不可用 | `STORE_UNAVAILABLE` | HTTP `503` 拒绝 | 放行 |
| 调用耗时超过 `ratelimit.storage.timeout` | `STORE_TIMEOUT` | HTTP `503` 拒绝 | 放行 |

- 降级时**一次请求只产出一个结论**（在 `RateLimitService` 统一捕获并翻译为 `StoreException`）；
  预留过程中若发生存储故障，会先回滚已预留层，不会出现“扣了部分配额又拒绝”的矛盾结果。
- 失败开放**不静默放行**：响应头带 `X-RateLimit-Decision=FAIL_OPEN`、
  `X-RateLimit-Degraded=true`、`X-RateLimit-Degraded-Cause=STORE_TIMEOUT|STORE_UNAVAILABLE`，
  同时写入审计日志。
- 失败关闭返回 503、`Retry-After: 1` 与 JSON 错误体；`/admin/**` 不经过限流过滤器，
  即使在降级状态下人工解除通道仍可用。

---

## 5. 配置项（`application.properties`）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `ratelimit.policy.tiers[i].dimension` | — | 第 i 层维度：`IP`/`USER`/`PATH`/`GLOBAL` |
| `ratelimit.policy.tiers[i].window` | — | 第 i 层固定窗口长度（如 `1s`、`10s`） |
| `ratelimit.policy.tiers[i].limit` | — | 第 i 层窗口内最大累计消耗量（按权重） |
| `ratelimit.policy.weights.<前缀>` | 空 | 路径前缀 → 请求权重；最长前缀匹配；未命中=1 |
| `ratelimit.policy.idempotent-ttl` | `30s` | 同一请求标识重试复用结论的窗口 |
| `ratelimit.policy.window` | `1s` | 兼容字段：未配置 tiers 时的窗口长度 |
| `ratelimit.policy.limit` | `10` | 兼容字段：未配置 tiers 时的窗口上限 |
| `ratelimit.policy.dimensions` | `IP` | 兼容字段：未配置 tiers 时的维度（有序） |
| `ratelimit.ban.enabled` | `true` | 是否启用封禁 |
| `ratelimit.ban.threshold-windows` | `3` | 连续超限窗口阈值，达到即升级 |
| `ratelimit.ban.level-duration[0..n]` | `1m,5m,30m` | 各级封禁时长 |
| `ratelimit.ban.observe-window` | `10m` | 解封后观察期 |
| `ratelimit.ban.reset-after-observe` | `true` | 观察期满未再犯是否清零 |
| `ratelimit.ban.dimension` | `IP` | 封禁判定维度 |
| `ratelimit.storage.ban-state-file` | `./data/ban-state.json` | 封禁持久化文件；空=仅内存 |
| `ratelimit.storage.quota-state-file` | `./data/quota-state.json` | 配额计数持久化文件；空=仅内存 |
| `ratelimit.storage.timeout` | `100ms` | 存储调用超时阈值，超过即降级 |
| `ratelimit.storage.fail-mode` | `FAIL_CLOSED` | `FAIL_CLOSED` / `FAIL_OPEN` |

时长支持 Spring Boot 简写（`500ms`、`30s`、`5m`）与 ISO-8601（`PT0.5S`）。

分层 + 加权配置示例：

```properties
ratelimit.policy.tiers[0].dimension=IP
ratelimit.policy.tiers[0].window=1s
ratelimit.policy.tiers[0].limit=10
ratelimit.policy.tiers[1].dimension=IP
ratelimit.policy.tiers[1].window=10s
ratelimit.policy.tiers[1].limit=50
ratelimit.policy.tiers[2].dimension=GLOBAL
ratelimit.policy.tiers[2].window=1s
ratelimit.policy.tiers[2].limit=200
ratelimit.policy.weights./api/query=1
ratelimit.policy.weights./api/export=10
```

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
| `GET  /admin/quota?dimension=IP&key=<来源键>` | 只读配额视图：各层用量/剩余/封禁状态 |

热更新请求体与配置一一对应，时长接受 `"500ms"` 简写。不传 `tiers` 即沿用旧单层配置；
传入分层与权重时例如：

```json
{
  "policy": {
    "tiers": [
      {"dimension": "IP", "window": "1s", "limit": 10},
      {"dimension": "IP", "window": "10s", "limit": 50},
      {"dimension": "GLOBAL", "window": "1s", "limit": 200}
    ],
    "weights": {"/api/query": 1, "/api/export": 10},
    "idempotentTtl": "30s"
  },
  "ban": {"enabled": true, "thresholdWindows": 3,
          "levelDuration": ["1m", "5m", "30m"], "observeWindow": "10m",
          "resetAfterObserve": true, "dimension": "IP"},
  "storage": {"banStateFile": "./data/ban-state.json",
              "quotaStateFile": "./data/quota-state.json",
              "timeout": "100ms", "failMode": "FAIL_CLOSED"}
}
```

> 旧字段写法（`window/limit/dimensions`）仍然接受：只要不传 `tiers`，即由它们生成单层配额。

响应头速查：`X-Request-Id`、`X-RateLimit-Decision`、`X-RateLimit-Policy-Version`、
`Retry-After`，失败开放时另有 `X-RateLimit-Degraded` / `X-RateLimit-Degraded-Cause`。

---

## 7. 本地验证

### 7.1 构建与测试（离线可用）

```bash
mvn -o test          # 42 个测试：加权/分层/幂等/视图/重启延续 + 原有窗口·并发·热更新·封禁·降级
mvn -o package -DskipTests
```

测试覆盖：

- `RateLimitServiceTest`：固定窗口准确放行/拒绝、窗口切换重置、16 线程并发计数精确、
  热更新只影响新窗口且历史判定版本不改写、`STORE_UNAVAILABLE`/`STORE_TIMEOUT` ×
  FAIL_OPEN/FAIL_CLOSED 四象限、结论自洽性。
- `WeightedQuotaTest`：重请求按权重消耗、最长前缀匹配、未配置权重等同请求条数、
  权重热更新只影响新窗口且历史用量/版本不改写。
- `TieredQuotaTest`：多层全部满足才放行、拒绝明确命中层（序号/上限/已用/剩余）、
  GLOBAL 跨来源拦截、短/长窗口交叉各自准确、16 线程并发各层精确、热更新各层不串计数。
- `IdempotentRetryTest`：同一请求标识重复到达结论完全一致（放行/被拒/降级三种）、
  重试不触碰存储、同标识并发只评估一次、降级期间首次结论也被复用、TTL 过后重新判定。
- `QuotaViewTest`：视图按层报告用量/剩余、与拒绝判定同源、只读不改状态、窗口切换与未知来源。
- `RestartPersistenceTest`：配额（加权/分层）用量写盘重启后延续、策略版本号跨重启单调、
  封禁状态重启延续、人工解除后重启不再变回封禁。
- `BanServiceTest`：连续超限升级、同窗口不重复计数、观察期再犯级别 +1、观察期满清零、
  人工立即解除、正常流量永不被封。
- `BanIntegrationTest`：经真实服务编排验证升级→封禁中不计数→到期解除→人工解除。
- `WebIntegrationTest`（MockMvc）：429/403/503 状态码与响应头、失败开放标记头、
  ISO 简写热更新、管理端在存储故障时仍可用、分层拒绝体含命中层明细、`/admin/quota` 只读视图。

单元测试日志由 `DefaultDecisionAuditLog` 打印，能看到请求标识、判定输入与最终结论（含权重、
命中层、用量、剩余、是否回放），形如：

```
rate-limit decision requestId=h-2 input=[ip=10.0.0.1, user=null, method=GET, path=/api/export/big]
  result=[allowed=false, reason=RATE_LIMITED,
  detail=quota exceeded on tier[0]:IP:1000ms key=10.0.0.1 used=8 limit=10 remaining=2 weight=8 ...,
  requestWeight=8, limitedByTier=0, tierName=tier[0]:IP:1000ms, tierLimit=10, tierRemaining=2, replayed=false]
rate-limit decision requestId=h-2 ... replayed=true   # 同一请求标识重试：结论一致且不访问存储
```

### 7.2 启动与手工验证

```bash
# 内存态快速验证（端口随机，见启动日志；以下用固定端口演示）
java -jar target/zero-trust-rate-limiter-java-1.0-SNAPSHOT.jar \
     --server.port=18080 \
     --ratelimit.policy.limit=3 \
     --ratelimit.policy.window=1s \
     --ratelimit.storage.ban-state-file=/tmp/ban-state.json \
     --ratelimit.storage.quota-state-file=/tmp/quota-state.json
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

# 4) 热更新为“分层 + 加权”（新窗口生效，版本号 +1）
curl -s -X POST http://127.0.0.1:18080/admin/policy/reload \
  -H 'Content-Type: application/json' -d '{
    "policy":{
      "tiers":[{"dimension":"IP","window":"1s","limit":10},
               {"dimension":"IP","window":"10s","limit":50},
               {"dimension":"GLOBAL","window":"1s","limit":200}],
      "weights":{"/api/query":1,"/api/export":10},
      "idempotentTtl":"30s"},
    "ban":{"enabled":true,"thresholdWindows":3,"levelDuration":["1m","5m","30m"],
           "observeWindow":"10m","resetAfterObserve":true,"dimension":"IP"},
    "storage":{"banStateFile":"/tmp/ban-state.json","quotaStateFile":"/tmp/quota-state.json",
               "timeout":"100ms","failMode":"FAIL_CLOSED"}}'

# 5) 加权消耗：导出请求一次消耗 10；只读视图查看各层用量/剩余
curl -s http://127.0.0.1:18080/api/hello -H 'X-Forwarded-For: 9.9.9.9'   # 权重 1
curl -s "http://127.0.0.1:18080/admin/quota?dimension=IP&key=9.9.9.9" | jq .

# 6) 重试一致性：相同 X-Request-Id 重复请求，结论完全相同（含 replayed=true），且不重复扣配额
for i in 1 2 3; do
  curl -s -o /dev/null -w "%{http_code}\n" \
       http://127.0.0.1:18080/api/hello -H 'X-Request-Id: fixed-id-123'
done

# 7) 重启进程后：当前窗口各层用量延续；封禁（含人工解除）状态也延续
```

指定用户维度验证：请求带 `X-User-Id: alice` 即可按用户计数（在某层 `dimension` 配置为 `USER`）。
导出类重请求可打到 `/api/export/...`（配合上面的权重配置体验一次消耗 10）。

---

## 8. 设计取舍说明

- **固定窗口**而非滑动窗口：实现简单、边界确定、可复现；窗口临界点可能有 2 倍突发，
  对本防护场景可接受，窗口长度/层数可调。
- **加权“预留 + 回滚”而非先扣再判**：分层配额要求被拒请求不消耗任何一层，因此采用每层
  CAS“有余量才预留”，任一层不足即回滚已预留层；代价是一次请求可能触达多层计数器，
  但层数通常很少（2~3 层），换取跨层严格一致。
- **被拒请求不计数**：与早期“拒绝也 +1”不同，新语义下用量恒等于实际放行的加权消耗，
  只读视图的已用/剩余与真实容量一致，也使回滚干净。
- **计数键含窗口长度**：短/长窗口层在窗口起点对齐时也互不串用，是分层正确性的关键。
- **配额计数与封禁均持久化**：配额写本地 JSON（异步合并 + 原子 rename，测试可 `flush()`），
  策略版本号单独持久化以保证重启后命名空间不被复用；适合本地/单实例，不依赖外部服务。
- **幂等结论短期内存缓存**：重试一致性与“不压垮存储”都在进程内解决（分段锁 + 双重检查 + TTL），
  不引入外部依赖；多实例部署时各实例对同一请求标识也各自保证“同标识只判一次”。
- **版本化计数命名空间**：以少量内存换取热更新边界的确定性与历史判定不可变性。
- **降级在编排层统一裁决**：存储层只抛 `StoreException(Kind)`，由服务层按策略给出唯一结论，
  保证可观测（日志 + 响应头）且不静默；预留中途故障先回滚，降级结论同样可被重试幂等复用。
