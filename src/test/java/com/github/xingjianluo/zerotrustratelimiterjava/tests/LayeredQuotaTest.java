package com.github.xingjianluo.zerotrustratelimiterjava.tests;

import com.github.xingjianluo.zerotrustratelimiterjava.audit.DecisionAuditLog;
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
import com.github.xingjianluo.zerotrustratelimiterjava.store.InMemoryRateLimitStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分层配额：多层全部满足才放行、拒绝时明确给出层名/上限/已用/剩余；
 * 不同窗口长度交叉切换互不串计数；并发下各层计数准确；热更新产生独立版本命名空间。
 */
class LayeredQuotaTest {

    private static final Logger log = LoggerFactory.getLogger(LayeredQuotaTest.class);
    private static final long T0 = 10_000L;

    private ManualClock clock;
    private InMemoryRateLimitStateStore rateStore;
    private DecisionAuditLog auditLog;
    private RateLimitService service;
    private QuotaViewService viewService;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        clock = new ManualClock(T0);
        rateStore = new InMemoryRateLimitStateStore(clock);
        var banStore = new FileBanStateStore(clock, tempDir.resolve("ban.json").toString());
        auditLog = new DefaultDecisionAuditLog(clock);
        service = new RateLimitService(rateStore, new BanService(banStore, clock),
                new KeyExtractor(), new PolicyHolder(), clock, auditLog);
        viewService = new QuotaViewService(service, new BanService(banStore, clock), clock);
    }

    private RequestContext req(String id, String ip) {
        return new RequestContext(id, ip, null, "/api/hello", "GET");
    }

    /** 两层：IP 短窗 2s/4；GLOBAL 长窗 10s/6。 */
    private RateLimitProperties twoLayers(long shortWin, long shortLimit,
                                          long longWin, long longLimit) {
        RateLimitProperties p = TestProps.create(shortWin, shortLimit, FailMode.FAIL_CLOSED, 100);
        p.getBan().setEnabled(false);
        RateLimitProperties.Layer ipShort = new RateLimitProperties.Layer();
        ipShort.setName("ip-short");
        ipShort.setDimension(LimitDimension.IP);
        ipShort.setWindow(Duration.ofMillis(shortWin));
        ipShort.setLimit(shortLimit);
        ipShort.setDrivesBan(false);
        RateLimitProperties.Layer globalLong = new RateLimitProperties.Layer();
        globalLong.setName("global-long");
        globalLong.setDimension(LimitDimension.GLOBAL);
        globalLong.setWindow(Duration.ofMillis(longWin));
        globalLong.setLimit(longLimit);
        globalLong.setDrivesBan(false);
        p.getPolicy().setLayers(List.of(ipShort, globalLong));
        return p;
    }

    @Test
    void allLayersMustPassAndRejectionNamesTheBlockingLayer() {
        service.reload(twoLayers(2000, 4, 10_000, 6));

        // 前 4 次：两层均未超限，全部放行（IP=4, GLOBAL=4）
        List<Decision> first = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            first.add(service.decide(req("mix-" + i, "1.1.1.1")));
            assertTrue(first.get(i).allowed());
        }

        // 第 5 次：两层都计入（IP=5、GLOBAL=5），但按顺序 IP 短窗先超限
        Decision blockedByShort = service.decide(req("mix-over-1", "1.1.1.1"));
        assertFalse(blockedByShort.allowed());
        assertEquals(DecisionReason.RATE_LIMITED, blockedByShort.reason());
        assertEquals("ip-short", blockedByShort.layerName());
        assertEquals(LimitDimension.IP, blockedByShort.limitedBy());
        assertEquals(5, blockedByShort.currentCount());
        assertEquals(4, blockedByShort.layerLimit());
        assertEquals(0, blockedByShort.remaining());
        log.info("blocked by short layer: {}", blockedByShort.detail());

        // 换一个 IP：IP 短窗是新键（用量 1，不超限），但 GLOBAL 已 5，第 2 个请求使全局到 7 被长窗层拦下
        Decision g1 = service.decide(req("mix-other-1", "2.2.2.2"));
        assertTrue(g1.allowed());
        Decision g2 = service.decide(req("mix-other-2", "2.2.2.2"));
        assertFalse(g2.allowed());
        assertEquals("global-long", g2.layerName());
        assertEquals(LimitDimension.GLOBAL, g2.limitedBy());
        assertEquals(7, g2.currentCount());
        assertEquals(6, g2.layerLimit());
        assertEquals(0, g2.remaining());
        log.info("blocked by long layer: {}", g2.detail());
    }

    @Test
    void crossingWindowLengthsAreCountedIndependently() {
        service.reload(twoLayers(2000, 3, 10_000, 100));

        // 短窗 1（T0 对齐到 10000）：用满 3 个，第 4 个被短窗拦
        for (int i = 0; i < 3; i++) {
            assertTrue(service.decide(req("c1-" + i, "9.9.9.9")).allowed());
        }
        Decision over = service.decide(req("c1-over", "9.9.9.9"));
        assertEquals("ip-short", over.layerName());

        // 推进 2s：短窗切换（新键，用量归 0）；长窗未切换，但这里长窗 100 不会拦
        clock.advance(2000);
        for (int i = 0; i < 3; i++) {
            Decision d = service.decide(req("c2-" + i, "9.9.9.9"));
            assertTrue(d.allowed(), "短窗切换后应重新有 3 个额度");
        }
        // 视图确认：当前短窗窗口起点与用量正确，且长窗累计了跨短窗的全部用量
        QuotaView view = viewService.view(LimitDimension.IP, "9.9.9.9");
        var shortStatus = view.layers().stream()
                .filter(l -> l.name().equals("ip-short")).findFirst().orElseThrow();
        assertEquals(T0 + 2000, shortStatus.windowStart());
        assertEquals(3, shortStatus.used());
        assertEquals(0, shortStatus.remaining());

        QuotaView globalView = viewService.view(LimitDimension.GLOBAL, "global");
        var longStatus = globalView.layers().stream()
                .filter(l -> l.name().equals("global-long")).findFirst().orElseThrow();
        assertEquals(7, longStatus.used(), "长窗跨短窗累计：3+1(被拒也计入消耗)+3");
    }

    @Test
    void concurrentLayeredConsumptionCountsExactly() throws Exception {
        service.reload(twoLayers(10_000, 40, 10_000, 60));
        int threads = 16;
        int perThread = 5; // 80 个请求；IP 上限 40，全局上限 60
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger deniedByIp = new AtomicInteger();
        AtomicInteger deniedByGlobal = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            final int idx = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        Decision d = service.decide(req("cc-" + idx + "-" + i, "7.7.7.7"));
                        if (d.allowed()) {
                            allowed.incrementAndGet();
                        } else if ("ip-short".equals(d.layerName())) {
                            deniedByIp.incrementAndGet();
                        } else if ("global-long".equals(d.layerName())) {
                            deniedByGlobal.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();
        log.info("layered concurrency allowed={} deniedByIp={} deniedByGlobal={}",
                allowed.get(), deniedByIp.get(), deniedByGlobal.get());
        // 放行要求两层都满足：前 40 个两层都满足；第 41~60 个 IP 超限被拒（全局仍计入）；
        // 第 61~80 个 IP 与全局都超限，按顺序仍记为 IP 层拦下
        assertEquals(40, allowed.get(), "受更严格的 IP 层约束，恰好放行 40");
        assertEquals(40, deniedByIp.get() + deniedByGlobal.get());
        // 视图与判定同源：用量确定
        QuotaView view = viewService.view(LimitDimension.IP, "7.7.7.7");
        assertEquals(80, view.layers().stream()
                .filter(l -> l.name().equals("ip-short")).findFirst().orElseThrow().used(),
                "IP 层对所有 80 个请求独立计数");
        QuotaView gv = viewService.view(LimitDimension.GLOBAL, "global");
        assertEquals(80, gv.layers().stream()
                .filter(l -> l.name().equals("global-long")).findFirst().orElseThrow().used(),
                "全局层同样对全部请求计数");
    }

    @Test
    void hotReloadKeepsOldLayerCountersAndNewVersionStartsFresh() {
        service.reload(twoLayers(2000, 2, 10_000, 100));
        assertEquals(1, service.currentVersion());
        for (int i = 0; i < 2; i++) {
            assertTrue(service.decide(req("h-1-" + i, "5.5.5.5")).allowed());
        }
        assertFalse(service.decide(req("h-1-over", "5.5.5.5")).allowed());

        long v2 = service.reload(twoLayers(2000, 5, 10_000, 100));
        assertEquals(2, v2);
        // 即使在同一时刻，新版本计数命名空间从 0 开始：5 个全部放行
        for (int i = 0; i < 5; i++) {
            Decision d = service.decide(req("h-2-" + i, "5.5.5.5"));
            assertTrue(d.allowed());
            assertEquals(2, d.policyVersion());
        }
        Decision sixth = service.decide(req("h-2-over", "5.5.5.5"));
        assertFalse(sixth.allowed());
        assertEquals("ip-short", sixth.layerName());
        assertEquals(6, sixth.currentCount());
    }
}
