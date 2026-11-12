package com.paicli.eval.runner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 中位数 / 最小值 / 最大值。 */
public final class Stats {

    public record Summary(int n, double median, double min, double max) {
    }

    private Stats() {
    }

    public static Summary of(List<? extends Number> values) {
        List<Double> numbers = new ArrayList<>();
        for (Number value : values) {
            if (value != null) {
                numbers.add(value.doubleValue());
            }
        }
        if (numbers.isEmpty()) {
            return new Summary(0, Double.NaN, Double.NaN, Double.NaN);
        }
        Collections.sort(numbers);
        int n = numbers.size();
        double median = n % 2 == 1
                ? numbers.get(n / 2)
                : (numbers.get(n / 2 - 1) + numbers.get(n / 2)) / 2.0;
        return new Summary(n, median, numbers.get(0), numbers.get(n - 1));
    }
}
