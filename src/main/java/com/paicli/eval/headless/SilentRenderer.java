package com.paicli.eval.headless;

import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.llm.LlmClient;
import com.paicli.render.Renderer;
import com.paicli.render.StatusInfo;

import java.io.PrintStream;
import java.util.List;

/**
 * headless 用的静默 Renderer：所有交互/装饰输出全部丢弃，只保留 {@link #stream()} 作为文本出口。
 *
 * <p>不依赖 PlainRenderer 的构造方式，避免被交互式实现的签名变化牵连。</p>
 */
final class SilentRenderer implements Renderer {

    private final PrintStream out;

    SilentRenderer(PrintStream out) {
        this.out = out;
    }

    @Override
    public void start() {
    }

    @Override
    public void close() {
    }

    @Override
    public PrintStream stream() {
        return out;
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
        return ApprovalResult.reject("headless 模式不进行终端交互审批");
    }

    @Override
    public int openPalette(String title, List<String> items) {
        return -1;
    }
}
