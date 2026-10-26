package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;

/** One-off symmetric verifier replay: never calls a model or edits original artifacts. */
public class VerifierReplay {
    static final ObjectMapper JSON = new ObjectMapper();
    static Path directory(Path p) throws Exception {
        return Files.createDirectory(p, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------")));
    }
    static void json(Path p, Object value) throws Exception {
        Files.writeString(p, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value),
                StandardOpenOption.CREATE_NEW);
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toRealPath();
        var suite = SuiteDefinition.load(Path.of(args[1]).toRealPath());
        Path output = directory(root.resolve("verifier-replay-v1"));
        var verifier = new DockerBenchmarkVerifier(Path.of("/usr/local/bin/docker"),
                "sha256:770224998e34bdd809f205a26d31bff62684b65e78751a3ab5c319d5791632e8");
        List<Object> reports = new ArrayList<>();
        for (String run : List.of("resume-deepseek-full-dev-r1", "resume-glm-full-dev-r1")) {
            Path source = root.resolve(run);
            if (!Files.isRegularFile(source.resolve("aggregate.json")))
                throw new IllegalStateException("original run is incomplete: " + run);
            Path replay = directory(output.resolve(run));
            List<Object> rows = new ArrayList<>();
            int score = 0, passed = 0;
            for (var c : suite.activeCases()) {
                Path models = source.resolve("cases").resolve(c.id()).resolve("models");
                Path old;
                try (var dirs = Files.list(models)) {
                    var all = dirs.toList();
                    if (all.size() != 1) throw new IllegalStateException("ambiguous model");
                    old = all.get(0).resolve("repeat-001");
                }
                ObjectNode evidence = (ObjectNode) JSON.readTree(
                        old.resolve("verifier-evidence/envelope.json").toFile());
                var originalSnapshot = new BenchmarkVerifierWorkspaceSnapshot.Snapshot(
                        old.resolve("verifier-workspace"),
                        evidence.path("verifierWorkspaceTreeSha256").asText(),
                        evidence.path("verifierWorkspaceFileCount").asInt(),
                        evidence.path("verifierWorkspaceTotalBytes").asLong());
                originalSnapshot.verifyUnchanged();
                Path episode = directory(replay.resolve(c.id()));
                Path workspace = directory(episode.resolve("workspace"));
                var frozen = BenchmarkVerifierWorkspaceSnapshot.create(originalSnapshot.directory(), workspace);
                if (!frozen.treeSha256().equals(originalSnapshot.treeSha256()))
                    throw new IllegalStateException("replay changed candidate snapshot");
                var bundle = BenchmarkVerifierBundle.create(suite.resolveVerifier(c),
                        directory(episode.resolve("bundle")));
                String oldBundle = evidence.path("verifierBundleTreeSha256").asText();
                evidence.put("verifierBundleTreeSha256", bundle.treeSha256());
                evidence.put("verifierBundleFileCount", bundle.fileCount());
                evidence.put("verifierBundleTotalBytes", bundle.totalBytes());
                Path envelope = episode.resolve("envelope.json");
                json(envelope, evidence);
                var result = verifier.verify(bundle.invocation(), workspace,
                        directory(episode.resolve("home")), envelope, Duration.ofSeconds(60));
                frozen.verifyUnchanged();
                originalSnapshot.verifyUnchanged();
                bundle.verifyUnchanged();
                json(episode.resolve("verifier.json"), result);
                boolean pass = result.status() == BenchmarkVerifier.Status.PASSED;
                if (pass) { score += c.weight(); passed++; }
                var row = Map.of("caseId", c.id(), "weight", c.weight(), "status", result.status(),
                        "snapshotSha256", frozen.treeSha256(), "oldBundleSha256", oldBundle,
                        "newBundleSha256", bundle.treeSha256(), "exitCode", result.exitCode());
                rows.add(row);
                System.out.println(run + " " + c.id() + " " + result.status());
            }
            reports.add(Map.of("runId", run, "replayOnly", true, "providerCalls", 0,
                    "passed", passed, "total", rows.size(), "diagnosticScore", score,
                    "publishable", false, "cases", rows));
        }
        json(output.resolve("summary.json"), reports);
    }
}
