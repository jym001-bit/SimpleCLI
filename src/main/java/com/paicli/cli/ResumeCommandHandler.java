package com.paicli.cli;

import com.paicli.agent.Agent;
import com.paicli.history.ConversationLedger;
import com.paicli.history.SessionResume;
import com.paicli.llm.LlmClient;
import com.paicli.util.TerminalTable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * /resume 命令：不带参数列出当前项目可以恢复的会话，带序号或会话 ID 时恢复那次对话。
 * Main.java 只负责分发和打印。
 */
final class ResumeCommandHandler {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final int QUESTION_PREVIEW = 40;
    private static final int ANSWER_PREVIEW = 120;

    record Result(String output, boolean restored) {
        static Result message(String output) {
            return new Result(output, false);
        }
    }

    private ResumeCommandHandler() {
    }

    static Result handle(Agent agent, String selector) {
        ConversationLedger ledger = agent.getConversationLedger();
        Path directory = sessionDirectory(ledger);
        String projectPath = agent.getToolRegistry().getProjectPath();
        try {
            if (selector == null || selector.isBlank()) {
                return Result.message(list(SessionResume.listSessions(
                        directory, projectPath, ledger.sessionId(), SessionResume.LIST_LIMIT)));
            }
            Optional<SessionResume.RestoredConversation> restored =
                    SessionResume.load(directory, projectPath, ledger.sessionId(), selector);
            if (restored.isEmpty()) {
                return Result.message("❌ 找不到序号或 ID 为 " + selector.trim() + " 的会话\n"
                        + "   先输入 /resume 查看可以恢复的会话\n");
            }
            boolean replacedCurrent = agent.getConversationHistory().size() > 1;
            agent.resumeConversation(restored.get().sessionId(), restored.get().messages());
            return new Result(restoredMessage(restored.get(), replacedCurrent), true);
        } catch (IOException | RuntimeException e) {
            return Result.message("❌ 读取会话记录失败: " + e.getMessage() + "\n");
        }
    }

    static String list(List<SessionResume.SessionSummary> sessions) {
        if (sessions.isEmpty()) {
            return "📭 当前项目没有可以恢复的会话\n"
                    + "   只有升级到支持 /resume 的版本之后开始、并且至少得到过一次完整回答的会话才能恢复\n";
        }
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < sessions.size(); i++) {
            SessionResume.SessionSummary session = sessions.get(i);
            rows.add(List.of(
                    String.valueOf(i + 1),
                    formatTime(session.lastActiveMillis()),
                    String.valueOf(session.messageCount()),
                    preview(session.lastQuestion(), QUESTION_PREVIEW)));
        }
        return "可以恢复的会话（当前项目，按最后使用时间排序）：\n"
                + TerminalTable.render(List.of("序号", "最后使用", "消息数", "最后一个问题"), rows, TerminalTable.columns())
                + "\n输入 /resume <序号> 恢复，例如 /resume 1；恢复后会替换当前对话\n";
    }

    static String restoredMessage(SessionResume.RestoredConversation restored, boolean replacedCurrent) {
        StringBuilder out = new StringBuilder();
        out.append("✅ 已恢复 ").append(formatTime(restored.lastActiveMillis()))
                .append(" 的会话（").append(restored.messages().size()).append(" 条消息），可以接着提问\n");
        String question = preview(restored.lastQuestion(), ANSWER_PREVIEW);
        String answer = preview(lastAnswer(restored.messages()), ANSWER_PREVIEW);
        if (restored.droppedTrailingMessages() > 0) {
            out.append("   最后一个问题：").append(question).append("\n");
            out.append("   ⚠ 这一轮当时没有完成（中途退出或取消），没有恢复\n");
            out.append("   最近一次完整回答：").append(answer).append("\n");
        } else {
            out.append("   最后一个问题：").append(question).append("\n");
            out.append("   回答：").append(answer).append("\n");
        }
        if (replacedCurrent) {
            out.append("   恢复前的对话也已保存，之后可以用 /resume 找回\n");
        }
        out.append("   提示：项目文件在那之后可能改过，需要时让模型重新读取\n");
        return out.toString();
    }

    private static Path sessionDirectory(ConversationLedger ledger) {
        if (ledger.isEnabled() && ledger.file() != null) {
            return ledger.file().getParent();
        }
        return Path.of(System.getProperty("user.home"), ".paicli", "history", "raw");
    }

    private static String lastAnswer(List<LlmClient.Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            LlmClient.Message message = messages.get(i);
            if ("assistant".equals(message.role())) {
                return message.content();
            }
        }
        return "";
    }

    private static String formatTime(long millis) {
        return TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    static String preview(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "（无）";
        }
        String oneLine = text.strip().replaceAll("\\s+", " ");
        if (oneLine.codePointCount(0, oneLine.length()) <= maxChars) {
            return oneLine;
        }
        return oneLine.substring(0, oneLine.offsetByCodePoints(0, maxChars)) + "…";
    }
}
