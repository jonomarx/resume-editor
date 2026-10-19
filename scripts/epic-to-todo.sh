#!/usr/bin/env bash
# Move every BACKLOG card of an epic to TODO. Usage: scripts/epic-to-todo.sh EPIC_NUMBER [--dry-run]
set -euo pipefail
cd "$(dirname "$0")"; . ./_board.sh
epic="${1:?usage: epic-to-todo.sh EPIC_NUMBER [--dry-run]}"; dry="${2:-}"
n=0
while IFS=$'\t' read -r item issue status; do
  [ -z "$item" ] && continue
  echo "#$issue: $status -> TODO"
  [ "$dry" = "--dry-run" ] || set_status "$item" "$OPT_TODO"
  n=$((n+1))
done < <(board_cards "$epic" BACKLOG)
echo "$n card(s) moved${dry:+ (dry run)}"
