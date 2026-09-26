package com.github.xingjianluo.zerotrustratelimiterjava.store;

import com.github.xingjianluo.zerotrustratelimiterjava.model.BanRecord;

import java.util.List;
import java.util.Optional;

/**
 * 封禁状态存储。实现需保证读取-修改-写回的原子性，并在写后持久化。
 */
public interface BanStateStore {

    /** 原子地读取并更新单个来源的封禁记录（不存在时以空记录传入），返回更新后的记录。 */
    BanRecord compute(String key, java.util.function.UnaryOperator<BanRecord> mutation);

    /** 查询单个来源记录。 */
    Optional<BanRecord> find(String key);

    /** 列出全部记录（管理端用）。 */
    List<BanRecord> findAll();
}
