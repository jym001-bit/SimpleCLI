package com.paicli.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.context.ContextProfile;
import com.paicli.eval.benchmark.relay.TeamObservationWire;
import com.paicli.llm.LlmClient;
import com.paicli.memory.AutoCompactionManager;
import com.paicli.memory.LongTermMemory;
import com.paicli.memory.MemoryManager;
import com.paicli.tool.ToolOutput;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static com.paicli.agent.TeamExecutionObserver.*;
import static org.junit.jupiter.api.Assertions.*;

/** Native offline observation controls, not E2 admission, independent verification or model scores. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class TeamExecutionObservationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GOAL = "离线完成代码和测试，再根据两者结果编写文档；允许项目内读写文件，禁止联网。私有任务标记甲。";
    private static final String CODE = "CODE_RESULT_甲：代码实现完整，边界已处理。";
    private static final String TEST = "TEST_RESULT_乙：测试覆盖正常、空值与错误路径。";
    private static final String DOCUMENT = "DOC_RESULT_丙：已整合代码和测试。";
    @TempDir Path temp;
    private final List<Throwable> codecFailures = new CopyOnWriteArrayList<>();
    private final List<AgentOrchestrator> nativeAgents = new ArrayList<>();
    private final Map<AgentOrchestrator, Long> expectedObservationFailures = new IdentityHashMap<>();
    private String priorSessionMemory;

    @BeforeEach void disableAsynchronousSessionMemoryForOfflineScripts() {
        priorSessionMemory = System.getProperty(AutoCompactionManager.SESSION_MEMORY_PROPERTY);
        System.setProperty(AutoCompactionManager.SESSION_MEMORY_PROPERTY, "false");
    }

    @AfterEach void assertCodecAndObserverFailuresOutsideTheIsolatedCallback() {
        try {
            List<Executable> checks = new ArrayList<>();
            checks.add(() -> assertTrue(codecFailures.isEmpty(), () -> "native Team codec failures: "
                    + codecFailures.stream().map(error -> error.getClass().getSimpleName()).toList()));
            for (var orchestrator : nativeAgents) {
                checks.add(() -> assertEquals(expectedObservationFailures.getOrDefault(orchestrator, 0L).longValue(),
                        orchestrator.getExecutionObservationFailures(), "unexpected isolated observation failure"));
            }
            assertAll(checks);
        } finally {
            if (priorSessionMemory == null) System.clearProperty(AutoCompactionManager.SESSION_MEMORY_PROPERTY);
            else System.setProperty(AutoCompactionManager.SESSION_MEMORY_PROPERTY, priorSessionMemory);
        }
    }

    @Test void realParallelCodeAndTestFinishBeforeDocReceivesBothDependencies() throws Exception {
        var events = new CopyOnWriteArrayList<Event>();
        var client = new ParallelClient(false); var registry = new RecordingRegistry();
        var orchestrator = observed(client, registry, events::add);
        String result = orchestrator.runExplicitTask(GOAL, GOAL);
        assertTrue(result.startsWith("✅ 多 Agent 协作任务完成"));
        assertEquals(10, client.requests.get()); assertEquals(2, client.branchThreads.size());
        assertEquals(0, orchestrator.getExecutionObservationFailures());
        RunStarted started = only(events, RunStarted.class);
        assertEquals(TextFingerprint.of(GOAL), started.input()); assertEquals(TextFingerprint.of(GOAL), started.submittedInput());
        assertTrue(started.explicitTaskEnvelope()); assertEquals(2, started.workerCount()); assertEquals(2, started.maxRetriesPerStep());
        var plan = only(events, PlanPrepared.class);
        assertEquals(List.of("step_1", "step_2", "step_3"), plan.steps().stream().map(StepNode::stepId).toList());
        assertEquals(List.of(), plan.steps().get(0).dependencies()); assertEquals(List.of(), plan.steps().get(1).dependencies());
        assertEquals(List.of(TextFingerprint.of("step_1"), TextFingerprint.of("step_2")), plan.steps().get(2).dependencies());
        assertTrue(overlap(events, "step_1", "step_2") > 0, "actual step bodies overlap, not just queued futures");
        assertTrue(entered(events, "step_3").elapsedNanos() >= Math.max(exited(events, "step_1").elapsedNanos(), exited(events, "step_2").elapsedNanos()));
        var doc = entered(events, "step_3");
        assertEquals(2, doc.batchOrdinal()); assertEquals(List.of("step_1", "step_2"), doc.dependencies().stream().map(DependencyInput::stepId).toList());
        for (var dependency : doc.dependencies()) {
            assertEquals(ProductStatus.COMPLETED, dependency.productStatus()); assertFalse(dependency.truncated());
            assertEquals(exited(events, dependency.stepId()).result(), dependency.fullResult());
            assertEquals(dependency.fullResult(), dependency.injectedResult());
        }
        assertTrue(client.docConsumedBoth); assertEquals(CODE + "\n" + TEST, Files.readString(temp.resolve("doc.txt")));
        var docInput = select(events, ActivationInputPrepared.class).stream()
                .filter(event -> event.activation().role() == AgentRole.WORKER && "step_3".equals(event.activation().stepId())).findFirst().orElseThrow();
        assertEquals(TextFingerprint.of(client.inputs.get("DOC")), docInput.userText());
        assertEquals(TextFingerprint.of(client.inputs.get("DOC").split("\n\n当前任务：", 2)[0]), doc.context());
        assertEquals(0, docInput.imagePartCount());
        var workerActivations = activations(events, AgentRole.WORKER);
        assertEquals(3, workerActivations.size());
        assertNotEquals(workerActivations.get(0).activation().actorInstanceId(), workerActivations.get(1).activation().actorInstanceId());
        var docActivation = workerActivations.stream().filter(event -> event.activation().stepId().equals("step_3")).findFirst().orElseThrow().activation();
        var previous = workerActivations.stream().filter(event -> !event.activation().stepId().equals("step_3")
                && event.activation().actorInstanceId().equals(docActivation.actorInstanceId())).findFirst().orElseThrow().activation();
        assertTrue(docActivation.historyGeneration() > previous.historyGeneration());
        var batches = select(events, ToolBatchReturned.class);
        assertEquals(3, batches.size());
        for (var batch : batches) {
            assertEquals(1, batch.iteration()); assertEquals(1, batch.results().size());
            assertEquals(0, batch.results().get(0).ordinal()); assertEquals("repeated-call-id", batch.results().get(0).callId());
            assertTrue(batch.results().get(0).successful());
        }
        assertEquals(List.of(ReviewDecision.APPROVED, ReviewDecision.APPROVED, ReviewDecision.APPROVED),
                select(events, ReviewEvaluated.class).stream().map(ReviewEvaluated::decision).toList());
        assertTrue(select(events, StepExited.class).stream().allMatch(event -> event.reason() == StepExitReason.APPROVED));
        assertEquals(RunExitReason.COMPLETED, only(events, RunExited.class).reason());
        assertTrue(events.stream().allMatch(event -> event.runId().equals(started.runId())));
        assertThrows(UnsupportedOperationException.class, () -> plan.steps().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.steps().get(2).dependencies().clear());
        assertThrows(UnsupportedOperationException.class, () -> doc.dependencies().clear());
        assertThrows(UnsupportedOperationException.class, () -> batches.get(0).results().clear());
        assertNoRaw(events, GOAL, CODE, TEST, DOCUMENT, parallelPlan(false), client.inputs.get("DOC"));
    }

    @Test void serializedDependencyCannotMasqueradeAsParallelDespiteSameFinalDocument() throws Exception {
        var events = new CopyOnWriteArrayList<Event>(); var client = new ParallelClient(true);
        assertTrue(observed(client, new ToolRegistry(), events::add).runExplicitTask(GOAL, GOAL).startsWith("✅"));
        assertTrue(overlap(events, "step_1", "step_2") <= 0);
        assertEquals(List.of(TextFingerprint.of("step_1")), only(events, PlanPrepared.class).steps().get(1).dependencies());
        assertTrue(client.docConsumedBoth); assertEquals(CODE + "\n" + TEST, Files.readString(temp.resolve("doc.txt")));
    }

    @Test void dependencyObservationDistinguishesFullResultFromNativeFiveHundredCharacterPreview() throws Exception {
        String original = "PRIVATE_LONG_PREFIX_甲" + "界".repeat(600) + "PRIVATE_LONG_TAIL_乙";
        String preview = original.substring(0, 500) + "...";
        String plan = "{\"steps\":[{\"id\":\"a\",\"description\":\"SOURCE\",\"type\":\"ANALYSIS\",\"dependencies\":[]},"
                + "{\"id\":\"b\",\"description\":\"CONSUMER\",\"type\":\"ANALYSIS\",\"dependencies\":[\"a\"]}]}";
        var client = new ScriptClient(text(plan), text(original), review(true), text("consumer-result"), review(true));
        var events = new CopyOnWriteArrayList<Event>();
        assertTrue(observed(client, new ToolRegistry(), events::add).runExplicitTask(GOAL, GOAL).startsWith("✅"));
        var input = entered(events, "step_2").dependencies().get(0);
        assertTrue(input.truncated()); assertEquals(TextFingerprint.of(original), input.fullResult());
        assertEquals(TextFingerprint.of(preview), input.injectedResult());
        assertTrue(user(client.requests.get(3)).contains(preview));
        assertFalse(user(client.requests.get(3)).contains("PRIVATE_LONG_TAIL_乙"));
        assertNoRaw(events, "PRIVATE_LONG_PREFIX_甲", "PRIVATE_LONG_TAIL_乙", original, preview);
    }

    @Test void threeWorkerAttemptsShareHistoryButHaveDifferentActivationsAndReviewHistoryResets() throws Exception {
        var client = new ScriptClient(text(singlePlan()), text("attempt-one-private"), review(false),
                text("attempt-two-private"), review(false), text("attempt-three-private"), review(true));
        var events = new CopyOnWriteArrayList<Event>();
        String result = observed(client, new ToolRegistry(), events::add).runExplicitTask(GOAL, GOAL);
        assertTrue(result.contains("attempt-three-private")); assertFalse(result.contains("attempt-one-private"));
        var workers = activations(events, AgentRole.WORKER); var reviewers = activations(events, AgentRole.REVIEWER);
        assertEquals(3, workers.size()); assertEquals(3, reviewers.size());
        assertEquals(List.of(1, 2, 3), workers.stream().map(event -> event.activation().attempt()).toList());
        assertEquals(1, workers.stream().map(event -> event.activation().actorInstanceId()).distinct().count());
        assertEquals(1, workers.stream().map(event -> event.activation().historyGeneration()).distinct().count());
        assertEquals(3, workers.stream().map(event -> event.activation().activationId()).distinct().count());
        assertEquals(List.of(1, 3, 5), workers.stream().map(ActivationEntered::historyMessagesBefore).toList());
        assertEquals(List.of(1, 1, 1), reviewers.stream().map(ActivationEntered::historyMessagesBefore).toList());
        assertEquals(1, reviewers.stream().map(event -> event.activation().actorInstanceId()).distinct().count());
        assertEquals(3, reviewers.stream().map(event -> event.activation().historyGeneration()).distinct().count());
        var workerInputs = select(events, ActivationInputPrepared.class).stream().filter(event -> event.activation().role() == AgentRole.WORKER).toList();
        for (int index = 0; index < 3; index++) {
            var input = workerInputs.get(index); var request = client.requests.get(1 + index * 2);
            assertEquals(1 + index * 2, input.userMessageIndex());
            assertEquals(TextFingerprint.of(request.get(input.userMessageIndex()).content()), input.userText());
        }
        assertTrue(client.requests.get(3).stream().anyMatch(message -> "attempt-one-private".equals(message.content())));
        assertTrue(client.requests.get(5).stream().anyMatch(message -> "attempt-two-private".equals(message.content())));
        var reviews = select(events, ReviewEvaluated.class);
        assertEquals(List.of(ReviewDecision.REJECTED, ReviewDecision.REJECTED, ReviewDecision.APPROVED), reviews.stream().map(ReviewEvaluated::decision).toList());
        for (int index = 0; index < 3; index++) {
            assertEquals(workers.get(index).activation().activationId(), reviews.get(index).workerActivationId());
            assertEquals(reviewers.get(index).activation().activationId(), reviews.get(index).reviewerActivationId());
        }
        assertEquals(workers.get(2).activation().activationId(), only(events, StepExited.class).acceptedWorkerActivationId());
        assertNoRaw(events, "attempt-one-private", "attempt-two-private", "attempt-three-private", "REVIEW_PRIVATE_FEEDBACK_戊", "WORK_PRIVATE_DESCRIPTION_丁");
    }

    @ParameterizedTest @ValueSource(ints = {1, 2})
    void reviewerIoErrorIsNotApprovalEvenWhenNativeProductRetainsCompletedResult(int attempt) throws Exception {
        var failure = new IOException("REVIEW_ERROR_PRIVATE_DETAIL");
        ScriptClient client = attempt == 1 ? new ScriptClient(text(singlePlan()), text("retained-one"), failure)
                : new ScriptClient(text(singlePlan()), text("rejected-one"), review(false), text("retained-two"), failure);
        var events = new CopyOnWriteArrayList<Event>();
        String result = observed(client, new ToolRegistry(), events::add).runExplicitTask(GOAL, GOAL);
        assertTrue(result.startsWith("✅ 多 Agent 协作任务完成")); assertTrue(result.contains(attempt == 1 ? "retained-one" : "retained-two"));
        var reviews = select(events, ReviewEvaluated.class);
        assertEquals(attempt, reviews.size()); assertEquals(ReviewDecision.ERROR, reviews.get(attempt - 1).decision());
        assertTrue(reviews.stream().noneMatch(event -> event.decision() == ReviewDecision.APPROVED));
        StepExited exit = only(events, StepExited.class);
        assertEquals(ProductStatus.COMPLETED, exit.productStatus()); assertEquals(StepExitReason.REVIEW_ERROR_RETAINED, exit.reason());
        assertEquals(RunExitReason.COMPLETED, only(events, RunExited.class).reason());
        assertTrue(select(events, ActivationExited.class).stream().anyMatch(event -> event.activation().role() == AgentRole.REVIEWER && event.kind() == ExitKind.ERROR));
        assertNoRaw(events, "REVIEW_ERROR_PRIVATE_DETAIL");
    }

    @Test void finalReviewerRejectionRemainsSeparateFromProductCompletion() throws Exception {
        var client = new ScriptClient(text(singlePlan()), text("first"), review(false), text("second"), review(false), text("third"), review(false));
        var events = new CopyOnWriteArrayList<Event>();
        assertTrue(observed(client, new ToolRegistry(), events::add).runExplicitTask(GOAL, GOAL).startsWith("✅"));
        assertEquals(3, activations(events, AgentRole.WORKER).size());
        assertTrue(select(events, ReviewEvaluated.class).stream().allMatch(event -> event.decision() == ReviewDecision.REJECTED));
        assertEquals(3, select(events, ReviewEvaluated.class).size());
        assertEquals(ProductStatus.COMPLETED, only(events, StepExited.class).productStatus());
        assertEquals(StepExitReason.RETRIES_EXHAUSTED_RETAINED, only(events, StepExited.class).reason());
        assertEquals(TextFingerprint.of("third"), only(events, StepExited.class).result());
    }

    @Test void failedWorkerRetriesDoNotReplaceTheIdentityOfTheRetainedEarlierResult() throws Exception {
        var client = new ScriptClient(text(singlePlan()), text("retained-original-worker"), review(false),
                new IOException("PRIVATE_RETRY_ERROR_ONE"), new IOException("PRIVATE_RETRY_ERROR_TWO"));
        var events = new CopyOnWriteArrayList<Event>();
        String result = observed(client, new ToolRegistry(), events::add).runExplicitTask(GOAL, GOAL);
        assertTrue(result.startsWith("✅")); assertTrue(result.contains("retained-original-worker"));
        var workers = activations(events, AgentRole.WORKER); assertEquals(3, workers.size());
        assertEquals(1, select(events, ReviewEvaluated.class).size());
        var exit = only(events, StepExited.class);
        assertEquals(ProductStatus.COMPLETED, exit.productStatus());
        assertEquals(StepExitReason.RETRIES_EXHAUSTED_RETAINED, exit.reason());
        assertEquals(workers.get(0).activation().activationId(), exit.acceptedWorkerActivationId());
        assertEquals(TextFingerprint.of("retained-original-worker"), exit.result());
        assertEquals(List.of(ExitKind.NORMAL, ExitKind.ERROR, ExitKind.ERROR), select(events, ActivationExited.class).stream()
                .filter(event -> event.activation().role() == AgentRole.WORKER).map(ActivationExited::kind).toList());
        assertNoRaw(events, "PRIVATE_RETRY_ERROR_ONE", "PRIVATE_RETRY_ERROR_TWO", "retained-original-worker");
    }

    @Test void budgetFinalizationIsPartialWithNoToolsWithoutChangingNativeCompletion() throws Exception {
        String property = "paicli.react.hard.max.iterations", prior = System.getProperty(property);
        try {
            System.setProperty(property, "1");
            var client = new ScriptClient(text(singlePlan()), tools(call("shared", "read_file", "{\"path\":\"input.txt\"}")), text("仅完成读取，未完成业务"), review(true));
            var events = new CopyOnWriteArrayList<Event>();
            String result = observed(client, new ToolRegistry(), events::add).runExplicitTask(GOAL, GOAL);
            assertTrue(result.contains("部分完成")); assertTrue(result.startsWith("✅"));
            assertEquals(4, client.requests.size()); assertTrue(client.exposedTools.get(2).isEmpty());
            BudgetFinalization budget = only(events, BudgetFinalization.class);
            assertEquals(AgentRole.WORKER, budget.activation().role()); assertEquals(1, budget.iteration());
            assertEquals("HARD_ITERATION_LIMIT", budget.exitReason());
            var exit = select(events, ActivationExited.class).stream().filter(event -> event.activation().activationId().equals(budget.activation().activationId())).findFirst().orElseThrow();
            assertEquals(ExitKind.PARTIAL, exit.kind());
            assertEquals(ProductStatus.COMPLETED, only(events, StepExited.class).productStatus());
        } finally {
            if (prior == null) System.clearProperty(property); else System.setProperty(property, prior);
        }
    }

    @Test void uncheckedWorkerThrowRemainsAThrowAndContainsNoExceptionMessageInEvents() throws Exception {
        var exception = new IllegalStateException("PRIVATE_THROW_DETAIL");
        var events = new CopyOnWriteArrayList<Event>();
        var orchestrator = observed(new ScriptClient(text(singlePlan()), exception), new ToolRegistry(), events::add);
        assertSame(exception, assertThrows(IllegalStateException.class, () -> orchestrator.runExplicitTask(GOAL, GOAL)));
        var activation = select(events, ActivationExited.class).stream().filter(event -> event.activation().role() == AgentRole.WORKER).findFirst().orElseThrow();
        assertEquals(ExitKind.THREW, activation.kind()); assertEquals(IllegalStateException.class.getName(), activation.exceptionType());
        assertFalse(activation.result().present());
        assertEquals(StepExitReason.THREW, only(events, StepExited.class).reason());
        assertEquals(RunExitReason.THREW, only(events, RunExited.class).reason());
        assertNoRaw(events, "PRIVATE_THROW_DETAIL");
        var disabled = agent(new ScriptClient(text(singlePlan()), exception), new ToolRegistry());
        assertSame(exception, assertThrows(IllegalStateException.class, () -> disabled.runExplicitTask(GOAL, GOAL)));
    }

    @Test void caughtWorkerIoErrorRemainsFailedAndDependentStepNeverEnters() throws Exception {
        String plan = "{\"steps\":[{\"id\":\"a\",\"description\":\"SOURCE\",\"type\":\"ANALYSIS\",\"dependencies\":[]},"
                + "{\"id\":\"b\",\"description\":\"CONSUMER\",\"type\":\"ANALYSIS\",\"dependencies\":[\"a\"]}]}";
        var events = new CopyOnWriteArrayList<Event>();
        String result = observed(new ScriptClient(text(plan), new IOException("PRIVATE_WORKER_IO_ERROR")), new ToolRegistry(), events::add)
                .runExplicitTask(GOAL, GOAL);
        assertTrue(result.contains("未完全完成")); assertEquals(1, select(events, StepEntered.class).size());
        assertEquals(ProductStatus.FAILED, exited(events, "step_1").productStatus());
        assertEquals(StepExitReason.WORKER_ERROR, exited(events, "step_1").reason());
        assertEquals(StepExitReason.BLOCKED_DEPENDENCY, exited(events, "step_2").reason());
        assertEquals(ProductStatus.PENDING, exited(events, "step_2").productStatus());
        assertEquals(0, select(events, ReviewEvaluated.class).size());
        assertEquals(RunExitReason.INCOMPLETE, only(events, RunExited.class).reason());
        assertEquals(ExitKind.ERROR, select(events, ActivationExited.class).stream()
                .filter(event -> event.activation().role() == AgentRole.WORKER).findFirst().orElseThrow().kind());
        assertNoRaw(events, "PRIVATE_WORKER_IO_ERROR");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void observerExceptionDoesNotChangeResultsOrToolEffectsAndDefaultIsDisabled(boolean assertionError) throws Exception {
        var baselineClient = new ParallelClient(false);
        var baseline = agent(baselineClient, new ToolRegistry());
        assertNull(baseline.getExecutionObserver());
        String expected = baseline.runExplicitTask(GOAL, GOAL);
        assertEquals(0, baseline.getExecutionObservationFailures());
        var observedClient = new ParallelClient(false);
        var calls = new AtomicInteger();
        var instrumented = observed(observedClient, new ToolRegistry(), event -> {
            calls.incrementAndGet();
            if (event instanceof ToolBatchReturned) {
                if (assertionError) throw new AssertionError("PRIVATE_OBSERVER_DETAIL");
                throw new IllegalStateException("PRIVATE_OBSERVER_DETAIL");
            }
        });
        expectedObservationFailures.put(instrumented, 1L);
        assertEquals(expected, instrumented.runExplicitTask(GOAL, GOAL));
        assertTrue(calls.get() > 0); assertEquals(1, instrumented.getExecutionObservationFailures());
        assertEquals(baselineClient.requests.get(), observedClient.requests.get());
        assertEquals(CODE + "\n" + TEST, Files.readString(temp.resolve("doc.txt")));
    }

    @Test void nativeFullCompactionMarksUnsupportedScopeBeforeSummaryAndInvalidatesInputIndex() throws Exception {
        String toolText = "COMPACTION_TOOL_PRIVATE_甲" + "界".repeat(6_000);
        String summary = """
                ## 当前目标与成功条件
                COMPACTION_SUMMARY_PRIVATE_乙：完成离线任务。
                ## 用户要求与已确认决定
                只读取本地文件。
                ## 已完成工作及证据
                已观察三份离线图片工具结果。
                ## 未解决问题与下一步
                继续完成 worker 工作。
                """.trim();
        var events = new CopyOnWriteArrayList<Event>();
        var summaryEventCursors = new ArrayList<Integer>();
        var script = new ScriptClient(text(singlePlan()), tools(
                // 参数互不相同：三次相同调用会被重复检测判定为同一动作重复，额外注入提醒
                call("image-one", "read_file", "{\"path\":\"input.txt\",\"limit\":1}"),
                call("image-two", "read_file", "{\"path\":\"input.txt\",\"limit\":2}"),
                call("image-three", "read_file", "{\"path\":\"input.txt\",\"limit\":3}")),
                text(summary), text("compacted-worker-result"), review(true));
        var client = new BaseClient() {
            @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
                if (messages.get(0).content().equals("你是一个对话摘要助手，只输出摘要本身，不输出元描述。")) {
                    summaryEventCursors.add(events.size());
                }
                return script.chat(messages, tools, listener);
            }
        };
        // Scripted image-bearing tool outputs exercise the native tool-result/user-message append path.
        // There is no external image request and no injected conversation-history or observation event.
        var registry = new ToolRegistry() {
            @Override public ToolOutput executeToolOutput(String name, String argumentsJson) {
                ToolOutput actual = super.executeToolOutput(name, argumentsJson);
                if (!"read_file".equals(name) || !actual.successful()) return actual;
                return new ToolOutput(toolText, List.of(LlmClient.ContentPart.imageBase64(
                        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jG3sAAAAASUVORK5CYII=", "image/png")));
            }
        };
        var orchestrator = observed(client, registry, events::add);
        registry.setContextProfile(ContextProfile.custom(8_000));
        assertTrue(orchestrator.runExplicitTask(GOAL, GOAL).contains("compacted-worker-result"));
        assertEquals(5, script.requests.size()); assertEquals(1, summaryEventCursors.size());
        var compacted = only(events, HistoryCompacted.class);
        assertEquals(AgentRole.WORKER, compacted.activation().role()); assertEquals(2, compacted.iteration());
        assertFalse(compacted.userMessageIndexValid()); assertTrue(compacted.afterMessages() < compacted.beforeMessages());
        var marker = select(events, CompactionScopeUnsupported.class).stream()
                .filter(event -> event.activation().equals(compacted.activation()) && event.iteration() == compacted.iteration())
                .findFirst().orElseThrow();
        assertFalse(marker.sessionMemoryEnabled()); assertTrue(marker.fullSummaryThresholdReached());
        assertTrue(events.indexOf(marker) < summaryEventCursors.get(0), "marker must precede the actual summary request");
        assertTrue(summaryEventCursors.get(0) <= events.indexOf(compacted), "history change is observed only after summary returns");
        assertTrue(marker.elapsedNanos() <= compacted.elapsedNanos());
        var input = select(events, ActivationInputPrepared.class).stream()
                .filter(event -> event.activation().equals(compacted.activation())).findFirst().orElseThrow();
        assertEquals(1, input.userMessageIndex());
        assertEquals(TextFingerprint.of(script.requests.get(1).get(1).content()), input.userText());
        assertTrue(script.requests.get(2).get(1).content().contains(toolText));
        assertTrue(script.exposedTools.get(2).isEmpty(), "native full-summary call does not expose tools");
        assertEquals("[已压缩的历史对话摘要]\n" + summary, script.requests.get(3).get(1).content());
        assertNotEquals(input.userText(), TextFingerprint.of(script.requests.get(3).get(1).content()));
        assertEquals(3, script.requests.get(3).stream().filter(message -> message.contentParts() != null
                && message.contentParts().stream().anyMatch(LlmClient.ContentPart::isImage)).count());
        assertEquals(List.of(1, 1, 1), only(events, ToolBatchReturned.class).results().stream().map(ToolResult::imagePartCount).toList());
        assertEquals(RunExitReason.COMPLETED, only(events, RunExited.class).reason());
        assertNoRaw(events, toolText, summary, "compacted-worker-result");
    }

    @Test void deniedToolsAndRepeatedCallIdsKeepPostPolicyOrderAcrossIterationsAndActivations() throws Exception {
        String read = "{\"path\":\"input.txt\"}", denied = "{\"url\":\"https://untrusted.invalid/PRIVATE_TOOL_ARG\"}";
        var batch = tools(call("same-call", "read_file", read), call("same-denied", "web_fetch", denied));
        var client = new ScriptClient(text(singlePlan()), batch, tools(call("same-call", "read_file", read)), text("first worker"),
                review(false), batch, text("second worker"), review(true));
        var events = new CopyOnWriteArrayList<Event>(); var registry = new RecordingRegistry();
        assertTrue(observed(client, registry, events::add).runExplicitTask(GOAL, GOAL).startsWith("✅"));
        var batches = select(events, ToolBatchReturned.class);
        assertEquals(3, batches.size()); assertEquals(3, registry.results.size());
        assertEquals(List.of(1, 2, 1), batches.stream().map(ToolBatchReturned::iteration).toList());
        assertEquals(batches.get(0).activation(), batches.get(1).activation());
        assertNotEquals(batches.get(0).activation().activationId(), batches.get(2).activation().activationId());
        for (int index = 0; index < batches.size(); index++) {
            var actual = batches.get(index).results(); var raw = registry.results.get(index);
            assertEquals(raw.size(), actual.size());
            for (int ordinal = 0; ordinal < raw.size(); ordinal++) {
                var expected = raw.get(ordinal); var event = actual.get(ordinal);
                assertEquals(ordinal, event.ordinal()); assertEquals(expected.id(), event.callId()); assertEquals(expected.name(), event.name());
                assertEquals(TextFingerprint.of(expected.argumentsJson()), event.arguments()); assertEquals(TextFingerprint.of(expected.result()), event.result());
                assertEquals(expected.successful(), event.successful()); assertEquals(expected.timedOut(), event.timedOut());
            }
            assertTrue(actual.get(0).successful());
            if (actual.size() == 2) assertFalse(actual.get(1).successful());
        }
        assertNoRaw(events, read, denied, "TOOL_RAW_RESULT_隐私甲");
    }

    @Test void subsequentRunsReuseActorsOnlyWithFreshRunAndActivationAndClearedHistories() throws Exception {
        var client = new ScriptClient(text(singlePlan()), text("first-run-result"), review(true), text(singlePlan()), text("second-run-result"), review(true));
        var events = new CopyOnWriteArrayList<Event>(); var orchestrator = observed(client, new ToolRegistry(), events::add);
        assertTrue(orchestrator.runExplicitTask(GOAL, GOAL).contains("first-run-result"));
        assertTrue(orchestrator.runExplicitTask(GOAL, GOAL).contains("second-run-result"));
        var starts = select(events, RunStarted.class); assertEquals(2, starts.size()); assertNotEquals(starts.get(0).runId(), starts.get(1).runId());
        for (AgentRole role : AgentRole.values()) {
            var entries = activations(events, role); assertEquals(2, entries.size());
            var first = entries.get(0).activation(); var second = entries.get(1).activation();
            assertEquals(first.actorInstanceId(), second.actorInstanceId()); assertNotEquals(first.activationId(), second.activationId());
            assertNotEquals(first.runId(), second.runId()); assertTrue(second.historyGeneration() > first.historyGeneration());
            assertEquals(1, entries.get(0).historyMessagesBefore()); assertEquals(1, entries.get(1).historyMessagesBefore());
        }
        assertTrue(client.requests.get(4).stream().noneMatch(message -> "first-run-result".equals(message.content())));
    }

    @Test void nullEmptyAndUtf8FingerprintsDoNotCollapseDifferentText() {
        assertEquals(new TextFingerprint(false, 0, null), TextFingerprint.of(null));
        assertNotEquals(TextFingerprint.of(null), TextFingerprint.of(""));
        assertEquals(3, TextFingerprint.of("甲").utf8Bytes());
        assertNotEquals(TextFingerprint.of("甲"), TextFingerprint.of("甲\n"));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", TextFingerprint.of("").sha256());
    }

    private AgentOrchestrator observed(LlmClient client, ToolRegistry registry, TeamExecutionObserver observer) throws IOException {
        var orchestrator = agent(client, registry);
        orchestrator.setExecutionObserver(event -> {
            try {
                assertEquals(event, TeamObservationWire.decode(event.getClass().getSimpleName(), TeamObservationWire.encode(event)),
                        "actual native observation must round-trip exactly");
            } catch (IOException | RuntimeException | AssertionError error) {
                // Native isolation also catches AssertionError. Assert this independent list after the run.
                codecFailures.add(error);
            }
            observer.onEvent(event);
        });
        return orchestrator;
    }
    private static <T extends Event> List<T> select(List<Event> events, Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }
    private static <T extends Event> T only(List<Event> events, Class<T> type) {
        var matching = select(events, type); assertEquals(1, matching.size()); return matching.get(0);
    }
    private static List<ActivationEntered> activations(List<Event> events, AgentRole role) {
        return select(events, ActivationEntered.class).stream().filter(event -> event.activation().role() == role).toList();
    }
    private static StepEntered entered(List<Event> events, String step) {
        return select(events, StepEntered.class).stream().filter(event -> event.stepId().equals(step)).findFirst().orElseThrow();
    }
    private static StepExited exited(List<Event> events, String step) {
        return select(events, StepExited.class).stream().filter(event -> event.stepId().equals(step)).findFirst().orElseThrow();
    }
    private static long overlap(List<Event> events, String first, String second) {
        return Math.min(exited(events, first).elapsedNanos(), exited(events, second).elapsedNanos())
                - Math.max(entered(events, first).elapsedNanos(), entered(events, second).elapsedNanos());
    }
    private static void assertNoRaw(List<Event> events, String... sensitiveTexts) throws IOException {
        String json = JSON.writeValueAsString(events);
        for (String text : sensitiveTexts) {
            assertFalse(json.contains(text), "event contains raw private text");
            String encoded = JSON.writeValueAsString(text);
            assertFalse(json.contains(encoded.substring(1, encoded.length() - 1)), "event contains JSON-escaped raw private text");
        }
    }

    private AgentOrchestrator agent(LlmClient client, ToolRegistry registry) throws IOException {
        Files.writeString(temp.resolve("input.txt"), "TOOL_RAW_RESULT_隐私甲\n");
        registry.setProjectPath(temp.toString());
        var orchestrator = new AgentOrchestrator(client, registry,
                new MemoryManager(client, 4096, 1_000_000, new LongTermMemory(temp.resolve("memory").toFile())), quiet());
        nativeAgents.add(orchestrator);
        return orchestrator;
    }

    private static PrintStream quiet() { return new PrintStream(OutputStream.nullOutputStream()); }
    private static String singlePlan() {
        return "{\"steps\":[{\"id\":\"work\",\"description\":\"WORK_PRIVATE_DESCRIPTION_丁\",\"type\":\"ANALYSIS\",\"dependencies\":[]}]}";
    }
    private static String parallelPlan(boolean serialized) {
        return "{\"steps\":[{\"id\":\"code\",\"description\":\"CODE\",\"type\":\"FILE_WRITE\",\"dependencies\":[]},"
                + "{\"id\":\"tests\",\"description\":\"TEST\",\"type\":\"FILE_WRITE\",\"dependencies\":"
                + (serialized ? "[\"code\"]" : "[]") + "},"
                + "{\"id\":\"docs\",\"description\":\"DOC\",\"type\":\"FILE_WRITE\",\"dependencies\":[\"code\",\"tests\"]}]}";
    }
    private static LlmClient.ChatResponse text(String value) {
        return new LlmClient.ChatResponse("assistant", value, null, 1, 1);
    }
    private static LlmClient.ChatResponse review(boolean approved) {
        return text("{\"approved\":" + approved + ",\"summary\":\"REVIEW_PRIVATE_FEEDBACK_戊\",\"issues\":[]} ");
    }
    private static LlmClient.ToolCall call(String id, String name, String arguments) {
        return new LlmClient.ToolCall(id, new LlmClient.ToolCall.Function(name, arguments));
    }
    private static LlmClient.ChatResponse tools(LlmClient.ToolCall... calls) {
        return new LlmClient.ChatResponse("assistant", "", List.of(calls), 1, 1);
    }
    private static String user(List<LlmClient.Message> messages) {
        for (int index = messages.size() - 1; index >= 0; index--)
            if ("user".equals(messages.get(index).role())) return messages.get(index).content();
        throw new IllegalStateException("missing scripted input");
    }
    private static String task(String input) {
        var matcher = Pattern.compile("当前任务：([^\\r\\n]+)").matcher(input);
        if (!matcher.find()) throw new IllegalStateException("missing worker task in native input");
        return matcher.group(1);
    }
    private static void await(CountDownLatch barrier) {
        try {
            if (!barrier.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("offline control barrier timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("offline control interrupted", error);
        }
    }

    private abstract static class BaseClient implements LlmClient {
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }
        @Override public String getModelName() { return "scripted-team-control"; }
        @Override public String getProviderName() { return "offline-control"; }
        @Override public int maxContextWindow() { return 1_000_000; }
    }

    private static final class ScriptClient extends BaseClient {
        final Queue<Object> responses;
        final List<List<Message>> requests = new ArrayList<>();
        final List<List<Tool>> exposedTools = new ArrayList<>();
        ScriptClient(Object... responses) { this.responses = new ArrayDeque<>(List.of(responses)); }
        @Override public synchronized ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            requests.add(List.copyOf(messages)); exposedTools.add(tools == null ? List.of() : List.copyOf(tools));
            Object next = responses.poll();
            if (next instanceof IOException error) throw error;
            if (next instanceof RuntimeException error) throw error;
            if (!(next instanceof ChatResponse result)) throw new IOException("offline script exhausted");
            return result;
        }
    }

    private static final class ParallelClient extends BaseClient {
        final boolean serialized;
        final CountDownLatch branches = new CountDownLatch(2);
        final Set<Long> branchThreads = ConcurrentHashMap.newKeySet();
        final Map<String, AtomicInteger> iterations = new ConcurrentHashMap<>();
        final Map<String, String> inputs = new ConcurrentHashMap<>();
        final AtomicInteger requests = new AtomicInteger();
        volatile boolean docConsumedBoth;
        ParallelClient(boolean serialized) { this.serialized = serialized; }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            requests.incrementAndGet();
            String input = user(messages);
            if (input.startsWith("请为以下任务制定执行计划")) return text(parallelPlan(serialized));
            if (input.startsWith("原始任务：")) return review(true);
            String label = task(input);
            inputs.putIfAbsent(label, input);
            int iteration = iterations.computeIfAbsent(label, unused -> new AtomicInteger()).incrementAndGet();
            if (iteration == 1) {
                if (!label.equals("DOC")) {
                    branchThreads.add(Thread.currentThread().getId());
                    if (!serialized) { branches.countDown(); await(branches); }
                } else docConsumedBoth = input.contains(CODE) && input.contains(TEST);
                String content = label.equals("CODE") ? CODE : label.equals("TEST") ? TEST : CODE + "\n" + TEST;
                return tools(call("repeated-call-id", "write_file", JSON.writeValueAsString(
                        Map.of("path", label.toLowerCase() + ".txt", "content", content))));
            }
            if (iteration != 2) throw new IOException("unexpected worker continuation");
            return text(label.equals("CODE") ? CODE : label.equals("TEST") ? TEST : DOCUMENT);
        }
    }

    private static final class RecordingRegistry extends ToolRegistry {
        final List<List<ToolExecutionResult>> results = new CopyOnWriteArrayList<>();
        @Override public void onPolicyToolResults(List<ToolExecutionResult> completed) {
            results.add(List.copyOf(completed));
        }
    }
}
