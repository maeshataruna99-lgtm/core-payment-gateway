import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import StatusBadge from './StatusBadge.vue'

describe('StatusBadge', () => {
  it.each(['SUCCEEDED', 'PENDING', 'UNKNOWN', 'FAILED', 'EXPIRED', 'CREATED', 'DELIVERED', 'CANCELLED'])(
    'shows %s as text and an icon, not color alone',
    (status) => {
      const wrapper = mount(StatusBadge, { props: { status } })
      expect(wrapper.text()).toBe(status)
      expect(wrapper.find('svg').exists()).toBe(true)
    },
  )

  it('shows success in green and failure in red, as the blueprint requires', () => {
    expect(mount(StatusBadge, { props: { status: 'SUCCEEDED' } }).classes()).toContain('text-green-800')
    expect(mount(StatusBadge, { props: { status: 'FAILED' } }).classes()).toContain('text-red-800')
    expect(mount(StatusBadge, { props: { status: 'PENDING' } }).classes()).toContain('text-amber-900')
  })

  it('does not show unknown as success', () => {
    expect(mount(StatusBadge, { props: { status: 'UNKNOWN' } }).classes()).not.toContain('text-green-800')
  })

  it('accepts an unexpected status', () => {
    expect(mount(StatusBadge, { props: { status: 'NEW_STATE' } }).text()).toBe('NEW_STATE')
  })
})
