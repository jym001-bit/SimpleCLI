package com.paicli.eval.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Fail-closed macOS Seatbelt profile for deterministic verifier processes. */
final class BenchmarkVerifierSandbox {
    private static final Path EXECUTABLE = Path.of("/usr/bin/sandbox-exec");
    private static final List<Path> SYSTEM_READ_ROOTS = List.of(
            Path.of("/System/Library"),
            Path.of("/bin"),
            Path.of("/sbin"),
            Path.of("/usr/bin"),
            Path.of("/usr/sbin"),
            Path.of("/usr/lib"),
            Path.of("/usr/libexec"),
            Path.of("/usr/share"),
            Path.of("/Library/Java"),
            Path.of("/Library/Developer"),
            Path.of("/Applications/Xcode.app/Contents/Developer"),
            Path.of("/opt/homebrew/bin"),
            Path.of("/opt/homebrew/sbin"),
            Path.of("/opt/homebrew/Cellar"),
            Path.of("/opt/homebrew/lib"),
            Path.of("/opt/homebrew/share"),
            Path.of("/usr/local/bin"),
            Path.of("/usr/local/sbin"),
            Path.of("/usr/local/Cellar"),
            Path.of("/usr/local/lib"),
            Path.of("/usr/local/share"));
    private static final List<Path> DEVICE_READ_PATHS = List.of(
            Path.of("/dev/null"),
            Path.of("/dev/random"),
            Path.of("/dev/urandom"),
            Path.of("/dev/zero"));

    private BenchmarkVerifierSandbox() {
    }

    static SandboxedInvocation prepare(CaseDefinition.VerifierInvocation invocation,
                                       Path workspace,
                                       Path verifierTemp) throws IOException, InterruptedException {
        return prepare(invocation, workspace, verifierTemp, null);
    }

    static SandboxedInvocation prepare(CaseDefinition.VerifierInvocation invocation,
                                       Path workspace,
                                       Path verifierTemp,
                                       Path evidence) throws IOException, InterruptedException {
        if (!Files.isRegularFile(EXECUTABLE) || !Files.isExecutable(EXECUTABLE)) {
            throw new SandboxUnavailableException("RUNNER_SANDBOX_UNAVAILABLE: sandbox-exec is unavailable");
        }
        Path realWorkspace = canonicalDirectory(workspace, "verifier workspace");
        Path realTemp = canonicalDirectory(verifierTemp, "verifier temp");
        Path workingDirectory = canonicalDirectory(invocation.workingDirectory(),
                "verifier working directory");

        Path realEvidence = canonicalEvidence(evidence);
        Set<Path> declaredReadPaths = declaredReadPaths(
                invocation.arguments(), workingDirectory, realWorkspace, realEvidence);
        String profile = profile(realWorkspace, realTemp, declaredReadPaths, realEvidence);
        probe(profile, workingDirectory, realTemp);

        List<String> arguments = new ArrayList<>();
        arguments.add(EXECUTABLE.toString());
        arguments.add("-p");
        arguments.add(profile);
        arguments.addAll(invocation.arguments());
        return new SandboxedInvocation(List.copyOf(arguments), workingDirectory, profile);
    }

    private static Set<Path> declaredReadPaths(List<String> arguments,
                                               Path workingDirectory,
                                               Path workspace,
                                               Path evidence) throws IOException {
        Set<Path> declared = new LinkedHashSet<>();
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            Path candidate;
            try {
                candidate = Path.of(argument);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (!candidate.isAbsolute()) {
                candidate = workingDirectory.resolve(candidate).normalize();
            }
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (Files.isSymbolicLink(candidate)) {
                throw new IOException("verifier argv references a symbolic link: " + candidate);
            }
            Path real = candidate.toRealPath();
            if (real.startsWith(workspace)) {
                continue;
            }
            if (evidence != null && real.equals(evidence)) {
                continue;
            }
            if (!real.startsWith(workingDirectory)) {
                throw new IOException("verifier argv file escapes suite directory: " + candidate);
            }
            declared.add(real);
        }
        return declared;
    }

    private static String profile(Path workspace,
                                  Path verifierTemp,
                                  Set<Path> declaredReadPaths,
                                  Path evidence) {
        StringBuilder value = new StringBuilder();
        value.append("(version 1)\n")
                .append("(deny default)\n")
                .append("(deny network*)\n")
                .append("(allow process*)\n")
                .append("(allow sysctl-read)\n")
                .append("(allow mach-lookup)\n")
                .append("(allow file-read*\n");
        appendSubpath(value, workspace);
        appendSubpath(value, verifierTemp);
        for (Path root : SYSTEM_READ_ROOTS) {
            if (Files.isDirectory(root)) {
                appendSubpath(value, root.toAbsolutePath().normalize());
            }
        }
        for (Path toolchain : BenchmarkProcessEnvironment.fixedToolchainDirectories()) {
            appendSubpath(value, toolchain);
        }
        for (Path device : DEVICE_READ_PATHS) {
            if (Files.exists(device)) {
                appendLiteral(value, device);
            }
        }
        for (Path declared : declaredReadPaths) {
            appendLiteral(value, declared);
        }
        if (evidence != null) {
            appendLiteral(value, evidence);
        }
        value.append(")\n")
                .append("(allow file-write*\n");
        appendSubpath(value, verifierTemp);
        appendLiteral(value, Path.of("/dev/null"));
        value.append(")\n");
        return value.toString();
    }

    private static void probe(String profile, Path workingDirectory, Path verifierTemp)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(
                EXECUTABLE.toString(), "-p", profile, "/usr/bin/true");
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(false);
        BenchmarkProcessEnvironment.sanitize(builder.environment(), verifierTemp, verifierTemp);
        BenchmarkSubprocess.Result result = BenchmarkSubprocess.run(
                builder, null, Duration.ofSeconds(3), 16_384, 16_384);
        if (result.timedOut() || result.exitCode() == null || result.exitCode() != 0) {
            String diagnostic = SecretRedactor.redact(
                    (result.stderr() == null ? "" : result.stderr())
                            .replaceAll("[\\r\\n]+", " ")
                            .trim());
            if (diagnostic.length() > 1_024) {
                diagnostic = diagnostic.substring(0, 1_024) + "...[truncated]";
            }
            throw new SandboxUnavailableException(
                    "RUNNER_SANDBOX_UNAVAILABLE: verifier Seatbelt probe failed"
                            + (diagnostic.isBlank() ? "" : " (" + diagnostic + ")"));
        }
    }

    private static Path canonicalDirectory(Path raw, String label) throws IOException {
        if (raw == null || Files.isSymbolicLink(raw)
                || !Files.isDirectory(raw, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " is not a safe directory");
        }
        return raw.toAbsolutePath().normalize().toRealPath();
    }

    private static Path canonicalEvidence(Path raw) throws IOException {
        if (raw == null) {
            return null;
        }
        if (Files.isSymbolicLink(raw) || !Files.isRegularFile(raw, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("verifier evidence is not a safe regular file");
        }
        return raw.toAbsolutePath().normalize().toRealPath();
    }

    private static void appendSubpath(StringBuilder profile, Path path) {
        profile.append("  (subpath ").append(seatbeltString(path)).append(")\n");
    }

    private static void appendLiteral(StringBuilder profile, Path path) {
        profile.append("  (literal ").append(seatbeltString(path)).append(")\n");
    }

    private static String seatbeltString(Path path) {
        String value = path.toAbsolutePath().normalize().toString();
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    record SandboxedInvocation(List<String> arguments, Path workingDirectory, String profile) {
    }

    static final class SandboxUnavailableException extends IOException {
        SandboxUnavailableException(String message) {
            super(message);
        }
    }
}
