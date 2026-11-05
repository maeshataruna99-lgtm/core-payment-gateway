import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, request } from './api'

function stubFetch(status: number, body: string, contentType = 'application/json') {
  const fetchMock = vi.fn(
    async () => new Response(body, { status, headers: { 'content-type': contentType } }),
  )
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

afterEach(() => vi.unstubAllGlobals())

describe('request', () => {
  it('sends the API key and a JSON body', async () => {
    const fetchMock = stubFetch(200, '{"ok":true}')
    const result = await request<{ ok: boolean }>('/x', {
      method: 'POST',
      apiKey: 'key-1',
      body: { a: 1 },
    })
    expect(result.ok).toBe(true)
    expect(fetchMock).toHaveBeenCalledWith('/x', {
      method: 'POST',
      headers: { 'X-Api-Key': 'key-1', 'Content-Type': 'application/json' },
      body: '{"a":1}',
    })
  })

  it('sends a raw body without changing it', async () => {
    const fetchMock = stubFetch(200, 'done', 'text/plain')
    expect(await request('/x', { method: 'POST', rawBody: 'a,b\n' })).toBe('done')
    expect(fetchMock.mock.calls[0]).toEqual([
      '/x',
      { method: 'POST', headers: {}, body: 'a,b\n' },
    ])
  })

  it('sends no key when signed out', async () => {
    const fetchMock = stubFetch(200, '{}')
    await request('/x', { apiKey: null })
    expect(fetchMock.mock.calls[0]).toEqual(['/x', { method: 'GET', headers: {}, body: undefined }])
  })

  it('turns the error format into an ApiError', async () => {
    stubFetch(
      409,
      '{"code":"REFUND_EXCEEDS_BALANCE","message":"Too much.","traceId":"t-1","fieldErrors":[{"field":"amount","message":"x"}]}',
    )
    const error = (await request('/x').catch((e) => e)) as ApiError
    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(409)
    expect(error.code).toBe('REFUND_EXCEEDS_BALANCE')
    expect(error.traceId).toBe('t-1')
    expect(error.fieldErrors[0].field).toBe('amount')
  })

  it('survives an error that is not JSON', async () => {
    stubFetch(502, '<html>Bad gateway</html>', 'text/html')
    const error = (await request('/x').catch((e) => e)) as ApiError
    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(502)
    expect(error.message).not.toContain('<html>')
  })
})
