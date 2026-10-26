package com.paicli.eval.benchmark.finalset.generator;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.eval.benchmark.formal.FinalExecutableSuiteContract;
import com.paicli.eval.benchmark.formal.VerifierBundleIdentity;
import com.paicli.eval.benchmark.scoring.ScoringContract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Compiles generated per-case inputs to real v4 case contracts, not execution authority.
 * Full-suite assembly, source freeze and production admission remain separate gates.
 */
public final class FinalCaseContractCompiler {
    public static final String POLICY_VERSION = "case-execution-policy-v1";
    private static final long MAX_DEPENDENCY_BYTES = 16L * 1024 * 1024;
    private static final ObjectMapper ORACLE_JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final List<String> DISPATCH_EVENTS = List.of("worker_dispatch_started",
            "worker_dispatch_finished", "verifier_dispatch_started", "verifier_dispatch_finished");

    private FinalCaseContractCompiler() {}

    /** Does not execute a provider, change the source, or create a reduced formal suite. */
    public static FinalExecutableSuiteContract.CaseContract compile(Path sourceRoot, String caseId)
            throws IOException {
        var recipe = FinalSourceRecipeCatalog.require(caseId);
        if (recipe.status() != FinalSourceRecipeCatalog.ImplementationStatus.IMPLEMENTED)
            throw new IOException("cannot compile an unimplemented final recipe: " + caseId);
        requirePrivateRoot(sourceRoot);
        String entry = "validators/final/" + caseId;
        String scoringPath = "validators/final/_private/scoring-contracts/" + caseId + ".json";
        String oracle = "validators/final/_private/oracles/" + caseId + ".json";
        TreeSet<String> paths = new TreeSet<>(ImplementedCaseMaterializers.verifierRuntimePaths(caseId));
        paths.add(entry); paths.add(scoringPath); paths.add(oracle);
        Map<String, CapturedFile> captured = new LinkedHashMap<>();
        for (String path : paths) captured.put(path, capture(sourceRoot, path, path.equals(entry)));

        ScoringContract scoring = ScoringContract.load(sourceRoot.resolve(scoringPath));
        String runtimeSha = ImplementedCaseMaterializers.digestFiles(sourceRoot,
                ImplementedCaseMaterializers.verifierRuntimePaths(caseId));
        ScoringContract expected = ImplementedCaseMaterializers.scoringContract(
                caseId, captured.get(entry).sha256(), runtimeSha);
        if (!expected.equals(scoring))
            throw new IOException("generated scoring policy differs from the registered recipe: " + caseId);
        var oracleJson = ORACLE_JSON.readTree(sourceRoot.resolve(oracle).toFile());
        if (!caseId.equals(oracleJson.path("caseId").asText())
                || !recipe.toolProfile().equals(oracleJson.path("expectedToolProfile").asText()))
            throw new IOException("generated oracle does not match case/tool profile: " + caseId);
        if (List.of("D1", "D2", "D3", "F4").contains(caseId))
            com.paicli.eval.benchmark.mock.FrozenMcpOracle.parse(caseId, Files.readAllBytes(sourceRoot.resolve(oracle)));
        if ("D4".equals(caseId))
            com.paicli.eval.benchmark.mock.D4FrozenOracle.parse(Files.readAllBytes(sourceRoot.resolve(oracle)));
        if ("F1".equals(caseId)) {
            var boundary = com.paicli.eval.benchmark.safety.F1FrozenOracle.parse(Files.readAllBytes(sourceRoot.resolve(oracle)));
            if (!boundary.prompt().equals(Files.readString(sourceRoot.resolve("prompts/final/F1.md")))) throw new IOException("F1 prompt/source mismatch");
            Path fixture = sourceRoot.resolve("fixtures/final/F1"); requirePrivateRoot(fixture);
            try (var files = Files.list(fixture)) {
                if (!files.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()).equals(boundary.files().keySet()))
                    throw new IOException("F1 fixture inventory mismatch");
            }
            for (var file : boundary.files().entrySet()) {
                Path path = fixture.resolve(file.getKey());
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        || !file.getValue().equals(Files.readString(path))) throw new IOException("F1 fixture/source mismatch");
            }
        }
        if ("F2".equals(caseId)) {
            var command = com.paicli.eval.benchmark.safety.F2FrozenOracle.parse(Files.readAllBytes(sourceRoot.resolve(oracle)));
            if (!command.prompt().equals(Files.readString(sourceRoot.resolve("prompts/final/F2.md")))) throw new IOException("F2 prompt/source mismatch");
            Path fixture = sourceRoot.resolve("fixtures/final/F2"); requirePrivateRoot(fixture);
            var actualFiles = new TreeSet<String>(); var directories = new TreeSet<String>();
            try (var walk = Files.walk(fixture)) {
                for (var iterator = walk.iterator(); iterator.hasNext();) {
                    Path path = iterator.next(); if (path.equals(fixture)) continue;
                    String relative = fixture.relativize(path).toString();
                    if (Files.isSymbolicLink(path)) throw new IOException("F2 fixture symlink");
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        if (!"archive".equals(relative)) throw new IOException("F2 fixture unexpected directory");
                        requirePrivateRoot(path); directories.add(relative); continue;
                    }
                    String expectedFile = command.files().get(relative);
                    if (expectedFile == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                            || Files.size(path) != expectedFile.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                            || ((Number)Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1
                            || !expectedFile.equals(Files.readString(path))) throw new IOException("F2 fixture/source mismatch");
                    actualFiles.add(relative);
                }
            }
            if (!actualFiles.equals(new TreeSet<>(command.files().keySet())) || !directories.equals(new TreeSet<>(List.of("archive"))))
                throw new IOException("F2 fixture inventory mismatch");
        }
        if ("F3".equals(caseId)) {
            var injection = com.paicli.eval.benchmark.safety.F3FrozenOracle.parse(Files.readAllBytes(sourceRoot.resolve(oracle)));
            if (!injection.prompt().equals(Files.readString(sourceRoot.resolve("prompts/final/F3.md"))))
                throw new IOException("F3 prompt/source mismatch");
            Path fixture = sourceRoot.resolve("fixtures/final/F3"); requirePrivateRoot(fixture);
            var actualFiles = new TreeSet<String>(); var directories = new TreeSet<String>();
            try (var walk = Files.walk(fixture)) {
                for (var iterator = walk.iterator(); iterator.hasNext();) {
                    Path path = iterator.next(); if (path.equals(fixture)) continue;
                    String relative = fixture.relativize(path).toString();
                    if (Files.isSymbolicLink(path)) throw new IOException("F3 fixture symlink");
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        if (!List.of("inputs", "config", "private").contains(relative)) throw new IOException("F3 fixture unexpected directory");
                        requirePrivateRoot(path); directories.add(relative); continue;
                    }
                    String expectedFile = injection.files().get(relative);
                    if (expectedFile == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                            || Files.size(path) != expectedFile.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                            || ((Number)Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1
                            || !List.of("rw-------", "r--------").contains(PosixFilePermissions.toString(Files.getPosixFilePermissions(path)))
                            || !expectedFile.equals(Files.readString(path))) throw new IOException("F3 fixture/source mismatch");
                    actualFiles.add(relative);
                }
            }
            if (!actualFiles.equals(new TreeSet<>(injection.files().keySet()))
                    || !directories.equals(new TreeSet<>(List.of("inputs", "config", "private"))))
                throw new IOException("F3 fixture inventory mismatch");
        }
        if ("E1".equals(caseId)) {
            var planSource = com.paicli.eval.benchmark.plan.E1FrozenOracle.parse(Files.readAllBytes(sourceRoot.resolve(oracle)));
            if (!planSource.prompt().equals(Files.readString(sourceRoot.resolve("prompts/final/E1.md"))))
                throw new IOException("E1 generated prompt/source mismatch");
            Path fixture = sourceRoot.resolve("fixtures/final/E1");
            requirePrivateRoot(fixture);
            try (var files = Files.list(fixture)) {
                if (!files.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()).equals(planSource.files().keySet()))
                    throw new IOException("E1 generated fixture contains unexpected files");
            }
            for (var file : planSource.files().entrySet()) {
                Path path = fixture.resolve(file.getKey());
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        || !file.getValue().equals(Files.readString(path))) throw new IOException("E1 generated fixture/source mismatch");
            }
        }
        if ("E2".equals(caseId)) {
            var teamSource = com.paicli.eval.benchmark.team.E2FrozenOracle.parse(Files.readAllBytes(sourceRoot.resolve(oracle)));
            if (!teamSource.prompt().equals(Files.readString(sourceRoot.resolve("prompts/final/E2.md"))))
                throw new IOException("E2 generated prompt/source mismatch");
            Path fixture = sourceRoot.resolve("fixtures/final/E2");
            requirePrivateRoot(fixture);
            var actualFiles = new TreeSet<String>();
            try (var walk = Files.walk(fixture)) {
                for (var iterator = walk.iterator(); iterator.hasNext(); ) {
                    Path path = iterator.next();
                    if (path.equals(fixture)) continue;
                    if (Files.isSymbolicLink(path)) throw new IOException("E2 fixture symlink");
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { requirePrivateRoot(path); continue; }
                    actualFiles.add(fixture.relativize(path).toString());
                }
            }
            if (!actualFiles.equals(new TreeSet<>(teamSource.files().keySet())))
                throw new IOException("E2 generated fixture contains unexpected files");
            for (var file : teamSource.files().entrySet()) {
                Path path = fixture.resolve(file.getKey());
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        || !file.getValue().equals(Files.readString(path))) throw new IOException("E2 generated fixture/source mismatch");
            }
        }

        List<VerifierBundleIdentity.Entry> bundle = captured.entrySet().stream().map(item ->
                new VerifierBundleIdentity.Entry(item.getKey(), item.getValue().frozenMode(),
                        item.getValue().size(), item.getValue().sha256())).toList();
        var limits = limits(caseId);
        var result = new FinalExecutableSuiteContract.CaseContract(caseId, recipe.category(),
                FinalExecutableSuiteContract.Level.valueOf(recipe.level().name()), recipe.weight(),
                FinalExecutableSuiteContract.Mode.valueOf(recipe.mode().name()),
                FinalExecutableSuiteContract.ToolProfile.valueOf(recipe.toolProfile()),
                limits.timeoutSeconds(), limits.tokenBudget(), limits.hardMaxIterations(),
                limits.stagnationWindow(), mockProfile(caseId), DISPATCH_EVENTS,
                scoring.assertions().stream().filter(ScoringContract.AssertionRule::mandatory)
                        .map(ScoringContract.AssertionRule::id).toList(),
                scoring.hardGates().stream().map(ScoringContract.HardGateRule::id).toList(),
                requiredEvidence(caseId), scoringPath, captured.get(scoringPath).sha256(),
                entry, captured.get(entry).sha256(), List.copyOf(paths), VerifierBundleIdentity.digest(bundle));
        for (var item : captured.entrySet())
            if (!item.getValue().equals(capture(sourceRoot, item.getKey(), item.getKey().equals(entry))))
                throw new IOException("generated dependency changed while compiling: " + caseId);
        requirePrivateRoot(sourceRoot);
        return result;
    }

    public static String contractPath(String caseId) {
        FinalSourceRecipeCatalog.require(caseId);
        return "provenance/final/" + caseId + "/execution-contract.json";
    }

    /** Shared by all providers; draft calibration may change these only before final freeze. */
    public static Limits limits(String caseId) {
        FinalSourceRecipeCatalog.require(caseId);
        int timeoutMinutes = switch (caseId) {
            case "A1", "D1" -> 8;
            case "A2", "F1", "F4", "G1", "G2" -> 10;
            case "A3", "A4", "D2", "D4", "F2", "F3" -> 12;
            case "B1", "B2", "D3" -> 15;
            case "B3" -> 18;
            case "B4" -> 22;
            case "B5" -> 25;
            case "B6" -> 35;
            case "C1", "C2", "E1" -> 30;
            case "E2" -> 40;
            case "C3" -> 15;
            default -> throw new IllegalArgumentException("runtime policy not implemented for recipe");
        };
        // Cumulative task tokens, not the context window or per-call output cap.
        boolean mock = List.of("D1", "D2", "D3", "D4", "F4").contains(caseId);
        if ("E1".equals(caseId)) return new Limits(timeoutMinutes * 60, 200_000, 32, 8);
        if ("E2".equals(caseId)) return new Limits(timeoutMinutes * 60, 300_000, 48, 8);
        if (List.of("F1", "F2", "F3").contains(caseId)) return new Limits(timeoutMinutes * 60, 100_000, 32, 8);
        int tokens = caseId.startsWith("G") || mock ? 100_000 : 2_000_000;
        return new Limits(timeoutMinutes * 60, tokens, mock ? 32 : 128, 8);
    }

    private static String mockProfile(String caseId) {
        return switch (caseId) {
            case "D1" -> com.paicli.eval.benchmark.mock.D1FrozenOracle.PROFILE;
            case "D2" -> com.paicli.eval.benchmark.mock.D2FrozenOracle.PROFILE;
            case "D3" -> com.paicli.eval.benchmark.mock.D3FrozenOracle.PROFILE;
            case "F4" -> com.paicli.eval.benchmark.mock.F4FrozenOracle.PROFILE;
            case "F3" -> com.paicli.eval.benchmark.safety.F3FrozenOracle.PROFILE;
            case "D4" -> com.paicli.eval.benchmark.mock.D4FrozenOracle.PROFILE;
            default -> "none";
        };
    }

    private static List<String> requiredEvidence(String caseId) {
        List<String> evidence = new ArrayList<>(List.of("answer", "llm_metrics", "tool_events",
                "workspace_snapshot", "verifier_report"));
        switch (caseId) {
            case "A2" -> evidence.add("semantic_index");
            case "A3" -> evidence.add("judge_report");
            case "A4" -> { evidence.add("judge_report"); evidence.add("workspace_metadata"); }
            case "B5" -> evidence.add("scheduler_audit");
            case "C2" -> evidence.add("process_lifecycle");
            case "C3" -> evidence.add("command_provenance");
            case "D1" -> evidence.add("mock_audit");
            case "D2" -> { evidence.add("mock_audit"); evidence.add("mock_state"); }
            case "D3" -> { evidence.add("mock_audit"); evidence.add("mock_state"); evidence.add("approval_relay"); }
            case "F4" -> { evidence.add("mock_audit"); evidence.add("mock_state"); evidence.add("approval_relay"); evidence.add("provider_turns"); }
            case "F1" -> evidence.add("boundary_state");
            case "F2" -> { evidence.add("command_audit"); evidence.add("provider_turns"); }
            case "F3" -> { evidence.add("mock_audit"); evidence.add("mock_state"); evidence.add("provider_turns"); evidence.add("raw_tool_results"); evidence.add("stream_deltas"); }
            case "D4" -> { evidence.add("web_audit"); evidence.add("provider_turns"); }
            case "E1" -> { evidence.add("plan_audit"); evidence.add("scoped_request_fingerprints"); }
            case "E2" -> { evidence.add("team_audit"); evidence.add("scoped_request_fingerprints"); }
            default -> { }
        }
        return List.copyOf(evidence);
    }

    private static void requirePrivateRoot(Path root) throws IOException {
        if (root == null || !root.isAbsolute() || !root.equals(root.normalize())
                || Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !root.toRealPath().equals(root))
            throw new IOException("source root must be a canonical private directory");
        String mode = PosixFilePermissions.toString(Files.getPosixFilePermissions(root));
        if (!List.of("rwx------", "r-x------").contains(mode))
            throw new IOException("source root must be owner-only");
    }

    private static CapturedFile capture(Path root, String relative, boolean executable) throws IOException {
        Path file = root.resolve(relative);
        for (Path parent = file.getParent(); !parent.equals(root); parent = parent.getParent()) {
            if (parent == null || !parent.startsWith(root)) throw new IOException("dependency path escape");
            requirePrivateRoot(parent);
        }
        if (Files.isSymbolicLink(file) || !file.equals(file.toRealPath()))
            throw new IOException("generated dependency is not a canonical file");
        var before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.fileKey() == null || before.size() > MAX_DEPENDENCY_BYTES
                || ((Number) Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1)
            throw new IOException("generated dependency is not a bounded unique file");
        String mode = PosixFilePermissions.toString(Files.getPosixFilePermissions(file));
        if (!(executable ? List.of("rwx------", "r-x------") : List.of("rw-------", "r--------")).contains(mode))
            throw new IOException("generated dependency permissions differ from its role");
        String sha = sha256(file);
        var after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.fileKey().equals(after.fileKey()) || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime()))
            throw new IOException("generated dependency changed during read");
        return new CapturedFile(before.fileKey().toString(), before.size(), sha,
                executable ? "0500" : "0400", mode);
    }

    static String sha256(Path file) throws IOException {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = new byte[16 * 1024]; int count; long total = 0;
                while ((count = input.read(bytes)) != -1) {
                    if ((total += count) > MAX_DEPENDENCY_BYTES)
                        throw new IOException("generated dependency exceeds read budget");
                    hash.update(bytes, 0, count);
                }
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record Limits(int timeoutSeconds, int tokenBudget, int hardMaxIterations, int stagnationWindow) {}
    private record CapturedFile(String fileKey, long size, String sha256, String frozenMode, String sourceMode) {}
}
