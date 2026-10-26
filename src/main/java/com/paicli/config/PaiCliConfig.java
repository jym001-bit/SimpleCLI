package com.paicli.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.paicli.llm.ModelProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PaiCliConfig {

    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".paicli");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.json");
    private static final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private String defaultProvider = "deepseek";
    private Map<String, ProviderConfig> providers = new LinkedHashMap<>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProviderConfig {
        private String apiKey;
        private String baseUrl;
        private String model;
        private String loraId;
        private Map<String, ModelProfile> models = new LinkedHashMap<>();
        private double temperature = 0.7;  // 默认温度
        private int maxTokens = 8192;      // 默认最大 token 数

        public ProviderConfig() {}

        public ProviderConfig(String apiKey, String baseUrl, String model) {
            this.apiKey = apiKey;
            this.baseUrl = baseUrl;
            this.model = model;
        }

        public Map<String, ModelProfile> getModels() { return models; }
        public void setModels(Map<String, ModelProfile> models) {
            this.models = models == null ? new LinkedHashMap<>() : new LinkedHashMap<>(models);
        }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getLoraId() { return loraId; }
        public void setLoraId(String loraId) { this.loraId = loraId; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    }

    public String getDefaultProvider() { return defaultProvider; }
    public void setDefaultProvider(String defaultProvider) { this.defaultProvider = defaultProvider; }
    public Map<String, ProviderConfig> getProviders() { return providers; }
    public void setProviders(Map<String, ProviderConfig> providers) { this.providers = providers; }

    public String getApiKey(String provider) {
        ProviderConfig providerConfig = providers.get(provider);
        if (providerConfig != null && providerConfig.getApiKey() != null && !providerConfig.getApiKey().isBlank()) {
            return providerConfig.getApiKey();
        }
        return loadApiKeyFromEnv(provider);
    }

    public String getModel(String provider) {
        ProviderConfig providerConfig = providers.get(provider);
        if (providerConfig != null && providerConfig.getModel() != null && !providerConfig.getModel().isBlank()) {
            return providerConfig.getModel();
        }
        return loadModelFromEnv(provider);
    }

    /** Explicit launch configuration wins over the last model saved by /model. */
    public String getStartupModel(String provider) {
        if (provider == null) return null;
        String explicit = loadModelFromEnv(provider);
        return explicit == null || explicit.isBlank() ? getModel(provider) : explicit;
    }

    public String getBaseUrl(String provider) {
        ProviderConfig providerConfig = providers.get(provider);
        if (providerConfig != null && providerConfig.getBaseUrl() != null && !providerConfig.getBaseUrl().isBlank()) {
            return providerConfig.getBaseUrl();
        }
        return loadBaseUrlFromEnv(provider);
    }

    public String getLoraId(String provider) {
        ProviderConfig providerConfig = providers.get(provider);
        if (providerConfig != null && providerConfig.getLoraId() != null && !providerConfig.getLoraId().isBlank()) {
            return providerConfig.getLoraId();
        }
        return loadLoraIdFromEnv(provider);
    }

    public static PaiCliConfig load() {
        if (Files.exists(CONFIG_FILE)) {
            try {
                return mapper.readValue(CONFIG_FILE.toFile(), PaiCliConfig.class);
            } catch (IOException e) {
                System.err.println("⚠️ 配置文件读取失败，使用默认配置: " + e.getMessage());
            }
        }
        return new PaiCliConfig();
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_DIR);
            mapper.writeValue(CONFIG_FILE.toFile(), this);
        } catch (IOException e) {
            System.err.println("⚠️ 配置保存失败: " + e.getMessage());
        }
    }

    /** Model management only reports success after an atomic config replacement. */
    public void saveOrThrow() throws IOException {
        saveOrThrow(CONFIG_FILE);
    }

    void saveOrThrow(Path destination) throws IOException {
        Path parent = destination.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".config-", ".tmp");
        try {
            mapper.writeValue(temporary.toFile(), this);
            try {
                Files.move(temporary, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    protected String loadModelFromEnv(String provider) {
        String envKey = switch (provider.toLowerCase()) {
            case "glm" -> "GLM_MODEL";
            case "deepseek" -> "DEEPSEEK_MODEL";
            case "hunyuan" -> "HUNYUAN_MODEL";
            case "kimi" -> "KIMI_MODEL";
            case "freellmapi" -> "FREELLMAPI_MODEL";
            case "xfyun" -> "XFYUN_MAAS_MODEL";
            default -> provider.toUpperCase() + "_MODEL";
        };

        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }

        String dotEnvValue = readFromDotEnv(envKey);
        if (dotEnvValue != null && !dotEnvValue.isBlank()) {
            return dotEnvValue.trim();
        }

        if ("kimi".equalsIgnoreCase(provider)) {
            String moonshotValue = System.getenv("MOONSHOT_MODEL");
            if (moonshotValue != null && !moonshotValue.isBlank()) {
                return moonshotValue.trim();
            }
            String moonshotDotEnvValue = readFromDotEnv("MOONSHOT_MODEL");
            if (moonshotDotEnvValue != null && !moonshotDotEnvValue.isBlank()) {
                return moonshotDotEnvValue.trim();
            }
        }

        if ("xfyun".equalsIgnoreCase(provider)) {
            String xfyunValue = System.getenv("XFYUN_MODEL");
            if (xfyunValue != null && !xfyunValue.isBlank()) {
                return xfyunValue.trim();
            }
            String xfyunDotEnvValue = readFromDotEnv("XFYUN_MODEL");
            if (xfyunDotEnvValue != null && !xfyunDotEnvValue.isBlank()) {
                return xfyunDotEnvValue.trim();
            }
        }

        return null;
    }

    private static String loadApiKeyFromEnv(String provider) {
        return loadApiKeyFromSources(provider, System::getenv, PaiCliConfig::readFromDotEnv);
    }

    static String loadApiKeyFromSources(String provider,
                                        Function<String, String> processEnvironment,
                                        Function<String, String> dotEnv) {
        String normalizedProvider = provider.toLowerCase(Locale.ROOT);
        List<String> keys = switch (normalizedProvider) {
            case "glm" -> List.of("GLM_API_KEY");
            case "deepseek" -> List.of("DEEPSEEK_API_KEY");
            case "hunyuan" -> List.of(
                    "HUNYUAN_API_KEY", "TOKENHUB_API_KEY", "TENCENTMAAS_API_KEY");
            case "step" -> List.of("STEP_API_KEY");
            case "kimi" -> List.of("KIMI_API_KEY", "MOONSHOT_API_KEY");
            case "freellmapi" -> List.of("FREELLMAPI_API_KEY");
            case "xfyun" -> List.of("XFYUN_MAAS_API_KEY", "XFYUN_API_KEY");
            default -> List.of(provider.toUpperCase() + "_API_KEY");
        };

        for (String key : keys) {
            String processValue = processEnvironment.apply(key);
            if (processValue != null && !processValue.isBlank()) {
                return processValue.trim();
            }
            String dotEnvValue = dotEnv.apply(key);
            if (dotEnvValue != null && !dotEnvValue.isBlank()) {
                return dotEnvValue.trim();
            }
        }
        return null;
    }

    private static String loadBaseUrlFromEnv(String provider) {
        String envKey = switch (provider.toLowerCase()) {
            case "step" -> "STEP_BASE_URL";
            case "hunyuan" -> "HUNYUAN_BASE_URL";
            case "kimi" -> "KIMI_BASE_URL";
            case "freellmapi" -> "FREELLMAPI_BASE_URL";
            case "xfyun" -> "XFYUN_MAAS_BASE_URL";
            default -> provider.toUpperCase() + "_BASE_URL";
        };

        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }

        String dotEnvValue = readFromDotEnv(envKey);
        if (dotEnvValue != null && !dotEnvValue.isBlank()) {
            return dotEnvValue.trim();
        }

        if ("kimi".equalsIgnoreCase(provider)) {
            String moonshotValue = System.getenv("MOONSHOT_BASE_URL");
            if (moonshotValue != null && !moonshotValue.isBlank()) {
                return moonshotValue.trim();
            }
            String moonshotDotEnvValue = readFromDotEnv("MOONSHOT_BASE_URL");
            if (moonshotDotEnvValue != null && !moonshotDotEnvValue.isBlank()) {
                return moonshotDotEnvValue.trim();
            }
        }

        if ("xfyun".equalsIgnoreCase(provider)) {
            String xfyunValue = System.getenv("XFYUN_BASE_URL");
            if (xfyunValue != null && !xfyunValue.isBlank()) {
                return xfyunValue.trim();
            }
            String xfyunDotEnvValue = readFromDotEnv("XFYUN_BASE_URL");
            if (xfyunDotEnvValue != null && !xfyunDotEnvValue.isBlank()) {
                return xfyunDotEnvValue.trim();
            }
        }

        return null;
    }

    private static String loadLoraIdFromEnv(String provider) {
        if (!"xfyun".equalsIgnoreCase(provider)) {
            return null;
        }

        String envValue = System.getenv("XFYUN_MAAS_LORA_ID");
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }

        String dotEnvValue = readFromDotEnv("XFYUN_MAAS_LORA_ID");
        if (dotEnvValue != null && !dotEnvValue.isBlank()) {
            return dotEnvValue.trim();
        }

        String xfyunValue = System.getenv("XFYUN_LORA_ID");
        if (xfyunValue != null && !xfyunValue.isBlank()) {
            return xfyunValue.trim();
        }

        String xfyunDotEnvValue = readFromDotEnv("XFYUN_LORA_ID");
        if (xfyunDotEnvValue != null && !xfyunDotEnvValue.isBlank()) {
            return xfyunDotEnvValue.trim();
        }
        return null;
    }

    private static String readFromDotEnv(String key) {
        File[] envFiles = { new File(".env"), new File(System.getProperty("user.home"), ".env") };
        for (File envFile : envFiles) {
            if (!envFile.exists()) continue;
            try (BufferedReader reader = new BufferedReader(new FileReader(envFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.startsWith(key + "=")) {
                        return line.substring((key + "=").length()).trim();
                    }
                }
            } catch (IOException ignored) {}
        }
        return null;
    }
}
