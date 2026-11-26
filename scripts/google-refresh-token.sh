#!/usr/bin/env bash
# Fetches a Google refresh token for kitly-cal (scopes: calendar.events, calendar.freebusy).
#
#   scripts/google-refresh-token.sh <client-id> <client-secret>
#
# Needs an OAuth client of type "Desktop app", python3 and curl. Sign in with the Google account
# whose calendar kitly-cal should use. The token is printed once; put it into
# KITLY_CAL_GOOGLE_REFRESH_TOKEN and never into the repository.
set -euo pipefail

CLIENT_ID="${1:?client id missing}"
CLIENT_SECRET="${2:?client secret missing}"
PORT="${PORT:-8765}"
REDIRECT="http://127.0.0.1:${PORT}"
SCOPES="https://www.googleapis.com/auth/calendar.events https://www.googleapis.com/auth/calendar.freebusy"

urlencode() { python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$1"; }

AUTH_URL="https://accounts.google.com/o/oauth2/v2/auth?response_type=code&access_type=offline&prompt=consent"
AUTH_URL+="&client_id=$(urlencode "$CLIENT_ID")&redirect_uri=$(urlencode "$REDIRECT")&scope=$(urlencode "$SCOPES")"

echo "Open this URL in the browser and sign in:"
echo
echo "  $AUTH_URL"
echo
command -v xdg-open >/dev/null && xdg-open "$AUTH_URL" >/dev/null 2>&1 || true
command -v open >/dev/null && open "$AUTH_URL" >/dev/null 2>&1 || true

# Wait for Google's redirect to the loopback address and take the code from it.
CODE="$(python3 - "$PORT" <<'PY'
import http.server, sys, urllib.parse
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        self.send_response(200); self.send_header("Content-Type", "text/plain; charset=utf-8"); self.end_headers()
        if "code" in query:
            self.wfile.write("kitly-cal: Anmeldung erhalten, Fenster kann geschlossen werden.".encode())
            print(query["code"][0]); sys.stdout.flush()
            return
        self.wfile.write(("Fehler: " + query.get("error", ["?"])[0]).encode())
    def log_message(self, *args): pass
http.server.HTTPServer(("127.0.0.1", int(sys.argv[1])), Handler).handle_request()
PY
)"
if [ -z "$CODE" ]; then
  echo "Google returned no authorization code (sign-in cancelled?)." >&2
  exit 1
fi

ANSWER="$(curl -sS https://oauth2.googleapis.com/token \
  --data-urlencode "grant_type=authorization_code" \
  --data-urlencode "code=${CODE}" \
  --data-urlencode "client_id=${CLIENT_ID}" \
  --data-urlencode "client_secret=${CLIENT_SECRET}" \
  --data-urlencode "redirect_uri=${REDIRECT}")"

TOKEN="$(printf '%s' "$ANSWER" | python3 -c 'import json, sys; print(json.load(sys.stdin).get("refresh_token", ""))')"
if [ -z "$TOKEN" ]; then
  echo "No refresh token in Google's answer:" >&2
  printf '%s\n' "$ANSWER" | python3 -c 'import json, sys; a = json.load(sys.stdin); a.pop("access_token", None); print(a)' >&2
  exit 1
fi
echo
echo "KITLY_CAL_GOOGLE_REFRESH_TOKEN=${TOKEN}"
