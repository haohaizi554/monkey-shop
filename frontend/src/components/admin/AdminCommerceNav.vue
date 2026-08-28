<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()

const tabs = computed(() => [
  { path: '/admin/orders', label: t('nav.adminOrders') },
  { path: '/admin/returns', label: t('nav.adminReturns') },
  { path: '/admin/payments', label: t('nav.adminPayments') },
  { path: '/admin/logistics', label: t('nav.adminLogistics') },
  { path: '/admin/members', label: t('nav.adminMembers') },
])

async function navigate(path: string | number) {
  const target = String(path)
  if (target !== route.path) {
    await router.push(target)
  }
}
</script>

<template>
  <nav
    class="admin-commerce-nav"
    data-surface="commerce-navigation"
    data-observatory="commerce"
    tabindex="0"
    :aria-label="t('nav.adminCommerce')"
  >
    <el-tabs :model-value="route.path" stretch @tab-change="navigate">
      <el-tab-pane v-for="tab in tabs" :key="tab.path" :name="tab.path">
        <template #label>
          <span
            class="admin-commerce-nav__label"
            :aria-current="route.path === tab.path ? 'page' : undefined"
          >
            {{ tab.label }}
          </span>
        </template>
      </el-tab-pane>
    </el-tabs>
  </nav>
</template>

<style scoped>
.admin-commerce-nav {
  width: 100%;
  max-width: 100%;
  min-width: 0;
  padding-inline: var(--space-1);
  border-bottom: 1px solid var(--admin-line-strong);
  color: var(--admin-ink);
  background: var(--admin-surface);
  scrollbar-color: var(--admin-line-strong) transparent;
  scrollbar-width: thin;
}

.admin-commerce-nav :deep(.el-tabs__header) {
  margin: 0;
}

.admin-commerce-nav :deep(.el-tabs__nav-wrap),
.admin-commerce-nav :deep(.el-tabs__nav) {
  min-width: 0;
}

.admin-commerce-nav :deep(.el-tabs__nav-wrap::after) {
  height: 0;
}

.admin-commerce-nav :deep(.el-tabs__item) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 44px;
  color: var(--admin-muted);
  font-weight: var(--font-weight-semibold);
  white-space: nowrap;
}

.admin-commerce-nav :deep(.el-tabs__item.is-active) {
  color: var(--admin-ink);
  font-weight: var(--font-weight-bold);
}

.admin-commerce-nav :deep(.el-tabs__active-bar) {
  background: var(--admin-accent);
}

.admin-commerce-nav :deep(.el-tabs__item:focus-visible) {
  z-index: 1;
  outline: var(--focus-width) solid var(--admin-primary);
  outline-offset: calc(var(--focus-offset) * -1);
}

.admin-commerce-nav__label {
  min-width: 0;
}

@media (max-width: 700px) {
  .admin-commerce-nav {
    overflow-x: auto;
    overscroll-behavior-inline: contain;
  }

  .admin-commerce-nav :deep(.el-tabs),
  .admin-commerce-nav :deep(.el-tabs__nav-wrap),
  .admin-commerce-nav :deep(.el-tabs__nav) {
    min-width: 680px;
  }
}
</style>
