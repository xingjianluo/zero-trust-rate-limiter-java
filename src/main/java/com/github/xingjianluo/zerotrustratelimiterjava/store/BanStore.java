package com.github.xingjianluo.zerotrustratelimiterjava.store;

import com.github.xingjianluo.zerotrustratelimiterjava.model.BanState;

/** Persistent ban-state storage; survives process restarts via a local file snapshot. */
public interface BanStore {

    BanState get(String source);

    /** Insert or update the state for a source, atomically persisted. */
    void put(BanState state);

    /** Remove all state for a source (manual unban). */
    void remove(String source);

    java.util.Map<String, BanState> snapshot();
}
