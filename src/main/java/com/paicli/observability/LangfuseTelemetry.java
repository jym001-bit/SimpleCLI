package com.paicli.observability;

import com.paicli.llm.LlmClient;
import com.paicli.tool.ToolRegistry.ToolExecutionResult;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Optional, metadata-only Langfuse tracing. No prompts, tool arguments or results leave this class. */
public final class LangfuseTelemetry {
    private static final Logger log = LoggerFactory.getLogger(LangfuseTelemetry.class);
    private static final ContextKey<TraceLabels> LABELS = ContextKey.named("paicli-langfuse-labels");
    private static final ContextKey<TraceCapture> EVALUATION = ContextKey.named("paicli-evaluation-trace");
    private static final String MODE_KEY = "langfuse.trace.metadata.mode";
    private static final String SESSION_KEY = "langfuse.session.id";
    private static final String TRACE_NAME_KEY = "langfuse.trace.name";
    private static final String OBSERVATION_TYPE_KEY = "langfuse.observation.type";
    private static final String OBSERVATION_METADATA = "langfuse.observation.metadata.";
    private static final State STATE = createState();
    private static final AtomicBoolean CLOSED = new AtomicBoolean();

    private LangfuseTelemetry() {
    }

    public static boolean enabled() {
        return STATE.tracer() != null;
    }

    /** Runs one evaluation task with its own ID and returns the ID of the actual root span. */
    public static <T> TracedResult<T> evaluate(String taskId, Supplier<T> action) {
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId is required");
        TraceCapture capture = new TraceCapture(taskId);
        T value;
        try (Scope ignored = Context.current().with(EVALUATION, capture).makeCurrent()) {
            value = action.get();
        }
        return new TracedResult<>(value, capture.traceId);
    }

    public record TracedResult<T>(T value, String traceId) {}

    /** Flush the short-lived CLI's pending spans without holding the terminal open. */
    public static void shutdown() {
        if (STATE.provider() == null || !CLOSED.compareAndSet(false, true)) return;
        try {
            STATE.provider().forceFlush().join(3, TimeUnit.SECONDS);
        } finally {
            STATE.provider().close();
        }
    }

    public static <T> T observeTurn(String mode, String sessionId, Supplier<T> action) {
        if (!enabled()) return action.get();
        TraceCapture capture = Context.current().get(EVALUATION);
        String taskId = capture == null ? UUID.randomUUID().toString() : capture.taskId;
        TraceLabels labels = new TraceLabels(mode, sessionId, taskId);
        Span span = STATE.tracer().spanBuilder("simple-cli " + mode).startSpan();
        span.setAttribute(OBSERVATION_TYPE_KEY, "agent");
        if (capture != null) capture.traceId = span.getSpanContext().getTraceId();
        applyLabels(span, labels);
        try (Scope scope = Context.current().with(LABELS, labels).with(span).makeCurrent()) {
            return action.get();
        } catch (RuntimeException | Error failure) {
            markFailure(span, failure);
            throw failure;
        } finally {
            span.end();
        }
    }

    @FunctionalInterface
    public interface ChatCall {
        LlmClient.ChatResponse call() throws IOException;
    }

    public static LlmClient.ChatResponse observeGeneration(String provider, String model, ChatCall action)
            throws IOException {
        if (!enabled()) return action.call();
        Span span = STATE.tracer().spanBuilder("llm " + provider).startSpan();
        span.setAttribute(OBSERVATION_TYPE_KEY, "generation");
        span.setAttribute("langfuse.observation.model.name", model);
        span.setAttribute(OBSERVATION_METADATA + "provider", provider);
        applyLabels(span, Context.current().get(LABELS));
        try (Scope scope = span.makeCurrent()) {
            LlmClient.ChatResponse response = action.call();
            if (response.usagePresent()) {
                span.setAttribute("langfuse.observation.usage_details", usageJson(response));
            } else {
                span.setAttribute(OBSERVATION_METADATA + "usage_available", false);
            }
            List<String> callIds = response.toolCalls() == null ? List.of() : response.toolCalls().stream()
                    .map(LlmClient.ToolCall::id)
                    .filter(LangfuseTelemetry::safeCallId)
                    .toList();
            span.setAttribute(OBSERVATION_METADATA + "tool_call_count",
                    response.toolCalls() == null ? 0 : response.toolCalls().size());
            if (!callIds.isEmpty()) {
                span.setAttribute(AttributeKey.stringArrayKey(OBSERVATION_METADATA + "tool_call_ids"), callIds);
            }
            return response;
        } catch (IOException | RuntimeException | Error failure) {
            markFailure(span, failure);
            throw failure;
        } finally {
            span.end();
        }
    }

    /** Mark a handled business failure without exporting an error message or user content. */
    public static void recordFailure(String errorType) {
        if (!enabled()) return;
        Span span = Span.current();
        if (!span.getSpanContext().isValid()) return;
        span.setStatus(StatusCode.ERROR);
        if (errorType != null && errorType.matches("[A-Z_]{1,64}")) {
            span.setAttribute(OBSERVATION_METADATA + "error_type", errorType);
        }
    }

    /** Trace a real execution phase while keeping its inputs and outputs local. */
    public static <T> T observeStage(String name, Supplier<T> action) {
        if (!enabled()) return action.get();
        Span span = STATE.tracer().spanBuilder(name).startSpan();
        span.setAttribute(OBSERVATION_TYPE_KEY, "span");
        applyLabels(span, Context.current().get(LABELS));
        try (Scope scope = span.makeCurrent()) {
            return action.get();
        } catch (RuntimeException | Error failure) {
            markFailure(span, failure);
            throw failure;
        } finally {
            span.end();
        }
    }

    /** Called after policy decisions so denied calls appear alongside executed calls. */
    public static void recordTools(List<ToolExecutionResult> results) {
        if (!enabled() || results == null || Context.current().get(LABELS) == null) return;
        TraceLabels labels = Context.current().get(LABELS);
        for (ToolExecutionResult result : results) {
            if (result == null) continue;
            long elapsed = Math.max(0, result.elapsedMillis());
            Span span = STATE.tracer().spanBuilder("tool " + result.name())
                    .setStartTimestamp(Instant.now().minusMillis(elapsed)).startSpan();
            span.setAttribute(OBSERVATION_TYPE_KEY, "tool");
            span.setAttribute(OBSERVATION_METADATA + "tool_name", result.name());
            span.setAttribute(OBSERVATION_METADATA + "duration_ms", elapsed);
            span.setAttribute(OBSERVATION_METADATA + "successful", result.successful());
            span.setAttribute(OBSERVATION_METADATA + "timed_out", result.timedOut());
            if (safeCallId(result.id())) {
                span.setAttribute(OBSERVATION_METADATA + "tool_call_id", result.id());
            }
            applyLabels(span, labels);
            if (!result.successful()) {
                span.setStatus(StatusCode.ERROR);
                span.setAttribute(OBSERVATION_METADATA + "tool_error_type", safeToolErrorType(result));
            }
            span.end();
        }
    }

    /** Carry the active trace into Plan/Team executor threads. */
    public static Runnable wrap(Runnable task) {
        return Context.current().wrap(task);
    }

    public static <T> java.util.concurrent.Callable<T> wrap(java.util.concurrent.Callable<T> task) {
        return Context.current().wrap(task);
    }

    private static void applyLabels(Span span, TraceLabels labels) {
        if (labels == null) return;
        span.setAttribute(TRACE_NAME_KEY, "simple-cli " + labels.mode());
        span.setAttribute(MODE_KEY, labels.mode());
        span.setAttribute(OBSERVATION_METADATA + "task_id", labels.taskId());
        if (labels.sessionId() != null && !labels.sessionId().isBlank()) {
            span.setAttribute(SESSION_KEY, labels.sessionId());
        }
    }

    private static void markFailure(Span span, Throwable failure) {
        span.setStatus(StatusCode.ERROR);
        // Exception messages often contain paths, prompt fragments or provider responses.
        span.setAttribute(OBSERVATION_METADATA + "error_type", failure.getClass().getSimpleName());
    }

    private static String usageJson(LlmClient.ChatResponse response) {
        int input = Math.max(0, response.inputTokens());
        int output = Math.max(0, response.outputTokens());
        int cached = Math.max(0, response.cachedInputTokens());
        // OpenAI-compatible prompt_tokens includes cached_tokens. Langfuse treats usage_details
        // entries as mutually exclusive buckets, so only uncached input belongs in "input".
        int uncached = Math.max(0, input - Math.min(input, cached));
        return "{\"input\":" + uncached + ",\"output\":" + output
                + ",\"input_cached\":" + cached + "}";
    }

    private static boolean safeCallId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,128}");
    }

    private static String safeToolErrorType(ToolExecutionResult result) {
        if (result.timedOut()) return "TOOL_TIMEOUT";
        String text = result.result() == null ? "" : result.result().stripLeading();
        if ("read_file".equals(result.name()) && text.startsWith("读取文件失败")) return "READ_FILE_FAILED";
        if (text.startsWith("参数校验失败") || text.startsWith("参数错误")) return "TOOL_ARGUMENT_ERROR";
        return "TOOL_FAILURE";
    }

    private static State createState() {
        String enabled = System.getenv("LANGFUSE_ENABLED");
        if (enabled == null) enabled = System.getenv("PAICLI_LANGFUSE_ENABLED");
        if (!"true".equalsIgnoreCase(enabled)) return new State(null, null);
        String publicKey = System.getenv("LANGFUSE_PUBLIC_KEY");
        String secretKey = System.getenv("LANGFUSE_SECRET_KEY");
        String baseUrl = System.getenv("LANGFUSE_BASE_URL");
        if (publicKey == null || publicKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            log.warn("Langfuse 已启用，但缺少公钥或密钥；本次不发送追踪数据");
            return new State(null, null);
        }
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://cloud.langfuse.com";
        try {
            URI uri = URI.create(baseUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    && !("http".equalsIgnoreCase(uri.getScheme())
                    && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost())))) {
                throw new IllegalArgumentException("Langfuse 地址必须使用 HTTPS（本机部署可使用 HTTP）");
            }
            String endpoint = baseUrl.replaceAll("/+$", "") + "/api/public/otel/v1/traces";
            String auth = Base64.getEncoder().encodeToString(
                    (publicKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8));
            OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                    .setEndpoint(endpoint)
                    .addHeader("Authorization", "Basic " + auth)
                    .addHeader("x-langfuse-ingestion-version", "4")
                    .setTimeout(Duration.ofSeconds(3))
                    .build();
            SdkTracerProvider provider = SdkTracerProvider.builder()
                    .setResource(Resource.create(Attributes.of(
                            AttributeKey.stringKey("service.name"), "simple-cli")))
                    .addSpanProcessor(BatchSpanProcessor.builder(exporter)
                            .setMaxQueueSize(256)
                            .setMaxExportBatchSize(32)
                            .setScheduleDelay(Duration.ofMillis(500))
                            .build())
                    .build();
            Runtime.getRuntime().addShutdownHook(new Thread(LangfuseTelemetry::shutdown,
                    "paicli-langfuse-shutdown"));
            return new State(provider.get("simple-cli"), provider);
        } catch (RuntimeException failure) {
            log.warn("Langfuse 配置无效；本次不发送追踪数据：{}", failure.getClass().getSimpleName());
            return new State(null, null);
        }
    }

    private record TraceLabels(String mode, String sessionId, String taskId) {}
    private static final class TraceCapture {
        private final String taskId;
        private String traceId;
        private TraceCapture(String taskId) { this.taskId = taskId; }
    }
    private record State(Tracer tracer, SdkTracerProvider provider) {}
}
