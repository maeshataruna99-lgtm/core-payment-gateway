# Product Scope

Status: Approved, 2026-11-05

PayGate Sandbox is a portfolio simulator of an Indonesian payment gateway. It runs locally and moves no real money.

## In scope

- The 10 REST use cases in the project blueprint: checkout, payment, status, provider callback, webhook management, webhook retry, refund, settlement, and reconciliation.
- Simulated QRIS and Virtual Account flows as the main demo, plus simulated e-wallet and card channels.
- A payment state machine, double-entry ledger, transactional outbox, signed webhooks, and audit events.
- An operator dashboard and a customer checkout page.
- One documented command to start the local demo with Docker Compose.

## Non-goals

- Processing real funds or connecting to a real bank or provider.
- Collecting card numbers, CVV, PINs, OTPs, bank logins, or real customer data.
- Claiming PCI DSS, BI, or BI-SNAP compliance, or a payment license.
- Claiming API compatibility with Midtrans, Xendit, DOKU, or any other provider.
- Production deployment, high availability, or multi-region operation.
- Microservices. See [ADR-001](../architecture/adr-001-modular-monolith.md).

## Simulator boundaries

| Area | Real in this project | Simulated |
|---|---|---|
| API, auth, validation | Yes | |
| State machine, ledger, idempotency | Yes | |
| Webhook signing and delivery | Yes | |
| Provider and bank responses | | `SandboxPaymentProvider` |
| QR codes and VA numbers | | Sample values that cannot be paid |
| Money, fees, and balances | | Numbers in a local database |
| Settlement files | | Generated mock files |

Every screen shows a sandbox label. Mock events and balances are labelled as simulated.

## Success criteria

A reviewer can follow the demo script in 5 to 10 minutes and explain the payment lifecycle, the reliability choices, and what is simulated.

## Decisions

Payment rules are in [decisions-payment-invariants.md](../architecture/decisions-payment-invariants.md). Where that document and the blueprint differ, that document applies.

## Open question

The blueprint suggests Vue 3, while shadcn/ui, used as the reference for the mock, is a React library. The frontend stack must be chosen before frontend work starts.
