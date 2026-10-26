package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.eval.benchmark.mock.D4FrozenOracle;
import com.paicli.eval.benchmark.mock.D4WebMock;
import com.paicli.eval.benchmark.finalset.generator.FinalSourceGenerator;
import com.paicli.eval.benchmark.formal.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class D4EvidenceContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;
    private FormalVerifierBundleMaterializer.TrustedVerifierBundle bundle;
    @BeforeEach void materializeRealVerifierBundle() throws Exception {
        var parent = temp.toRealPath(); Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"));
        var source = new FinalSourceGenerator().generateIncompleteSource(new FinalSourceGenerator.GenerationRequest(
                parent.resolve("source"), "12345678abcdef90".repeat(4)));
        var plan = FormalTestAdmission.createWithGeneratedMock(parent.resolve("admission"), source.sourceRoot(), "D4").plan();
        var episode = BenchmarkArtifactStore.create(parent.resolve("artifacts"), "envelope-test").episode("D4", "deepseek-v4-flash", 1, 1);
        bundle = FormalVerifierBundleMaterializer.materialize(plan.requireCase("D4"), episode);
    }
    @AfterEach void restorePermissions() throws Exception {
        try (var walk = Files.walk(temp)) {
            for (Path p : walk.toList()) if (!Files.isSymbolicLink(p))
                Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(Files.isDirectory(p) ? "rwx------" : "rw-------"));
        }
    }
    @Test void hostEvidenceIsImmutableAndRejectsMissingBindingFields() throws Exception {
        var events = new ArrayList<D4WebMock.AuditEvent>();
        var turns = new ArrayList<D4WebMock.ProviderTurn>();
        var evidence = evidence(events, turns);
        events.add(new D4WebMock.AuditEvent(1, "SEARCH", "query", 2, "a".repeat(64), true, List.of()));
        turns.add(new D4WebMock.ProviderTurn(1, 0, "answer", List.of(), List.of()));
        assertTrue(evidence.events().isEmpty()); assertTrue(evidence.providerTurns().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> evidence.providerTurns().add(turns.get(0)));
        assertEquals(evidence, JSON.treeToValue(JSON.valueToTree(evidence), FormalMockWebBinding.MockEvidence.class));
        assertThrows(IllegalArgumentException.class, () -> evidence(null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> evidence(List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new FormalMockWebBinding.MockEvidence(1, "D4", D4FrozenOracle.PROFILE,
                7, "a".repeat(64), "b".repeat(64), List.of(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new FormalMockWebBinding.MockEvidence(1, "D4", D4FrozenOracle.PROFILE,
                8, "a".repeat(64), null, List.of(), List.of(), List.of()));
    }
    @Test void webEnvelopeRequiresExactCaseModeProfileAndKeepsAuditOutsideWorkspace() throws Exception {
        var value = evidence(List.of(), List.of());
        for (var bad : List.of("missing", "case", "mode", "profile")) {
            var root = Files.createDirectory(temp.resolve(bad));
            var workspace = Files.createDirectory(root.resolve("workspace")); var home = Files.createDirectory(root.resolve("home"));
            assertThrows(IOException.class, () -> BenchmarkEvidenceEnvelope.writeFormal(root, workspace, home,
                    bad.equals("case") ? "D3" : "D4", 1, bad.equals("mode") ? CaseDefinition.Mode.PLAN : CaseDefinition.Mode.REACT,
                    bad.equals("profile") ? BenchmarkToolProfile.FILE_ONLY : BenchmarkToolProfile.MOCK_WEB,
                    "answer", null, null, bundle, List.of(), null, bad.equals("missing") ? null : value, "secret"));
        }
        var root = Files.createDirectory(temp.resolve("valid"));
        var workspace = Files.createDirectory(root.resolve("workspace")); var home = Files.createDirectory(root.resolve("home"));
        var path = BenchmarkEvidenceEnvelope.writeFormal(root, workspace, home, "D4", 1, CaseDefinition.Mode.REACT,
                BenchmarkToolProfile.MOCK_WEB, "answer", null, null, bundle, List.of(), null, value, "secret");
        assertEquals(root.toRealPath(), path.getParent().getParent()); assertFalse(path.startsWith(workspace.toRealPath()));
        var json = JSON.readTree(path.toFile()); assertEquals(4, json.path("schemaVersion").asInt());
        assertEquals(JSON.valueToTree(value), json.path("mockWeb")); assertFalse(json.has("mockMcp"));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path));
    }
    @Test void hostTurnContainingCredentialCanaryIsRejectedWithoutWritingIt() throws Exception {
        var root = Files.createDirectory(temp.resolve("canary"));
        var workspace = Files.createDirectory(root.resolve("workspace")); var home = Files.createDirectory(root.resolve("home"));
        String secret = "exact-host-credential-canary";
        var value = evidence(List.of(), List.of(new D4WebMock.ProviderTurn(1, 0, secret, List.of(), List.of())));
        assertThrows(IOException.class, () -> BenchmarkEvidenceEnvelope.writeFormal(root, workspace, home, "D4", 1,
                CaseDefinition.Mode.REACT, BenchmarkToolProfile.MOCK_WEB, "answer", null, null, bundle, List.of(), null, value, secret));
        assertFalse(Files.exists(root.resolve("verifier-evidence/envelope.json")));
    }
    private static FormalMockWebBinding.MockEvidence evidence(List<D4WebMock.AuditEvent> events, List<D4WebMock.ProviderTurn> turns) {
        return new FormalMockWebBinding.MockEvidence(1, "D4", D4FrozenOracle.PROFILE, 8, "a".repeat(64), "b".repeat(64), events, List.of(), turns);
    }
}
