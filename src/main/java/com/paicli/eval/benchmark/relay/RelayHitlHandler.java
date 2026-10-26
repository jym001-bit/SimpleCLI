package com.paicli.eval.benchmark.relay;

import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.hitl.HitlHandler;

import java.io.IOException;
import java.io.UncheckedIOException;

/** No local allow-all cache: every native MCP approval is decided by the host. */
public final class RelayHitlHandler implements HitlHandler {
    private final RelayLlmClient relay;

    public RelayHitlHandler(RelayLlmClient relay) { this.relay = java.util.Objects.requireNonNull(relay); }

    @Override public ApprovalResult requestApproval(ApprovalRequest request) {
        try {
            var result = relay.requestApproval(request.toolName(), request.arguments());
            return result.approved() ? ApprovalResult.approve() : ApprovalResult.reject(result.reason());
        } catch (IOException error) {
            // Transport failure is an invalid evaluation, not a scripted user rejection or a silent allow.
            throw new UncheckedIOException("host approval channel failed", error);
        }
    }
    @Override public boolean isEnabled() { return true; }
    @Override public void setEnabled(boolean enabled) {
        if (!enabled) throw new IllegalStateException("frozen relay HITL cannot be disabled");
    }
}
