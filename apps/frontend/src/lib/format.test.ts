import { describe, expect, it } from 'vitest'
import { dateTime, methodName, rupiah } from './format'

describe('rupiah', () => {
  it('uses Indonesian thousand separators', () => {
    expect(rupiah(250000)).toBe('Rp 250.000')
  })
  it('keeps the sign in front', () => {
    expect(rupiah(-20000)).toBe('- Rp 20.000')
  })
  it('shows zero', () => {
    expect(rupiah(0)).toBe('Rp 0')
  })
})

describe('dateTime', () => {
  it('shows UTC times as Western Indonesia Time', () => {
    const text = dateTime('2026-11-05T17:30:00Z')
    expect(text).toContain('06')
    expect(text).toContain('00.30.00')
    expect(text.endsWith('WIB')).toBe(true)
  })
})

describe('methodName', () => {
  it('labels methods as simulated', () => {
    expect(methodName('QRIS')).toBe('QRIS simulasi')
    expect(methodName('VIRTUAL_ACCOUNT')).toBe('VA simulasi')
  })
})
