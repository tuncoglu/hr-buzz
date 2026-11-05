#!/usr/bin/env bash
# Point a package's PRODUCTION track at an already-uploaded bundle (full rollout) and commit the edit.
# usage: play-production.sh PACKAGE VERSIONCODE VERSIONNAME ["release notes (en-GB)"]
# Without notes the existing release notes are kept. Play usually sends the change for review by itself;
# if it refuses that, the edit is committed unsent and you press "Send for review" in the Console.
set -euo pipefail
# shellcheck source=play-common.sh
. "$(dirname "$0")/play-common.sh"
pkg="${1:?package}"; code="${2:?versionCode}"; vname="${3:?versionName}"; notes="${4:-}"
api=$(play_api "$pkg")

eid=$(curl -sS -X POST "${auth[@]}" -H 'Content-Length: 0' "$api/edits" | jq -r '.id // empty')
[ -n "$eid" ] || { echo "could not open edit"; exit 1; }
committed=0
trap '[ "$committed" = 1 ] || curl -sS -o /dev/null -X DELETE "${auth[@]}" "$api/edits/$eid"' EXIT

cur=$(curl -sS "${auth[@]}" "$api/edits/$eid/tracks/production")
echo "before: $(jq -c '[.releases[]? | {name,status,versionCodes}]' <<<"$cur")"
body=$(jq -c --arg code "$code" --arg name "$code ($vname)" --arg notes "$notes" '
  {track:"production", releases:[{
     name:$name, status:"completed", versionCodes:[$code],
     releaseNotes: (if $notes != "" then [{language:"en-GB", text:$notes}] else ((.releases[0].releaseNotes) // []) end)
  }]}' <<<"$cur")
resp=$(curl -sS -X PUT "${auth[@]}" -H 'Content-Type: application/json' -d "$body" "$api/edits/$eid/tracks/production")
jq -e '.track' >/dev/null <<<"$resp" || { echo "production update FAILED: $(jq -c '.error // .' <<<"$resp")"; exit 1; }

done_resp=$(curl -sS -X POST "${auth[@]}" -H 'Content-Length: 0' "$api/edits/$eid:commit")
if jq -e '.id' >/dev/null <<<"$done_resp"; then
  committed=1; echo "committed: production -> $code ($vname); Play sends it for review itself"
elif jq -e '.error.message | test("changesNotSentForReview")' >/dev/null <<<"$done_resp"; then
  done_resp=$(curl -sS -X POST "${auth[@]}" -H 'Content-Length: 0' "$api/edits/$eid:commit?changesNotSentForReview=true")
  jq -e '.id' >/dev/null <<<"$done_resp" || { echo "commit FAILED: $(jq -c '.error // .' <<<"$done_resp")"; exit 1; }
  committed=1; echo "committed UNSENT: production -> $code ($vname); press 'Send for review' in the Play Console"
else
  echo "commit FAILED: $(jq -c '.error // .' <<<"$done_resp")"; exit 1
fi
