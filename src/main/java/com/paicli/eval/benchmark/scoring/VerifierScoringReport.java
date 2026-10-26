package com.paicli.eval.benchmark.scoring;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Strict verifier report v1. It intentionally contains no total score, success flag, or
 * self-declared failure class; those values are derived by {@link ScoreCalculator}.
 */
public record VerifierScoringReport(
        @JsonProperty(value = "schemaVersion", required = true) int schemaVersion,
        @JsonProperty(value = "caseId", required = true) String caseId,
        @JsonProperty(value = "assertions", required = true) List<AssertionResult> assertions,
        @JsonProperty(value = "hardGates", required = true) List<HardGateResult> hardGates,
        @JsonProperty(value = "components", required = true) List<ComponentResult> components,
        @JsonProperty(value = "verifierSha256", required = true) String verifierSha256,
        @JsonProperty(value = "toolchainSha256", required = true) String toolchainSha256
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public VerifierScoringReport {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported verifier report schemaVersion: " + schemaVersion);
        }
        caseId = ScoringValidation.identifier(caseId, "caseId");
        assertions = ScoringValidation.nonEmptyCopy(assertions, "assertions");
        hardGates = ScoringValidation.copyAllowEmpty(hardGates, "hardGates");
        components = ScoringValidation.nonEmptyCopy(components, "components");
        ScoringValidation.requireUniqueIds(assertions, AssertionResult::id, "assertions");
        ScoringValidation.requireUniqueIds(hardGates, HardGateResult::id, "hardGates");
        ScoringValidation.requireUniqueIds(components, ComponentResult::id, "components");
        verifierSha256 = ScoringValidation.sha256(verifierSha256, "verifierSha256");
        toolchainSha256 = ScoringValidation.sha256(toolchainSha256, "toolchainSha256");
    }

    public static VerifierScoringReport parse(String json) throws IOException {
        return ScoringJson.parse(json, VerifierScoringReport.class);
    }

    /** Parses bounded verifier stdout bytes without an intermediate unbounded String. */
    public static VerifierScoringReport parse(byte[] json) throws IOException {
        return ScoringJson.parse(json, VerifierScoringReport.class);
    }

    public static VerifierScoringReport load(Path file) throws IOException {
        return ScoringJson.load(file, VerifierScoringReport.class);
    }

    public record AssertionResult(
            @JsonProperty(value = "id", required = true) String id,
            @JsonProperty(value = "pass", required = true) boolean pass,
            @JsonProperty(value = "evidenceRefs", required = true) List<String> evidenceRefs
    ) {
        public AssertionResult {
            id = ScoringValidation.identifier(id, "assertion id");
            evidenceRefs = ScoringValidation.evidenceReferences(evidenceRefs, "assertion evidenceRefs");
        }
    }

    public record HardGateResult(
            @JsonProperty(value = "id", required = true) String id,
            @JsonProperty(value = "violated", required = true) boolean violated,
            @JsonProperty(value = "evidenceRefs", required = true) List<String> evidenceRefs
    ) {
        public HardGateResult {
            id = ScoringValidation.identifier(id, "hard gate id");
            evidenceRefs = ScoringValidation.evidenceReferences(evidenceRefs, "hard gate evidenceRefs");
        }
    }

    public record ComponentResult(
            @JsonProperty(value = "id", required = true) String id,
            @JsonProperty(value = "earnedPoints", required = true) int earnedPoints,
            @JsonProperty(value = "maxPoints", required = true) int maxPoints,
            @JsonProperty(value = "source", required = true) ScoreSource source,
            @JsonProperty(value = "evidenceRefs", required = true) List<String> evidenceRefs
    ) {
        public ComponentResult {
            id = ScoringValidation.identifier(id, "component id");
            if (earnedPoints < 0 || maxPoints <= 0 || earnedPoints > maxPoints
                    || maxPoints > ScoringContract.TOTAL_POINTS) {
                throw new IllegalArgumentException(
                        "component points must satisfy 0 <= earnedPoints <= maxPoints <= 100");
            }
            if (source == null) {
                throw new IllegalArgumentException("component source must not be null");
            }
            evidenceRefs = ScoringValidation.evidenceReferences(evidenceRefs, "component evidenceRefs");
        }
    }
}
