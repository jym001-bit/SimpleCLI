package com.paicli.memory;

import com.paicli.llm.GLMClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryManagerTest {
    @TempDir Path tempDir;

    @Test
    void shouldReportConversationHistoryAsTheOnlyShortTermContext() {
        MemoryManager manager = new MemoryManager(new GLMClient("test-key"), 40, 128000,
                new LongTermMemory(tempDir.toFile()));
        assertTrue(manager.getSystemStatus().contains("由当前 Agent conversationHistory 维护"));
    }

    @Test
    void shouldClearLongTermMemoryOnlyWhenExplicitlyRequested() {
        LongTermMemory longTerm = new LongTermMemory(tempDir.toFile());
        MemoryManager manager = new MemoryManager(new GLMClient("test-key"), 32768, 128000, longTerm);
        manager.storeFact("用户偏好使用中文交流");
        manager.storeFact("项目路径: /tmp/demo");
        assertEquals(2, longTerm.size());
        manager.clearLongTerm();
        assertEquals(0, longTerm.size());
    }

    @Test
    void shouldStoreProjectScopedFactsByDefault() {
        LongTermMemory longTerm = new LongTermMemory(tempDir.toFile());
        MemoryManager manager = new MemoryManager(new GLMClient("test-key"), 32768, 128000, longTerm);
        manager.setProjectPath("/repo/current");
        manager.storeFact("当前项目使用 Java 17");
        manager.storeFact("默认用中文回答", "global");
        MemoryEntry projectEntry = longTerm.search("Java", 5, manager.getCurrentProject()).get(0);
        assertEquals("project", projectEntry.getMetadata().get("scope"));
        assertTrue(projectEntry.getMetadata().get("project").endsWith("/repo/current"));
        assertEquals("global", longTerm.search("中文", 5).get(0).getMetadata().get("scope"));
    }

    @Test
    void shouldSearchOnlyCurrentProjectAndGlobalFacts() {
        LongTermMemory longTerm = new LongTermMemory(tempDir.toFile());
        MemoryManager manager = new MemoryManager(new GLMClient("test-key"), 32768, 128000, longTerm);
        manager.setProjectPath("/repo/current");
        longTerm.store(new MemoryEntry("current", "当前项目使用 Java 17", MemoryEntry.MemoryType.FACT,
                java.util.Map.of("scope", "project", "project", manager.getCurrentProject()), 10));
        longTerm.store(new MemoryEntry("other", "其他项目使用 Java 8", MemoryEntry.MemoryType.FACT,
                java.util.Map.of("scope", "project", "project", "/repo/other"), 10));
        List<MemoryEntry> results = manager.searchLongTerm("Java", 10);
        assertEquals(1, results.size());
        assertEquals("current", results.get(0).getId());
    }

    @Test
    void compressionTriggerRatioAppliesToAllModelsUniformly() {
        MemoryManager manager = new MemoryManager(new GLMClient("test-key"));
        assertEquals(0.835, manager.getContextProfile().compressionTriggerRatio(), 0.001);
        assertEquals(200000, manager.getTokenBudget().getContextWindow());
        assertEquals(167000, manager.getContextProfile().compressionTriggerTokens());
    }

    @Test
    void explicitSaveRecordsExternalContextInMetadata() {
        LongTermMemory longTerm = new LongTermMemory(tempDir.toFile());
        MemoryManager manager = new MemoryManager(new GLMClient("test-key"), 32768, 128000, longTerm);
        ExternalContextTracker tracker = new ExternalContextTracker(true);
        manager.setExternalContextTracker(tracker);

        manager.storeFact("本地构建使用 Maven");
        tracker.record("web_fetch");
        tracker.record("mcp__docs__search");
        manager.storeFact("部署文档要求 JDK 21", "global");

        MemoryEntry local = longTerm.search("Maven", 5, manager.getCurrentProject()).get(0);
        MemoryEntry external = longTerm.search("JDK", 5).get(0);
        assertEquals(null, local.getMetadata().get(ExternalContextTracker.METADATA_FLAG));
        assertEquals("true", external.getMetadata().get(ExternalContextTracker.METADATA_FLAG));
        assertEquals("web_fetch,mcp__docs__search",
                external.getMetadata().get(ExternalContextTracker.METADATA_SOURCES));
        assertEquals("global", external.getMetadata().get("scope"));
        assertTrue(manager.blocksAutomaticMemory());
    }
}
