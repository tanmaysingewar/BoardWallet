#!/usr/bin/env bash
# Requires a running BoardWallet app, curl, and jq.
# Creates two users and two boards, including real test payments.
# Leaves test data in the database for inspection.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
BASE_URL="${BASE_URL%/}"
for dependency in curl jq uuidgen; do
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
    local method="$1" path="$2" token="$3" payload="$4" output="$5" key="${6:-}"
    local -a args
    args=(--silent --show-error --connect-timeout 5 --max-time 45
          --request "$method" --output "$output" --write-out '%{http_code}'
          --header 'Content-Type: application/json')
    if [[ -n "$payload" ]]; then
        args+=(--data "$payload")
    fi
    if [[ -n "$key" ]]; then
        args+=(--header "Idempotency-Key: $key")
    fi
    if [[ -n "$token" ]]; then
        printf 'header = "Authorization: Bearer %s"\n' "$token" |
            curl --config - "${args[@]}" "$BASE_URL$path"
    else
        curl "${args[@]}" "$BASE_URL$path"
    fi
}

request() {
    local expected="$1" method="$2" path="$3" token="${4:-}" payload="${5:-}" key="${6:-}"
    STATUS=$(http "$method" "$path" "$token" "$payload" "$WORK_DIR/response.json" "$key") ||
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

# Resolve wallets from the member-only board details response.
CREATOR_WALLET=$(printf '%s' "$RESPONSE" | jq -er --arg name "$CREATOR" \
    '.players[] | select(.username == $name) | .walletId')
PLAYER_WALLET=$(printf '%s' "$RESPONSE" | jq -er --arg name "$PLAYER" \
    '.players[] | select(.username == $name) | .walletId')
PAYMENT_PATH="$BOARD_PATH/payments"

payment_payload() {
    jq -nc --arg recipient "$1" --argjson amount "$2" --arg reason "${3:-RENT}" \
        '{recipientWalletId: $recipient, amount: $amount, reason: $reason}'
}

assert_balances() {
    local creator_balance="$1" player_balance="$2"
    request 200 GET "$BOARD_PATH" "$CREATOR_TOKEN"
    printf '%s' "$RESPONSE" | jq -e \
        --arg creator "$CREATOR" --arg player "$PLAYER" \
        --argjson creatorBalance "$creator_balance" --argjson playerBalance "$player_balance" \
        '.status == "ACTIVE" and (.players | length) == 2 and
         ([.players[] | select(.username == $creator) | .balance] == [$creatorBalance]) and
         ([.players[] | select(.username == $player) | .balance] == [$playerBalance])' \
        >/dev/null || fail "Expected balances: creator=$creator_balance, player=$player_balance"
    echo "PASS: balances creator=$creator_balance, player=$player_balance"
}

assert_same_payment() {
    local original="$1"
    printf '%s' "$RESPONSE" | jq -e --argjson original "$original" \
        '. == $original' >/dev/null || fail 'Retry must return the original payment response'
}

# Launch both requests before waiting; responses remain in separate files.
parallel_payments() {
    local path="$1" token_a="$2" payload_a="$3" key_a="$4"
    local token_b="$5" payload_b="$6" key_b="$7"
    http POST "$path" "$token_a" "$payload_a" "$WORK_DIR/payment-a.json" "$key_a" \
        > "$WORK_DIR/payment-status-a" &
    local pid_a=$!
    http POST "$path" "$token_b" "$payload_b" "$WORK_DIR/payment-b.json" "$key_b" \
        > "$WORK_DIR/payment-status-b" &
    local pid_b=$!
    local wait_failed=0
    wait "$pid_a" || wait_failed=1
    wait "$pid_b" || wait_failed=1
    [[ "$wait_failed" == 0 ]] || fail 'Concurrent payment failed to connect'
    STATUS_A=$(cat "$WORK_DIR/payment-status-a")
    STATUS_B=$(cat "$WORK_DIR/payment-status-b")
}

echo 'Testing payment validation and authentication...'
PAYLOAD=$(payment_payload "$PLAYER_WALLET" 500)
request 401 POST "$PAYMENT_PATH" '' "$PAYLOAD" "$(uuidgen)"
request 400 POST "$PAYMENT_PATH" "$CREATOR_TOKEN" "$PAYLOAD"
request 400 POST "$PAYMENT_PATH" "$CREATOR_TOKEN" "$PAYLOAD" 'not-a-uuid'
for amount in 0 -1 1.001; do
    request 400 POST "$PAYMENT_PATH" "$CREATOR_TOKEN" \
        "$(payment_payload "$PLAYER_WALLET" "$amount")" "$(uuidgen)"
done
request 400 POST "$PAYMENT_PATH" "$CREATOR_TOKEN" \
    "$(payment_payload "$PLAYER_WALLET" 10 STARTING_MONEY)" "$(uuidgen)"
request 400 POST "$PAYMENT_PATH" "$CREATOR_TOKEN" \
    "$(payment_payload "$CREATOR_WALLET" 10)" "$(uuidgen)"
assert_balances 1500 1500

echo 'Testing successful payment and normalized-amount replay...'
PAYMENT_KEY=$(uuidgen)
request 200 POST "$PAYMENT_PATH" "$CREATOR_TOKEN" "$PAYLOAD" "$PAYMENT_KEY"
ORIGINAL_PAYMENT="$RESPONSE"
printf '%s' "$RESPONSE" | jq -e \
    --arg board "$BOARD_ID" --arg sender "$CREATOR_WALLET" --arg recipient "$PLAYER_WALLET" \
    '.boardId == $board and .fromWalletId == $sender and .toWalletId == $recipient and
     .amount == 500 and .reason == "RENT" and
     (.transactionId | type == "string" and length > 0) and
     (.createdAt | type == "string" and length > 0)' >/dev/null || fail 'Incorrect payment response'
request 200 POST "$PAYMENT_PATH" "$CREATOR_TOKEN" \
    "$(payment_payload "$PLAYER_WALLET" 500.00)" "$PAYMENT_KEY"
assert_same_payment "$ORIGINAL_PAYMENT"
request 409 POST "$PAYMENT_PATH" "$CREATOR_TOKEN" \
    "$(payment_payload "$PLAYER_WALLET" 501)" "$PAYMENT_KEY"
request 409 POST "$PAYMENT_PATH" "$CREATOR_TOKEN" \
    "$(payment_payload "$PLAYER_WALLET" 500 OTHER)" "$PAYMENT_KEY"
request 400 POST "$PAYMENT_PATH" "$CREATOR_TOKEN" \
    "$(payment_payload "$PLAYER_WALLET" 1001)" "$(uuidgen)"
assert_balances 1000 2000

echo 'Testing concurrent duplicate requests...'
CONCURRENT_KEY=$(uuidgen)
PAYLOAD=$(payment_payload "$PLAYER_WALLET" 100)
parallel_payments "$PAYMENT_PATH" "$CREATOR_TOKEN" "$PAYLOAD" "$CONCURRENT_KEY" \
    "$CREATOR_TOKEN" "$PAYLOAD" "$CONCURRENT_KEY"
[[ "$STATUS_A" == 200 && "$STATUS_B" == 200 ]] ||
    fail "Duplicate requests must both return 200; received $STATUS_A and $STATUS_B"
RESPONSE=$(cat "$WORK_DIR/payment-b.json")
assert_same_payment "$(cat "$WORK_DIR/payment-a.json")"
assert_balances 900 2100

echo 'Testing concurrent overspending with different keys...'
PAYLOAD=$(payment_payload "$PLAYER_WALLET" 700)
parallel_payments "$PAYMENT_PATH" "$CREATOR_TOKEN" "$PAYLOAD" "$(uuidgen)" \
    "$CREATOR_TOKEN" "$PAYLOAD" "$(uuidgen)"
if ! { [[ "$STATUS_A" == 200 && "$STATUS_B" == 400 ]] ||
       [[ "$STATUS_A" == 400 && "$STATUS_B" == 200 ]]; }; then
    fail "Overspending must return one 200 and one 400; received $STATUS_A and $STATUS_B"
fi
assert_balances 200 2800

echo 'Testing opposite-direction payments and per-user key scope...'
SHARED_KEY=$(uuidgen)
parallel_payments "$PAYMENT_PATH" "$CREATOR_TOKEN" "$(payment_payload "$PLAYER_WALLET" 50)" "$SHARED_KEY" \
    "$PLAYER_TOKEN" "$(payment_payload "$CREATOR_WALLET" 50)" "$SHARED_KEY"
[[ "$STATUS_A" == 200 && "$STATUS_B" == 200 ]] ||
    fail "Opposite-direction payments must succeed; received $STATUS_A and $STATUS_B"
TRANSACTION_A=$(jq -er '.transactionId' "$WORK_DIR/payment-a.json")
TRANSACTION_B=$(jq -er '.transactionId' "$WORK_DIR/payment-b.json")
[[ "$TRANSACTION_A" != "$TRANSACTION_B" ]] || fail 'Different users must have independent keys'
assert_balances 200 2800

echo 'Testing retry of a declined payment after receiving funds...'
FAILED_KEY=$(uuidgen)
PAYLOAD=$(payment_payload "$PLAYER_WALLET" 300)
request 400 POST "$PAYMENT_PATH" "$CREATOR_TOKEN" "$PAYLOAD" "$FAILED_KEY"
assert_balances 200 2800
request 200 POST "$PAYMENT_PATH" "$PLAYER_TOKEN" \
    "$(payment_payload "$CREATOR_WALLET" 200)" "$(uuidgen)"
request 200 POST "$PAYMENT_PATH" "$CREATOR_TOKEN" "$PAYLOAD" "$FAILED_KEY"
RETRIED_PAYMENT="$RESPONSE"
request 200 POST "$PAYMENT_PATH" "$CREATOR_TOKEN" "$PAYLOAD" "$FAILED_KEY"
assert_same_payment "$RETRIED_PAYMENT"
assert_balances 100 2900

echo 'Testing board membership, lifecycle, and cross-board recipients...'
request 200 POST /api/boards "$CREATOR_TOKEN" \
    "$(jq -nc --arg name "Payment boundaries $RUN_ID" --argjson amount "$STARTING_AMOUNT" \
        '{name: $name, startingAmount: $amount}')"
OTHER_BOARD_ID=$(printf '%s' "$RESPONSE" | jq -er '.id')
OTHER_JOIN_CODE=$(printf '%s' "$RESPONSE" | jq -er '.joinCode')
OTHER_PATH="/api/boards/$OTHER_BOARD_ID"
request 200 GET "$OTHER_PATH" "$CREATOR_TOKEN"
OTHER_CREATOR_WALLET=$(printf '%s' "$RESPONSE" | jq -er '.players[0].walletId')
request 403 POST "$OTHER_PATH/payments" "$PLAYER_TOKEN" \
    "$(payment_payload "$OTHER_CREATOR_WALLET" 10)" "$(uuidgen)"
request 404 POST "$PAYMENT_PATH" "$CREATOR_TOKEN" \
    "$(payment_payload "$OTHER_CREATOR_WALLET" 10)" "$(uuidgen)"
request 200 POST /api/boards/join "$PLAYER_TOKEN" \
    "$(jq -nc --arg code "$OTHER_JOIN_CODE" '{joinCode: $code}')"
OTHER_PLAYER_WALLET=$(printf '%s' "$RESPONSE" | jq -er '.walletId')
request 409 POST "$OTHER_PATH/payments" "$CREATOR_TOKEN" \
    "$(payment_payload "$OTHER_PLAYER_WALLET" 10)" "$(uuidgen)"
request 200 GET "$OTHER_PATH" "$CREATOR_TOKEN"
assert_json '.status == "WAITING" and (.players | length) == 2 and all(.players[]; .balance == 0)' \
    'Rejected payments must leave the waiting board and balances unchanged'
assert_balances 100 2900

printf '\nAll board-start and payment API checks passed. Board: %s\n' "$BOARD_ID"
printf '%s\n' "$RESPONSE" | jq .
echo "Additional waiting board: $OTHER_BOARD_ID"
echo 'This checks API responses and balances; database history and forced mid-payment rollback need separate checks.'
