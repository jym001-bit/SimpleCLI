package com.paicli.skill;

import com.paicli.llm.LlmClient;
import com.paicli.tool.LoadedSkillMessages;
import com.paicli.tool.ToolResultBoundary;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolRegistry.ToolExecutionResult;
import com.paicli.tool.ToolRegistry.ToolInvocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LoadSkillToolTest {

    @Test
    void loadsExistingSkillAndBuildsSameTurnMessage(@TempDir Path tempDir) throws IOException {
        ToolRegistry tools = toolsWith(registryWith(tempDir, "web-access", "决策手册",
                "# Body\nwhen to fetch\nwhen to browse\n"));

        List<ToolExecutionResult> results = load(tools, "{\"name\":\"web-access\"}");

        assertTrue(results.get(0).result().contains("已加载 skill 'web-access'"), results.get(0).result());
        assertFalse(results.get(0).result().contains("when to fetch"), "正文不应进入 untrusted 工具结果");
        String message = LoadedSkillMessages.from(results, tools.getSkillRegistry());
        assertTrue(message.contains("## 已加载 Skill：web-access"), message);
        assertTrue(message.contains("when to fetch"), message);
    }

    @Test
    void unknownSkillProducesNoInjection(@TempDir Path tempDir) throws IOException {
        ToolRegistry tools = toolsWith(registryWith(tempDir, "real-one", "desc", "body"));

        List<ToolExecutionResult> results = load(tools, "{\"name\":\"nonexistent\"}");

        assertTrue(results.get(0).result().contains("未找到"), results.get(0).result());
        assertEquals("", LoadedSkillMessages.from(results, tools.getSkillRegistry()));
    }

    @Test
    void disabledSkillProducesNoInjection(@TempDir Path tempDir) throws IOException {
        SkillStateStore state = new SkillStateStore(tempDir.resolve("skills.json"));
        state.disable("web-access");
        SkillRegistry registry = new SkillRegistry(null,
                writeUserSkill(tempDir, "web-access", "desc", "body").getParent().getParent(),
                null, state);
        registry.reload();
        ToolRegistry tools = toolsWith(registry);

        List<ToolExecutionResult> results = load(tools, "{\"name\":\"web-access\"}");

        assertTrue(results.get(0).result().contains("已被禁用"), results.get(0).result());
        assertEquals("", LoadedSkillMessages.from(results, tools.getSkillRegistry()));
    }

    @Test
    void truncatesOversizedBody(@TempDir Path tempDir) throws IOException {
        StringBuilder big = new StringBuilder();
        while (big.length() < 6 * 1024) big.append("0123456789");
        ToolRegistry tools = toolsWith(registryWith(tempDir, "huge", "desc", big.toString()));

        List<ToolExecutionResult> results = load(tools, "{\"name\":\"huge\"}");

        assertTrue(results.get(0).result().contains("已加载 skill 'huge'"));
        String message = LoadedSkillMessages.from(results, tools.getSkillRegistry());
        assertTrue(message.contains("正文过长已截断"), "应包含截断标记");
    }

    @Test
    void truncationHintPointsToCallableToolWithFileOffset(@TempDir Path tempDir) throws IOException {
        StringBuilder big = new StringBuilder();
        for (int line = 1; big.length() < 6 * 1024; line++) big.append("line-").append(line).append(" 0123456789\n");
        ToolRegistry tools = toolsWith(registryWith(tempDir, "huge", "desc", big.toString()));

        String message = LoadedSkillMessages.from(load(tools, "{\"name\":\"huge\"}"), tools.getSkillRegistry());

        assertFalse(message.contains("/skill show"), "模型调用不了用户命令");
        java.util.regex.Matcher offset = java.util.regex.Pattern
                .compile("load_skill\\(name=\"huge\", file=\"SKILL.md\", offset=(\\d+)\\)").matcher(message);
        assertTrue(offset.find(), message.substring(message.length() - 200));
        String continued = tools.executeTool("load_skill",
                "{\"name\":\"huge\",\"file\":\"SKILL.md\",\"offset\":" + offset.group(1) + ",\"limit\":1}");
        String lastShown = message.substring(0, message.indexOf("...(正文过长已截断")).trim();
        lastShown = lastShown.substring(lastShown.lastIndexOf('\n') + 1);
        int lastShownNumber = Integer.parseInt(lastShown.substring(5, lastShown.indexOf(' ')));
        assertTrue(continued.contains("line-" + (lastShownNumber + 1) + " "), continued);
    }

    @Test
    void injectionCarriesSourceAndSkillDirectory(@TempDir Path tempDir) throws IOException {
        Path projectSkills = tempDir.resolve("project").resolve(".paicli").resolve("skills");
        Path skillDir = projectSkills.resolve("deploy");
        Files.createDirectories(skillDir.resolve("references"));
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: deploy\ndescription: d\n---\n先读 references/a.md\n");
        Files.writeString(skillDir.resolve("references").resolve("a.md"), "REFERENCE_A");
        SkillRegistry registry = new SkillRegistry(null, null, projectSkills,
                new SkillStateStore(tempDir.resolve("skills.json")));
        registry.reload();
        registry.trust(registry.findSkill("deploy"));
        ToolRegistry tools = toolsWith(registry);

        String message = LoadedSkillMessages.from(load(tools, "{\"name\":\"deploy\"}"), registry);

        assertTrue(message.contains("## 已加载 Skill：deploy（来源：project）"), message);
        assertTrue(message.contains("Skill 目录：" + skillDir.toAbsolutePath().normalize()), message);
        assertTrue(tools.executeTool("load_skill", "{\"name\":\"deploy\",\"file\":\"references/a.md\"}")
                .contains("REFERENCE_A"));
        assertTrue(tools.executeTool("load_skill", "{\"name\":\"deploy\",\"file\":\"references\"}")
                .contains("references/a.md"));
    }

    @Test
    void fileReadsCannotEscapeSkillDirectory(@TempDir Path tempDir) throws IOException {
        ToolRegistry tools = toolsWith(registryWith(tempDir, "demo", "desc", "demo-body"));
        Files.writeString(tempDir.resolve("secret.txt"), "SECRET");

        List<ToolExecutionResult> results = load(tools, "{\"name\":\"demo\",\"file\":\"../../secret.txt\"}");

        assertFalse(results.get(0).result().contains("SECRET"), results.get(0).result());
        assertTrue(results.get(0).result().contains("不在 Skill 'demo' 的目录内"), results.get(0).result());
        assertEquals("", LoadedSkillMessages.from(results, tools.getSkillRegistry()), "读附属文件不注入正文");
    }

    @Test
    void skillStillInHistoryIsNotInjectedTwice(@TempDir Path tempDir) throws IOException {
        ToolRegistry tools = toolsWith(registryWith(tempDir, "demo", "desc", "demo-body"));
        List<LlmClient.Message> history = new ArrayList<>();
        LoadedSkillMessages.Injection first = LoadedSkillMessages.prepare(
                load(tools, "{\"name\":\"demo\"}"), tools.getSkillRegistry(), history);
        history.add(LlmClient.Message.user(first.message()));

        LoadedSkillMessages.Injection second = LoadedSkillMessages.prepare(
                load(tools, "{\"name\":\"demo\"}"), tools.getSkillRegistry(), history);

        assertTrue(first.message().contains("demo-body"));
        assertEquals("", second.message());
        assertTrue(second.results().get(0).result().contains("正文还在当前上下文里"), second.results().get(0).result());
        assertTrue(second.results().get(0).successful());
    }

    @Test
    void sessionBudgetRejectsNewSkillOnceExhausted(@TempDir Path tempDir) throws IOException {
        Path userRoot = tempDir.resolve("user-skills");
        for (String name : List.of("alpha", "beta")) {
            Files.createDirectories(userRoot.resolve(name));
            Files.writeString(userRoot.resolve(name).resolve("SKILL.md"),
                    "---\nname: " + name + "\ndescription: d\n---\n" + "x".repeat(4_000) + "\n");
        }
        SkillRegistry registry = new SkillRegistry(null, userRoot, null, new SkillStateStore(tempDir.resolve("s.json")));
        registry.reload();
        ToolRegistry tools = toolsWith(registry);
        String old = System.getProperty("paicli.skill.body.budget");
        System.setProperty("paicli.skill.body.budget", "6000");
        try {
            List<LlmClient.Message> history = new ArrayList<>();
            history.add(LlmClient.Message.user(LoadedSkillMessages.prepare(
                    load(tools, "{\"name\":\"alpha\"}"), registry, history).message()));

            LoadedSkillMessages.Injection beta = LoadedSkillMessages.prepare(
                    load(tools, "{\"name\":\"beta\"}"), registry, history);

            assertEquals("", beta.message());
            assertFalse(beta.results().get(0).successful());
            assertTrue(beta.results().get(0).result().contains("字符预算"), beta.results().get(0).result());
        } finally {
            if (old == null) System.clearProperty("paicli.skill.body.budget");
            else System.setProperty("paicli.skill.body.budget", old);
        }
    }

    @Test
    void untrustedProjectSkillStaysInUntrustedToolResultUntilTrusted(@TempDir Path tempDir) throws IOException {
        Path projectSkills = tempDir.resolve("project").resolve(".paicli").resolve("skills");
        Files.createDirectories(projectSkills.resolve("deploy"));
        Path skillMd = projectSkills.resolve("deploy").resolve("SKILL.md");
        Files.writeString(skillMd, "---\nname: deploy\ndescription: d\n---\nPROJECT_BODY rm -rf build\n");
        SkillRegistry registry = new SkillRegistry(null, null, projectSkills,
                new SkillStateStore(tempDir.resolve("skills.json")));
        registry.reload();
        ToolRegistry tools = toolsWith(registry);

        List<ToolExecutionResult> untrusted = load(tools, "{\"name\":\"deploy\"}");
        assertTrue(untrusted.get(0).result().contains("尚未被用户信任"), untrusted.get(0).result());
        assertTrue(untrusted.get(0).result().contains("PROJECT_BODY"), "正文作为不可信数据留在工具结果里");
        assertTrue(ToolResultBoundary.wrap(untrusted.get(0)).contains("untrusted-data"));
        assertEquals("", LoadedSkillMessages.from(untrusted, registry));

        registry.trust(registry.findSkill("deploy"));
        List<ToolExecutionResult> trusted = load(tools, "{\"name\":\"deploy\"}");
        assertFalse(trusted.get(0).result().contains("PROJECT_BODY"));
        assertTrue(LoadedSkillMessages.from(trusted, registry).contains("PROJECT_BODY"));

        Files.writeString(skillMd, "---\nname: deploy\ndescription: d\n---\nPROJECT_BODY v2\n");
        registry.reload();
        assertFalse(registry.isTrusted(registry.findSkill("deploy")), "正文改动后需要重新信任");
    }

    @Test
    void sameSkillLoadedTwiceInOneBatchIsInjectedOnce(@TempDir Path tempDir) throws IOException {
        ToolRegistry tools = toolsWith(registryWith(tempDir, "demo", "desc", "demo-body"));

        List<ToolExecutionResult> results = tools.executeTools(List.of(
                new ToolInvocation("call-1", "load_skill", "{\"name\":\"demo\"}"),
                new ToolInvocation("call-2", "load_skill", "{\"name\":\"demo\"}")));

        String message = LoadedSkillMessages.from(results, tools.getSkillRegistry());
        assertEquals(message.indexOf("## 已加载 Skill：demo"), message.lastIndexOf("## 已加载 Skill：demo"));
    }

    @Test
    void failsWhenNameMissing() {
        ToolRegistry tools = toolsWith(new SkillRegistry(null, null, null, null));

        String result = tools.executeTool("load_skill", "{}");
        assertTrue(result.contains("缺少必填参数 name"), result);
    }

    @Test
    void referencesListingMergesBuiltinCacheWithUserOverlay(@TempDir Path tempDir) throws IOException {
        Fixture f = fixture(tempDir);
        writeFile(f.builtinRefs.resolve("site-patterns/a.com.md"), "builtin-a");
        writeFile(f.overlayRefs.resolve("site-patterns/a.com.md"), "user-a");
        writeFile(f.overlayRefs.resolve("site-patterns/b.com.md"), "user-b");
        f.registry.reload();

        assertEquals(Skill.Source.BUILTIN, f.registry.findSkill("web-access").source(),
                "没有 SKILL.md 的用户级补充目录不能覆盖内置 skill");
        List<ToolExecutionResult> results = load(f.tools,
                "{\"name\":\"web-access\",\"file\":\"references/site-patterns\"}");

        String listing = results.get(0).result();
        assertTrue(results.get(0).successful(), listing);
        int userSection = listing.indexOf("用户级补充");
        assertTrue(userSection > 0, listing);
        String own = listing.substring(0, userSection);
        String user = listing.substring(userSection);
        assertTrue(own.contains("references/site-patterns/a.com.md"), listing);
        assertFalse(own.contains("b.com.md"), listing);
        assertTrue(user.contains("references/site-patterns/a.com.md"), listing);
        assertTrue(user.contains("references/site-patterns/b.com.md"), listing);
        assertEquals("", LoadedSkillMessages.from(results, f.registry), "读 references 不应重复注入 SKILL.md 正文");
    }

    @Test
    void referenceFileReturnsBuiltinThenUserAddition(@TempDir Path tempDir) throws IOException {
        Fixture f = fixture(tempDir);
        writeFile(f.builtinRefs.resolve("site-patterns/a.com.md"), "builtin-a");
        writeFile(f.overlayRefs.resolve("site-patterns/a.com.md"), "user-a");

        String result = f.tools.executeTool("load_skill",
                "{\"name\":\"web-access\",\"file\":\"references/site-patterns/a.com.md\"}");

        assertTrue(result.contains("builtin-a") && result.contains("user-a"), result);
        assertTrue(result.indexOf("builtin-a") < result.indexOf("user-a"), result);
    }

    @Test
    void referenceFileOnlyInUserOverlayIsReadable(@TempDir Path tempDir) throws IOException {
        Fixture f = fixture(tempDir);
        writeFile(f.overlayRefs.resolve("site-patterns/new.com.md"), "user-only");

        String result = f.tools.executeTool("load_skill",
                "{\"name\":\"web-access\",\"file\":\"references/site-patterns/new.com.md\"}");

        assertTrue(result.contains("user-only") && result.contains("用户级补充"), result);
    }

    @Test
    void userOverlayIsOnlyMergedUnderReferences(@TempDir Path tempDir) throws IOException {
        Fixture f = fixture(tempDir);
        writeFile(f.userRoot.resolve("web-access/notes.md"), "outside-references");

        String result = f.tools.executeTool("load_skill", "{\"name\":\"web-access\",\"file\":\"notes.md\"}");

        assertTrue(result.contains("load_skill 失败"), result);
        assertFalse(result.contains("outside-references"), result);
    }

    @Test
    void referenceReadRejectsTraversalAndMissingFiles(@TempDir Path tempDir) throws IOException {
        Fixture f = fixture(tempDir);
        Files.writeString(tempDir.resolve("secret.md"), "TOP-SECRET-CONTENT");

        for (String file : List.of("../SKILL.md", "references/../../secret.md", "/etc/hosts", "~/.ssh/id_rsa")) {
            List<ToolExecutionResult> results = load(f.tools,
                    "{\"name\":\"web-access\",\"file\":\"" + file + "\"}");
            assertTrue(results.get(0).result().contains("load_skill 失败"), file + " -> " + results.get(0).result());
            assertFalse(results.get(0).result().contains("TOP-SECRET-CONTENT"), results.get(0).result());
        }
        List<ToolExecutionResult> missing = load(f.tools,
                "{\"name\":\"web-access\",\"file\":\"references/site-patterns/none.md\"}");
        assertTrue(missing.get(0).result().contains("load_skill 失败"), missing.get(0).result());
    }

    @Test
    void saveSkillReferenceWritesOnlyTheUserOverlayAndIsReadBack(@TempDir Path tempDir) throws IOException {
        Fixture f = fixture(tempDir);
        Path builtinFile = writeFile(f.builtinRefs.resolve("site-patterns/a.com.md"), "builtin-a");

        String first = f.tools.executeTool("save_skill_reference", "{\"name\":\"web-access\","
                + "\"file\":\"references/site-patterns/a.com.md\",\"content\":\"## 已知陷阱\\n- first\"}");
        String second = f.tools.executeTool("save_skill_reference", "{\"name\":\"web-access\","
                + "\"file\":\"references/site-patterns/a.com.md\",\"content\":\"- second\"}");

        Path overlayFile = f.overlayRefs.resolve("site-patterns/a.com.md");
        assertTrue(first.contains("已新建"), first);
        assertTrue(second.contains("已追加"), second);
        assertEquals("## 已知陷阱\n- first\n\n- second\n", Files.readString(overlayFile));
        assertEquals("builtin-a", Files.readString(builtinFile), "内置缓存不能被改写");
        assertFalse(Files.exists(f.userRoot.resolve("web-access/SKILL.md")));

        f.registry.reload();
        assertEquals(Skill.Source.BUILTIN, f.registry.findSkill("web-access").source());
        String read = f.tools.executeTool("load_skill",
                "{\"name\":\"web-access\",\"file\":\"references/site-patterns/a.com.md\"}");
        assertTrue(read.contains("builtin-a") && read.contains("- first") && read.contains("- second"), read);

        String overwritten = f.tools.executeTool("save_skill_reference", "{\"name\":\"web-access\","
                + "\"file\":\"references/site-patterns/a.com.md\",\"content\":\"- only\",\"overwrite\":true}");
        assertTrue(overwritten.contains("已覆盖"), overwritten);
        assertEquals("- only\n", Files.readString(overlayFile));
        assertEquals("builtin-a", Files.readString(builtinFile));
    }

    @Test
    void saveSkillReferenceRejectsUnsafeTargets(@TempDir Path tempDir) throws IOException {
        Fixture f = fixture(tempDir);
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Files.createDirectories(f.overlayRefs);
        boolean symlinkAvailable;
        try {
            Files.createSymbolicLink(f.overlayRefs.resolve("linked"), outside);
            symlinkAvailable = true;
        } catch (IOException | UnsupportedOperationException e) {
            symlinkAvailable = false;
        }

        List<String> unsafeFiles = new ArrayList<>(List.of("../SKILL.md", "references/../../evil.md", "/tmp/evil.md",
                "references/site-patterns/evil.sh", "references/.hidden.md", "site-patterns/evil.md"));
        if (symlinkAvailable) unsafeFiles.add("references/linked/evil.md");
        for (String file : unsafeFiles) {
            List<ToolExecutionResult> results = f.tools.executeTools(List.of(new ToolInvocation("c", "save_skill_reference",
                    "{\"name\":\"web-access\",\"file\":\"" + file + "\",\"content\":\"x\"}")));
            assertFalse(results.get(0).successful(), file + " -> " + results.get(0).result());
        }
        try (var stream = Files.list(outside)) {
            assertEquals(0, stream.count(), "符号链接目标不能被写入");
        }
        assertFalse(Files.exists(tempDir.resolve("evil.md")));
        assertFalse(Files.exists(f.userRoot.resolve("web-access/SKILL.md")));

        String oversized = "x".repeat(SkillReferences.MAX_WRITE_CHARS + 1);
        assertFalse(f.tools.executeTools(List.of(new ToolInvocation("c", "save_skill_reference",
                "{\"name\":\"web-access\",\"file\":\"references/big.md\",\"content\":\"" + oversized + "\"}")))
                .get(0).successful());
        assertFalse(Files.exists(f.overlayRefs.resolve("big.md")));
    }

    @Test
    void saveSkillReferenceRequiresAnEnabledSkill(@TempDir Path tempDir) throws IOException {
        Fixture f = fixture(tempDir);
        f.registry.stateStore().disable("web-access");

        for (String name : List.of("web-access", "missing")) {
            List<ToolExecutionResult> results = f.tools.executeTools(List.of(new ToolInvocation("c", "save_skill_reference",
                    "{\"name\":\"" + name + "\",\"file\":\"references/a.md\",\"content\":\"x\"}")));
            assertFalse(results.get(0).successful(), results.get(0).result());
        }
        assertFalse(Files.exists(f.userRoot.resolve(Path.of("missing"))));
        assertFalse(Files.exists(f.overlayRefs.resolve("a.md")));
    }

    @Test
    void pathGuardStillRejectsWriteFileIntoTheOverlay(@TempDir Path tempDir) throws IOException {
        Fixture f = fixture(tempDir);
        Path project = Files.createDirectories(tempDir.resolve("project"));
        f.tools.setProjectPath(project.toString());
        Path target = f.overlayRefs.resolve("site-patterns/a.com.md");

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String writeResult = f.tools.executeTool("write_file",
                mapper.writeValueAsString(java.util.Map.of("path", target.toString(), "content", "x")));
        String readResult = f.tools.executeTool("read_file",
                mapper.writeValueAsString(java.util.Map.of("path", f.builtinRefs.resolve("cdp.md").toString())));

        assertTrue(writeResult.contains("策略拒绝"), writeResult);
        assertTrue(readResult.contains("策略拒绝"), readResult);
        assertFalse(Files.exists(target));
    }

    private static ToolRegistry toolsWith(SkillRegistry registry) {
        ToolRegistry tools = new ToolRegistry();
        tools.setSkillRegistry(registry);
        return tools;
    }

    private static List<ToolExecutionResult> load(ToolRegistry tools, String argumentsJson) {
        return tools.executeTools(List.of(new ToolInvocation("call-1", "load_skill", argumentsJson)));
    }

    private static SkillRegistry registryWith(Path tempDir, String name, String desc, String body) throws IOException {
        Path userRoot = writeUserSkill(tempDir, name, desc, body).getParent().getParent();
        SkillStateStore state = new SkillStateStore(tempDir.resolve("skills.json"));
        SkillRegistry registry = new SkillRegistry(null, userRoot, null, state);
        registry.reload();
        return registry;
    }

    private static Path writeUserSkill(Path tempDir, String name, String desc, String body) throws IOException {
        Path userRoot = tempDir.resolve("user-skills");
        Path skillDir = userRoot.resolve(name);
        Files.createDirectories(skillDir);
        Path skillMd = skillDir.resolve("SKILL.md");
        Files.writeString(skillMd,
                "---\nname: " + name
                        + "\ndescription: " + desc
                        + "\n---\n" + body + "\n");
        return skillMd;
    }

    /** 内置缓存 + 只有 references 的用户级补充目录，与 Main 中 skills-cache / skills 的布局一致。 */
    private static Fixture fixture(Path tempDir) throws IOException {
        Path builtinRoot = tempDir.resolve("skills-cache");
        Path skillDir = Files.createDirectories(builtinRoot.resolve("web-access"));
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: web-access\ndescription: d\n---\nbody\n");
        Path builtinRefs = Files.createDirectories(skillDir.resolve("references"));
        writeFile(builtinRefs.resolve("cdp.md"), "cdp");
        Path userRoot = Files.createDirectories(tempDir.resolve("user-skills"));
        SkillRegistry registry = new SkillRegistry(builtinRoot, userRoot, null,
                new SkillStateStore(tempDir.resolve("skills.json")));
        registry.reload();
        return new Fixture(toolsWith(registry), registry, userRoot, builtinRefs,
                userRoot.resolve("web-access").resolve("references"));
    }

    private static Path writeFile(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private record Fixture(ToolRegistry tools, SkillRegistry registry, Path userRoot, Path builtinRefs,
                           Path overlayRefs) {
    }
}
