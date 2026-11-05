# PayGate Sandbox

A portfolio simulator of an Indonesian payment gateway.

**SANDBOX ONLY:** it processes no real funds, connects to no real bank or provider, and must not receive real card numbers, PINs, OTPs, or bank logins. It does not claim PCI DSS, Bank Indonesia, or BI-SNAP compliance, or compatibility with any provider's API.

Status: Phase 3, payment orchestration. A simulated QRIS or Virtual Account payment can complete through the API. Signed callbacks and webhooks arrive in Phase 4, and the dashboard in Phase 7.

## Run the demo

You need Docker with Compose. You do not need Java or any credentials.

```bash
docker compose -f infra/docker/compose.yaml up --build
```

When it has started:

| URL | Content |
|---|---|
| http://localhost:8080/actuator/health | Health check |
| http://localhost:8080/swagger-ui.html | API documentation |
| http://localhost:8080/v3/api-docs | OpenAPI contract |

The demo seeds a synthetic merchant, Toko Contoh, with a dummy API key. To create a simulated checkout:

```bash
curl -i -X POST http://localhost:8080/api/v1/checkout-sessions -H "X-Api-Key: sandbox-demo-merchant-key" -H "Idempotency-Key: demo-order-0001" -H "Content-Type: application/json" -d '{"merchantOrderId":"ORD-1","amount":250000,"currency":"IDR"}'
```

Run it again to see the same checkout returned with `Idempotency-Replayed: true`. Change the amount but keep the key to see a `409`.

Then start a payment with `POST /api/v1/payments`. Staff finish it with `POST /api/v1/sandbox/payments/{paymentId}/events`, which simulates the provider's answer. [smoke-test.sh](scripts/smoke-test.sh) runs the whole flow, and the demo operator key is `sandbox-demo-operator-key`.

The last two digits of the amount choose how the simulated provider behaves:

| Amount ends in | Behavior |
|---|---|
| `01` | The provider rejects the payment: `FAILED` |
| `02` | The call times out: `UNKNOWN`, then `PENDING` after a status check, within about 15 seconds |
| `03` | Every call times out: `UNKNOWN` until a simulated answer arrives |
| Anything else | `PENDING` until a simulated answer arrives or the checkout expires |

To stop the demo and delete its local database:

```bash
docker compose -f infra/docker/compose.yaml down --volumes
```

To change the password or port, copy `.env.example` to `infra/docker/.env` and edit it.

## Develop

You need Java 21 and Docker. Docker is used by the integration tests.

```bash
cd apps/backend && ./mvnw verify
```

`verify` checks formatting, runs all tests, and builds the application. To fix formatting:

```bash
cd apps/backend && ./mvnw spotless:apply
```

To check the whole startup from a clean checkout. This deletes the demo's database when it finishes:

```bash
sh scripts/smoke-test.sh
```

## Layout

| Path | Content |
|---|---|
| `apps/backend` | Spring Boot application |
| `infra/docker` | Docker Compose demo |
| `scripts` | Developer scripts |
| `docs` | Architecture, research, scope, design, and glossary |

## Documents

- [Product scope](docs/roadmap/product-scope.md)
- [Payment decisions and state tables](docs/architecture/decisions-payment-invariants.md)
- [ADR-001: Modular monolith](docs/architecture/adr-001-modular-monolith.md)
- [ADR-002: Spring Boot 4](docs/architecture/adr-002-spring-boot-4.md)
- [Threat model](docs/architecture/threat-model.md)
- [Documentation standard](docs/architecture/documentation-standard.md)
- [Design mock](docs/design/README.md)
