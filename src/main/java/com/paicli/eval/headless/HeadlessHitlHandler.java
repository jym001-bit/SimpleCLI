package com.paicli.eval.headless;

import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.hitl.HitlHandler;

/**
 * headless 场景的审批处理器：没有终端，只能按策略自动放行或自动拒绝。
 *
 * <ul>
 *   <li>{@code auto}：危险工具直接批准；</li>
 *   <li>{@code deny-dangerous}：危险工具一律拒绝，让模型看到拒绝结果后自行调整。</li>
 * </ul>
 */
final class HeadlessHitlHandler implements HitlHandler {

    private final boolean autoApprove;
    private volatile boolean enabled = true;

    HeadlessHitlHandler(boolean autoApprove) {
        this.autoApprove = autoApprove;
    }

    @Override
    public ApprovalResult requestApproval(ApprovalRequest request) {
        if (autoApprove) {
            return ApprovalResult.approve();
        }
        String tool = request == null ? "unknown" : request.toolName();
        return ApprovalResult.reject("headless 审批策略 deny-dangerous 拒绝: " + tool);
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
