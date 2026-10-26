package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkEvidenceEnvelopeTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void writesStrictOwnerOnlySiblingEnvelopeAndScrubsExactKey(@TempDir Path tempDir) throws Exception {
        Path episode = Files.createDirectory(tempDir.resolve("episode"));
        Path workspace = Files.createDirectory(episode.resolve("workspace"));
        Path home = Files.createDirectory(episode.resolve("home"));
        String key = "literal-provider-key-not-pattern-shaped";
        TracingLlmClient.Metrics metrics = new TracingLlmClient.Metrics(
                2, 100, 20, 30, 4, 900,
                2, "glm-5.3-flash", true, true);
        BenchmarkToolExecutionEvidence firstTool = BenchmarkToolExecutionEvidence.from(
                1,
                "call-1",
                "read_file",
                "{\"path\":\"README.md\",\"token\":\"" + key + "\"}",
                "contents include " + key,
                12,
                false,
                true);
        BenchmarkToolExecutionEvidence secondTool = BenchmarkToolExecutionEvidence.from(
                2,
                "call-2",
                "grep_code",
                "{\"pattern\":\"needle\"}",
                "not found",
                3,
                false,
                false);

        Path evidence = BenchmarkEvidenceEnvelope.write(
                episode,
                workspace,
                home,
                "case-one",
                3,
                CaseDefinition.Mode.PLAN,
                BenchmarkToolProfile.READ_ONLY,
                "answer contains " + key + " and Authorization: Bearer abcdefghijkl",
                metrics,
                null,
                null,
                List.of(firstTool, secondTool),
                key);

        assertEquals(episode.toRealPath(), evidence.getParent().getParent());
        assertFalse(evidence.startsWith(workspace.toRealPath()));
        assertFalse(evidence.startsWith(home.toRealPath()));
        assertFalse(Files.readString(evidence).contains(key));
        JsonNode json = MAPPER.readTree(evidence.toFile());
        assertEquals(2, json.path("schemaVersion").asInt());
        assertEquals("case-one", json.path("caseId").asText());
        assertEquals(3, json.path("repeat").asInt());
        assertEquals("plan", json.path("mode").asText());
        assertEquals("READ_ONLY", json.path("toolProfile").asText());
        assertTrue(json.path("answer").asText().contains(SecretRedactor.REDACTED));
        assertEquals(2, json.path("llmMetrics").path("calls").asInt());
        assertEquals(100, json.path("llmMetrics").path("inputTokens").asLong());
        assertEquals(13, json.path("llmMetrics").size());
        assertFalse(json.path("llmMetrics").has("contextCapSatisfied"));
        assertFalse(json.has("requestedContextWindowCapTokens"));
        assertFalse(json.has("effectiveContextWindowCapTokens"));
        JsonNode toolExecutions = json.path("toolExecutions");
        assertEquals(2, toolExecutions.size());
        assertEquals(1, toolExecutions.get(0).path("ordinal").asInt());
        assertEquals("call-1", toolExecutions.get(0).path("callId").asText());
        assertEquals("read_file", toolExecutions.get(0).path("toolName").asText());
        assertTrue(toolExecutions.get(0).path("argumentsJson").asText()
                .contains(SecretRedactor.REDACTED));
        assertTrue(toolExecutions.get(0).path("resultPreview").asText()
                .contains(SecretRedactor.REDACTED));
        assertEquals(firstTool.resultSha256(),
                toolExecutions.get(0).path("resultSha256").asText());
        assertEquals(firstTool.resultChars(),
                toolExecutions.get(0).path("resultChars").asLong());
        assertEquals(2, toolExecutions.get(1).path("ordinal").asInt());
        assertEquals("grep_code", toolExecutions.get(1).path("toolName").asText());
        assertFalse(toolExecutions.get(1).path("successful").asBoolean());

        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(evidence);
            assertEquals(Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE), permissions);
        } catch (UnsupportedOperationException ignored) {
            // The containing episode remains private on non-POSIX test hosts.
        }
    }

    @Test
    void refusesEvidenceLocationThatDoesNotShareEpisode(@TempDir Path tempDir) throws Exception {
        Path episode = Files.createDirectory(tempDir.resolve("episode"));
        Path workspace = Files.createDirectory(episode.resolve("workspace"));
        Path other = Files.createDirectory(tempDir.resolve("other"));
        Path home = Files.createDirectory(other.resolve("home"));

        assertThrows(java.io.IOException.class, () -> BenchmarkEvidenceEnvelope.write(
                episode,
                workspace,
                home,
                "case-one",
                1,
                CaseDefinition.Mode.REACT,
                BenchmarkToolProfile.FILE_ONLY,
                "answer",
                null,
                "secret"));
    }

    @Test
    void rejectsMoreThanMaximumToolExecutions(@TempDir Path tempDir) throws Exception {
        Path episode = Files.createDirectory(tempDir.resolve("episode"));
        Path workspace = Files.createDirectory(episode.resolve("workspace"));
        Path home = Files.createDirectory(episode.resolve("home"));
        BenchmarkToolExecutionEvidence event = BenchmarkToolExecutionEvidence.from(
                1, "call-1", "read_file", "{}", "ok", 1, false, true);

        assertThrows(IllegalArgumentException.class, () -> BenchmarkEvidenceEnvelope.write(
                episode,
                workspace,
                home,
                "case-one",
                1,
                CaseDefinition.Mode.REACT,
                BenchmarkToolProfile.FILE_ONLY,
                "answer",
                null,
                null,
                null,
                java.util.Collections.nCopies(
                        BenchmarkToolExecutionEvidence.MAX_EVENTS + 1, event),
                "secret"));
    }

    @Test
    void rejectsMissingAndReorderedToolExecutionOrdinals(@TempDir Path tempDir) throws Exception {
        BenchmarkToolExecutionEvidence first = BenchmarkToolExecutionEvidence.from(
                1, "call-1", "read_file", "{}", "one", 1, false, true);
        BenchmarkToolExecutionEvidence second = BenchmarkToolExecutionEvidence.from(
                2, "call-2", "grep_code", "{}", "two", 1, false, true);
        BenchmarkToolExecutionEvidence third = BenchmarkToolExecutionEvidence.from(
                3, "call-3", "list_dir", "{}", "three", 1, false, true);

        assertNonContiguousOrdinalsRejected(
                tempDir.resolve("missing"), List.of(first, third));
        assertNonContiguousOrdinalsRejected(
                tempDir.resolve("reordered"), List.of(second, first));
    }

    private static void assertNonContiguousOrdinalsRejected(
            Path root,
            List<BenchmarkToolExecutionEvidence> toolExecutions) throws Exception {
        Files.createDirectory(root);
        Path episode = Files.createDirectory(root.resolve("episode"));
        Path workspace = Files.createDirectory(episode.resolve("workspace"));
        Path home = Files.createDirectory(episode.resolve("home"));

        assertThrows(IllegalArgumentException.class, () -> BenchmarkEvidenceEnvelope.write(
                episode,
                workspace,
                home,
                "case-one",
                1,
                CaseDefinition.Mode.REACT,
                BenchmarkToolProfile.FILE_ONLY,
                "answer",
                null,
                null,
                null,
                toolExecutions,
                "secret"));
    }
}
