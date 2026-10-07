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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 加权配额：重请求按权重消耗、窗口内按累加消耗判定、权重调整只对之后新窗口生效、
 * 未配置权重时行为与旧版一致。判定输入/结论经审计日志打印（含 requestId 与权重）。
 */
class WeightedQuotaTest {

    private static final Logger log = LoggerFactory.getLogger(WeightedQuotaTest.class);
    private static final long T0 = 10_000L;

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
    }

    private RequestContext req(String id, String path) {
        return new RequestContext(id, "1.1.1.1", null, path, "GET");
    }

    private RateLimitProperties propsWithWeights() {
        RateLimitProperties p = TestProps.create(1000, 10, FailMode.FAIL_CLOSED, 100);
        p.getBan().setEnabled(false);
        RateLimitProperties.WeightRule heavy = new RateLimitProperties.WeightRule();
        heavy.setPath("/api/export/");
        heavy.setWeight(4);
        RateLimitProperties.WeightRule exact = new RateLimitProperties.WeightRule();
        exact.setPath("/api/import");
        exact.setWeight(6);
        p.getPolicy().setWeights(List.of(heavy, exact));
        return p;
    }

    @Test
    void heavyRequestsConsumeWeightedQuotaAndLightRequestsStayAtOne() {
        service.reload(propsWithWeights());

        Decision d1 = service.decide(req("w-light-1", "/api/hello"));
        assertTrue(d1.allowed());
        assertEquals(1, d1.weight());

        Decision d2 = service.decide(req("w-export-1", "/api/export/2026/report"));
        assertTrue(d2.allowed());
        assertEquals(4, d2.weight(), "前缀匹配应命中 4 权重");

        // 1 + 4 = 5 尚可容纳一个轻请求
        Decision d3 = service.decide(req("w-light-mid", "/api/hello"));
        assertTrue(d3.allowed());
        assertEquals(1, d3.weight());

        // 再来一个权重 6 的重请求：6 + 6 = 12 > 10，本次即被拦
        Decision denied = service.decide(req("w-import-1", "/api/import"));
        assertFalse(denied.allowed());
        assertEquals(6, denied.weight(), "精确匹配应命中 6 权重");
        assertEquals(DecisionReason.RATE_LIMITED, denied.reason());
        assertEquals(12, denied.currentCount(), "累加消耗量 1+4+1+6");
        assertEquals(10, denied.layerLimit());
        assertEquals(0, denied.remaining());
        assertNotNull(denied.layerName());
        log.info("weighted denial detail={}", denied.detail());
    }

    @Test
    void prefixRuleMustNotMatchSimilarButDifferentPath() {
        service.reload(propsWithWeights());
        Decision similar = service.decide(req("w-sim", "/api/exported-data"));
        assertTrue(similar.allowed());
        assertEquals(1, similar.weight(), "/export/ 前缀不能误伤 /exported-data");
    }

    @Test
    void weightHotReloadOnlyAffectsNewWindowAndKeepsHistoricalUsage() {
        service.reload(propsWithWeights()); // export 权重 4
        Decision exportV1 = service.decide(req("w-v1-export", "/api/export/a"));
        assertEquals(4, exportV1.weight());
        assertEquals(1, exportV1.policyVersion());

        // 同一窗口内热更新权重为 2：版本升级，但进行中的旧窗口用量保持为 4，不被改写
        RateLimitProperties changed = propsWithWeights();
        RateLimitProperties.WeightRule lighter = new RateLimitProperties.WeightRule();
        lighter.setPath("/api/export/");
        lighter.setWeight(2);
        changed.getPolicy().setWeights(List.of(lighter));
        long v2 = service.reload(changed);
        assertEquals(2, v2);
        Decision sameWindow = service.decide(req("w-v1b-export", "/api/export/a"));
        assertEquals(2, sameWindow.weight());
        assertEquals(2, sameWindow.policyVersion());

        // 历史审计里的第一次判定仍然是版本 1、权重 4
        Decision historical = auditLog.recent(50).stream()
                .filter(e -> "w-v1-export".equals(e.request().requestId()))
                .findFirst().orElseThrow().decision();
        assertEquals(1, historical.policyVersion());
        assertEquals(4, historical.weight());

        // 切到下一窗口：完全按新权重 2 计数（10 容量可放 5 个）
        clock.advance(1000);
        List<Decision> nextWindow = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            nextWindow.add(service.decide(req("w-v2-" + i, "/api/export/a")));
        }
        assertEquals(5, nextWindow.stream().filter(Decision::allowed).count());
        Decision rejected = nextWindow.get(5);
        assertEquals(DecisionReason.RATE_LIMITED, rejected.reason());
        assertEquals(2, rejected.policyVersion());
        assertEquals(12, rejected.currentCount(), "6 次 * 权重 2");
    }

    @Test
    void noWeightConfigBehavesExactlyAsLegacyOnePerRequest() {
        RateLimitProperties legacy = TestProps.create(1000, 3, FailMode.FAIL_CLOSED, 100);
        legacy.getBan().setEnabled(false);
        assertNull(legacy.getPolicy().getWeights());
        service.reload(legacy);
        for (int i = 0; i < 3; i++) {
            Decision d = service.decide(req("leg-" + i, "/anything/here/" + i));
            assertTrue(d.allowed());
            assertEquals(1, d.weight());
        }
        Decision fourth = service.decide(req("leg-3", "/anything/here/3"));
        assertFalse(fourth.allowed());
        assertEquals(4, fourth.currentCount(), "旧行为仍按请求条数计数");
    }
}
