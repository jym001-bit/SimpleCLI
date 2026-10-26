package com.paicli.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Embedding 客户端，支持 Ollama 本地模型和 OpenAI 兼容的远程 API
 */
public class EmbeddingClient {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    private final String provider;
    private final String model;
    private final String baseUrl;
    private final String apiKey;
    private final int batchSize;
    // 老版本 Ollama（0.3 之前）没有 /api/embed，遇到 404 后本进程改回逐条调用
    private volatile boolean ollamaBatchUnsupported;

    public EmbeddingClient() {
        this.provider = getEnv("EMBEDDING_PROVIDER", "ollama");
        this.model = getEnv("EMBEDDING_MODEL", "nomic-embed-text:latest");
        this.baseUrl = getEnv("EMBEDDING_BASE_URL", inferDefaultUrl(provider));
        this.apiKey = getEnv("EMBEDDING_API_KEY", "");
        this.batchSize = parseBatchSize(getEnv("EMBEDDING_BATCH_SIZE", ""));
    }

    public EmbeddingClient(String provider, String model, String baseUrl, String apiKey) {
        this.provider = provider;
        this.model = model;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.batchSize = DEFAULT_BATCH_SIZE;
    }

    // 实测本地 Ollama nomic-embed-text：逐条 16.4ms/条，10 条一批 8.1ms/条，50 条一批 7.2ms/条
    static final int DEFAULT_BATCH_SIZE = 32;

    // 安全截断长度（中文密集文本 2000 字符 ≈ 4000~6000 token，适配 8192 上下文模型）
    private static final int MAX_INPUT_CHARS = 2000;

    /**
     * 获取文本的向量表示
     */
    public float[] embed(String text) throws IOException {
        if (text == null || text.isEmpty()) {
            return new float[0];
        }

        // 截断过长文本，防止 API 报错
        String input = text.length() > MAX_INPUT_CHARS
                ? text.substring(0, MAX_INPUT_CHARS)
                : text;

        return switch (provider.toLowerCase()) {
            case "ollama" -> embedOllama(input);
            case "openai", "zhipu", "glm" -> embedOpenAICompatible(input);
            default -> embedOllama(input);
        };
    }

    /**
     * 批量向量化，返回顺序和输入一致；索引时用它代替逐条 {@link #embed(String)}。
     * 每 {@code EMBEDDING_BATCH_SIZE} 条（默认 32）发一次请求，空文本不发请求、直接给空向量。
     */
    public List<float[]> embedAll(List<String> texts) throws IOException {
        List<float[]> result = new ArrayList<>(texts.size());
        List<String> batch = new ArrayList<>();
        List<Integer> batchSlots = new ArrayList<>();
        for (String text : texts) {
            if (text == null || text.isEmpty()) {
                result.add(new float[0]);
                continue;
            }
            result.add(null);
            batch.add(text.length() > MAX_INPUT_CHARS ? text.substring(0, MAX_INPUT_CHARS) : text);
            batchSlots.add(result.size() - 1);
            if (batch.size() == batchSize) {
                fill(result, batchSlots, embedBatch(batch));
                batch.clear();
                batchSlots.clear();
            }
        }
        if (!batch.isEmpty()) {
            fill(result, batchSlots, embedBatch(batch));
        }
        return result;
    }

    private static void fill(List<float[]> result, List<Integer> slots, List<float[]> embeddings) throws IOException {
        if (embeddings.size() != slots.size()) {
            throw new IOException("Embedding API 返回 " + embeddings.size() + " 个向量，请求了 " + slots.size() + " 条");
        }
        for (int i = 0; i < slots.size(); i++) {
            result.set(slots.get(i), embeddings.get(i));
        }
    }

    private List<float[]> embedBatch(List<String> inputs) throws IOException {
        return switch (provider.toLowerCase()) {
            case "openai", "zhipu", "glm" -> embedOpenAICompatibleBatch(inputs);
            default -> embedOllamaBatch(inputs);
        };
    }

    private List<float[]> embedOllamaBatch(List<String> inputs) throws IOException {
        if (!ollamaBatchUnsupported) {
            ObjectNode requestBody = mapper.createObjectNode();
            requestBody.put("model", model);
            ArrayNode input = requestBody.putArray("input");
            inputs.forEach(input::add);
            try {
                String responseBody = postJson(baseUrl + "/api/embed", requestBody.toString(), false);
                JsonNode embeddings = mapper.readTree(responseBody).path("embeddings");
                if (!embeddings.isArray()) {
                    throw new IOException("Ollama 返回的 embeddings 格式不正确: " + abbreviate(responseBody));
                }
                List<float[]> result = new ArrayList<>(embeddings.size());
                embeddings.forEach(node -> result.add(toVector(node)));
                return result;
            } catch (NotFoundException e) {
                // 模型不存在也是 404（body 是 {"error":"model ... not found"}），那种情况逐条调用也没用
                if (!e.getMessage().contains("page not found")) {
                    throw e;
                }
                ollamaBatchUnsupported = true;
            }
        }
        List<float[]> result = new ArrayList<>(inputs.size());
        for (String text : inputs) {
            result.add(embedOllama(text));
        }
        return result;
    }

    private List<float[]> embedOpenAICompatibleBatch(List<String> inputs) throws IOException {
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", model);
        ArrayNode input = requestBody.putArray("input");
        inputs.forEach(input::add);

        String responseBody = postJson(baseUrl + "/embeddings", requestBody.toString(), true);
        JsonNode data = mapper.readTree(responseBody).path("data");
        if (!data.isArray()) {
            throw new IOException("API 返回的 embedding 格式不正确: " + abbreviate(responseBody));
        }
        // 按 index 字段放回原位，不依赖返回顺序
        float[][] ordered = new float[inputs.size()][];
        int position = 0;
        for (JsonNode item : data) {
            int index = item.has("index") ? item.path("index").asInt() : position;
            if (index < 0 || index >= ordered.length) {
                throw new IOException("API 返回的 embedding 序号越界: " + index);
            }
            ordered[index] = toVector(item.path("embedding"));
            position++;
        }
        List<float[]> result = new ArrayList<>(ordered.length);
        for (float[] vector : ordered) {
            if (vector == null) {
                throw new IOException("API 返回的 embedding 数量不足，请求了 " + inputs.size() + " 条");
            }
            result.add(vector);
        }
        return result;
    }

    private static float[] toVector(JsonNode node) {
        float[] vector = new float[node.size()];
        for (int i = 0; i < node.size(); i++) {
            vector[i] = (float) node.get(i).asDouble();
        }
        return vector;
    }

    private static String abbreviate(String text) {
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }

    static int parseBatchSize(String configured) {
        if (configured == null || configured.isBlank()) {
            return DEFAULT_BATCH_SIZE;
        }
        try {
            int value = Integer.parseInt(configured.trim());
            return value > 0 ? value : DEFAULT_BATCH_SIZE;
        } catch (NumberFormatException e) {
            return DEFAULT_BATCH_SIZE;
        }
    }

    private float[] embedOllama(String text) throws IOException {
        String url = baseUrl + "/api/embeddings";

        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("prompt", text);

        String responseBody = postJson(url, requestBody.toString(), false);
        JsonNode root = mapper.readTree(responseBody);
        JsonNode embeddingNode = root.path("embedding");

        if (!embeddingNode.isArray()) {
            throw new IOException("Ollama 返回的 embedding 格式不正确: " + responseBody);
        }

        float[] embedding = new float[embeddingNode.size()];
        for (int i = 0; i < embeddingNode.size(); i++) {
            embedding[i] = (float) embeddingNode.get(i).asDouble();
        }
        return embedding;
    }

    private float[] embedOpenAICompatible(String text) throws IOException {
        String url = baseUrl + "/embeddings";

        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("input", text);

        String responseBody = postJson(url, requestBody.toString(), true);
        JsonNode root = mapper.readTree(responseBody);
        JsonNode data = root.path("data");

        if (!data.isArray() || data.isEmpty()) {
            throw new IOException("API 返回的 embedding 格式不正确: " + responseBody);
        }

        JsonNode embeddingNode = data.get(0).path("embedding");
        float[] embedding = new float[embeddingNode.size()];
        for (int i = 0; i < embeddingNode.size(); i++) {
            embedding[i] = (float) embeddingNode.get(i).asDouble();
        }
        return embedding;
    }

    private String postJson(String url, String jsonBody, boolean useAuth) throws IOException {
        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(body);

        if (useAuth && apiKey != null && !apiKey.isEmpty()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        try (Response response = HTTP_CLIENT.newCall(builder.build()).execute()) {
            ResponseBody responseBody = response.body();
            if (!response.isSuccessful()) {
                String error = responseBody != null ? responseBody.string() : "无响应";
                String message = "Embedding API 请求失败 [" + response.code() + "]: " + error;
                throw response.code() == 404 ? new NotFoundException(message) : new IOException(message);
            }
            if (responseBody == null) {
                throw new IOException("Embedding API 返回空响应体");
            }
            return responseBody.string();
        }
    }

    private static final class NotFoundException extends IOException {
        NotFoundException(String message) {
            super(message);
        }
    }

    private static String inferDefaultUrl(String provider) {
        return switch (provider.toLowerCase()) {
            case "ollama" -> "http://localhost:11434";
            case "zhipu", "glm" -> "https://open.bigmodel.cn/api/paas/v4";
            default -> "http://localhost:11434";
        };
    }

    private static String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        value = System.getProperty(key);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        return defaultValue;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public int getBatchSize() {
        return batchSize;
    }
}
