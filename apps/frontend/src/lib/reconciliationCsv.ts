import type { ReconciliationRun } from './types'

/** Quotes every field, and stops a spreadsheet from running a value as a formula. */
function field(value: string | undefined): string {
  const text = value ?? ''
  const safe = /^[=+\-@\t\r]/.test(text) ? `'${text}` : text
  return `"${safe.replaceAll('"', '""')}"`
}

export function discrepanciesToCsv(items: NonNullable<ReconciliationRun['discrepancies']>): string {
  const rows = items.map((item) =>
    [item.type, item.providerReference, item.paymentId, item.internalValue, item.providerValue]
      .map(field)
      .join(','),
  )
  return ['type,provider_reference,payment_id,internal_value,provider_value', ...rows].join('\n') + '\n'
}
