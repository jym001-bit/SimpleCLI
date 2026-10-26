package com.paicli.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LongTermMemoryTest {
    @TempDir
    Path tempDir;

    private LongTermMemory memory;

    @BeforeEach
    void setUp() {
        memory = new LongTermMemory(tempDir.toFile());
    }

    @Test
    void shouldStoreAndRetrieve() {
        MemoryEntry entry = new MemoryEntry("fact-1", "项目使用Java 17", MemoryEntry.MemoryType.FACT, null, 10);
        memory.store(entry);

        assertTrue(memory.retrieve("fact-1").isPresent());
        assertEquals("项目使用Java 17", memory.retrieve("fact-1").get().getContent());
    }

    @Test
    void twoInstancesSharingFileShouldNotOverwriteEachOther() {
        LongTermMemory other = new LongTermMemory(tempDir.toFile());

        memory.store(new MemoryEntry("fact-a", "项目使用 Maven 构建", MemoryEntry.MemoryType.FACT, null, 8));
        other.store(new MemoryEntry("fact-b", "接口统一返回 Result 包装", MemoryEntry.MemoryType.FACT, null, 8));
        memory.store(new MemoryEntry("fact-c", "日志框架使用 SLF4J", MemoryEntry.MemoryType.FACT, null, 8));

        LongTermMemory reopened = new LongTermMemory(tempDir.toFile());
        assertEquals(3, reopened.size(), "三次写入都应落盘，后写的实例不能用旧快照覆盖先写的记忆");
        assertTrue(reopened.retrieve("fact-a").isPresent());
        assertTrue(reopened.retrieve("fact-b").isPresent());
        assertTrue(reopened.retrieve("fact-c").isPresent());
    }

    @Test
    void readsShouldSeeWritesFromAnotherInstance() {
        LongTermMemory other = new LongTermMemory(tempDir.toFile());

        other.store(new MemoryEntry("fact-x", "测试用 JUnit 5", MemoryEntry.MemoryType.FACT, null, 6));

        assertTrue(memory.retrieve("fact-x").isPresent(), "另一个实例写入后，本实例读取前应刷新");
        assertEquals(1, memory.getAll().size());
    }

    @Test
    void corruptFileShouldBeBackedUpInsteadOfWipingMemories() throws Exception {
        memory.store(new MemoryEntry("fact-1", "项目使用Java 17", MemoryEntry.MemoryType.FACT, null, 10));
        Path file = tempDir.resolve("long_term_memory.json");
        Files.writeString(file, "[{\"id\": \"broken\"");

        memory.store(new MemoryEntry("fact-2", "构建用 Maven", MemoryEntry.MemoryType.FACT, null, 6));

        LongTermMemory reopened = new LongTermMemory(tempDir.toFile());
        assertTrue(reopened.retrieve("fact-1").isPresent(), "解析失败时应保留内存中已有记忆");
        assertTrue(reopened.retrieve("fact-2").isPresent());
        try (var files = Files.list(tempDir)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString()
                    .startsWith("long_term_memory.json.corrupt-")), "坏文件应另存备份");
        }
    }

    @Test
    void shouldDeduplicateSameContent() {
        MemoryEntry entry1 = new MemoryEntry("fact-1", "相同内容", MemoryEntry.MemoryType.FACT, null, 5);
        MemoryEntry entry2 = new MemoryEntry("fact-2", "相同内容", MemoryEntry.MemoryType.FACT, null, 5);

        memory.store(entry1);
        memory.store(entry2);

        assertEquals(1, memory.size());
    }

    @Test
    void shouldDeduplicateNormalizedFormattingVariantsInSameDomain() {
        Map<String, String> domain = Map.of(
                "scope", "project",
                "project", "/repo/current"
        );
        memory.store(new MemoryEntry(
                "fact-1",
                "  当前项目使用 Ｊａｖａ １７。\n",
                MemoryEntry.MemoryType.FACT,
                domain,
                10
        ));
        memory.store(new MemoryEntry(
                "fact-2",
                "当前项目使用java17.",
                MemoryEntry.MemoryType.FACT,
                domain,
                10
        ));

        assertEquals(1, memory.size());
        assertTrue(memory.retrieve("fact-1").isPresent());
    }

    @Test
    void shouldDeduplicateConservativeGrammaticalVariantInSameDomain() {
        Map<String, String> domain = Map.of(
                "scope", "project",
                "project", "/repo/current"
        );
        memory.store(new MemoryEntry(
                "fact-1",
                "当前项目使用Java17开发",
                MemoryEntry.MemoryType.FACT,
                domain,
                10
        ));
        memory.store(new MemoryEntry(
                "fact-2",
                "当前项目使用的是Java17开发",
                MemoryEntry.MemoryType.FACT,
                domain,
                10
        ));

        assertEquals(1, memory.size());
    }

    @Test
    void shouldNotDeduplicateAcrossMemoryTypes() {
        memory.store(new MemoryEntry(
                "fact-1",
                "默认用中文回答",
                MemoryEntry.MemoryType.FACT,
                Map.of("scope", "global"),
                5
        ));
        memory.store(new MemoryEntry(
                "summary-1",
                "默认用中文回答",
                MemoryEntry.MemoryType.SUMMARY,
                Map.of("scope", "global"),
                5
        ));

        assertEquals(2, memory.size());
    }

    @Test
    void shouldNotDeduplicateAcrossGlobalAndProjectScopes() {
        memory.store(new MemoryEntry(
                "global",
                "默认用中文回答",
                MemoryEntry.MemoryType.FACT,
                Map.of("scope", "global"),
                5
        ));
        memory.store(new MemoryEntry(
                "project",
                "默认用中文回答",
                MemoryEntry.MemoryType.FACT,
                Map.of("scope", "project", "project", "/repo/current"),
                5
        ));

        assertEquals(2, memory.size());
    }

    @Test
    void shouldNotDeduplicateAcrossProjects() {
        memory.store(new MemoryEntry(
                "project-a",
                "项目使用Java17",
                MemoryEntry.MemoryType.FACT,
                Map.of("scope", "project", "project", "/repo/a"),
                5
        ));
        memory.store(new MemoryEntry(
                "project-b",
                "项目使用Java17",
                MemoryEntry.MemoryType.FACT,
                Map.of("scope", "project", "project", "/repo/b"),
                5
        ));

        assertEquals(2, memory.size());
    }

    @Test
    void projectScopeWithoutProjectKeyShouldNotBeDeduplicated() {
        Map<String, String> incompleteDomain = Map.of("scope", "project");
        memory.store(new MemoryEntry(
                "fact-1",
                "项目使用Java17",
                MemoryEntry.MemoryType.FACT,
                incompleteDomain,
                5
        ));
        memory.store(new MemoryEntry(
                "fact-2",
                "项目使用Java17",
                MemoryEntry.MemoryType.FACT,
                incompleteDomain,
                5
        ));

        assertEquals(2, memory.size());
    }

    @Test
    void shouldKeepConflictingNumbersCodeMarkersAndMeaningfulWords() {
        Map<String, String> domain = Map.of(
                "scope", "project",
                "project", "/repo/current"
        );
        memory.store(new MemoryEntry(
                "java-17",
                "当前项目必须使用Java17构建",
                MemoryEntry.MemoryType.FACT,
                domain,
                8
        ));
        memory.store(new MemoryEntry(
                "java-21",
                "当前项目必须使用Java21构建",
                MemoryEntry.MemoryType.FACT,
                domain,
                8
        ));
        memory.store(new MemoryEntry(
                "c",
                "代码示例使用C",
                MemoryEntry.MemoryType.FACT,
                domain,
                5
        ));
        memory.store(new MemoryEntry(
                "cpp",
                "代码示例使用C++",
                MemoryEntry.MemoryType.FACT,
                domain,
                5
        ));
        memory.store(new MemoryEntry(
                "simple",
                "默认使用中文回答所有技术问题",
                MemoryEntry.MemoryType.FACT,
                domain,
                8
        ));
        memory.store(new MemoryEntry(
                "traditional",
                "默认使用繁体中文回答所有技术问题",
                MemoryEntry.MemoryType.FACT,
                domain,
                8
        ));
        memory.store(new MemoryEntry(
                "allow-auto-commit",
                "当前项目允许自动提交代码",
                MemoryEntry.MemoryType.FACT,
                domain,
                8
        ));
        memory.store(new MemoryEntry(
                "deny-auto-commit",
                "当前项目不允许自动提交代码",
                MemoryEntry.MemoryType.FACT,
                domain,
                8
        ));
        memory.store(new MemoryEntry(
                "thumbs-up",
                "默认用👍表示同意",
                MemoryEntry.MemoryType.FACT,
                domain,
                5
        ));
        memory.store(new MemoryEntry(
                "thumbs-down",
                "默认用👎表示同意",
                MemoryEntry.MemoryType.FACT,
                domain,
                5
        ));

        assertEquals(10, memory.size());
    }

    @Test
    void concurrentDuplicateStoresShouldBeAtomic() throws Exception {
        int workers = 12;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workers);
        Map<String, String> domain = Map.of(
                "scope", "project",
                "project", "/repo/current"
        );
        try {
            for (int i = 0; i < workers; i++) {
                int index = i;
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        memory.store(new MemoryEntry(
                                "fact-" + index,
                                "并发保存时也只能留下同一条事实",
                                MemoryEntry.MemoryType.FACT,
                                domain,
                                7
                        ));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, memory.size());
        assertEquals(7, memory.getTokenCount());
        assertEquals(1, new LongTermMemory(tempDir.toFile()).size());
    }

    @Test
    void replacingSameIdShouldKeepTokenCountAccurate() {
        memory.store(new MemoryEntry(
                "fact-1",
                "项目使用Java17",
                MemoryEntry.MemoryType.FACT,
                Map.of("scope", "project", "project", "/repo/current"),
                5
        ));
        memory.store(new MemoryEntry(
                "fact-1",
                "项目使用Python开发",
                MemoryEntry.MemoryType.FACT,
                Map.of("scope", "project", "project", "/repo/current"),
                9
        ));

        assertEquals(1, memory.size());
        assertEquals(9, memory.getTokenCount());
        assertEquals("项目使用Python开发", memory.retrieve("fact-1").orElseThrow().getContent());
    }

    @Test
    void shouldSearchByKeywords() {
        memory.store(new MemoryEntry("f1", "用户偏好使用IntelliJ IDEA", MemoryEntry.MemoryType.FACT, null, 10));
        memory.store(new MemoryEntry("f2", "项目路径: /home/user/project", MemoryEntry.MemoryType.FACT, null, 10));

        var results = memory.search("IntelliJ", 5);
        assertEquals(1, results.size());
    }

    @Test
    void shouldSearchByMultipleKeywords() {
        memory.store(new MemoryEntry("f1", "用户偏好使用Java开发", MemoryEntry.MemoryType.FACT, null, 10));

        var results = memory.search("Java 偏好", 5);
        assertFalse(results.isEmpty());
    }

    @Test
    void shouldSearchChineseWithoutRelyingOnSpaces() {
        memory.store(new MemoryEntry("f1", "用户偏好使用Java开发", MemoryEntry.MemoryType.FACT, null, 10));

        var results = memory.search("偏好设置", 5);
        assertFalse(results.isEmpty());
    }

    @Test
    void shouldDeleteEntry() {
        memory.store(new MemoryEntry("f1", "测试内容", MemoryEntry.MemoryType.FACT, null, 5));
        assertTrue(memory.delete("f1"));
        assertEquals(0, memory.size());
    }

    @Test
    void shouldFilterByType() {
        memory.store(new MemoryEntry("f1", "事实1", MemoryEntry.MemoryType.FACT, null, 5));
        memory.store(new MemoryEntry("s1", "摘要1", MemoryEntry.MemoryType.SUMMARY, null, 5));

        var facts = memory.getByType(MemoryEntry.MemoryType.FACT);
        assertEquals(1, facts.size());
    }

    @Test
    void shouldPersistAndReload() {
        memory.store(new MemoryEntry("f1", "持久化测试内容", MemoryEntry.MemoryType.FACT, null, 10));
        memory.store(new MemoryEntry("s1", "摘要测试", MemoryEntry.MemoryType.SUMMARY, null, 8));

        // 创建新实例，从磁盘加载
        LongTermMemory reloaded = new LongTermMemory(tempDir.toFile());
        assertEquals(2, reloaded.size());
        assertTrue(reloaded.retrieve("f1").isPresent());
    }

    @Test
    void shouldPreserveTimestampAfterReload() {
        Instant timestamp = Instant.parse("2026-04-20T12:34:56Z");
        memory.store(new MemoryEntry("f1", "带时间戳的事实", MemoryEntry.MemoryType.FACT, timestamp, null, 10));

        LongTermMemory reloaded = new LongTermMemory(tempDir.toFile());
        assertEquals(timestamp, reloaded.retrieve("f1").orElseThrow().getTimestamp());
    }

    @Test
    void shouldFilterProjectScopedMemories() {
        memory.store(new MemoryEntry("global", "默认用中文回答", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "global"), 10));
        memory.store(new MemoryEntry("project-a", "项目A使用 Java 17", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "project", "project", "/repo/a"), 10));
        memory.store(new MemoryEntry("project-b", "项目B使用 Python", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "project", "project", "/repo/b"), 10));

        var visible = memory.getAll("/repo/a");

        assertEquals(2, visible.size());
        assertTrue(visible.stream().anyMatch(entry -> entry.getId().equals("global")));
        assertTrue(visible.stream().anyMatch(entry -> entry.getId().equals("project-a")));
        assertTrue(visible.stream().noneMatch(entry -> entry.getId().equals("project-b")));
    }

    @Test
    void legacyMemoriesWithoutScopeRemainGlobal() {
        MemoryEntry legacy = new MemoryEntry("legacy", "历史偏好", MemoryEntry.MemoryType.FACT, null, 10);

        assertEquals("global", LongTermMemory.scopeOf(legacy));
        assertTrue(LongTermMemory.isVisibleInProject(legacy, "/repo/current"));
    }
}
