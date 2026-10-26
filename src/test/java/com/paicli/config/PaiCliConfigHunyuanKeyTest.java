package com.paicli.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PaiCliConfigHunyuanKeyTest {

    @Test
    void resolvesHunyuanAliasesInDocumentedPriorityWithoutExposingValues() {
        Map<String, String> process = Map.of(
                "TOKENHUB_API_KEY", "tokenhub-marker",
                "TENCENTMAAS_API_KEY", "tencentmaas-marker");
        Map<String, String> dotEnv = Map.of("HUNYUAN_API_KEY", "canonical-marker");

        String resolved = PaiCliConfig.loadApiKeyFromSources(
                "hunyuan", process::get, dotEnv::get);

        assertEquals("canonical-marker", resolved);
    }

    @Test
    void fallsBackFromBlankCanonicalToTokenHubThenTencentMaas() {
        Map<String, String> process = Map.of(
                "HUNYUAN_API_KEY", "  ",
                "TENCENTMAAS_API_KEY", "tencentmaas-marker");
        Map<String, String> dotEnv = Map.of("TOKENHUB_API_KEY", " tokenhub-marker ");

        String tokenHubResolved = PaiCliConfig.loadApiKeyFromSources(
                "hunyuan", process::get, dotEnv::get);
        String tencentMaasResolved = PaiCliConfig.loadApiKeyFromSources(
                "hunyuan",
                Map.of("HUNYUAN_API_KEY", "  ", "TOKENHUB_API_KEY", " ")::get,
                Map.of("TENCENTMAAS_API_KEY", " tencentmaas-marker ")::get);

        assertEquals("tokenhub-marker", tokenHubResolved);
        assertEquals("tencentmaas-marker", tencentMaasResolved);
    }

    @Test
    void hunyuanAliasesDoNotAffectOtherProviders() {
        Map<String, String> process = Map.of(
                "TOKENHUB_API_KEY", "tokenhub-marker",
                "TENCENTMAAS_API_KEY", "tencentmaas-marker");

        String resolved = PaiCliConfig.loadApiKeyFromSources(
                "glm", process::get, ignored -> null);

        assertNull(resolved);
    }
}
