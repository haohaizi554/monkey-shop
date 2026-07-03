<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'

const auth = useAuthStore()
const theme = useThemeStore()
const { locale, t } = useI18n()

const languageLabel = computed(() => (locale.value === 'zh' ? 'EN' : 'ZH'))

function toggleLocale() {
  locale.value = locale.value === 'zh' ? 'en' : 'zh'
  localStorage.setItem('monkeyshop-locale', locale.value)
}
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <RouterLink class="brand" to="/shop" aria-label="MonkeyShop">
        <span class="brand-mark" aria-hidden="true">MS</span>
        <span>MonkeyShop</span>
      </RouterLink>

      <nav class="primary-nav" aria-label="Primary">
        <RouterLink to="/shop">
          <span class="nav-mark" aria-hidden="true">S</span>
          <span>{{ t('nav.shop') }}</span>
        </RouterLink>
        <RouterLink v-if="auth.isLoggedIn" to="/orders">
          <span class="nav-mark" aria-hidden="true">O</span>
          <span>{{ t('nav.orders') }}</span>
        </RouterLink>
        <RouterLink v-if="auth.isLoggedIn" to="/cart">
          <span class="nav-mark" aria-hidden="true">C</span>
          <span>{{ t('nav.cart') }}</span>
        </RouterLink>
        <RouterLink v-if="auth.isLoggedIn" to="/profile">
          <span class="nav-mark" aria-hidden="true">P</span>
          <span>{{ t('nav.profile') }}</span>
        </RouterLink>
        <RouterLink v-if="auth.isAdmin" to="/admin">
          <span class="nav-mark" aria-hidden="true">A</span>
          <span>{{ t('nav.admin') }}</span>
        </RouterLink>
        <RouterLink v-if="auth.isAdmin" to="/inventory">
          <span class="nav-mark" aria-hidden="true">I</span>
          <span>{{ t('nav.inventory') }}</span>
        </RouterLink>
        <RouterLink v-if="auth.isAdmin" to="/marketing">
          <span class="nav-mark" aria-hidden="true">M</span>
          <span>{{ t('nav.marketing') }}</span>
        </RouterLink>
      </nav>

      <div class="header-actions">
        <button
          class="icon-button"
          type="button"
          :aria-label="theme.isDark ? 'Light theme' : 'Dark theme'"
          @click="theme.toggle()"
        >
          <span aria-hidden="true">{{ theme.isDark ? 'L' : 'D' }}</span>
        </button>
        <button
          class="text-button"
          type="button"
          aria-label="Switch language"
          @click="toggleLocale"
        >
          {{ languageLabel }}
        </button>
        <button
          v-if="auth.isLoggedIn"
          class="secondary-button"
          type="button"
          @click="auth.logout()"
        >
          {{ t('nav.logout') }}
        </button>
        <RouterLink v-else class="primary-button" to="/login">
          {{ t('nav.login') }}
        </RouterLink>
      </div>
    </header>

    <main class="app-main">
      <slot />
    </main>
  </div>
</template>
