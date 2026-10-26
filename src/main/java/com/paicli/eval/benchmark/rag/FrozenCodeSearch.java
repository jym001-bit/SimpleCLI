package com.paicli.eval.benchmark.rag;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.tool.ToolOutput;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic benchmark implementation of {@code search_code}.
 *
 * <p>This class has no embedding, network, database, Ollama, or workspace-file
 * dependency. Ranking uses only frozen content and frozen semantic aliases from
 * {@link FrozenCodeSearchIndex}. It is suitable for wiring directly into
 * {@code BenchmarkToolRegistry.setFrozenCodeSearch(search::apply)}.</p>
 */
public final class FrozenCodeSearch implements Function<String, ToolOutput> {
    public static final String RANKING_VERSION = "frozen-semantic-terms-v1";
    public static final int DEFAULT_TOP_K = 5;
    public static final int MAX_TOP_K = 30;
    public static final int MAX_ARGUMENT_BYTES = 8 * 1024;
    public static final int MAX_OUTPUT_BYTES = 16 * 1024;
    public static final int MAX_SNIPPET_BYTES = 2 * 1024;

    private static final int MAX_QUERY_CHARACTERS = 1_024;
    private static final Set<String> ARGUMENT_FIELDS = Set.of("query", "top_k");
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("(?<=[\\p{Ll}\\p{N}])(?=\\p{Lu})");
    private static final ObjectMapper JSON = new ObjectMapper(
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final FrozenCodeSearchIndex index;
    private final List<SearchableDocument> documents;

    public FrozenCodeSearch(FrozenCodeSearchIndex index) {
        if (index == null) {
            throw new IllegalArgumentException("frozen code index must not be null");
        }
        this.index = index;
        this.documents = index.documents().stream().map(SearchableDocument::new).toList();
    }

    public FrozenCodeSearchIndex index() {
        return index;
    }

    @Override
    public ToolOutput apply(String argumentsJson) {
        SearchArguments arguments;
        try {
            arguments = parseArguments(argumentsJson);
        } catch (IOException | IllegalArgumentException error) {
            return ToolOutput.failure("benchmark frozen code search arguments are invalid: " + error.getMessage());
        }

        try {
            return ToolOutput.text(render(arguments));
        } catch (IOException | RuntimeException error) {
            return ToolOutput.failure("benchmark frozen code search failed closed");
        }
    }

    private String render(SearchArguments arguments) throws IOException {
        String normalizedQuery = normalizePhrase(arguments.query());
        Set<String> queryTokens = tokenize(arguments.query());
        List<RankedDocument> matches = documents.stream()
                .map(document -> new RankedDocument(document, score(document, normalizedQuery, queryTokens)))
                .filter(result -> result.score() > 0)
                .sorted(RankedDocument.ORDER)
                .toList();

        int candidateCount = Math.min(arguments.topK(), matches.size());
        List<Result> returned = new ArrayList<>(candidateCount);
        boolean contentWasTruncated = false;
        boolean outputBudgetWasHit = false;

        for (int index = 0; index < candidateCount; index++) {
            RankedDocument ranked = matches.get(index);
            Utf8Prefix snippet = utf8Prefix(ranked.document().source().content(), MAX_SNIPPET_BYTES);
            Result result = new Result(
                    index + 1,
                    ranked.document().source().path(),
                    ranked.document().source().startLine(),
                    ranked.document().source().endLine(),
                    ranked.document().source().digest(),
                    ranked.score(),
                    snippet.value(),
                    snippet.partial());
            returned.add(result);
            contentWasTruncated |= snippet.partial();

            Response provisional = response(arguments, matches.size(), returned, true);
            if (serializedBytes(provisional) > MAX_OUTPUT_BYTES) {
                returned.remove(returned.size() - 1);
                outputBudgetWasHit = true;
                break;
            }
        }

        boolean partial = contentWasTruncated
                || outputBudgetWasHit
                || matches.size() > returned.size();
        Response response = response(arguments, matches.size(), returned, partial);
        String json = JSON.writeValueAsString(response);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES) {
            throw new IOException("frozen search output exceeded its deterministic budget");
        }
        return json;
    }

    private Response response(SearchArguments arguments,
                              int matched,
                              List<Result> returned,
                              boolean partial) {
        return new Response(
                "paicli.benchmark.frozen_code_search",
                RANKING_VERSION,
                index.version(),
                index.indexDigest(),
                arguments.query(),
                arguments.topK(),
                matched,
                returned.size(),
                partial,
                List.copyOf(returned));
    }

    private static long serializedBytes(Response response) throws IOException {
        return JSON.writeValueAsBytes(response).length;
    }

    private static long score(SearchableDocument document,
                              String normalizedQuery,
                              Set<String> queryTokens) {
        long score = 0;
        for (String token : queryTokens) {
            if (document.semanticTokens().contains(token)) {
                score += 100;
            }
            if (document.pathTokens().contains(token)) {
                score += 30;
            }
            if (document.contentTokens().contains(token)) {
                score += 10;
            }
        }
        for (String term : document.normalizedSemanticTerms()) {
            if (normalizedQuery.equals(term)) {
                score += 800;
            } else if (containsPhrase(normalizedQuery, term)) {
                score += 400L + 25L * tokenize(term).size();
            } else if (containsPhrase(term, normalizedQuery)) {
                score += 200;
            }
        }
        if (containsPhrase(document.normalizedContent(), normalizedQuery)) {
            score += 160;
        }
        if (containsPhrase(document.normalizedPath(), normalizedQuery)) {
            score += 120;
        }
        return score;
    }

    private static boolean containsPhrase(String text, String phrase) {
        if (text.isEmpty() || phrase.isEmpty()) {
            return false;
        }
        return (" " + text + " ").contains(" " + phrase + " ");
    }

    private static SearchArguments parseArguments(String argumentsJson) throws IOException {
        if (argumentsJson == null) {
            throw new IOException("arguments must not be null");
        }
        byte[] bytes = argumentsJson.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_ARGUMENT_BYTES) {
            throw new IOException("argument size is outside the accepted bound");
        }
        JsonNode parsed = JSON.readTree(bytes);
        if (!(parsed instanceof ObjectNode object)) {
            throw new IOException("arguments must be a JSON object");
        }
        Iterator<String> fields = object.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!ARGUMENT_FIELDS.contains(field)) {
                throw new IOException("unknown argument field: " + field);
            }
        }

        JsonNode queryNode = object.get("query");
        if (queryNode == null || !queryNode.isTextual()) {
            throw new IOException("query must be a string");
        }
        String query = queryNode.textValue().trim();
        if (query.isEmpty() || query.length() > MAX_QUERY_CHARACTERS
                || query.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                && !Character.isWhitespace(codePoint))) {
            throw new IOException("query is blank, too long, or contains control characters");
        }

        int topK = DEFAULT_TOP_K;
        JsonNode topKNode = object.get("top_k");
        if (topKNode != null) {
            if (!topKNode.isIntegralNumber() || !topKNode.canConvertToInt()) {
                throw new IOException("top_k must be an integer");
            }
            topK = Math.max(1, Math.min(topKNode.intValue(), MAX_TOP_K));
        }
        return new SearchArguments(query, topK);
    }

    private static String normalizePhrase(String value) {
        String camelSeparated = CAMEL_BOUNDARY.matcher(value).replaceAll(" ");
        String normalized = Normalizer.normalize(camelSeparated, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        Matcher matcher = TOKEN.matcher(normalized);
        StringBuilder phrase = new StringBuilder();
        while (matcher.find()) {
            if (!phrase.isEmpty()) {
                phrase.append(' ');
            }
            phrase.append(matcher.group());
        }
        return phrase.toString();
    }

    private static Set<String> tokenize(String value) {
        String normalized = normalizePhrase(value);
        if (normalized.isEmpty()) {
            return Set.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : normalized.split(" ")) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return Set.copyOf(tokens);
    }

    private static Utf8Prefix utf8Prefix(String value, int maxBytes) {
        byte[] full = value.getBytes(StandardCharsets.UTF_8);
        if (full.length <= maxBytes) {
            return new Utf8Prefix(value, false);
        }
        int usedBytes = 0;
        int end = 0;
        while (end < value.length()) {
            int codePoint = value.codePointAt(end);
            int charCount = Character.charCount(codePoint);
            int codePointBytes = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (usedBytes + codePointBytes > maxBytes) {
                break;
            }
            usedBytes += codePointBytes;
            end += charCount;
        }
        return new Utf8Prefix(value.substring(0, end), true);
    }

    private record SearchArguments(String query, int topK) {
    }

    private record SearchableDocument(FrozenCodeSearchIndex.Document source,
                                      String normalizedPath,
                                      String normalizedContent,
                                      List<String> normalizedSemanticTerms,
                                      Set<String> pathTokens,
                                      Set<String> contentTokens,
                                      Set<String> semanticTokens) {
        private SearchableDocument(FrozenCodeSearchIndex.Document source) {
            this(source,
                    normalizePhrase(source.path()),
                    normalizePhrase(source.content()),
                    source.semanticTerms().stream().map(FrozenCodeSearch::normalizePhrase).toList(),
                    tokenize(source.path()),
                    tokenize(source.content()),
                    semanticTokens(source.semanticTerms()));
        }

        private static Set<String> semanticTokens(List<String> terms) {
            Set<String> tokens = new HashSet<>();
            for (String term : terms) {
                tokens.addAll(tokenize(term));
            }
            return Set.copyOf(tokens);
        }
    }

    private record RankedDocument(SearchableDocument document, long score) {
        private static final Comparator<RankedDocument> ORDER = Comparator
                .comparingLong(RankedDocument::score).reversed()
                .thenComparing(result -> result.document().source().path())
                .thenComparingInt(result -> result.document().source().startLine())
                .thenComparingInt(result -> result.document().source().endLine())
                .thenComparing(result -> result.document().source().digest());
    }

    private record Utf8Prefix(String value, boolean partial) {
    }

    private record Response(String type,
                            String rankingVersion,
                            String indexVersion,
                            String indexDigest,
                            String query,
                            int requestedTopK,
                            int matched,
                            int returned,
                            boolean partial,
                            List<Result> results) {
    }

    private record Result(int rank,
                          String path,
                          int startLine,
                          int endLine,
                          String digest,
                          long score,
                          String content,
                          boolean contentPartial) {
    }
}
