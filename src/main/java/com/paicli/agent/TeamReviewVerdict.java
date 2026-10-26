package com.paicli.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

/**
 * Reviewer conclusion read from the reviewer's JSON reply.
 *
 * <p>Fail-closed: only a JSON object whose {@code approved} field is the JSON boolean {@code true}
 * approves a step. Empty output, prose, malformed JSON, a string {@code "true"} or a missing field
 * all mean "not approved". There is deliberately no keyword fallback such as matching "通过".
 */
record TeamReviewVerdict(boolean approved, String feedback) {

    static final String DEFAULT_FEEDBACK = "审查未通过，请改进执行结果";

    static TeamReviewVerdict parse(String reviewContent) {
        JsonNode root;
        try {
            root = TeamStructuredReply.parseObject(reviewContent);
        } catch (IOException e) {
            return rejected("审查结论无法解析（" + e.getMessage() + "），按未通过处理");
        }
        JsonNode approvedNode = root.get("approved");
        if (approvedNode == null || !approvedNode.isBoolean()) {
            return rejected("审查结论缺少布尔类型的 approved 字段，按未通过处理");
        }
        return new TeamReviewVerdict(approvedNode.booleanValue(), feedbackOf(root));
    }

    private static TeamReviewVerdict rejected(String feedback) {
        return new TeamReviewVerdict(false, feedback);
    }

    private static String feedbackOf(JsonNode root) {
        String issues = bulletList(root.get("issues"));
        if (!issues.isEmpty()) {
            return issues;
        }
        String suggestions = bulletList(root.get("suggestions"));
        if (!suggestions.isEmpty()) {
            return suggestions;
        }
        JsonNode summary = root.get("summary");
        if (summary != null && summary.isTextual() && !summary.asText().isBlank()) {
            return summary.asText();
        }
        return DEFAULT_FEEDBACK;
    }

    private static String bulletList(JsonNode items) {
        if (items == null || !items.isArray() || items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : items) {
            String text = item.isTextual() ? item.asText() : item.toString();
            sb.append("- ").append(text).append("\n");
        }
        return sb.toString().trim();
    }
}
