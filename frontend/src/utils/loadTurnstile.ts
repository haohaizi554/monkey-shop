export type TurnstileApi = {
  render: (element: HTMLElement, options: Record<string, unknown>) => string
  remove: (widgetId?: string) => void
}

declare global {
  interface Window {
    turnstile?: TurnstileApi
  }
}

let loader: Promise<void> | null = null

export function loadTurnstile(): Promise<void> {
  if (window.turnstile) {
    return Promise.resolve()
  }
  if (loader) {
    return loader
  }

  loader = new Promise((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>('script[data-turnstile-api="true"]')
    const script = existing ?? document.createElement('script')

    const handleLoad = () => {
      if (window.turnstile) {
        resolve()
        return
      }
      script.remove()
      loader = null
      reject(new Error('turnstile unavailable'))
    }
    const handleError = () => {
      script.remove()
      loader = null
      reject(new Error('turnstile unavailable'))
    }

    script.addEventListener('load', handleLoad, { once: true })
    script.addEventListener('error', handleError, { once: true })
    if (!existing) {
      script.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit'
      script.async = true
      script.defer = true
      script.dataset.turnstileApi = 'true'
      document.head.appendChild(script)
    }
  })
  return loader
}
