import { api, type Item } from '../api'
import { formatSize, h, icons, toast } from '../dom'
import { navigate, routes } from '../router'

const GRID_KEY = 'lhc.grid'

export async function renderBrowse(root: HTMLElement, sourceId: string, loc: string) {
  const data = await api.list(sourceId, loc)
  let grid = localStorage.getItem(GRID_KEY) !== 'false'

  // 압축 파일은 안에 다른 압축이 있으면 폴더로, 아니면 바로 뷰어로 연다(서버가 판단).
  async function open(item: Item) {
    if (item.kind === 'DIRECTORY') return navigate(routes.browse(sourceId, item.location))
    if (item.kind === 'IMAGE') {
      const book = await api.book(sourceId, loc)
      const index = Math.max(0, book.pages.findIndex((p) => p.location === item.location))
      return navigate(routes.read(sourceId, loc, index))
    }
    try {
      const book = await api.book(sourceId, item.location)
      navigate(book.browse ? routes.browse(sourceId, item.location) : routes.read(sourceId, item.location))
    } catch (e) {
      toast((e as Error).message)
    }
  }

  const hasImages = data.items.some((i) => i.kind === 'IMAGE')
  const body = h('main.browse', {})

  function draw() {
    body.replaceChildren(
      data.items.length === 0
        ? h('p.empty', {}, '이미지나 압축 파일이 없습니다.')
        : grid
          ? h('div.grid', {}, ...data.items.map((item) => gridCell(item)))
          : h('ul.list', {}, ...data.items.map((item) => listRow(item))),
    )
  }

  function badge(item: Item) {
    const p = item.progress
    if (!p) return null
    const done = p.page >= p.pageCount - 1
    return done ? h('span.done', { title: '다 읽음' }, '✓') : h('div.bar.overlay', {}, h('div', { style: { width: `${((p.page + 1) / p.pageCount) * 100}%` } }))
  }

  function gridCell(item: Item) {
    return h(
      'button.cell',
      { onclick: () => open(item) },
      h(
        'div.cover',
        {},
        h('span.placeholder', {}, item.kind === 'DIRECTORY' ? icons.folder : item.kind === 'ARCHIVE' ? icons.archive : icons.image),
        h('img', { src: api.coverUrl(sourceId, item.location), loading: 'lazy', alt: '', onerror: (e: Event) => (e.target as HTMLElement).remove() }),
        badge(item),
      ),
      h('div.title', {}, item.name),
    )
  }

  function listRow(item: Item) {
    const meta = [item.size > 0 ? formatSize(item.size) : '', item.progress ? `${item.progress.page + 1}/${item.progress.pageCount}쪽` : '']
      .filter(Boolean)
      .join(' · ')
    return h(
      'li',
      {},
      h(
        'button.row',
        { onclick: () => open(item) },
        h('span.icon-cell', {}, item.kind === 'DIRECTORY' ? icons.folder : item.kind === 'ARCHIVE' ? icons.archive : icons.image),
        h('span.grow', {}, h('div', {}, item.name), meta ? h('div.muted.small', {}, meta) : null),
      ),
    )
  }

  const back = data.parent !== null ? routes.browse(sourceId, data.parent) : routes.home()
  root.replaceChildren(
    h(
      'header.topbar',
      {},
      h('a.icon', { href: back, title: '뒤로' }, icons.back),
      h('div.grow', {}, h('h1.ellipsis', {}, data.name), h('div.muted.small.ellipsis', {}, data.location)),
      h(
        'button.icon',
        {
          title: '보기 방식',
          onclick: () => {
            grid = !grid
            localStorage.setItem(GRID_KEY, String(grid))
            draw()
          },
        },
        '▦',
      ),
      h('a.icon', { href: routes.home(), title: '홈' }, icons.home),
    ),
    body,
  )
  if (hasImages || loc.includes('!/')) root.append(h('a.fab', { href: routes.read(sourceId, loc) }, '▶ 이 폴더 읽기'))
  draw()
}
