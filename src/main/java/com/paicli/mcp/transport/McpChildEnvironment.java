package com.paicli.mcp.transport;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * stdio MCP 子进程的环境变量：只从 PaiCLI 自己的环境里带过去运行必需的变量，
 * 再叠加配置里给这个 server 单独写的 env。用户机器上的 API Key 默认不传给 MCP 服务，
 * 需要的话在配置里显式写 {@code "env": {"GITHUB_TOKEN": "${GITHUB_TOKEN}"}}。
 */
public final class McpChildEnvironment {

    /** 按大写比较：Windows 的变量名不区分大小写，代理变量在 Unix 上也常见小写写法。 */
    private static final Set<String> INHERITED = Set.of(
            // 基础运行环境
            "PATH", "HOME", "USER", "LOGNAME", "SHELL", "TERM", "TZ",
            "LANG", "LANGUAGE", "TMPDIR", "TMP", "TEMP",
            "XDG_CONFIG_HOME", "XDG_CACHE_HOME", "XDG_DATA_HOME", "XDG_RUNTIME_DIR",
            "DISPLAY", "WAYLAND_DISPLAY", "JAVA_HOME",
            // 代理与证书：国内跑 npx / uvx 常常离不开
            "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY",
            "NODE_EXTRA_CA_CERTS", "SSL_CERT_FILE", "SSL_CERT_DIR",
            "NPM_CONFIG_REGISTRY",
            // Windows 下进程正常启动需要的变量
            "SYSTEMROOT", "SYSTEMDRIVE", "WINDIR", "COMSPEC", "PATHEXT",
            "APPDATA", "LOCALAPPDATA", "USERPROFILE", "USERNAME", "HOMEDRIVE", "HOMEPATH",
            "PROGRAMFILES", "PROGRAMFILES(X86)", "PROGRAMDATA",
            "PROCESSOR_ARCHITECTURE", "NUMBER_OF_PROCESSORS"
    );

    private McpChildEnvironment() {
    }

    public static boolean isInherited(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        return INHERITED.contains(upper) || upper.startsWith("LC_");
    }

    public static Map<String, String> build(Map<String, String> parent, Map<String, String> configured) {
        Map<String, String> result = new LinkedHashMap<>();
        if (parent != null) {
            parent.forEach((name, value) -> {
                if (value != null && isInherited(name)) {
                    result.put(name, value);
                }
            });
        }
        if (configured != null) {
            configured.forEach((name, value) -> {
                if (name != null && value != null) {
                    result.put(name, value);
                }
            });
        }
        return result;
    }
}
