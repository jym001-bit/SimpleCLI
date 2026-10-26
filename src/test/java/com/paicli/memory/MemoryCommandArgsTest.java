package com.paicli.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryCommandArgsTest {
    @Test
    void parsesSaveFlagsInAnyOrder() {
        MemoryCommandArgs.SaveRequest request = MemoryCommandArgs.parseSave("--force --global 默认用中文回答");

        assertEquals("默认用中文回答", request.fact());
        assertEquals("global", request.scope());
        assertTrue(request.force());
    }

    @Test
    void plainSaveDefaultsToProjectWithoutForce() {
        MemoryCommandArgs.SaveRequest request = MemoryCommandArgs.parseSave("项目使用 Java 17");

        assertEquals("项目使用 Java 17", request.fact());
        assertEquals("project", request.scope());
        assertFalse(request.force());
    }

    @Test
    void unknownFlagIsKeptAsFactText() {
        assertEquals("--verbose 输出", MemoryCommandArgs.parseSave("--verbose 输出").fact());
        assertEquals("", MemoryCommandArgs.parseSave("--global").fact());
    }

    @Test
    void parsesReplaceIdAndFact() {
        MemoryCommandArgs.ReplaceRequest request = MemoryCommandArgs.parseReplace("fact-1234 项目使用 Java 21");

        assertEquals("fact-1234", request.id());
        assertEquals("项目使用 Java 21", request.fact());
        assertTrue(request.valid());
        assertFalse(MemoryCommandArgs.parseReplace("fact-1234").valid());
    }
}
