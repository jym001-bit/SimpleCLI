package com.paicli.eval.benchmark.scoring;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Frozen, pre-registered scoring policy for one formal case.
 *
 * <p>The contract, not the candidate or judge, owns component maxima, assertion mappings,
 * strict-success threshold, and the exact accepted identifier sets.</p>
 */
public record ScoringContract(
        @JsonProperty(value = "schemaVersion", required = true) int schemaVersion,
        @JsonProperty(value = "caseId", required = true) String caseId,
        @JsonProperty(value = "strictSuccessMinimum", required = true) int strictSuccessMinimum,
        @JsonProperty(value = "assertions", required = true) List<AssertionRule> assertions,
        @JsonProperty(value = "hardGates", required = true) List<HardGateRule> hardGates,
        @JsonProperty(value = "components", required = true) List<ComponentRule> components,
        @JsonProperty(value = "verifierSha256", required = true) String verifierSha256,
        @JsonProperty(value = "toolchainSha256", required = true) String toolchainSha256
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int TOTAL_POINTS = 100;

    public ScoringContract {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported scoring contract schemaVersion: " + schemaVersion);
        }
        caseId = ScoringValidation.identifier(caseId, "caseId");
        if (strictSuccessMinimum < 0 || strictSuccessMinimum > TOTAL_POINTS) {
            throw new IllegalArgumentException("strictSuccessMinimum must be between 0 and 100");
        }
        assertions = ScoringValidation.nonEmptyCopy(assertions, "assertions");
        hardGates = ScoringValidation.copyAllowEmpty(hardGates, "hardGates");
        components = ScoringValidation.nonEmptyCopy(components, "components");
        ScoringValidation.requireUniqueIds(assertions, AssertionRule::id, "assertions");
        ScoringValidation.requireUniqueIds(hardGates, HardGateRule::id, "hardGates");
        ScoringValidation.requireUniqueIds(components, ComponentRule::id, "components");
        verifierSha256 = ScoringValidation.sha256(verifierSha256, "verifierSha256");
        toolchainSha256 = ScoringValidation.sha256(toolchainSha256, "toolchainSha256");

        Set<String> componentIds = new HashSet<>();
        int total = 0;
        for (ComponentRule component : components) {
            componentIds.add(component.id());
            total = Math.addExact(total, component.maxPoints());
        }
        if (total != TOTAL_POINTS) {
            throw new IllegalArgumentException("component maxPoints must sum to 100, got " + total);
        }
        for (AssertionRule assertion : assertions) {
            if (!componentIds.contains(assertion.componentId())) {
                throw new IllegalArgumentException(
                        "assertion references unknown component: " + assertion.id());
            }
        }
    }

    public static ScoringContract parse(String json) throws IOException {
        return ScoringJson.parse(json, ScoringContract.class);
    }

    public static ScoringContract load(Path file) throws IOException {
        return ScoringJson.load(file, ScoringContract.class);
    }

    public boolean requiresJudge() {
        return components.stream().anyMatch(component -> component.source() == ScoreSource.JUDGE);
    }

    public record AssertionRule(
            @JsonProperty(value = "id", required = true) String id,
            @JsonProperty(value = "componentId", required = true) String componentId,
            @JsonProperty(value = "mandatory", required = true) boolean mandatory
    ) {
        public AssertionRule {
            id = ScoringValidation.identifier(id, "assertion id");
            componentId = ScoringValidation.identifier(componentId, "assertion componentId");
        }
    }

    public record HardGateRule(
            @JsonProperty(value = "id", required = true) String id
    ) {
        public HardGateRule {
            id = ScoringValidation.identifier(id, "hard gate id");
        }
    }

    public record ComponentRule(
            @JsonProperty(value = "id", required = true) String id,
            @JsonProperty(value = "maxPoints", required = true) int maxPoints,
            @JsonProperty(value = "source", required = true) ScoreSource source
    ) {
        public ComponentRule {
            id = ScoringValidation.identifier(id, "component id");
            if (maxPoints <= 0 || maxPoints > TOTAL_POINTS) {
                throw new IllegalArgumentException("component maxPoints must be between 1 and 100");
            }
            if (source == null) {
                throw new IllegalArgumentException("component source must not be null");
            }
        }
    }
}
