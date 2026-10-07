package com.github.xingjianluo.zerotrustratelimiterjava.tests;

import com.github.xingjianluo.zerotrustratelimiterjava.audit.DefaultDecisionAuditLog;
import com.github.xingjianluo.zerotrustratelimiterjava.config.RateLimitProperties;
import com.github.xingjianluo.zerotrustratelimiterjava.core.BanService;
import com.github.xingjianluo.zerotrustratelimiterjava.core.DecisionCache;
import com.github.xingjianluo.zerotrustratelimiterjava.core.KeyExtractor;
import com.github.xingjianluo.zerotrustratelimiterjava.core.PolicyHolder;
import com.github.xingjianluo.zerotrustratelimiterjava.core.QuotaViewService;
import com.github.xingjianluo.zerotrustratelimiterjava.core.RateLimitService;
import com.github.xingjianluo.zerotrustratelimiterjava.core.WeightResolver;
import com.github.xingjianluo.zerotrustratelimiterjava.model.Decision;
import com.github.xingjianluo.zerotrustratelimiterjava.model.DecisionReason;
import com.github.xingjianluo.zerotrustratelimiterjava.model.FailMode;
import com.github.xingjianluo.zerotrustratelimiterjava.model.LimitDimension;
import com.github.xingjianluo.zerotrustratelimiterjava.model.QuotaView;
import com.github.xingjianluo.zerotrustratelimiterjava.model.RequestContext;
import com.github.xingjianluo.zerotrustratelimiterjava.model.TierUsage;
import com.github.xingjianluo.zerotrustratelimiterjava.store.FileBanStateStore;
import com.github.xingjianluo.zerotrustratelimiterjava.store.InMemoryRateLimitStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 只读配额视图：按来源/维度查询各层窗口起点、上限、已用量、剩余额度与封禁状态；
 * 视图与入口判定读同一份状态，且视图调用不改变任何计数。
 */
class QuotaViewTest {

    private static final Logger log = LoggerFactory.getLogger(QuotaViewTest.class);
    private static final long T0 = 80_000L;

    private ManualClock clock;
    private InMemoryRateLimitStateStore rateStore;
    private RateLimitService service;
    private QuotaViewService viewService;
    private BanService banService;

    @AfterEach
    void drainPersist() {
        rateStore.flush();
    }

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        clock = new ManualClock(T0);
        rateStore = new InMemoryRateLimitStateStore(clock, tempDir.resolve("quota.json").toString());
        FileBanStateStore banStore = new FileBanStateStore(clock, tempDir.resolve("ban.json").toString());
        banService = new BanService(banStore, clock);
        PolicyHolder holder = new PolicyHolder();
        var audit = new DefaultDecisionAuditLog(clock);
        service = new RateLimitService(rateStore, banService, new KeyExtractor(), new WeightResolver(),
                holder, new DecisionCache(clock), clock, audit);
        viewService = new QuotaViewService(rateStore, banService, holder, clock);

        RateLimitProperties p = TestProps.create(1000, 100, FailMode.FAIL_CLOSED, 100);
        List<RateLimitProperties.Tier> tiers = new ArrayList<>();
        tiers.add(new RateLimitProperties.Tier(LimitDimension.IP, Duration.ofSeconds(1), 3));
        tiers.add(new RateLimitProperties.Tier(LimitDimension.IP, Duration.ofSeconds(10), 7));
        tiers.add(new RateLimitProperties.Tier(LimitDimension.GLOBAL, Duration.ofSeconds(1), 100));
        p.getPolicy().setTiers(tiers);
        p.getBan().setEnabled(false);
        service.publishInitial(p);
    }

    private Decision req(String id, String ip) {
        return service.decide(new RequestContext(id, ip, null, "/api/hello", "GET"));
    }

    @Test
    void viewReportsPerTierUsageAndRemainingFromSameState() {
        String ip = "10.11.12.13";
        req("v-1", ip);
        req("v-2", ip);

        QuotaView view = viewService.view(LimitDimension.IP, ip);
        assertEquals(ip, view.key());
        assertEquals(LimitDimension.IP, view.dimension());
        assertEquals(2, view.tiers().size(), "应只返回 IP 维度的两层（短/长），不含 GLOBAL");

        TierUsage shortTier = view.tiers().get(0);
        TierUsage longTier = view.tiers().get(1);
        assertEquals(0, shortTier.tierIndex());
        assertEquals(1000L, shortTier.windowMillis());
        assertEquals(3L, shortTier.limit());
        assertEquals(2L, shortTier.used());
        assertEquals(1L, shortTier.remaining());
        assertEquals(1, longTier.tierIndex());
        assertEquals(10_000L, longTier.windowMillis());
        assertEquals(7L, longTier.limit());
        assertEquals(2L, longTier.used());
        assertEquals(5L, longTier.remaining());
        assertFalse(view.banned());
        log.info("view ip={} shortUsed={}/{} longUsed={}/{}", ip,
                shortTier.used(), shortTier.limit(), longTier.used(), longTier.limit());
    }

    @Test
    void viewDoesNotMutateCountersAndTracksWindowSwitch() {
        String ip = "10.11.12.99";
        req("m-1", ip);
        int entriesBefore = rateStore.size();
        viewService.view(LimitDimension.IP, ip);
        viewService.view(LimitDimension.IP, ip);
        assertEquals(entriesBefore, rateStore.size(), "只读视图不得新增/改变计数");

        // 短窗口切换后：短窗口用量归零，长窗口仍累计
        clock.advance(1000);
        req("m-2", ip);
        QuotaView view = viewService.view(LimitDimension.IP, ip);
        assertEquals(1L, view.tiers().get(0).used(), "新短窗口从 1 开始");
        assertEquals(2L, view.tiers().get(1).used(), "长窗口跨短窗口累计为 2");

        // 跨过 10s 长窗口后两层都重新开始
        clock.advance(10_000);
        req("m-3", ip);
        QuotaView reset = viewService.view(LimitDimension.IP, ip);
        assertEquals(1L, reset.tiers().get(0).used());
        assertEquals(1L, reset.tiers().get(1).used(), "长窗口切换后用量重置");
    }

    @Test
    void viewShowsUnknownSourceWithFullRemaining() {
        QuotaView view = viewService.view(LimitDimension.IP, "255.255.255.255");
        assertEquals(2, view.tiers().size());
        assertEquals(0L, view.tiers().get(0).used());
        assertEquals(3L, view.tiers().get(0).remaining());
        assertFalse(view.banned());
    }

    @Test
    void viewRefusesViaDecisionAndViewAgrees() {
        String ip = "10.44.44.44";
        req("r-1", ip);
        req("r-2", ip);
        req("r-3", ip); // 用满短窗口
        Decision rejected = req("r-4", ip);
        assertEquals(DecisionReason.RATE_LIMITED, rejected.reason());
        assertEquals(0, rejected.limitedByTier());

        // 视图与拒绝结论一致：短窗口用量 3、剩余 0
        QuotaView view = viewService.view(LimitDimension.IP, ip);
        TierUsage shortTier = view.tiers().get(0);
        assertEquals(3L, shortTier.used());
        assertEquals(0L, shortTier.remaining());
        assertEquals(rejected.tierLimit(), shortTier.limit());
        log.info("rejection/view agree: rejectedUsed={} viewUsed={} remaining={}",
                rejected.currentCount(), shortTier.used(), shortTier.remaining());
    }
}
