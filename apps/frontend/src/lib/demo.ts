/**
 * Dummy keys built into the local demo image only. Without them, every demo control is hidden, so
 * a build without these values has no shortcut to any privileged action.
 */
export const demoMerchantKey: string | undefined = import.meta.env.VITE_DEMO_MERCHANT_KEY
export const demoOperatorKey: string | undefined = import.meta.env.VITE_DEMO_OPERATOR_KEY
export const demoMerchantId: string | undefined = import.meta.env.VITE_DEMO_MERCHANT_ID

export const isDemo = Boolean(demoMerchantKey && demoOperatorKey)

/** The last two digits of an amount choose how the simulated provider behaves. */
export const SCENARIOS = [
  { suffix: 0, name: 'Normal', note: 'PENDING sampai jawaban provider simulasi tiba' },
  { suffix: 1, name: 'Ditolak', note: 'Provider simulasi menolak: FAILED' },
  { suffix: 2, name: 'Timeout sekali', note: 'UNKNOWN, lalu PENDING setelah cek status' },
  { suffix: 3, name: 'Selalu timeout', note: 'UNKNOWN sampai jawaban provider simulasi tiba' },
] as const
