package com.paicli.eval.headless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.agent.Agent;
import com.paicli.agent.AgentOrchestrator;
import com.paicli.agent.ExecutionOutcome;
import com.paicli.agent.PlanExecuteAgent;
import com.paicli.config.PaiCliConfig;
import com.paicli.llm.LlmClient;
import com.paicli.llm.LlmClientFactory;
import com.paicli.observability.LangfuseTelemetry;
import com.paicli.runtime.CancellationContext;
import com.paicli.runtime.CancellationToken;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 非交互运行单个任务，把结果写成 JSON，并用退出码区分结束原因。
 *
 * <p>不经过 JLine/Renderer：输出全部丢进空流，只保留结果文件；对现有引擎零改动。</p>
 */
public final class HeadlessMain {

    public static final int EXIT_COMPLETED = 0;
    public static final int EXIT_BUDGET_EXHAUSTED = 2;
    public static final int EXIT_TIMEOUT = 3;
    public static final int EXIT_ERROR = 4;
    public static final int EXIT_USAGE = 5;
    public static final int EXIT_CANCELLED = 6;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HeadlessMain() {
    }

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (IllegalArgumentException e) {
            System.err.println("[headless] 参数错误: " + e.getMessage());
            System.err.println(HeadlessOptions.USAGE);
            code = EXIT_USAGE;
        } catch (Throwable t) {
            System.err.println("[headless] 启动失败: " + describe(t));
            code = EXIT_ERROR;
        }
        System.out.flush();
        System.err.flush();
        System.exit(code);
    }

    static int run(String[] args) throws IOException {
        HeadlessOptions options = HeadlessOptions.parse(args);
        if (options.help) {
            System.out.println(HeadlessOptions.USAGE);
            return EXIT_COMPLETED;
        }
        String prompt = options.resolvePrompt();
        Path out = (options.out == null ? Path.of("headless-result.json") : options.out)
                .toAbsolutePath().normalize();

        HeadlessResult result = execute(options, prompt);
        writeResult(out, result);
        System.out.printf(Locale.ROOT,
                "[headless] completed=%s reason=%s requests=%d tokens=%d/%d toolCalls=%d elapsed=%.2fs%n",
                result.completed(), result.exitReason(), result.requests(),
                result.inputTokens(), result.outputTokens(), result.toolCalls(), result.elapsedSeconds());
        return exitCodeFor(result.exitReason());
    }

    private static HeadlessResult execute(HeadlessOptions options, String prompt) {
        long startNanos = System.nanoTime();
        Path workdir = options.workdir.toAbsolutePath().normalize();

        LlmClient base;
        if (options.stub) {
            base = new StubLlmClient(options.stubAnswer, options.stubDelayMs, options.stubLoop, options.stubFail);
        } else {
            PaiCliConfig config = PaiCliConfig.load();
            base = options.provider == null
                    ? LlmClientFactory.createFromConfig(config)
                    : LlmClientFactory.create(options.provider, options.model, config);
        }
        if (base == null) {
            return new HeadlessResult(false, "error", 0, 0L, 0L, 0L,
                    elapsedSeconds(startNanos), 0, "", "未找到可用的 API Key", options.mode.name().toLowerCase(Locale.ROOT),
                    null, null, workdir.toString(), null);
        }

        CountingLlmClient client = new CountingLlmClient(base, options.maxRequests);
        HeadlessHitlHandler hitl = new HeadlessHitlHandler(options.approval == HeadlessOptions.Approval.AUTO);
        CountingToolRegistry registry = new CountingToolRegistry(hitl);
        registry.setProjectPath(workdir.toString());

        PrintStream quiet = new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8);
        CancellationToken token = CancellationContext.startRun();
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "paicli-headless-agent");
            thread.setDaemon(true);
            return thread;
        });

        String answer = "";
        String exitReason;
        String error = null;
        String traceName = options.traceName == null || options.traceName.isBlank()
                ? "eval-headless" : options.traceName;
        java.util.concurrent.atomic.AtomicReference<String> traceId = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            Future<ExecutionOutcome> future = executor.submit(() -> {
                if (!LangfuseTelemetry.enabled()) {
                    return runMode(options.mode, client, registry, prompt, quiet);
                }
                LangfuseTelemetry.TracedResult<ExecutionOutcome> traced =
                        LangfuseTelemetry.evaluate(traceName,
                                () -> runMode(options.mode, client, registry, prompt, quiet));
                traceId.set(traced.traceId());
                return traced.value();
            });
            ExecutionOutcome outcome = future.get(options.timeoutSeconds, TimeUnit.SECONDS);
            exitReason = exitReasonFor(outcome.reason());
            answer = outcome.content() == null ? "" : outcome.content();
            if (!"completed".equals(exitReason)) {
                error = answer.isBlank() ? "终止原因: " + outcome.reason() : answer;
            }
        } catch (TimeoutException e) {
            token.cancel();
            exitReason = "timeout";
            error = "超过 " + options.timeoutSeconds + "s 未结束";
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof BudgetExceededException budget) {
                exitReason = "budget_exhausted";
                error = budget.getMessage();
            } else {
                exitReason = "error";
                error = describe(cause == null ? e : cause);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exitReason = "error";
            error = "运行被中断";
        } finally {
            executor.shutdownNow();
            LangfuseTelemetry.shutdown();
        }

        return new HeadlessResult(
                "completed".equals(exitReason),
                exitReason,
                client.requests(),
                client.inputTokens(),
                client.outputTokens(),
                client.cachedInputTokens(),
                elapsedSeconds(startNanos),
                registry.executionCount(),
                answer,
                error,
                options.mode.name().toLowerCase(Locale.ROOT),
                base.getModelName(),
                base.getProviderName(),
                workdir.toString(),
                traceId.get()
        );
    }

    private static ExecutionOutcome runMode(HeadlessOptions.Mode mode, LlmClient client,
                                            CountingToolRegistry registry, String prompt, PrintStream quiet) {
        Agent reactAgent = new Agent(client, registry);
        reactAgent.setRenderer(new SilentRenderer(quiet));
        reactAgent.setReturnFinalResponseWhenStreamed(true);
        return switch (mode) {
            case REACT -> reactAgent.runWithOutcome(prompt);
            case PLAN -> new PlanExecuteAgent(
                    client,
                    registry,
                    reactAgent.getMemoryManager(),
                    (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                    quiet
            ).runWithOutcome(prompt);
            case TEAM -> new AgentOrchestrator(
                    client,
                    registry,
                    reactAgent.getMemoryManager(),
                    quiet
            ).runWithOutcome(prompt);
        };
    }

    /**
     * 把引擎的机器可读结束原因映射成评测口径：
     * completed / cancelled / llm_error / error / budget_exhausted / stopped。
     */
    private static String exitReasonFor(ExecutionOutcome.Reason reason) {
        if (reason == null) {
            return "error";
        }
        return switch (reason) {
            case COMPLETED -> "completed";
            case CANCELLED -> "cancelled";
            case MODEL_ERROR -> "error";
            case EXECUTION_ERROR -> "error";
            case TOKEN_BUDGET_EXCEEDED, MODEL_REQUEST_BUDGET_EXCEEDED -> "budget_exhausted";
            case HARD_ITERATION_LIMIT, STAGNATION_DETECTED -> "stopped";
        };
    }

    private static void writeResult(Path out, HeadlessResult result) throws IOException {
        Path parent = out.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), result);
    }

    private static double elapsedSeconds(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000_000.0;
    }

    private static int exitCodeFor(String exitReason) {
        return switch (exitReason) {
            case "completed" -> EXIT_COMPLETED;
            case "budget_exhausted" -> EXIT_BUDGET_EXHAUSTED;
            case "timeout" -> EXIT_TIMEOUT;
            case "cancelled" -> EXIT_CANCELLED;
            default -> EXIT_ERROR;
        };
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank()
                ? t.getClass().getSimpleName()
                : t.getClass().getSimpleName() + ": " + message;
    }
}
