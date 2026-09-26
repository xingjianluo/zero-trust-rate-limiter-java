package com.github.xingjianluo.zerotrustratelimiterjava.core;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 持有当前生效的不可变策略快照。热更新以 CAS/原子替换方式发布，
 * 进行中的请求继续使用替换前拿到的快照，新请求使用新快照。
 */
public class PolicyHolder {

    private final AtomicReference<RateLimitPolicy> current = new AtomicReference<>();

    /**
     * @return 当前生效快照；尚未发布时返回 null（正常启动流程不会出现）
     */
    public RateLimitPolicy current() {
        return current.get();
    }

    /**
     * 原子发布新快照。新版本号必须严格大于旧版本。
     *
     * @throws IllegalArgumentException 版本号未递增
     */
    public void replace(RateLimitPolicy policy) {
        RateLimitPolicy old = current.get();
        if (old != null && policy.version() <= old.version()) {
            throw new IllegalArgumentException(
                    "policy version must increase: old=" + old.version() + ", new=" + policy.version());
        }
        current.set(policy);
    }
}
