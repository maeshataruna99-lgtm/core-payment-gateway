export type PaymentStatus = 'CREATED' | 'PENDING' | 'UNKNOWN' | 'SUCCEEDED' | 'FAILED' | 'EXPIRED'

export interface PaymentRow {
  paymentId: string
  checkoutId: string
  merchantId: string
  status: PaymentStatus
  method: string
  amount: number
  currency: string
  createdAt: string
  updatedAt: string
}

export interface PaymentDetail {
  paymentId: string
  checkoutId: string
  status: PaymentStatus
  method: string
  channelCode?: string
  paidMethod?: string
  amount: number
  currency: string
  providerReference?: string
  instruction?: { label: string; value: string }
  createdAt: string
  updatedAt: string
  attempts: { paymentId: string; status: PaymentStatus; method: string }[]
  timeline?: { at: string; event: string; from: string; to: string }[]
  discrepancies?: { type: string; detail: string; at: string }[]
}

export interface PaymentStats {
  counts: Record<PaymentStatus, number>
  succeededAmount: number
  total: number
}

export interface Checkout {
  checkoutId: string
  merchantName: string
  merchantOrderId: string
  amount: number
  currency: string
  description?: string
  status: 'OPEN' | 'EXPIRED' | 'PAID'
  expiresAt: string
  allowedChannels: string[]
  checkoutUrl?: string
  latestPayment?: { paymentId: string; status: PaymentStatus; method: string }
}

export interface Refund {
  refundId: string
  amount: number
  reason: string
  status: string
  actor: string
  createdAt: string
}

export interface SettlementLine {
  transactionId: string
  kind: 'PAYMENT' | 'REFUND' | 'ADJUSTMENT'
  referenceId: string
  amount: number
  fee: number
}

export interface Settlement {
  id?: string
  merchantId: string
  gross: number
  fees: number
  refunds: number
  adjustments: number
  net: number
  finalizedAt?: string
  lines?: SettlementLine[]
}

export interface WebhookEndpoint {
  id: string
  url: string
  events: string[]
  active: boolean
  createdAt: string
  secret?: string
}

export interface WebhookDelivery {
  id: string
  eventId: string
  eventType: string
  state: 'PENDING' | 'DELIVERED' | 'FAILED' | 'CANCELLED'
  attempts: number
  nextAttemptAt: string
  createdAt: string
  history?: { attempt: number; statusCode?: number; error?: string; at: string }[]
}

export interface ReconciliationRun {
  runId: string
  merchantId: string
  from: string
  to: string
  rowCount: number
  matchedCount: number
  discrepancyCount: number
  createdAt: string
  discrepancies?: {
    type: string
    providerReference?: string
    paymentId?: string
    internalValue?: string
    providerValue?: string
  }[]
}

export interface Merchant {
  id: string
  name: string
}
