<script setup lang="ts">
import {
  Document,
  Goods,
  Grid,
  House,
  Moon,
  Search,
  ShoppingCart,
  Star,
  Sunny,
  SwitchButton,
  User,
} from '@element-plus/icons-vue'
import { computed, type Component } from 'vue'
import { useI18n } from 'vue-i18n'
import BrandMascot from '@/components/mascot/BrandMascot.vue'
import { useNotify } from '@/composables/useNotify'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'

const props = withDefaults(defineProps<{ compact?: boolean }>(), { compact: false })
const auth = useAuthStore()
const theme = useThemeStore()
const notify = useNotify()
const { locale, t } = useI18n()

interface HeaderLink {
  to: string
  label: string
  icon: Component
}

const primaryLinks = computed<HeaderLink[]>(() => [
  { to: '/shop', label: t('nav.discover'), icon: House },
  { to: '/search#category-filter', label: t('nav.categories'), icon: Grid },
  { to: '/search', label: t('nav.search'), icon: Search },
  { to: '/recommendations', label: t('nav.recommend'), icon: Star },
  { to: '/orders', label: t('nav.orders'), icon: Document },
  { to: '/cart', label: t('nav.cart'), icon: ShoppingCart },
  { to: '/membership', label: t('nav.membership'), icon: Goods },
])

const languageLabel = computed(() => (locale.value === 'zh' ? 'EN' : 'ZH'))
const themeIcon = computed(() => (theme.isDark ? Sunny : Moon))
const themeLabel = computed(() => (theme.isDark ? t('nav.lightTheme') : t('nav.darkTheme')))

function toggleLocale() {
  locale.value = locale.value === 'zh' ? 'en' : 'zh'
  localStorage.setItem('monkeyshop-locale', locale.value)
}

async function logout() {
  try {
    await auth.logout()
  } catch (error) {
    notify.fromApiError(error, 'common.logoutFailed')
  }
}
</script>

<template>
  <header class="app-header consumer-header" :data-compact="props.compact">
    <RouterLink class="brand" to="/shop" :aria-label="$t('nav.homeLabel')">
      <BrandMascot />
      <span>MonkeyShop</span>
    </RouterLink>

    <nav v-if="!props.compact" class="primary-nav" :aria-label="$t('nav.primaryNavigation')">
      <RouterLink
        v-for="link in primaryLinks"
        :key="link.to"
        :to="link.to"
        :aria-label="link.label"
      >
        <component :is="link.icon" class="nav-icon" aria-hidden="true" />
        <span>{{ link.label }}</span>
      </RouterLink>
    </nav>

    <div class="header-actions">
      <RouterLink
        v-if="!props.compact"
        class="icon-button consumer-header__search-shortcut"
        to="/search"
        :aria-label="$t('nav.search')"
      >
        <Search aria-hidden="true" />
      </RouterLink>
      <RouterLink
        v-if="!props.compact"
        class="icon-button consumer-header__cart-shortcut"
        to="/cart"
        :aria-label="$t('nav.cart')"
      >
        <ShoppingCart aria-hidden="true" />
      </RouterLink>
      <button
        class="icon-button consumer-header__theme"
        type="button"
        :aria-label="themeLabel"
        @click="theme.toggle()"
      >
        <component :is="themeIcon" aria-hidden="true" />
      </button>
      <button
        class="text-button language-button"
        type="button"
        :aria-label="$t('nav.switchLanguage')"
        @click="toggleLocale"
      >
        {{ languageLabel }}
      </button>

      <RouterLink
        v-if="props.compact"
        class="secondary-button consumer-header__compact-shop"
        to="/shop"
      >
        <House aria-hidden="true" />
        <span>{{ $t('nav.shop') }}</span>
      </RouterLink>
      <RouterLink
        v-else-if="auth.isLoggedIn"
        class="secondary-button consumer-header__account consumer-header__account-action"
        to="/profile"
        :aria-label="$t('nav.account')"
      >
        <User aria-hidden="true" />
        <span>{{ auth.displayName }}</span>
      </RouterLink>
      <RouterLink v-else class="primary-button consumer-header__account-action" to="/login">
        <User aria-hidden="true" />
        <span>{{ $t('nav.login') }}</span>
      </RouterLink>
      <button
        v-if="!props.compact && auth.isLoggedIn"
        class="icon-button consumer-header__logout"
        type="button"
        :aria-label="$t('nav.logout')"
        @click="logout"
      >
        <SwitchButton aria-hidden="true" />
      </button>
    </div>
  </header>
</template>
