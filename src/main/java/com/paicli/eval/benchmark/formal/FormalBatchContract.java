package com.paicli.eval.benchmark.formal;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Frozen environment and symmetric-run contract. V3 retains three models; v4 registers two. */
public record FormalBatchContract(
        @JsonProperty(value = "contractVersion", required = true) int contractVersion,
        @JsonProperty(value = "format", required = true) String format,
        @JsonProperty(value = "executableSuiteContractSha256", required = true)
        String executableSuiteContractSha256,
        @JsonProperty(value = "freezeManifestSha256", required = true) String freezeManifestSha256,
        @JsonProperty(value = "contentTreeSha256", required = true) String contentTreeSha256,
        @JsonProperty(value = "suiteSha256", required = true) String suiteSha256,
        @JsonProperty(value = "validatorTreeSha256", required = true) String validatorTreeSha256,
        @JsonProperty(value = "candidateJarSha256", required = true) String candidateJarSha256,
        @JsonProperty(value = "candidateCommit", required = true) String candidateCommit,
        @JsonProperty(value = "runnerJarSha256", required = true) String runnerJarSha256,
        @JsonProperty(value = "runnerInventorySha256", required = true)
        String runnerInventorySha256,
        @JsonProperty(value = "runnerCommit", required = true) String runnerCommit,
        @JsonProperty(value = "workerImageId", required = true) String workerImageId,
        @JsonProperty(value = "verifierImageId", required = true) String verifierImageId,
        @JsonProperty(value = "dockerExecutablePath", required = true) String dockerExecutablePath,
        @JsonProperty(value = "dockerExecutableSha256", required = true) String dockerExecutableSha256,
        @JsonProperty(value = "models", required = true) List<ModelBinding> models,
        @JsonProperty(value = "repeats", required = true) int repeats,
        @JsonProperty(value = "caseOrder", required = true) List<String> caseOrder,
        @JsonProperty(value = "timezone", required = true) String timezone,
        @JsonProperty(value = "runtimeDate", required = true) String runtimeDate,
        @JsonProperty(value = "commonContextCapTokens", required = true) int commonContextCapTokens,
        @JsonProperty(value = "maxOutputTokensPerCall", required = true)
        int maxOutputTokensPerCall,
        @JsonProperty(value = "invalidRunPolicy", required = true) String invalidRunPolicy,
        @JsonProperty(value = "bestOfN", required = true) boolean bestOfN,
        @JsonProperty(value = "dirty", required = true) boolean dirty
) {
    public static final int LEGACY_VERSION = 3;
    public static final String LEGACY_FORMAT = "paicli-formal-batch-contract-v3";
    public static final int CURRENT_VERSION = 4;
    public static final String FORMAT = "paicli-formal-batch-contract-v4";
    public static final String INVALID_RUN_POLICY = "ALL_MODEL_SYMMETRIC_RERUN";
    public static final String TIMEZONE = "UTC";
    public static final int COMMON_CONTEXT_CAP_TOKENS = 1_000_000;
    public static final int MAX_OUTPUT_TOKENS_PER_CALL = 16_384;
    private static final List<ModelBinding> LEGACY_MODELS = List.of(
            new ModelBinding("deepseek", "deepseek-v4-flash"),
            new ModelBinding("hunyuan", "hy4-preview"),
            new ModelBinding("glm", "glm-5.3-flash"));
    private static final List<ModelBinding> CURRENT_MODELS = List.of(
            new ModelBinding("deepseek", "deepseek-v4-flash"),
            new ModelBinding("glm", "glm-5.3-flash"));
    // Local to this versioned contract: no coercion or parser changes for unrelated contracts.
    private static final ObjectMapper BATCH_MAPPER = FormalContractSupport.MAPPER.copy()
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);

    public FormalBatchContract {
        FormalContractSupport.requireVersionAndFormat(
                contractVersion, contractVersion == LEGACY_VERSION ? LEGACY_VERSION : CURRENT_VERSION, format,
                contractVersion == LEGACY_VERSION ? LEGACY_FORMAT : FORMAT);
        FormalContractSupport.requireSha256(
                executableSuiteContractSha256, "executableSuiteContractSha256");
        FormalContractSupport.requireSha256(freezeManifestSha256, "freezeManifestSha256");
        FormalContractSupport.requireSha256(contentTreeSha256, "contentTreeSha256");
        FormalContractSupport.requireSha256(suiteSha256, "suiteSha256");
        FormalContractSupport.requireSha256(validatorTreeSha256, "validatorTreeSha256");
        FormalContractSupport.requireSha256(candidateJarSha256, "candidateJarSha256");
        FormalContractSupport.requireCommitId(candidateCommit, "candidateCommit");
        FormalContractSupport.requireSha256(runnerJarSha256, "runnerJarSha256");
        FormalContractSupport.requireSha256(runnerInventorySha256, "runnerInventorySha256");
        FormalContractSupport.requireCommitId(runnerCommit, "runnerCommit");
        FormalContractSupport.requireImageId(workerImageId, "workerImageId");
        FormalContractSupport.requireImageId(verifierImageId, "verifierImageId");
        Path canonicalDocker = FormalContractSupport.requireCanonicalExecutable(dockerExecutablePath);
        dockerExecutablePath = canonicalDocker.toString();
        FormalContractSupport.requireSha256(dockerExecutableSha256, "dockerExecutableSha256");
        try {
            String actualDockerSha256 = FormalContractSupport.sha256(canonicalDocker);
            if (!actualDockerSha256.equals(dockerExecutableSha256)) {
                throw new IllegalArgumentException(
                        "dockerExecutableSha256 does not match dockerExecutablePath bytes");
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("cannot hash dockerExecutablePath", error);
        }
        models = models == null ? null : List.copyOf(models);
        List<ModelBinding> requiredModels = contractVersion == LEGACY_VERSION
                ? LEGACY_MODELS : CURRENT_MODELS;
        if (!requiredModels.equals(models)) {
            throw new IllegalArgumentException(
                    "models must exactly match the preregistered cohort and order for batch v" + contractVersion);
        }
        if (repeats != 3) {
            throw new IllegalArgumentException("formal batch repeats must equal 3");
        }
        caseOrder = validateCaseOrder(caseOrder);
        if (!TIMEZONE.equals(timezone)) {
            throw new IllegalArgumentException("formal batch timezone must be UTC");
        }
        requireIsoDate(runtimeDate);
        if (commonContextCapTokens != COMMON_CONTEXT_CAP_TOKENS) {
            throw new IllegalArgumentException(
                    "commonContextCapTokens must equal 1000000");
        }
        if (maxOutputTokensPerCall != MAX_OUTPUT_TOKENS_PER_CALL) {
            throw new IllegalArgumentException(
                    "maxOutputTokensPerCall must equal 16384");
        }
        if (!INVALID_RUN_POLICY.equals(invalidRunPolicy)) {
            throw new IllegalArgumentException(
                    "invalidRunPolicy must require all-model symmetric rerun");
        }
        if (bestOfN) {
            throw new IllegalArgumentException("bestOfN must be false for a formal batch");
        }
        if (dirty) {
            throw new IllegalArgumentException("dirty must be false for a formal batch");
        }
    }

    public static FormalBatchContract load(Path file) throws IOException {
        // Retain the shared bounded regular-file/duplicate/unknown/trailing safeguards, then
        // reject values such as version 4.0 or "4" instead of silently interpreting them as v4.
        JsonNode json = FormalContractSupport.load(file, JsonNode.class);
        if (json == null || !json.isObject()) throw new IOException("invalid formal batch contract root");
        try {
            return BATCH_MAPPER.treeToValue(json, FormalBatchContract.class);
        } catch (IOException | IllegalArgumentException error) {
            throw new IOException("invalid formal batch contract", error);
        }
    }

    /** Full registered Cartesian product, never a caller-selected executable subset. */
    public int expectedEpisodeCount() {
        return Math.multiplyExact(caseOrder.size(), Math.multiplyExact(models.size(), repeats));
    }

    /** Writes a new batch registration without replacing an existing registration. */
    public Path write(Path file) throws IOException {
        return FormalContractSupport.writeNew(file, this);
    }

    /** Ensures the pre-registered order is exactly the executable suite order. */
    public void validateAgainst(FinalExecutableSuiteContract executableSuite) {
        if (executableSuite == null || !caseOrder.equals(executableSuite.orderedCaseIds())) {
            throw new IllegalArgumentException(
                    "formal batch caseOrder does not match executable suite order");
        }
    }

    /** Validates both the registered contract bytes and its exact case order. */
    public FinalExecutableSuiteContract validateAgainst(Path executableSuiteContractFile)
            throws IOException {
        String actualSha256 = FormalContractSupport.sha256(executableSuiteContractFile);
        if (!executableSuiteContractSha256.equals(actualSha256)) {
            throw new IllegalArgumentException(
                    "executableSuiteContractSha256 does not match registered contract bytes");
        }
        FinalExecutableSuiteContract executableSuite =
                FinalExecutableSuiteContract.load(executableSuiteContractFile);
        validateAgainst(executableSuite);
        return executableSuite;
    }

    @Override
    public String toString() {
        Path docker = Path.of(dockerExecutablePath);
        String dockerName = docker.getFileName() == null ? "[executable]" : docker.getFileName().toString();
        return "FormalBatchContract[format=" + format
                + ", executableSuite=" + shortDigest(executableSuiteContractSha256)
                + ", freezeManifest=" + shortDigest(freezeManifestSha256)
                + ", candidateJar=" + shortDigest(candidateJarSha256)
                + ", runnerJar=" + shortDigest(runnerJarSha256)
                + ", runnerInventory=" + shortDigest(runnerInventorySha256)
                + ", workerImage=" + shortDigest(workerImageId.substring("sha256:".length()))
                + ", verifierImage=" + shortDigest(verifierImageId.substring("sha256:".length()))
                + ", docker=" + dockerName + "@" + shortDigest(dockerExecutableSha256)
                + ", models=" + models + ", repeats=" + repeats
                + ", cases=" + caseOrder.size() + ", runtimeDate=" + runtimeDate + "]";
    }

    private static List<String> validateCaseOrder(List<String> values) {
        if (values == null || values.size() != FinalExecutableSuiteContract.REQUIRED_CASE_COUNT) {
            throw new IllegalArgumentException("caseOrder must contain exactly 28 case ids");
        }
        List<String> copy = List.copyOf(values);
        Set<String> ids = new HashSet<>();
        for (String id : copy) {
            FormalContractSupport.requireSafeIdentifier(id, "caseOrder id");
            if (!ids.add(id)) {
                throw new IllegalArgumentException("duplicate caseOrder id: " + id);
            }
        }
        return copy;
    }

    private static void requireIsoDate(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("runtimeDate must not be blank");
        }
        try {
            if (!LocalDate.parse(value).toString().equals(value)) {
                throw new IllegalArgumentException("runtimeDate must be canonical ISO-8601 date");
            }
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException("runtimeDate must be canonical ISO-8601 date", error);
        }
    }

    private static String shortDigest(String digest) {
        return digest.substring(0, 12);
    }

    /** One exact provider/model binding. No endpoint or credential is part of this public contract. */
    public record ModelBinding(
            @JsonProperty(value = "provider", required = true) String provider,
            @JsonProperty(value = "model", required = true) String model) {
        public ModelBinding {
            FormalContractSupport.requireSafeIdentifier(provider, "provider");
            FormalContractSupport.requireSafeIdentifier(model, "model");
        }
    }
}
