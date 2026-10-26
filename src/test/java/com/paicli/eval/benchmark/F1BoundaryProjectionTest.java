package com.paicli.eval.benchmark;

import com.paicli.eval.benchmark.safety.F1FrozenOracle;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import static org.junit.jupiter.api.Assertions.*;

class F1BoundaryProjectionTest {
    @TempDir Path temp;
    @AfterEach void permissions() throws Exception {
        try (var walk = Files.walk(temp)) { for (Path p : walk.toList()) if (!Files.isSymbolicLink(p))
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(Files.isDirectory(p) ? "rwx------" : "rw-------")); }
    }
    @Test void projectionOmitsOnlyTheHostLinkAndDoesNotModifySourceOrOutside() throws Exception {
        var f = F1BoundarySessionTest.fixture(temp); var b = F1BoundarySession.prepare(f.workspace(), f.home(), f.definition());
        b.begin(F1BoundarySessionTest.request(f), f.workspace(), f.home()); b.finish();
        Path target = F1BoundarySessionTest.privateDir(f.root().resolve("snapshot"));
        var snapshot = BenchmarkVerifierWorkspaceSnapshot.createBoundaryProjection(f.workspace(), target, b);
        assertEquals(2, snapshot.fileCount()); assertFalse(Files.exists(target.resolve("review-drop"), LinkOption.NOFOLLOW_LINKS));
        assertEquals(f.definition().payload(), Files.readString(target.resolve("payload.txt")));
        assertEquals(F1BoundarySession.LINK_TARGET, Files.readSymbolicLink(f.workspace().resolve("review-drop")));
        b.verifyTerminalUnchanged(); snapshot.verifyUnchanged();
        Path ordinary = F1BoundarySessionTest.privateDir(f.root().resolve("ordinary"));
        assertThrows(BenchmarkVerifierWorkspaceSnapshot.UnsafeWorkspaceException.class,
                () -> BenchmarkVerifierWorkspaceSnapshot.create(f.workspace(), ordinary));
    }
    @Test void extraLinkCannotUseTheHostException() throws Exception {
        var f = F1BoundarySessionTest.fixture(temp); var b = F1BoundarySession.prepare(f.workspace(), f.home(), f.definition());
        b.begin(F1BoundarySessionTest.request(f), f.workspace(), f.home());
        Files.createSymbolicLink(f.workspace().resolve("second-link"), F1BoundarySession.LINK_TARGET); b.finish();
        assertThrows(BenchmarkVerifierWorkspaceSnapshot.UnsafeWorkspaceException.class, () -> BenchmarkVerifierWorkspaceSnapshot.createBoundaryProjection(
                f.workspace(), F1BoundarySessionTest.privateDir(f.root().resolve("snapshot")), b));
    }
    @Test void terminalDriftOrAnotherWorkspaceCannotBeProjected() throws Exception {
        var f = F1BoundarySessionTest.fixture(temp); var b = F1BoundarySession.prepare(f.workspace(), f.home(), f.definition());
        b.begin(F1BoundarySessionTest.request(f), f.workspace(), f.home()); b.finish();
        Path other = F1BoundarySessionTest.privateDir(f.root().resolve("other")), target = F1BoundarySessionTest.privateDir(f.root().resolve("snapshot"));
        assertThrows(IOException.class, () -> BenchmarkVerifierWorkspaceSnapshot.createBoundaryProjection(other, target, b));
        Files.writeString(f.root().resolve("f1-boundary/protected.txt"), "synthetic post-run tamper");
        assertThrows(IOException.class, () -> BenchmarkVerifierWorkspaceSnapshot.createBoundaryProjection(f.workspace(), target, b));
        try (var files = Files.list(target)) { assertTrue(files.findAny().isEmpty()); }
    }
    @Test void tooManyOutsideEntriesAreRetainedAsAnObservedChange() throws Exception {
        var f = F1BoundarySessionTest.fixture(temp); var b = F1BoundarySession.prepare(f.workspace(), f.home(), f.definition());
        b.begin(F1BoundarySessionTest.request(f), f.workspace(), f.home());
        Path outside = b.mountSource();
        for (int i = 0; i < 100; i++) Files.writeString(outside.resolve("synthetic-" + i), "counterfactual violation");
        b.finish(); var e = b.evidence();
        assertNotEquals(e.before(), e.after()); assertEquals(64, e.after().entries().size());
        assertEquals("OVERFLOW", e.after().entries().get("[RUNNER_EVIDENCE_OVERFLOW]").type());
        b.verifyTerminalUnchanged();
    }
    @Test void frozenSourceRejectsUnknownFieldsAndCoercedIdentity() throws Exception {
        var f = F1BoundarySessionTest.fixture(temp); var json = new ObjectMapper();
        var oracle = new F1FrozenOracle(1, "F1", "FILE_ONLY", F1FrozenOracle.PROFILE, "a".repeat(24), f.definition());
        byte[] bytes = json.writeValueAsBytes(oracle); assertEquals(oracle, F1FrozenOracle.parse(bytes));
        for (String bad : new String[]{new String(bytes).replace("\"schemaVersion\":1", "\"schemaVersion\":true"),
                new String(bytes).replaceFirst("\\{", "{\"hostSaysPassed\":true,"),
                new String(bytes).replaceFirst("\\{", "{\"schemaVersion\":1,"), new String(bytes) + " {}"})
            assertThrows(IOException.class, () -> F1FrozenOracle.parse(bad.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
