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
            command.environment().put("PAICLI_LANGFUSE_ENABLED", "true");
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
            assertTrue(body.contains("input_cached"));
            assertFalse(body.contains("PRIVATE_PROMPT_SENTINEL"));
            assertFalse(body.contains("PRIVATE_TOOL_SENTINEL"));
        }
    }

    /** Isolated JVM lets the test set environment variables before telemetry initializes. */
    public static void main(String[] args) {
        LangfuseTelemetry.observeTurn("react", "session-test", () -> {
            try {
                LangfuseTelemetry.observeGeneration("test-provider", "test-model",
                        () -> new LlmClient.ChatResponse("assistant", "PRIVATE_PROMPT_SENTINEL",
                                List.of(), 12, 4));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
            var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                executor.submit(LangfuseTelemetry.wrap(() -> LangfuseTelemetry.recordTools(List.of(
                        new ToolExecutionResult("call-1", "read_file", "PRIVATE_TOOL_SENTINEL",
                                "PRIVATE_TOOL_SENTINEL", 10, false, List.of()))))).get();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                executor.shutdownNow();
            }
            return "PRIVATE_PROMPT_SENTINEL";
        });
        LangfuseTelemetry.shutdown();
    }
}
