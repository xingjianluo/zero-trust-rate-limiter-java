package com.github.xingjianluo.zerotrustratelimiterjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 新配额特性的端到端验证：加权请求、同一 X-Request-Id 重试结论一致（含 X-RateLimit-Replay）、
 * 拒绝体携带分层信息、管理端只读配额视图。
 */
@SpringBootTest(properties = {
        "ratelimit.storage.ban-state-file=",
        "ratelimit.storage.rate-state-file=",
        "ratelimit.ban.enabled=false",
        "ratelimit.storage.timeout=200ms",
        "ratelimit.storage.fail-mode=FAIL_CLOSED",
        "ratelimit.replay.enabled=true",
        "ratelimit.replay.ttl=5s"
})
@AutoConfigureMockMvc
class QuotaFeatureWebIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private com.github.xingjianluo.zerotrustratelimiterjava.core.RateLimitService rateLimitService;

    private final ObjectMapper mapper = new ObjectMapper();

    private void reloadLayeredWeighted(long shortLimit, long globalLimit) throws Exception {
        String payload = """
                {
                  "policy": {
                    "window": "1s", "limit": 1000, "dimensions": ["IP"],
                    "layers": [
                      {"name": "ip-short", "dimension": "IP", "window": "10s", "limit": %d},
                      {"name": "global-long", "dimension": "GLOBAL", "window": "60s", "limit": %d}
                    ],
                    "weights": [
                      {"path": "/api/export", "weight": 5}
                    ]
                  },
                  "ban": {"enabled": false, "thresholdWindows": 2,
                          "levelDuration": ["1m"], "observeWindow": "10m",
                          "resetAfterObserve": true, "dimension": "IP"},
                  "storage": {"banStateFile": "", "rateStateFile": "",
                              "timeout": "200ms", "failMode": "FAIL_CLOSED"},
                  "replay": {"enabled": true, "ttl": "5s", "capacity": 10000}
                }
                """.formatted(shortLimit, globalLimit);
        mockMvc.perform(post("/admin/policy/reload")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk());
    }

    @Test
    void weightedRequestReplayAndQuotaViewEndToEnd() throws Exception {
        reloadLayeredWeighted(10, 1000);

        // 两个普通请求（权重 1）
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/api/hello")
                            .header("X-Forwarded-For", "10.2.0.1")
                            .header("X-Request-Id", "web-norm-" + i))
                    .andExpect(status().isOk());
        }

        // 一个重请求（权重 5），累计 7
        mockMvc.perform(get("/api/export/report")
                        .header("X-Forwarded-For", "10.2.0.1")
                        .header("X-Request-Id", "web-heavy-1"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-RateLimit-Replay"));

        // 同一 X-Request-Id 重试：结论一致且标记 replay，不再消耗配额
        mockMvc.perform(get("/api/export/report")
                        .header("X-Forwarded-For", "10.2.0.1")
                        .header("X-Request-Id", "web-heavy-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Replay", "true"))
                .andExpect(header().string("X-RateLimit-Decision", "ALLOWED"));

        // 视图：IP 层用量仍为 7（重试未重复计数），全局层也是 7
        MvcResult viewResult = mockMvc.perform(get("/admin/quota")
                        .param("dimension", "IP").param("key", "10.2.0.1"))
                .andExpect(status().isOk()).andReturn();
        JsonNode view = mapper.readTree(viewResult.getResponse().getContentAsString());
        JsonNode ipLayer = view.get("layers").get(0);
        assertEquals("ip-short", ipLayer.get("name").asText());
        assertEquals(7, ipLayer.get("used").asInt());
        assertEquals(3, ipLayer.get("remaining").asInt());
        MvcResult gView = mockMvc.perform(get("/admin/quota")
                        .param("dimension", "GLOBAL").param("key", "global"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(7, mapper.readTree(gView.getResponse().getContentAsString())
                .get("layers").get(0).get("used").asInt());

        // 再来一个重请求：7+5=12 > 10，被 ip-short 层拦下
        MvcResult rejected = mockMvc.perform(get("/api/export/report")
                        .header("X-Forwarded-For", "10.2.0.1")
                        .header("X-Request-Id", "web-heavy-over"))
                .andExpect(status().is(429))
                .andExpect(header().string("X-RateLimit-Layer", "ip-short"))
                .andReturn();
        JsonNode body = mapper.readTree(rejected.getResponse().getContentAsString());
        assertEquals("RATE_LIMITED", body.get("reason").asText());
        assertEquals("ip-short", body.get("layerName").asText());
        assertEquals(12, body.get("used").asInt());
        assertEquals(10, body.get("layerLimit").asInt());
        assertEquals(0, body.get("remaining").asInt());
        assertEquals(5, body.get("weight").asInt());

        // 被拒请求用同一 X-Request-Id 重试：仍然 429、同一层、同样数字
        MvcResult replayRejected = mockMvc.perform(get("/api/export/report")
                        .header("X-Forwarded-For", "10.2.0.1")
                        .header("X-Request-Id", "web-heavy-over"))
                .andExpect(status().is(429))
                .andExpect(header().string("X-RateLimit-Replay", "true"))
                .andExpect(header().string("X-RateLimit-Layer", "ip-short"))
                .andReturn();
        JsonNode replayBody = mapper.readTree(replayRejected.getResponse().getContentAsString());
        assertEquals(12, replayBody.get("used").asInt());
        assertEquals(0, replayBody.get("remaining").asInt());
    }
}
