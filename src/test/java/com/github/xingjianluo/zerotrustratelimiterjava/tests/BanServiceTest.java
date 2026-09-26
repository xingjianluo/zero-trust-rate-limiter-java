package com.github.xingjianluo.zerotrustratelimiterjava.tests;

import com.github.xingjianluo.zerotrustratelimiterjava.config.RateLimitProperties;
import com.github.xingjianluo.zerotrustratelimiterjava.core.BanService;
import com.github.xingjianluo.zerotrustratelimiterjava.core.RateLimitPolicy;
import com.github.xingjianluo.zerotrustratelimiterjava.model.BanRecord;
import com.github.xingjianluo.zerotrustratelimiterjava.store.FileBanStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 封禁升级与解除规则的确定性单元测试。
 *
 * <p>测试中的判定输入/结果通过审计日志体系打印（见服务层测试），
 * 这里直接断言状态机迁移结果。
 */
class BanServiceTest {

    private ManualClock clock;
    private FileBanStateStore store;
    private BanService banService;
    private RateLimitPolicy policy;

    // 规则：连续 2 个窗口超限 -> 封禁；级别时长 100ms / 500ms；观察期 1000ms
    private static final long T0 = 1_000_000L;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        clock = new ManualClock(T0);
        store = new FileBanStateStore(clock, tempDir.resolve("ban.json").toString());
        banService = new BanService(store, clock);
        RateLimitProperties props = new RateLimitProperties();
        props.getBan().setThresholdWindows(2);
        props.getBan().setLevelDuration(java.util.List.of(
                java.time.Duration.ofMillis(100),
                java.time.Duration.ofMillis(500)));
        props.getBan().setObserveWindow(java.time.Duration.ofMillis(1000));
        props.getBan().setResetAfterObserve(true);
        policy = RateLimitPolicy.fromProperties(1L, props);
    }

    @Test
    void consecutiveLimitedWindowsEscalateToBanLevel1() {
        banService.recordLimitedWindow("1.1.1.1", 1000L, policy);
        assertEquals(0, store.find("1.1.1.1").orElseThrow().level());

        BanRecord afterSecond = banService.recordLimitedWindow("1.1.1.1", 2000L, policy);
        assertEquals(1, afterSecond.level());
        assertEquals(T0 + 100, afterSecond.bannedUntil());
        assertEquals(100, afterSecond.banDurationMillis());
        assertTrue(banService.checkActiveBan("1.1.1.1", policy).isPresent());
    }

    @Test
    void repeatedOverLimitInSameWindowCountedOnce() {
        banService.recordLimitedWindow("1.1.1.1", 1000L, policy);
        banService.recordLimitedWindow("1.1.1.1", 1000L, policy); // 同窗口不重复计数
        BanRecord r = store.find("1.1.1.1").orElseThrow();
        assertEquals(1, r.consecutiveLimitedWindows());
        assertEquals(0, r.level());
    }

    @Test
    void banExpiresThenReoffendingInObserveWindowEscalatesLevel2() {
        banService.recordLimitedWindow("1.1.1.1", 1000L, policy);
        banService.recordLimitedWindow("1.1.1.1", 2000L, policy); // 1 级封禁
        assertEquals(1, banService.checkActiveBan("1.1.1.1", policy).orElseThrow().level());

        clock.advance(100); // 封禁到期
        assertTrue(banService.checkActiveBan("1.1.1.1", policy).isEmpty());

        // 观察期内再犯：连续两个窗口超限 -> 重新封禁且级别 2，时长取第二档
        banService.recordLimitedWindow("1.1.1.1", 3000L, policy);
        BanRecord reBanned = banService.recordLimitedWindow("1.1.1.1", 4000L, policy);
        assertEquals(2, reBanned.level());
        assertEquals(T0 + 100 + 500, reBanned.bannedUntil());
        assertEquals(500, reBanned.banDurationMillis());
    }

    @Test
    void cleanRecordAfterObserveWindowResetsLevel() {
        banService.recordLimitedWindow("1.1.1.1", 1000L, policy);
        banService.recordLimitedWindow("1.1.1.1", 2000L, policy); // 1 级
        clock.advance(100); // 到期
        banService.checkActiveBan("1.1.1.1", policy); // 触发解除，进入观察期

        clock.advance(1000); // 观察期满且未再犯
        Optional<BanRecord> active = banService.checkActiveBan("1.1.1.1", policy);
        assertTrue(active.isEmpty());
        BanRecord reset = store.find("1.1.1.1").orElseThrow();
        assertEquals(0, reset.level());
        assertEquals(0, reset.consecutiveLimitedWindows());
    }

    @Test
    void manualUnbanTakesEffectImmediatelyAndClearsState() {
        banService.recordLimitedWindow("1.1.1.1", 1000L, policy);
        banService.recordLimitedWindow("1.1.1.1", 2000L, policy);
        assertTrue(banService.checkActiveBan("1.1.1.1", policy).isPresent());

        BanRecord unbanned = banService.unban("1.1.1.1");
        assertEquals(0, unbanned.level());
        assertEquals(0, unbanned.bannedUntil());
        assertEquals(0, unbanned.consecutiveLimitedWindows());
        assertTrue(banService.checkActiveBan("1.1.1.1", policy).isEmpty());
    }

    @Test
    void normalTrafficIsNeverBanned() {
        // 只有 1 个超限窗口、然后窗口计数不连续 -> 永不升级
        banService.recordLimitedWindow("1.1.1.1", 1000L, policy);
        clock.advance(10_000);
        banService.recordLimitedWindow("1.1.1.1", 12_000L, policy);
        assertEquals(0, store.find("1.1.1.1").orElseThrow().level());
        assertTrue(banService.checkActiveBan("1.1.1.1", policy).isEmpty());
    }
}
