# Indonesian Payment Ecosystem Research

Research date: 2026-11-05. Regulations, limits, and provider support can change, so check these sources again before any public release.

This project is a simulator. Nothing here means it complies with, or integrates with, any regulation or provider.

## Verified facts

These facts were read on the linked official pages.

### QRIS

- QRIS is the QR payment standard set by Bank Indonesia. Payment service providers that use QR payments must apply it. [BI: QRIS](https://www.bi.go.id/id/fungsi-utama/sistem-pembayaran/ritel/kanal-layanan/qris/default.aspx)
- BI states a limit of Rp10,000,000 per QRIS transaction. Issuers may set cumulative daily or monthly limits. [BI: QRIS](https://www.bi.go.id/id/fungsi-utama/sistem-pembayaran/ritel/kanal-layanan/qris/default.aspx)
- PADG 3/2025 is the third amendment to PADG 21/18/PADG/2019 and took effect on 19 February 2025. It extends QRIS to messaging-based QR technology, with or without scanning. [BI: PADG 3/2025](https://www.bi.go.id/id/publikasi/peraturan/Pages/PADG_032025.aspx)

### SNAP

- SNAP is the national open API payment standard set by Bank Indonesia. [ASPI: SNAP](https://aspi-indonesia.or.id/standar-dan-layanan/standar-open-api-pembayaran-indonesia-snap/)
- It covers protocols, API architecture, authentication, authorization, encryption, access management, and request and response structure. Its governance guideline covers consumer protection, data protection, prudential requirements, and contracts. [ASPI: SNAP](https://aspi-indonesia.or.id/standar-dan-layanan/standar-open-api-pembayaran-indonesia-snap/)

### Providers

- Midtrans: a GoPay transaction paid by scanning a QR code sends a notification with `payment_type` set to `qris`, not `gopay`. [Midtrans: E-wallet integration](https://docs.midtrans.com/docs/coreapi-e-money-integration)
- Xendit lists these Indonesian channels: [Xendit: Available channels](https://docs.xendit.co/docs/available-payment-channels)
  - Virtual accounts: BCA, BJB, BNC, BNI, BRI, BSI, BSS, CIMB, HANA, MANDIRI, MUAMALAT, PERMATA.
  - E-wallets: GOPAY, DANA, LINKAJA, OVO, SHOPEEPAY, ASTRAPAY, JENIUSPAY, NEXCASH.
  - QRIS: minimum 1, maximum 20,000,000, settlement T+2 business days.
- DOKU Checkout lists these channels: [DOKU: Supported methods](https://developers.doku.com/accept-payments/doku-checkout/configuration/supported-payment-methods)
  - Virtual accounts: BCA, Mandiri, Bank Syariah Mandiri, BRI, BNI, DOKU, Permata, CIMB, Danamon, BTN, BNC, BSS, BJB, Sinarmas.
  - E-wallets: OVO, ShopeePay, DOKU, LinkAja, DANA.
  - QRIS: `QRIS`.
- DOKU does not support QRIS in its sandbox and has no QRIS transaction simulator. [DOKU: Manage payment methods](https://docs.doku.com/get-started/manage-business/manage-payment-methods)

## Unverified or conflicting

- **QRIS merchant discount rate:** the rate section of the BI page was not read in full. Do not use any MDR figure as fact.
- **QRIS maximum:** BI states Rp10,000,000 per transaction and Xendit lists 20,000,000. The pages do not explain the difference.
- **SNAP developer site:** `apidevportal.bi.go.id` did not resolve during this research. The ASPI page names `apidevportal.aspi-indonesia.or.id` as the developer site, which was not read.

## Assumptions

- A provider's sandbox cannot be relied on for every channel, based on the DOKU QRIS case.
- Channel availability differs by provider and can change.

## Simulator behavior

These are design choices for this project, not facts about the market.

- All channels, banks, fees, and settlement files are simulated.
- Amount limits and fees are configuration. They are not regulatory values.
- The payment model stores the requested channel and the actual channel separately, because the Midtrans case shows they can differ.
- The project makes no claim of BI-SNAP compliance or provider compatibility.
