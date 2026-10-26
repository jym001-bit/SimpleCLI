package com.paicli.rag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeIndexIncrementalTest {

    @TempDir
    Path dir;
    private Path project;
    private String previousRagDir;

    @BeforeEach
    void setUp() throws IOException {
        previousRagDir = System.getProperty("paicli.rag.dir");
        System.setProperty("paicli.rag.dir", dir.resolve("rag").toString());
        project = dir.resolve("project");
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/A.java"), "public class A { void a() { b(); } void b() {} }");
        Files.writeString(project.resolve("src/B.java"), "public class B extends A { void c() {} }");
        Files.writeString(project.resolve("README.md"), "# demo\nhello");
    }

    @AfterEach
    void tearDown() {
        if (previousRagDir == null) {
            System.clearProperty("paicli.rag.dir");
        } else {
            System.setProperty("paicli.rag.dir", previousRagDir);
        }
    }

    @Test
    void secondRunSkipsUnchangedFiles() {
        StubEmbedding embedding = new StubEmbedding("m1", 32);
        CodeIndex.IndexResult first = new CodeIndex(embedding).index(project.toString());
        int firstTexts = embedding.texts.size();

        CodeIndex.IndexResult second = new CodeIndex(embedding).index(project.toString());

        assertTrue(firstTexts > 0);
        assertEquals(firstTexts, embedding.texts.size(), "内容没变不该再调 Embedding");
        assertEquals(first.chunkCount(), second.chunkCount());
        assertEquals(first.relationCount(), second.relationCount());
        assertTrue(second.message().contains("更新 0 个文件，3 个没变跳过"), second.message());
    }

    @Test
    void changedAndDeletedFilesAreUpdated() throws Exception {
        StubEmbedding embedding = new StubEmbedding("m1", 32);
        new CodeIndex(embedding).index(project.toString());
        embedding.texts.clear();

        Files.writeString(project.resolve("src/A.java"), "public class A { void renamed() {} }");
        Files.delete(project.resolve("src/B.java"));
        CodeIndex.IndexResult result = new CodeIndex(embedding).index(project.toString());

        assertFalse(embedding.texts.isEmpty());
        assertTrue(embedding.texts.stream().noneMatch(text -> text.contains("README") || text.contains("hello")),
                "没改的文件不该重新向量化: " + embedding.texts);
        try (VectorStore store = new VectorStore(project.toString())) {
            assertFalse(store.searchByKeyword("renamed").isEmpty());
            assertTrue(store.searchByKeyword("void a()").isEmpty(), "旧内容要删掉");
            assertTrue(store.searchByKeyword("class B").isEmpty(), "删掉的文件不能留在索引里");
            assertTrue(store.getRelations("B").isEmpty(), "删掉的文件的关系也要清掉");
            assertEquals(2, store.indexedFiles().size());
        }
        assertTrue(result.message().contains("更新 1 个文件"), result.message());
    }

    @Test
    void embeddingFailureKeepsTheOldIndexAndRetriesNextTime() throws Exception {
        StubEmbedding working = new StubEmbedding("m1", 32);
        CodeIndex.IndexResult first = new CodeIndex(working).index(project.toString());
        Files.writeString(project.resolve("src/A.java"), "public class A { void changed() {} }");

        StubEmbedding broken = new StubEmbedding("m1", 32);
        broken.fail = true;
        CodeIndex.IndexResult failedRun = new CodeIndex(broken).index(project.toString());

        assertEquals(first.chunkCount(), failedRun.chunkCount(), "Embedding 服务不可用时不能把索引清空");
        assertTrue(failedRun.message().contains("1 个失败、保留旧索引"), failedRun.message());
        try (VectorStore store = new VectorStore(project.toString())) {
            assertFalse(store.searchByKeyword("void a()").isEmpty());
        }

        new CodeIndex(working).index(project.toString());
        try (VectorStore store = new VectorStore(project.toString())) {
            assertFalse(store.searchByKeyword("changed").isEmpty(), "下次索引要补上");
        }
    }

    @Test
    void switchingEmbeddingModelReindexesEverything() {
        StubEmbedding m1 = new StubEmbedding("m1", 32);
        new CodeIndex(m1).index(project.toString());
        StubEmbedding m2 = new StubEmbedding("m2", 32);

        CodeIndex.IndexResult result = new CodeIndex(m2).index(project.toString());

        assertEquals(m1.texts.size(), m2.texts.size(), "换模型后旧向量不能混用");
        assertTrue(result.message().contains("更新 3 个文件"), result.message());
    }

    @Test
    void embeddingRequestsAreBatchedAcrossFiles() throws Exception {
        for (int i = 0; i < 20; i++) {
            Files.writeString(project.resolve("src/F" + i + ".md"), "note " + i);
        }
        StubEmbedding embedding = new StubEmbedding("m1", 8);

        new CodeIndex(embedding).index(project.toString());

        // A.java 3 块（类 + 2 个方法）、B.java 2 块、README 和 20 个 md 各 1 块，共 26 块、23 个文件
        assertEquals(26, embedding.texts.size());
        assertTrue(embedding.calls <= 4, "按 8 条一批应该 3~4 次请求，实际 " + embedding.calls + " 次");
    }

    @Test
    void oversizedFilesAreSkippedAndLegacyIndexIsRebuilt() throws Exception {
        Files.writeString(project.resolve("big.json"), "x".repeat(1024 * 1024 + 1));
        // 旧版本留下的数据：有代码块但没有文件指纹
        try (VectorStore store = new VectorStore(project.toString())) {
            store.insertChunks(List.of(new VectorStore.CodeChunkEntry(
                    CodeChunk.fileChunk(project.resolve("gone.md").toString(), "legacy leftover"), new float[]{1f})));
        }
        List<String> messages = new ArrayList<>();

        new CodeIndex(new StubEmbedding("m1", 32), messages::add).index(project.toString());

        assertTrue(messages.stream().anyMatch(m -> m.contains("另有 1 个超过 1MB 的文件跳过")), messages.toString());
        try (VectorStore store = new VectorStore(project.toString())) {
            assertTrue(store.searchByKeyword("legacy leftover").isEmpty(), "没有指纹的旧索引要整体重建");
            assertEquals(3, store.indexedFiles().size());
        }
    }

    private static final class StubEmbedding extends EmbeddingClient {
        private final int batchSize;
        private final List<String> texts = new ArrayList<>();
        private int calls;
        private boolean fail;

        private StubEmbedding(String model, int batchSize) {
            super("ollama", model, "http://localhost", "");
            this.batchSize = batchSize;
        }

        @Override
        public List<float[]> embedAll(List<String> inputs) throws IOException {
            if (fail) {
                throw new IOException("connection refused");
            }
            calls++;
            texts.addAll(inputs);
            return inputs.stream().map(text -> new float[]{text.length(), 1f}).toList();
        }

        @Override
        public int getBatchSize() {
            return batchSize;
        }
    }
}
