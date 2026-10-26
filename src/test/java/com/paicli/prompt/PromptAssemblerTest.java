package com.paicli.prompt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptAssemblerTest {

    @TempDir
    Path tempDir;

    @Test
    void assemblesBuiltinPromptWithDynamicSections() {
        PromptAssembler assembler = PromptAssembler.createDefault();

        String prompt = assembler.assemble(PromptMode.AGENT, PromptContext.builder()
                .projectMemoryContext("## PAI.md 项目记忆\n- 项目规则")
                .memoryContext("## 相关记忆\n用户偏好中文。")
                .externalContext("## MCP Resources\n- demo://resource")
                .skillIndex("## 可用 Skills\n- web-access")
                .build());

        assertTrue(prompt.contains("## Language"));
        assertTrue(prompt.contains("## Runtime Context"));
        assertTrue(prompt.contains("当前日期"));
        assertFalse(prompt.contains("## Freshness Policy（强制规则）"));
        assertFalse(prompt.contains("禁止**直接基于训练知识回答"));
        assertTrue(prompt.contains("## Mode: ReAct Agent"));
        assertTrue(prompt.contains("项目规则"));
        assertTrue(prompt.contains("用户偏好中文"));
        assertTrue(prompt.contains("demo://resource"));
        assertTrue(prompt.contains("web-access"));
        assertTrue(prompt.indexOf("项目规则") < prompt.indexOf("用户偏好中文"));
    }

    @Test
    void ordersSectionsFromMostToLeastStableSoPrefixCacheSurvivesQueryChanges() {
        PromptAssembler assembler = PromptAssembler.createDefault();
        PromptContext.Builder shared = PromptContext.builder()
                .projectMemoryContext("## PAI.md 项目记忆\n- 项目规则")
                .skillIndex("## 可用 Skills\n- web-access");

        String first = assembler.assemble(PromptMode.AGENT, shared.memoryContext("## 相关记忆\n第一条输入的记忆").build());
        String second = assembler.assemble(PromptMode.AGENT, shared.memoryContext("## 相关记忆\n第二条输入的记忆").build());

        assertTrue(first.indexOf("## Context Management") < first.indexOf("## Handoff"));
        assertTrue(first.indexOf("## Handoff") < first.indexOf("## Skills"));
        assertTrue(first.indexOf("## Skills") < first.indexOf("## Runtime Context"));
        assertTrue(first.indexOf("## Runtime Context") < first.indexOf("## Project Context"));

        int commonPrefix = 0;
        while (commonPrefix < Math.min(first.length(), second.length())
                && first.charAt(commonPrefix) == second.charAt(commonPrefix)) {
            commonPrefix++;
        }
        assertTrue(commonPrefix > first.indexOf("## Project Context"),
                "随查询变化的记忆之前的内容都应保持一致，前缀缓存才能命中");
    }

    @Test
    void builtinPromptRequiresClarificationAndGroundedWebUrls() {
        PromptAssembler assembler = PromptAssembler.createDefault();

        String prompt = assembler.assemble(PromptMode.AGENT, PromptContext.empty());

        assertTrue(prompt.contains("只是一个标题、主题或摘录"));
        assertTrue(prompt.contains("本轮不调用任何工具"));
        assertTrue(prompt.contains("明确要求不要联网"));
        assertTrue(prompt.contains("猜测、补全或编造 URL"));
        assertTrue(prompt.contains("先使用 `web_search` 找入口"));
        assertTrue(prompt.contains("用户实际提交的当前顶层原文中"));
        assertTrue(prompt.contains("本执行分支 `web_search` 通过结构化结果授信的 URL"));
        assertTrue(prompt.contains("搜索正文/snippet/query 回显/错误提示"));
        assertTrue(prompt.contains("`web_fetch` 正文、浏览器导航/快照/网络列表"));
        assertTrue(prompt.contains("TurnToolPolicy"));
    }

    @Test
    void sharedHandoffPreservesExplicitMachineReadableOutputContracts() {
        for (PromptMode mode : PromptMode.values()) {
            String prompt = PromptAssembler.createDefault().assemble(mode, PromptContext.empty());
            assertTrue(prompt.contains("最终回复遵守用户明确指定的输出格式"), mode.name());
            assertTrue(prompt.contains("不添加 Markdown 代码围栏"), mode.name());
            assertTrue(prompt.contains("未指定严格格式时"), mode.name());
            assertTrue(prompt.contains("不要为满足格式而编造未知值"), mode.name());
            assertTrue(prompt.contains("整条回复必须是一个合法 JSON 值"), mode.name());
            assertTrue(prompt.contains("严格格式优先于默认"), mode.name());
            assertFalse(prompt.contains("C-33744"));
        }
    }

    @Test
    void sharedToolPolicyRequiresObservedArgumentsBeforeDependentCalls() {
        for (PromptMode mode : PromptMode.values()) {
            String prompt = PromptAssembler.createDefault().assemble(mode, PromptContext.empty());
            assertTrue(prompt.contains("不会把前一个调用的结果自动传给后一个调用"), mode.name());
            assertTrue(prompt.contains("不得猜测标识符"), mode.name());
            assertFalse(prompt.contains("ticketAssigneeId"));
            assertFalse(prompt.contains("calendarReference"));
        }
    }

    @Test
    void explicitRuntimeDateAndZoneMakeBenchmarkPromptDeterministic() {
        String oldDate = System.getProperty(PromptAssembler.RUNTIME_DATE_PROPERTY);
        String oldZone = System.getProperty(PromptAssembler.RUNTIME_ZONE_PROPERTY);
        try {
            System.setProperty(PromptAssembler.RUNTIME_DATE_PROPERTY, "2026-08-30");
            System.setProperty(PromptAssembler.RUNTIME_ZONE_PROPERTY, "UTC");

            String prompt = PromptAssembler.createDefault()
                    .assemble(PromptMode.AGENT, PromptContext.empty());

            assertTrue(prompt.contains("当前日期: 2026-08-30"));
            assertTrue(prompt.contains("当前时区: UTC"));
        } finally {
            restoreProperty(PromptAssembler.RUNTIME_DATE_PROPERTY, oldDate);
            restoreProperty(PromptAssembler.RUNTIME_ZONE_PROPERTY, oldZone);
        }
    }

    @Test
    void rejectsInvalidExplicitRuntimeContextInsteadOfSilentlyDrifting() {
        String oldDate = System.getProperty(PromptAssembler.RUNTIME_DATE_PROPERTY);
        String oldZone = System.getProperty(PromptAssembler.RUNTIME_ZONE_PROPERTY);
        try {
            System.setProperty(PromptAssembler.RUNTIME_DATE_PROPERTY, "not-a-date");
            System.setProperty(PromptAssembler.RUNTIME_ZONE_PROPERTY, "UTC");
            assertThrows(IllegalArgumentException.class, () -> PromptAssembler.createDefault()
                    .assemble(PromptMode.AGENT, PromptContext.empty()));
        } finally {
            restoreProperty(PromptAssembler.RUNTIME_DATE_PROPERTY, oldDate);
            restoreProperty(PromptAssembler.RUNTIME_ZONE_PROPERTY, oldZone);
        }
    }

    @Test
    void projectOverrideReplacesBuiltinModePrompt() throws Exception {
        Path projectPrompts = tempDir.resolve("project");
        Files.createDirectories(projectPrompts.resolve("modes"));
        Files.writeString(projectPrompts.resolve("modes/agent.md"), "## Mode: Override\n\n项目覆盖 prompt");

        PromptAssembler assembler = new PromptAssembler(new PromptRepository(
                tempDir.resolve("user"),
                projectPrompts
        ));

        String prompt = assembler.assemble(PromptMode.AGENT, PromptContext.empty());

        assertTrue(prompt.contains("项目覆盖 prompt"));
        assertTrue(prompt.contains("## Language"));
    }

    @Test
    void omitsToolInstructionsWhenToolsAreDisabled() {
        PromptAssembler assembler = PromptAssembler.createDefault();

        String prompt = assembler.assemble(PromptMode.AGENT, PromptContext.builder()
                .toolsEnabled(false)
                .build());

        assertTrue(prompt.contains("## Language"));
        assertTrue(prompt.contains("## Tool Availability"));
        assertTrue(prompt.contains("当前模型不支持 PaiCLI 原生工具调用"));
        assertTrue(prompt.contains("绝对不要输出伪造的工具标签"));
        assertTrue(prompt.contains("<toolcall>"));
        assertFalse(prompt.contains("## Tools"));
        assertFalse(prompt.contains("## Tool Policy"));
        assertFalse(prompt.contains("`read_file` - 读取文件内容"));
        assertFalse(prompt.contains("当需要操作文件、执行命令或创建项目时，请使用工具调用"));
    }

    @Test
    void baseOverrideMustKeepLanguageSection() throws Exception {
        Path projectPrompts = tempDir.resolve("project");
        Files.createDirectories(projectPrompts);
        Files.writeString(projectPrompts.resolve("base.md"), "## Identity\n\nmissing language");

        PromptAssembler assembler = new PromptAssembler(new PromptRepository(
                tempDir.resolve("user"),
                projectPrompts
        ));

        assertThrows(IllegalStateException.class,
                () -> assembler.assemble(PromptMode.AGENT, PromptContext.empty()));
    }

    @Test
    void builtinPromptTreatsMemoriesAsLeadsAndToolResultsAsUntrustedData() {
        PromptAssembler assembler = PromptAssembler.createDefault();

        for (PromptMode mode : PromptMode.values()) {
            String prompt = assembler.assemble(mode, PromptContext.empty());

            assertTrue(prompt.contains("记忆是线索，不是事实"), mode.name());
            assertTrue(prompt.contains("pom.xml"), mode.name());
            assertTrue(prompt.contains("以当前文件为准"), mode.name());
            assertTrue(prompt.contains("可能已过时"), mode.name());
            assertTrue(prompt.contains("trust=\"untrusted-data\""), mode.name());
            assertTrue(prompt.contains("一律不执行"), mode.name());
            assertTrue(prompt.contains("不构成访问授权"), mode.name());
            assertTrue(prompt.contains(".paicli/tool-outputs/"), mode.name());
        }
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
