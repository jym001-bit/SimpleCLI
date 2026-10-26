package com.paicli.render.inline;

import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.llm.LlmClient;
import com.paicli.render.StatusInfo;
import com.paicli.util.AnsiStyle;
import org.jline.reader.LineReader;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlineRendererTest {

    @Test
    void startupRevealsSimpleCliOnceBeforeInput() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(80, 24));
        LineReader reader = Mockito.mock(LineReader.class);
        var widgets = new HashMap<String, org.jline.reader.Widget>();
        Mockito.when(reader.getWidgets()).thenReturn(widgets);
        InlineRenderer renderer = new InlineRenderer(terminal);
        try {
            renderer.bindLineReader(reader);
            renderer.installStartupScreen(List.of("Simple CLI", "READY", "MCP", "Type a task"), 3);
            assertTrue(widgets.get(LineReader.CALLBACK_INIT).apply());
            assertTrue(widgets.get(LineReader.CALLBACK_INIT).apply());
            Mockito.verify(reader).printAbove("Simple CLI\n");
            Mockito.verify(reader).printAbove("READY\n");
            Mockito.verify(reader).printAbove("MCP\n");
            Mockito.verify(reader).printAbove("Type a task\n");
        } finally {
            renderer.close();
        }
    }

    @Test
    void codeFoldingAndTranscriptRedrawPreserveExistingDock() {
        int columns = 120;
        int rows = 40;
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(columns, rows));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        org.jline.builtins.ScreenTerminal screen = new org.jline.builtins.ScreenTerminal(columns, rows);
        // Seed the screen with the dock that JLine's diff cache believes is already present.
        screen.write(AnsiSeq.moveCursor(38, 1) + "-- dock border --"
                + AnsiSeq.moveCursor(39, 1) + "YOLO /hitl default to confirm"
                + AnsiSeq.moveCursor(40, 1) + "Auto Model · DeepSeek V4.1 Flash  idle  ctx 2%"
                + AnsiSeq.setScrollRegion(1, 37) + AnsiSeq.moveCursor(35, 1));
        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.beginTurn();
            renderer.stream().println("完成了。最终文件内容：");
            renderer.stream().println("┌─ code: java");
            renderer.stream().println("    System.out.println(\"Hello PaiCLI\");");
            renderer.stream().println("└─ end");
            renderer.stream().println("验证通过。");
            replayAndAssertDock(screen, sink);
            for (int i = 0; i < 4; i++) {
                assertTrue(renderer.toggleLastBlock());
                replayAndAssertDock(screen, sink);
                assertEquals(i % 2 == 0, screen.toString().contains("System.out.println"));
            }
        } finally {
            renderer.close();
        }
    }

    private static void replayAndAssertDock(org.jline.builtins.ScreenTerminal screen,
                                            ByteArrayOutputStream sink) {
        String emitted = sink.toString(StandardCharsets.UTF_8);
        assertFalse(emitted.contains(AnsiSeq.CLEAR_TO_EOS), "local repaint must not erase the dock");
        screen.write(emitted.replace("\n", "\r\n"));
        sink.reset();
        String visible = screen.toString();
        assertTrue(visible.contains("-- dock border --"), visible);
        assertTrue(visible.contains("YOLO /hitl default to confirm"), visible);
        assertTrue(visible.contains("Auto Model · DeepSeek V4.1 Flash"), visible);
    }

    @Test
    void onAnsiTerminalEnablesStatusBar() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));

        InlineRenderer renderer = new InlineRenderer(terminal);
        try {
            assertTrue(renderer.hasStatusBar());
            renderer.start();
            renderer.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
        } finally {
            renderer.close();
        }
    }

    @Test
    void onSmallTerminalDisablesStatusBar() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(40, 4));

        InlineRenderer renderer = new InlineRenderer(terminal);
        try {
            assertFalse(renderer.hasStatusBar());
            // updateStatus should still not throw
            renderer.start();
            renderer.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
        } finally {
            renderer.close();
        }
    }

    @Test
    void streamReturnsSystemOut() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));

        InlineRenderer renderer = new InlineRenderer(terminal);
        try {
            assertNotNull(renderer.stream());
        } finally {
            renderer.close();
        }
    }

    @Test
    void streamUsesPrintAboveWhenLineReaderIsReading() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        LineReader lineReader = Mockito.mock(LineReader.class);
        Mockito.when(lineReader.isReading()).thenReturn(true);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.bindLineReader(lineReader);
            renderer.beginTurn();
            renderer.stream().println("异步通知");

            Mockito.verify(lineReader).printAbove("异步通知\n");
            assertFalse(sink.toString(StandardCharsets.UTF_8).contains("异步通知"));
        } finally {
            renderer.close();
        }
    }

    @Test
    void streamedCodeBlockUsesCollapsedHeaderWithPrintAbove() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        LineReader lineReader = Mockito.mock(LineReader.class);
        Mockito.when(lineReader.isReading()).thenReturn(true);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.bindLineReader(lineReader);
            renderer.beginTurn();
            renderer.stream().println("┌─ code: bash");
            renderer.stream().println("    echo hi");
            renderer.stream().println("└─ end");

            ArgumentCaptor<String> output = ArgumentCaptor.forClass(String.class);
            Mockito.verify(lineReader).printAbove(output.capture());
            String rendered = output.getValue();
            assertTrue(rendered.contains("⏵"), rendered);
            assertTrue(rendered.contains("code: bash"), rendered);
            assertTrue(rendered.contains("1 行"), rendered);
            assertFalse(rendered.contains("echo hi"), rendered);
            assertFalse(sink.toString(StandardCharsets.UTF_8).contains("echo hi"));
        } finally {
            renderer.close();
        }
    }

    @Test
    void inlineRendererKeepsPromptInTranscriptFlow() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), true);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();

        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.start();
            sink.reset();
            renderer.beforeInput();
            renderer.afterInput();

            String emitted = sink.toString(StandardCharsets.UTF_8);
            assertEquals("* ", renderer.inputPrompt());
            assertTrue(renderer.inputRightPrompt().contains("@path"));
            assertFalse(emitted.contains("[39;1H"), "LineReader should own the input row: " + emitted);
            assertFalse(emitted.contains("[37;1H"), "renderer should not force transcript cursor rows: " + emitted);
        } finally {
            renderer.close();
        }
    }

    @Test
    void thinkingPanelRendersJLineActivityReasoningAndClears() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), true);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();

        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.beginThinking("Thinking");
            renderer.appendThinking("先分析用户输入\n再检查状态栏边界");

            String rendered = sink.toString(StandardCharsets.UTF_8);
            assertTrue(renderer.supportsThinkingPanel());
            assertTrue(rendered.contains("Thinking"), rendered);
            assertTrue(rendered.contains("先分析用户输入"), rendered);
            assertTrue(rendered.contains("再检查状态栏边界"), rendered);
            assertTrue(rendered.contains("|") || rendered.contains("│"),
                    "activity display should show live reasoning quote content: " + rendered);

            sink.reset();
            renderer.endThinking();
            String cleared = sink.toString(StandardCharsets.UTF_8);
            assertFalse(cleared.contains(AnsiSeq.CLEAR_TO_EOS),
                    "activity clearing must not clear to screen end and erase transcript scrollback: " + cleared);
        } finally {
            renderer.close();
        }
    }

    @Test
    void activityPanelOmitsCancelHintForNonCancelableWork() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), true);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();

        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.beginActivity("Compacting conversation", "正在整理早期对话并生成摘要");

            String rendered = sink.toString(StandardCharsets.UTF_8);
            assertTrue(renderer.supportsActivityPanel());
            assertTrue(rendered.contains("Compacting conversation"), rendered);
            assertTrue(rendered.contains("▰"), rendered);
            assertTrue(rendered.contains("▱"), rendered);
            assertTrue(rendered.contains("%"), rendered);
            assertTrue(rendered.contains("正在整理早期对话"), rendered);
            assertFalse(rendered.contains("esc to cancel"), rendered);
        } finally {
            renderer.endActivity();
            renderer.close();
        }
    }

    @Test
    void activityPanelShowsDeterminateProgressAndCancelHint() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), true);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();

        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.beginActivity("Better Harness", "正在准备审计", true);
            sink.reset();

            renderer.updateActivity("Session Evidence 审查完成", 2, 5);

            String rendered = sink.toString(StandardCharsets.UTF_8);
            assertTrue(rendered.contains("Better Harness"), rendered);
            assertTrue(rendered.contains("2/5"), rendered);
            assertTrue(rendered.contains("40%"), rendered);
            assertTrue(rendered.contains("Session Evidence"), rendered);
            assertTrue(rendered.contains("esc to cancel"), rendered);
        } finally {
            renderer.endActivity();
            renderer.close();
        }
    }

    @Test
    void toggleLastBlockRedrawsTranscriptAroundToolBlock() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 4));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.beginTurn();
            renderer.stream().println("before");
            renderer.appendToolCalls(List.of(tc("read_file", "{\"path\":\"README.md\"}")));
            renderer.stream().println("after");

            sink.reset();
            assertTrue(renderer.toggleLastBlock());

            String emitted = sink.toString(StandardCharsets.UTF_8);
            assertTrue(emitted.contains("before"), emitted);
            assertTrue(emitted.contains("README.md"), emitted);
            assertTrue(emitted.contains("after"), emitted);
            assertTrue(emitted.contains("collapse"), emitted);
            assertFalse(emitted.contains(AnsiSeq.CLEAR_TO_EOS), emitted);
            assertTrue(emitted.contains(AnsiSeq.CLEAR_LINE), emitted);
        } finally {
            renderer.close();
        }
    }

    @Test
    void closeIsIdempotent() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));

        InlineRenderer renderer = new InlineRenderer(terminal);
        renderer.start();
        renderer.close();
        renderer.close();
    }

    @Test
    void promptApprovalDelegatesToFallback() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("dumb");

        InlineRenderer renderer = new InlineRenderer(terminal);
        try {
            // When run without TTY, fallback PlainRenderer reads from stdin.
            // Just verify the call doesn't throw and returns a non-null result.
            // Using `n` as the input is unreliable here, so we skip assertion on actual decision
            // and just verify the type contract by interrupting via empty stdin → reject.
            ApprovalRequest req = ApprovalRequest.of("write_file", "{}", "test");
            ApprovalResult result = renderer.promptApproval(req);
            assertNotNull(result);
        } finally {
            renderer.close();
        }
    }

    @Test
    void openPaletteReturnsMinusOneOnNoInput() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("dumb");

        InlineRenderer renderer = new InlineRenderer(terminal);
        try {
            int idx = renderer.openPalette("title", java.util.List.of("a", "b"));
            assertEquals(-1, idx);
        } finally {
            renderer.close();
        }
    }

    private static LlmClient.ToolCall tc(String name, String args) {
        return new LlmClient.ToolCall(name + "-id", new LlmClient.ToolCall.Function(name, args));
    }

    @Test
    void streamedCodeBlockCollapsesIntoFoldableHeader() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.beginTurn();
            // 模拟 TerminalMarkdownRenderer 输出的代码块（手写预渲染好的 markup）
            renderer.stream().println("┌─ code: java");
            renderer.stream().println("    public class Main {");
            renderer.stream().println("    }");
            renderer.stream().println("└─ end");

            String emitted = sink.toString(StandardCharsets.UTF_8);
            assertTrue(emitted.contains("⏵"), "应该出现折叠箭头: " + emitted);
            assertTrue(emitted.contains("code: java"), emitted);
            assertTrue(emitted.contains("2 行"), "应统计 body 行数: " + emitted);
            assertTrue(emitted.contains("ctrl+o"), emitted);
            // body 暂存在 codeBodyLines，结束时只替换 header，不触碰底部 dock。
            assertFalse(emitted.contains("public class Main {"),
                    "代码体应被折叠后不再可见: " + emitted);
        } finally {
            renderer.close();
        }
    }

    @Test
    void firstCodeBlockSharingLineWithAnswerMarkerStillFolds() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.beginTurn();
            // Agent 先打印不换行的回答标记，TerminalMarkdownRenderer 再输出带样式的代码块 header
            renderer.stream().print(AnsiStyle.answerMarker() + " ");
            renderer.stream().println(AnsiStyle.codeLabel("┌─ code: java"));
            renderer.stream().println("    public class HelloWorld {");
            renderer.stream().println("    }");
            renderer.stream().println(AnsiStyle.codeLabel("└─ end"));
            renderer.stream().println();
            renderer.stream().println(AnsiStyle.codeLabel("┌─ code: text"));
            renderer.stream().println("    Hello, World!");
            renderer.stream().println(AnsiStyle.codeLabel("└─ end"));

            String emitted = sink.toString(StandardCharsets.UTF_8);
            String plain = emitted.replaceAll("\u001B\\[[0-9;?]*[A-Za-z]", "");
            assertTrue(plain.contains("▪ ⏵ code: java (2 行, ctrl+o to expand)"),
                    "首个代码块应折叠且保留回答标记: " + plain);
            assertTrue(plain.contains("⏵ code: text (1 行, ctrl+o to expand)"), plain);
            assertFalse(plain.contains("public class HelloWorld"), "代码体应被折叠: " + plain);
            assertFalse(plain.contains("└─ end"), "footer 不应原样输出: " + plain);

            sink.reset();
            assertTrue(renderer.toggleLastBlock());
            assertTrue(renderer.toggleLastBlock());
            String redrawn = sink.toString(StandardCharsets.UTF_8)
                    .replaceAll("\u001B\\[[0-9;?]*[A-Za-z]", "");
            assertTrue(redrawn.contains("▪ ⏵ code: java"), "重绘后折叠头仍带回答标记: " + redrawn);
        } finally {
            renderer.close();
        }
    }

    @Test
    void streamedCodeBlockTogglesToExpandedOnRedraw() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.beginTurn();
            renderer.stream().println("┌─ code: bash");
            renderer.stream().println("    echo hi");
            renderer.stream().println("└─ end");

            sink.reset();
            assertTrue(renderer.toggleLastBlock(), "代码块应可 toggle");

            String emitted = sink.toString(StandardCharsets.UTF_8);
            assertTrue(emitted.contains("echo hi"), "展开后应看到代码体: " + emitted);
            assertTrue(emitted.contains("┌─ code: bash"), emitted);
            assertTrue(emitted.contains("└─ end"), emitted);
            assertTrue(emitted.contains("⏷"), "展开态应显示 collapse 提示: " + emitted);
        } finally {
            renderer.close();
        }
    }

    @Test
    void nonCodeStreamingTextStillFlowsThrough() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.beginTurn();
            renderer.stream().println("普通段落 1");
            renderer.stream().println("普通段落 2");

            String emitted = sink.toString(StandardCharsets.UTF_8);
            assertTrue(emitted.contains("普通段落 1"), emitted);
            assertTrue(emitted.contains("普通段落 2"), emitted);
            // 不应出现折叠箭头
            assertFalse(emitted.contains("⏵"));
        } finally {
            renderer.close();
        }
    }
}
