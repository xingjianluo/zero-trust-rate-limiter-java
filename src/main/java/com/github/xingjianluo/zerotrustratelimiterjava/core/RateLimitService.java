package com.github.xingjianluo.zerotrustratelimiterjava.core;

import com.github.xingjianluo.zerotrustratelimiterjava.audit.DecisionAuditLog;
import com.github.xingjianluo.zerotrustratelimiterjava.config.RateLimitProperties;
import com.github.xingjianluo.zerotrustratelimiterjava.model.BanRecord;
import com.github.xingjianluo.zerotrustratelimiterjava.model.Decision;
import com.github.xingjianluo.zerotrustratelimiterjava.model.DecisionReason;
import com.github.xingjianluo.zerotrustratelimiterjava.model.FailMode;
import com.github.xingjianluo.zerotrustratelimiterjava.model.LimitDimension;
import com.github.xingjianluo.zerotrustratelimiterjava.model.RequestContext;
import com.github.xingjianluo.zerotrustratelimiterjava.store.RateLimitStateStore;
import com.github.xingjianluo.zerotrustratelimiterjava.store.StoreException;
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
 *   <li>取当前不可变策略快照（热更新只影响之后的请求）；</li>
 *   <li>若启用封禁，先查封禁：封禁中直接拒绝（BANNED），不触碰限流计数；</li>
 *   <li>按配置顺序逐维度做固定窗口原子计数，首个超过阈值的维度拒绝（RATE_LIMITED），
 *       且仅当触发维度等于封禁维度时记录一次窗口超限用于升级；</li>
 *   <li>任一存储调用抛错或超过超时阈值，按 FAIL_OPEN / FAIL_CLOSED 降级；</li>
 *   <li>所有结论写审计日志。</li>
 * </ol>
 */
@Service
public class RateLimitService {

    private static final long FAIL_CLOSED_RETRY_AFTER_MS = 1000L;

    private final RateLimitStateStore rateStore;
    private final BanService banService;
    private final KeyExtractor keyExtractor;
    private final PolicyHolder policyHolder;
    private final Clock clock;
    private final DecisionAuditLog auditLog;
    private final AtomicLong versionSeq = new AtomicLong(0L);

    private final ExecutorService guardExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ratelimit-store-guard");
                t.setDaemon(true);
                return t;
            });

    public RateLimitService(RateLimitStateStore rateStore,
                            BanService banService,
                            KeyExtractor keyExtractor,
                            PolicyHolder policyHolder,
                            Clock clock,
                            DecisionAuditLog auditLog) {
        this.rateStore = rateStore;
        this.banService = banService;
        this.keyExtractor = keyExtractor;
        this.policyHolder = policyHolder;
        this.clock = clock;
        this.auditLog = auditLog;
    }

    /** 判定入口。 */
    public Decision decide(RequestContext request) {
        RateLimitPolicy policy = policyHolder.current();
        long now = clock.nowMillis();
        Decision decision;
        try {
            decision = doDecide(request, policy, now);
        } catch (StoreException e) {
            decision = degrade(request, policy, e.getKind().name(), e.getMessage(), now);
        }
        auditLog.append(request, decision);
        return decision;
    }

    private Decision doDecide(RequestContext request, RateLimitPolicy policy, long now) {
        // 1) 封禁优先检查
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
                            false, null, retryAfter);
                }
            }
        }

        // 2) 逐维度固定窗口计数
        long windowStart = now - (now % policy.windowMillis());
        for (LimitDimension dimension : policy.dimensions()) {
            String key = keyExtractor.extract(dimension, request);
            if (key == null) {
                continue; // 该维度对当前请求不可用（如匿名用户的 USER 维度），跳过
            }
            long count = withTimeout(
                    () -> rateStore.incrementAndGet(policy.version(), dimension, key, windowStart),
                    policy.timeoutMillis(), "increment:" + dimension);
            if (count > policy.limit()) {
                // 3) 仅封禁维度的超限才驱动升级
                int banLevel = 0;
                long banUntil = -1L;
                if (policy.banEnabled() && dimension == policy.banDimension()) {
                    BanRecord updated = withTimeout(
                            () -> banService.recordLimitedWindow(key, windowStart, policy),
                            policy.timeoutMillis(), "recordLimited");
                    banLevel = updated.level();
                    banUntil = updated.bannedUntil() > 0 ? updated.bannedUntil() : -1L;
                }
                long retryAfter = Math.max(0L, windowStart + policy.windowMillis() - now);
                return new Decision(request.requestId(), false, DecisionReason.RATE_LIMITED,
                        "rate limit exceeded on dimension " + dimension + " key=" + key
                                + " count=" + count + " limit=" + policy.limit()
                                + " windowStart=" + windowStart,
                        policy.version(), windowStart, count, dimension, banLevel, banUntil,
                        false, null, retryAfter);
            }
        }

        return new Decision(request.requestId(), true, DecisionReason.ALLOWED, "allowed",
                policy.version(), windowStart, -1L, null, 0, -1L, false, null, -1L);
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
                    version, -1L, -1L, null, 0, -1L, true, cause, -1L);
        }
        return new Decision(request.requestId(), false, DecisionReason.FAIL_CLOSED,
                "degraded deny due to " + cause + ": " + message,
                version, -1L, -1L, null, 0, -1L, true, cause, FAIL_CLOSED_RETRY_AFTER_MS);
    }

    /**
     * 热更新：由配置构建新版本快照并原子发布。版本边界确定——进行中的请求持有旧快照，
     * 之后的请求使用新快照；旧版本计数键不再被引用，历史判定不被改写。
     *
     * @return 新版本号
     */
    public long reload(RateLimitProperties properties) {
        long newVersion = versionSeq.incrementAndGet();
        RateLimitPolicy policy = RateLimitPolicy.fromProperties(newVersion, properties);
        policyHolder.replace(policy);
        return newVersion;
    }

    /** 当前策略版本；未初始化返回 -1。 */
    public long currentVersion() {
        RateLimitPolicy policy = policyHolder.current();
        return policy == null ? -1L : policy.version();
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
