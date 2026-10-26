package com.paicli.harness;

import com.paicli.history.ConversationLedger;
import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BetterHarnessRunnerTest {

    @Test
    void runsThreeEvidencePassesAndWritesDurableArtifacts(@TempDir Path tempDir)
            throws Exception {
        Files.writeString(tempDir.resolve("AGENTS.md"), "# Rules");
        ConversationLedger ledger =
                ConversationLedger.open(tempDir.resolve("history"), "session-test");
        StubLlmClient client = new StubLlmClient();
        BetterHarnessRunner runner = new BetterHarnessRunner(
                client,
                tempDir,
                ledger,
                null,
                Clock.fixed(Instant.parse("2026-07-30T12:34:56.789Z"), ZoneOffset.UTC));

        List<BetterHarnessRunner.ProgressEvent> progress = new CopyOnWriteArrayList<>();
        BetterHarnessRunner.RunResult result = runner.run(
                new BetterHarnessOptions(BetterHarnessOptions.Depth.NORMAL, false),
                progress::add);

        assertTrue(result.durable());
        assertEquals(1, result.findingCount());
        assertEquals(4, client.callCount.get());
        assertTrue(Files.isRegularFile(result.reportMarkdownPath()));
        assertTrue(Files.isRegularFile(result.reportHtmlPath()));
        assertTrue(Files.isRegularFile(result.findingsJsonPath()));
        assertTrue(Files.readString(result.findingsJsonPath()).contains("\"BH-001\""));
        assertTrue(Files.readString(result.reportHtmlPath()).contains("<!doctype html>"));
        assertEquals(BetterHarnessRunner.ProgressStage.COLLECTING, progress.get(0).stage());
        assertEquals(3, progress.stream()
                .filter(event -> event.stage() == BetterHarnessRunner.ProgressStage.ANALYZING)
                .filter(event -> event.message().endsWith("审查完成"))
                .count());
        assertTrue(progress.stream().anyMatch(event ->
                event.stage() == BetterHarnessRunner.ProgressStage.RECONCILING
                        && event.completed() == 4
                        && event.total() == 5));
        BetterHarnessRunner.ProgressEvent last = progress.get(progress.size() - 1);
        assertEquals(BetterHarnessRunner.ProgressStage.COMPLETE, last.stage());
        assertEquals(5, last.completed());
        assertEquals(5, last.total());
    }

    @Test
    void inlineModeWritesNoReportDirectory(@TempDir Path tempDir) throws Exception {
        ConversationLedger ledger =
                ConversationLedger.open(tempDir.resolve("history"), "session-test");
        BetterHarnessRunner runner = new BetterHarnessRunner(
                new StubLlmClient(),
                tempDir,
                ledger,
                null,
                Clock.fixed(Instant.parse("2026-07-30T12:34:56.789Z"), ZoneOffset.UTC));

        BetterHarnessRunner.RunResult result = runner.run(
                new BetterHarnessOptions(BetterHarnessOptions.Depth.QUICK, true));

        assertFalse(result.durable());
        assertNotNull(result.reportMarkdown());
        assertFalse(Files.exists(tempDir.resolve(".paicli/better-harness")));
    }

    @Test
    void reportsFirstCompletedLaneBeforeOtherLanesFinish(@TempDir Path tempDir)
            throws Exception {
        ConversationLedger ledger =
                ConversationLedger.open(tempDir.resolve("history"), "session-test");
        CountDownLatch slowLaneRelease = new CountDownLatch(1);
        StubLlmClient client = new StubLlmClient(slowLaneRelease);
        BetterHarnessRunner runner = new BetterHarnessRunner(
                client,
                tempDir,
                ledger,
                null,
                Clock.fixed(Instant.parse("2026-07-30T12:34:56.789Z"), ZoneOffset.UTC));
        CountDownLatch firstLaneProgress = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<BetterHarnessRunner.RunResult> future = executor.submit(() ->
                runner.run(
                        new BetterHarnessOptions(BetterHarnessOptions.Depth.QUICK, true),
                        event -> {
                            if (event.stage() == BetterHarnessRunner.ProgressStage.ANALYZING
                                    && event.completed() == 2) {
                                firstLaneProgress.countDown();
                            }
                        }));
        try {
            assertTrue(firstLaneProgress.await(2, TimeUnit.SECONDS),
                    "first completed specialist should report progress immediately");
            assertFalse(future.isDone(),
                    "the other specialist calls should still be running");
            slowLaneRelease.countDown();
            assertNotNull(future.get(5, TimeUnit.SECONDS));
        } finally {
            slowLaneRelease.countDown();
            executor.shutdownNow();
        }
    }

    private static final class StubLlmClient implements LlmClient {
        private final AtomicInteger callCount = new AtomicInteger();
        private final CountDownLatch slowLaneRelease;

        private StubLlmClient() {
            this(null);
        }

        private StubLlmClient(CountDownLatch slowLaneRelease) {
            this.slowLaneRelease = slowLaneRelease;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            return answer();
        }

        @Override
        public ChatResponse chat(List<Message> messages,
                                 List<Tool> tools,
                                 StreamListener listener) {
            return answer();
        }

        private ChatResponse answer() {
            int call = callCount.incrementAndGet();
            if (call <= 3) {
                if (call > 1 && slowLaneRelease != null) {
                    try {
                        slowLaneRelease.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return new ChatResponse(
                        "assistant",
                        "Candidate: validation execution is unobserved.",
                        List.of(),
                        10,
                        5);
            }
            String json = """
                    {
                      "reportMarkdown": "# PaiCLI Better Harness Report\\n\\n## Findings\\n\\nOne finding.",
                      "findings": [
                        {
                          "id": "BH-001",
                          "severity": "Medium",
                          "dimension": "Change Validation",
                          "title": "Validation outcome is unobserved",
                          "evidence": ["No execution outcome in metadata-only evidence"],
                          "impact": "Confidence is limited",
                          "repair": "Record validation outcomes",
                          "acceptanceChecks": ["A later episode links a passing test result"]
                        }
                      ]
                    }
                    """;
            return new ChatResponse("assistant", json, List.of(), 20, 10);
        }

        @Override
        public String getModelName() {
            return "stub";
        }

        @Override
        public String getProviderName() {
            return "stub";
        }
    }
}
