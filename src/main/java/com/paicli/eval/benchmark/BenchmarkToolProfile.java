package com.paicli.eval.benchmark;

import java.util.Locale;
import java.util.Set;

/** Frozen tool surfaces used by every worker in one benchmark run. */
public enum BenchmarkToolProfile {
    REASONING_ONLY(Set.of(), false),
    MOCK_MCP(Set.of(), false),
    MOCK_MCP_FILE_ONLY(Set.of("read_file", "write_file", "list_dir", "glob_files", "grep_code", "create_project"), false),
    MOCK_WEB(Set.of("web_search", "web_fetch"), false),
    READ_ONLY(Set.of(
            "read_file",
            "list_dir",
            "glob_files",
            "grep_code"), false),
    CODE_RAG(Set.of(
            "read_file",
            "list_dir",
            "glob_files",
            "grep_code",
            "search_code"), false),
    FILE_ONLY(Set.of(
            "read_file",
            "write_file",
            "list_dir",
            "glob_files",
            "grep_code",
            "create_project"), false),
    LOCAL_COMMAND(Set.of(
            "read_file",
            "write_file",
            "list_dir",
            "glob_files",
            "grep_code",
            "execute_command",
            "create_project"), true);

    private final Set<String> allowedTools;
    private final boolean commandSandboxRequired;

    BenchmarkToolProfile(Set<String> allowedTools, boolean commandSandboxRequired) {
        this.allowedTools = Set.copyOf(allowedTools);
        this.commandSandboxRequired = commandSandboxRequired;
    }

    Set<String> allowedTools() {
        return allowedTools;
    }

    boolean commandSandboxRequired() {
        return commandSandboxRequired;
    }

    static BenchmarkToolProfile parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return FILE_ONLY;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    "tool profile must be REASONING_ONLY, READ_ONLY, CODE_RAG, FILE_ONLY, "
                            + "LOCAL_COMMAND, MOCK_MCP, MOCK_MCP_FILE_ONLY, or MOCK_WEB: " + raw);
        }
    }
}
