package com.paicli.eval.benchmark;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One immutable benchmark case loaded from a suite manifest.
 *
 * <p>Paths and verifier arguments remain data. A future worker must execute
 * {@link VerifierInvocation#arguments()} directly with {@code ProcessBuilder};
 * it must never concatenate the arguments into a shell command.
 */
public record CaseDefinition(
        @JsonProperty(value = "id", required = true) String id,
        @JsonProperty(value = "title", required = true) String title,
        @JsonProperty(value = "category", required = true) String category,
        @JsonProperty(value = "level", required = true) Level level,
        @JsonProperty(value = "weight", required = true) int weight,
        @JsonProperty(value = "mode", required = true) Mode mode,
        @JsonProperty(value = "fixturePath", required = true) String fixturePath,
        @JsonProperty(value = "prompt", required = true) String prompt,
        @JsonProperty(value = "verifierType", required = true) VerifierType verifierType,
        @JsonProperty(value = "verifierCommand", required = true) List<String> verifierCommand,
        @JsonProperty(value = "status", required = true) Status status
) {
    public static final String WORKSPACE_PLACEHOLDER = "{workspace}";
    public static final String EVIDENCE_PLACEHOLDER = "{evidence}";
    private static final Pattern SAFE_IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern WINDOWS_ABSOLUTE =
            Pattern.compile("^[A-Za-z]:[\\\\/].*");
    private static final Pattern PARENT_SEGMENT =
            Pattern.compile("(^|[=\\\\/])\\.\\.($|[\\\\/])");

    public CaseDefinition {
        verifierCommand = verifierCommand == null ? null : List.copyOf(verifierCommand);
    }

    void validate(Path suiteDirectory) {
        requireSafeIdentifier(id, "case id");
        requireText(title, "case title");
        requireText(category, "case category");
        if (level == null) {
            throw new IllegalArgumentException("case level must not be null: " + id);
        }
        if (mode == null) {
            throw new IllegalArgumentException("case mode must not be null: " + id);
        }
        if (status == null) {
            throw new IllegalArgumentException("case status must not be null: " + id);
        }
        if (status == Status.ACTIVE && (weight <= 0 || weight > 100)) {
            throw new IllegalArgumentException("active case weight must be between 1 and 100: " + id);
        }
        if (status != Status.ACTIVE && weight != 0) {
            throw new IllegalArgumentException("non-active case weight must be 0: " + id);
        }
        requireText(prompt, "case prompt");
        resolveFixture(suiteDirectory);
        validateVerifierCommand();
    }

    /** Resolves the fixture relative to the suite file directory and rejects escapes. */
    public Path resolveFixture(Path suiteDirectory) {
        return resolveRelativePath(suiteDirectory, fixturePath, "fixturePath");
    }

    /**
     * Returns a shell-free verifier invocation rooted at the suite directory.
     * Relative executable and argument paths therefore have one stable base.
     */
    public VerifierInvocation resolveVerifier(Path suiteDirectory) {
        Path workingDirectory = requireSuiteDirectory(suiteDirectory);
        validateVerifierCommand();
        return new VerifierInvocation(workingDirectory, verifierCommand);
    }

    private void validateVerifierCommand() {
        if (verifierType == null) {
            throw new IllegalArgumentException("verifierType must not be null: " + id);
        }
        if (verifierCommand == null) {
            throw new IllegalArgumentException("verifierCommand must not be null: " + id);
        }
        if (verifierType == VerifierType.NONE) {
            if (!verifierCommand.isEmpty()) {
                throw new IllegalArgumentException("verifierCommand must be empty when verifierType=none: " + id);
            }
            return;
        }
        if (verifierCommand.isEmpty()) {
            throw new IllegalArgumentException("verifierCommand must not be empty: " + id);
        }
        for (String argument : verifierCommand) {
            validateCommandArgument(argument);
        }
    }

    private static void validateCommandArgument(String argument) {
        if (argument == null || argument.isBlank()) {
            throw new IllegalArgumentException("verifierCommand arguments must not be blank");
        }
        if (argument.indexOf('\0') >= 0 || argument.indexOf('\n') >= 0 || argument.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("verifierCommand arguments must not contain control characters");
        }
        String value = argument.trim();
        requireWholeArgumentPlaceholder(argument, WORKSPACE_PLACEHOLDER);
        requireWholeArgumentPlaceholder(argument, EVIDENCE_PLACEHOLDER);
        if (value.startsWith("/") || value.startsWith("\\") || value.startsWith("~/")
                || WINDOWS_ABSOLUTE.matcher(value).matches()) {
            throw new IllegalArgumentException("verifierCommand must not contain absolute paths: " + argument);
        }
        if (PARENT_SEGMENT.matcher(value).find()) {
            throw new IllegalArgumentException("verifierCommand must not contain '..' path segments: " + argument);
        }
    }

    private static void requireWholeArgumentPlaceholder(String argument, String placeholder) {
        if (argument.contains(placeholder) && !argument.equals(placeholder)) {
            throw new IllegalArgumentException(
                    "verifierCommand placeholder must be one complete argv item: " + placeholder);
        }
    }

    static void requireSafeIdentifier(String value, String label) {
        if (value == null || !SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " must match " + SAFE_IDENTIFIER.pattern());
        }
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
    }

    private static Path resolveRelativePath(Path suiteDirectory, String raw, String label) {
        Path root = requireSuiteDirectory(suiteDirectory);
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }

        final Path relative;
        try {
            relative = Path.of(raw.trim());
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException(label + " is invalid: " + raw, e);
        }
        if (relative.isAbsolute() || WINDOWS_ABSOLUTE.matcher(raw.trim()).matches()) {
            throw new IllegalArgumentException(label + " must be relative to the suite directory: " + raw);
        }

        Path normalizedRelative = relative.normalize();
        if (normalizedRelative.toString().isBlank() || normalizedRelative.equals(Path.of("."))
                || normalizedRelative.startsWith("..")) {
            throw new IllegalArgumentException(label + " escapes or aliases the suite directory: " + raw);
        }
        Path resolved = root.resolve(normalizedRelative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException(label + " escapes the suite directory: " + raw);
        }

        // Lexical checks are insufficient when an existing fixture is a symlink.
        if (Files.exists(resolved)) {
            try {
                Path realRoot = root.toRealPath();
                Path realResolved = resolved.toRealPath();
                if (!realResolved.startsWith(realRoot)) {
                    throw new IllegalArgumentException(label + " resolves outside the suite directory: " + raw);
                }
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException("failed to resolve " + label + ": " + raw, e);
            }
        }
        return resolved;
    }

    private static Path requireSuiteDirectory(Path suiteDirectory) {
        if (suiteDirectory == null) {
            throw new IllegalArgumentException("suite directory must not be null");
        }
        return suiteDirectory.toAbsolutePath().normalize();
    }

    public record VerifierInvocation(Path workingDirectory, List<String> arguments) {
        public VerifierInvocation {
            workingDirectory = workingDirectory.toAbsolutePath().normalize();
            arguments = List.copyOf(arguments);
            for (String argument : arguments) {
                if (argument == null) {
                    throw new IllegalArgumentException("verifier argument must not be null");
                }
                requireWholeArgumentPlaceholder(argument, WORKSPACE_PLACEHOLDER);
                requireWholeArgumentPlaceholder(argument, EVIDENCE_PLACEHOLDER);
            }
        }

        /** Materializes only the runner-owned workspace token; no shell expansion is performed. */
        public VerifierInvocation forWorkspace(Path workspace) {
            if (workspace == null) {
                throw new IllegalArgumentException("workspace must not be null");
            }
            String safeWorkspace = workspace.toAbsolutePath().normalize().toString();
            List<String> materialized = arguments.stream()
                    .map(argument -> argument.equals(WORKSPACE_PLACEHOLDER) ? safeWorkspace : argument)
                    .toList();
            return new VerifierInvocation(workingDirectory, materialized);
        }

        /**
         * Materializes the two explicit runner-owned path tokens as whole argv items.
         * Evidence is mandatory only for commands that declare {@code {evidence}}.
         */
        public VerifierInvocation materialize(Path workspace, Path evidence) {
            if (workspace == null) {
                throw new IllegalArgumentException("workspace must not be null");
            }
            if (requiresEvidence() && evidence == null) {
                throw new IllegalArgumentException(
                        "verifier evidence is required by the {evidence} argv placeholder");
            }
            String safeWorkspace = workspace.toAbsolutePath().normalize().toString();
            String safeEvidence = evidence == null
                    ? null
                    : evidence.toAbsolutePath().normalize().toString();
            List<String> materialized = arguments.stream()
                    .map(argument -> {
                        if (argument.equals(WORKSPACE_PLACEHOLDER)) {
                            return safeWorkspace;
                        }
                        if (argument.equals(EVIDENCE_PLACEHOLDER)) {
                            return safeEvidence;
                        }
                        return argument;
                    })
                    .toList();
            return new VerifierInvocation(workingDirectory, materialized);
        }

        public boolean requiresEvidence() {
            return arguments.stream().anyMatch(EVIDENCE_PLACEHOLDER::equals);
        }
    }

    public enum Level {
        L1, L2, L3;

        @JsonCreator
        public static Level fromJson(String value) {
            return parseEnum(Level.class, value, "level");
        }

        @JsonValue
        public String toJson() {
            return name();
        }
    }

    public enum Mode {
        REACT, PLAN, TEAM;

        @JsonCreator
        public static Mode fromJson(String value) {
            return parseEnum(Mode.class, value, "mode");
        }

        @JsonValue
        public String toJson() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum VerifierType {
        NONE, COMMAND;

        @JsonCreator
        public static VerifierType fromJson(String value) {
            return parseEnum(VerifierType.class, value, "verifierType");
        }

        @JsonValue
        public String toJson() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Status {
        ACTIVE, DRAFT, DISABLED;

        @JsonCreator
        public static Status fromJson(String value) {
            return parseEnum(Status.class, value, "status");
        }

        @JsonValue
        public String toJson() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unsupported " + label + ": " + value, e);
        }
    }
}
