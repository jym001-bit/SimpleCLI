package com.paicli.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MemoryRetrieverTest {
    @TempDir Path tempDir;
    private LongTermMemory longTerm;
    private MemoryRetriever retriever;

    @BeforeEach
    void setUp() {
        longTerm = new LongTermMemory(tempDir.toFile());
        retriever = new MemoryRetriever(longTerm);
    }

    @Test
    void shouldRetrieveFromLongTerm() {
        longTerm.store(new MemoryEntry("f1", "用户偏好：喜欢用Spring Boot", MemoryEntry.MemoryType.FACT, null, 10));
        assertFalse(retriever.retrieve("Spring Boot", 5).isEmpty());
    }

    @Test
    void shouldBuildContextForQuery() {
        longTerm.store(new MemoryEntry("f1", "项目路径: /home/dev/myapp", MemoryEntry.MemoryType.FACT, null, 10));
        String context = retriever.buildContextForQuery("项目路径", 200);
        assertFalse(context.isEmpty());
        assertTrue(context.contains("/home/dev/myapp"));
    }

    @Test
    void shouldNotTreatCurrentConversationAsRetrievableMemory() {
        longTerm.store(new MemoryEntry("f1", "用户偏好使用中文交流", MemoryEntry.MemoryType.FACT, null, 10));
        assertTrue(retriever.buildContextForQuery("新建一个第六期的文件夹，里面有一个test.txt文件", 200).isEmpty());
    }

    @Test
    void shouldReturnEmptyForNoMatch() {
        assertTrue(retriever.retrieve("Spring Boot", 5).isEmpty());
    }

    @Test
    void shouldRetrieveChineseByPhraseFragments() {
        longTerm.store(new MemoryEntry("f1", "用户偏好使用Java开发", MemoryEntry.MemoryType.FACT, null, 10));
        var results = retriever.retrieve("偏好设置", 5);
        assertFalse(results.isEmpty());
        assertEquals("f1", results.get(0).getId());
    }

    @Test
    void shouldInjectOnlyGlobalAndCurrentProjectLongTermMemory() {
        longTerm.store(new MemoryEntry("global", "默认用中文回答", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "global"), 10));
        longTerm.store(new MemoryEntry("current", "当前项目使用 Java 17", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "project", "project", "/repo/current"), 10));
        longTerm.store(new MemoryEntry("other", "其他项目使用 Python", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "project", "project", "/repo/other"), 10));
        String context = retriever.buildContextForQuery("项目 使用", 300, "/repo/current");
        assertTrue(context.contains("当前项目使用 Java 17"));
        assertFalse(context.contains("其他项目使用 Python"));
    }
}
