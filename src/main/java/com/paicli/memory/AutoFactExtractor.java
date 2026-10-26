package com.paicli.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Selects durable facts from the user's own submitted turn, never from tool or assistant text. */
public final class AutoFactExtractor {
    public record Result(List<String> facts, boolean called, int inputTokens,
                         int outputTokens, int cachedInputTokens) {
        static Result skipped() { return new Result(List.of(), false, 0, 0, 0); }
    }
    public static final String ENABLED_PROPERTY = "paicli.memory.auto.extract.enabled";
    public static final String ENABLED_ENV = "PAICLI_MEMORY_AUTO_EXTRACT_ENABLED";
    private static final int MAX_INPUT_CHARS = 4_000;
    private static final int MAX_FACTS = 3;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern POSSIBLE_FACT = Pattern.compile(
            "我|我们|本项目|项目|仓库|团队|公司|偏好|习惯|默认|技术栈|长期|以后|后续|接下来|始终|每次");
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)api[_ -]?key|access[_ -]?key|secret|password|passwd|bearer|token|sk-[a-z0-9]"
                    + "|密码|密钥|身份证|手机号|电话号码|邮箱|验证码");
    private static final Pattern TEMPORARY = Pattern.compile(
            "现在|这次|今天|明天|刚才|临时|马上|立刻");
    private static final String PROMPT = """
            从下面这条用户原文中，找出用户明确陈述、跨会话仍可能有用的稳定偏好或项目事实。
            只选用户自己说出的事实；任务要求、当前临时状态、推测、代码示例、引用资料、
            凭证和个人敏感信息一律不要选。不要补全、改写或推断。
            严格输出 JSON：{"facts":[{"quote":"用户原文中的连续片段"}]}。
            quote 必须逐字出现在用户原文中，最多 3 条；没有合格事实就输出 {"facts":[]}。

            === 用户原文 ===
            %s
            === 结束 ===
            """;

    private volatile LlmClient llmClient;

    public AutoFactExtractor(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public Result extractFacts(String submittedUserInput) throws IOException {
        if (submittedUserInput == null || submittedUserInput.length() < 5
                || submittedUserInput.length() > MAX_INPUT_CHARS
                || llmClient == null) {
            return Result.skipped();
        }
        // Keep unrelated stable statements, but never send a sentence containing a
        // credential or personal identifier to the extraction model.
        String safeInput = withoutSensitiveSentences(submittedUserInput);
        if (safeInput.length() < 5 || !POSSIBLE_FACT.matcher(safeInput).find()) {
            return Result.skipped();
        }
        LlmClient.ChatResponse response = llmClient.chat(List.of(
                LlmClient.Message.system("你只抽取用户明确陈述的稳定事实，只输出指定 JSON，不调用工具。"),
                LlmClient.Message.user(PROMPT.formatted(safeInput))
        ), null);
        if (response == null) return Result.skipped();
        Result empty = result(List.of(), response);
        if (response.content() == null) return empty;

        JsonNode root;
        try {
            root = JSON.readTree(response.content());
        } catch (IOException e) {
            return empty;
        }
        if (root == null || !root.isObject() || root.size() != 1
                || !root.has("facts") || !root.get("facts").isArray()
                || root.get("facts").size() > MAX_FACTS) {
            return empty;
        }
        Set<String> facts = new LinkedHashSet<>();
        for (JsonNode item : root.get("facts")) {
            if (!item.isObject() || item.size() != 1 || !item.has("quote")
                    || !item.get("quote").isTextual()) {
                return empty;
            }
            String quote = item.get("quote").asText().trim();
            if (quote.length() < 5 || quote.length() > 180 || quote.contains("\n")
                    || !safeInput.contains(quote)
                    || SENSITIVE.matcher(quote).find()
                    || TEMPORARY.matcher(quote).find()
                    || isQuotedOrQuestion(safeInput, quote)) {
                continue;
            }
            facts.add(quote);
        }
        return result(new ArrayList<>(facts), response);
    }

    private static Result result(List<String> facts, LlmClient.ChatResponse response) {
        return new Result(List.copyOf(facts), true, response.inputTokens(),
                response.outputTokens(), response.cachedInputTokens());
    }

    private static String withoutSensitiveSentences(String input) {
        StringBuilder safe = new StringBuilder();
        for (String sentence : input.split("(?<=[。；;\\n])", -1)) {
            if (!SENSITIVE.matcher(sentence).find()) safe.append(sentence);
        }
        return safe.toString().trim();
    }

    private static boolean isQuotedOrQuestion(String input, String quote) {
        int position = input.indexOf(quote);
        if (position < 0) return true;
        String before = input.substring(0, position);
        if (before.split("```", -1).length % 2 == 0) return true;
        int lineStart = input.lastIndexOf('\n', position);
        String precedingLine = input.substring(lineStart + 1, position).trim();
        if (precedingLine.startsWith(">")) return true;
        if (insidePairedQuote(input, position, quote.length(), '‘', '’')
                || insidePairedQuote(input, position, quote.length(), '“', '”')) return true;
        int sentenceEnd = input.length();
        for (char boundary : new char[]{'。', '；', ';', '\n'}) {
            int found = input.indexOf(boundary, position + quote.length());
            if (found >= 0) sentenceEnd = Math.min(sentenceEnd, found);
        }
        String sentence = input.substring(lineStart + 1, sentenceEnd);
        return sentence.contains("？") || sentence.contains("?");
    }

    private static boolean insidePairedQuote(String input, int start, int length,
                                             char open, char close) {
        String before = input.substring(0, start);
        return before.lastIndexOf(open) > before.lastIndexOf(close)
                && input.indexOf(close, start + length) >= 0;
    }

    /** Interactive CLI default; embedded agents enable extraction explicitly. */
    public static boolean enabledByConfiguration() {
        String raw = System.getProperty(ENABLED_PROPERTY);
        if (raw == null || raw.isBlank()) raw = System.getenv(ENABLED_ENV);
        if (raw == null || raw.isBlank()) raw = readDotEnv(new File(".env"));
        if (raw == null || raw.isBlank()) {
            raw = readDotEnv(new File(System.getProperty("user.home"), ".env"));
        }
        if (raw == null || raw.isBlank()) return true;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return !("false".equals(normalized) || "0".equals(normalized)
                || "no".equals(normalized) || "off".equals(normalized));
    }

    private static String readDotEnv(File file) {
        if (!file.isFile()) return null;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith(ENABLED_ENV + "=")) {
                    return trimmed.substring(ENABLED_ENV.length() + 1).trim();
                }
            }
        } catch (IOException ignored) {
            // The CLI retains its default when optional local configuration cannot be read.
        }
        return null;
    }
}
