package com.paicli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.browser.BrowserConnector;
import com.paicli.mcp.protocol.McpToolDescriptor;
import com.paicli.web.SearchProvider;
import com.paicli.web.SearchResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ToolRegistryTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void executionResultCarriesTypedFailureStatus() {
        ToolRegistry registry = new ToolRegistry();

        ToolOutput output = registry.executeToolOutput("missing_tool", "{}");
        ToolRegistry.ToolExecutionResult result = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("missing", "missing_tool", "{}"))).get(0);

        assertFalse(output.successful());
        assertFalse(result.successful());
        assertTrue(result.discoveredUrls().isEmpty());
    }

    @Test
    void exposesToolDefinitionsInStableNameOrder() {
        ToolRegistry registry = new ToolRegistry();

        List<String> names = registry.getToolDefinitions().stream()
                .map(com.paicli.llm.LlmClient.Tool::name)
                .toList();
        List<String> sorted = names.stream().sorted().toList();

        assertEquals(sorted, names);
    }

    @Test
    void removesCredentialsButKeepsBuildEnvironmentForBenchmarkShells() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("PATH", "/usr/bin");
        environment.put("JAVA_HOME", "/jdk");
        environment.put("DEEPSEEK_API_KEY", "secret");
        environment.put("LANGFUSE_SECRET_KEY", "secret");
        environment.put("ANTHROPIC_AUTH_TOKEN", "secret");
        environment.put("AWS_SECRET_ACCESS_KEY", "secret");
        environment.put("DATABASE_PASSWORD", "secret");

        ToolRegistry.removeSensitiveCommandEnvironment(environment);

        assertEquals(Map.of("PATH", "/usr/bin", "JAVA_HOME", "/jdk"), environment);
    }

    @Test
    void shouldRunCommandInProjectDirectory(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("execute_command", "{\"command\":\"pwd\"}");

        assertTrue(result.contains(tempDir.toString()));
    }

    @Test
    void commandExitCodeControlsTypedToolSuccess(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        ToolOutput passed = registry.executeToolOutput(
                "execute_command", "{\"command\":\"printf ok\"}");
        ToolOutput failed = registry.executeToolOutput(
                "execute_command", "{\"command\":\"printf problem; exit 7\"}");
        ToolRegistry.ToolExecutionResult observed = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation(
                        "call-exit-7", "execute_command",
                        "{\"command\":\"printf problem; exit 7\"}"))).get(0);

        assertTrue(passed.successful());
        assertTrue(passed.text().contains("exit code: 0"));
        assertFalse(failed.successful());
        assertTrue(failed.text().contains("exit code: 7"));
        assertFalse(observed.successful());
        assertTrue(observed.result().contains("exit code: 7"));
    }

    @Test
    void commandSandboxIsDisabledByDefault() {
        ToolRegistry registry = new ToolRegistry();

        assertFalse(registry.isCommandSandboxEnabled());
    }

    @Test
    void commandSandboxBuildsEscapedSeatbeltProfileAndDirectArgv(@TempDir Path tempDir) throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace-quote\"-slash\\"));
        CommandSandbox sandbox = new CommandSandbox(
                workspace,
                Path.of("/usr/bin/sandbox-exec"),
                Map.of("PATH", "/usr/bin"),
                Path.of(System.getProperty("java.home")));

        String profile = sandbox.profile();
        String escapedWorkspace = workspace.toRealPath().toString()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");

        assertTrue(profile.contains("(deny default)"));
        assertTrue(profile.contains("(deny network*)"));
        assertTrue(profile.contains("(allow file-read*"));
        assertTrue(profile.contains("(allow file-write*"));
        assertTrue(profile.contains("(subpath \"" + escapedWorkspace + "\")"));
        assertTrue(profile.contains("(literal \"/dev/null\")"));
        String readSection = profile.substring(
                profile.indexOf("(allow file-read*"),
                profile.indexOf("(allow file-write*"));
        assertFalse(readSection.contains("(subpath \"/Library\")"));
        assertFalse(readSection.contains("(subpath \"/opt/homebrew\")"));
        assertFalse(readSection.contains("(subpath \"/usr/local\")"));
        assertFalse(readSection.contains("/private/var/db"));
        String writeSection = profile.substring(profile.indexOf("(allow file-write*"));
        assertFalse(writeSection.contains("(subpath \"/dev\")"),
                "write profile must not grant the whole /dev tree");

        List<String> arguments = sandbox.arguments("printf ok");
        assertEquals("/usr/bin/sandbox-exec", arguments.get(0));
        assertEquals("-p", arguments.get(1));
        assertEquals(profile, arguments.get(2));
        assertEquals("/bin/bash", arguments.get(3));
        assertEquals("-c", arguments.get(4));
        assertEquals("printf ok", arguments.get(5));
    }

    @Test
    void commandSandboxFailsClosedWhenExecutableIsUnavailable(@TempDir Path tempDir) {
        CommandSandbox sandbox = new CommandSandbox(
                tempDir,
                tempDir.resolve("missing-sandbox-exec"),
                Map.of("PATH", "/usr/bin"),
                Path.of(System.getProperty("java.home")));

        Exception error = assertThrows(java.io.IOException.class,
                () -> sandbox.prepare(tempDir, "printf should-not-run"));

        assertTrue(error.getMessage().contains("命令沙箱不可用"));
    }

    @Test
    void commandSandboxEnableFailsClosedWhenExecutableProbeFails(@TempDir Path tempDir) {
        Path falseExecutable = Path.of("/usr/bin/false");
        assumeTrue(Files.isExecutable(falseExecutable), "/usr/bin/false is unavailable");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> CommandSandbox.enable(
                        tempDir,
                        falseExecutable,
                        Map.of("PATH", "/usr/bin"),
                        Path.of(System.getProperty("java.home"))));

        assertTrue(error.getMessage().contains("Seatbelt probe exit code="), error.getMessage());
    }

    @Test
    void macSandboxAllowsWorkspaceReadWriteAndIsolatesHomeAndTmp(@TempDir Path tempDir) throws Exception {
        assumeMacSandboxAvailable();
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Files.writeString(workspace.resolve("input.txt"), "inside-data\n");
        ToolRegistry registry = sandboxedRegistry(workspace);

        String result = executeCommand(registry,
                "cat input.txt && printf 'inside-ok' > output.txt && "
                        + "printf '\\nHOME=%s\\nTMPDIR=%s\\n' \"$HOME\" \"$TMPDIR\"");

        assertTrue(result.contains("exit code: 0"), result);
        assertTrue(result.contains("inside-data"), result);
        assertEquals("inside-ok", Files.readString(workspace.resolve("output.txt")));
        Path sandboxState = workspace.toRealPath().resolve(".paicli-command-sandbox");
        assertTrue(result.contains("HOME=" + sandboxState.resolve("home")), result);
        assertTrue(result.contains("TMPDIR=" + sandboxState.resolve("tmp")), result);
    }

    @Test
    void macSandboxRejectsOutsideReadWriteParentEscapeAndSymlinkEscape(@TempDir Path tempDir) throws Exception {
        assumeMacSandboxAvailable();
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path outsideSecret = outside.resolve("secret.txt");
        Path outsideCanary = outside.resolve("canary.txt");
        Files.writeString(outsideSecret, "outside-secret");
        Files.writeString(outsideCanary, "unchanged");
        Files.writeString(workspace.resolve("source.txt"), "source");
        Files.createSymbolicLink(workspace.resolve("outside-link"), outside);
        ToolRegistry registry = sandboxedRegistry(workspace);

        String readResult = executeCommand(registry, "cat " + shellQuote(outsideSecret));
        String absoluteWriteResult = executeCommand(
                registry, "printf changed > " + shellQuote(outside.resolve("written.txt")));
        String parentEscapeResult = executeCommand(registry, "cp source.txt ../escape-copy.txt");
        String symlinkReadResult = executeCommand(registry, "cat outside-link/secret.txt");
        String symlinkEscapeResult = executeCommand(
                registry, "printf changed > outside-link/canary.txt");

        assertFalse(readResult.contains("exit code: 0"), readResult);
        assertFalse(readResult.contains("outside-secret"), readResult);
        assertFalse(absoluteWriteResult.contains("exit code: 0"), absoluteWriteResult);
        assertFalse(parentEscapeResult.contains("exit code: 0"), parentEscapeResult);
        assertFalse(symlinkReadResult.contains("exit code: 0"), symlinkReadResult);
        assertFalse(symlinkReadResult.contains("outside-secret"), symlinkReadResult);
        assertFalse(symlinkEscapeResult.contains("exit code: 0"), symlinkEscapeResult);
        assertFalse(Files.exists(outside.resolve("written.txt")));
        assertFalse(Files.exists(tempDir.resolve("escape-copy.txt")));
        assertEquals("unchanged", Files.readString(outsideCanary));
    }

    @Test
    void macSandboxRejectsLoopbackAndPublicNetwork(@TempDir Path tempDir) throws Exception {
        assumeMacSandboxAvailable();
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        ToolRegistry registry = sandboxedRegistry(workspace);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "loopback-ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            String loopback = executeCommand(registry,
                    "/usr/bin/curl --max-time 2 --silent --show-error http://127.0.0.1:" + port + "/");
            String publicNetwork = executeCommand(registry,
                    "/usr/bin/curl --max-time 2 --silent --show-error https://example.com/");

            assertFalse(loopback.contains("exit code: 0"), loopback);
            assertFalse(loopback.contains("loopback-ok"), loopback);
            assertFalse(publicNetwork.contains("exit code: 0"), publicNetwork);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void macSandboxAllowsDevelopmentRuntimesAndDevNull(@TempDir Path tempDir) throws Exception {
        assumeMacSandboxAvailable();
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        ToolRegistry registry = sandboxedRegistry(workspace);

        List<String> runtimeCommands = new ArrayList<>();
        runtimeCommands.add("java -version");
        runtimeCommands.add("javac -version");
        if (commandAvailable("python3")) {
            runtimeCommands.add("python3 --version");
        }
        if (commandAvailable("node")) {
            runtimeCommands.add("node --version");
        }
        for (String command : runtimeCommands) {
            String result = executeCommand(registry, command);
            assertTrue(result.contains("exit code: 0"), command + "\n" + result);
        }

        String redirected = executeCommand(
                registry, "printf hidden >/dev/null 2>&1; printf visible");
        assertTrue(redirected.contains("exit code: 0"), redirected);
        assertTrue(redirected.contains("visible"), redirected);
        assertFalse(redirected.contains("hidden"), redirected);
    }

    @Test
    void shouldRejectBroadFilesystemScan() {
        ToolRegistry registry = new ToolRegistry();

        String result = registry.executeTool("execute_command", "{\"command\":\"find / -name \\\"pom.xml\\\" -type f | head -20\"}");

        assertTrue(result.contains("策略拒绝"));
    }

    @Test
    void shouldReadRequestedLineRange(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Sample.java");
        Files.writeString(file, String.join("\n",
                "class Sample {",
                "  void first() {}",
                "  void second() {}",
                "}"));
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("read_file", "{\"path\":\"Sample.java\",\"offset\":2,\"limit\":2}");

        assertTrue(result.contains("lines 2-3 of 4"));
        assertTrue(result.contains("2 |   void first() {}"));
        assertTrue(result.contains("3 |   void second() {}"));
        assertTrue(!result.contains("class Sample {"));
    }

    @Test
    void editFileReplacesOnlyUniqueTextAndReportsDiff(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Sample.java");
        Files.writeString(file, "class Sample {\n  int value = 1;\n  int keep = 2;\n}\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        List<String[]> diffs = new ArrayList<>();
        registry.setWriteFileObserver((path, beforeAfter) -> diffs.add(beforeAfter));

        ToolOutput result = registry.executeToolOutput("edit_file", MAPPER.writeValueAsString(Map.of(
                "path", "Sample.java", "old_text", "int value = 1", "new_text", "int value = 3")));

        assertTrue(result.successful(), result.text());
        assertEquals("class Sample {\n  int value = 3;\n  int keep = 2;\n}\n", Files.readString(file));
        assertEquals(1, diffs.size());
        assertTrue(diffs.get(0)[0].contains("value = 1"));
        assertTrue(diffs.get(0)[1].contains("value = 3"));
    }

    @Test
    void editFileRejectsMissingOrRepeatedTextWithoutChangingFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Sample.txt");
        Files.writeString(file, "same\nsame\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        ToolOutput missing = registry.executeToolOutput("edit_file", MAPPER.writeValueAsString(Map.of(
                "path", "Sample.txt", "old_text", "missing", "new_text", "changed")));
        ToolOutput repeated = registry.executeToolOutput("edit_file", MAPPER.writeValueAsString(Map.of(
                "path", "Sample.txt", "old_text", "same", "new_text", "changed")));

        assertFalse(missing.successful());
        assertFalse(repeated.successful());
        assertTrue(repeated.text().contains("出现多次"));
        assertEquals("same\nsame\n", Files.readString(file));
    }

    @Test
    void editFileMatchesLfSnippetInCrlfFileAndKeepsCrlf(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Windows.java");
        Files.writeString(file, "class A {\r\n  int x = 1;\r\n  int y = 2;\r\n}\r\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        ToolOutput result = registry.executeToolOutput("edit_file", MAPPER.writeValueAsString(Map.of(
                "path", "Windows.java",
                "old_text", "  int x = 1;\n  int y = 2;",
                "new_text", "  int x = 10;\n  int y = 20;")));

        assertEquals("文件已编辑: Windows.java", result.text());
        assertEquals("class A {\r\n  int x = 10;\r\n  int y = 20;\r\n}\r\n", Files.readString(file));
    }

    @Test
    void editFileReplaceAllReportsReplacementCount(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Log.java");
        Files.writeString(file, "log(a);\nlog(b);\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        ToolOutput result = registry.executeToolOutput("edit_file", MAPPER.writeValueAsString(Map.of(
                "path", "Log.java", "old_text", "log(", "new_text", "logger.info(", "replace_all", true)));

        assertEquals("文件已编辑: Log.java（替换 2 处）", result.text());
        assertEquals("logger.info(a);\nlogger.info(b);\n", Files.readString(file));
    }

    @Test
    void editFileRejectsPathOutsideProject(@TempDir Path tempDir) throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path outside = tempDir.resolve("outside.txt");
        Files.writeString(outside, "secret");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(workspace.toString());

        ToolOutput result = registry.executeToolOutput("edit_file", MAPPER.writeValueAsString(Map.of(
                "path", "../outside.txt", "old_text", "secret", "new_text", "changed")));

        assertFalse(result.successful());
        assertEquals("secret", Files.readString(outside));
    }

    @Test
    void editFileRejectsOverlappingMatches(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("overlap.txt");
        Files.writeString(file, "aaa");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        ToolOutput result = registry.executeToolOutput("edit_file", MAPPER.writeValueAsString(Map.of(
                "path", "overlap.txt", "old_text", "aa", "new_text", "x")));

        assertFalse(result.successful());
        assertEquals("aaa", Files.readString(file));
    }

    @Test
    void shouldGlobFilesInsideProject(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Files.writeString(tempDir.resolve("src/main/java/com/example/UserService.java"), "class UserService {}\n");
        Files.writeString(tempDir.resolve("README.md"), "# demo\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("glob_files", "{\"pattern\":\"**/*Service.java\"}");

        assertTrue(result.contains("src/main/java/com/example/UserService.java"));
        assertTrue(!result.contains("README.md"));
    }

    @Test
    void shouldGlobRootFileByName(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "# demo\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("glob_files", "{\"pattern\":\"README.md\"}");

        assertTrue(result.contains("README.md"));
    }

    @Test
    void shouldGrepCodeWithLineNumbersAndContext(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Files.writeString(tempDir.resolve("src/main/java/com/example/UserService.java"), String.join("\n",
                "class UserService {",
                "  User getUserById(String id) {",
                "    return repository.findById(id);",
                "  }",
                "}"));
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("grep_code",
                "{\"pattern\":\"getUserById\",\"glob\":\"**/*.java\",\"context_lines\":1}");

        assertTrue(result.contains("src/main/java/com/example/UserService.java:2"));
        assertTrue(result.contains(">    2 |   User getUserById(String id) {"));
        assertTrue(result.contains("     3 |     return repository.findById(id);"));
    }

    @Test
    void shouldSkipCommonDependencyDirectoriesWhenGrepping(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("src"));
        Files.createDirectories(tempDir.resolve("node_modules/pkg"));
        Files.writeString(tempDir.resolve("src/App.java"), "class App { String marker = \"targetSymbol\"; }\n");
        Files.writeString(tempDir.resolve("node_modules/pkg/Generated.java"), "class Generated { String marker = \"targetSymbol\"; }\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("grep_code", "{\"pattern\":\"targetSymbol\",\"max_results\":10}");

        assertTrue(result.contains("src/App.java:1"));
        assertTrue(!result.contains("node_modules"));
    }

    @Test
    void shouldExposePartialWhenGrepReachesHeadLimit(@TempDir Path tempDir) throws Exception {
        String previous = System.getProperty("paicli.search.disable.rg");
        System.setProperty("paicli.search.disable.rg", "true");
        try {
            Files.writeString(tempDir.resolve("Many.java"), String.join("\n",
                    "class Many {",
                    "  String first = \"needle\";",
                    "  String second = \"needle\";",
                    "}"));
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(tempDir.toString());

            String result = registry.executeTool("grep_code",
                    "{\"pattern\":\"needle\",\"head_limit\":1,\"max_results\":10}");

            assertTrue(result.contains("Many.java:2"));
            assertTrue(!result.contains("Many.java:3"));
            assertTrue(result.contains("partial: true"));
            assertTrue(result.contains("head_limit=1"));
            assertTrue(result.contains("suggested_reads"));
            assertTrue(result.contains("read_file {\"path\":\"Many.java\""));
        } finally {
            restoreSystemProperty("paicli.search.disable.rg", previous);
        }
    }

    @Test
    void shouldExposePartialWhenGrepResultReachesCharacterBudget(@TempDir Path tempDir) throws Exception {
        String previous = System.getProperty("paicli.search.disable.rg");
        System.setProperty("paicli.search.disable.rg", "true");
        try {
            String longNeedleLine = "needle " + "x".repeat(1200);
            Files.writeString(tempDir.resolve("Budget.java"), String.join("\n",
                    "class Budget {",
                    "  String first = \"" + longNeedleLine + "\";",
                    "  String second = \"" + longNeedleLine + "\";",
                    "}"));
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(tempDir.toString());

            String result = registry.executeTool("grep_code",
                    "{\"pattern\":\"needle\",\"max_results\":10,\"max_chars\":1000}");

            assertTrue(result.contains("Budget.java:2"));
            assertTrue(result.contains("partial: true"));
            assertTrue(result.contains("max_chars=1000"));
        } finally {
            restoreSystemProperty("paicli.search.disable.rg", previous);
        }
    }

    @Test
    void shouldTimeoutLongRunningCommandWithoutHanging(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry(1);
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("execute_command", "{\"command\":\"sleep 2\"}");

        assertTrue(result.contains("命令执行超时"));
    }

    @Test
    void shouldRouteWebSearchThroughStepSearchMcpForStep37Flash() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setCurrentModel("step", "step-3.7-flash");
        registry.registerMcpTool(stepSearchDescriptor("web_search", """
                {
                  "type": "object",
                  "properties": {
                    "query": {"type": "string"},
                    "top_k": {"type": "integer"}
                  }
                }
                """), args -> "step-result:" + args);

        ToolOutput output = registry.executeToolOutput(
                "web_search", "{\"query\":\"Step 3.7 Flash\",\"top_k\":3}");
        String result = output.text();

        assertTrue(result.contains("[StepSearch]"));
        assertTrue(result.contains("step-result"));
        assertTrue(result.contains("\"query\":\"Step 3.7 Flash\""));
        assertTrue(result.contains("\"top_k\":3"));
        assertTrue(output.successful());
        assertTrue(output.discoveredUrls().isEmpty(),
                "unstructured MCP prose must not grant URL provenance");
    }

    @Test
    void builtInWebSearchPublishesOnlyStructuredHttpResultUrls() {
        ToolRegistry registry = new ToolRegistry();
        registry.setSearchProvider(new SearchProvider() {
            @Override
            public String name() {
                return "stub";
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public String unavailableHint() {
                return "";
            }

            @Override
            public List<SearchResult> search(String query, int topK) {
                return List.of(
                        SearchResult.of(1, "valid", "https://example.com/article", "snippet mentions https://evil.example"),
                        SearchResult.of(2, "invalid", "not-a-url", "ignored"));
            }
        });

        ToolOutput output = registry.executeToolOutput(
                "web_search", "{\"query\":\"target\",\"top_k\":5}");

        assertTrue(output.successful());
        assertEquals(List.of("https://example.com/article"), output.discoveredUrls());
        assertTrue(output.text().contains("https://evil.example"),
                "snippet remains readable but is not promoted to URL metadata");
    }

    @Test
    void shouldRouteWebFetchThroughStepSearchMcpForStep37Flash() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setCurrentModel("step", "step-3.7-flash");
        registry.registerMcpTool(stepSearchDescriptor("web_fetch", """
                {
                  "type": "object",
                  "properties": {
                    "url": {"type": "string"},
                    "max_chars": {"type": "integer"}
                  }
                }
                """), args -> "step-fetch:" + args);

        String result = registry.executeTool("web_fetch",
                "{\"url\":\"https://203.0.113.10/docs/step-search\",\"max_chars\":1200}");

        assertTrue(result.contains("[StepSearch]"));
        assertTrue(result.contains("step-fetch"));
        assertTrue(result.contains("\"url\":\"https://203.0.113.10/docs/step-search\""));
        assertTrue(result.contains("\"max_chars\":1200"));
    }

    @Test
    void shouldNotRouteStepSearchForOlderStepModel() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setCurrentModel("step", "step-3.5-flash");
        registry.registerMcpTool(stepSearchDescriptor("web_search", """
                {"type": "object", "properties": {"query": {"type": "string"}}}
                """), args -> "step-result:" + args);

        String result = registry.executeTool("web_search", "{\"query\":\"Step 3.7 Flash\"}");

        assertFalse(result.contains("step-result"));
    }

    @Test
    void shouldExecuteMultipleToolInvocationsInParallelAndKeepResultOrder() {
        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicInteger current = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry() {
            @Override
            public String executeTool(String name, String argumentsJson) {
                int now = current.incrementAndGet();
                peak.updateAndGet(prev -> Math.max(prev, now));
                bothStarted.countDown();
                try {
                    assertTrue(bothStarted.await(5, TimeUnit.SECONDS), "两个工具调用应同时进入执行区");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    current.decrementAndGet();
                }
                return "result-" + name;
            }
        };

        List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call_1", "read_file", "{}"),
                new ToolRegistry.ToolInvocation("call_2", "grep_code", "{}")
        ));

        assertEquals(2, peak.get(), "两个只读工具调用应并行执行");
        assertEquals("call_1", results.get(0).id());
        assertEquals("result-read_file", results.get(0).result());
        assertEquals("call_2", results.get(1).id());
        assertEquals("result-grep_code", results.get(1).result());
    }

    @Test
    void shouldSerializeConcurrentEditsToSameFileWithoutLosingUpdates(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("counter.txt");
        Files.writeString(file, "a=0\nb=0\nc=0\nd=0\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("e1", "edit_file",
                        "{\"path\":\"counter.txt\",\"old_text\":\"a=0\",\"new_text\":\"a=1\"}"),
                new ToolRegistry.ToolInvocation("e2", "edit_file",
                        "{\"path\":\"counter.txt\",\"old_text\":\"b=0\",\"new_text\":\"b=1\"}"),
                new ToolRegistry.ToolInvocation("e3", "edit_file",
                        "{\"path\":\"counter.txt\",\"old_text\":\"c=0\",\"new_text\":\"c=1\"}"),
                new ToolRegistry.ToolInvocation("e4", "edit_file",
                        "{\"path\":\"counter.txt\",\"old_text\":\"d=0\",\"new_text\":\"d=1\"}")
        ));

        assertEquals(List.of("e1", "e2", "e3", "e4"), results.stream()
                .map(ToolRegistry.ToolExecutionResult::id)
                .toList());
        assertEquals("a=1\nb=1\nc=1\nd=1\n", Files.readString(file), "同一批次的多次编辑都必须保留");
    }

    @Test
    void skillReferenceWriteIsNotParallelSafe() {
        assertTrue(ToolRegistry.isParallelSafeTool("load_skill"));
        assertFalse(ToolRegistry.isParallelSafeTool("save_skill_reference"));
    }

    @Test
    void shouldRunSideEffectToolsSeriallyBetweenParallelReadOnlyRuns() {
        AtomicInteger current = new AtomicInteger();
        AtomicInteger writePeak = new AtomicInteger();
        List<String> executionOrder = java.util.Collections.synchronizedList(new ArrayList<>());
        ToolRegistry registry = new ToolRegistry() {
            @Override
            public String executeTool(String name, String argumentsJson) {
                int now = current.incrementAndGet();
                if (!ToolRegistry.isParallelSafeTool(name)) {
                    writePeak.updateAndGet(previous -> Math.max(previous, now));
                }
                executionOrder.add(name + ":" + argumentsJson);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    current.decrementAndGet();
                }
                return "result-" + argumentsJson;
            }
        };

        List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("r1", "read_file", "1"),
                new ToolRegistry.ToolInvocation("r2", "grep_code", "2"),
                new ToolRegistry.ToolInvocation("w1", "write_file", "3"),
                new ToolRegistry.ToolInvocation("c1", "execute_command", "4"),
                new ToolRegistry.ToolInvocation("r3", "read_file", "5")
        ));

        assertEquals(1, writePeak.get(), "有副作用的工具执行时不应有其他工具同时运行");
        assertEquals(List.of("r1", "r2", "w1", "c1", "r3"), results.stream()
                .map(ToolRegistry.ToolExecutionResult::id)
                .toList());
        List<String> order = List.copyOf(executionOrder);
        assertEquals(List.of("write_file:3", "execute_command:4", "read_file:5"), order.subList(2, 5),
                "写入、命令和后续读取必须按模型给出的顺序执行");
    }

    @Test
    void shouldExecuteBrowserContainingBatchSequentiallyInDeclaredOrder() {
        CountDownLatch laterCallEntered = new CountDownLatch(1);
        AtomicInteger current = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<String> executionOrder = java.util.Collections.synchronizedList(new ArrayList<>());
        ToolRegistry registry = new ToolRegistry() {
            @Override
            public String executeTool(String name, String argumentsJson) {
                int now = current.incrementAndGet();
                peak.updateAndGet(previous -> Math.max(previous, now));
                executionOrder.add(name);
                try {
                    if ("mcp__chrome-devtools__new_page".equals(name)) {
                        laterCallEntered.await(300, TimeUnit.MILLISECONDS);
                    } else {
                        laterCallEntered.countDown();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    current.decrementAndGet();
                }
                return "result-" + name;
            }
        };

        List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation(
                        "browser", "mcp__chrome-devtools__new_page", "{}"),
                new ToolRegistry.ToolInvocation("local", "read_file", "{}")
        ));

        assertEquals(1, peak.get(), "含浏览器工具的整个批次应串行执行");
        assertEquals(List.of("mcp__chrome-devtools__new_page", "read_file"), executionOrder);
        assertEquals(List.of("browser", "local"), results.stream()
                .map(ToolRegistry.ToolExecutionResult::id)
                .toList());
    }

    private static McpToolDescriptor stepSearchDescriptor(String name, String schema) throws Exception {
        JsonNode inputSchema = MAPPER.readTree(schema);
        return new McpToolDescriptor(
                "step_search",
                name,
                "mcp__step_search__" + name,
                "StepSearch " + name,
                inputSchema);
    }

    @Test
    void shouldCancelToolInvocationWhenBatchTimeoutIsReached() {
        ToolRegistry registry = new ToolRegistry(1, 1) {
            @Override
            public String executeTool(String name, String argumentsJson) {
                if ("grep_code".equals(name)) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return "result-" + name;
            }
        };

        List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call_1", "grep_code", "{}"),
                new ToolRegistry.ToolInvocation("call_2", "read_file", "{}")
        ));

        assertTrue(results.get(0).timedOut());
        assertTrue(results.get(0).result().contains("工具执行超时"));
        assertEquals("result-read_file", results.get(1).result());
    }

    @Test
    void browserConnectToolUsesInjectedConnector() {
        ToolRegistry registry = new ToolRegistry();
        registry.setBrowserConnector(new BrowserConnector() {
            @Override
            public String status() {
                return "status-ok";
            }

            @Override
            public String connectDefault() {
                return "connected";
            }

            @Override
            public String disconnect() {
                return "disconnected";
            }
        });

        assertEquals("connected", registry.executeTool("browser_connect", "{}"));
        assertEquals("status-ok", registry.executeTool("browser_status", "{}"));
        assertEquals("disconnected", registry.executeTool("browser_disconnect", "{}"));
    }

    @Test
    void saveMemoryToolUsesInjectedMemorySaver() {
        ToolRegistry registry = new ToolRegistry();
        List<String> saved = new ArrayList<>();
        registry.setMemorySaver(saved::add);

        String result = registry.executeTool("save_memory", "{\"fact\":\"访问 yuque.com 时复用登录态\"}");

        assertEquals(List.of("访问 yuque.com 时复用登录态"), saved);
        assertTrue(result.contains("已保存到长期记忆"));
    }

    @Test
    void saveMemoryToolPassesScopeToScopedSaver() {
        ToolRegistry registry = new ToolRegistry();
        List<String> saved = new ArrayList<>();
        registry.setScopedMemorySaver((fact, scope) -> saved.add(scope + ":" + fact));

        String result = registry.executeTool("save_memory", "{\"fact\":\"默认用中文回答\",\"scope\":\"global\"}");

        assertEquals(List.of("global:默认用中文回答"), saved);
        assertTrue(result.contains("长期记忆(global)"));
    }

    @Test
    void saveMemoryToolReturnsWriterFeedbackAndPassesUserChoice() {
        ToolRegistry registry = new ToolRegistry();
        List<String> calls = new ArrayList<>();
        registry.setMemoryWriter((fact, scope, replaceId, keepBoth) -> {
            calls.add(scope + ":" + fact + ":" + replaceId + ":" + keepBoth);
            return "⚠️ 未写入：新内容与已有长期记忆高度相似但不一致";
        });

        String conflict = registry.executeTool("save_memory", "{\"fact\":\"项目使用 Java 21\"}");
        registry.executeTool("save_memory",
                "{\"fact\":\"项目使用 Java 21\",\"replace_id\":\"fact-1\",\"keep_both\":false}");
        registry.executeTool("save_memory", "{\"fact\":\"项目使用 Java 21\",\"keep_both\":true}");

        assertTrue(conflict.contains("未写入"));
        assertEquals(List.of(
                "project:项目使用 Java 21:null:false",
                "project:项目使用 Java 21:fact-1:false",
                "project:项目使用 Java 21:null:true"), calls);
    }

    private static ToolRegistry sandboxedRegistry(Path workspace) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(workspace.toString());
        registry.setCommandSandboxRoot(workspace);
        return registry;
    }

    private static String executeCommand(ToolRegistry registry, String command) throws Exception {
        return registry.executeTool(
                "execute_command",
                MAPPER.writeValueAsString(Map.of("command", command)));
    }

    private static String shellQuote(Path path) {
        return "'" + path.toString().replace("'", "'\"'\"'") + "'";
    }

    private static void assumeMacSandboxAvailable() throws Exception {
        String osName = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        assumeTrue(osName.contains("mac") && Files.isExecutable(CommandSandbox.DEFAULT_EXECUTABLE),
                "macOS sandbox-exec is unavailable");
        Process process = new ProcessBuilder(
                CommandSandbox.DEFAULT_EXECUTABLE.toString(),
                "-p",
                "(version 1)\n(allow default)\n",
                "/usr/bin/true")
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(3, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        assumeTrue(finished && process.exitValue() == 0,
                "sandbox-exec cannot run inside the current host sandbox");
    }

    private static boolean commandAvailable(String command) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return false;
        }
        for (String entry : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!entry.isBlank() && Files.isExecutable(Path.of(entry).resolve(command))) {
                return true;
            }
        }
        return false;
    }

    private static void restoreSystemProperty(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }
}
