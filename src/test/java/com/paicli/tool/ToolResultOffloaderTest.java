package com.paicli.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ToolResultOffloaderTest {
    private static final Pattern FILE_LINE = Pattern.compile("文件: (\\S+)");

    @TempDir
    Path projectRoot;

    @Test
    void smallResultsStayInline() {
        ToolResultOffloader offloader = new ToolResultOffloader(projectRoot, true, 5_000);

        assertEquals("short", offloader.offloadIfOversized("grep_code", "{}", "short"));
        assertFalse(Files.exists(projectRoot.resolve(ToolResultOffloader.OUTPUT_DIR)));
    }

    @Test
    void oversizedResultIsWrittenToSessionFileAndSummarized() throws Exception {
        ToolResultOffloader offloader = new ToolResultOffloader(projectRoot, true, 5_000);
        String content = numberedLines(2_000);

        String summary = offloader.offloadIfOversized("mcp__chrome-devtools__take_snapshot", "{}", content);

        assertTrue(summary.length() < content.length() / 3, "上下文里只应保留摘要");
        assertTrue(summary.contains("tool: mcp__chrome-devtools__take_snapshot"));
        assertTrue(summary.contains("2000 行"));
        assertTrue(summary.contains("line-0001"), "应保留开头预览");
        assertTrue(summary.contains("line-2000"), "应保留结尾预览");
        Path file = projectRoot.resolve(filePath(summary));
        assertTrue(file.startsWith(projectRoot.resolve(offloader.sessionDirectory())));
        assertEquals(content, Files.readString(file), "完整内容必须可恢复");
        assertEquals("*\n", Files.readString(projectRoot.resolve(ToolResultOffloader.OUTPUT_DIR).resolve(".gitignore")));
    }

    @Test
    void offloadedFileCanBeReadBackThroughReadFileWithoutReoffloading() {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(projectRoot.toString());
        ToolResultOffloader offloader = new ToolResultOffloader(projectRoot, true, 5_000);
        registry.setToolResultOffloader(offloader);
        String summary = offloader.offloadIfOversized("grep_code", "{}", numberedLines(2_000));
        String path = filePath(summary);

        String readBackArgs = "{\"path\":\"" + path + "\",\"offset\":1500,\"limit\":3}";
        ToolRegistry.ToolExecutionResult readBack = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("r1", "read_file", readBackArgs))).get(0);

        assertTrue(readBack.result().contains("line-1500"), readBack.result());
        assertFalse(readBack.result().contains("已卸载"), "显式分段读取不应再次卸载");
    }

    @Test
    void readFileExemptionOnlyCoversExplicitRangesAndOffloadDirectory() {
        ToolResultOffloader offloader = new ToolResultOffloader(projectRoot, true, 5_000);
        String big = numberedLines(2_000);

        assertEquals(big, offloader.offloadIfOversized("read_file", "{\"path\":\"A.java\",\"limit\":2000}", big));
        assertEquals(big, offloader.offloadIfOversized("read_file",
                "{\"path\":\".paicli/tool-outputs/s/001-x.txt\"}", big));
        assertNotEquals(big, offloader.offloadIfOversized("read_file", "{\"path\":\"A.java\"}", big));
    }

    @Test
    void disabledOffloaderPassesThrough() {
        ToolResultOffloader offloader = new ToolResultOffloader(projectRoot, false, 5_000);
        String big = numberedLines(2_000);

        assertEquals(big, offloader.offloadIfOversized("grep_code", "{}", big));
    }

    @Test
    void contextOffloadKeepsTypedMetadata() {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(projectRoot.toString());
        registry.setToolResultOffloader(new ToolResultOffloader(projectRoot, true, 5_000));
        ToolRegistry.ToolExecutionResult raw = new ToolRegistry.ToolExecutionResult(
                "c1", "web_search", "{}", numberedLines(2_000), 7, false, List.of(), true,
                List.of("https://example.com/a"));

        ToolRegistry.ToolExecutionResult result = registry.offloadForContext(raw);

        assertTrue(result.result().startsWith("[工具输出过大"), result.result());
        assertTrue(result.successful());
        assertEquals("c1", result.id());
        assertEquals(List.of("https://example.com/a"), result.discoveredUrls(),
                "卸载只改变文本，不能改变 URL 结构化凭据");
        ToolRegistry.ToolExecutionResult small = new ToolRegistry.ToolExecutionResult(
                "c2", "web_search", "{}", "small", 1, false, List.of(), true, List.of());
        assertSame(small, registry.offloadForContext(small), "小结果原样返回");
    }

    @Test
    void largeCommandOutputIsRecoverableInsteadOfTruncated() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(projectRoot.toString());
        registry.setToolResultOffloader(new ToolResultOffloader(projectRoot, true, 32_000));

        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        String command = windows ? "1..20000" : "seq 1 20000";
        String result = registry.executeTool("execute_command", "{\"command\":\"" + command + "\"}");

        assertTrue(result.contains("[工具输出过大"), result.substring(0, Math.min(300, result.length())));
        assertFalse(result.contains("输出已截断"));
        String saved = Files.readString(projectRoot.resolve(filePath(result)));
        assertTrue(saved.startsWith("1\n2\n"));
        assertTrue(saved.contains("\n20000\n"), "完整命令输出应写入会话文件");
    }

    private static String filePath(String summary) {
        Matcher matcher = FILE_LINE.matcher(summary);
        assertTrue(matcher.find(), summary);
        return matcher.group(1);
    }

    private static String numberedLines(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> String.format("line-%04d some representative tool output text", i))
                .collect(Collectors.joining("\n"));
    }
}
