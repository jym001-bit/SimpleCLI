package com.paicli.eval.benchmark.formal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FormalBenchmarkAdmissionTest {
    @Test
    void onlyVerifiedArtifactsCreateAdmissionAndRetainFull252EpisodePlan(@TempDir Path base)
            throws Exception {
        var fixture = FormalBenchmarkPreflightTest.Fixture.create(base.resolve("admission"));
        var admission = new FormalBenchmarkAdmission(fixture.preflight()).admit(inputs(fixture));
        assertEquals(28, admission.plan().cases().size());
        assertEquals(252, admission.plan().episodes().size());
        assertEquals(admission.plan().artifacts().formalBatchContractSha256(),
                admission.batchSha256());
        admission.verifyUnchanged();
        for (var constructor : FormalBenchmarkAdmission.AdmittedBatch.class
                .getDeclaredConstructors()) assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        assertFalse(admission.toString().contains(base.toString()));
        assertFalse(inputs(fixture).toString().contains(base.toString()));
    }

    @Test
    void changedJarInvalidatesPreviouslyAdmittedBatch(@TempDir Path base) throws Exception {
        var fixture = FormalBenchmarkPreflightTest.Fixture.create(base.resolve("drift"));
        var admission = new FormalBenchmarkAdmission(fixture.preflight()).admit(inputs(fixture));
        Files.writeString(admission.plan().artifacts().candidateJar(), "changed candidate");
        assertThrows(IOException.class, admission::verifyUnchanged);
    }

    @Test
    void changedFixtureInvalidatesPreviouslyAdmittedBatch(@TempDir Path base) throws Exception {
        var fixture = FormalBenchmarkPreflightTest.Fixture.create(base.resolve("fixture-drift"));
        var admission = new FormalBenchmarkAdmission(fixture.preflight()).admit(inputs(fixture));
        Files.writeString(admission.plan().cases().get(0).fixture().sourcePath(), "changed fixture");
        assertThrows(IOException.class, admission::verifyUnchanged);
    }

    private static FormalBenchmarkAdmission.Inputs inputs(
            FormalBenchmarkPreflightTest.Fixture fixture) {
        return new FormalBenchmarkAdmission.Inputs(fixture.frozenRoot(), fixture.publicRepository(),
                fixture.executableContractFile(), fixture.batchContractFile(),
                fixture.candidateJar(), fixture.runnerJar());
    }
}
