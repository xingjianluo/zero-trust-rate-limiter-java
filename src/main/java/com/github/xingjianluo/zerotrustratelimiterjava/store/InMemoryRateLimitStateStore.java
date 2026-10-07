package com.github.xingjianluo.zerotrustratelimiterjava.store;

import java.util.List;

/**
 * 基于内存的加权分层计数存储（进程重启即丢失）。
 *
 * <p>原子性与过期清理见 {@link AbstractRateLimitStateStore}；本类不做持久化，
 * {@link #persist(List)} 为空操作。保留测试用故障注入开关（继承自基类）。
 */
public class InMemoryRateLimitStateStore extends AbstractRateLimitStateStore {

    public InMemoryRateLimitStateStore() {
        super(null);
    }

    /** 测试构造：注入手动时钟，使窗口过期判断与判定时钟一致。 */
    public InMemoryRateLimitStateStore(com.github.xingjianluo.zerotrustratelimiterjava.core.Clock clock) {
        super(clock);
    }

    @Override
    protected void persist(List<WindowCounter> snapshot) {
        // 纯内存：无需写盘
    }
}
