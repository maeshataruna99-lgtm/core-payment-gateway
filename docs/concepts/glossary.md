# Glossary

| Term | Meaning in this project |
|---|---|
| Adjustment | A settlement line that corrects an earlier final batch, such as a later refund. |
| Attempt | One try to pay a checkout through one channel. A checkout can have several. |
| Channel | A specific way to pay, such as a simulated bank's Virtual Account. |
| Checkout session | A merchant's request to collect an amount for one order, with an expiry time. |
| Discrepancy | A recorded mismatch that needs review. It never changes the ledger or payment status by itself. |
| Double-entry ledger | Records where every money movement has equal debit and credit entries. |
| Idempotency key | A client-supplied key that makes a repeated request return the original result and not repeat its effect. |
| MDR | Merchant discount rate, the fee a merchant pays per transaction. This project only simulates fees. |
| Outbox | A table written in the same database transaction as a state change, from which events are published later. |
| Provider | An outside party that processes a payment. Here it is always `SandboxPaymentProvider`. |
| QRIS | Bank Indonesia's QR payment standard. This project only simulates QRIS flows. |
| Reconciliation | Comparing internal records with a provider's settlement file to find discrepancies. |
| Refund | Returning part or all of a succeeded payment, tracked with its own state. |
| Settlement | Calculating what is owed to a merchant: gross, minus fees and refunds, plus adjustments, equals net. |
| SNAP | Bank Indonesia's national open API payment standard. This project does not claim to comply with it. |
| Virtual Account (VA) | A number assigned to one payment, paid by bank transfer. This project uses sample numbers. |
| Webhook | A signed HTTP notification sent from the gateway to a merchant. Delivery is at-least-once. |

Payment states are defined in [decisions-payment-invariants.md](../architecture/decisions-payment-invariants.md).
