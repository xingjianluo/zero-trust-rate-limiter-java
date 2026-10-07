package com.github.xingjianluo.zerotrustratelimiterjava.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.zerotrustratelimiterjava.core.RateLimitService;
import com.github.xingjianluo.zerotrustratelimiterjava.model.Decision;
import com.github.xingjianluo.zerotrustratelimiterjava.model.DecisionReason;
import com.github.xingjianluo.zerotrustratelimiterjava.model.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 入口限流过滤器：构造判定输入（含可复现的请求标识），执行统一判定，
 * 并按原因写出可区分的响应：
 * <ul>
 *   <li>ALLOWED / FAIL_OPEN：放行；FAIL_OPEN 附带降级响应头（不静默放行）；</li>
 *   <li>RATE_LIMITED：429 + Retry-After；</li>
 *   <li>BANNED：403；</li>
 *   <li>FAIL_CLOSED：503 + Retry-After。</li>
 * </ul>
 * 管理端点 /admin/** 不经过本过滤器，保证人工解除随时可用。
 */
@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String ADMIN_PREFIX = "/admin";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String USER_HEADER = "X-User-Id";
    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RateLimitFilter(RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith(ADMIN_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        RequestContext context = new RequestContext(
                requestId,
                resolveClientIp(request),
                request.getHeader(USER_HEADER),
                request.getRequestURI(),
                request.getMethod());

        Decision decision = rateLimitService.decide(context);
        response.setHeader("X-RateLimit-Decision", decision.reason().name());
        response.setHeader("X-RateLimit-Policy-Version", Long.toString(decision.policyVersion()));
        if (decision.replay()) {
            response.setHeader("X-RateLimit-Replay", "true");
        }
        if (decision.layerName() != null) {
            response.setHeader("X-RateLimit-Layer", decision.layerName());
        }

        switch (decision.reason()) {
            case ALLOWED -> filterChain.doFilter(request, response);
            case FAIL_OPEN -> {
                // 失败开放：放行但显式标记降级，绝不静默
                response.setHeader("X-RateLimit-Degraded", "true");
                response.setHeader("X-RateLimit-Degraded-Cause", String.valueOf(decision.degradedCause()));
                filterChain.doFilter(request, response);
            }
            case RATE_LIMITED -> writeRejection(response, decision, 429);
            case BANNED -> writeRejection(response, decision, HttpServletResponse.SC_FORBIDDEN);
            case FAIL_CLOSED -> writeRejection(response, decision,
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        }
    }

    private void writeRejection(HttpServletResponse response, Decision decision, int status) throws IOException {
        if (decision.retriedAfterMillis() >= 0) {
            long seconds = Math.max(1L, (decision.retriedAfterMillis() + 999L) / 1000L);
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
        }
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", decision.requestId());
        body.put("allowed", decision.allowed());
        body.put("reason", decision.reason().name());
        body.put("detail", decision.detail());
        body.put("policyVersion", decision.policyVersion());
        body.put("degraded", decision.degraded());
        body.put("degradedCause", decision.degradedCause());
        body.put("retryAfterMillis", decision.retriedAfterMillis());
        if (decision.limitedBy() != null) {
            body.put("limitedBy", decision.limitedBy().name());
        }
        if (decision.layerName() != null) {
            body.put("layerName", decision.layerName());
            body.put("layerLimit", decision.layerLimit());
            body.put("used", decision.currentCount());
            body.put("remaining", decision.remaining());
        }
        if (decision.weight() >= 0) {
            body.put("weight", decision.weight());
        }
        body.put("replay", decision.replay());
        if (decision.banLevel() > 0) {
            body.put("banLevel", decision.banLevel());
            body.put("banUntil", decision.banUntil());
        }
        objectMapper.writeValue(response.getWriter(), body);
    }

    private String resolveRequestId(HttpServletRequest request) {
        String header = request.getHeader(REQUEST_ID_HEADER);
        if (header != null && !header.isBlank()) {
            return header;
        }
        return UUID.randomUUID().toString();
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader(FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
