package com.paicli.eval.benchmark.finalset;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Minimal shell-free entry point for creating and independently verifying a private freeze. */
public final class FinalDatasetFreezeMain {
    private static final Set<String> FREEZE_OPTIONS = Set.of(
            "--source", "--destination", "--public-repo", "--suite", "--validators");
    private static final Set<String> VERIFY_OPTIONS = Set.of("--frozen", "--public-repo");

    private FinalDatasetFreezeMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args == null || args.length == 0 || "--help".equals(args[0]) || "help".equals(args[0])) {
            printUsage();
            return;
        }
        String action = args[0];
        FinalDatasetFreezer freezer = new FinalDatasetFreezer();
        switch (action) {
            case "freeze" -> {
                Map<String, String> options = parseOptions(args, FREEZE_OPTIONS);
                FinalDatasetFreezer.FreezeResult result = freezer.freeze(
                        new FinalDatasetFreezer.FreezeRequest(
                                Path.of(require(options, "--source")),
                                Path.of(require(options, "--destination")),
                                Path.of(require(options, "--public-repo")),
                                require(options, "--suite"),
                                require(options, "--validators")));
                printResult("created", result);
            }
            case "verify" -> {
                Map<String, String> options = parseOptions(args, VERIFY_OPTIONS);
                FinalDatasetFreezer.FreezeResult result = freezer.verify(
                        Path.of(require(options, "--frozen")),
                        Path.of(require(options, "--public-repo")));
                printResult("verified", result);
            }
            default -> throw new IllegalArgumentException("unknown action: " + action);
        }
    }

    private static Map<String, String> parseOptions(String[] args, Set<String> allowed) {
        if ((args.length - 1) % 2 != 0) {
            throw new IllegalArgumentException("every option must have exactly one value");
        }
        Map<String, String> parsed = new HashMap<>();
        for (int index = 1; index < args.length; index += 2) {
            String option = args[index];
            if (!allowed.contains(option)) {
                throw new IllegalArgumentException("unknown option for " + args[0] + ": " + option);
            }
            String value = args[index + 1];
            if (value == null || value.isBlank() || value.startsWith("--")) {
                throw new IllegalArgumentException("missing value for option: " + option);
            }
            if (parsed.put(option, value) != null) {
                throw new IllegalArgumentException("duplicate option: " + option);
            }
        }
        if (!parsed.keySet().equals(allowed)) {
            Set<String> missing = new java.util.TreeSet<>(allowed);
            missing.removeAll(parsed.keySet());
            throw new IllegalArgumentException("missing required options: " + missing);
        }
        return Map.copyOf(parsed);
    }

    private static String require(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required option: " + key);
        }
        return value;
    }

    private static void printResult(String action, FinalDatasetFreezer.FreezeResult result) {
        System.out.println("Final dataset freeze " + action + ": " + result.frozenRoot());
        System.out.println("Manifest SHA-256: " + result.manifestSha256());
        System.out.println("Content tree SHA-256: " + result.manifest().contentTreeSha256());
        System.out.println("Suite SHA-256: " + result.manifest().suiteSha256());
        System.out.println("Validator tree SHA-256: "
                + result.manifest().validatorTreeSha256());
    }

    private static void printUsage() {
        System.out.println("""
                Usage:
                  FinalDatasetFreezeMain freeze \\
                    --source <absolute-private-source> \\
                    --destination <absolute-new-freeze> \\
                    --public-repo <absolute-public-paicli-repo> \\
                    --suite <portable-relative-suite-json> \\
                    --validators <portable-relative-validator-root>

                  FinalDatasetFreezeMain verify \\
                    --frozen <absolute-frozen-root> \\
                    --public-repo <absolute-public-paicli-repo>
                """);
    }
}
