#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "dev-node-event-report: $1" >&2
  exit 1
}

[ "$#" -eq 1 ] || fail "expected one workspace argument"
workspace="$1"
[ -d "$workspace" ] || fail "workspace does not exist"
workspace="$(cd "$workspace" && pwd -P)"
[ -f "$workspace/events.js" ] || fail "events.js is missing"
[ -f "$workspace/data/events.ndjson" ] || fail "events.ndjson is missing"
[ -f "$workspace/output/report.json" ] || fail "formal output is missing"

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/paicli-events.XXXXXX")"
trap 'rm -rf "$tmp_dir"' EXIT
node "$workspace/events.js" "$workspace/data/events.ndjson" "$tmp_dir/generated.json" \
  || fail "events.js execution failed"

node - "$tmp_dir/generated.json" "$workspace/output/report.json" <<'NODE' \
  || fail "generated or formal event report is incorrect"
const fs = require('fs');
const expected = {
  totalActive: 5,
  byType: {
    click: { events: 3, uniqueUsers: 2 },
    login: { events: 2, uniqueUsers: 2 },
  },
};
for (const path of process.argv.slice(2)) {
  const actual = JSON.parse(fs.readFileSync(path, 'utf8'));
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`unexpected report in ${path}: ${JSON.stringify(actual)}`);
  }
}
NODE

echo "dev-node-event-report: PASS"
