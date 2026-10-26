package com.paicli.tool;

/**
 * 交互式命令沙箱的配置结果，启动时展示给用户。
 *
 * @param mode    用户配置的模式
 * @param active  是否真正启用了沙箱
 * @param backend 启用的后端（macOS Seatbelt / Linux bubblewrap），未启用时为空串
 * @param message 给用户看的一行说明；OFF 时为空串，保持与旧版本一致的安静启动
 */
public record CommandSandboxStatus(CommandSandboxMode mode, boolean active, String backend, String message) {
    public CommandSandboxStatus {
        backend = backend == null ? "" : backend;
        message = message == null ? "" : message;
    }

    static CommandSandboxStatus off() {
        return new CommandSandboxStatus(CommandSandboxMode.OFF, false, "", "");
    }
}
