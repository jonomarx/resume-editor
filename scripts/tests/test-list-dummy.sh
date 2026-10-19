#!/usr/bin/env bash
# Unit test for list-dummy.sh on fixture trees.
set -u
cd "$(dirname "$0")/.."
fails=0
check() { if [ "$2" = "$3" ]; then echo "PASS: $1"; else echo "FAIL: $1"; echo "--- expected"; echo "$3"; echo "--- got"; echo "$2"; fails=$((fails+1)); fi; }

out=$(./list-dummy.sh tests/fixtures/tree)
expected='Remaining DUMMY markers: 2

| Story | Location |
| --- | --- |
| US-014 | `App.java:1` |
| US-006 | `sub/client.ts:1` |'
check "lists markers, skips node_modules and literal placeholders" "$out" "$expected"
check "reports none on a clean tree" "$(./list-dummy.sh tests/fixtures/empty)" "No remaining DUMMY markers."
exit $fails
