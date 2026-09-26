package com.github.xingjianluo.zerotrustratelimiterjava.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.zerotrustratelimiterjava.core.RateLimitService;
import com.github.xingjianluo.zerotrustratelimiterjava.model.RateLimitDecision;
import com.github.xingjianluo.zerotrustratelimiterjava.model.RequestEntry;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Enforces {@link RateLimitService} decisions on entry requests.
 *
 * <p>Status mapping: 429 RATE_LIMITED/BANNED semantics are split via the
 * {@code X-RateLimit-Reason} header; 503 for fail-closed; fail-open requests pass with
 * {@code X-RateLimit-Degraded: 1}. Admin paths are not rate limited.
 */
public class RateLimitFilter implements Filter {

    private static final List<String> SKIP_PREFIXES = List.of(
            "/admin", "/actuator", "/error");

    private final RateLimitService service;
    private final ObjectMapper mapper = new ObjectMapper();

    public RateLimitFilter(RateLimitService service) {
        this.service = service;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        String path = http.getRequestURI();

        if (SKIP_PREFIXES.stream().anyMatch(path::startsWith)) {
            chain.doFilter(request, response);
            return;
        }

        String requestId = resolveRequestId(http);
        MDC.put("requestId", requestId);
        try {
            RequestEntry entry = new RequestEntry(
                    requestId,
                    resolveClientIp(http),
                    path,
                    http.getMethod(),
                    http.getHeader("X-User-Id"),
                    http.getHeader("Authorization"));

            RateLimitDecision decision = service.decide(entry);
            decorateHeaders(httpResponse, decision);

            if (decision.allowed()) {
                chain.doFilter(request, response);
            } else {
                writeRejection(httpResponse, decision);
            }
        } finally {
            MDC.remove("requestId");
        }
    }

    private String resolveRequestId(HttpServletRequest http) {
        String id = http.getHeader("X-Request-Id");
        return (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id;
    }

    private String resolveClientIp(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return http.getRemoteAddr();
    }

    private void decorateHeaders(HttpServletResponse response, RateLimitDecision d) {
        response.setHeader("X-Request-Id", d.requestId());
        response.setHeader("X-RateLimit-Policy", d.policyVersion());
        response.setHeader("X-RateLimit-Reason", d.reasonCode());
        if (d.degraded()) {
            response.setHeader("X-RateLimit-Degraded", "1");
        }
        if (d.banUntilMillis() > 0) {
            response.setHeader("X-Ban-Until", String.valueOf(d.banUntilMillis()));
            response.setHeader("X-Ban-Level", String.valueOf(d.banLevel()));
        }
        if (d.limit() > 0) {
            response.setHeader("X-RateLimit-Limit", String.valueOf(d.limit()));
            response.setHeader("X-RateLimit-Window-Start",
                    String.valueOf(d.windowStartMillis()));
        }
    }

    private void writeRejection(HttpServletResponse response, RateLimitDecision d)
            throws IOException {
        int status = switch (d.reasonCode()) {
            case "RATE_LIMITED", "BANNED" -> 429;
            default -> 503; // FAIL_CLOSED_UNAVAILABLE / FAIL_CLOSED_TIMEOUT
        };
        response.setStatus(status);
        response.setContentType("application/json");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", d.requestId());
        body.put("allowed", false);
        body.put("reasonCode", d.reasonCode());
        body.put("reasonDetail", d.reasonDetail());
        body.put("policyVersion", d.policyVersion());
        body.put("ruleName", d.ruleName());
        body.put("windowStartMillis", d.windowStartMillis());
        body.put("observedCount", d.observedCount());
        body.put("limit", d.limit());
        body.put("source", d.source());
        body.put("banLevel", d.banLevel());
        body.put("banUntilMillis", d.banUntilMillis());
        mapper.writeValue(response.getOutputStream(), body);
    }
}
