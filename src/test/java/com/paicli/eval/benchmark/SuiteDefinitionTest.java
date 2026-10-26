package com.paicli.eval.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuiteDefinitionTest {

    @Test
    void loadsStrictManifestAndResolvesRelativeFixtureAndVerifier(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("fixtures/l1"));
        Path suiteFile = writeSuite(tempDir, validSuiteJson());

        SuiteDefinition suite = SuiteDefinition.load(suiteFile);

        assertEquals("1.0", suite.version());
        assertEquals("PaiCLI Agent v1", suite.name());
        assertEquals(3, suite.activeCases().size());
        CaseDefinition first = suite.cases().get(0);
        assertEquals(tempDir.resolve("fixtures/l1").toAbsolutePath().normalize(),
                suite.resolveFixture(first));
        CaseDefinition.VerifierInvocation verifier = suite.resolveVerifier(first);
        assertEquals(tempDir.toAbsolutePath().normalize(), verifier.workingDirectory());
        assertEquals(List.of("mvn", "-q", "test"), verifier.arguments());

        CaseDefinition.VerifierInvocation withWorkspace = new CaseDefinition.VerifierInvocation(
                tempDir, List.of("validators/check.sh", "{workspace}"))
                .forWorkspace(tempDir.resolve("run-workspace"));
        assertEquals(tempDir.resolve("run-workspace").toAbsolutePath().normalize().toString(),
                withWorkspace.arguments().get(1));
    }

    @Test
    void rejectsDuplicateCaseIds(@TempDir Path tempDir) throws Exception {
        String json = """
                {
                  "version":"1", "name":"duplicate", "cases":[
                    %s,
                    %s
                  ]
                }
                """.formatted(caseJson("same", "L1", 50, "fixtures/a", "command",
                        "[\"mvn\",\"test\"]", "active"),
                caseJson("same", "L2", 50, "fixtures/b", "none", "[]", "active"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SuiteDefinition.load(writeSuite(tempDir, json)));
        assertTrue(error.getMessage().contains("duplicate case id"));
    }

    @Test
    void requiresActiveWeightsToSumToOneHundred(@TempDir Path tempDir) throws Exception {
        String json = """
                {"version":"1", "name":"weights", "cases":[%s,%s]}
                """.formatted(
                caseJson("a", "L1", 40, "fixtures/a", "none", "[]", "active"),
                caseJson("b", "L2", 50, "fixtures/b", "none", "[]", "active"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SuiteDefinition.load(writeSuite(tempDir, json)));
        assertTrue(error.getMessage().contains("sum to 100"));
    }

    @Test
    void rejectsFixturePathEscape(@TempDir Path tempDir) throws Exception {
        String json = """
                {"version":"1", "name":"escape", "cases":[%s]}
                """.formatted(caseJson("escape", "L1", 100, "../outside",
                "none", "[]", "active"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SuiteDefinition.load(writeSuite(tempDir, json)));
        assertTrue(error.getMessage().contains("fixturePath"));
    }

    @Test
    void rejectsUnsafeVerifierArguments(@TempDir Path tempDir) throws Exception {
        String traversal = """
                {"version":"1", "name":"command", "cases":[%s]}
                """.formatted(caseJson("command", "L1", 100, "fixtures/a",
                "command", "[\"scripts/verify.sh\",\"../hidden\"]", "active"));
        String absolute = """
                {"version":"1", "name":"command", "cases":[%s]}
                """.formatted(caseJson("command", "L1", 100, "fixtures/a",
                "command", "[\"/usr/bin/env\"]", "active"));

        assertThrows(IllegalArgumentException.class,
                () -> SuiteDefinition.load(writeSuite(tempDir, traversal)));
        assertThrows(IllegalArgumentException.class,
                () -> SuiteDefinition.load(writeSuite(tempDir, absolute)));
    }

    @Test
    void placeholdersMustOccupyOneCompleteArgvItem(@TempDir Path tempDir) throws Exception {
        for (String escaped : List.of(
                "--workspace={workspace}", "prefix-{evidence}", "{evidence}/x", " {evidence} ")) {
            String json = """
                    {"version":"1", "name":"placeholder", "cases":[%s]}
                    """.formatted(caseJson("placeholder", "L1", 100, "fixtures/a",
                    "command", "[\"bash\",\"" + escaped + "\"]", "active"));
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> SuiteDefinition.load(writeSuite(tempDir, json)));
            assertTrue(error.getMessage().contains("complete argv item"));
        }
    }

    @Test
    void rejectsUnknownJsonFields(@TempDir Path tempDir) throws Exception {
        String json = validSuiteJson().replace(
                "\"name\":\"PaiCLI Agent v1\"",
                "\"name\":\"PaiCLI Agent v1\",\"unexpected\":true");

        assertThrows(Exception.class, () -> SuiteDefinition.load(writeSuite(tempDir, json)));
    }

    private static Path writeSuite(Path directory, String json) throws Exception {
        Path file = directory.resolve("suite.json");
        Files.writeString(file, json);
        return file;
    }

    private static String validSuiteJson() {
        return """
                {
                  "version":"1.0",
                  "name":"PaiCLI Agent v1",
                  "cases":[
                    %s,
                    %s,
                    %s
                  ]
                }
                """.formatted(
                caseJson("read-001", "L1", 30, "fixtures/l1", "command",
                        "[\"mvn\",\"-q\",\"test\"]", "active"),
                caseJson("fix-001", "L2", 30, "fixtures/l2", "none", "[]", "active"),
                caseJson("long-001", "L3", 40, "fixtures/l3", "none", "[]", "active"));
    }

    private static String caseJson(String id,
                                   String level,
                                   int weight,
                                   String fixture,
                                   String verifierType,
                                   String verifierCommand,
                                   String status) {
        return """
                {
                  "id":"%s",
                  "title":"Case %s",
                  "category":"coding",
                  "level":"%s",
                  "weight":%d,
                  "mode":"react",
                  "fixturePath":"%s",
                  "prompt":"Complete the task",
                  "verifierType":"%s",
                  "verifierCommand":%s,
                  "status":"%s"
                }
                """.formatted(id, id, level, weight, fixture, verifierType, verifierCommand, status);
    }
}
