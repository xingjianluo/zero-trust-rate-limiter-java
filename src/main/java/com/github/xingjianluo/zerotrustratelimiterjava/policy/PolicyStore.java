package com.github.xingjianluo.zerotrustratelimiterjava.policy;

import com.github.xingjianluo.zerotrustratelimiterjava.model.RateLimitPolicy;
import java.util.List;

/** Persistent versioned policy storage. */
public interface PolicyStore {

    void save(RateLimitPolicy policy);

    /** All policy versions, ordered by creation time (oldest first). */
    List<RateLimitPolicy> loadAll();
}
