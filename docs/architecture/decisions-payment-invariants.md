# Payment Invariant Decisions

Status: Accepted, 2026-11-05

These decisions fill gaps in the project blueprint, which requires explicit state transitions but does not define them. Where this document and the blueprint differ, this document applies. All payments, fees, and balances in this project are simulated.

## 1. Provider timeout

A provider timeout moves the attempt to `UNKNOWN`. It never counts as a failure. The outcome is resolved by a status check and by reconciliation.

## 2. Double payment and late success

Two active attempts on one checkout can both be paid, for example through QRIS and a Virtual Account. A success can also arrive after an attempt has failed or expired.

- Every provider-confirmed success is recorded.
- The first success settles the checkout.
- Any later success becomes a discrepancy to be refunded.

## 3. Refunds

- The refundable balance is reserved when a refund is created, so parallel requests cannot exceed it.
- A refund made after its settlement batch is final becomes an adjustment in the next batch. The final batch does not change.

## 4. Ledger and fees

- Minimum ledger accounts: `provider_clearing`, `merchant_payable`, `fee_revenue`.
- Fees are configuration labelled as simulated. Bank Indonesia rates are not hardcoded, because they can change.

## 5. Money type and limits

- Amounts are stored as `long` in whole rupiah.
- Amount limits are configuration.

## 6. Idempotency

- The idempotency key is sent in a request header.
- A key is scoped to the merchant, the key value, and a hash of the request.
- Reusing a key with a different body returns `409`.

## 7. Authentication and RBAC

Authentication and RBAC are delivered in Phase 2, because UC-01 already requires an authenticated merchant.

## 8. Start Goal

The Start Goal is reached at the end of Phase 3, as a walking skeleton. Phase 1 still ends at a passing health check.

## 9. SSRF in local development

Webhook destinations on private addresses stay blocked, except for an allowlist of receiver hosts that exists only in the local profile.

## 10. Code documentation standard

The What/Why/How standard applies to services, APIs, scripts, and migrations. It does not apply to every method.

## 11. Callback ordering

- A callback that arrives before the provider response is accepted. The late provider response does not change the state.
- A failure callback that arrives after a success does not change the state. It is recorded as a discrepancy.

## Payment attempt states

Each row is the current state. Each column is the incoming event. "No change" means the state stays the same.

| State | Provider: pending | Provider: failed | Timeout | Callback: success | Callback: failed | Expiry |
|---|---|---|---|---|---|---|
| `CREATED` | `PENDING` | `FAILED` | `UNKNOWN` | `SUCCEEDED` | `FAILED` | `EXPIRED` |
| `PENDING` | No change | `FAILED` | `UNKNOWN` | `SUCCEEDED` | `FAILED` | `EXPIRED` |
| `UNKNOWN` | `PENDING` | `FAILED` | No change, check status | `SUCCEEDED` | `FAILED` | No change |
| `SUCCEEDED` | No change | No change, discrepancy | No change | No change, deduplicate | No change, discrepancy | No change |
| `FAILED` | No change | No change | No change | `SUCCEEDED` (late) | No change | No change |
| `EXPIRED` | No change | No change | No change | `SUCCEEDED` (late) | No change | No change |

- An attempt in `UNKNOWN` does not expire, because money may already have moved. Decision 1 resolves it.
- Decision 2 decides whether a `SUCCEEDED` attempt settles the checkout or becomes a discrepancy to refund.
- A replayed event ID is rejected before this table applies.

The payment ID in the API is the ID of one attempt. Every event is recorded in `payment_attempt_events`. A status check runs for `UNKNOWN` attempts, and for `CREATED` attempts older than one minute.

A provider call never reports success, because the state table has no such column. This differs from blueprint UC-03. Success only arrives as a callback.

## Refund states

A refund moves from `PENDING` to `SUCCEEDED` or `FAILED`. The payment stays `SUCCEEDED`, and its refunded amount is calculated from its refunds. A timed-out refund stays `PENDING` and keeps its reservation until a status check resolves it.

## Ledger and settlement rules

These apply decisions 3 and 4.

- A second success on a checkout posts to a fourth account, `unapplied_funds`. It earns no fee and is never settled, so it can only be refunded.
- A fee is charged when a payment settles a checkout, and it is not returned by a refund. The rate is rounded half up.
- Ledger entries are posted when a refund succeeds, not when it is reserved.
- A settlement batch is final when created, and the preview is not saved.
- A batch whose net is zero or less is refused. Its lines wait for the next batch.
