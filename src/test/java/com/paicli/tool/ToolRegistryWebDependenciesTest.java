package com.paicli.tool;

import com.paicli.eval.benchmark.mock.D4WebMock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryWebDependenciesTest {
    @Test void partialInstallHasNoEffectAndCompleteInstallCannotBeReplaced() {
        var mock = new D4WebMock(new byte[32]);
        var registry = new EmbeddedRegistry();
        assertThrows(NullPointerException.class, () -> registry.installWebDependencies(mock.searchProvider(), mock.fetcher(), null));
        registry.install(mock);
        var output = registry.executeToolOutput("web_search", "{\"query\":\"current SDK defaults\",\"top_k\":2}");
        assertTrue(output.successful()); assertEquals(2, output.discoveredUrls().size());
        assertThrows(IllegalStateException.class, () -> registry.install(mock));
        assertEquals(1, mock.audit().size());
    }

    @Test void preexistingProductionOrTestProviderCannotBeReplacedByOfflineInstall() {
        var mock = new D4WebMock(new byte[32]);
        var registry = new EmbeddedRegistry();
        registry.setSearchProvider(mock.searchProvider());
        assertThrows(IllegalStateException.class, () -> registry.install(mock));
        assertTrue(mock.audit().isEmpty());
    }

    private static final class EmbeddedRegistry extends ToolRegistry {
        void install(D4WebMock mock) { installWebDependencies(mock.searchProvider(), mock.fetcher(), mock.networkPolicy()); }
    }
}
