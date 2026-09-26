package com.github.xingjianluo.zerotrustratelimiterjava.web;

import com.github.xingjianluo.zerotrustratelimiterjava.core.DecisionLog;
import com.github.xingjianluo.zerotrustratelimiterjava.core.DecisionMetrics;
import com.github.xingjianluo.zerotrustratelimiterjava.core.RateLimitService;
import com.github.xingjianluo.zerotrustratelimiterjava.core.TimeSource;
import com.github.xingjianluo.zerotrustratelimiterjava.core.Windows;
import com.github.xingjianluo.zerotrustratelimiterjava.model.RateLimitPolicy;
import com.github.xingjianluo.zerotrustratelimiterjava.policy.PolicyManager;
import com.github.xingjianluo.zerotrustratelimiterjava.store.BanStore;
import com.github.xingjianluo.zerotrustratelimiterjava.store.FaultState;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Local-only admin endpoints for hot policy updates, manual unban, fault injection and
 * observability. These paths are exempt from rate limiting by {@link RateLimitFilter}.
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final PolicyManager policyManager;
    private final RateLimitService rateLimitService;
    private final BanStore banStore;
    private final FaultState faultState;
    private final DecisionMetrics metrics;
    private final DecisionLog decisionLog;
    private final TimeSource timeSource;

    public AdminController(PolicyManager policyManager, RateLimitService rateLimitService,
                           BanStore banStore, FaultState faultState,
                           DecisionMetrics metrics, DecisionLog decisionLog,
                           TimeSource timeSource) {
        this.policyManager = policyManager;
        this.rateLimitService = rateLimitService;
        this.banStore = banStore;
        this.faultState = faultState;
        this.metrics = metrics;
        this.decisionLog = decisionLog;
        this.timeSource = timeSource;
    }

    /** Publish a new policy version. By default it becomes effective at the next window
     *  boundary of the first rule so the switch is observable at a deterministic edge. */
    @PostMapping("/policies")
    public Map<String, Object> publish(@RequestBody RateLimitPolicy requested,
                                       @RequestParam(defaultValue = "true")
                                       boolean alignToWindow) {
        long windowSeconds = requested.rules().isEmpty()
                ? 60L : requested.rules().get(0).windowSeconds();
        long now = timeSource.currentTimeMillis();
        long effectiveAt = alignToWindow
                ? Windows.windowStart(now, windowSeconds) + windowSeconds * 1000L
                : requested.effectiveAtMillis() > 0
                    ? requested.effectiveAtMillis() : now;
        RateLimitPolicy policy = new RateLimitPolicy(
                requested.version(), now, effectiveAt,
                requested.rules(), requested.violationsForBan(),
                requested.maxBanLevel(), requested.baseBanSeconds(),
                requested.banMultiplier(), requested.violationRecoverySeconds());
        policyManager.publish(policy);
        return Map.of("published", policy.version(),
                "effectiveAtMillis", effectiveAt,
                "activeAtPublish", policyManager.activeAt(now).version());
    }

    @PostMapping("/policies/reload")
    public Map<String, Object> reload() {
        policyManager.reload();
        RateLimitPolicy active = policyManager.activeAt(timeSource.currentTimeMillis());
        return Map.of("activeVersion", active.version(),
                "versions", policyManager.history().size());
    }

    @GetMapping("/policies")
    public List<RateLimitPolicy> policies() {
        return policyManager.history();
    }

    @PostMapping("/bans/{source}/unban")
    public Map<String, Object> unban(@PathVariable String source) {
        rateLimitService.unban(source);
        return Map.of("unbanned", source, "effective", true);
    }

    @GetMapping("/bans")
    public Map<String, Object> bans() {
        return Map.of("bans", banStore.snapshot(),
                "nowMillis", timeSource.currentTimeMillis());
    }

    /** Inject storage unavailability for degradation testing. */
    @PostMapping("/faults")
    public Map<String, Object> setFault(
            @RequestParam(defaultValue = "false") boolean unavailable,
            @RequestParam(defaultValue = "0") long forcedDelayMillis,
            @RequestParam(defaultValue = "false") boolean reset) {
        if (reset) {
            faultState.reset();
        } else {
            faultState.setUnavailable(unavailable);
            faultState.setForcedDelayMillis(forcedDelayMillis);
        }
        return Map.of("unavailable", faultState.isUnavailable(),
                "forcedDelayMillis", faultState.getForcedDelayMillis());
    }

    @GetMapping("/metrics")
    public Map<String, Long> metrics() {
        return metrics.snapshot();
    }

    @GetMapping("/decisions")
    public Map<String, Object> decisions(@RequestParam(defaultValue = "20") int limit) {
        return Map.of("nowMillis", timeSource.currentTimeMillis(),
                "decisions", decisionLog.recent(limit));
    }
}
