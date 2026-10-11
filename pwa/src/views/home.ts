import { api } from '../api'
import { h } from '../dom'
import { navigate, routes } from '../router'

export async function renderHome(root: HTMLElement) {
  const [sources, recent] = await Promise.all([api.sources(), api.recent()])

  const isStandalone = matchMedia('(display-mode: standalone)').matches || (navigator as { standalone?: boolean }).standalone
  const installHint =
    !isStandalone && /iPhone|iPad/.test(navigator.userAgent)
      ? h('div.hint', {}, '공유 버튼 → "홈 화면에 추가"를 누르면 앱처럼 전체 화면으로 쓸 수 있습니다.')
      : null

  const recentRow = recent.length
    ? h(
        'section',
        {},
        h('h2', {}, '최근 읽은 책'),
        h(
          'div.recent',
          {},
          ...recent
            .filter((p) => sources.some((s) => s.id === p.sourceId))
            .map((p) =>
              h(
                'a.recent-card',
                { href: routes.read(p.sourceId, p.location, p.page) },
                h('div.cover', {}, h('img', { src: api.coverUrl(p.sourceId, p.location), loading: 'lazy', alt: '' })),
                h('div.bar', {}, h('div', { style: { width: `${((p.page + 1) / Math.max(1, p.pageCount)) * 100}%` } })),
                h('div.title', {}, p.title),
                h('div.muted.small', {}, `${p.page + 1} / ${p.pageCount}`),
              ),
            ),
        ),
      )
    : null

  root.replaceChildren(
    h(
      'header.topbar',
      {},
      h('h1', {}, 'LH Comics'),
      h(
        'button.icon',
        {
          title: '로그아웃',
          onclick: async () => {
            await api.logout()
            navigate('#/login')
          },
        },
        '⎋',
      ),
    ),
    h(
      'main.home',
      {},
      installHint,
      recentRow,
      h('h2', {}, '라이브러리'),
      h(
        'ul.list',
        {},
        ...sources.map((s) => h('li', {}, h('a', { href: routes.browse(s.id, '/') }, h('span.icon-cell', {}, '📚'), h('span', {}, s.name)))),
      ),
    ),
  )
}
