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
 * <p>升级与恢复规则（确定、稳定）：
 * <ol>
 *   <li>每个限流窗口内该来源第一次超限时 {@code consecutiveLimitedWindows} +1（同窗口重复超限不重复计数）。</li>
 *   <li>连续超限窗口数达到 {@code banThreshold}：封禁级别 +1，按新级别取封禁时长并写入
 *       {@code bannedUntil}；封禁时长在此时固化进记录，之后策略调整不改变既有封禁。</li>
 *   <li>封禁到期：自动解除，记录进入观察期（保留级别）。观察期内再次超限 → 立即重新封禁且级别再 +1。</li>
 *   <li>观察期满且未再超限：级别与违规计数清零（需要 {@code resetAfterObserve=true}）。</li>
 *   <li>正常请求从不调用本服务，不会被误封；人工解除立即清零并持久化。</li>
 * </ol>
 * 所有状态变更通过 {@link BanStateStore#compute} 原子完成。
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
    public Optional<BanRecord> checkActiveBan(String key, RateLimitPolicy policy) {
        long now = clock.nowMillis();
        BanRecord r = store.compute(key, cur -> advance(cur, now, policy));
        return r.bannedUntil() > now ? Optional.of(r) : Optional.empty();
    }

    /**
     * 记录一次“窗口超限”事件。同一窗口内的重复超限不重复计数。
     *
     * <p>分层配额下由 {@link #recordLimitedWindow(String, long, long, RateLimitPolicy)}
     * 传入触发层自己的窗口长度，判断“相邻窗口”时以该层窗口为准；不同层的事件通过
     * 各自的窗口起点区分，短窗口层的重复超限不会错误打断长窗口层的连续计数。
     *
     * @return 更新后的记录（可能因此变为封禁状态）
     */
    public BanRecord recordLimitedWindow(String key, long windowStart, RateLimitPolicy policy) {
        return recordLimitedWindow(key, windowStart, policy.windowMillis(), policy);
    }

    /**
     * 记录一次“窗口超限”事件，并显式给出触发分层的窗口长度（毫秒）。
     */
    public BanRecord recordLimitedWindow(String key, long windowStart, long layerWindowMillis,
                                         RateLimitPolicy policy) {
        long now = clock.nowMillis();
        return store.compute(key, cur -> {
            BanRecord advanced = advance(cur, now, policy);
            // 同窗口重复超限不累计
            if (advanced.lastLimitedWindowStart() == windowStart) {
                return advanced;
            }
            // “连续超限窗口”要求窗口相邻：与上次超限窗口间隔超过该触发层的一个窗口，
            // 说明中间存在未超限窗口，违规链中断，计数从本次重新开始。
            boolean streakBroken = advanced.lastLimitedWindowStart() > 0
                    && windowStart - advanced.lastLimitedWindowStart() > layerWindowMillis;
            int consecutive = streakBroken ? 1 : advanced.consecutiveLimitedWindows() + 1;
            int level = advanced.level();
            long bannedUntil = 0L;
            long duration = 0L;
            if (consecutive >= policy.banThreshold()) {
                level = level + 1;
                duration = durationForLevel(level, policy);
                bannedUntil = now + duration;
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
                    now);
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

    /** 只读查询单个来源的当前记录（不推进到期/观察期状态，供只读视图使用）。 */
    public Optional<BanRecord> findRecord(String key) {
        return store.find(key);
    }

    /** 列出全部来源状态视图。 */
    public List<BanView> listBans() {
        long now = clock.nowMillis();
        return store.findAll().stream()
                .map(r -> BanView.from(r, now))
                .toList();
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
