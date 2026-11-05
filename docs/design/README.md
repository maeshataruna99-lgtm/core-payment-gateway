# Design Mock

The mock shows the planned architecture, payment workflow, and operator and checkout screens. All data in it is sample data.

The source files are in `mock/`. They are `.dc.html` files for the design canvas and need its runtime, so they do not render correctly when opened directly in a browser.

To view them, open the [PayGate Sandbox Design canvas](https://claude.ai/artifact/A8P3toNc7R4ZNMTp4bDLtf). The link is private until its owner shares it.

| File | Content |
|---|---|
| `Main.dc.html` | System architecture |
| `Workflow.dc.html` | Simulated QRIS payment workflow |
| `States.dc.html` | Payment attempt state table |
| `Overview.dc.html` | Overview dashboard |
| `Monitor.dc.html` | Transaction monitor and payment detail |
| `Checkout.dc.html` | Checkout demo |
| `Webhooks.dc.html` | Webhook Center |
| `Settlement.dc.html` | Settlement and reconciliation |
| `Settings.dc.html` | Sandbox settings |

The screens follow the visual patterns of [shadcn/ui](https://github.com/shadcn-ui/ui), recreated in HTML. The application itself uses shadcn-vue.

The application differs from this mock in these ways:

- Pages poll the API every 3 to 5 seconds, not the SSE shown in the architecture diagram. Polling works with the API key header and needs no extra server code.
- The settings page lists the simulated provider's scenarios. It has no latency control or scenario selector, because scenarios are chosen by the amount on the checkout demo page.
- The refund event names are `refund.succeeded` and `refund.failed`.
- A settlement has no draft status. The preview is the draft.
