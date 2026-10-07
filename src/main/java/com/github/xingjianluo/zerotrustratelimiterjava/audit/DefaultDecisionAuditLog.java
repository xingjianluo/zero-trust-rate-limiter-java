package com.github.xingjianluo.zerotrustratelimiterjava.audit;

import com.github.xingjianluo.zerotrustratelimiterjava.core.Clock;
import com.github.xingjianluo.zerotrustratelimiterjava.model.Decision;
import com.github.xingjianluo.zerotrustratelimiterjava.model.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 默认审计日志：结构化输出到 SLF4J（请求标识、判定输入、最终结果），
 * 并在有界双端队列中保留最近 {@value #CAPACITY} 条供测试与管理端断言。
 * 仅追加，不修改历史记录。
 */
@Component
public class DefaultDecisionAuditLog implements DecisionAuditLog {

    private static final Logger log = LoggerFactory.getLogger(DefaultDecisionAuditLog.class);
    private static final int CAPACITY = 2048;

    private final Clock clock;
    private final ConcurrentLinkedDeque<Entry> entries = new ConcurrentLinkedDeque<>();

    public DefaultDecisionAuditLog(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void append(RequestContext request, Decision decision) {
        Entry entry = new Entry(clock.nowMillis(), request, decision);
        entries.addLast(entry);
        while (entries.size() > CAPACITY) {
            entries.pollFirst();
        }
        log.info("rate-limit decision requestId={} input=[ip={}, user={}, method={}, path={}] "
                        + "result=[allowed={}, reason={}, detail={}, policyVersion={}, windowStart={}, "
                        + "currentCount={}, limitedBy={}, layerName={}, layerLimit={}, remaining={}, "
                        + "weight={}, replay={}, banLevel={}, banUntil={}, degraded={}, "
                        + "degradedCause={}, retryAfterMillis={}]",
                request.requestId(), request.ip(), request.user(), request.method(), request.path(),
                decision.allowed(), decision.reason(), decision.detail(), decision.policyVersion(),
                decision.windowStart(), decision.currentCount(), decision.limitedBy(),
                decision.layerName(), decision.layerLimit(), decision.remaining(), decision.weight(),
                decision.replay(), decision.banLevel(), decision.banUntil(), decision.degraded(),
                decision.degradedCause(), decision.retriedAfterMillis());
    }

    @Override
    public List<Entry> recent(int max) {
        List<Entry> snapshot = new ArrayList<>(entries);
        if (snapshot.size() <= max) {
            return snapshot;
        }
        return snapshot.subList(snapshot.size() - max, snapshot.size());
    }
}
