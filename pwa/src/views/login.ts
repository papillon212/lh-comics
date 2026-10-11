import { api } from '../api'
import { h } from '../dom'
import { navigate, routes } from '../router'

export async function renderLogin(root: HTMLElement) {
  const info = await api.info()
  if (!info.authRequired || info.authenticated) {
    navigate(routes.home(), true)
    return
  }
  const input = h('input', { type: 'password', placeholder: '서버 비밀번호', autocomplete: 'current-password' })
  const message = h('p.muted', {}, '')
  const form = h(
    'form.login',
    {
      onsubmit: async (e: Event) => {
        e.preventDefault()
        try {
          await api.login(input.value)
          navigate(routes.home(), true)
        } catch (err) {
          message.textContent = (err as Error).message
        }
      },
    },
    h('h1', {}, 'LH Comics'),
    h('p.muted', {}, 'NAS에 설치한 LH Comics 서버에 로그인하세요.'),
    input,
    h('button', { type: 'submit' }, '로그인'),
    message,
  )
  root.replaceChildren(form)
  input.focus()
}
