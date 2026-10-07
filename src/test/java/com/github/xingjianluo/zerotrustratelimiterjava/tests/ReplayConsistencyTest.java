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
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 客户端重试一致性：同一请求标识短时间内重复到达，结论（放行/拦下分层/原因/用量/策略版本）
 * 与首次完全一致；降级结论同样可重放；重放不访问状态存储；同键并发只产生一次判定。
 */
class ReplayConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(ReplayConsistencyTest.class);
    private static final long T0 = 500_000L;

    private ManualClock clock;
    private InMemoryRateLimitStateStore rateStore;
    private DecisionAuditLog auditLog;
    private RateLimitService service;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        clock = new ManualClock(T0);
        rateStore = new InMemoryRateLimitStateStore(clock);
        var banStore = new FileBanStateStore(clock, tempDir.resolve("ban.json").toString());
        auditLog = new DefaultDecisionAuditLog(clock);
        service = new RateLimitService(rateStore, new BanService(banStore, clock),
                new KeyExtractor(), new PolicyHolder(), clock, auditLog);
        // 直接构造的服务不经过 Spring 启动初始化，这里显式启用 replay
        service.configureReplay(true, 5000L, 10_000);
    }

    private RequestContext req(String id) {
        return new RequestContext(id, "3.3.3.3", null, "/api/hello", "GET");
    }

    private RateLimitProperties props(int limit, FailMode mode) {
        RateLimitProperties p = TestProps.create(10_000, limit, mode, 100);
        p.getBan().setEnabled(false);
        p.getReplay().setEnabled(true);
        p.getReplay().setTtl(Duration.ofSeconds(5));
        return p;
    }

    private void assertSameConclusion(Decision first, Decision replay) {
        assertTrue(replay.replay(), "重放必须显式标记");
        assertEquals(first.allowed(), replay.allowed());
        assertEquals(first.reason(), replay.reason());
        assertEquals(first.layerName(), replay.layerName());
        assertEquals(first.layerLimit(), replay.layerLimit());
        assertEquals(first.currentCount(), replay.currentCount());
        assertEquals(first.remaining(), replay.remaining());
        assertEquals(first.weight(), replay.weight());
        assertEquals(first.policyVersion(), replay.policyVersion());
        assertEquals(first.detail(), replay.detail());
        assertEquals(first.degraded(), replay.degraded());
        assertEquals(first.degradedCause(), replay.degradedCause());
    }

    @Test
    void duplicateAllowedRequestReturnsIdenticalConclusion() {
        service.reload(props(10, FailMode.FAIL_CLOSED));
        Decision first = service.decide(req("dup-allow"));
        assertTrue(first.allowed());
        int sizeAfterFirst = rateStore.size();

        Decision second = service.decide(req("dup-allow"));
        Decision third = service.decide(req("dup-allow"));
        assertSameConclusion(first, second);
        assertSameConclusion(first, third);
        assertEquals(sizeAfterFirst, rateStore.size(), "重放不得再次访问/写入计数存储");
        log.info("replay allow first={} replay={}", first, second);
    }

    @Test
    void duplicateRejectedRequestKeepsLayerAndCountsAndNeverFlipsToAllowed() {
        service.reload(props(2, FailMode.FAIL_CLOSED));
        // 先用别的请求把窗口用满
        assertTrue(service.decide(req("fill-1")).allowed());
        assertTrue(service.decide(req("fill-2")).allowed());

        Decision first = service.decide(req("dup-deny"));
        assertFalse(first.allowed());
        assertEquals(DecisionReason.RATE_LIMITED, first.reason());
        long usedAtFirst = first.currentCount();
        int sizeAfterFirst = rateStore.size();

        // 无论重复多少次、窗口用量如何变化，结论与首次用量完全一致，且计数器不再增加
        for (int i = 0; i < 10; i++) {
            Decision again = service.decide(req("dup-deny"));
            assertSameConclusion(first, again);
            assertEquals(usedAtFirst, again.currentCount());
        }
        assertEquals(sizeAfterFirst, rateStore.size(), "重试不得反复打存储");
    }

    @Test
    void replayAlsoCoversDegradedConclusionAndSkipsStoreCalls() {
        service.reload(props(10, FailMode.FAIL_CLOSED));
        rateStore.setFaultUnavailable(true);
        Decision first;
        try {
            first = service.decide(req("dup-degrade"));
        } finally {
            rateStore.setFaultUnavailable(false);
        }
        assertFalse(first.allowed());
        assertEquals(DecisionReason.FAIL_CLOSED, first.reason());
        assertTrue(first.degraded());
        assertEquals("STORE_UNAVAILABLE", first.degradedCause());

        // 即便此刻存储已恢复，重试仍返回首次的降级结论，且不访问存储
        Decision again = service.decide(req("dup-degrade"));
        assertSameConclusion(first, again);

        // 反过来：首次 FAIL_OPEN 放行，之后存储恢复，重放仍是降级放行
        rateStore.reset();
        service.reload(props(10, FailMode.FAIL_OPEN));
        rateStore.setFaultDelayMillis(150);
        Decision openFirst;
        try {
            openFirst = service.decide(req("dup-open"));
        } finally {
            rateStore.setFaultDelayMillis(0);
        }
        assertEquals(DecisionReason.FAIL_OPEN, openFirst.reason());
        Decision openAgain = service.decide(req("dup-open"));
        assertSameConclusion(openFirst, openAgain);
        assertTrue(openAgain.allowed());
    }

    @Test
    void concurrentDuplicatesOfSameRequestProduceOneDecisionAndOneCounterIncrement() throws Exception {
        service.reload(props(100, FailMode.FAIL_CLOSED));
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Decision[] decisions = new Decision[threads];
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    decisions[idx] = service.decide(req("dup-concurrent"));
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

        long firstCount = decisions[0].currentCount();
        long replayCount = 0;
        for (Decision d : decisions) {
            assertEquals(decisions[0].allowed(), d.allowed());
            assertEquals(decisions[0].reason(), d.reason());
            assertEquals(decisions[0].detail(), d.detail());
            if (d.replay()) {
                replayCount++;
            }
        }
        assertEquals(threads - 1, replayCount, "仅一次真实判定，其余全部重放");
        // 该请求在 IP 层只产生 1 次消耗
        long used = rateStore.findActive(clock.nowMillis()).stream()
                .filter(c -> c.dimension().name().equals("IP") && c.dimensionKey().equals("3.3.3.3"))
                .mapToLong(c -> c.consumed()).sum();
        assertEquals(1, used, "同键并发不得重复计数");
        log.info("concurrent duplicates firstUsed={} replays={}", firstCount, replayCount);
    }

    @Test
    void afterTtlExpiresRequestIsReEvaluatedInTheCurrentWindow() {
        service.reload(props(10, FailMode.FAIL_CLOSED));
        Decision first = service.decide(req("dup-ttl"));
        assertTrue(first.allowed());

        clock.advance(4999);
        Decision stillCached = service.decide(req("dup-ttl"));
        assertTrue(stillCached.replay());

        clock.advance(2); // 超过 5s TTL
        Decision reEvaluated = service.decide(req("dup-ttl"));
        assertFalse(reEvaluated.replay(), "TTL 过期后应重新判定");
        assertTrue(reEvaluated.allowed());
        // 重算产生了第二次消耗
        long used = rateStore.findActive(clock.nowMillis()).stream()
                .filter(c -> c.dimensionKey().equals("3.3.3.3"))
                .mapToLong(c -> c.consumed()).sum();
        assertEquals(2, used);
    }

    @Test
    void replayedConclusionKeepsPolicyVersionAcrossHotReload() {
        service.reload(props(10, FailMode.FAIL_CLOSED));
        Decision first = service.decide(req("dup-version"));
        assertEquals(1, first.policyVersion());

        service.reload(props(50, FailMode.FAIL_CLOSED));
        assertEquals(2, service.currentVersion());
        Decision again = service.decide(req("dup-version"));
        assertEquals(1, again.policyVersion(), "重放结论锁定首次策略版本");
        assertSameConclusion(first, again);
    }
}
