package com.paicli.eval.benchmark;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Launches one isolated worker JVM. The API key is written once to stdin and never to argv/env. */
final class BenchmarkWorkerProcess implements BenchmarkCoordinatorMain.WorkerExecutor {
    static final int TOKEN_BUDGET = 500_000;
    static final int HARD_MAX_ITERATIONS = 80;
    static final int STAGNATION_WINDOW = 3;
    private static final int STDERR_LIMIT = 128 * 1024;

    private final String classPath;
    private final Path coordinatorWorkingDirectory;

    BenchmarkWorkerProcess() {
        this(System.getProperty("java.class.path"), Path.of(System.getProperty("user.dir")));
    }

    BenchmarkWorkerProcess(String classPath, Path coordinatorWorkingDirectory) {
        if (classPath == null || classPath.isBlank()) {
            throw new IllegalArgumentException("java.class.path must not be blank");
        }
        this.coordinatorWorkingDirectory = coordinatorWorkingDirectory.toAbsolutePath().normalize();
        this.classPath = absolutizeClassPath(classPath, this.coordinatorWorkingDirectory);
    }

    @Override
    public BenchmarkCoordinatorMain.WorkerExecution execute(
            BenchmarkProtocol.WorkerRequest request,
            Path workspace,
            Path isolatedHome,
            Duration timeout) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command(request, workspace, isolatedHome));
        builder.directory(workspace.toFile());
        builder.redirectErrorStream(false);
        Path isolatedTemp = BenchmarkProcessEnvironment.prepareIsolatedTemp(workspace);
        BenchmarkProcessEnvironment.sanitize(builder.environment(), isolatedHome, isolatedTemp);

        byte[] requestBytes = BenchmarkProtocol.writeRequest(request);
        BenchmarkSubprocess.Result process = BenchmarkSubprocess.run(
                builder,
                requestBytes,
                timeout,
                BenchmarkProtocol.MAX_RESPONSE_BYTES,
                STDERR_LIMIT);

        if (containsExact(process.stdout(), request.apiKey())
                || containsExact(process.stderr(), request.apiKey())) {
            return BenchmarkCoordinatorMain.WorkerExecution.securityFailure(process.elapsedMillis());
        }
        String diagnostic = diagnostic(process);
        if (process.timedOut()) {
            return BenchmarkCoordinatorMain.WorkerExecution.timeout(process.elapsedMillis(), diagnostic);
        }
        if (process.exitCode() == null || process.exitCode() != 0) {
            return BenchmarkCoordinatorMain.WorkerExecution.processFailure(
                    process.exitCode(), process.elapsedMillis(), diagnostic);
        }
        if (process.stdoutTruncated()) {
            return BenchmarkCoordinatorMain.WorkerExecution.processFailure(
                    process.exitCode(), process.elapsedMillis(), "worker response exceeded size limit");
        }

        try {
            BenchmarkProtocol.WorkerResponse response = BenchmarkProtocol.readResponse(
                    process.stdout().getBytes(StandardCharsets.UTF_8));
            return BenchmarkCoordinatorMain.WorkerExecution.completed(
                    response, process.exitCode(), process.elapsedMillis(), diagnostic);
        } catch (IOException | IllegalArgumentException e) {
            return BenchmarkCoordinatorMain.WorkerExecution.processFailure(
                    process.exitCode(),
                    process.elapsedMillis(),
                    "worker returned an invalid protocol response");
        }
    }

    List<String> command(BenchmarkProtocol.WorkerRequest request,
                         Path workspace,
                         Path isolatedHome) {
        Path java = Path.of(System.getProperty("java.home"), "bin", executable("java"))
                .toAbsolutePath().normalize();
        return List.of(
                java.toString(),
                "-Dfile.encoding=UTF-8",
                "-Duser.language=en",
                "-Duser.country=US",
                "-Duser.timezone=UTC",
                "-Dpaicli.prompt.runtime.zone=UTC",
                "-Dpaicli.prompt.runtime.date=" + request.runtimeDate(),
                "-Duser.home=" + isolatedHome.toAbsolutePath().normalize(),
                "-Duser.dir=" + workspace.toAbsolutePath().normalize(),
                "-Dpaicli.react.token.budget=" + request.agentLimits().tokenBudget(),
                "-Dpaicli.react.hard.max.iterations=" + request.agentLimits().hardMaxIterations(),
                "-Dpaicli.react.stagnation.window=" + request.agentLimits().stagnationWindow(),
                "-Dpaicli.compaction.session-memory.enabled=false",
                "-cp",
                classPath,
                BenchmarkWorkerMain.class.getName());
    }

    static String absolutizeClassPath(String rawClassPath, Path baseDirectory) {
        String[] entries = rawClassPath.split(java.util.regex.Pattern.quote(File.pathSeparator), -1);
        List<String> absolute = new ArrayList<>(entries.length);
        for (String entry : entries) {
            String value = entry.isBlank() ? "." : entry;
            Path path = Path.of(value);
            if (!path.isAbsolute()) {
                path = baseDirectory.resolve(path);
            }
            absolute.add(path.toAbsolutePath().normalize().toString());
        }
        return String.join(File.pathSeparator, absolute);
    }

    private static String diagnostic(BenchmarkSubprocess.Result process) {
        String stderr = SecretRedactor.redact(process.stderr() == null ? "" : process.stderr()).trim();
        if (stderr.isBlank()) {
            return process.stderrTruncated() ? "worker stderr was truncated" : "";
        }
        if (process.stderrTruncated()) {
            stderr += "\n...[stderr truncated]";
        }
        return stderr;
    }

    private static String executable(String base) {
        return System.getProperty("os.name", "").toLowerCase().contains("win")
                ? base + ".exe"
                : base;
    }

    private static boolean containsExact(String value, String secret) {
        return value != null && secret != null && !secret.isEmpty() && value.contains(secret);
    }
}
