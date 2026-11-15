package com.paicli.eval.headless;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HeadlessToolLogOptionsTest {

    @Test
    void acceptsAbsoluteToolLogPath() {
        Path path = Path.of(System.getProperty("java.io.tmpdir"))
                .resolve("paicli-tool-log-test.jsonl");
        HeadlessOptions options = HeadlessOptions.parse(new String[]{
                "--prompt", "x", "--tool-log", path.toString()
        });
        assertEquals(path, options.toolLog);
    }

    @Test
    void rejectsRelativeToolLogPath() {
        assertThrows(IllegalArgumentException.class, () -> HeadlessOptions.parse(new String[]{
                "--prompt", "x", "--tool-log", "relative-tool-log.jsonl"
        }));
    }
}
