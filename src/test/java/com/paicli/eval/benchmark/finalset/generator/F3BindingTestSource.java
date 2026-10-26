package com.paicli.eval.benchmark.finalset.generator;

import com.paicli.eval.benchmark.formal.*;
import com.paicli.eval.benchmark.scoring.ScoringContract;
import com.paicli.eval.benchmark.safety.F3FrozenOracle;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** One synthetic generated F3 descriptor for binding tests; never a complete suite admission. */
public final class F3BindingTestSource {
    private F3BindingTestSource() { }
    static Path materialize(Path root) throws Exception {
        Files.createDirectory(root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        root = root.toRealPath();
        byte[] seed = new byte[32]; Arrays.fill(seed, (byte)39);
        var variant = SeededVariant.derive(seed, "F3", "1"); var writer = new PrivateSourceWriter(root);
        writer.text("prompts/final/F3.md", F3CaseMaterializer.prompt(variant));
        writer.json("provenance/final/F3/CASE-METADATA.json", Map.of("caseId", "F3", "variantId", variant.variantId()));
        ImplementedCaseMaterializers.materialize(writer, FinalSourceRecipeCatalog.require("F3"), variant);
        writer.json(FinalCaseContractCompiler.contractPath("F3"), FinalCaseContractCompiler.compile(root, "F3"));
        return root;
    }
    public static FormalExecutionPlan.CasePlan generate(Path root) throws Exception {
        root = materialize(root);
        var contract = FinalCaseContractCompiler.compile(root, "F3");
        String scoringPath = contract.scoringContractPath(); var scoring = ScoringContract.load(root.resolve(scoringPath));
        var deps = new ArrayList<FormalExecutionPlan.VerifierDependency>();
        for (String path : contract.verifierDependencyPaths())
            deps.add(new FormalExecutionPlan.VerifierDependency(path, root.resolve(path), path.equals(contract.verifierEntryPath()) ? "0500" : "0400",
                    Files.size(root.resolve(path)), FinalCaseContractCompiler.sha256(root.resolve(path))));
        deps.sort(Comparator.comparing(FormalExecutionPlan.VerifierDependency::frozenPath));
        String bundle = VerifierBundleIdentity.digest(deps.stream().map(d -> new VerifierBundleIdentity.Entry(d.frozenPath(), d.mode(), d.size(), d.sha256())).toList());
        var oracle = F3FrozenOracle.parse(Files.readAllBytes(root.resolve(F3FrozenOracle.PATH)));
        String fixturePath = "fixtures/final/F3"; var files = new ArrayList<FormalExecutionPlan.FixtureFile>();
        for (String name : new TreeSet<>(oracle.files().keySet())) {
            String path = fixturePath + "/" + name;
            files.add(new FormalExecutionPlan.FixtureFile(path, FinalCaseContractCompiler.sha256(root.resolve(path)), Files.size(root.resolve(path)), "0400"));
        }
        var snapshotText = new StringBuilder("paicli-formal-fixture-snapshot-v1\0DIRECTORY\0" + fixturePath + "\n");
        for (var file : files) snapshotText.append(file.frozenPath()).append('\0').append(file.sha256()).append('\0').append(file.mode()).append('\0').append(file.size()).append('\n');
        var fixture = new FormalExecutionPlan.FixtureSnapshot(fixturePath, fixturePath, root.resolve(fixturePath), FormalBenchmarkPreflight.FixtureKind.DIRECTORY,
                hash(snapshotText.toString()), files.size(), files.stream().mapToLong(FormalExecutionPlan.FixtureFile::size).sum(), files);
        String prompt = oracle.prompt(), scoringSha = FinalCaseContractCompiler.sha256(root.resolve(scoringPath));
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(
                    Files.isDirectory(path) || path.equals(root.resolve(contract.verifierEntryPath())) ? "r-x------" : "r--------"));
        }
        return new FormalExecutionPlan.CasePlan(1, contract, scoring, root.resolve(scoringPath), scoringSha, prompt, hash(prompt), fixture,
                new FormalExecutionPlan.VerifierCommand(root, List.of("validators/final/F3", "{workspace}", "{evidence}"),
                        contract.verifierEntryPath(), root.resolve(contract.verifierEntryPath()), scoring.verifierSha256(), deps, bundle));
    }
    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
