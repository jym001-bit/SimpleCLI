package com.paicli.cli;

import com.paicli.hitl.HitlHandler;

import java.util.Locale;
import java.util.Optional;

/**
 * 交互会话的工作模式，Shift+Tab 按 auto → plan → ask 循环切换。
 *
 * <p>模式只是现有开关的组合，不另外保存一份权限状态：审批档位存在 {@link HitlHandler} 里，
 * plan 由 {@link SessionModeController} 记录。用 /hitl 改了审批档位，显示的模式会跟着变。</p>
 */
public enum SessionMode {
    /**
     * 启动默认，不打断用户：读写文件直接执行；Shell 命令由模型分类器审查，放行就执行；
     * 不放行的命令以及 MCP 工具、回滚快照都交回模型处理，同一轮连续被拦 3 次才转人工。
     */
    AUTO("auto", "自动审查：低风险命令直接执行，有风险的交回模型处理，不打断你"),
    /** 每条输入走 Plan-and-Execute：先生成计划，审阅确认后再执行；审批同 auto。 */
    PLAN("plan", "每条输入先规划，审阅确认后再执行"),
    /** 审批：写文件、编辑文件、执行命令、创建项目、回滚快照和 MCP 工具都要确认。 */
    ASK("ask", "审批：全部危险操作都要确认");

    private final String id;
    private final String description;

    SessionMode(String id, String description) {
        this.id = id;
        this.description = description;
    }

    public String id() {
        return id;
    }

    public String description() {
        return description;
    }

    /** 状态栏文案，保持 ASCII，状态栏按字符数计算列宽。 */
    public String statusLabel() {
        return name() + " shift+tab to cycle";
    }

    public SessionMode next() {
        SessionMode[] modes = values();
        return modes[(ordinal() + 1) % modes.length];
    }

    public static Optional<SessionMode> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (SessionMode mode : values()) {
            if (mode.id.equals(normalized)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }

    /** 由审批档位和 plan 开关推出当前模式。 */
    static SessionMode of(HitlHandler hitl, boolean planMode) {
        if (planMode) {
            return PLAN;
        }
        return hitl.isEnabled() ? ASK : AUTO;
    }
}
