package com.paicli.eval.benchmark.finalset.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.eval.benchmark.formal.*;
import com.paicli.eval.benchmark.scoring.ScoringContract;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Single generated E1 case descriptor for binding tests, not a 28-case formal admission. */
public final class E1BindingTestSource {
    private E1BindingTestSource() { }
    public static FormalExecutionPlan.CasePlan generate(Path root) throws Exception {
        Files.createDirectory(root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        byte[] seed = new byte[32]; Arrays.fill(seed, (byte)27);
        E1CaseMaterializer.materializePrototype(new PrivateSourceWriter(root), SeededVariant.derive(seed, "E1", "1"));
        var json = new ObjectMapper(); String scoringPath = "validators/final/_private/scoring-contracts/E1.json";
        var scoring = json.readValue(root.resolve(scoringPath).toFile(), ScoringContract.class);
        var deps = new ArrayList<FormalExecutionPlan.VerifierDependency>();
        for (String path : List.of("validators/final/E1", "validators/final/_private/e1_replay.py", "validators/final/_private/e1_verify.py",
                "validators/final/_private/oracles/E1.json", scoringPath))
            deps.add(new FormalExecutionPlan.VerifierDependency(path, root.resolve(path), path.equals("validators/final/E1") ? "0500" : "0400",
                    Files.size(root.resolve(path)), hash(Files.readAllBytes(root.resolve(path)))));
        deps.sort(Comparator.comparing(FormalExecutionPlan.VerifierDependency::frozenPath));
        String bundle = VerifierBundleIdentity.digest(deps.stream().map(d -> new VerifierBundleIdentity.Entry(d.frozenPath(), d.mode(), d.size(), d.sha256())).toList());
        String scoringSha = hash(Files.readAllBytes(root.resolve(scoringPath)));
        var contract = FinalCaseContractCompiler.compile(root, "E1");
        String fixturePath = "fixtures/final/E1"; var files = new ArrayList<FormalExecutionPlan.FixtureFile>();
        for (String name : List.of("left.csv", "right.csv")) {
            String path = fixturePath + "/" + name;
            files.add(new FormalExecutionPlan.FixtureFile(path, hash(Files.readAllBytes(root.resolve(path))), Files.size(root.resolve(path)), "0400"));
        }
        var snapshotText = new StringBuilder("paicli-formal-fixture-snapshot-v1\0DIRECTORY\0" + fixturePath + "\n");
        for (var file : files) snapshotText.append(file.frozenPath()).append('\0').append(file.sha256()).append('\0').append(file.mode()).append('\0').append(file.size()).append('\n');
        var fixture = new FormalExecutionPlan.FixtureSnapshot(fixturePath, fixturePath, root.resolve(fixturePath), FormalBenchmarkPreflight.FixtureKind.DIRECTORY,
                hash(snapshotText.toString().getBytes(StandardCharsets.UTF_8)), files.size(), files.stream().mapToLong(FormalExecutionPlan.FixtureFile::size).sum(), files);
        String prompt = Files.readString(root.resolve("prompts/final/E1.md"));
        // Test-only descriptor for the synthetic 28-slot admission harness, not catalog registration.
        Path descriptor = root.resolve("provenance/final/E1/execution-contract.json");
        Files.createDirectories(descriptor.getParent());
        json.writeValue(descriptor.toFile(), contract);
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(
                    Files.isDirectory(path) ? "r-x------" : path.equals(root.resolve("validators/final/E1")) ? "r-x------" : "r--------"));
        }
        return new FormalExecutionPlan.CasePlan(1, contract, scoring, root.resolve(scoringPath), scoringSha, prompt,
                hash(prompt.getBytes(StandardCharsets.UTF_8)), fixture,
                new FormalExecutionPlan.VerifierCommand(root, List.of("validators/final/E1", "{workspace}", "{evidence}"),
                        "validators/final/E1", root.resolve("validators/final/E1"), scoring.verifierSha256(), deps, bundle));
    }
    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
