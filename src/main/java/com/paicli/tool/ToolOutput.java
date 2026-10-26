package com.paicli.tool;

import com.paicli.llm.LlmClient;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Typed result returned by a tool implementation.
 *
 * <p>{@code discoveredUrls} is deliberately separate from the human-readable
 * text. Only tools that own a structured URL source (currently the built-in
 * {@code web_search} provider) may populate it; callers must never recover URL
 * authority by scraping {@link #text()}.</p>
 */
public record ToolOutput(String text,
                         List<LlmClient.ContentPart> imageParts,
                         boolean successful,
                         List<String> discoveredUrls) {
    public ToolOutput {
        text = text == null ? "" : text;
        imageParts = imageParts == null ? List.of() : List.copyOf(imageParts);
        discoveredUrls = discoveredUrls == null
                ? List.of()
                : discoveredUrls.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(url -> !url.isEmpty())
                .distinct()
                .toList();
    }

    /** Backward-compatible constructor for successful text/image tools. */
    public ToolOutput(String text, List<LlmClient.ContentPart> imageParts) {
        this(text, imageParts, true, List.of());
    }

    public static ToolOutput text(String text) {
        return new ToolOutput(text, List.of(), true, List.of());
    }

    public static ToolOutput failure(String text) {
        return new ToolOutput(text, List.of(), false, List.of());
    }

    public static ToolOutput failure(String text, List<LlmClient.ContentPart> imageParts) {
        return new ToolOutput(text, imageParts, false, List.of());
    }

    public static ToolOutput discovered(String text, Collection<String> discoveredUrls) {
        return new ToolOutput(text, List.of(), true,
                discoveredUrls == null ? List.of() : List.copyOf(discoveredUrls));
    }

    public boolean hasImageParts() {
        return !imageParts.isEmpty();
    }
}
