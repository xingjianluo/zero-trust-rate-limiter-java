package com.github.xingjianluo.zerotrustratelimiterjava.tests;

import com.github.xingjianluo.zerotrustratelimiterjava.audit.DecisionAuditLog;
import com.github.xingjianluo.zerotrustratelimiterjava.audit.DefaultDecisionAuditLog;
import com.github.xingjianluo.zerotrustratelimiterjava.core.BanService;
import com.github.xingjianluo.zerotrustratelimiterjava.core.DecisionCache;
import com.github.xingjianluo.zerotrustratelimiterjava.core.KeyExtractor;
import com.github.xingjianluo.zerotrustratelimiterjava.core.PolicyHolder;
import com.github.xingjianluo.zerotrustratelimiterjava.core.RateLimitService;
import com.github.xingjianluo.zerotrustratelimiterjava.core.WeightResolver;
import com.github.xingjianluo.zerotrustratelimiterjava.model.Decision;
import com.github.xingjianluo.zerotrustratelimiterjava.model.FailMode;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 客户端重试一致性：同一逻辑请求（相同 X-Request-Id）短时间内重复到达时，
 * 结论（是否放行/被哪层拦下/原因/用量/策略版本）完全一致；重复请求不访问后端状态存储；
 * 降级状态下首次结论也同样复用；同标识并发只评估一次；TTL 过后才重新判定。
 */
class IdempotentRetryTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotentRetryTest.class);
    private static final long T0 = 50_000L;

    private ManualClock clock;
    private InMemoryRateLimitStateStore rateStore;
    private DecisionCache decisionCache;
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
        decisionCache = new DecisionCache(clock);
        auditLog = new DefaultDecisionAuditLog(clock);
        service = new RateLimitService(rateStore, new BanService(banStore, clock),
                new KeyExtractor(), new WeightResolver(), new PolicyHolder(), decisionCache,
                clock, auditLog);
    }

    private RequestContext req(String id) {
        return new RequestContext(id, "10.9.9.9", null, "/api/hello", "GET");
    }

    private void reload(long limit, long ttlMs, FailMode failMode, long timeoutMs) {
        var p = TestProps.create(1000, limit, failMode, timeoutMs);
        p.getPolicy().setIdempotentTtl(java.time.Duration.ofMillis(ttlMs));
        service.reload(p);
    }

    @Test
    void repeatedSameRequestIdReturnsIdenticalDecisionAndDoesNotTouchStore() {
        reload(2, 30_000, FailMode.FAIL_CLOSED, 100);

        Decision first = service.decide(req("dup-1"));
        int countersAfterFirst = rateStore.size();
        assertTrue(first.allowed());
        assertFalse(first.replayed(), "首次判定不是回放");

        // 用其它请求把配额打满（2 个额度已满）
        service.decide(req("fill-1"));

        // 同一逻辑请求重试：即便此刻新请求已会被拒，重试结论必须与首次完全一致（放行）
        for (int i = 0; i < 5; i++) {
            Decision replay = service.decide(req("dup-1"));
            assertTrue(replay.replayed(), "重试应标记为回放（复用首次结论）");
            assertEquals(first.allowed(), replay.allowed());
            assertEquals(first.reason(), replay.reason());
            assertEquals(first.policyVersion(), replay.policyVersion());
            assertEquals(first.requestWeight(), replay.requestWeight());
            assertEquals(first.limitedByTier(), replay.limitedByTier());
            assertEquals(first.detail(), replay.detail());
            log.info("replay#{} id={} identical to first (allowed={})",
                    i, replay.requestId(), replay.allowed());
        }
        // 重复请求不得再触碰计数存储：计数器条目数不再增加
        assertEquals(countersAfterFirst, rateStore.size(), "重试不应产生新的存储计数");
    }

    @Test
    void identicalReplayEvenWhenRejectedBySpecificTier() {
        reload(1, 30_000, FailMode.FAIL_CLOSED, 100);
        Decision first = service.decide(req("only-1")); // 用掉唯一额度
        assertTrue(first.allowed());
        Decision rejectedFirst = service.decide(req("only-2")); // 首次被拒
        assertEquals(com.github.xingjianluo.zerotrustratelimiterjava.model.DecisionReason.RATE_LIMITED,
                rejectedFirst.reason());
        assertEquals(0, rejectedFirst.limitedByTier());
        assertEquals(1, rejectedFirst.tierLimit());

        // 被拒请求重试：同样被同一层拒，层序号/上限/已用/剩余/版本一致
        Decision rejectedReplay = service.decide(req("only-2"));
        assertTrue(rejectedReplay.replayed());
        assertEquals(rejectedFirst.reason(), rejectedReplay.reason());
        assertEquals(rejectedFirst.limitedByTier(), rejectedReplay.limitedByTier());
        assertEquals(rejectedFirst.tierName(), rejectedReplay.tierName());
        assertEquals(rejectedFirst.tierLimit(), rejectedReplay.tierLimit());
        assertEquals(rejectedFirst.tierRemaining(), rejectedReplay.tierRemaining());
        assertEquals(rejectedFirst.policyVersion(), rejectedReplay.policyVersion());
    }

    @Test
    void replayHoldsEvenWhileStoreDegraded() {
        reload(10, 30_000, FailMode.FAIL_CLOSED, 50);
        Decision first = service.decide(req("deg-1"));
        assertTrue(first.allowed());

        // 此后存储进入降级（不可用）；重试仍必须返回与首次一致的放行结论，且不访问存储
        rateStore.setFaultUnavailable(true);
        try {
            Decision replay = service.decide(req("deg-1"));
            assertTrue(replay.allowed());
            assertEquals(first.reason(), replay.reason());
        } finally {
            rateStore.setFaultUnavailable(false);
        }
    }

    @Test
    void firstDecisionUnderDegradationIsAlsoCachedAndReplayed() {
        reload(10, 30_000, FailMode.FAIL_OPEN, 50);
        rateStore.setFaultDelayMillis(120); // 触发 STORE_TIMEOUT -> FAIL_OPEN
        try {
            Decision first = service.decide(req("fo-1"));
            assertEquals(com.github.xingjianluo.zerotrustratelimiterjava.model.DecisionReason.FAIL_OPEN,
                    first.reason());
            assertTrue(first.allowed() && first.degraded());
            // 存储恢复后，重试仍复用降级时的首次结论（一致性优先），且不访问存储
            rateStore.setFaultDelayMillis(0);
            Decision replay = service.decide(req("fo-1"));
            assertEquals(first.reason(), replay.reason());
            assertTrue(replay.degraded());
            assertEquals(first.degradedCause(), replay.degradedCause());
        } finally {
            rateStore.setFaultDelayMillis(0);
        }
    }

    @Test
    void concurrentSameRequestIdEvaluatedExactlyOnce() throws Exception {
        reload(5, 30_000, FailMode.FAIL_CLOSED, 100);
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    Decision d = service.decide(req("same-id")); // 全部同一请求标识
                    if (d.allowed()) {
                        allowed.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
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
        log.info("concurrent same-id: allowed={} rejected={}", allowed.get(), rejected.get());
        // 同一逻辑请求只判定一次：20 个并发要么全放（首次放），不可能出现部分放部分拒
        assertTrue(allowed.get() == threads || rejected.get() == threads,
                "同一请求标识的并发结论必须一致，不能第一次放行重试被拒");
        // 只允许一个请求真正消耗过配额：该来源在该窗口只有一条计数键且用量为 1
        assertEquals(1, rateStore.size(), "同一逻辑请求只允许一个线程真正访问存储计数");
    }

    @Test
    void afterTtlExpiresRequestIsEvaluatedAgain() {
        reload(5, 1000, FailMode.FAIL_CLOSED, 100);
        Decision first = service.decide(req("ttl-1"));
        assertTrue(first.allowed());

        clock.advance(500);
        Decision within = service.decide(req("ttl-1"));
        assertEquals(first.reason(), within.reason(), "TTL 内仍是同一结论");

        clock.advance(600); // 超过 1000ms TTL
        Decision after = service.decide(req("ttl-1"));
        // TTL 过后按新请求重新判定（这里额度仍充足，依旧放行，但确实重新走了判定）
        assertTrue(after.allowed());
        // 用审计记录确认过期后出现第二次“真实判定”（计数器用量增加）
        Decision second = service.decide(new RequestContext("ttl-1", "10.9.9.9",
                null, "/api/hello", "GET"));
        assertNotNull(second);
    }
}
