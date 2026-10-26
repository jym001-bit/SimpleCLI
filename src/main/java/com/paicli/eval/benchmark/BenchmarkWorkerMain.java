package com.paicli.eval.benchmark;

import com.paicli.agent.Agent;
import com.paicli.agent.AgentOrchestrator;
import com.paicli.agent.PlanExecuteAgent;
import com.paicli.history.ConversationLedger;
import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.llm.DeepSeekClient;
import com.paicli.llm.GLMClient;
import com.paicli.llm.HunyuanClient;
import com.paicli.llm.LlmClient;
import com.paicli.memory.MemoryManager;
import com.paicli.prompt.PromptAssembler;
import com.paicli.render.Renderer;
import com.paicli.render.StatusInfo;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Entry point for one independent, isolated ReAct benchmark episode. */
public final class BenchmarkWorkerMain {
    private static final Pattern HTTP_STATUS = Pattern.compile(
            "(?i)(?:API(?:request)?(?:failed|\u8bf7\u6c42\u5931\u8d25)|HTTP)[^0-9]{0,20}([45][0-9]{2})");

    private BenchmarkWorkerMain() {
    }

    public static void main(String[] args) {
        PrintStream protocolOut = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8));

        BenchmarkProtocol.WorkerResponse response;
        byte[] requestBytes = null;
        try {
            requestBytes = System.in.readNBytes(BenchmarkProtocol.MAX_REQUEST_BYTES + 1);
            System.in.close();
            if (requestBytes.length > BenchmarkProtocol.MAX_REQUEST_BYTES) {
                response = BenchmarkProtocol.WorkerResponse.failure(
                        "INVALID_REQUEST", "worker request exceeded size limit", null);
            } else {
                BenchmarkProtocol.WorkerRequest request = BenchmarkProtocol.readRequest(requestBytes);
                response = execute(request);
            }
        } catch (IOException | IllegalArgumentException e) {
            response = BenchmarkProtocol.WorkerResponse.failure(
                    "INVALID_REQUEST", "invalid benchmark worker request", null);
        } catch (Exception e) {
            response = BenchmarkProtocol.WorkerResponse.failure(
                    e.getClass().getSimpleName(), "benchmark worker execution failed", null);
        } finally {
            if (requestBytes != null) {
                Arrays.fill(requestBytes, (byte) 0);
            }
        }

        try {
            protocolOut.write(BenchmarkProtocol.writeResponse(response));
            protocolOut.println();
            protocolOut.flush();
        } catch (IOException e) {
            // No safe protocol response can be emitted. The coordinator will classify the empty output as infra.
        }
    }

    static BenchmarkProtocol.WorkerResponse execute(BenchmarkProtocol.WorkerRequest request) {
        if (request.toolProfile() == BenchmarkToolProfile.MOCK_WEB) {
            return BenchmarkProtocol.WorkerResponse.failure(
                    "UNSUPPORTED_TOOL_PROFILE", "mock Web requires a bound Docker host relay", null);
        }
        if (request.toolProfile() == BenchmarkToolProfile.MOCK_MCP || request.toolProfile() == BenchmarkToolProfile.MOCK_MCP_FILE_ONLY) {
            return BenchmarkProtocol.WorkerResponse.failure(
                    "UNSUPPORTED_TOOL_PROFILE", "mock MCP requires a Docker host relay", null);
        }
        TracingLlmClient tracing = null;
        try {
            requireExplicitBudget("paicli.react.token.budget");
            requireExplicitBudget("paicli.react.hard.max.iterations");
            requireFrozenRuntimeContext(request);
            final SafePaths paths;
            try {
                paths = validatePaths(request);
            } catch (IOException e) {
                return BenchmarkProtocol.WorkerResponse.failure(
                        "RUNNER_IO_ERROR", safeExceptionMessage(e), null);
            }
            ContextWindowCappedLlmClient provider = ContextWindowCappedLlmClient.cap(
                    createClient(request),
                    request.agentLimits().contextWindowCapTokens(),
                    request.agentLimits().maxOutputTokensPerCall());
            tracing = new TracingLlmClient(provider, paths.episode().resolve("llm-trace.jsonl"));
            if (provider.effectiveContextWindowCapTokens()
                    != provider.requestedContextWindowCapTokens()) {
                return BenchmarkProtocol.WorkerResponse.failure(
                        "CONTEXT_CAP_UNAVAILABLE",
                        "provider context capacity is below the requested benchmark cap",
                        tracing.metrics());
            }

            BenchmarkToolRegistry tools = new BenchmarkToolRegistry(request.toolProfile());
            tools.setProjectPath(paths.workspace().toString());
            tools.setSanitizeCommandEnvironment(true);
            if (request.toolProfile().commandSandboxRequired()) {
                try {
                    tools.setCommandSandboxRoot(paths.workspace());
                } catch (IllegalStateException e) {
                    return BenchmarkProtocol.WorkerResponse.failure(
                            "RUNNER_SANDBOX_UNAVAILABLE", "command sandbox unavailable", tracing.metrics());
                }
            }

            ConversationLedger ledger = ConversationLedger.open(
                    paths.episode().resolve("conversation"), "benchmark-episode");
            String answer = executeMode(request, tracing, tools, ledger);
            TracingLlmClient.Metrics metrics = tracing.metrics();
            String invalidEvidenceType = tracing.evaluationInvalidFailureType();
            if (invalidEvidenceType.isBlank()) {
                invalidEvidenceType = BenchmarkProviderEvidenceGate
                        .invalidEvaluationFailureType(request, metrics);
            }
            if (invalidEvidenceType != null && !invalidEvidenceType.isBlank()) {
                return BenchmarkProtocol.WorkerResponse.failure(
                        invalidEvidenceType,
                        "provider evidence is incomplete, so the evaluation episode is invalid",
                        metrics);
            }
            if (tracing.failedCalls() > 0) {
                String errorType = tracing.lastFailureType().isBlank()
                        ? isConfirmedTransientProviderFailure(tracing.lastErrorMessage())
                                ? "PROVIDER_TRANSIENT"
                                : "LLM_API_ERROR"
                        : tracing.lastFailureType();
                return BenchmarkProtocol.WorkerResponse.failure(
                        errorType,
                        answer == null || answer.isBlank() ? "LLM call failed" : answer,
                        metrics);
            }
            String evidenceFailureType =
                    BenchmarkProviderEvidenceGate.failureType(request, metrics);
            if (evidenceFailureType != null) {
                return BenchmarkProtocol.WorkerResponse.failure(
                        evidenceFailureType,
                        "provider identity, usage, context cap, or request fingerprint evidence is incomplete",
                        metrics);
            }
            return BenchmarkProtocol.WorkerResponse.success(answer, metrics);
        } catch (Exception e) {
            TracingLlmClient.Metrics metrics = tracing == null ? null : tracing.metrics();
            return BenchmarkProtocol.WorkerResponse.failure(
                    e.getClass().getSimpleName(), safeExceptionMessage(e), metrics);
        }
    }

    private static String executeMode(BenchmarkProtocol.WorkerRequest request,
                                      TracingLlmClient tracing,
                                      BenchmarkToolRegistry tools,
                                      ConversationLedger ledger) {
        return switch (request.mode().trim().toLowerCase(Locale.ROOT)) {
            case "react" -> {
                Agent agent = new Agent(tracing, tools);
                agent.setConversationLedger(ledger);
                agent.setRenderer(new SilentRenderer());
                agent.setReturnFinalResponseWhenStreamed(true);
                agent.setExternalContextSupplier(tools::promptPolicy);
                yield agent.runExplicitTask(request.prompt(), request.prompt());
            }
            case "plan" -> {
                PlanExecuteAgent agent = new PlanExecuteAgent(
                        tracing,
                        tools,
                        null,
                        (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                        silentPrintStream());
                agent.setConversationLedger(ledger);
                agent.setExternalContextSupplier(tools::promptPolicy);
                yield agent.runExplicitTask(request.prompt(), request.prompt());
            }
            case "team" -> {
                AgentOrchestrator agent = new AgentOrchestrator(
                        tracing,
                        tools,
                        new MemoryManager(tracing),
                        silentPrintStream());
                agent.setConversationLedger(ledger);
                agent.setExternalContextSupplier(tools::promptPolicy);
                yield agent.runExplicitTask(request.prompt(), request.prompt());
            }
            default -> throw new IllegalArgumentException(
                    "unsupported benchmark mode: " + request.mode());
        };
    }

    private static PrintStream silentPrintStream() {
        return new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8);
    }

    private static LlmClient createClient(BenchmarkProtocol.WorkerRequest request) {
        String provider = BenchmarkProtocol.normalizeProvider(request.provider());
        String baseUrl = request.baseUrl();
        return switch (provider) {
            case "deepseek" -> {
                rejectCustomBaseUrl(provider, baseUrl);
                yield new DeepSeekClient(
                        request.apiKey(), request.model(),
                        request.agentLimits().maxOutputTokensPerCall());
            }
            case "glm" -> {
                rejectCustomBaseUrl(provider, baseUrl);
                yield new GLMClient(
                        request.apiKey(), request.model(),
                        request.agentLimits().maxOutputTokensPerCall());
            }
            case "hunyuan" -> new HunyuanClient(
                    request.apiKey(), request.model(), baseUrl,
                    request.agentLimits().maxOutputTokensPerCall());
            default -> throw new IllegalArgumentException("unsupported benchmark provider: " + provider);
        };
    }

    private static SafePaths validatePaths(BenchmarkProtocol.WorkerRequest request) throws IOException {
        Path episode = safeDirectory(Path.of(request.episodeDirectory()), "episodeDirectory");
        Path workspace = safeDirectory(Path.of(request.workspace()), "workspace");
        Path home = safeDirectory(Path.of(request.home()), "home");
        if (!workspace.startsWith(episode) || !home.startsWith(episode)) {
            throw new IOException("worker workspace and home must be inside the episode directory");
        }
        Path processDirectory = Path.of(System.getProperty("user.dir")).toRealPath();
        if (!Files.isSameFile(processDirectory, workspace)) {
            throw new IOException("worker JVM cwd does not match the isolated workspace");
        }
        Path configuredHome = Path.of(System.getProperty("user.home")).toRealPath();
        if (!Files.isSameFile(configuredHome, home)) {
            throw new IOException("worker JVM user.home does not match the isolated home");
        }
        if (!"UTC".equals(java.util.TimeZone.getDefault().getID())) {
            throw new IOException("worker JVM timezone must be UTC");
        }
        return new SafePaths(episode, workspace, home);
    }

    private static Path safeDirectory(Path raw, String label) throws IOException {
        if (!raw.isAbsolute()) {
            throw new IOException(label + " must be absolute");
        }
        Path normalized = raw.normalize();
        if (Files.isSymbolicLink(normalized)
                || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " is not a safe directory");
        }
        return normalized.toRealPath();
    }

    private static void requireExplicitBudget(String key) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("worker requires explicit " + key);
        }
        try {
            if (Integer.parseInt(raw) <= 0) {
                throw new IllegalStateException("worker budget must be positive: " + key);
            }
        } catch (NumberFormatException e) {
            throw new IllegalStateException("worker budget must be an integer: " + key, e);
        }
    }

    private static void requireFrozenRuntimeContext(BenchmarkProtocol.WorkerRequest request) {
        String actualDate = System.getProperty(PromptAssembler.RUNTIME_DATE_PROPERTY, "");
        String actualZone = System.getProperty(PromptAssembler.RUNTIME_ZONE_PROPERTY, "");
        if (!request.runtimeDate().equals(actualDate) || !"UTC".equals(actualZone)) {
            throw new IllegalStateException("worker prompt runtime context does not match request");
        }
    }

    private static void rejectCustomBaseUrl(String provider, String baseUrl) {
        if (baseUrl != null && !baseUrl.isBlank()) {
            throw new IllegalArgumentException(
                    provider + " benchmark uses the provider client's fixed official endpoint");
        }
    }

    private static String safeExceptionMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        return SecretRedactor.redact(message);
    }

    private static boolean isConfirmedTransientProviderFailure(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        Matcher matcher = HTTP_STATUS.matcher(message);
        while (matcher.find()) {
            int status = Integer.parseInt(matcher.group(1));
            if (status == 429 || status >= 500) {
                return true;
            }
        }
        return false;
    }

    private record SafePaths(Path episode, Path workspace, Path home) {
    }

    private static final class SilentRenderer implements Renderer {
        private final PrintStream sink = new PrintStream(
                OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8);

        @Override
        public void start() {
        }

        @Override
        public void close() {
            sink.close();
        }

        @Override
        public PrintStream stream() {
            return sink;
        }

        @Override
        public boolean rendersReasoning() {
            return false;
        }

        @Override
        public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) {
        }

        @Override
        public void appendDiff(String filePath, String before, String after) {
        }

        @Override
        public void updateStatus(StatusInfo status) {
        }

        @Override
        public ApprovalResult promptApproval(ApprovalRequest request) {
            return ApprovalResult.reject("benchmark worker has no interactive approval channel");
        }

        @Override
        public int openPalette(String title, List<String> items) {
            return -1;
        }
    }
}
