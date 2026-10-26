package com.paicli.eval.benchmark.relay;

import com.paicli.llm.LlmClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Strict, lossless conversion at the provider-relay isolation boundary. */
final class RelayWireConversions {
    private RelayWireConversions() {
    }

    static List<BenchmarkRelayProtocol.WireMessage> toWireMessages(List<LlmClient.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        if (messages.size() > BenchmarkRelayProtocol.MAX_MESSAGES) {
            throw new IllegalArgumentException("messages exceeds relay limit");
        }
        List<BenchmarkRelayProtocol.WireMessage> result = new ArrayList<>(messages.size());
        for (LlmClient.Message message : messages) {
            Objects.requireNonNull(message, "message");
            validateMessageShape(message.role(), message.toolCalls(), message.toolCallId());
            result.add(new BenchmarkRelayProtocol.WireMessage(
                    message.role(), message.content(), message.reasoningContent(),
                    toWireToolCalls(message.toolCalls()), message.toolCallId(),
                    toWireContentParts(message.contentParts())));
        }
        return List.copyOf(result);
    }

    static List<LlmClient.Message> fromWireMessages(List<BenchmarkRelayProtocol.WireMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        List<LlmClient.Message> result = new ArrayList<>(messages.size());
        for (BenchmarkRelayProtocol.WireMessage message : messages) {
            Objects.requireNonNull(message, "wire message");
            validateMessageShape(message.role(), message.toolCalls(), message.toolCallId());
            result.add(new LlmClient.Message(
                    message.role(), message.content(), message.reasoningContent(),
                    fromWireToolCalls(message.toolCalls()), message.toolCallId(),
                    fromWireContentParts(message.contentParts())));
        }
        return List.copyOf(result);
    }

    static List<BenchmarkRelayProtocol.WireTool> toWireTools(List<LlmClient.Tool> tools) {
        List<LlmClient.Tool> source = tools == null ? List.of() : List.copyOf(tools);
        if (source.size() > BenchmarkRelayProtocol.MAX_TOOLS) {
            throw new IllegalArgumentException("tools exceeds relay limit");
        }
        List<BenchmarkRelayProtocol.WireTool> result = new ArrayList<>(source.size());
        for (LlmClient.Tool tool : source) {
            Objects.requireNonNull(tool, "tool");
            result.add(new BenchmarkRelayProtocol.WireTool(
                    tool.name(), tool.description(), Objects.requireNonNull(tool.parameters(), "tool.parameters")));
        }
        return List.copyOf(result);
    }

    static List<LlmClient.Tool> fromWireTools(List<BenchmarkRelayProtocol.WireTool> tools) {
        List<BenchmarkRelayProtocol.WireTool> source = tools == null ? List.of() : List.copyOf(tools);
        List<LlmClient.Tool> result = new ArrayList<>(source.size());
        for (BenchmarkRelayProtocol.WireTool tool : source) {
            Objects.requireNonNull(tool, "wire tool");
            result.add(new LlmClient.Tool(tool.name(), tool.description(), tool.parameters().deepCopy()));
        }
        return List.copyOf(result);
    }

    static BenchmarkRelayProtocol.WireChatResponse toWireResponse(LlmClient.ChatResponse response) {
        Objects.requireNonNull(response, "response");
        if (!"assistant".equals(normalizeRole(response.role()))) {
            throw new IllegalArgumentException("chat response role must be assistant");
        }
        return new BenchmarkRelayProtocol.WireChatResponse(
                response.role(), response.content(), response.reasoningContent(),
                toWireToolCalls(response.toolCalls()), response.inputTokens(), response.outputTokens(),
                response.cachedInputTokens(), response.resolvedModel(), response.usagePresent());
    }

    static LlmClient.ChatResponse fromWireResponse(BenchmarkRelayProtocol.WireChatResponse response) {
        Objects.requireNonNull(response, "wire response");
        if (!"assistant".equals(normalizeRole(response.role()))) {
            throw new IllegalArgumentException("chat response role must be assistant");
        }
        return new LlmClient.ChatResponse(
                response.role(), response.content(), response.reasoningContent(),
                fromWireToolCalls(response.toolCalls()), response.inputTokens(), response.outputTokens(),
                response.cachedInputTokens(), response.resolvedModel(), response.usagePresent());
    }

    static BenchmarkRelayProtocol.Capabilities capabilitiesOf(LlmClient client) {
        Objects.requireNonNull(client, "client");
        return new BenchmarkRelayProtocol.Capabilities(
                true, client.supportsTools(), true, client.supportsPromptCaching(),
                client.supportsImageInput(), client.promptCacheMode(), client.maxContextWindow());
    }

    private static List<BenchmarkRelayProtocol.WireToolCall> toWireToolCalls(List<LlmClient.ToolCall> calls) {
        List<LlmClient.ToolCall> source = calls == null ? List.of() : List.copyOf(calls);
        if (source.size() > BenchmarkRelayProtocol.MAX_TOOL_CALLS) {
            throw new IllegalArgumentException("tool calls exceeds relay limit");
        }
        List<BenchmarkRelayProtocol.WireToolCall> result = new ArrayList<>(source.size());
        for (LlmClient.ToolCall call : source) {
            Objects.requireNonNull(call, "tool call");
            LlmClient.ToolCall.Function function = Objects.requireNonNull(call.function(), "tool call function");
            result.add(new BenchmarkRelayProtocol.WireToolCall(
                    call.id(), function.name(), function.arguments()));
        }
        return List.copyOf(result);
    }

    private static List<LlmClient.ToolCall> fromWireToolCalls(
            List<BenchmarkRelayProtocol.WireToolCall> calls) {
        List<BenchmarkRelayProtocol.WireToolCall> source = calls == null ? List.of() : List.copyOf(calls);
        List<LlmClient.ToolCall> result = new ArrayList<>(source.size());
        for (BenchmarkRelayProtocol.WireToolCall call : source) {
            Objects.requireNonNull(call, "wire tool call");
            result.add(new LlmClient.ToolCall(
                    call.id(), new LlmClient.ToolCall.Function(call.name(), call.arguments())));
        }
        return List.copyOf(result);
    }

    private static List<BenchmarkRelayProtocol.WireContentPart> toWireContentParts(
            List<LlmClient.ContentPart> parts) {
        List<LlmClient.ContentPart> source = parts == null ? List.of() : List.copyOf(parts);
        if (source.size() > BenchmarkRelayProtocol.MAX_CONTENT_PARTS) {
            throw new IllegalArgumentException("content parts exceeds relay limit");
        }
        List<BenchmarkRelayProtocol.WireContentPart> result = new ArrayList<>(source.size());
        for (LlmClient.ContentPart part : source) {
            Objects.requireNonNull(part, "content part");
            result.add(new BenchmarkRelayProtocol.WireContentPart(
                    part.type(), part.text(), part.imageBase64(), part.imageUrl(), part.mimeType()));
        }
        return List.copyOf(result);
    }

    private static List<LlmClient.ContentPart> fromWireContentParts(
            List<BenchmarkRelayProtocol.WireContentPart> parts) {
        List<BenchmarkRelayProtocol.WireContentPart> source = parts == null ? List.of() : List.copyOf(parts);
        List<LlmClient.ContentPart> result = new ArrayList<>(source.size());
        for (BenchmarkRelayProtocol.WireContentPart part : source) {
            Objects.requireNonNull(part, "wire content part");
            result.add(new LlmClient.ContentPart(
                    part.type(), part.text(), part.imageBase64(), part.imageUrl(), part.mimeType()));
        }
        return List.copyOf(result);
    }

    private static void validateMessageShape(String role, List<?> toolCalls, String toolCallId) {
        String normalized = normalizeRole(role);
        boolean hasCalls = toolCalls != null && !toolCalls.isEmpty();
        boolean hasToolCallId = toolCallId != null && !toolCallId.isBlank();
        if ("tool".equals(normalized)) {
            if (!hasToolCallId || hasCalls) {
                throw new IllegalArgumentException("tool message requires only toolCallId");
            }
        } else if (hasToolCallId) {
            throw new IllegalArgumentException("only tool messages may contain toolCallId");
        }
        if (hasCalls && !"assistant".equals(normalized)) {
            throw new IllegalArgumentException("only assistant messages may contain tool calls");
        }
    }

    private static String normalizeRole(String role) {
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        String normalized = role.toLowerCase(Locale.ROOT);
        if (!List.of("system", "user", "assistant", "tool").contains(normalized)) {
            throw new IllegalArgumentException("unsupported role: " + role);
        }
        return normalized;
    }
}
