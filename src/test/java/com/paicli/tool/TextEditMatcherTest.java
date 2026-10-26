package com.paicli.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextEditMatcherTest {

    @Test
    void exactUniqueMatchIsReplaced() {
        TextEditMatcher.Result result = TextEditMatcher.apply(
                "a = 1;\nb = 2;\n", "b = 2", "b = 3", false, "f.txt");

        assertEquals("a = 1;\nb = 3;\n", result.content());
        assertFalse(result.fuzzy());
    }

    @Test
    void duplicateMatchReportsCountAndStartLines() {
        TextEditMatcher.EditException error = assertThrows(TextEditMatcher.EditException.class,
                () -> TextEditMatcher.apply("x\nsame\ny\nsame\n", "same", "z", false, "f.txt"));

        assertTrue(error.getMessage().contains("出现多次（2 处，起始行 2、4）"), error.getMessage());
        assertTrue(error.getMessage().contains("replace_all=true"), error.getMessage());
    }

    @Test
    void overlappingMatchesCountAsDuplicates() {
        assertThrows(TextEditMatcher.EditException.class,
                () -> TextEditMatcher.apply("aaa", "aa", "x", false, "f.txt"));
    }

    @Test
    void replaceAllReplacesEveryNonOverlappingExactMatch() {
        TextEditMatcher.Result result = TextEditMatcher.apply(
                "log(a);\nlog(b);\nlog(c);\n", "log(", "logger.info(", true, "f.txt");

        assertEquals("logger.info(a);\nlogger.info(b);\nlogger.info(c);\n", result.content());
        assertEquals(3, result.replacements());
    }

    @Test
    void replaceAllTreatsReplacementLiterally() {
        TextEditMatcher.Result result = TextEditMatcher.apply("a a", "a", "$1\\", true, "f.txt");

        assertEquals("$1\\ $1\\", result.content());
    }

    @Test
    void replaceAllWithoutMatchFails() {
        TextEditMatcher.EditException error = assertThrows(TextEditMatcher.EditException.class,
                () -> TextEditMatcher.apply("abc", "zzz", "y", true, "f.txt"));

        assertTrue(error.getMessage().startsWith("old_text 在文件中不存在"), error.getMessage());
    }

    @Test
    void lfSnippetMatchesCrlfFileAndKeepsCrlf() {
        TextEditMatcher.Result result = TextEditMatcher.apply(
                "class A {\r\n  int x = 1;\r\n  int y = 2;\r\n}\r\n",
                "  int x = 1;\n  int y = 2;", "  int x = 10;\n  int y = 20;", false, "A.java");

        assertEquals("class A {\r\n  int x = 10;\r\n  int y = 20;\r\n}\r\n", result.content());
    }

    @Test
    void smartQuotesAndDashesInFileStillMatchAsciiSnippet() {
        String content = "msg = “hello — world”;\nnext();\n";

        TextEditMatcher.Result result = TextEditMatcher.apply(
                content, "msg = \"hello - world\";", "msg = \"hi\";", false, "f.txt");

        assertEquals("msg = \"hi\";\nnext();\n", result.content());
        assertTrue(result.fuzzy());
    }

    @Test
    void trailingWhitespaceDifferencesStillMatchAndKeepUntouchedTrailingSpaces() {
        String content = "first();   \nsecond();\t\nthird();\n";

        TextEditMatcher.Result result = TextEditMatcher.apply(
                content, "first();\nsecond();", "one();\ntwo();", false, "f.txt");

        assertEquals("one();\ntwo();\t\nthird();\n", result.content());
    }

    @Test
    void fullWidthCharactersMatchThroughNfkc() {
        TextEditMatcher.Result result = TextEditMatcher.apply(
                "name = ＡＢＣ１２３;\n", "name = ABC123;", "name = ok;", false, "f.txt");

        assertEquals("name = ok;\n", result.content());
    }

    @Test
    void fuzzyMatchRefusesToSplitAnExpandedCharacter() {
        // ﬁ 归一化成 fi，片段只命中 i 时不能替换半个连字
        assertThrows(TextEditMatcher.EditException.class,
                () -> TextEditMatcher.apply("ﬁx\n", "ix", "y", false, "f.txt"));
    }

    @Test
    void fuzzyDuplicatesAreRejected() {
        TextEditMatcher.EditException error = assertThrows(TextEditMatcher.EditException.class,
                () -> TextEditMatcher.apply("say “hi”;\nsay ‘hi’;\nsay “hi”;\n", "say \"hi\";", "x", false, "f.txt"));

        assertTrue(error.getMessage().contains("起始行 1、3"), error.getMessage());
    }

    @Test
    void lineNumberPrefixesFromReadFileAreStrippedAfterAFailedMatch() {
        String content = "class A {\n  int x = 1;\n  int y = 2;\n}\n";

        TextEditMatcher.Result result = TextEditMatcher.apply(content,
                "    2 |   int x = 1;\n    3 |   int y = 2;",
                "    2 |   int x = 10;\n    3 |   int y = 20;", false, "A.java");

        assertEquals("class A {\n  int x = 10;\n  int y = 20;\n}\n", result.content());
        assertTrue(result.lineNumbersStripped());
    }

    @Test
    void newTextKeepsItsOwnContentWhenOnlyOldTextHasPrefixes() {
        TextEditMatcher.Result result = TextEditMatcher.apply("  int x = 1;\n",
                "    1 |   int x = 1;", "  int x = 2;", false, "A.java");

        assertEquals("  int x = 2;\n", result.content());
    }

    @Test
    void contentThatLooksLineNumberedStaysExactlyEditable() {
        TextEditMatcher.Result result = TextEditMatcher.apply("1 | keep\n2 | edit\n",
                "2 | edit", "2 | done", false, "table.txt");

        assertEquals("1 | keep\n2 | done\n", result.content());
        assertFalse(result.lineNumbersStripped());
    }

    @Test
    void failedRetryReportsTheOriginalError() {
        TextEditMatcher.EditException error = assertThrows(TextEditMatcher.EditException.class,
                () -> TextEditMatcher.apply("abc\n", "    1 | missing", "x", false, "f.txt"));

        assertTrue(error.getMessage().startsWith("old_text 在文件中不存在"), error.getMessage());
    }

    @Test
    void stripLineNumberPrefixesRequiresEveryNonEmptyLineToBePrefixed() {
        assertEquals("a\n\nb", TextEditMatcher.stripLineNumberPrefixes("   1 | a\n\n   3 | b"));
        assertEquals("x", TextEditMatcher.stripLineNumberPrefixes("     7→x"));
        assertNull(TextEditMatcher.stripLineNumberPrefixes("   1 | a\nplain"));
        assertNull(TextEditMatcher.stripLineNumberPrefixes("no prefix"));
    }

    @Test
    void emptyOldTextIsRejected() {
        assertThrows(TextEditMatcher.EditException.class,
                () -> TextEditMatcher.apply("abc", "", "x", false, "f.txt"));
    }
}
