package com.github.xingjianluo.zerotrustratelimiterjava.tests;

import com.github.xingjianluo.zerotrustratelimiterjava.audit.DecisionAuditLog;
import com.github.xingjianluo.zerotrustratelimiterjava.audit.DefaultDecisionAuditLog;
import com.github.xingjianluo.zerotrustratelimiterjava.config.RateLimitProperties;
import com.github.xingjianluo.zerotrustratelimiterjava.core.BanService;
import com.github.xingjianluo.zerotrustratelimiterjava.core.KeyExtractor;
import com.github.xingjianluo.zerotrustratelimiterjava.core.PolicyHolder;
import com.github.xingjianluo.zerotrustratelimiterjava.core.RateLimitService;
import com.github.xingjianluo.zerotrustratelimiterjava.model.Decision;
import com.github.xingjianluo.zerotrustratelimiterjava.model.DecisionReason;
import com.github.xingjianluo.zerotrustratelimiterjava.model.FailMode;
import com.github.xingjianluo.zerotrustratelimiterjava.model.RequestContext;
import com.github.xingjianluo.zerotrustratelimiterjava.store.FileBanStateStore;
import com.github.xingjianluo.zerotrustratelimiterjava.store.InMemoryRateLimitStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 核心判定服务测试：固定窗口切换、并发计数准确、热更新边界、降级原因与模式区分。
 *
 * <p>每次判定的请求标识、输入与最终结果由 {@link DefaultDecisionAuditLog} 打印到测试日志。
 */
class RateLimitServiceTest {

    private static final Logger log = LoggerFactory.getLogger(RateLimitServiceTest.class);

    private static final long T0 = 10_000L;
    private static final long WINDOW = 1000L;

    private ManualClock clock;
    private InMemoryRateLimitStateStore rateStore;
    private FileBanStateStore banStore;
    private DecisionAuditLog auditLog;
    private RateLimitService service;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        clock = new ManualClock(T0);
        rateStore = new InMemoryRateLimitStateStore();
        banStore = new FileBanStateStore(clock, tempDir.resolve("ban.json").toString());
        BanService banService = new BanService(banStore, clock);
        auditLog = new DefaultDecisionAuditLog(clock);
        service = new RateLimitService(rateStore, banService, new KeyExtractor(),
                new PolicyHolder(), clock, auditLog);
    }

    private RequestContext req(String id, String ip) {
        return new RequestContext(id, ip, null, "/api/hello", "GET");
    }

    private List<Decision> burst(String prefix, String ip, int n) {
        List<Decision> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            results.add(service.decide(req(prefix + "-" + i, ip)));
        }
        return results;
    }

    private long allowed(List<Decision> ds) {
        return ds.stream().filter(Decision::allowed).count();
    }

    private long rejected(List<Decision> ds, DecisionReason reason) {
        return ds.stream().filter(d -> d.reason() == reason).count();
    }

    @Test
    void fixedWindowAllowsExactlyLimitThenRejectsAndResetsOnSwitch() {
        service.reload(TestProps.create(WINDOW, 5, FailMode.FAIL_CLOSED, 100));

        List<Decision> first = burst("w1", "1.1.1.1", 8);
        assertEquals(5, allowed(first), "窗口内恰好放行 limit 个");
        assertEquals(3, rejected(first, DecisionReason.RATE_LIMITED));
        Decision limited = first.stream().filter(d -> d.reason() == DecisionReason.RATE_LIMITED)
                .findFirst().orElseThrow();
        assertEquals(T0 - T0 % WINDOW, limited.windowStart());
        assertEquals(6, limited.currentCount());

        // 切换窗口后计数重新开始
        clock.advance(WINDOW);
        List<Decision> second = burst("w2", "1.1.1.1", 5);
        assertEquals(5, allowed(second));
        assertEquals(0, rejected(second, DecisionReason.RATE_LIMITED));
        log.info("window switch verified: audit entries={}", auditLog.recent(3).size());
    }

    @Test
    void concurrentRequestsInOneWindowCountExactly() throws Exception {
        service.reload(TestProps.create(WINDOW, 50, FailMode.FAIL_CLOSED, 100));
        int threads = 16;
        int perThread = 10; // 共 160 个请求，limit 50
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowedCnt = new AtomicInteger();
        AtomicInteger rejectedCnt = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            final int idx = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        Decision d = service.decide(req("c-" + idx + "-" + i, "2.2.2.2"));
                        if (d.allowed()) {
                            allowedCnt.incrementAndGet();
                        } else if (d.reason() == DecisionReason.RATE_LIMITED) {
                            rejectedCnt.incrementAndGet();
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
        log.info("concurrency result allowed={} rejected={}", allowedCnt.get(), rejectedCnt.get());
        assertEquals(50, allowedCnt.get(), "并发下放行数必须恰好等于阈值");
        assertEquals(110, rejectedCnt.get(), "并发下拒绝数必须确定");
    }

    @Test
    void hotReloadOnlyAffectsNewWindowAndBumpsVersionAtomically() {
        service.reload(TestProps.create(WINDOW, 5, FailMode.FAIL_CLOSED, 100));
        assertEquals(1, service.currentVersion());
        burst("v1", "3.3.3.3", 5); // 用满旧窗口 5 个

        // 同一窗口内热更新：旧窗口计数不动，版本立即变为 2
        RateLimitProperties relaxed = TestProps.create(WINDOW, 100, FailMode.FAIL_CLOSED, 100);
        long v2 = service.reload(relaxed);
        assertEquals(2, v2);
        // 新窗口使用新版本：100 个请求全放行；若旧计数被沿用则会被误拒
        clock.advance(WINDOW);
        List<Decision> after = burst("v2", "3.3.3.3", 100);
        assertEquals(100, allowed(after));
        assertTrue(after.stream().allMatch(d -> d.policyVersion() == 2));

        // 旧策略历史判定保持为版本 1，不被改写
        long v1Historical = auditLog.recent(200).stream()
                .filter(e -> e.request().requestId().startsWith("v1-"))
                .findFirst().orElseThrow().decision().policyVersion();
        assertEquals(1, v1Historical);
    }

    @Test
    void unavailableStoreFailsClosedWithDistinguishableCause() {
        service.reload(TestProps.create(WINDOW, 5, FailMode.FAIL_CLOSED, 100));
        rateStore.setFaultUnavailable(true);
        Decision d = service.decide(req("fc-1", "4.4.4.4"));
        assertFalse(d.allowed());
        assertEquals(DecisionReason.FAIL_CLOSED, d.reason());
        assertTrue(d.degraded());
        assertEquals("STORE_UNAVAILABLE", d.degradedCause());
        rateStore.setFaultUnavailable(false);
    }

    @Test
    void slowStoreFailsClosedViaTimeoutCause() {
        service.reload(TestProps.create(WINDOW, 5, FailMode.FAIL_CLOSED, 50));
        rateStore.setFaultDelayMillis(150); // 超过 50ms 阈值
        Decision d = service.decide(req("to-1", "5.5.5.5"));
        assertFalse(d.allowed());
        assertEquals(DecisionReason.FAIL_CLOSED, d.reason());
        assertEquals("STORE_TIMEOUT", d.degradedCause(), "超时与不可用原因必须可区分");
        rateStore.setFaultDelayMillis(0);
    }

    @Test
    void failOpenAllowsButMarksDegraded() {
        service.reload(TestProps.create(WINDOW, 5, FailMode.FAIL_OPEN, 50));
        rateStore.setFaultDelayMillis(150);
        Decision d = service.decide(req("fo-1", "6.6.6.6"));
        assertTrue(d.allowed(), "失败开放应放行");
        assertEquals(DecisionReason.FAIL_OPEN, d.reason());
        assertTrue(d.degraded());
        assertEquals("STORE_TIMEOUT", d.degradedCause());
        rateStore.setFaultDelayMillis(0);
    }

    @Test
    void decisionNeverContradictory() {
        service.reload(TestProps.create(WINDOW, 3, FailMode.FAIL_CLOSED, 100));
        for (Decision d : burst("consistency", "7.7.7.7", 6)) {
            // allowed 与 reason 必须自洽
            if (d.allowed()) {
                assertTrue(d.reason() == DecisionReason.ALLOWED
                        || d.reason() == DecisionReason.FAIL_OPEN);
                assertFalse(d.reason() == DecisionReason.RATE_LIMITED
                        || d.reason() == DecisionReason.BANNED
                        || d.reason() == DecisionReason.FAIL_CLOSED);
            } else {
                assertFalse(d.reason() == DecisionReason.ALLOWED);
            }
        }
    }
}
