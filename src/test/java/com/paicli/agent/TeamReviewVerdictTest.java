package com.paicli.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamReviewVerdictTest {

    @Test
    void approvesOnlyOnJsonBooleanTrue() {
        assertTrue(TeamReviewVerdict.parse(
                "{\"approved\": true, \"summary\": \"通过\", \"issues\": []}").approved());
        assertTrue(TeamReviewVerdict.parse(
                "```json\n{\"approved\": true, \"summary\": \"通过\"}\n```").approved());
        assertFalse(TeamReviewVerdict.parse(
                "{\"approved\": false, \"summary\": \"未通过\", \"issues\": [\"缺少错误处理\"]}").approved());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "审查通过，代码质量良好",
            "没有通过审查",
            "hmm",
            "{\"summary\": \"无 approved 字段\"}",
            "{\"approved\": \"true\"}",
            "{\"approved\": 1}",
            "{\"approved\": null}",
            "[{\"approved\": true}]",
            "{\"approved\": false, \"approved\": true}",
            "{\"approved\": true} 但其实还有问题",
            "结论如下：\n```json\n{\"approved\": true}\n```",
            "```python\n{\"approved\": true}\n```",
            "```json\n{\"approved\": true}"
    })
    void anythingElseFailsClosed(String reply) {
        TeamReviewVerdict verdict = TeamReviewVerdict.parse(reply);

        assertFalse(verdict.approved(), () -> "should reject: " + reply);
        assertTrue(verdict.feedback().contains("按未通过处理"), verdict::feedback);
    }

    @Test
    void feedbackPrefersIssuesThenSuggestionsThenSummary() {
        assertEquals("- 缺少错误处理\n- 代码风格不一致", TeamReviewVerdict.parse("""
                {"approved": false, "summary": "存在问题",
                 "issues": ["缺少错误处理", "代码风格不一致"], "suggestions": ["添加 try-catch"]}
                """).feedback());
        assertEquals("- 添加 try-catch", TeamReviewVerdict.parse(
                "{\"approved\": false, \"issues\": [], \"suggestions\": [\"添加 try-catch\"]}").feedback());
        assertEquals("质量不达标", TeamReviewVerdict.parse(
                "{\"approved\": false, \"summary\": \"质量不达标\", \"issues\": []}").feedback());
        assertEquals(TeamReviewVerdict.DEFAULT_FEEDBACK,
                TeamReviewVerdict.parse("{\"approved\": false}").feedback());
    }

    @Test
    void fencesInsideStringValuesArePreserved() {
        TeamReviewVerdict verdict = TeamReviewVerdict.parse(
                "{\"approved\": false, \"issues\": [\"示例应写成 ```java 代码块\"]}");

        assertFalse(verdict.approved());
        assertEquals("- 示例应写成 ```java 代码块", verdict.feedback());
    }
}
