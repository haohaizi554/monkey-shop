<script setup lang="ts">
import {
  Avatar,
  Box,
  CreditCard,
  DataAnalysis,
  Discount,
  Document,
  Goods,
  House,
  Moon,
  OfficeBuilding,
  Search,
  Setting,
  Star,
  Sunny,
  SwitchButton,
  User,
  Van,
  Warning,
} from '@element-plus/icons-vue'
import { computed, type Component } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'

const auth = useAuthStore()
const theme = useThemeStore()
const { locale, t } = useI18n()

const languageLabel = computed(() => (locale.value === 'zh' ? 'EN' : 'ZH'))
const themeIcon = computed(() => (theme.isDark ? Sunny : Moon))

interface NavLink {
  to: string
  label: string
  icon: Component
}

const navLinks = computed<NavLink[]>(() => {
  const links: NavLink[] = [
    { to: '/shop', label: t('nav.shop'), icon: House },
    { to: '/search', label: t('nav.search'), icon: Search },
  ]
  if (auth.isLoggedIn) {
    links.push(
      { to: '/recommendations', label: t('nav.recommend'), icon: Star },
      { to: '/orders', label: t('nav.orders'), icon: Document },
      { to: '/cart', label: t('nav.cart'), icon: Goods },
      { to: '/payment', label: t('nav.payment'), icon: CreditCard },
      { to: '/logistics', label: t('nav.logistics'), icon: Van },
      { to: '/membership', label: t('nav.membership'), icon: Avatar },
      { to: '/profile', label: t('nav.profile'), icon: User },
    )
  }
  if (auth.isAdmin) {
    links.push(
      { to: '/admin', label: t('nav.admin'), icon: Setting },
      { to: '/inventory', label: t('nav.inventory'), icon: Box },
      { to: '/marketing', label: t('nav.marketing'), icon: Discount },
      { to: '/risk', label: t('nav.risk'), icon: Warning },
      { to: '/dashboard', label: t('nav.dashboard'), icon: DataAnalysis },
      { to: '/tenants', label: t('nav.tenants'), icon: OfficeBuilding },
    )
  }
  return links
})

function toggleLocale() {
  locale.value = locale.value === 'zh' ? 'en' : 'zh'
  localStorage.setItem('monkeyshop-locale', locale.value)
}
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <RouterLink class="brand" to="/shop" aria-label="MonkeyShop">
        <span class="brand-mark" aria-hidden="true">
          <Goods />
        </span>
        <span>MonkeyShop</span>
      </RouterLink>

      <nav class="primary-nav" aria-label="Primary">
        <RouterLink v-for="link in navLinks" :key="link.to" :to="link.to" :aria-label="link.label">
          <span class="nav-mark" aria-hidden="true">
            <component :is="link.icon" />
          </span>
          <span class="nav-label">{{ link.label }}</span>
        </RouterLink>
      </nav>

      <div class="header-actions">
        <button
          class="icon-button"
          type="button"
          :aria-label="theme.isDark ? 'Light theme' : 'Dark theme'"
          @click="theme.toggle()"
        >
          <component :is="themeIcon" class="action-icon" aria-hidden="true" />
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
          <SwitchButton class="action-icon" aria-hidden="true" />
          <span>{{ t('nav.logout') }}</span>
        </button>
        <RouterLink v-else class="primary-button" to="/login">
          <User class="action-icon" aria-hidden="true" />
          <span>{{ t('nav.login') }}</span>
        </RouterLink>
      </div>
    </header>

    <main class="app-main">
      <slot />
    </main>
  </div>
</template>