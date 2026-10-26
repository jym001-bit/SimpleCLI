package com.paicli.eval.benchmark;

import com.paicli.eval.benchmark.formal.*;
import com.paicli.eval.benchmark.mock.*;
import com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;

/** Closed D4 host binding. No arbitrary endpoint, Candidate-supplied service or oracle mount. */
final class FormalMockWebBinding {
    private final FormalExecutionPlan.VerifierDependency dependency;
    private final D4FrozenOracle oracle;
    private final String fileKey, promptSha256;
    private FormalMockWebBinding(FormalExecutionPlan.VerifierDependency dependency, Capture capture, String prompt) {
        this.dependency = dependency; oracle = capture.oracle(); fileKey = capture.fileKey();
        promptSha256 = BenchmarkRelayProtocol.textSha256(prompt);
    }
    static boolean supports(FormalExecutionPlan.CasePlan plan) {
        return "D4".equals(plan.id()) && D4FrozenOracle.PROFILE.equals(plan.mockProfile())
                && plan.mode() == FinalExecutableSuiteContract.Mode.REACT
                && plan.toolProfile() == FinalExecutableSuiteContract.ToolProfile.MOCK_WEB
                && plan.evidenceRequirements().containsAll(List.of("web_audit", "provider_turns"));
    }
    static FormalMockWebBinding capture(FormalExecutionPlan.CasePlan plan) throws IOException {
        if (!supports(plan)) throw new IOException("unsupported formal Web binding");
        var dependency = plan.verifier().dependencies().stream().filter(d -> d.frozenPath().equals(D4FrozenOracle.PATH)).findFirst()
                .orElseThrow(() -> new IOException("D4 source not registered"));
        var capture = read(dependency); var oracle = capture.oracle();
        if (oracle.schemaVersion() != 2) throw new IOException("formal D4 requires the declared README baseline");
        String prompt = "# D4 " + com.paicli.eval.benchmark.finalset.generator.FinalSourceRecipeCatalog.require("D4").title()
                + "\n\n" + oracle.newService().prompt() + "\n\nVariant: " + oracle.variantId() + "\n";
        if (!prompt.equals(plan.prompt())) throw new IOException("D4 prompt/source mismatch");
        var fixture = plan.fixture();
        if (fixture.kind() != FormalBenchmarkPreflight.FixtureKind.DIRECTORY || fixture.fileCount() != 1
                || !fixture.files().get(0).frozenPath().equals(fixture.frozenPath() + "/README.md")
                || !fixture.files().get(0).sha256().equals(oracle.baselineFiles().get("README.md")))
            throw new IOException("D4 fixture/source mismatch");
        return new FormalMockWebBinding(dependency, capture, prompt);
    }
    void verifyUnchanged() throws IOException {
        var now = read(dependency);
        if (!fileKey.equals(now.fileKey()) || !oracle.equals(now.oracle())) throw new IOException("frozen D4 source changed");
    }
    D4WebMock newService() throws IOException { verifyUnchanged(); return oracle.newService(); }
    MockEvidence evidence(D4WebMock mock) {
        return new MockEvidence(1, "D4", D4FrozenOracle.PROFILE, BenchmarkRelayProtocol.VERSION, dependency.sha256(), promptSha256,
                mock.audit(), mock.relayAudit(), mock.providerAudit());
    }
    /** Immutable typed host evidence; partial traces are retained even when a Worker fails. */
    record MockEvidence(int schemaVersion, String caseId, String profile, int relayVersion,
            String mockSourceSha256, String promptSha256, List<D4WebMock.AuditEvent> events,
            List<D4WebMock.RelayExchange> relayEvents, List<D4WebMock.ProviderTurn> providerTurns) {
        MockEvidence {
            if (schemaVersion != 1 || !"D4".equals(caseId) || !D4FrozenOracle.PROFILE.equals(profile)
                    || (relayVersion != 8 && relayVersion != 9 && relayVersion != 10 && relayVersion != 11 && relayVersion != 12)
                    || mockSourceSha256 == null || !mockSourceSha256.matches("[a-f0-9]{64}")
                    || promptSha256 == null || !promptSha256.matches("[a-f0-9]{64}")
                    || events == null || relayEvents == null || providerTurns == null
                    || events.size() > 4096 || relayEvents.size() > 4096 || providerTurns.size() > 4096)
                throw new IllegalArgumentException("invalid host Web evidence");
            events = List.copyOf(events); relayEvents = List.copyOf(relayEvents); providerTurns = List.copyOf(providerTurns);
        }
    }
    private static Capture read(FormalExecutionPlan.VerifierDependency dependency) throws IOException {
        Path path = dependency.sourcePath();
        if (!"0400".equals(dependency.mode()) || dependency.size() > D4FrozenOracle.MAX_BYTES
                || Files.isSymbolicLink(path) || !path.equals(path.toRealPath())) throw new IOException("unsafe D4 source");
        Path root = path;
        for (int i = 0; i < Path.of(dependency.frozenPath()).getNameCount(); i++) root = root.getParent();
        if (root == null || !root.resolve(dependency.frozenPath()).equals(path)) throw new IOException("D4 path binding mismatch");
        for (Path dir = path.getParent(); dir != null; dir = dir.getParent()) {
            if (Files.isSymbolicLink(dir) || !dir.equals(dir.toRealPath())
                    || !List.of("rwx------", "r-x------").contains(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir))))
                throw new IOException("unsafe D4 source parent");
            if (dir.equals(root)) break;
        }
        var before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.fileKey() == null || before.size() != dependency.size() || !privateSingleFile(path))
            throw new IOException("D4 source identity mismatch");
        byte[] bytes;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(D4FrozenOracle.MAX_BYTES + 1); }
        var after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.fileKey().equals(after.fileKey()) || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || after.size() != dependency.size() || bytes.length != dependency.size() || !privateSingleFile(path)
                || !hash(bytes).equals(dependency.sha256())) throw new IOException("D4 source drift");
        return new Capture(D4FrozenOracle.parse(bytes), before.fileKey().toString());
    }
    private static boolean privateSingleFile(Path path) throws IOException {
        return ((Number)Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() == 1
                && Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString("r--------"));
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private record Capture(D4FrozenOracle oracle, String fileKey) { }
}
