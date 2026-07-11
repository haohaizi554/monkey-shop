<script setup lang="ts">
import {
  Box,
  DataAnalysis,
  Discount,
  Goods,
  OfficeBuilding,
  Setting,
  Warning,
} from '@element-plus/icons-vue'
import { useEventListener, useMediaQuery } from '@vueuse/core'
import { computed, nextTick, ref, watch, type Component } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: [] }>()
const { t } = useI18n()
const isDesktop = useMediaQuery('(min-width: 901px)')
const visible = computed(() => isDesktop.value || props.open)
const sidebar = ref<HTMLElement>()

interface AdminLink {
  to: string
  label: string
  icon: Component
}

const groups = computed(() => [
  {
    label: t('nav.adminStore'),
    links: [
      { to: '/admin', label: t('nav.admin'), icon: Setting },
      { to: '/inventory', label: t('nav.inventory'), icon: Box },
      { to: '/marketing', label: t('nav.marketing'), icon: Discount },
    ] satisfies AdminLink[],
  },
  {
    label: t('nav.adminOps'),
    links: [
      { to: '/dashboard', label: t('nav.dashboard'), icon: DataAnalysis },
      { to: '/risk', label: t('nav.risk'), icon: Warning },
      { to: '/tenants', label: t('nav.tenants'), icon: OfficeBuilding },
    ] satisfies AdminLink[],
  },
])

watch(
  () => props.open,
  async (open) => {
    if (!open || isDesktop.value) {
      return
    }
    await nextTick()
    sidebar.value?.querySelector<HTMLElement>('a')?.focus()
  },
)

function closeOnMobile() {
  if (!isDesktop.value) {
    emit('close')
  }
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && props.open && !isDesktop.value) {
    emit('close')
    return
  }
  if (event.key !== 'Tab' || !props.open || isDesktop.value || !sidebar.value) {
    return
  }
  const focusable = Array.from(
    sidebar.value.querySelectorAll<HTMLElement>(
      'a, button:not([disabled]), [tabindex]:not([tabindex="-1"])',
    ),
  )
  const first = focusable[0]
  const last = focusable.at(-1)
  if (!first || !last) {
    return
  }
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

useEventListener(window, 'keydown', onKeydown)
</script>

<template>
  <button
    v-if="props.open && !isDesktop"
    class="admin-sidebar-backdrop"
    type="button"
    :aria-label="$t('nav.closeNavigation')"
    @click="emit('close')"
  />
  <aside
    v-show="visible"
    id="admin-navigation"
    ref="sidebar"
    class="admin-sidebar"
    :class="{ 'is-open': props.open }"
    :role="isDesktop ? undefined : 'dialog'"
    :aria-modal="isDesktop ? undefined : 'true'"
    :aria-label="$t('nav.adminNavigation')"
  >
    <RouterLink class="admin-brand" to="/admin" :aria-label="$t('nav.admin')">
      <span class="brand-mark" aria-hidden="true"><Goods /></span>
      <span>MonkeyShop</span>
    </RouterLink>
    <nav :aria-label="$t('nav.adminNavigation')">
      <section v-for="group in groups" :key="group.label" class="admin-nav-group">
        <p>{{ group.label }}</p>
        <RouterLink v-for="link in group.links" :key="link.to" :to="link.to" @click="closeOnMobile">
          <component :is="link.icon" aria-hidden="true" />
          <span>{{ link.label }}</span>
        </RouterLink>
      </section>
    </nav>
  </aside>
</template>
