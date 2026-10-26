package com.paicli.agent;

import com.paicli.tool.ToolRegistry.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunawayGuardTest {

    @Test
    void remindsOnceWhenSameActionRepeatsThreeStepsInARow() {
        RunawayGuard guard = new RunawayGuard();

        assertTrue(guard.observe(List.of(ok("list_dir", "{\"path\":\".\"}"))).isEmpty());
        assertTrue(guard.observe(List.of(ok("list_dir", "{\"path\":\".\"}"))).isEmpty());
        Optional<String> reminder = guard.observe(List.of(ok("list_dir", "{\"path\":\".\"}")));

        assertTrue(reminder.isPresent());
        assertTrue(reminder.get().startsWith("[runaway guard] 同一个工具调用"), reminder.get());
        assertTrue(reminder.get().contains("不要保存它"), "提醒必须声明只对本次任务有效");
        assertTrue(guard.observe(List.of(ok("list_dir", "{\"path\":\".\"}"))).isEmpty(),
                "每次任务最多提醒一次");
    }

    @Test
    void argumentKeyOrderDoesNotMakeADifferentAction() {
        RunawayGuard guard = new RunawayGuard();

        guard.observe(List.of(ok("read_file", "{\"path\":\"a.txt\",\"limit\":10}")));
        guard.observe(List.of(ok("read_file", "{\"limit\":10,\"path\":\"a.txt\"}")));

        assertTrue(guard.observe(List.of(ok("read_file", "{ \"path\": \"a.txt\", \"limit\": 10 }"))).isPresent());
    }

    @Test
    void differentArgumentsOrAStepWithoutTheActionResetTheStreak() {
        RunawayGuard guard = new RunawayGuard();

        guard.observe(List.of(ok("read_file", "{\"path\":\"a.txt\"}")));
        guard.observe(List.of(ok("read_file", "{\"path\":\"a.txt\"}")));
        guard.observe(List.of(ok("read_file", "{\"path\":\"b.txt\"}")));
        guard.observe(List.of(ok("read_file", "{\"path\":\"a.txt\"}")));
        guard.observe(List.of(ok("read_file", "{\"path\":\"a.txt\"}")));

        assertFalse(guard.reminderAttempted(), "中间换了参数，连续计数应该归零");
    }

    @Test
    void identicalCallsInOneBatchAddUp() {
        RunawayGuard guard = new RunawayGuard();

        Optional<String> reminder = guard.observe(List.of(
                ok("grep_code", "{\"query\":\"TODO\"}"),
                ok("grep_code", "{\"query\":\"TODO\"}"),
                ok("grep_code", "{\"query\":\"TODO\"}")));

        assertTrue(reminder.isPresent());
    }

    @Test
    void otherCallsInTheSameBatchDoNotBreakTheStreak() {
        RunawayGuard guard = new RunawayGuard();

        guard.observe(List.of(ok("list_dir", "{\"path\":\".\"}"), ok("read_file", "{\"path\":\"1\"}")));
        guard.observe(List.of(ok("list_dir", "{\"path\":\".\"}"), ok("read_file", "{\"path\":\"2\"}")));

        assertTrue(guard.observe(List.of(ok("list_dir", "{\"path\":\".\"}"))).isPresent());
    }

    @Test
    void sameErrorFamilyTakesPriorityOverActionRepeat() {
        RunawayGuard guard = new RunawayGuard();

        guard.observe(List.of(failed("web_fetch", "{\"url\":\"https://a.test\"}", "抓取失败: 连接超时")));
        guard.observe(List.of(failed("web_fetch", "{\"url\":\"https://a.test\"}", "抓取失败: read timed out")));
        Optional<String> reminder = guard.observe(List.of(
                failed("web_fetch", "{\"url\":\"https://a.test\"}", "抓取失败: 请求超时")));

        assertTrue(reminder.isPresent());
        assertTrue(reminder.get().startsWith("[runaway guard] 同一类工具错误"), reminder.get());
    }

    @Test
    void blockedCallsAreNotCountedAndBreakTheStreak() {
        RunawayGuard guard = new RunawayGuard();

        guard.observe(List.of(ok("execute_command", "{\"command\":\"ls\"}")));
        guard.observe(List.of(ok("execute_command", "{\"command\":\"ls\"}")));
        guard.observe(List.of(failed("execute_command", "{\"command\":\"ls\"}", "[HITL] 操作已被拒绝：用户拒绝")));
        guard.observe(List.of(ok("execute_command", "{\"command\":\"ls\"}")));
        guard.observe(List.of(ok("execute_command", "{\"command\":\"ls\"}")));

        assertFalse(guard.reminderAttempted());
        assertTrue(guard.observe(List.of(ok("execute_command", "{\"command\":\"ls\"}"))).isPresent());
    }

    @Test
    void errorFamilyGroupsByCategoryThenFallsBackToText() {
        assertEquals(RunawayGuard.errorFamily("Connection timed out"),
                RunawayGuard.errorFamily("请求超时，已取消"));
        assertEquals(Optional.of("category:not_found"), RunawayGuard.errorFamily("old_text 在文件中不存在: a.txt"));
        assertEquals(Optional.of("text:boom   x".replaceAll("\\s+", " ")), RunawayGuard.errorFamily("  BOOM \n x "));
        assertTrue(RunawayGuard.errorFamily("   ").isEmpty());
    }

    @Test
    void thresholdBelowThreeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RunawayGuard(2));
    }

    private static ToolExecutionResult ok(String name, String args) {
        return new ToolExecutionResult("id-" + name, name, args, "结果", 1, false, List.of(), true, List.of());
    }

    private static ToolExecutionResult failed(String name, String args, String text) {
        return new ToolExecutionResult("id-" + name, name, args, text, 1, false, List.of(), false, List.of());
    }
}
