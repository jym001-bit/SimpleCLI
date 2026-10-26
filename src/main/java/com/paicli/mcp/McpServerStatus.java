package com.paicli.mcp;

public enum McpServerStatus {
    STARTING,
    READY,
    DISABLED,
    ERROR,
    /** 项目级配置还没被用户确认，没有启动 */
    UNTRUSTED
}
