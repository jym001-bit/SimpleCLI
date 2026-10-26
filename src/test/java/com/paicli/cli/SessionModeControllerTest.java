package com.paicli.cli;

import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.hitl.HitlHandler;
import com.paicli.hitl.SwitchableHitlHandler;
import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionModeControllerTest {

    @Test
    void startsInAutoAndCyclesAutoPlanAsk() {
        SessionModeController modes = new SessionModeController(interactiveHandler());

        List<SessionMode> seen = new ArrayList<>();
        seen.add(modes.current());
        for (int i = 0; i < 3; i++) {
            seen.add(modes.cycle());
        }

        assertEquals(List.of(SessionMode.AUTO, SessionMode.PLAN, SessionMode.ASK, SessionMode.AUTO), seen);
    }

    @Test
    void eachModeMapsToApprovalStateAndPlanFlag() {
        SwitchableHitlHandler hitl = interactiveHandler();
        SessionModeController modes = new SessionModeController(hitl);

        modes.apply(SessionMode.PLAN);
        assertTrue(modes.planMode());
        assertFalse(hitl.isEnabled());
        assertTrue(hitl.isHighRiskConfirmationEnabled(), "plan 模式执行阶段仍确认高危操作");

        modes.apply(SessionMode.ASK);
        assertTrue(hitl.isEnabled());

        modes.apply(SessionMode.AUTO);
        assertFalse(hitl.isEnabled());
        assertTrue(hitl.isHighRiskConfirmationEnabled());
    }

    @Test
    void hitlCommandChangesAreReflectedInCurrentMode() {
        SwitchableHitlHandler hitl = interactiveHandler();
        SessionModeController modes = new SessionModeController(hitl);

        hitl.switchConfirmationMode("on");
        assertEquals(SessionMode.ASK, modes.current());

        hitl.switchConfirmationMode("default");
        assertEquals(SessionMode.AUTO, modes.current());
    }

    @Test
    void modeCommandListsSwitchesAndRejectsUnknownModes() {
        SessionModeController modes = new SessionModeController(interactiveHandler());

        assertTrue(modes.handleCommand(null).contains("Shift+Tab"));
        assertTrue(modes.handleCommand("plan").startsWith("已切换到 plan"));
        assertEquals(SessionMode.PLAN, modes.current());
        assertTrue(modes.handleCommand("nope").startsWith("未知模式"));
        assertTrue(modes.handleCommand("yolo").startsWith("未知模式"), "交互式 CLI 不再提供全部放行");
        assertEquals(SessionMode.PLAN, modes.current(), "未知模式不能改变当前状态");
    }

    @Test
    void statusLabelStaysAsciiForColumnMath() {
        for (SessionMode mode : SessionMode.values()) {
            assertTrue(mode.statusLabel().chars().allMatch(c -> c < 128), mode.statusLabel());
            assertTrue(mode.statusLabel().startsWith(mode.name()));
        }
    }

    @Test
    void shiftTabIsBoundToTheCycleWidget() throws Exception {
        try (Terminal terminal = TerminalBuilder.builder()
                .dumb(true)
                .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
                .build()) {
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
            AtomicInteger cycles = new AtomicInteger();

            Main.bindSessionModeCycle(reader, cycles::incrementAndGet);

            KeyMap<Binding> main = reader.getKeyMaps().get(LineReader.MAIN);
            Reference bound = assertInstanceOf(Reference.class, main.getBound(Main.SHIFT_TAB));
            assertEquals("paicli-cycle-session-mode", bound.name());
            reader.getWidgets().get("paicli-cycle-session-mode").apply();
            assertEquals(1, cycles.get());
        }
    }

    @Test
    void shiftTabDuringRawKeyReadingIsNotAStandaloneEscape() {
        // 计划审阅和任务运行中的 ESC 监听读的是原始按键；Shift+Tab 不能被当成取消
        assertEquals(Main.EscapeSequenceType.CONTROL_SEQUENCE, Main.classifyEscapeSequence("[Z"));
    }

    private static SwitchableHitlHandler interactiveHandler() {
        SwitchableHitlHandler handler = new SwitchableHitlHandler(new NoopHandler());
        handler.setHighRiskConfirmationEnabled(true);
        return handler;
    }

    private static final class NoopHandler implements HitlHandler {
        private boolean enabled;

        @Override
        public ApprovalResult requestApproval(ApprovalRequest request) {
            return ApprovalResult.reject("test");
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
