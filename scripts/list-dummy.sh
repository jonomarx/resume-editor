#!/usr/bin/env bash
# List remaining DUMMY(US-###) markers as a markdown report. Usage: scripts/list-dummy.sh [ROOT]
# Skips build output, dependencies, docs, and the guides that merely describe the convention.
set -euo pipefail
root="${1:-.}"
cd "$root"
matches=$(grep -rnE 'DUMMY\(US-[0-9]+\)' . \
  --exclude-dir=.git --exclude-dir=node_modules --exclude-dir=target --exclude-dir=dist \
  --exclude-dir=docs --exclude-dir=scripts --exclude=AGENTS.md --exclude=CLAUDE.md 2>/dev/null \
  | sed 's|^\./||' | sort || true)
if [ -z "$matches" ]; then
  echo "No remaining DUMMY markers."
  exit 0
fi
count=$(printf '%s\n' "$matches" | wc -l | tr -d ' ')
echo "Remaining DUMMY markers: $count"
echo
echo "| Story | Location |"
echo "| --- | --- |"
printf '%s\n' "$matches" | while IFS= read -r line; do
  story=$(printf '%s' "$line" | grep -oE 'DUMMY\(US-[0-9]+\)' | head -1 | sed -E 's/DUMMY\((US-[0-9]+)\)/\1/')
  loc=$(printf '%s' "$line" | cut -d: -f1,2)
  echo "| $story | \`$loc\` |"
done
