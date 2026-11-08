package com.paicli.agent;
import com.paicli.observability.LangfuseTelemetry;

import com.paicli.history.ConversationLedger;
import com.paicli.llm.LlmClient;
import com.paicli.memory.MemoryManager;
import com.paicli.memory.TokenBudget;
import com.paicli.runtime.CancellationContext;
import com.paicli.runtime.CancellationToken;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.TurnToolPolicy;
import com.paicli.util.AnsiStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.paicli.agent.TeamExecutionObserver.*;

/**
 * Agent 编排器 - Multi-Agent 系统的"主"
 *
 * 负责管理团队、分配任务、路由消息、解决冲突。
 * 采用主从架构：编排器是主，子代理是从。
 *
 * 协作流程：
 * 1. 用户提交任务 -> 编排器交给规划者
 * 2. 规划者拆解任务 -> 编排器解析计划
 * 3. 编排器按依赖顺序将子任务分配给执行者
 * 4. 执行者返回结果 -> 编排器交给检查者
 * 5. 检查者通过则完成，否则带上反馈重新分配给执行者
 * 6. 所有子任务完成后，编排器汇总返回最终结果
 *
 * 并行策略：
 * - 同一依赖批次内部 **并行** 执行（最多 Worker 池大小并发，默认 2）
 * - 每个并行步骤使用独立的 PrintStream 缓冲流式输出，批次结束后按 step_id 顺序 flush 到 stdout，
 *   避免多线程写同一个终端流造成交错，同时仍让用户看到结构化的执行过程
 * - 单步批次仍走直连流式路径，保持"实时打字"的观感
 * - Worker 通过 {@link java.util.concurrent.BlockingQueue} 池化分配，确保同一 Worker 不会被两个步骤并发占用
 * - Reviewer 在并行路径中按步骤即时创建独立实例，避免对话历史竞争
 */
public class AgentOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);
    private static final int MAX_RETRIES_PER_STEP = 2;

    private final LlmClient llmClient;
    private final SubAgent planner;
    private final List<SubAgent> workers;
    private final SubAgent reviewer;
    private final MemoryManager memoryManager;
    private final ToolRegistry toolRegistry;
    private final PrintStream out;
    private ConversationLedger conversationLedger = ConversationLedger.disabled();
    private Supplier<String> externalContextSupplier = () -> "";
    private volatile TeamExecutionObserver executionObserver;
    private final AtomicLong executionObservationFailures = new AtomicLong();
    private final AtomicBoolean runActive = new AtomicBoolean();
    private volatile ExecutorService cancellationCleanup;

    /** Optional, process-local diagnostics only; callbacks may run concurrently. */
    public void setExecutionObserver(TeamExecutionObserver observer) { this.executionObserver = observer; }

    public TeamExecutionObserver getExecutionObserver() { return executionObserver; }

    /** Number of runs whose observer failed; not a product execution failure count. */
    public long getExecutionObservationFailures() { return executionObservationFailures.get(); }

    // 执行步骤的数据结构（package-private 供测试访问）
    record ExecutionStep(String id, String description, String type,
                                  List<String> dependencies, String result,
                                  StepStatus status, ExecutionOutcome.Reason reason, String error) {
        ExecutionStep(String id, String description, String type, List<String> dependencies,
                      String result, StepStatus status) {
            this(id, description, type, dependencies, result, status,
                    status == StepStatus.FAILED ? ExecutionOutcome.Reason.EXECUTION_ERROR
                            : ExecutionOutcome.Reason.COMPLETED);
        }
        ExecutionStep(String id, String description, String type, List<String> dependencies,
                      String result, StepStatus status, ExecutionOutcome.Reason reason) {
            this(id, description, type, dependencies, result, status, reason, null);
        }
        static ExecutionStep pending(String id, String description, String type, List<String> dependencies) {
            return new ExecutionStep(id, description, type, dependencies, null, StepStatus.PENDING);
        }

        ExecutionStep withResult(String result) {
            return new ExecutionStep(id, description, type, dependencies, result, StepStatus.COMPLETED);
        }

        ExecutionStep withFailed(String result) {
            return new ExecutionStep(id, description, type, dependencies, result, StepStatus.FAILED);
        }

        ExecutionStep withFailed(String result, ExecutionOutcome.Reason reason) {
            return new ExecutionStep(id, description, type, dependencies, result, StepStatus.FAILED, reason);
        }

        ExecutionStep started() {
            return new ExecutionStep(id, description, type, dependencies, result, StepStatus.RUNNING);
        }
    }

    enum StepStatus {
        PENDING, RUNNING, COMPLETED, FAILED
    }

    public AgentOrchestrator(LlmClient llmClient) {
        this(llmClient, new ToolRegistry(), new MemoryManager(llmClient));
    }

    public AgentOrchestrator(LlmClient llmClient, ToolRegistry toolRegistry) {
        this(llmClient, toolRegistry, new MemoryManager(llmClient));
    }

    public AgentOrchestrator(LlmClient llmClient, ToolRegistry toolRegistry, MemoryManager memoryManager) {
        this(llmClient, toolRegistry, memoryManager, System.out);
    }

    public AgentOrchestrator(LlmClient llmClient, ToolRegistry toolRegistry,
                             MemoryManager memoryManager, PrintStream out) {
        this.llmClient = llmClient;
        this.out = out == null ? System.out : out;
        this.toolRegistry = toolRegistry;
        this.toolRegistry.setContextProfile(memoryManager.getContextProfile());
        this.toolRegistry.setCurrentModel(llmClient.getProviderName(), llmClient.getModelName());
        memoryManager.setProjectPath(this.toolRegistry.getProjectPath());
        memoryManager.setExternalContextTracker(this.toolRegistry.getExternalContextTracker());
        this.toolRegistry.setMemoryWriter((fact, scope, replaceId, keepBoth) ->
                memoryManager.storeFact(fact, scope, replaceId, keepBoth).describe());
        this.planner = new SubAgent("planner", AgentRole.PLANNER, llmClient, toolRegistry);
        this.workers = List.of(
                new SubAgent("worker-1", AgentRole.WORKER, llmClient, toolRegistry),
                new SubAgent("worker-2", AgentRole.WORKER, llmClient, toolRegistry)
        );
        this.reviewer = new SubAgent("reviewer", AgentRole.REVIEWER, llmClient, toolRegistry);
        this.memoryManager = memoryManager;
    }

    public void setExternalContextSupplier(Supplier<String> externalContextSupplier) {
        this.externalContextSupplier = externalContextSupplier == null ? () -> "" : externalContextSupplier;
        planner.setExternalContextSupplier(this.externalContextSupplier);
        workers.forEach(worker -> worker.setExternalContextSupplier(this.externalContextSupplier));
        reviewer.setExternalContextSupplier(this.externalContextSupplier);
    }

    /**
     * 把 Skill 索引下发给所有 SubAgent。load_skill 的正文由各 SubAgent 在自己的工具循环里
     * 紧跟工具结果注入，角色之间不共享待注入状态。
     */
    public void setSkillSystem(com.paicli.skill.SkillRegistry skillRegistry) {
        planner.setSkillRegistry(skillRegistry);
        for (SubAgent worker : workers) {
            worker.setSkillRegistry(skillRegistry);
        }
        reviewer.setSkillRegistry(skillRegistry);
    }

    public void setConversationLedger(ConversationLedger conversationLedger) {
        this.conversationLedger = conversationLedger == null
                ? ConversationLedger.disabled()
                : conversationLedger;
        planner.setConversationLedger(this.conversationLedger);
        workers.forEach(worker -> worker.setConversationLedger(this.conversationLedger));
        reviewer.setConversationLedger(this.conversationLedger);
    }

    /**
     * 运行多 Agent 协作任务
     */
    public String run(String userInput) {
        return run(userInput, userInput);
    }

    /** Use submittedUserInput for policy decisions and userInput for expanded task context. */
    public String run(String userInput, String submittedUserInput) {
        return runInternal(userInput, submittedUserInput, false, false).displayContent();
    }

    public ExecutionOutcome runWithOutcome(String userInput) { return runWithOutcome(userInput, userInput); }

    public ExecutionOutcome runWithOutcome(String userInput, String submittedUserInput) {
        return runWithOutcome(userInput, TaskContract.understand(submittedUserInput));
    }

    public ExecutionOutcome runWithOutcome(String userInput, TaskContract contract) {
        return runInternal(userInput, contract.goal(), false, true, contract);
    }

    /**
     * Runs an input that a trusted caller has already classified as an explicit task envelope.
     * URL provenance and no-web rules still come from {@code submittedUserInput}; only the
     * bare-title actionability heuristic is bypassed.
     */
    public String runExplicitTask(String userInput, String submittedUserInput) {
        return runInternal(userInput, submittedUserInput, true, false).displayContent();
    }

    public ExecutionOutcome runExplicitTaskWithOutcome(String userInput, String submittedUserInput) {
        return runInternal(userInput, submittedUserInput, true, true,
                TaskContract.understand(submittedUserInput));
    }

    private ExecutionOutcome runInternal(String userInput,
                               String submittedUserInput,
                               boolean explicitTaskEnvelope, boolean captureErrors) {
        return runInternal(userInput, submittedUserInput, explicitTaskEnvelope, captureErrors,
                TaskContract.understand(submittedUserInput));
    }

    private ExecutionOutcome runInternal(String userInput,
                               String submittedUserInput,
                               boolean explicitTaskEnvelope, boolean captureErrors,
                               TaskContract contract) {
        if (!runActive.compareAndSet(false, true)) return new ExecutionOutcome(ExecutionOutcome.Reason.EXECUTION_ERROR,
                "⏹️ 当前 Team 任务仍在执行或清理，请稍后再试。");
        try {
            ExecutorService previous = cancellationCleanup;
            if (previous != null && !previous.isTerminated()) {
                return new ExecutionOutcome(ExecutionOutcome.Reason.EXECUTION_ERROR, "⏹️ 上一次 Team 任务仍在清理，请稍后再试。");
            }
            CancellationToken callerToken = CancellationContext.currentThreadToken();
            try (CancellationContext.Scope ignored = CancellationContext.bind(
                    callerToken == null ? new CancellationToken() : callerToken)) {
                ExecutionOutcome outcome = LangfuseTelemetry.observeTurn("team",
                        conversationLedger.isEnabled() ? conversationLedger.sessionId() : null,
                        () -> runTraced(userInput, submittedUserInput, explicitTaskEnvelope, captureErrors, contract));
                return outcome.acceptance() == null ? new ExecutionOutcome(outcome.reason(), outcome.content(),
                        outcome.displayContent(), TaskAcceptance.notEvaluated()) : outcome;
            }
        } finally { runActive.set(false); }
    }

    private ExecutionOutcome runTraced(String userInput,
                             String submittedUserInput,
                             boolean explicitTaskEnvelope, boolean captureErrors, TaskContract contract) {
        TeamObservation observation = new TeamObservation(executionObserver,
                TaskAcceptance.fromContract(contract, toolRegistry.getProjectPath()));
        observation.emit(() -> new RunStarted(observation.runId, observation.now(),
                TextFingerprint.of(userInput), TextFingerprint.of(submittedUserInput),
                explicitTaskEnvelope, workers.size(), MAX_RETRIES_PER_STEP));
        String result = null;
        Throwable failure = null;
        try {
            ExecutionOutcome outcome = runObserved(userInput, submittedUserInput, explicitTaskEnvelope, observation);
            result = outcome.displayContent();
            if (observation.runExitReason == RunExitReason.COMPLETED
                    && !CancellationContext.isCancelled()) {
                int saved = memoryManager.extractFactsFromUserTurn(submittedUserInput).size();
                if (saved > 0) {
                    out.println("💾 自动提取并保存 " + saved
                            + " 条项目事实（待核实；可用 /memory list 查看和删除）。");
                }
            }
            return new ExecutionOutcome(outcome.reason(), outcome.content(), outcome.displayContent(),
                    outcome.completed() ? observation.acceptance.verify() : TaskAcceptance.notEvaluated());
        } catch (RuntimeException e) {
            failure = e;
            if (!captureErrors) throw e;
            String partial = observation.steps.isEmpty() ? "" : buildFinalResult(observation.steps, true);
            return new ExecutionOutcome(CancellationContext.isCancelled() ? ExecutionOutcome.Reason.CANCELLED
                    : ExecutionOutcome.Reason.EXECUTION_ERROR, "❌ 执行失败: " + e.getMessage() + "\n" + partial);
        } catch (Error e) {
            failure = e;
            throw e;
        } finally {
            observation.finish(result, failure);
        }
    }

    private ExecutionOutcome runObserved(String userInput, String submittedUserInput,
                               boolean explicitTaskEnvelope, TeamObservation observation) {
        log.info("Multi-Agent run started: inputLength={}", userInput == null ? 0 : userInput.length());
        TurnToolPolicy turnToolPolicy = explicitTaskEnvelope
                ? TurnToolPolicy.forExplicitTask(
                        submittedUserInput,
                        toolRegistry.isSharedBrowserSession(),
                        toolRegistry.hasAgentOwnedCurrentBrowserPage())
                : TurnToolPolicy.fromUserInput(
                        submittedUserInput,
                        toolRegistry.isSharedBrowserSession(),
                        toolRegistry.hasAgentOwnedCurrentBrowserPage());
        planner.setTurnToolPolicy(turnToolPolicy);
        workers.forEach(worker -> worker.setTurnToolPolicy(turnToolPolicy));
        reviewer.setTurnToolPolicy(turnToolPolicy);
        conversationLedger.appendMessage(
                "team",
                "orchestrator",
                "user_input",
                LlmClient.Message.user(userInput));
        if (CancellationContext.isCancelled()) {
            observation.runExitReason = RunExitReason.CANCELLED;
            conversationLedger.appendEvent(
                    "run_cancelled", "team", "orchestrator", "before_planning", Map.of());
            return new ExecutionOutcome(ExecutionOutcome.Reason.CANCELLED, "⏹️ 已取消当前多 Agent 任务。");
        }

        // 1. 规划阶段：让规划者拆解任务
        out.println(AnsiStyle.heading("📋 第一阶段：规划"));
        out.println("🧑‍💼 规划者正在分析任务...\n");

        AgentMessage planMessage = AgentMessage.task("orchestrator",
                "请为以下任务制定执行计划：\n" + userInput);
        ActivationObservation planActivation = observation.activation(planner, null, 1);
        AgentMessage planResult = planner.execute(planMessage, out, planActivation);
        planner.clearHistory();
        if (CancellationContext.isCancelled()) {
            observation.runExitReason = RunExitReason.CANCELLED;
            return new ExecutionOutcome(ExecutionOutcome.Reason.CANCELLED, "⏹️ 已取消当前多 Agent 任务。");
        }

        if (planResult.type() == AgentMessage.Type.ERROR) {
            observation.runExitReason = RunExitReason.PLAN_ERROR;
            return new ExecutionOutcome(planResult.outcome() == null ? ExecutionOutcome.Reason.MODEL_ERROR
                    : planResult.outcome().reason(), "❌ 规划阶段失败，规划者 LLM 调用出错：" + planResult.content());
        }
        if (planResult.outcome() != null && !planResult.outcome().completed()) {
            observation.runExitReason = RunExitReason.PLAN_ERROR;
            return new ExecutionOutcome(planResult.outcome().reason(), "❌ 规划阶段未完成：" + planResult.content());
        }
        if (planResult.content() == null || planResult.content().isBlank()) {
            observation.runExitReason = RunExitReason.PLAN_EMPTY;
            return new ExecutionOutcome(ExecutionOutcome.Reason.EXECUTION_ERROR, "❌ 规划失败：规划者未能生成有效计划");
        }

        // 2. 解析计划
        List<ExecutionStep> steps = new CopyOnWriteArrayList<>(parsePlan(planResult.content()));
        observation.emit(() -> new PlanPrepared(observation.runId, observation.now(),
                ActivationObservation.id(planActivation), TextFingerprint.of(planResult.content()),
                steps.stream().map(step -> new StepNode(step.id(), TextFingerprint.of(step.description()),
                        TextFingerprint.of(step.type()), step.dependencies().stream()
                                .map(TextFingerprint::of).toList())).toList()));
        observation.steps = steps;
        if (steps.isEmpty()) {
            observation.runExitReason = RunExitReason.PLAN_INVALID;
            return new ExecutionOutcome(ExecutionOutcome.Reason.EXECUTION_ERROR,
                    "❌ 规划失败：无法解析执行计划\n原始输出:\n" + planResult.content());
        }

        out.println(AnsiStyle.heading("📋 执行计划"));
        out.println(summarizeSteps(steps) + "\n");

        // 3. 执行阶段：按依赖顺序分配给执行者
        out.println(AnsiStyle.heading("⚡ 第二阶段：执行"));
        Map<String, Integer> retryCount = new ConcurrentHashMap<>();
        Map<String, TurnToolPolicy.TrustedUrlContext> stepTrustedUrls = new ConcurrentHashMap<>();
        int singleStepCursor = 0;
        int batchIndex = 0;

        while (true) {
            if (CancellationContext.isCancelled() || steps.stream().anyMatch(
                    step -> step.reason() == ExecutionOutcome.Reason.CANCELLED)) {
                return cancelTeam(steps, observation);
            }
            List<ExecutionStep> executable = getExecutableSteps(steps);
            if (executable.isEmpty()) {
                break;
            }
            batchIndex++;

            if (executable.size() == 1) {
                // 单步批次：直接串行流式输出，保持实时打字观感
                ExecutionStep step = executable.get(0);
                SubAgent worker = workers.get(singleStepCursor % workers.size());
                singleStepCursor++;
                List<TurnToolPolicy.TrustedUrlContext> dependencyUrls = dependencyTrustedUrls(
                        step, stepTrustedUrls);
                TurnToolPolicy stepPolicy = turnToolPolicy.forkWithTrustedUrls(dependencyUrls);
                String context = buildStepContext(steps, step, dependencyUrls);
                StepObservation stepObservation = observation.step(step, steps, context, batchIndex);
                runSingleStep(step, steps, retryCount, worker, context, stepPolicy,
                        stepTrustedUrls, stepObservation);
            } else {
                // 多步批次：真正并行执行，每步用独立的 PrintStream 缓冲，完成后按 step_id 顺序 flush
                out.println("⚡ 批次 #" + batchIndex + "：" + executable.size()
                        + " 个独立步骤并行执行（最多 " + workers.size() + " 个并发 Worker）\n");
                runBatchParallel(executable, steps, retryCount, turnToolPolicy, stepTrustedUrls,
                        observation, batchIndex);
            }
        }

        if (CancellationContext.isCancelled()) return cancelTeam(steps, observation);

        // 5. 处理因前置失败而无法执行的残留步骤（显式提示用户）
        for (ExecutionStep step : steps) {
            if (step.status() == StepStatus.PENDING) {
                observation.blocked(step);
                out.println("⏭️ 步骤 [" + step.id() + "] 因前置步骤失败被跳过: " + step.description());
            }
        }

        // 6. 汇总结果
        String finalResult = buildFinalResult(steps);
        observation.runExitReason = steps.stream().allMatch(step -> step.status() == StepStatus.COMPLETED)
                ? RunExitReason.COMPLETED : RunExitReason.INCOMPLETE;
        conversationLedger.appendMessage(
                "team",
                "orchestrator",
                "run_result",
                LlmClient.Message.assistant(finalResult));

        ExecutionOutcome.Reason reason = ExecutionOutcome.aggregateReason(steps.stream()
                .map(ExecutionStep::reason).toList());
        if (reason == ExecutionOutcome.Reason.COMPLETED
                && steps.stream().anyMatch(step -> step.status() != StepStatus.COMPLETED))
            reason = ExecutionOutcome.Reason.EXECUTION_ERROR;
        return new ExecutionOutcome(reason, buildFinalResult(steps, true), finalResult);
    }

    private void runSingleStep(ExecutionStep step, List<ExecutionStep> steps,
                               Map<String, Integer> retryCount, SubAgent worker, String context,
                               TurnToolPolicy stepPolicy,
                               Map<String, TurnToolPolicy.TrustedUrlContext> stepTrustedUrls,
                               StepObservation observation) {
        CancellationToken token = Objects.requireNonNull(CancellationContext.currentThreadToken());
        ExecutorService executor = newTeamExecutor(1);
        Future<?> future = executor.submit(LangfuseTelemetry.wrap(() -> {
            try (CancellationContext.Scope ignored = CancellationContext.bind(token)) {
                try {
                    if (token.isCancelled()) {
                        if (observation != null) observation.reason = StepExitReason.CANCELLED;
                        return;
                    }
                    runStep(step, steps, retryCount, worker, reviewer, context, out,
                            stepPolicy, stepTrustedUrls, observation);
                } catch (RuntimeException | Error e) {
                    if (observation != null) observation.threw(e);
                    throw e;
                } finally {
                    worker.clearHistory();
                    if (observation != null) observation.exited(steps);
                }
            }
        }));
        awaitTeamBatch(List.of(future), executor, token, true);
    }

    private static ExecutorService newTeamExecutor(int parallelism) {
        return Executors.newFixedThreadPool(parallelism, r -> {
            Thread thread = new Thread(r, "paicli-multi-agent");
            thread.setDaemon(true);
            return thread;
        });
    }

    private ExecutionOutcome cancelTeam(List<ExecutionStep> steps, TeamObservation observation) {
        observation.runExitReason = RunExitReason.CANCELLED;
        StringBuilder result = new StringBuilder("⏹️ 已取消当前多 Agent 任务。\n已保留的结果（不代表整个任务完成）：\n");
        for (ExecutionStep step : steps) {
            if (step.status() != StepStatus.COMPLETED) {
                ExecutionStep cancelled = step.withFailed(step.result() == null ? "用户取消" : step.result());
                updateStep(steps, step.id(), cancelled);
                observation.cancelledBeforeStart(cancelled);
            }
            result.append('[').append(step.id()).append("] ")
                    .append(step.status() == StepStatus.COMPLETED ? "已完成：" : "未完成：")
                    .append(step.description()).append('\n');
            if (step.result() != null && !step.result().isBlank()) result.append(step.result()).append('\n');
        }
        String text = result.toString();
        conversationLedger.appendEvent("run_cancelled", "team", "orchestrator", "execution", Map.of());
        conversationLedger.appendMessage("team", "orchestrator", "run_result", LlmClient.Message.assistant(text));
        return new ExecutionOutcome(ExecutionOutcome.Reason.CANCELLED, text);
    }

    /**
     * 解析规划者输出的 JSON 计划；计划不合法时返回空列表，由调用方按规划失败处理。
     */
    List<ExecutionStep> parsePlan(String planJson) {
        try {
            return TeamPlanParser.parse(planJson);
        } catch (IOException e) {
            log.warn("Rejected team plan: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 获取当前可执行的步骤（依赖已全部完成）
     */
    List<ExecutionStep> getExecutableSteps(List<ExecutionStep> steps) {
        Map<String, StepStatus> statusMap = new HashMap<>();
        for (ExecutionStep step : steps) {
            statusMap.put(step.id(), step.status());
        }

        return steps.stream()
                .filter(step -> step.status() == StepStatus.PENDING)
                .filter(step -> step.dependencies().stream()
                        .allMatch(dep -> statusMap.get(dep) == StepStatus.COMPLETED))
                .toList();
    }

    /**
     * 获取记忆管理器
     */
    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    /**
     * 获取工具注册表（用于同步项目路径）
     */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    private synchronized void updateStep(List<ExecutionStep> steps, String stepId, ExecutionStep updated) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id().equals(stepId)) {
                steps.set(i, updated);
                return;
            }
        }
    }

    /**
     * 并行执行一批相互独立的步骤。
     *
     * 每个步骤获取一个 Worker（池化，避免同一 Worker 被两个步骤并发占用），同时创建独立的 Reviewer 实例，
     * 流式输出写入步骤本地的 ByteArrayOutputStream；所有任务完成后按 step_id 顺序将缓冲区 flush 到 stdout。
     */
    private void runBatchParallel(List<ExecutionStep> batch, List<ExecutionStep> steps,
                                  Map<String, Integer> retryCount,
                                  TurnToolPolicy turnToolPolicy,
                                  Map<String, TurnToolPolicy.TrustedUrlContext> stepTrustedUrls,
                                  TeamObservation observation, int batchOrdinal) {
        int parallelism = Math.min(batch.size(), workers.size());
        ExecutorService executor = newTeamExecutor(parallelism);
        BlockingQueue<SubAgent> workerPool = new LinkedBlockingQueue<>(workers);
        Map<String, ByteArrayOutputStream> buffers = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>();
        CancellationToken token = Objects.requireNonNull(CancellationContext.currentThreadToken());

        for (ExecutionStep step : batch) {
            if (token.isCancelled()) break;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            buffers.put(step.id(), baos);
            PrintStream stepOut = new PrintStream(baos, true, StandardCharsets.UTF_8);
            List<TurnToolPolicy.TrustedUrlContext> dependencyUrls = dependencyTrustedUrls(
                    step, stepTrustedUrls);
            TurnToolPolicy stepPolicy = turnToolPolicy.forkWithTrustedUrls(dependencyUrls);
            String context = buildStepContext(steps, step, dependencyUrls);

            futures.add(executor.submit(LangfuseTelemetry.wrap(() -> {
                try (CancellationContext.Scope ignored = CancellationContext.bind(token)) {
                    if (token.isCancelled()) return null;
                    StepObservation stepObservation = observation.step(step, steps, context, batchOrdinal);
                    try {
                        SubAgent worker = null;
                        SubAgent localReviewer = new SubAgent(
                                "reviewer-" + step.id(), AgentRole.REVIEWER, llmClient, toolRegistry);
                        localReviewer.setConversationLedger(conversationLedger);
                        try {
                            worker = workerPool.take();
                            if (token.isCancelled()) {
                                if (stepObservation != null) stepObservation.reason = StepExitReason.CANCELLED;
                                return null;
                            }
                            runStep(step, steps, retryCount, worker, localReviewer, context, stepOut,
                                    stepPolicy, stepTrustedUrls, stepObservation);
                        } catch (InterruptedException e) {
                            if (stepObservation != null) stepObservation.cancelled(e);
                            Thread.currentThread().interrupt();
                            updateStep(steps, step.id(), step.withFailed("并行执行被中断"));
                            stepOut.println("❌ 步骤 [" + step.id() + "] 被中断\n");
                        } catch (RuntimeException e) {
                            if (stepObservation != null) stepObservation.threw(e);
                            log.error("Parallel step {} failed unexpectedly", step.id(), e);
                            updateStep(steps, step.id(), step.withFailed("并行执行异常: " + e.getMessage()));
                            stepOut.println("❌ 步骤 [" + step.id() + "] 并行执行异常：" + e.getMessage() + "\n");
                        } finally {
                            if (worker != null) {
                                worker.clearHistory();
                                workerPool.offer(worker);
                            }
                            stepOut.flush();
                        }
                        return null;
                    } catch (RuntimeException | Error e) {
                        if (stepObservation != null) stepObservation.threw(e);
                        throw e;
                    } finally {
                        if (stepObservation != null) stepObservation.exited(steps);
                    }
                }
            })));
        }

        awaitTeamBatch(futures, executor, token, false);

        // 按 step_id 顺序 flush 各步骤的缓冲输出，保证用户看到的执行过程有稳定顺序
        for (ExecutionStep step : batch) {
            ByteArrayOutputStream buf = buffers.get(step.id());
            if (buf != null && buf.size() > 0) {
                out.print(buf.toString(StandardCharsets.UTF_8));
                out.flush();
            }
        }
    }

    /** Cancel only this batch's private executor; allow cooperative workers to return partial results. */
    private void awaitTeamBatch(List<Future<?>> futures, ExecutorService executor,
                                CancellationToken token, boolean propagateFailure) {
        boolean interrupted = false;
        long cleanupEnd = 0;
        try {
            for (Future<?> future : futures) {
                while (true) {
                    if (token.isCancelled() && cleanupEnd == 0) {
                        cleanupEnd = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
                        for (Runnable queued : executor.shutdownNow()) {
                            if (queued instanceof Future<?> pending) pending.cancel(false);
                        }
                    }
                    if (cleanupEnd != 0 && System.nanoTime() >= cleanupEnd) {
                        cancellationCleanup = executor;
                        future.cancel(true);
                        break;
                    }
                    try {
                        future.get(100, TimeUnit.MILLISECONDS);
                        break;
                    } catch (TimeoutException ignored) {
                        // Poll cancellation, not an additional task deadline.
                    } catch (InterruptedException e) {
                        interrupted = true;
                        token.cancel();
                    } catch (CancellationException e) {
                        break;
                    } catch (ExecutionException e) {
                        if (propagateFailure && e.getCause() instanceof Error fatal) throw fatal;
                        if (propagateFailure && e.getCause() instanceof RuntimeException error) throw error;
                        log.error("Parallel step task failed", e.getCause());
                        break;
                    }
                }
            }
        } finally {
            executor.shutdown();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /**
     * 执行单个步骤（Worker 执行 + Reviewer 审查 + 最多 2 次重试）。
     *
     * 此方法被串行和并行两条路径共享，通过 {@code out} 控制流式输出目的地。
     */
    private void runStep(ExecutionStep step, List<ExecutionStep> steps,
                         Map<String, Integer> retryCount,
                         SubAgent worker, SubAgent reviewer, String context,
                         PrintStream out, TurnToolPolicy stepPolicy,
                         Map<String, TurnToolPolicy.TrustedUrlContext> stepTrustedUrls,
                         StepObservation observation) {
        try {
            runStepWithPolicy(step, steps, retryCount, worker, reviewer, context, out,
                    stepPolicy, stepTrustedUrls, observation);
        } finally {
            stepPolicy.releaseBrowserLease();
        }
    }

    private void runStepWithPolicy(ExecutionStep step, List<ExecutionStep> steps,
                                   Map<String, Integer> retryCount,
                                   SubAgent worker, SubAgent reviewer, String context,
                                   PrintStream out, TurnToolPolicy stepPolicy,
                                   Map<String, TurnToolPolicy.TrustedUrlContext> stepTrustedUrls,
                                   StepObservation observation) {
        out.println("🛠️ " + worker.getName() + " 执行步骤 [" + step.id() + "]: " + step.description());
        if (CancellationContext.isCancelled()) {
            if (observation != null) observation.reason = StepExitReason.CANCELLED;
            updateStep(steps, step.id(), step.withFailed("用户取消"));
            out.println("⏹️ 步骤 [" + step.id() + "] 已取消\n");
            return;
        }

        AgentMessage taskMsg = AgentMessage.task("orchestrator", step.description());
        ActivationObservation workerActivation = observation == null ? null : observation.worker(worker, 1);
        AgentMessage result = worker.executeWithContext(taskMsg, context, out, stepPolicy, workerActivation);
        if (stopCancelledStep(step, steps, result, result.content(), out, observation, workerActivation)) return;

        if (retainIncompleteWorkerResult(step, steps, result, out, observation, workerActivation)) return;
        if (result.type() == AgentMessage.Type.ERROR || result.outcome() != null
                && result.outcome().reason() == ExecutionOutcome.Reason.EXECUTION_ERROR) {
            if (observation != null) observation.reason = StepExitReason.WORKER_ERROR;
            updateStep(steps, step.id(), step.withFailed(result.content(), result.outcome() == null
                    ? ExecutionOutcome.Reason.MODEL_ERROR : result.outcome().reason()));
            out.println("❌ 步骤 [" + step.id() + "] 执行失败：" + result.content() + "\n");
            return;
        }
        if (result.content() == null || result.content().isBlank()) {
            if (observation != null) observation.reason = StepExitReason.EMPTY_RESULT;
            updateStep(steps, step.id(), step.withFailed("执行结果为空"));
            out.println("❌ 步骤 [" + step.id() + "] 执行失败：结果为空\n");
            return;
        }

        updateStep(steps, step.id(), new ExecutionStep(step.id(), step.description(), step.type(),
                step.dependencies(), result.content(), StepStatus.RUNNING));
        if (stopCancelledStep(step, steps, null, result.content(), out, observation, workerActivation)) return;
        out.println("🔍 " + reviewer.getName() + " 正在审查步骤 [" + step.id() + "] 的结果...");
        if (observation != null) observation.accepted(workerActivation);
        ActivationObservation reviewerActivation = observation == null ? null : observation.reviewer(reviewer, 1);
        AgentMessage reviewResult = reviewer.review(step.description(), result.content(), out, reviewerActivation);
        reviewer.clearHistory();
        if (stopCancelledStep(step, steps, reviewResult, result.content(), out, observation, workerActivation)) return;

        if (reviewResult.type() == AgentMessage.Type.ERROR) {
            if (observation != null) observation.reviewed(1, workerActivation, reviewerActivation,
                    ReviewDecision.ERROR, reviewResult.content(), null);
            log.warn("Reviewer failed for step {}: {}", step.id(), reviewResult.content());
            out.println("⚠️ 步骤 [" + step.id() + "] 审查失败，保留当前执行结果，但任务尚未完成\n");
            updateStep(steps, step.id(), step.withFailed(result.content(), ExecutionOutcome.Reason.MODEL_ERROR));
            return;
        }

        TeamReviewVerdict verdict = TeamReviewVerdict.parse(reviewResult.content());
        boolean approved = verdict.approved();
        String acceptedResult = result.content();

        if (approved) {
            if (observation != null) observation.reviewed(1, workerActivation, reviewerActivation,
                    ReviewDecision.APPROVED, reviewResult.content(), null);
            markStepCompleted(steps, step, acceptedResult, stepPolicy, stepTrustedUrls);
            out.println("✅ 步骤 [" + step.id() + "] 审查通过\n");
            return;
        }

        int retries = retryCount.getOrDefault(step.id(), 0);
        ExecutionOutcome.Reason lastWorkerFailure = null;
        boolean reviewerFailed = false;
        String issues = verdict.feedback();
        if (observation != null) observation.reviewed(1, workerActivation, reviewerActivation,
                ReviewDecision.REJECTED, reviewResult.content(), issues);
        log.info("Step {} rejected (retry {}/{}): {}", step.id(), retries, MAX_RETRIES_PER_STEP, issues);

        while (!approved && retries < MAX_RETRIES_PER_STEP) {
            if (stopCancelledStep(step, steps, null, acceptedResult, out, observation, workerActivation)) return;
            retries++;
            retryCount.put(step.id(), retries);
            out.println("⚠️ 步骤 [" + step.id() + "] 审查未通过，正在重新执行...");
            out.println("   反馈: " + issues + "\n");

            String feedbackContext = context + "\n\n之前的执行结果被审查拒绝，原因：\n" + issues;
            workerActivation = observation == null ? null : observation.worker(worker, retries + 1);
            AgentMessage retryResult = worker.executeWithContext(
                    taskMsg, feedbackContext, out, stepPolicy, workerActivation);
            if (stopCancelledStep(step, steps, retryResult,
                    acceptedResult + "\n重试中已取得的部分结果：\n" + retryResult.content(),
                    out, observation, workerActivation)) return;
            if (retainIncompleteWorkerResult(step, steps, retryResult, out, observation, workerActivation)) return;
            if (retryResult.type() == AgentMessage.Type.ERROR || retryResult.outcome() != null
                    && retryResult.outcome().reason() == ExecutionOutcome.Reason.EXECUTION_ERROR) {
                lastWorkerFailure = retryResult.outcome() == null ? ExecutionOutcome.Reason.MODEL_ERROR
                        : retryResult.outcome().reason();
                log.warn("Step {} retry {} failed at LLM layer: {}", step.id(), retries, retryResult.content());
                issues = "重试时 LLM 调用失败：" + retryResult.content();
                approved = false;
                continue;
            }
            if (retryResult.content() == null || retryResult.content().isBlank()) {
                lastWorkerFailure = ExecutionOutcome.Reason.EXECUTION_ERROR;
                if (observation != null) observation.accepted(workerActivation);
                acceptedResult = "执行结果为空";
                approved = false;
                issues = "执行结果为空";
                log.info("Step {} retry {} returned empty result", step.id(), retries);
                continue;
            }

            acceptedResult = retryResult.content();
            lastWorkerFailure = null;
            updateStep(steps, step.id(), new ExecutionStep(step.id(), step.description(), step.type(),
                    step.dependencies(), acceptedResult, StepStatus.RUNNING));
            if (stopCancelledStep(step, steps, null, acceptedResult, out, observation, workerActivation)) return;
            if (observation != null) observation.accepted(workerActivation);
            reviewerActivation = observation == null ? null : observation.reviewer(reviewer, retries + 1);
            AgentMessage retryReview = reviewer.review(step.description(), acceptedResult, out, reviewerActivation);
            reviewer.clearHistory();
            if (stopCancelledStep(step, steps, retryReview, acceptedResult, out, observation, workerActivation)) return;

            if (retryReview.type() == AgentMessage.Type.ERROR) {
                if (observation != null) observation.reviewed(retries + 1, workerActivation,
                        reviewerActivation, ReviewDecision.ERROR, retryReview.content(), null);
                log.warn("Reviewer failed for step {} retry {}: {}", step.id(), retries, retryReview.content());
                reviewerFailed = true;
                issues = "审查请求失败";
                break;
            }

            TeamReviewVerdict retryVerdict = TeamReviewVerdict.parse(retryReview.content());
            approved = retryVerdict.approved();
            issues = retryVerdict.feedback();
            if (observation != null) observation.reviewed(retries + 1, workerActivation,
                    reviewerActivation, approved ? ReviewDecision.APPROVED : ReviewDecision.REJECTED,
                    retryReview.content(), issues);
        }

        if (stopCancelledStep(step, steps, null, acceptedResult, out, observation, workerActivation)) return;
        if (lastWorkerFailure != null) {
            if (observation != null) observation.reason = StepExitReason.WORKER_ERROR;
            updateStep(steps, step.id(), new ExecutionStep(step.id(), step.description(), step.type(),
                    step.dependencies(), acceptedResult, StepStatus.FAILED, lastWorkerFailure, issues));
            out.println("❌ 步骤 [" + step.id() + "] Worker 重试未完成，保留当前结果\n");
            return;
        }
        if (!approved) {
            updateStep(steps, step.id(), step.withFailed(acceptedResult,
                    reviewerFailed ? ExecutionOutcome.Reason.MODEL_ERROR : ExecutionOutcome.Reason.EXECUTION_ERROR));
            out.println("⚠️ 步骤 [" + step.id() + "] "
                    + (reviewerFailed ? "审查失败" : "超过最大重试次数且审查未通过")
                    + "，保留当前结果，但任务尚未完成\n");
            return;
        }
        markStepCompleted(steps, step, acceptedResult, stepPolicy, stepTrustedUrls);
        out.println("✅ 步骤 [" + step.id() + "] 重试后审查通过\n");
    }

    private boolean stopCancelledStep(ExecutionStep step, List<ExecutionStep> steps,
                                      AgentMessage response, String partial, PrintStream out,
                                      StepObservation observation, ActivationObservation workerActivation) {
        if (!CancellationContext.isCancelled() && (response == null || response.outcome() == null
                || response.outcome().reason() != ExecutionOutcome.Reason.CANCELLED)) return false;
        if (observation != null) {
            observation.reason = StepExitReason.CANCELLED;
            observation.accepted(workerActivation);
        }
        updateStep(steps, step.id(), step.withFailed(partial, ExecutionOutcome.Reason.CANCELLED));
        out.println("⏹️ 步骤 [" + step.id() + "] 已取消，保留已有结果\n");
        return true;
    }

    /** A Worker budget summary must not be promoted to success by a Reviewer. */
    private boolean retainIncompleteWorkerResult(ExecutionStep step, List<ExecutionStep> steps,
                                                 AgentMessage result, PrintStream out,
                                                 StepObservation observation, ActivationObservation workerActivation) {
        ExecutionOutcome outcome = result.outcome();
        if (outcome == null || outcome.completed()
                || outcome.reason() == ExecutionOutcome.Reason.MODEL_ERROR
                || outcome.reason() == ExecutionOutcome.Reason.EXECUTION_ERROR) return false;
        if (observation != null) {
            observation.reason = outcome.reason() == ExecutionOutcome.Reason.CANCELLED
                    ? StepExitReason.CANCELLED : StepExitReason.WORKER_INCOMPLETE;
            observation.accepted(workerActivation); // Identifies the retained content, not completion.
        }
        updateStep(steps, step.id(), step.withFailed(outcome.content(), outcome.reason()));
        out.println("⚠️ 步骤 [" + step.id() + "] 未完成（" + outcome.reason() + "），保留部分结果\n");
        return true;
    }

    private String buildStepContext(List<ExecutionStep> steps, ExecutionStep currentStep,
                                    List<TurnToolPolicy.TrustedUrlContext> dependencyUrls) {
        StringBuilder context = new StringBuilder();
        context.append("总任务上下文：\n");

        for (ExecutionStep step : steps) {
            if (step.status() == StepStatus.COMPLETED && currentStep.dependencies().contains(step.id())) {
                context.append("已完成的依赖步骤 [").append(step.id()).append("]: ")
                        .append(step.description()).append("\n");
                if (step.result() != null && !step.result().isBlank()) {
                    String preview = step.result().length() > 500
                            ? step.result().substring(0, 500) + "..."
                            : step.result();
                    context.append("结果：").append(preview).append("\n");
                }
                context.append("\n");
            }
        }

        Set<String> trustedDependencyUrls = dependencyUrls.stream()
                .flatMap(contextItem -> contextItem.urls().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!trustedDependencyUrls.isEmpty()) {
            context.append("依赖分支经 web_search 验证的 URL（可供当前步骤抓取/导航）：\n");
            trustedDependencyUrls.forEach(url -> context.append("- ").append(url).append("\n"));
            context.append("\n");
        }

        return context.toString();
    }

    private static List<TurnToolPolicy.TrustedUrlContext> dependencyTrustedUrls(
            ExecutionStep step,
            Map<String, TurnToolPolicy.TrustedUrlContext> stepTrustedUrls) {
        return step.dependencies().stream()
                .map(stepTrustedUrls::get)
                .filter(Objects::nonNull)
                .toList();
    }

    private void markStepCompleted(List<ExecutionStep> steps,
                                   ExecutionStep step,
                                   String result,
                                   TurnToolPolicy stepPolicy,
                                   Map<String, TurnToolPolicy.TrustedUrlContext> stepTrustedUrls) {
        updateStep(steps, step.id(), step.withResult(result));
        stepTrustedUrls.put(step.id(), stepPolicy.trustedUrlContext());
    }

    private String summarizeSteps(List<ExecutionStep> steps) {
        StringBuilder sb = new StringBuilder();
        for (ExecutionStep step : steps) {
            String deps = step.dependencies().isEmpty() ? "无"
                    : String.join(", ", step.dependencies());
            sb.append(String.format("  %s [%s] %s (依赖: %s)%n",
                    step.status() == StepStatus.COMPLETED ? "✅" : "⏳",
                    step.id(), step.description(), deps));
        }
        return sb.toString();
    }

    /**
     * 构建最终汇总。
     *
     * 注意：Worker/Reviewer 的完整输出在执行阶段已经通过流式渲染打印给用户，
     * 此处只返回"步骤状态 + 简短预览"作为总结，避免同一段内容被打印 2-3 次。
     */
    private String buildFinalResult(List<ExecutionStep> steps) {
        return buildFinalResult(steps, false);
    }

    private String buildFinalResult(List<ExecutionStep> steps, boolean fullContent) {
        StringBuilder result = new StringBuilder();
        boolean allCompleted = steps.stream().allMatch(step -> step.status() == StepStatus.COMPLETED);
        boolean hasFailedSteps = steps.stream().anyMatch(step -> step.status() == StepStatus.FAILED);

        if (allCompleted) {
            result.append("✅ 多 Agent 协作任务完成！\n\n");
        } else if (hasFailedSteps) {
            result.append("⚠️ 多 Agent 协作任务未完全完成，存在失败步骤。\n\n");
        } else {
            result.append("⚠️ 多 Agent 协作任务部分完成，仍有未执行步骤。\n\n");
        }
        result.append("📋 执行总结：\n");

        for (ExecutionStep step : steps) {
            result.append("[").append(step.id()).append("] ");
            if (step.status() == StepStatus.COMPLETED) {
                result.append("✅ ");
            } else if (step.status() == StepStatus.FAILED) {
                result.append("❌ ");
            } else {
                result.append("⏳ ");
            }
            result.append(step.description()).append("\n");

            if (step.result() != null && !step.result().isBlank()) {
                String preview = !fullContent && step.result().length() > 120
                        ? step.result().substring(0, 120) + "..."
                        : step.result();
                result.append("   结果：").append(preview).append("\n");
            }
            if (step.error() != null && !step.error().isBlank())
                result.append("   错误：").append(step.error()).append("\n");
        }

        return result.toString();
    }

    /** Captured per run. Observation failures never enter the product control flow. */
    private final class TeamObservation {
        private final TeamExecutionObserver observer;
        private final String runId;
        private final long origin;
        private final AtomicBoolean failed = new AtomicBoolean();
        private final TaskAcceptance acceptance;
        private final Set<String> enteredSteps;
        private RunExitReason runExitReason = RunExitReason.THREW;
        private List<ExecutionStep> steps = List.of();

        private TeamObservation(TeamExecutionObserver observer, TaskAcceptance acceptance) {
            this.observer = observer;
            this.acceptance = acceptance;
            this.runId = UUID.randomUUID().toString();
            this.origin = observer == null ? 0 : System.nanoTime();
            this.enteredSteps = ConcurrentHashMap.newKeySet();
        }

        private long now() { return observer == null ? 0 : Math.max(0, System.nanoTime() - origin); }

        private boolean enabled() { return observer != null && !failed.get(); }

        private void emit(Supplier<Event> event) {
            if (!enabled()) return;
            try {
                Event value = event.get();
                if (value != null) observer.onEvent(value);
            } catch (RuntimeException | AssertionError e) {
                if (failed.compareAndSet(false, true)) {
                    executionObservationFailures.incrementAndGet();
                    log.warn("Team execution observer failed: {}", e.getClass().getName());
                }
            }
        }

        private ActivationObservation activation(SubAgent actor, String stepId, int attempt) {
            return new ActivationObservation(this, new ActivationIdentity(runId, stepId, attempt,
                    actor.observationRole(), UUID.randomUUID().toString(),
                    actor.observationActorInstanceId(), actor.observationHistoryGeneration()));
        }

        private StepObservation step(ExecutionStep step, List<ExecutionStep> steps,
                                     String context, int batchOrdinal) {
            StepObservation observation = new StepObservation(this, step.id());
            enteredSteps.add(step.id());
            emit(() -> new StepEntered(runId, now(), step.id(), batchOrdinal,
                    Thread.currentThread().getId(), TextFingerprint.of(context), steps.stream()
                    .filter(dependency -> dependency.status() == StepStatus.COMPLETED
                            && step.dependencies().contains(dependency.id()))
                    .map(dependency -> {
                        String result = dependency.result();
                        boolean truncated = result != null && !result.isBlank() && result.length() > 500;
                        String injected = result == null || result.isBlank() ? null
                                : truncated ? result.substring(0, 500) + "..." : result;
                        return new DependencyInput(dependency.id(), ProductStatus.COMPLETED,
                                TextFingerprint.of(result), TextFingerprint.of(injected), truncated);
                    }).toList()));
            return observation;
        }

        private void blocked(ExecutionStep step) {
            // Native status remains PENDING inside a running step. After an interrupted
            // Future wait, do not invent a second exit for a still-running descendant.
            emit(() -> enteredSteps.contains(step.id()) ? null
                    : new StepExited(runId, now(), step.id(), ProductStatus.PENDING,
                            StepExitReason.BLOCKED_DEPENDENCY, null, null,
                            TextFingerprint.of(step.result()), null));
        }

        private void cancelledBeforeStart(ExecutionStep step) {
            emit(() -> enteredSteps.contains(step.id()) ? null
                    : new StepExited(runId, now(), step.id(), ProductStatus.FAILED,
                            StepExitReason.CANCELLED, null, null, TextFingerprint.of(step.result()), null));
        }

        private void finish(String result, Throwable failure) {
            emit(() -> new RunExited(runId, now(), failure == null ? runExitReason : RunExitReason.THREW,
                    TextFingerprint.of(result), failure == null ? null : failure.getClass().getName()));
        }
    }

    /** Passed explicitly per invocation: no mutable shared actor/request scope. */
    static final class ActivationObservation {
        private final TeamObservation run;
        private final ActivationIdentity identity;
        private boolean partial;

        private ActivationObservation(TeamObservation run, ActivationIdentity identity) {
            this.run = run;
            this.identity = identity;
        }

        private static String id(ActivationObservation observation) {
            return observation == null ? null : observation.identity.activationId();
        }

        void entered(int historyMessagesBefore) {
            run.emit(() -> new ActivationEntered(identity, run.now(), Thread.currentThread().getId(),
                    historyMessagesBefore));
        }

        void inputPrepared(int index, LlmClient.Message message) {
            run.emit(() -> new ActivationInputPrepared(identity, run.now(), index,
                    TextFingerprint.of(message.content()), message.imagePartCount()));
        }

        void compacted(int iteration, int before, int after) {
            run.emit(() -> new HistoryCompacted(identity, run.now(), iteration, before, after, false));
        }

        void compactionScope(int iteration, List<LlmClient.Message> history, int triggerTokens,
                              boolean sessionMemoryEnabled) {
            // Evaluate only inside the enabled, failure-isolated observer path. The native
            // manager still makes its own unchanged estimate and compaction decision.
            run.emit(() -> {
                if (triggerTokens <= 0) return null;
                boolean thresholdReached = TokenBudget.estimateMessagesTokens(history) >= triggerTokens;
                return sessionMemoryEnabled || thresholdReached
                        ? new CompactionScopeUnsupported(identity, run.now(), iteration,
                                sessionMemoryEnabled, thresholdReached) : null;
            });
        }

        void partial(int iteration, String reason) {
            partial = true;
            run.emit(() -> new BudgetFinalization(identity, run.now(), iteration, reason));
        }

        void tools(int iteration, List<ToolRegistry.ToolExecutionResult> results) {
            if (identity.role() == AgentRole.WORKER) run.acceptance.observe(results);
            run.emit(() -> {
                List<ToolResult> summaries = new ArrayList<>();
                for (int i = 0; i < results.size(); i++) {
                    ToolRegistry.ToolExecutionResult result = results.get(i);
                    summaries.add(new ToolResult(i, result.id(), result.name(),
                            TextFingerprint.of(result.argumentsJson()), TextFingerprint.of(result.result()),
                            result.successful(), result.timedOut(), result.imageParts().size()));
                }
                return new ToolBatchReturned(identity, run.now(), iteration, summaries);
            });
        }

        void exited(AgentMessage result, Throwable failure) {
            run.emit(() -> new ActivationExited(identity, run.now(), failure != null ? ExitKind.THREW
                    : partial ? ExitKind.PARTIAL : result != null && result.type() == AgentMessage.Type.ERROR
                    ? ExitKind.ERROR : ExitKind.NORMAL,
                    TextFingerprint.of(result == null ? null : result.content()),
                    failure == null ? null : failure.getClass().getName()));
        }
    }

    private static final class StepObservation {
        private final TeamObservation run;
        private final String stepId;
        private StepExitReason reason = StepExitReason.THREW;
        private String acceptedWorkerActivationId;
        private String lastReviewerActivationId;
        private Throwable failure;

        private StepObservation(TeamObservation run, String stepId) {
            this.run = run;
            this.stepId = stepId;
        }

        private ActivationObservation worker(SubAgent worker, int attempt) {
            return run.activation(worker, stepId, attempt);
        }

        private ActivationObservation reviewer(SubAgent reviewer, int attempt) {
            ActivationObservation observation = run.activation(reviewer, stepId, attempt);
            lastReviewerActivationId = ActivationObservation.id(observation);
            return observation;
        }

        private void accepted(ActivationObservation worker) {
            acceptedWorkerActivationId = ActivationObservation.id(worker);
        }

        private void reviewed(int attempt, ActivationObservation worker, ActivationObservation reviewer,
                              ReviewDecision decision, String result, String issues) {
            reason = switch (decision) {
                case APPROVED -> StepExitReason.APPROVED;
                case REJECTED -> StepExitReason.RETRIES_EXHAUSTED_RETAINED;
                case ERROR -> StepExitReason.REVIEW_ERROR_RETAINED;
            };
            run.emit(() -> new ReviewEvaluated(run.runId, run.now(), stepId, attempt,
                    ActivationObservation.id(worker), ActivationObservation.id(reviewer), decision,
                    TextFingerprint.of(result), TextFingerprint.of(issues)));
        }

        private void threw(Throwable error) { reason = StepExitReason.THREW; failure = error; }

        private void cancelled(Throwable error) { reason = StepExitReason.CANCELLED; failure = error; }

        private void exited(List<ExecutionStep> steps) {
            run.emit(() -> {
                ExecutionStep terminal = steps.stream().filter(step -> step.id().equals(stepId))
                        .findFirst().orElseThrow();
                return new StepExited(run.runId, run.now(), stepId,
                        ProductStatus.valueOf(terminal.status().name()), reason,
                        acceptedWorkerActivationId, lastReviewerActivationId,
                        TextFingerprint.of(terminal.result()), failure == null ? null : failure.getClass().getName());
            });
        }
    }
}
