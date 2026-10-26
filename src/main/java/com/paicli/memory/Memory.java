package com.paicli.memory;

import java.util.List;
import java.util.Optional;

/**
 * Memory 接口 - 记忆系统的统一抽象
 *
 * 当前用于长期记忆持久化；当前对话的短期上下文直接由 Agent conversationHistory 维护。
 */
public interface Memory {
    /**
     * 存储一条记忆
     */
    void store(MemoryEntry entry);

    /**
     * 根据ID检索记忆
     */
    Optional<MemoryEntry> retrieve(String id);

    /**
     * 搜索相关记忆
     */
    List<MemoryEntry> search(String query, int limit);

    /**
     * 获取所有记忆
     */
    List<MemoryEntry> getAll();

    /**
     * 删除指定记忆
     */
    boolean delete(String id);

    /**
     * 清空所有记忆
     */
    void clear();

    /**
     * 获取当前记忆的 token 总数
     */
    int getTokenCount();

    /**
     * 获取记忆条数
     */
    int size();
}
