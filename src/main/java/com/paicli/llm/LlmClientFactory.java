package com.paicli.llm;

import com.paicli.config.PaiCliConfig;

public class LlmClientFactory {

    private LlmClientFactory() {}

    public static LlmClient create(String provider, PaiCliConfig config) {
        return create(provider, null, config);
    }

    /**
     * auto 模式审批分类器用的轻量客户端：沿用当前供应商与 Key，关闭思考、限制输出长度，
     * 只做一次短判断。{@code classifierModel} 为空时使用该供应商当前配置的模型。
     */
    public static LlmClient createApprovalClassifier(String provider, String classifierModel, PaiCliConfig config) {
        LlmClient base = create(provider, classifierModel, config);
        if (base == null) {
            return null;
        }
        AbstractOpenAiCompatibleClient transport = base instanceof ProfiledLlmClient profiled
                ? profiled.transport() : (AbstractOpenAiCompatibleClient) base;
        ModelProfile classifierProfile = new ModelProfile(
                null, null, false, "disabled", null, false, false, 400, "approval-classifier");
        return transport.withModelProfile(base.getModelName(), classifierProfile);
    }

    public static LlmClient create(String provider, String selectedModel, PaiCliConfig config) {
        if (provider == null) return null;

        String normalized = normalizeProvider(provider);
        String configuredProvider = provider.trim().toLowerCase();
        String apiKey = config.getApiKey(normalized);
        if ((apiKey == null || apiKey.isBlank()) && !configuredProvider.equals(normalized)) {
            apiKey = config.getApiKey(configuredProvider);
        }
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }

        String model = firstConfigured(selectedModel, firstConfigured(config.getModel(normalized),
                configuredProvider.equals(normalized) ? null : config.getModel(configuredProvider)));
        String baseUrl = firstConfigured(config.getBaseUrl(normalized),
                configuredProvider.equals(normalized) ? null : config.getBaseUrl(configuredProvider));
        String loraId = firstConfigured(config.getLoraId(normalized),
                configuredProvider.equals(normalized) ? null : config.getLoraId(configuredProvider));

        AbstractOpenAiCompatibleClient client = createClient(normalized, apiKey, model, baseUrl, loraId);
        if (client == null) return null;
        String requestedModel = client.getModelName();
        PaiCliConfig.ProviderConfig providerConfig = config.getProviders().get(normalized);
        ModelProfile profile = providerConfig == null ? null : providerConfig.getModels().get(requestedModel);
        if (profile == null) return client;
        if (profile.template() != null) client = createClient(normalized, apiKey, profile.template(), baseUrl, loraId);
        return client.withModelProfile(requestedModel, profile);
    }

    private static AbstractOpenAiCompatibleClient createClient(String provider, String apiKey, String model,
                                                               String baseUrl, String loraId) {
        return switch (provider) {
            case "glm" -> new GLMClient(apiKey, model);
            case "deepseek" -> new DeepSeekClient(apiKey, model);
            case "hunyuan" -> new HunyuanClient(apiKey, model, baseUrl);
            case "step" -> new StepClient(apiKey, model, baseUrl);
            case "kimi" -> new KimiClient(apiKey, model, baseUrl);
            case "freellmapi" -> new FreeLlmApiClient(apiKey, model, baseUrl);
            case "xfyun" -> new XfyunMaaSClient(apiKey, model, baseUrl, loraId);
            case "agnes" -> new AgnesClient(apiKey, model, baseUrl);
            default -> null;
        };
    }

    public static LlmClient createFromConfig(PaiCliConfig config) {
        LlmClient client = create(config.getDefaultProvider(), config.getStartupModel(config.getDefaultProvider()), config);
        if (client != null) {
            return client;
        }

        for (String provider : new String[]{"deepseek", "glm", "hunyuan", "step", "kimi", "freellmapi", "xfyun", "agnes"}) {
            client = create(provider, config.getStartupModel(provider), config);
            if (client != null) {
                return client;
            }
        }

        return null;
    }

    private static String normalizeProvider(String provider) {
        String normalized = provider.trim().toLowerCase();
        return switch (normalized) {
            case "stepfun", "step-fun" -> "step";
            case "hy4", "hy4-preview", "tokenhub", "tencent-hunyuan", "tencent_hunyuan" -> "hunyuan";
            case "moonshot", "moonshotai", "moonshot-ai" -> "kimi";
            case "free-llm-api", "free_llm_api", "freellm", "free-llm" -> "freellmapi";
            case "xfyun-maas", "xfyun_maas", "iflytek", "iflytek-maas", "iflytek_maas", "maas" -> "xfyun";
            case "agnes-ai", "agnes_ai", "sapiens", "sapiens-ai", "sapiens_ai" -> "agnes";
            default -> normalized;
        };
    }

    private static String firstConfigured(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }
}
