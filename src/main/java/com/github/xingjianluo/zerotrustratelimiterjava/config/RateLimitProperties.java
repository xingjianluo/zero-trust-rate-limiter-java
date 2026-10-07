package com.github.xingjianluo.zerotrustratelimiterjava.config;

import com.github.xingjianluo.zerotrustratelimiterjava.model.FailMode;
import com.github.xingjianluo.zerotrustratelimiterjava.model.LimitDimension;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 限流/封禁/降级配置（前缀 {@code ratelimit}）。
 *
 * <p>该类同时作为管理端热更新接口的请求体：提交整份配置后原子替换生效。
 */
@ConfigurationProperties(prefix = "ratelimit")
public class RateLimitProperties {

    /** 限流策略。 */
    private Policy policy = new Policy();
    /** 临时封禁策略。 */
    private Ban ban = new Ban();
    /** 存储与降级配置。 */
    private Storage storage = new Storage();

    public Policy getPolicy() {
        return policy;
    }

    public void setPolicy(Policy policy) {
        this.policy = policy;
    }

    public Ban getBan() {
        return ban;
    }

    public void setBan(Ban ban) {
        this.ban = ban;
    }

    public Storage getStorage() {
        return storage;
    }

    public void setStorage(Storage storage) {
        this.storage = storage;
    }

    /** 限流窗口策略。 */
    public static class Policy {
        /** 固定窗口长度（未配置 tiers 分层时的兼容字段）。 */
        private Duration window = Duration.ofSeconds(1);
        /** 窗口内允许的最大请求数（未配置 tiers 分层时的兼容字段）。 */
        private long limit = 10;
        /** 参与计数与拒绝判定的维度（按顺序判定，首个超限维度生效；未配置 tiers 时使用）。 */
        private List<LimitDimension> dimensions = List.of(LimitDimension.IP);
        /**
         * 分层配额：同一请求同时受每一层约束，全部满足才放行。
         * 为空时退化为由 window/limit/dimensions 生成的单层配额（旧行为）。
         */
        private List<Tier> tiers = List.of();
        /**
         * 请求权重配置：按路径前缀映射到本次请求消耗的配额量。
         * 键为路径前缀（按配置顺序最长前缀匹配，例如 {@code /api/export}），值为正整数权重。
         * 未匹配到任何前缀的请求权重为 1，与旧行为一致。权重调整只对之后新开始的窗口生效。
         */
        private Map<String, Long> weights = Map.of();
        /**
         * 客户端重试幂等窗口：同一请求标识在该时长内重复到达时直接复用首次结论，
         * 不再访问后端状态存储。
         */
        private Duration idempotentTtl = Duration.ofSeconds(30);

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }

        public long getLimit() {
            return limit;
        }

        public void setLimit(long limit) {
            this.limit = limit;
        }

        public List<LimitDimension> getDimensions() {
            return dimensions;
        }

        public void setDimensions(List<LimitDimension> dimensions) {
            this.dimensions = dimensions;
        }

        public List<Tier> getTiers() {
            return tiers;
        }

        public void setTiers(List<Tier> tiers) {
            this.tiers = tiers;
        }

        public Map<String, Long> getWeights() {
            return weights;
        }

        public void setWeights(Map<String, Long> weights) {
            this.weights = weights;
        }

        public Duration getIdempotentTtl() {
            return idempotentTtl;
        }

        public void setIdempotentTtl(Duration idempotentTtl) {
            this.idempotentTtl = idempotentTtl;
        }
    }

    /**
     * 一层配额约束：对指定维度，在 {@code window} 长度的固定窗口内累计消耗量不得超过 {@code limit}。
     * 一个请求同时受多层约束（如来源短窗口、来源长窗口、全局总量），全部满足才放行。
     */
    public static class Tier {
        /** 该层约束的维度。 */
        private LimitDimension dimension = LimitDimension.IP;
        /** 该层窗口长度。 */
        private Duration window = Duration.ofSeconds(1);
        /** 该层窗口内允许的最大累计消耗量（权重累加，非请求条数）。 */
        private long limit = 10;

        public Tier() {
        }

        public Tier(LimitDimension dimension, Duration window, long limit) {
            this.dimension = dimension;
            this.window = window;
            this.limit = limit;
        }

        public LimitDimension getDimension() {
            return dimension;
        }

        public void setDimension(LimitDimension dimension) {
            this.dimension = dimension;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }

        public long getLimit() {
            return limit;
        }

        public void setLimit(long limit) {
            this.limit = limit;
        }
    }

    /** 临时封禁升级/解除策略。 */
    public static class Ban {
        /** 是否启用临时封禁。 */
        private boolean enabled = true;
        /** 连续多少个窗口超限后升级为封禁（第 1 级）。 */
        private int thresholdWindows = 3;
        /** 各级封禁时长，按级别从 1 开始取；超出数组长度时取最后一个值。 */
        private List<Duration> levelDuration = List.of(
                Duration.ofMinutes(1),
                Duration.ofMinutes(5),
                Duration.ofMinutes(30));
        /** 解除封禁后若在观察期内再次超限，封禁级别 +1；观察期长度。 */
        private Duration observeWindow = Duration.ofMinutes(10);
        /** 未再超限的观察期满后，级别与违规计数重置为 0。 */
        private boolean resetAfterObserve = true;
        /** 封禁判定维度（按此维度的超限记录升级/封禁）。 */
        private LimitDimension dimension = LimitDimension.IP;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getThresholdWindows() {
            return thresholdWindows;
        }

        public void setThresholdWindows(int thresholdWindows) {
            this.thresholdWindows = thresholdWindows;
        }

        public List<Duration> getLevelDuration() {
            return levelDuration;
        }

        public void setLevelDuration(List<Duration> levelDuration) {
            this.levelDuration = levelDuration;
        }

        public Duration getObserveWindow() {
            return observeWindow;
        }

        public void setObserveWindow(Duration observeWindow) {
            this.observeWindow = observeWindow;
        }

        public boolean isResetAfterObserve() {
            return resetAfterObserve;
        }

        public void setResetAfterObserve(boolean resetAfterObserve) {
            this.resetAfterObserve = resetAfterObserve;
        }

        public LimitDimension getDimension() {
            return dimension;
        }

        public void setDimension(LimitDimension dimension) {
            this.dimension = dimension;
        }
    }

    /** 存储位置与降级阈值配置。 */
    public static class Storage {
        /** 封禁状态持久化文件（空表示仅内存）。 */
        private String banStateFile = "";
        /** 配额计数持久化文件（空表示仅内存；重启后窗口用量清零）。 */
        private String quotaStateFile = "";
        /** 单次存储调用超过该耗时即视为超时并走降级。 */
        private Duration timeout = Duration.ofMillis(100);
        /** 降级模式。 */
        private FailMode failMode = FailMode.FAIL_CLOSED;

        public String getBanStateFile() {
            return banStateFile;
        }

        public void setBanStateFile(String banStateFile) {
            this.banStateFile = banStateFile;
        }

        public String getQuotaStateFile() {
            return quotaStateFile;
        }

        public void setQuotaStateFile(String quotaStateFile) {
            this.quotaStateFile = quotaStateFile;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public FailMode getFailMode() {
            return failMode;
        }

        public void setFailMode(FailMode failMode) {
            this.failMode = failMode;
        }
    }
}
