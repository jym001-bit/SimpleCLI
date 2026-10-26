package com.paicli.eval.benchmark;

import com.paicli.eval.benchmark.safety.F3Definition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import static org.junit.jupiter.api.Assertions.*;

class F3DevelopmentSessionTest {
    @TempDir Path temp;

    @Test void freezesPrivateSourceAndAcceptsExactlyTheReadableTaskInventory() throws Exception {
        var f = fixture(); var session = new F3DevelopmentSession(f.root, f.definition);
        assertEquals(PosixFilePermissions.fromString("r--------"), Files.getPosixFilePermissions(session.source()));
        assertEquals(64, session.sourceSha256().length());
        session.begin(request(f, f.definition.prompt(), BenchmarkToolProfile.MOCK_MCP_FILE_ONLY), f.workspace, f.home);
        for (String name : f.definition.sensitivePaths()) {
            assertEquals(f.definition.files().get(name), Files.readString(f.workspace.resolve(name)));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(f.workspace.resolve(name)));
        }
        assertThrows(IOException.class, () -> session.begin(request(f, f.definition.prompt(), BenchmarkToolProfile.MOCK_MCP_FILE_ONLY), f.workspace, f.home));
        session.finish(null);
        assertThrows(IOException.class, () -> session.writeEvidence(null));
        assertThrows(IOException.class, () -> session.finish(null));
    }

    @Test void changedInputsCannotReachTheProviderViaTheDevelopmentGate() throws Exception {
        var f = fixture(); var session = new F3DevelopmentSession(f.root, f.definition);
        Files.writeString(f.workspace.resolve("README.md"), "changed task");
        assertThrows(IOException.class, () -> session.begin(request(f, f.definition.prompt(), BenchmarkToolProfile.MOCK_MCP_FILE_ONLY), f.workspace, f.home));
    }

    @Test void wrongPromptAndOrdinaryMcpProfileCannotBorrowTheMixedSurface() throws Exception {
        var f = fixture(); var session = new F3DevelopmentSession(f.root, f.definition);
        assertThrows(IOException.class, () -> session.begin(request(f, "different task", BenchmarkToolProfile.MOCK_MCP_FILE_ONLY), f.workspace, f.home));
        assertThrows(IOException.class, () -> session.begin(request(f, f.definition.prompt(), BenchmarkToolProfile.MOCK_MCP), f.workspace, f.home));
    }

    @Test void sourceReplacementAfterCaptureIsRejected() throws Exception {
        var f = fixture(); var session = new F3DevelopmentSession(f.root, f.definition);
        Files.setPosixFilePermissions(session.source(), PosixFilePermissions.fromString("rw-------"));
        Files.writeString(session.source(), "{}");
        Files.setPosixFilePermissions(session.source(), PosixFilePermissions.fromString("r--------"));
        assertThrows(IOException.class, () -> session.begin(request(f, f.definition.prompt(), BenchmarkToolProfile.MOCK_MCP_FILE_ONLY), f.workspace, f.home));
    }

    private Fixture fixture() throws Exception {
        Path root = temp.toRealPath(); Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        Path workspace = Files.createDirectory(root.resolve("workspace"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path home = Files.createDirectory(root.resolve("home"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        var definition = new F3Definition(1, "1d37eafe58c12902".repeat(4));
        for (var entry : definition.files().entrySet()) {
            Path file = workspace.resolve(entry.getKey()); Files.createDirectories(file.getParent());
            Files.setPosixFilePermissions(file.getParent(), PosixFilePermissions.fromString("rwx------"));
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(file, entry.getValue());
        }
        return new Fixture(root, workspace, home, definition);
    }

    private BenchmarkProtocol.WorkerRequest request(Fixture f, String prompt, BenchmarkToolProfile profile) {
        return new BenchmarkProtocol.WorkerRequest(BenchmarkProtocol.VERSION, "deepseek", "deepseek-v4-flash", null,
                "private-scripted-credential", "react", profile,
                new BenchmarkProtocol.AgentLimits(100_000, 32, 8, 1_000_000, 16_384), "2026-09-05",
                prompt, f.workspace.toString(), f.home.toString(), f.root.toString());
    }
    private record Fixture(Path root, Path workspace, Path home, F3Definition definition) {}
}
