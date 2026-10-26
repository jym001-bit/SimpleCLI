package com.paicli.eval.benchmark;

import com.paicli.config.PaiCliConfig;
import com.paicli.eval.benchmark.formal.FormalBenchmarkAdmission;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Formal CLI: locations only, with no model/case/budget/worker override or subset mode. */
public final class FormalBenchmarkCoordinatorMain {
    private FormalBenchmarkCoordinatorMain() {}

    public static void main(String[] args) {
        int exit = execute(args, System.out, System.err);
        if (exit != 0) System.exit(exit);
    }

    static int execute(String[] args, PrintStream out, PrintStream err) {
        if (args != null && args.length == 1 && "--help".equals(args[0])) {
            out.println(usage());
            return 0;
        }
        try {
            Options options = Options.parse(args);
            var admission = new FormalBenchmarkAdmission().admit(options.inputs());
            var ready = FormalBatchPreparation.prepare(admission,
                    provider -> credential(PaiCliConfig.load(), provider));
            out.println("Admitted: " + ready.caseCount() + " cases / " + ready.episodeCount()
                    + " episodes; batch=" + ready.batchSha256());
            if (options.checkOnly()) {
                out.println("Check only: no Candidate/provider execution; publishable=false");
                return 0;
            }
            var report = FormalBatchRunner.run(ready, options.output(), options.runId());
            out.println("Formal artifacts: " + report.directory());
            out.println("Status: " + report.summary().status() + "; attempted="
                    + report.summary().attemptedEpisodes() + "/" + report.summary().plannedEpisodes());
            out.println("Publishable: false; formalScores=null");
            return report.summary().completeValidCoverage() ? 0 : 3;
        } catch (FormalEpisodeRequestFactory.PreparationException error) {
            err.println("Formal preparation rejected: " + error.defect().code());
            return 2;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            err.println("Formal execution interrupted; no publishable score produced.");
            return 130;
        } catch (Exception error) {
            // No raw exception messages: paths and provider-controlled text may be private.
            err.println("Formal benchmark rejected (" + error.getClass().getSimpleName()
                    + "); check frozen artifacts, capabilities and local credentials.");
            err.println(usage());
            return 2;
        }
    }

    static FormalEpisodeRequestFactory.HostCredential credential(PaiCliConfig config, String provider) {
        if (!Set.of("deepseek", "hunyuan", "glm").contains(provider))
            throw new IllegalArgumentException("unsupported formal provider");
        String key = config.getApiKey(provider);
        if (key == null || key.isBlank()) return null;
        // DeepSeek/GLM use the same fixed official clients as the dev coordinator.
        // Hunyuan's configurable endpoint still has to pass the factory's official-origin gate.
        String baseUrl = "hunyuan".equals(provider) ? config.getBaseUrl(provider) : null;
        if (baseUrl != null) baseUrl = baseUrl.trim().replaceAll("/+$", "");
        return new FormalEpisodeRequestFactory.HostCredential(provider, baseUrl, key);
    }

    static String usage() {
        return "Usage: java -cp <paicli.jar> " + FormalBenchmarkCoordinatorMain.class.getName()
                + " --frozen-root <absolute-path> --public-repository-root <absolute-path>"
                + " --executable-suite <absolute-path> --batch-contract <absolute-path>"
                + " --candidate-jar <absolute-path> --runner-jar <absolute-path>"
                + " --output <absolute-private-path> --run-id <new-id> [--check]";
    }

    record Options(FormalBenchmarkAdmission.Inputs inputs, Path output, String runId, boolean checkOnly) {
        private static final Set<String> PATH_OPTIONS = Set.of("--frozen-root", "--public-repository-root",
                "--executable-suite", "--batch-contract", "--candidate-jar", "--runner-jar", "--output");

        static Options parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            boolean check = false;
            if (args == null) args = new String[0];
            for (int i = 0; i < args.length; i++) {
                String name = args[i];
                if ("--check".equals(name)) {
                    if (check) throw new IllegalArgumentException("duplicate check option");
                    check = true;
                    continue;
                }
                if (!PATH_OPTIONS.contains(name) && !"--run-id".equals(name))
                    throw new IllegalArgumentException("unknown formal option");
                if (++i == args.length || args[i] == null || args[i].isBlank() || args[i].startsWith("--"))
                    throw new IllegalArgumentException("missing formal option value");
                if (values.put(name, args[i]) != null)
                    throw new IllegalArgumentException("duplicate formal option");
            }
            Map<String, Path> paths = new LinkedHashMap<>();
            for (String name : PATH_OPTIONS) {
                String raw = values.get(name);
                if (raw == null) throw new IllegalArgumentException("missing formal path option");
                Path path = Path.of(raw);
                if (!path.isAbsolute() || !path.equals(path.normalize()))
                    throw new IllegalArgumentException("formal paths must be absolute and normalized");
                paths.put(name, path);
            }
            String runId = values.get("--run-id");
            CaseDefinition.requireSafeIdentifier(runId, "formal run id");
            return new Options(new FormalBenchmarkAdmission.Inputs(paths.get("--frozen-root"),
                    paths.get("--public-repository-root"), paths.get("--executable-suite"),
                    paths.get("--batch-contract"), paths.get("--candidate-jar"), paths.get("--runner-jar")),
                    paths.get("--output"), runId, check);
        }

        @Override public String toString() { return "FormalOptions[private artifact locations]"; }
    }
}
