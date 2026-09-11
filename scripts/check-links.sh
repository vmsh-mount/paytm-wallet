#!/usr/bin/env bash
# TASK-13 acceptance criterion: every link in docs/**, README.md, SUBMISSION.md
# resolves. Checks both external http(s) URLs (HEAD/GET -> 2xx/3xx) and
# relative file links (path exists on disk, anchors ignored).
set -uo pipefail
cd "$(dirname "$0")/.."

FAILURES=0
ok()   { printf '  \033[32mok\033[0m  %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

FILES=$(find . -name '*.md' -not -path './.git/*')

# Extract [text](target) links; skip mailto:, skip bare '#' anchors-only.
extract_links() { grep -oE '\]\(([^)]+)\)' "$1" | sed -E 's/^\]\(//; s/\)$//'; }

for f in $FILES; do
  dir=$(dirname "$f")
  while IFS= read -r link; do
    [ -z "$link" ] && continue
    case "$link" in
      mailto:*) continue ;;
      http://*|https://*)
        code=$(curl -s -o /dev/null -w '%{http_code}' -L --max-time 15 "$link" || echo 000)
        case "$code" in
          2??|3??) ok "$f -> $link ($code)" ;;
          *) fail "$f -> $link ($code)" ;;
        esac
        ;;
      \#*) continue ;; # in-page anchor, not checked
      ../../actions/*) continue ;; # GitHub-relative badge/workflow link, only resolves on github.com
      *)
        target="${link%%#*}"
        [ -z "$target" ] && continue
        path="$dir/$target"
        if [ -e "$path" ]; then
          ok "$f -> $link"
        else
          fail "$f -> $link (no such file: $path)"
        fi
        ;;
    esac
  done < <(extract_links "$f")
done

echo "== $FAILURES broken link(s) =="
exit "$FAILURES"
