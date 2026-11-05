import { onMounted, onUnmounted, ref, type Ref } from 'vue'
import { ApiError } from '@/lib/api'

export interface Loaded<T> {
  data: Ref<T | null>
  /** True only for the first load, so polling does not make the page flicker. */
  loading: Ref<boolean>
  error: Ref<string | null>
  forbidden: Ref<boolean>
  lastUpdated: Ref<Date | null>
  reload: () => Promise<void>
}

/**
 * Loads data when a page opens and optionally polls it.
 *
 * A failed poll keeps the last data on screen and shows the error, so a brief outage does not blank
 * the page.
 */
export function useLoad<T>(load: () => Promise<T>, pollMs?: number): Loaded<T> {
  const data = ref<T | null>(null) as Ref<T | null>
  const loading = ref(true)
  const error = ref<string | null>(null)
  const forbidden = ref(false)
  const lastUpdated = ref<Date | null>(null)
  let timer: ReturnType<typeof setInterval> | undefined

  async function reload(): Promise<void> {
    try {
      data.value = await load()
      error.value = null
      forbidden.value = false
      lastUpdated.value = new Date()
    } catch (e) {
      forbidden.value = e instanceof ApiError && e.status === 403
      error.value = e instanceof Error ? e.message : 'Terjadi kesalahan.'
    } finally {
      loading.value = false
    }
  }

  onMounted(() => {
    void reload()
    if (pollMs) {
      timer = setInterval(() => void reload(), pollMs)
    }
  })
  onUnmounted(() => clearInterval(timer))

  return { data, loading, error, forbidden, lastUpdated, reload }
}
