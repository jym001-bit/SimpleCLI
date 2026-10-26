package com.paicli.eval.benchmark;

import com.paicli.config.PaiCliConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FormalBenchmarkCoordinatorOptionsTest {
    @Test
    void acceptsOnlyEightLocationsAndOptionalCheck() {
        var options = FormalBenchmarkCoordinatorMain.Options.parse(arguments());
        assertEquals(Path.of("/private/frozen"), options.inputs().frozenRoot());
        assertEquals("formal-run-1", options.runId());
        assertFalse(options.checkOnly());
        var checked = extend(arguments(), "--check");
        assertTrue(FormalBenchmarkCoordinatorMain.Options.parse(checked).checkOnly());
    }

    @Test
    void refusesEvaluationOverridesSecretsDuplicatesAndRelativePaths() {
        for (String flag : List.of("--case", "--model", "--provider", "--repeats", "--timeout-seconds",
                "--tool-profile", "--api-key", "--worker-isolation", "--docker-worker-image"))
            assertThrows(IllegalArgumentException.class, () -> FormalBenchmarkCoordinatorMain.Options.parse(
                    extend(arguments(), flag, "override")));
        assertThrows(IllegalArgumentException.class, () -> FormalBenchmarkCoordinatorMain.Options.parse(
                extend(arguments(), "--run-id", "second")));
        assertThrows(IllegalArgumentException.class, () -> FormalBenchmarkCoordinatorMain.Options.parse(
                extend(arguments(), "--check", "--check")));
        String[] relative = arguments(); relative[1] = "relative";
        assertThrows(IllegalArgumentException.class, () -> FormalBenchmarkCoordinatorMain.Options.parse(relative));
        String[] traversing = arguments(); traversing[1] = "/private/../frozen";
        assertThrows(IllegalArgumentException.class, () -> FormalBenchmarkCoordinatorMain.Options.parse(traversing));
        assertThrows(IllegalArgumentException.class, () -> FormalBenchmarkCoordinatorMain.Options.parse(new String[0]));
    }

    @Test
    void helpAndBadArgumentsDoNotTouchCredentialsDockerOrPrivatePaths() {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        assertEquals(0, FormalBenchmarkCoordinatorMain.execute(new String[]{"--help"},
                new PrintStream(output), new PrintStream(error)));
        assertTrue(output.toString().contains("--check"));
        assertEquals(2, FormalBenchmarkCoordinatorMain.execute(new String[]{"--api-key", "do-not-print"},
                new PrintStream(output), new PrintStream(error)));
        assertFalse(error.toString().contains("do-not-print"));
    }

    @Test
    void credentialsStayHostOnlyAndModelsDoNotComeFromUserConfig() {
        var config = new PaiCliConfig();
        config.getProviders().put("hunyuan", new PaiCliConfig.ProviderConfig("local-only-canary",
                "https://tokenhub.tencentmaas.com/v1/", "other-model"));
        var credential = FormalBenchmarkCoordinatorMain.credential(config, "hunyuan");
        assertEquals("https://tokenhub.tencentmaas.com/v1", credential.baseUrl());
        assertFalse(credential.toString().contains("local-only-canary"));
        assertThrows(IllegalArgumentException.class,
                () -> FormalBenchmarkCoordinatorMain.credential(config, "other-provider"));
    }

    private static String[] arguments() {
        return new String[]{"--frozen-root", "/private/frozen", "--public-repository-root", "/private/public",
                "--executable-suite", "/private/contracts/suite.json", "--batch-contract", "/private/contracts/batch.json",
                "--candidate-jar", "/private/candidate.jar", "--runner-jar", "/private/runner.jar",
                "--output", "/private/results", "--run-id", "formal-run-1"};
    }

    private static String[] extend(String[] original, String... extra) {
        var list = new ArrayList<>(List.of(original)); list.addAll(List.of(extra));
        return list.toArray(String[]::new);
    }
}
