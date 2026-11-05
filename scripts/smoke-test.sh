#!/bin/sh
# Purpose: Starts the local demo and checks that the backend reports healthy.
# Why: It proves the documented startup works from a clean checkout.
# Prerequisites: Docker with Compose. Java is not needed.
# How to run: sh scripts/smoke-test.sh
# Inputs: BACKEND_PORT, optional, defaults to 8080. KEEP_RUNNING=1 leaves the demo running.
# Expected output: "Smoke test passed" and exit code 0.
# Failure behavior: Prints container logs and exits non-zero.
# Data: It deletes the demo's containers and database volume when it finishes,
#       unless KEEP_RUNNING=1 is set. It changes nothing outside this demo.
set -eu

compose_file="$(dirname "$0")/../infra/docker/compose.yaml"
url="http://localhost:${BACKEND_PORT:-8080}/actuator/health"

cleanup() {
  if [ "${KEEP_RUNNING:-0}" != "1" ]; then
    docker compose -f "$compose_file" down --volumes
  fi
}
trap cleanup EXIT

if ! docker compose -f "$compose_file" up --detach --build --wait; then
  docker compose -f "$compose_file" logs
  exit 1
fi

body="$(curl -fsS "$url")"
case "$body" in
  *'"status":"UP"'*) echo "Smoke test passed: $body" ;;
  *)
    echo "Unexpected health response: $body"
    docker compose -f "$compose_file" logs
    exit 1
    ;;
esac
