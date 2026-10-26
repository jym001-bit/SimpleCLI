package com.paicli.memory;

import java.util.HashMap;
import java.util.Map;

/**
 * 长期记忆写入时的冲突检测器。
 *
 * <p>与 {@link MemoryDeduplicator} 分工：去重器只合并格式差异，数字、版本不同的事实会被保留；
 * 冲突检测器专门找出“同一去重域里高度相似、但不是重复”的条目——它们大概率是同一件事的
 * 新旧两个版本（例如“项目用 Java 17”和“项目用 Java 21”）。检测结果只用于阻止静默写入并把
 * 两条都交给用户决定，不会自动覆盖或删除任何一条。</p>
 */
final class MemoryConflictDetector {
    static final double DEFAULT_SIMILARITY_THRESHOLD = 0.8d;
    static final String THRESHOLD_PROPERTY = "paicli.memory.conflict.threshold";
    static final String THRESHOLD_ENV = "PAICLI_MEMORY_CONFLICT_THRESHOLD";

    /** 数字/版本号掩码占位符，用私有区字符避免和正文撞车。 */
    private static final String NUMBER_MASK = "\uE000";

    private final double similarityThreshold;

    MemoryConflictDetector(double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }

    static MemoryConflictDetector fromConfiguration() {
        return new MemoryConflictDetector(configuredThreshold());
    }

    boolean isConflict(MemoryEntry existing, MemoryEntry incoming) {
        if (!MemoryDeduplicator.sameDomain(existing, incoming)) {
            return false;
        }
        if (MemoryDeduplicator.isDuplicate(existing, incoming)) {
            return false;
        }
        String left = MemoryDeduplicator.canonicalize(existing.getContent());
        String right = MemoryDeduplicator.canonicalize(incoming.getContent());
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        return isNumericVariant(left, right) || bigramDice(left, right) >= similarityThreshold;
    }

    /** 只有数字或版本号不同，其余文字完全一致。 */
    static boolean isNumericVariant(String left, String right) {
        String maskedLeft = maskNumbers(left);
        String maskedRight = maskNumbers(right);
        return maskedLeft.contains(NUMBER_MASK) && maskedLeft.equals(maskedRight);
    }

    /** 字符二元组 Dice 系数：2|A∩B| / (|A|+|B|)，按多重集计数。 */
    static double bigramDice(String left, String right) {
        Map<String, Integer> leftGrams = bigrams(left);
        Map<String, Integer> rightGrams = bigrams(right);
        int leftTotal = leftGrams.values().stream().mapToInt(Integer::intValue).sum();
        int rightTotal = rightGrams.values().stream().mapToInt(Integer::intValue).sum();
        if (leftTotal == 0 || rightTotal == 0) {
            return 0d;
        }
        int shared = 0;
        for (Map.Entry<String, Integer> gram : leftGrams.entrySet()) {
            shared += Math.min(gram.getValue(), rightGrams.getOrDefault(gram.getKey(), 0));
        }
        return 2d * shared / (leftTotal + rightTotal);
    }

    private static String maskNumbers(String canonical) {
        return canonical.replaceAll("\\d+(?:\\.\\d+)*", NUMBER_MASK);
    }

    private static Map<String, Integer> bigrams(String text) {
        int[] codePoints = text.codePoints().toArray();
        Map<String, Integer> grams = new HashMap<>();
        for (int i = 0; i + 1 < codePoints.length; i++) {
            String gram = new String(codePoints, i, 2);
            grams.merge(gram, 1, Integer::sum);
        }
        return grams;
    }

    private static double configuredThreshold() {
        String raw = System.getProperty(THRESHOLD_PROPERTY);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(THRESHOLD_ENV);
        }
        if (raw == null || raw.isBlank()) {
            return DEFAULT_SIMILARITY_THRESHOLD;
        }
        try {
            double value = Double.parseDouble(raw.trim());
            return value > 0d && value <= 1d ? value : DEFAULT_SIMILARITY_THRESHOLD;
        } catch (NumberFormatException e) {
            return DEFAULT_SIMILARITY_THRESHOLD;
        }
    }
}
