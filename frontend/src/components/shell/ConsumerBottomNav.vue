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
  return [
    { to: '/shop', label: t('nav.discover'), icon: House },
    { to: '/search', label: t('nav.search'), icon: Search },
    { to: '/cart', label: t('nav.cart'), icon: ShoppingCart },
    { to: '/orders', label: t('nav.orders'), icon: Document },
    {
      to: auth.isLoggedIn ? '/profile' : '/login',
      label: t('nav.me'),
      icon: User,
    },
  ]
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
