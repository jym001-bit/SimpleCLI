#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "dev-code-retry-location: $1" >&2
  exit 1
}

[ "$#" -eq 1 ] || fail "expected one workspace argument"
workspace="$1"
[ -d "$workspace" ] || fail "workspace does not exist"
workspace="$(cd "$workspace" && pwd -P)"
[ -f "$workspace/answer/location.json" ] || fail "answer/location.json is missing"

python3 - "$workspace" <<'PY' || fail "answer or read-only source state is incorrect"
import hashlib
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
expected_hashes = {
    "README.md": "32427c9e308698fd43b5d154de0ef172e3bbb10ccd04754c03e29dc9c04501f0",
    "docs/retry-notes.md": "76e04caf2165e84903fb31623348324f12b13eea726e5ce50578caf6b91d4623",
    "src/config/runtime.js": "c3027454e5cf3f30426743a8aca77851a6c5d0b32ec3249e36005f518ac6d6bd",
    "src/net/retry-window.js": "f2b5b4ed7261ee45d9820380e226dd5244ec21ef6a69a57482a19289cd7c5566",
    "src/net/retry-client.js": "53d0725d6e002052933008b137b1b230d3643f29ce3b20d782b02bf6fb1762c9",
}
for relative, expected in expected_hashes.items():
    actual = hashlib.sha256((root / relative).read_bytes()).hexdigest()
    if actual != expected:
        raise SystemExit(f"read-only source changed: {relative}")

answer = json.loads((root / "answer/location.json").read_text(encoding="utf-8"))
if set(answer) != {"path", "symbol", "explanation"}:
    raise SystemExit("answer must contain exactly path, symbol, explanation")
if answer["path"] != "src/net/retry-window.js":
    raise SystemExit("incorrect implementation path")
if answer["symbol"] != "clampRetryDelay":
    raise SystemExit("incorrect symbol")
explanation = answer["explanation"]
if not isinstance(explanation, str) or not explanation.strip():
    raise SystemExit("explanation is empty")
required_terms = ("Math.min", "maxWaitMs")
if not all(term in explanation for term in required_terms):
    raise SystemExit("explanation does not identify the limiting operation")
PY

echo "dev-code-retry-location: PASS"
