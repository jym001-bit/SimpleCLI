package com.paicli.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

public interface McpTransport extends AutoCloseable {
    void send(JsonNode message) throws IOException;

    void onReceive(Consumer<JsonNode> listener);

    /**
     * transport 意外断开（例如 stdio 子进程自己退出了）时回调一次，参数是原因；主动 close 不触发。
     * 注册时如果已经断开，立即回调。
     */
    default void onUnexpectedClose(Consumer<String> listener) {
    }

    default List<String> stderrLines() {
        return List.of();
    }

    default Long processId() {
        return null;
    }

    default String transportName() {
        return "unknown";
    }

    @Override
    void close();
}
