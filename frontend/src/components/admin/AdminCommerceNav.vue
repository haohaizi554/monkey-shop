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
  <nav class="admin-commerce-nav" :aria-label="t('nav.adminCommerce')">
    <el-tabs :model-value="route.path" stretch @tab-change="navigate">
      <el-tab-pane v-for="tab in tabs" :key="tab.path" :name="tab.path" :label="tab.label" />
    </el-tabs>
  </nav>
</template>

<style scoped>
.admin-commerce-nav {
  min-width: 0;
  padding-inline: var(--space-1);
  border-bottom: 1px solid var(--color-line-strong);
}

.admin-commerce-nav :deep(.el-tabs__header) {
  margin: 0;
}

.admin-commerce-nav :deep(.el-tabs__nav-wrap::after) {
  height: 0;
}

.admin-commerce-nav :deep(.el-tabs__item) {
  min-height: 44px;
  color: var(--color-muted);
  font-weight: 700;
}

.admin-commerce-nav :deep(.el-tabs__item.is-active) {
  color: var(--color-brand-strong);
}

.admin-commerce-nav :deep(.el-tabs__active-bar) {
  background: var(--color-brand);
}

@media (max-width: 700px) {
  .admin-commerce-nav {
    overflow-x: auto;
  }

  .admin-commerce-nav :deep(.el-tabs__nav) {
    min-width: 680px;
  }
}
</style>
