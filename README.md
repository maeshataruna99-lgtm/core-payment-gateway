# PayGate Sandbox

A portfolio simulator of an Indonesian payment gateway.

**SANDBOX ONLY:** it processes no real funds, connects to no real bank or provider, and must not receive real card numbers, PINs, OTPs, or bank logins. It does not claim PCI DSS, Bank Indonesia, or BI-SNAP compliance, or compatibility with any provider's API.

Status: Phase 7, user interface. All 10 REST use cases work, and the whole flow can be run from the dashboard and checkout pages. Hardening and release materials arrive in Phase 8.

## Run the demo

You need Docker with Compose. You do not need Java or any credentials.

```bash
docker compose -f infra/docker/compose.yaml up --build
```

When it has started:

| URL | Content |
|---|---|
| http://localhost:5173 | Dashboard and checkout. The sign-in page has demo buttons. |
| http://localhost:8080/actuator/health | Health check |
| http://localhost:8080/swagger-ui.html | API documentation |
| http://localhost:8080/v3/api-docs | OpenAPI contract |

The demo seeds a synthetic merchant, Toko Contoh, with a dummy API key. To create a simulated checkout:

```bash
curl -i -X POST http://localhost:8080/api/v1/checkout-sessions -H "X-Api-Key: sandbox-demo-merchant-key" -H "Idempotency-Key: demo-order-0001" -H "Content-Type: application/json" -d '{"merchantOrderId":"ORD-1","amount":250000,"currency":"IDR"}'
```

Run it again to see the same checkout returned with `Idempotency-Replayed: true`. Change the amount but keep the key to see a `409`.

Then start a payment with `POST /api/v1/payments`. It finishes through a signed callback to `POST /api/v1/sandbox/provider-callbacks/sandbox`, or through `POST /api/v1/sandbox/payments/{paymentId}/events`, which lets staff simulate the provider's answer. [smoke-test.sh](scripts/smoke-test.sh) runs the whole flow, including a replayed callback and a webhook retry. The demo operator key is `sandbox-demo-operator-key`.

The last two digits of the amount choose how the simulated provider behaves:

| Amount ends in | Behavior |
|---|---|
| `01` | The provider rejects the payment: `FAILED` |
| `02` | The call times out: `UNKNOWN`, then `PENDING` after a status check, within about 15 seconds |
| `03` | Every call times out: `UNKNOWN` until a simulated answer arrives |
| Anything else | `PENDING` until a simulated answer arrives or the checkout expires |

## Refunds and settlement

`POST /api/v1/payments/{paymentId}/refunds` refunds part or all of a succeeded payment. A refund amount ending in `01`, `02`, or `03` triggers the same simulated rejection and timeouts as a payment. Otherwise it succeeds at once.

Staff settle a merchant in two steps. `POST /api/v1/settlements/preview` shows gross, fees, refunds, adjustments, and net without saving anything. `POST /api/v1/settlements` finalizes that batch, and needs `"confirm": true` and the preview's net as `expectedNet`. No bank transfer happens.

Staff can read privileged actions at `GET /api/v1/audit-events`, and check their hash chain at `GET /api/v1/audit-events/verification`.

## Reconciliation

`POST /api/v1/reconciliation/runs?merchantId=&from=&to=` compares a provider's settlement file, sent as a CSV body, with internal payments. It reports payments missing from either side, duplicates, and differences in amount, currency, status, or date. Dates may differ by up to 24 hours. Differences are stored for review and never change a payment or the ledger.

In the demo, `GET /api/v1/sandbox/settlement-files?merchantId=&from=&to=&faults=` generates a file. `faults` can add deliberate errors: `AMOUNT`, `STATUS`, `MISSING`, `DUPLICATE`, and `UNKNOWN_REFERENCE`. The demo merchant ID is `00000000-0000-4000-8000-000000000001`.

## Receive webhooks

Register a URL with `POST /api/v1/webhook-endpoints`. The response shows the signing secret once. Delivery is at-least-once, so deduplicate on `X-PayGate-Event-Id`.

To verify a webhook, read `t` and `v1` from the `X-PayGate-Signature` header (`t=<epoch seconds>,v1=<hex>`). Compute the HMAC-SHA256 of `t.body` with your secret, compare it with `v1`, and reject an old `t`.

A failed delivery retries with exponential backoff, up to 5 attempts. `POST /api/v1/webhook-deliveries/{id}/retry` retries a failed one, up to 10 attempts in total. Only HTTPS URLs on public addresses are allowed, except for `localhost` in the demo. The demo's test receiver is `http://localhost:8080/demo/webhook-receiver?failTimes=1`, which fails the given number of times per event.

## Stop the demo

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

The frontend uses Node 24, Vue 3, and [shadcn-vue](https://www.shadcn-vue.com). To check it:

```bash
cd apps/frontend && npm ci && npm run typecheck && npm test && npm run build
```

To run it in development, with the backend on port 8080:

```bash
cd apps/frontend && npm run dev
```

To check the whole startup from a clean checkout. This deletes the demo's database when it finishes:

```bash
sh scripts/smoke-test.sh
```

## Layout

| Path | Content |
|---|---|
| `apps/backend` | Spring Boot application |
| `apps/frontend` | Vue dashboard and checkout |
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
