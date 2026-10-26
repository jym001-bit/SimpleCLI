package dev.range;

public record ClosedRange(int start, int end) {

    public ClosedRange {
        if (start > end) {
            throw new IllegalArgumentException("start must not exceed end");
        }
    }

    public boolean contains(int value) {
        return value > start && value < end;
    }

    public boolean overlaps(ClosedRange other) {
        if (other == null) {
            return false;
        }
        return start < other.end && other.start < end;
    }
}
