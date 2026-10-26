package com.paicli.memory;

import java.util.Locale;

/**
 * {@code /save} 和 {@code /memory replace} 的参数解析，CLI 与 TUI 共用。
 */
public final class MemoryCommandArgs {

    /** {@code /save [--global|--project] [--force] <事实>} */
    public record SaveRequest(String fact, String scope, boolean force) {
    }

    /** {@code /memory replace <旧 id> <新事实>} */
    public record ReplaceRequest(String id, String fact) {
        public boolean valid() {
            return !id.isEmpty() && !fact.isEmpty();
        }
    }

    private MemoryCommandArgs() {
    }

    public static SaveRequest parseSave(String payload) {
        String rest = payload == null ? "" : payload.trim();
        String scope = "project";
        boolean force = false;
        while (rest.startsWith("--")) {
            int space = firstWhitespace(rest);
            String flag = (space < 0 ? rest : rest.substring(0, space)).toLowerCase(Locale.ROOT);
            if (!"--global".equals(flag) && !"--project".equals(flag) && !"--force".equals(flag)) {
                break;
            }
            if ("--global".equals(flag)) {
                scope = "global";
            } else if ("--project".equals(flag)) {
                scope = "project";
            } else {
                force = true;
            }
            rest = space < 0 ? "" : rest.substring(space).trim();
        }
        return new SaveRequest(rest, scope, force);
    }

    public static ReplaceRequest parseReplace(String payload) {
        String value = payload == null ? "" : payload.trim();
        int space = firstWhitespace(value);
        if (space < 0) {
            return new ReplaceRequest(value, "");
        }
        return new ReplaceRequest(value.substring(0, space), value.substring(space).trim());
    }

    private static int firstWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
