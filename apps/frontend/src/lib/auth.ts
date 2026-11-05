import { computed, ref } from 'vue'
import { ApiError, request, type RequestOptions } from './api'

export interface Me {
  clientId: string
  role: 'MERCHANT' | 'OPERATOR' | 'ADMIN'
  merchantId: string | null
}

const STORAGE_KEY = 'paygate-api-key'

// shortcut: the API key is kept in sessionStorage, which suits a local demo with dummy keys;
// use an HttpOnly session cookie before this dashboard holds a real credential.
const apiKey = ref<string | null>(sessionStorage.getItem(STORAGE_KEY))

export const me = ref<Me | null>(null)
export const isStaff = computed(() => me.value !== null && me.value.role !== 'MERCHANT')

/** Checks a key with the API and keeps it for this browser tab. */
export async function signIn(key: string): Promise<void> {
  me.value = await request<Me>('/api/v1/me', { apiKey: key })
  apiKey.value = key
  sessionStorage.setItem(STORAGE_KEY, key)
}

export function signOut(): void {
  apiKey.value = null
  me.value = null
  sessionStorage.removeItem(STORAGE_KEY)
}

/** Restores the session after a reload. Returns false when there is none. */
export async function restoreSession(): Promise<boolean> {
  if (me.value) {
    return true
  }
  if (!apiKey.value) {
    return false
  }
  try {
    await signIn(apiKey.value)
    return true
  } catch {
    signOut()
    return false
  }
}

/** Calls the API as the signed-in client. A rejected key ends the session. */
export async function authed<T>(path: string, options: RequestOptions = {}): Promise<T> {
  try {
    return await request<T>(path, { ...options, apiKey: apiKey.value })
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      signOut()
      window.location.assign('/login')
    }
    throw error
  }
}
