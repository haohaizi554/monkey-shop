<script setup lang="ts">
import { Menu, Moon, Sunny, SwitchButton, User } from '@element-plus/icons-vue'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { useNotify } from '@/composables/useNotify'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'

defineProps<{ navigationOpen: boolean }>()
const emit = defineEmits<{ toggleNavigation: [] }>()
const route = useRoute()
const auth = useAuthStore()
const theme = useThemeStore()
const notify = useNotify()
const { locale, t } = useI18n()
const navigationTrigger = ref<HTMLButtonElement>()

const pageTitle = computed(() => t(route.meta.titleKey || 'nav.admin'))
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

function focusNavigationTrigger() {
  navigationTrigger.value?.focus()
}

defineExpose({ focusNavigationTrigger })
</script>

<template>
  <header class="app-header admin-topbar">
    <button
      ref="navigationTrigger"
      class="icon-button admin-topbar__menu"
      type="button"
      :aria-label="$t('nav.openNavigation')"
      :aria-expanded="navigationOpen"
      aria-controls="admin-navigation"
      @click="emit('toggleNavigation')"
    >
      <Menu aria-hidden="true" />
    </button>
    <div class="admin-topbar__title">
      <nav :aria-label="$t('nav.breadcrumb')">
        <RouterLink to="/admin">{{ $t('nav.admin') }}</RouterLink>
        <span aria-hidden="true">/</span>
        <span aria-current="page">{{ pageTitle }}</span>
      </nav>
      <h1>{{ pageTitle }}</h1>
    </div>
    <div class="header-actions">
      <button class="icon-button" type="button" :aria-label="themeLabel" @click="theme.toggle()">
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
      <RouterLink class="admin-account" to="/profile" :aria-label="$t('nav.account')">
        <User aria-hidden="true" />
        <span>{{ auth.displayName }}</span>
      </RouterLink>
      <button class="icon-button" type="button" :aria-label="$t('nav.logout')" @click="logout">
        <SwitchButton aria-hidden="true" />
      </button>
    </div>
  </header>
</template>
