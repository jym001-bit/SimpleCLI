package com.paicli.memory;

import com.paicli.llm.GLMClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MemoryConflictTest {
    private static final Map<String, String> PROJECT_A = Map.of("scope", "project", "project", "/repo/a");
    private static final Map<String, String> PROJECT_B = Map.of("scope", "project", "project", "/repo/b");

    @TempDir
    Path tempDir;

    private final MemoryConflictDetector detector = new MemoryConflictDetector(0.8d);
    private LongTermMemory longTerm;
    private MemoryManager manager;

    @BeforeEach
    void setUp() {
        longTerm = new LongTermMemory(tempDir.toFile(), detector);
        manager = new MemoryManager(new GLMClient("test-key"), 32768, 128000, longTerm);
        manager.setProjectPath("/repo/a");
    }

    @Test
    void numericVersionVariantInSameDomainIsConflict() {
        assertTrue(detector.isConflict(fact("a", "项目使用 Java 17", PROJECT_A), fact("b", "项目使用 Java 21", PROJECT_A)));
        assertTrue(detector.isConflict(fact("a", "服务端口是 8080", PROJECT_A), fact("b", "服务端口是 9090", PROJECT_A)));
    }

    @Test
    void differentDomainDuplicateAndUnrelatedEntriesAreNotConflicts() {
        assertFalse(detector.isConflict(fact("a", "项目使用 Java 17", PROJECT_A), fact("b", "项目使用 Java 21", PROJECT_B)));
        assertFalse(detector.isConflict(fact("a", "项目使用 Java 17", PROJECT_A), fact("b", "项目使用 Java 17。", PROJECT_A)));
        assertFalse(detector.isConflict(fact("a", "项目使用 Java 17", PROJECT_A), fact("b", "提交前运行 mvn test", PROJECT_A)));
    }

    @Test
    void highlySimilarTextIsConflictButThresholdIsRespected() {
        MemoryEntry existing = fact("a", "提交代码前必须运行 mvn test -Pquick 回归", PROJECT_A);
        MemoryEntry incoming = fact("b", "提交代码前必须运行 mvn test -Pquick 全量回归", PROJECT_A);

        assertTrue(detector.isConflict(existing, incoming));
        assertFalse(new MemoryConflictDetector(0.99d).isConflict(existing, incoming));
    }

    @Test
    void deduplicatorStillKeepsNumericDifferencesOnPlainStore() {
        longTerm.store(fact("a", "项目使用 Java 17", PROJECT_A));
        longTerm.store(fact("b", "项目使用 Java 21", PROJECT_A));

        assertEquals(2, longTerm.size());
    }

    @Test
    void conflictingWriteIsNotStoredAndSurfacesBothEntries() {
        String oldId = manager.storeFact("项目使用 Java 17").entry().getId();

        MemoryWriteResult result = manager.storeFact("项目使用 Java 21");

        assertEquals(MemoryWriteResult.Status.CONFLICT, result.status());
        assertFalse(result.written());
        assertEquals(1, longTerm.size());
        assertEquals("项目使用 Java 17", longTerm.retrieve(oldId).orElseThrow().getContent());
        String message = result.describe();
        assertTrue(message.contains(oldId), message);
        assertTrue(message.contains("项目使用 Java 17"), message);
        assertTrue(message.contains("项目使用 Java 21"), message);
        assertTrue(message.contains("/memory replace " + oldId), message);
        assertTrue(message.contains("/save --force"), message);
    }

    @Test
    void userCanKeepBothOrReplace() {
        String oldId = manager.storeFact("项目使用 Java 17").entry().getId();

        MemoryWriteResult kept = manager.storeFact("项目使用 Java 21", "project", null, true);
        assertEquals(MemoryWriteResult.Status.STORED, kept.status());
        assertEquals(2, longTerm.size());

        MemoryWriteResult replaced = manager.replaceFact(oldId, "项目使用 Java 25");
        // 替换时仍会对其他条目做冲突检测：Java 21 那条还在，所以需要用户先处理它。
        assertEquals(MemoryWriteResult.Status.CONFLICT, replaced.status());
        assertTrue(longTerm.retrieve(oldId).isPresent());

        manager.deleteLongTerm(kept.entry().getId());
        MemoryWriteResult replacedNow = manager.replaceFact(oldId, "项目使用 Java 25");
        assertEquals(MemoryWriteResult.Status.REPLACED, replacedNow.status());
        assertTrue(longTerm.retrieve(oldId).isEmpty());
        assertEquals(1, longTerm.size());
        assertTrue(replacedNow.describe().contains("项目使用 Java 17"));
        assertTrue(replacedNow.describe().contains("项目使用 Java 25"));
    }

    @Test
    void replaceTargetMustBeVisibleInCurrentProject() {
        longTerm.store(fact("other", "项目使用 Java 17", PROJECT_B));

        MemoryWriteResult result = manager.storeFact("项目使用 Java 21", "project", "other", false);

        assertEquals(MemoryWriteResult.Status.REPLACE_TARGET_NOT_FOUND, result.status());
        assertTrue(longTerm.retrieve("other").isPresent());
        assertEquals(1, longTerm.size());
    }

    @Test
    void duplicateWriteRefreshesVerificationInsteadOfStoringAgain() {
        MemoryWriteResult first = manager.storeFact("项目使用 Java 17");
        MemoryWriteResult second = manager.storeFact("项目使用 Java 17。");

        assertEquals(MemoryWriteResult.Status.DUPLICATE, second.status());
        assertEquals(first.entry().getId(), second.entry().getId());
        assertFalse(second.entry().getLastVerifiedAt().isBefore(first.entry().getLastVerifiedAt()));
        assertEquals(1, longTerm.size());
    }

    private static MemoryEntry fact(String id, String content, Map<String, String> metadata) {
        return new MemoryEntry(id, content, MemoryEntry.MemoryType.FACT, metadata, MemoryEntry.estimateTokens(content));
    }
}
