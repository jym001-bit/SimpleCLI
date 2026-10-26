package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.eval.benchmark.mock.F3SupportBundleMock;
import com.paicli.eval.benchmark.relay.F3ToolResultAudit;
import com.paicli.eval.benchmark.safety.F3Definition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;

/**
 * F3 source and evidence primitive, used by development controls and an independently frozen
 * formal binding. This type alone grants no formal admission, score, or arbitrary host mount.
 */
final class F3DevelopmentSession {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    private final Path episode, source;
    private final F3Definition definition;
    private final String sourceSha256, sourceFileKey;
    private final F3SupportBundleMock mock;
    private final F3ToolResultAudit audit;
    private boolean begun, finished;
    private Path workspace, home;
    private String actualCredential;
    private BenchmarkCoordinatorMain.WorkerExecution returned;

    F3DevelopmentSession(Path episode, F3Definition definition) throws IOException {
        this.episode = privateDirectory(episode);
        this.definition = Objects.requireNonNull(definition);
        source = this.episode.resolve("f3-source.json");
        byte[] bytes = JSON.writeValueAsBytes(definition);
        Files.createFile(source, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(source, bytes, StandardOpenOption.WRITE);
        Files.setPosixFilePermissions(source, PosixFilePermissions.fromString("r--------"));
        sourceSha256 = hash(bytes);
        sourceFileKey = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey().toString();
        mock = new F3SupportBundleMock(definition);
        audit = new F3ToolResultAudit(definition.prompt());
    }

    synchronized void begin(BenchmarkProtocol.WorkerRequest request, Path candidateWorkspace, Path isolatedHome) throws IOException {
        if (begun || finished) throw new IOException("F3 session is single use");
        verifySource();
        workspace = privateDirectory(candidateWorkspace); home = privateDirectory(isolatedHome);
        if (!episode.equals(workspace.getParent()) || !episode.equals(home.getParent()) || workspace.equals(home)
                || !request.workspace().equals(workspace.toString()) || !request.home().equals(home.toString())
                || !request.episodeDirectory().equals(episode.toString())
                || !"REACT".equalsIgnoreCase(request.mode()) || request.toolProfile() != BenchmarkToolProfile.MOCK_MCP_FILE_ONLY
                || !request.prompt().equals(definition.prompt()) || !mock.definition().equals(definition)
                || !request.agentLimits().equals(new BenchmarkProtocol.AgentLimits(100_000, 32, 8, 1_000_000, 16_384)))
            throw new IOException("F3 development source, paths, profile or limits differ");
        if (!readTree(workspace).equals(definition.files()) || !readTree(home).isEmpty())
            throw new IOException("F3 runtime input differs from its private source");
        actualCredential = request.apiKey();
        // The synthetic canaries belong to the task, never to the actual credential guard.
        if (BenchmarkSecretCanary.contains(actualCredential, JSON.writeValueAsString(definition.files())))
            throw new IOException("actual credential appears in F3 task source");
        begun = true;
    }

    synchronized void finish(BenchmarkCoordinatorMain.WorkerExecution execution) throws IOException {
        if (!begun || finished) throw new IOException("F3 invalid completion lifecycle");
        finished = true; returned = execution; verifySource();
    }

    /** Only the exact returned object may supply the diagnostic terminal and metrics. */
    synchronized Path writeEvidence(BenchmarkCoordinatorMain.WorkerExecution execution) throws IOException {
        ObjectNode evidence = snapshotEvidence(execution);
        byte[] bytes = JSON.writeValueAsBytes(evidence);
        Path directory = BenchmarkProcessEnvironment.preparePrivateDirectory(episode.resolve("f3-evidence"));
        Path target = directory.resolve("envelope.json");
        Files.createFile(target, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(target, bytes, StandardOpenOption.WRITE);
        verifySource();
        return target;
    }

    /** Read-only host snapshot; no file creation and no Candidate-supplied authority fields. */
    synchronized ObjectNode snapshotEvidence(BenchmarkCoordinatorMain.WorkerExecution execution) throws IOException {
        if (!finished || execution == null || returned != execution || execution.response() == null || !execution.response().success())
            throw new IOException("F3 terminal must be classified before successful diagnostic replay");
        verifySource(); audit.requireHealthy();
        var snapshot = audit.snapshot();
        if (snapshot.terminal() == null || !snapshot.terminal().answer().equals(execution.response().answer())
                || !JSON.readTree(JSON.writeValueAsBytes(snapshot.terminal().toolExecutions()))
                .equals(JSON.readTree(JSON.writeValueAsBytes(execution.toolExecutions()))))
            throw new IOException("F3 returned execution differs from host terminal");
        var evidence = JSON.createObjectNode().put("schemaVersion", 1).put("kind", "F3_DEVELOPMENT_CONTROL")
                .put("sourceSha256", sourceSha256).put("answer", execution.response().answer());
        evidence.set("definition", JSON.valueToTree(definition));
        evidence.set("audit", JSON.valueToTree(snapshot));
        evidence.set("mockAudit", JSON.valueToTree(mock.audit()));
        evidence.set("toolExecutions", JSON.valueToTree(execution.toolExecutions()));
        evidence.set("workspaceBefore", JSON.valueToTree(definition.files()));
        evidence.set("workspaceAfter", JSON.valueToTree(readTree(workspace)));
        evidence.set("homeAfter", JSON.valueToTree(readTree(home)));
        byte[] bytes = JSON.writeValueAsBytes(evidence);
        if (BenchmarkSecretCanary.contains(actualCredential, new String(bytes, StandardCharsets.UTF_8)))
            throw new IOException("actual credential in F3 raw evidence");
        verifySource();
        return evidence;
    }

    F3SupportBundleMock mock() { return mock; }
    F3ToolResultAudit audit() { return audit; }
    Path source() { return source; }
    String sourceSha256() { return sourceSha256; }
    synchronized void verifyUnchanged() throws IOException { verifySource(); }

    private void verifySource() throws IOException {
        var attributes = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.fileKey() == null || !sourceFileKey.equals(attributes.fileKey().toString())
                || !source.equals(source.toRealPath()) || ((Number)Files.getAttribute(source, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1
                || !Files.getPosixFilePermissions(source).equals(PosixFilePermissions.fromString("r--------"))
                || attributes.size() > 1024 || !sourceSha256.equals(hash(Files.readAllBytes(source))))
            throw new IOException("F3 private source changed");
    }

    private static Path privateDirectory(Path path) throws IOException {
        if (path == null || !path.isAbsolute() || Files.isSymbolicLink(path)
                || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || !path.equals(path.toRealPath())
                || !Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString("rwx------")))
            throw new IOException("F3 needs a canonical private directory");
        return path;
    }

    /** Bounded UTF-8 file inventory of the host-persisted workspace/home, not OS-wide surveillance. */
    static Map<String, String> readTree(Path root) throws IOException {
        Map<String, String> files = new TreeMap<>(); long total = 0;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("F3 evidence inventory contains a symbolic link");
                var before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (before.isDirectory()) continue;
                if (!before.isRegularFile() || before.fileKey() == null || before.size() > MAX_FILE_BYTES
                        || files.size() >= 256 || (total += before.size()) > 16L * MAX_FILE_BYTES
                        || ((Number)Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1)
                    throw new IOException("F3 inventory is unsafe or exceeds the declared bound");
                byte[] bytes;
                try (var in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { bytes = in.readNBytes(MAX_FILE_BYTES + 1); }
                var after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                String value = new String(bytes, StandardCharsets.UTF_8);
                if (bytes.length != before.size() || !Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8))
                        || !before.fileKey().equals(after.fileKey()) || before.size() != after.size()
                        || !before.lastModifiedTime().equals(after.lastModifiedTime()))
                    throw new IOException("F3 inventory changed or contains non UTF-8 data");
                files.put(root.relativize(path).toString().replace('\\', '/'), value);
            }
        }
        return Collections.unmodifiableMap(files);
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
