package com.github.xingjianluo.zerotrustratelimiterjava.tests;

import com.github.xingjianluo.zerotrustratelimiterjava.audit.DecisionAuditLog;
import com.github.xingjianluo.zerotrustratelimiterjava.audit.DefaultDecisionAuditLog;
import com.github.xingjianluo.zerotrustratelimiterjava.config.RateLimitProperties;
import com.github.xingjianluo.zerotrustratelimiterjava.core.BanService;
import com.github.xingjianluo.zerotrustratelimiterjava.core.DecisionCache;
import com.github.xingjianluo.zerotrustratelimiterjava.core.KeyExtractor;
import com.github.xingjianluo.zerotrustratelimiterjava.core.PolicyHolder;
import com.github.xingjianluo.zerotrustratelimiterjava.core.RateLimitService;
import com.github.xingjianluo.zerotrustratelimiterjava.core.WeightResolver;
import com.github.xingjianluo.zerotrustratelimiterjava.model.Decision;
import com.github.xingjianluo.zerotrustratelimiterjava.model.DecisionReason;
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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 加权配额：按可配置权重消耗配额、窗口内按累计消耗量判定、未配置权重与旧行为一致、
 * 权重配置热更新只对之后新窗口生效（历史用量/判定不被改写）。
 */
class WeightedQuotaTest {

    private static final Logger log = LoggerFactory.getLogger(WeightedQuotaTest.class);
    private static final long T0 = 30_000L;
    private static final long WINDOW = 1000L;

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

    private RequestContext req(String id, String path) {
        return new RequestContext(id, "10.0.0.1", null, path, "GET");
    }

    private RateLimitProperties props(long limit, Map<String, Long> weights) {
        RateLimitProperties p = TestProps.create(WINDOW, limit, FailMode.FAIL_CLOSED, 100);
        p.getPolicy().setWeights(weights);
        return p;
    }

    private Map<String, Long> weights(String... kv) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], Long.parseLong(kv[i + 1]));
        }
        return m;
    }

    @Test
    void heavyRequestConsumesConfiguredWeight() {
        // 上限 10：普通查询权重 1，导出权重 8
        service.reload(props(10, weights("/api/export", "8", "/api", "1")));

        Decision heavy = service.decide(req("h-1", "/api/export/big"));
        assertTrue(heavy.allowed());
        assertEquals(8, heavy.requestWeight());

        // 再消耗 8 的导出请求会超限（8+8=16 > 10）：该请求不被预留，用量保持 8，
        // 拒绝信息仍报告上限/已用/剩余
        Decision rejected = service.decide(req("h-2", "/api/export/big"));
        assertFalse(rejected.allowed());
        assertEquals(DecisionReason.RATE_LIMITED, rejected.reason());
        assertEquals(8, rejected.currentCount());
        assertEquals(10, rejected.tierLimit());
        assertEquals(2, rejected.tierRemaining());
        assertEquals(8, rejected.requestWeight());
        assertEquals("tier[0]:IP:1000ms", rejected.tierName());
        log.info("weighted reject: id={} weight={} used={}/{} remaining={}",
                rejected.requestId(), rejected.requestWeight(),
                rejected.currentCount(), rejected.tierLimit(), rejected.tierRemaining());
    }

    @Test
    void longestPrefixWinsAndUnmatchedWeighsOne() {
        service.reload(props(100, weights("/api/export", "10", "/api", "2")));

        assertEquals(10, service.decide(req("m-export", "/api/export/x")).requestWeight());
        assertEquals(2, service.decide(req("m-api", "/api/other")).requestWeight());
        // 未匹配任何前缀：权重 1（旧行为）
        assertEquals(1, service.decide(req("m-other", "/health")).requestWeight());
    }

    @Test
    void noWeightsConfiguredBehavesExactlyLikeRequestCount() {
        service.reload(props(5, Map.of()));
        for (int i = 0; i < 5; i++) {
            assertTrue(service.decide(req("n-" + i, "/api/export/" + i)).allowed());
        }
        Decision sixth = service.decide(req("n-6", "/api/export/6"));
        assertFalse(sixth.allowed());
        assertEquals(5, sixth.currentCount(), "被拒请求不消耗配额，用量保持在上限 5");
    }

    @Test
    void weightChangeOnlyAffectsNewWindowAndHistoryKept() {
        // v1：上限 20，无权重（都按 1 消耗），窗口内消耗 8
        service.reload(props(20, Map.of()));
        for (int i = 0; i < 8; i++) {
            service.decide(req("v1-" + i, "/api/export/x"));
        }

        // 同窗口热更新：导出权重调为 10（新版本命名空间，旧窗口用量不被改写）
        service.reload(props(20, weights("/api/export", "10")));
        // 仍在旧窗口时间点，但新版本计数从 0 开始：两个重请求（10+10=20）恰好放满
        Decision h1 = service.decide(req("v2-1", "/api/export/x"));
        Decision h2 = service.decide(req("v2-2", "/api/export/x"));
        assertTrue(h1.allowed() && h2.allowed());
        assertEquals(2, h2.policyVersion());
        Decision h3 = service.decide(req("v2-3", "/api/export/x"));
        assertFalse(h3.allowed(), "第三个重请求会使累计达 30 > 20，应在新版本窗口被拒");
        assertEquals(20, h3.currentCount(), "被拒不消耗，用量保持 20");

        // 历史判定（v1）的版本与用量不被改写
        Decision v1Historical = auditLog.recent(100).stream()
                .map(e -> e.decision())
                .filter(d -> "v1-0".equals(d.requestId())).findFirst().orElseThrow();
        assertEquals(1, v1Historical.policyVersion());

        // 切换到下一窗口后，新权重正常生效，计数重新从 0 开始
        clock.advance(WINDOW);
        Decision next = service.decide(req("v2w-1", "/api/export/x"));
        assertTrue(next.allowed());
        assertEquals(10, next.requestWeight());
    }
}
