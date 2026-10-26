package com.paicli.util;

import org.jline.utils.AttributedString;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Compact CLI tables. Names remain complete; narrow terminals use labelled records. */
public final class TerminalTable {
    private TerminalTable() {}

    public static int columns() {
        for (String value : new String[]{System.getProperty("paicli.render.columns"), System.getenv("COLUMNS")}) {
            try {
                if (value != null && !value.isBlank()) return Math.max(20, Integer.parseInt(value.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return 100;
    }

    public static int width(String text) {
        return AttributedString.fromAnsi(text == null ? "" : text).columnLength();
    }

    public static String wrap(String text, int columns) {
        AttributedString value = AttributedString.fromAnsi(text == null ? "" : text);
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int end = 0; end <= value.length(); end++) {
            if (end != value.length() && value.charAt(end) != '\n') continue;
            AttributedString remaining = value.subSequence(start, end);
            while (remaining.columnLength() > Math.max(2, columns)) {
                int split = remaining.columnSubSequence(0, Math.max(2, columns)).length();
                // 模型 ID 和命令中的 ASCII 单词能放进一行时，尽量整体移到下一行。
                if (split < remaining.length() && split > 0
                        && identifierChar(remaining.charAt(split)) && identifierChar(remaining.charAt(split - 1))) {
                    int wordStart = split;
                    while (wordStart > 0 && identifierChar(remaining.charAt(wordStart - 1))) wordStart--;
                    if (wordStart > 0) split = wordStart;
                }
                lines.add(styled(remaining.subSequence(0, split)));
                remaining = remaining.subSequence(split, remaining.length());
            }
            lines.add(styled(remaining));
            start = end + 1;
        }
        return String.join("\n", lines);
    }

    private static boolean identifierChar(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
                || "-_.:/@+".indexOf(c) >= 0;
    }

    private static String styled(AttributedString text) {
        return AnsiStyle.isEnabled() ? text.toAnsi() : text.toString();
    }

    public static String render(List<String> headers, List<List<String>> rows, int columns) {
        if (headers.isEmpty() || rows.isEmpty()) return "";
        rows = rows.stream().map(row -> row.stream().map(value -> value == null ? ""
                : value.replaceAll("\\R", " ").replace("\t", "  ")).toList()).toList();
        int available = Math.max(20, columns) - 2;
        int[] widths = headers.stream().mapToInt(TerminalTable::width).toArray();
        for (List<String> row : rows) {
            if (row.size() != headers.size()) throw new IllegalArgumentException("表格列数不一致");
            for (int i = 0; i < widths.length; i++) widths[i] = Math.max(widths[i], width(row.get(i)));
        }
        int visible = widths.length;
        // 最后一列（状态/摘要）先下移，主表仍放不下时再切为纵向条目。
        if (totalWidth(widths, visible) > available) visible--;
        if (visible < 2 || totalWidth(widths, visible) > available) return records(headers, rows, available);

        StringBuilder out = new StringBuilder();
        out.append("  ").append(AnsiStyle.subtle(line(headers, widths, visible))).append('\n');
        out.append("  ").append(AnsiStyle.subtle("─".repeat(totalWidth(widths, visible)))).append('\n');
        for (List<String> row : rows) {
            out.append("  ").append(line(row, widths, visible)).append('\n');
            for (int i = visible; i < headers.size(); i++) {
                appendWrapped(out, headers.get(i) + "：" + row.get(i), available);
            }
            if (visible < headers.size()) out.append('\n');
        }
        return out.toString().stripTrailing();
    }

    private static int totalWidth(int[] widths, int count) {
        return Arrays.stream(widths, 0, count).sum() + Math.max(0, count - 1) * 2;
    }

    private static String line(List<String> values, int[] widths, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            String value = values.get(i);
            out.append(value);
            if (i < count - 1) out.append(" ".repeat(widths[i] - width(value) + 2));
        }
        return out.toString();
    }

    private static String records(List<String> headers, List<List<String>> rows, int available) {
        StringBuilder out = new StringBuilder();
        for (List<String> row : rows) {
            appendWrapped(out, row.get(0), available);
            List<String> details = new ArrayList<>();
            for (int i = 1; i < headers.size(); i++) details.add(headers.get(i) + "：" + row.get(i));
            appendWrapped(out, String.join(" · ", details), available);
            out.append('\n');
        }
        return out.toString().stripTrailing();
    }

    private static void appendWrapped(StringBuilder out, String text, int available) {
        for (String line : wrap(text, available).split("\n", -1)) out.append("  ").append(line).append('\n');
    }
}
