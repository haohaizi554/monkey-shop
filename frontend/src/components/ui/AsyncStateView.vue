<script setup lang="ts">
import { Loading, RefreshRight, Search, Warning } from '@element-plus/icons-vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { AsyncStatus } from '@/composables/useAsyncState'

const props = withDefaults(
  defineProps<{
    status: AsyncStatus
    error?: string | null
    loadingLines?: number
    emptyTitle?: string
    emptyDescription?: string
  }>(),
  {
    error: null,
    loadingLines: 3,
    emptyTitle: undefined,
    emptyDescription: undefined,
  },
)

defineEmits<{ retry: [] }>()

const { t, te } = useI18n()
const errorMessage = computed(() => {
  if (!props.error) {
    return t('common.requestFailed')
  }
  return te(props.error) ? t(props.error) : props.error
})
</script>

<template>
  <div
    class="async-state-view"
    :data-status="status"
    :aria-busy="status === 'loading' || status === 'updating'"
  >
    <div v-if="status === 'idle'" class="async-state-view__idle">
      <slot name="idle" />
    </div>

    <div v-else-if="status === 'loading'" class="async-state-view__loading" role="status">
      <slot name="loading">
        <span class="visually-hidden">{{ t('common.loading') }}</span>
        <span
          v-for="line in loadingLines"
          :key="line"
          class="async-state-view__skeleton-line"
          aria-hidden="true"
        />
      </slot>
    </div>

    <div v-else-if="status === 'error'" class="async-state-view__error" role="alert">
      <slot name="error" :error="errorMessage">
        <el-icon class="async-state-view__state-icon" aria-hidden="true"><Warning /></el-icon>
        <p>{{ errorMessage }}</p>
        <el-button class="async-state-view__retry" :icon="RefreshRight" @click="$emit('retry')">
          {{ t('common.retry') }}
        </el-button>
      </slot>
    </div>

    <div v-else-if="status === 'empty'" class="async-state-view__empty" role="status">
      <slot name="empty">
        <el-icon class="async-state-view__state-icon" aria-hidden="true"><Search /></el-icon>
        <h2>{{ emptyTitle || t('common.noData') }}</h2>
        <p v-if="emptyDescription">{{ emptyDescription }}</p>
      </slot>
    </div>

    <div v-else class="async-state-view__content">
      <div v-if="status === 'updating'" class="async-state-view__updating" role="status">
        <el-icon class="is-loading" aria-hidden="true"><Loading /></el-icon>
        <span>{{ t('common.updating') }}</span>
      </div>
      <slot />
    </div>
  </div>
</template>
