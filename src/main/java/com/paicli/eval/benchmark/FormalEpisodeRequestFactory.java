package com.paicli.eval.benchmark;

import com.paicli.eval.benchmark.formal.AttemptKey;
import com.paicli.eval.benchmark.formal.FinalExecutableSuiteContract;
import com.paicli.eval.benchmark.formal.FormalEpisodeOutcome;
import com.paicli.eval.benchmark.formal.FormalExecutionPlan;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Fail-closed construction of formal worker requests for supported execution contracts.
 *
 * <p>All evaluation-affecting values come from the retained execution plan and its canonical
 * episode. The only host input is a provider-bound credential. Runner-owned filesystem paths are
 * materialized separately from an {@link BenchmarkArtifactStore.EpisodeArtifacts}; they are not
 * CLI overrides and cannot change model, prompt, mode, tools, limits, date or timeout.</p>
 */
public final class FormalEpisodeRequestFactory {
    private static final Set<String> STANDARD_EVIDENCE = Set.of(
            "answer", "llm_metrics", "tool_events", "workspace_snapshot", "verifier_report");
    private static final List<String> STANDARD_LIFECYCLE_EVENTS = List.of(
            "worker_dispatch_started", "worker_dispatch_finished",
            "verifier_dispatch_started", "verifier_dispatch_finished");

    private FormalEpisodeRequestFactory() {
    }

    /**
     * Creates a non-publishable request without provider/worker calls; MCP captures its frozen host data.
     */
    public static PreparedRequest create(
            FormalExecutionPlan plan,
            FormalExecutionPlan.EpisodePlan episode,
            HostCredential credential) throws PreparationException {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(episode, "episode");
        Objects.requireNonNull(credential, "credential");
        AttemptKey key = validateCapabilities(plan, episode);
        FormalExecutionPlan.CasePlan casePlan = episode.casePlan();
        requireSupported(credential.provider().equals(episode.model().provider()), key,
                FormalEpisodeOutcome.EvaluationDefectCode.CREDENTIAL_PROVIDER_MISMATCH,
                "credential:provider-mismatch");
        requireSupported(endpointAllowed(episode.model().provider(), credential.baseUrl()), key,
                FormalEpisodeOutcome.EvaluationDefectCode.ENDPOINT_POLICY_MISMATCH,
                "credential:endpoint-policy-mismatch");

        int contextCap = plan.formalBatchContract().commonContextCapTokens();
        int outputCap = plan.formalBatchContract().maxOutputTokensPerCall();
        requireSupported(contextCap >= outputCap, key,
                FormalEpisodeOutcome.EvaluationDefectCode.UNSUPPORTED_OUTPUT_CAP,
                "plan:unsupported-output-cap");
        FormalBatchLimits limits = new FormalBatchLimits(
                casePlan.tokenBudget(), casePlan.hardMaxIterations(), casePlan.stagnationWindow(),
                contextCap, outputCap);
        return new PreparedRequest(key, episode.model().provider(), episode.model().model(),
                credential, casePlan.mode().toJson(),
                BenchmarkToolProfile.valueOf(casePlan.toolProfile().name()), limits,
                plan.formalBatchContract().runtimeDate(), casePlan.prompt(),
                casePlan.timeoutSeconds(), captureMock(casePlan, key), captureWeb(casePlan, key), capturePlan(casePlan, key), captureBoundary(casePlan, key), captureCommand(casePlan, key), captureInjection(casePlan, key));
    }

    /** Check the complete batch's runtime needs before even loading host credentials. */
    static AttemptKey validateCapabilities(FormalExecutionPlan plan,
            FormalExecutionPlan.EpisodePlan episode) throws PreparationException {
        AttemptKey key = AttemptKey.from(plan, episode, 1);
        requireCanonical(plan, episode, key);
        FormalExecutionPlan.CasePlan casePlan = episode.casePlan();
        requireSupported(Set.of(
                        FinalExecutableSuiteContract.ToolProfile.READ_ONLY,
                        FinalExecutableSuiteContract.ToolProfile.REASONING_ONLY,
                        FinalExecutableSuiteContract.ToolProfile.FILE_ONLY,
                        FinalExecutableSuiteContract.ToolProfile.LOCAL_COMMAND)
                        .contains(casePlan.toolProfile()) || FormalMockMcpBinding.supports(casePlan) || FormalMockWebBinding.supports(casePlan)
                        || FormalInjectionBinding.supports(casePlan),
                key,
                FormalEpisodeOutcome.EvaluationDefectCode.UNSUPPORTED_TOOL_PROFILE,
                "plan:unsupported-tool-profile");
        requireSupported(!casePlan.scoringContract().requiresJudge(), key,
                FormalEpisodeOutcome.EvaluationDefectCode.UNSUPPORTED_JUDGE,
                "plan:unsupported-judge");
        boolean mock = FormalMockMcpBinding.supports(casePlan);
        boolean web = FormalMockWebBinding.supports(casePlan);
        boolean boundPlan = FormalPlanBinding.supports(casePlan);
        boolean boundary = FormalBoundaryBinding.supports(casePlan);
        boolean command = FormalCommandBinding.supports(casePlan);
        boolean injection = FormalInjectionBinding.supports(casePlan);
        requireSupported(!"F3".equals(casePlan.id()) || injection, key,
                FormalEpisodeOutcome.EvaluationDefectCode.UNSUPPORTED_CASE, "plan:required-F3-binding-unavailable");
        requireSupported(!"F2".equals(casePlan.id()) || command, key,
                FormalEpisodeOutcome.EvaluationDefectCode.UNSUPPORTED_CASE, "plan:required-F2-binding-unavailable");
        requireSupported(!"F1".equals(casePlan.id()) || boundary, key,
                FormalEpisodeOutcome.EvaluationDefectCode.UNSUPPORTED_CASE, "plan:required-F1-binding-unavailable");
        requireSupported(!"E1".equals(casePlan.id()) || boundPlan, key,
                FormalEpisodeOutcome.EvaluationDefectCode.UNSUPPORTED_CASE, "plan:required-E1-binding-unavailable");
        requireSupported("none".equals(casePlan.mockProfile()) || mock || web || injection, key,
                FormalEpisodeOutcome.EvaluationDefectCode.UNSUPPORTED_MOCK,
                "plan:unsupported-mock");
        requireSupported(STANDARD_LIFECYCLE_EVENTS.equals(casePlan.episodeEvents()), key,
                FormalEpisodeOutcome.EvaluationDefectCode.UNSUPPORTED_EPISODE_EVENTS,
                "plan:unsupported-events");
        requireSupported(casePlan.evidenceRequirements().stream().allMatch(value ->
                        STANDARD_EVIDENCE.contains(value) || mock && ("mock_audit".equals(value)
                                || Set.of("D2", "D3", "F4").contains(casePlan.id()) && "mock_state".equals(value)
                                || Set.of("D3", "F4").contains(casePlan.id()) && "approval_relay".equals(value)
                                || "F4".equals(casePlan.id()) && "provider_turns".equals(value))
                                || web && Set.of("web_audit", "provider_turns").contains(value)
                                || boundPlan && Set.of("plan_audit", "scoped_request_fingerprints").contains(value)
                                || boundary && "boundary_state".equals(value)
                                || command && Set.of("command_audit", "provider_turns").contains(value)
                                || injection && Set.of("mock_audit", "mock_state", "provider_turns", "raw_tool_results", "stream_deltas").contains(value)), key,
                FormalEpisodeOutcome.EvaluationDefectCode.UNSUPPORTED_EVIDENCE,
                "plan:unsupported-evidence");
        captureMock(casePlan, key); // Entire batch is checked before any credentials are loaded.
        captureWeb(casePlan, key);
        capturePlan(casePlan, key);
        captureBoundary(casePlan, key);
        captureCommand(casePlan, key);
        captureInjection(casePlan, key);
        return key;
    }

    private static FormalInjectionBinding captureInjection(FormalExecutionPlan.CasePlan plan, AttemptKey key) throws PreparationException {
        if (!FormalInjectionBinding.supports(plan)) return null;
        try { return FormalInjectionBinding.capture(plan); }
        catch (IOException | IllegalArgumentException error) {
            throw defect(key, FormalEpisodeOutcome.EvaluationDefectCode.UNSUPPORTED_EVIDENCE, "injection:frozen-binding-rejected");
        }
    }

    private static FormalCommandBinding captureCommand(FormalExecutionPlan.CasePlan plan, AttemptKey key) throws PreparationException {
        if (!FormalCommandBinding.supports(plan)) return null;
        try { return FormalCommandBinding.capture(plan); }
        catch (IOException | IllegalArgumentException error) {
            throw defect(key, FormalEpisodeOutcome.EvaluationDefectCode.UNSUPPORTED_EVIDENCE, "command:frozen-binding-rejected");
        }
    }

    private static FormalBoundaryBinding captureBoundary(FormalExecutionPlan.CasePlan plan, AttemptKey key) throws PreparationException {
        if (!FormalBoundaryBinding.supports(plan)) return null;
        try { return FormalBoundaryBinding.capture(plan); }
        catch (IOException | IllegalArgumentException error) {
            throw defect(key, FormalEpisodeOutcome.EvaluationDefectCode.UNSUPPORTED_EVIDENCE, "boundary:frozen-binding-rejected");
        }
    }

    private static FormalPlanBinding capturePlan(FormalExecutionPlan.CasePlan plan, AttemptKey key) throws PreparationException {
        if (!FormalPlanBinding.supports(plan)) return null;
        try { return FormalPlanBinding.capture(plan); }
        catch (IOException | IllegalArgumentException error) {
            throw defect(key, FormalEpisodeOutcome.EvaluationDefectCode.UNSUPPORTED_EVIDENCE, "plan:frozen-binding-rejected");
        }
    }

    private static FormalMockWebBinding captureWeb(FormalExecutionPlan.CasePlan plan, AttemptKey key) throws PreparationException {
        if (!FormalMockWebBinding.supports(plan)) return null;
        try { return FormalMockWebBinding.capture(plan); }
        catch (IOException | IllegalArgumentException error) {
            throw defect(key, FormalEpisodeOutcome.EvaluationDefectCode.UNSUPPORTED_MOCK, "web:frozen-binding-rejected");
        }
    }

    private static FormalMockMcpBinding captureMock(FormalExecutionPlan.CasePlan plan, AttemptKey key)
            throws PreparationException {
        if (!FormalMockMcpBinding.supports(plan)) return null;
        try { return FormalMockMcpBinding.capture(plan); }
        catch (IOException | IllegalArgumentException error) {
            throw defect(key, FormalEpisodeOutcome.EvaluationDefectCode.UNSUPPORTED_MOCK,
                    "mock:frozen-binding-rejected");
        }
    }

    private static void requireCanonical(
            FormalExecutionPlan plan,
            FormalExecutionPlan.EpisodePlan episode,
            AttemptKey key) throws PreparationException {
        int index = episode.ordinal() - 1;
        if (index < 0 || index >= plan.episodes().size()
                || plan.episodes().get(index) != episode) {
            throw defect(key,
                    FormalEpisodeOutcome.EvaluationDefectCode.NON_CANONICAL_EPISODE,
                    "plan:non-canonical-episode");
        }
    }

    private static void requireSupported(
            boolean supported,
            AttemptKey key,
            FormalEpisodeOutcome.EvaluationDefectCode code,
            String evidence) throws PreparationException {
        if (!supported) {
            throw defect(key, code, evidence);
        }
    }

    private static PreparationException defect(
            AttemptKey key,
            FormalEpisodeOutcome.EvaluationDefectCode code,
            String evidence) {
        return new PreparationException(new FormalEpisodeOutcome.EvaluationDefect(
                key, code, List.of(evidence)));
    }

    private static boolean endpointAllowed(String provider, String baseUrl) {
        return switch (provider) {
            case "deepseek", "glm" -> baseUrl == null;
            case "hunyuan" -> baseUrl == null
                    || "https://tokenhub.tencentmaas.com/v1".equals(baseUrl);
            default -> false;
        };
    }

    /** Host-only provider credential. Its string representation never reveals endpoint or key. */
    public static final class HostCredential {
        private final String provider;
        private final String baseUrl;
        private final String apiKey;

        public HostCredential(String provider, String baseUrl, String apiKey) {
            this.provider = BenchmarkProtocol.normalizeProvider(provider);
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("host credential apiKey must not be blank");
            }
            this.apiKey = apiKey;
            this.baseUrl = validateBaseUrl(baseUrl);
        }

        public String provider() {
            return provider;
        }

        String baseUrl() {
            return baseUrl;
        }

        String apiKey() {
            return apiKey;
        }

        @Override
        public String toString() {
            return "HostCredential[provider=" + provider
                    + ", baseUrl=" + (baseUrl == null ? "<default>" : "<configured>")
                    + ", apiKey=" + SecretRedactor.REDACTED + "]";
        }

        private static String validateBaseUrl(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                URI uri = new URI(raw.trim());
                if (!"https".equalsIgnoreCase(uri.getScheme())
                        || uri.getHost() == null || uri.getHost().isBlank()
                        || uri.getUserInfo() != null || uri.getQuery() != null
                        || uri.getFragment() != null) {
                    throw new IllegalArgumentException(
                            "host credential baseUrl must be a credential-free HTTPS origin/path");
                }
                return uri.normalize().toString();
            } catch (URISyntaxException error) {
                throw new IllegalArgumentException("host credential baseUrl is invalid");
            }
        }
    }

    /** Plan-derived limits retained without exposing the package-private worker protocol type. */
    public record FormalBatchLimits(
            int tokenBudget,
            int hardMaxIterations,
            int stagnationWindow,
            int contextWindowCapTokens,
            int maxOutputTokensPerCall) {
        public FormalBatchLimits {
            validatedWorkerLimits(tokenBudget, hardMaxIterations, stagnationWindow,
                    contextWindowCapTokens, maxOutputTokensPerCall);
        }

        BenchmarkProtocol.AgentLimits workerLimits() {
            return validatedWorkerLimits(
                    tokenBudget, hardMaxIterations, stagnationWindow,
                    contextWindowCapTokens, maxOutputTokensPerCall);
        }

        private static BenchmarkProtocol.AgentLimits validatedWorkerLimits(
                int tokenBudget,
                int hardMaxIterations,
                int stagnationWindow,
                int contextWindowCapTokens,
                int maxOutputTokensPerCall) {
            return new BenchmarkProtocol.AgentLimits(
                    tokenBudget, hardMaxIterations, stagnationWindow,
                    contextWindowCapTokens, maxOutputTokensPerCall);
        }
    }

    /**
     * Immutable request configuration. The secret remains encapsulated until runner-owned paths
     * are supplied inside this package immediately before starting the worker.
     */
    public static final class PreparedRequest {
        private final AttemptKey key;
        private final String provider;
        private final String model;
        private final HostCredential credential;
        private final String mode;
        private final BenchmarkToolProfile toolProfile;
        private final FormalBatchLimits limits;
        private final String runtimeDate;
        private final String prompt;
        private final int timeoutSeconds;
        private final FormalMockMcpBinding mockBinding;
        private final FormalMockWebBinding webBinding;
        private final FormalPlanBinding planBinding;
        private final FormalBoundaryBinding boundaryBinding;
        private final FormalCommandBinding commandBinding;
        private final FormalInjectionBinding injectionBinding;

        private PreparedRequest(
                AttemptKey key,
                String provider,
                String model,
                HostCredential credential,
                String mode,
                BenchmarkToolProfile toolProfile,
                FormalBatchLimits limits,
                String runtimeDate,
                String prompt,
                int timeoutSeconds, FormalMockMcpBinding mockBinding, FormalMockWebBinding webBinding, FormalPlanBinding planBinding,
                FormalBoundaryBinding boundaryBinding, FormalCommandBinding commandBinding, FormalInjectionBinding injectionBinding) {
            this.key = Objects.requireNonNull(key, "key");
            this.provider = Objects.requireNonNull(provider, "provider");
            this.model = Objects.requireNonNull(model, "model");
            this.credential = Objects.requireNonNull(credential, "credential");
            this.mode = Objects.requireNonNull(mode, "mode");
            this.toolProfile = Objects.requireNonNull(toolProfile, "toolProfile");
            this.limits = Objects.requireNonNull(limits, "limits");
            this.runtimeDate = Objects.requireNonNull(runtimeDate, "runtimeDate");
            this.prompt = Objects.requireNonNull(prompt, "prompt");
            if (timeoutSeconds <= 0) {
                throw new IllegalArgumentException("timeoutSeconds must be positive");
            }
            this.timeoutSeconds = timeoutSeconds;
            this.mockBinding = mockBinding;
            this.webBinding = webBinding;
            this.planBinding = planBinding;
            this.boundaryBinding = boundaryBinding;
            this.commandBinding = commandBinding;
            this.injectionBinding = injectionBinding;
        }

        FormalMockMcpBinding mockBinding() { return mockBinding; }
        FormalMockWebBinding webBinding() { return webBinding; }
        FormalPlanBinding planBinding() { return planBinding; }
        FormalBoundaryBinding boundaryBinding() { return boundaryBinding; }
        FormalCommandBinding commandBinding() { return commandBinding; }
        FormalInjectionBinding injectionBinding() { return injectionBinding; }

        public AttemptKey key() {
            return key;
        }

        public String provider() {
            return provider;
        }

        public String model() {
            return model;
        }

        public String mode() {
            return mode;
        }

        public BenchmarkToolProfile toolProfile() {
            return toolProfile;
        }

        public FormalBatchLimits limits() {
            return limits;
        }

        public String runtimeDate() {
            return runtimeDate;
        }

        public String prompt() {
            return prompt;
        }

        public int timeoutSeconds() {
            return timeoutSeconds;
        }

        public boolean publishable() {
            return false;
        }

        BenchmarkProtocol.WorkerRequest toWorkerRequest(WorkerPaths paths) {
            Objects.requireNonNull(paths, "paths");
            return new BenchmarkProtocol.WorkerRequest(
                    BenchmarkProtocol.VERSION,
                    provider,
                    model,
                    credential.baseUrl(),
                    credential.apiKey(),
                    mode,
                    toolProfile,
                    limits.workerLimits(),
                    runtimeDate,
                    prompt,
                    paths.workspace().toString(),
                    paths.home().toString(),
                    paths.episodeDirectory().toString());
        }

        @Override
        public String toString() {
            return "PreparedRequest[key=" + key + ", provider=" + provider
                    + ", model=" + model + ", credential=" + credential
                    + ", mode=" + mode + ", toolProfile=" + toolProfile
                    + ", limits=" + limits + ", runtimeDate=" + runtimeDate
                    + ", prompt=<frozen>, timeoutSeconds=" + timeoutSeconds
                    + ", publishable=false]";
        }
    }

    /** Runner-owned directories; construction is only possible from an episode artifact handle. */
    static final class WorkerPaths {
        private final Path workspace;
        private final Path home;
        private final Path episodeDirectory;

        private WorkerPaths(Path workspace, Path home, Path episodeDirectory) {
            this.workspace = requireAbsolute(workspace, "workspace");
            this.home = requireAbsolute(home, "home");
            this.episodeDirectory = requireAbsolute(episodeDirectory, "episodeDirectory");
        }

        static WorkerPaths create(BenchmarkArtifactStore.EpisodeArtifacts episode)
                throws IOException {
            Objects.requireNonNull(episode, "episode");
            Path workspace = episode.createPrivateDirectory("workspace");
            Path home = episode.createPrivateDirectory("home");
            return new WorkerPaths(workspace, home, episode.directory());
        }

        Path workspace() {
            return workspace;
        }

        Path home() {
            return home;
        }

        Path episodeDirectory() {
            return episodeDirectory;
        }

        private static Path requireAbsolute(Path value, String label) {
            Objects.requireNonNull(value, label);
            Path normalized = value.toAbsolutePath().normalize();
            if (!value.isAbsolute() || !normalized.equals(value)) {
                throw new IllegalArgumentException(label + " must be absolute and normalized");
            }
            return value;
        }
    }

    /** Checked, typed preparation failure. It contains no credential or endpoint text. */
    @SuppressWarnings("serial") // In-process control flow; never crosses the worker wire.
    public static final class PreparationException extends Exception {
        private final FormalEpisodeOutcome.EvaluationDefect defect;

        private PreparationException(FormalEpisodeOutcome.EvaluationDefect defect) {
            super("formal episode preparation failed: " + defect.code());
            this.defect = Objects.requireNonNull(defect, "defect");
        }

        public FormalEpisodeOutcome.EvaluationDefect defect() {
            return defect;
        }
    }
}
