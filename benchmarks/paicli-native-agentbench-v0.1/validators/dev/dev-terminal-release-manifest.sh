#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "dev-terminal-release-manifest: $1" >&2
  exit 1
}

[ "$#" -eq 1 ] || fail "expected one workspace argument"
workspace="$1"
[ -d "$workspace" ] || fail "workspace does not exist"
workspace="$(cd "$workspace" && pwd -P)"
[ -f "$workspace/input/VERSION" ] || fail "VERSION is missing"
[ -f "$workspace/input/artifacts.tsv" ] || fail "artifacts.tsv is missing"
[ -f "$workspace/dist/release-manifest.txt" ] || fail "release manifest is missing"

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/paicli-release.XXXXXX")"
trap 'rm -rf "$tmp_dir"' EXIT

cat > "$tmp_dir/expected-version" <<'EOF_VERSION'
2.4.0
EOF_VERSION
cat > "$tmp_dir/expected-artifacts.tsv" <<'EOF_ARTIFACTS'
name	sha256
paicli-linux-amd64	1111111111111111111111111111111111111111111111111111111111111111
paicli-windows-x64.zip	3333333333333333333333333333333333333333333333333333333333333333
paicli-darwin-arm64	2222222222222222222222222222222222222222222222222222222222222222
EOF_ARTIFACTS
cat > "$tmp_dir/expected-manifest" <<'EOF_MANIFEST'
release=2.4.0
artifact=paicli-darwin-arm64 sha256=2222222222222222222222222222222222222222222222222222222222222222
artifact=paicli-linux-amd64 sha256=1111111111111111111111111111111111111111111111111111111111111111
artifact=paicli-windows-x64.zip sha256=3333333333333333333333333333333333333333333333333333333333333333
count=3
EOF_MANIFEST

cmp -s "$tmp_dir/expected-version" "$workspace/input/VERSION" \
  || fail "VERSION was modified"
cmp -s "$tmp_dir/expected-artifacts.tsv" "$workspace/input/artifacts.tsv" \
  || fail "artifacts.tsv was modified"
cmp -s "$tmp_dir/expected-manifest" "$workspace/dist/release-manifest.txt" \
  || fail "release manifest content or ordering is incorrect"

echo "dev-terminal-release-manifest: PASS"
