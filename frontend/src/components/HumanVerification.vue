<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps<{
  action: string
  siteKey?: string
  modelValue?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

type TurnstileApi = {
  render: (element: HTMLElement, options: Record<string, unknown>) => string
  remove: (widgetId?: string) => void
}

declare global {
  interface Window {
    turnstile?: TurnstileApi
  }
}

const widgetHost = ref<HTMLElement | null>(null)
let widgetId: string | undefined
let loader: Promise<void> | null = null

function loadTurnstile(): Promise<void> {
  if (window.turnstile) {
    return Promise.resolve()
  }
  if (loader) {
    return loader
  }
  loader = new Promise((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>('script[data-turnstile-api="true"]')
    if (existing) {
      existing.addEventListener('load', () => resolve(), { once: true })
      existing.addEventListener('error', () => reject(new Error('turnstile unavailable')), {
        once: true,
      })
      return
    }
    const script = document.createElement('script')
    script.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit'
    script.async = true
    script.defer = true
    script.dataset.turnstileApi = 'true'
    script.addEventListener('load', () => resolve(), { once: true })
    script.addEventListener('error', () => reject(new Error('turnstile unavailable')), {
      once: true,
    })
    document.head.appendChild(script)
  })
  return loader
}

async function renderWidget() {
  if (!props.siteKey || !widgetHost.value) {
    return
  }
  await loadTurnstile()
  await nextTick()
  if (!window.turnstile || !widgetHost.value) {
    return
  }
  if (widgetId) {
    window.turnstile.remove(widgetId)
  }
  widgetHost.value.replaceChildren()
  widgetId = window.turnstile.render(widgetHost.value, {
    sitekey: props.siteKey,
    action: props.action,
    callback: (token: string) => emit('update:modelValue', token),
    'expired-callback': () => emit('update:modelValue', ''),
    'error-callback': () => emit('update:modelValue', ''),
  })
}

onMounted(() => {
  void renderWidget()
})

onBeforeUnmount(() => {
  if (widgetId && window.turnstile) {
    window.turnstile.remove(widgetId)
  }
})

watch(
  () => [props.siteKey, props.action],
  () => {
    emit('update:modelValue', '')
    void renderWidget()
  },
)
</script>

<template>
  <div class="turnstile-box">
    <div ref="widgetHost" class="turnstile-widget" />
  </div>
</template>
