type Child = Node | string | null | undefined | false

/** 작은 DOM 헬퍼. h('div.card', { onclick }, '내용') */
export function h<K extends keyof HTMLElementTagNameMap>(
  tag: K | `${K}.${string}`,
  props: Partial<Record<string, unknown>> = {},
  ...children: Child[]
): HTMLElementTagNameMap[K] {
  const [name, ...classes] = tag.split('.')
  const el = document.createElement(name as K)
  if (classes.length) el.className = classes.join(' ')
  for (const [key, value] of Object.entries(props)) {
    if (value === undefined || value === null || value === false) continue
    if (key.startsWith('on') && typeof value === 'function') {
      el.addEventListener(key.slice(2), value as EventListener)
    } else if (key === 'style' && typeof value === 'object') {
      Object.assign(el.style, value)
    } else if (key in el && key !== 'list') {
      ;(el as unknown as Record<string, unknown>)[key] = value
    } else {
      el.setAttribute(key, String(value))
    }
  }
  for (const child of children) {
    if (child === null || child === undefined || child === false) continue
    el.append(child)
  }
  return el
}

export function formatSize(bytes: number): string {
  if (bytes >= 1 << 30) return `${(bytes / (1 << 30)).toFixed(1)} GB`
  if (bytes >= 1 << 20) return `${(bytes / (1 << 20)).toFixed(1)} MB`
  if (bytes >= 1 << 10) return `${Math.round(bytes / (1 << 10))} KB`
  return `${bytes} B`
}

export function toast(message: string) {
  const el = h('div.toast', {}, message)
  document.body.append(el)
  setTimeout(() => el.remove(), 3000)
}

export const icons = {
  back: '‹',
  folder: '📁',
  archive: '🗜️',
  image: '🖼️',
  home: '⌂',
  settings: '⚙',
}
