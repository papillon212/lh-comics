import { api, type BookResponse, type Item } from '../api'
import { h } from '../dom'
import { navigate, routes } from '../router'

type Layout = 'paged' | 'vertical' | 'horizontal'
type Direction = 'ltr' | 'rtl'

interface ReaderSettings {
  layout: Layout
  direction: Direction
  background: string
  /** 꺼져 있으면 서버가 화면 폭에 맞춰 줄인 이미지를 받는다(데이터 절약, iOS 메모리 보호). */
  fullQuality: boolean
  tapZones: boolean
}

const SETTINGS_KEY = 'lhc.reader'
const defaults: ReaderSettings = { layout: 'paged', direction: 'ltr', background: '#000000', fullQuality: false, tapZones: true }

function loadSettings(): ReaderSettings {
  try {
    return { ...defaults, ...JSON.parse(localStorage.getItem(SETTINGS_KEY) || '{}') }
  } catch {
    return { ...defaults }
  }
}

export async function renderReader(root: HTMLElement, sourceId: string, loc: string, requestedPage: number) {
  const book = await api.book(sourceId, loc)
  if (book.browse) {
    navigate(routes.browse(sourceId, loc), true)
    return
  }
  if (book.pages.length === 0) {
    root.replaceChildren(
      h('div.error', {}, h('p', {}, '이 위치에는 바로 볼 이미지가 없습니다.'), h('a.button', { href: routes.browse(sourceId, loc) }, '폴더로 열기')),
    )
    return
  }
  return new Reader(root, sourceId, loc, book, requestedPage).start()
}

class Reader {
  private settings = loadSettings()
  private current = 0
  private menuVisible = false
  private saveTimer = 0
  private observer?: IntersectionObserver
  private track!: HTMLElement
  private pageEls: HTMLElement[] = []
  private readonly count: number

  private readonly menu = h('div.reader-menu', {})
  private readonly indicator = h('div.page-indicator', {})
  private readonly container = h('div.reader', {})

  constructor(
    private root: HTMLElement,
    private sourceId: string,
    private loc: string,
    private book: BookResponse,
    requestedPage: number,
  ) {
    this.count = book.pages.length
    const saved = book.progress
    const start = requestedPage >= 0 ? requestedPage : saved && saved.page < saved.pageCount - 1 ? saved.page : 0
    this.current = Math.min(Math.max(0, start), this.count - 1)
  }

  start() {
    document.title = this.book.title
    this.root.replaceChildren(this.container)
    this.build()
    const onKey = (e: KeyboardEvent) => this.onKey(e)
    const onHide = () => this.saveNow()
    window.addEventListener('keydown', onKey)
    window.addEventListener('pagehide', onHide)
    return () => {
      window.removeEventListener('keydown', onKey)
      window.removeEventListener('pagehide', onHide)
      this.observer?.disconnect()
      this.saveNow()
      document.title = 'LH Comics'
    }
  }

  /** 설정이 바뀌면 트랙을 다시 만들고 현재 페이지로 돌아간다. */
  private build() {
    const s = this.settings
    this.observer?.disconnect()
    this.container.style.background = s.background
    this.track = h(`div.track.${s.layout}`, { dir: s.layout !== 'vertical' ? s.direction : 'ltr' })
    this.track.addEventListener('click', (e) => this.onTap(e))

    const width = s.fullQuality ? undefined : Math.min(2400, Math.round((window.innerWidth * (window.devicePixelRatio || 1)) / 100) * 100)
    this.pageEls = this.book.pages.map((page, i) => {
      const near = Math.abs(i - this.current) <= 2
      const img = h('img', {
        src: api.imageUrl(this.sourceId, page.location, width),
        loading: near ? 'eager' : 'lazy',
        decoding: 'async',
        alt: page.name,
        draggable: false,
      })
      const el = h('div.page', { 'data-index': i }, img)
      img.addEventListener('load', () => el.classList.add('loaded'))
      return el
    })
    this.track.append(...this.pageEls, this.endCard())
    this.container.replaceChildren(this.track, this.indicator, this.menu)

    // 화면 중앙선을 지나는 페이지를 현재 페이지로 본다(세로로 아주 긴 웹툰 이미지도 정확).
    const margin = s.layout === 'vertical' ? '-50% 0px -50% 0px' : '0px -50% 0px -50%'
    this.observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) this.setCurrent(Number((entry.target as HTMLElement).dataset.index))
        }
      },
      { root: this.track, rootMargin: margin },
    )
    this.pageEls.forEach((el) => this.observer!.observe(el))
    requestAnimationFrame(() => this.jump(this.current, false))
    this.renderIndicator()
    this.renderMenu()
  }

  private endCard() {
    const next = this.book.next
    return h(
      'div.page.end-card',
      {},
      h('p', {}, '마지막 페이지입니다'),
      next ? h('p.muted', {}, next.name) : null,
      next ? h('button', { onclick: (e: Event) => { e.stopPropagation(); this.openBook(next) } }, '다음 권 읽기') : null,
      h('button.secondary', { onclick: (e: Event) => { e.stopPropagation(); this.close() } }, '닫기'),
    )
  }

  private setCurrent(index: number) {
    if (index === this.current) return
    this.current = index
    this.renderIndicator()
    if (this.menuVisible) this.renderMenu()
    clearTimeout(this.saveTimer)
    this.saveTimer = window.setTimeout(() => this.saveNow(), 600)
  }

  private saveNow() {
    clearTimeout(this.saveTimer)
    api
      .saveProgress({
        sourceId: this.sourceId,
        location: this.loc,
        title: this.book.title,
        page: this.current,
        pageCount: this.count,
        updatedAt: Date.now(),
      })
      .catch(() => {})
  }

  private jump(index: number, smooth = true) {
    const el = this.pageEls[Math.min(Math.max(0, index), this.count - 1)]
    el?.scrollIntoView({ behavior: smooth ? 'smooth' : 'instant', block: 'start', inline: 'start' })
  }

  private step(forward: boolean) {
    const s = this.settings
    if (s.layout === 'paged') {
      const target = this.current + (forward ? 1 : -1)
      if (target >= this.count) {
        this.track.lastElementChild?.scrollIntoView({ behavior: 'smooth', inline: 'start' })
        return
      }
      this.jump(target)
      return
    }
    // 연속 스크롤: 화면의 85%씩 넘겨 앞 화면 끝이 살짝 보이게 한다.
    const vertical = s.layout === 'vertical'
    const amount = (vertical ? this.track.clientHeight : this.track.clientWidth) * 0.85 * (forward ? 1 : -1)
    const sign = !vertical && s.direction === 'rtl' ? -1 : 1
    this.track.scrollBy({ top: vertical ? amount : 0, left: vertical ? 0 : amount * sign, behavior: 'smooth' })
  }

  private onTap(e: MouseEvent) {
    const s = this.settings
    const fx = e.clientX / window.innerWidth
    const fy = e.clientY / window.innerHeight
    let zone = 0
    if (s.tapZones && !this.menuVisible) {
      if (s.layout === 'vertical') zone = fy < 0.3 ? -1 : fy > 0.7 ? 1 : 0
      else if (fx < 0.3) zone = s.direction === 'rtl' ? 1 : -1
      else if (fx > 0.7) zone = s.direction === 'rtl' ? -1 : 1
    }
    if (zone === 0) this.toggleMenu()
    else this.step(zone > 0)
  }

  private onKey(e: KeyboardEvent) {
    const rtl = this.settings.direction === 'rtl' && this.settings.layout !== 'vertical'
    const map: Record<string, boolean | undefined> = {
      ArrowRight: !rtl,
      ArrowLeft: rtl,
      ArrowDown: true,
      ArrowUp: false,
      ' ': true,
      PageDown: true,
      PageUp: false,
    }
    const forward = map[e.key]
    if (forward === undefined) {
      if (e.key === 'Escape') this.close()
      return
    }
    e.preventDefault()
    this.step(forward)
  }

  private toggleMenu() {
    this.menuVisible = !this.menuVisible
    this.renderMenu()
  }

  private renderIndicator() {
    this.indicator.textContent = `${this.current + 1} / ${this.count}`
  }

  private renderMenu() {
    this.menu.classList.toggle('visible', this.menuVisible)
    this.indicator.classList.toggle('hidden', this.menuVisible)
    if (!this.menuVisible) {
      this.menu.replaceChildren()
      return
    }
    const rtl = this.settings.direction === 'rtl' && this.settings.layout !== 'vertical'
    const slider = h('input', { type: 'range', min: 0, max: this.count - 1, value: this.current, dir: rtl ? 'rtl' : 'ltr' })
    const label = h('div.page-label', {}, `${this.current + 1} / ${this.count}`)
    slider.addEventListener('input', () => (label.textContent = `${Number(slider.value) + 1} / ${this.count}`))
    slider.addEventListener('change', () => this.jump(Number(slider.value), false))

    const { previous, next } = this.book
    this.menu.replaceChildren(
      h(
        'div.menu-top',
        {},
        h('button.icon', { onclick: () => this.close(), title: '닫기' }, '‹'),
        h('div.grow.ellipsis', {}, this.book.title),
        h('button.icon', { onclick: () => this.showSettings(), title: '보기 설정' }, '⚙'),
      ),
      h(
        'div.menu-bottom',
        {},
        label,
        h(
          'div.slider-row',
          { dir: rtl ? 'rtl' : 'ltr' },
          h('button.icon', { disabled: !previous, onclick: () => previous && this.openBook(previous), title: '이전 권' }, '⏮'),
          slider,
          h('button.icon', { disabled: !next, onclick: () => next && this.openBook(next), title: '다음 권' }, '⏭'),
        ),
      ),
    )
  }

  private showSettings() {
    const s = this.settings
    const update = (patch: Partial<ReaderSettings>) => {
      this.settings = { ...this.settings, ...patch }
      localStorage.setItem(SETTINGS_KEY, JSON.stringify(this.settings))
      sheet.remove()
      this.build()
      this.showSettings()
    }
    const choice = <T extends string>(label: string, value: T, options: [T, string][], onPick: (v: T) => void) =>
      h(
        'div.setting',
        {},
        h('div.setting-label', {}, label),
        h('div.chips', {}, ...options.map(([v, text]) => h(`button.chip${v === value ? '.selected' : ''}`, { onclick: () => onPick(v) }, text))),
      )
    const toggle = (label: string, checked: boolean, onChange: (v: boolean) => void) => {
      const input = h('input', { type: 'checkbox', checked })
      input.addEventListener('change', () => onChange(input.checked))
      return h('label.setting.toggle', {}, h('span', {}, label), input)
    }

    const sheet = h(
      'div.sheet-backdrop',
      { onclick: (e: Event) => e.target === sheet && sheet.remove() },
      h(
        'div.sheet',
        {},
        choice<Layout>('보기 방식', s.layout, [['paged', '한 장씩'], ['vertical', '세로 스크롤 (웹툰)'], ['horizontal', '가로 스크롤']], (v) => update({ layout: v })),
        s.layout !== 'vertical'
          ? choice<Direction>('넘기는 방향', s.direction, [['ltr', '왼쪽 → 오른쪽'], ['rtl', '오른쪽 → 왼쪽']], (v) => update({ direction: v }))
          : null,
        choice('배경색', s.background, [['#000000', '검정'], ['#202020', '어두운 회색'], ['#f5f0e6', '종이'], ['#ffffff', '흰색']], (v) => update({ background: v })),
        toggle('원본 화질 (데이터를 더 씀)', s.fullQuality, (v) => update({ fullQuality: v })),
        toggle('화면 가장자리 탭으로 넘기기', s.tapZones, (v) => update({ tapZones: v })),
        h('button.secondary', { onclick: () => sheet.remove() }, '닫기'),
      ),
    )
    this.container.append(sheet)
  }

  private openBook(item: Item) {
    this.saveNow()
    navigate(routes.read(this.sourceId, item.location, 0), true)
  }

  /** 다음 권으로 넘어오며 기록을 교체했으므로 뒤로 가기 대신 항상 상위 폴더로 간다. */
  private close() {
    navigate(this.book.parent !== null ? routes.browse(this.sourceId, this.book.parent) : routes.home())
  }
}
