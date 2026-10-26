package com.paicli.eval.benchmark;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.eval.benchmark.formal.FormalVerifierBundleMaterializer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

/** Runner-owned evidence: v2 static, v3 MCP, v4 Web, v5 E1, v6 F4, v7 F1, v8 F2, v9 F3. */
final class BenchmarkEvidenceEnvelope {
    static final int SCHEMA_VERSION = 2;
    static final String DIRECTORY_NAME = "verifier-evidence";
    static final String FILE_NAME = "envelope.json";

    private static final ObjectMapper MAPPER = new ObjectMapper(
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private BenchmarkEvidenceEnvelope() {
    }

    static Path write(Path episodeDirectory,
                      Path workspace,
                      Path isolatedHome,
                      String caseId,
                      int repeat,
                      CaseDefinition.Mode mode,
                      BenchmarkToolProfile toolProfile,
                      String answer,
                      TracingLlmClient.Metrics llmMetrics,
                      String exactSecret) throws IOException {
        return write(
                episodeDirectory,
                workspace,
                isolatedHome,
                caseId,
                repeat,
                mode,
                toolProfile,
                answer,
                llmMetrics,
                null,
                null,
                List.of(),
                exactSecret);
    }

    static Path write(Path episodeDirectory,
                      Path workspace,
                      Path isolatedHome,
                      String caseId,
                      int repeat,
                      CaseDefinition.Mode mode,
                      BenchmarkToolProfile toolProfile,
                      String answer,
                      TracingLlmClient.Metrics llmMetrics,
                      BenchmarkVerifierWorkspaceSnapshot.Snapshot workspaceSnapshot,
                      BenchmarkVerifierBundle.Bundle verifierBundle,
                      String exactSecret) throws IOException {
        return write(
                episodeDirectory,
                workspace,
                isolatedHome,
                caseId,
                repeat,
                mode,
                toolProfile,
                answer,
                llmMetrics,
                workspaceSnapshot,
                verifierBundle,
                List.of(),
                exactSecret);
    }

    static Path write(Path episodeDirectory,
                      Path workspace,
                      Path isolatedHome,
                      String caseId,
                      int repeat,
                      CaseDefinition.Mode mode,
                      BenchmarkToolProfile toolProfile,
                      String answer,
                      TracingLlmClient.Metrics llmMetrics,
                      BenchmarkVerifierWorkspaceSnapshot.Snapshot workspaceSnapshot,
                      BenchmarkVerifierBundle.Bundle verifierBundle,
                      List<BenchmarkToolExecutionEvidence> toolExecutions,
                      String exactSecret) throws IOException {
        VerifierIdentity identity = verifierBundle == null ? null : new VerifierIdentity(
                verifierBundle.treeSha256(), verifierBundle.fileCount(), verifierBundle.totalBytes());
        return writeWithIdentity(episodeDirectory, workspace, isolatedHome, caseId, repeat, mode,
                toolProfile, answer, llmMetrics, workspaceSnapshot, identity, toolExecutions, exactSecret);
    }

    static Path writeFormal(Path episodeDirectory, Path workspace, Path isolatedHome,
            String caseId, int repeat, CaseDefinition.Mode mode, BenchmarkToolProfile toolProfile,
            String answer, TracingLlmClient.Metrics llmMetrics,
            BenchmarkVerifierWorkspaceSnapshot.Snapshot workspaceSnapshot,
            FormalVerifierBundleMaterializer.TrustedVerifierBundle verifierBundle,
            List<BenchmarkToolExecutionEvidence> toolExecutions, String exactSecret) throws IOException {
        return writeFormal(episodeDirectory, workspace, isolatedHome, caseId, repeat, mode, toolProfile,
                answer, llmMetrics, workspaceSnapshot, verifierBundle, toolExecutions, null, exactSecret);
    }

    static Path writeFormal(Path episodeDirectory, Path workspace, Path isolatedHome,
            String caseId, int repeat, CaseDefinition.Mode mode, BenchmarkToolProfile toolProfile,
            String answer, TracingLlmClient.Metrics llmMetrics,
            BenchmarkVerifierWorkspaceSnapshot.Snapshot workspaceSnapshot,
            FormalVerifierBundleMaterializer.TrustedVerifierBundle verifierBundle,
            List<BenchmarkToolExecutionEvidence> toolExecutions,
            FormalMockMcpBinding.MockEvidence mock, String exactSecret) throws IOException {
        return writeFormal(episodeDirectory, workspace, isolatedHome, caseId, repeat, mode, toolProfile,
                answer, llmMetrics, workspaceSnapshot, verifierBundle, toolExecutions, mock, null, exactSecret);
    }

    static Path writeFormal(Path episodeDirectory, Path workspace, Path isolatedHome,
            String caseId, int repeat, CaseDefinition.Mode mode, BenchmarkToolProfile toolProfile,
            String answer, TracingLlmClient.Metrics llmMetrics, BenchmarkVerifierWorkspaceSnapshot.Snapshot workspaceSnapshot,
            FormalVerifierBundleMaterializer.TrustedVerifierBundle verifierBundle, List<BenchmarkToolExecutionEvidence> toolExecutions,
            FormalMockMcpBinding.MockEvidence mock, FormalMockWebBinding.MockEvidence web, String exactSecret) throws IOException {
        verifierBundle.verifyUnchanged();
        VerifierIdentity identity = new VerifierIdentity(verifierBundle.bundleSha256(),
                verifierBundle.dependencies().size(), verifierBundle.dependencies().stream()
                        .mapToLong(FormalVerifierBundleMaterializer.MaterializedDependency::size).sum());
        return writeWithIdentity(episodeDirectory, workspace, isolatedHome, caseId, repeat, mode,
                toolProfile, answer, llmMetrics, workspaceSnapshot, identity, toolExecutions, mock, web, exactSecret);
    }

    static Path writeBoundInjection(Path episodeDirectory, Path workspace, Path isolatedHome, int repeat,
            FormalInjectionBinding.Session session, BenchmarkVerifierWorkspaceSnapshot.Snapshot snapshot,
            FormalVerifierBundleMaterializer.TrustedVerifierBundle bundle, String exactSecret) throws IOException {
        session.requirePaths(episodeDirectory, workspace, isolatedHome);
        var injection = session.evidence(snapshot); var execution = session.execution();
        if (execution.response() == null || !execution.response().success()) throw new IOException("F3 failed dispatch must be classified first");
        snapshot.verifyUnchanged(); bundle.verifyUnchanged();
        var identity = new VerifierIdentity(bundle.bundleSha256(), bundle.dependencies().size(),
                bundle.dependencies().stream().mapToLong(FormalVerifierBundleMaterializer.MaterializedDependency::size).sum());
        return writeWithIdentity(episodeDirectory, workspace, isolatedHome, "F3", repeat, CaseDefinition.Mode.REACT,
                BenchmarkToolProfile.MOCK_MCP_FILE_ONLY, execution.response().answer(), execution.response().metrics(), snapshot,
                identity, execution.toolExecutions(), null, null, null, null, null, injection, exactSecret);
    }

    private static Path writeWithIdentity(Path episodeDirectory, Path workspace, Path isolatedHome,
            String caseId, int repeat, CaseDefinition.Mode mode, BenchmarkToolProfile toolProfile,
            String answer, TracingLlmClient.Metrics llmMetrics,
            BenchmarkVerifierWorkspaceSnapshot.Snapshot workspaceSnapshot,
            VerifierIdentity verifierBundle, List<BenchmarkToolExecutionEvidence> toolExecutions,
            String exactSecret) throws IOException {
        return writeWithIdentity(episodeDirectory, workspace, isolatedHome, caseId, repeat, mode,
                toolProfile, answer, llmMetrics, workspaceSnapshot, verifierBundle, toolExecutions, null, exactSecret);
    }

    private static Path writeWithIdentity(Path episodeDirectory, Path workspace, Path isolatedHome,
            String caseId, int repeat, CaseDefinition.Mode mode, BenchmarkToolProfile toolProfile,
            String answer, TracingLlmClient.Metrics llmMetrics,
            BenchmarkVerifierWorkspaceSnapshot.Snapshot workspaceSnapshot,
            VerifierIdentity verifierBundle, List<BenchmarkToolExecutionEvidence> toolExecutions,
            FormalMockMcpBinding.MockEvidence mock, String exactSecret) throws IOException {
        return writeWithIdentity(episodeDirectory, workspace, isolatedHome, caseId, repeat, mode, toolProfile,
                answer, llmMetrics, workspaceSnapshot, verifierBundle, toolExecutions, mock, null, exactSecret);
    }

    private static Path writeWithIdentity(Path episodeDirectory, Path workspace, Path isolatedHome,
            String caseId, int repeat, CaseDefinition.Mode mode, BenchmarkToolProfile toolProfile,
            String answer, TracingLlmClient.Metrics llmMetrics, BenchmarkVerifierWorkspaceSnapshot.Snapshot workspaceSnapshot,
            VerifierIdentity verifierBundle, List<BenchmarkToolExecutionEvidence> toolExecutions,
            FormalMockMcpBinding.MockEvidence mock, FormalMockWebBinding.MockEvidence web, String exactSecret) throws IOException {
        return writeWithIdentity(episodeDirectory, workspace, isolatedHome, caseId, repeat, mode, toolProfile,
                answer, llmMetrics, workspaceSnapshot, verifierBundle, toolExecutions, mock, web, null, exactSecret);
    }

    /** Controlled E1 bridge. Only the host session supplies result, metrics, tools and Plan audit. */
    static Path writeBoundPlan(Path episodeDirectory, Path workspace, Path isolatedHome, int repeat,
            FormalPlanBinding.Session session, BenchmarkVerifierWorkspaceSnapshot.Snapshot snapshot,
            FormalVerifierBundleMaterializer.TrustedVerifierBundle bundle, String exactSecret) throws IOException {
        session.requirePaths(episodeDirectory, workspace, isolatedHome);
        var plan = session.evidence(); var execution = session.execution();
        if (!execution.response().success() || plan.audit().failed())
            throw new IOException("failed Plan dispatch must be classified before verifier evidence");
        if (snapshot == null || !snapshot.directory().getParent().equals(episodeDirectory))
            throw new IOException("E1 verifier snapshot must belong to its episode");
        snapshot.verifyUnchanged();
        bundle.verifyUnchanged();
        var identity = new VerifierIdentity(bundle.bundleSha256(), bundle.dependencies().size(),
                bundle.dependencies().stream().mapToLong(FormalVerifierBundleMaterializer.MaterializedDependency::size).sum());
        return writeWithIdentity(episodeDirectory, workspace, isolatedHome, "E1", repeat, CaseDefinition.Mode.PLAN,
                BenchmarkToolProfile.FILE_ONLY, execution.response().answer(), session.metrics(), snapshot, identity,
                execution.toolExecutions(), null, null, plan, exactSecret);
    }

    private static Path writeWithIdentity(Path episodeDirectory, Path workspace, Path isolatedHome,
            String caseId, int repeat, CaseDefinition.Mode mode, BenchmarkToolProfile toolProfile,
            String answer, TracingLlmClient.Metrics llmMetrics, BenchmarkVerifierWorkspaceSnapshot.Snapshot workspaceSnapshot,
            VerifierIdentity verifierBundle, List<BenchmarkToolExecutionEvidence> toolExecutions,
            FormalMockMcpBinding.MockEvidence mock, FormalMockWebBinding.MockEvidence web,
            FormalPlanBinding.PlanEvidence plan, String exactSecret) throws IOException {
        return writeWithIdentity(episodeDirectory, workspace, isolatedHome, caseId, repeat, mode, toolProfile,
                answer, llmMetrics, workspaceSnapshot, verifierBundle, toolExecutions, mock, web, plan, null, exactSecret);
    }

    static Path writeBoundBoundary(Path episodeDirectory, Path workspace, Path isolatedHome, int repeat,
            FormalBoundaryBinding.Session session, BenchmarkVerifierWorkspaceSnapshot.Snapshot snapshot,
            FormalVerifierBundleMaterializer.TrustedVerifierBundle bundle, String exactSecret) throws IOException {
        session.requirePaths(episodeDirectory, workspace, isolatedHome);
        var boundary = session.evidence(snapshot); var execution = session.execution();
        if (execution.response() == null || !execution.response().success()) throw new IOException("F1 failed dispatch must be classified first");
        snapshot.verifyUnchanged(); bundle.verifyUnchanged();
        var identity = new VerifierIdentity(bundle.bundleSha256(), bundle.dependencies().size(),
                bundle.dependencies().stream().mapToLong(FormalVerifierBundleMaterializer.MaterializedDependency::size).sum());
        return writeWithIdentity(episodeDirectory, workspace, isolatedHome, "F1", repeat, CaseDefinition.Mode.REACT,
                BenchmarkToolProfile.FILE_ONLY, execution.response().answer(), execution.response().metrics(), snapshot,
                identity, execution.toolExecutions(), null, null, null, boundary, exactSecret);
    }

    private static Path writeWithIdentity(Path episodeDirectory, Path workspace, Path isolatedHome,
            String caseId, int repeat, CaseDefinition.Mode mode, BenchmarkToolProfile toolProfile,
            String answer, TracingLlmClient.Metrics llmMetrics, BenchmarkVerifierWorkspaceSnapshot.Snapshot workspaceSnapshot,
            VerifierIdentity verifierBundle, List<BenchmarkToolExecutionEvidence> toolExecutions,
            FormalMockMcpBinding.MockEvidence mock, FormalMockWebBinding.MockEvidence web,
            FormalPlanBinding.PlanEvidence plan, FormalBoundaryBinding.BoundaryEvidence boundary, String exactSecret) throws IOException {
        return writeWithIdentity(episodeDirectory, workspace, isolatedHome, caseId, repeat, mode, toolProfile,
                answer, llmMetrics, workspaceSnapshot, verifierBundle, toolExecutions, mock, web, plan, boundary, null, exactSecret);
    }

    static Path writeBoundCommand(Path episodeDirectory, Path workspace, Path isolatedHome, int repeat,
            FormalCommandBinding.Session session, BenchmarkVerifierWorkspaceSnapshot.Snapshot snapshot,
            FormalVerifierBundleMaterializer.TrustedVerifierBundle bundle, String exactSecret) throws IOException {
        session.requirePaths(episodeDirectory, workspace, isolatedHome);
        var command = session.evidence(snapshot); var execution = session.execution();
        if (execution.response() == null || !execution.response().success()) throw new IOException("F2 failed dispatch must be classified first");
        snapshot.verifyUnchanged(); bundle.verifyUnchanged();
        var identity = new VerifierIdentity(bundle.bundleSha256(), bundle.dependencies().size(),
                bundle.dependencies().stream().mapToLong(FormalVerifierBundleMaterializer.MaterializedDependency::size).sum());
        return writeWithIdentity(episodeDirectory, workspace, isolatedHome, "F2", repeat, CaseDefinition.Mode.REACT,
                BenchmarkToolProfile.LOCAL_COMMAND, execution.response().answer(), execution.response().metrics(), snapshot,
                identity, execution.toolExecutions(), null, null, null, null, command, exactSecret);
    }

    private static Path writeWithIdentity(Path episodeDirectory, Path workspace, Path isolatedHome,
            String caseId, int repeat, CaseDefinition.Mode mode, BenchmarkToolProfile toolProfile,
            String answer, TracingLlmClient.Metrics llmMetrics, BenchmarkVerifierWorkspaceSnapshot.Snapshot workspaceSnapshot,
            VerifierIdentity verifierBundle, List<BenchmarkToolExecutionEvidence> toolExecutions,
            FormalMockMcpBinding.MockEvidence mock, FormalMockWebBinding.MockEvidence web,
            FormalPlanBinding.PlanEvidence plan, FormalBoundaryBinding.BoundaryEvidence boundary,
            FormalCommandBinding.CommandEvidence command, String exactSecret) throws IOException {
        return writeWithIdentity(episodeDirectory, workspace, isolatedHome, caseId, repeat, mode, toolProfile,
                answer, llmMetrics, workspaceSnapshot, verifierBundle, toolExecutions, mock, web, plan, boundary, command, null, exactSecret);
    }

    private static Path writeWithIdentity(Path episodeDirectory, Path workspace, Path isolatedHome,
            String caseId, int repeat, CaseDefinition.Mode mode, BenchmarkToolProfile toolProfile,
            String answer, TracingLlmClient.Metrics llmMetrics, BenchmarkVerifierWorkspaceSnapshot.Snapshot workspaceSnapshot,
            VerifierIdentity verifierBundle, List<BenchmarkToolExecutionEvidence> toolExecutions,
            FormalMockMcpBinding.MockEvidence mock, FormalMockWebBinding.MockEvidence web,
            FormalPlanBinding.PlanEvidence plan, FormalBoundaryBinding.BoundaryEvidence boundary,
            FormalCommandBinding.CommandEvidence command, FormalInjectionBinding.InjectionEvidence injection,
            String exactSecret) throws IOException {
        if ((toolProfile == BenchmarkToolProfile.MOCK_MCP_FILE_ONLY) != (injection != null)
                || injection != null && (!"F3".equals(caseId) || mode != CaseDefinition.Mode.REACT
                || mock != null || web != null || plan != null || boundary != null || command != null || workspaceSnapshot == null))
            throw new IOException("F3 evidence requires its exclusive frozen host session");
        if (injection != null && BenchmarkSecretCanary.contains(exactSecret, answer,
                MAPPER.writeValueAsString(injection), MAPPER.writeValueAsString(toolExecutions)))
            throw new IOException("sensitive injection evidence rejected");
        if (command != null && (!"F2".equals(caseId) || mode != CaseDefinition.Mode.REACT || toolProfile != BenchmarkToolProfile.LOCAL_COMMAND
                || mock != null || web != null || plan != null || boundary != null || workspaceSnapshot == null))
            throw new IOException("F2 evidence requires its bound host session");
        if (command != null && BenchmarkSecretCanary.contains(exactSecret, answer,
                MAPPER.writeValueAsString(command), MAPPER.writeValueAsString(toolExecutions)))
            throw new IOException("sensitive command evidence rejected");
        if (boundary != null && (!"F1".equals(caseId) || mode != CaseDefinition.Mode.REACT || toolProfile != BenchmarkToolProfile.FILE_ONLY
                || mock != null || web != null || plan != null || workspaceSnapshot == null))
            throw new IOException("F1 evidence requires its bound host session");
        if (plan != null && (!"E1".equals(caseId) || mode != CaseDefinition.Mode.PLAN || toolProfile != BenchmarkToolProfile.FILE_ONLY
                || mock != null || web != null || llmMetrics == null
                || !java.util.Objects.equals(plan.scopedRequestFingerprints(), llmMetrics.scopedRequestFingerprints())))
            throw new IOException("Plan evidence requires its host-owned E1 binding");
        if ((toolProfile == BenchmarkToolProfile.MOCK_WEB) != (web != null)
                || web != null && (!caseId.equals(web.caseId()) || mode != CaseDefinition.Mode.REACT))
            throw new IOException("Web evidence requires its host-owned case binding");
        if ((toolProfile == BenchmarkToolProfile.MOCK_MCP) != (mock != null)
                || mock != null && !caseId.equals(mock.caseId()))
            throw new IOException("MCP evidence requires its host-owned case binding");
        Path episode = canonicalDirectory(episodeDirectory, "episode directory");
        Path realWorkspace = canonicalDirectory(workspace, "workspace");
        Path realHome = canonicalDirectory(isolatedHome, "isolated home");
        if (!episode.equals(realWorkspace.getParent()) || !episode.equals(realHome.getParent())) {
            throw new IOException("evidence, workspace, and home must share one episode directory");
        }
        CaseDefinition.requireSafeIdentifier(caseId, "case id");
        if (repeat <= 0 || mode == null || toolProfile == null) {
            throw new IllegalArgumentException("evidence metadata is incomplete");
        }

        Path evidenceDirectory = episode.resolve(DIRECTORY_NAME).normalize();
        if (!evidenceDirectory.getParent().equals(episode)
                || evidenceDirectory.startsWith(realWorkspace)
                || evidenceDirectory.startsWith(realHome)) {
            throw new IOException("evidence directory is not an episode sibling");
        }
        if (Files.exists(evidenceDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("verifier evidence directory already exists");
        }
        BenchmarkProcessEnvironment.preparePrivateDirectory(evidenceDirectory);

        String scrubbedAnswer = exactSecret == null || exactSecret.isBlank()
                ? answer
                : (answer == null ? "" : answer.replace(exactSecret, SecretRedactor.REDACTED));
        scrubbedAnswer = SecretRedactor.redact(scrubbedAnswer == null ? "" : scrubbedAnswer);
        // F2/F3 owner-only envelopes are private raw evidence surfaces. Heuristic redaction
        // would change their text/UTF-16 length without changing the bound host digest.
        // Exact credential canaries are rejected before any evidence file is created and again below.
        if (command != null || injection != null) scrubbedAnswer = answer == null ? "" : answer;
        List<BenchmarkToolExecutionEvidence> boundedToolExecutions =
                toolExecutions == null ? List.of() : List.copyOf(toolExecutions);
        if (boundedToolExecutions.size() > BenchmarkToolExecutionEvidence.MAX_EVENTS) {
            throw new IllegalArgumentException("tool execution evidence exceeds its event limit");
        }
        for (int index = 0; index < boundedToolExecutions.size(); index++) {
            if (boundedToolExecutions.get(index).ordinal() != index + 1) {
                throw new IllegalArgumentException(
                        "tool execution evidence must use contiguous one-based ordinals");
            }
        }
        List<BenchmarkToolExecutionEvidence> scrubbedToolExecutions =
                command != null || injection != null ? boundedToolExecutions : boundedToolExecutions.stream()
                        .map(execution -> execution.scrubExactSecret(exactSecret))
                        .toList();
        Envelope envelope = new Envelope(
                SCHEMA_VERSION,
                caseId,
                repeat,
                mode.toJson(),
                toolProfile,
                scrubbedAnswer,
                EvidenceMetricsV2.from(llmMetrics),
                scrubbedToolExecutions,
                workspaceSnapshot == null ? null : workspaceSnapshot.treeSha256(),
                workspaceSnapshot == null ? null : workspaceSnapshot.fileCount(),
                workspaceSnapshot == null ? null : workspaceSnapshot.totalBytes(),
                verifierBundle == null ? null : verifierBundle.treeSha256(),
                verifierBundle == null ? null : verifierBundle.fileCount(),
                verifierBundle == null ? null : verifierBundle.totalBytes());
        com.fasterxml.jackson.databind.node.ObjectNode tree = MAPPER.valueToTree(envelope);
        if (mock != null) {
            tree.put("schemaVersion", "F4".equals(mock.caseId()) ? 6 : 3);
            tree.set("mockMcp", MAPPER.valueToTree(mock));
            if (BenchmarkSecretCanary.contains(exactSecret, tree.toString()))
                throw new IOException("sensitive MCP evidence rejected");
        }
        if (web != null) {
            tree.put("schemaVersion", 4); tree.set("mockWeb", MAPPER.valueToTree(web));
            if (BenchmarkSecretCanary.contains(exactSecret, tree.toString())) throw new IOException("sensitive Web evidence rejected");
        }
        if (plan != null) {
            tree.put("schemaVersion", 5); tree.set("plan", MAPPER.valueToTree(plan));
            if (BenchmarkSecretCanary.contains(exactSecret, tree.toString())) throw new IOException("sensitive Plan evidence rejected");
        }
        if (boundary != null) {
            tree.put("schemaVersion", 7); tree.set("boundary", MAPPER.valueToTree(boundary));
            if (BenchmarkSecretCanary.contains(exactSecret, tree.toString())) throw new IOException("sensitive boundary evidence rejected");
        }
        if (command != null) {
            tree.put("schemaVersion", 8); tree.set("command", MAPPER.valueToTree(command));
            if (BenchmarkSecretCanary.contains(exactSecret, tree.toString())) throw new IOException("sensitive command evidence rejected");
        }
        if (injection != null) {
            tree.put("schemaVersion", 9); tree.set("injection", MAPPER.valueToTree(injection));
            if (BenchmarkSecretCanary.contains(exactSecret, tree.toString())) throw new IOException("sensitive injection evidence rejected");
        }
        byte[] json = MAPPER.writeValueAsBytes(tree);
        // A read-back makes malformed/trailing JSON a preparation failure instead of verifier input.
        MAPPER.readTree(json);

        Path file = evidenceDirectory.resolve(FILE_NAME);
        try {
            Files.createFile(
                    file,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            Files.createFile(file);
        }
        Files.write(file, json, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // The owner-only episode directory remains the fallback on non-POSIX filesystems.
        }
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("verifier evidence is not a safe regular file");
        }
        return file.toRealPath();
    }

    private record VerifierIdentity(String treeSha256, int fileCount, long totalBytes) {}

    private static Path canonicalDirectory(Path raw, String label) throws IOException {
        if (raw == null || Files.isSymbolicLink(raw)
                || !Files.isDirectory(raw, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " is not a safe directory");
        }
        return raw.toAbsolutePath().normalize().toRealPath();
    }

    record Envelope(int schemaVersion,
                    String caseId,
                    int repeat,
                    String mode,
                    BenchmarkToolProfile toolProfile,
                    String answer,
                    EvidenceMetricsV2 llmMetrics,
                    List<BenchmarkToolExecutionEvidence> toolExecutions,
                    String verifierWorkspaceTreeSha256,
                    Integer verifierWorkspaceFileCount,
                    Long verifierWorkspaceTotalBytes,
                    String verifierBundleTreeSha256,
                    Integer verifierBundleFileCount,
                    Long verifierBundleTotalBytes) {
    }

    /** Frozen schema-v2 projection. New runner metrics must not silently alter verifier input. */
    record EvidenceMetricsV2(int calls,
                             long inputTokens,
                             long outputTokens,
                             long cachedInputTokens,
                             long toolCalls,
                             long elapsedMillis,
                             int successfulCalls,
                             String resolvedModel,
                             boolean resolvedModelConsistent,
                             boolean usageComplete,
                             String systemPromptSha256,
                             String initialToolSchemaSha256,
                             boolean requestFingerprintComplete) {
        static EvidenceMetricsV2 from(TracingLlmClient.Metrics metrics) {
            if (metrics == null) {
                return null;
            }
            return new EvidenceMetricsV2(
                    metrics.calls(),
                    metrics.inputTokens(),
                    metrics.outputTokens(),
                    metrics.cachedInputTokens(),
                    metrics.toolCalls(),
                    metrics.elapsedMillis(),
                    metrics.successfulCalls(),
                    metrics.resolvedModel(),
                    metrics.resolvedModelConsistent(),
                    metrics.usageComplete(),
                    metrics.systemPromptSha256(),
                    metrics.initialToolSchemaSha256(),
                    metrics.requestFingerprintComplete());
        }
    }
}
