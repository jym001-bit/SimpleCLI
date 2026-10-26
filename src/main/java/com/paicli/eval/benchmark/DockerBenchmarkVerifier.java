package com.paicli.eval.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Runs one deterministic verifier in a frozen, networkless Docker image. */
final class DockerBenchmarkVerifier implements BenchmarkCoordinatorMain.VerifierExecutor {
    private static final Pattern FROZEN_IMAGE_ID = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern CONTAINER_ID = Pattern.compile("[0-9a-f]{12,64}");
    private static final int OUTPUT_LIMIT = 256 * 1024;
    private static final int CLEANUP_OUTPUT_LIMIT = 16 * 1024;
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(10);
    private static final long MAX_CIDFILE_BYTES = 66L;
    private static final String CONTAINER_WORKSPACE = "/workspace";
    private static final String CONTAINER_SUITE = "/suite";
    private static final String CONTAINER_EVIDENCE = "/evidence/envelope.json";

    private final Path dockerExecutable;
    private final String imageId;
    private final ProcessExecutor processExecutor;
    private final HostIdentityResolver identityResolver;

    DockerBenchmarkVerifier(Path dockerExecutable, String imageId) {
        this(dockerExecutable, imageId, BenchmarkSubprocess::run, DockerBenchmarkVerifier::unixIdentity);
    }

    DockerBenchmarkVerifier(Path dockerExecutable,
                            String imageId,
                            ProcessExecutor processExecutor,
                            HostIdentityResolver identityResolver) {
        if (dockerExecutable == null || !dockerExecutable.isAbsolute()) {
            throw new IllegalArgumentException("docker executable must be an absolute path");
        }
        String executableText = dockerExecutable.toString();
        if (executableText.indexOf('\n') >= 0 || executableText.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("docker executable must not contain control characters");
        }
        if (imageId == null || !FROZEN_IMAGE_ID.matcher(imageId).matches()) {
            throw new IllegalArgumentException(
                    "docker verifier image must be a frozen sha256:<64 lowercase hex> image ID");
        }
        if (processExecutor == null) {
            throw new IllegalArgumentException("process executor must not be null");
        }
        if (identityResolver == null) {
            throw new IllegalArgumentException("host identity resolver must not be null");
        }
        this.dockerExecutable = dockerExecutable.normalize();
        this.imageId = imageId;
        this.processExecutor = processExecutor;
        this.identityResolver = identityResolver;
    }

    @Override
    public BenchmarkVerifier.Result verify(CaseDefinition.VerifierInvocation invocation,
                                           Path workspace,
                                           Path isolatedHome,
                                           Path evidence,
                                           Duration timeout) throws IOException, InterruptedException {
        if (invocation == null || invocation.arguments().isEmpty()) {
            throw new IllegalArgumentException("verifier invocation must not be empty");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("verifier timeout must be positive");
        }

        Path realWorkspace = canonicalDirectory(workspace, "verifier workspace");
        Path realHome = canonicalDirectory(isolatedHome, "verifier home");
        Path episode = realWorkspace.getParent();
        if (episode == null || !episode.equals(realHome.getParent())) {
            throw new IOException("verifier workspace and home must share one episode directory");
        }
        Path realSuite = canonicalDirectory(invocation.workingDirectory(), "verifier suite directory");
        Path realEvidence = canonicalEvidence(invocation, evidence, episode, realWorkspace, realHome);
        HostIdentity identity = identityResolver.resolve(realWorkspace);
        if (identity == null || identity.uid() <= 0 || identity.gid() < 0) {
            throw new IOException("docker verifier requires a non-root host uid and valid gid");
        }

        Path verifierTemp = BenchmarkProcessEnvironment.preparePrivateDirectory(
                episode.resolve("verifier-docker-tmp"));
        Path cidFile = verifierTemp.resolve("container.cid");
        if (Files.exists(cidFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("docker verifier cidfile already exists");
        }

        final CaseDefinition.VerifierInvocation materialized;
        try {
            materialized = invocation.materialize(realWorkspace, realEvidence);
        } catch (IllegalArgumentException e) {
            throw new IOException("verifier evidence materialization failed", e);
        }
        List<String> containerArguments = containerArguments(
                materialized.arguments(), realWorkspace, realEvidence);
        List<String> dockerArguments = dockerArguments(
                containerArguments, realSuite, realWorkspace, realEvidence,
                verifierTemp, cidFile, identity);
        ProcessBuilder builder = new ProcessBuilder(dockerArguments);
        builder.directory(verifierTemp.toFile());
        builder.redirectErrorStream(false);
        BenchmarkProcessEnvironment.sanitize(builder.environment(), realHome, verifierTemp);

        BenchmarkSubprocess.Result process = null;
        Throwable runFailure = null;
        InterruptedException interruption = null;
        try {
            process = processExecutor.run(builder, null, timeout, OUTPUT_LIMIT, OUTPUT_LIMIT);
        } catch (IOException error) {
            runFailure = error;
        } catch (RuntimeException error) {
            runFailure = error;
        } catch (InterruptedException error) {
            interruption = error;
        }

        InfrastructureException cleanupFailure = null;
        try {
            removeContainer(cidFile, verifierTemp);
        } catch (InfrastructureException error) {
            cleanupFailure = error;
        }
        if (interruption != null) {
            Thread.currentThread().interrupt();
            if (cleanupFailure != null) {
                cleanupFailure.addSuppressed(interruption);
                throw cleanupFailure;
            }
            throw interruption;
        }
        if (cleanupFailure != null) {
            if (runFailure != null) {
                cleanupFailure.addSuppressed(runFailure);
            }
            throw cleanupFailure;
        }
        if (runFailure != null) {
            throw new InfrastructureException("docker verifier process execution failed", runFailure);
        }
        if (process == null) {
            throw new InfrastructureException("docker verifier returned no process result");
        }
        if (Integer.valueOf(125).equals(process.exitCode())) {
            throw new InfrastructureException("docker verifier could not start the container");
        }

        BenchmarkVerifier.Status status;
        if (process.timedOut()) {
            status = BenchmarkVerifier.Status.TIMEOUT;
        } else if (process.exitCode() != null && process.exitCode() == 0) {
            status = BenchmarkVerifier.Status.PASSED;
        } else {
            status = BenchmarkVerifier.Status.FAILED;
        }

        BoundedText stdout = redactAndLimit(
                process.stdout(),
                realSuite, invocation.workingDirectory(),
                realWorkspace, workspace.toAbsolutePath().normalize(),
                realEvidence, evidence == null ? null : evidence.toAbsolutePath().normalize(),
                verifierTemp);
        BoundedText stderr = redactAndLimit(
                process.stderr(),
                realSuite, invocation.workingDirectory(),
                realWorkspace, workspace.toAbsolutePath().normalize(),
                realEvidence, evidence == null ? null : evidence.toAbsolutePath().normalize(),
                verifierTemp);
        return new BenchmarkVerifier.Result(
                status,
                process.exitCode(),
                process.elapsedMillis(),
                containerArguments,
                stdout.value(),
                stderr.value(),
                process.stdoutTruncated() || stdout.truncated(),
                process.stderrTruncated() || stderr.truncated(),
                true,
                policyFingerprint());
    }

    BenchmarkVerifier.Result verify(CaseDefinition.VerifierInvocation invocation,
                                    Path workspace,
                                    Path isolatedHome,
                                    Duration timeout) throws IOException, InterruptedException {
        return verify(invocation, workspace, isolatedHome, null, timeout);
    }

    private List<String> dockerArguments(List<String> verifierArguments,
                                         Path suite,
                                         Path workspace,
                                         Path evidence,
                                         Path verifierTemp,
                                         Path cidFile,
                                         HostIdentity identity) throws IOException {
        String entrypoint = verifierArguments.get(0);
        if (entrypoint.isBlank() || entrypoint.indexOf('\n') >= 0 || entrypoint.indexOf('\r') >= 0) {
            throw new IOException("docker verifier entrypoint is invalid");
        }

        List<String> arguments = new ArrayList<>();
        arguments.add(dockerExecutable.toString());
        arguments.add("run");
        arguments.add("--pull=never");
        arguments.add("--network");
        arguments.add("none");
        arguments.add("--read-only");
        arguments.add("--user");
        arguments.add(identity.uid() + ":" + identity.gid());
        arguments.add("--cap-drop");
        arguments.add("ALL");
        arguments.add("--security-opt");
        arguments.add("no-new-privileges:true");
        arguments.add("--pids-limit");
        arguments.add("64");
        arguments.add("--memory");
        arguments.add("256m");
        arguments.add("--memory-swap");
        arguments.add("256m");
        arguments.add("--cpus");
        arguments.add("0.5");
        arguments.add("--ulimit");
        arguments.add("nofile=256:256");
        arguments.add("--env");
        arguments.add("HOME=/tmp");
        arguments.add("--env");
        arguments.add("TMPDIR=/tmp");
        arguments.add("--env");
        arguments.add("PYTHONDONTWRITEBYTECODE=1");
        arguments.add("--tmpfs");
        arguments.add("/tmp:rw,nosuid,nodev,noexec,size=64m,mode=1777");
        arguments.add("--mount");
        arguments.add(readOnlyMount(suite, CONTAINER_SUITE));
        arguments.add("--mount");
        arguments.add(readOnlyMount(workspace, CONTAINER_WORKSPACE));
        if (evidence != null) {
            arguments.add("--mount");
            arguments.add(readOnlyMount(evidence, CONTAINER_EVIDENCE));
        }
        arguments.add("--workdir");
        arguments.add(CONTAINER_SUITE);
        arguments.add("--cidfile");
        arguments.add(cidFile.toString());
        arguments.add("--entrypoint");
        arguments.add(entrypoint);
        arguments.add(imageId);
        arguments.addAll(verifierArguments.subList(1, verifierArguments.size()));
        return List.copyOf(arguments);
    }

    private static List<String> containerArguments(List<String> materializedArguments,
                                                   Path workspace,
                                                   Path evidence)
            throws IOException {
        if (materializedArguments == null || materializedArguments.isEmpty()) {
            throw new IOException("materialized verifier invocation must not be empty");
        }
        String hostWorkspace = workspace.toString();
        return materializedArguments.stream()
                .map(argument -> {
                    if (argument.equals(hostWorkspace)) {
                        return CONTAINER_WORKSPACE;
                    }
                    if (evidence != null && argument.equals(evidence.toString())) {
                        return CONTAINER_EVIDENCE;
                    }
                    return argument;
                })
                .toList();
    }

    private static Path canonicalEvidence(CaseDefinition.VerifierInvocation invocation,
                                          Path evidence,
                                          Path episode,
                                          Path workspace,
                                          Path home) throws IOException {
        if (!invocation.requiresEvidence()) {
            return null;
        }
        if (evidence == null || Files.isSymbolicLink(evidence)
                || !Files.isRegularFile(evidence, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("verifier evidence is required but missing or unsafe");
        }
        Path real = evidence.toAbsolutePath().normalize().toRealPath();
        Path evidenceDirectory = real.getParent();
        if (evidenceDirectory == null || !episode.equals(evidenceDirectory.getParent())
                || real.startsWith(workspace) || real.startsWith(home)) {
            throw new IOException("verifier evidence must be outside workspace/home in an episode sibling");
        }
        return real;
    }

    private static String readOnlyMount(Path source, String target) throws IOException {
        String value = source.toString();
        if (value.indexOf(',') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IOException("docker bind source contains a character unsupported by --mount");
        }
        return "type=bind,source=" + value + ",target=" + target + ",readonly";
    }

    private void removeContainer(Path cidFile, Path workingDirectory)
            throws InfrastructureException {
        CidRecord cid = readSafeCid(cidFile);
        ProcessBuilder cleanup = new ProcessBuilder(
                dockerExecutable.toString(), "rm", "-f", cid.containerId());
        cleanup.directory(workingDirectory.toFile());
        cleanup.redirectErrorStream(false);
        BenchmarkProcessEnvironment.sanitize(
                cleanup.environment(), workingDirectory, workingDirectory);

        final BenchmarkSubprocess.Result result;
        try {
            result = processExecutor.run(
                    cleanup, null, CLEANUP_TIMEOUT, CLEANUP_OUTPUT_LIMIT, CLEANUP_OUTPUT_LIMIT);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new InfrastructureException("docker verifier cleanup was interrupted", error);
        } catch (IOException | RuntimeException error) {
            throw new InfrastructureException("docker verifier cleanup could not be executed", error);
        }
        if (result == null || result.stdout() == null || result.stderr() == null) {
            throw new InfrastructureException("docker verifier cleanup output was not fully drained");
        }
        if (result.timedOut()) {
            throw new InfrastructureException("docker verifier cleanup timed out");
        }
        if (result.exitCode() == null || result.exitCode() != 0) {
            throw new InfrastructureException("docker verifier cleanup failed");
        }
        cid.verifyUnchanged();
        try {
            Files.delete(cidFile);
        } catch (IOException error) {
            throw new InfrastructureException("docker verifier cidfile could not be removed", error);
        }
    }

    private static CidRecord readSafeCid(Path cidFile) throws InfrastructureException {
        try {
            if (cidFile == null || Files.isSymbolicLink(cidFile)
                    || !Files.isRegularFile(cidFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new InfrastructureException(
                        "docker verifier cidfile is missing or not a safe regular file");
            }
            BasicFileAttributes before = Files.readAttributes(
                    cidFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile() || before.fileKey() == null
                    || before.size() < 12 || before.size() > MAX_CIDFILE_BYTES) {
                throw new InfrastructureException("docker verifier cidfile metadata is invalid");
            }
            Object links = Files.getAttribute(cidFile, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
            if (!(links instanceof Number number) || number.longValue() != 1L) {
                throw new InfrastructureException("docker verifier cidfile is hard-linked");
            }
            String containerId = Files.readString(cidFile, StandardCharsets.UTF_8).trim();
            BasicFileAttributes after = Files.readAttributes(
                    cidFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!sameIdentity(before, after)) {
                throw new InfrastructureException("docker verifier cidfile changed while it was read");
            }
            if (!CONTAINER_ID.matcher(containerId).matches()) {
                throw new InfrastructureException(
                        "docker verifier cidfile contains an invalid container ID");
            }
            return new CidRecord(
                    cidFile, containerId, before.fileKey(), before.size(),
                    before.lastModifiedTime().toMillis());
        } catch (InfrastructureException error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw new InfrastructureException("docker verifier cidfile could not be validated", error);
        }
    }

    private static boolean sameIdentity(BasicFileAttributes first, BasicFileAttributes second) {
        return Objects.equals(first.fileKey(), second.fileKey())
                && first.size() == second.size()
                && first.lastModifiedTime().equals(second.lastModifiedTime());
    }

    private static HostIdentity unixIdentity(Path workspace) throws IOException {
        try {
            Number uid = (Number) Files.getAttribute(
                    workspace, "unix:uid", LinkOption.NOFOLLOW_LINKS);
            Number gid = (Number) Files.getAttribute(
                    workspace, "unix:gid", LinkOption.NOFOLLOW_LINKS);
            return new HostIdentity(uid.longValue(), gid.longValue());
        } catch (UnsupportedOperationException | ClassCastException e) {
            throw new IOException("host filesystem does not expose unix uid:gid", e);
        }
    }

    private static Path canonicalDirectory(Path raw, String label) throws IOException {
        if (raw == null || Files.isSymbolicLink(raw)
                || !Files.isDirectory(raw, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " is not a safe directory");
        }
        return raw.toAbsolutePath().normalize().toRealPath();
    }

    private static BoundedText redactAndLimit(String raw,
                                              Path suite,
                                              Path suiteAlias,
                                              Path workspace,
                                              Path workspaceAlias,
                                              Path evidence,
                                              Path evidenceAlias,
                                              Path verifierTemp) {
        String normalized = raw == null ? "" : raw;
        List<PathReplacement> replacements = new ArrayList<>();
        replacements.add(new PathReplacement(suite, CONTAINER_SUITE));
        replacements.add(new PathReplacement(suiteAlias, CONTAINER_SUITE));
        replacements.add(new PathReplacement(workspace, CONTAINER_WORKSPACE));
        replacements.add(new PathReplacement(workspaceAlias, CONTAINER_WORKSPACE));
        replacements.add(new PathReplacement(evidence, CONTAINER_EVIDENCE));
        replacements.add(new PathReplacement(evidenceAlias, CONTAINER_EVIDENCE));
        replacements.add(new PathReplacement(verifierTemp, "[RUNNER_VERIFIER_TEMP]"));
        List<PathReplacement> orderedReplacements = replacements.stream()
                .filter(replacement -> replacement.path() != null)
                .sorted(Comparator.comparingInt(
                        (PathReplacement replacement) -> replacement.path().toString().length())
                        .reversed())
                .toList();
        for (PathReplacement replacement : orderedReplacements) {
            normalized = normalized.replace(
                    replacement.path().toString(), replacement.replacement());
        }
        String redacted = SecretRedactor.redact(normalized);
        byte[] bytes = redacted.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= OUTPUT_LIMIT) {
            return new BoundedText(redacted, false);
        }

        StringBuilder limited = new StringBuilder(Math.min(redacted.length(), OUTPUT_LIMIT));
        int used = 0;
        for (int offset = 0; offset < redacted.length();) {
            int codePoint = redacted.codePointAt(offset);
            String value = new String(Character.toChars(codePoint));
            int length = value.getBytes(StandardCharsets.UTF_8).length;
            if (used + length > OUTPUT_LIMIT) {
                break;
            }
            limited.append(value);
            used += length;
            offset += Character.charCount(codePoint);
        }
        return new BoundedText(limited.toString(), true);
    }

    private String policyFingerprint() {
        String policy = imageId
                + "\nnetwork=none"
                + "\nread-only"
                + "\ncap-drop=ALL"
                + "\nno-new-privileges"
                + "\nworkspace=ro"
                + "\nsuite=ro"
                + "\nevidence-file=ro";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(policy.getBytes(StandardCharsets.UTF_8));
            return "docker-" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @FunctionalInterface
    interface ProcessExecutor {
        BenchmarkSubprocess.Result run(ProcessBuilder builder,
                                       byte[] stdin,
                                       Duration timeout,
                                       int stdoutLimit,
                                       int stderrLimit) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface HostIdentityResolver {
        HostIdentity resolve(Path workspace) throws IOException;
    }

    record HostIdentity(long uid, long gid) {
    }

    static final class InfrastructureException extends IOException {
        InfrastructureException(String message) {
            super(message);
        }

        InfrastructureException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record CidRecord(Path path,
                             String containerId,
                             Object fileKey,
                             long size,
                             long lastModifiedMillis) {
        private void verifyUnchanged() throws InfrastructureException {
            try {
                if (Files.isSymbolicLink(path)
                        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new InfrastructureException(
                            "docker verifier cidfile was replaced before removal");
                }
                BasicFileAttributes current = Files.readAttributes(
                        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!Objects.equals(fileKey, current.fileKey())
                        || size != current.size()
                        || lastModifiedMillis != current.lastModifiedTime().toMillis()) {
                    throw new InfrastructureException(
                            "docker verifier cidfile changed before removal");
                }
            } catch (InfrastructureException error) {
                throw error;
            } catch (IOException | RuntimeException error) {
                throw new InfrastructureException(
                        "docker verifier cidfile could not be revalidated", error);
            }
        }
    }

    private record BoundedText(String value, boolean truncated) {
    }

    private record PathReplacement(Path path, String replacement) {
    }
}
