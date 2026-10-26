package com.paicli.eval.benchmark;

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

class BenchmarkVerifierBundleTest {
    @Test
    void copiesOnlyArgvReferencedVerifierFiles(@TempDir Path rawTempDir) throws Exception {
        Path tempDir = rawTempDir.toRealPath();
        Path suite = Files.createDirectory(tempDir.resolve("private-suite"));
        Path validators = Files.createDirectories(suite.resolve("validators/case-one"));
        Path verifier = Files.writeString(validators.resolve("verify.sh"), "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(verifier, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        Files.writeString(validators.resolve("oracle.json"), "{\"ok\":true}\n");
        Files.writeString(suite.resolve("private-canary.txt"), "must-not-be-mounted\n");
        Path target = Files.createDirectory(tempDir.resolve("bundle"));
        CaseDefinition.VerifierInvocation invocation = new CaseDefinition.VerifierInvocation(
                suite, List.of("validators/case-one/verify.sh", CaseDefinition.WORKSPACE_PLACEHOLDER));

        BenchmarkVerifierBundle.Bundle bundle = BenchmarkVerifierBundle.create(invocation, target);

        assertEquals(target.toRealPath(), bundle.invocation().workingDirectory());
        assertEquals(1, bundle.fileCount());
        assertTrue(Files.isRegularFile(target.resolve("validators/case-one/verify.sh")));
        assertFalse(Files.exists(target.resolve("validators/case-one/oracle.json")));
        assertFalse(Files.exists(target.resolve("private-canary.txt")));
        assertEquals(64, bundle.treeSha256().length());
        assertEquals(Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_EXECUTE),
                Files.getPosixFilePermissions(target.resolve("validators/case-one/verify.sh")));
        bundle.verifyUnchanged();

        Files.setPosixFilePermissions(target.resolve("validators/case-one/verify.sh"), Set.of(
                PosixFilePermission.OWNER_READ));
        assertThrows(java.io.IOException.class, bundle::verifyUnchanged);
    }

    @Test
    void supportsInterpreterPlusOneExplicitScriptWithoutCopyingSiblingValidators(
            @TempDir Path rawTempDir) throws Exception {
        Path tempDir = rawTempDir.toRealPath();
        Path suite = Files.createDirectory(tempDir.resolve("suite"));
        Path validators = Files.createDirectory(suite.resolve("validators"));
        Files.writeString(validators.resolve("selected.sh"), "exit 0\n");
        Files.writeString(validators.resolve("other.sh"), "exit 99\n");
        Path target = Files.createDirectory(tempDir.resolve("bundle"));
        CaseDefinition.VerifierInvocation invocation = new CaseDefinition.VerifierInvocation(
                suite,
                List.of("bash", "validators/selected.sh", CaseDefinition.WORKSPACE_PLACEHOLDER));

        BenchmarkVerifierBundle.Bundle bundle = BenchmarkVerifierBundle.create(invocation, target);

        assertEquals(1, bundle.fileCount());
        assertTrue(Files.exists(target.resolve("validators/selected.sh")));
        assertFalse(Files.exists(target.resolve("validators/other.sh")));
    }

    @Test
    void rejectsSymlinkReferencedByVerifierArgv(@TempDir Path rawTempDir) throws Exception {
        Path tempDir = rawTempDir.toRealPath();
        Path suite = Files.createDirectory(tempDir.resolve("suite"));
        Path outside = Files.writeString(tempDir.resolve("outside.sh"), "exit 0\n");
        Files.createSymbolicLink(suite.resolve("verify.sh"), outside);
        Path target = Files.createDirectory(tempDir.resolve("bundle"));
        CaseDefinition.VerifierInvocation invocation = new CaseDefinition.VerifierInvocation(
                suite, List.of("verify.sh", CaseDefinition.WORKSPACE_PLACEHOLDER));

        assertThrows(java.io.IOException.class,
                () -> BenchmarkVerifierBundle.create(invocation, target));
    }
}
