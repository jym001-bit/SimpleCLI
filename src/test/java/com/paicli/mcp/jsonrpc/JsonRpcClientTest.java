package com.paicli.mcp.jsonrpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.mcp.transport.McpTransport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class JsonRpcClientTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void pairsResponseByNumericId() throws Exception {
        LoopbackTransport transport = new LoopbackTransport("""
                {"jsonrpc":"2.0","id":1,"result":{"ok":true}}
                """);
        JsonRpcClient client = new JsonRpcClient(transport);

        JsonNode result = client.request("ping", MAPPER.createObjectNode(), 1);

        assertTrue(result.path("ok").asBoolean());
        assertTrue(transport.sent.path("id").isNumber());
    }

    @Test
    void mapsJsonRpcErrorToException() {
        LoopbackTransport transport = new LoopbackTransport("""
                {"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"missing"}}
                """);
        JsonRpcClient client = new JsonRpcClient(transport);

        JsonRpcException error = assertThrows(JsonRpcException.class,
                () -> client.request("missing", MAPPER.createObjectNode(), 1));
        assertEquals(-32601, error.code());
    }

    @Test
    void serverRequestWithCollidingIdDoesNotCompleteOurPendingRequest() throws Exception {
        ManualTransport transport = new ManualTransport();
        JsonRpcClient client = new JsonRpcClient(transport);
        CompletableFuture<JsonNode> call = CompletableFuture.supplyAsync(() -> {
            try {
                return client.request("tools/list", MAPPER.createObjectNode(), 3);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        JsonNode ours = transport.awaitSent(m -> m.has("method"));

        // server 主动发 ping，id 恰好和我们的请求相同
        transport.deliver("{\"jsonrpc\":\"2.0\",\"id\":" + ours.path("id").asLong() + ",\"method\":\"ping\"}");
        JsonNode pong = transport.awaitSent(m -> !m.has("method"));
        assertEquals(ours.path("id").asLong(), pong.path("id").asLong());
        assertTrue(pong.path("result").isObject());
        assertFalse(call.isDone(), "server 的 ping 不能被当成我们请求的回复");

        transport.deliver("{\"jsonrpc\":\"2.0\",\"id\":" + ours.path("id").asLong() + ",\"result\":{\"tools\":[]}}");
        assertTrue(call.get(2, TimeUnit.SECONDS).path("tools").isArray());
        client.close();
    }

    @Test
    void unsupportedServerRequestGetsMethodNotFoundWithOriginalId() throws Exception {
        ManualTransport transport = new ManualTransport();
        JsonRpcClient client = new JsonRpcClient(transport);

        transport.deliver("{\"jsonrpc\":\"2.0\",\"id\":\"req-9\",\"method\":\"sampling/createMessage\",\"params\":{}}");

        JsonNode reply = transport.awaitSent(m -> true);
        assertEquals("req-9", reply.path("id").asText(), "回复要原样带回 server 的 id（包括字符串类型）");
        assertEquals(-32601, reply.path("error").path("code").asInt());
        client.close();
    }

    @Test
    void notificationsStillReachListeners() throws Exception {
        ManualTransport transport = new ManualTransport();
        JsonRpcClient client = new JsonRpcClient(transport);
        AtomicReference<String> method = new AtomicReference<>();
        client.onNotification(n -> method.set(n.path("method").asText()));

        transport.deliver("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}");

        assertEquals("notifications/tools/list_changed", method.get());
        assertTrue(transport.sent.isEmpty(), "通知不需要回复");
        client.close();
    }

    @Test
    void responseWithNumericStringIdIsMatched() throws Exception {
        LoopbackTransport transport = new LoopbackTransport("""
                {"jsonrpc":"2.0","id":"1","result":{"ok":true}}
                """);
        JsonRpcClient client = new JsonRpcClient(transport);

        assertTrue(client.request("ping", MAPPER.createObjectNode(), 1).path("ok").asBoolean());
    }

    private static final class ManualTransport implements McpTransport {
        private final List<JsonNode> sent = new CopyOnWriteArrayList<>();
        private Consumer<JsonNode> listener;

        void deliver(String json) throws IOException {
            listener.accept(MAPPER.readTree(json));
        }

        JsonNode awaitSent(Predicate<JsonNode> filter) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < deadline) {
                for (JsonNode message : sent) {
                    if (filter.test(message)) {
                        sent.remove(message);
                        return message;
                    }
                }
                Thread.sleep(10);
            }
            throw new AssertionError("2 秒内没有发出预期消息，已发送: " + sent);
        }

        @Override
        public void send(JsonNode message) {
            sent.add(message);
        }

        @Override
        public void onReceive(Consumer<JsonNode> listener) {
            this.listener = listener;
        }

        @Override
        public void close() {
        }
    }

    private static final class LoopbackTransport implements McpTransport {
        private final String response;
        private Consumer<JsonNode> listener;
        private JsonNode sent;

        private LoopbackTransport(String response) {
            this.response = response;
        }

        @Override
        public void send(JsonNode message) throws IOException {
            sent = message;
            listener.accept(MAPPER.readTree(response));
        }

        @Override
        public void onReceive(Consumer<JsonNode> listener) {
            this.listener = listener;
        }

        @Override
        public void close() {
        }
    }
}
