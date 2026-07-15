<script lang="ts">
import { computed, defineComponent, type PropType } from 'vue'
import { useI18n } from 'vue-i18n'

export type StatusTone = 'neutral' | 'info' | 'success' | 'warning' | 'danger'

export interface StatusTagDefinition {
  tone: StatusTone
  labelKey: string
}

const STATUS_TAGS: Readonly<Record<string, StatusTagDefinition>> = Object.freeze({
  PENDING_PAYMENT: { tone: 'warning', labelKey: 'status.order.pendingPayment' },
  PAID: { tone: 'info', labelKey: 'status.payment.paid' },
  PROCESSING: { tone: 'info', labelKey: 'status.payment.processing' },
  REFUNDING: { tone: 'warning', labelKey: 'status.payment.refunding' },
  REFUNDED: { tone: 'neutral', labelKey: 'status.payment.refunded' },
  FULFILLING: { tone: 'info', labelKey: 'status.order.fulfilling' },
  SHIPPED: { tone: 'info', labelKey: 'status.logistics.shipped' },
  DELIVERED: { tone: 'success', labelKey: 'status.logistics.delivered' },
  COMPLETED: { tone: 'success', labelKey: 'status.common.completed' },
  CANCELLED: { tone: 'neutral', labelKey: 'status.order.cancelled' },
  FAILED: { tone: 'danger', labelKey: 'status.common.failed' },
  IN_STOCK: { tone: 'success', labelKey: 'status.inventory.inStock' },
  LOW_STOCK: { tone: 'warning', labelKey: 'status.inventory.lowStock' },
  OUT_OF_STOCK: { tone: 'danger', labelKey: 'status.inventory.outOfStock' },
  PENDING_REVIEW: { tone: 'warning', labelKey: 'status.risk.pendingReview' },
  APPROVED: { tone: 'success', labelKey: 'status.common.approved' },
  REJECTED: { tone: 'danger', labelKey: 'status.common.rejected' },
  BLOCKED: { tone: 'danger', labelKey: 'status.risk.blocked' },
  ACTIVE: { tone: 'success', labelKey: 'status.tenant.active' },
  SUSPENDED: { tone: 'warning', labelKey: 'status.tenant.suspended' },
  EXPIRED: { tone: 'neutral', labelKey: 'status.tenant.expired' },
})

export function resolveStatusTag(status: string): StatusTagDefinition {
  const normalized = status.trim().toUpperCase().replace(/[\s-]+/g, '_')
  return STATUS_TAGS[normalized] ?? { tone: 'neutral', labelKey: 'status.common.unknown' }
}

function humanizeStatus(status: string): string {
  return status
    .trim()
    .toLowerCase()
    .replace(/[\s_-]+/g, ' ')
    .replace(/(^|\s)\S/g, (letter) => letter.toUpperCase())
}

export default defineComponent({
  name: 'StatusTag',
  props: {
    status: { type: String, required: true },
    label: { type: String, default: undefined },
    tone: { type: String as PropType<StatusTone>, default: undefined },
  },
  setup(props) {
    const { t, te } = useI18n()
    const definition = computed(() => resolveStatusTag(props.status))
    const resolvedTone = computed(() => props.tone ?? definition.value.tone)
    const resolvedLabel = computed(() => {
      if (props.label) {
        return props.label
      }
      return te(definition.value.labelKey)
        ? t(definition.value.labelKey)
        : humanizeStatus(props.status)
    })
    return { resolvedLabel, resolvedTone }
  },
})
</script>

<template>
  <span class="status-tag" :data-tone="resolvedTone">
    <span class="status-tag__dot" aria-hidden="true" />
    <span>{{ resolvedLabel }}</span>
  </span>
</template>

<style scoped>
.status-tag {
  --status-accent: var(--color-muted);
  --status-surface: var(--color-surface-subtle);
  display: inline-flex;
  gap: var(--space-2);
  align-items: center;
  min-height: 28px;
  max-width: 100%;
  padding: 0 var(--space-3);
  border: 1px solid color-mix(in srgb, var(--status-accent) 30%, var(--color-line));
  border-radius: var(--radius-pill);
  color: var(--color-ink);
  background: var(--status-surface);
  font-size: var(--text-xs);
  font-weight: 700;
  line-height: 1.2;
}

.status-tag[data-tone='info'] {
  --status-accent: var(--color-cobalt);
  --status-surface: color-mix(in srgb, var(--color-cobalt-soft) 72%, var(--color-surface));
}

.status-tag[data-tone='success'] {
  --status-accent: var(--color-success);
  --status-surface: color-mix(in srgb, var(--color-success-soft) 72%, var(--color-surface));
}

.status-tag[data-tone='warning'] {
  --status-accent: var(--color-honey);
  --status-surface: color-mix(in srgb, var(--color-honey-soft) 72%, var(--color-surface));
}

.status-tag[data-tone='danger'] {
  --status-accent: var(--color-danger);
  --status-surface: color-mix(in srgb, var(--color-danger-soft) 72%, var(--color-surface));
}

.status-tag__dot {
  flex: 0 0 auto;
  width: 7px;
  height: 7px;
  border-radius: var(--radius-circle);
  background: var(--status-accent);
}

.status-tag > span:last-child {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
