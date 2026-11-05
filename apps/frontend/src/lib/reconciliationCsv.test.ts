import { describe, expect, it } from 'vitest'
import { discrepanciesToCsv } from './reconciliationCsv'

describe('discrepanciesToCsv', () => {
  it('writes a header and quoted fields', () => {
    const csv = discrepanciesToCsv([
      { type: 'AMOUNT_MISMATCH', providerReference: 'SBX-1', internalValue: '1000', providerValue: '2000' },
    ])
    expect(csv).toBe(
      'type,provider_reference,payment_id,internal_value,provider_value\n' +
        '"AMOUNT_MISMATCH","SBX-1","","1000","2000"\n',
    )
  })

  it('escapes quotes', () => {
    expect(discrepanciesToCsv([{ type: 'A"B' }])).toContain('"A""B"')
  })

  it('stops a spreadsheet from running a provider value as a formula', () => {
    const csv = discrepanciesToCsv([{ type: 'STATUS_MISMATCH', providerValue: '=HYPERLINK("x")' }])
    expect(csv).toContain(`"'=HYPERLINK(""x"")"`)
  })
})
