package com.paicli.eval.benchmark.rag;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Immutable, content-addressed input for the benchmark-only offline code search.
 *
 * <p>The document content is carried inside the index. A loaded index never
 * resolves {@link Document#path()} against a workspace, so an indexed result
 * cannot be changed by unregistered workspace files after the freeze.</p>
 */
public final class FrozenCodeSearchIndex {
    public static final String VERSION = "paicli-benchmark-frozen-code-index-v1";
    public static final int MAX_INDEX_BYTES = 4 * 1024 * 1024;
    public static final int MAX_DOCUMENTS = 4_096;
    public static final int MAX_DOCUMENT_CONTENT_BYTES = 64 * 1024;
    public static final int MAX_TERMS_PER_DOCUMENT = 128;

    private static final int MAX_PATH_LENGTH = 512;
    private static final int MAX_TERM_LENGTH = 256;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final ObjectMapper STRICT_JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    private final String version;
    private final String indexDigest;
    private final List<Document> documents;

    public FrozenCodeSearchIndex(String version, String indexDigest, List<Document> documents) {
        if (!VERSION.equals(version)) {
            throw new IllegalArgumentException("unsupported frozen code index version");
        }
        requireDigest(indexDigest, "index digest");
        if (documents == null || documents.isEmpty() || documents.size() > MAX_DOCUMENTS) {
            throw new IllegalArgumentException("frozen code index document count is outside the accepted bound");
        }
        if (documents.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("frozen code index must not contain null documents");
        }

        List<Document> frozenDocuments = List.copyOf(documents);
        Set<DocumentIdentity> identities = new HashSet<>();
        for (Document document : frozenDocuments) {
            DocumentIdentity identity = new DocumentIdentity(
                    document.path(), document.startLine(), document.endLine());
            if (!identities.add(identity)) {
                throw new IllegalArgumentException("duplicate frozen code document slice: " + document.path());
            }
        }

        String computedDigest = computeIndexDigest(frozenDocuments);
        if (!MessageDigest.isEqual(
                indexDigest.getBytes(StandardCharsets.US_ASCII),
                computedDigest.getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("frozen code index digest mismatch");
        }

        this.version = version;
        this.indexDigest = indexDigest;
        this.documents = frozenDocuments;
    }

    public String version() {
        return version;
    }

    public String indexDigest() {
        return indexDigest;
    }

    public List<Document> documents() {
        return documents;
    }

    /** Loads only the registered index file. Document paths are never opened. */
    public static FrozenCodeSearchIndex load(Path indexFile) throws IOException {
        if (indexFile == null) {
            throw new IOException("frozen code index path must not be null");
        }
        Path normalized = indexFile.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)
                || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("frozen code index must be a non-symlink regular file: " + normalized);
        }
        long size = Files.size(normalized);
        if (size <= 0 || size > MAX_INDEX_BYTES) {
            throw new IOException("frozen code index size is outside the accepted bound: " + size);
        }
        byte[] bytes = Files.readAllBytes(normalized);
        if (bytes.length != size || bytes.length > MAX_INDEX_BYTES) {
            throw new IOException("frozen code index changed while it was being loaded");
        }
        return parse(bytes);
    }

    public static FrozenCodeSearchIndex parse(byte[] json) throws IOException {
        if (json == null || json.length == 0 || json.length > MAX_INDEX_BYTES) {
            throw new IOException("frozen code index JSON size is outside the accepted bound");
        }
        try {
            WireIndex wire = STRICT_JSON.readValue(json, WireIndex.class);
            List<Document> documents = wire.documents().stream()
                    .map(document -> new Document(
                            document.path(),
                            document.startLine(),
                            document.endLine(),
                            document.content(),
                            document.digest(),
                            document.semanticTerms()))
                    .toList();
            return new FrozenCodeSearchIndex(wire.version(), wire.indexDigest(), documents);
        } catch (IOException | IllegalArgumentException | NullPointerException error) {
            throw new IOException("invalid frozen code index JSON", error);
        }
    }

    /**
     * Computes the versioned digest over an unambiguous length-prefixed binary
     * representation. JSON whitespace and object field order are intentionally
     * outside the identity; document and semantic-term array order are inside it.
     */
    public static String computeIndexDigest(List<Document> documents) {
        if (documents == null) {
            throw new IllegalArgumentException("documents must not be null");
        }
        MessageDigest digest = sha256();
        updateString(digest, VERSION);
        updateInt(digest, documents.size());
        for (Document document : documents) {
            if (document == null) {
                throw new IllegalArgumentException("documents must not contain null entries");
            }
            updateString(digest, document.path());
            updateInt(digest, document.startLine());
            updateInt(digest, document.endLine());
            updateString(digest, document.content());
            updateString(digest, document.digest());
            updateInt(digest, document.semanticTerms().size());
            for (String term : document.semanticTerms()) {
                updateString(digest, term);
            }
        }
        return hex(digest.digest());
    }

    public static String sha256Hex(String content) {
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        return hex(sha256().digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    /** One frozen code slice and its precomputed semantic aliases. */
    public record Document(String path,
                           int startLine,
                           int endLine,
                           String content,
                           String digest,
                           List<String> semanticTerms) {
        public Document {
            validatePath(path);
            if (startLine < 1 || endLine < startLine) {
                throw new IllegalArgumentException("frozen code document line range is invalid");
            }
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("frozen code document content must not be blank");
            }
            if (content.indexOf('\r') >= 0 || content.indexOf('\0') >= 0
                    || content.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_CONTENT_BYTES) {
                throw new IllegalArgumentException("frozen code document content is not normalized or is too large");
            }
            int lineCount = logicalLineCount(content);
            if (endLine != startLine + lineCount - 1) {
                throw new IllegalArgumentException("frozen code document line range does not match its content");
            }
            requireDigest(digest, "document digest");
            String computedDigest = sha256Hex(content);
            if (!MessageDigest.isEqual(
                    digest.getBytes(StandardCharsets.US_ASCII),
                    computedDigest.getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalArgumentException("frozen code document digest mismatch: " + path);
            }
            if (semanticTerms == null || semanticTerms.isEmpty()
                    || semanticTerms.size() > MAX_TERMS_PER_DOCUMENT) {
                throw new IllegalArgumentException("semantic term count is outside the accepted bound: " + path);
            }
            Set<String> normalizedTerms = new HashSet<>();
            for (String term : semanticTerms) {
                if (term == null || term.isBlank() || term.length() > MAX_TERM_LENGTH
                        || containsControl(term)) {
                    throw new IllegalArgumentException("invalid frozen semantic term: " + path);
                }
                if (!normalizedTerms.add(normalizeTerm(term))) {
                    throw new IllegalArgumentException("duplicate frozen semantic term: " + path);
                }
            }
            semanticTerms = List.copyOf(semanticTerms);
        }
    }

    private static void validatePath(String path) {
        if (path == null || path.isBlank() || path.length() > MAX_PATH_LENGTH
                || path.startsWith("/") || path.endsWith("/") || path.indexOf('\\') >= 0
                || containsControl(path)) {
            throw new IllegalArgumentException("frozen code document path is invalid");
        }
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("frozen code document path is not normalized");
            }
        }
    }

    private static boolean containsControl(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private static int logicalLineCount(String content) {
        int effectiveLength = content.endsWith("\n") ? content.length() - 1 : content.length();
        int lines = 1;
        for (int index = 0; index < effectiveLength; index++) {
            if (content.charAt(index) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    private static String normalizeTerm(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static void requireDigest(String digest, String label) {
        if (digest == null || !SHA256.matcher(digest).matches()) {
            throw new IllegalArgumentException(label + " must be a lowercase SHA-256 hex digest");
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private static void updateString(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        updateInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            result.append(Character.forDigit(value & 0x0f, 16));
        }
        return result.toString();
    }

    private record DocumentIdentity(String path, int startLine, int endLine) {
    }

    private record WireIndex(
            @JsonProperty(value = "version", required = true) String version,
            @JsonProperty(value = "indexDigest", required = true) String indexDigest,
            @JsonProperty(value = "documents", required = true) List<WireDocument> documents) {
    }

    private record WireDocument(
            @JsonProperty(value = "path", required = true) String path,
            @JsonProperty(value = "startLine", required = true) int startLine,
            @JsonProperty(value = "endLine", required = true) int endLine,
            @JsonProperty(value = "content", required = true) String content,
            @JsonProperty(value = "digest", required = true) String digest,
            @JsonProperty(value = "semanticTerms", required = true) List<String> semanticTerms) {
    }
}
