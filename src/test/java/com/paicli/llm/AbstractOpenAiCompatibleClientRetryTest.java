package com.paicli.llm;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractOpenAiCompatibleClientRetryTest {

    @Test
    void runCancellationAbortsOnlyItsHttpCallWithoutInterruptingTheWaitingThread() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE));
            server.enqueue(success("unrelated request completed").setBodyDelay(1, java.util.concurrent.TimeUnit.SECONDS));
            TestClient client = client(server);
            var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
            var firstToken = new java.util.concurrent.atomic.AtomicReference<com.paicli.runtime.CancellationToken>();
            try {
                var first = executor.submit(() -> {
                    var token = com.paicli.runtime.CancellationContext.startRun();
                    firstToken.set(token);
                    try { return client.chat(List.of(LlmClient.Message.user("cancel this request")), null); }
                    finally { com.paicli.runtime.CancellationContext.clear(token); }
                });
                org.junit.jupiter.api.Assertions.assertNotNull(server.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS));
                var second = executor.submit(() -> {
                    var token = com.paicli.runtime.CancellationContext.startRun();
                    try { return client.chat(List.of(LlmClient.Message.user("keep this request")), null); }
                    finally { com.paicli.runtime.CancellationContext.clear(token); }
                });
                org.junit.jupiter.api.Assertions.assertNotNull(server.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS));

                firstToken.get().cancel(); // Deliberately do not future.cancel/interrupt the worker.

                var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> first.get(3, java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof IOException);
                assertEquals("unrelated request completed", second.get(3, java.util.concurrent.TimeUnit.SECONDS).content());
                assertEquals(2, server.getRequestCount(), "取消不得触发新 HTTP 重试或取消另一任务的请求");
            } finally {
                if (firstToken.get() != null) firstToken.get().cancel();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void alreadyCancelledRunDoesNotOpenAnHttpRequest() throws Exception {
        var token = com.paicli.runtime.CancellationContext.startRun();
        try (MockWebServer server = new MockWebServer()) {
            TestClient client = client(server);
            token.cancel();
            assertThrows(IOException.class, () -> client.chat(List.of(LlmClient.Message.user("stop")), null));
            assertEquals(0, server.getRequestCount());
        } finally { com.paicli.runtime.CancellationContext.clear(token); }
    }

    @Test
    void exhaustedHttpRetriesDoNotTriggerPlanReplanning() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            TestClient client = client(server);
            String planJson = """
                    {"tasks":[{"id":"x","description":"analyze","type":"ANALYSIS","dependencies":[]},
                    {"id":"y","description":"summarize","type":"ANALYSIS","dependencies":["x"]}]}
                    """.replace("\n", " ").trim();
            server.enqueue(success(planJson.replace("\"", "\\\"")));
            // Extra failures make an erroneous replan fail promptly rather than hang waiting for a response.
            for (int i = 0; i < 9; i++) server.enqueue(new MockResponse().setResponseCode(503).setBody("temporary"));
            var quiet = new java.io.PrintStream(new java.io.ByteArrayOutputStream());
            var reviewedPlan = new java.util.concurrent.atomic.AtomicReference<com.paicli.plan.ExecutionPlan>();
            var agent = new com.paicli.agent.PlanExecuteAgent(client, new com.paicli.tool.ToolRegistry(),
                    null, (goal, plan) -> {
                        reviewedPlan.set(plan);
                        return com.paicli.agent.PlanExecuteAgent.PlanReviewDecision.execute();
                    }, quiet);

            String result = agent.run("分析项目");

            assertEquals(4, server.getRequestCount(), "一次规划 + 三次任务 HTTP 尝试，不能再叠加重规划");
            assertEquals(com.paicli.plan.Task.TaskStatus.SKIPPED, reviewedPlan.get().getTask("task_2").getStatus());
            assertTrue(result.contains("CLIENT_OWNS_RETRIES"));
            assertTrue(result.startsWith("⚠️"));
        }
    }

    private static final LlmRetryPolicy NO_WAIT_RETRY_POLICY = LlmRetryPolicy.forTest(
            3, 0L, 0L, () -> 0.5d, millis -> {
            }, Clock.systemUTC());

    @Test
    void retriesOnlyTransientHttpStatuses() throws Exception {
        int[] transientStatuses = {408, 429, 500, 502, 503, 504};

        try (MockWebServer server = new MockWebServer()) {
            TestClient client = client(server);
            for (int status : transientStatuses) {
                server.enqueue(new MockResponse().setResponseCode(status).setBody("temporary"));
                server.enqueue(success("recovered-" + status));

                LlmClient.ChatResponse response = client.chat(
                        List.of(LlmClient.Message.user("test-" + status)), null);

                assertEquals("recovered-" + status, response.content());
            }
            assertEquals(transientStatuses.length * 2, server.getRequestCount());
        }
    }

    @Test
    void streamedBodyKeepsContentLengthAndIsResentIdenticallyOnRetry() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            TestClient client = client(server);
            server.enqueue(new MockResponse().setResponseCode(503).setBody("temporary"));
            server.enqueue(success("ok"));

            client.chat(List.of(LlmClient.Message.user("中文内容 + emoji 😀 + \"quotes\"")), null);

            okhttp3.mockwebserver.RecordedRequest first = server.takeRequest();
            okhttp3.mockwebserver.RecordedRequest second = server.takeRequest();
            String firstBody = first.getBody().readUtf8();
            assertEquals(firstBody, second.getBody().readUtf8(), "重试要发同样的请求体");
            assertEquals(String.valueOf(first.getBodySize()), first.getHeader("Content-Length"),
                    "照常带准确的 Content-Length");
            assertEquals(null, first.getHeader("Transfer-Encoding"), "不改成分块传输");
            assertEquals("application/json; charset=utf-8", first.getHeader("Content-Type"));
            assertTrue(firstBody.contains("emoji 😀"), "emoji 原样写出，不转义成代理对: " + firstBody);
        }
    }

    @Test
    void doesNotRetryDeterministicHttpErrors() throws Exception {
        int[] deterministicStatuses = {400, 401, 403, 404, 501};

        try (MockWebServer server = new MockWebServer()) {
            TestClient client = client(server);
            for (int status : deterministicStatuses) {
                server.enqueue(new MockResponse().setResponseCode(status).setBody("permanent"));

                IOException failure = assertThrows(IOException.class,
                        () -> client.chat(List.of(LlmClient.Message.user("test-" + status)), null));

                assertTrue(failure.getMessage().contains(String.valueOf(status)));
            }
            assertEquals(deterministicStatuses.length, server.getRequestCount());
        }
    }

    @Test
    void retriesConnectionFailureBeforeResponse() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(success("recovered"));
            AtomicInteger calls = new AtomicInteger();
            OkHttpClient flakyHttpClient = AbstractOpenAiCompatibleClient.SHARED_HTTP_CLIENT.newBuilder()
                    .addInterceptor(chain -> {
                        if (calls.getAndIncrement() == 0) {
                            throw new SocketTimeoutException("synthetic connect timeout");
                        }
                        return chain.proceed(chain.request());
                    })
                    .build();
            TestClient client = new TestClient(
                    server.url("/chat/completions").toString(),
                    NO_WAIT_RETRY_POLICY,
                    flakyHttpClient
            );

            LlmClient.ChatResponse response = client.chat(
                    List.of(LlmClient.Message.user("hello")), null);

            assertEquals("recovered", response.content());
            assertEquals(2, calls.get());
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test
    void retriesIncompleteStreamWhenNoListenerConsumedOutput() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(incompleteStream("discard-me"));
            server.enqueue(success("complete"));

            LlmClient.ChatResponse response = client(server).chat(
                    List.of(LlmClient.Message.user("hello")), null);

            assertEquals("complete", response.content());
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test
    void doesNotReplayIncompleteStreamAfterListenerConsumedOutput() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(incompleteStream("visible-half"));
            server.enqueue(success("must-not-run"));
            StringBuilder streamed = new StringBuilder();

            IOException failure = assertThrows(IOException.class, () -> client(server).chat(
                    List.of(LlmClient.Message.user("hello")),
                    null,
                    new LlmClient.StreamListener() {
                        @Override
                        public void onContentDelta(String delta) {
                            streamed.append(delta);
                        }
                    }));

            assertTrue(failure.getMessage().contains("完成标记前中断"));
            assertEquals("visible-half", streamed.toString());
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test
    void acceptsFinishReasonAsCompletionWithoutDoneSentinel() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("""
                            data: {"choices":[{"delta":{"role":"assistant","content":"complete"},"finish_reason":"stop"}]}

                            """));

            LlmClient.ChatResponse response = client(server).chat(
                    List.of(LlmClient.Message.user("hello")), null);

            assertEquals("complete", response.content());
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test
    void retriesRetryableErrorInsideSuccessfulSseResponse() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("""
                            data: {"error":{"code":"rate_limit_exceeded","message":"slow down"}}

                            """));
            server.enqueue(success("recovered"));

            LlmClient.ChatResponse response = client(server).chat(
                    List.of(LlmClient.Message.user("hello")), null);

            assertEquals("recovered", response.content());
            assertEquals(2, server.getRequestCount());
        }
    }

    private static TestClient client(MockWebServer server) {
        return new TestClient(server.url("/chat/completions").toString(), NO_WAIT_RETRY_POLICY);
    }

    private static MockResponse success(String content) {
        return new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        data: {"choices":[{"delta":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1}}

                        data: [DONE]

                        """.formatted(content));
    }

    private static MockResponse incompleteStream(String content) {
        return new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        data: {"choices":[{"delta":{"role":"assistant","content":"%s"}}]}

                        """.formatted(content));
    }

    private static final class TestClient extends AbstractOpenAiCompatibleClient {
        private final String apiUrl;
        private final LlmRetryPolicy retryPolicy;
        private final OkHttpClient httpClient;

        private TestClient(String apiUrl, LlmRetryPolicy retryPolicy) {
            this(apiUrl, retryPolicy, null);
        }

        private TestClient(String apiUrl, LlmRetryPolicy retryPolicy, OkHttpClient httpClient) {
            this.apiUrl = apiUrl;
            this.retryPolicy = retryPolicy;
            this.httpClient = httpClient;
        }

        @Override
        protected String getApiUrl() {
            return apiUrl;
        }

        @Override
        protected String getModel() {
            return "retry-test";
        }

        @Override
        protected String getApiKey() {
            return "test-key";
        }

        @Override
        LlmRetryPolicy retryPolicy() {
            return retryPolicy;
        }

        @Override
        protected OkHttpClient httpClient() {
            return httpClient == null ? super.httpClient() : httpClient;
        }

        @Override
        public String getModelName() {
            return getModel();
        }

        @Override
        public String getProviderName() {
            return "test";
        }
    }
}
