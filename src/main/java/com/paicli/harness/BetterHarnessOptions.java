package com.paicli.harness;

import java.util.Locale;

/**
 * User-facing options for the native PaiCLI Better Harness review.
 */
public record BetterHarnessOptions(Depth depth, boolean inline) {

    public enum Depth {
        QUICK,
        NORMAL
    }

    public record ParseResult(BetterHarnessOptions options, String error) {
        public boolean valid() {
            return options != null && error == null;
        }
    }

    public static ParseResult parse(String payload) {
        Depth depth = Depth.NORMAL;
        boolean inline = false;
        if (payload == null || payload.isBlank()) {
            return new ParseResult(new BetterHarnessOptions(depth, false), null);
        }

        for (String token : payload.trim().split("\\s+")) {
            switch (token.toLowerCase(Locale.ROOT)) {
                case "quick", "--quick" -> depth = Depth.QUICK;
                case "normal", "--normal" -> depth = Depth.NORMAL;
                case "inline", "--inline", "--no-files" -> inline = true;
                default -> {
                    return new ParseResult(null,
                            "未知参数: " + token
                                    + "。用法: /better-harness [quick|normal] [--inline]");
                }
            }
        }
        return new ParseResult(new BetterHarnessOptions(depth, inline), null);
    }
}
