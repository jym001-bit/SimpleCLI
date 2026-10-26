package com.paicli.hitl;

/**
 * HITL 审批交互接口 - 定义人工审批的交互契约
 *
 * 实现类负责与用户交互，收集用户对危险操作的审批决策。
 * 当前仓库提供基于终端的实现（TerminalHitlHandler）。
 *
 * 设计约定：
 * - 审批是同步阻塞操作，实现类需等待用户输入后才返回
 * - 实现类不负责判断"是否需要审批"，该判断由 ApprovalPolicy 负责
 * - 实现类只负责"展示请求 + 收集决策"
 */
public interface HitlHandler {

    /**
     * 向用户展示审批请求并收集决策
     *
     * @param request 待审批的工具调用信息
     * @return 用户的审批决策
     */
    ApprovalResult requestApproval(ApprovalRequest request);

    /**
     * HITL 是否处于启用状态
     * 如果未启用，调用方可以直接跳过审批流程
     */
    boolean isEnabled();

    /**
     * 启用/禁用 HITL 审批
     *
     * @param enabled true 表示启用，false 表示关闭
     */
    void setEnabled(boolean enabled);

    /**
     * 未开启完整 HITL 时，是否仍对高危操作（执行命令、回滚、MCP）请求确认。
     * 默认关闭；只有交互式 CLI 使用的 {@link SwitchableHitlHandler} 保存这个状态。
     */
    default boolean isHighRiskConfirmationEnabled() {
        return false;
    }

    default void setHighRiskConfirmationEnabled(boolean enabled) {
    }

    /** 当前是否有任何一层人工确认在生效（完整 HITL 或高危默认确认）。 */
    default boolean isConfirmationActive() {
        return isEnabled() || isHighRiskConfirmationEnabled();
    }

    /** 给用户看的当前确认档位。 */
    default String confirmationModeLabel() {
        if (isEnabled()) {
            return "审批（写文件、编辑文件、执行命令、创建项目、回滚快照、MCP 工具都要确认）";
        }
        return isHighRiskConfirmationEnabled()
                ? "auto（Shell 命令由模型审查，低风险直接执行；有风险的操作交回模型处理，同一轮连续被拦 3 次才请求确认）"
                : "关闭（仅非交互通道使用）";
    }

    /**
     * /hitl on | default 的统一切换逻辑，CLI 与 TUI 共用；返回给用户的提示。
     * 交互式 CLI 不提供“全部放行”，最宽松的档位就是 auto。
     */
    default String switchConfirmationMode(String mode) {
        switch (mode) {
            case "on" -> {
                setEnabled(true);
                setHighRiskConfirmationEnabled(true);
            }
            case "default" -> {
                setEnabled(false);
                setHighRiskConfirmationEnabled(true);
                clearApprovedAll();
            }
            default -> {
                return "HITL 当前档位：" + confirmationModeLabel()
                        + "\n   /hitl on      - 审批：全部危险操作都确认"
                        + "\n   /hitl default - auto：低风险命令直接执行，有风险的交回模型处理（启动默认）";
            }
        }
        return "HITL 已切换到：" + confirmationModeLabel();
    }

    /** auto 模式下分类器放行了一次调用；交互式实现在终端打一行说明，让用户知道没弹框的原因。 */
    default void onAutoApproved(String toolName, String reason) {
    }

    /** auto 模式下一次调用被拦回给模型；交互式实现在终端打一行说明。 */
    default void onAutoDenied(String toolName, String reason) {
    }

    default boolean isApprovedAllByTool(String toolName) {
        return false;
    }

    default boolean isApprovedAllByServer(String serverName) {
        return false;
    }

    default void clearApprovedAll() {
    }

    default void clearApprovedAllForServer(String serverName) {
    }
}
