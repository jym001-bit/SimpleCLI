package com.paicli.eval.benchmark.formal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormalBatchContractTest {
    @Test
    void writesLoadsValidatesSuiteOrderAndRedactsDockerPath(@TempDir Path tempDir) throws Exception {
        Path docker = executable(tempDir.resolve("private-bin").resolve("docker-formal"));
        FormalBatchContract contract = validContract(docker);
        Path executableSuiteFile = tempDir.resolve("executable-suite.json");
        FinalExecutableSuiteContractTest.validContract().write(executableSuiteFile);
        contract = withExecutableSuiteDigest(
                contract, FormalContractSupport.sha256(executableSuiteFile));
        Path file = tempDir.resolve("formal-batch.json");

        contract.write(file);
        FormalBatchContract loaded = FormalBatchContract.load(file);
        assertEquals(FinalExecutableSuiteContractTest.validContract(),
                loaded.validateAgainst(executableSuiteFile));

        assertEquals(contract, loaded);
        assertEquals(3, loaded.models().size());
        assertEquals(3, loaded.repeats());
        assertEquals(1_000_000, loaded.commonContextCapTokens());
        assertEquals(16_384, loaded.maxOutputTokensPerCall());
        assertEquals("c".repeat(64), loaded.runnerInventorySha256());
        assertFalse(loaded.bestOfN());
        assertFalse(loaded.dirty());
        assertFalse(loaded.toString().contains(docker.getParent().toString()));
        assertTrue(loaded.toString().contains("docker-formal@"));
        assertTrue(loaded.toString().contains("cases=28"));
    }

    @Test
    void rejectsWrongModelsRepeatsFlagsPolicyAndCaseOrder(@TempDir Path tempDir) throws Exception {
        Path docker = executable(tempDir.resolve("docker"));
        FormalBatchContract valid = validContract(docker);

        List<FormalBatchContract.ModelBinding> reversed = new ArrayList<>(valid.models());
        java.util.Collections.reverse(reversed);
        assertThrows(IllegalArgumentException.class,
                () -> copy(valid, reversed, 3, valid.caseOrder(), false, false,
                        FormalBatchContract.INVALID_RUN_POLICY));
        assertThrows(IllegalArgumentException.class,
                () -> copy(valid, valid.models(), 2, valid.caseOrder(), false, false,
                        FormalBatchContract.INVALID_RUN_POLICY));
        assertThrows(IllegalArgumentException.class,
                () -> copy(valid, valid.models(), 3, valid.caseOrder(), true, false,
                        FormalBatchContract.INVALID_RUN_POLICY));
        assertThrows(IllegalArgumentException.class,
                () -> copy(valid, valid.models(), 3, valid.caseOrder(), false, true,
                        FormalBatchContract.INVALID_RUN_POLICY));
        assertThrows(IllegalArgumentException.class,
                () -> copy(valid, valid.models(), 3, valid.caseOrder(), false, false,
                        "RERUN_ONLY_FAILED_MODEL"));

        List<String> duplicateOrder = new ArrayList<>(valid.caseOrder());
        duplicateOrder.set(27, duplicateOrder.get(0));
        assertThrows(IllegalArgumentException.class,
                () -> copy(valid, valid.models(), 3, duplicateOrder, false, false,
                        FormalBatchContract.INVALID_RUN_POLICY));
        assertThrows(IllegalArgumentException.class, () -> withContextCap(valid, 999_999));
        assertThrows(IllegalArgumentException.class, () -> withContextCap(valid, 1_000_001));
        assertThrows(IllegalArgumentException.class, () -> withOutputCap(valid, 16_383));
        assertThrows(IllegalArgumentException.class, () -> withOutputCap(valid, 16_385));
    }

    @Test
    void rejectsNonCanonicalOrNonExecutableDockerPath(@TempDir Path tempDir) throws Exception {
        Path executable = executable(tempDir.resolve("bin").resolve("docker"));
        FormalBatchContract valid = validContract(executable);

        assertThrows(IllegalArgumentException.class,
                () -> withDocker(valid, executable.getParent().resolve("..").resolve("bin/docker").toString()));

        Path plain = Files.writeString(tempDir.resolve("plain-docker"), "not executable");
        plain.toFile().setExecutable(false, false);
        assertThrows(IllegalArgumentException.class,
                () -> withDocker(valid, plain.toString()));
    }

    @Test
    void loaderRejectsUnknownDuplicateAndTrailingJson(@TempDir Path tempDir) throws Exception {
        Path docker = executable(tempDir.resolve("docker"));
        Path validFile = tempDir.resolve("valid.json");
        validContract(docker).write(validFile);
        String json = Files.readString(validFile);

        assertInvalid(tempDir.resolve("unknown.json"),
                json.replaceFirst("\\{", "{\"unknown\":1,"));
        assertInvalid(tempDir.resolve("duplicate.json"),
                json.replaceFirst("\"repeats\"\\s*:\\s*3",
                        "\"repeats\":3,\"repeats\":3"));
        assertInvalid(tempDir.resolve("trailing.json"), json + "\nfalse\n");
        assertInvalid(tempDir.resolve("legacy-v2-version.json"),
                json.replaceFirst("\"contractVersion\"\\s*:\\s*3", "\"contractVersion\":2"));
        assertInvalid(tempDir.resolve("legacy-v2-format.json"),
                json.replace("paicli-formal-batch-contract-v3",
                        "paicli-formal-batch-contract-v2"));
        assertInvalid(tempDir.resolve("missing-output-cap.json"),
                json.replaceFirst(",?\\s*\"maxOutputTokensPerCall\"\\s*:\\s*16384", ""));
    }

    private static FormalBatchContract validContract(Path docker) {
        return new FormalBatchContract(
                FormalBatchContract.LEGACY_VERSION,
                FormalBatchContract.LEGACY_FORMAT,
                "1".repeat(64),
                "2".repeat(64),
                "3".repeat(64),
                "4".repeat(64),
                "5".repeat(64),
                "6".repeat(64),
                "a".repeat(40),
                "7".repeat(64),
                "c".repeat(64),
                "b".repeat(40),
                "sha256:" + "8".repeat(64),
                "sha256:" + "9".repeat(64),
                docker.toString(),
                dockerSha256(docker),
                List.of(
                        new FormalBatchContract.ModelBinding("deepseek", "deepseek-v4-flash"),
                        new FormalBatchContract.ModelBinding("hunyuan", "hy4-preview"),
                        new FormalBatchContract.ModelBinding("glm", "glm-5.3-flash")),
                3,
                FinalExecutableSuiteContractTest.validContract().orderedCaseIds(),
                "UTC",
                "2026-08-31",
                1_000_000,
                16_384,
                FormalBatchContract.INVALID_RUN_POLICY,
                false,
                false);
    }

    private static FormalBatchContract copy(FormalBatchContract source,
                                            List<FormalBatchContract.ModelBinding> models,
                                            int repeats,
                                            List<String> order,
                                            boolean bestOfN,
                                            boolean dirty,
                                            String invalidPolicy) {
        return new FormalBatchContract(
                source.contractVersion(), source.format(), source.executableSuiteContractSha256(),
                source.freezeManifestSha256(), source.contentTreeSha256(), source.suiteSha256(),
                source.validatorTreeSha256(), source.candidateJarSha256(), source.candidateCommit(),
                source.runnerJarSha256(), source.runnerInventorySha256(), source.runnerCommit(),
                source.workerImageId(),
                source.verifierImageId(), source.dockerExecutablePath(),
                source.dockerExecutableSha256(), models, repeats, order, source.timezone(),
                source.runtimeDate(), source.commonContextCapTokens(),
                source.maxOutputTokensPerCall(), invalidPolicy, bestOfN, dirty);
    }

    private static FormalBatchContract withDocker(FormalBatchContract source, String dockerPath) {
        return new FormalBatchContract(
                source.contractVersion(), source.format(), source.executableSuiteContractSha256(),
                source.freezeManifestSha256(), source.contentTreeSha256(), source.suiteSha256(),
                source.validatorTreeSha256(), source.candidateJarSha256(), source.candidateCommit(),
                source.runnerJarSha256(), source.runnerInventorySha256(), source.runnerCommit(),
                source.workerImageId(),
                source.verifierImageId(), dockerPath, source.dockerExecutableSha256(), source.models(),
                source.repeats(), source.caseOrder(), source.timezone(), source.runtimeDate(),
                source.commonContextCapTokens(), source.maxOutputTokensPerCall(),
                source.invalidRunPolicy(), source.bestOfN(), source.dirty());
    }

    private static FormalBatchContract withExecutableSuiteDigest(
            FormalBatchContract source,
            String executableSuiteDigest) {
        return new FormalBatchContract(
                source.contractVersion(), source.format(), executableSuiteDigest,
                source.freezeManifestSha256(), source.contentTreeSha256(), source.suiteSha256(),
                source.validatorTreeSha256(), source.candidateJarSha256(), source.candidateCommit(),
                source.runnerJarSha256(), source.runnerInventorySha256(), source.runnerCommit(),
                source.workerImageId(),
                source.verifierImageId(), source.dockerExecutablePath(),
                source.dockerExecutableSha256(), source.models(), source.repeats(), source.caseOrder(),
                source.timezone(), source.runtimeDate(), source.commonContextCapTokens(),
                source.maxOutputTokensPerCall(),
                source.invalidRunPolicy(), source.bestOfN(), source.dirty());
    }

    private static FormalBatchContract withContextCap(
            FormalBatchContract source,
            int contextCapTokens) {
        return new FormalBatchContract(
                source.contractVersion(), source.format(), source.executableSuiteContractSha256(),
                source.freezeManifestSha256(), source.contentTreeSha256(), source.suiteSha256(),
                source.validatorTreeSha256(), source.candidateJarSha256(), source.candidateCommit(),
                source.runnerJarSha256(), source.runnerInventorySha256(), source.runnerCommit(),
                source.workerImageId(), source.verifierImageId(), source.dockerExecutablePath(),
                source.dockerExecutableSha256(), source.models(), source.repeats(), source.caseOrder(),
                source.timezone(), source.runtimeDate(), contextCapTokens,
                source.maxOutputTokensPerCall(),
                source.invalidRunPolicy(), source.bestOfN(), source.dirty());
    }

    private static FormalBatchContract withOutputCap(
            FormalBatchContract source,
            int maxOutputTokensPerCall) {
        return new FormalBatchContract(
                source.contractVersion(), source.format(), source.executableSuiteContractSha256(),
                source.freezeManifestSha256(), source.contentTreeSha256(), source.suiteSha256(),
                source.validatorTreeSha256(), source.candidateJarSha256(), source.candidateCommit(),
                source.runnerJarSha256(), source.runnerInventorySha256(), source.runnerCommit(),
                source.workerImageId(), source.verifierImageId(), source.dockerExecutablePath(),
                source.dockerExecutableSha256(), source.models(), source.repeats(), source.caseOrder(),
                source.timezone(), source.runtimeDate(), source.commonContextCapTokens(),
                maxOutputTokensPerCall, source.invalidRunPolicy(), source.bestOfN(), source.dirty());
    }

    private static Path executable(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "#!/bin/sh\nexit 0\n");
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            if (!path.toFile().setExecutable(true, true)) {
                throw new IOException("cannot make test executable");
            }
        }
        return path.toRealPath();
    }

    private static String dockerSha256(Path docker) {
        try {
            return FormalContractSupport.sha256(docker);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void assertInvalid(Path file, String json) throws IOException {
        Files.writeString(file, json);
        IOException error = assertThrows(IOException.class, () -> FormalBatchContract.load(file));
        assertTrue(error.getMessage().contains("formal contract") || error.getCause() != null);
    }
}
