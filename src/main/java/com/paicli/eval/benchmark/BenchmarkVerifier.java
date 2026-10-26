package com.paicli.eval.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/** Executes a validated verifier argv directly, without command-string or shell expansion. */
final class BenchmarkVerifier implements BenchmarkCoordinatorMain.VerifierExecutor {
    private static final int OUTPUT_LIMIT = 256 * 1024;

    @Override
    public Result verify(CaseDefinition.VerifierInvocation invocation,
                         Path workspace,
                         Path isolatedHome,
                         Path evidence,
                         Duration timeout) throws IOException, InterruptedException {
        if (invocation == null || invocation.arguments().isEmpty()) {
            throw new IllegalArgumentException("verifier invocation must not be empty");
        }
        if (workspace == null || Files.isSymbolicLink(workspace)
                || !Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("verifier workspace is not a safe directory");
        }
        Path realEvidence = requireEvidence(invocation, evidence, workspace, isolatedHome);
        final CaseDefinition.VerifierInvocation materialized;
        try {
            materialized = invocation.materialize(workspace, realEvidence);
        } catch (IllegalArgumentException e) {
            throw new IOException("verifier evidence materialization failed", e);
        }
        Path episode = workspace.toAbsolutePath().normalize().getParent();
        if (episode == null || isolatedHome == null
                || !isolatedHome.toAbsolutePath().normalize().getParent().equals(episode)) {
            throw new IOException("verifier workspace and home must share one episode directory");
        }
        Path verifierTemp = BenchmarkProcessEnvironment.preparePrivateDirectory(
                episode.resolve("verifier-tmp"));
        BenchmarkVerifierSandbox.SandboxedInvocation sandboxed =
                BenchmarkVerifierSandbox.prepare(materialized, workspace, verifierTemp, realEvidence);
        ProcessBuilder builder = new ProcessBuilder(sandboxed.arguments());
        builder.directory(sandboxed.workingDirectory().toFile());
        builder.redirectErrorStream(false);
        BenchmarkProcessEnvironment.sanitize(builder.environment(), verifierTemp, verifierTemp);

        BenchmarkSubprocess.Result process = BenchmarkSubprocess.run(
                builder, null, timeout, OUTPUT_LIMIT, OUTPUT_LIMIT);
        Status status;
        if (process.timedOut()) {
            status = Status.TIMEOUT;
        } else if (process.exitCode() != null && process.exitCode() == 0) {
            status = Status.PASSED;
        } else {
            status = Status.FAILED;
        }
        return new Result(
                status,
                process.exitCode(),
                process.elapsedMillis(),
                materialized.arguments(),
                process.stdout(),
                process.stderr(),
                process.stdoutTruncated(),
                process.stderrTruncated(),
                true,
                shortHash(sandboxed.profile()));
    }

    Result verify(CaseDefinition.VerifierInvocation invocation,
                  Path workspace,
                  Path isolatedHome,
                  Duration timeout) throws IOException, InterruptedException {
        return verify(invocation, workspace, isolatedHome, null, timeout);
    }

    private static Path requireEvidence(CaseDefinition.VerifierInvocation invocation,
                                        Path evidence,
                                        Path workspace,
                                        Path isolatedHome) throws IOException {
        if (!invocation.requiresEvidence()) {
            return null;
        }
        if (evidence == null || Files.isSymbolicLink(evidence)
                || !Files.isRegularFile(evidence, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("verifier evidence is required but missing or unsafe");
        }
        Path realEvidence = evidence.toAbsolutePath().normalize().toRealPath();
        Path episode = workspace.toAbsolutePath().normalize().getParent();
        Path homeEpisode = isolatedHome == null
                ? null
                : isolatedHome.toAbsolutePath().normalize().getParent();
        if (episode == null || !episode.equals(homeEpisode)
                || realEvidence.startsWith(workspace.toAbsolutePath().normalize())
                || realEvidence.startsWith(isolatedHome.toAbsolutePath().normalize())
                || !episode.equals(realEvidence.getParent().getParent())) {
            throw new IOException("verifier evidence must be outside workspace/home in an episode sibling");
        }
        return realEvidence;
    }

    enum Status {
        PASSED,
        FAILED,
        TIMEOUT
    }

    record Result(Status status,
                  Integer exitCode,
                  long elapsedMillis,
                  List<String> arguments,
                  String stdout,
                  String stderr,
                  boolean stdoutTruncated,
                  boolean stderrTruncated,
                  boolean sandboxed,
                  String sandboxProfileFingerprint) {
        Result {
            arguments = List.copyOf(arguments);
        }

        boolean passed() {
            return status == Status.PASSED;
        }
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
