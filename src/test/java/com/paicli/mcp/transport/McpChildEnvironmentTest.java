package com.paicli.mcp.transport;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpChildEnvironmentTest {

    @Test
    void keepsRuntimeVariablesAndDropsSecrets() {
        Map<String, String> parent = Map.of(
                "PATH", "/usr/bin",
                "HOME", "/Users/me",
                "LC_ALL", "zh_CN.UTF-8",
                "https_proxy", "http://127.0.0.1:7890",
                "DEEPSEEK_API_KEY", "sk-secret",
                "GITHUB_TOKEN", "ghp-secret");

        Map<String, String> env = McpChildEnvironment.build(parent, Map.of());

        assertEquals("/usr/bin", env.get("PATH"));
        assertEquals("/Users/me", env.get("HOME"));
        assertEquals("zh_CN.UTF-8", env.get("LC_ALL"));
        assertEquals("http://127.0.0.1:7890", env.get("https_proxy"), "小写代理变量也要保留");
        assertFalse(env.containsKey("DEEPSEEK_API_KEY"));
        assertFalse(env.containsKey("GITHUB_TOKEN"));
    }

    @Test
    void configuredEnvIsAddedAndOverridesInherited() {
        Map<String, String> env = McpChildEnvironment.build(
                Map.of("PATH", "/usr/bin"),
                Map.of("GITHUB_TOKEN", "ghp-explicit", "PATH", "/opt/bin"));

        assertEquals("ghp-explicit", env.get("GITHUB_TOKEN"));
        assertEquals("/opt/bin", env.get("PATH"));
    }

    @Test
    void windowsVariablesMatchCaseInsensitively() {
        assertTrue(McpChildEnvironment.isInherited("SystemRoot"));
        assertTrue(McpChildEnvironment.isInherited("Path"));
        assertTrue(McpChildEnvironment.isInherited("ProgramFiles(x86)"));
        assertFalse(McpChildEnvironment.isInherited("OPENAI_API_KEY"));
    }
}
