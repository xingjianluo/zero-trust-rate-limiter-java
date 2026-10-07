package com.github.xingjianluo.zerotrustratelimiterjava.core;

import com.github.xingjianluo.zerotrustratelimiterjava.model.BanRecord;
import com.github.xingjianluo.zerotrustratelimiterjava.model.LimitDimension;
import com.github.xingjianluo.zerotrustratelimiterjava.model.QuotaView;
import com.github.xingjianluo.zerotrustratelimiterjava.model.TierUsage;
import com.github.xingjianluo.zerotrustratelimiterjava.store.RateLimitStateStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 配额只读视图：按来源（维度 + 维度键）查出当前策略下相关各层的窗口起点、上限、已用量、
 * 剩余额度，以及是否处于封禁中。
 *
 * <p>数据来源与入口判定是同一份内存计数（{@link RateLimitStateStore#snapshot}）与同一份
 * 封禁记录（{@link BanService#find}），因此视图数值与实际判定一致，不会出现“判定已超限但
 * 视图显示还有额度”的脱节。视图不改变任何状态。
 */
@Service
public class QuotaViewService {

    private final RateLimitStateStore rateStore;
    private final BanService banService;
    private final PolicyHolder policyHolder;
    private final Clock clock;

    public QuotaViewService(RateLimitStateStore rateStore,
                            BanService banService,
                            PolicyHolder policyHolder,
                            Clock clock) {
        this.rateStore = rateStore;
        this.banService = banService;
        this.policyHolder = policyHolder;
        this.clock = clock;
    }

    /**
     * 查询单个来源在指定维度下的各层配额视图。
     *
     * @param dimension 维度（仅返回该维度相关的层）
     * @param key       维度键（如来源 IP）
     * @return 聚合视图；当前窗口无任何计数时各层 used=0、remaining=limit
     */
    public QuotaView view(LimitDimension dimension, String key) {
        RateLimitPolicy policy = policyHolder.current();
        long now = clock.nowMillis();

        List<QuotaTier> tiers = policy.tiers().stream()
                .filter(t -> t.dimension() == dimension)
                .toList();

        List<TierUsage> usages = new ArrayList<>();
        for (QuotaTier tier : tiers) {
            long windowStart = now - (now % tier.windowMillis());
            // 逐层按其窗口长度读取同源计数，短/长窗口互不串用
            long used = rateStore.snapshot(policy.version(), dimension, key,
                            tier.windowMillis(), List.of(windowStart)).stream()
                    .map(RateLimitStateStore.CounterSnapshot::used)
                    .findFirst().orElse(0L);
            long remaining = Math.max(0L, tier.limit() - used);
            usages.add(new TierUsage(tier.index(), tier.name(), tier.dimension(),
                    tier.windowMillis(), windowStart, tier.limit(), used, remaining));
        }

        boolean banned = false;
        int banLevel = 0;
        long banUntil = 0L;
        if (dimension == policy.banDimension()) {
            Optional<BanRecord> record = banService.find(key);
            if (record.isPresent() && record.get().bannedUntil() > now) {
                banned = true;
                banLevel = record.get().level();
                banUntil = record.get().bannedUntil();
            }
        }
        return new QuotaView(dimension, key, banned, banLevel, banUntil,
                policy.version(), List.copyOf(usages));
    }
}
