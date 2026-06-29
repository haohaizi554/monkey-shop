export function readCookie(name: string): string | null {
  const row = document.cookie.split('; ').find((item) => item.startsWith(`${name}=`))
  return row ? decodeURIComponent(row.split('=').slice(1).join('=')) : null
}

export function csrfHeader(): Record<string, string> {
  const token = readCookie('XSRF-TOKEN')
  return token ? { 'X-XSRF-TOKEN': token } : {}
}
