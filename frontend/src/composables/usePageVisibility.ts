import { getCurrentScope, onScopeDispose, readonly, ref, type Ref } from 'vue'

export interface PageVisibilityController {
  isVisible: Readonly<Ref<boolean>>
  start(callback: () => void | Promise<void>, intervalMs: number): void
  stop(): void
}

export function usePageVisibility(): PageVisibilityController {
  const isVisible = ref(document.visibilityState === 'visible')
  let timer: ReturnType<typeof setInterval> | undefined
  let callback: (() => void | Promise<void>) | undefined
  let intervalMs = 0
  let active = false
  let running = false

  function clearTimer() {
    if (timer !== undefined) {
      clearInterval(timer)
      timer = undefined
    }
  }

  async function invoke() {
    if (!active || !isVisible.value || !callback || running) {
      return
    }
    running = true
    try {
      await callback()
    } finally {
      running = false
    }
  }

  function schedule() {
    clearTimer()
    if (!active || !isVisible.value || intervalMs <= 0) {
      return
    }
    timer = setInterval(() => void invoke(), intervalMs)
  }

  function onVisibilityChange() {
    isVisible.value = document.visibilityState === 'visible'
    if (!isVisible.value) {
      clearTimer()
      return
    }
    if (active) {
      void invoke()
      schedule()
    }
  }

  function start(nextCallback: () => void | Promise<void>, nextIntervalMs: number) {
    callback = nextCallback
    intervalMs = Math.max(1, nextIntervalMs)
    active = true
    schedule()
  }

  function stop() {
    active = false
    clearTimer()
  }

  document.addEventListener('visibilitychange', onVisibilityChange)
  if (getCurrentScope()) {
    onScopeDispose(() => {
      stop()
      document.removeEventListener('visibilitychange', onVisibilityChange)
    })
  }

  return { isVisible: readonly(isVisible), start, stop }
}
