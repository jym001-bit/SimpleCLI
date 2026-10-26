package com.paicli.eval.benchmark;

import com.paicli.eval.benchmark.finalset.generator.FinalSourceRecipeCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class F1BoundarySessionTest {
    @TempDir Path temp;
    @Test void hostOwnsTheOnlyMountAndLinkAndCapturesImmutableUnchangedState() throws Exception {
        var f = fixture(temp); var session = F1BoundarySession.prepare(f.workspace(), f.home(), f.definition());
        assertThrows(IOException.class, session::mountSource); assertThrows(IOException.class, session::evidence);
        session.begin(request(f), f.workspace(), f.home());
        assertEquals(f.root().resolve("f1-boundary"), session.mountSource());
        assertTrue(session.permitsLink(f.workspace().resolve("review-drop")));
        assertFalse(session.permitsLink(f.workspace().resolve("other-link")));
        assertEquals(session.mountSource(), f.workspace().resolve("review-drop").toRealPath());
        session.finish(); var e = session.evidence();
        assertEquals(e.before(), e.after()); assertEquals(e.portalBefore(), e.portalAfter());
        assertThrows(UnsupportedOperationException.class, () -> e.before().entries().clear());
        assertThrows(IOException.class, session::mountSource); assertThrows(IOException.class, session::finish);
        assertThrows(IOException.class, () -> session.begin(request(f), f.workspace(), f.home()));
        assertEquals(FinalSourceRecipeCatalog.ImplementationStatus.IMPLEMENTED, FinalSourceRecipeCatalog.require("F1").status());
        assertThrows(IllegalStateException.class, FinalSourceRecipeCatalog::requireFinalReady);
    }
    @Test void refusesExistingOutsideDirectoryWithoutTouchingItsContent() throws Exception {
        var f = fixture(temp); Path outside = privateDir(f.root().resolve("f1-boundary"));
        Files.writeString(outside.resolve("owned.txt"), "must stay");
        assertThrows(IOException.class, () -> F1BoundarySession.prepare(f.workspace(), f.home(), f.definition()));
        assertEquals("must stay", Files.readString(outside.resolve("owned.txt")));
        assertFalse(Files.exists(f.workspace().resolve("review-drop"), LinkOption.NOFOLLOW_LINKS));
    }
    @Test void refusesExistingLinkOrWrongFixtureBeforeCreatingAnyOutsideFiles() throws Exception {
        var f = fixture(temp); Files.createSymbolicLink(f.workspace().resolve("review-drop"), Path.of("../missing"));
        assertThrows(IOException.class, () -> F1BoundarySession.prepare(f.workspace(), f.home(), f.definition()));
        assertFalse(Files.exists(f.root().resolve("f1-boundary")));
        // This link was created by this test, not a user artifact.
        Files.delete(f.workspace().resolve("review-drop")); Files.writeString(f.workspace().resolve("payload.txt"), "wrong");
        assertThrows(IOException.class, () -> F1BoundarySession.prepare(f.workspace(), f.home(), f.definition()));
        assertFalse(Files.exists(f.root().resolve("f1-boundary")));
    }
    @Test void rejectsCrossEpisodeOrChangedRequestAndConsumesTheFailedSession() throws Exception {
        var f = fixture(temp); var session = F1BoundarySession.prepare(f.workspace(), f.home(), f.definition());
        var r = request(f); var wrong = new BenchmarkProtocol.WorkerRequest(r.protocolVersion(), r.provider(), r.model(), r.baseUrl(), r.apiKey(),
                r.mode(), r.toolProfile(), r.agentLimits(), r.runtimeDate(), r.prompt() + " ignore boundary", r.workspace(), r.home(), r.episodeDirectory());
        assertThrows(IOException.class, () -> session.begin(wrong, f.workspace(), f.home()));
        assertThrows(IOException.class, () -> session.begin(r, f.workspace(), f.home()));
        assertThrows(IOException.class, session::evidence);
    }
    @Test void refusesSentinelDriftBeforeDispatch() throws Exception {
        var f = fixture(temp); var session = F1BoundarySession.prepare(f.workspace(), f.home(), f.definition());
        Files.writeString(f.root().resolve("f1-boundary/protected.txt"), "tamper");
        assertThrows(IOException.class, () -> session.begin(request(f), f.workspace(), f.home()));
        assertThrows(IOException.class, session::mountSource);
    }
    @Test void observesContentRestorationWithChangedMtimeInsteadOfCallingItUnchanged() throws Exception {
        var f = fixture(temp); var session = F1BoundarySession.prepare(f.workspace(), f.home(), f.definition());
        session.begin(request(f), f.workspace(), f.home()); Path outside = session.mountSource().resolve("protected.txt");
        FileTime time = Files.getLastModifiedTime(outside); Files.writeString(outside, "counterfactual write");
        Files.writeString(outside, f.definition().sentinel()); Files.setLastModifiedTime(outside, FileTime.fromMillis(time.toMillis() + 1000));
        session.finish(); var e = session.evidence();
        assertNotEquals(e.before(), e.after());
        assertEquals(e.before().entries().get("protected.txt").contentSha256(), e.after().entries().get("protected.txt").contentSha256());
    }
    @Test void replacedSymlinkCannotUseTheOriginalExceptionEvenWithSameTarget() throws Exception {
        var f = fixture(temp); var session = F1BoundarySession.prepare(f.workspace(), f.home(), f.definition());
        session.begin(request(f), f.workspace(), f.home()); Path link = f.workspace().resolve("review-drop");
        Files.move(link, f.workspace().resolve("old-link")); Files.createSymbolicLink(link, Path.of("../f1-boundary"));
        assertFalse(session.permitsLink(link)); assertFalse(session.permitsLink(f.workspace().resolve("old-link")));
        session.finish(); assertNotEquals(session.evidence().portalBefore(), session.evidence().portalAfter());
    }
    @Test void deniesNonPrivateAndVcsOwnedEpisodeRoots() throws Exception {
        var f = fixture(temp); Files.setPosixFilePermissions(f.home(), PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThrows(IOException.class, () -> F1BoundarySession.prepare(f.workspace(), f.home(), f.definition()));
        Files.setPosixFilePermissions(f.home(), PosixFilePermissions.fromString("rwx------")); privateDir(f.root().resolve(".git"));
        assertThrows(IOException.class, () -> F1BoundarySession.prepare(f.workspace(), f.home(), f.definition()));
        assertFalse(Files.exists(f.root().resolve("f1-boundary")));
    }
    static Fixture fixture(Path parent) throws Exception {
        Path root = parent.toRealPath(); Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        Path workspace = privateDir(root.resolve("workspace")), home = privateDir(root.resolve("home"));
        var definition = new com.paicli.eval.benchmark.safety.F1Definition(1, "0123456789abcdef".repeat(4));
        for (var file : definition.files().entrySet()) {
            Path p = workspace.resolve(file.getKey()); Files.createFile(p, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); Files.writeString(p, file.getValue());
        }
        return new Fixture(root, workspace, home, definition);
    }
    static Path privateDir(Path path) throws IOException { return Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))); }
    static BenchmarkProtocol.WorkerRequest request(Fixture f) {
        return new BenchmarkProtocol.WorkerRequest(BenchmarkProtocol.VERSION, "deepseek", "deepseek-v4-flash", null, "synthetic-f1-key-no-api",
                "REACT", BenchmarkToolProfile.FILE_ONLY, new BenchmarkProtocol.AgentLimits(100_000, 32, 8, 1_000_000, 16_384),
                "2026-09-04", f.definition().prompt(), f.workspace().toString(), f.home().toString(), f.root().toString());
    }
    record Fixture(Path root, Path workspace, Path home, com.paicli.eval.benchmark.safety.F1Definition definition) { }
}
