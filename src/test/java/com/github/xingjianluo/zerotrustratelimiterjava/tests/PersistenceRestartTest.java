package com.github.xingjianluo.zerotrustratelimiterjava.tests;

import com.github.xingjianluo.zerotrustratelimiterjava.audit.DefaultDecisionAuditLog;
import com.github.xingjianluo.zerotrustratelimiterjava.config.RateLimitProperties;
import com.github.xingjianluo.zerotrustratelimiterjava.core.BanService;
import com.github.xingjianluo.zerotrustratelimiterjava.core.KeyExtractor;
import com.github.xingjianluo.zerotrustratelimiterjava.core.PolicyHolder;
import com.github.xingjianluo.zerotrustratelimiterjava.core.QuotaViewService;
import com.github.xingjianluo.zerotrustratelimiterjava.core.RateLimitService;
import com.github.xingjianluo.zerotrustratelimiterjava.model.Decision;
import com.github.xingjianluo.zerotrustratelimiterjava.model.DecisionReason;
import com.github.xingjianluo.zerotrustratelimiterjava.model.FailMode;
import com.github.xingjianluo.zerotrustratelimiterjava.model.LimitDimension;
import com.github.xingjianluo.zerotrustratelimiterjava.model.QuotaView;
import com.github.xingjianluo.zerotrustratelimiterjava.model.RequestContext;
import com.github.xingjianluo.zerotrustratelimiterjava.store.FileBanStateStore;
import com.github.xingjianluo.zerotrustratelimiterjava.store.FileRateLimitStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 重启延续：使用文件持久化的计数与封禁存储构造一套服务，制造用量/封禁/人工解除后，
 * 用相同文件“重建”整套服务（模拟进程重启），断言当前窗口用量、封禁状态与人工解除结果延续。
 */
class PersistenceRestartTest {

    private static final Logger log = LoggerFactory.getLogger(PersistenceRestartTest.class);

    private record Stack(ManualClock clock,
                         FileRateLimitStateStore rateStore,
                         FileBanStateStore banStore,
                         RateLimitService service,
                         QuotaViewService views) {
    }

    private Stack build(ManualClock clock, Path dir) {
        FileRateLimitStateStore rateStore =
                new FileRateLimitStateStore(clock, dir.resolve("rate.json").toString());
        FileBanStateStore banStore =
                new FileBanStateStore(clock, dir.resolve("ban.json").toString());
        BanService banService = new BanService(banStore, clock);
        RateLimitService service = new RateLimitService(rateStore, banService,
                new KeyExtractor(), new PolicyHolder(), clock, new DefaultDecisionAuditLog(clock));
        QuotaViewService views = new QuotaViewService(service, banService, clock);
        return new Stack(clock, rateStore, banStore, service, views);
    }

    private RateLimitProperties layeredProps() {
        RateLimitProperties p = TestProps.create(1000, 10, FailMode.FAIL_CLOSED, 100);
        p.getBan().setEnabled(false);
        RateLimitProperties.Layer ip = new RateLimitProperties.Layer();
        ip.setName("ip-short");
        ip.setDimension(LimitDimension.IP);
        ip.setWindow(Duration.ofMillis(1000));
        ip.setLimit(10);
        RateLimitProperties.Layer global = new RateLimitProperties.Layer();
        global.setName("global-long");
        global.setDimension(LimitDimension.GLOBAL);
        global.setWindow(Duration.ofMillis(10_000));
        global.setLimit(100);
        p.getPolicy().setLayers(List.of(ip, global));
        return p;
    }

    private RequestContext req(String id, String ip) {
        return new RequestContext(id, ip, null, "/api/hello", "GET");
    }

    @Test
    void quotaUsageSurvivesRestartWithinSameWindow(@TempDir Path dir) {
        ManualClock clock = new ManualClock(1_000_000L);
        Stack first = build(clock, dir);
        first.service().reload(layeredProps());

        for (int i = 0; i < 7; i++) {
            assertTrue(first.service().decide(req("pre-" + i, "8.8.8.8")).allowed());
        }
        QuotaView before = first.views().view(LimitDimension.IP, "8.8.8.8");
        assertEquals(7, before.layers().get(0).used());

        // 窗口未结束时“重启”：加载持久化计数，用量从 7 继续
        Stack second = build(clock, dir);
        second.service().reload(layeredProps());
        QuotaView after = second.views().view(LimitDimension.IP, "8.8.8.8");
        assertEquals(7, after.layers().get(0).used(), "重启后当前窗口已用量必须延续");
        assertEquals(3, after.layers().get(0).remaining());

        // 再发 4 个：7+3 放满，第 4 个超限被拒
        for (int i = 0; i < 3; i++) {
            assertTrue(second.service().decide(req("post-" + i, "8.8.8.8")).allowed());
        }
        Decision over = second.service().decide(req("post-over", "8.8.8.8"));
        assertFalse(over.allowed());
        assertEquals(11, over.currentCount());
        log.info("quota continued across restart: {}", over.detail());
    }

    @Test
    void expiredWindowCountersAreDroppedOnRestart(@TempDir Path dir) {
        ManualClock clock = new ManualClock(1_000_000L);
        Stack first = build(clock, dir);
        first.service().reload(layeredProps());
        for (int i = 0; i < 5; i++) {
            first.service().decide(req("e-" + i, "8.8.4.4"));
        }

        clock.advance(12_000); // 两层窗口都已结束
        Stack second = build(clock, dir);
        second.service().reload(layeredProps());
        QuotaView view = second.views().view(LimitDimension.IP, "8.8.4.4");
        assertEquals(0, view.layers().get(0).used(), "已过窗口的用量不应延续");
        assertEquals(10, view.layers().get(0).remaining());
    }

    @Test
    void banStateAndManualUnbanSurviveRestart(@TempDir Path dir) {
        ManualClock clock = new ManualClock(2_000_000L);
        Stack first = build(clock, dir);
        RateLimitProperties props = TestProps.create(1000, 2, FailMode.FAIL_CLOSED, 100);
        props.getBan().setEnabled(true);
        props.getBan().setThresholdWindows(1); // 每个超限窗口即升级
        props.getBan().setLevelDuration(List.of(Duration.ofMinutes(30)));
        props.getBan().setObserveWindow(Duration.ofMinutes(60));
        first.service().reload(props);

        // 2 个放行 + 1 个超限 -> 触发 1 级封禁
        assertTrue(first.service().decide(req("b-1", "6.6.6.6")).allowed());
        assertTrue(first.service().decide(req("b-2", "6.6.6.6")).allowed());
        Decision banned = first.service().decide(req("b-3", "6.6.6.6"));
        assertEquals(DecisionReason.RATE_LIMITED, banned.reason());

        // 重启：封禁仍然有效，请求直接 BANNED，不会因重启变回未封禁
        Stack second = build(clock, dir);
        second.service().reload(props);
        QuotaView bannedView = second.views().view(LimitDimension.IP, "6.6.6.6");
        assertTrue(bannedView.banned(), "重启后封禁状态必须延续");
        assertEquals(1, bannedView.banLevel());
        Decision stillBanned = second.service().decide(req("b-after-restart", "6.6.6.6"));
        assertEquals(DecisionReason.BANNED, stillBanned.reason());

        // 人工解除后再次重启：保持已解除，请求恢复正常判定
        new BanService(second.banStore(), clock).unban("6.6.6.6");
        Stack third = build(clock, dir);
        third.service().reload(props);
        QuotaView unbannedView = third.views().view(LimitDimension.IP, "6.6.6.6");
        assertFalse(unbannedView.banned(), "人工解除在重启后必须保持");
        // 新窗口（推进 1s）
        clock.advance(1000);
        Decision normal = third.service().decide(req("b-normal-again", "6.6.6.6"));
        assertEquals(DecisionReason.ALLOWED, normal.reason());
        log.info("unban survived restart: decision={}", normal.reason());
    }
}
