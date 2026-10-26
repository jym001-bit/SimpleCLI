package com.paicli.eval.benchmark.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.tool.ToolOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrozenCodeSearchTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void frozenSemanticAliasesRankTheA2TargetAheadOfLexicalDecoys() throws Exception {
        FrozenCodeSearch search = search(List.of(
                document("packages/reports/src/account-summary.ts",
                        "export function accountSummary() { return 'trial'; }\n",
                        List.of("account report", "trial display")),
                document("packages/clock/src/settlement-window.ts",
                        "export function rollbackBoundary(day) {\n  return monthLength(day);\n}\n",
                        List.of("trial account business clock settlement", "month end rollback"))));

        ToolOutput output = search.apply("{\"query\":\"trial account business clock settlement\",\"top_k\":2}");
        JsonNode response = JSON.readTree(output.text());

        assertTrue(output.successful());
        assertEquals("packages/clock/src/settlement-window.ts",
                response.path("results").get(0).path("path").asText());
        assertEquals("rollbackBoundary(day)",
                response.path("results").get(0).path("content").asText().split(" ")[2]);
        assertEquals(FrozenCodeSearch.RANKING_VERSION, response.path("rankingVersion").asText());
        assertFalse(response.path("partial").asBoolean());
    }

    @Test
    void orderingAndOutputAreDeterministicAcrossRepeatedQueries() throws Exception {
        FrozenCodeSearch search = search(List.of(
                document("src/zeta.ts", "export const zeta = 1;\n", List.of("shared intent")),
                document("src/alpha.ts", "export const alpha = 1;\n", List.of("shared intent"))));
        String args = "{\"query\":\"shared intent\",\"top_k\":2}";

        String first = search.apply(args).text();
        String second = search.apply(args).text();
        JsonNode response = JSON.readTree(first);

        assertEquals(first, second);
        assertEquals("src/alpha.ts", response.path("results").get(0).path("path").asText());
        assertEquals("src/zeta.ts", response.path("results").get(1).path("path").asText());
    }

    @Test
    void topKDefaultsAndClampsLikePaiCliSearchCode() throws Exception {
        List<FrozenCodeSearchIndex.Document> documents = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            documents.add(document("src/Item" + index + ".java",
                    "class Item" + index + " {}\n", List.of("needle behavior")));
        }
        FrozenCodeSearch search = search(documents);

        JsonNode defaultResponse = response(search.apply("{\"query\":\"needle behavior\"}"));
        assertEquals(5, defaultResponse.path("requestedTopK").asInt());
        assertEquals(5, defaultResponse.path("returned").asInt());
        assertTrue(defaultResponse.path("partial").asBoolean());

        JsonNode lowerClamp = response(search.apply("{\"query\":\"needle behavior\",\"top_k\":0}"));
        assertEquals(1, lowerClamp.path("requestedTopK").asInt());
        assertEquals(1, lowerClamp.path("returned").asInt());
        assertTrue(lowerClamp.path("partial").asBoolean());

        JsonNode upperClamp = response(search.apply("{\"query\":\"needle behavior\",\"top_k\":999}"));
        assertEquals(FrozenCodeSearch.MAX_TOP_K, upperClamp.path("requestedTopK").asInt());
        assertEquals(7, upperClamp.path("returned").asInt());
        assertFalse(upperClamp.path("partial").asBoolean());
    }

    @Test
    void malformedArgumentsFailClosed() {
        FrozenCodeSearch search = search(List.of(
                document("src/A.java", "class A {}\n", List.of("alpha behavior"))));

        List<String> invalid = List.of(
                "null",
                "[]",
                "{}",
                "{\"query\":null}",
                "{\"query\":\"alpha\",\"extra\":1}",
                "{\"query\":\"alpha\",\"query\":\"beta\"}",
                "{\"query\":\"alpha\",\"top_k\":1.5}",
                "{\"query\":\"alpha\",\"top_k\":\"5\"}",
                "{\"query\":\"alpha\"} {}"
        );

        for (String arguments : invalid) {
            ToolOutput output = search.apply(arguments);
            assertFalse(output.successful(), arguments);
            assertTrue(output.text().startsWith("benchmark frozen code search arguments are invalid:"), arguments);
        }
        assertFalse(search.apply(null).successful());
    }

    @Test
    void outputBudgetAndSnippetTruncationAreExplicit() throws Exception {
        String content = "needle ".repeat(7_000);
        FrozenCodeSearch search = search(List.of(
                document("src/large/generated-file.ts", content, List.of("large needle implementation"))));

        ToolOutput output = search.apply("{\"query\":\"large needle implementation\"}");
        JsonNode response = response(output);

        assertTrue(output.successful());
        assertTrue(output.text().getBytes(StandardCharsets.UTF_8).length <= FrozenCodeSearch.MAX_OUTPUT_BYTES);
        assertTrue(response.path("partial").asBoolean());
        assertTrue(response.path("results").get(0).path("contentPartial").asBoolean());
        assertTrue(response.path("results").get(0).path("content").asText()
                .getBytes(StandardCharsets.UTF_8).length <= FrozenCodeSearch.MAX_SNIPPET_BYTES);
    }

    @Test
    void neverScansUnregisteredWorkspaceFiles(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("secret-unregistered.ts"),
                "outsideOnlyWorkspaceToken", StandardCharsets.UTF_8);
        FrozenCodeSearch search = search(List.of(
                document("registered/missing-on-disk.ts",
                        "export const frozenValue = 7;\n", List.of("registered frozen value"))));

        JsonNode absent = response(search.apply("{\"query\":\"outsideOnlyWorkspaceToken\"}"));
        assertEquals(0, absent.path("matched").asInt());
        assertEquals(0, absent.path("returned").asInt());
        assertFalse(absent.path("partial").asBoolean());

        JsonNode frozen = response(search.apply("{\"query\":\"registered frozen value\"}"));
        assertEquals("registered/missing-on-disk.ts",
                frozen.path("results").get(0).path("path").asText());
        assertEquals("export const frozenValue = 7;\n",
                frozen.path("results").get(0).path("content").asText());
    }

    @Test
    void responseCarriesFrozenDocumentAndIndexIdentity() throws Exception {
        FrozenCodeSearchIndex.Document document = document(
                "src/Identity.java", "class Identity {}\n", List.of("identity lookup"));
        FrozenCodeSearch search = search(List.of(document));

        JsonNode response = response(search.apply("{\"query\":\"identity lookup\"}"));

        assertEquals(search.index().indexDigest(), response.path("indexDigest").asText());
        assertEquals(FrozenCodeSearchIndex.VERSION, response.path("indexVersion").asText());
        assertEquals(document.digest(), response.path("results").get(0).path("digest").asText());
        assertEquals(document.startLine(), response.path("results").get(0).path("startLine").asInt());
        assertEquals(document.endLine(), response.path("results").get(0).path("endLine").asInt());
    }

    private static JsonNode response(ToolOutput output) throws Exception {
        assertTrue(output.successful(), output.text());
        return JSON.readTree(output.text());
    }

    private static FrozenCodeSearch search(List<FrozenCodeSearchIndex.Document> documents) {
        String digest = FrozenCodeSearchIndex.computeIndexDigest(documents);
        return new FrozenCodeSearch(new FrozenCodeSearchIndex(
                FrozenCodeSearchIndex.VERSION, digest, documents));
    }

    private static FrozenCodeSearchIndex.Document document(String path,
                                                           String content,
                                                           List<String> semanticTerms) {
        int lines = 1 + (int) (content.endsWith("\n")
                ? content.substring(0, content.length() - 1)
                : content).chars().filter(value -> value == '\n').count();
        return new FrozenCodeSearchIndex.Document(
                path,
                1,
                lines,
                content,
                FrozenCodeSearchIndex.sha256Hex(content),
                semanticTerms);
    }
}
