package dev.refactor;

import java.util.Locale;

public final class CustomerLabeler {

    public String label(String value) {
        return "customer:" + canonicalize(value);
    }

    private static String canonicalize(String value) {
        if (value == null) {
            return "";
        }
        return value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
