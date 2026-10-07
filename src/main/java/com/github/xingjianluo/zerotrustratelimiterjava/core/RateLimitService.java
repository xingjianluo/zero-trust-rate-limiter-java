package com.github.xingjianluo.zerotrustratelimiterjava.core;

import com.github.xingjianluo.zerotrustratelimiterjava.audit.DecisionAuditLog;
import com.github.xingjianluo.zerotrustratelimiterjava.config.RateLimitProperties;
import com.github.xingjianluo.zerotrustratelimiterjava.model.BanRecord;
import com.github.xingjianluo.zerotrustratelimiterjava.model.Decision;
import com.github.xingjianluo.zerotrustratelimiterjava.model.DecisionReason;
import com.github.xingjianluo.zerotrustratelimiterjava.model.FailMode;
import com.github.xingjianluo.zerotrustratelimiterjava.model.RequestContext;
import com.github.xingjianluo.zerotrustratelimiterjava.store.RateLimitStateStore;
import com.github.xingjianluo.zerotrustratelimiterjava.store.StoreException;
import com.github.xingjianluo.zerotrustratelimiterjava.store.PolicyStateStore;
import org.springframework.stereotype.Service;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 入口请求判定编排（加权 + 分层配额 + 重试幂等）。
 *
 * <p>判定步骤（每一步对一次请求只产出一个一致结论，绝不互相矛盾）：
 * <ol>
 *   <li><b>重试幂等</b>：先按请求标识查 {@link DecisionCache}，命中且未过期则原样返回首次结论
 *       （含是否放行/被哪层拦下/原因/用量/策略版本），不访问任何后端状态存储；同标识并发只评估一次；</li>
 *   <li>取当前不可变策略快照（热更新只影响之后的请求）；</li>
 *   <li>若启用封禁，先查封禁：封禁中直接拒绝（BANNED），不触碰配额计数；</li>
 *   <li>解析请求权重，按层级顺序对每一层做固定窗口加权原子消耗：
 *       任一层 {@code used > limit} 即拒绝（RATE_LIMITED），并携带层序号/名称/上限/已用/剩余；
 *       仅当命中层维度等于封禁维度时记录一次窗口超限用于升级；所有层满足才放行；</li>
 *   <li>任一存储调用抛错或超过超时阈值，按 FAIL_OPEN / FAIL_CLOSED 降级（降级结论同样被幂等缓存）；</li>
 *   <li>所有结论写审计日志。</li>
 * </ol>
 */
@Service
public class RateLimitService {

    private static final long FAIL_CLOSED_RETRY_AFTER_MS = 1000L;

    private final RateLimitStateStore rateStore;
    private final BanService banService;
    private final KeyExtractor keyExtractor;
    private final WeightResolver weightResolver;
    private final PolicyHolder policyHolder;
    private final DecisionCache decisionCache;
    private final Clock clock;
    private final DecisionAuditLog auditLog;
    private final java.nio.file.Path versionFile;
    private final PolicyStateStore policyStateStore;
    private final AtomicLong versionSeq = new AtomicLong(0L);

    private final ExecutorService guardExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ratelimit-store-guard");
                t.setDaemon(true);
                return t;
            });

    /** 便捷构造：按配额文件路径自建策略配置存储（测试/手工装配使用）。 */
    public RateLimitService(RateLimitStateStore rateStore,
                            BanService banService,
                            KeyExtractor keyExtractor,
                            WeightResolver weightResolver,
                            PolicyHolder policyHolder,
                            DecisionCache decisionCache,
                            Clock clock,
                            DecisionAuditLog auditLog,
                            String quotaStateFile) {
        this(rateStore, banService, keyExtractor, weightResolver, policyHolder, decisionCache,
                clock, auditLog, versionFileOf(quotaStateFile),
                PolicyStateStore.forQuotaFile(quotaStateFile));
    }

    /** 字段全量私有构造（路径与策略存储都已解析好）。 */
    private RateLimitService(RateLimitStateStore rateStore,
                             BanService banService,
                             KeyExtractor keyExtractor,
                             WeightResolver weightResolver,
                             PolicyHolder policyHolder,
                             DecisionCache decisionCache,
                             Clock clock,
                             DecisionAuditLog auditLog,
                             java.nio.file.Path versionFilePath,
                             PolicyStateStore policyStateStore) {
        this.rateStore = rateStore;
        this.banService = banService;
        this.keyExtractor = keyExtractor;
        this.weightResolver = weightResolver;
        this.policyHolder = policyHolder;
        this.decisionCache = decisionCache;
        this.clock = clock;
        this.auditLog = auditLog;
        this.policyStateStore = policyStateStore;
        this.versionFile = versionFilePath;
        this.versionSeq.set(loadVersion());
    }

    /** 全量构造（Spring 装配使用）：文件路径由 {@code ratelimit.storage.quota-state-file} 注入。 */
    @Autowired
    public RateLimitService(RateLimitStateStore rateStore,
                            BanService banService,
                            KeyExtractor keyExtractor,
                            WeightResolver weightResolver,
                            PolicyHolder policyHolder,
                            DecisionCache decisionCache,
                            Clock clock,
                            DecisionAuditLog auditLog,
                            @org.springframework.beans.factory.annotation.Value(
                                    "${ratelimit.storage.quota-state-file:}") String quotaStateFile,
                            PolicyStateStore policyStateStore) {
        this(rateStore, banService, keyExtractor, weightResolver, policyHolder, decisionCache,
                clock, auditLog, versionFileOf(quotaStateFile), policyStateStore);
    }

    private static java.nio.file.Path versionFileOf(String quotaStateFile) {
        if (quotaStateFile == null || quotaStateFile.isBlank()) {
            return null;
        }
        return java.nio.file.Path.of(quotaStateFile).toAbsolutePath()
                .resolveSibling("policy-version.dat");
    }

    /**
     * 向后兼容构造：使用默认权重解析器与进程内幂等缓存，且不持久化策略版本（纯内存）。
     * 仅供既有单元测试使用。
     */
    public RateLimitService(RateLimitStateStore rateStore,
                            BanService banService,
                            KeyExtractor keyExtractor,
                            PolicyHolder policyHolder,
                            Clock clock,
                            DecisionAuditLog auditLog) {
        this(rateStore, banService, keyExtractor, new WeightResolver(),
                policyHolder, new DecisionCache(clock), clock, auditLog, "");
    }

    /**
     * 向后兼容构造（供需要显式权重解析器/缓存的测试使用），不持久化策略版本。
     */
    public RateLimitService(RateLimitStateStore rateStore,
                            BanService banService,
                            KeyExtractor keyExtractor,
                            WeightResolver weightResolver,
                            PolicyHolder policyHolder,
                            DecisionCache decisionCache,
                            Clock clock,
                            DecisionAuditLog auditLog) {
        this(rateStore, banService, keyExtractor, weightResolver, policyHolder,
                decisionCache, clock, auditLog, "");
    }

    // ---- 策略版本号持久化：保证重启后版本单调延续，计数命名空间不被复用 ----

    private long loadVersion() {
        if (versionFile == null || !java.nio.file.Files.exists(versionFile)) {
            return 0L;
        }
        try {
            String text = java.nio.file.Files.readString(versionFile).trim();
            return text.isBlank() ? 0L : Long.parseLong(text);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(RateLimitService.class)
                    .warn("failed to load policy version from {}, starting at 0", versionFile, e);
            return 0L;
        }
    }

    private void persistVersion(long version) {
        if (versionFile == null) {
            return;
        }
        try {
            java.nio.file.Files.createDirectories(versionFile.toAbsolutePath().getParent());
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile(
                    versionFile.toAbsolutePath().getParent(), "policy-version-", ".tmp");
            java.nio.file.Files.writeString(tmp, Long.toString(version));
            try {
                java.nio.file.Files.move(tmp, versionFile,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.io.IOException atomicFail) {
                java.nio.file.Files.move(tmp, versionFile,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (java.io.IOException e) {
            org.slf4j.LoggerFactory.getLogger(RateLimitService.class)
                    .warn("failed to persist policy version to {}", versionFile, e);
        }
    }

    /** 判定入口。 */
    public Decision decide(RequestContext request) {
        // 0) 重试幂等：先查缓存（不访问存储），命中即返回与首次完全一致的结论（仅标记为回放）
        Optional<Decision> cached = decisionCache.get(request.requestId());
        if (cached.isPresent()) {
            Decision replay = markReplayed(cached.get());
            auditLog.append(request, replay);
            return replay;
        }

        RateLimitPolicy policy = policyHolder.current();
        long now = clock.nowMillis();
        Object lock = decisionCache.lockFor(request.requestId());
        Decision decision;
        synchronized (lock) {
            // 双重检查：并发的同一标识请求在此复用已完成结论
            cached = decisionCache.get(request.requestId());
            if (cached.isPresent()) {
                decision = markReplayed(cached.get());
            } else {
                try {
                    decision = doDecide(request, policy, now);
                } catch (StoreException e) {
                    decision = degrade(request, policy, e.getKind().name(), e.getMessage(), now);
                }
                // 首次结论（含降级结论）固化，后续重试完全一致，且不再访问存储
                decisionCache.putCompleted(request.requestId(), decision, policy.idempotentTtlMs());
            }
        }
        auditLog.append(request, decision);
        return decision;
    }

    /**
     * 构造回放结论：是否放行/原因/被哪层拦下/用量/版本等与首次完全一致，仅 {@code replayed}
     * 置真以区分这是重试复用（该元数据标记不改变任何判定语义）。
     */
    private Decision markReplayed(Decision d) {
        if (d.replayed()) {
            return d;
        }
        return new Decision(d.requestId(), d.allowed(), d.reason(), d.detail(), d.policyVersion(),
                d.windowStart(), d.currentCount(), d.limitedBy(), d.banLevel(), d.banUntil(),
                d.degraded(), d.degradedCause(), d.retriedAfterMillis(), d.requestWeight(),
                d.limitedByTier(), d.tierName(), d.tierLimit(), d.tierRemaining(), true);
    }

    private Decision doDecide(RequestContext request, RateLimitPolicy policy, long now) {
        // 1) 封禁优先检查
        if (policy.banEnabled()) {
            String banKey = keyExtractor.extract(policy.banDimension(), request);
            if (banKey != null) {
                Optional<BanRecord> active = withTimeout(
                        () -> banService.checkActiveBan(banKey, policy, now),
                        policy.timeoutMillis(), "checkBan");
                if (active.isPresent()) {
                    BanRecord ban = active.get();
                    long retryAfter = Math.max(0L, ban.bannedUntil() - now);
                    return new Decision(request.requestId(), false, DecisionReason.BANNED,
                            "source is temporarily banned at level " + ban.level()
                                    + " until " + ban.bannedUntil(),
                            policy.version(), -1L, -1L, null, ban.level(), ban.bannedUntil(),
                            false, null, retryAfter, weightResolver.resolve(policy, request),
                            -1, null, -1L, -1L, false);
                }
            }
        }

        // 2) 加权 + 分层配额：所有层都能预留才放行；任一层不足则回滚此前预留并整体拒绝
        long weight = weightResolver.resolve(policy, request);
        // 记录本请求在每一层的预留结果，供失败时回滚
        java.util.List<Reservation> reservations = new java.util.ArrayList<>();
        try {
            for (QuotaTier tier : policy.tiers()) {
                String key = keyExtractor.extract(tier.dimension(), request);
                if (key == null) {
                    continue; // 该层维度对当前请求不可用（如匿名用户的 USER 层），跳过
                }
                long windowStart = now - (now % tier.windowMillis());
                long used = withTimeout(
                        () -> rateStore.tryConsume(policy.version(), tier.dimension(), key,
                                tier.windowMillis(), windowStart, weight, tier.limit()),
                        policy.timeoutMillis(), "consume:" + tier.name());
                if (used < 0) {
                    // 该层容量不足：回滚本请求先前层的预留，被拒请求不消耗任何配额
                    rollbackReservations(policy, reservations);
                    long currentUsed = withTimeout(
                            () -> currentUsage(tier, key, windowStart, policy.version()),
                            policy.timeoutMillis(), "readUsed:" + tier.name());
                    return rateLimited(request, policy, tier, key, windowStart,
                            currentUsed, weight, now);
                }
                reservations.add(new Reservation(tier, key, windowStart, weight));
            }
        } catch (StoreException e) {
            // 预留过程中存储故障：归还已预留量后交给上层统一降级，避免计数泄漏
            rollbackReservations(policy, reservations);
            throw e;
        }

        return new Decision(request.requestId(), true, DecisionReason.ALLOWED, "allowed",
                policy.version(), -1L, -1L, null, 0, -1L, false, null, -1L,
                weight, -1, null, -1L, -1L, false);
    }

    /** 一次成功预留的记录。 */
    private record Reservation(QuotaTier tier, String key, long windowStart, long weight) {
    }

    private long currentUsage(QuotaTier tier, String key, long windowStart, long version) {
        if (rateStore instanceof com.github.xingjianluo.zerotrustratelimiterjava.store.InMemoryRateLimitStateStore mem) {
            return mem.currentUsed(version, tier.dimension(), key, tier.windowMillis(), windowStart);
        }
        return rateStore.snapshot(version, tier.dimension(), key,
                        tier.windowMillis(), java.util.List.of(windowStart))
                .stream().findFirst()
                .map(com.github.xingjianluo.zerotrustratelimiterjava.store.RateLimitStateStore.CounterSnapshot::used)
                .orElse(0L);
    }

    /** 回滚本请求此前所有成功预留（尽力归还，存储异常由调用方上下文决定是否升级）。 */
    private void rollbackReservations(RateLimitPolicy policy, java.util.List<Reservation> reservations) {
        for (Reservation r : reservations) {
            try {
                rateStore.refund(policy.version(), r.tier().dimension(), r.key(),
                        r.tier().windowMillis(), r.windowStart(), r.weight());
            } catch (StoreException refundError) {
                // 回滚失败不掩盖原始结论；单实例内存存储下不会发生
                org.slf4j.LoggerFactory.getLogger(RateLimitService.class)
                        .warn("failed to refund reservation on {}", r.tier().name(), refundError);
            }
        }
    }

    /** 构造分层限流拒绝结论；若命中层属于封禁维度则驱动一次封禁升级。 */
    private Decision rateLimited(RequestContext request, RateLimitPolicy policy, QuotaTier tier,
                                 String key, long windowStart, long currentUsed, long weight, long now) {
        int banLevel = 0;
        long banUntil = -1L;
        long retryAfter = Math.max(0L, windowStart + tier.windowMillis() - now);
        long remaining = Math.max(0L, tier.limit() - currentUsed);
        if (policy.banEnabled() && tier.dimension() == policy.banDimension()) {
            long banWindowStart = now - (now % policy.windowMillis());
            BanRecord updated = withTimeout(
                    () -> banService.recordLimitedWindow(key, banWindowStart, policy, now),
                    policy.timeoutMillis(), "recordLimited");
            banLevel = updated.level();
            banUntil = updated.bannedUntil() > 0 ? updated.bannedUntil() : -1L;
        }
        return new Decision(request.requestId(), false, DecisionReason.RATE_LIMITED,
                "quota exceeded on " + tier.name() + " key=" + key
                        + " used=" + currentUsed + " limit=" + tier.limit()
                        + " remaining=" + remaining + " weight=" + weight
                        + " windowStart=" + windowStart,
                policy.version(), windowStart, currentUsed, tier.dimension(), banLevel, banUntil,
                false, null, retryAfter, weight,
                tier.index(), tier.name(), tier.limit(), remaining, false);
    }

    /**
     * 降级：同一请求只在此处产出唯一结论。FAIL_CLOSED 拒绝；FAIL_OPEN 放行但显式标记。
     */
    private Decision degrade(RequestContext request, RateLimitPolicy policy,
                             String cause, String message, long now) {
        long version = policy == null ? 0L : policy.version();
        FailMode mode = policy == null ? FailMode.FAIL_CLOSED : policy.failMode();
        long weight = 1L;
        if (policy != null) {
            weight = weightResolver.resolve(policy, request);
        }
        if (mode == FailMode.FAIL_OPEN) {
            return new Decision(request.requestId(), true, DecisionReason.FAIL_OPEN,
                    "degraded allow due to " + cause + ": " + message,
                    version, -1L, -1L, null, 0, -1L, true, cause, -1L,
                    weight, -1, null, -1L, -1L, false);
        }
        return new Decision(request.requestId(), false, DecisionReason.FAIL_CLOSED,
                "degraded deny due to " + cause + ": " + message,
                version, -1L, -1L, null, 0, -1L, true, cause, FAIL_CLOSED_RETRY_AFTER_MS,
                weight, -1, null, -1L, -1L, false);
    }

    /**
     * 热更新：由配置构建新版本快照并原子发布。版本边界确定——进行中的请求持有旧快照，
     * 之后的请求使用新快照；旧版本计数键不再被引用，历史判定不被改写。
     * 新权重/新分层只对之后新开始的窗口生效。
     *
     * @return 新版本号
     */
    /**
     * 启动时优先使用最近一次持久化的策略配置（分层/权重等在重启后延续）；
     * 无持久化文件时回退到传入的启动配置（application.properties / 命令行）。
     */
    public RateLimitProperties startupProperties(RateLimitProperties configured) {
        return policyStateStore.load().orElse(configured);
    }

    /**
     * 启动首发：重启后从持久化版本号延续（计数命名空间不被复用）；全新启动（无历史版本）
     * 时发布版本 1。幂等——重复首发不会递增版本。
     *
     * @return 发布的版本号
     */
    public long publishInitial(RateLimitProperties properties) {
        long version = versionSeq.get();
        long initial = version > 0 ? version : 1L;
        if (initial != version) {
            versionSeq.set(initial);
        }
        RateLimitPolicy policy = RateLimitPolicy.fromProperties(initial, properties);
        policyHolder.replace(policy);
        persistVersion(initial);
        policyStateStore.save(properties);
        return initial;
    }

    /**
     * 热更新：版本号 +1，原子发布新快照。版本边界确定——进行中的请求持有旧快照，
     * 之后的请求使用新快照；旧版本计数键不再被引用，历史判定不被改写。
     * 新权重/新分层只对之后新开始的窗口生效。
     *
     * @return 新版本号
     */
    public long reload(RateLimitProperties properties) {
        long newVersion = versionSeq.incrementAndGet();
        RateLimitPolicy policy = RateLimitPolicy.fromProperties(newVersion, properties);
        policyHolder.replace(policy);
        persistVersion(newVersion);
        policyStateStore.save(properties);
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
