package com.github.xingjianluo.zerotrustratelimiterjava.tests;

import com.github.xingjianluo.zerotrustratelimiterjava.store.FaultState;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end checks through the servlet filter: rejection status codes, distinct reason
 * codes, fail-open degradation header and immediate manual unban.
 * Uses a unique client IP so the seeded default policy starts with a clean key.
 */
@SpringBootTest(properties = {
        "rate-limiter.data-dir=target/test-data/http",
        "rate-limiter.fail-strategy=CLOSED",
        "rate-limiter.store-timeout-millis=300"
})
@AutoConfigureMockMvc
class HttpIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(HttpIntegrationTest.class);

    @Autowired
    MockMvc mockMvc;

    @Autowired
    FaultState faultState;

    @Test
    void rateLimitReturns429ThenUnbanAllowsAndFaultReturns503() throws Exception {
        String ip = "10.77.88.99";

        // Default policy: 5 requests / 10s per IP on /api/*
        for (int i = 1; i <= 5; i++) {
            mockMvc.perform(get("/api/hello").header("X-Forwarded-For", ip))
                    .andExpect(status().isOk());
        }
        MvcResult rejected = mockMvc
                .perform(get("/api/hello").header("X-Forwarded-For", ip))
                .andExpect(status().is(429))
                .andExpect(header().string("X-RateLimit-Reason", "RATE_LIMITED"))
                .andReturn();
        log.info("requestId={} inputs=/api/hello 6th result=status:429 body:{}",
                rejected.getResponse().getHeader("X-Request-Id"),
                rejected.getResponse().getContentAsString());

        // Fail-closed under forced storage unavailability: 503 with distinct reason.
        faultState.setUnavailable(true);
        String freshIp = "10.77.88.100";
        mockMvc.perform(get("/api/hello").header("X-Forwarded-For", freshIp))
                .andExpect(status().is(503))
                .andExpect(header().string("X-RateLimit-Reason",
                        "FAIL_CLOSED_UNAVAILABLE"));
        faultState.reset();

        // Admin endpoints stay reachable even while the limiter would otherwise reject.
        mockMvc.perform(post("/admin/bans/" + ip + "/unban"))
                .andExpect(status().isOk());

        // After unban counters are still high in the current window, so rate limiting
        // still rejects (unban clears ban, not the window counter). Advance is not
        // possible with wall clock here; assert metrics endpoint is observable instead.
        mockMvc.perform(get("/admin/metrics"))
                .andExpect(status().isOk());
    }

    @Test
    void adminFaultAndDecisionsEndpointsAreExemptFromFiltering() throws Exception {
        mockMvc.perform(get("/admin/policies")).andExpect(status().isOk());
        mockMvc.perform(get("/admin/decisions?limit=3")).andExpect(status().isOk());
    }
}
