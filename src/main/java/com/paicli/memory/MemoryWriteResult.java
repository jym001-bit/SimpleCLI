package com.paicli.memory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 一次长期记忆写入的结果。
 *
 * <p>冲突时不会写入任何内容，{@link #describe()} 会把已有条目和新内容一起列出来，
 * 并给出三种处理方式，由用户决定保留哪一条。</p>
 */
public record MemoryWriteResult(Status status,
                                MemoryEntry entry,
                                List<MemoryEntry> conflicts,
                                MemoryEntry replaced,
                                String scope,
                                String requestedReplaceId) {

    public enum Status {
        /** 新条目已写入 */
        STORED,
        /** 已按用户选择用新条目替换旧条目 */
        REPLACED,
        /** 已存在等价条目，只刷新了它的核实时间 */
        DUPLICATE,
        /** 与已有条目高度相似但不相同，未写入，等待用户决定 */
        CONFLICT,
        /** replace 目标不存在或不在当前项目可见范围内，未写入 */
        REPLACE_TARGET_NOT_FOUND
    }

    public MemoryWriteResult {
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
    }

    static MemoryWriteResult stored(MemoryEntry entry, String scope) {
        return new MemoryWriteResult(Status.STORED, entry, List.of(), null, scope, null);
    }

    static MemoryWriteResult replaced(MemoryEntry entry, MemoryEntry replaced, String scope) {
        return new MemoryWriteResult(Status.REPLACED, entry, List.of(), replaced, scope, replaced.getId());
    }

    static MemoryWriteResult duplicate(MemoryEntry existing, String scope) {
        return new MemoryWriteResult(Status.DUPLICATE, existing, List.of(), null, scope, null);
    }

    static MemoryWriteResult conflict(MemoryEntry incoming, List<MemoryEntry> conflicts, String scope) {
        return new MemoryWriteResult(Status.CONFLICT, incoming, conflicts, null, scope, null);
    }

    static MemoryWriteResult replaceTargetNotFound(MemoryEntry incoming, String replaceId, String scope) {
        return new MemoryWriteResult(Status.REPLACE_TARGET_NOT_FOUND, incoming, List.of(), null, scope, replaceId);
    }

    public boolean written() {
        return status == Status.STORED || status == Status.REPLACED;
    }

    /** 给 CLI、TUI 和 save_memory 工具共用的用户可读说明。 */
    public String describe() {
        String content = entry == null ? "" : entry.getContent();
        return switch (status) {
            case STORED -> "💾 已保存到长期记忆(" + scope + "): " + content;
            case REPLACED -> "💾 已用新内容替换长期记忆 " + replaced.getId() + "(" + scope + ")\n"
                    + "  旧: " + replaced.getContent() + "\n"
                    + "  新: " + content + " (" + entry.getId() + ")";
            case DUPLICATE -> "💾 长期记忆中已有等价条目 " + entry.getId()
                    + "，未重复写入，已刷新其核实时间: " + content;
            case REPLACE_TARGET_NOT_FOUND -> "❌ 未写入：要替换的长期记忆 " + requestedReplaceId
                    + " 不存在或不在当前项目可见范围内。";
            case CONFLICT -> describeConflict(content);
        };
    }

    private String describeConflict(String incoming) {
        StringBuilder sb = new StringBuilder("⚠️ 未写入：新内容与已有长期记忆高度相似但不一致，可能是同一事实的新旧版本。\n");
        for (MemoryEntry existing : conflicts) {
            sb.append("  已有 ").append(existing.getId())
                    .append("（写入 ").append(date(existing.getTimestamp()))
                    .append("，最后核实 ").append(date(existing.getLastVerifiedAt())).append("）: ")
                    .append(existing.getContent()).append("\n");
        }
        sb.append("  新的: ").append(incoming).append("\n");
        MemoryEntry first = conflicts.isEmpty() ? null : conflicts.get(0);
        String firstId = first == null ? "<id>" : first.getId();
        String scopeFlag = "global".equals(scope) ? "--global " : "";
        sb.append("请让用户选择保留哪一条：\n")
                .append("  - 保留已有：无需操作\n")
                .append("  - 改用新的：/memory replace ").append(firstId).append(" ").append(incoming)
                .append("（或调用 save_memory 并传 replace_id=").append(firstId).append("）\n")
                .append("  - 两条都保留：/save --force ").append(scopeFlag).append(incoming);
        return sb.toString();
    }

    private static LocalDate date(Instant instant) {
        return LocalDate.ofInstant(instant, ZoneId.systemDefault());
    }
}
