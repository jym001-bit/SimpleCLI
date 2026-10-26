package com.paicli.util;

import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TerminalTableTest {
    @Test
    void alignsChineseAndAnsiCellsByDisplayColumns() {
        String output = TerminalTable.render(List.of("名称", "输入", "状态"), List.of(
                List.of("\u001b[32m中文模型\u001b[0m", "文本·图片", "可用"),
                List.of("model", "文本", "待配置")), 80);
        var lines = output.lines().map(line -> AttributedString.fromAnsi(line).toString()).toList();
        String first = lines.get(2), second = lines.get(3);
        assertEquals(TerminalTable.width(first.substring(0, first.indexOf("文本"))),
                TerminalTable.width(second.substring(0, second.indexOf("文本"))));
        assertEquals(TerminalTable.width(first.substring(0, first.indexOf("可用"))),
                TerminalTable.width(second.substring(0, second.indexOf("待配置"))));
    }

    @ParameterizedTest
    @ValueSource(ints = {120, 60, 40, 20})
    void narrowLayoutsPreserveNamesAndFitEveryLine(int columns) {
        String name = "deepseek-v4-flash-vision-exp";
        String output = TerminalTable.render(List.of("模型", "上下文", "输入", "状态"), List.of(
                List.of(name, "1M", "文本·图片", "兼容旧名")), columns);
        for (String line : output.split("\n")) assertTrue(TerminalTable.width(line) <= columns, line);
        String plain = AttributedString.fromAnsi(output).toString();
        assertTrue(plain.replaceAll("\\s", "").contains(name));
        assertTrue(plain.contains("兼容旧名"));
        assertFalse(plain.contains("…"));
    }

    @Test
    void wrapsMultilineNotesWithoutLosingCharactersOrColours() {
        String input = "\u001b[32m中文abc模型\u001b[0m\n第二行说明";
        String output = TerminalTable.wrap(input, 8);
        assertEquals(AttributedString.fromAnsi(input).toString().replace("\n", ""),
                AttributedString.fromAnsi(output).toString().replace("\n", ""));
        for (String line : output.split("\n")) assertTrue(TerminalTable.width(line) <= 8, line);
    }
}
