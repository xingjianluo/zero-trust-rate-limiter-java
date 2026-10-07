package com.github.xingjianluo.zerotrustratelimiterjava.tests;

import com.github.xingjianluo.zerotrustratelimiterjava.audit.DefaultDecisionAuditLog;
import com.github.xingjianluo.zerotrustratelimiterjava.config.RateLimitProperties;
import com.github.xingjianluo.zerotrustratelimiterjava.core.BanService;
import com.github.xingjianluo.zerotrustratelimiterjava.core.KeyExtractor;
import com.github.xingjianluo.zerotrustratelimiterjava.core.PolicyHolder;
import com.github.xingjianluo.zerotrustratelimiterjava.core.QuotaViewService;
import com.github.xingjianluo.zerotrustratelimiterjava.core.RateLimitService;
import com.github.xingjianluo.zerotrustratelimiterjava.model.LimitDimension;
import com.github.xingjianluo.zerotrustratelimiterjava.model.QuotaLayerStatus;
import com.github.xingjianluo.zerotrustratelimiterjava.model.QuotaView;
import com.github.xingjianluo.zerotrustratelimiterjava.model.RequestContext;
import com.github.xingjianluo.zerotrustratelimiterjava.store.FileBanStateStore;
import com.github.xingjianluo.zerotrustratelimiterjava.store.InMemoryRateLimitStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 只读配额视图：按维度/来源返回各层用量、上限、剩余与封禁状态；数值与判定同源；
 * 只读调用不改变任何计数。
 */
class QuotaViewTest {

    private static final Logger log = LoggerFactory.getLogger(QuotaViewTest.class);
    private static final long T0 = 50_000L;

    private ManualClock clock;
    private InMemoryRateLimitStateStore rateStore;
    private FileBanStateStore banStore;
    private RateLimitService service;
    private QuotaViewService views;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        clock = new ManualClock(T0);
        rateStore = new InMemoryRateLimitStateStore(clock);
        banStore = new FileBanStateStore(clock, tempDir.resolve("ban.json").toString());
        BanService banService = new BanService(banStore, clock);
        service = new RateLimitService(rateStore, banService, new KeyExtractor(),
                new PolicyHolder(), clock, new DefaultDecisionAuditLog(clock));
        views = new QuotaViewService(service, banService, clock);
    }

    private RateLimitProperties props() {
        RateLimitProperties p = TestProps.create(2000, 10,
                com.github.xingjianluo.zerotrustratelimiterjava.model.FailMode.FAIL_CLOSED, 100);
        p.getBan().setEnabled(false);
        RateLimitProperties.Layer ip = new RateLimitProperties.Layer();
        ip.setName("ip-2s");
        ip.setDimension(LimitDimension.IP);
        ip.setWindow(Duration.ofMillis(2000));
        ip.setLimit(10);
        RateLimitProperties.Layer global = new RateLimitProperties.Layer();
        global.setName("global-10s");
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
    void viewReportsPerLayerUsageAndRemainingFromTheSameState() {
        service.reload(props());
        for (int i = 0; i < 4; i++) {
            service.decide(req("v-a-" + i, "4.4.4.4"));
        }
        for (int i = 0; i < 2; i++) {
            service.decide(req("v-b-" + i, "5.5.5.5"));
        }

        QuotaView ipView = views.view(LimitDimension.IP, "4.4.4.4");
        assertEquals(LimitDimension.IP, ipView.dimension());
        assertEquals("4.4.4.4", ipView.key());
        assertFalse(ipView.banned());
        assertEquals(1, ipView.layers().size(), "只包含 IP 维度的层");
        QuotaLayerStatus ipStatus = ipView.layers().get(0);
        assertEquals("ip-2s", ipStatus.name());
        assertEquals(T0 - T0 % 2000, ipStatus.windowStart());
        assertEquals(4, ipStatus.used());
        assertEquals(6, ipStatus.remaining());
        assertEquals(10, ipStatus.limit());

        // 全局层聚合两个来源共 6 个
        QuotaView globalView = views.view(LimitDimension.GLOBAL, "global");
        QuotaLayerStatus gStatus = globalView.layers().get(0);
        assertEquals(6, gStatus.used());
        assertEquals(94, gStatus.remaining());

        // 只读：连续查询用量不变
        views.view(LimitDimension.IP, "4.4.4.4");
        views.view(LimitDimension.IP, "4.4.4.4");
        assertEquals(4, views.view(LimitDimension.IP, "4.4.4.4").layers().get(0).used());
        log.info("quota view: {}", ipView);

        // 未知来源：used=0，remaining=limit
        QuotaView unknown = views.view(LimitDimension.IP, "9.0.9.9");
        assertEquals(0, unknown.layers().get(0).used());
        assertEquals(10, unknown.layers().get(0).remaining());
    }

    @Test
    void viewTracksWindowSwitchAndBanFlag() {
        RateLimitProperties p = props();
        p.getBan().setEnabled(true);
        p.getBan().setThresholdWindows(1);
        p.getBan().setLevelDuration(List.of(Duration.ofMinutes(30)));
        p.getBan().setObserveWindow(Duration.ofMinutes(60));
        service.reload(p);

        for (int i = 0; i < 10; i++) {
            service.decide(req("vb-" + i, "1.2.3.4"));
        }
        // 第 11 个超限，thresholdWindows=1 立即封禁
        service.decide(req("vb-over", "1.2.3.4"));
        QuotaView bannedView = views.view(LimitDimension.IP, "1.2.3.4");
        assertTrue(bannedView.banned());
        assertEquals(1, bannedView.banLevel());
        assertTrue(bannedView.bannedUntil() > clock.nowMillis());
        log.info("banned view: bannedUntil={}", bannedView.bannedUntil());

        // 切换到下一个 2s 窗口：用量归零；封禁仍在
        clock.advance(2000);
        QuotaView fresh = views.view(LimitDimension.IP, "1.2.3.4");
        assertEquals(0, fresh.layers().get(0).used());
        assertEquals(10, fresh.layers().get(0).remaining());
        assertTrue(fresh.banned());
    }
}
