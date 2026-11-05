/** An error response from the API, in its standard format. */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly traceId: string | undefined
  readonly fieldErrors: { field: string; message: string }[]

  constructor(
    status: number,
    code: string,
    message: string,
    traceId?: string,
    fieldErrors: { field: string; message: string }[] = [],
  ) {
    super(message)
    this.status = status
    this.code = code
    this.traceId = traceId
    this.fieldErrors = fieldErrors
  }
}

export interface RequestOptions {
  method?: string
  headers?: Record<string, string>
  body?: unknown
  /** Sent as is, for CSV. Otherwise {@link body} is sent as JSON. */
  rawBody?: string
  apiKey?: string | null
}

/** Calls the API on this origin. A proxy forwards `/api` to the backend. */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { ...options.headers }
  if (options.apiKey) {
    headers['X-Api-Key'] = options.apiKey
  }
  let body: string | undefined
  if (options.rawBody !== undefined) {
    body = options.rawBody
  } else if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(options.body)
  }
  const response = await fetch(path, { method: options.method ?? 'GET', headers, body })
  const text = await response.text()
  if (!response.ok) {
    throw toError(response.status, text)
  }
  const type = response.headers.get('content-type') ?? ''
  return (type.includes('json') && text ? JSON.parse(text) : text) as T
}

function toError(status: number, text: string): ApiError {
  try {
    const error = JSON.parse(text)
    return new ApiError(status, error.code, error.message, error.traceId, error.fieldErrors ?? [])
  } catch {
    return new ApiError(status, 'UNEXPECTED', 'Server tidak dapat dihubungi. Coba lagi.')
  }
}

export function newIdempotencyKey(): string {
  return crypto.randomUUID()
}
