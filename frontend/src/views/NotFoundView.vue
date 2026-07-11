<script setup lang="ts">
import { Warning } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import AppShell from '@/components/AppShell.vue'

const { t } = useI18n()
const router = useRouter()
</script>

<template>
  <AppShell>
    <div class="empty-state">
      <el-icon :size="48" color="var(--color-brand)"><Warning /></el-icon>
      <h1>{{ t('common.notFound') }}</h1>
      <p>{{ t('common.notFoundHint') }}</p>
      <el-button type="primary" @click="router.push('/shop')">{{ t('nav.shop') }}</el-button>
    </div>
  </AppShell>
</template>
