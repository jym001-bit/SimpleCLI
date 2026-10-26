package com.paicli.eval.benchmark.formal;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** The execution authority is a verified admission, never a caller-constructed plan/JSON. */
public final class FormalBenchmarkAdmission {
    private final FormalBenchmarkPreflight preflight;

    public FormalBenchmarkAdmission() {
        this(new FormalBenchmarkPreflight());
    }

    // Test seam remains inside the trusted formal package; no CLI can override verification.
    FormalBenchmarkAdmission(FormalBenchmarkPreflight preflight) {
        this.preflight = Objects.requireNonNull(preflight);
    }

    public AdmittedBatch admit(Inputs inputs) throws IOException {
        Objects.requireNonNull(inputs, "inputs");
        FormalExecutionPlan plan = verify(inputs);
        return new AdmittedBatch(this, inputs, plan);
    }

    private FormalExecutionPlan verify(Inputs inputs) throws IOException {
        return FormalExecutionPlan.from(preflight.verify(inputs.frozenRoot(),
                inputs.publicRepositoryRoot(), inputs.executableSuiteContract(),
                inputs.formalBatchContract(), inputs.candidateJar(), inputs.runnerJar()));
    }

    /** Only artifact locations: no prompt, model, mode, budget, tool or case overrides. */
    public record Inputs(Path frozenRoot, Path publicRepositoryRoot,
                         Path executableSuiteContract, Path formalBatchContract,
                         Path candidateJar, Path runnerJar) {
        public Inputs {
            Objects.requireNonNull(frozenRoot);
            Objects.requireNonNull(publicRepositoryRoot);
            Objects.requireNonNull(executableSuiteContract);
            Objects.requireNonNull(formalBatchContract);
            Objects.requireNonNull(candidateJar);
            Objects.requireNonNull(runnerJar);
        }

        @Override public String toString() { return "FormalAdmissionInputs[private artifacts]"; }
    }

    /** Cannot be built from public records or deserialized as an execution capability. */
    public static final class AdmittedBatch {
        private final FormalBenchmarkAdmission owner;
        private final Inputs inputs;
        private final FormalExecutionPlan plan;

        private AdmittedBatch(FormalBenchmarkAdmission owner, Inputs inputs,
                              FormalExecutionPlan plan) {
            this.owner = owner;
            this.inputs = inputs;
            this.plan = plan;
        }

        /** Immutable audit data; exposing it does not grant authority to a reconstructed plan. */
        public FormalExecutionPlan plan() { return plan; }

        public String batchSha256() { return plan.artifacts().formalBatchContractSha256(); }

        public void requireSeparateOutput(Path output) throws IOException {
            for (Path input : java.util.List.of(inputs.frozenRoot(), inputs.publicRepositoryRoot(),
                    inputs.executableSuiteContract(), inputs.formalBatchContract(),
                    inputs.candidateJar(), inputs.runnerJar())) {
                Path real = input.toRealPath();
                if (output.startsWith(real) || real.startsWith(output))
                    throw new IOException("formal output overlaps an admitted input");
            }
        }

        /** Rerun admission immediately before execution; changed artifacts need a new batch. */
        public void verifyUnchanged() throws IOException {
            FormalExecutionPlan current = owner.verify(inputs);
            if (!plan.equals(current))
                throw new IOException("formal admission changed; create a new registered batch");
        }

        @Override public String toString() {
            return "AdmittedBatch[batch=" + batchSha256() + ", episodes="
                    + plan.episodes().size() + ", publishable=false]";
        }
    }
}
