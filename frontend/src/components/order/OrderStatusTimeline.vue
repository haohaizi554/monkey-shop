<script setup lang="ts">
import { CircleCheck, Clock, CreditCard, Goods, RefreshLeft, Van } from '@element-plus/icons-vue'
import { computed, type Component } from 'vue'
import { useI18n } from 'vue-i18n'
import { dateTime } from '@/utils/format'

export interface OrderTimelineTimestamps {
  created?: string
  paid?: string
  shipped?: string
  delivered?: string
  completed?: string
  returnRequested?: string
  returnShipped?: string
  refunded?: string
}

export interface OrderTimelineLogisticsEvent {
  id?: number | string
  eventType?: string
  fromStatus?: string
  toStatus?: string
  eventTime?: string
  location?: string
  remark?: string
}

const props = withDefaults(
  defineProps<{
    currentStatus?: string
    status?: string
    timestamps?: OrderTimelineTimestamps
    logisticsEvents?: OrderTimelineLogisticsEvent[]
  }>(),
  {
    currentStatus: '',
    status: '',
    timestamps: () => ({}),
    logisticsEvents: () => [],
  },
)

const { locale } = useI18n()

interface TimelineStep {
  key: string
  label: string
  icon: Component
  timestamp?: string
}

const aliases: Record<string, string> = {
  CREATED: 'PAYMENT_PENDING',
  PENDING: 'PAYMENT_PENDING',
  PAYMENT_PENDING: 'PAYMENT_PENDING',
  PAYMENT_COMPLETED: 'PAID',
  '\u5f85\u652f\u4ed8': 'PAYMENT_PENDING',
  '\u5df2\u652f\u4ed8': 'PAID',
  '\u90e8\u5206\u53d1\u8d27': 'PARTIALLY_SHIPPED',
  '\u5df2\u53d1\u8d27': 'SHIPPED',
  '\u90e8\u5206\u7b7e\u6536': 'PARTIALLY_RECEIVED',
  '\u5df2\u5b8c\u6210': 'COMPLETED',
  '\u7533\u8bf7\u9000\u8d27': 'RETURN_REQUESTED',
  '\u5f85\u9000\u8d27\u53d1\u8d27': 'WAITING_RETURN_SHIPMENT',
  '\u9000\u8d27\u4e2d': 'RETURN_SHIPPING',
  '\u5df2\u9000\u6b3e': 'REFUNDED',
}

const orderProgress: Record<string, number> = {
  PAYMENT_PENDING: 1,
  PAID: 2,
  PARTIALLY_SHIPPED: 2,
  SHIPPED: 3,
  PARTIALLY_RECEIVED: 3,
  COMPLETED: 4,
  RETURN_REQUESTED: 5,
  WAITING_RETURN_SHIPMENT: 6,
  RETURN_SHIPPING: 6,
  REFUNDED: 7,
}

const logisticsProgress: Record<string, number> = {
  ORDERED: 0,
  PICKED_UP: 1,
  IN_TRANSIT: 2,
  OUT_FOR_DELIVERY: 3,
  SIGNED: 4,
}

const normalizedStatus = computed(() => {
  const rawStatus = (props.currentStatus || props.status || '').trim()
  return aliases[rawStatus] ?? rawStatus
})

const isChinese = computed(() => locale.value === 'zh')
const localized = (english: string, chinese: string) => (isChinese.value ? chinese : english)

function safeStatusLabel(status: string): string {
  const labels: Record<string, [string, string]> = {
    PAYMENT_PENDING: ['Awaiting payment', '\u5f85\u652f\u4ed8'],
    PAID: ['Paid', '\u5df2\u652f\u4ed8'],
    PARTIALLY_SHIPPED: ['Partially shipped', '\u90e8\u5206\u53d1\u8d27'],
    SHIPPED: ['Shipped', '\u5df2\u53d1\u8d27'],
    PARTIALLY_RECEIVED: ['Partially received', '\u90e8\u5206\u7b7e\u6536'],
    COMPLETED: ['Completed', '\u5df2\u5b8c\u6210'],
    RETURN_REQUESTED: ['Return requested', '\u5df2\u7533\u8bf7\u9000\u8d27'],
    WAITING_RETURN_SHIPMENT: ['Awaiting return shipment', '\u5f85\u5bc4\u56de'],
    RETURN_SHIPPING: ['Return in transit', '\u9000\u8d27\u8fd0\u8f93\u4e2d'],
    REFUNDED: ['Refunded', '\u5df2\u9000\u6b3e'],
    ORDERED: ['Shipment created', '\u8fd0\u5355\u5df2\u521b\u5efa'],
    PICKED_UP: ['Picked up', '\u5df2\u63fd\u6536'],
    IN_TRANSIT: ['In transit', '\u8fd0\u8f93\u4e2d'],
    OUT_FOR_DELIVERY: ['Out for delivery', '\u6d3e\u9001\u4e2d'],
    SIGNED: ['Delivered', '\u5df2\u7b7e\u6536'],
    CANCELLED: ['Cancelled', '\u5df2\u53d6\u6d88'],
    FAILED: ['Action required', '\u9700\u8981\u5904\u7406'],
  }
  const label = labels[aliases[status] ?? status]
  return label ? localized(label[0], label[1]) : localized('Processing', '\u5904\u7406\u4e2d')
}

function eventTimestamp(...statuses: string[]): string | undefined {
  return props.logisticsEvents.find((event) =>
    statuses.includes(aliases[event.toStatus ?? ''] ?? event.toStatus ?? ''),
  )?.eventTime
}

const usesLogisticsProgress = computed(() => normalizedStatus.value in logisticsProgress)
const includesReturn = computed(() =>
  ['RETURN_REQUESTED', 'WAITING_RETURN_SHIPMENT', 'RETURN_SHIPPING', 'REFUNDED'].includes(
    normalizedStatus.value,
  ),
)

const steps = computed<TimelineStep[]>(() => {
  if (usesLogisticsProgress.value) {
    return [
      {
        key: 'shipment-created',
        label: localized('Shipment created', '\u8fd0\u5355\u5df2\u521b\u5efa'),
        icon: Goods,
        timestamp: props.timestamps.created,
      },
      {
        key: 'picked-up',
        label: localized('Picked up', '\u5df2\u63fd\u6536'),
        icon: Goods,
        timestamp: eventTimestamp('PICKED_UP'),
      },
      {
        key: 'in-transit',
        label: localized('In transit', '\u8fd0\u8f93\u4e2d'),
        icon: Van,
        timestamp: eventTimestamp('IN_TRANSIT'),
      },
      {
        key: 'out-for-delivery',
        label: localized('Out for delivery', '\u6d3e\u9001\u4e2d'),
        icon: Van,
        timestamp: eventTimestamp('OUT_FOR_DELIVERY'),
      },
      {
        key: 'delivered',
        label: localized('Delivered', '\u5df2\u7b7e\u6536'),
        icon: CircleCheck,
        timestamp: props.timestamps.delivered ?? eventTimestamp('SIGNED'),
      },
    ]
  }

  const orderSteps: TimelineStep[] = [
    {
      key: 'created',
      label: localized('Order placed', '\u8ba2\u5355\u5df2\u521b\u5efa'),
      icon: Goods,
      timestamp: props.timestamps.created,
    },
    {
      key: 'payment',
      label: localized('Payment', '\u652f\u4ed8'),
      icon: CreditCard,
      timestamp: props.timestamps.paid,
    },
    {
      key: 'preparing',
      label: localized('Preparing shipment', '\u5907\u8d27\u4e2d'),
      icon: Clock,
      timestamp: props.timestamps.shipped,
    },
    {
      key: 'shipping',
      label: localized('Delivery', '\u914d\u9001'),
      icon: Van,
      timestamp: props.timestamps.delivered,
    },
    {
      key: 'completed',
      label: localized('Completed', '\u5df2\u5b8c\u6210'),
      icon: CircleCheck,
      timestamp: props.timestamps.completed,
    },
  ]

  if (includesReturn.value) {
    orderSteps.push(
      {
        key: 'return-requested',
        label: localized('Return requested', '\u5df2\u7533\u8bf7\u9000\u8d27'),
        icon: RefreshLeft,
        timestamp: props.timestamps.returnRequested,
      },
      {
        key: 'return-shipping',
        label: localized('Return shipment', '\u9000\u8d27\u8fd0\u8f93'),
        icon: Van,
        timestamp: props.timestamps.returnShipped,
      },
      {
        key: 'refunded',
        label: localized('Refunded', '\u5df2\u9000\u6b3e'),
        icon: CircleCheck,
        timestamp: props.timestamps.refunded,
      },
    )
  }
  return orderSteps
})

const currentIndex = computed(() => {
  const progress = usesLogisticsProgress.value ? logisticsProgress : orderProgress
  return progress[normalizedStatus.value] ?? 0
})

function stepState(index: number): 'complete' | 'current' | 'upcoming' {
  if (index < currentIndex.value) {
    return 'complete'
  }
  return index === currentIndex.value ? 'current' : 'upcoming'
}

function eventKey(event: OrderTimelineLogisticsEvent, index: number): string {
  return String(event.id ?? `${event.eventTime ?? 'event'}-${index}`)
}

function eventLabel(event: OrderTimelineLogisticsEvent): string {
  const eventLabels: Record<string, [string, string]> = {
    PICKUP: ['Pickup', '\u63fd\u6536'],
    TRANSIT: ['Transit update', '\u8fd0\u8f93\u66f4\u65b0'],
    DISPATCH: ['Delivery update', '\u6d3e\u9001\u66f4\u65b0'],
    SIGN: ['Delivery confirmed', '\u7b7e\u6536\u786e\u8ba4'],
  }
  const label = eventLabels[event.eventType ?? '']
  return label
    ? localized(label[0], label[1])
    : localized('Tracking update', '\u7269\u6d41\u66f4\u65b0')
}
</script>

<template>
  <section
    class="order-status-timeline"
    :aria-label="localized('Order progress', '\u8ba2\u5355\u8fdb\u5ea6')"
  >
    <div class="order-status-timeline__current" role="status">
      <Clock aria-hidden="true" />
      <strong>{{ safeStatusLabel(normalizedStatus) }}</strong>
    </div>

    <ol class="order-status-timeline__steps" :style="{ '--timeline-columns': steps.length }">
      <li
        v-for="(step, index) in steps"
        :key="step.key"
        :class="`is-${stepState(index)}`"
        :aria-current="stepState(index) === 'current' ? 'step' : undefined"
      >
        <span class="order-status-timeline__icon" aria-hidden="true">
          <component :is="step.icon" />
        </span>
        <span class="order-status-timeline__label">{{ step.label }}</span>
        <time v-if="step.timestamp" :datetime="step.timestamp">{{ dateTime(step.timestamp) }}</time>
      </li>
    </ol>

    <ul v-if="logisticsEvents.length" class="order-status-timeline__events">
      <li v-for="(event, index) in logisticsEvents" :key="eventKey(event, index)">
        <CircleCheck aria-hidden="true" />
        <span>
          <strong>{{ eventLabel(event) }}</strong>
          <span>{{ safeStatusLabel(event.toStatus ?? '') }}</span>
          <span v-if="event.location">{{ event.location }}</span>
        </span>
        <time v-if="event.eventTime" :datetime="event.eventTime">{{
          dateTime(event.eventTime)
        }}</time>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.order-status-timeline {
  display: grid;
  gap: var(--space-3);
  min-width: 0;
}

.order-status-timeline__current {
  align-items: center;
  display: flex;
  gap: var(--space-2);
}

.order-status-timeline__current svg,
.order-status-timeline__events svg {
  flex: 0 0 18px;
  height: 18px;
  width: 18px;
}

.order-status-timeline__steps {
  display: grid;
  gap: 0;
  grid-template-columns: repeat(var(--timeline-columns, 5), minmax(72px, 1fr));
  list-style: none;
  margin: 0;
  overflow-x: auto;
  padding: 0 0 4px;
}

.order-status-timeline__steps li {
  align-content: start;
  color: var(--el-text-color-placeholder);
  display: grid;
  font-size: var(--text-xs);
  gap: var(--space-1);
  min-width: 80px;
  padding-right: var(--space-2);
  position: relative;
}

.order-status-timeline__steps li::before {
  background: var(--el-border-color);
  content: '';
  height: 2px;
  left: 24px;
  position: absolute;
  right: 0;
  top: 11px;
}

.order-status-timeline__steps li:last-child::before {
  display: none;
}

.order-status-timeline__steps li.is-complete,
.order-status-timeline__steps li.is-current {
  color: var(--el-text-color-primary);
}

.order-status-timeline__steps li.is-complete::before {
  background: var(--el-color-success);
}

.order-status-timeline__icon {
  align-items: center;
  background: var(--el-bg-color);
  border: 1px solid currentColor;
  border-radius: var(--radius-circle);
  display: inline-flex;
  height: 24px;
  justify-content: center;
  position: relative;
  width: 24px;
  z-index: 1;
}

.is-complete .order-status-timeline__icon {
  color: var(--el-color-success);
}

.is-current .order-status-timeline__icon {
  color: var(--el-color-primary);
}

.order-status-timeline__icon svg {
  height: 14px;
  width: 14px;
}

.order-status-timeline__label {
  overflow-wrap: anywhere;
}

.order-status-timeline time {
  color: var(--el-text-color-secondary);
  font-size: var(--text-xs);
}

.order-status-timeline__events {
  border-top: 1px solid var(--el-border-color-lighter);
  display: grid;
  gap: var(--space-2);
  list-style: none;
  margin: 0;
  padding: var(--space-3) 0 0;
}

.order-status-timeline__events li {
  align-items: start;
  display: grid;
  gap: var(--space-2);
  grid-template-columns: auto minmax(0, 1fr) auto;
}

.order-status-timeline__events li > span {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
  min-width: 0;
}

@media (max-width: 640px) {
  .order-status-timeline__events li {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .order-status-timeline__events time {
    grid-column: 2;
  }
}
</style>
