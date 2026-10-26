package com.paicli.eval.benchmark.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrozenCodeSearchIndexTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void parsesAValidContentAddressedIndex() throws Exception {
        List<FrozenCodeSearchIndex.Document> documents = List.of(
                document("src/billing/BusinessClock.ts", 7,
                        "export function rollbackBoundary() {\n  return monthLength();\n}\n",
                        List.of("trial account settlement", "business clock")));

        FrozenCodeSearchIndex index = FrozenCodeSearchIndex.parse(indexJson(documents));

        assertEquals(FrozenCodeSearchIndex.VERSION, index.version());
        assertEquals(FrozenCodeSearchIndex.computeIndexDigest(documents), index.indexDigest());
        assertEquals(documents, index.documents());
    }

    @Test
    void rejectsUnknownDuplicateTrailingAndNullJson() throws Exception {
        List<FrozenCodeSearchIndex.Document> documents = List.of(
                document("src/A.java", 1, "class A {}\n", List.of("alpha behavior")));
        byte[] validBytes = indexJson(documents);
        ObjectNode valid = (ObjectNode) JSON.readTree(validBytes);

        ObjectNode unknownRoot = valid.deepCopy();
        unknownRoot.put("unexpected", true);
        assertInvalid(JSON.writeValueAsBytes(unknownRoot));

        ObjectNode unknownDocument = valid.deepCopy();
        ((ObjectNode) unknownDocument.withArray("documents").get(0)).put("unexpected", true);
        assertInvalid(JSON.writeValueAsBytes(unknownDocument));

        String validText = new String(validBytes, StandardCharsets.UTF_8);
        String duplicate = "{\"version\":\"" + FrozenCodeSearchIndex.VERSION
                + "\",\"version\":\"" + FrozenCodeSearchIndex.VERSION + "\","
                + validText.substring(validText.indexOf(',') + 1);
        assertInvalid(duplicate.getBytes(StandardCharsets.UTF_8));
        assertInvalid((validText + " {}").getBytes(StandardCharsets.UTF_8));

        ObjectNode nullVersion = valid.deepCopy();
        nullVersion.putNull("version");
        assertInvalid(JSON.writeValueAsBytes(nullVersion));

        ObjectNode nullDocument = valid.deepCopy();
        nullDocument.withArray("documents").set(0, NullNode.getInstance());
        assertInvalid(JSON.writeValueAsBytes(nullDocument));
    }

    @Test
    void rejectsDocumentOrIndexDigestMismatch() throws Exception {
        List<FrozenCodeSearchIndex.Document> documents = List.of(
                document("src/A.java", 1, "class A {}\n", List.of("alpha behavior")));
        ObjectNode root = (ObjectNode) JSON.readTree(indexJson(documents));

        ObjectNode badDocument = root.deepCopy();
        ((ObjectNode) badDocument.withArray("documents").get(0)).put("digest", "0".repeat(64));
        assertInvalid(JSON.writeValueAsBytes(badDocument));

        ObjectNode badIndex = root.deepCopy();
        badIndex.put("indexDigest", "f".repeat(64));
        assertInvalid(JSON.writeValueAsBytes(badIndex));
    }

    @Test
    void rejectsDuplicateDocumentSlicesEvenWhenDigestMatches() throws Exception {
        FrozenCodeSearchIndex.Document document = document(
                "src/A.java", 1, "class A {}\n", List.of("alpha behavior"));
        List<FrozenCodeSearchIndex.Document> duplicateDocuments = List.of(document, document);

        assertInvalid(indexJson(duplicateDocuments));
    }

    @Test
    void validatesNormalizedPathLinesContentAndTerms() {
        String oneLine = "class A {}\n";
        String digest = FrozenCodeSearchIndex.sha256Hex(oneLine);

        assertThrows(IllegalArgumentException.class, () -> new FrozenCodeSearchIndex.Document(
                "../src/A.java", 1, 1, oneLine, digest, List.of("alpha")));
        assertThrows(IllegalArgumentException.class, () -> new FrozenCodeSearchIndex.Document(
                "/src/A.java", 1, 1, oneLine, digest, List.of("alpha")));
        assertThrows(IllegalArgumentException.class, () -> new FrozenCodeSearchIndex.Document(
                "src/A.java", 1, 2, oneLine, digest, List.of("alpha")));
        assertThrows(IllegalArgumentException.class, () -> new FrozenCodeSearchIndex.Document(
                "src/A.java", 1, 1, "class A {}\r\n",
                FrozenCodeSearchIndex.sha256Hex("class A {}\r\n"), List.of("alpha")));
        assertThrows(IllegalArgumentException.class, () -> new FrozenCodeSearchIndex.Document(
                "src/A.java", 1, 1, oneLine, digest, List.of("Alpha", "alpha")));
        assertThrows(IllegalArgumentException.class, () -> new FrozenCodeSearchIndex.Document(
                "src/A.java", 1, 1, oneLine, digest, Arrays.asList("alpha", null)));
    }

    @Test
    void fileLoaderRejectsSymlinkAndOversizedOrEmptyInput(@TempDir Path tempDir) throws Exception {
        Path valid = tempDir.resolve("index.json");
        List<FrozenCodeSearchIndex.Document> documents = List.of(
                document("src/A.java", 1, "class A {}\n", List.of("alpha behavior")));
        Files.write(valid, indexJson(documents));
        assertEquals(documents, FrozenCodeSearchIndex.load(valid).documents());

        Path symlink = tempDir.resolve("linked-index.json");
        Files.createSymbolicLink(symlink, valid.getFileName());
        assertThrows(IOException.class, () -> FrozenCodeSearchIndex.load(symlink));

        Path empty = tempDir.resolve("empty.json");
        Files.write(empty, new byte[0]);
        assertThrows(IOException.class, () -> FrozenCodeSearchIndex.load(empty));

        assertThrows(IOException.class, () -> FrozenCodeSearchIndex.parse(
                new byte[FrozenCodeSearchIndex.MAX_INDEX_BYTES + 1]));
    }

    @Test
    void digestIncludesDocumentAndSemanticTermOrder() {
        FrozenCodeSearchIndex.Document first = document(
                "src/A.java", 1, "class A {}\n", List.of("alpha", "entrypoint"));
        FrozenCodeSearchIndex.Document second = document(
                "src/B.java", 1, "class B {}\n", List.of("beta", "worker"));

        String original = FrozenCodeSearchIndex.computeIndexDigest(List.of(first, second));
        String documentsReordered = FrozenCodeSearchIndex.computeIndexDigest(List.of(second, first));
        FrozenCodeSearchIndex.Document termsReordered = document(
                first.path(), first.startLine(), first.content(), List.of("entrypoint", "alpha"));
        String termsChanged = FrozenCodeSearchIndex.computeIndexDigest(List.of(termsReordered, second));

        assertTrue(!original.equals(documentsReordered));
        assertTrue(!original.equals(termsChanged));
    }

    private static void assertInvalid(byte[] json) {
        assertThrows(IOException.class, () -> FrozenCodeSearchIndex.parse(json));
    }

    private static FrozenCodeSearchIndex.Document document(String path,
                                                           int startLine,
                                                           String content,
                                                           List<String> semanticTerms) {
        int endLine = startLine + logicalLineCount(content) - 1;
        return new FrozenCodeSearchIndex.Document(
                path,
                startLine,
                endLine,
                content,
                FrozenCodeSearchIndex.sha256Hex(content),
                semanticTerms);
    }

    private static int logicalLineCount(String content) {
        String effective = content.endsWith("\n") ? content.substring(0, content.length() - 1) : content;
        return 1 + (int) effective.chars().filter(value -> value == '\n').count();
    }

    private static byte[] indexJson(List<FrozenCodeSearchIndex.Document> documents) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", FrozenCodeSearchIndex.VERSION);
        root.put("indexDigest", FrozenCodeSearchIndex.computeIndexDigest(documents));
        List<Map<String, Object>> wireDocuments = new ArrayList<>();
        for (FrozenCodeSearchIndex.Document document : documents) {
            Map<String, Object> wire = new LinkedHashMap<>();
            wire.put("path", document.path());
            wire.put("startLine", document.startLine());
            wire.put("endLine", document.endLine());
            wire.put("content", document.content());
            wire.put("digest", document.digest());
            wire.put("semanticTerms", document.semanticTerms());
            wireDocuments.add(wire);
        }
        root.put("documents", wireDocuments);
        return JSON.writeValueAsBytes(root);
    }
}
