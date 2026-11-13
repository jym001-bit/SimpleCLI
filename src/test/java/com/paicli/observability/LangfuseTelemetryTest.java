package com.paicli.observability;

import com.paicli.llm.LlmClient;
import com.paicli.tool.ToolRegistry.ToolExecutionResult;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LangfuseTelemetryTest {

    private static final String PATH_KEY = "langfuse.observation.metadata.tool_path";
    private static final String COMMAND_KEY = "langfuse.observation.metadata.tool_command";
    private static final String PREVIEW_KEY = "langfuse.observation.metadata.tool_result_preview";

    @AfterEach
    void clearDetailProperty() {
        System.clearProperty(LangfuseTelemetry.DETAIL_PROPERTY);
    }

    private static ToolExecutionResult tool(String name, String argumentsJson, String result) {
        return new ToolExecutionResult("call-1", name, argumentsJson, result,
                12L, false, List.of(), true, List.of());
    }

    @Test
    void mavenTestProcessDisablesTelemetryEvenWhenHostEnablesIt() {
        assertFalse(LangfuseTelemetry.enabled(), "Maven tests must not inherit live telemetry from the host");
    }

    @Test
    void missingCredentialsLeaveAgentProbeRunningWithoutExport() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            String javaBinary = System.getProperty("os.name", "").toLowerCase().contains("win")
                    ? "java.exe" : "java";
            String java = Path.of(System.getProperty("java.home"), "bin", javaBinary).toString();
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            ProcessBuilder command = new ProcessBuilder(java, "-Dpaicli.log.dir=target/langfuse-test-logs", "-cp", classpath,
                    LangfuseTelemetryTest.class.getName(), "probe");
            command.environment().put("LANGFUSE_ENABLED", "true");
            command.environment().remove("LANGFUSE_PUBLIC_KEY");
            command.environment().remove("LANGFUSE_SECRET_KEY");
            command.environment().put("LANGFUSE_BASE_URL", server.url("/").toString());
            command.redirectErrorStream(true);
            command.redirectOutput(Path.of("target", "langfuse-missing-credentials.out").toFile());
            Process process = command.start();
            assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void exporterFailureDoesNotFailTheAgentProbe() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            for (int i = 0; i < 5; i++) server.enqueue(new MockResponse().setResponseCode(503));
            server.start();
            String javaBinary = System.getProperty("os.name", "").toLowerCase().contains("win")
                    ? "java.exe" : "java";
            String java = Path.of(System.getProperty("java.home"), "bin", javaBinary).toString();
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            ProcessBuilder command = new ProcessBuilder(java, "-Dpaicli.log.dir=target/langfuse-test-logs", "-cp", classpath,
                    LangfuseTelemetryTest.class.getName(), "probe");
            command.environment().put("LANGFUSE_ENABLED", "true");
            command.environment().put("LANGFUSE_PUBLIC_KEY", "pk-test");
            command.environment().put("LANGFUSE_SECRET_KEY", "sk-test");
            command.environment().put("LANGFUSE_BASE_URL", server.url("/").toString());
            command.redirectErrorStream(true);
            command.redirectOutput(Path.of("target", "langfuse-export-failure.out").toFile());
            Process process = command.start();
            assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void sendsOneTraceWithModelAndToolMetricsButNoContent() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
            server.start();
            String javaBinary = System.getProperty("os.name", "").toLowerCase().contains("win")
                    ? "java.exe" : "java";
            String java = Path.of(System.getProperty("java.home"), "bin", javaBinary).toString();
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            ProcessBuilder command = new ProcessBuilder(java, "-Dpaicli.log.dir=target/langfuse-test-logs", "-cp", classpath,
                    LangfuseTelemetryTest.class.getName(), "probe");
            command.environment().put("LANGFUSE_ENABLED", "true");
            command.environment().put("LANGFUSE_PUBLIC_KEY", "pk-test");
            command.environment().put("LANGFUSE_SECRET_KEY", "sk-test");
            command.environment().put("LANGFUSE_BASE_URL", server.url("/").toString());
            command.redirectErrorStream(true);
            command.redirectOutput(Path.of("target", "langfuse-probe.out").toFile());
            Process process = command.start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                fail("probe should exit promptly");
            }
            assertEquals(0, process.exitValue());

            RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("/api/public/otel/v1/traces", request.getPath());
            assertEquals("4", request.getHeader("x-langfuse-ingestion-version"));
            assertEquals("Basic cGstdGVzdDpzay10ZXN0", request.getHeader("Authorization"));
            String body = new String(request.getBody().readByteArray(), StandardCharsets.ISO_8859_1);
            assertTrue(body.contains("simple-cli react"));
            assertTrue(body.contains("test-model"));
            assertTrue(body.contains("read_file"));
            assertTrue(body.contains("memory retrieval"));
            assertTrue(body.contains("input_cached"));
            assertTrue(body.contains("{\"input\":9,\"output\":4,\"input_cached\":3}"));
            assertEquals(1, body.split("langfuse.observation.usage_details", -1).length - 1);
            assertTrue(body.contains("usage_available"));
            assertTrue(body.contains("LLM_IO_ERROR"));
            assertTrue(body.contains("task_id"));
            assertTrue(body.contains("task-test-id"));
            assertTrue(body.contains("tool_call_ids"));
            assertTrue(body.contains("call-1"));
            assertTrue(body.contains("READ_FILE_FAILED"));
            assertFalse(body.contains("PRIVATE_PROMPT_SENTINEL"));
            assertFalse(body.contains("PRIVATE_TOOL_SENTINEL"));
        }
    }

    @Test
    void rootTaskAndUnderstandingShareExportedTraceWithExecution() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
            server.start();
            String javaBinary = System.getProperty("os.name", "").toLowerCase().contains("win")
                    ? "java.exe" : "java";
            String java = Path.of(System.getProperty("java.home"), "bin", javaBinary).toString();
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            ProcessBuilder command = new ProcessBuilder(java, "-Dpaicli.log.dir=target/langfuse-test-logs", "-cp",
                    classpath, LangfuseTelemetryTest.class.getName(), "root-probe");
            command.environment().put("LANGFUSE_ENABLED", "true");
            command.environment().put("LANGFUSE_PUBLIC_KEY", "pk-test");
            command.environment().put("LANGFUSE_SECRET_KEY", "sk-test");
            command.environment().put("LANGFUSE_BASE_URL", server.url("/").toString());
            command.redirectErrorStream(true);
            command.redirectOutput(Path.of("target", "langfuse-root-probe.out").toFile());
            Process process = command.start();
            assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(request);
            String body = new String(request.getBody().readByteArray(), StandardCharsets.ISO_8859_1);
            assertTrue(body.contains("simple-cli root-task"));
            assertTrue(body.contains("task understanding"));
            assertTrue(body.contains("simple-cli react"));
            assertTrue(body.contains("root-probe-id"));
            assertFalse(body.contains("PRIVATE_PROMPT_SENTINEL"));
        }
    }

    @Test
    void recordsPathAndTruncatedResultWhenDetailEnabled() {
        System.setProperty(LangfuseTelemetry.DETAIL_PROPERTY, "true");
        String content = "A".repeat(500);

        Map<String, String> metadata = LangfuseTelemetry.toolDetailAttributes(
                tool("read_file", "{\"path\":\"src/main/java/shop/OrderService.java\"}", content));

        System.out.println("[detail][read_file] " + metadata);
        assertEquals("src/main/java/shop/OrderService.java", metadata.get(PATH_KEY));
        assertEquals(200, metadata.get(PREVIEW_KEY).length());
        assertEquals("A".repeat(200), metadata.get(PREVIEW_KEY));
        assertFalse(metadata.containsKey(COMMAND_KEY));
    }

    @Test
    void truncatesCommandTo300Characters() {
        System.setProperty(LangfuseTelemetry.DETAIL_PROPERTY, "true");
        String command = "mvn -o -q test " + "x".repeat(400);

        Map<String, String> metadata = LangfuseTelemetry.toolDetailAttributes(
                tool("execute_command", "{\"command\":\"" + command + "\"}", "BUILD SUCCESS"));

        System.out.println("[detail][execute_command] commandLength=" + metadata.get(COMMAND_KEY).length()
                + " preview=" + metadata.get(PREVIEW_KEY));
        assertEquals(300, metadata.get(COMMAND_KEY).length());
        assertEquals(command.substring(0, 300), metadata.get(COMMAND_KEY));
        assertFalse(metadata.containsKey(PATH_KEY));
        assertEquals("BUILD SUCCESS", metadata.get(PREVIEW_KEY));
    }

    @Test
    void recordsEditFilePathVerbatim() {
        System.setProperty(LangfuseTelemetry.DETAIL_PROPERTY, "true");

        Map<String, String> metadata = LangfuseTelemetry.toolDetailAttributes(
                tool("edit_file", "{\"path\":\"src/main/java/shop/CartService.java\",\"old_text\":\"a\"}", "ok"));

        System.out.println("[detail][edit_file] " + metadata);
        assertEquals("src/main/java/shop/CartService.java", metadata.get(PATH_KEY));
    }

    @Test
    void recordsNoDetailWhenDisabledByDefault() {
        Map<String, String> metadata = LangfuseTelemetry.toolDetailAttributes(
                tool("read_file", "{\"path\":\"src/main/java/shop/OrderService.java\"}", "content"));

        System.out.println("[detail][disabled-default] " + metadata);
        assertTrue(metadata.isEmpty());
    }

    @Test
    void recordsNoDetailWhenExplicitlyDisabled() {
        System.setProperty(LangfuseTelemetry.DETAIL_PROPERTY, "false");

        Map<String, String> metadata = LangfuseTelemetry.toolDetailAttributes(
                tool("execute_command", "{\"command\":\"mvn -o test\"}", "content"));

        System.out.println("[detail][disabled-false] " + metadata);
        assertTrue(metadata.isEmpty());
    }

    @Test
    void keepsPreviewButSkipsArgumentsWhenArgumentsAreMalformed() {
        System.setProperty(LangfuseTelemetry.DETAIL_PROPERTY, "true");

        Map<String, String> metadata = LangfuseTelemetry.toolDetailAttributes(
                tool("read_file", "not-json", "file content"));

        System.out.println("[detail][malformed-args] " + metadata);
        assertFalse(metadata.containsKey(PATH_KEY));
        assertEquals("file content", metadata.get(PREVIEW_KEY));
    }

    /** Isolated JVM lets the test set environment variables before telemetry initializes. */
    public static void main(String[] args) {
        if (args.length > 0 && "root-probe".equals(args[0])) {
            try (var root = LangfuseTelemetry.openRootTask("root-probe-id")) {
                LangfuseTelemetry.observeStage("task understanding", () -> {
                    try {
                        return LangfuseTelemetry.observeGeneration("test-provider", "test-model",
                                () -> new LlmClient.ChatResponse("assistant", "PRIVATE_PROMPT_SENTINEL",
                                        List.of(), 2, 1));
                    } catch (java.io.IOException error) { throw new IllegalStateException(error); }
                });
                LangfuseTelemetry.observeTurn("react", null, () -> "done");
            }
            LangfuseTelemetry.shutdown();
            return;
        }
        LangfuseTelemetry.evaluate("task-test-id", () -> LangfuseTelemetry.observeTurn("react", "session-test", () -> {
            LangfuseTelemetry.observeStage("memory retrieval", () -> "PRIVATE_PROMPT_SENTINEL");
            try {
                LangfuseTelemetry.observeGeneration("test-provider", "test-model",
                        () -> new LlmClient.ChatResponse("assistant", "PRIVATE_PROMPT_SENTINEL",
                                null, List.of(new LlmClient.ToolCall("call-1",
                                        new LlmClient.ToolCall.Function("read_file", "{}"))),
                                12, 4, 3, "test-model", true));
                LangfuseTelemetry.observeGeneration("test-provider", "missing-usage-model",
                        () -> new LlmClient.ChatResponse("assistant", "PRIVATE_PROMPT_SENTINEL",
                                List.of(), 0, 0));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
            var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                executor.submit(LangfuseTelemetry.wrap(() -> LangfuseTelemetry.recordTools(List.of(
                        new ToolExecutionResult("call-1", "read_file", "PRIVATE_TOOL_SENTINEL",
                                "PRIVATE_TOOL_SENTINEL", 10, false, List.of()),
                        new ToolExecutionResult("call-failed", "read_file", "PRIVATE_TOOL_SENTINEL",
                                "读取文件失败: PRIVATE_TOOL_SENTINEL", 10, false, List.of(), false, List.of()))))).get();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                executor.shutdownNow();
            }
            LangfuseTelemetry.recordFailure("LLM_IO_ERROR");
            return "PRIVATE_PROMPT_SENTINEL";
        }));
        LangfuseTelemetry.shutdown();
    }
}
