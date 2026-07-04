import { defineStore } from 'pinia'
import { ref } from 'vue'

const storageKey = 'monkeyshop-theme'
const darkClass = 'dark'

function initialTheme(): boolean {
  if (typeof window === 'undefined') {
    return false
  }
  const stored = localStorage.getItem(storageKey)
  if (stored === 'dark') {
    return true
  }
  if (stored === 'light') {
    return false
  }
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false
}

function applyTheme(dark: boolean) {
  if (typeof document === 'undefined') {
    return
  }
  document.documentElement.classList.toggle(darkClass, dark)
  localStorage.setItem(storageKey, dark ? 'dark' : 'light')
}

export const useThemeStore = defineStore('theme', () => {
  const isDark = ref(initialTheme())
  applyTheme(isDark.value)

  function toggle() {
    isDark.value = !isDark.value
    applyTheme(isDark.value)
  }

  return { isDark, toggle }
})