package com.paicli.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillBuiltinExtractorTest {

    @Test
    void everyBuiltinHasValidFrontmatterAndHarnessTags(@TempDir Path tempDir) throws IOException {
        SkillBuiltinExtractor extractor = new SkillBuiltinExtractor(tempDir);
        extractor.extractAll();
        for (String name : extractor.builtinSkillNames()) {
            var parsed = SkillFrontmatterParser.parse(Files.readString(
                    extractor.skillCacheDir(name).resolve("SKILL.md")));
            assertTrue(parsed.warnings().isEmpty(), name + ": " + parsed.warnings());
            assertEquals(name, parsed.frontmatter().get("name"));
            if (name.equals("better-harness")) {
                assertEquals(List.of("harness", "review", "workflow"), parsed.frontmatter().get("tags"));
            }
        }
    }

    @Test
    void upgradesOldHarnessCacheToSupportedFrontmatter(@TempDir Path tempDir) throws IOException {
        Path harness = tempDir.resolve("better-harness");
        Files.createDirectories(harness);
        Files.writeString(harness.resolve(".version"), "1.1.0");
        Files.writeString(harness.resolve("SKILL.md"),
                "---\nname: better-harness\ntags:\n  - harness\n---\nold body\n");

        new SkillBuiltinExtractor(tempDir).extractAll();

        var parsed = SkillFrontmatterParser.parse(Files.readString(harness.resolve("SKILL.md")));
        assertTrue(parsed.warnings().isEmpty(), parsed.warnings().toString());
        assertEquals(List.of("harness", "review", "workflow"), parsed.frontmatter().get("tags"));
        assertTrue(parsed.body().contains("# PaiCLI Better Harness"));
    }

    @Test
    void extractsBuiltinSkillFromClasspath(@TempDir Path tempDir) throws IOException {
        SkillBuiltinExtractor extractor = new SkillBuiltinExtractor(tempDir);
        extractor.extractAll();

        Path skillDir = tempDir.resolve("web-access");
        assertTrue(Files.isDirectory(skillDir));
        assertTrue(Files.isRegularFile(skillDir.resolve("SKILL.md")));
        assertTrue(Files.isRegularFile(skillDir.resolve("references/cdp-cheatsheet.md")));
        assertTrue(Files.isRegularFile(skillDir.resolve("references/site-patterns/github.com.md")));
        assertTrue(Files.isRegularFile(tempDir.resolve("better-harness/SKILL.md")));
        assertTrue(Files.isRegularFile(skillDir.resolve(".version")));
        assertEquals(SkillBuiltinExtractor.CURRENT_VERSION,
                Files.readString(skillDir.resolve(".version")).trim());
    }

    @Test
    void skipsExtractionWhenVersionMatches(@TempDir Path tempDir) throws IOException {
        SkillBuiltinExtractor extractor = new SkillBuiltinExtractor(tempDir);
        extractor.extractAll();

        // 用一个标记文件验证：第二次 extractAll 不会清理整个目录
        Path marker = tempDir.resolve("web-access/.user-marker");
        Files.writeString(marker, "preserved");

        extractor.extractAll();
        assertTrue(Files.exists(marker), "版本一致时不应清空缓存目录");
    }

    @Test
    void rebuildsWhenVersionMismatch(@TempDir Path tempDir) throws IOException {
        SkillBuiltinExtractor extractor = new SkillBuiltinExtractor(tempDir);
        extractor.extractAll();

        // 模拟旧版本：把 .version 改成历史值
        Path versionFile = tempDir.resolve("web-access/.version");
        Files.writeString(versionFile, "0.0.0-old");
        Path marker = tempDir.resolve("web-access/.user-marker");
        Files.writeString(marker, "should be wiped");

        extractor.extractAll();
        assertFalse(Files.exists(marker), "版本变化时应清空缓存目录");
        assertEquals(SkillBuiltinExtractor.CURRENT_VERSION,
                Files.readString(versionFile).trim());
    }
}
