package com.paicli.skill;

import com.paicli.tool.LoadedSkillMessages;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolRegistry.ToolInvocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NotionKnowledgeOrganizerIntegrationTest {

    @Test
    void projectSkillRoutesToVisualGuideAndReferenceCanBeRead(@TempDir Path tempDir) {
        Path projectSkills = Path.of(".paicli", "skills").toAbsolutePath().normalize();
        SkillRegistry skills = new SkillRegistry(null, null, projectSkills,
                new SkillStateStore(tempDir.resolve("skills.json")));
        skills.reload();

        Skill skill = skills.findSkill("notion-knowledge-organizer");
        assertNotNull(skill, "项目级 Skill 应被发现");
        assertEquals(Skill.Source.PROJECT, skill.source());
        assertTrue(skills.warnings().isEmpty(), skills.warnings().toString());
        String index = SkillIndexFormatter.format(skills.enabledSkills());
        assertTrue(index.contains("notion-knowledge-organizer"));
        assertTrue(index.contains("Notion 笔记、知识库"), index);

        ToolRegistry tools = new ToolRegistry();
        tools.setProjectPath(Path.of(".").toAbsolutePath().normalize().toString());
        tools.setSkillRegistry(skills);
        List<ToolRegistry.ToolExecutionResult> untrusted = tools.executeTools(List.of(
                new ToolInvocation("load-1", "load_skill", "{\"name\":\"notion-knowledge-organizer\"}")));
        assertTrue(untrusted.get(0).result().contains("尚未被用户信任"));
        assertEquals("", LoadedSkillMessages.from(untrusted, skills));

        skills.trust(skill);
        List<ToolRegistry.ToolExecutionResult> loaded = tools.executeTools(List.of(
                new ToolInvocation("load-2", "load_skill", "{\"name\":\"notion-knowledge-organizer\"}")));
        String injected = LoadedSkillMessages.from(loaded, skills);
        assertTrue(injected.contains("## 工作模型"), injected);
        assertTrue(injected.contains("references/visual-study-page.md"), injected);
        assertFalse(injected.contains("正文过长已截断"), injected);

        String visualGuide = tools.executeTool("load_skill",
                "{\"name\":\"notion-knowledge-organizer\",\"file\":\"references/visual-study-page.md\"}");
        assertTrue(visualGuide.contains("先修正前两屏的阅读路径"), visualGuide);
        assertTrue(visualGuide.contains("回读确认图片"), visualGuide);
    }
}
