<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue'
import { ElAlert, ElButton } from 'element-plus'
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { loadTurnstile } from '@/utils/loadTurnstile'

const props = defineProps<{
  action: string
  siteKey?: string
  modelValue?: string
  label?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const { t } = useI18n()
const widgetHost = ref<HTMLElement | null>(null)
const loadError = ref('')
const retrying = ref(false)
let widgetId: string | undefined

async function renderWidget() {
  if (!widgetHost.value) {
    return
  }
  if (!props.siteKey) {
    loadError.value = t('auth.captchaLoadFailed')
    emit('update:modelValue', '')
    return
  }
  loadError.value = ''
  retrying.value = true
  try {
    await loadTurnstile()
    await nextTick()
    if (!window.turnstile || !widgetHost.value) {
      loadError.value = t('auth.captchaLoadFailed')
      return
    }
    if (widgetId) {
      window.turnstile.remove(widgetId)
    }
    widgetHost.value.replaceChildren()
    widgetId = window.turnstile.render(widgetHost.value, {
      sitekey: props.siteKey,
      action: props.action,
      callback: (token: string) => {
        loadError.value = ''
        emit('update:modelValue', token)
      },
      'expired-callback': () => {
        loadError.value = t('auth.captchaExpired')
        emit('update:modelValue', '')
      },
      'error-callback': () => {
        loadError.value = t('auth.captchaLoadFailed')
        emit('update:modelValue', '')
        return true
      },
    })
  } catch {
    loadError.value = t('auth.captchaLoadFailed')
    emit('update:modelValue', '')
  } finally {
    retrying.value = false
  }
}

onMounted(() => {
  void renderWidget()
})

onBeforeUnmount(() => {
  if (widgetId && window.turnstile) {
    window.turnstile.remove(widgetId)
  }
})

watch(
  () => [props.siteKey, props.action],
  () => {
    emit('update:modelValue', '')
    void renderWidget()
  },
)
</script>

<template>
  <div class="turnstile-box">
    <div ref="widgetHost" class="turnstile-widget" />
    <el-alert
      v-if="loadError"
      type="error"
      :closable="false"
      show-icon
      class="turnstile-error"
      aria-live="polite"
    >
      <template #default>
        <div class="turnstile-error-row">
          <span>{{ loadError }}</span>
          <el-button
            size="small"
            native-type="button"
            :icon="Refresh"
            :loading="retrying"
            :aria-label="t('auth.retryVerification', { context: label || t('auth.captcha') })"
            @click="renderWidget"
          >
            {{ t('common.retry') }}
          </el-button>
        </div>
      </template>
    </el-alert>
  </div>
</template>

<style scoped>
.turnstile-error {
  margin-top: var(--space-2);
}
.turnstile-error-row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
}
</style>
