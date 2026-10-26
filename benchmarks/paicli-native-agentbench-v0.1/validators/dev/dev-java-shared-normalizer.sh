#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "dev-java-shared-normalizer: $1" >&2
  exit 1
}

[ "$#" -eq 1 ] || fail "expected one workspace argument"
workspace="$1"
[ -d "$workspace" ] || fail "workspace does not exist"
workspace="$(cd "$workspace" && pwd -P)"
source_root="$workspace/src/dev/refactor"
customer="$source_root/CustomerLabeler.java"
ticket="$source_root/TicketLabeler.java"
[ -f "$customer" ] || fail "CustomerLabeler.java is missing"
[ -f "$ticket" ] || fail "TicketLabeler.java is missing"

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/paicli-normalizer.XXXXXX")"
trap 'rm -rf "$tmp_dir"' EXIT
mkdir -p "$tmp_dir/classes"
find "$workspace/src" -type f -name '*.java' -print | sort > "$tmp_dir/sources.list"
[ "$(wc -l < "$tmp_dir/sources.list" | tr -d ' ')" -ge 3 ] \
  || fail "a shared Java component was not added"

if grep -q 'replaceAll' "$customer" || grep -q 'replaceAll' "$ticket"; then
  fail "normalization logic remains duplicated in a labeler"
fi
if grep -q 'private static String canonicalize' "$customer" \
    || grep -q 'private static String canonicalize' "$ticket"; then
  fail "private duplicate canonicalizer remains in a labeler"
fi

shared_reference_found=false
for candidate in "$source_root"/*.java; do
  [ "$candidate" = "$customer" ] && continue
  [ "$candidate" = "$ticket" ] && continue
  class_name="$(basename "$candidate" .java)"
  if grep -q "$class_name" "$customer" && grep -q "$class_name" "$ticket"; then
    shared_reference_found=true
    break
  fi
done
[ "$shared_reference_found" = true ] || fail "both labelers do not reference one shared component"

javac -encoding UTF-8 -d "$tmp_dir/classes" @"$tmp_dir/sources.list" \
  || fail "project sources do not compile"

cat > "$tmp_dir/SharedNormalizerHiddenTest.java" <<'JAVA'
import dev.refactor.CustomerLabeler;
import dev.refactor.TicketLabeler;

public final class SharedNormalizerHiddenTest {
    private static void equal(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }

    public static void main(String[] args) {
        CustomerLabeler customers = new CustomerLabeler();
        TicketLabeler tickets = new TicketLabeler();
        equal("customer:", customers.label(null));
        equal("ticket:", tickets.label(null));
        equal("customer:alice smith", customers.label("  ALICE\t SMITH  "));
        equal("ticket:alice smith", tickets.label("  ALICE\t SMITH  "));
        equal("customer:éclair", customers.label(" ÉCLAIR "));
        equal("ticket:éclair", tickets.label(" ÉCLAIR "));
    }
}
JAVA

javac -encoding UTF-8 -cp "$tmp_dir/classes" -d "$tmp_dir/classes" \
  "$tmp_dir/SharedNormalizerHiddenTest.java" || fail "hidden test does not compile"
java -cp "$tmp_dir/classes" SharedNormalizerHiddenTest \
  || fail "refactored behavior is incorrect"

echo "dev-java-shared-normalizer: PASS"
