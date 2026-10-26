package com.paicli.cli;

import com.paicli.hitl.HitlHandler;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 切换会话模式：把 {@link SessionMode} 落到审批档位和 plan 开关上。
 * Shift+Tab、/mode 命令和 TUI 共用同一个实例，状态只有这一份。
 */
public final class SessionModeController {

    private final HitlHandler hitl;
    private final AtomicBoolean planMode = new AtomicBoolean(false);

    public SessionModeController(HitlHandler hitl) {
        this.hitl = Objects.requireNonNull(hitl, "hitl");
    }

    public SessionMode current() {
        return SessionMode.of(hitl, planMode.get());
    }

    /** 每条普通输入是否走 Plan-and-Execute。 */
    public boolean planMode() {
        return planMode.get();
    }

    public SessionMode cycle() {
        SessionMode next = current().next();
        apply(next);
        return next;
    }

    public void apply(SessionMode mode) {
        Objects.requireNonNull(mode, "mode");
        hitl.switchConfirmationMode(mode == SessionMode.ASK ? "on" : "default");
        planMode.set(mode == SessionMode.PLAN);
    }

    /** /mode 命令：不带参数时列出模式，带参数时切换；返回给用户的提示。 */
    public String handleCommand(String argument) {
        if (argument == null || argument.isBlank()) {
            StringBuilder out = new StringBuilder("当前模式：").append(describe(current()))
                    .append("\n   Shift+Tab 按 auto → plan → ask 循环切换，也可以直接输入：");
            for (SessionMode mode : SessionMode.values()) {
                out.append("\n   /mode ").append(String.format("%-5s", mode.id())).append(" - ").append(mode.description());
            }
            return out.toString();
        }
        return SessionMode.parse(argument)
                .map(mode -> {
                    apply(mode);
                    return "已切换到 " + describe(mode);
                })
                .orElse("未知模式：" + argument.trim() + "，可选 auto / plan / ask");
    }

    public static String describe(SessionMode mode) {
        return mode.id() + "（" + mode.description() + "）";
    }
}
