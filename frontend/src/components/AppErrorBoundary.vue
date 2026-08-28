<script setup lang="ts">
import { onErrorCaptured, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import MascotState from '@/components/mascot/MascotState.vue'
import { getUiErrorReference, reportUiError } from '@/utils/reportUiError'

const { t } = useI18n()
const route = useRoute()
const error = ref<Error | null>(null)
const errorReference = ref('')
const retryKey = ref(0)

onErrorCaptured((err, _instance, info) => {
  error.value = err instanceof Error ? err : new Error(String(err))
  const reference = getUiErrorReference(err)
  errorReference.value = reportUiError(err, info, reference)
  return false
})

function retry() {
  error.value = null
  errorReference.value = ''
  retryKey.value += 1
}

function clearBoundary() {
  error.value = null
  errorReference.value = ''
  retryKey.value += 1
}

watch(() => route.fullPath, clearBoundary)
</script>

<template>
  <div v-if="error" class="app-error-boundary" data-surface="error-recovery" role="alert">
    <MascotState
      class="app-error-boundary__mascot"
      pose="warning"
      size="sm"
      :alt="t('common.unexpectedError')"
      eager
    />
    <h2>{{ t('common.unexpectedError') }}</h2>
    <p>{{ t('common.recoveryHint') }}</p>
    <p class="app-error-boundary__reference">
      {{ t('common.errorReference', { reference: errorReference }) }}
    </p>
    <div class="app-error-boundary__actions">
      <el-button type="primary" data-touch-target="44" @click="retry">
        {{ t('common.retry') }}
      </el-button>
    </div>
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

.app-error-boundary__mascot {
  width: var(--space-16);
  max-width: 100%;
}

.app-error-boundary__actions :deep(.el-button) {
  min-height: var(--touch-target-min);
}

.app-error-boundary p {
  color: var(--color-text-muted);
  line-height: var(--leading-relaxed);
}

.app-error-boundary__reference {
  font-family: ui-monospace, monospace;
  font-size: var(--text-sm);
  font-variant-numeric: tabular-nums;
}
</style>
