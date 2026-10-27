package com.paicli.observability;

import com.paicli.llm.LlmClient;
import com.paicli.tool.ToolRegistry.ToolExecutionResult;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LangfuseTelemetryTest {
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

    /** Isolated JVM lets the test set environment variables before telemetry initializes. */
    public static void main(String[] args) {
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
