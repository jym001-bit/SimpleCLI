package com.paicli.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchProviderFactoryTest {

    @Test
    void explicitProviderOverridesAutoDetect() {
        assertEquals("zhipu", SearchProviderFactory.pickProvider("zhipu", null, "key", "http://localhost", null));
        assertEquals("searxng", SearchProviderFactory.pickProvider("searxng", "glm", "key", "http://localhost", null));
        assertEquals("serpapi", SearchProviderFactory.pickProvider("serpapi", null, null, "http://localhost", null));
    }

    @Test
    void autoSelectsZhipuWhenGlmKeyPresent() {
        // 保留既有搜索配置的自动选择优先级
        assertEquals("zhipu", SearchProviderFactory.pickProvider(null, "glm-key", null, null, null));
        assertEquals("zhipu", SearchProviderFactory.pickProvider(null, "glm-key", "serp-key", "http://localhost", null));
    }

    @Test
    void autoSelectsSerpapiWhenOnlySerpKeyPresent() {
        assertEquals("serpapi", SearchProviderFactory.pickProvider(null, null, "any-key", null, null));
        assertEquals("serpapi", SearchProviderFactory.pickProvider("", "", "any-key", null, null));
    }

    @Test
    void autoSelectsSearxngWhenOnlyUrlPresent() {
        assertEquals("searxng", SearchProviderFactory.pickProvider(null, null, null, "http://localhost:8888", null));
        assertEquals("searxng", SearchProviderFactory.pickProvider(null, "", "", "http://localhost:8888", null));
    }

    @Test
    void fallsBackToZhipuPlaceholder() {
        assertEquals("zhipu", SearchProviderFactory.pickProvider(null, null, null, null, null));
    }

    @Test
    void selectsDeepseekWhenNoExistingSearchBackendIsConfigured() {
        assertEquals("deepseek", SearchProviderFactory.pickProvider(null, null, null, null, "ds-key"));
        assertEquals("deepseek", SearchProviderFactory.pickProvider(" ", " ", "", null, "ds-key"));
        assertEquals("zhipu", SearchProviderFactory.pickProvider(null, null, null, null, " "));
    }

    @Test
    void preservesExistingBackendsWhenDeepseekKeyIsAlsoPresent() {
        assertEquals("zhipu", SearchProviderFactory.pickProvider(null, "glm", "serp", "http://localhost", "ds"));
        assertEquals("serpapi", SearchProviderFactory.pickProvider(null, null, "serp", "http://localhost", "ds"));
        assertEquals("searxng", SearchProviderFactory.pickProvider(null, null, null, "http://localhost", "ds"));
    }

    @Test
    void explicitDeepseekOverridesOtherKeysEvenWhenItsOwnKeyIsMissing() {
        assertEquals("deepseek", SearchProviderFactory.pickProvider(" DeepSeek ", "glm", "serp", "http://localhost", null));
        assertEquals("zhipu", SearchProviderFactory.pickProvider("zhipu", null, null, null, "ds"));
    }

    @Test
    void normalizesExplicitToLowercase() {
        assertEquals("searxng", SearchProviderFactory.pickProvider("SEARXNG", null, null, null, null));
        assertEquals("serpapi", SearchProviderFactory.pickProvider("  SerpAPI  ", null, null, null, null));
        assertEquals("zhipu", SearchProviderFactory.pickProvider("ZHIPU", null, null, null, null));
    }
}
