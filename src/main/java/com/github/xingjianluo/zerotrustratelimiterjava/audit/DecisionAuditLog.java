package com.github.xingjianluo.zerotrustratelimiterjava.audit;

import com.github.xingjianluo.zerotrustratelimiterjava.model.Decision;
import com.github.xingjianluo.zerotrustratelimiterjava.model.RequestContext;

import java.util.List;

/**
 * 判定审计日志：同时写入应用日志（含请求标识、判定输入、最终结果）与内存环形缓冲（供测试/管理端断言）。
 * 历史判定只追加、不修改，保证可复现与可解释。
 */
public interface DecisionAuditLog {

    /** 追加一条判定记录。 */
    void append(RequestContext request, Decision decision);

    /** 读取最近的判定记录（最多 max 条，按时间升序）。 */
    List<Entry> recent(int max);

    /** 一条审计记录。 */
    record Entry(long timestampMillis, RequestContext request, Decision decision) {
    }
}
