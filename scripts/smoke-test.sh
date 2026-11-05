#!/bin/sh
# Purpose: Starts the local demo and runs a simulated payment from checkout to reconciliation.
# Why: It proves the startup, payment lifecycle, replay protection, webhook retry, refund,
#      settlement, audit chain, and reconciliation from a clean checkout.
# Prerequisites: Docker with Compose, curl, and openssl. Java is not needed.
# How to run: sh scripts/smoke-test.sh
# Inputs: BACKEND_PORT, optional, defaults to 8080.
#         DEMO_MERCHANT_API_KEY, DEMO_OPERATOR_API_KEY, and PROVIDER_CALLBACK_SECRET, optional,
#         must match the demo.
#         KEEP_RUNNING=1 leaves the demo running.
# Expected output: "Smoke test passed" and exit code 0.
# Failure behavior: Prints container logs and exits non-zero.
# Data: It creates one simulated checkout, payment, refund, settlement, and two reconciliation
#       runs. It deletes the demo's containers and database
#       volume when it finishes, unless KEEP_RUNNING=1 is set.
set -eu

compose_file="$(dirname "$0")/../infra/docker/compose.yaml"
base="http://localhost:${BACKEND_PORT:-8080}"
merchant_key="${DEMO_MERCHANT_API_KEY:-sandbox-demo-merchant-key}"
operator_key="${DEMO_OPERATOR_API_KEY:-sandbox-demo-operator-key}"
callback_secret="${PROVIDER_CALLBACK_SECRET:-sandbox-demo-callback-secret}"
demo_merchant_id="00000000-0000-4000-8000-000000000001"
checkouts="$base/api/v1/checkout-sessions"
body='{"merchantOrderId":"SMOKE-1","amount":250000,"currency":"IDR"}'

cleanup() {
  if [ "${KEEP_RUNNING:-0}" != "1" ]; then
    docker compose -f "$compose_file" down --volumes
  fi
}
trap cleanup EXIT

fail() {
  echo "Smoke test failed: $1"
  docker compose -f "$compose_file" logs
  exit 1
}

create_checkout() {
  curl -fsS -X POST "$checkouts" \
    -H "X-Api-Key: $merchant_key" \
    -H "Idempotency-Key: smoke-test-key" \
    -H "Content-Type: application/json" \
    -d "$body"
}

checkout_id() {
  printf '%s' "$1" | sed -n 's/.*"checkoutId":"\([^"]*\)".*/\1/p'
}

status_of() {
  curl -s -o /dev/null -w '%{http_code}' "$@"
}

docker compose -f "$compose_file" up --detach --build --wait || fail "the demo did not start"

health="$(curl -fsS "$base/actuator/health")"
case "$health" in
  *'"status":"UP"'*) ;;
  *) fail "unexpected health response: $health" ;;
esac

first="$(create_checkout)" || fail "could not create a checkout"
second="$(create_checkout)" || fail "could not repeat the request"
id="$(checkout_id "$first")"
[ -n "$id" ] || fail "no checkout ID in response: $first"
[ "$id" = "$(checkout_id "$second")" ] || fail "a repeated request created a second checkout"

[ "$(status_of -H "X-Api-Key: $merchant_key" "$checkouts/$id")" = "200" ] \
  || fail "the merchant could not read its checkout"
[ "$(status_of "$checkouts/$id")" = "404" ] || fail "a checkout was readable without credentials"

curl -fsS -X POST "$base/api/v1/webhook-endpoints" \
  -H "X-Api-Key: $merchant_key" \
  -H "Content-Type: application/json" \
  -d '{"url":"http://localhost:8080/demo/webhook-receiver?failTimes=1","events":["payment.succeeded"]}' \
  > /dev/null || fail "could not register a webhook endpoint"

payment="$(curl -fsS -X POST "$base/api/v1/payments" \
  -H "X-Api-Key: $merchant_key" \
  -H "Idempotency-Key: smoke-test-payment" \
  -H "Content-Type: application/json" \
  -d "{\"checkoutId\":\"$id\",\"paymentMethod\":\"QRIS\"}")" || fail "could not start a payment"
payment_id="$(printf '%s' "$payment" | sed -n 's/.*"paymentId":"\([^"]*\)".*/\1/p' | cut -c1-36)"
[ -n "$payment_id" ] || fail "no payment ID in response: $payment"
case "$payment" in
  *'"status":"PENDING"'*) ;;
  *) fail "the payment did not start as pending: $payment" ;;
esac

reference="$(printf '%s' "$payment" | sed -n 's/.*"providerReference":"\([^"]*\)".*/\1/p')"
callback="{\"eventId\":\"smoke-event-1\",\"paymentId\":\"$payment_id\",\"merchantId\":\"$demo_merchant_id\",\"providerReference\":\"$reference\",\"outcome\":\"SUCCESS\",\"amount\":250000,\"currency\":\"IDR\"}"
send_callback() {
  timestamp="$(date +%s)"
  signature="$(printf '%s.%s' "$timestamp" "$1" | openssl dgst -sha256 -hmac "$2" | sed 's/^.* //')"
  curl -s -X POST "$base/api/v1/sandbox/provider-callbacks/sandbox" \
    -H "X-Provider-Timestamp: $timestamp" \
    -H "X-Provider-Signature: $signature" \
    -H "Content-Type: application/json" \
    -d "$1"
}

case "$(send_callback "$callback" wrong-secret)" in
  *INVALID_SIGNATURE*) ;;
  *) fail "a callback with a wrong signature was not rejected" ;;
esac
case "$(send_callback "$callback" "$callback_secret")" in
  *'"duplicate":false'*) ;;
  *) fail "the signed callback was not accepted" ;;
esac
case "$(send_callback "$callback" "$callback_secret")" in
  *'"duplicate":true'*) ;;
  *) fail "a replayed callback was not detected" ;;
esac

paid="$(curl -fsS -H "X-Api-Key: $merchant_key" "$base/api/v1/payments/$payment_id")" \
  || fail "could not read the payment"
case "$paid" in
  *'"status":"SUCCEEDED"'*) ;;
  *) fail "the payment did not succeed: $paid" ;;
esac
checkout="$(curl -fsS -H "X-Api-Key: $merchant_key" "$checkouts/$id")" \
  || fail "could not read the checkout"
case "$checkout" in
  *'"status":"PAID"'*) ;;
  *) fail "the checkout was not paid: $checkout" ;;
esac

case "$paid" in
  *'"event":"CALLBACK_SUCCESS"'*'"event":"CALLBACK_SUCCESS"'*) fail "a replay changed the payment" ;;
esac

# The receiver fails the first call on purpose, so delivery needs one retry.
delivery=""
tries=0
while [ "$tries" -lt 30 ]; do
  delivery="$(curl -fsS -H "X-Api-Key: $merchant_key" "$base/api/v1/webhook-deliveries")" \
    || fail "could not read webhook deliveries"
  case "$delivery" in
    *'"state":"DELIVERED"'*) break ;;
  esac
  tries=$((tries + 1))
  sleep 2
done
case "$delivery" in
  *'"state":"DELIVERED"'*'"attempts":2'* | *'"attempts":2'*'"state":"DELIVERED"'*) ;;
  *) fail "the webhook was not delivered on its second attempt: $delivery" ;;
esac

refund="$(curl -fsS -X POST "$base/api/v1/payments/$payment_id/refunds" \
  -H "X-Api-Key: $merchant_key" \
  -H "Idempotency-Key: smoke-test-refund" \
  -H "Content-Type: application/json" \
  -d '{"amount":50000,"reason":"Smoke test"}')" || fail "could not refund"
case "$refund" in
  *'"status":"SUCCEEDED"'*) ;;
  *) fail "the refund did not succeed: $refund" ;;
esac

# 250000 gross, minus 1750 in simulated fees, minus the 50000 refund.
expected_net=198250
preview="$(curl -fsS -X POST "$base/api/v1/settlements/preview" \
  -H "X-Api-Key: $operator_key" \
  -H "Content-Type: application/json" \
  -d "{\"merchantId\":\"$demo_merchant_id\"}")" || fail "could not preview the settlement"
case "$preview" in
  *"\"net\":$expected_net"*) ;;
  *) fail "unexpected settlement preview: $preview" ;;
esac
curl -fsS -X POST "$base/api/v1/settlements" \
  -H "X-Api-Key: $operator_key" \
  -H "Idempotency-Key: smoke-test-settlement" \
  -H "Content-Type: application/json" \
  -d "{\"merchantId\":\"$demo_merchant_id\",\"expectedNet\":$expected_net,\"confirm\":true}" \
  > /dev/null || fail "could not finalize the settlement"
[ "$(status_of -X POST "$base/api/v1/settlements" \
  -H "X-Api-Key: $operator_key" \
  -H "Idempotency-Key: smoke-test-settlement-2" \
  -H "Content-Type: application/json" \
  -d "{\"merchantId\":\"$demo_merchant_id\",\"expectedNet\":$expected_net,\"confirm\":true}")" = "409" ] \
  || fail "the same money could be settled twice"

case "$(curl -fsS -H "X-Api-Key: $operator_key" "$base/api/v1/audit-events/verification")" in
  *'"valid":true'*) ;;
  *) fail "the audit chain is not valid" ;;
esac

period="merchantId=$demo_merchant_id&from=2000-01-01T00:00:00Z&to=2100-01-01T00:00:00Z"
reconcile() {
  curl -fsS -H "X-Api-Key: $operator_key" \
    "$base/api/v1/sandbox/settlement-files?$period&faults=$1" \
    | curl -fsS -X POST "$base/api/v1/reconciliation/runs?$period" \
        -H "X-Api-Key: $operator_key" \
        -H "Content-Type: text/csv" \
        --data-binary @-
}

clean_run="$(reconcile "")" || fail "could not run a reconciliation"
case "$clean_run" in
  *'"matchedCount":1'*'"discrepancyCount":0'*) ;;
  *) fail "a clean settlement file reported differences: $clean_run" ;;
esac
faulty_run="$(reconcile "AMOUNT,UNKNOWN_REFERENCE")" || fail "could not run a reconciliation"
case "$faulty_run" in
  *'"discrepancyCount":2'*AMOUNT_MISMATCH*MISSING_INTERNALLY*) ;;
  *) fail "reconciliation did not find the deliberate errors: $faulty_run" ;;
esac
paid_after="$(curl -fsS -H "X-Api-Key: $merchant_key" "$base/api/v1/payments/$payment_id")" \
  || fail "could not read the payment"
case "$paid_after" in
  *'"status":"SUCCEEDED"'*) ;;
  *) fail "reconciliation changed the payment: $paid_after" ;;
esac

echo "Smoke test passed: payment $payment_id, webhook retried, refund, settlement of $expected_net, and reconciliation"
