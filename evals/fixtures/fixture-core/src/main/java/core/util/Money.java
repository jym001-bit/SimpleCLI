package core.util;

public final class Money {

    private Money() {
    }

    /** 四舍五入到整数分。 */
    public static long round(double value) {
        return (long) value;
    }

    public static String format(long cents) {
        return String.format(java.util.Locale.ROOT, "%.2f", cents / 100.0);
    }
}
