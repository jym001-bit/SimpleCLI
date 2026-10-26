package com.paicli.eval.benchmark.formal;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pre-registered per-case execution contract for the 28-case private final suite.
 *
 * <p>Every execution-affecting value is case-local. A formal runner must consume these values
 * verbatim and must reject any global CLI override for mode, tool profile, budgets, mocks,
 * evidence or verifier selection.</p>
 */
public record FinalExecutableSuiteContract(
        @JsonProperty(value = "contractVersion", required = true) int contractVersion,
        @JsonProperty(value = "format", required = true) String format,
        @JsonProperty(value = "suiteId", required = true) String suiteId,
        @JsonProperty(value = "suiteVersion", required = true) String suiteVersion,
        @JsonProperty(value = "blueprintPath", required = true) String blueprintPath,
        @JsonProperty(value = "blueprintSha256", required = true) String blueprintSha256,
        @JsonProperty(value = "suitePath", required = true) String suitePath,
        @JsonProperty(value = "suiteSha256", required = true) String suiteSha256,
        @JsonProperty(value = "cases", required = true) List<CaseContract> cases
) {
    public static final int CURRENT_VERSION = 4;
    public static final String FORMAT = "paicli-final-executable-suite-contract-v4";
    public static final int REQUIRED_CASE_COUNT = 28;
    private static final Map<Level, Integer> REQUIRED_LEVEL_WEIGHTS = Map.of(
            Level.L1, 40,
            Level.L2, 36,
            Level.L3, 24);

    public FinalExecutableSuiteContract {
        FormalContractSupport.requireVersionAndFormat(
                contractVersion, CURRENT_VERSION, format, FORMAT);
        FormalContractSupport.requireSafeIdentifier(suiteId, "suiteId");
        FormalContractSupport.requireSafeIdentifier(suiteVersion, "suiteVersion");
        FormalContractSupport.requireRelativePath(blueprintPath, "blueprintPath");
        FormalContractSupport.requireSha256(blueprintSha256, "blueprintSha256");
        FormalContractSupport.requireRelativePath(suitePath, "suitePath");
        if (blueprintPath.equals(suitePath)) {
            throw new IllegalArgumentException("blueprintPath must differ from suitePath");
        }
        FormalContractSupport.requireSha256(suiteSha256, "suiteSha256");
        cases = cases == null ? null : List.copyOf(cases);
        validateCases(cases);
    }

    public static FinalExecutableSuiteContract load(Path file) throws IOException {
        return FormalContractSupport.load(file, FinalExecutableSuiteContract.class);
    }

    /** Writes a new contract without replacing an existing registration. */
    public Path write(Path file) throws IOException {
        return FormalContractSupport.writeNew(file, this);
    }

    public List<String> orderedCaseIds() {
        return cases.stream().map(CaseContract::id).toList();
    }

    public CaseContract requireCase(String id) {
        FormalContractSupport.requireSafeIdentifier(id, "case id");
        return cases.stream()
                .filter(definition -> definition.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown formal case: " + id));
    }

    private static void validateCases(List<CaseContract> definitions) {
        if (definitions == null || definitions.size() != REQUIRED_CASE_COUNT) {
            throw new IllegalArgumentException("formal suite must contain exactly 28 cases");
        }
        Set<String> ids = new HashSet<>();
        Set<String> scoringContractPaths = new HashSet<>();
        Set<String> assertionIds = new HashSet<>();
        Set<String> hardGateIds = new HashSet<>();
        EnumMap<Level, Integer> weights = new EnumMap<>(Level.class);
        for (Level level : Level.values()) {
            weights.put(level, 0);
        }
        int totalWeight = 0;
        for (CaseContract definition : definitions) {
            if (definition == null) {
                throw new IllegalArgumentException("formal suite cases must not contain null");
            }
            if (!ids.add(definition.id())) {
                throw new IllegalArgumentException("duplicate formal case id: " + definition.id());
            }
            if (!scoringContractPaths.add(definition.scoringContractPath())) {
                throw new IllegalArgumentException(
                        "duplicate scoring contract path: " + definition.scoringContractPath());
            }
            totalWeight = Math.addExact(totalWeight, definition.weight());
            weights.merge(definition.level(), definition.weight(), Math::addExact);
            for (String assertionId : definition.mandatoryAssertionIds()) {
                if (!assertionIds.add(assertionId)) {
                    throw new IllegalArgumentException(
                            "mandatory assertion id is reused across cases: " + assertionId);
                }
            }
            for (String hardGateId : definition.hardGateIds()) {
                if (!hardGateIds.add(hardGateId)) {
                    throw new IllegalArgumentException(
                            "hard gate id is reused across cases: " + hardGateId);
                }
            }
        }
        if (totalWeight != 100) {
            throw new IllegalArgumentException("formal case weights must sum to 100, got " + totalWeight);
        }
        if (!weights.equals(REQUIRED_LEVEL_WEIGHTS)) {
            throw new IllegalArgumentException(
                    "formal level weights must be L1=40, L2=36, L3=24, got " + weights);
        }
    }

    /** One immutable case-level execution selection; no field may be replaced by a batch CLI flag. */
    public record CaseContract(
            @JsonProperty(value = "id", required = true) String id,
            @JsonProperty(value = "category", required = true) String category,
            @JsonProperty(value = "level", required = true) Level level,
            @JsonProperty(value = "weight", required = true) int weight,
            @JsonProperty(value = "mode", required = true) Mode mode,
            @JsonProperty(value = "toolProfile", required = true) ToolProfile toolProfile,
            @JsonProperty(value = "timeoutSeconds", required = true) int timeoutSeconds,
            @JsonProperty(value = "tokenBudget", required = true) int tokenBudget,
            @JsonProperty(value = "hardMaxIterations", required = true) int hardMaxIterations,
            @JsonProperty(value = "stagnationWindow", required = true) int stagnationWindow,
            @JsonProperty(value = "mockProfile", required = true) String mockProfile,
            @JsonProperty(value = "episodeEvents", required = true) List<String> episodeEvents,
            @JsonProperty(value = "mandatoryAssertionIds", required = true)
            List<String> mandatoryAssertionIds,
            @JsonProperty(value = "hardGateIds", required = true) List<String> hardGateIds,
            @JsonProperty(value = "evidenceRequirements", required = true)
            List<String> evidenceRequirements,
            @JsonProperty(value = "scoringContractPath", required = true)
            String scoringContractPath,
            @JsonProperty(value = "scoringContractSha256", required = true)
            String scoringContractSha256,
            @JsonProperty(value = "verifierEntryPath", required = true) String verifierEntryPath,
            @JsonProperty(value = "verifierSha256", required = true) String verifierSha256,
            @JsonProperty(value = "verifierDependencyPaths", required = true)
            List<String> verifierDependencyPaths,
            @JsonProperty(value = "verifierBundleSha256", required = true)
            String verifierBundleSha256
    ) {
        public CaseContract {
            FormalContractSupport.requireSafeIdentifier(id, "case id");
            FormalContractSupport.requireSafeIdentifier(category, "case category");
            if (level == null || mode == null || toolProfile == null) {
                throw new IllegalArgumentException("case level, mode and toolProfile must not be null: " + id);
            }
            if (weight <= 0 || weight > 100) {
                throw new IllegalArgumentException("case weight must be between 1 and 100: " + id);
            }
            FormalContractSupport.requirePositive(timeoutSeconds, "timeoutSeconds");
            if (timeoutSeconds > 86_400) {
                throw new IllegalArgumentException("timeoutSeconds must not exceed 86400: " + id);
            }
            FormalContractSupport.requirePositive(tokenBudget, "tokenBudget");
            FormalContractSupport.requirePositive(hardMaxIterations, "hardMaxIterations");
            FormalContractSupport.requirePositive(stagnationWindow, "stagnationWindow");
            if (stagnationWindow > hardMaxIterations) {
                throw new IllegalArgumentException(
                        "stagnationWindow must not exceed hardMaxIterations: " + id);
            }
            FormalContractSupport.requireSafeIdentifier(mockProfile, "mockProfile");
            episodeEvents = FormalContractSupport.requireIdentifierList(
                    episodeEvents, "episodeEvents");
            mandatoryAssertionIds = FormalContractSupport.requireIdentifierList(
                    mandatoryAssertionIds, "mandatoryAssertionIds");
            hardGateIds = FormalContractSupport.requireIdentifierList(hardGateIds, "hardGateIds");
            evidenceRequirements = FormalContractSupport.requireIdentifierList(
                    evidenceRequirements, "evidenceRequirements");
            FormalContractSupport.requireRelativePath(scoringContractPath, "scoringContractPath");
            FormalContractSupport.requireSha256(
                    scoringContractSha256, "scoringContractSha256");
            FormalContractSupport.requireRelativePath(verifierEntryPath, "verifierEntryPath");
            if (scoringContractPath.equals(verifierEntryPath)) {
                throw new IllegalArgumentException(
                        "scoringContractPath must differ from verifierEntryPath: " + id);
            }
            if (!verifierEntryPath.startsWith("validators/")) {
                throw new IllegalArgumentException(
                        "verifierEntryPath must remain below validators/: " + id);
            }
            FormalContractSupport.requireSha256(verifierSha256, "verifierSha256");
            verifierDependencyPaths = requireSortedVerifierDependencies(
                    verifierDependencyPaths, verifierEntryPath, id);
            FormalContractSupport.requireSha256(
                    verifierBundleSha256, "verifierBundleSha256");
        }

        /** A case contract alone is data, not a 28-case admission or execution capability. */
        public static CaseContract load(Path file) throws IOException {
            return FormalContractSupport.load(file, CaseContract.class);
        }

        private static List<String> requireSortedVerifierDependencies(
                List<String> paths,
                String verifierEntryPath,
                String caseId) {
            if (paths == null || paths.isEmpty()) {
                throw new IllegalArgumentException(
                        "verifierDependencyPaths must not be empty: " + caseId);
            }
            List<String> copy = List.copyOf(paths);
            String previous = null;
            boolean containsEntry = false;
            for (String path : copy) {
                FormalContractSupport.requireRelativePath(path, "verifier dependency path");
                if (!path.startsWith("validators/")) {
                    throw new IllegalArgumentException(
                            "verifier dependency must remain below validators/: " + caseId);
                }
                if (previous != null && previous.compareTo(path) >= 0) {
                    throw new IllegalArgumentException(
                            "verifierDependencyPaths must be unique and strictly sorted: "
                                    + caseId);
                }
                if (verifierEntryPath.equals(path)) {
                    containsEntry = true;
                }
                previous = path;
            }
            if (!containsEntry) {
                throw new IllegalArgumentException(
                        "verifierDependencyPaths must include verifierEntryPath: " + caseId);
            }
            return copy;
        }
    }

    public enum Level {
        L1, L2, L3;

        @JsonCreator
        public static Level fromJson(String raw) {
            return parseEnum(Level.class, raw, "level");
        }

        @JsonValue
        public String toJson() {
            return name();
        }
    }

    public enum Mode {
        REACT, PLAN, TEAM;

        @JsonCreator
        public static Mode fromJson(String raw) {
            return parseEnum(Mode.class, raw, "mode");
        }

        @JsonValue
        public String toJson() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum ToolProfile {
        REASONING_ONLY, READ_ONLY, CODE_RAG, FILE_ONLY, LOCAL_COMMAND,
        MOCK_WEB, MOCK_BROWSER, MOCK_MCP, MOCK_MCP_FILE_ONLY;

        @JsonCreator
        public static ToolProfile fromJson(String raw) {
            return parseEnum(ToolProfile.class, raw, "toolProfile");
        }

        @JsonValue
        public String toJson() {
            return name();
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String label) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("unsupported " + label + ": " + raw, error);
        }
    }
}
