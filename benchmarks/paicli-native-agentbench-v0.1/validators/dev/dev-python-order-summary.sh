#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "dev-python-order-summary: $1" >&2
  exit 1
}

[ "$#" -eq 1 ] || fail "expected one workspace argument"
workspace="$1"
[ -d "$workspace" ] || fail "workspace does not exist"
workspace="$(cd "$workspace" && pwd -P)"
[ -f "$workspace/orders.py" ] || fail "orders.py is missing"
[ -f "$workspace/data/orders.csv" ] || fail "orders.csv is missing"
[ -f "$workspace/output/summary.json" ] || fail "formal output is missing"

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/paicli-orders.XXXXXX")"
trap 'rm -rf "$tmp_dir"' EXIT
PYTHONDONTWRITEBYTECODE=1 python3 "$workspace/orders.py" \
  "$workspace/data/orders.csv" "$tmp_dir/generated.json" \
  || fail "orders.py execution failed"

python3 - "$tmp_dir/generated.json" "$workspace/output/summary.json" <<'PY' \
  || fail "generated or formal summary is incorrect"
import json
import sys

expected = {
    "totalSettled": 5,
    "currencies": {
        "CNY": {"paidOrders": 2, "refundedOrders": 1, "netAmount": "16.60"},
        "USD": {"paidOrders": 2, "refundedOrders": 0, "netAmount": "13.00"},
    },
}
for path in sys.argv[1:]:
    with open(path, encoding="utf-8") as source:
        actual = json.load(source)
    if actual != expected:
        raise SystemExit(f"unexpected summary in {path}: {actual!r}")
PY

echo "dev-python-order-summary: PASS"
