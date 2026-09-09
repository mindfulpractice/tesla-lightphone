#!/bin/bash
# Usage: ./tesla_auth.sh 'tesla-auth:CODE|VERIFIER'
# Exchanges auth code on your Mac (instant), pushes refresh token to emulator.

ADB=~/Library/Android/sdk/platform-tools/adb
INPUT="$1"

# Parse code and verifier
PAYLOAD="${INPUT#tesla-auth:}"
CODE="${PAYLOAD%%|*}"
VERIFIER="${PAYLOAD#*|}"

echo "Exchanging auth code..."

# Read credentials from local.properties
PROPS_FILE="$(dirname "$0")/local.properties"
if [ ! -f "$PROPS_FILE" ]; then
  echo "Error: local.properties not found. Add TESLA_CLIENT_ID, TESLA_CLIENT_SECRET, and TESLA_REDIRECT_URI."
  exit 1
fi
CLIENT_ID=$(grep '^TESLA_CLIENT_ID=' "$PROPS_FILE" | cut -d= -f2-)
CLIENT_SECRET=$(grep '^TESLA_CLIENT_SECRET=' "$PROPS_FILE" | cut -d= -f2-)
REDIRECT_URI=$(grep '^TESLA_REDIRECT_URI=' "$PROPS_FILE" | cut -d= -f2-)

# Exchange immediately via curl (happens in <1 second, no expiry risk)
RESPONSE=$(curl -s -X POST https://auth.tesla.com/oauth2/v3/token \
  --data-urlencode "grant_type=authorization_code" \
  --data-urlencode "client_id=$CLIENT_ID" \
  --data-urlencode "client_secret=$CLIENT_SECRET" \
  --data-urlencode "code=$CODE" \
  --data-urlencode "code_verifier=$VERIFIER" \
  --data-urlencode "redirect_uri=$REDIRECT_URI")

# Extract refresh token
REFRESH_TOKEN=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('refresh_token',''))" 2>/dev/null)

if [ -z "$REFRESH_TOKEN" ]; then
  echo "Exchange failed:"
  echo "$RESPONSE" | python3 -m json.tool 2>/dev/null || echo "$RESPONSE"
  exit 1
fi

echo "Got refresh token: ${REFRESH_TOKEN:0:20}..."

# Push refresh token to emulator (uses tesla-token: prefix = legacy flow, no expiry)
$ADB shell "run-as com.thelightphone.tool.tesla sh -c 'echo tesla-token:$REFRESH_TOKEN > /data/data/com.thelightphone.tool.tesla/files/tesla_auth.txt'"
echo "Done! Now tap Connect > Paste token instead."
