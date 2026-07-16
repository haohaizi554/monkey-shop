<script setup lang="ts">
import {
  Box,
  DataAnalysis,
  Discount,
  Menu,
  Moon,
  OfficeBuilding,
  Search,
  Setting,
  Sunny,
  SwitchButton,
  User,
  Warning,
} from '@element-plus/icons-vue'
import type { InputInstance } from 'element-plus'
import { useEventListener } from '@vueuse/core'
import { computed, nextTick, ref, type Component } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { useNotify } from '@/composables/useNotify'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'

interface CommandRoute {
  to: string
  label: string
  group: string
  icon: Component
}

defineProps<{ navigationOpen: boolean }>()
const emit = defineEmits<{ toggleNavigation: [] }>()
const route = useRoute()
const auth = useAuthStore()
const theme = useThemeStore()
const notify = useNotify()
const { locale, t } = useI18n()
const navigationTrigger = ref<HTMLButtonElement>()
const commandInput = ref<InputInstance>()
const commandOpen = ref(false)
const commandQuery = ref('')

const pageTitle = computed(() => t(route.meta.titleKey || 'nav.admin'))
const languageLabel = computed(() => (locale.value === 'zh' ? 'EN' : 'ZH'))
const themeIcon = computed(() => (theme.isDark ? Sunny : Moon))
const themeLabel = computed(() => (theme.isDark ? t('nav.lightTheme') : t('nav.darkTheme')))
const commandRoutes = computed<CommandRoute[]>(() => [
  { to: '/admin', label: t('nav.admin'), group: t('nav.adminStore'), icon: Setting },
  { to: '/inventory', label: t('nav.inventory'), group: t('nav.adminStore'), icon: Box },
  { to: '/marketing', label: t('nav.marketing'), group: t('nav.adminStore'), icon: Discount },
  { to: '/dashboard', label: t('nav.dashboard'), group: t('nav.adminOps'), icon: DataAnalysis },
  { to: '/risk', label: t('nav.riskReview'), group: t('nav.adminOps'), icon: Warning },
  { to: '/tenants', label: t('nav.tenants'), group: t('nav.adminOps'), icon: OfficeBuilding },
])
const filteredCommandRoutes = computed(() => {
  const query = commandQuery.value.trim().toLocaleLowerCase()
  if (!query) return commandRoutes.value
  return commandRoutes.value.filter((item) =>
    `${item.label} ${item.group}`.toLocaleLowerCase().includes(query),
  )
})

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

function openCommand() {
  commandOpen.value = true
}

function closeCommand() {
  commandOpen.value = false
  commandQuery.value = ''
}

async function focusCommandInput() {
  await nextTick()
  commandInput.value?.focus()
}

function onGlobalKeydown(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLocaleLowerCase() === 'k') {
    event.preventDefault()
    openCommand()
  }
}

function focusNavigationTrigger() {
  navigationTrigger.value?.focus()
}

useEventListener(window, 'keydown', onGlobalKeydown)

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

    <div class="admin-topbar__context">
      <nav :aria-label="$t('nav.breadcrumb')">
        <RouterLink to="/admin">{{ $t('nav.operationsWorkspace') }}</RouterLink>
        <span aria-hidden="true">/</span>
        <span aria-current="page">{{ pageTitle }}</span>
      </nav>
    </div>

    <button
      class="admin-command-trigger"
      type="button"
      :aria-label="$t('nav.searchWorkspace')"
      @click="openCommand"
    >
      <Search aria-hidden="true" />
      <span>{{ $t('nav.searchWorkspace') }}</span>
      <kbd>Ctrl K</kbd>
    </button>

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

    <el-dialog
      v-model="commandOpen"
      class="admin-command-dialog"
      :title="$t('nav.goToWorkspace')"
      width="min(560px, calc(100vw - 32px))"
      append-to-body
      destroy-on-close
      @opened="focusCommandInput"
      @closed="commandQuery = ''"
    >
      <div class="admin-command-dialog__body">
        <el-input
          ref="commandInput"
          v-model="commandQuery"
          type="search"
          :prefix-icon="Search"
          :aria-label="$t('nav.searchWorkspace')"
          :placeholder="$t('nav.searchWorkspaceHint')"
          clearable
        />
        <nav class="admin-command-results" :aria-label="$t('nav.goToWorkspace')">
          <RouterLink
            v-for="item in filteredCommandRoutes"
            :key="item.to"
            :to="item.to"
            :aria-label="item.label"
            @click="closeCommand"
          >
            <el-icon aria-hidden="true"><component :is="item.icon" /></el-icon>
            <span
              ><strong>{{ item.label }}</strong
              ><small>{{ item.group }}</small></span
            >
          </RouterLink>
          <p v-if="filteredCommandRoutes.length === 0" role="status">
            {{ $t('nav.noWorkspaceMatches') }}
          </p>
        </nav>
      </div>
    </el-dialog>
  </header>
</template>
