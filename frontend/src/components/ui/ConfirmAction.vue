<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { useNotify, type ConfirmOptions } from '@/composables/useNotify'

const props = withDefaults(
  defineProps<{
    content: string
    action: () => unknown | Promise<unknown>
    title?: string
    confirmText?: string
    cancelText?: string
    type?: ConfirmOptions['type']
    disabled?: boolean
  }>(),
  {
    title: undefined,
    confirmText: undefined,
    cancelText: undefined,
    type: 'warning',
    disabled: false,
  },
)

const emit = defineEmits<{
  confirmed: []
  cancelled: []
  error: [reason: unknown]
}>()

const notify = useNotify()
const pending = ref(false)

async function restoreFocus(element: HTMLElement | null) {
  await nextTick()
  element?.focus({ preventScroll: true })
}

async function execute(): Promise<boolean> {
  if (pending.value || props.disabled) {
    return false
  }

  const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
  const accepted = await notify.confirm({
    title: props.title,
    content: props.content,
    confirmText: props.confirmText,
    cancelText: props.cancelText,
    type: props.type,
  })
  if (!accepted) {
    emit('cancelled')
    await restoreFocus(trigger)
    return false
  }

  pending.value = true
  try {
    await props.action()
    emit('confirmed')
    return true
  } catch (reason) {
    emit('error', reason)
    return false
  } finally {
    pending.value = false
    await restoreFocus(trigger)
  }
}

defineExpose({ execute, pending })
</script>

<template>
  <button
    class="confirm-action"
    type="button"
    :disabled="disabled || pending"
    :aria-busy="pending"
    @click="execute"
  >
    <span class="confirm-action__content"><slot /></span>
    <span v-if="pending" class="confirm-action__spinner" aria-hidden="true" />
  </button>
</template>

<style scoped>
.confirm-action {
  position: relative;
  display: inline-flex;
  gap: var(--space-2);
  align-items: center;
  justify-content: center;
  min-height: 38px;
  padding: 0 var(--space-4);
  border: 1px solid var(--color-danger);
  border-radius: var(--radius-control);
  color: var(--color-text-inverse);
  background: var(--color-danger);
  font-weight: 700;
  cursor: pointer;
  transition:
    background-color var(--motion-fast),
    border-color var(--motion-fast),
    opacity var(--motion-fast);
}

.confirm-action:disabled {
  cursor: not-allowed;
  opacity: 0.58;
}

.confirm-action__spinner {
  width: 14px;
  height: 14px;
  border: 2px solid currentColor;
  border-right-color: transparent;
  border-radius: var(--radius-circle);
  animation: confirm-action-spin 700ms linear infinite;
}

@keyframes confirm-action-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
