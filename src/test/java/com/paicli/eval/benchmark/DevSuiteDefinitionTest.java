package com.paicli.eval.benchmark;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevSuiteDefinitionTest {
    @Test
    void repositoryDevelopmentSuiteLoadsWithRunnableFixturesAndValidators() throws Exception {
        Path suitePath = Path.of("benchmarks", "paicli-native-agentbench-v0.1", "dev-suite.json")
                .toAbsolutePath().normalize();
        SuiteDefinition suite = SuiteDefinition.load(suitePath);

        assertEquals(8, suite.activeCases().size());
        assertEquals(100, suite.activeCases().stream().mapToInt(CaseDefinition::weight).sum());
        for (CaseDefinition definition : suite.activeCases()) {
            assertEquals(CaseDefinition.Mode.REACT, definition.mode());
            assertEquals(CaseDefinition.VerifierType.COMMAND, definition.verifierType());
            assertTrue(Files.isDirectory(suite.resolveFixture(definition)));
            assertTrue(suite.resolveVerifier(definition).arguments().contains("{workspace}"));
        }
    }
}
