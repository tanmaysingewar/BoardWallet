#!/usr/bin/env bash
# Requires a running BoardWallet app, curl, and jq.
# Creates two users and one board; leaves them in the database for inspection.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
BASE_URL="${BASE_URL%/}"
for dependency in curl jq; do
    command -v "$dependency" >/dev/null || {
        echo "Missing dependency: $dependency" >&2
        exit 1
    }
done

WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT
RUN_ID=$(date +%s)${RANDOM}
CREATOR="c${RUN_ID}"
PLAYER="p${RUN_ID}"
PASSWORD="Bw-${RUN_ID}-Test!"
STARTING_AMOUNT=1500
RESPONSE=''
STATUS=''

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

# Pass bearer tokens through stdin rather than exposing them in curl arguments.
http() {
    local method="$1" path="$2" token="$3" payload="$4" output="$5"
    local -a args
    args=(--silent --show-error --connect-timeout 5 --max-time 45
          --request "$method" --output "$output" --write-out '%{http_code}'
          --header 'Content-Type: application/json')
    if [[ -n "$payload" ]]; then
        args+=(--data "$payload")
    fi
    if [[ -n "$token" ]]; then
        printf 'header = "Authorization: Bearer %s"\n' "$token" |
            curl --config - "${args[@]}" "$BASE_URL$path"
    else
        curl "${args[@]}" "$BASE_URL$path"
    fi
}

request() {
    local expected="$1" method="$2" path="$3" token="${4:-}" payload="${5:-}"
    STATUS=$(http "$method" "$path" "$token" "$payload" "$WORK_DIR/response.json") ||
        fail "Cannot reach $BASE_URL. Start the app and check BASE_URL."
    RESPONSE=$(cat "$WORK_DIR/response.json")
    [[ "$STATUS" == "$expected" ]] ||
        fail "$method $path: expected HTTP $expected, received $STATUS"
    echo "PASS: $method $path -> $STATUS"
}

assert_json() {
    local expression="$1" message="$2"
    printf '%s' "$RESPONSE" | jq -e "$expression" >/dev/null || fail "$message"
}

credentials() {
    jq -nc --arg username "$1" --arg password "$PASSWORD" \
        '{username: $username, password: $password}'
}

echo "Testing $BASE_URL with users $CREATOR and $PLAYER"
request 200 POST /api/auth/register '' "$(credentials "$CREATOR")"
request 200 POST /api/auth/register '' "$(credentials "$PLAYER")"

request 200 POST /api/auth/login '' "$(credentials "$CREATOR")"
CREATOR_TOKEN=$(printf '%s' "$RESPONSE" | jq -er '.accessToken | select(type == "string" and length > 0)')
request 200 POST /api/auth/login '' "$(credentials "$PLAYER")"
PLAYER_TOKEN=$(printf '%s' "$RESPONSE" | jq -er '.accessToken | select(type == "string" and length > 0)')

request 200 POST /api/boards "$CREATOR_TOKEN" \
    "$(jq -nc --arg name "Start flow $RUN_ID" --argjson amount "$STARTING_AMOUNT" \
        '{name: $name, startingAmount: $amount}')"
assert_json '.status == "WAITING"' 'New board must be WAITING'
BOARD_ID=$(printf '%s' "$RESPONSE" | jq -er '.id | select(type == "string" and length > 0)')
JOIN_CODE=$(printf '%s' "$RESPONSE" | jq -er '.joinCode | select(type == "string" and length > 0)')
BOARD_PATH="/api/boards/$BOARD_ID"

request 401 POST "$BOARD_PATH/start"
request 409 POST "$BOARD_PATH/start" "$CREATOR_TOKEN"
request 200 GET "$BOARD_PATH" "$CREATOR_TOKEN"
assert_json '.status == "WAITING" and (.players | length) == 1 and .players[0].balance == 0' \
    'Rejected solo start must leave the board WAITING and creator balance zero'

JOIN_PAYLOAD=$(jq -nc --arg code "$JOIN_CODE" '{joinCode: $code}')
request 200 POST /api/boards/join "$PLAYER_TOKEN" "$JOIN_PAYLOAD"
assert_json '.status == "WAITING" and .balance == 0' 'Joined player must have zero balance'
request 200 POST /api/boards/join "$PLAYER_TOKEN" "$JOIN_PAYLOAD"
request 403 POST "$BOARD_PATH/start" "$PLAYER_TOKEN"
request 200 GET "$BOARD_PATH" "$CREATOR_TOKEN"
assert_json '.status == "WAITING" and (.players | length) == 2 and all(.players[]; .balance == 0)' \
    'Joining twice must reuse membership; rejected player start must not change balances'

echo 'Sending two simultaneous creator start requests...'
http POST "$BOARD_PATH/start" "$CREATOR_TOKEN" '' "$WORK_DIR/start-a.json" > "$WORK_DIR/status-a" &
PID_A=$!
http POST "$BOARD_PATH/start" "$CREATOR_TOKEN" '' "$WORK_DIR/start-b.json" > "$WORK_DIR/status-b" &
PID_B=$!
WAIT_FAILED=0
wait "$PID_A" || WAIT_FAILED=1
wait "$PID_B" || WAIT_FAILED=1
[[ "$WAIT_FAILED" == 0 ]] || fail 'Concurrent start request failed to connect'
STATUS_A=$(cat "$WORK_DIR/status-a")
STATUS_B=$(cat "$WORK_DIR/status-b")
if ! { [[ "$STATUS_A" == 200 && "$STATUS_B" == 409 ]] ||
       [[ "$STATUS_A" == 409 && "$STATUS_B" == 200 ]]; }; then
    fail "Concurrent starts must return one 200 and one 409; received $STATUS_A and $STATUS_B"
fi
echo 'PASS: concurrent starts returned one 200 and one 409'

request 409 POST "$BOARD_PATH/start" "$CREATOR_TOKEN"
for token in "$CREATOR_TOKEN" "$PLAYER_TOKEN"; do
    request 200 GET "$BOARD_PATH" "$token"
    assert_json ".status == \"ACTIVE\" and .startingAmount == $STARTING_AMOUNT and
        (.players | length) == 2 and all(.players[]; .balance == $STARTING_AMOUNT)" \
        'Both players must see ACTIVE and exactly one starting-money credit each'
    printf '%s' "$RESPONSE" | jq -e --arg creator "$CREATOR" --arg player "$PLAYER" \
        '([.players[].username] | sort) == ([$creator, $player] | sort)' >/dev/null ||
        fail 'Board must contain the two registered players'
done

printf '\nAll API flow checks passed. Board: %s\n' "$BOARD_ID"
printf '%s\n' "$RESPONSE" | jq .
echo 'This checks API-visible balances; database history and forced rollback need separate checks.'
