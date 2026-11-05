# ADR-001: Modular Monolith

Status: Accepted, 2026-11-05

## Context

The project must demonstrate payment correctness and start locally with one command. One developer maintains it. A payment state change, its ledger entries, and its outbox event must commit together.

## Decision

Build one Spring Boot application with one PostgreSQL database, divided into domain modules such as `checkout`, `payment`, `provider`, `ledger`, `refund`, `settlement`, `reconciliation`, and `webhook`.

- PostgreSQL is the source of truth. Redis never holds the final state of money or payments.
- A state change, ledger entries, and outbox event commit in one database transaction.
- Provider calls run outside database transactions.
- Modules call each other through public services and do not use each other's tables or entities.

## Alternatives

- **Microservices:** would require distributed transactions or sagas for the core ledger write, and more infrastructure for the demo. Rejected for now.
- **Layered monolith without modules:** is simpler at the start, but domain boundaries would be harder to see and explain. Rejected.

## Consequences

- The local demo and the core money transaction stay simple.
- Module boundaries are not enforced by the network, so they need an automated check, such as ArchUnit, from Phase 1.
- All modules deploy and scale together. This is acceptable because production scale is a non-goal.
- The provider port and outbox leave a path to extract services later, if that ever becomes necessary.
