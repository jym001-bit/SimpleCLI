package com.paicli.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Explicit, bounded model-list GET. Does not send conversation content or run inference. */
public final class ModelDiscovery {
    private static final int MAX_BODY_BYTES = 1_048_576;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();

    public List<String> discover(LlmClient client) throws IOException {
        AbstractOpenAiCompatibleClient transport = client instanceof ProfiledLlmClient profiled
                ? profiled.transport() : (AbstractOpenAiCompatibleClient) client;
        HttpUrl chat = HttpUrl.get(transport.getApiUrl());
        String suffix = "/chat/completions";
        if (!chat.encodedPath().endsWith(suffix)) throw new IOException("该供应商暂不支持发现模型，请用 /model add 手动添加");
        HttpUrl url = chat.newBuilder().encodedPath(chat.encodedPath().substring(0,
                chat.encodedPath().length() - suffix.length()) + "/models").query(null).fragment(null).build();
        return fetch(url, transport.getApiKey());
    }

    List<String> fetch(HttpUrl url, String apiKey) throws IOException {
        Request request = new Request.Builder().url(url).header("Authorization", "Bearer " + apiKey).get().build();
        try (Response response = http.newCall(request).execute()) {
            if (response.code() == 404 || response.code() == 405)
                throw new IOException("供应商未提供模型列表接口，请用 /model add 手动添加");
            if (response.code() == 401 || response.code() == 403)
                throw new IOException("模型列表鉴权失败，请检查该供应商的 API Key");
            if (!response.isSuccessful()) throw new IOException("模型列表请求失败，HTTP " + response.code());
            if (response.body() == null) throw new IOException("模型列表响应为空");
            byte[] bytes = response.body().byteStream().readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) throw new IOException("模型列表响应超过 1MB");
            JsonNode root;
            try { root = new ObjectMapper().readTree(bytes); }
            catch (IOException e) { throw new IOException("模型列表响应不是有效 JSON"); }
            if (root == null || !root.path("data").isArray()) throw new IOException("模型列表响应缺少 data 数组");
            LinkedHashSet<String> models = new LinkedHashSet<>();
            for (JsonNode item : root.path("data")) {
                if (!item.path("id").isTextual()) throw new IOException("模型列表包含无效 ID");
                String id = item.path("id").asText();
                if (!ModelCatalog.validModelId(id)) throw new IOException("模型列表包含无效 ID");
                models.add(id);
                if (models.size() > 2000) throw new IOException("模型列表超过 2000 个条目");
            }
            return List.copyOf(models);
        } catch (java.io.InterruptedIOException e) {
            throw new IOException("获取模型列表超时，请重试或手动添加");
        }
    }
}
