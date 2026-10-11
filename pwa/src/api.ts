export type ItemKind = 'DIRECTORY' | 'ARCHIVE' | 'IMAGE'

export interface Progress {
  sourceId: string
  location: string
  title: string
  page: number
  pageCount: number
  updatedAt: number
}

export interface Item {
  name: string
  location: string
  kind: ItemKind
  size: number
  progress?: Progress | null
}

export interface Page {
  name: string
  location: string
}

export interface ListResponse {
  name: string
  location: string
  parent: string | null
  items: Item[]
}

export interface BookResponse {
  title: string
  location: string
  parent: string | null
  pages: Page[]
  progress: Progress | null
  next: Item | null
  previous: Item | null
  browse: boolean
}

export interface Source {
  id: string
  name: string
}

export class AuthError extends Error {}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, { credentials: 'same-origin', ...init })
  if (res.status === 401) throw new AuthError('login required')
  if (!res.ok) {
    const body = await res.json().catch(() => ({ error: res.statusText }))
    throw new Error(body.error || res.statusText)
  }
  if (res.status === 204) return undefined as T
  return res.json()
}

const q = (params: Record<string, string | number>) => new URLSearchParams(Object.entries(params).map(([k, v]) => [k, String(v)])).toString()

export const api = {
  info: () => request<{ authRequired: boolean; authenticated: boolean }>('/api/info'),
  login: (password: string) =>
    request<{ token: string }>('/api/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password }),
    }),
  logout: () => request<void>('/api/logout', { method: 'POST' }),
  sources: () => request<Source[]>('/api/sources'),
  list: (s: string, loc: string) => request<ListResponse>(`/api/list?${q({ s, loc })}`),
  book: (s: string, loc: string) => request<BookResponse>(`/api/book?${q({ s, loc })}`),
  recent: () => request<Progress[]>('/api/progress/recent'),
  saveProgress: (p: Progress) =>
    request<void>('/api/progress', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(p),
      keepalive: true,
    }),
  deleteProgress: (s: string, loc: string) => request<void>(`/api/progress?${q({ s, loc })}`, { method: 'DELETE' }),
  coverUrl: (s: string, loc: string) => `/api/cover?${q({ s, loc })}`,
  imageUrl: (s: string, loc: string, width?: number) => `/api/image?${q(width ? { s, loc, w: width } : { s, loc })}`,
}
