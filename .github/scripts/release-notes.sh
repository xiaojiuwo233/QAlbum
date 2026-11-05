#!/usr/bin/env bash
# 生成 GitHub Release 的更新说明（Markdown，输出到 stdout）。
# 用法：release-notes.sh [目标提交，默认 HEAD]
set -euo pipefail
shopt -s nocasematch

cd "$(git rev-parse --show-toplevel)"
target="${1:-HEAD}"

# 从目标的父提交找起，目标自己身上的 tag 不算「上一个版本」
if prev=$(git describe --tags --abbrev=0 --match '[0-9]*-[0-9]*' "$target^" 2>/dev/null); then
  range="$prev..$target"
else
  range="$target"
fi

skip_ci='\[(skip ci|ci skip|no ci|skip actions|actions skip)\]|^skip-checks: ?true'
subject_pattern='^(feat|fix)(\([^)]*\))?!?[[:space:]]*(:|：)[[:space:]]*(.*[^[:space:]])'

feats=""
fixes=""
commits=$(git rev-list --no-merges "$range")
for sha in $commits; do
  message=$(git show -s --format=%B "$sha")
  if grep -qiE "$skip_ci" <<<"$message"; then
    continue
  fi
  code_files=$(git diff-tree --no-commit-id --name-only -r --root "$sha" -- . ':(exclude)*.md' ':(exclude)docs/')
  [ -n "$code_files" ] || continue
  [[ ${message%%$'\n'*} =~ $subject_pattern ]] || continue

  item="- ${BASH_REMATCH[4]}"
  case "${BASH_REMATCH[1]}" in
    feat) grep -qxF -- "$item" <<<"$feats" || feats+="$item"$'\n' ;;
    fix) grep -qxF -- "$item" <<<"$fixes" || fixes+="$item"$'\n' ;;
  esac
done

printed=""
section() {
  [ -n "$2" ] || return 0
  [ -z "$printed" ] || echo
  printf '## %s\n%s' "$1" "$2"
  printed=1
}
section Feat "$feats"
section Bugfix "$fixes"
