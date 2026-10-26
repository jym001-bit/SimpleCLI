package com.paicli.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.PaiCliConfig;
import com.paicli.llm.*;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ModelCommandsTest {
    static class LocalConfig extends PaiCliConfig {
        String saved;
        boolean fail;
        int saves;
        @Override public void saveOrThrow() throws IOException {
            if (fail) throw new IOException("disk failure");
            saved = new ObjectMapper().writeValueAsString(this);
            saves++;
        }
        @Override public String getModel(String provider) {
            var value = getProviders().get(provider);
            return value == null ? null : value.getModel();
        }
        @Override public String getApiKey(String provider) {
            var value = getProviders().get(provider);
            return value == null ? null : value.getApiKey();
        }
        @Override public String getBaseUrl(String provider) {
            var value = getProviders().get(provider);
            return value == null ? null : value.getBaseUrl();
        }
    }

    @Test
    void addPersistsCapabilitiesWithoutSwitchingThenExplicitSwitchUsesNewModel() throws Exception {
        LocalConfig config = new LocalConfig();
        config.getProviders().put("deepseek", new PaiCliConfig.ProviderConfig("key", null, "deepseek-v4-pro"));
        String output = ModelCommands.handle("add deepseek next --like deepseek-flash --context 200000 --vision false --effort high", config, null);
        assertTrue(output.contains("已保存"));
        assertEquals("deepseek", config.getDefaultProvider());
        assertEquals("deepseek-v4-pro", config.getModel("deepseek"));
        PaiCliConfig restarted = new ObjectMapper().readValue(config.saved, PaiCliConfig.class);
        var restored = restarted.getProviders().get("deepseek").getModels().get("next");
        assertEquals(200_000, restored.contextWindow());
        assertEquals("deepseek-flash", restored.template());
        assertFalse(restored.imageInput());
        var selection = Main.resolveModelSelection("next", config);
        var client = ModelCommands.switchModel(config, selection);
        assertEquals("next", client.getModelName());
        assertEquals(200_000, client.maxContextWindow());
        assertFalse(client.supportsImageInput());
        assertEquals("deepseek", config.getDefaultProvider());
        assertEquals("next", config.getModel("deepseek"));
    }

    @Test
    void updatingOneFieldPreservesOtherOverridesAndRefreshOnlyAdds() throws Exception {
        LocalConfig config = new LocalConfig();
        ModelCommands.handle("add deepseek next --like deepseek-flash --vision false --context 200000", config, null);
        ModelCommands.handle("add deepseek next --context 300000", config, null);
        var prior = config.getProviders().get("deepseek").getModels().get("next");
        assertFalse(prior.imageInput());
        assertEquals("deepseek-flash", prior.template());
        assertEquals(1, ModelCatalog.mergeDiscovered(config, "deepseek", List.of("next", "deepseek-flash", "new", "new")));
        assertEquals(prior, config.getProviders().get("deepseek").getModels().get("next"));
        int saves = config.saves;
        assertEquals(0, ModelCatalog.mergeDiscovered(config, "deepseek", List.of("new")));
        assertEquals(saves, config.saves);
        var client = LlmClientFactory.create("deepseek", "new", keyConfig(config));
        assertEquals(128_000, client.maxContextWindow());
        assertFalse(client.supportsImageInput());
    }

    private static LocalConfig keyConfig(LocalConfig config) {
        config.getProviders().get("deepseek").setApiKey("key");
        return config;
    }

    @Test
    void refreshUsesConfiguredServerAndFailurePreservesCatalog() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            LocalConfig config = new LocalConfig();
            config.getProviders().put("freellmapi", new PaiCliConfig.ProviderConfig("key", server.url("/v1").toString(), "current"));
            server.enqueue(new MockResponse().setBody("{\"data\":[{\"id\":\"vendor/next\"}]}"));
            ModelCommands.handle("refresh freellmapi", config, null);
            assertEquals("/v1/models", server.takeRequest().getPath());
            assertEquals("current", config.getModel("freellmapi"));
            String saved = config.saved;
            server.enqueue(new MockResponse().setResponseCode(500));
            assertThrows(IOException.class, () -> ModelCommands.handle("refresh freellmapi", config, null));
            assertEquals(saved, config.saved);
            assertEquals(1, config.getProviders().get("freellmapi").getModels().size());
            assertEquals("freellmapi", Main.resolveModelSelection("freellmapi/vendor/next", config).provider());
            assertEquals("vendor/next", Main.resolveModelSelection("freellmapi/vendor/next", config).model());
        }
    }

    @Test
    void failedSaveRollsBackCatalogAndSelection() throws Exception {
        LocalConfig config = new LocalConfig();
        config.fail = true;
        assertThrows(IOException.class, () -> ModelCommands.handle("add deepseek next --like deepseek-flash", config, null));
        assertFalse(config.getProviders().containsKey("deepseek"));
        config.getProviders().put("deepseek", new PaiCliConfig.ProviderConfig("key", null, "deepseek-v4-pro"));
        assertThrows(IOException.class, () -> ModelCommands.switchModel(config, new Main.ModelSelection("deepseek", "next", true)));
        assertEquals("deepseek-v4-pro", config.getModel("deepseek"));
        assertEquals("deepseek", config.getDefaultProvider());
    }

    @Test
    void invalidInputAndMissingKeysDoNotSaveOrChangeSelection() {
        LocalConfig config = new LocalConfig();
        for (String input : List.of("refresh", "refresh unknown", "refresh deepseek extra", "add deepseek",
                "add deepseek next --vision yes", "add deepseek next --context -1", "add deepseek next --like missing",
                "add deepseek next --context 100000 --max-output 200000", "add deepseek next --thinking disabled --effort high",
                "add deepseek next --vision true --vision false", "add glm next --dsml true", "list deepseek extra")) {
            assertThrows(IllegalArgumentException.class, () -> ModelCommands.handle(input, config, null), input);
        }
        assertThrows(IllegalArgumentException.class, () -> ModelCommands.switchModel(config, new Main.ModelSelection("deepseek", "next", true)));
        assertTrue(config.getProviders().isEmpty());
        assertEquals(0, config.saves);
    }

    @Test
    void sameIdAcrossProvidersRequiresQualificationAndListDoesNotSave() throws Exception {
        LocalConfig config = new LocalConfig();
        ModelCommands.handle("add deepseek next", config, null);
        ModelCommands.handle("add kimi next", config, null);
        assertThrows(IllegalArgumentException.class, () -> Main.resolveModelSelection("next", config));
        assertEquals("kimi", Main.resolveModelSelection("kimi/next", config).provider());
        int saves = config.saves;
        var result = ModelCommands.handle("list", config, null);
        assertTrue(result.contains("DeepSeek ·"));
        assertTrue(result.contains("kimi ·"));
        assertEquals(2, result.lines().filter(line -> line.contains("next")).count());
        assertEquals(saves, config.saves);
    }

    @Test
    void deepseekListSeparatesCurrentModelsFromLegacyAliases() throws Exception {
        LocalConfig config = new LocalConfig();
        assertEquals(List.of("deepseek-flash", "deepseek-v4-pro"),
                List.copyOf(ModelCatalog.entries(config, "deepseek").keySet()));
        String cleanList = ModelCommands.handle("list deepseek", config, null);
        assertTrue(cleanList.contains("DeepSeek · 2 个模型"));
        assertFalse(cleanList.contains("deepseek/deepseek-v4-flash |"));
        assertFalse(cleanList.contains("deepseek/deepseek-v4-flash-vision-exp |"));

        config.getProviders().put("deepseek", new PaiCliConfig.ProviderConfig("key", null, "deepseek-v4-flash"));
        var client = LlmClientFactory.create("deepseek", config);
        String output = ModelCommands.handle("list deepseek", config, client);
        assertTrue(output.contains("● deepseek-v4-flash"));
        assertTrue(output.contains("兼容旧名"));
        assertTrue(output.contains("旧名映射：deepseek-v4-flash → deepseek-flash"));
        assertEquals("deepseek-v4-flash", client.getModelName(), "列表不能改写请求 ID");
        assertEquals("deepseek-v4-flash", config.getModel("deepseek"));
        assertEquals(0, config.saves);
        assertEquals("deepseek-v4-flash（兼容旧名 → deepseek-flash）",
                ModelCatalog.displayName("deepseek", client.getModelName()));
        assertEquals("deepseek-v4-flash", ModelCatalog.displayName("freellmapi", "deepseek-v4-flash"));
    }

    @Test
    void refreshingLegacyIdsDoesNotCreateConservativeProfilesOrMisleadingHints() throws Exception {
        LocalConfig config = new LocalConfig();
        config.getProviders().put("deepseek", new PaiCliConfig.ProviderConfig("key", null, "deepseek-v4-flash"));
        var client = LlmClientFactory.create("deepseek", config);
        int[] lookups = {0};
        String output = ModelCommands.handle("refresh deepseek", config, client, requestedClient -> {
            lookups[0]++;
            assertEquals("deepseek", requestedClient.getProviderName());
            return List.of("deepseek-flash", "deepseek-v4-pro", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp");
        });
        assertTrue(output.contains("返回 4 个 · 新增 0 个"));
        assertTrue(output.contains("兼容旧名 2 个"));
        assertTrue(output.contains("/model deepseek-flash"));
        assertFalse(output.contains("能力待配置"));
        assertFalse(output.contains("128k"));
        assertEquals(1, lookups[0]);
        assertTrue(config.getProviders().get("deepseek").getModels().isEmpty());
        assertEquals(0, config.saves);
        assertEquals("deepseek-v4-flash", config.getModel("deepseek"));
    }

    @Test
    void refreshStillAddsUnknownModelsAndPreservesUserOwnedAliasProfiles() throws Exception {
        LocalConfig config = new LocalConfig();
        ModelCommands.handle("add deepseek deepseek-v4-flash --context 200000", config, null);
        var savedAlias = config.getProviders().get("deepseek").getModels().get("deepseek-v4-flash");
        assertNull(savedAlias.imageInput(), "已知别名不应被写成未知文本模型");
        assertEquals(1, ModelCatalog.mergeDiscovered(config, "deepseek",
                List.of("deepseek-v4-flash", "deepseek-v4-flash-vision-exp", "future-model")));
        assertEquals(savedAlias, config.getProviders().get("deepseek").getModels().get("deepseek-v4-flash"));
        assertFalse(config.getProviders().get("deepseek").getModels().containsKey("deepseek-v4-flash-vision-exp"));
        assertEquals(ModelProfile.discovered(), config.getProviders().get("deepseek").getModels().get("future-model"));
    }

    @Test
    void legacyNamesRemainValidForExplicitSelectionAndTemplates() throws Exception {
        LocalConfig config = new LocalConfig();
        config.getProviders().put("deepseek", new PaiCliConfig.ProviderConfig("key", null, "deepseek-flash"));
        var selection = Main.resolveModelSelection("deepseek-v4-flash-vision-exp", config);
        assertEquals("deepseek-v4-flash-vision-exp", ModelCommands.switchModel(config, selection).getModelName());
        ModelCommands.handle("add deepseek custom --like deepseek-v4-flash", config, null);
        var preview = ModelCatalog.preview(config, "deepseek", "custom");
        assertEquals(1_000_000, preview.maxContextWindow());
        assertTrue(preview.supportsImageInput());
    }

    @Test
    void detailsKeepCapabilitiesOutOfTheListWithoutSaving() throws Exception {
        LocalConfig config = new LocalConfig();
        ModelCommands.handle("add deepseek next --like deepseek-flash --context 200000 --effort high --max-output 8000", config, null);
        int saves = config.saves;
        String list = ModelCommands.handle("list deepseek", config, null);
        assertFalse(list.contains("effort="));
        assertFalse(list.contains("context="));
        assertTrue(list.contains("200k"));
        String details = ModelCommands.handle("info next", config, null);
        assertTrue(details.contains("能力模板：deepseek-flash"));
        assertTrue(details.contains("推理强度：high"));
        assertTrue(details.contains("输出上限：8000"));
        assertEquals(saves, config.saves);
        assertThrows(IllegalArgumentException.class, () -> ModelCommands.handle("info", config, null));
        assertThrows(IllegalArgumentException.class, () -> ModelCommands.handle("info unknown-provider", config, null));
    }

    @Test
    void commandParserSeparatesManagementFromSwitching() {
        for (String command : List.of("/model list", "/model info", "/model info deepseek-flash", "/model list deepseek", "/model REFRESH deepseek", "/model add deepseek next")) {
            assertEquals(CliCommandParser.CommandType.MODEL_MANAGE, CliCommandParser.parse(command).type(), command);
        }
        assertEquals(CliCommandParser.CommandType.SWITCH_MODEL, CliCommandParser.parse("/model deepseek-flash").type());
    }
}
