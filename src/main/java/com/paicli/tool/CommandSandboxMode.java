package com.paicli.tool;

import java.util.Locale;

/**
 * 交互式 PaiCLI 的 {@code execute_command} 沙箱模式。
 *
 * <ul>
 *   <li>{@link #OFF}（默认）：与之前完全一致，命令直接在宿主执行；</li>
 *   <li>{@link #AUTO}：探测到可用沙箱（macOS Seatbelt / Linux bubblewrap）就启用，
 *       不可用时打印提示并回退到直接执行；</li>
 *   <li>{@link #REQUIRED}：必须有沙箱，不可用时 {@code execute_command} 直接拒绝执行。</li>
 * </ul>
 *
 * <p>默认保持关闭：沙箱没有网络，HOME 也被重定向到工作区内的私有目录，
 * {@code mvn} / {@code npm install} / {@code git push} 这类需要联网或读取 {@code ~/.m2}、
 * {@code ~/.gitconfig} 的命令在沙箱里会失败。</p>
 */
public enum CommandSandboxMode {
    OFF,
    AUTO,
    REQUIRED;

    public static final String PROPERTY = "paicli.command.sandbox";
    public static final String ENV = "PAICLI_COMMAND_SANDBOX";

    public static CommandSandboxMode fromConfiguration() {
        String raw = System.getProperty(PROPERTY);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(ENV);
        }
        return parse(raw);
    }

    /** off/false/0/no/空 → OFF；auto/on/true/1/yes → AUTO；required/strict → REQUIRED；无法识别按 OFF。 */
    public static CommandSandboxMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return OFF;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "auto", "on", "true", "1", "yes" -> AUTO;
            case "required", "require", "strict" -> REQUIRED;
            default -> OFF;
        };
    }
}
