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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Optional, metadata-only Langfuse tracing. No prompts, tool arguments or results leave this class. */
public final class LangfuseTelemetry {
    private static final Logger log = LoggerFactory.getLogger(LangfuseTelemetry.class);
    private static final ContextKey<TraceLabels> LABELS = ContextKey.named("paicli-langfuse-labels");
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

    /** Flush the short-lived CLI's pending spans without holding the terminal open. */
    public static void shutdown() {
        if (STATE.provider() == null || !CLOSED.compareAndSet(false, true)) return;
        try {
            STATE.provider().forceFlush().join(3, TimeUnit.SECONDS);
        } finally {
            STATE.provider().close();
        }
    }

    public static String observeTurn(String mode, String sessionId, Supplier<String> action) {
        if (!enabled()) return action.get();
        TraceLabels labels = new TraceLabels(mode, sessionId);
        Span span = STATE.tracer().spanBuilder("simple-cli " + mode).startSpan();
        span.setAttribute(OBSERVATION_TYPE_KEY, "agent");
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
            span.setAttribute("langfuse.observation.usage_details", usageJson(response));
            return response;
        } catch (IOException | RuntimeException | Error failure) {
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
            applyLabels(span, labels);
            if (!result.successful()) span.setStatus(StatusCode.ERROR);
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
        return "{\"input\":" + input + ",\"output\":" + output
                + ",\"input_cached\":" + cached + "}";
    }

    private static State createState() {
        if (!"true".equalsIgnoreCase(System.getenv("PAICLI_LANGFUSE_ENABLED"))) return new State(null, null);
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

    private record TraceLabels(String mode, String sessionId) {}
    private record State(Tracer tracer, SdkTracerProvider provider) {}
}
