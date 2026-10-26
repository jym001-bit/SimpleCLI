package com.paicli.memory;

import com.paicli.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 实验性的会话记忆压缩路径。
 *
 * <p>它在 conversationHistory 尚未达到自动压缩阈值时，异步维护一份增量结构化摘要；真正达到
 * 阈值后，直接使用已经准备好的摘要并保留最近原始消息。每一份消息列表都有独立状态，避免
 * ReAct、Plan 并行任务和 Team worker 之间互相污染。摘要不可用时，调用方必须回退到完整摘要。</p>
 */
public class SessionMemoryCompactor {
    private static final Logger log = LoggerFactory.getLogger(SessionMemoryCompactor.class);
    private static final double DEFAULT_PRECOMPUTE_RATIO = 0.60;
    private static final int DEFAULT_MIN_RETAIN_TOKENS = 10_000;
    private static final int DEFAULT_MIN_TEXT_MESSAGES = 5;
    private static final int DEFAULT_MAX_RETAIN_TOKENS = 40_000;
    private static final int MIN_INCREMENTAL_UPDATE_TOKENS = 4_000;

    private static final String INITIAL_SUMMARY_PROMPT = """
            请为下面这段 Agent 会话生成一份可继续工作的会话记忆。只记录仍然有用的信息：
            1. 当前目标、用户明确要求与约束
            2. 已完成工作、关键决定及其原因
            3. 涉及的文件、符号、命令和工具结果
            4. 失败尝试、未解决问题和下一步

            不要逐条复述，不要记录寒暄，不要输出元描述。用紧凑的中文 Markdown 输出。

            === 已完成的会话片段 ===
            %s
            === 会话片段结束 ===
            """;

    private static final String UPDATE_SUMMARY_PROMPT = """
            请用新增会话片段更新现有会话记忆。保留仍然有效的旧信息，合并新决定，删除已经过时或
            已被否定的内容。只输出更新后的完整会话记忆，不要输出解释。

            === 现有会话记忆 ===
            %s
            === 新增会话片段 ===
            %s
            === 新增片段结束 ===
            """;

    private static final ExecutorService BACKGROUND_EXECUTOR = Executors.newCachedThreadPool(
            new DaemonThreadFactory());

    private volatile LlmClient llmClient;
    private final boolean enabled;
    private final Executor executor;
    private final Config config;
    private final List<StateSlot> slots = new ArrayList<>();

    public SessionMemoryCompactor(LlmClient llmClient, boolean enabled) {
        this(llmClient, enabled, Config.defaults(), BACKGROUND_EXECUTOR);
    }

    SessionMemoryCompactor(LlmClient llmClient, boolean enabled, Config config, Executor executor) {
        this.llmClient = llmClient;
        this.enabled = enabled;
        this.config = config == null ? Config.defaults() : config;
        this.executor = executor == null ? BACKGROUND_EXECUTOR : executor;
    }

    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
        synchronized (this) {
            for (StateSlot slot : slots) {
                synchronized (slot.state) {
                    slot.state.reset();
                }
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 在阈值前异步生成或增量更新会话记忆，不阻塞主 Agent 调用。 */
    public boolean prepareIfNeeded(List<LlmClient.Message> history, int autoCompactTriggerTokens) {
        if (!enabled || history == null || history.isEmpty() || autoCompactTriggerTokens <= 0) {
            return false;
        }
        int currentTokens = TokenBudget.estimateMessagesTokens(history);
        int precomputeAt = Math.max(1, (int) Math.floor(autoCompactTriggerTokens * config.precomputeRatio()));
        if (currentTokens < precomputeAt || currentTokens >= autoCompactTriggerTokens) {
            return false;
        }

        State state = stateFor(history);
        synchronized (state) {
            if (state.pending != null && !state.pending.isDone()) {
                return false;
            }

            SummarySnapshot previous = state.ready;
            int coveredUntil = ConversationHistoryCompactor.systemEnd(history);
            if (previous != null) {
                coveredUntil = findIdentity(history, previous.firstPreservedMessage());
                if (coveredUntil < 0) {
                    state.reset();
                    previous = null;
                    coveredUntil = ConversationHistoryCompactor.systemEnd(history);
                }
            }

            int preserveFrom = calculatePreserveFrom(history);
            if (preserveFrom <= coveredUntil) {
                return false;
            }

            // Skill 正文压缩后由模型按需重新加载，runaway 提醒只对当次任务有效，二者都不进入会话记忆
            List<LlmClient.Message> delta = history.subList(coveredUntil, preserveFrom).stream()
                    .filter(message -> !InternalUserMessages.isInternal(message))
                    .toList();
            if (delta.isEmpty()) {
                return false;
            }
            if (previous != null
                    && TokenBudget.estimateMessagesTokens(delta) < MIN_INCREMENTAL_UPDATE_TOKENS) {
                return false;
            }
            LlmClient.Message firstPreserved = history.get(preserveFrom);
            String previousSummary = previous == null ? null : previous.summary();
            long generation = ++state.generation;

            CompletableFuture<SummarySnapshot> future = CompletableFuture.supplyAsync(() -> {
                try {
                    String summary = summarizeIncrement(previousSummary, delta);
                    if (summary == null || summary.isBlank()) {
                        return null;
                    }
                    return new SummarySnapshot(summary.trim(), firstPreserved);
                } catch (IOException e) {
                    log.warn("session memory precompute failed; full compaction will remain available", e);
                    return null;
                }
            }, executor);
            state.pending = future;
            log.info("session memory summary scheduled: incremental={}, deltaMessages={}, historyTokens={}",
                    previousSummary != null, delta.size(), currentTokens);
            future.whenComplete((snapshot, error) -> {
                synchronized (state) {
                    if (state.generation != generation) {
                        return;
                    }
                    state.pending = null;
                    if (error == null && snapshot != null) {
                        state.ready = snapshot;
                        log.info("session memory summary ready: chars={}, historyTokens={}",
                                snapshot.summary().length(), currentTokens);
                    }
                }
            });
            return true;
        }
    }

    /**
     * 尝试用已准备好的会话记忆重建真实 history。若结果仍超阈值或状态失效则返回 false。
     */
    public boolean compactIfReady(List<LlmClient.Message> history, int autoCompactTriggerTokens) {
        if (!enabled || history == null || history.isEmpty()) return false;
        State state = existingState(history);
        if (state == null) return false;

        SummarySnapshot snapshot;
        synchronized (state) {
            snapshot = state.ready;
        }
        if (snapshot == null) return false;

        int preserveFrom = findIdentity(history, snapshot.firstPreservedMessage());
        if (preserveFrom < 0) {
            clear(history);
            return false;
        }

        int beforeTokens = TokenBudget.estimateMessagesTokens(history);
        List<LlmClient.Message> rebuilt = ConversationHistoryCompactor.rebuildWithSummary(
                history,
                preserveFrom,
                "[会话记忆摘要]\n" + snapshot.summary());
        int afterTokens = TokenBudget.estimateMessagesTokens(rebuilt);
        if (afterTokens >= beforeTokens || afterTokens >= autoCompactTriggerTokens) {
            log.info("session memory compact skipped: tokens {} -> {}, trigger={}",
                    beforeTokens, afterTokens, autoCompactTriggerTokens);
            return false;
        }

        history.clear();
        history.addAll(rebuilt);
        clear(history);
        log.info("session memory compacted conversationHistory: tokens {} -> {}, messages={}",
                beforeTokens, afterTokens, history.size());
        return true;
    }

    public void clear(List<LlmClient.Message> history) {
        State state = existingState(history);
        if (state == null) return;
        synchronized (state) {
            state.reset();
        }
    }

    protected String summarizeIncrement(String previousSummary, List<LlmClient.Message> messages) throws IOException {
        LlmClient client = llmClient;
        if (client == null) throw new IOException("LLM client not configured");
        String serialized = serialize(messages);
        String prompt = previousSummary == null || previousSummary.isBlank()
                ? String.format(INITIAL_SUMMARY_PROMPT, serialized)
                : String.format(UPDATE_SUMMARY_PROMPT, previousSummary, serialized);
        LlmClient.ChatResponse response = client.chat(List.of(
                LlmClient.Message.system("你是会话记忆整理器，只输出会话记忆本身，不调用工具。"),
                LlmClient.Message.user(prompt)
        ), null);
        return response == null ? null : response.content();
    }

    private int calculatePreserveFrom(List<LlmClient.Message> history) {
        int systemEnd = ConversationHistoryCompactor.systemEnd(history);
        List<Integer> users = ConversationHistoryCompactor.userIndices(history, systemEnd);
        if (users.size() <= 1) return systemEnd;

        int chosen = users.get(users.size() - 1);
        for (int i = users.size() - 1; i >= 0; i--) {
            int candidate = users.get(i);
            List<LlmClient.Message> tail = history.subList(candidate, history.size());
            int tailTokens = TokenBudget.estimateMessagesTokens(tail);
            if (tailTokens > config.maxRetainTokens()) {
                break;
            }
            chosen = candidate;
            if (tailTokens >= config.minRetainTokens()
                    && countTextMessages(tail) >= config.minTextMessages()) {
                break;
            }
        }
        return Math.max(systemEnd, chosen);
    }

    private static int countTextMessages(List<LlmClient.Message> messages) {
        int count = 0;
        for (LlmClient.Message message : messages) {
            if (("user".equals(message.role()) || "assistant".equals(message.role()))
                    && !InternalUserMessages.isInternal(message)
                    && message.content() != null && !message.content().isBlank()) {
                count++;
            }
        }
        return count;
    }

    private static int findIdentity(List<LlmClient.Message> history, LlmClient.Message target) {
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i) == target) return i;
        }
        return -1;
    }

    private static String serialize(List<LlmClient.Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (LlmClient.Message message : messages) {
            sb.append(message.role().toUpperCase(Locale.ROOT)).append(": ");
            if (message.content() != null) sb.append(message.content());
            if (message.toolCalls() != null) {
                for (LlmClient.ToolCall call : message.toolCalls()) {
                    sb.append("\nTOOL_CALL ").append(call.function().name())
                            .append(": ").append(call.function().arguments());
                }
            }
            sb.append("\n\n");
        }
        return sb.toString();
    }

    private synchronized State stateFor(List<LlmClient.Message> history) {
        cleanupSlots();
        for (StateSlot slot : slots) {
            if (slot.history.get() == history) return slot.state;
        }
        State state = new State();
        slots.add(new StateSlot(new WeakReference<>(history), state));
        return state;
    }

    private synchronized State existingState(List<LlmClient.Message> history) {
        cleanupSlots();
        for (StateSlot slot : slots) {
            if (slot.history.get() == history) return slot.state;
        }
        return null;
    }

    private void cleanupSlots() {
        slots.removeIf(slot -> slot.history.get() == null);
    }

    record Config(double precomputeRatio, int minRetainTokens, int minTextMessages, int maxRetainTokens) {
        Config {
            if (!(precomputeRatio > 0 && precomputeRatio < 1)) {
                throw new IllegalArgumentException("precomputeRatio must be between 0 and 1");
            }
            minRetainTokens = Math.max(1, minRetainTokens);
            minTextMessages = Math.max(1, minTextMessages);
            maxRetainTokens = Math.max(minRetainTokens, maxRetainTokens);
        }

        static Config defaults() {
            return new Config(DEFAULT_PRECOMPUTE_RATIO, DEFAULT_MIN_RETAIN_TOKENS,
                    DEFAULT_MIN_TEXT_MESSAGES, DEFAULT_MAX_RETAIN_TOKENS);
        }
    }

    private record SummarySnapshot(String summary, LlmClient.Message firstPreservedMessage) {
    }

    private static final class State {
        private long generation;
        private SummarySnapshot ready;
        private CompletableFuture<SummarySnapshot> pending;

        private void reset() {
            generation++;
            if (pending != null) pending.cancel(true);
            pending = null;
            ready = null;
        }
    }

    private record StateSlot(WeakReference<List<LlmClient.Message>> history, State state) {
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "paicli-session-memory-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
