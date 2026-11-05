const RUPIAH = new Intl.NumberFormat('id-ID')
const TIME = new Intl.DateTimeFormat('id-ID', {
  timeZone: 'Asia/Jakarta',
  day: '2-digit',
  month: 'short',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
})

/** Formats whole rupiah. Negative amounts keep their sign in front of the currency. */
export function rupiah(amount: number): string {
  const text = `Rp ${RUPIAH.format(Math.abs(amount))}`
  return amount < 0 ? `- ${text}` : text
}

/** Times are stored in UTC and shown in Western Indonesia Time. */
export function dateTime(iso: string): string {
  return `${TIME.format(new Date(iso))} WIB`
}

const METHOD_NAMES: Record<string, string> = {
  QRIS: 'QRIS simulasi',
  VIRTUAL_ACCOUNT: 'VA simulasi',
}

export function methodName(method: string): string {
  return METHOD_NAMES[method] ?? method
}
