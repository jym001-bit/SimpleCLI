package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol.WireToolCall;
import com.paicli.tool.CommandExecutionObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Isolated serialization-boundary tests, not a formal-admission bypass or Worker run.
 * Reflection reaches the private common writer without adding a production test hook.
 * All strings, observations and credentials below are invented local test values.
 */
class F2RawEvidencePrivacyTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "literal-provider-key-not-pattern-shaped";
    @TempDir Path temp;

    @Test void boundF2PreservesRawAnswerArgumentsAndTruncatedPreviewInOwnerOnlyEvidence() throws Exception {
        var fixture = fixture();
        String tokenLike = "x".repeat(20000);
        String answer = "Synthetic text " + tokenLike + " Authorization: Bearer abcdefghijkl";
        String arguments = JSON.writeValueAsString(Map.of("path", "oversize.txt", "note", tokenLike));
        var tool = BenchmarkToolExecutionEvidence.from(1, "synthetic-tool", "read_file", arguments,
                "文件内容:\n" + tokenLike, 12, false, true);
        assertNotEquals(answer, SecretRedactor.redact(answer));
        assertNotEquals(arguments, SecretRedactor.redact(arguments));
        assertNotEquals(tool.resultPreview(), SecretRedactor.redact(tool.resultPreview()));
        var tools = List.of(tool);
        var command = command(answer, tools, requests(tools), List.of());
        Path evidence = raw(fixture, answer, tools, command, metrics("synthetic-model"), "F2",
                CaseDefinition.Mode.REACT, BenchmarkToolProfile.LOCAL_COMMAND, fixture.snapshot());
        var tree = JSON.readTree(evidence.toFile());
        assertEquals(8, tree.path("schemaVersion").asInt());
        assertEquals(answer, tree.path("answer").asText());
        // Apply the same JSON round-trip to both sides: in-memory valueToTree
        // keeps Java LongNode widths while parsed JSON uses IntNode for small values.
        assertEquals(JSON.readTree(JSON.writeValueAsBytes(tools)), tree.path("toolExecutions"));
        assertEquals(tree.path("answer"), tree.path("command").path("terminal").path("answer"));
        assertEquals(tree.path("toolExecutions"), tree.path("command").path("terminal").path("toolExecutions"));
        assertEquals(16384, tree.path("toolExecutions").get(0).path("resultPreview").asText().length());
        assertEquals(tool.resultChars(), tree.path("toolExecutions").get(0).path("resultChars").asLong());
        assertEquals(tool.resultSha256(), tree.path("toolExecutions").get(0).path("resultSha256").asText());
        assertFalse(Files.readString(evidence).contains(KEY));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(evidence));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(evidence.getParent()));
        fixture.snapshot().verifyUnchanged();
    }

    @ParameterizedTest @EnumSource(SecretLocation.class)
    void exactKnownCredentialIsRejectedWithoutCreatingAnEnvelope(SecretLocation location) throws Exception {
        var fixture = fixture();
        String answer = location == SecretLocation.ANSWER ? "sensitive " + KEY : "synthetic answer";
        String arguments = JSON.writeValueAsString(Map.of("path", "README.md", "note",
                location == SecretLocation.TOOL_ARGUMENTS ? KEY : "synthetic note"));
        String result = location == SecretLocation.TOOL_RESULT ? "sensitive " + KEY : "synthetic result";
        var tools = List.of(BenchmarkToolExecutionEvidence.from(1, "synthetic-tool", "read_file", arguments, result, 0, false, true));
        List<WireToolCall> requested = switch (location) {
            case REQUEST_ARGUMENTS -> List.of(new WireToolCall("synthetic-tool", "read_file", JSON.writeValueAsString(Map.of("path", KEY))));
            case REQUEST_ID -> List.of(new WireToolCall(KEY, "read_file", arguments));
            default -> requests(tools);
        };
        String observedCommand = location == SecretLocation.COMMAND_TEXT ? "echo " + KEY : "echo synthetic";
        String observedDirectory = location == SecretLocation.COMMAND_DIRECTORY ? "/workspace/" + KEY : "/workspace";
        List<String> argv = location == SecretLocation.COMMAND_ARGUMENTS ? List.of("bash", "-c", "echo " + KEY) : List.of("bash", "-c", observedCommand);
        var observation = new CommandExecutionObserver.Event(1, CommandExecutionObserver.Phase.STARTED,
                observedCommand, observedDirectory, argv, 1001, 1000, null,
                CommandExecutionObserver.Outcome.NONE, "", 0);
        String terminalAnswer = location == SecretLocation.TERMINAL_ANSWER ? "sensitive " + KEY : answer;
        var bound = command(terminalAnswer, tools, requested, List.of(observation));
        TracingLlmClient.Metrics metrics = metrics(location == SecretLocation.METRICS_MODEL ? KEY : "synthetic-model");
        var failure = assertThrows(IOException.class, () -> raw(fixture, answer, tools, bound, metrics,
                "F2", CaseDefinition.Mode.REACT, BenchmarkToolProfile.LOCAL_COMMAND, fixture.snapshot()));
        assertEquals("sensitive command evidence rejected", failure.getMessage());
        Path directory = fixture.episode().resolve(BenchmarkEvidenceEnvelope.DIRECTORY_NAME);
        assertFalse(Files.exists(directory.resolve(BenchmarkEvidenceEnvelope.FILE_NAME), LinkOption.NOFOLLOW_LINKS));
        if (location != SecretLocation.METRICS_MODEL) assertFalse(Files.exists(directory, LinkOption.NOFOLLOW_LINKS),
                "raw-text canary must reject before evidence directory creation");
        else {
            // Metadata enters only the final JSON projection. The second canary
            // runs after directory preparation but still before any file write.
            assertTrue(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS));
            try (var entries = Files.list(directory)) { assertEquals(0, entries.count()); }
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
        }
        assertFalse(BenchmarkSecretCanary.containsInTree(fixture.episode(), KEY));
    }

    @Test void ordinaryEnvelopeRetainsItsExistingHeuristicAndExactSecretRedaction() throws Exception {
        var fixture = fixture();
        String tokenLike = "x".repeat(20000);
        String answer = "synthetic " + KEY + " Authorization: Bearer abcdefghijkl " + tokenLike;
        String arguments = JSON.writeValueAsString(Map.of("path", "README.md", "note", tokenLike, "credential", KEY));
        var tool = BenchmarkToolExecutionEvidence.from(1, "synthetic-tool", "read_file", arguments,
                "synthetic " + KEY + " " + tokenLike, 0, false, true);
        Path evidence = BenchmarkEvidenceEnvelope.write(fixture.episode(), fixture.workspace(), fixture.home(), "ordinary-case", 1,
                CaseDefinition.Mode.REACT, BenchmarkToolProfile.READ_ONLY, answer, metrics("synthetic-model"),
                fixture.snapshot(), null, List.of(tool), KEY);
        var tree = JSON.readTree(evidence.toFile());
        assertEquals(2, tree.path("schemaVersion").asInt()); assertFalse(tree.has("command"));
        assertEquals(SecretRedactor.redact(answer.replace(KEY, SecretRedactor.REDACTED)), tree.path("answer").asText());
        assertEquals(JSON.readTree(JSON.writeValueAsBytes(tool.scrubExactSecret(KEY))), tree.path("toolExecutions").get(0));
        assertNotEquals(tool.resultPreview(), tree.path("toolExecutions").get(0).path("resultPreview").asText());
        assertFalse(Files.readString(evidence).contains(KEY));
        assertFalse(Files.readString(evidence).contains("abcdefghijkl"));
    }

    @ParameterizedTest @EnumSource(InvalidBinding.class)
    void rawModeCannotAccidentallyApplyToAnotherCaseModeProfileOrMissingSnapshot(InvalidBinding invalid) throws Exception {
        var fixture = fixture();
        var tool = BenchmarkToolExecutionEvidence.from(1, "synthetic-tool", "read_file", "{\"path\":\"README.md\"}", "synthetic", 0, false, true);
        var tools = List.of(tool); var command = command("synthetic answer", tools, requests(tools), List.of());
        var failure = assertThrows(IOException.class, () -> raw(fixture, "synthetic answer", tools, command, metrics("synthetic-model"),
                invalid == InvalidBinding.OTHER_CASE ? "F1" : "F2",
                invalid == InvalidBinding.OTHER_MODE ? CaseDefinition.Mode.PLAN : CaseDefinition.Mode.REACT,
                invalid == InvalidBinding.OTHER_PROFILE ? BenchmarkToolProfile.FILE_ONLY : BenchmarkToolProfile.LOCAL_COMMAND,
                invalid == InvalidBinding.NO_SNAPSHOT ? null : fixture.snapshot()));
        assertEquals("F2 evidence requires its bound host session", failure.getMessage());
        assertFalse(Files.exists(fixture.episode().resolve(BenchmarkEvidenceEnvelope.DIRECTORY_NAME), LinkOption.NOFOLLOW_LINKS));
    }

    private Fixture fixture() throws Exception {
        Path root = temp.toRealPath();
        Path episode = BenchmarkProcessEnvironment.preparePrivateDirectory(root.resolve("episode"));
        Path workspace = BenchmarkProcessEnvironment.preparePrivateDirectory(episode.resolve("workspace"));
        Path home = BenchmarkProcessEnvironment.preparePrivateDirectory(episode.resolve("home"));
        Path snapshotRoot = BenchmarkProcessEnvironment.preparePrivateDirectory(episode.resolve("verifier-workspace"));
        return new Fixture(episode, workspace, home, BenchmarkVerifierWorkspaceSnapshot.create(workspace, snapshotRoot));
    }
    private static List<WireToolCall> requests(List<BenchmarkToolExecutionEvidence> tools) {
        return tools.stream().map(tool -> new WireToolCall(tool.callId(), tool.toolName(), tool.argumentsJson())).toList();
    }
    private static FormalCommandBinding.CommandEvidence command(String answer, List<BenchmarkToolExecutionEvidence> tools,
            List<WireToolCall> requests, List<CommandExecutionObserver.Event> observations) {
        return new FormalCommandBinding.CommandEvidence(1, "F2", "f2-command-diagnostic-v1", "a".repeat(64), "b".repeat(64), "c".repeat(64),
                new FormalCommandBinding.CommandObservation(1, "CANDIDATE_COMMAND_OBSERVATIONS_NOT_OS_AUDIT", observations, 0),
                requests, new FormalCommandBinding.TerminalEvidence(answer, tools));
    }
    private static TracingLlmClient.Metrics metrics(String model) {
        return new TracingLlmClient.Metrics(1, 10, 10, 0, 1, 1, 1, model, true, true);
    }
    private static Path raw(Fixture fixture, String answer, List<BenchmarkToolExecutionEvidence> tools,
            FormalCommandBinding.CommandEvidence command, TracingLlmClient.Metrics metrics, String caseId,
            CaseDefinition.Mode mode, BenchmarkToolProfile profile, BenchmarkVerifierWorkspaceSnapshot.Snapshot snapshot) throws Exception {
        Method method = Arrays.stream(BenchmarkEvidenceEnvelope.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("writeWithIdentity") && candidate.getParameterCount() == 18
                        && candidate.getParameterTypes()[16] == FormalCommandBinding.CommandEvidence.class)
                .findFirst().orElseThrow(() -> new AssertionError("F2 private serialization boundary changed"));
        method.setAccessible(true);
        try {
            return (Path)method.invoke(null, fixture.episode(), fixture.workspace(), fixture.home(), caseId, 1, mode, profile,
                    answer, metrics, snapshot, null, tools, null, null, null, null, command, KEY);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            throw error;
        }
    }
    enum SecretLocation { ANSWER, TOOL_ARGUMENTS, TOOL_RESULT, REQUEST_ARGUMENTS, REQUEST_ID,
        COMMAND_TEXT, COMMAND_DIRECTORY, COMMAND_ARGUMENTS, TERMINAL_ANSWER, METRICS_MODEL }
    enum InvalidBinding { OTHER_CASE, OTHER_MODE, OTHER_PROFILE, NO_SNAPSHOT }
    record Fixture(Path episode, Path workspace, Path home, BenchmarkVerifierWorkspaceSnapshot.Snapshot snapshot) { }
}
