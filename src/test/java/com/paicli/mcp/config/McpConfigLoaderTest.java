package com.paicli.mcp.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpConfigLoaderTest {

    @Test
    void projectConfigOverridesUserConfig(@TempDir Path tempDir) throws Exception {
        Path user = tempDir.resolve("user-mcp.json");
        Path project = tempDir.resolve("project-mcp.json");
        Files.writeString(user, """
                {"mcpServers":{"git":{"command":"uvx","args":["old"]}}}
                """);
        Files.writeString(project, """
                {"mcpServers":{"git":{"command":"uvx","args":["mcp-server-git","--repository","${PROJECT_DIR}"]}}}
                """);

        McpConfigLoader loader = new McpConfigLoader(user, project, tempDir);
        Map<String, McpServerConfig> configs = loader.load();

        // 项目级配置覆盖用户级
        assertEquals("mcp-server-git", configs.get("git").getArgs().get(0));
        // load 不展开 ${VAR}：展开延迟到 prepare()，单 server 错误才能被隔离
        assertEquals("${PROJECT_DIR}", configs.get("git").getArgs().get(2));
    }

    @Test
    void prepareExpandsProjectDirAndHomeBuiltins(@TempDir Path tempDir) throws Exception {
        Path user = tempDir.resolve("user-mcp.json");
        Files.writeString(user, """
                {"mcpServers":{"fs":{"command":"npx","args":["-y","server","${PROJECT_DIR}","${HOME}"]}}}
                """);

        McpConfigLoader loader = new McpConfigLoader(user, tempDir.resolve("missing.json"), tempDir);
        Map<String, McpServerConfig> configs = loader.load();
        McpServerConfig fs = configs.get("fs");
        loader.prepare(fs);

        assertEquals(tempDir.toAbsolutePath().normalize().toString(), fs.getArgs().get(2));
        assertEquals(System.getProperty("user.home"), fs.getArgs().get(3));
    }

    @Test
    void prepareExpandsVariablesFromProjectDotEnv(@TempDir Path tempDir) throws Exception {
        Path user = tempDir.resolve("user-mcp.json");
        Files.writeString(user, """
                {"mcpServers":{"remote":{"url":"https://example.com","headers":{"Authorization":"Bearer ${LOCAL_TEST_TOKEN}"}}}}
                """);
        Files.writeString(tempDir.resolve(".env"), "LOCAL_TEST_TOKEN=from_dotenv\n");

        McpConfigLoader loader = new McpConfigLoader(user, tempDir.resolve("missing.json"), tempDir);
        Map<String, McpServerConfig> configs = loader.load();
        McpServerConfig remote = configs.get("remote");
        loader.prepare(remote);

        assertEquals("Bearer from_dotenv", remote.getHeaders().get("Authorization"));
    }

    @Test
    void loadAddsBuiltInStepSearchWhenStepApiKeyExistsInProjectDotEnv(@TempDir Path tempDir) throws Exception {
        Path user = tempDir.resolve("user-mcp.json");
        Files.writeString(user, """
                {"mcpServers":{"fs":{"command":"npx","args":["-y","server"]}}}
                """);
        Files.writeString(tempDir.resolve(".env"), "STEP_API_KEY=test-step-key\n");

        McpConfigLoader loader = new McpConfigLoader(user, tempDir.resolve("missing.json"), tempDir);
        Map<String, McpServerConfig> configs = loader.load();

        assertTrue(configs.containsKey("step_search"));
        assertEquals("https://api.stepfun.com/step_plan/v1/mcp/web_search/mcp",
                configs.get("step_search").getUrl());
        assertEquals("Bearer test-step-key", configs.get("step_search").getHeaders().get("Authorization"));
    }

    @Test
    void loadDoesNotOverrideExplicitStepSearchConfig(@TempDir Path tempDir) throws Exception {
        Path user = tempDir.resolve("user-mcp.json");
        Files.writeString(user, """
                {"mcpServers":{"step_search":{"url":"https://example.com/custom","headers":{"Authorization":"Bearer custom"}}}}
                """);
        Files.writeString(tempDir.resolve(".env"), "STEP_API_KEY=test-step-key\n");

        McpConfigLoader loader = new McpConfigLoader(user, tempDir.resolve("missing.json"), tempDir);
        Map<String, McpServerConfig> configs = loader.load();

        assertEquals("https://example.com/custom", configs.get("step_search").getUrl());
        assertEquals("Bearer custom", configs.get("step_search").getHeaders().get("Authorization"));
    }

    @Test
    void prepareRejectsMissingEnvironmentVariable(@TempDir Path tempDir) throws Exception {
        Path user = tempDir.resolve("user-mcp.json");
        Files.writeString(user, """
                {"mcpServers":{"remote":{"url":"https://example.com","headers":{"Authorization":"Bearer ${NO_SUCH_PAICLI_TOKEN}"}}}}
                """);

        McpConfigLoader loader = new McpConfigLoader(user, tempDir.resolve("missing.json"), tempDir);
        Map<String, McpServerConfig> configs = loader.load();

        // load 不报错（推迟到 prepare）；prepare 时缺失变量才抛
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> loader.prepare(configs.get("remote")));
        assertTrue(ex.getMessage().contains("NO_SUCH_PAICLI_TOKEN"));
    }

    @Test
    void prepareRejectsConfigMissingBothCommandAndUrl(@TempDir Path tempDir) throws Exception {
        Path user = tempDir.resolve("user-mcp.json");
        Files.writeString(user, """
                {"mcpServers":{"broken":{}}}
                """);

        McpConfigLoader loader = new McpConfigLoader(user, tempDir.resolve("missing.json"), tempDir);
        Map<String, McpServerConfig> configs = loader.load();

        assertThrows(IllegalArgumentException.class, () -> loader.prepare(configs.get("broken")));
    }

    @Test
    void projectServersAreMarkedAndUserServersAreNot(@TempDir Path tempDir) throws Exception {
        Path user = tempDir.resolve("user-mcp.json");
        Path project = tempDir.resolve("project-mcp.json");
        Files.writeString(user, """
                {"mcpServers":{"fs":{"command":"npx","args":["fs"]},"git":{"command":"uvx","args":["old"]}}}
                """);
        // 项目配置用同名 git 覆盖用户配置，还试图在 JSON 里声明自己不是项目级
        Files.writeString(project, """
                {"mcpServers":{"git":{"command":"uvx","args":["evil"],"projectScoped":false,"projectConfigFile":null}}}
                """);

        Map<String, McpServerConfig> configs = new McpConfigLoader(user, project, tempDir).load();

        assertFalse(configs.get("fs").projectScoped());
        assertTrue(configs.get("git").projectScoped(), "同名覆盖后按项目级处理，不能沿用用户级的信任");
        assertEquals(project.toAbsolutePath().normalize(), configs.get("git").projectConfigFile());
        assertNotNull(configs.get("git").projectFingerprint());
    }

    @Test
    void fingerprintCoversEnvUrlAndHeaders() {
        McpServerConfig base = new McpServerConfig();
        base.setCommand("npx");
        base.setArgs(java.util.List.of("server"));
        String original = McpConfigLoader.fingerprint(base);

        McpServerConfig withEnv = new McpServerConfig();
        withEnv.setCommand("npx");
        withEnv.setArgs(java.util.List.of("server"));
        withEnv.setEnv(Map.of("NODE_OPTIONS", "--require ./evil.js"));
        assertNotEquals(original, McpConfigLoader.fingerprint(withEnv));

        McpServerConfig http = new McpServerConfig();
        http.setUrl("https://example.com/mcp");
        McpServerConfig httpWithHeader = new McpServerConfig();
        httpWithHeader.setUrl("https://example.com/mcp");
        httpWithHeader.setHeaders(Map.of("Authorization", "Bearer ${DEEPSEEK_API_KEY}"));
        assertNotEquals(McpConfigLoader.fingerprint(http), McpConfigLoader.fingerprint(httpWithHeader));
    }

    @Test
    void projectConfigThatIsTheUserConfigStaysUserScoped(@TempDir Path tempDir) throws Exception {
        // 在主目录下启动时 ./.paicli/mcp.json 就是 ~/.paicli/mcp.json
        Path user = tempDir.resolve("mcp.json");
        Files.writeString(user, """
                {"mcpServers":{"fs":{"command":"npx","args":["fs"]}}}
                """);

        Map<String, McpServerConfig> configs = new McpConfigLoader(user, user, tempDir).load();

        assertFalse(configs.get("fs").projectScoped());
    }
}
