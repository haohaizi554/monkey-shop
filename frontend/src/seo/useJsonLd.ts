import { onBeforeUnmount, watch, type Ref } from 'vue'
import { serializeJsonLd } from './product-json-ld'

function readCspNonce(): string | null {
  return (
    document.querySelector('meta[name="csp-nonce"]')?.getAttribute('content') ??
    document.querySelector('script[nonce]')?.getAttribute('nonce') ??
    null
  )
}

export function useJsonLd(id: string, data: Ref<unknown>): void {
  if (typeof document === 'undefined') {
    return
  }

  const render = () => {
    if (data.value == null) {
      document.getElementById(id)?.remove()
      return
    }

    let script = document.getElementById(id)
    if (!script) {
      script = document.createElement('script')
      script.id = id
      script.setAttribute('type', 'application/ld+json')
      const nonce = readCspNonce()
      if (nonce) {
        script.setAttribute('nonce', nonce)
      }
      document.head.append(script)
    }
    script.textContent = serializeJsonLd(data.value)
  }

  watch(data, render, { deep: true, immediate: true })
  onBeforeUnmount(() => document.getElementById(id)?.remove())
}
