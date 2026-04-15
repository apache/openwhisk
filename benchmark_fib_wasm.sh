#!/bin/bash
set -e

NUM_INVOCATIONS=10
ACTION_NAME="fib_wasm"
COUCHDB_URL="http://whisk_admin:some_passw0rd@127.0.0.1:5984"
ACTIVATIONS_DB="whisk_local_activations"

echo "=== Invoking $ACTION_NAME $NUM_INVOCATIONS times ==="

ACTIVATION_IDS=()
for i in $(seq 1 $NUM_INVOCATIONS); do
  AID=$(wsk -i action invoke $ACTION_NAME | grep -oE '[a-f0-9]{32}')
  ACTIVATION_IDS+=("$AID")
  echo "  [$i/$NUM_INVOCATIONS] activation: $AID"
done

echo ""
echo "=== Waiting for activations to complete ==="
sleep 3

echo ""
echo "=== Fetching activation records from CouchDB ==="
echo ""

TOTAL_DURATION=0
COUNT=0

printf "%-34s %12s %s\n" "ACTIVATION ID" "DURATION(ms)" "STATUS"
printf "%-34s %12s %s\n" "---------------------------------" "------------" "------"

for AID in "${ACTIVATION_IDS[@]}"; do
  DOC_ID="guest/$AID"
  RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" -d "{\"selector\": {\"activationId\": \"$AID\"}}" "$COUCHDB_URL/$ACTIVATIONS_DB/_find")

  DURATION=$(echo "$RESPONSE" | jq '.docs[0].duration // empty')
  STATUS=$(echo "$RESPONSE" | jq -r '.docs[0].response.statusCode // "unknown"')

  if [ -n "$DURATION" ] && [ "$DURATION" != "null" ]; then
    printf "%-34s %12s %s\n" "$AID" "$DURATION" "$STATUS"
    TOTAL_DURATION=$((TOTAL_DURATION + DURATION))
    COUNT=$((COUNT + 1))
  else
    ERROR=$(echo "$RESPONSE" | jq -r '.docs[0].response.result.error // .error // "unknown error"')
    printf "%-34s %12s %s\n" "$AID" "N/A" "$ERROR"
  fi
done

echo ""
if [ $COUNT -gt 0 ]; then
  AVG=$(echo "scale=2; $TOTAL_DURATION / $COUNT" | bc)
  echo "=== Results ==="
  echo "  Successful activations: $COUNT / $NUM_INVOCATIONS"
  echo "  Total duration:         ${TOTAL_DURATION}ms"
  echo "  Average duration:       ${AVG}ms"
else
  echo "No successful activations found."
fi