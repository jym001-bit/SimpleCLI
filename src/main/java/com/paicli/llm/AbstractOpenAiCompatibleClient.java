package com.paicli.llm;
import com.paicli.observability.LangfuseTelemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import okio.BufferedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class AbstractOpenAiCompatibleClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(AbstractOpenAiCompatibleClient.class);
    protected static final ObjectMapper mapper = new ObjectMapper();

    // SSE 流式接口下，OkHttp 的 readTimeout 是"两次 read 之间的最大间隔"，不是请求总时长。
    // GLM-5.1 在生成大段 reasoning_content 时服务端可能长时间静默，所以默认值放宽到 300s；
    // callTimeout 作为整体兜底，覆盖极端情况下的连接半死状态。
    // 三项均可通过系统属性覆盖，便于不同模型 / 网络环境调优。
    protected static final OkHttpClient SHARED_HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(readTimeoutSeconds("paicli.llm.connect.timeout.seconds", 60), TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds("paicli.llm.read.timeout.seconds", 300), TimeUnit.SECONDS)
            .writeTimeout(readTimeoutSeconds("paicli.llm.write.timeout.seconds", 60), TimeUnit.SECONDS)
            .callTimeout(readTimeoutSeconds("paicli.llm.call.timeout.seconds", 600), TimeUnit.SECONDS)
            // 重试由 LlmRetryPolicy 统一分类和限次，避免 OkHttp 隐式重放突破次数上限。
            .retryOnConnectionFailure(false)
            .build();

    private ModelProfile modelProfile;
    private String configuredModel;

    final LlmClient withModelProfile(String model, ModelProfile profile) {
        if (profile.maxOutputTokens() != null && profile.maxOutputTokens() >
                (profile.contextWindow() == null ? maxContextWindow() : profile.contextWindow())) {
            throw new IllegalArgumentException("max-output 不能超过模型上下文窗口");
        }
        this.modelProfile = profile;
        this.configuredModel = model;
        return new ProfiledLlmClient(this, model, profile);
    }

    final boolean effectiveImageInput() {
        return modelProfile != null && modelProfile.imageInput() != null
                ? modelProfile.imageInput() : supportsImageInput();
    }

    protected final boolean effectiveDsml(boolean fallback) {
        return modelProfile != null && modelProfile.dsml() != null ? modelProfile.dsml() : fallback;
    }

    private boolean effectiveReasoningHistory() {
        return modelProfile != null && modelProfile.reasoningHistory() != null
                ? modelProfile.reasoningHistory() : shouldSendReasoningContentInRequestHistory();
    }

    private void applyModelProfile(ObjectNode body) {
        if (modelProfile == null) return;
        if (modelProfile.thinking() != null) {
            ObjectNode thinking = body.has("thinking") && body.get("thinking").isObject()
                    ? (ObjectNode) body.get("thinking") : body.putObject("thinking");
            thinking.put("type", modelProfile.thinking());
            if ("disabled".equals(modelProfile.thinking())) body.remove("reasoning_effort");
        }
        if (modelProfile.reasoningEffort() != null) body.put("reasoning_effort", modelProfile.reasoningEffort());
        if (modelProfile.maxOutputTokens() != null) body.put("max_tokens", modelProfile.maxOutputTokens());
    }

    private final AtomicBoolean cancellationRequested = new AtomicBoolean();

    private static long readTimeoutSeconds(String key, long defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    protected abstract String getApiUrl();

    protected abstract String getModel();

    protected abstract String getApiKey();

    protected boolean shouldSendReasoningContentInRequestHistory() {
        return false;
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        return chat(messages, tools, StreamListener.NO_OP);
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
        return LangfuseTelemetry.observeGeneration(getProviderName(), getModelName(),
                () -> chatUntraced(messages, tools, listener));
    }

    private ChatResponse chatUntraced(List<Message> messages, List<Tool> tools,
                                      StreamListener listener) throws IOException {
        if (cancellationRequested.get()) {
            throw new IOException("LLM request cancelled");
        }
        StreamListener streamListener = listener == null ? StreamListener.NO_OP : listener;
        RequestBody body = new JsonRequestBody(buildRequestBody(messages, tools));

        Request.Builder request = new Request.Builder()
                .url(getApiUrl())
                .header("Authorization", "Bearer " + getApiKey())
                .header("Content-Type", "application/json")
                .post(body);
        customizeRequest(request);
        Request builtRequest = request.build();

        LlmRetryPolicy retryPolicy = retryPolicy();
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= retryPolicy.maxAttempts(); attempt++) {
            if (cancellationRequested.get()) {
                throw new IOException("LLM request cancelled");
            }
            AttemptProgress progress = new AttemptProgress(streamListener != StreamListener.NO_OP);
            try {
                return executeAttempt(builtRequest, streamListener, progress, retryPolicy);
            } catch (IOException failure) {
                if (lastFailure != null && lastFailure != failure) {
                    failure.addSuppressed(lastFailure);
                }
                lastFailure = failure;

                boolean canRetry = attempt < retryPolicy.maxAttempts()
                        && !cancellationRequested.get()
                        && !progress.hasConsumableOutput()
                        && retryPolicy.isRetryableFailure(failure);
                if (!canRetry) {
                    throw failure;
                }

                int retryNumber = attempt;
                long delayMillis = retryPolicy.delayMillis(retryNumber, retryAfterHeader(failure));
                log.warn("LLM request failed before streaming output; retrying provider={} model={} "
                                + "attempt={}/{} delayMs={} cause={}",
                        getProviderName(), getModelName(), attempt + 1, retryPolicy.maxAttempts(),
                        delayMillis, failure.getMessage());
                sleepBeforeRetry(retryPolicy, delayMillis);
            }
        }
        throw lastFailure != null ? lastFailure : new IOException("LLM 请求失败");
    }

    @Override
    public void cancelInFlightCalls() {
        cancellationRequested.set(true);
        httpClient().dispatcher().cancelAll();
    }

    private void sleepBeforeRetry(LlmRetryPolicy retryPolicy, long delayMillis) throws IOException {
        long remaining = Math.max(0L, delayMillis);
        while (remaining > 0L) {
            if (cancellationRequested.get()) {
                throw new IOException("LLM request cancelled");
            }
            long slice = Math.min(100L, remaining);
            retryPolicy.sleep(slice);
            remaining -= slice;
        }
    }

    private ChatResponse executeAttempt(Request builtRequest,
                                        StreamListener streamListener,
                                        AttemptProgress progress,
                                        LlmRetryPolicy retryPolicy) throws IOException {
        try (Response response = httpClient().newCall(builtRequest).execute()) {
            ResponseBody responseBodyObj = response.body();
            if (!response.isSuccessful()) {
                String errorBody = responseBodyObj != null ? responseBodyObj.string() : "无响应体";
                throw new LlmHttpException(
                        response.code(),
                        response.header("Retry-After"),
                        "API请求失败: " + response.code() + " - " + errorBody
                );
            }
            if (responseBodyObj == null) {
                throw new IOException("API返回空响应体");
            }

            BufferedSource source = responseBodyObj.source();
            String role = "assistant";
            StringBuilder content = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            List<ToolCallAccumulator> toolAccumulators = new ArrayList<>();
            int inputTokens = 0;
            int outputTokens = 0;
            int cachedInputTokens = 0;
            String resolvedModel = null;
            boolean resolvedModelConflict = false;
            boolean usagePresent = false;
            boolean streamCompleted = false;

            while (!source.exhausted()) {
                String line = source.readUtf8Line();
                if (line == null) {
                    break;
                }

                String trimmed = line.trim();
                if (trimmed.isEmpty() || !trimmed.startsWith("data:")) {
                    continue;
                }

                String payload = trimmed.substring("data:".length()).trim();
                if (payload.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(payload)) {
                    streamCompleted = true;
                    break;
                }

                JsonNode root = mapper.readTree(payload);
                String eventModel = normalizedResolvedModel(root.path("model"));
                if (eventModel != null) {
                    if (resolvedModel == null) {
                        resolvedModel = eventModel;
                    } else if (!resolvedModel.equals(eventModel)) {
                        resolvedModelConflict = true;
                    }
                }
                JsonNode error = root.path("error");
                if (!error.isMissingNode() && !error.isNull()) {
                    throw new LlmStreamingApiException(
                            "API请求失败: " + formatStreamingError(error),
                            isRetryableStreamingError(error, retryPolicy)
                    );
                }
                JsonNode usage = root.path("usage");
                if (hasCompleteTokenUsage(usage)) {
                    usagePresent = true;
                    inputTokens = usage.path("prompt_tokens").intValue();
                    outputTokens = usage.path("completion_tokens").intValue();
                    cachedInputTokens = parseCachedInputTokens(usage, cachedInputTokens);
                }

                JsonNode choices = root.path("choices");
                if (!choices.isArray() || choices.isEmpty()) {
                    continue;
                }

                JsonNode choice = choices.get(0);
                JsonNode finishReason = choice.get("finish_reason");
                if (finishReason != null && !finishReason.isNull() && !finishReason.asText("").isBlank()) {
                    streamCompleted = true;
                }
                JsonNode delta = choice.path("delta");
                if (delta.isMissingNode() || delta.isNull()) {
                    delta = choice.path("message");
                }
                if (delta.isMissingNode() || delta.isNull()) {
                    continue;
                }

                String deltaRole = delta.path("role").asText("");
                if (!deltaRole.isEmpty()) {
                    role = deltaRole;
                }

                String reasoningDelta = extractReasoningDelta(delta);
                if (!reasoningDelta.isEmpty()) {
                    reasoning.append(reasoningDelta);
                    progress.markConsumableOutput();
                    streamListener.onReasoningDelta(reasoningDelta);
                }

                String contentDelta = delta.path("content").asText("");
                if (!contentDelta.isEmpty()) {
                    content.append(contentDelta);
                    progress.markConsumableOutput();
                    streamListener.onContentDelta(contentDelta);
                }

                mergeToolCallDeltas(toolAccumulators, delta.path("tool_calls"));
            }

            if (!streamCompleted) {
                throw new LlmStreamInterruptedException("LLM 流式响应在完成标记前中断");
            }

            List<ToolCall> toolCalls = buildToolCalls(toolAccumulators);
            if (content.isEmpty() && reasoning.isEmpty() && (toolCalls == null || toolCalls.isEmpty())) {
                throw new IOException("API返回空内容，请检查 provider/model 配置或该模型是否支持当前请求参数");
            }

            return new ChatResponse(
                    role,
                    content.toString(),
                    reasoning.toString(),
                    toolCalls,
                    inputTokens,
                    outputTokens,
                    cachedInputTokens,
                    resolvedModelConflict ? null : resolvedModel,
                    usagePresent
            );
        }
    }

    /**
     * A usage object is evidence only when both required counters are explicit, non-negative
     * integers that fit the public response type. Empty, partial or coercible string objects are
     * not proof of complete provider usage.
     */
    static boolean hasCompleteTokenUsage(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return false;
        }
        return isNonNegativeInt(usage.get("prompt_tokens"))
                && isNonNegativeInt(usage.get("completion_tokens"));
    }

    private static boolean isNonNegativeInt(JsonNode value) {
        return value != null
                && value.isIntegralNumber()
                && value.canConvertToInt()
                && value.intValue() >= 0;
    }

    private static String normalizedResolvedModel(JsonNode modelNode) {
        if (modelNode == null || !modelNode.isTextual()) {
            return null;
        }
        String value = modelNode.asText().trim();
        return value.isEmpty() || value.length() > 256 ? null : value;
    }

    LlmRetryPolicy retryPolicy() {
        return LlmRetryPolicy.fromSystemProperties();
    }

    private String retryAfterHeader(IOException failure) {
        return failure instanceof LlmHttpException httpFailure ? httpFailure.retryAfter() : null;
    }

    private boolean isRetryableStreamingError(JsonNode error, LlmRetryPolicy retryPolicy) {
        int status = error.path("status").asInt(0);
        if (status == 0 && error.path("code").canConvertToInt()) {
            status = error.path("code").asInt(0);
        }
        if (status != 0) {
            return retryPolicy.isRetryableStatus(status);
        }

        String code = error.path("code").asText("").toLowerCase(Locale.ROOT);
        String type = error.path("type").asText("").toLowerCase(Locale.ROOT);
        String message = error.path("message").asText("").toLowerCase(Locale.ROOT);
        String combined = code + " " + type + " " + message;
        return combined.contains("rate_limit")
                || combined.contains("too many requests")
                || combined.contains("overloaded")
                || combined.contains("server_error")
                || combined.contains("temporarily unavailable")
                || combined.contains("timeout");
    }

    private String formatStreamingError(JsonNode error) {
        String message = error.path("message").asText("");
        String code = error.path("code").asText("");
        if (!code.isEmpty() && !message.isEmpty()) {
            return code + " - " + message;
        }
        if (!message.isEmpty()) {
            return message;
        }
        return error.toString();
    }

    private String extractReasoningDelta(JsonNode delta) {
        String reasoningContent = delta.path("reasoning_content").asText("");
        if (!reasoningContent.isEmpty()) {
            return reasoningContent;
        }
        String reasoning = delta.path("reasoning").asText("");
        if (!reasoning.isEmpty()) {
            return reasoning;
        }
        JsonNode details = delta.path("reasoning_details");
        if (details.isArray() && !details.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode detail : details) {
                String text = detail.path("text").asText("");
                if (text.isEmpty()) {
                    text = detail.path("content").asText("");
                }
                if (!text.isEmpty()) {
                    sb.append(text);
                }
            }
            return sb.toString();
        }
        return "";
    }

    private int parseCachedInputTokens(JsonNode usage, int fallback) {
        int cached = usage.path("cached_tokens").asInt(fallback);
        cached = usage.path("prompt_cache_hit_tokens").asInt(cached);
        cached = usage.path("input_cache_hit_tokens").asInt(cached);
        JsonNode promptDetails = usage.path("prompt_tokens_details");
        if (!promptDetails.isMissingNode()) {
            cached = promptDetails.path("cached_tokens").asInt(cached);
        }
        JsonNode inputDetails = usage.path("input_tokens_details");
        if (!inputDetails.isMissingNode()) {
            cached = inputDetails.path("cached_tokens").asInt(cached);
        }
        return cached;
    }

    private ObjectNode buildRequestBody(List<Message> messages, List<Tool> tools) {
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", configuredModel == null ? getModel() : configuredModel);
        requestBody.put("stream", true);

        ArrayNode messagesArray = requestBody.putArray("messages");
        for (Message msg : messages) {
            ObjectNode msgNode = messagesArray.addObject();
            msgNode.put("role", msg.role());
            appendMessageContent(msgNode, msg);
            if (effectiveReasoningHistory()
                    && "assistant".equals(msg.role())
                    && msg.reasoningContent() != null
                    && !msg.reasoningContent().isBlank()) {
                msgNode.put("reasoning_content", msg.reasoningContent());
            }

            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                ArrayNode toolCallsArray = msgNode.putArray("tool_calls");
                for (ToolCall tc : msg.toolCalls()) {
                    ObjectNode tcNode = toolCallsArray.addObject();
                    tcNode.put("id", tc.id());
                    tcNode.put("type", "function");
                    ObjectNode functionNode = tcNode.putObject("function");
                    functionNode.put("name", tc.function().name());
                    functionNode.put("arguments", tc.function().arguments());
                }
            }

            if (msg.toolCallId() != null) {
                msgNode.put("tool_call_id", msg.toolCallId());
            }
        }

        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = requestBody.putArray("tools");
            for (Tool tool : tools) {
                ObjectNode toolNode = toolsArray.addObject();
                toolNode.put("type", "function");
                ObjectNode functionNode = toolNode.putObject("function");
                functionNode.put("name", tool.name());
                functionNode.put("description", tool.description());
                functionNode.set("parameters", tool.parameters());
            }
        }
        customizeRequestBody(requestBody);
        applyModelProfile(requestBody);
        return requestBody;
    }

    protected void customizeRequestBody(ObjectNode requestBody) {
    }

    protected void customizeRequest(Request.Builder request) {
    }

    protected OkHttpClient httpClient() {
        return SHARED_HTTP_CLIENT;
    }

    private static final class AttemptProgress {
        private final boolean hasStreamingConsumer;
        private boolean consumableOutput;

        private AttemptProgress(boolean hasStreamingConsumer) {
            this.hasStreamingConsumer = hasStreamingConsumer;
        }

        void markConsumableOutput() {
            if (hasStreamingConsumer) {
                consumableOutput = true;
            }
        }

        boolean hasConsumableOutput() {
            return consumableOutput;
        }
    }

    private void appendMessageContent(ObjectNode msgNode, Message msg) {
        if (msg.hasImageContent() && !effectiveImageInput()) {
            appendMessageContent(msgNode, msg.withoutImageContent(
                    "当前 provider/model 不支持图片附件，已省略 {count} 张；请基于文字工具结果继续，必要时改用支持视觉输入的模型。"));
            return;
        }

        if (!msg.hasContentParts()) {
            msgNode.put("content", msg.content());
            return;
        }

        ArrayNode contentArray = msgNode.putArray("content");
        for (LlmClient.ContentPart part : msg.contentParts()) {
            if (part == null) {
                continue;
            }
            if (part.isText()) {
                if (part.text() != null && !part.text().isBlank()) {
                    ObjectNode textNode = contentArray.addObject();
                    textNode.put("type", "text");
                    textNode.put("text", part.text());
                }
                continue;
            }
            if (part.isImage()) {
                String imageUrl = toImageUrl(part);
                if (imageUrl == null || imageUrl.isBlank()) {
                    continue;
                }
                ObjectNode imageNode = contentArray.addObject();
                imageNode.put("type", "image_url");
                ObjectNode imageUrlNode = imageNode.putObject("image_url");
                imageUrlNode.put("url", imageUrl);
            }
        }

        if (contentArray.isEmpty()) {
            msgNode.put("content", msg.content());
        }
    }

    protected String toImageUrl(LlmClient.ContentPart part) {
        if ("image_url".equals(part.type())) {
            return part.imageUrl();
        }
        if ("image_base64".equals(part.type())) {
            String mimeType = part.mimeType() == null || part.mimeType().isBlank() ? "image/png" : part.mimeType();
            return "data:" + mimeType + ";base64," + part.imageBase64();
        }
        return null;
    }

    private void mergeToolCallDeltas(List<ToolCallAccumulator> accumulators, JsonNode toolCallsNode) {
        if (toolCallsNode == null || !toolCallsNode.isArray()) {
            return;
        }

        for (JsonNode tc : toolCallsNode) {
            int index = tc.path("index").asInt(accumulators.size());
            while (accumulators.size() <= index) {
                accumulators.add(new ToolCallAccumulator());
            }

            ToolCallAccumulator acc = accumulators.get(index);
            String id = tc.path("id").asText("");
            if (!id.isEmpty()) {
                acc.id = id;
            }

            JsonNode function = tc.path("function");
            String name = function.path("name").asText("");
            if (!name.isEmpty()) {
                acc.name.append(name);
            }
            String arguments = function.path("arguments").asText("");
            if (!arguments.isEmpty()) {
                acc.arguments.append(arguments);
            }
        }
    }

    private List<ToolCall> buildToolCalls(List<ToolCallAccumulator> accumulators) {
        if (accumulators.isEmpty()) {
            return null;
        }

        List<ToolCall> toolCalls = new ArrayList<>();
        for (int index = 0; index < accumulators.size(); index++) {
            ToolCallAccumulator acc = accumulators.get(index);
            String name = acc.name.toString();
            if (name.isBlank()) {
                // 按 index 补位产生的空槽，没有真正的工具调用
                continue;
            }
            String id = acc.id;
            if (id == null || id.isBlank()) {
                // 个别兼容接口流式返回不带 id；直接丢弃会让模型以为调用了工具却没有结果，补一个本地 id
                id = "call_" + index;
                log.warn("Streamed tool call {} arrived without id; assigned {}", name, id);
            }
            toolCalls.add(new ToolCall(id, new ToolCall.Function(name, acc.arguments.toString())));
        }
        return toolCalls.isEmpty() ? null : toolCalls;
    }

    private static final class ToolCallAccumulator {
        private String id;
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }
}
