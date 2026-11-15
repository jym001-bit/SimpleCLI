package com.paicli.tool;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W3 环境净化回归组：只测现有 {@link ToolRegistry#removeSensitiveCommandEnvironment(Map)}
 * 和 {@link ToolRegistry#isSensitiveEnvironmentName(String)} 的当前行为。
 *
 * <p>不启动子进程、不调用模型；只检查变量名是否存在，值统一用占位符。</p>
 */
class ToolRegistryEnvSanitizeTest {

    private static Map<String, String> environment(String... names) {
        Map<String, String> env = new LinkedHashMap<>();
        for (String name : names) {
            env.put(name, "placeholder");
        }
        return env;
    }

    @Test
    void removesKnownProviderAndSecretNames() {
        Map<String, String> env = environment(
                "DEEPSEEK_API_KEY", "OPENCODE_GO_API_KEY", "SERPAPI_KEY",
                "LANGFUSE_SECRET_KEY", "LANGFUSE_PUBLIC_KEY",
                "ANTHROPIC_AUTH_TOKEN", "AWS_SECRET_ACCESS_KEY",
                "MY_CLIENT_SECRET", "DB_PASSWORD", "PRIVATE_KEY",
                "GITHUB_TOKEN", "NOTION_TOKEN", "AWS_SESSION_TOKEN",
                "PATH", "JAVA_HOME");

        ToolRegistry.removeSensitiveCommandEnvironment(env);

        assertFalse(env.containsKey("DEEPSEEK_API_KEY"));
        assertFalse(env.containsKey("OPENCODE_GO_API_KEY"));
        assertFalse(env.containsKey("LANGFUSE_SECRET_KEY"));
        assertFalse(env.containsKey("ANTHROPIC_AUTH_TOKEN"));
        assertFalse(env.containsKey("AWS_SECRET_ACCESS_KEY"));
        assertFalse(env.containsKey("MY_CLIENT_SECRET"));
        assertFalse(env.containsKey("DB_PASSWORD"));
        assertFalse(env.containsKey("PRIVATE_KEY"));

        // 当前净化名单的已知漏项：阶段 4 再决定是否补。
        assertTrue(env.containsKey("SERPAPI_KEY"));
        assertTrue(env.containsKey("NOTION_TOKEN"));
        assertTrue(env.containsKey("GITHUB_TOKEN"));
        assertTrue(env.containsKey("AWS_SESSION_TOKEN"));
        assertTrue(env.containsKey("LANGFUSE_PUBLIC_KEY"));

        // 运行必需变量不能被误删。
        assertTrue(env.containsKey("PATH"));
        assertTrue(env.containsKey("JAVA_HOME"));
    }

    @Test
    void keepsRuntimeVariables() {
        Map<String, String> env = environment(
                "PATH", "JAVA_HOME", "HOME", "TMP", "TEMP", "USERPROFILE");

        ToolRegistry.removeSensitiveCommandEnvironment(env);

        assertTrue(env.containsKey("PATH"));
        assertTrue(env.containsKey("JAVA_HOME"));
        assertTrue(env.containsKey("HOME"));
        assertTrue(env.containsKey("TMP"));
        assertTrue(env.containsKey("TEMP"));
        assertTrue(env.containsKey("USERPROFILE"));
    }

    @Test
    void removesMixedCaseApiKeyButKeepsLowercaseSerpApi() {
        Map<String, String> env = environment(
                "DeepSeek_Api_Key", "deepseek_api_key", "serpapi_key",
                "Langfuse_Secret_Key");

        ToolRegistry.removeSensitiveCommandEnvironment(env);

        assertFalse(env.containsKey("DeepSeek_Api_Key"));
        assertFalse(env.containsKey("deepseek_api_key"));
        assertFalse(env.containsKey("Langfuse_Secret_Key"));
        assertTrue(env.containsKey("serpapi_key"));
    }

    @Test
    void nullAndEmptyMapsAreSafe() {
        ToolRegistry.removeSensitiveCommandEnvironment(null);
        Map<String, String> empty = new LinkedHashMap<>();
        ToolRegistry.removeSensitiveCommandEnvironment(empty);
        assertTrue(empty.isEmpty());
    }

    @Test
    void recognizesNamesBySuffixAndExactMatch() {
        assertTrue(ToolRegistry.isSensitiveEnvironmentName("DEEPSEEK_API_KEY"));
        assertTrue(ToolRegistry.isSensitiveEnvironmentName("deepseek_api_key"));
        assertTrue(ToolRegistry.isSensitiveEnvironmentName("ANTHROPIC_AUTH_TOKEN"));
        assertTrue(ToolRegistry.isSensitiveEnvironmentName("AWS_SECRET_ACCESS_KEY"));
        assertTrue(ToolRegistry.isSensitiveEnvironmentName("AUTHORIZATION"));
        assertTrue(ToolRegistry.isSensitiveEnvironmentName("LANGFUSE_SECRET_KEY"));

        assertFalse(ToolRegistry.isSensitiveEnvironmentName("SERPAPI_KEY"));
        assertFalse(ToolRegistry.isSensitiveEnvironmentName("NOTION_TOKEN"));
        assertFalse(ToolRegistry.isSensitiveEnvironmentName("GITHUB_TOKEN"));
        assertFalse(ToolRegistry.isSensitiveEnvironmentName("AWS_SESSION_TOKEN"));
        assertFalse(ToolRegistry.isSensitiveEnvironmentName("LANGFUSE_PUBLIC_KEY"));
        assertFalse(ToolRegistry.isSensitiveEnvironmentName("PATH"));
        assertFalse(ToolRegistry.isSensitiveEnvironmentName(null));
        assertFalse(ToolRegistry.isSensitiveEnvironmentName("  "));
    }
}
