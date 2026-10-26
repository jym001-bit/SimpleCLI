package com.paicli.eval.benchmark.scoring;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Fail-closed JSON codec shared by the frozen scoring contract and verifier report. */
final class ScoringJson {
    static final int MAX_JSON_BYTES = 1024 * 1024;

    private static final ObjectMapper MAPPER = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    private ScoringJson() {
    }

    static <T> T parse(String json, Class<T> type) throws IOException {
        if (json == null) {
            throw new IOException("scoring JSON must not be null");
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return parse(bytes, type);
    }

    static <T> T parse(byte[] bytes, Class<T> type) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_JSON_BYTES) {
            throw new IOException("scoring JSON size is outside the accepted bound");
        }
        try {
            return MAPPER.readValue(bytes, type);
        } catch (IOException | IllegalArgumentException error) {
            throw new IOException("invalid scoring JSON", error);
        }
    }

    static <T> T load(Path file, Class<T> type) throws IOException {
        if (file == null) {
            throw new IOException("scoring JSON path must not be null");
        }
        Path normalized = file.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)
                || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("scoring JSON must be a non-symlink regular file: " + normalized);
        }
        long size = Files.size(normalized);
        if (size <= 0 || size > MAX_JSON_BYTES) {
            throw new IOException("scoring JSON size is outside the accepted bound: " + size);
        }
        try (InputStream input = Files.newInputStream(normalized, StandardOpenOption.READ)) {
            try {
                return MAPPER.readValue(input, type);
            } catch (IOException | IllegalArgumentException error) {
                throw new IOException("invalid scoring JSON: " + normalized, error);
            }
        }
    }
}
