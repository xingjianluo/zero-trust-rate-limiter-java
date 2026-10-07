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

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 限流与封禁联动：通过真实的服务编排验证“连续超限窗口 -> 封禁 -> 封禁中拒绝 ->
 * 到期解除 -> 人工解除立即生效”。
 */
class BanIntegrationTest {

    private static final long T0 = 20_000L;

    private ManualClock clock;
    private InMemoryRateLimitStateStore rateStore;
    private FileBanStateStore banStore;
    private BanService banService;
    private DecisionAuditLog auditLog;
    private RateLimitService service;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        clock = new ManualClock(T0);
        rateStore = new InMemoryRateLimitStateStore(clock);
        banStore = new FileBanStateStore(clock, tempDir.resolve("ban.json").toString());
        banService = new BanService(banStore, clock);
        auditLog = new DefaultDecisionAuditLog(clock);
        service = new RateLimitService(rateStore, banService, new KeyExtractor(),
                new PolicyHolder(), clock, auditLog);
    }

    private RateLimitProperties props() {
        RateLimitProperties p = TestProps.create(1000, 3, FailMode.FAIL_CLOSED, 100);
        p.getBan().setEnabled(true);
        p.getBan().setThresholdWindows(2);
        p.getBan().setLevelDuration(List.of(Duration.ofMillis(500), Duration.ofMillis(2000)));
        p.getBan().setObserveWindow(Duration.ofMillis(1000));
        return p;
    }

    private List<Decision> burst(String prefix, int n) {
        List<Decision> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(service.decide(new RequestContext(prefix + "-" + i, "9.9.9.9",
                    null, "/api/hello", "GET")));
        }
        return out;
    }

    @Test
    void overLimitWindowsLeadToBanAndBannedRequestsSkipCounters() {
        service.reload(props());

        // 窗口 1：3 放行 + 2 限流
        List<Decision> w1 = burst("w1", 5);
        assertEquals(3, w1.stream().filter(Decision::allowed).count());
        assertEquals(2, w1.stream().filter(d -> d.reason() == DecisionReason.RATE_LIMITED).count());

        clock.advance(1000);
        // 窗口 2：再次超限即升级封禁；触发升级的当次仍是 RATE_LIMITED
        List<Decision> w2 = burst("w2", 5);
        long bannedInW2 = w2.stream().filter(d -> d.reason() == DecisionReason.BANNED).count();
        assertTrue(bannedInW2 >= 1, "窗口2升级后同窗口后续请求应直接 BANNED");

        // 封禁中：全部 BANNED，且不产生限流计数
        clock.advance(100);
        int countersBefore = rateStore.size();
        List<Decision> during = burst("banned", 3);
        assertEquals(3, during.stream().filter(d -> d.reason() == DecisionReason.BANNED).count());
        assertEquals(countersBefore, rateStore.size(), "封禁中请求不得触碰限流计数");
        assertTrue(during.get(0).banUntil() > clock.nowMillis());
        assertEquals(1, during.get(0).banLevel());

        // 到期后解除，进入观察期：新窗口恢复正常计数（500ms 封禁期已过且进入下一窗口）
        clock.set(T0 + 3000);
        List<Decision> afterExpiry = burst("expired", 3);
        assertEquals(3, afterExpiry.stream().filter(Decision::allowed).count());

        // 人工解除清零
        banService.unban("9.9.9.9");
        assertEquals(0, banStore.find("9.9.9.9").orElseThrow().level());
    }
}
