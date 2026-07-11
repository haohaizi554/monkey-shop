<script setup lang="ts">
import { Warning } from '@element-plus/icons-vue'
import { onErrorCaptured, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'

const { t } = useI18n()
const route = useRoute()
const error = ref<Error | null>(null)
const retryKey = ref(0)

onErrorCaptured((err) => {
  error.value = err instanceof Error ? err : new Error(String(err))
  return false
})

function retry() {
  error.value = null
  retryKey.value += 1
}

watch(
  () => route.fullPath,
  () => {
    error.value = null
  },
)
</script>

<template>
  <div v-if="error" class="app-error-boundary" role="alert">
    <el-icon :size="40" color="var(--color-danger)"><Warning /></el-icon>
    <h2>{{ t('common.unexpectedError') }}</h2>
    <p>{{ t('common.recoveryHint') }}</p>
    <el-button type="primary" @click="retry">{{ t('common.retry') }}</el-button>
    <details>
      <summary>{{ t('common.errorDetails') }}</summary>
      <pre>{{ error.stack ?? error.message }}</pre>
    </details>
  </div>
  <div v-else :key="retryKey" class="app-error-boundary-content">
    <slot />
  </div>
</template>

<style scoped>
.app-error-boundary {
  display: grid;
  justify-items: start;
  gap: var(--space-3);
  width: min(640px, 100%);
  margin: var(--space-10) auto;
  padding: var(--space-6);
  border: 1px solid var(--color-line);
  border-radius: var(--radius-surface);
  background: var(--color-surface);
}

.app-error-boundary p {
  color: var(--color-text-muted);
  line-height: var(--leading-relaxed);
}

.app-error-boundary details {
  width: 100%;
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}

.app-error-boundary pre {
  max-height: 180px;
  overflow: auto;
  padding: var(--space-3);
  border-radius: var(--radius-control);
  background: var(--color-surface-subtle);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
