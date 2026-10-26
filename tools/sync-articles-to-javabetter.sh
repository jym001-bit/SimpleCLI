#!/usr/bin/env bash
# 把 docs/articles 下的文章同步到 toBeBetterJavaer（javabetter.cn）的 PaiCLI 目录。
# paicli 仓库是唯一源；站点侧只保留副本，不要在那边直接改文章。
#
# 用法：
#   tools/sync-articles-to-javabetter.sh            # 同步全部文章
#   tools/sync-articles-to-javabetter.sh --dry-run  # 只列出会变化的文件
# 目标目录可用 JAVABETTER_PAICLI_DIR 覆盖。
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
source_dir="$repo_root/docs/articles"
target_dir="${JAVABETTER_PAICLI_DIR:-$HOME/Documents/GitHub/toBeBetterJavaer/docs/src/sidebar/itwanger/paicli}"

if [[ ! -d "$target_dir" ]]; then
  echo "目标目录不存在: $target_dir" >&2
  echo "可通过 JAVABETTER_PAICLI_DIR 指定 toBeBetterJavaer 中的 PaiCLI 文章目录" >&2
  exit 1
fi

dry_run=()
if [[ "${1:-}" == "--dry-run" ]]; then
  dry_run=(--dry-run)
fi

# README.md 是本仓库的索引页，不同步到站点
rsync -av --checksum ${dry_run[@]+"${dry_run[@]}"} --exclude 'README.md' --include '*.md' --exclude '*' \
  "$source_dir/" "$target_dir/"
