#!/usr/bin/env bash
# Requires a running BoardWallet app, curl, and jq.
# Covers registration, login, board creation/list/join/start, wallet and bank reads, transaction history,
# player payments, bank payments, bank payouts, and board finishing.
# Creates two users and two boards with test payments; finishes both boards.
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

assert_bank_totals() {
    local path="$1" token="$2" received="$3" paid_out="$4" wallet_id="${5:-}"
    request 200 GET "$path" "$token"
    printf '%s' "$RESPONSE" | jq -e \
        --arg walletId "$wallet_id" --argjson received "$received" --argjson paidOut "$paid_out" \
        '(.walletId | type == "string" and length > 0) and
         ($walletId == "" or .walletId == $walletId) and .unlimitedFunds == true and
         .totalReceived == $received and .totalPaidOut == $paidOut' >/dev/null ||
        fail "Expected $path to report unlimited funds, received=$received, paid out=$paid_out"
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

echo 'Testing board-list authentication and empty results...'
request 401 GET /api/boards
request 200 GET /api/boards "$PLAYER_TOKEN"
assert_json '. == []' 'A user with no boards must get an empty array'

request 200 POST /api/boards "$CREATOR_TOKEN" \
    "$(jq -nc --arg name "Start flow $RUN_ID" --argjson amount "$STARTING_AMOUNT" \
        '{name: $name, startingAmount: $amount}')"
assert_json '.status == "WAITING"' 'New board must be WAITING'
BOARD_ID=$(printf '%s' "$RESPONSE" | jq -er '.id | select(type == "string" and length > 0)')
JOIN_CODE=$(printf '%s' "$RESPONSE" | jq -er '.joinCode | select(type == "string" and length > 0)')
BOARD_PATH="/api/boards/$BOARD_ID"
BANK_PATH="$BOARD_PATH/bank"
HISTORY_PATH="$BOARD_PATH/transactions"

echo 'Testing transaction history access and empty results...'
request 401 GET "$HISTORY_PATH"
request 403 GET "$HISTORY_PATH" "$PLAYER_TOKEN"
request 404 GET "/api/boards/$(uuidgen)/transactions" "$CREATOR_TOKEN"
request 200 GET "$HISTORY_PATH" "$CREATOR_TOKEN"
assert_json '.content == [] and .page == {size: 20, number: 0, totalElements: 0, totalPages: 0}' \
    'A waiting board must return an empty history with default pagination'

echo 'Testing bank details on a waiting board...'
request 401 GET "$BANK_PATH"
request 403 GET "$BANK_PATH" "$PLAYER_TOKEN"
request 404 GET "/api/boards/$(uuidgen)/bank" "$CREATOR_TOKEN"
assert_bank_totals "$BANK_PATH" "$CREATOR_TOKEN" 0 0
BANK_WALLET=$(printf '%s' "$RESPONSE" | jq -er '.walletId')

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
CREATOR_ID=$(printf '%s' "$RESPONSE" | jq -er --arg name "$CREATOR" \
    '.players[] | select(.username == $name) | .userId')
PLAYER_WALLET=$(printf '%s' "$RESPONSE" | jq -er --arg name "$PLAYER" \
    '.players[] | select(.username == $name) | .walletId')
MY_WALLET_PATH="$BOARD_PATH/wallets/me"
PAYMENT_PATH="$BOARD_PATH/payments"

echo 'Testing that starting money counts toward bank payouts...'
STARTING_TOTAL=$((2 * STARTING_AMOUNT))
assert_bank_totals "$BANK_PATH" "$PLAYER_TOKEN" 0 "$STARTING_TOTAL" "$BANK_WALLET"

payment_payload() {
    jq -nc --arg recipient "$1" --argjson amount "$2" --arg reason "${3:-RENT}" \
        '{recipientWalletId: $recipient, amount: $amount, reason: $reason}'
}

bank_payment_payload() {
    jq -nc --argjson amount "$1" --arg reason "${2:-PENALTY}" \
        '{amount: $amount, reason: $reason}'
}

bank_payout_payload() {
    jq -nc --argjson amount "$1" --arg reason "${2:-REWARD}" \
        '{amount: $amount, reason: $reason}'
}

assert_my_wallet() {
    local token="$1" username="$2" wallet_id="$3" balance="$4"
    request 200 GET "$MY_WALLET_PATH" "$token"
    printf '%s' "$RESPONSE" | jq -e \
        --arg username "$username" --arg walletId "$wallet_id" --argjson balance "$balance" \
        '.username == $username and .walletId == $walletId and .balance == $balance' \
        >/dev/null || fail "Expected $username to see wallet $wallet_id with balance $balance"
}

assert_balances() {
    local creator_balance="$1" player_balance="$2" board_status="${3:-ACTIVE}"
    request 200 GET "$BOARD_PATH" "$CREATOR_TOKEN"
    printf '%s' "$RESPONSE" | jq -e \
        --arg creator "$CREATOR" --arg player "$PLAYER" \
        --arg status "$board_status" \
        --argjson creatorBalance "$creator_balance" --argjson playerBalance "$player_balance" \
        '.status == $status and (.players | length) == 2 and
         ([.players[] | select(.username == $creator) | .balance] == [$creatorBalance]) and
         ([.players[] | select(.username == $player) | .balance] == [$playerBalance])' \
        >/dev/null || fail "Expected $board_status board with balances: creator=$creator_balance, player=$player_balance"
    echo "PASS: balances creator=$creator_balance, player=$player_balance"
}

assert_same_payment() {
    local original="$1"
    printf '%s' "$RESPONSE" | jq -e --argjson original "$original" \
        '. == $original' >/dev/null || fail 'Retry must return the original payment response'
}

# Launch both requests before waiting; an optional eighth argument selects
# a different route for the second request (payment or payout).
parallel_payments() {
    local path="$1" token_a="$2" payload_a="$3" key_a="$4"
    local token_b="$5" payload_b="$6" key_b="$7"
    local path_b="${8:-$path}"
    http POST "$path" "$token_a" "$payload_a" "$WORK_DIR/payment-a.json" "$key_a" \
        > "$WORK_DIR/payment-status-a" &
    local pid_a=$!
    http POST "$path_b" "$token_b" "$payload_b" "$WORK_DIR/payment-b.json" "$key_b" \
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

echo 'Testing newest-first history, page boundaries, and wallet filtering...'
request 200 GET "$HISTORY_PATH?size=2" "$PLAYER_TOKEN"
printf '%s' "$RESPONSE" | jq -e --argjson payment "$ORIGINAL_PAYMENT" --arg initiator "$CREATOR_ID" \
    '.page == {size: 2, number: 0, totalElements: 3, totalPages: 2} and
     (.content | length) == 2 and
     (.content[0] | del(.initiatedByUserId, .createdAt)) == ($payment | del(.createdAt)) and
     .content[0].initiatedByUserId == $initiator and
     (.content[0].createdAt | type == "string" and length > 0) and
     .content[1].reason == "STARTING_MONEY"' >/dev/null ||
    fail 'First page must contain the player payment followed by a starting-money transfer'
FIRST_PAGE_IDS=$(printf '%s' "$RESPONSE" | jq -c '[.content[].transactionId]')

request 200 GET "$HISTORY_PATH?page=1&size=2" "$PLAYER_TOKEN"
printf '%s' "$RESPONSE" | jq -e --argjson firstPageIds "$FIRST_PAGE_IDS" \
    '.page == {size: 2, number: 1, totalElements: 3, totalPages: 2} and
     (.content | length) == 1 and .content[0].reason == "STARTING_MONEY" and
     (.content[0].transactionId as $id | ($firstPageIds | index($id)) == null)' >/dev/null ||
    fail 'Second page must contain the remaining starting-money transfer without overlap'
request 200 GET "$HISTORY_PATH?page=2&size=2" "$PLAYER_TOKEN"
assert_json '.content == [] and .page == {size: 2, number: 2, totalElements: 3, totalPages: 2}' \
    'A page past the end must be empty and preserve the total count'

request 200 GET "$HISTORY_PATH?walletId=$CREATOR_WALLET&size=1" "$PLAYER_TOKEN"
printf '%s' "$RESPONSE" | jq -e --arg wallet "$CREATOR_WALLET" \
    '.page == {size: 1, number: 0, totalElements: 2, totalPages: 2} and
     (.content | length) == 1 and .content[0].fromWalletId == $wallet and .content[0].reason == "RENT"' \
    >/dev/null || fail 'Wallet history must count both directions and show the newest outgoing payment'
request 200 GET "$HISTORY_PATH?walletId=$CREATOR_WALLET&page=1&size=1" "$PLAYER_TOKEN"
printf '%s' "$RESPONSE" | jq -e --arg wallet "$CREATOR_WALLET" \
    '(.content | length) == 1 and .content[0].toWalletId == $wallet and .content[0].reason == "STARTING_MONEY"' \
    >/dev/null || fail 'Wallet history must include the incoming starting-money transfer'

for query in 'page=-1' 'size=0' 'size=101' 'page=2147483647&size=2' 'walletId=not-a-uuid'; do
    request 400 GET "$HISTORY_PATH?$query" "$CREATOR_TOKEN"
done

echo 'Testing each member sees their own wallet and updated balance...'
request 401 GET "$MY_WALLET_PATH"
request 404 GET "/api/boards/$(uuidgen)/wallets/me" "$CREATOR_TOKEN"
assert_my_wallet "$CREATOR_TOKEN" "$CREATOR" "$CREATOR_WALLET" 1000
assert_my_wallet "$PLAYER_TOKEN" "$PLAYER" "$PLAYER_WALLET" 2000

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

echo 'Testing board summaries, newest-first order, and membership filtering...'
request 200 GET /api/boards "$CREATOR_TOKEN"
printf '%s' "$RESPONSE" | jq -e \
    --arg first "$BOARD_ID" --arg second "$OTHER_BOARD_ID" \
    --arg firstName "Start flow $RUN_ID" --arg secondName "Payment boundaries $RUN_ID" \
    'map({boardId, name, status}) == [
        {boardId: $second, name: $secondName, status: "WAITING"},
        {boardId: $first, name: $firstName, status: "ACTIVE"}
    ]' >/dev/null || fail 'Creator must see both board summaries, newest first'
request 200 GET /api/boards "$PLAYER_TOKEN"
printf '%s' "$RESPONSE" | jq -e --arg board "$BOARD_ID" \
    'map(.boardId) == [$board]' >/dev/null ||
    fail 'Player must see only the board they joined'

request 403 GET "$OTHER_PATH/wallets/me" "$PLAYER_TOKEN"
request 401 POST "$OTHER_PATH/finish"
request 403 POST "$OTHER_PATH/finish" "$PLAYER_TOKEN"
request 409 POST "$OTHER_PATH/finish" "$CREATOR_TOKEN"
request 404 POST "/api/boards/$(uuidgen)/finish" "$CREATOR_TOKEN"
request 200 GET "$OTHER_PATH" "$CREATOR_TOKEN"
assert_json '.status == "WAITING" and (.players | length) == 1 and .players[0].balance == 0' \
    'Rejected finish requests must leave the waiting board unchanged'
OTHER_CREATOR_WALLET=$(printf '%s' "$RESPONSE" | jq -er '.players[0].walletId')
request 200 GET "$HISTORY_PATH?walletId=$OTHER_CREATOR_WALLET" "$CREATOR_TOKEN"
assert_json '.content == [] and .page.totalElements == 0' \
    'Filtering by a wallet on another board must return no transactions'
request 403 POST "$OTHER_PATH/payments" "$PLAYER_TOKEN" \
    "$(payment_payload "$OTHER_CREATOR_WALLET" 10)" "$(uuidgen)"
request 403 POST "$OTHER_PATH/payments/bank" "$PLAYER_TOKEN" \
    "$(bank_payment_payload 10)" "$(uuidgen)"
request 403 POST "$OTHER_PATH/payments/bank/payout" "$PLAYER_TOKEN" \
    "$(bank_payout_payload 10)" "$(uuidgen)"
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

BANK_PAYMENT_PATH="$PAYMENT_PATH/bank"

echo 'Testing bank-payment authentication, headers, and request validation...'
BANK_PAYLOAD=$(bank_payment_payload 25)
request 401 POST "$BANK_PAYMENT_PATH" '' "$BANK_PAYLOAD" "$(uuidgen)"
request 400 POST "$BANK_PAYMENT_PATH" "$CREATOR_TOKEN" "$BANK_PAYLOAD"
request 400 POST "$BANK_PAYMENT_PATH" "$CREATOR_TOKEN" "$BANK_PAYLOAD" 'not-a-uuid'
for amount in 0 -1 1.001 100000000000000000; do
    request 400 POST "$BANK_PAYMENT_PATH" "$CREATOR_TOKEN" \
        "$(bank_payment_payload "$amount")" "$(uuidgen)"
done
for reason in STARTING_MONEY RENT REWARD PASS_START UNKNOWN; do
    request 400 POST "$BANK_PAYMENT_PATH" "$CREATOR_TOKEN" \
        "$(bank_payment_payload 10 "$reason")" "$(uuidgen)"
done
for payload in '{}' '{"amount":25}' '{"reason":"PENALTY"}' \
    '{"amount":null,"reason":"PENALTY"}' '{"amount":25,"reason":null}'; do
    request 400 POST "$BANK_PAYMENT_PATH" "$CREATOR_TOKEN" "$payload" "$(uuidgen)"
done
request 404 POST "/api/boards/$(uuidgen)/payments/bank" "$CREATOR_TOKEN" \
    "$BANK_PAYLOAD" "$(uuidgen)"
request 409 POST "$OTHER_PATH/payments/bank" "$CREATOR_TOKEN" \
    "$BANK_PAYLOAD" "$(uuidgen)"
request 200 GET "$OTHER_PATH" "$CREATOR_TOKEN"
assert_json '.status == "WAITING" and all(.players[]; .balance == 0)' \
    'Rejected bank payments must leave the waiting board unchanged'
assert_balances 100 2900

echo 'Testing successful bank payment and normalized-amount replay...'
BANK_PAYMENT_KEY=$(uuidgen)
request 200 POST "$BANK_PAYMENT_PATH" "$CREATOR_TOKEN" "$BANK_PAYLOAD" "$BANK_PAYMENT_KEY"
ORIGINAL_BANK_PAYMENT="$RESPONSE"
printf '%s' "$RESPONSE" | jq -e \
    --arg board "$BOARD_ID" --arg sender "$CREATOR_WALLET" --arg bank "$BANK_WALLET" \
    '.boardId == $board and .fromWalletId == $sender and
     .toWalletId == $bank and
     .amount == 25 and .reason == "PENALTY" and
     (.transactionId | type == "string" and length > 0) and
     (.createdAt | type == "string" and length > 0)' >/dev/null || fail 'Incorrect bank-payment response'
assert_balances 75 2900
request 200 POST "$BANK_PAYMENT_PATH" "$CREATOR_TOKEN" \
    '{"amount":25.00,"reason":"PENALTY"}' "$BANK_PAYMENT_KEY"
assert_same_payment "$ORIGINAL_BANK_PAYMENT"
assert_bank_totals "$BANK_PATH" "$PLAYER_TOKEN" 25 "$STARTING_TOTAL" "$BANK_WALLET"
request 409 POST "$BANK_PAYMENT_PATH" "$CREATOR_TOKEN" \
    "$(bank_payment_payload 26)" "$BANK_PAYMENT_KEY"
request 409 POST "$BANK_PAYMENT_PATH" "$CREATOR_TOKEN" \
    "$(bank_payment_payload 25 OTHER)" "$BANK_PAYMENT_KEY"
request 409 POST "$OTHER_PATH/payments/bank" "$CREATOR_TOKEN" \
    "$BANK_PAYLOAD" "$BANK_PAYMENT_KEY"

echo 'Testing that idempotency keys cannot be reused across payment routes...'
request 409 POST "$BANK_PAYMENT_PATH" "$CREATOR_TOKEN" "$BANK_PAYLOAD" "$PAYMENT_KEY"
request 409 POST "$PAYMENT_PATH" "$CREATOR_TOKEN" \
    "$(payment_payload "$PLAYER_WALLET" 25 OTHER)" "$BANK_PAYMENT_KEY"
assert_balances 75 2900

echo 'Testing that another player spends only their own funds with an independent key scope...'
request 200 POST "$BANK_PAYMENT_PATH" "$PLAYER_TOKEN" "$BANK_PAYLOAD" "$BANK_PAYMENT_KEY"
printf '%s' "$RESPONSE" | jq -e \
    --arg sender "$PLAYER_WALLET" --arg bank "$BANK_WALLET" \
    --arg transaction "$(printf '%s' "$ORIGINAL_BANK_PAYMENT" | jq -r '.transactionId')" \
    '.fromWalletId == $sender and .toWalletId == $bank and
     .transactionId != $transaction and .amount == 25 and .reason == "PENALTY"' \
    >/dev/null || fail 'Player bank payment must use their own wallet and a distinct transaction'
assert_balances 75 2875

echo 'Testing concurrent duplicate bank payments...'
BANK_DUPLICATE_KEY=$(uuidgen)
BANK_PAYLOAD=$(bank_payment_payload 10)
parallel_payments "$BANK_PAYMENT_PATH" "$CREATOR_TOKEN" "$BANK_PAYLOAD" "$BANK_DUPLICATE_KEY" \
    "$CREATOR_TOKEN" "$BANK_PAYLOAD" "$BANK_DUPLICATE_KEY"
[[ "$STATUS_A" == 200 && "$STATUS_B" == 200 ]] ||
    fail "Duplicate bank payments must both return 200; received $STATUS_A and $STATUS_B"
RESPONSE=$(cat "$WORK_DIR/payment-b.json")
assert_same_payment "$(cat "$WORK_DIR/payment-a.json")"
assert_balances 65 2875

echo 'Testing concurrent bank payments cannot overspend...'
BANK_PAYLOAD=$(bank_payment_payload 50)
parallel_payments "$BANK_PAYMENT_PATH" "$CREATOR_TOKEN" "$BANK_PAYLOAD" "$(uuidgen)" \
    "$CREATOR_TOKEN" "$BANK_PAYLOAD" "$(uuidgen)"
if ! { [[ "$STATUS_A" == 200 && "$STATUS_B" == 400 ]] ||
       [[ "$STATUS_A" == 400 && "$STATUS_B" == 200 ]]; }; then
    fail "Bank overspending must return one 200 and one 400; received $STATUS_A and $STATUS_B"
fi
assert_balances 15 2875

echo 'Testing a declined bank payment can succeed with the same key after receiving funds...'
FAILED_BANK_KEY=$(uuidgen)
BANK_PAYLOAD=$(bank_payment_payload 20 OTHER)
request 400 POST "$BANK_PAYMENT_PATH" "$CREATOR_TOKEN" "$BANK_PAYLOAD" "$FAILED_BANK_KEY"
assert_balances 15 2875
request 200 POST "$PAYMENT_PATH" "$PLAYER_TOKEN" \
    "$(payment_payload "$CREATOR_WALLET" 10)" "$(uuidgen)"
assert_balances 25 2865
request 200 POST "$BANK_PAYMENT_PATH" "$CREATOR_TOKEN" "$BANK_PAYLOAD" "$FAILED_BANK_KEY"
RETRIED_BANK_PAYMENT="$RESPONSE"
assert_json '.amount == 20 and .reason == "OTHER"' 'OTHER must be accepted for bank payments'
request 200 POST "$BANK_PAYMENT_PATH" "$CREATOR_TOKEN" "$BANK_PAYLOAD" "$FAILED_BANK_KEY"
assert_same_payment "$RETRIED_BANK_PAYMENT"
assert_balances 5 2865

echo 'Testing concurrent bank and player payments share the same spending protection...'
parallel_payments "$BANK_PAYMENT_PATH" "$CREATOR_TOKEN" "$(bank_payment_payload 4)" "$(uuidgen)" \
    "$CREATOR_TOKEN" "$(payment_payload "$PLAYER_WALLET" 4)" "$(uuidgen)" "$PAYMENT_PATH"
if [[ "$STATUS_A" == 200 && "$STATUS_B" == 400 ]]; then
    FINAL_PLAYER_BALANCE=2865
elif [[ "$STATUS_A" == 400 && "$STATUS_B" == 200 ]]; then
    FINAL_PLAYER_BALANCE=2869
else
    fail "Mixed-route overspending must return one 200 and one 400; received $STATUS_A and $STATUS_B"
fi
assert_balances 1 "$FINAL_PLAYER_BALANCE"

echo 'Testing concurrent reuse of one bank-payment key with different amounts...'
CONFLICTING_BANK_KEY=$(uuidgen)
parallel_payments "$BANK_PAYMENT_PATH" "$PLAYER_TOKEN" "$(bank_payment_payload 10)" "$CONFLICTING_BANK_KEY" \
    "$PLAYER_TOKEN" "$(bank_payment_payload 11)" "$CONFLICTING_BANK_KEY"
if [[ "$STATUS_A" == 200 && "$STATUS_B" == 409 ]]; then
    FINAL_PLAYER_BALANCE=$((FINAL_PLAYER_BALANCE - 10))
    BANK_CONFLICT_WINNER=$(cat "$WORK_DIR/payment-a.json")
    WINNING_BANK_PAYLOAD=$(bank_payment_payload 10)
elif [[ "$STATUS_A" == 409 && "$STATUS_B" == 200 ]]; then
    FINAL_PLAYER_BALANCE=$((FINAL_PLAYER_BALANCE - 11))
    BANK_CONFLICT_WINNER=$(cat "$WORK_DIR/payment-b.json")
    WINNING_BANK_PAYLOAD=$(bank_payment_payload 11)
else
    fail "Conflicting bank requests must return one 200 and one 409; received $STATUS_A and $STATUS_B"
fi
request 200 POST "$BANK_PAYMENT_PATH" "$PLAYER_TOKEN" "$WINNING_BANK_PAYLOAD" "$CONFLICTING_BANK_KEY"
assert_same_payment "$BANK_CONFLICT_WINNER"
assert_balances 1 "$FINAL_PLAYER_BALANCE"

echo 'Testing exact-balance spending, decimal amounts, and replay after funds are exhausted...'
request 200 POST "$BANK_PAYMENT_PATH" "$CREATOR_TOKEN" "$(bank_payment_payload 1)" "$(uuidgen)"
request 400 POST "$BANK_PAYMENT_PATH" "$CREATOR_TOKEN" "$(bank_payment_payload 0.01)" "$(uuidgen)"
request 200 POST "$BANK_PAYMENT_PATH" "$CREATOR_TOKEN" "$(bank_payment_payload 25)" "$BANK_PAYMENT_KEY"
assert_same_payment "$ORIGINAL_BANK_PAYMENT"
assert_balances 0 "$FINAL_PLAYER_BALANCE"
DECIMAL_BANK_KEY=$(uuidgen)
request 200 POST "$BANK_PAYMENT_PATH" "$PLAYER_TOKEN" "$(bank_payment_payload 1.25)" "$DECIMAL_BANK_KEY"
DECIMAL_BANK_PAYMENT="$RESPONSE"
assert_json '.amount == 1.25' 'Bank payments must preserve decimal amounts'
request 200 POST "$BANK_PAYMENT_PATH" "$PLAYER_TOKEN" "$(bank_payment_payload 1.25)" "$DECIMAL_BANK_KEY"
assert_same_payment "$DECIMAL_BANK_PAYMENT"
FINAL_PLAYER_BALANCE=$(jq -n --argjson balance "$FINAL_PLAYER_BALANCE" '$balance - 1.25')
assert_balances 0 "$FINAL_PLAYER_BALANCE"

BANK_PAYOUT_PATH="$BANK_PAYMENT_PATH/payout"

echo 'Testing bank-payout authentication, headers, and request validation...'
PAYOUT_PAYLOAD=$(bank_payout_payload 100)
request 401 POST "$BANK_PAYOUT_PATH" '' "$PAYOUT_PAYLOAD" "$(uuidgen)"
request 400 POST "$BANK_PAYOUT_PATH" "$CREATOR_TOKEN" "$PAYOUT_PAYLOAD"
request 400 POST "$BANK_PAYOUT_PATH" "$CREATOR_TOKEN" "$PAYOUT_PAYLOAD" 'not-a-uuid'
for amount in 0 -1 1.001 100000000000000000; do
    request 400 POST "$BANK_PAYOUT_PATH" "$CREATOR_TOKEN" \
        "$(bank_payout_payload "$amount")" "$(uuidgen)"
done
for reason in STARTING_MONEY RENT PENALTY UNKNOWN; do
    request 400 POST "$BANK_PAYOUT_PATH" "$CREATOR_TOKEN" \
        "$(bank_payout_payload 10 "$reason")" "$(uuidgen)"
done
for payload in '{}' '{"amount":100}' '{"reason":"REWARD"}' \
    '{"amount":null,"reason":"REWARD"}' '{"amount":100,"reason":null}'; do
    request 400 POST "$BANK_PAYOUT_PATH" "$CREATOR_TOKEN" "$payload" "$(uuidgen)"
done
request 404 POST "/api/boards/$(uuidgen)/payments/bank/payout" "$CREATOR_TOKEN" \
    "$PAYOUT_PAYLOAD" "$(uuidgen)"
WAITING_PAYOUT_KEY=$(uuidgen)
request 409 POST "$OTHER_PATH/payments/bank/payout" "$PLAYER_TOKEN" \
    "$PAYOUT_PAYLOAD" "$WAITING_PAYOUT_KEY"
request 200 GET "$OTHER_PATH" "$CREATOR_TOKEN"
assert_json '.status == "WAITING" and (.players | length) == 2 and all(.players[]; .balance == 0)' \
    'Rejected payouts must leave the waiting board and balances unchanged'
assert_balances 0 "$FINAL_PLAYER_BALANCE"

echo 'Testing a bank payout into an empty player wallet and normalized-amount replay...'
PAYOUT_KEY=$(uuidgen)
request 200 POST "$BANK_PAYOUT_PATH" "$CREATOR_TOKEN" "$PAYOUT_PAYLOAD" "$PAYOUT_KEY"
ORIGINAL_PAYOUT="$RESPONSE"
printf '%s' "$RESPONSE" | jq -e \
    --arg board "$BOARD_ID" --arg bank "$BANK_WALLET" --arg player "$CREATOR_WALLET" \
    '.boardId == $board and .fromWalletId == $bank and .toWalletId == $player and
     .amount == 100 and .reason == "REWARD" and
     (.transactionId | type == "string" and length > 0) and
     (.createdAt | type == "string" and length > 0)' >/dev/null || fail 'Incorrect bank-payout response'
request 200 POST "$BANK_PAYOUT_PATH" "$CREATOR_TOKEN" \
    '{"amount":100.00,"reason":"REWARD"}' "$PAYOUT_KEY"
assert_same_payment "$ORIGINAL_PAYOUT"
# Before this first payout, the creator had zero and all other player funds
# were in the second wallet. The difference from starting money went to the bank.
BANK_RECEIVED=$(jq -n --argjson start "$STARTING_TOTAL" \
    --argjson playerBalance "$FINAL_PLAYER_BALANCE" '$start - $playerBalance')
assert_bank_totals "$BANK_PATH" "$PLAYER_TOKEN" "$BANK_RECEIVED" \
    "$((STARTING_TOTAL + 100))" "$BANK_WALLET"
request 409 POST "$BANK_PAYOUT_PATH" "$CREATOR_TOKEN" \
    "$(bank_payout_payload 101)" "$PAYOUT_KEY"
request 409 POST "$BANK_PAYOUT_PATH" "$CREATOR_TOKEN" \
    "$(bank_payout_payload 100 PASS_START)" "$PAYOUT_KEY"
request 409 POST "$OTHER_PATH/payments/bank/payout" "$CREATOR_TOKEN" \
    "$PAYOUT_PAYLOAD" "$PAYOUT_KEY"
assert_balances 100 "$FINAL_PLAYER_BALANCE"

echo 'Testing a non-creator can claim for themselves with an independent key scope...'
request 200 POST "$BANK_PAYOUT_PATH" "$PLAYER_TOKEN" \
    "$(bank_payout_payload 100 PASS_START)" "$PAYOUT_KEY"
printf '%s' "$RESPONSE" | jq -e \
    --arg bank "$BANK_WALLET" --arg player "$PLAYER_WALLET" \
    --arg original "$(printf '%s' "$ORIGINAL_PAYOUT" | jq -r '.transactionId')" \
    '.fromWalletId == $bank and .toWalletId == $player and
     .transactionId != $original and .amount == 100 and .reason == "PASS_START"' \
    >/dev/null || fail 'A non-creator payout must credit their own wallet'
FINAL_PLAYER_BALANCE=$(jq -n --argjson balance "$FINAL_PLAYER_BALANCE" '$balance + 100')
assert_balances 100 "$FINAL_PLAYER_BALANCE"

echo 'Testing identical amounts and reasons cannot hide a change in payment direction...'
DIRECTION_KEY=$(uuidgen)
request 200 POST "$BANK_PAYOUT_PATH" "$CREATOR_TOKEN" \
    "$(bank_payout_payload 10 OTHER)" "$DIRECTION_KEY"
request 409 POST "$BANK_PAYMENT_PATH" "$CREATOR_TOKEN" \
    "$(bank_payment_payload 10 OTHER)" "$DIRECTION_KEY"
request 409 POST "$PAYMENT_PATH" "$CREATOR_TOKEN" \
    "$(payment_payload "$PLAYER_WALLET" 10 OTHER)" "$DIRECTION_KEY"
request 409 POST "$BANK_PAYOUT_PATH" "$CREATOR_TOKEN" \
    "$(bank_payout_payload 25 OTHER)" "$BANK_PAYMENT_KEY"
request 409 POST "$BANK_PAYOUT_PATH" "$CREATOR_TOKEN" \
    "$(bank_payout_payload 500 OTHER)" "$PAYMENT_KEY"
assert_balances 110 "$FINAL_PLAYER_BALANCE"

echo 'Testing concurrent duplicate bank payouts credit only once...'
PAYOUT_DUPLICATE_KEY=$(uuidgen)
PAYOUT_PAYLOAD=$(bank_payout_payload 20)
parallel_payments "$BANK_PAYOUT_PATH" "$CREATOR_TOKEN" "$PAYOUT_PAYLOAD" "$PAYOUT_DUPLICATE_KEY" \
    "$CREATOR_TOKEN" "$PAYOUT_PAYLOAD" "$PAYOUT_DUPLICATE_KEY"
[[ "$STATUS_A" == 200 && "$STATUS_B" == 200 ]] ||
    fail "Duplicate payouts must both return 200; received $STATUS_A and $STATUS_B"
RESPONSE=$(cat "$WORK_DIR/payment-b.json")
assert_same_payment "$(cat "$WORK_DIR/payment-a.json")"
assert_balances 130 "$FINAL_PLAYER_BALANCE"

echo 'Testing concurrent payouts with different keys preserve both credits...'
parallel_payments "$BANK_PAYOUT_PATH" "$CREATOR_TOKEN" "$(bank_payout_payload 30)" "$(uuidgen)" \
    "$CREATOR_TOKEN" "$(bank_payout_payload 40)" "$(uuidgen)"
[[ "$STATUS_A" == 200 && "$STATUS_B" == 200 ]] ||
    fail "Independent payouts must both succeed; received $STATUS_A and $STATUS_B"
[[ "$(jq -er '.transactionId' "$WORK_DIR/payment-a.json")" != \
   "$(jq -er '.transactionId' "$WORK_DIR/payment-b.json")" ]] ||
    fail 'Independent payouts must create distinct transactions'
assert_balances 200 "$FINAL_PLAYER_BALANCE"

echo 'Testing concurrent reuse of one payout key with different amounts...'
CONFLICTING_PAYOUT_KEY=$(uuidgen)
parallel_payments "$BANK_PAYOUT_PATH" "$CREATOR_TOKEN" "$(bank_payout_payload 10)" "$CONFLICTING_PAYOUT_KEY" \
    "$CREATOR_TOKEN" "$(bank_payout_payload 11)" "$CONFLICTING_PAYOUT_KEY"
if [[ "$STATUS_A" == 200 && "$STATUS_B" == 409 ]]; then
    CREATOR_BALANCE=210
    PAYOUT_CONFLICT_WINNER=$(cat "$WORK_DIR/payment-a.json")
    WINNING_PAYOUT_PAYLOAD=$(bank_payout_payload 10)
elif [[ "$STATUS_A" == 409 && "$STATUS_B" == 200 ]]; then
    CREATOR_BALANCE=211
    PAYOUT_CONFLICT_WINNER=$(cat "$WORK_DIR/payment-b.json")
    WINNING_PAYOUT_PAYLOAD=$(bank_payout_payload 11)
else
    fail "Conflicting payouts must return one 200 and one 409; received $STATUS_A and $STATUS_B"
fi
request 200 POST "$BANK_PAYOUT_PATH" "$CREATOR_TOKEN" "$WINNING_PAYOUT_PAYLOAD" "$CONFLICTING_PAYOUT_KEY"
assert_same_payment "$PAYOUT_CONFLICT_WINNER"
assert_balances "$CREATOR_BALANCE" "$FINAL_PLAYER_BALANCE"

echo 'Testing simultaneous payout and player spending preserve both balance changes...'
parallel_payments "$BANK_PAYOUT_PATH" "$CREATOR_TOKEN" "$(bank_payout_payload 50)" "$(uuidgen)" \
    "$CREATOR_TOKEN" "$(payment_payload "$PLAYER_WALLET" 20)" "$(uuidgen)" "$PAYMENT_PATH"
[[ "$STATUS_A" == 200 && "$STATUS_B" == 200 ]] ||
    fail "Payout and player payment must succeed; received $STATUS_A and $STATUS_B"
CREATOR_BALANCE=$((CREATOR_BALANCE + 30))
FINAL_PLAYER_BALANCE=$(jq -n --argjson balance "$FINAL_PLAYER_BALANCE" '$balance + 20')
assert_balances "$CREATOR_BALANCE" "$FINAL_PLAYER_BALANCE"

echo 'Testing simultaneous payout and bank spending preserve both balance changes...'
parallel_payments "$BANK_PAYOUT_PATH" "$CREATOR_TOKEN" "$(bank_payout_payload 50)" "$(uuidgen)" \
    "$CREATOR_TOKEN" "$(bank_payment_payload 20)" "$(uuidgen)" "$BANK_PAYMENT_PATH"
[[ "$STATUS_A" == 200 && "$STATUS_B" == 200 ]] ||
    fail "Payout and bank payment must succeed; received $STATUS_A and $STATUS_B"
CREATOR_BALANCE=$((CREATOR_BALANCE + 30))
assert_balances "$CREATOR_BALANCE" "$FINAL_PLAYER_BALANCE"

echo 'Testing decimal payouts and replay after subsequent balance changes...'
DECIMAL_PAYOUT_KEY=$(uuidgen)
request 200 POST "$BANK_PAYOUT_PATH" "$CREATOR_TOKEN" \
    "$(bank_payout_payload 1.25)" "$DECIMAL_PAYOUT_KEY"
DECIMAL_PAYOUT="$RESPONSE"
assert_json '.amount == 1.25' 'Payouts must preserve decimal amounts'
request 200 POST "$BANK_PAYOUT_PATH" "$CREATOR_TOKEN" \
    "$(bank_payout_payload 1.25)" "$DECIMAL_PAYOUT_KEY"
assert_same_payment "$DECIMAL_PAYOUT"
request 200 POST "$BANK_PAYOUT_PATH" "$CREATOR_TOKEN" \
    "$(bank_payout_payload 100)" "$PAYOUT_KEY"
assert_same_payment "$ORIGINAL_PAYOUT"
CREATOR_BALANCE=$(jq -n --argjson balance "$CREATOR_BALANCE" '$balance + 1.25')
assert_balances "$CREATOR_BALANCE" "$FINAL_PLAYER_BALANCE"

echo 'Testing a payout rejected while WAITING can succeed with the same key after start...'
request 200 POST "$OTHER_PATH/start" "$CREATOR_TOKEN"
request 200 POST "$OTHER_PATH/payments/bank/payout" "$PLAYER_TOKEN" \
    "$(bank_payout_payload 100)" "$WAITING_PAYOUT_KEY"
WAITING_RETRIED_PAYOUT="$RESPONSE"
assert_json '.amount == 100 and .reason == "REWARD"' 'Retry after board start must succeed'
request 200 POST "$OTHER_PATH/payments/bank/payout" "$PLAYER_TOKEN" \
    "$(bank_payout_payload 100)" "$WAITING_PAYOUT_KEY"
assert_same_payment "$WAITING_RETRIED_PAYOUT"
request 200 GET "$OTHER_PATH" "$CREATOR_TOKEN"
printf '%s' "$RESPONSE" | jq -e --arg creator "$CREATOR" --arg player "$PLAYER" \
    '.status == "ACTIVE" and (.players | length) == 2 and
     ([.players[] | select(.username == $creator) | .balance] == [1500]) and
     ([.players[] | select(.username == $player) | .balance] == [1600])' \
    >/dev/null || fail 'Retry after start must credit the non-creator exactly once'
assert_balances "$CREATOR_BALANCE" "$FINAL_PLAYER_BALANCE"

echo 'Testing finish permissions on an active board...'
request 401 POST "$BOARD_PATH/finish"
request 403 POST "$BOARD_PATH/finish" "$PLAYER_TOKEN"
assert_balances "$CREATOR_BALANCE" "$FINAL_PLAYER_BALANCE"

echo 'Sending two simultaneous creator finish requests...'
parallel_payments "$BOARD_PATH/finish" "$CREATOR_TOKEN" '' '' \
    "$CREATOR_TOKEN" '' ''
if ! { [[ "$STATUS_A" == 200 && "$STATUS_B" == 409 ]] ||
       [[ "$STATUS_A" == 409 && "$STATUS_B" == 200 ]]; }; then
    fail "Concurrent finishes must return one 200 and one 409; received $STATUS_A and $STATUS_B"
fi
if [[ "$STATUS_A" == 200 ]]; then
    RESPONSE=$(cat "$WORK_DIR/payment-a.json")
else
    RESPONSE=$(cat "$WORK_DIR/payment-b.json")
fi
assert_json '.status == "FINISHED"' 'Successful finish must return FINISHED board details'
assert_balances "$CREATOR_BALANCE" "$FINAL_PLAYER_BALANCE" FINISHED
request 409 POST "$BOARD_PATH/finish" "$CREATOR_TOKEN"
request 409 POST "$BOARD_PATH/start" "$CREATOR_TOKEN"
request 200 POST /api/boards/join "$PLAYER_TOKEN" "$JOIN_PAYLOAD"
assert_json '.status == "FINISHED"' 'Existing membership replay must report the finished status'
request 200 GET "$BOARD_PATH" "$PLAYER_TOKEN"
assert_json '.status == "FINISHED"' 'Members must still be able to read a finished board'

echo 'Testing finished boards reject new payments and payouts...'
request 409 POST "$PAYMENT_PATH" "$CREATOR_TOKEN" \
    "$(payment_payload "$PLAYER_WALLET" 1)" "$(uuidgen)"
request 409 POST "$BANK_PAYMENT_PATH" "$CREATOR_TOKEN" \
    "$(bank_payment_payload 1)" "$(uuidgen)"
request 409 POST "$BANK_PAYOUT_PATH" "$CREATOR_TOKEN" \
    "$(bank_payout_payload 1)" "$(uuidgen)"

echo 'Testing completed payments still replay after finishing without moving money...'
request 200 POST "$PAYMENT_PATH" "$CREATOR_TOKEN" \
    "$(payment_payload "$PLAYER_WALLET" 500)" "$PAYMENT_KEY"
assert_same_payment "$ORIGINAL_PAYMENT"
request 200 POST "$BANK_PAYMENT_PATH" "$CREATOR_TOKEN" \
    "$(bank_payment_payload 25)" "$BANK_PAYMENT_KEY"
assert_same_payment "$ORIGINAL_BANK_PAYMENT"
request 200 POST "$BANK_PAYOUT_PATH" "$CREATOR_TOKEN" \
    "$(bank_payout_payload 100)" "$PAYOUT_KEY"
assert_same_payment "$ORIGINAL_PAYOUT"
assert_balances "$CREATOR_BALANCE" "$FINAL_PLAYER_BALANCE" FINISHED

echo 'Testing finish racing with a new player payment on the other board...'
FINISH_RACE_KEY=$(uuidgen)
FINISH_RACE_PAYLOAD=$(payment_payload "$OTHER_PLAYER_WALLET" 10)
parallel_payments "$OTHER_PATH/finish" "$CREATOR_TOKEN" '' '' \
    "$CREATOR_TOKEN" "$FINISH_RACE_PAYLOAD" "$FINISH_RACE_KEY" "$OTHER_PATH/payments"
[[ "$STATUS_A" == 200 ]] || fail "Racing finish must succeed; received $STATUS_A"
RESPONSE=$(cat "$WORK_DIR/payment-a.json")
assert_json '.status == "FINISHED"' 'Racing finish must return FINISHED'
if [[ "$STATUS_B" == 200 ]]; then
    OTHER_CREATOR_BALANCE=1490
    OTHER_PLAYER_BALANCE=1610
    FINISH_RACE_PAYMENT=$(cat "$WORK_DIR/payment-b.json")
    request 200 POST "$OTHER_PATH/payments" "$CREATOR_TOKEN" \
        "$FINISH_RACE_PAYLOAD" "$FINISH_RACE_KEY"
    assert_same_payment "$FINISH_RACE_PAYMENT"
elif [[ "$STATUS_B" == 409 ]]; then
    OTHER_CREATOR_BALANCE=1500
    OTHER_PLAYER_BALANCE=1600
    request 409 POST "$OTHER_PATH/payments" "$CREATOR_TOKEN" \
        "$FINISH_RACE_PAYLOAD" "$FINISH_RACE_KEY"
else
    fail "Racing payment must succeed before finish or be rejected; received $STATUS_B"
fi
request 200 GET "$OTHER_PATH" "$CREATOR_TOKEN"
printf '%s' "$RESPONSE" | jq -e \
    --arg creator "$CREATOR" --arg player "$PLAYER" \
    --argjson creatorBalance "$OTHER_CREATOR_BALANCE" \
    --argjson playerBalance "$OTHER_PLAYER_BALANCE" \
    '.status == "FINISHED" and (.players | length) == 2 and
     ([.players[] | select(.username == $creator) | .balance] == [$creatorBalance]) and
     ([.players[] | select(.username == $player) | .balance] == [$playerBalance])' \
    >/dev/null || fail 'Finish/payment race must preserve exactly the accepted balance changes'
echo 'Testing bank totals on a finished board with an independent ledger...'
assert_bank_totals "$OTHER_PATH/bank" "$PLAYER_TOKEN" 0 "$((STARTING_TOTAL + 100))"
request 200 GET "$OTHER_PATH/transactions" "$PLAYER_TOKEN"
printf '%s' "$RESPONSE" | jq -e --arg board "$OTHER_BOARD_ID" \
    'all(.content[]; .boardId == $board) and any(.content[]; .reason == "REWARD")' \
    >/dev/null || fail 'Members must still see the finished board history scoped to that board'

echo 'Testing finished boards remain listed for their members...'
request 200 GET /api/boards "$PLAYER_TOKEN"
printf '%s' "$RESPONSE" | jq -e --arg first "$BOARD_ID" --arg second "$OTHER_BOARD_ID" \
    'map({boardId, status}) == [
        {boardId: $second, status: "FINISHED"},
        {boardId: $first, status: "FINISHED"}
    ]' >/dev/null || fail 'Both joined boards must remain listed with FINISHED status'
echo 'Testing members can still read their final wallet balances...'
assert_my_wallet "$CREATOR_TOKEN" "$CREATOR" "$CREATOR_WALLET" "$CREATOR_BALANCE"
assert_my_wallet "$PLAYER_TOKEN" "$PLAYER" "$PLAYER_WALLET" "$FINAL_PLAYER_BALANCE"
assert_balances "$CREATOR_BALANCE" "$FINAL_PLAYER_BALANCE" FINISHED

printf '\nAll board-list, board-start, player-wallet, player-payment, bank-details, bank-payment, bank-payout, board-finish, and transaction-history API checks passed. Board: %s\n' "$BOARD_ID"
printf '%s\n' "$RESPONSE" | jq .
echo "Additional board (also finished): $OTHER_BOARD_ID"
echo 'This checks API responses, balances, and visible history; direct database checks and forced mid-payment rollback are separate.'
