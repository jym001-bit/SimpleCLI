package com.paicli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.mcp.protocol.McpSchemaSanitizer;
import com.paicli.mcp.protocol.McpToolDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ToolArgumentValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void missingRequiredParameterIsReportedByName(@TempDir Path dir) {
        ToolRegistry registry = registryIn(dir);

        ToolOutput output = registry.executeToolOutput("write_file", "{\"path\":\"a.txt\"}");

        assertFalse(output.successful());
        assertTrue(output.text().contains("缺少必填参数 content"), output.text());
        assertFalse(Files.exists(dir.resolve("a.txt")), "参数不合格不能执行");
    }

    @Test
    void wrongTypeIsReportedWithExpectedType(@TempDir Path dir) {
        ToolRegistry registry = registryIn(dir);

        ToolOutput output = registry.executeToolOutput("read_file", "{\"path\":{\"name\":\"a.txt\"}}");

        assertFalse(output.successful());
        assertTrue(output.text().contains("参数 path 应该是字符串或数字或布尔值（true / false），收到的是对象"), output.text());
    }

    @Test
    void builtInToolsKeepTheirOwnLenientParsingOfSingleValues(@TempDir Path dir) throws Exception {
        // 评测的独立复算按这套语义还原 read_file 看到的内容：空格、正号、其他文字的数字、解析不了时用默认值
        Files.writeString(dir.resolve("a.txt"), "one\ntwo\nthree\n");
        ToolRegistry registry = registryIn(dir);

        ToolOutput padded = registry.executeToolOutput("read_file", "{\"path\":\"a.txt\",\"offset\":\"  +2 \",\"limit\":\"١\"}");
        ToolOutput garbage = registry.executeToolOutput("read_file", "{\"path\":\"a.txt\",\"offset\":\"abc\"}");

        assertTrue(padded.successful(), padded.text());
        assertTrue(padded.text().contains("two") && !padded.text().contains("three"), padded.text());
        assertTrue(garbage.successful(), "解析不了的数字按默认值处理，和以前一样: " + garbage.text());
    }

    @Test
    void mcpToolsAreCheckedStrictlyApartFromQuotedNumbers() throws Exception {
        JsonNode schema = MAPPER.readTree("{\"type\":\"object\",\"properties\":{\"limit\":{\"type\":\"integer\"}}}");
        ToolRegistry registry = new ToolRegistry();
        registry.registerMcpToolOutput(new McpToolDescriptor("demo", "list", "mcp__demo__list", "", schema, schema),
                args -> ToolOutput.text("ok"));

        assertTrue(registry.executeToolOutput("mcp__demo__list", "{\"limit\":\"5\"}").successful());
        ToolOutput bad = registry.executeToolOutput("mcp__demo__list", "{\"limit\":\"abc\"}");
        assertFalse(bad.successful());
        assertTrue(bad.text().contains("参数 limit 应该是整数，收到的是字符串"), bad.text());
    }

    @Test
    void invalidJsonIsReportedSeparatelyWithSnippet(@TempDir Path dir) {
        ToolRegistry registry = registryIn(dir);

        ToolOutput output = registry.executeToolOutput("read_file", "{\"path\": \"a.txt\"");

        assertFalse(output.successful());
        assertTrue(output.text().contains("不是合法的 JSON"), output.text());
        assertTrue(output.text().contains("{\"path\": \"a.txt\""), "要带上收到的参数原文: " + output.text());
    }

    @Test
    void nonObjectArgumentsAreRejected(@TempDir Path dir) {
        ToolOutput output = registryIn(dir).executeToolOutput("read_file", "[\"a.txt\"]");

        assertFalse(output.successful());
        assertTrue(output.text().contains("必须是 JSON 对象"), output.text());
    }

    @Test
    void stringNumbersAndNullOptionalsStillWork(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "one\ntwo\nthree\n");
        ToolRegistry registry = registryIn(dir);

        ToolOutput quoted = registry.executeToolOutput("read_file", "{\"path\":\"a.txt\",\"offset\":\"2\",\"limit\":\"1\"}");
        ToolOutput withNull = registry.executeToolOutput("read_file", "{\"path\":\"a.txt\",\"offset\":null}");

        assertTrue(quoted.successful(), quoted.text());
        assertTrue(quoted.text().contains("two") && !quoted.text().contains("three"), quoted.text());
        assertTrue(withNull.successful(), "null 的可选参数当作没传: " + withNull.text());
        assertTrue(withNull.text().contains("one"), withNull.text());
    }

    @Test
    void enumIsAdvertisedAndEnforced(@TempDir Path dir) {
        ToolRegistry registry = registryIn(dir);
        JsonNode typeSchema = registry.getToolDefinitions().stream()
                .filter(tool -> tool.name().equals("create_project"))
                .findFirst().orElseThrow()
                .parameters().path("properties").path("type");
        assertEquals("[\"java\",\"python\",\"node\"]", typeSchema.path("enum").toString());

        ToolOutput output = registry.executeToolOutput("create_project", "{\"name\":\"demo\",\"type\":\"go\"}");

        assertFalse(output.successful());
        assertTrue(output.text().contains("参数 type 只能取"), output.text());
        assertFalse(Files.exists(dir.resolve("demo")), "以前会建一个空目录并说创建成功");
    }

    @Test
    void mcpToolIsValidatedAgainstRawSchemaNotTheSanitizedOne() throws Exception {
        // 清洗后的 Schema 只给模型看（去掉 null 选项、放宽复杂的 anyOf），校验要按 server 的原始 Schema
        JsonNode raw = MAPPER.readTree("""
                {"type":"object","properties":{
                  "query":{"type":"string"},
                  "cursor":{"anyOf":[{"type":"string"},{"type":"null"}]}
                },"required":["query"]}
                """);
        ToolRegistry registry = new ToolRegistry();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> received = new AtomicReference<>();
        registry.registerMcpToolOutput(new McpToolDescriptor("demo", "search", "mcp__demo__search", "search",
                McpSchemaSanitizer.sanitize(raw), raw), args -> {
            calls.incrementAndGet();
            received.set(args);
            return ToolOutput.text("ok");
        });

        ToolOutput ok = registry.executeToolOutput("mcp__demo__search", "{\"query\":\"java\",\"cursor\":\"abc\"}");
        ToolOutput missing = registry.executeToolOutput("mcp__demo__search", "{\"cursor\":\"abc\"}");

        assertTrue(ok.successful(), ok.text());
        assertEquals("{\"query\":\"java\",\"cursor\":\"abc\"}", received.get(), "MCP 参数原样转发");
        assertFalse(missing.successful());
        assertTrue(missing.text().contains("缺少必填参数 query"), missing.text());
        assertEquals(1, calls.get(), "参数不合格的调用不能发给 server");
    }

    @Test
    void unusableSchemasSkipValidationInsteadOfBlockingTheTool() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        AtomicInteger calls = new AtomicInteger();
        JsonNode externalRef = MAPPER.readTree("""
                {"type":"object","properties":{"x":{"$ref":"https://example.com/schemas/x.json"}},"required":["x"]}
                """);
        JsonNode unknownDraft = MAPPER.readTree("""
                {"$schema":"https://example.com/my-draft","type":"object","properties":{"q":{"type":"string"}},"required":["q"]}
                """);
        registry.registerMcpToolOutput(new McpToolDescriptor("demo", "ext", "mcp__demo__ext", "", externalRef, externalRef),
                args -> { calls.incrementAndGet(); return ToolOutput.text("ok"); });
        registry.registerMcpToolOutput(new McpToolDescriptor("demo", "draft", "mcp__demo__draft", "", unknownDraft, unknownDraft),
                args -> { calls.incrementAndGet(); return ToolOutput.text("ok"); });

        assertTrue(registry.executeToolOutput("mcp__demo__ext", "{}").successful(), "外部 $ref 不去联网加载，跳过校验");
        assertFalse(registry.executeToolOutput("mcp__demo__draft", "{}").successful(),
                "不认识的 $schema 地址按 2020-12 校验，不联网");
        assertTrue(registry.executeToolOutput("mcp__demo__draft", "{\"q\":\"x\"}").successful());
        assertEquals(2, calls.get());
    }

    @Test
    void reRegisteredToolUsesTheNewSchema() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        JsonNode v1 = MAPPER.readTree("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}},\"required\":[\"a\"]}");
        JsonNode v2 = MAPPER.readTree("{\"type\":\"object\",\"properties\":{\"b\":{\"type\":\"string\"}},\"required\":[\"b\"]}");
        registry.registerMcpToolOutput(new McpToolDescriptor("demo", "t", "mcp__demo__t", "", v1, v1), args -> ToolOutput.text("ok"));
        assertTrue(registry.executeToolOutput("mcp__demo__t", "{\"a\":\"x\"}").successful());

        registry.registerMcpToolOutput(new McpToolDescriptor("demo", "t", "mcp__demo__t", "", v2, v2), args -> ToolOutput.text("ok"));

        assertFalse(registry.executeToolOutput("mcp__demo__t", "{\"a\":\"x\"}").successful(), "tools/list_changed 后按新 Schema 校验");
        assertTrue(registry.executeToolOutput("mcp__demo__t", "{\"b\":\"x\"}").successful());
    }

    private static ToolRegistry registryIn(Path dir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(dir.toString());
        return registry;
    }
}
