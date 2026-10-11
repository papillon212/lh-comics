export function navigate(hash: string, replace = false) {
  if (replace) location.replace(hash)
  else location.hash = hash
}

export const routes = {
  home: () => '#/',
  browse: (s: string, loc: string) => `#/browse?${new URLSearchParams({ s, loc })}`,
  read: (s: string, loc: string, page = -1) => `#/read?${new URLSearchParams({ s, loc, p: String(page) })}`,
}
