import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import LoadState from './LoadState.vue'

function render(props: Record<string, unknown>) {
  return mount(LoadState, {
    props: { loading: false, error: null, ...props },
    slots: { default: '<p>CONTENT</p>' },
  })
}

describe('LoadState', () => {
  it('shows content when nothing else applies', () => {
    expect(render({}).text()).toContain('CONTENT')
  })

  it('shows a loading state instead of content', () => {
    const wrapper = render({ loading: true })
    expect(wrapper.text()).not.toContain('CONTENT')
    expect(wrapper.find('[role="status"]').exists()).toBe(true)
  })

  it('shows the permission state', () => {
    const wrapper = render({ forbidden: true, error: 'x' })
    expect(wrapper.text()).toContain('Tidak punya izin')
    expect(wrapper.text()).not.toContain('CONTENT')
  })

  it('shows the empty text', () => {
    const wrapper = render({ empty: true, emptyText: 'Kosong' })
    expect(wrapper.text()).toContain('Kosong')
    expect(wrapper.text()).not.toContain('CONTENT')
  })

  it('keeps earlier data on screen when a reload fails', () => {
    const wrapper = render({ error: 'Gagal' })
    expect(wrapper.text()).toContain('Gagal')
    expect(wrapper.text()).toContain('CONTENT')
  })

  it('hides content when there is no data yet', () => {
    const wrapper = render({ error: 'Gagal', hasData: false })
    expect(wrapper.text()).toContain('Gagal')
    expect(wrapper.text()).not.toContain('CONTENT')
  })
})
