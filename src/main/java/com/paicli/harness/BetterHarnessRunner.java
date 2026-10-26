package com.paicli.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.history.ConversationLedger;
import com.paicli.llm.LlmClient;
import com.paicli.skill.Skill;
import com.paicli.skill.SkillRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * PaiCLI-native Better Harness workflow.
 *
 * <p>Three read-only evidence specialists run independently and in parallel.
 * A lead model call reconciles their candidate findings, then deterministic Java
 * code owns durable report rendering.
 */
public final class BetterHarnessRunner {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int TOTAL_WORK_UNITS = 5;
    private static final DateTimeFormatter RUN_ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final String ATTRIBUTION =
            "Method adapted from QoderAI/better-harness (MIT), Agent Work Loop.";

    public record RunResult(String reportMarkdown,
                            int findingCount,
                            Path reportMarkdownPath,
                            Path reportHtmlPath,
                            Path findingsJsonPath,
                            boolean durable) {
    }

    public enum ProgressStage {
        COLLECTING,
        ANALYZING,
        RECONCILING,
        RENDERING,
        COMPLETE
    }

    public record ProgressEvent(ProgressStage stage,
                                String message,
                                int completed,
                                int total) {
    }

    @FunctionalInterface
    public interface ProgressListener {
        ProgressListener NO_OP = event -> {
        };

        void onProgress(ProgressEvent event);
    }

    private record Lane(String id, String question, String evidence) {
    }

    private record LaneResult(String id, String analysis) {
    }

    private record Draft(String markdown, ArrayNode findings, boolean structured) {
    }

    private final LlmClient llmClient;
    private final Path workspace;
    private final ConversationLedger ledger;
    private final SkillRegistry skillRegistry;
    private final Clock clock;

    public BetterHarnessRunner(LlmClient llmClient,
                               Path workspace,
                               ConversationLedger ledger,
                               SkillRegistry skillRegistry) {
        this(llmClient, workspace, ledger, skillRegistry, Clock.systemDefaultZone());
    }

    BetterHarnessRunner(LlmClient llmClient,
                        Path workspace,
                        ConversationLedger ledger,
                        SkillRegistry skillRegistry,
                        Clock clock) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.workspace = workspace.toAbsolutePath().normalize();
        this.ledger = ledger == null ? ConversationLedger.disabled() : ledger;
        this.skillRegistry = skillRegistry;
        this.clock = clock;
    }

    public RunResult run(BetterHarnessOptions options) throws IOException {
        return run(options, ProgressListener.NO_OP);
    }

    public RunResult run(BetterHarnessOptions options,
                         ProgressListener progressListener) throws IOException {
        ProgressListener progress = progressListener == null
                ? ProgressListener.NO_OP
                : progressListener;
        notifyProgress(progress, ProgressStage.COLLECTING,
                "正在冻结脱敏证据快照", 0);
        BetterHarnessEvidenceCollector.EvidenceBundle bundle =
                new BetterHarnessEvidenceCollector(workspace, ledger, skillRegistry)
                        .collect(options.depth());
        notifyProgress(progress, ProgressStage.ANALYZING,
                "证据已冻结，启动 3 路并行审查", 1);
        String skillGuidance = resolveSkillGuidance();
        List<Lane> lanes = List.of(
                new Lane(
                        "session-evidence",
                        "Does the observed task episode show reproducible execution, validation, "
                                + "safe delivery, and learning signals? Keep content-dependent claims unobserved.",
                        bundle.sessionEvidence()),
                new Lane(
                        "project-harness",
                        "Do repository guidance, validation, and delivery mechanisms make the "
                                + "coding-agent workflow explicit and reproducible?",
                        bundle.projectHarness()),
                new Lane(
                        "agent-customize",
                        "Are PaiCLI rules, Skills, prompts, MCP surfaces, Memory entrypoints, and "
                                + "policy assets present and coherently wired? Presence never proves use.",
                        bundle.agentCustomize()));

        List<LaneResult> laneResults =
                runEvidencePasses(lanes, skillGuidance, options.depth(), progress);
        notifyProgress(progress, ProgressStage.RECONCILING,
                "3 路审查已完成，Lead 正在汇总 findings", 4);
        Draft draft = reconcile(laneResults, skillGuidance, options.depth());
        notifyProgress(progress, ProgressStage.RENDERING,
                options.inline() ? "报告已生成，正在准备终端输出" : "报告已生成，正在写入文件", 5);
        String markdown = normalizeMarkdown(draft.markdown(), draft.structured());
        if (options.inline()) {
            notifyProgress(progress, ProgressStage.COMPLETE,
                    "审计完成", 5);
            return new RunResult(markdown, draft.findings().size(), null, null, null, false);
        }
        RunResult result = writeDurableReport(markdown, draft.findings(), options, bundle);
        notifyProgress(progress, ProgressStage.COMPLETE,
                "审计完成，已生成 3 份报告文件", 5);
        return result;
    }

    private List<LaneResult> runEvidencePasses(List<Lane> lanes,
                                               String skillGuidance,
                                               BetterHarnessOptions.Depth depth,
                                               ProgressListener progress)
            throws IOException {
        ExecutorService executor = Executors.newFixedThreadPool(3, runnable -> {
            Thread thread = new Thread(runnable, "paicli-better-harness-evidence");
            thread.setDaemon(true);
            return thread;
        });
        try {
            CompletionService<LaneResult> completionService =
                    new ExecutorCompletionService<>(executor);
            List<Callable<LaneResult>> tasks = lanes.stream()
                    .<Callable<LaneResult>>map(lane -> () ->
                            analyzeLane(lane, skillGuidance, depth))
                    .toList();
            for (Callable<LaneResult> task : tasks) {
                completionService.submit(task);
            }
            Map<String, LaneResult> completedById = new HashMap<>();
            for (int completed = 1; completed <= lanes.size(); completed++) {
                try {
                    Future<LaneResult> future = completionService.take();
                    LaneResult result = future.get();
                    completedById.put(result.id(), result);
                    notifyProgress(progress, ProgressStage.ANALYZING,
                            laneDisplayName(result.id()) + " 审查完成",
                            1 + completed);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof IOException ioException) {
                        throw ioException;
                    }
                    throw new IOException("Better Harness evidence pass failed: "
                            + cause.getMessage(), cause);
                }
            }
            List<LaneResult> ordered = new ArrayList<>(lanes.size());
            for (Lane lane : lanes) {
                LaneResult result = completedById.get(lane.id());
                if (result != null) {
                    ordered.add(result);
                }
            }
            return ordered;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Better Harness review interrupted", e);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void notifyProgress(ProgressListener listener,
                                       ProgressStage stage,
                                       String message,
                                       int completed) {
        listener.onProgress(new ProgressEvent(
                stage,
                message,
                Math.max(0, Math.min(TOTAL_WORK_UNITS, completed)),
                TOTAL_WORK_UNITS));
    }

    private static String laneDisplayName(String id) {
        return switch (id) {
            case "session-evidence" -> "Session Evidence";
            case "project-harness" -> "Project Harness";
            case "agent-customize" -> "Agent Customize";
            default -> id;
        };
    }

    private LaneResult analyzeLane(Lane lane,
                                   String skillGuidance,
                                   BetterHarnessOptions.Depth depth) throws IOException {
        String system = """
                You are one independent, read-only Better Harness evidence specialist.
                Analyze only the supplied lane. Do not infer evidence from counts, file presence,
                prior knowledge, or another lane. Missing evidence stays explicit.
                Return 0-3 candidate findings for quick depth or 0-5 for normal depth.
                For every candidate provide: dimension, observed consequence, evidence,
                confidence, smallest repair owner, and acceptance check.
                Do not assign the final severity or an overall score.

                PaiCLI adaptation guidance:
                %s
                """.formatted(skillGuidance);
        String user = """
                Review depth: %s
                Lane: %s
                Question: %s

                Evidence snapshot:
                %s
                """.formatted(depth.name().toLowerCase(), lane.id(), lane.question(), lane.evidence());
        ledger.appendMessage("harness", lane.id(), "specialist_prompt", LlmClient.Message.user(user));
        LlmClient.ChatResponse response = llmClient.chat(
                List.of(LlmClient.Message.system(system), LlmClient.Message.user(user)),
                null);
        String analysis = response.content() == null ? "" : response.content().trim();
        ledger.appendMessage(
                "harness", lane.id(), "specialist_result", LlmClient.Message.assistant(analysis));
        return new LaneResult(lane.id(), analysis);
    }

    private Draft reconcile(List<LaneResult> laneResults,
                            String skillGuidance,
                            BetterHarnessOptions.Depth depth) throws IOException {
        String system = """
                You are the lead reviewer for a PaiCLI-native Better Harness run.
                Reconcile the three independent evidence passes. Keep only findings with a
                defensible consequence, evidence boundary, smallest repair owner, and verifier.
                Do not turn unobserved behavior into a negative score. Do not merge findings
                merely because they share a theme. Assign one final severity (High, Medium, Low)
                and one Agent Work Loop dimension to each retained finding.

                Return JSON only, with this exact shape:
                {
                  "reportMarkdown": "# PaiCLI Better Harness Report\\n...",
                  "findings": [
                    {
                      "id": "BH-001",
                      "severity": "High|Medium|Low",
                      "dimension": "Task Understanding|Controlled Execution|Change Validation|Reliable Delivery|Learning Capture",
                      "title": "...",
                      "evidence": ["..."],
                      "impact": "...",
                      "repair": "...",
                      "acceptanceChecks": ["..."]
                    }
                  ]
                }

                The Markdown report must contain: scope and limitations, five-dimension overview,
                prioritized findings, three or fewer next moves, and evidence brief.
                Write the report in Chinese. Do not claim causal improvement from one run.

                PaiCLI adaptation guidance:
                %s
                """.formatted(skillGuidance);
        StringBuilder user = new StringBuilder("Review depth: ")
                .append(depth.name().toLowerCase())
                .append("\nWorkspace: ")
                .append(workspace)
                .append("\n\n");
        for (LaneResult result : laneResults) {
            user.append("## ")
                    .append(result.id())
                    .append("\n")
                    .append(result.analysis())
                    .append("\n\n");
        }
        ledger.appendMessage(
                "harness", "lead", "reconciliation_prompt", LlmClient.Message.user(user.toString()));
        LlmClient.ChatResponse response = llmClient.chat(
                List.of(LlmClient.Message.system(system), LlmClient.Message.user(user.toString())),
                null);
        String content = response.content() == null ? "" : response.content().trim();
        ledger.appendMessage(
                "harness", "lead", "reconciliation_result", LlmClient.Message.assistant(content));
        return parseDraft(content);
    }

    private Draft parseDraft(String content) {
        String cleaned = stripCodeFence(content);
        try {
            JsonNode root = MAPPER.readTree(cleaned);
            String markdown = root.path("reportMarkdown").asText("");
            JsonNode findingsNode = root.path("findings");
            ArrayNode findings = findingsNode.isArray()
                    ? (ArrayNode) findingsNode.deepCopy()
                    : MAPPER.createArrayNode();
            if (!markdown.isBlank()) {
                return new Draft(markdown, findings, true);
            }
        } catch (Exception ignored) {
            // Preserve useful model output as a partial report instead of losing the run.
        }
        String fallback = content.isBlank()
                ? "# PaiCLI Better Harness Report\n\n本次 lead 未返回可用报告。"
                : content;
        return new Draft(fallback, MAPPER.createArrayNode(), false);
    }

    private RunResult writeDurableReport(String markdown,
                                         ArrayNode findings,
                                         BetterHarnessOptions options,
                                         BetterHarnessEvidenceCollector.EvidenceBundle bundle)
            throws IOException {
        String runId = RUN_ID_FORMAT.format(clock.instant().atZone(ZoneId.systemDefault()));
        Path runDir = workspace.resolve(".paicli")
                .resolve("better-harness")
                .resolve(runId);
        Files.createDirectories(runDir);

        Path markdownPath = runDir.resolve("report.md");
        Path htmlPath = runDir.resolve("report.html");
        Path findingsPath = runDir.resolve("findings.json");
        Files.writeString(markdownPath, markdown + "\n", StandardCharsets.UTF_8);
        Files.writeString(htmlPath, renderHtml(markdown), StandardCharsets.UTF_8);

        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode summary = root.putObject("summary");
        summary.put("schemaVersion", 1);
        summary.put("platform", "paicli");
        summary.put("workspace", workspace.toString());
        summary.put("depth", options.depth().name().toLowerCase());
        summary.put("generatedAt", clock.instant().toString());
        summary.put("findingCount", findings.size());
        summary.put("attribution", ATTRIBUTION);
        ObjectNode evidence = summary.putObject("evidence");
        evidence.put("sessionEvidenceChars", bundle.sessionEvidence().length());
        evidence.put("projectHarnessChars", bundle.projectHarness().length());
        evidence.put("agentCustomizeChars", bundle.agentCustomize().length());
        root.set("findings", findings);
        Files.writeString(
                findingsPath,
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n",
                StandardCharsets.UTF_8);

        return new RunResult(
                markdown,
                findings.size(),
                markdownPath,
                htmlPath,
                findingsPath,
                true);
    }

    private String resolveSkillGuidance() {
        if (skillRegistry == null) {
            return ATTRIBUTION;
        }
        Skill skill = skillRegistry.findSkill("better-harness");
        if (skill == null || skill.body().isBlank()) {
            return ATTRIBUTION;
        }
        return skill.body();
    }

    private static String normalizeMarkdown(String markdown, boolean structured) {
        String normalized = markdown == null ? "" : markdown.trim();
        if (!normalized.startsWith("# ")) {
            normalized = "# PaiCLI Better Harness Report\n\n" + normalized;
        }
        if (!structured) {
            normalized += "\n\n> 注意：lead 未返回规范 JSON，本报告按非结构化结果保留，"
                    + "`findings.json` 不包含可执行 finding。";
        }
        return normalized + "\n\n---\n\n" + ATTRIBUTION;
    }

    private static String stripCodeFence(String content) {
        String trimmed = content == null ? "" : content.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        if (firstNewline >= 0 && lastFence > firstNewline) {
            return trimmed.substring(firstNewline + 1, lastFence).trim();
        }
        return trimmed;
    }

    private static String renderHtml(String markdown) {
        String escaped = markdown
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
        return """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width,initial-scale=1">
                  <title>PaiCLI Better Harness Report</title>
                  <style>
                    :root { color-scheme: light dark; }
                    body { margin: 0; font-family: ui-sans-serif, system-ui, sans-serif;
                           background: #f4f7f6; color: #17211e; }
                    main { max-width: 1040px; margin: 40px auto; padding: 36px;
                           background: #fff; border: 1px solid #dce7e3;
                           border-radius: 18px; box-shadow: 0 14px 40px #0f766e18; }
                    pre { white-space: pre-wrap; overflow-wrap: anywhere; margin: 0;
                          font: 15px/1.75 ui-monospace, SFMono-Regular, Menlo, monospace; }
                    @media (prefers-color-scheme: dark) {
                      body { background: #0f1715; color: #dce7e3; }
                      main { background: #15201d; border-color: #294039; }
                    }
                  </style>
                </head>
                <body><main><pre>%s</pre></main></body>
                </html>
                """.formatted(escaped);
    }
}
