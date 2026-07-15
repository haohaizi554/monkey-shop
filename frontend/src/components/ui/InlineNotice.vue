<script setup lang="ts">
import { CircleCheck, InfoFilled, WarningFilled } from '@element-plus/icons-vue'
import { computed, onBeforeUnmount, ref, watch, type Component } from 'vue'
import { useI18n } from 'vue-i18n'

type InlineNoticeSeverity = 'info' | 'success' | 'warning' | 'danger'

const props = withDefaults(
  defineProps<{
    severity?: InlineNoticeSeverity
    title?: string
    message?: string
    retryAfterSeconds?: number
    dismissible?: boolean
  }>(),
  {
    severity: 'info',
    title: undefined,
    message: undefined,
    retryAfterSeconds: undefined,
    dismissible: false,
  },
)

const emit = defineEmits<{
  dismiss: []
  retry: []
  countdownComplete: []
}>()

const { t } = useI18n()
const remainingSeconds = ref(0)
let countdownTimer: ReturnType<typeof setInterval> | undefined

const icons: Record<InlineNoticeSeverity, Component> = {
  info: InfoFilled,
  success: CircleCheck,
  warning: WarningFilled,
  danger: WarningFilled,
}

const role = computed<'alert' | 'status'>(() =>
  props.severity === 'warning' || props.severity === 'danger' ? 'alert' : 'status',
)
const icon = computed(() => icons[props.severity])
function stopCountdown() {
  if (countdownTimer !== undefined) {
    clearInterval(countdownTimer)
    countdownTimer = undefined
  }
}

function resetCountdown(seconds?: number) {
  stopCountdown()
  remainingSeconds.value = Math.max(0, Math.ceil(seconds ?? 0))
  if (remainingSeconds.value <= 0) {
    return
  }
  countdownTimer = setInterval(() => {
    remainingSeconds.value = Math.max(0, remainingSeconds.value - 1)
    if (remainingSeconds.value === 0) {
      stopCountdown()
      emit('countdownComplete')
    }
  }, 1000)
}

watch(() => props.retryAfterSeconds, resetCountdown, { immediate: true })
onBeforeUnmount(stopCountdown)
</script>

<template>
  <aside class="inline-notice" :data-severity="severity" :role="role" aria-atomic="true">
    <el-icon class="inline-notice__icon" aria-hidden="true">
      <component :is="icon" />
    </el-icon>
    <div class="inline-notice__content">
      <strong v-if="title" class="inline-notice__title">{{ title }}</strong>
      <p v-if="message" class="inline-notice__message">{{ message }}</p>
      <slot />
      <div v-if="retryAfterSeconds !== undefined" class="inline-notice__retry">
        <span v-if="remainingSeconds > 0" data-numeric>
          {{ t('feedback.retryAfter', { seconds: remainingSeconds }) }}
        </span>
        <button v-else type="button" @click="$emit('retry')">
          {{ t('common.retry') }}
        </button>
      </div>
    </div>
    <button
      v-if="dismissible"
      class="inline-notice__dismiss"
      type="button"
      :aria-label="t('common.dismiss')"
      @click="$emit('dismiss')"
    >
      &times;
    </button>
  </aside>
</template>

<style scoped>
.inline-notice {
  display: grid;
  grid-template-columns: 22px minmax(0, 1fr) auto;
  gap: var(--space-3);
  align-items: start;
  min-width: 0;
  padding: var(--space-3) var(--space-4);
  border: 1px solid var(--notice-line, var(--color-line));
  border-radius: var(--radius-surface);
  color: var(--color-ink);
  background: var(--notice-surface, var(--color-surface-subtle));
}

.inline-notice[data-severity='info'] {
  --notice-line: color-mix(in srgb, var(--color-cobalt) 38%, var(--color-line));
  --notice-surface: color-mix(in srgb, var(--color-cobalt-soft) 72%, var(--color-surface));
  --notice-accent: var(--color-cobalt);
}

.inline-notice[data-severity='success'] {
  --notice-line: color-mix(in srgb, var(--color-success) 38%, var(--color-line));
  --notice-surface: color-mix(in srgb, var(--color-success-soft) 72%, var(--color-surface));
  --notice-accent: var(--color-success);
}

.inline-notice[data-severity='warning'] {
  --notice-line: color-mix(in srgb, var(--color-honey) 42%, var(--color-line));
  --notice-surface: color-mix(in srgb, var(--color-honey-soft) 72%, var(--color-surface));
  --notice-accent: var(--color-honey);
}

.inline-notice[data-severity='danger'] {
  --notice-line: color-mix(in srgb, var(--color-danger) 40%, var(--color-line));
  --notice-surface: color-mix(in srgb, var(--color-danger-soft) 72%, var(--color-surface));
  --notice-accent: var(--color-danger);
}

.inline-notice__icon {
  color: var(--notice-accent);
  font-size: 21px;
}

.inline-notice__content {
  display: grid;
  gap: var(--space-1);
  min-width: 0;
}

.inline-notice__title,
.inline-notice__message {
  overflow-wrap: anywhere;
}

.inline-notice__title {
  font-size: var(--text-sm);
}

.inline-notice__message {
  margin: 0;
  color: var(--color-muted);
  font-size: var(--text-sm);
  line-height: var(--leading-normal);
}

.inline-notice__retry {
  margin-top: var(--space-1);
  color: var(--color-muted);
  font-size: var(--text-xs);
}

.inline-notice__retry button,
.inline-notice__dismiss {
  min-width: 36px;
  min-height: 36px;
  padding: 0 var(--space-2);
  border: 0;
  border-radius: var(--radius-control);
  color: var(--notice-accent);
  background: transparent;
  font-weight: 700;
  cursor: pointer;
}

.inline-notice__dismiss {
  color: var(--color-muted);
  font-size: var(--text-xl);
  line-height: 1;
}
</style>
