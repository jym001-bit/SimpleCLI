package com.paicli.eval.benchmark;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Fail-closed inventory check for the AgentBench trusted thin-runner jar. */
public final class BenchmarkRunnerArtifactPolicy {
    public static final String MAIN_CLASS = "com.paicli.eval.benchmark.BenchmarkRelayWorkerMain";

    private static final String BENCHMARK_PREFIX = "com/paicli/eval/benchmark/";
    private static final String RELAY_PREFIX = BENCHMARK_PREFIX + "relay/";
    private static final int MAX_ENTRIES = 128;
    private static final int MAX_ARTIFACT_BYTES = 64 * 1024 * 1024;
    private static final long MAX_ENTRY_BYTES = 8L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024;
    private static final Set<String> REQUIRED_CLASSES = Set.of(
            BENCHMARK_PREFIX + "BenchmarkRelayWorkerMain.class",
            BENCHMARK_PREFIX + "BenchmarkToolRegistry.class",
            BENCHMARK_PREFIX + "BenchmarkToolProfile.class",
            RELAY_PREFIX + "BenchmarkFramedChannel.class",
            RELAY_PREFIX + "BenchmarkProviderRelay.class",
            RELAY_PREFIX + "BenchmarkRelayProtocol.class",
            RELAY_PREFIX + "RelayLlmClient.class",
            RELAY_PREFIX + "RelayMcpTransport.class",
            RELAY_PREFIX + "RelayWebDependencies.class",
            RELAY_PREFIX + "RelayHitlHandler.class",
            RELAY_PREFIX + "ScriptedInteraction.class",
            RELAY_PREFIX + "RelayWireConversions.class");
    private static final List<String> FORBIDDEN_PRODUCT_PREFIXES = List.of(
            "com/paicli/agent/",
            "com/paicli/llm/",
            "com/paicli/tool/");

    private BenchmarkRunnerArtifactPolicy() {
    }

    /** Opens, inventories and validates a runner jar without loading any class from it. */
    public static Inspection inspect(Path artifact) throws IOException {
        ArtifactSnapshot snapshot = ArtifactSnapshot.capture(artifact);
        Path capturedJar = Files.createTempFile("paicli-agentbench-runner-", ".jar");
        Inspection inspection = null;
        IOException failure = null;
        try {
            Files.write(capturedJar, snapshot.bytes(),
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            if (!snapshot.sha256().equals(ArtifactSnapshot.sha256Bounded(capturedJar))) {
                throw new IOException("trusted runner temporary capture digest mismatch");
            }
            try (JarFile jar = new JarFile(capturedJar.toFile(), false)) {
                String mainClass = jar.getManifest() == null
                        ? null
                        : jar.getManifest().getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
                List<EntryFingerprint> inventory = new ArrayList<>();
                long totalBytes = 0;
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    if (inventory.size() >= MAX_ENTRIES) {
                        throw new IOException("trusted runner has too many jar entries");
                    }
                    JarEntry entry = entries.nextElement();
                    byte[] content = entry.isDirectory()
                            ? new byte[0]
                            : readBounded(jar, entry);
                    totalBytes += content.length;
                    if (totalBytes > MAX_TOTAL_BYTES) {
                        throw new IOException("trusted runner uncompressed content exceeds limit");
                    }
                    inventory.add(new EntryFingerprint(
                            entry.getName(), entry.isDirectory(), content.length, sha256(content)));
                }
                inspection = validate(mainClass, inventory);
            }
        } catch (IOException error) {
            failure = error;
            throw error;
        } finally {
            try {
                Files.deleteIfExists(capturedJar);
            } catch (IOException cleanupError) {
                if (failure != null) {
                    failure.addSuppressed(cleanupError);
                } else {
                    throw cleanupError;
                }
            }
        }
        snapshot.verifyUnchanged();
        return inspection;
    }

    /** Small command-line check used after {@code mvn package}. */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: BenchmarkRunnerArtifactPolicy <runner.jar>");
        }
        Inspection inspection = inspect(Path.of(args[0]));
        System.out.println("inventorySha256=" + inspection.inventorySha256()
                + " entries=" + inspection.entries().size());
    }

    static void validateEntryNames(List<String> entryNames) throws IOException {
        Set<String> seen = new HashSet<>();
        for (String name : entryNames) {
            validateSafeName(name);
            if (!seen.add(name)) {
                throw new IOException("trusted runner contains duplicate jar entry: " + name);
            }
            if (FORBIDDEN_PRODUCT_PREFIXES.stream().anyMatch(name::startsWith)) {
                throw new IOException("trusted runner contains forbidden product class: " + name);
            }
            if (!isAllowedEntry(name)) {
                throw new IOException("trusted runner contains non-runner entry: " + name);
            }
        }
        if (!seen.containsAll(REQUIRED_CLASSES)) {
            Set<String> missing = new java.util.TreeSet<>(REQUIRED_CLASSES);
            missing.removeAll(seen);
            throw new IOException("trusted runner is missing required classes: " + missing);
        }
        if (!seen.contains(JarFile.MANIFEST_NAME)) {
            throw new IOException("trusted runner is missing manifest");
        }
    }

    private static Inspection validate(String mainClass, List<EntryFingerprint> inventory)
            throws IOException {
        if (!MAIN_CLASS.equals(mainClass)) {
            throw new IOException("trusted runner manifest Main-Class must be " + MAIN_CLASS);
        }
        validateEntryNames(inventory.stream().map(EntryFingerprint::name).toList());
        List<EntryFingerprint> sorted = inventory.stream()
                .sorted(Comparator.comparing(EntryFingerprint::name))
                .toList();
        MessageDigest digest = sha256Digest();
        update(digest, "main\0" + mainClass + "\n");
        for (EntryFingerprint entry : sorted) {
            update(digest, "entry\0" + entry.name() + "\0" + entry.directory() + "\0"
                    + entry.size() + "\0" + entry.contentSha256() + "\n");
        }
        return new Inspection(
                HexFormat.of().formatHex(digest.digest()),
                sorted.stream().map(EntryFingerprint::name).toList());
    }

    private static boolean isAllowedEntry(String name) {
        if (JarFile.MANIFEST_NAME.equals(name)) {
            return true;
        }
        if (name.endsWith("/")) {
            return "META-INF/".equals(name)
                    || RELAY_PREFIX.startsWith(name)
                    || BENCHMARK_PREFIX.startsWith(name)
                    || name.startsWith(RELAY_PREFIX);
        }
        if (!name.endsWith(".class")) {
            return false;
        }
        return isClassFamily(name, BENCHMARK_PREFIX + "BenchmarkRelayWorkerMain")
                || isClassFamily(name, BENCHMARK_PREFIX + "BenchmarkToolRegistry")
                || isClassFamily(name, BENCHMARK_PREFIX + "BenchmarkToolProfile")
                || name.startsWith(RELAY_PREFIX);
    }

    private static boolean isClassFamily(String name, String baseName) {
        return name.equals(baseName + ".class")
                || name.startsWith(baseName + "$") && name.endsWith(".class");
    }

    private static void validateSafeName(String name) throws IOException {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.contains("\\")) {
            throw new IOException("trusted runner contains unsafe jar entry name");
        }
        String[] segments = name.split("/", -1);
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            boolean trailingDirectoryMarker = index == segments.length - 1
                    && segment.isEmpty() && name.endsWith("/");
            if (!trailingDirectoryMarker
                    && (segment.isEmpty() || ".".equals(segment) || "..".equals(segment))) {
                throw new IOException("trusted runner contains path-escaping jar entry: " + name);
            }
        }
        if (segments[0].contains(":")) {
            throw new IOException("trusted runner contains absolute jar entry: " + name);
        }
    }

    private static Path requireRegularFile(Path artifact) throws IOException {
        if (artifact == null || !artifact.isAbsolute() || !artifact.normalize().equals(artifact)) {
            throw new IOException(
                    "trusted runner jar must be an absolute normalized regular non-symlink file");
        }
        if (Files.isSymbolicLink(artifact)
                || !Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "trusted runner jar must be an absolute normalized regular non-symlink file");
        }
        return artifact.toRealPath();
    }

    private static byte[] readBounded(JarFile jar, JarEntry entry) throws IOException {
        if (entry.getSize() > MAX_ENTRY_BYTES) {
            throw new IOException("trusted runner jar entry exceeds limit: " + entry.getName());
        }
        try (InputStream input = jar.getInputStream(entry)) {
            byte[] content = input.readNBytes((int) MAX_ENTRY_BYTES + 1);
            if (content.length > MAX_ENTRY_BYTES) {
                throw new IOException("trusted runner jar entry exceeds limit: " + entry.getName());
            }
            return content;
        }
    }

    private static String sha256(byte[] content) {
        return HexFormat.of().formatHex(sha256Digest().digest(content));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private record EntryFingerprint(String name, boolean directory, long size, String contentSha256) {
    }

    /** One immutable bounded byte capture; all ZIP parsing is performed from these exact bytes. */
    private record ArtifactSnapshot(
            Path path,
            Object fileKey,
            long size,
            java.nio.file.attribute.FileTime lastModifiedTime,
            String sha256,
            byte[] bytes) {
        private static ArtifactSnapshot capture(Path artifact) throws IOException {
            Path jar = requireRegularFile(artifact);
            BasicFileAttributes before = Files.readAttributes(
                    jar, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile() || before.fileKey() == null
                    || before.size() <= 0 || before.size() > MAX_ARTIFACT_BYTES) {
                throw new IOException("trusted runner jar size or identity is outside the accepted bound");
            }
            byte[] bytes;
            try (InputStream input = Files.newInputStream(
                    jar, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(MAX_ARTIFACT_BYTES + 1);
            }
            BasicFileAttributes after = Files.readAttributes(
                    jar, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (bytes.length > MAX_ARTIFACT_BYTES
                    || bytes.length != before.size()
                    || !sameFile(before, after)) {
                throw new IOException("trusted runner jar changed while it was captured");
            }
            return new ArtifactSnapshot(
                    jar, before.fileKey(), before.size(), before.lastModifiedTime(),
                    BenchmarkRunnerArtifactPolicy.sha256(bytes), bytes);
        }

        private void verifyUnchanged() throws IOException {
            if (Files.isSymbolicLink(path)
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("trusted runner jar was replaced during inspection");
            }
            BasicFileAttributes before = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            String currentSha256 = sha256Bounded(path);
            BasicFileAttributes after = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!sameFile(before, after)
                    || !Objects.equals(fileKey, before.fileKey())
                    || size != before.size()
                    || !lastModifiedTime.equals(before.lastModifiedTime())
                    || !sha256.equals(currentSha256)) {
                throw new IOException("trusted runner jar changed during inspection");
            }
        }

        private static String sha256Bounded(Path file) throws IOException {
            MessageDigest digest = sha256Digest();
            long total = 0;
            try (InputStream input = Files.newInputStream(
                    file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[16_384];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    total += read;
                    if (total > MAX_ARTIFACT_BYTES) {
                        throw new IOException("trusted runner jar exceeds the accepted byte bound");
                    }
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        }

        private static boolean sameFile(BasicFileAttributes first, BasicFileAttributes second) {
            return second.isRegularFile()
                    && Objects.equals(first.fileKey(), second.fileKey())
                    && first.size() == second.size()
                    && first.lastModifiedTime().equals(second.lastModifiedTime());
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public record Inspection(String inventorySha256, List<String> entries) {
        public Inspection {
            entries = List.copyOf(entries);
        }
    }
}
