package com.github.xingjianluo.zerotrustratelimiterjava.tests;

import com.github.xingjianluo.zerotrustratelimiterjava.audit.DecisionAuditLog;
import com.github.xingjianluo.zerotrustratelimiterjava.audit.DefaultDecisionAuditLog;
import com.github.xingjianluo.zerotrustratelimiterjava.config.RateLimitProperties;
import com.github.xingjianluo.zerotrustratelimiterjava.core.BanService;
import com.github.xingjianluo.zerotrustratelimiterjava.core.DecisionCache;
import com.github.xingjianluo.zerotrustratelimiterjava.core.KeyExtractor;
import com.github.xingjianluo.zerotrustratelimiterjava.core.PolicyHolder;
import com.github.xingjianluo.zerotrustratelimiterjava.core.QuotaTier;
import com.github.xingjianluo.zerotrustratelimiterjava.core.RateLimitPolicy;
import com.github.xingjianluo.zerotrustratelimiterjava.core.RateLimitService;
import com.github.xingjianluo.zerotrustratelimiterjava.core.WeightResolver;
import com.github.xingjianluo.zerotrustratelimiterjava.model.Decision;
import com.github.xingjianluo.zerotrustratelimiterjava.model.DecisionReason;
import com.github.xingjianluo.zerotrustratelimiterjava.model.FailMode;
import com.github.xingjianluo.zerotrustratelimiterjava.model.LimitDimension;
import com.github.xingjianluo.zerotrustratelimiterjava.model.RequestContext;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分层配额：同一请求同时受多层约束（来源短窗口/来源长窗口/全局），只有全部满足才放行；
 * 拒绝时明确是哪一层拦下、该层上限、当前已用量与剩余额度；短/长窗口交叉切换与并发下
 * 各层计数都必须准确；策略热更新后各层不串计数。
 */
class TieredQuotaTest {

    private static final Logger log = LoggerFactory.getLogger(TieredQuotaTest.class);
    private static final long T0 = 40_000L;

    private ManualClock clock;
    private InMemoryRateLimitStateStore rateStore;
    private DecisionAuditLog auditLog;
    private RateLimitService service;

    @AfterEach
    void drainPersist() {
        rateStore.flush();
    }

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        clock = new ManualClock(T0);
        rateStore = new InMemoryRateLimitStateStore(clock, tempDir.resolve("quota.json").toString());
        FileBanStateStore banStore = new FileBanStateStore(clock, tempDir.resolve("ban.json").toString());
        auditLog = new DefaultDecisionAuditLog(clock);
        service = new RateLimitService(rateStore, new BanService(banStore, clock),
                new KeyExtractor(), new WeightResolver(), new PolicyHolder(), new DecisionCache(clock),
                clock, auditLog);
    }

    private RequestContext req(String id, String ip) {
        return new RequestContext(id, ip, null, "/api/hello", "GET");
    }

    /** 三层：IP 短窗口 1s 上限 3；IP 长窗口 10s 上限 5；GLOBAL 1s 上限 100。 */
    private RateLimitProperties threeTierProps() {
        RateLimitProperties p = TestProps.create(1000, 100, FailMode.FAIL_CLOSED, 100);
        List<RateLimitProperties.Tier> tiers = new ArrayList<>();
        tiers.add(new RateLimitProperties.Tier(LimitDimension.IP, Duration.ofSeconds(1), 3));
        tiers.add(new RateLimitProperties.Tier(LimitDimension.IP, Duration.ofSeconds(10), 5));
        tiers.add(new RateLimitProperties.Tier(LimitDimension.GLOBAL, Duration.ofSeconds(1), 100));
        p.getPolicy().setTiers(tiers);
        return p;
    }

    @Test
    void allTiersMustPassAndRejectionNamesTheTier() {
        service.reload(threeTierProps());

        // 第 1 个短窗口：3 放行，第 4 个被短窗口层 tier[0] 拦下
        List<Decision> w1 = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            w1.add(service.decide(req("w1-" + i, "10.1.1.1")));
        }
        assertEquals(3, w1.stream().filter(Decision::allowed).count());
        Decision shortLimited = w1.get(3);
        assertEquals(DecisionReason.RATE_LIMITED, shortLimited.reason());
        assertEquals(0, shortLimited.limitedByTier());
        assertEquals("tier[0]:IP:1000ms", shortLimited.tierName());
        assertEquals(3, shortLimited.tierLimit());
        assertEquals(3, shortLimited.currentCount(), "被拒不消耗，短窗口用量停在上限 3");
        assertEquals(0, shortLimited.tierRemaining());
        log.info("short-window tier reject tier={} used={} remaining={}",
                shortLimited.tierName(), shortLimited.currentCount(), shortLimited.tierRemaining());

        // 推进到第 2 个短窗口（仍在同一 10s 长窗口内）：短窗口恢复，但长窗口累计已达 5 -> 被 tier[1] 拦下
        clock.advance(1000);
        Decision longLimited = null;
        for (int i = 0; i < 3; i++) {
            Decision d = service.decide(req("w2-" + i, "10.1.1.1"));
            if (d.reason() == DecisionReason.RATE_LIMITED && longLimited == null) {
                longLimited = d;
            }
        }
        assertNotNull(longLimited, "长窗口累计超限后必须被 tier[1] 拦下");
        assertEquals(1, longLimited.limitedByTier());
        assertEquals(LimitDimension.IP, longLimited.limitedBy());
        assertEquals(5, longLimited.tierLimit());
        assertEquals(5, longLimited.currentCount(), "长窗口用量达到上限 5，被拒不消耗");
        assertEquals(0, longLimited.tierRemaining());
        log.info("long-window tier reject tier={} used={} limit={} remaining={}",
                longLimited.tierName(), longLimited.currentCount(),
                longLimited.tierLimit(), longLimited.tierRemaining());
    }

    @Test
    void globalTierBlocksAcrossSources() {
        service.reload(threeTierProps());
        // 全局 1s 上限 100：用不同来源把全局打满
        RateLimitProperties tight = TestProps.create(1000, 100, FailMode.FAIL_CLOSED, 100);
        List<RateLimitProperties.Tier> tiers = List.of(
                new RateLimitProperties.Tier(LimitDimension.IP, Duration.ofSeconds(1), 1000),
                new RateLimitProperties.Tier(LimitDimension.GLOBAL, Duration.ofSeconds(1), 10));
        tight.getPolicy().setTiers(new ArrayList<>(tiers));
        service.reload(tight);

        for (int i = 0; i < 10; i++) {
            assertTrue(service.decide(req("g-" + i, "10.2." + i + ".1")).allowed());
        }
        Decision blocked = service.decide(req("g-over", "10.2.99.9"));
        assertEquals(DecisionReason.RATE_LIMITED, blocked.reason());
        assertEquals(1, blocked.limitedByTier(), "应由 GLOBAL 层（tier[1]）拦下");
        assertEquals(LimitDimension.GLOBAL, blocked.limitedBy());
    }

    @Test
    void crossingWindowLengthsCountIndependently() {
        service.reload(threeTierProps());
        // 每个 1s 短窗口打 3 个（放满短窗口），推进 1s，共做两个短窗口 = 长窗口累计 6
        for (int i = 0; i < 3; i++) {
            assertTrue(service.decide(req("c-a-" + i, "10.3.3.3")).allowed());
        }
        clock.advance(1000);
        int allowedSecond = 0;
        Decision limitedSecond = null;
        for (int i = 0; i < 3; i++) {
            Decision d = service.decide(req("c-b-" + i, "10.3.3.3"));
            if (d.allowed()) {
                allowedSecond++;
            } else if (limitedSecond == null) {
                limitedSecond = d;
            }
        }
        // 长窗口上限 5：第二短窗口只能再放 2 个，第 3 个被长窗口层拒
        assertEquals(2, allowedSecond);
        assertNotNull(limitedSecond);
        assertEquals(1, limitedSecond.limitedByTier());

        // 跨过 10s 长窗口边界后，长窗口计数重置（短窗口也在新窗口）
        clock.advance(10_000);
        Decision afterLongReset = service.decide(req("c-reset", "10.3.3.3"));
        assertTrue(afterLongReset.allowed(), "长窗口切换后应恢复放行");
    }

    @Test
    void concurrentAcrossTiersCountsExactly() throws Exception {
        // 只用一层做精确并发断言：16 线程 × 10 请求，层上限 50
        RateLimitProperties p = TestProps.create(1000, 100, FailMode.FAIL_CLOSED, 100);
        p.getPolicy().setTiers(new ArrayList<>(List.of(
                new RateLimitProperties.Tier(LimitDimension.IP, Duration.ofSeconds(1), 50))));
        service.reload(p);

        int threads = 16;
        int perThread = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            final int idx = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        // 每个请求唯一标识，避免命中幂等缓存（本用例验证并发计数）
                        Decision d = service.decide(req("cc-" + idx + "-" + i, "10.4.4.4"));
                        if (d.allowed()) {
                            allowed.incrementAndGet();
                        } else if (d.reason() == DecisionReason.RATE_LIMITED) {
                            rejected.incrementAndGet();
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
        log.info("tiered concurrency allowed={} rejected={}", allowed.get(), rejected.get());
        assertEquals(50, allowed.get(), "分层计数并发下放行数必须恰好等于层上限");
        assertEquals(110, rejected.get());
    }

    @Test
    void snapshotTiersAreBuiltFromPolicy() {
        RateLimitProperties props = threeTierProps();
        RateLimitPolicy snapshot = RateLimitPolicy.fromProperties(7L, props);
        assertEquals(3, snapshot.tiers().size());
        assertEquals(0, snapshot.tiers().get(0).index());
        assertEquals(LimitDimension.IP, snapshot.tiers().get(0).dimension());
        assertEquals(1000L, snapshot.tiers().get(0).windowMillis());
        assertEquals(3L, snapshot.tiers().get(0).limit());
        assertEquals(1, snapshot.tiers().get(1).index());
        assertEquals(10_000L, snapshot.tiers().get(1).windowMillis());
        assertEquals(5L, snapshot.tiers().get(1).limit());
        assertEquals(LimitDimension.GLOBAL, snapshot.tiers().get(2).dimension());
        assertEquals(7L, snapshot.version());
        // 旧访问器：封禁维度层窗口取封禁维度（默认 IP）对应层的窗口
        assertEquals(1000L, snapshot.windowMillis());
    }

    @Test
    void hotReloadTiersStartFreshAndOldNamespaceKept() {
        // v1：IP 短窗口上限 3
        RateLimitProperties v1 = TestProps.create(1000, 3, FailMode.FAIL_CLOSED, 100);
        service.reload(v1);
        for (int i = 0; i < 3; i++) {
            assertTrue(service.decide(req("hot-1-" + i, "10.6.6.6")).allowed());
        }
        assertEquals(1, service.currentVersion());

        // 热更新为三层且放宽（短窗口上限 5、长窗口上限 50、全局 100），仍在同一物理窗口时间点
        RateLimitProperties v2 = threeTierProps();
        List<RateLimitProperties.Tier> relaxed = new ArrayList<>();
        relaxed.add(new RateLimitProperties.Tier(LimitDimension.IP, Duration.ofSeconds(1), 5));
        relaxed.add(new RateLimitProperties.Tier(LimitDimension.IP, Duration.ofSeconds(10), 50));
        relaxed.add(new RateLimitProperties.Tier(LimitDimension.GLOBAL, Duration.ofSeconds(1), 100));
        v2.getPolicy().setTiers(relaxed);
        assertEquals(2, service.reload(v2));

        // 新版本命名空间：短窗口从 0 开始，可再放 5 个（若沿用旧计数只剩 2 个）
        for (int i = 0; i < 5; i++) {
            Decision d = service.decide(req("hot-2-" + i, "10.6.6.6"));
            assertTrue(d.allowed(), "新版本短窗口应从 0 开始计数");
            assertEquals(2, d.policyVersion());
        }
        Decision sixth = service.decide(req("hot-2-6", "10.6.6.6"));
        assertEquals(DecisionReason.RATE_LIMITED, sixth.reason());
        assertEquals(0, sixth.limitedByTier());

        // 旧版本历史判定仍标记为版本 1，不被改写
        Decision v1Historical = auditLog.recent(200).stream()
                .map(e -> e.decision())
                .filter(d -> "hot-1-0".equals(d.requestId())).findFirst().orElseThrow();
        assertEquals(1, v1Historical.policyVersion());
    }
}
