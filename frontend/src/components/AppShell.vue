<script setup lang="ts">
import { Box, Moon, ShoppingBag, Sunny, User, Tickets, DataBoard } from '@element-plus/icons-vue'
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
  <el-container class="app-shell">
    <el-header class="app-header">
      <RouterLink class="brand" to="/shop" aria-label="MonkeyShop">
        <el-icon><Box /></el-icon>
        <span>MonkeyShop</span>
      </RouterLink>

      <nav class="primary-nav" aria-label="Primary">
        <RouterLink to="/shop">
          <el-icon><ShoppingBag /></el-icon>
          <span>{{ t('nav.shop') }}</span>
        </RouterLink>
        <RouterLink v-if="auth.isLoggedIn" to="/orders">
          <el-icon><Tickets /></el-icon>
          <span>{{ t('nav.orders') }}</span>
        </RouterLink>
        <RouterLink v-if="auth.isLoggedIn" to="/profile">
          <el-icon><User /></el-icon>
          <span>{{ t('nav.profile') }}</span>
        </RouterLink>
        <RouterLink v-if="auth.isAdmin" to="/admin">
          <el-icon><DataBoard /></el-icon>
          <span>{{ t('nav.admin') }}</span>
        </RouterLink>
      </nav>

      <div class="header-actions">
        <el-button
          text
          circle
          :aria-label="theme.isDark ? 'Light theme' : 'Dark theme'"
          @click="theme.toggle()"
        >
          <el-icon><component :is="theme.isDark ? Sunny : Moon" /></el-icon>
        </el-button>
        <el-button text @click="toggleLocale">
          {{ languageLabel }}
        </el-button>
        <el-button v-if="auth.isLoggedIn" type="primary" plain @click="auth.logout()">
          {{ t('nav.logout') }}
        </el-button>
        <RouterLink v-else to="/login">
          <el-button type="primary">
            {{ t('nav.login') }}
          </el-button>
        </RouterLink>
      </div>
    </el-header>

    <el-main class="app-main">
      <slot />
    </el-main>
  </el-container>
</template>
