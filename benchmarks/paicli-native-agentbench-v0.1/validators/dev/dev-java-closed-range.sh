#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "dev-java-closed-range: $1" >&2
  exit 1
}

[ "$#" -eq 1 ] || fail "expected one workspace argument"
workspace="$1"
[ -d "$workspace" ] || fail "workspace does not exist"
workspace="$(cd "$workspace" && pwd -P)"
source_file="$workspace/src/dev/range/ClosedRange.java"
[ -f "$source_file" ] || fail "ClosedRange.java is missing"

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/paicli-range.XXXXXX")"
trap 'rm -rf "$tmp_dir"' EXIT
mkdir -p "$tmp_dir/classes"

javac -encoding UTF-8 -d "$tmp_dir/classes" "$source_file" \
  || fail "project source does not compile"

cat > "$tmp_dir/ClosedRangeHiddenTest.java" <<'JAVA'
import dev.range.ClosedRange;

public final class ClosedRangeHiddenTest {
    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) {
        ClosedRange range = new ClosedRange(3, 7);
        check(range.contains(3), "closed range must contain the start endpoint");
        check(range.contains(7), "closed range must contain the end endpoint");
        check(range.contains(5), "closed range must contain an interior value");
        check(!range.contains(2), "value before the range must be excluded");
        check(!range.contains(8), "value after the range must be excluded");
        check(range.overlaps(new ClosedRange(7, 9)), "shared end/start endpoint must overlap");
        check(range.overlaps(new ClosedRange(1, 3)), "shared start/end endpoint must overlap");
        check(range.overlaps(new ClosedRange(4, 6)), "nested range must overlap");
        check(!range.overlaps(new ClosedRange(8, 10)), "separated ranges must not overlap");
        check(!range.overlaps(null), "null must not overlap");
    }
}
JAVA

javac -encoding UTF-8 -cp "$tmp_dir/classes" -d "$tmp_dir/classes" \
  "$tmp_dir/ClosedRangeHiddenTest.java" || fail "hidden test does not compile"
java -cp "$tmp_dir/classes" ClosedRangeHiddenTest \
  || fail "closed-range behavior is incorrect"

echo "dev-java-closed-range: PASS"
