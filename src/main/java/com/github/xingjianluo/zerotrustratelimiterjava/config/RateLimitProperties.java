package com.github.xingjianluo.zerotrustratelimiterjava.config;

import com.github.xingjianluo.zerotrustratelimiterjava.model.FailMode;
import com.github.xingjianluo.zerotrustratelimiterjava.model.LimitDimension;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * 限流/封禁/降级配置（前缀 {@code ratelimit}）。
 *
 * <p>该类同时作为管理端热更新接口的请求体：提交整份配置后原子替换生效。
 */
@ConfigurationProperties(prefix = "ratelimit")
public class RateLimitProperties {

    /** 限流窗口策略。 */
    private Policy policy = new Policy();
    /** 临时封禁策略。 */
    private Ban ban = new Ban();
    /** 存储与降级配置。 */
    private Storage storage = new Storage();
    /** 幂等重试判定缓存配置。 */
    private Replay replay = new Replay();

    public Replay getReplay() {
        return replay;
    }

    public void setReplay(Replay replay) {
        this.replay = replay;
    }

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
        /** 固定窗口长度。 */
        private Duration window = Duration.ofSeconds(1);
        /** 窗口内允许的最大请求数。 */
        private long limit = 10;
        /** 参与计数与拒绝判定的维度（按顺序判定，首个超限维度生效）。 */
        private List<LimitDimension> dimensions = List.of(LimitDimension.IP);
        /**
         * 分层配额（可选）。为空时由 {@code window/limit/dimensions} 派生出每个维度一层，
         * 与旧版行为完全兼容；非空时以本配置为准（每层独立窗口长度与上限）。
         */
        private List<Layer> layers;
        /**
         * 请求权重规则（可选）。命中规则的请求按 {@code weight} 消耗配额；
         * 未命中/未配置时按 1 消耗。热更新只对之后新开始的窗口生效。
         */
        private List<WeightRule> weights;

        public List<Layer> getLayers() {
            return layers;
        }

        public void setLayers(List<Layer> layers) {
            this.layers = layers;
        }

        public List<WeightRule> getWeights() {
            return weights;
        }

        public void setWeights(List<WeightRule> weights) {
            this.weights = weights;
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

        public List<LimitDimension> getDimensions() {
            return dimensions;
        }

        public void setDimensions(List<LimitDimension> dimensions) {
            this.dimensions = dimensions;
        }
    }

    /** 单条分层配额配置。 */
    public static class Layer {
        /** 分层名称（缺省由维度名派生），用于拒绝原因与只读视图标识。 */
        private String name;
        /** 限流维度。 */
        private LimitDimension dimension;
        /** 本层窗口长度（缺省取 policy.window）。 */
        private Duration window;
        /** 本层窗口内加权消耗总量上限。 */
        private long limit;
        /** 本层超限是否驱动临时封禁升级（缺省：维度等于封禁维度时为 true）。 */
        private Boolean drivesBan;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
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

        public Boolean getDrivesBan() {
            return drivesBan;
        }

        public void setDrivesBan(Boolean drivesBan) {
            this.drivesBan = drivesBan;
        }
    }

    /** 单条请求权重配置。 */
    public static class WeightRule {
        /** 路径匹配：精确路径优先，其次最长前缀（以 / 结尾的目录前缀）。 */
        private String path;
        /** 每次请求的配额消耗量（正整数）。 */
        private long weight = 1;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public long getWeight() {
            return weight;
        }

        public void setWeight(long weight) {
            this.weight = weight;
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
        /** 单次存储调用超过该耗时即视为超时并走降级。 */
        private Duration timeout = Duration.ofMillis(100);
        /** 降级模式。 */
        private FailMode failMode = FailMode.FAIL_CLOSED;
        /** 配额计数持久化文件（空表示仅内存，重启后窗口用量清零）。 */
        private String rateStateFile = "./data/rate-state.json";

        public String getBanStateFile() {
            return banStateFile;
        }

        public void setBanStateFile(String banStateFile) {
            this.banStateFile = banStateFile;
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

        public String getRateStateFile() {
            return rateStateFile;
        }

        public void setRateStateFile(String rateStateFile) {
            this.rateStateFile = rateStateFile;
        }
    }

    /** 幂等重试缓存：同一请求标识在 TTL 内重复到达直接返回首次结论，不再访问状态存储。 */
    public static class Replay {
        /** 是否启用幂等结论缓存。 */
        private boolean enabled = true;
        /** 结论缓存有效期（毫秒）。 */
        private Duration ttl = Duration.ofSeconds(5);
        /** 最多缓存条目数（超出后淘汰最旧条目）。 */
        private int capacity = 10_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }
    }
}
