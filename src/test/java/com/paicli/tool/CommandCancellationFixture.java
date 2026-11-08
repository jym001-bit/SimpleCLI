package com.paicli.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Safe local process tree for cancellation tests; never calls a model or an external service. */
public final class CommandCancellationFixture {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("child")) {
            Thread.sleep(30_000);
            return;
        }
        Process child = new ProcessBuilder(java(), "-cp", classpath(),
                CommandCancellationFixture.class.getName(), "child").inheritIO().start();
        try {
            Files.writeString(Path.of(args[0]), Long.toString(child.pid()));
            child.waitFor();
        } finally { child.destroyForcibly(); }
    }

    public static String command(Path ready) {
        // Emit from the shell before starting the Java tree. Child readiness alone does not
        // establish that PowerShell has forwarded the native child's buffered stdout.
        return (windows() ? "Write-Output 'ACTUAL_PARTIAL_OUTPUT'; & " : "printf 'ACTUAL_PARTIAL_OUTPUT\\n'; ")
                + quote(java()) + " -cp " + quote(classpath()) + " "
                + quote(CommandCancellationFixture.class.getName()) + " " + quote(ready.toString());
    }

    public static void awaitReady(Path ready) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while ((!Files.exists(ready) || Files.size(ready) == 0) && System.nanoTime() < deadline)
            Thread.sleep(20);
        if (!Files.exists(ready) || Files.size(ready) == 0) throw new AssertionError("Fixture did not start");
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
    private static String java() { return Path.of(System.getProperty("java.home"), "bin", windows() ? "java.exe" : "java").toString(); }
    private static String classpath() {
        try {
            // The command runs in a temporary project directory, not Maven's working directory.
            return Path.of(CommandCancellationFixture.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toString();
        } catch (java.net.URISyntaxException e) { throw new IllegalStateException(e); }
    }
    private static String quote(String s) { return "'" + s.replace("'", windows() ? "''" : "'\"'\"'") + "'"; }
}
