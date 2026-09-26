package com.github.xingjianluo.zerotrustratelimiterjava.tests;

import com.github.xingjianluo.zerotrustratelimiterjava.config.RateLimitProperties;
import com.github.xingjianluo.zerotrustratelimiterjava.model.FailMode;

import java.time.Duration;
import java.util.List;

/** 测试用配置工厂。 */
final class TestProps {

    private TestProps() {
    }

    static RateLimitProperties create(long windowMs, long limit, FailMode failMode, long timeoutMs) {
        RateLimitProperties props = new RateLimitProperties();
        props.getPolicy().setWindow(Duration.ofMillis(windowMs));
        props.getPolicy().setLimit(limit);
        props.getStorage().setFailMode(failMode);
        props.getStorage().setTimeout(Duration.ofMillis(timeoutMs));
        props.getStorage().setBanStateFile("");
        props.getBan().setEnabled(false); // 服务层基础测试默认关闭封禁，专项测试再打开
        props.getBan().setThresholdWindows(2);
        props.getBan().setLevelDuration(List.of(Duration.ofMinutes(1)));
        props.getBan().setObserveWindow(Duration.ofMinutes(10));
        return props;
    }
}
