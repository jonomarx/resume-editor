#!/usr/bin/env bash
# Move an epic's DONE cards (and any still in TODO) to ARCHIVE. Usage: scripts/archive-epic.sh EPIC_NUMBER [--dry-run]
set -euo pipefail
cd "$(dirname "$0")"; . ./_board.sh
epic="${1:?usage: archive-epic.sh EPIC_NUMBER [--dry-run]}"; dry="${2:-}"
n=0
while IFS=$'\t' read -r item issue status; do
  [ -z "$item" ] && continue
  echo "#$issue: $status -> ARCHIVE"
  [ "$dry" = "--dry-run" ] || set_status "$item" "$OPT_ARCHIVE"
  n=$((n+1))
done < <(board_cards "$epic" TODO DONE)
echo "$n card(s) moved${dry:+ (dry run)}"
