# Threat Model

Status: Draft, 2026-11-05. The controls for client-set status, double payment, duplicate requests, cross-merchant access, and rate limits exist, and RBAC exists for checkout and payment. The controls for forged and replayed callbacks, SSRF, webhook spoofing, and sandbox endpoint exposure exist. Webhook secrets are encrypted at rest. The controls for over-refund, privilege misuse, and tampered records exist: the database rejects edits to the ledger, final settlements, and audit log, and a hash chain reveals an edit that bypasses this. The secret leak controls exist except log redaction, which is limited to returning no internal error detail to clients. Other controls are not implemented yet.

## Assets

- Integrity of payment status, ledger, refund, and settlement records, even though the money is simulated.
- Merchant data isolation.
- Webhook secrets, callback signing keys, and JWT signing keys.
- Audit history.

## Trust boundaries

1. Merchant API clients to the gateway.
2. Customer browser to checkout.
3. Operator browser to dashboard.
4. Simulator callbacks to the gateway.
5. Gateway webhooks to merchant URLs.
6. Gateway to PostgreSQL and Redis.

## Threats and controls

| Threat | Example | Control | Roadmap phase |
|---|---|---|---|
| Forged callback | A request marks a payment as paid | HMAC signature; validate amount, currency, merchant, and reference | 4 |
| Replayed callback | The same event is sent twice | Unique event ID; replay is rejected | 4 |
| Client-set status | A client sends `PAID` | No API accepts a final status; transitions follow the state table | 3 |
| Duplicate request | A retry creates a second payment or refund | Idempotency key scoped to merchant, key, and request hash | 2 |
| Over-refund | Parallel refunds exceed the amount paid | Refundable balance is reserved in one transaction | 5 |
| Double payment | QRIS and VA attempts are both paid | First success settles; later success becomes a discrepancy | 3 |
| Cross-merchant access | Merchant A reads merchant B's payment | Merchant-scoped queries and authorization tests | 2 |
| Privilege misuse | A merchant finalizes settlement | RBAC, explicit confirmation, and audit events | 2, 5 |
| SSRF | A webhook URL targets an internal service | Block private, loopback, and link-local targets, including after redirects and DNS resolution; allowlist only in the local profile | 4 |
| Webhook spoofing | A third party imitates the gateway | HMAC signature with timestamp; secret shown once | 4 |
| Secret leak | A key appears in Git or logs | `.env` not committed, dummy values in `.env.example`, secret scanning, log redaction | 1 |
| Tampered records | Direct edits to final settlement or audit rows | Application allows no updates to these records; a database user with direct access remains out of scope | 5 |
| Sandbox endpoint exposure | Simulator callback is reachable in another profile | Endpoint disabled outside the sandbox profile | 4 |
| Abuse | A public endpoint is flooded | Rate limits | 2 |
| Mistaken real data | A user types a real card number or OTP | No such fields exist; sandbox label on every screen | 7 |

## Out of scope

Real payment fraud, PCI DSS scope, host and network hardening, and attackers with database or host access. This project is a local demo and must not be deployed as a real payment service.

## Open items

- API clients use API keys, stored only as SHA-256 hashes. The dashboard signs in with the same keys and keeps the key in `sessionStorage`, where a script injection could read it. A strict Content-Security-Policy limits that, and an HttpOnly session cookie is needed before the dashboard holds a real credential.
- The demo image builds its dummy keys into its JavaScript, so anyone who can open the demo can act as its operator. A build without those values has no such shortcut.
- The rate limit sees the frontend proxy as one address for browser traffic.
- Rate limits count per process and per socket address, so they would need rework behind a proxy.
- The URL check runs before each send, but the HTTP client resolves DNS again, so a short DNS rebinding window remains. Redirects are not followed.
- A customer token never expires by itself. It only reads one checkout, and the checkout's status shows its expiry.
- Review this model again in Phase 8.
