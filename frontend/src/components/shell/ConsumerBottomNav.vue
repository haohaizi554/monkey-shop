<script setup lang="ts">
import { Document, House, Search, ShoppingCart, User } from '@element-plus/icons-vue'
import { computed, type Component } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const { t } = useI18n()

interface BottomLink {
  to: string
  label: string
  icon: Component
}

const links = computed<BottomLink[]>(() => {
  const items: BottomLink[] = [
    { to: '/shop', label: t('nav.shop'), icon: House },
    { to: '/search', label: t('nav.search'), icon: Search },
  ]
  if (auth.isLoggedIn) {
    items.push(
      { to: '/cart', label: t('nav.cart'), icon: ShoppingCart },
      { to: '/orders', label: t('nav.orders'), icon: Document },
      { to: '/profile', label: t('nav.profile'), icon: User },
    )
  } else {
    items.push({ to: '/login', label: t('nav.login'), icon: User })
  }
  return items
})
</script>

<template>
  <nav class="consumer-bottom-nav" :aria-label="$t('nav.mobilePrimary')">
    <RouterLink v-for="link in links" :key="link.to" :to="link.to">
      <component :is="link.icon" aria-hidden="true" />
      <span>{{ link.label }}</span>
    </RouterLink>
  </nav>
</template>
