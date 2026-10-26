package com.paicli.llm;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class DeepSeekClient extends AbstractOpenAiCompatibleClient {

    private static final String API_URL = "https://api.deepseek.com/chat/completions";
    private static final String DEFAULT_MODEL = "deepseek-flash";
    private static final OkHttpClient HTTP_1_1_CLIENT = SHARED_HTTP_CLIENT.newBuilder()
            .protocols(List.of(Protocol.HTTP_1_1))
            .build();
    private static final String DSML_TAG = "｜｜DSML｜｜";
    private static final String DSML_OPEN_PREFIX = "<" + DSML_TAG;
    private static final Pattern DSML_TOOL_BLOCK = Pattern.compile(
            "(?s)<" + DSML_TAG + "\\s*(tool_calls|calls)>\\s*(.*?)\\s*</" + DSML_TAG + "\\s*\\1>");
    private static final Pattern DSML_INVOKE = Pattern.compile(
            "(?s)<" + DSML_TAG + "\\s*invoke\\s+([^>]*)>(.*?)</" + DSML_TAG + "\\s*invoke>");
    private static final Pattern DSML_PARAMETER = Pattern.compile(
            "(?s)<" + DSML_TAG + "\\s*parameter\\s+([^>]*)>(.*?)</" + DSML_TAG + "\\s*parameter>");
    private static final Pattern DSML_ATTRIBUTE = Pattern.compile(
            "([A-Za-z][A-Za-z0-9_-]{0,63})=\"([^\"\\r\\n]{0,256})\"");
    private static final Pattern SAFE_TOOL_OR_PARAMETER_NAME = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_.:-]{0,127}");
    private static final int MAX_DSML_CONTENT_CHARS = 256_000;
    private static final int MAX_DSML_TOOL_CALLS = 32;
    private static final int MAX_DSML_PARAMETERS = 64;
    private static final int MAX_BENCHMARK_OUTPUT_TOKENS = 16_384;
    private final String apiKey;
    private final String model;
    private final String apiUrl;
    private final Integer benchmarkMaxOutputTokens;
    private final AtomicLong dsmlCallSequence = new AtomicLong();

    public DeepSeekClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL, API_URL, null);
    }

    public DeepSeekClient(String apiKey, String model) {
        this(apiKey, model, API_URL, null);
    }

    /** Creates an immutable provider instance for a frozen benchmark output limit. */
    public DeepSeekClient(String apiKey, String model, int benchmarkMaxOutputTokens) {
        this(apiKey, model, API_URL,
                Integer.valueOf(requireBenchmarkMaxOutputTokens(benchmarkMaxOutputTokens)));
    }

    DeepSeekClient(String apiKey, String model, String apiUrl) {
        this(apiKey, model, apiUrl, null);
    }

    DeepSeekClient(String apiKey, String model, String apiUrl, int benchmarkMaxOutputTokens) {
        this(apiKey, model, apiUrl,
                Integer.valueOf(requireBenchmarkMaxOutputTokens(benchmarkMaxOutputTokens)));
    }

    private DeepSeekClient(String apiKey, String model, String apiUrl,
                           Integer benchmarkMaxOutputTokens) {
        this.apiKey = apiKey;
        this.model = model != null && !model.isBlank() ? model : DEFAULT_MODEL;
        this.apiUrl = apiUrl != null && !apiUrl.isBlank() ? apiUrl : API_URL;
        this.benchmarkMaxOutputTokens = benchmarkMaxOutputTokens;
    }

    @Override
    protected String getApiUrl() {
        return apiUrl;
    }

    @Override
    protected String getModel() {
        return model;
    }

    @Override
    protected String getApiKey() {
        return apiKey;
    }

    @Override
    protected boolean shouldSendReasoningContentInRequestHistory() {
        return true;
    }

    @Override
    protected void customizeRequestBody(ObjectNode requestBody) {
        if (benchmarkMaxOutputTokens != null) {
            requestBody.put("max_tokens", benchmarkMaxOutputTokens);
        }
        if (!usesV4Protocol()) {
            return;
        }
        requestBody.put("temperature", 1.0);
        requestBody.put("top_p", 0.95);
        requestBody.put("reasoning_effort", "max");
        requestBody.putObject("thinking").put("type", "enabled");
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        return chat(messages, tools, StreamListener.NO_OP);
    }

    @Override
    public ChatResponse chat(List<Message> messages,
                             List<Tool> tools,
                             StreamListener listener) throws IOException {
        StreamListener target = listener == null ? StreamListener.NO_OP : listener;
        if (!shouldFilterContentForDsml(tools, target)) {
            return normalizeDsml(super.chat(messages, tools, target), tools);
        }

        // DeepSeek V4 may encode tool calls as DSML in the content stream. Keep only the shortest
        // suffix that could become an opening tag across SSE chunk boundaries, so ordinary content
        // remains genuinely streaming. Once an opening tag is observed, hold that raw tail until
        // the completed response can be classified and either remove or replay it exactly once.
        RollingDsmlStreamListener filtering = new RollingDsmlStreamListener(target);
        ChatResponse raw = super.chat(messages, tools, filtering);
        ChatResponse normalized = normalizeDsml(raw, tools);
        filtering.complete(normalized != raw);
        return normalized;
    }

    /** 能转换的 DSML 已经变成 tool_calls 并从正文移除；正文里还剩 DSML 标记就说明没有执行。 */
    @Override
    public boolean looksLikeUnexecutedToolCall(String content) {
        return content != null && content.contains(DSML_OPEN_PREFIX);
    }

    @Override
    public boolean supportsImageInput() {
        // The official API routes both retired Flash aliases to V4.1 Flash.
        return switch (model.trim().toLowerCase(Locale.ROOT)) {
            case "deepseek-flash", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp" -> true;
            default -> false;
        };
    }

    @Override
    protected OkHttpClient httpClient() {
        return HTTP_1_1_CLIENT;
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public String getProviderName() {
        return "deepseek";
    }

    @Override
    public int maxContextWindow() {
        return 1_000_000;
    }

    @Override
    public boolean supportsPromptCaching() {
        return true;
    }

    @Override
    public String promptCacheMode() {
        return "automatic-prefix-cache";
    }

    private boolean usesV4Protocol() {
        String normalized = model.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("deepseek-flash") || normalized.startsWith("deepseek-v4-");
    }

    private static int requireBenchmarkMaxOutputTokens(int value) {
        if (value <= 0 || value > MAX_BENCHMARK_OUTPUT_TOKENS) {
            throw new IllegalArgumentException(
                    "benchmarkMaxOutputTokens must be between 1 and 16384");
        }
        return value;
    }

    private boolean shouldFilterContentForDsml(List<Tool> tools, StreamListener listener) {
        return effectiveDsml(usesV4Protocol())
                && tools != null
                && !tools.isEmpty()
                && listener != StreamListener.NO_OP;
    }

    private ChatResponse normalizeDsml(ChatResponse response, List<Tool> tools) {
        if (!effectiveDsml(usesV4Protocol()) || response == null || response.hasToolCalls()
                || response.content() == null || response.content().isBlank()
                || tools == null || tools.isEmpty()) {
            return response;
        }
        Set<String> allowedTools = tools.stream()
                .filter(Objects::nonNull)
                .map(Tool::name)
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        DsmlParseResult parsed = parseDsml(response.content(), allowedTools);
        if (parsed == null || parsed.toolCalls().isEmpty()) {
            return response;
        }
        return new ChatResponse(
                response.role(),
                parsed.content(),
                response.reasoningContent(),
                parsed.toolCalls(),
                response.inputTokens(),
                response.outputTokens(),
                response.cachedInputTokens(),
                response.resolvedModel(),
                response.usagePresent());
    }

    private DsmlParseResult parseDsml(String content, Set<String> allowedTools) {
        if (content.length() > MAX_DSML_CONTENT_CHARS) {
            return null;
        }
        Matcher block = DSML_TOOL_BLOCK.matcher(content);
        if (!block.find()) {
            return null;
        }
        int blockStart = block.start();
        int blockEnd = block.end();
        String body = block.group(2);
        if (block.find()) {
            return null;
        }
        Matcher invokes = DSML_INVOKE.matcher(body);
        List<ToolCall> calls = new ArrayList<>();
        int cursor = 0;
        while (invokes.find()) {
            if (!body.substring(cursor, invokes.start()).isBlank()) {
                return null;
            }
            Map<String, String> invokeAttributes = attributes(invokes.group(1));
            if (invokeAttributes == null || invokeAttributes.size() != 1) {
                return null;
            }
            String toolName = invokeAttributes.get("name");
            if (!isSafeName(toolName) || !allowedTools.contains(toolName)) {
                return null;
            }
            ObjectNode arguments = parseParameters(invokes.group(2));
            if (arguments == null) {
                return null;
            }
            calls.add(new ToolCall(
                    "dsml_call_" + dsmlCallSequence.incrementAndGet(),
                    new ToolCall.Function(toolName, arguments.toString())));
            if (calls.size() > MAX_DSML_TOOL_CALLS) {
                return null;
            }
            cursor = invokes.end();
        }
        if (calls.isEmpty() || !body.substring(cursor).isBlank()) {
            return null;
        }
        String remaining = (content.substring(0, blockStart) + content.substring(blockEnd)).trim();
        if (remaining.contains(DSML_TAG)) {
            return null;
        }
        return new DsmlParseResult(remaining, List.copyOf(calls));
    }

    private static ObjectNode parseParameters(String body) {
        ObjectNode arguments = mapper.createObjectNode();
        Matcher parameters = DSML_PARAMETER.matcher(body);
        int cursor = 0;
        int count = 0;
        while (parameters.find()) {
            if (!body.substring(cursor, parameters.start()).isBlank()) {
                return null;
            }
            Map<String, String> parameterAttributes = attributes(parameters.group(1));
            if (parameterAttributes == null
                    || !parameterAttributes.keySet().stream()
                    .allMatch(name -> name.equals("name") || name.equals("string"))) {
                return null;
            }
            String name = parameterAttributes.get("name");
            if (!isSafeName(name) || arguments.has(name)) {
                return null;
            }
            String stringFlag = parameterAttributes.get("string");
            if (stringFlag != null
                    && !"true".equalsIgnoreCase(stringFlag)
                    && !"false".equalsIgnoreCase(stringFlag)) {
                return null;
            }
            String value = unescapeDsml(parameters.group(2));
            if ("false".equalsIgnoreCase(stringFlag)) {
                try {
                    JsonNode parsed = mapper.reader()
                            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .readTree(value);
                    arguments.set(name, parsed == null ? mapper.nullNode() : parsed);
                } catch (IOException e) {
                    return null;
                }
            } else {
                arguments.put(name, value);
            }
            count++;
            if (count > MAX_DSML_PARAMETERS) {
                return null;
            }
            cursor = parameters.end();
        }
        return body.substring(cursor).isBlank() ? arguments : null;
    }

    private static Map<String, String> attributes(String raw) {
        String text = raw == null ? "" : raw.trim();
        Matcher matcher = DSML_ATTRIBUTE.matcher(text);
        Map<String, String> result = new LinkedHashMap<>();
        int cursor = 0;
        while (matcher.find()) {
            if (!text.substring(cursor, matcher.start()).isBlank()) {
                return null;
            }
            String previous = result.put(matcher.group(1), unescapeDsml(matcher.group(2)));
            if (previous != null) {
                return null;
            }
            cursor = matcher.end();
        }
        return !result.isEmpty() && text.substring(cursor).isBlank() ? result : null;
    }

    private static boolean isSafeName(String value) {
        return value != null && SAFE_TOOL_OR_PARAMETER_NAME.matcher(value).matches();
    }

    private static String unescapeDsml(String value) {
        return value.replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    /**
     * Streams text until a DSML tag begins. The candidate block and everything after
     * it stay ordered in {@code pendingTail}; completion either drops the block after a successful
     * strict parse or replays the untouched tail when the payload is malformed or unauthorized.
     */
    private static final class RollingDsmlStreamListener implements StreamListener {
        private final StreamListener target;
        private final StringBuilder pendingTail = new StringBuilder();
        private boolean capturingToolBlock;

        private RollingDsmlStreamListener(StreamListener target) {
            this.target = target;
        }

        @Override
        public void onReasoningDelta(String delta) {
            target.onReasoningDelta(delta);
        }

        @Override
        public void onContentDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            pendingTail.append(delta);
            if (capturingToolBlock) {
                return;
            }

            int openingTag = pendingTail.indexOf(DSML_OPEN_PREFIX);
            if (openingTag >= 0) {
                emit(pendingTail.substring(0, openingTag));
                pendingTail.delete(0, openingTag);
                capturingToolBlock = true;
                return;
            }

            int retained = longestOpeningTagPrefixSuffix(pendingTail);
            int safeLength = pendingTail.length() - retained;
            if (safeLength > 0) {
                emit(pendingTail.substring(0, safeLength));
                pendingTail.delete(0, safeLength);
            }
        }

        private void complete(boolean convertedDsml) {
            if (!capturingToolBlock || !convertedDsml) {
                emit(pendingTail.toString());
                pendingTail.setLength(0);
                return;
            }

            Matcher block = DSML_TOOL_BLOCK.matcher(pendingTail);
            if (block.find()) {
                emit(pendingTail.substring(block.end()));
            } else {
                // normalizeDsml() currently cannot convert without a closing tag. Keep this
                // fail-safe in case the parser evolves independently from the stream filter.
                emit(pendingTail.toString());
            }
            pendingTail.setLength(0);
        }

        private void emit(String value) {
            if (value != null && !value.isEmpty()) {
                target.onContentDelta(value);
            }
        }

        private static int longestOpeningTagPrefixSuffix(CharSequence value) {
            int maximum = Math.min(value.length(), DSML_OPEN_PREFIX.length() - 1);
            for (int length = maximum; length > 0; length--) {
                int valueStart = value.length() - length;
                boolean matches = true;
                for (int index = 0; index < length; index++) {
                    if (value.charAt(valueStart + index) != DSML_OPEN_PREFIX.charAt(index)) {
                        matches = false;
                        break;
                    }
                }
                if (matches) {
                    return length;
                }
            }
            return 0;
        }
    }

    private record DsmlParseResult(String content, List<ToolCall> toolCalls) {
    }

}
