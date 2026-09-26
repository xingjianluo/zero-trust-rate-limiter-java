package com.github.xingjianluo.zerotrustratelimiterjava.core;

import com.github.xingjianluo.zerotrustratelimiterjava.model.RateLimitDecision;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Bounded ring of recent decisions for in-process explainability and replay.
 * Newest first when read.
 */
public class DecisionLog {

    private final int capacity;
    private final Deque<RateLimitDecision> decisions = new ArrayDeque<>();
    private final Object lock = new Object();

    public DecisionLog(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public void append(RateLimitDecision decision) {
        synchronized (lock) {
            decisions.addFirst(decision);
            while (decisions.size() > capacity) {
                decisions.removeLast();
            }
        }
    }

    public List<RateLimitDecision> recent(int limit) {
        synchronized (lock) {
            List<RateLimitDecision> result = new ArrayList<>();
            for (RateLimitDecision decision : decisions) {
                if (result.size() >= limit) {
                    break;
                }
                result.add(decision);
            }
            return result;
        }
    }
}
