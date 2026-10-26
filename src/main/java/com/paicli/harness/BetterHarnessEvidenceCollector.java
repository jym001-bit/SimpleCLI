package com.paicli.harness;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.history.ConversationLedger;
import com.paicli.llm.LlmClient;
import com.paicli.skill.Skill;
import com.paicli.skill.SkillRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds a bounded, secret-conscious evidence snapshot before any model review.
 *
 * <p>The collector deliberately keeps the three Better Harness evidence domains
 * separate. It never serializes raw user/assistant message bodies, tool arguments,
 * environment files, memory bodies, or MCP secret values.
 */
public final class BetterHarnessEvidenceCollector {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
            ".git", "target", "node_modules", "dist", "build", "coverage");
    private static final List<String> GUIDANCE_FILES = List.of(
            "AGENTS.md", "PAI.md", "README.md", "ROADMAP.md", "CLAUDE.md");

    public record EvidenceBundle(String sessionEvidence,
                                 String projectHarness,
                                 String agentCustomize) {
    }

    private final Path workspace;
    private final ConversationLedger ledger;
    private final SkillRegistry skillRegistry;

    public BetterHarnessEvidenceCollector(Path workspace,
                                          ConversationLedger ledger,
                                          SkillRegistry skillRegistry) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.ledger = ledger == null ? ConversationLedger.disabled() : ledger;
        this.skillRegistry = skillRegistry;
    }

    public EvidenceBundle collect(BetterHarnessOptions.Depth depth) throws IOException {
        int excerptBudget = depth == BetterHarnessOptions.Depth.QUICK ? 4_000 : 12_000;
        int fileLimit = depth == BetterHarnessOptions.Depth.QUICK ? 5_000 : 20_000;
        List<String> files = inventoryFiles(fileLimit);
        return new EvidenceBundle(
                json(sessionEvidence()),
                json(projectHarness(files, excerptBudget, fileLimit)),
                json(agentCustomize(files)));
    }

    private Map<String, Object> sessionEvidence() throws IOException {
        List<ConversationLedger.Entry> entries = ledger.readAll();
        Map<String, Long> eventCounts = new TreeMap<>();
        Map<String, Long> modeCounts = new TreeMap<>();
        Map<String, Long> actorCounts = new TreeMap<>();
        Map<String, Long> toolCounts = new TreeMap<>();
        long firstTimestamp = Long.MAX_VALUE;
        long lastTimestamp = Long.MIN_VALUE;

        for (ConversationLedger.Entry entry : entries) {
            increment(eventCounts, entry.event());
            increment(modeCounts, entry.mode());
            increment(actorCounts, entry.actor());
            firstTimestamp = Math.min(firstTimestamp, entry.timestamp());
            lastTimestamp = Math.max(lastTimestamp, entry.timestamp());
            LlmClient.Message message = entry.message();
            if (message == null || message.toolCalls() == null) {
                continue;
            }
            for (LlmClient.ToolCall toolCall : message.toolCalls()) {
                if (toolCall != null && toolCall.function() != null) {
                    increment(toolCounts, toolCall.function().name());
                }
            }
        }

        Map<String, Object> lane = new LinkedHashMap<>();
        lane.put("platform", "paicli");
        lane.put("workspace", workspace.toString());
        lane.put("scope", "current-session-metadata-only");
        lane.put("rawContentIncluded", false);
        lane.put("sessionId", ledger.sessionId());
        lane.put("ledgerEnabled", ledger.isEnabled());
        lane.put("entryCount", entries.size());
        lane.put("eventCounts", eventCounts);
        lane.put("modeCounts", modeCounts);
        lane.put("actorCounts", actorCounts);
        lane.put("toolCallCounts", toolCounts);
        lane.put("firstSeen", firstTimestamp == Long.MAX_VALUE
                ? null : Instant.ofEpochMilli(firstTimestamp).toString());
        lane.put("lastSeen", lastTimestamp == Long.MIN_VALUE
                ? null : Instant.ofEpochMilli(lastTimestamp).toString());
        lane.put("limitations", List.of(
                "Only the active PaiCLI ledger is included.",
                "Message bodies, reasoning, tool arguments, results, images, and memory bodies are excluded.",
                "Outcome claims that require content-level evidence must remain unobserved."));
        return lane;
    }

    private Map<String, Object> projectHarness(List<String> files,
                                               int excerptBudget,
                                               int fileLimit) {
        Map<String, String> guidance = new LinkedHashMap<>();
        int remaining = excerptBudget;
        for (String name : GUIDANCE_FILES) {
            Path candidate = workspace.resolve(name);
            if (!Files.isRegularFile(candidate) || remaining <= 0) {
                continue;
            }
            String excerpt = readExcerpt(candidate, remaining);
            guidance.put(name, excerpt);
            remaining -= excerpt.length();
        }

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("sourceFiles", count(files, path ->
                path.startsWith("src/main/") && path.endsWith(".java")));
        counts.put("testFiles", count(files, path ->
                path.startsWith("src/test/") && path.endsWith(".java")));
        counts.put("documentationFiles", count(files, path ->
                path.startsWith("docs/") && path.endsWith(".md")));
        counts.put("ciFiles", count(files, this::isCiFile));
        counts.put("specFiles", count(files, path ->
                path.matches("(?i)(^|.*/)(specs?|adrs?|rfcs?)/.*\\.md$")));

        Map<String, Object> lane = new LinkedHashMap<>();
        lane.put("platform", "paicli");
        lane.put("workspace", workspace.toString());
        lane.put("inventoryTruncated", files.size() >= fileLimit);
        lane.put("counts", counts);
        lane.put("guidanceExcerpts", guidance);
        lane.put("validationSignals", validationSignals(files));
        lane.put("deliverySignals", deliverySignals(files));
        lane.put("limitations", List.of(
                "No command was executed while collecting this snapshot.",
                "A configured test or CI file proves presence, not that it ran successfully.",
                "Repository history and external CI outcomes are not included."));
        return lane;
    }

    private Map<String, Object> agentCustomize(List<String> files) {
        List<Map<String, Object>> skills = new ArrayList<>();
        if (skillRegistry != null) {
            for (Skill skill : skillRegistry.enabledSkills()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", skill.name());
                item.put("source", skill.source().name().toLowerCase());
                item.put("path", safeRelative(skill.skillMdPath()));
                skills.add(item);
            }
        }

        Map<String, Object> lane = new LinkedHashMap<>();
        lane.put("platform", "paicli");
        lane.put("workspace", workspace.toString());
        lane.put("enabledSkills", skills);
        lane.put("projectSkillFiles", files.stream()
                .filter(path -> path.matches("(?i)^\\.paicli/skills/[^/]+/SKILL\\.md$"))
                .toList());
        lane.put("promptFiles", files.stream()
                .filter(path -> path.startsWith("src/main/resources/prompts/"))
                .toList());
        lane.put("mcpConfigFiles", files.stream()
                .filter(path -> path.equals(".paicli/mcp.json")
                        || path.equals(".paicli/mcp.jsonc")
                        || path.equals("mcp.json")
                        || path.equals("mcp.jsonc"))
                .toList());
        lane.put("policyFiles", files.stream()
                .filter(path -> path.startsWith("src/main/java/com/paicli/policy/")
                        || path.startsWith("src/main/java/com/paicli/hitl/"))
                .toList());
        lane.put("memoryEntrypoints", files.stream()
                .filter(path -> path.equals("PAI.md")
                        || path.equals(".paicli/PAI.md")
                        || path.equals("PAI.local.md")
                        || path.equals(".paicli/PAI.local.md"))
                .toList());
        lane.put("secretValuesIncluded", false);
        lane.put("limitations", List.of(
                "Configured asset presence does not prove runtime use.",
                "User-home Skills, MCP configuration, and Memory bodies are outside the default scope.",
                "MCP configuration values and environment files are never read."));
        return lane;
    }

    private List<String> inventoryFiles(int limit) throws IOException {
        List<String> files = new ArrayList<>();
        Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(workspace)
                        && SKIPPED_DIRECTORIES.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (dir.startsWith(workspace.resolve(".paicli").resolve("better-harness"))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return files.size() >= limit
                        ? FileVisitResult.TERMINATE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    files.add(workspace.relativize(file).toString().replace('\\', '/'));
                }
                return files.size() >= limit
                        ? FileVisitResult.TERMINATE
                        : FileVisitResult.CONTINUE;
            }
        });
        return files.stream().sorted().toList();
    }

    private List<String> validationSignals(List<String> files) {
        return files.stream()
                .filter(path -> path.equals("pom.xml")
                        || path.endsWith("Test.java")
                        || path.endsWith("Tests.java")
                        || path.matches("(?i)(^|.*/)(checkstyle|pmd|spotbugs|jacoco).*"))
                .limit(200)
                .toList();
    }

    private List<String> deliverySignals(List<String> files) {
        return files.stream()
                .filter(this::isCiFile)
                .limit(100)
                .toList();
    }

    private boolean isCiFile(String path) {
        return path.startsWith(".github/workflows/")
                || path.equals(".gitlab-ci.yml")
                || path.equals(".gitlab-ci.yaml")
                || path.startsWith(".circleci/")
                || path.equals("Jenkinsfile")
                || path.equals("azure-pipelines.yml")
                || path.equals("azure-pipelines.yaml");
    }

    private String safeRelative(Path path) {
        if (path == null) {
            return null;
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(workspace)) {
            return workspace.relativize(normalized).toString().replace('\\', '/');
        }
        return "<outside-workspace>/" + normalized.getFileName();
    }

    private static String readExcerpt(Path file, int maxChars) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            return text.length() <= maxChars
                    ? text
                    : text.substring(0, maxChars) + "\n[truncated]";
        } catch (IOException e) {
            return "[unavailable: " + e.getClass().getSimpleName() + "]";
        }
    }

    private static long count(List<String> values,
                              java.util.function.Predicate<String> predicate) {
        return values.stream().filter(predicate).count();
    }

    private static void increment(Map<String, Long> counts, String key) {
        counts.merge(key == null || key.isBlank() ? "unknown" : key, 1L, Long::sum);
    }

    private static String json(Map<String, Object> value) throws JsonProcessingException {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    }
}
