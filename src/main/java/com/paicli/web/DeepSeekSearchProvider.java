package com.paicli.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 通过 DeepSeek Anthropic Messages API 调用原生搜索。
 * 每次搜索包含一次独立模型请求；只有结构化搜索结果能提供 URL，回答正文不作为结果。
 */
public final class DeepSeekSearchProvider implements SearchProvider {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");
    private static final String ENDPOINT = "https://api.deepseek.com/anthropic/v1/messages";
    private static final String DEFAULT_MODEL = "deepseek-flash";

    private final String apiKey;
    private final String model;
    private final OkHttpClient httpClient;

    public DeepSeekSearchProvider(String apiKey) {
        this(apiKey, null);
    }

    public DeepSeekSearchProvider(String apiKey, String model) {
        this(apiKey, model, new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .callTimeout(120, TimeUnit.SECONDS)
                .build());
    }

    DeepSeekSearchProvider(String apiKey, String model, OkHttpClient httpClient) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
        // 搜索是计费的模型调用，不自动重试，也不携带凭证跟随重定向。
        this.httpClient = httpClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build();
    }

    @Override
    public String name() {
        return "deepseek";
    }

    @Override
    public boolean isReady() {
        return !apiKey.isBlank();
    }

    @Override
    public String unavailableHint() {
        return "DeepSeek 搜索未配置 API Key。请在 .env 中设置 DEEPSEEK_API_KEY；"
                + "需要指定搜索服务时设置 SEARCH_PROVIDER=deepseek。";
    }

    @Override
    public List<SearchResult> search(String query, int topK) throws IOException {
        if (!isReady()) {
            throw new IOException(unavailableHint());
        }
        if (query == null || query.isBlank()) {
            throw new IOException("DeepSeek 搜索关键词不能为空");
        }
        int limit = topK > 0 ? Math.min(topK, 50) : 10;
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("model", model);
        payload.put("max_tokens", 4096);
        payload.putArray("messages").addObject().put("role", "user")
                .putArray("content").addObject().put("type", "text")
                .put("text", "Perform a web search for the query: " + query);
        payload.putArray("tools").addObject()
                .put("type", "web_search_20250305")
                .put("name", "web_search")
                .put("max_uses", 5);

        Request request = new Request.Builder()
                .url(ENDPOINT)
                .header("x-api-key", apiKey)
                .header("Authorization", "Bearer " + apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("Accept", "application/json")
                .post(oneShotBody(payload.toString()))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                if (response.code() == 401 || response.code() == 403) {
                    throw new IOException("DeepSeek 搜索认证失败 (HTTP " + response.code()
                            + ")，请检查 DEEPSEEK_API_KEY 及账号权限");
                }
                throw new IOException("DeepSeek 搜索请求失败 (HTTP " + response.code() + ")");
            }
            return parse(response.body() == null ? "" : response.body().string(), limit);
        }
    }

    private static RequestBody oneShotBody(String json) {
        RequestBody delegate = RequestBody.create(json, JSON_MEDIA);
        // retryOnConnectionFailure=false 仍不足以阻止 503 + Retry-After: 0 的跟进请求。
        // 标记为一次性请求体，防止 HTTP 客户端重放计费的模型调用。
        return new RequestBody() {
            @Override
            public MediaType contentType() {
                return delegate.contentType();
            }

            @Override
            public long contentLength() throws IOException {
                return delegate.contentLength();
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                delegate.writeTo(sink);
            }

            @Override
            public boolean isOneShot() {
                return true;
            }
        };
    }

    private List<SearchResult> parse(String json, int limit) throws IOException {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (IOException e) {
            // 不把服务端正文或其中可能反射的凭证带入错误输出。
            throw new IOException("DeepSeek 搜索返回了无效 JSON");
        }
        if (root == null || !root.path("content").isArray()) {
            throw new IOException("DeepSeek 搜索响应缺少 content 数组");
        }
        JsonNode blocks = root.path("content");
        Map<String, String> snippets = new HashMap<>();
        for (JsonNode block : blocks) {
            if (!"text".equals(block.path("type").asText()) || !block.path("citations").isArray()) {
                continue;
            }
            for (JsonNode citation : block.path("citations")) {
                String url = citation.path("url").asText("").trim();
                String excerpt = citation.path("cited_text").asText("");
                if (!url.isBlank() && !excerpt.isBlank()) {
                    snippets.putIfAbsent(url, excerpt);
                }
            }
        }

        boolean foundSearchBlock = false;
        List<SearchResult> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode block : blocks) {
            if (!"web_search_tool_result".equals(block.path("type").asText())) {
                continue;
            }
            foundSearchBlock = true;
            JsonNode content = block.path("content");
            if ("web_search_tool_result_error".equals(content.path("type").asText())) {
                throw new IOException("DeepSeek 原生搜索工具执行失败");
            }
            if (!content.isArray()) {
                throw new IOException("DeepSeek 搜索结果块格式无效");
            }
            for (JsonNode item : content) {
                String type = item.path("type").asText();
                if ("web_search_tool_result_error".equals(type)) {
                    throw new IOException("DeepSeek 原生搜索工具执行失败");
                }
                if (!"web_search_result".equals(type)) {
                    continue;
                }
                String url = item.path("url").asText("").trim();
                if (HttpUrl.parse(url) == null || !seen.add(url) || results.size() >= limit) {
                    continue;
                }
                results.add(SearchResult.of(results.size() + 1, item.path("title").asText(""),
                        url, snippets.getOrDefault(url, "")));
            }
        }
        if (!foundSearchBlock) {
            throw new IOException("DeepSeek 未返回结构化搜索结果，可能未触发原生搜索");
        }
        return results;
    }
}
