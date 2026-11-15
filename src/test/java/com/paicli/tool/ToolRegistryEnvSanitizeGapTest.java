package com.paicli.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * W3 环境净化缺口组：这些输入在阶段 4 之前不会被现有净化名单删除。
 * 默认跳过；用 -Dpaicli.safety.gap=true 运行，失败即确认缺口。
 */
@EnabledIfSystemProperty(named = "paicli.safety.gap", matches = "true")
class ToolRegistryEnvSanitizeGapTest {

    private static Map<String, String> environment(String... names) {
        Map<String, String> env = new LinkedHashMap<>();
        for (String name : names) {
            env.put(name, "placeholder");
        }
        return env;
    }

    @Test
    @DisplayName("E1/gap：SERPAPI_KEY 期望被净化")
    void serpApiKeyShouldBeRemoved() {
        Map<String, String> env = environment("SERPAPI_KEY", "PATH");
        ToolRegistry.removeSensitiveCommandEnvironment(env);
        assertFalse(env.containsKey("SERPAPI_KEY"), "SERPAPI_KEY 仍在环境里");
    }

    @Test
    @DisplayName("E1/gap：NOTION_TOKEN 期望被净化")
    void notionTokenShouldBeRemoved() {
        Map<String, String> env = environment("NOTION_TOKEN", "PATH");
        ToolRegistry.removeSensitiveCommandEnvironment(env);
        assertFalse(env.containsKey("NOTION_TOKEN"), "NOTION_TOKEN 仍在环境里");
    }

    @Test
    @DisplayName("E1/gap：AWS_SESSION_TOKEN 期望被净化")
    void awsSessionTokenShouldBeRemoved() {
        Map<String, String> env = environment("AWS_SESSION_TOKEN", "PATH");
        ToolRegistry.removeSensitiveCommandEnvironment(env);
        assertFalse(env.containsKey("AWS_SESSION_TOKEN"), "AWS_SESSION_TOKEN 仍在环境里");
    }
}
