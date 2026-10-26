package com.paicli.eval.benchmark;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Versioned, strictly validated benchmark suite manifest. */
public record SuiteDefinition(
        @JsonProperty(value = "version", required = true) String version,
        @JsonProperty(value = "name", required = true) String name,
        @JsonProperty(value = "cases", required = true) List<CaseDefinition> cases,
        @JsonIgnore Path sourceDirectory
) {
    private static final ObjectMapper MAPPER = new ObjectMapper(
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @JsonCreator
    public SuiteDefinition(
            @JsonProperty(value = "version", required = true) String version,
            @JsonProperty(value = "name", required = true) String name,
            @JsonProperty(value = "cases", required = true) List<CaseDefinition> cases) {
        this(version, name, cases, null);
    }

    public SuiteDefinition {
        cases = cases == null ? null : List.copyOf(cases);
        sourceDirectory = sourceDirectory == null ? null : sourceDirectory.toAbsolutePath().normalize();
    }

    public static SuiteDefinition load(Path suiteFile) throws IOException {
        if (suiteFile == null) {
            throw new IllegalArgumentException("suite file must not be null");
        }
        Path absolute = suiteFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) {
            throw new IOException("benchmark suite file does not exist: " + absolute);
        }

        SuiteDefinition parsed;
        try (InputStream input = Files.newInputStream(absolute)) {
            parsed = MAPPER.readValue(input, SuiteDefinition.class);
        }
        SuiteDefinition loaded = new SuiteDefinition(
                parsed.version(), parsed.name(), parsed.cases(), absolute.getParent());
        loaded.validate();
        return loaded;
    }

    public List<CaseDefinition> activeCases() {
        return cases.stream()
                .filter(definition -> definition.status() == CaseDefinition.Status.ACTIVE)
                .toList();
    }

    public Path resolveFixture(CaseDefinition definition) {
        requireLoaded();
        if (definition == null || !cases.contains(definition)) {
            throw new IllegalArgumentException("case does not belong to this suite");
        }
        return definition.resolveFixture(sourceDirectory);
    }

    public CaseDefinition.VerifierInvocation resolveVerifier(CaseDefinition definition) {
        requireLoaded();
        if (definition == null || !cases.contains(definition)) {
            throw new IllegalArgumentException("case does not belong to this suite");
        }
        return definition.resolveVerifier(sourceDirectory);
    }

    private void validate() {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("suite version must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("suite name must not be blank");
        }
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("suite cases must not be empty");
        }
        if (sourceDirectory == null) {
            throw new IllegalArgumentException("suite must be loaded from a file before validation");
        }

        Set<String> ids = new HashSet<>();
        int activeWeight = 0;
        int activeCount = 0;
        for (CaseDefinition definition : cases) {
            if (definition == null) {
                throw new IllegalArgumentException("suite cases must not contain null entries");
            }
            definition.validate(sourceDirectory);
            if (!ids.add(definition.id())) {
                throw new IllegalArgumentException("duplicate case id: " + definition.id());
            }
            if (definition.status() == CaseDefinition.Status.ACTIVE) {
                activeCount++;
                activeWeight += definition.weight();
            }
        }
        if (activeCount == 0) {
            throw new IllegalArgumentException("suite must contain at least one active case");
        }
        if (activeWeight != 100) {
            throw new IllegalArgumentException("active case weights must sum to 100, got " + activeWeight);
        }
    }

    private void requireLoaded() {
        if (sourceDirectory == null) {
            throw new IllegalStateException("suite has no source directory; load it with SuiteDefinition.load");
        }
    }
}
