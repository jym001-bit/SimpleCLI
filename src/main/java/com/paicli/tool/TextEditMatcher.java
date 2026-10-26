package com.paicli.tool;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * edit_file 的文本定位与替换，纯函数，不碰文件系统。
 *
 * <p>按顺序尝试三种方式，全部针对模型常见的抄写误差，参考 MiniMax Code 的 edit 实现：</p>
 * <ol>
 *   <li>精确匹配；文件是 CRLF 而片段只有 LF 时，按 CRLF 重新匹配并保留原换行</li>
 *   <li>逐行归一化后匹配：NFKC、去行尾空白、弯引号 / 各种横线 / 特殊空格统一成 ASCII，
 *       找到后映射回原文位置，只替换原文里对应的那一段</li>
 *   <li>片段每个非空行都带 read_file 的行号前缀（如 {@code "   12 | "}）时，剥掉前缀再试一次</li>
 * </ol>
 *
 * <p>唯一性按可重叠的方式判断：{@code "aa"} 在 {@code "aaa"} 里算两处，宁可拒绝也不去猜。
 * replace_all 只做精确匹配，按不重叠的方式逐个替换。</p>
 */
final class TextEditMatcher {

    private static final Pattern LINE_NUMBER_PREFIX = Pattern.compile("^\\s*\\d+(?: \\| ?|→|\\t)(.*)$");
    private static final int MAX_REPORTED_LINES = 3;

    record Result(String content, int replacements, boolean fuzzy, boolean lineNumbersStripped) {}

    static final class EditException extends IllegalArgumentException {
        EditException(String message) {
            super(message);
        }
    }

    private TextEditMatcher() {
    }

    static Result apply(String content, String oldText, String newText, boolean replaceAll, String path) {
        if (oldText == null || oldText.isEmpty()) {
            throw new EditException("old_text 必须非空");
        }
        try {
            return applyOnce(content, oldText, newText, replaceAll, path, false);
        } catch (EditException notFound) {
            if (!notFound.getMessage().startsWith("old_text 在文件中不存在")) {
                throw notFound;
            }
            String strippedOld = stripLineNumberPrefixes(oldText);
            if (strippedOld == null) {
                throw notFound;
            }
            String strippedNew = stripLineNumberPrefixes(newText);
            try {
                return applyOnce(content, strippedOld, strippedNew == null ? newText : strippedNew,
                        replaceAll, path, true);
            } catch (EditException retryFailed) {
                // 剥前缀只是兜底，重试失败时报告模型原始片段的错误，避免误导
                throw notFound;
            }
        }
    }

    private static Result applyOnce(String content, String oldText, String newText, boolean replaceAll,
                                    String path, boolean lineNumbersStripped) {
        String matchText = oldText;
        String replacement = newText;
        if (content.indexOf(matchText) < 0 && usesCrlf(content) && oldText.contains("\n")) {
            // 模型输出的片段通常只用 \n；CRLF 文件按 CRLF 重新匹配，替换文本也转成 CRLF，保持原换行风格
            matchText = toCrlf(oldText);
            replacement = toCrlf(newText);
        } else if (usesCrlf(content)) {
            replacement = toCrlf(newText);
        }
        if (replaceAll) {
            return replaceAllExact(content, matchText, replacement, path, lineNumbersStripped);
        }
        List<Integer> exact = overlappingOccurrences(content, matchText);
        if (exact.size() == 1) {
            int start = exact.get(0);
            return new Result(splice(content, start, start + matchText.length(), replacement),
                    1, false, lineNumbersStripped);
        }
        if (exact.size() > 1) {
            throw duplicate(content, exact, path);
        }
        return replaceFuzzy(content, oldText, replacement, path, lineNumbersStripped);
    }

    private static Result replaceAllExact(String content, String matchText, String replacement, String path,
                                          boolean lineNumbersStripped) {
        StringBuilder out = new StringBuilder(content.length());
        int count = 0;
        int from = 0;
        int index;
        while ((index = content.indexOf(matchText, from)) >= 0) {
            out.append(content, from, index).append(replacement);
            from = index + matchText.length();
            count++;
        }
        if (count == 0) {
            throw notFound(path);
        }
        out.append(content, from, content.length());
        return new Result(out.toString(), count, false, lineNumbersStripped);
    }

    private static Result replaceFuzzy(String content, String oldText, String replacement, String path,
                                       boolean lineNumbersStripped) {
        NormalizedText haystack = NormalizedText.of(content);
        String needle = NormalizedText.of(oldText).text();
        if (needle.isBlank()) {
            throw notFound(path);
        }
        List<Integer> matches = overlappingOccurrences(haystack.text(), needle);
        if (matches.isEmpty()) {
            throw notFound(path);
        }
        List<Integer> originalStarts = matches.stream().map(haystack::originalStart).toList();
        if (matches.size() > 1) {
            throw duplicate(content, originalStarts, path);
        }
        int start = originalStarts.get(0);
        int end = haystack.originalEnd(matches.get(0) + needle.length() - 1);
        // 归一化可能把一个字符展开成多个（如连字 ﬁ → fi），片段只命中其中一部分时拒绝，不做半个字符的替换
        if (!NormalizedText.of(content.substring(start, end)).text().equals(needle)) {
            throw notFound(path);
        }
        return new Result(splice(content, start, end, replacement), 1, true, lineNumbersStripped);
    }

    /** 每个非空行都带行号前缀时返回剥掉前缀后的文本，否则返回 null。 */
    static String stripLineNumberPrefixes(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        boolean sawPrefix = false;
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            if (lines[i].isEmpty()) {
                continue;
            }
            Matcher matcher = LINE_NUMBER_PREFIX.matcher(lines[i]);
            if (!matcher.matches()) {
                return null;
            }
            sawPrefix = true;
            out.append(matcher.group(1));
        }
        return sawPrefix ? out.toString() : null;
    }

    private static List<Integer> overlappingOccurrences(String text, String needle) {
        List<Integer> positions = new ArrayList<>();
        int index = text.indexOf(needle);
        while (index >= 0) {
            positions.add(index);
            index = text.indexOf(needle, index + 1);
        }
        return positions;
    }

    private static EditException notFound(String path) {
        return new EditException("old_text 在文件中不存在: " + path
                + "。片段必须和文件内容一致（含缩进）；如果片段来自记忆或较早的读取，先用 read_file 重新读取再试");
    }

    private static EditException duplicate(String content, List<Integer> starts, String path) {
        List<String> lines = starts.stream().limit(MAX_REPORTED_LINES)
                .map(start -> String.valueOf(lineNumberAt(content, start))).toList();
        String more = starts.size() > MAX_REPORTED_LINES ? " 等" : "";
        return new EditException("old_text 在文件中出现多次（" + starts.size() + " 处，起始行 "
                + String.join("、", lines) + more + "），请提供更长的上下文让它唯一，"
                + "确实要全部替换时设置 replace_all=true: " + path);
    }

    private static int lineNumberAt(String content, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String splice(String content, int start, int end, String replacement) {
        return content.substring(0, start) + replacement + content.substring(end);
    }

    private static boolean usesCrlf(String content) {
        return content.contains("\r\n");
    }

    private static String toCrlf(String text) {
        return text.replace("\r\n", "\n").replace("\n", "\r\n");
    }

    /**
     * 逐个码点归一化后的文本，并记录每个归一化字符来自原文的哪一段，用于把匹配位置映射回原文。
     * 换行保持一一对应，每行末尾的空白（含 CR）不进入归一化文本。
     */
    private record NormalizedText(String text, int[] starts, int[] ends) {

        static NormalizedText of(String original) {
            StringBuilder text = new StringBuilder(original.length());
            List<int[]> spans = new ArrayList<>(original.length());
            int lineStart = 0;
            while (lineStart <= original.length()) {
                int newline = original.indexOf('\n', lineStart);
                int lineEnd = newline < 0 ? original.length() : newline;
                int contentEnd = lineEnd;
                while (contentEnd > lineStart && Character.isWhitespace(original.charAt(contentEnd - 1))) {
                    contentEnd--;
                }
                int i = lineStart;
                while (i < contentEnd) {
                    int codePoint = original.codePointAt(i);
                    int next = i + Character.charCount(codePoint);
                    String piece = normalizeCodePoint(codePoint);
                    for (int k = 0; k < piece.length(); k++) {
                        text.append(piece.charAt(k));
                        spans.add(new int[]{i, next});
                    }
                    i = next;
                }
                if (newline < 0) {
                    break;
                }
                text.append('\n');
                spans.add(new int[]{newline, newline + 1});
                lineStart = newline + 1;
            }
            int[] starts = new int[spans.size()];
            int[] ends = new int[spans.size()];
            for (int k = 0; k < spans.size(); k++) {
                starts[k] = spans.get(k)[0];
                ends[k] = spans.get(k)[1];
            }
            return new NormalizedText(text.toString(), starts, ends);
        }

        int originalStart(int normalizedIndex) {
            return starts[normalizedIndex];
        }

        int originalEnd(int normalizedIndex) {
            return ends[normalizedIndex];
        }

        private static String normalizeCodePoint(int codePoint) {
            switch (codePoint) {
                case 0x2018, 0x2019, 0x201A, 0x201B:
                    return "'";
                case 0x201C, 0x201D, 0x201E, 0x201F:
                    return "\"";
                case 0x2010, 0x2011, 0x2012, 0x2013, 0x2014, 0x2015, 0x2212:
                    return "-";
                case 0x00A0, 0x202F, 0x205F, 0x3000:
                    return " ";
                default:
                    if (codePoint >= 0x2002 && codePoint <= 0x200A) {
                        return " ";
                    }
                    return Normalizer.normalize(new String(Character.toChars(codePoint)), Normalizer.Form.NFKC);
            }
        }
    }
}
