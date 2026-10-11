import './style.css'
import { AuthError } from './api'
import { h } from './dom'
import { navigate, routes } from './router'
import { renderBrowse } from './views/browse'
import { renderHome } from './views/home'
import { renderLogin } from './views/login'
import { renderReader } from './views/reader'

const app = document.getElementById('app')!

/** 화면이 바뀔 때 이전 화면의 정리 함수(이벤트 해제, 진행률 저장 등)를 부른다. */
let cleanup: (() => void) | void

async function render() {
  cleanup?.()
  cleanup = undefined
  const [path, query = ''] = location.hash.replace(/^#/, '').split('?')
  const params = new URLSearchParams(query)
  app.replaceChildren(h('div.loading', {}, '불러오는 중…'))
  try {
    switch (path) {
      case '/login':
        cleanup = await renderLogin(app)
        break
      case '/browse':
        cleanup = await renderBrowse(app, params.get('s')!, params.get('loc') || '/')
        break
      case '/read':
        cleanup = await renderReader(app, params.get('s')!, params.get('loc')!, Number(params.get('p') ?? -1))
        break
      default:
        cleanup = await renderHome(app)
    }
  } catch (e) {
    if (e instanceof AuthError) {
      navigate('#/login', true)
      return
    }
    app.replaceChildren(
      h(
        'div.error',
        {},
        h('p', {}, `오류: ${(e as Error).message}`),
        h('button', { onclick: () => render() }, '다시 시도'),
        h('button.secondary', { onclick: () => navigate(routes.home()) }, '홈으로'),
      ),
    )
  }
}

window.addEventListener('hashchange', render)
render()

if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('/sw.js').catch(() => {})
}
