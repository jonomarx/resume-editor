# Shared by the board helper scripts (source it). Project 1 of owner jonomarx.
OWNER=jonomarx
PROJECT_NUMBER=1
PROJECT_ID=PVT_kwHOAH2Vxs4BnrIU
STATUS_FIELD=PVTSSF_lAHOAH2Vxs4BnrIUzhmOmt4
OPT_BACKLOG=a42ef0da
OPT_TODO=90bbf1c2
OPT_DONE=a3e7a4df
OPT_ARCHIVE=aa7e347d

# board_cards EPIC STATUS... -> lines "itemId<TAB>issueNumber<TAB>status" for cards labeled epic:EPIC in any of the statuses
board_cards() {
  local epic="$1"; shift
  local statuses; statuses=$(printf '%s,' "$@")
  gh project item-list "$PROJECT_NUMBER" --owner "$OWNER" --format json --limit 500 \
    | python3 -c '
import json, sys
epic, wanted = sys.argv[1], set(sys.argv[2].strip(",").split(","))
for i in json.load(sys.stdin)["items"]:
    if "epic:" + epic in (i.get("labels") or []) and i.get("status") in wanted:
        print("%s\t%s\t%s" % (i["id"], i["content"].get("number"), i["status"]))
' "$epic" "$statuses"
}

set_status() { # itemId optionId
  gh project item-edit --project-id "$PROJECT_ID" --id "$1" --field-id "$STATUS_FIELD" --single-select-option-id "$2" >/dev/null
}
