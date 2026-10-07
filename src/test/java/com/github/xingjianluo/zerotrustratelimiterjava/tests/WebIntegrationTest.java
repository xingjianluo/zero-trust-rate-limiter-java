package com.github.xingjianluo.zerotrustratelimiterjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.zerotrustratelimiterjava.config.RateLimitProperties;
import com.github.xingjianluo.zerotrustratelimiterjava.core.RateLimitService;
import com.github.xingjianluo.zerotrustratelimiterjava.store.FileBanStateStore;
import com.github.xingjianluo.zerotrustratelimiterjava.store.InMemoryRateLimitStateStore;
import com.github.xingjianluo.zerotrustratelimiterjava.model.LimitDimension;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 端到端 Web 集成测试：默认配置（limit=10）下验证状态码、原因响应头、
 * 管理端热更新与人工解除。降级模式/阈值的快速边界由服务层测试保证。
 */
@SpringBootTest(properties = {
        "ratelimit.storage.ban-state-file=",
        "ratelimit.storage.quota-state-file=",
        "ratelimit.policy.window=1s",
        "ratelimit.policy.limit=3",
        "ratelimit.ban.enabled=false",
        "ratelimit.storage.timeout=200ms",
        "ratelimit.storage.fail-mode=FAIL_CLOSED"
})
@AutoConfigureMockMvc
class WebIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RateLimitService rateLimitService;
    @Autowired
    private InMemoryRateLimitStateStore rateStore;
    @Autowired
    private FileBanStateStore banStore;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void allowedThenRateLimitedWithDistinguishableResponse() throws Exception {
        // 为避免与其它用例共享计数器，先热更新换一个新版本命名空间
        reloadConfig(3, "FAIL_CLOSED", 200);

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/hello")
                            .header("X-Forwarded-For", "10.1.0.1")
                            .header("X-Request-Id", "req-" + i))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Request-Id", "req-" + i))
                    .andExpect(header().string("X-RateLimit-Decision", "ALLOWED"));
        }
        MvcResult rejected = mockMvc.perform(get("/api/hello")
                        .header("X-Forwarded-For", "10.1.0.1")
                        .header("X-Request-Id", "req-4"))
                .andExpect(status().is(429))
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("X-RateLimit-Decision", "RATE_LIMITED"))
                .andReturn();
        JsonNode body = mapper.readTree(rejected.getResponse().getContentAsString());
        assertEquals("RATE_LIMITED", body.get("reason").asText());
        assertEquals("IP", body.get("limitedBy").asText());
        assertEquals("req-4", body.get("requestId").asText());
    }

    @Test
    void failClosedReturns503AndFailOpenMarksHeaderAfterReload() throws Exception {
        reloadConfig(100, "FAIL_CLOSED", 50);
        rateStore.setFaultUnavailable(true);
        try {
            MvcResult closed = mockMvc.perform(get("/api/hello")
                            .header("X-Forwarded-For", "10.1.0.2"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("X-RateLimit-Decision", "FAIL_CLOSED"))
                    .andReturn();
            assertEquals("STORE_UNAVAILABLE",
                    mapper.readTree(closed.getResponse().getContentAsString()).get("degradedCause").asText());
        } finally {
            rateStore.setFaultUnavailable(false);
        }

        reloadConfig(100, "FAIL_OPEN", 50);
        rateStore.setFaultDelayMillis(120);
        try {
            mockMvc.perform(get("/api/hello")
                            .header("X-Forwarded-For", "10.1.0.3"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Decision", "FAIL_OPEN"))
                    .andExpect(header().string("X-RateLimit-Degraded", "true"))
                    .andExpect(header().string("X-RateLimit-Degraded-Cause", "STORE_TIMEOUT"));
        } finally {
            rateStore.setFaultDelayMillis(0);
        }
    }

    @Test
    void adminEndpointsAreNotRateLimitedAndReloadBumpsVersion() throws Exception {
        reloadConfig(100, "FAIL_CLOSED", 100);
        long before = rateLimitService.currentVersion();
        String payload = """
                {
                  "policy": {"window": "500ms", "limit": 7, "dimensions": ["IP"]},
                  "ban": {"enabled": false, "thresholdWindows": 2,
                          "levelDuration": ["1m"], "observeWindow": "10m",
                          "resetAfterObserve": true, "dimension": "IP"},
                  "storage": {"banStateFile": "", "timeout": "100ms", "failMode": "FAIL_CLOSED"}
                }
                """;
        mockMvc.perform(post("/admin/policy/reload")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyVersion").value(before + 1))
                .andExpect(jsonPath("$.status").value("applied"));

        // 即使限流存储故障，管理端也必须可用（人工解除通道不被阻塞）
        rateStore.setFaultUnavailable(true);
        try {
            mockMvc.perform(post("/admin/bans/10.0.0.9/unban"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("unbanned"));
            mockMvc.perform(get("/admin/bans")).andExpect(status().isOk());
        } finally {
            rateStore.setFaultUnavailable(false);
        }
    }

    @Test
    void tieredRejectionBodyNamesTierAndQuotaViewMatches() throws Exception {
        // 分层配置：IP 短窗口 1s 上限 2；GLOBAL 1s 上限 100
        RateLimitProperties p = new RateLimitProperties();
        p.getPolicy().setWindow(Duration.ofMillis(1000));
        p.getPolicy().setTiers(List.of(
                new RateLimitProperties.Tier(LimitDimension.IP, Duration.ofMillis(1000), 2),
                new RateLimitProperties.Tier(LimitDimension.GLOBAL, Duration.ofMillis(1000), 100)));
        p.getBan().setEnabled(false);
        p.getBan().setLevelDuration(List.of(Duration.ofMinutes(1)));
        p.getStorage().setTimeout(Duration.ofMillis(100));
        p.getStorage().setFailMode(com.github.xingjianluo.zerotrustratelimiterjava.model.FailMode.FAIL_CLOSED);
        rateStore.reset();
        banStore.reset();
        rateLimitService.reload(p);

        mockMvc.perform(get("/api/hello").header("X-Forwarded-For", "10.7.0.1").header("X-Request-Id", "t-1"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/hello").header("X-Forwarded-For", "10.7.0.1").header("X-Request-Id", "t-2"))
                .andExpect(status().isOk());
        MvcResult rejected = mockMvc.perform(get("/api/hello")
                        .header("X-Forwarded-For", "10.7.0.1").header("X-Request-Id", "t-3"))
                .andExpect(status().is(429))
                .andReturn();
        JsonNode body = mapper.readTree(rejected.getResponse().getContentAsString());
        assertEquals("RATE_LIMITED", body.get("reason").asText());
        assertEquals(0, body.get("limitedByTier").asInt());
        assertEquals("tier[0]:IP:1000ms", body.get("tierName").asText());
        assertEquals(2, body.get("tierLimit").asInt());
        assertEquals(2, body.get("currentUsage").asInt());
        assertEquals(0, body.get("tierRemaining").asInt());
        assertEquals(1, body.get("requestWeight").asInt());

        // 只读视图与判定同源：短窗口已用 2、剩余 0
        MvcResult view = mockMvc.perform(get("/admin/quota")
                        .param("dimension", "IP").param("key", "10.7.0.1"))
                .andExpect(status().isOk()).andReturn();
        JsonNode viewBody = mapper.readTree(view.getResponse().getContentAsString());
        assertEquals("IP", viewBody.get("dimension").asText());
        assertEquals("10.7.0.1", viewBody.get("key").asText());
        assertEquals(false, viewBody.get("banned").asBoolean());
        JsonNode tiers = viewBody.get("tiers");
        assertEquals(1, tiers.size(), "IP 维度只有一层");
        assertEquals(2, tiers.get(0).get("used").asInt());
        assertEquals(0, tiers.get(0).get("remaining").asInt());
        assertEquals(2, tiers.get(0).get("limit").asInt());
    }

    private void reloadConfig(long limit, String failMode, long timeoutMs) throws Exception {
        RateLimitProperties p = new RateLimitProperties();
        p.getPolicy().setWindow(Duration.ofMillis(1000));
        p.getPolicy().setLimit(limit);
        p.getBan().setEnabled(false);
        p.getBan().setLevelDuration(List.of(Duration.ofMinutes(1)));
        p.getStorage().setTimeout(Duration.ofMillis(timeoutMs));
        p.getStorage().setFailMode(com.github.xingjianluo.zerotrustratelimiterjava.model.FailMode.valueOf(failMode));
        // 用例隔离：清空计数/封禁/故障，再发布新版本计数命名空间
        rateStore.reset();
        banStore.reset();
        rateLimitService.reload(p);
    }
}
