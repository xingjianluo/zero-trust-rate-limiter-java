package com.github.xingjianluo.zerotrustratelimiterjava.core;

import com.github.xingjianluo.zerotrustratelimiterjava.model.BanRecord;
import com.github.xingjianluo.zerotrustratelimiterjava.model.BanView;
import com.github.xingjianluo.zerotrustratelimiterjava.store.BanStateStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 临时封禁领域服务：封禁检查、超限后的升级、到期/观察期解除、人工解除。
 *
 * <p>分层配额下，封禁只由“封禁维度对应层”的窗口超限事件驱动；连续窗口的相邻判断
 * 使用 {@link RateLimitPolicy#windowMillis()}（封禁维度层的窗口长度）。
 *
 * <p>升级与恢复规则（确定、稳定）：
 * <ol>
 *   <li>每个封禁窗口内该来源第一次超限时 {@code consecutiveLimitedWindows} +1（同窗口重复超限不重复计数）。</li>
 *   <li>连续超限窗口数达到 {@code banThreshold}：封禁级别 +1，按新级别取封禁时长并写入
 *       {@code bannedUntil}；封禁时长在此时固化进记录，之后策略调整不改变既有封禁。</li>
 *   <li>封禁到期：自动解除，记录进入观察期（保留级别）。观察期内再次超限 → 立即重新封禁且级别再 +1。</li>
 *   <li>观察期满且未再超限：级别与违规计数清零（需要 {@code resetAfterObserve=true}）。</li>
 *   <li>正常请求从不调用本服务，不会被误封；人工解除立即清零并持久化。</li>
 * </ol>
 * 所有状态变更通过 {@link BanStateStore#compute} 原子完成。
 *
 * <p>所有方法接受显式 {@code nowMillis}：由编排层在一次请求开始时取一次时间传入，
 * 保证同一次判定（含重试回放）时间基准一致、可测试。
 */
@Service
public class BanService {

    private final BanStateStore store;
    private final Clock clock;

    public BanService(BanStateStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * 检查来源当前是否被封禁；同时原子地完成“到期自动解除/观察期满重置”的状态推进。
     *
     * @return 封禁中的记录；未封禁（含刚被自动解除/重置）返回 empty
     */
    public Optional<BanRecord> checkActiveBan(String key, RateLimitPolicy policy, long nowMillis) {
        BanRecord r = store.compute(key, cur -> advance(cur, nowMillis, policy));
        return r.bannedUntil() > nowMillis ? Optional.of(r) : Optional.empty();
    }

    /**
     * 记录一次“封禁维度窗口超限”事件。同一窗口内的重复超限不重复计数。
     *
     * @return 更新后的记录（可能因此变为封禁状态）
     */
    public BanRecord recordLimitedWindow(String key, long windowStart, RateLimitPolicy policy, long nowMillis) {
        return store.compute(key, cur -> {
            BanRecord advanced = advance(cur, nowMillis, policy);
            // 同窗口重复超限不累计
            if (advanced.lastLimitedWindowStart() == windowStart) {
                return advanced;
            }
            // “连续超限窗口”要求窗口相邻：与上次超限窗口间隔超过一个封禁维度窗口，说明中间存在
            // 未超限窗口，违规链中断，计数从本次重新开始。
            long windowMillis = policy.windowMillis();
            boolean streakBroken = advanced.lastLimitedWindowStart() > 0
                    && windowStart - advanced.lastLimitedWindowStart() > windowMillis;
            int consecutive = streakBroken ? 1 : advanced.consecutiveLimitedWindows() + 1;
            int level = advanced.level();
            long bannedUntil = 0L;
            long duration = 0L;
            if (consecutive >= policy.banThreshold()) {
                level = level + 1;
                duration = durationForLevel(level, policy);
                bannedUntil = nowMillis + duration;
                consecutive = 0;
            }
            return new BanRecord(
                    key,
                    level,
                    bannedUntil,
                    duration,
                    consecutive,
                    windowStart,
                    advanced.releasedAt(),
                    bannedUntil > 0 ? policy.version() : advanced.policyVersionAtBan(),
                    nowMillis);
        });
    }

    /**
     * 人工解除：立即生效，清零级别、封禁到期与违规计数，并持久化空状态（重启后仍为已解除）。
     */
    public BanRecord unban(String key) {
        long now = clock.nowMillis();
        return store.compute(key, cur -> new BanRecord(
                key, 0, 0L, 0L, 0, 0L, now, 0L, now));
    }

    /** 列出全部来源状态视图。 */
    public List<BanView> listBans() {
        long now = clock.nowMillis();
        return store.findAll().stream()
                .map(r -> BanView.from(r, now))
                .toList();
    }

    /** 单个来源的当前记录（只读，供配额视图聚合封禁状态）。 */
    public Optional<BanRecord> find(String key) {
        return store.find(key);
    }

    /**
     * 推进时间相关状态：封禁到期 → 标记解除并进入观察期；观察期满 → 按配置重置。
     */
    private BanRecord advance(BanRecord cur, long now, RateLimitPolicy policy) {
        BanRecord r = cur;
        if (r.bannedUntil() > 0 && now >= r.bannedUntil()) {
            r = new BanRecord(r.key(), r.level(), 0L, 0L, r.consecutiveLimitedWindows(),
                    r.lastLimitedWindowStart(), r.bannedUntil(), r.policyVersionAtBan(), now);
        }
        if (r.bannedUntil() == 0 && r.level() > 0 && policy.banResetObserve()
                && r.releasedAt() > 0 && now - r.releasedAt() >= policy.banObserveMs()) {
            r = new BanRecord(r.key(), 0, 0L, 0L, 0, 0L, r.releasedAt(), 0L, now);
        }
        return r;
    }

    private long durationForLevel(int level, RateLimitPolicy policy) {
        List<Long> durations = policy.banDurations();
        int idx = Math.min(Math.max(level, 1), durations.size()) - 1;
        return durations.get(idx);
    }
}
