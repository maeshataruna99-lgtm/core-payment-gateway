#!/bin/sh
# Purpose: Starts the local demo and runs a simulated payment from checkout to paid.
# Why: It proves the documented startup and payment lifecycle work from a clean checkout.
# Prerequisites: Docker with Compose, and curl. Java is not needed.
# How to run: sh scripts/smoke-test.sh
# Inputs: BACKEND_PORT, optional, defaults to 8080.
#         DEMO_MERCHANT_API_KEY and DEMO_OPERATOR_API_KEY, optional, must match the running demo.
#         KEEP_RUNNING=1 leaves the demo running.
# Expected output: "Smoke test passed" and exit code 0.
# Failure behavior: Prints container logs and exits non-zero.
# Data: It creates one simulated checkout and payment. It deletes the demo's containers and database
#       volume when it finishes, unless KEEP_RUNNING=1 is set.
set -eu

compose_file="$(dirname "$0")/../infra/docker/compose.yaml"
base="http://localhost:${BACKEND_PORT:-8080}"
merchant_key="${DEMO_MERCHANT_API_KEY:-sandbox-demo-merchant-key}"
operator_key="${DEMO_OPERATOR_API_KEY:-sandbox-demo-operator-key}"
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

curl -fsS -X POST "$base/api/v1/sandbox/payments/$payment_id/events" \
  -H "X-Api-Key: $operator_key" \
  -H "Content-Type: application/json" \
  -d '{"outcome":"SUCCESS"}' > /dev/null || fail "could not simulate the provider's answer"

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

echo "Smoke test passed: checkout $id, payment $payment_id"
