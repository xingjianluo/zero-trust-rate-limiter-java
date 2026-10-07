package com.github.xingjianluo.zerotrustratelimiterjava.core;

import com.github.xingjianluo.zerotrustratelimiterjava.audit.DecisionAuditLog;
import com.github.xingjianluo.zerotrustratelimiterjava.config.RateLimitProperties;
import com.github.xingjianluo.zerotrustratelimiterjava.model.BanRecord;
import com.github.xingjianluo.zerotrustratelimiterjava.model.Decision;
import com.github.xingjianluo.zerotrustratelimiterjava.model.DecisionReason;
import com.github.xingjianluo.zerotrustratelimiterjava.model.FailMode;
import com.github.xingjianluo.zerotrustratelimiterjava.model.QuotaLayer;
import com.github.xingjianluo.zerotrustratelimiterjava.model.RequestContext;
import com.github.xingjianluo.zerotrustratelimiterjava.store.RateLimitStateStore;
import com.github.xingjianluo.zerotrustratelimiterjava.store.StoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 入口请求判定编排。
 *
 * <p>判定步骤（每一步对一次请求只产出一个一致结论，绝不互相矛盾）：
 * <ol>
 *   <li>幂等缓存：同一 requestId 在 TTL 内重复到达，直接返回首次结论
 *       （是否放行/被哪层拦下/原因/用量/策略版本完全一致），且不再访问状态存储；</li>
 *   <li>取当前不可变策略快照（热更新只影响之后的请求/之后新开始的窗口）；</li>
 *   <li>若启用封禁，先查封禁：封禁中直接拒绝（BANNED），不触碰配额计数；</li>
 *   <li>按权重规则解析本请求权重；对每一层配额用该层自己的窗口起点做加权原子累加，
 *       首个超限的层拒绝（RATE_LIMITED，带层名/上限/已用/剩余），
 *       且仅 drivesBan 的层记录一次窗口超限用于升级；全部满足才放行；</li>
 *   <li>任一存储调用抛错或超过超时阈值，按 FAIL_OPEN / FAIL_CLOSED 降级
 *       （降级结论同样进幂等缓存，重试时结论一致且不重复打存储）；</li>
 *   <li>所有结论写审计日志。</li>
 * </ol>
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);
    private static final long FAIL_CLOSED_RETRY_AFTER_MS = 1000L;

    private final RateLimitStateStore rateStore;
    private final BanService banService;
    private final KeyExtractor keyExtractor;
    private final PolicyHolder policyHolder;
    private final Clock clock;
    private final DecisionAuditLog auditLog;
    private final WeightResolver weightResolver;
    private final DecisionReplayCache replayCache;
    private final AtomicLong versionSeq = new AtomicLong(0L);

    private final ExecutorService guardExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ratelimit-store-guard");
                t.setDaemon(true);
                return t;
            });

    @org.springframework.beans.factory.annotation.Autowired
    public RateLimitService(RateLimitStateStore rateStore,
                            BanService banService,
                            KeyExtractor keyExtractor,
                            PolicyHolder policyHolder,
                            Clock clock,
                            DecisionAuditLog auditLog) {
        this(rateStore, banService, keyExtractor, policyHolder, clock, auditLog, new WeightResolver());
    }

    public RateLimitService(RateLimitStateStore rateStore,
                            BanService banService,
                            KeyExtractor keyExtractor,
                            PolicyHolder policyHolder,
                            Clock clock,
                            DecisionAuditLog auditLog,
                            WeightResolver weightResolver) {
        this.rateStore = rateStore;
        this.banService = banService;
        this.keyExtractor = keyExtractor;
        this.policyHolder = policyHolder;
        this.clock = clock;
        this.auditLog = auditLog;
        this.weightResolver = weightResolver;
        // 幂等缓存属于请求生命周期，默认 TTL 5s、容量 1 万；可由 Spring 属性通过
        // configureReplay() 在启动时调整，热更新不清空（重试结论必须跨热更新稳定）。
        this.replayCache = new DecisionReplayCache(nullSafeClock(clock), 5000L, 10_000);
    }

    private static Clock nullSafeClock(Clock c) {
        return c != null ? c : System::currentTimeMillis;
    }

    /** 判定入口。 */
    public Decision decide(RequestContext request) {
        RateLimitPolicy policy = policyHolder.current();

        // 1) 幂等：同一逻辑请求重复到达，直接复用首次结论（不访问任何状态存储）
        DecisionReplayCache cache = replayCache;
        Decision cached = cache.get(request.requestId());
        if (cached != null) {
            Decision replay = asReplay(cached);
            auditLog.append(request, replay);
            log.debug("replay cache hit requestId={} reason={} layer={}",
                    request.requestId(), replay.reason(), replay.layerName());
            return replay;
        }

        // 同 requestId 并发首次到达：串行化，只允许一次真实判定
        synchronized (cache.lockFor(request.requestId())) {
            cached = cache.get(request.requestId());
            if (cached != null) {
                Decision replay = asReplay(cached);
                auditLog.append(request, replay);
                return replay;
            }

            long now = clock.nowMillis();
            Decision decision;
            try {
                decision = doDecide(request, policy, now);
            } catch (StoreException e) {
                decision = degrade(request, policy, e.getKind().name(), e.getMessage(), now);
            }
            cache.put(request.requestId(), decision);
            auditLog.append(request, decision);
            return decision;
        }
    }

    private Decision asReplay(Decision first) {
        return new Decision(first.requestId(), first.allowed(), first.reason(), first.detail(),
                first.policyVersion(), first.windowStart(), first.currentCount(),
                first.limitedBy(), first.banLevel(), first.banUntil(), first.degraded(),
                first.degradedCause(), first.retriedAfterMillis(), first.layerName(),
                first.layerLimit(), first.remaining(), first.weight(), true);
    }

    private Decision doDecide(RequestContext request, RateLimitPolicy policy, long now) {
        // 2) 封禁优先检查
        if (policy.banEnabled()) {
            String banKey = keyExtractor.extract(policy.banDimension(), request);
            if (banKey != null) {
                Optional<BanRecord> active = withTimeout(
                        () -> banService.checkActiveBan(banKey, policy),
                        policy.timeoutMillis(), "checkBan");
                if (active.isPresent()) {
                    BanRecord ban = active.get();
                    long retryAfter = Math.max(0L, ban.bannedUntil() - now);
                    return new Decision(request.requestId(), false, DecisionReason.BANNED,
                            "source is temporarily banned at level " + ban.level()
                                    + " until " + ban.bannedUntil(),
                            policy.version(), -1L, -1L, null, ban.level(), ban.bannedUntil(),
                            false, null, retryAfter, null, -1L, -1L, -1L, false);
                }
            }
        }

        // 3) 权重解析：只依据当前快照；用量按请求时刻的权重累加
        long weight = weightResolver.resolve(request, policy.weights());

        // 4) 所有适用层都先独立累加本请求的加权消耗，再统一判断——不能因某层先超限就短路，
        //    否则后判定层（如全局长窗）会少计被前层拦下的流量，各层计数必须互相独立。
        record LayerResult(QuotaLayer layer, String key, long windowStart, long consumed) {
            boolean exceeded() {
                return consumed > layer.limit();
            }
        }
        java.util.List<LayerResult> results = new java.util.ArrayList<>();
        for (QuotaLayer layer : policy.layers()) {
            String key = keyExtractor.extract(layer.dimension(), request);
            if (key == null) {
                continue; // 该维度对当前请求不可用（如匿名用户的 USER 层），跳过
            }
            long windowStart = now - (now % layer.windowMillis());
            final long w = weight;
            long consumed = withTimeout(
                    () -> rateStore.consumeAndGet(policy.version(), layer, key, windowStart, w),
                    policy.timeoutMillis(), "consume:" + layer.name());
            results.add(new LayerResult(layer, key, windowStart, consumed));
        }
        // 首个超限层（按策略顺序）作为拦下原因，保证可解释且确定
        LayerResult blocking = results.stream()
                .filter(LayerResult::exceeded).findFirst().orElse(null);
        if (blocking != null) {
            QuotaLayer layer = blocking.layer();
            int banLevel = 0;
            long banUntil = -1L;
            if (policy.banEnabled() && layer.drivesBan()) {
                BanRecord updated = withTimeout(
                        () -> banService.recordLimitedWindow(
                                blocking.key(), blocking.windowStart(),
                                layer.windowMillis(), policy),
                        policy.timeoutMillis(), "recordLimited:" + layer.name());
                banLevel = updated.level();
                banUntil = updated.bannedUntil() > 0 ? updated.bannedUntil() : -1L;
            }
            long retryAfter = Math.max(0L, blocking.windowStart() + layer.windowMillis() - now);
            long remaining = Math.max(0L, layer.limit() - blocking.consumed());
            String detail = "rate limit exceeded on layer '" + layer.name() + "'"
                    + " dimension=" + layer.dimension() + " key=" + blocking.key()
                    + " used=" + blocking.consumed() + " limit=" + layer.limit()
                    + " remaining=" + remaining + " weight=" + weight
                    + " windowStart=" + blocking.windowStart() + " windowMillis=" + layer.windowMillis();
            return new Decision(request.requestId(), false, DecisionReason.RATE_LIMITED,
                    detail, policy.version(), blocking.windowStart(), blocking.consumed(),
                    layer.dimension(), banLevel, banUntil, false, null, retryAfter,
                    layer.name(), layer.limit(), remaining, weight, false);
        }

        return new Decision(request.requestId(), true, DecisionReason.ALLOWED,
                "allowed (weight=" + weight + ")", policy.version(), -1L, -1L, null,
                0, -1L, false, null, -1L, null, -1L, -1L, weight, false);
    }

    /**
     * 降级：同一请求只在此处产出唯一结论。FAIL_CLOSED 拒绝；FAIL_OPEN 放行但显式标记。
     */
    private Decision degrade(RequestContext request, RateLimitPolicy policy,
                             String cause, String message, long now) {
        long version = policy == null ? 0L : policy.version();
        FailMode mode = policy == null ? FailMode.FAIL_CLOSED : policy.failMode();
        if (mode == FailMode.FAIL_OPEN) {
            return new Decision(request.requestId(), true, DecisionReason.FAIL_OPEN,
                    "degraded allow due to " + cause + ": " + message,
                    version, -1L, -1L, null, 0, -1L, true, cause, -1L,
                    null, -1L, -1L, -1L, false);
        }
        return new Decision(request.requestId(), false, DecisionReason.FAIL_CLOSED,
                "degraded deny due to " + cause + ": " + message,
                version, -1L, -1L, null, 0, -1L, true, cause, FAIL_CLOSED_RETRY_AFTER_MS,
                null, -1L, -1L, -1L, false);
    }

    /**
     * 热更新：由配置构建新版本快照并原子发布，同时按配置重建幂等缓存。
     * 版本边界确定——进行中的请求持有旧快照，之后的请求使用新快照；
     * 旧版本计数键不再被引用，历史判定不被改写。
     *
     * @return 新版本号
     */
    public long reload(RateLimitProperties properties) {
        long newVersion = versionSeq.incrementAndGet();
        RateLimitPolicy policy = RateLimitPolicy.fromProperties(newVersion, properties);
        policyHolder.replace(policy);
        return newVersion;
    }

    /**
     * 按配置调整幂等缓存（启动初始化时调用；enabled=false 或 TTL<=0 表示关闭）。
     * 运行期热更新不调用本方法，保证重试结论跨策略版本稳定。
     */
    public void configureReplay(boolean enabled, long ttlMillis, int capacity) {
        replayCache.reconfigure(enabled ? ttlMillis : 0L, Math.max(1, capacity));
    }

    /** 当前策略版本；未初始化返回 -1。 */
    public long currentVersion() {
        RateLimitPolicy policy = policyHolder.current();
        return policy == null ? -1L : policy.version();
    }

    /** 当前生效策略快照（只读视图服务用）；未发布返回 null。 */
    public RateLimitPolicy currentPolicy() {
        return policyHolder.current();
    }

    /** 暴露计数存储（只读视图服务复用同一份状态）。 */
    public RateLimitStateStore stateStore() {
        return rateStore;
    }

    /** 暴露键提取器（只读视图服务用）。 */
    public KeyExtractor keyExtractor() {
        return keyExtractor;
    }

    /** 测试用：清空幂等缓存。 */
    public void clearReplayCache() {
        replayCache.clear();
    }

    /**
     * 带超时保护地执行存储调用。超时被 {@code cancel(true)} 中断并翻译为 STORE_TIMEOUT，
     * 其它异常翻译为 STORE_UNAVAILABLE，保证异常原因可区分、不静默吞掉。
     */
    private <T> T withTimeout(Supplier<T> action, long timeoutMillis, String opName) {
        Future<T> future = guardExecutor.submit(action::get);
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new StoreException(StoreException.Kind.STORE_TIMEOUT,
                    "store operation timed out: " + opName + " after " + timeoutMillis + "ms", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new StoreException(StoreException.Kind.STORE_TIMEOUT,
                    "interrupted waiting for store operation: " + opName, e);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof StoreException se) {
                throw se;
            }
            throw new StoreException(StoreException.Kind.STORE_UNAVAILABLE,
                    "store operation failed: " + opName, cause);
        }
    }
}
