package com.paicli.eval.runner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatsTest {

    @Test
    void oddCountMedianIsMiddle() {
        Stats.Summary summary = Stats.of(List.of(5, 1, 3));
        assertEquals(3, summary.n());
        assertEquals(3.0, summary.median());
        assertEquals(1.0, summary.min());
        assertEquals(5.0, summary.max());
    }

    @Test
    void evenCountMedianAveragesMiddleTwo() {
        Stats.Summary summary = Stats.of(List.of(1, 2, 3, 4));
        assertEquals(2.5, summary.median());
        assertEquals(1.0, summary.min());
        assertEquals(4.0, summary.max());
    }

    @Test
    void emptyListIsNaN() {
        Stats.Summary summary = Stats.of(List.of());
        assertEquals(0, summary.n());
        assertEquals(Double.NaN, summary.median());
    }
}
