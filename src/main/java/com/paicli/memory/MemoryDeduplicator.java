package com.paicli.memory;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

/**
 * 长期记忆的确定性、高精度去重器。
 *
 * <p>它不是事实冲突消解器。只有处于相同 type/scope/project 去重域的条目才会比较内容；
 * 内容比较先消除 Unicode 宽窄、大小写、空白和普通标点差异，再保守地容忍少量中文语法助词。
 * 数字、代码符号或实质文字不同的事实会继续保留，交给用户审计和删除。</p>
 */
final class MemoryDeduplicator {
    /**
     * 语法助词近似匹配只用于足够长的内容，避免短句信息不足时误判。
     */
    private static final int MIN_APPROXIMATE_LENGTH = 8;

    /**
     * 较短文本至少覆盖较长文本的 82%，且额外字符只能是下列语法助词。
     */
    private static final double GRAMMATICAL_VARIANT_THRESHOLD = 0.82d;
    private static final String IGNORABLE_GRAMMATICAL_PARTICLES = "的地得是";

    /**
     * 这些符号可能是版本、路径、代码或数值的一部分，不能像普通句读一样丢弃。
     */
    private static final String SIGNIFICANT_PUNCTUATION = "#/@\\%_-'’!:&|^~";

    private MemoryDeduplicator() {
    }

    static boolean isDuplicate(MemoryEntry existing, MemoryEntry incoming) {
        if (!sameDomain(existing, incoming)) {
            return false;
        }
        return hasEquivalentContent(existing.getContent(), incoming.getContent());
    }

    static boolean sameDomain(MemoryEntry left, MemoryEntry right) {
        if (left.getType() != right.getType()) {
            return false;
        }

        String leftScope = LongTermMemory.scopeOf(left);
        String rightScope = LongTermMemory.scopeOf(right);
        if (!leftScope.equals(rightScope)) {
            return false;
        }

        if ("project".equals(leftScope)) {
            String leftProject = left.getMetadata().get("project");
            String rightProject = right.getMetadata().get("project");
            return leftProject != null
                    && !leftProject.isBlank()
                    && Objects.equals(leftProject, rightProject);
        }
        return true;
    }

    private static boolean hasEquivalentContent(String left, String right) {
        String first = canonicalize(left);
        String second = canonicalize(right);

        if (first.equals(second)) {
            return true;
        }
        if (first.isEmpty() || second.isEmpty()) {
            return false;
        }
        return isGrammaticalVariant(first, second);
    }

    static String canonicalize(String content) {
        String normalized = Normalizer.normalize(
                content == null ? "" : content,
                Normalizer.Form.NFKC
        ).toLowerCase(Locale.ROOT);

        int[] codePoints = normalized.codePoints().toArray();
        StringBuilder canonical = new StringBuilder();
        for (int index = 0; index < codePoints.length; index++) {
            int codePoint = codePoints[index];
            if (Character.isLetterOrDigit(codePoint)) {
                canonical.appendCodePoint(codePoint);
            } else if (isSignificantMarker(codePoints, index)) {
                // 保留原位置，避免 “C++ 和 Java” 与 “C 和 Java++” 产生相同签名。
                canonical.appendCodePoint(codePoint);
            }
        }
        return canonical.toString();
    }

    private static boolean isSignificantMarker(int[] codePoints, int index) {
        int codePoint = codePoints[index];
        int type = Character.getType(codePoint);
        if (type == Character.MATH_SYMBOL
                || type == Character.CURRENCY_SYMBOL
                || type == Character.MODIFIER_SYMBOL
                || type == Character.OTHER_SYMBOL) {
            // 包括 +、货币符号和 emoji；这些内容可能就是事实值。
            return true;
        }
        if (codePoint == '.') {
            // 版本号、域名和文件名中的点有意义，句末英文句号没有。
            return hasLetterOrDigitBefore(codePoints, index) && hasLetterOrDigitAfter(codePoints, index);
        }
        if ((codePoint == '\'' || codePoint == '’')
                && !(hasLetterOrDigitBefore(codePoints, index) && hasLetterOrDigitAfter(codePoints, index))) {
            // 普通引号属于格式差异；单词内部撇号才保留。
            return false;
        }
        return SIGNIFICANT_PUNCTUATION.indexOf(codePoint) >= 0;
    }

    private static boolean hasLetterOrDigitBefore(int[] codePoints, int index) {
        return index > 0 && Character.isLetterOrDigit(codePoints[index - 1]);
    }

    private static boolean hasLetterOrDigitAfter(int[] codePoints, int index) {
        return index + 1 < codePoints.length && Character.isLetterOrDigit(codePoints[index + 1]);
    }

    /**
     * 只允许一侧比另一侧多出“的/地/得/是”这类语法助词。
     * 这是有意采用的高精度边界：同义词改写不会被强行合并，更不会把相反或更新后的事实删除。
     */
    private static boolean isGrammaticalVariant(String left, String right) {
        int[] first = left.codePoints().toArray();
        int[] second = right.codePoints().toArray();
        int[] shorter = first.length <= second.length ? first : second;
        int[] longer = first.length <= second.length ? second : first;

        if (shorter.length < MIN_APPROXIMATE_LENGTH) {
            return false;
        }
        double containment = (double) shorter.length / longer.length;
        if (containment < GRAMMATICAL_VARIANT_THRESHOLD) {
            return false;
        }

        int shortIndex = 0;
        for (int codePoint : longer) {
            if (shortIndex < shorter.length && codePoint == shorter[shortIndex]) {
                shortIndex++;
                continue;
            }
            if (IGNORABLE_GRAMMATICAL_PARTICLES.indexOf(codePoint) < 0) {
                return false;
            }
        }
        return shortIndex == shorter.length;
    }
}
