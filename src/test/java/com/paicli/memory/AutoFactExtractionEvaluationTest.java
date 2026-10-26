package com.paicli.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.PaiCliConfig;
import com.paicli.llm.LlmClient;
import com.paicli.llm.LlmClientFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Frozen synthetic inputs and human-written gold spans. Live evaluation is explicit opt-in. */
class AutoFactExtractionEvaluationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LIVE_PROPERTY = "paicli.test.memory.live";
    private static final String PROVIDER_PROPERTY = "paicli.test.memory.provider";

    @TempDir Path tempDir;

    @Test
    void idealExtractorCanSaveEveryEligibleGoldFact() throws IOException {
        List<EvalCase> cases = loadCases();
        Evaluation result = evaluate(cases, true, null, "oracle");
        writeReport(result);
        assertEquals(1.0, result.f1(), "Deterministic storage/filtering lost a gold fact");
        assertEquals(cases.size(), result.exactCases(), "Deterministic storage/filtering changed a case");
    }

    @Test
    @EnabledIfSystemProperty(named = LIVE_PROPERTY, matches = "true")
    void liveModelMeetsQualityGate() throws IOException {
        String provider = System.getProperty(PROVIDER_PROPERTY, "glm");
        LlmClient model = LlmClientFactory.create(provider, PaiCliConfig.load());
        assertNotNull(model, "No configured model/key for " + provider);
        Evaluation result = evaluate(loadCases(), false, model, provider + "-" + model.getModelName());
        writeReport(result);
        assertTrue(result.f1() >= 0.85, "Live extraction F1 below the predeclared 0.85 gate");
        assertTrue(result.exactCases() >= 15, "Fewer than 15 of 18 cases passed exactly");
        assertEquals(0, result.negativeFalsePositives(), "A negative case wrote a memory");
    }

    @Test
    void twoAtomicFactsCountAsTwoCorrectPredictions() {
        assertEquals(2, matchedFacts(List.of("仓库的主语言是 Kotlin", "构建工具是 Gradle"),
                List.of("仓库的主语言是 Kotlin", "构建工具是 Gradle")));
    }

    private Evaluation evaluate(List<EvalCase> cases, boolean oracle, LlmClient model,
                                String runName) throws IOException {
        List<Map<String, Object>> details = new ArrayList<>();
        int goldCount = 0, savedCount = 0, truePositives = 0, exactCases = 0;
        int negativeFalsePositives = 0, modelCalls = 0, inputTokens = 0, outputTokens = 0;
        for (EvalCase item : cases) {
            Path storeDir = Files.createDirectories(tempDir.resolve(runName).resolve(item.id()));
            LlmClient client = oracle ? new OracleClient(item.gold()) : model;
            MemoryManager manager = new MemoryManager(client, 32_768, 128_000,
                    new LongTermMemory(storeDir.toFile()));
            manager.setProjectPath(storeDir.toString());
            manager.setAutoFactExtractionEnabled(true);
            List<String> saved = manager.extractFactsFromUserTurn(item.input()).stream()
                    .map(MemoryEntry::getContent).toList();
            int tp = matchedFacts(item.gold(), saved);
            int fp = saved.size() - tp;
            boolean pass = tp == item.gold().size() && fp == 0;
            goldCount += item.gold().size();
            savedCount += saved.size();
            truePositives += tp;
            if (pass) exactCases++;
            if (item.gold().isEmpty()) negativeFalsePositives += saved.size();
            TokenBudget usage = manager.getTokenBudget();
            modelCalls += usage.getLlmCallCount();
            inputTokens += usage.getTotalInputTokens();
            outputTokens += usage.getTotalOutputTokens();
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("id", item.id());
            detail.put("category", item.category());
            detail.put("gold", item.gold());
            detail.put("saved", saved);
            detail.put("pass", pass);
            detail.put("modelCalls", usage.getLlmCallCount());
            details.add(detail);
        }
        return new Evaluation(runName, cases.size(), goldCount, savedCount, truePositives,
                exactCases, negativeFalsePositives, modelCalls, inputTokens, outputTokens,
                details);
    }

    private static int matchedFacts(List<String> gold, List<String> saved) {
        boolean[] matched = new boolean[gold.size()];
        int count = 0;
        for (String prediction : saved) {
            for (int i = 0; i < gold.size(); i++) {
                if (!matched[i] && prediction.contains(gold.get(i))) {
                    matched[i] = true;
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    private static List<EvalCase> loadCases() throws IOException {
        try (InputStream stream = AutoFactExtractionEvaluationTest.class.getResourceAsStream(
                "/memory/auto-fact-eval-v2.json")) {
            assertNotNull(stream);
            JsonNode root = JSON.readTree(stream);
            List<EvalCase> cases = new ArrayList<>();
            for (JsonNode node : root) {
                List<String> gold = new ArrayList<>();
                node.get("gold").forEach(value -> gold.add(value.asText()));
                String input = node.get("input").asText();
                for (String quote : gold) assertTrue(input.contains(quote), node.get("id").asText());
                cases.add(new EvalCase(node.get("id").asText(), node.get("category").asText(),
                        input, List.copyOf(gold)));
            }
            assertEquals(18, cases.size(), "Changing the benchmark needs a new version and gate");
            return List.copyOf(cases);
        }
    }

    private static void writeReport(Evaluation result) throws IOException {
        Path dir = Files.createDirectories(Path.of("target", "memory-eval"));
        Path file = dir.resolve(result.runName() + "-v2.json");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("dataset", "auto-fact-eval-v2");
        report.put("model", result.runName());
        report.put("cases", result.cases());
        report.put("goldFacts", result.goldCount());
        report.put("savedFacts", result.savedCount());
        report.put("truePositives", result.truePositives());
        report.put("precision", result.precision());
        report.put("recall", result.recall());
        report.put("f1", result.f1());
        report.put("exactCases", result.exactCases());
        report.put("negativeFalsePositives", result.negativeFalsePositives());
        report.put("modelCalls", result.modelCalls());
        report.put("inputTokens", result.inputTokens());
        report.put("outputTokens", result.outputTokens());
        report.put("details", result.details());
        JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), report);
        System.out.printf("Memory eval %s: %d/%d exact, P=%.3f R=%.3f F1=%.3f, negative FP=%d, calls=%d, tokens=%d+%d; %s%n",
                result.runName(), result.exactCases(), result.cases(), result.precision(),
                result.recall(), result.f1(), result.negativeFalsePositives(),
                result.modelCalls(), result.inputTokens(), result.outputTokens(), file);
    }

    private record EvalCase(String id, String category, String input, List<String> gold) {}

    private record Evaluation(String runName, int cases, int goldCount, int savedCount,
                              int truePositives, int exactCases, int negativeFalsePositives,
                              int modelCalls, int inputTokens, int outputTokens,
                              List<Map<String, Object>> details) {
        double precision() { return savedCount == 0 ? 0 : (double) truePositives / savedCount; }
        double recall() { return goldCount == 0 ? 0 : (double) truePositives / goldCount; }
        double f1() {
            double p = precision(), r = recall();
            return p + r == 0 ? 0 : 2 * p * r / (p + r);
        }
    }

    private static final class OracleClient implements LlmClient {
        private final List<String> gold;

        private OracleClient(List<String> gold) { this.gold = gold; }

        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools)
                throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                           StreamListener listener) throws IOException {
            List<Map<String, String>> facts = gold.stream().map(q -> Map.of("quote", q)).toList();
            return new ChatResponse("assistant", JSON.writeValueAsString(Map.of("facts", facts)),
                    null, 10, 10);
        }

        @Override public String getModelName() { return "oracle"; }
        @Override public String getProviderName() { return "test"; }
    }
}
