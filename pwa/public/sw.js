// 앱 화면(셸)과 표지 썸네일만 캐시한다. 만화 페이지는 용량이 커서 브라우저 HTTP 캐시에 맡긴다.
const SHELL = 'lhc-shell-v1'
const COVERS = 'lhc-covers-v1'
const MAX_COVERS = 500

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(SHELL).then((c) => c.addAll(['/', '/manifest.webmanifest'])))
  self.skipWaiting()
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== SHELL && k !== COVERS).map((k) => caches.delete(k)))),
  )
  self.clients.claim()
})

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url)
  if (event.request.method !== 'GET' || url.origin !== location.origin) return

  if (url.pathname === '/api/cover') {
    event.respondWith(coverFirst(event.request))
    return
  }
  if (url.pathname.startsWith('/api/')) return

  // 셸: 네트워크 우선, 오프라인이면 캐시
  event.respondWith(
    fetch(event.request)
      .then((res) => {
        if (res.ok) {
          const copy = res.clone()
          caches.open(SHELL).then((c) => c.put(event.request, copy))
        }
        return res
      })
      .catch(() => caches.match(event.request).then((r) => r || caches.match('/'))),
  )
})

async function coverFirst(request) {
  const cache = await caches.open(COVERS)
  const hit = await cache.match(request)
  if (hit) return hit
  const res = await fetch(request)
  if (res.status === 200) {
    await cache.put(request, res.clone())
    const keys = await cache.keys()
    for (const k of keys.slice(0, Math.max(0, keys.length - MAX_COVERS))) await cache.delete(k)
  }
  return res
}
