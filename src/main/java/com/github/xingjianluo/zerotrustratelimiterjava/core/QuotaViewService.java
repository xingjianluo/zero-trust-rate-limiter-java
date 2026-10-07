package com.github.xingjianluo.zerotrustratelimiterjava.core;

import com.github.xingjianluo.zerotrustratelimiterjava.model.BanRecord;
import com.github.xingjianluo.zerotrustratelimiterjava.model.LimitDimension;
import com.github.xingjianluo.zerotrustratelimiterjava.model.QuotaLayer;
import com.github.xingjianluo.zerotrustratelimiterjava.model.QuotaLayerStatus;
import com.github.xingjianluo.zerotrustratelimiterjava.model.QuotaView;
import com.github.xingjianluo.zerotrustratelimiterjava.store.WindowCounter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 配额只读视图服务：按维度/来源查询各层当前窗口的用量、剩余额度与封禁状态。
 *
 * <p>视图直接读取 {@link RateLimitService} 判定所用的同一个计数存储与策略快照，
 * 不做任何写操作，因此视图数值与入口判定基于同一份状态。
 */
public class QuotaViewService {

    private final RateLimitService rateLimitService;
    private final BanService banService;
    private final Clock clock;

    public QuotaViewService(RateLimitService rateLimitService, BanService banService, Clock clock) {
        this.rateLimitService = rateLimitService;
        this.banService = banService;
        this.clock = clock;
    }

    /**
     * 查询指定维度键下各层配额的当前状态。
     *
     * @param dimension 维度
     * @param key       维度键（如具体 IP）
     */
    public QuotaView view(LimitDimension dimension, String key) {
        RateLimitPolicy policy = rateLimitService.currentPolicy();
        long now = clock.nowMillis();
        List<QuotaLayerStatus> statuses = new ArrayList<>();
        if (policy != null) {
            for (QuotaLayer layer : policy.layers()) {
                if (layer.dimension() != dimension) {
                    continue;
                }
                long windowStart = now - (now % layer.windowMillis());
                Optional<WindowCounter> counter = rateLimitService.stateStore()
                        .find(policy.version(), layer.name(), dimension, key, windowStart);
                long used = counter.map(WindowCounter::consumed).orElse(0L);
                long remaining = Math.max(0L, layer.limit() - used);
                statuses.add(new QuotaLayerStatus(
                        layer.name(), dimension, windowStart, layer.windowMillis(),
                        layer.limit(), used, remaining));
            }
        }

        boolean banned = false;
        long bannedUntil = 0L;
        int banLevel = 0;
        Optional<BanRecord> record = banService.findRecord(key);
        if (record.isPresent()) {
            BanRecord r = record.get();
            if (r.bannedUntil() > now) {
                banned = true;
                bannedUntil = r.bannedUntil();
                banLevel = r.level();
            }
        }
        return new QuotaView(dimension, key, banned, bannedUntil, banLevel, List.copyOf(statuses));
    }
}
