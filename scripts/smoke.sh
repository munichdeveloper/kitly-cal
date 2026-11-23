#!/usr/bin/env bash
# End-to-end check of a running kitly-cal service without making a real booking.
#
#   scripts/smoke.sh <base-url> <api-key> [--confirm] [--allow-live] [--event-type=1]
#
# With a sandbox key everything stays in the sandbox world. With a live key the script refuses to
# run unless --allow-live is given; then it reserves a real slot for a few seconds and always
# declines it again (it never confirms, so no webhook, mail or calendar entry can result).
#
# Needs curl and jq.
set -euo pipefail

BASE="${1:-}"; KEY="${2:-}"
if [ -z "$BASE" ] || [ -z "$KEY" ]; then
  echo "usage: scripts/smoke.sh <base-url> <api-key> [--confirm] [--allow-live] [--event-type=1]" >&2
  exit 2
fi
shift 2
BASE="${BASE%/}"; CONFIRM=0; ALLOW_LIVE=0; EVENT_TYPE=1
for arg in "$@"; do
  case "$arg" in
    --confirm) CONFIRM=1 ;;
    --allow-live) ALLOW_LIVE=1 ;;
    --event-type=*) EVENT_TYPE="${arg#--event-type=}" ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

STEP=0; UID_CREATED=""; DONE=0
STATUS=""; BODY=""

# call METHOD PATH [JSON]  ->  sets STATUS and BODY
call() {
  local out
  if [ $# -ge 3 ]; then
    out=$(curl -sS -X "$1" -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
      -H "cal-api-version: 2024-08-13" -d "$3" -w $'\n%{http_code}' "$BASE$2")
  else
    out=$(curl -sS -X "$1" -H "Authorization: Bearer $KEY" -H "cal-api-version: 2024-08-13" -w $'\n%{http_code}' "$BASE$2")
  fi
  STATUS="${out##*$'\n'}"; BODY="${out%$'\n'*}"
}

check() { # check CONDITION LABEL
  STEP=$((STEP + 1))
  if [ "$1" = "1" ]; then
    printf 'ok   %2d  %s\n' "$STEP" "$2"
  else
    printf 'FAIL %2d  %s\n         HTTP %s %s\n' "$STEP" "$2" "$STATUS" "$BODY"
    exit 1
  fi
}

is() { [ "$1" = "$2" ] && echo 1 || echo 0; }

cleanup() {
  # Never leave a reservation behind, whatever went wrong above.
  if [ -n "$UID_CREATED" ] && [ "$DONE" = "0" ]; then
    curl -sS -o /dev/null -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
      -d '{"reason":"smoke test cleanup"}' "$BASE/v2/bookings/$UID_CREATED/decline" || true
  fi
}
trap cleanup EXIT

slot_starts() { # prints the UTC start of every offered slot, one per line
  local from to
  from=$(date -u +%Y-%m-%d); to=$(date -u -d "+45 days" +%Y-%m-%d 2>/dev/null || date -u -v+45d +%Y-%m-%d)
  call GET "/v2/slots?eventTypeId=$EVENT_TYPE&start=$from&end=$to&timeZone=UTC"
  [ "$STATUS" = "200" ] || { check 0 "slots can be read"; }
  echo "$BODY" | jq -r '.data | to_entries[] | .value[] | .start | sub("\\.000\\+00:00$"; ".000Z")'
}

STATUS=$(curl -sS -o /dev/null -w '%{http_code}' "$BASE/health"); BODY=""
check "$(is "$STATUS" 200)" "service is healthy at $BASE"

call GET /internal/whoami
check "$(is "$STATUS" 200)" "API key is accepted"
SANDBOX=$(echo "$BODY" | jq -r '.data.sandbox')
if [ "$SANDBOX" != "true" ] && [ "$ALLOW_LIVE" = "0" ]; then
  echo "This is a LIVE key. Use a sandbox key, or pass --allow-live to reserve and release a real slot." >&2
  exit 1
fi
if [ "$SANDBOX" != "true" ] && [ "$CONFIRM" = "1" ]; then
  echo "--confirm is only allowed with a sandbox key." >&2
  exit 1
fi
[ "$SANDBOX" = "true" ] && echo "         sandbox key: nothing here touches real slots" || echo "         LIVE key: a real slot is held for a moment"

SLOTS=$(slot_starts)
COUNT=$(echo "$SLOTS" | grep -c . || true)
check "$([ "$COUNT" -gt 0 ] && echo 1 || echo 0)" "slots are offered ($COUNT in the next 45 days)"
START=$(echo "$SLOTS" | head -1)

BOOKING=$(jq -n --arg start "$START" --argjson eventTypeId "$EVENT_TYPE" \
  '{start: $start, eventTypeId: $eventTypeId, attendee: {name: "Smoke Test", email: "smoke-test@example.com", timeZone: "Europe/Berlin", language: "de"}, metadata: {source: "smoke-test"}}')
call POST /v2/bookings "$BOOKING"
check "$([ "$STATUS" = "201" ] && [ "$(echo "$BODY" | jq -r '.data.status')" = "pending" ] && echo 1 || echo 0)" "slot $START is reserved as pending"
UID_CREATED=$(echo "$BODY" | jq -r '.data.uid')

check "$(slot_starts | grep -qx "$START" && echo 0 || echo 1)" "the reserved slot is no longer offered"

call POST /v2/bookings "$BOOKING"
check "$(is "$STATUS" 400)" "a second booking of the same slot is refused"

call GET "/v2/bookings?status=unconfirmed&take=100&skip=0"
LISTED=$(echo "$BODY" | jq -r --arg uid "$UID_CREATED" '[.data[] | select(.uid == $uid and .createdAt != null and .metadata.source == "smoke-test")] | length')
check "$(is "$LISTED" 1)" "the reservation is listed as unconfirmed with createdAt and metadata"

if [ "$CONFIRM" = "1" ]; then
  call POST "/v2/bookings/$UID_CREATED/confirm" '{}'
  check "$([ "$STATUS" = "200" ] && [ "$(echo "$BODY" | jq -r '.data.status')" = "accepted" ] && echo 1 || echo 0)" "the booking is confirmed"
  call POST "/v2/bookings/$UID_CREATED/confirm" '{}'
  check "$(is "$STATUS" 200)" "a repeated confirm succeeds"
  call POST "/v2/bookings/$UID_CREATED/decline" '{"reason":"smoke test"}'
  check "$(is "$STATUS" 400)" "a confirmed booking cannot be declined"
  DONE=1
  echo "         the confirmed sandbox booking stays in the sandbox; its slot remains blocked there"
  echo; echo "All checks passed."
  exit 0
fi

call POST "/v2/bookings/$UID_CREATED/decline" '{"reason":"smoke test"}'
check "$([ "$STATUS" = "200" ] && [ "$(echo "$BODY" | jq -r '.data.status')" = "rejected" ] && echo 1 || echo 0)" "the reservation is declined"
DONE=1
check "$(slot_starts | grep -qx "$START" && echo 1 || echo 0)" "the slot is offered again"
call POST "/v2/bookings/$UID_CREATED/confirm" '{}'
check "$(is "$STATUS" 400)" "a declined booking cannot be confirmed"

echo; echo "All checks passed."
