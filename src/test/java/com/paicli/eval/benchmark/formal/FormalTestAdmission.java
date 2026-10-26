package com.paicli.eval.benchmark.formal;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Synthetic filesystem admission only; not a production freezer attestation or final dataset. */
public final class FormalTestAdmission {
    private FormalTestAdmission() {}

    public static FormalBenchmarkAdmission.AdmittedBatch create(Path base, String toolProfile)
            throws Exception {
        return create(base, toolProfile, false);
    }

    public static FormalBenchmarkAdmission.AdmittedBatch createForExecution(Path base)
            throws Exception {
        return create(base, null, true);
    }

    /** Explicit new v4 two-model cohort; legacy entry points continue producing v3/252. */
    public static FormalBenchmarkAdmission.AdmittedBatch createTwoModels(Path base, String toolProfile)
            throws Exception {
        return create(base, toolProfile, false, true);
    }

    public static FormalBenchmarkAdmission.AdmittedBatch createForTwoModelExecution(Path base)
            throws Exception {
        return create(base, null, true, true);
    }

    /** 27 synthetic cases plus one real generated D1; not a final-dataset attestation. */
    public static FormalBenchmarkAdmission.AdmittedBatch createWithGeneratedD1(Path base, Path source)
            throws Exception {
        return createWithGeneratedMock(base, source, "D1");
    }

    /** One real generated mock case in an otherwise synthetic 28-case admission. No production attestation. */
    public static FormalBenchmarkAdmission.AdmittedBatch createWithGeneratedMock(Path base, Path source, String caseId)
            throws Exception {
        if (!java.util.List.of("D1", "D2", "D3", "D4", "F4").contains(caseId)) throw new IllegalArgumentException("unknown mock control");
        return createWithGeneratedCase(base, source, caseId);
    }

    /** One standalone E1 prototype and 27 synthetic cases; never a production dataset attestation. */
    public static FormalBenchmarkAdmission.AdmittedBatch createWithGeneratedE1(Path base, Path source) throws Exception {
        return createWithGeneratedCase(base, source, "E1");
    }
    /** One generated F1 and 27 synthetic cases; never a production admission. */
    public static FormalBenchmarkAdmission.AdmittedBatch createWithGeneratedF1(Path base, Path source) throws Exception {
        return createWithGeneratedCase(base, source, "F1");
    }
    /** One generated F2 with synthetic admission peers; never provider or OS-audit attestation. */
    public static FormalBenchmarkAdmission.AdmittedBatch createWithGeneratedF2(Path base, Path source) throws Exception {
        return createWithGeneratedCase(base, source, "F2");
    }

    /** One generated F3 and 27 synthetic peers; not a completed or publishable dataset. */
    public static FormalBenchmarkAdmission.AdmittedBatch createWithGeneratedF3(Path base, Path source) throws Exception {
        return createWithGeneratedCase(base, source, "F3");
    }

    private static FormalBenchmarkAdmission.AdmittedBatch createWithGeneratedCase(Path base, Path source, String caseId) throws Exception {
        var fixture = FormalBenchmarkPreflightTest.Fixture.create(base, source, caseId);
        try (var walk = Files.walk(fixture.frozenRoot())) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                String mode = Files.isDirectory(path) || Files.isExecutable(path) ? "r-x------" : "r--------";
                Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString(mode));
            }
        }
        return new FormalBenchmarkAdmission(fixture.preflight()).admit(new FormalBenchmarkAdmission.Inputs(
                fixture.frozenRoot(), fixture.publicRepository(), fixture.executableContractFile(),
                fixture.batchContractFile(), fixture.candidateJar(), fixture.runnerJar()));
    }

    private static FormalBenchmarkAdmission.AdmittedBatch create(Path base, String toolProfile,
                                                                boolean readOnlyTree) throws Exception {
        return create(base, toolProfile, readOnlyTree, false);
    }

    private static FormalBenchmarkAdmission.AdmittedBatch create(Path base, String toolProfile,
                                                                boolean readOnlyTree, boolean twoModels) throws Exception {
        var f = twoModels ? FormalBenchmarkPreflightTest.Fixture.createTwoModels(base)
                : FormalBenchmarkPreflightTest.Fixture.create(base);
        if (toolProfile != null) {
            var mapper = FormalContractSupport.MAPPER;
            ObjectNode suite = (ObjectNode) mapper.readTree(f.executableContractFile().toFile());
            ((ObjectNode) suite.path("cases").get(0)).put("toolProfile", toolProfile);
            mapper.writeValue(f.executableContractFile().toFile(), suite);
            ObjectNode batch = (ObjectNode) mapper.readTree(f.batchContractFile().toFile());
            batch.put("executableSuiteContractSha256", HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            Files.readAllBytes(f.executableContractFile()))));
            mapper.writeValue(f.batchContractFile().toFile(), batch);
        }
        if (readOnlyTree) {
            try (var walk = Files.walk(f.frozenRoot())) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    String mode = Files.isDirectory(p) || Files.isExecutable(p) ? "r-x------" : "r--------";
                    Files.setPosixFilePermissions(p, java.nio.file.attribute.PosixFilePermissions.fromString(mode));
                }
            }
        }
        return new FormalBenchmarkAdmission(f.preflight()).admit(
                new FormalBenchmarkAdmission.Inputs(f.frozenRoot(), f.publicRepository(),
                        f.executableContractFile(), f.batchContractFile(),
                        f.candidateJar(), f.runnerJar()));
    }
}
