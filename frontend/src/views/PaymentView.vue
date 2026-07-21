<script setup lang="ts">
import { CreditCard, Money, Position, RefreshRight, Search, Wallet } from '@element-plus/icons-vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { ApiError } from '@/api/http'
import * as paymentsApi from '@/api/payments'
import MascotState, { type MascotPose } from '@/components/mascot/MascotState.vue'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import DataTableShell from '@/components/ui/DataTableShell.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import { useAsyncState } from '@/composables/useAsyncState'
import { useNotify } from '@/composables/useNotify'
import type { PaymentMethod, PaymentResponse, PaymentStatus } from '@/types'
import { dateTime, money } from '@/utils/format'
import { getIdempotencyIntent } from '@/utils/idempotencyIntent'
import { navigateToPaymentProvider, resolvePaymentRedirectUrl } from '@/utils/paymentRedirect'

const route = useRoute()
const router = useRouter()
const { locale, t } = useI18n()
const notify = useNotify()
const paymentResource = useAsyncState<PaymentResponse | null>({ timeoutMs: 20000 })

const createPending = ref(false)
const createError = ref('')
const redirectPending = ref(false)
const redirectError = ref('')
const refundPending = ref(false)
const refundError = ref('')
const refundAmount = ref(0)
const refundReason = ref('')
const orderQueue = ref(readOrderQueue())

const form = reactive({
  orderId: orderQueue.value[0] ?? 0,
  method: 'WECHAT' as PaymentMethod,
  bankCardNo: '',
  totpCode: '',
})

const payment = computed(() => paymentResource.data.value)
const fundsOperationPending = computed(
  () => createPending.value || redirectPending.value || refundPending.value,
)
const paymentControlsLocked = computed(
  () => fundsOperationPending.value || paymentResource.isLoading.value,
)
const refundable = computed(() => {
  if (!payment.value) {
    return 0
  }
  return Math.max(0, Number(payment.value.paidAmount) - Number(payment.value.refundedAmount))
})
const paymentRedirectUrl = computed(() => {
  if (payment.value?.status !== 'PENDING') {
    return null
  }
  return resolvePaymentRedirectUrl(payment.value.paymentUrl, window.location.href)
})
const paymentRedirectRejected = computed(
  () =>
    payment.value?.status === 'PENDING' &&
    Boolean(payment.value.paymentUrl?.trim()) &&
    paymentRedirectUrl.value === null,
)
const isChinese = computed(() => locale.value === 'zh')
const paymentPose = computed<MascotPose>(() => {
  if (!payment.value) {
    return 'clipboard'
  }
  if (payment.value.status === 'PAID') {
    return 'celebrate'
  }
  if (payment.value.status === 'PENDING') {
    return 'hourglass'
  }
  if (payment.value.status === 'FAILED' || payment.value.status === 'SUSPENDED') {
    return 'warning'
  }
  return 'clipboard'
})
const paymentStateTitle = computed(() => {
  const status = payment.value?.status
  if (status === 'PAID') {
    return t('payment.paymentSuccessful')
  }
  if (status === 'PENDING') {
    return t('payment.paymentPendingTitle')
  }
  if (status === 'FAILED') {
    return t('payment.paymentFailedTitle')
  }
  if (status === 'SUSPENDED') {
    return t('payment.paymentHeldTitle')
  }
  if (status === 'REFUNDED' || status === 'PARTIALLY_REFUNDED') {
    return t('payment.paymentRefundedTitle')
  }
  return t('payment.paymentProcessingTitle')
})
const paymentStateHint = computed(() => {
  const status = payment.value?.status
  if (status === 'PAID') {
    return t('payment.paymentSuccessfulHint')
  }
  if (status === 'PENDING') {
    if (paymentRedirectUrl.value) {
      return t('payment.paymentPendingRedirectHint')
    }
    return t('payment.paymentPendingHint')
  }
  if (status === 'FAILED') {
    return t('payment.paymentFailedHint')
  }
  if (status === 'SUSPENDED') {
    return t('payment.paymentHeldHint')
  }
  if (status === 'REFUNDED' || status === 'PARTIALLY_REFUNDED') {
    return t('payment.paymentRefundedHint')
  }
  return t('payment.paymentProcessingHint')
})
const providerResultPending = computed(() => payment.value?.status === 'PENDING')
const paymentCreationAllowed = computed(
  () => payment.value === null || payment.value.status === 'FAILED',
)

function readOrderQueue(): number[] {
  const rawOrderIds = Array.isArray(route.query.orderIds)
    ? route.query.orderIds.join(',')
    : String(route.query.orderIds ?? '')
  const candidates = [route.params.orderId, route.query.orderId, ...rawOrderIds.split(',')]
  return Array.from(
    new Set(
      candidates
        .map((value) => Number(value ?? 0))
        .filter((value) => Number.isSafeInteger(value) && value > 0),
    ),
  )
}

function rememberOrder(orderId: number) {
  if (!Number.isSafeInteger(orderId) || orderId <= 0 || orderQueue.value.includes(orderId)) {
    return
  }
  orderQueue.value = [...orderQueue.value, orderId]
}

function localized(english: string, chinese: string): string {
  return isChinese.value ? chinese : english
}

function safePaymentMethod(method: string): string {
  const labels: Record<string, [string, string]> = {
    WECHAT: ['WeChat Pay', '\u5fae\u4fe1\u652f\u4ed8'],
    ALIPAY: ['Alipay', '\u652f\u4ed8\u5b9d'],
    BANK_CARD: ['Bank card', '\u94f6\u884c\u5361'],
  }
  const label = labels[method]
  return label
    ? localized(label[0], label[1])
    : localized('Other method', '\u5176\u4ed6\u65b9\u5f0f')
}

function safePaymentStatus(status: string): string {
  const labels: Record<string, [string, string]> = {
    PENDING: ['Pending', '\u5f85\u652f\u4ed8'],
    PAID: ['Paid', '\u5df2\u652f\u4ed8'],
    PARTIALLY_REFUNDED: ['Partially refunded', '\u90e8\u5206\u9000\u6b3e'],
    REFUNDED: ['Refunded', '\u5df2\u9000\u6b3e'],
    SUSPENDED: ['On hold', '\u5df2\u6682\u505c'],
    FAILED: ['Failed', '\u652f\u4ed8\u5931\u8d25'],
  }
  const label = labels[status]
  return label ? localized(label[0], label[1]) : localized('Processing', '\u5904\u7406\u4e2d')
}

function paymentStatusType(status: PaymentStatus): 'success' | 'warning' | 'info' | 'danger' {
  if (status === 'PAID') {
    return 'success'
  }
  if (status === 'PENDING' || status === 'PARTIALLY_REFUNDED') {
    return 'warning'
  }
  if (status === 'REFUNDED') {
    return 'info'
  }
  return 'danger'
}

function isPaymentResponse(value: unknown): value is PaymentResponse {
  return Boolean(
    value &&
    typeof value === 'object' &&
    typeof (value as PaymentResponse).paymentNo === 'string' &&
    typeof (value as PaymentResponse).status === 'string',
  )
}

async function loadPayment() {
  if (fundsOperationPending.value) {
    return
  }
  rememberOrder(form.orderId)
  await fetchPayment()
}

async function fetchPayment() {
  if (!form.orderId) {
    paymentResource.reset()
    return
  }
  await paymentResource.load(
    async () => {
      try {
        const result = await paymentsApi.paymentForOrder(form.orderId)
        return isPaymentResponse(result) ? result : null
      } catch (error) {
        if (error instanceof ApiError && error.status === 404) {
          return null
        }
        throw error
      }
    },
    {
      isEmpty: (result) => result === null,
      preserveData: true,
    },
  )
}

async function selectOrder(orderId: number) {
  if (paymentControlsLocked.value || orderId === form.orderId) {
    return
  }
  form.orderId = orderId
  createError.value = ''
  redirectError.value = ''
  refundError.value = ''
  refundAmount.value = 0
  refundReason.value = ''
  paymentResource.reset()
  await router.replace({
    path: `/payment/${orderId}`,
    query: orderQueue.value.length > 1 ? { orderIds: orderQueue.value.join(',') } : undefined,
  })
  await fetchPayment()
}

async function setCurrentPayment(result: PaymentResponse) {
  await paymentResource.load(() => Promise.resolve(result), {
    isEmpty: () => false,
    preserveData: false,
    timeoutMs: 0,
  })
}

function openPaymentProvider(destination = paymentRedirectUrl.value) {
  if (!destination || redirectPending.value) {
    return
  }

  redirectPending.value = true
  redirectError.value = ''
  try {
    navigateToPaymentProvider(destination)
  } catch {
    redirectError.value = t('payment.redirectUnavailable')
  } finally {
    redirectPending.value = false
  }
}

async function submitPayment() {
  if (!form.orderId || paymentControlsLocked.value || !paymentCreationAllowed.value) {
    return
  }

  const payload = {
    orderId: form.orderId,
    method: form.method,
    bankCardNo: form.method === 'BANK_CARD' ? form.bankCardNo : undefined,
    totpCode: form.totpCode || undefined,
  }
  createPending.value = true
  createError.value = ''
  redirectError.value = ''
  try {
    paymentResource.cancel()
    const intent = getIdempotencyIntent('payment:create', payload)
    const created = await paymentsApi.createPayment(payload, intent.key)
    intent.complete()
    await setCurrentPayment(created)
    if (created.status === 'PAID') {
      notify.success(t('payment.paymentSuccessful'), {
        key: `payment:${payload.orderId}:paid`,
      })
    } else {
      notify.info(t('payment.paymentPending'), {
        key: `payment:${payload.orderId}:pending`,
      })
      if (created.status === 'PENDING') {
        const destination = resolvePaymentRedirectUrl(created.paymentUrl, window.location.href)
        if (destination) {
          openPaymentProvider(destination)
        } else if (created.paymentUrl?.trim()) {
          redirectError.value = t('payment.redirectUnavailable')
        }
      }
    }
  } catch {
    createError.value = t('payment.createFailed')
  } finally {
    createPending.value = false
  }
}

async function submitRefund() {
  const targetPayment = payment.value
  if (!targetPayment || refundAmount.value <= 0 || paymentControlsLocked.value) {
    return
  }

  const payload = {
    paymentNo: targetPayment.paymentNo,
    amount: refundAmount.value,
    reason: refundReason.value || undefined,
  }
  refundPending.value = true
  refundError.value = ''
  try {
    const confirmed = await notify.confirm({
      title: t('common.refund'),
      content: `${t('common.confirm')} ${t('common.refund')}`,
      type: 'warning',
    })
    if (!confirmed) {
      return
    }

    paymentResource.cancel()
    const intent = getIdempotencyIntent('payment:refund', payload)
    await paymentsApi.refundPayment(payload, intent.key)
    intent.complete()
    notify.success(t('payment.refundSubmitted'), {
      key: `payment:${targetPayment.paymentNo}:refunded`,
    })
    refundAmount.value = 0
    refundReason.value = ''
    refundPending.value = false
    await fetchPayment()
  } catch {
    refundError.value = t('payment.refundFailed')
  } finally {
    refundPending.value = false
  }
}

onMounted(() => {
  void loadPayment()
})
</script>

<template>
  <div class="route-view payment-view">
    <PageHeader :title="$t('nav.payment')">
      <template #actions>
        <el-button
          :icon="RefreshRight"
          :loading="paymentResource.status.value === 'updating'"
          :disabled="paymentControlsLocked || !form.orderId"
          @click="loadPayment"
        >
          {{ $t('common.refresh') }}
        </el-button>
      </template>
    </PageHeader>

    <section
      v-if="orderQueue.length > 1"
      class="order-queue"
      :aria-label="$t('payment.orderQueue')"
    >
      <div class="order-queue__heading">
        <strong>{{ $t('payment.orderQueue') }}</strong>
        <span>{{ orderQueue.length }}</span>
      </div>
      <div class="order-queue__list">
        <button
          v-for="orderId in orderQueue"
          :key="orderId"
          type="button"
          class="order-queue__item"
          :class="{ 'is-current': orderId === form.orderId }"
          :aria-pressed="orderId === form.orderId"
          :disabled="paymentControlsLocked"
          @click="selectOrder(orderId)"
        >
          {{ $t('payment.orderId') }} {{ orderId }}
        </button>
      </div>
    </section>

    <section class="payment-task lookup-task" :aria-label="$t('common.search')">
      <h2>
        <el-icon aria-hidden="true"><Search /></el-icon>
        {{ $t('common.search') }} {{ $t('common.payment') }}
      </h2>
      <form class="task-form task-form--inline" @submit.prevent="loadPayment">
        <el-input-number
          v-model="form.orderId"
          :min="1"
          controls-position="right"
          :disabled="paymentControlsLocked"
          :aria-label="$t('payment.orderId')"
          :placeholder="$t('payment.orderId')"
        />
        <el-button
          type="primary"
          native-type="submit"
          :loading="paymentResource.status.value === 'loading'"
          :disabled="paymentControlsLocked || !form.orderId"
        >
          {{ $t('common.search') }}
        </el-button>
      </form>
    </section>

    <div class="payment-workspace">
      <section class="payment-task create-task" :aria-label="$t('payment.createPayment')">
        <h2>
          <el-icon aria-hidden="true"><Wallet /></el-icon>
          {{ $t('payment.createPayment') }}
        </h2>
        <p v-if="!paymentCreationAllowed" class="payment-create-lock" role="status">
          {{ $t('payment.existingPaymentLock') }}
        </p>
        <form v-if="paymentCreationAllowed" class="task-form" @submit.prevent="submitPayment">
          <el-segmented
            v-model="form.method"
            :options="[
              { label: $t('payment.wechat'), value: 'WECHAT' },
              { label: $t('payment.alipay'), value: 'ALIPAY' },
              { label: $t('payment.bankCard'), value: 'BANK_CARD' },
            ]"
            :aria-label="$t('payment.paymentMethod')"
            :disabled="paymentControlsLocked || !paymentCreationAllowed"
          />
          <el-input
            v-if="form.method === 'BANK_CARD'"
            v-model="form.bankCardNo"
            :prefix-icon="CreditCard"
            autocomplete="off"
            inputmode="numeric"
            :disabled="paymentControlsLocked || !paymentCreationAllowed"
            :aria-label="$t('payment.bankCardNo')"
            :placeholder="$t('payment.bankCardNo')"
          />
          <el-input
            v-model="form.totpCode"
            autocomplete="one-time-code"
            :disabled="paymentControlsLocked || !paymentCreationAllowed"
            :aria-label="$t('payment.totpCode')"
            :placeholder="$t('payment.totpCode')"
          />
          <p v-if="createError" class="task-error" role="alert">{{ createError }}</p>
          <el-button
            type="primary"
            native-type="submit"
            :loading="createPending"
            :disabled="paymentControlsLocked || !paymentCreationAllowed || !form.orderId"
          >
            {{ $t('payment.submitPayment') }}
          </el-button>
        </form>
      </section>

      <AsyncStateView
        :status="paymentResource.status.value"
        :error="paymentResource.error.value"
        :empty-title="$t('payment.noPayment')"
        :empty-description="$t('payment.noPaymentHint')"
        @retry="loadPayment"
      >
        <template #idle>
          <section class="payment-empty" role="status">
            <MascotState pose="clipboard" size="sm" :alt="$t('payment.noPayment')" />
            <div>
              <h2>{{ $t('payment.noPayment') }}</h2>
              <p>{{ $t('payment.noPaymentHint') }}</p>
            </div>
          </section>
        </template>

        <template #empty>
          <section class="payment-empty" role="status">
            <MascotState pose="clipboard" size="sm" :alt="$t('payment.noPayment')" />
            <div>
              <h2>{{ $t('payment.noPayment') }}</h2>
              <p>{{ $t('payment.noPaymentHint') }}</p>
            </div>
          </section>
        </template>

        <template #error>
          <section class="payment-empty" role="alert">
            <MascotState pose="warning" size="sm" :alt="$t('payment.loadFailed')" />
            <div>
              <h2>{{ $t('payment.loadFailed') }}</h2>
              <el-button :icon="RefreshRight" @click="loadPayment">
                {{ $t('common.retry') }}
              </el-button>
            </div>
          </section>
        </template>

        <DataTableShell v-if="payment" :aria-label="$t('payment.paymentStatus')">
          <section class="payment-task current-payment">
            <div class="payment-status-stage" :data-status="payment.status">
              <MascotState :pose="paymentPose" size="md" :alt="paymentStateTitle" />
              <div class="payment-status-stage__copy">
                <span class="payment-status-stage__eyebrow">
                  {{ $t('payment.currentOrder') }} #{{ payment.orderId }}
                </span>
                <h2>{{ paymentStateTitle }}</h2>
                <p>{{ paymentStateHint }}</p>
                <div class="payment-status-stage__actions">
                  <el-tag :type="paymentStatusType(payment.status)" disable-transitions>
                    {{ safePaymentStatus(payment.status) }}
                  </el-tag>
                  <el-button
                    v-if="paymentRedirectUrl"
                    data-testid="payment-provider-continue"
                    type="primary"
                    :icon="Position"
                    :loading="redirectPending"
                    :disabled="paymentControlsLocked"
                    @click="openPaymentProvider()"
                  >
                    {{ $t('payment.continueAtProvider') }}
                  </el-button>
                  <el-button
                    v-if="providerResultPending"
                    :icon="RefreshRight"
                    :loading="paymentResource.status.value === 'updating'"
                    :disabled="paymentControlsLocked"
                    @click="loadPayment"
                  >
                    {{ $t('payment.checkStatus') }}
                  </el-button>
                </div>
                <p
                  v-if="redirectError || paymentRedirectRejected"
                  data-testid="payment-redirect-error"
                  class="payment-redirect-error"
                  role="alert"
                >
                  {{ redirectError || $t('payment.redirectUnavailable') }}
                </p>
              </div>
            </div>

            <header class="payment-task__heading">
              <h2>
                <el-icon aria-hidden="true"><Money /></el-icon>
                {{ $t('common.payment') }} {{ payment.paymentNo }}
              </h2>
            </header>

            <dl class="payment-summary">
              <div>
                <dt>{{ $t('payment.paymentMethod') }}</dt>
                <dd>{{ safePaymentMethod(payment.method) }}</dd>
              </div>
              <div>
                <dt>{{ $t('payment.paymentAmount') }}</dt>
                <dd>{{ money(payment.amount) }}</dd>
              </div>
              <div>
                <dt>{{ $t('payment.created') }}</dt>
                <dd>{{ dateTime(payment.createTime) }}</dd>
              </div>
              <div v-if="payment.bankCardLast4">
                <dt>{{ $t('payment.bankCard') }}</dt>
                <dd>**** {{ payment.bankCardLast4 }}</dd>
              </div>
            </dl>

            <form
              v-if="refundable > 0"
              class="refund-task task-form"
              @submit.prevent="submitRefund"
            >
              <h3>{{ $t('common.refund') }}</h3>
              <el-input-number
                v-model="refundAmount"
                :min="0"
                :max="refundable"
                :precision="2"
                controls-position="right"
                :disabled="paymentControlsLocked"
                :aria-label="$t('payment.paymentAmount')"
              />
              <el-input
                v-model="refundReason"
                :disabled="paymentControlsLocked"
                :aria-label="$t('payment.refundReason')"
                :placeholder="$t('payment.refundReason')"
              />
              <p v-if="refundError" class="task-error" role="alert">{{ refundError }}</p>
              <el-button
                type="warning"
                native-type="submit"
                :loading="refundPending"
                :disabled="paymentControlsLocked || refundAmount <= 0 || refundable <= 0"
              >
                {{ $t('common.refund') }}
              </el-button>
            </form>
          </section>
        </DataTableShell>
      </AsyncStateView>
    </div>
  </div>
</template>

<style scoped>
.payment-view {
  display: grid;
  gap: var(--space-5);
}

.order-queue {
  display: grid;
  gap: var(--space-3);
  padding: var(--space-3) 0;
  border-top: 1px solid var(--color-line);
  border-bottom: 1px solid var(--color-line);
}

.order-queue__heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
}

.order-queue__heading span {
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  border-radius: var(--radius-circle);
  background: var(--color-brand-soft);
  color: var(--color-brand);
  font-size: var(--text-xs);
  font-weight: 800;
}

.order-queue__list {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.order-queue__item {
  min-height: 38px;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-line);
  border-radius: var(--radius-control);
  background: var(--color-surface);
  color: var(--color-text);
  cursor: pointer;
  font: inherit;
}

.order-queue__item:hover,
.order-queue__item.is-current {
  border-color: var(--color-brand);
  background: var(--color-brand-soft);
  color: var(--color-brand);
}

.order-queue__item:focus-visible {
  outline: var(--focus-width) solid var(--focus-ring);
  outline-offset: var(--focus-offset);
}

.order-queue__item:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.payment-workspace {
  display: grid;
  grid-template-columns: minmax(280px, 0.72fr) minmax(0, 1.28fr);
  gap: var(--space-6);
  align-items: start;
}

.payment-task {
  display: grid;
  gap: var(--space-4);
  min-width: 0;
  padding-top: var(--space-4);
  border-top: 1px solid var(--color-line);
}

.payment-task h2,
.payment-task h3,
.payment-empty h2 {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin: 0;
  font-size: var(--text-base);
}

.task-form {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--space-3);
  align-items: start;
}

.task-form--inline {
  grid-template-columns: minmax(220px, 360px) auto;
  justify-content: start;
}

.task-form > .el-button {
  justify-self: start;
  min-height: 40px;
}

.create-task {
  position: sticky;
  top: calc(var(--consumer-header-height, 72px) + var(--space-4));
}

.payment-create-lock {
  margin: 0;
  padding: var(--space-3);
  border-left: 3px solid var(--color-warning);
  background: var(--color-warning-soft);
  color: var(--color-text);
  font-size: var(--text-sm);
  line-height: var(--leading-normal);
}

.payment-status-stage {
  display: grid;
  grid-template-columns: minmax(132px, 180px) minmax(0, 1fr);
  gap: var(--space-5);
  align-items: center;
  padding: var(--space-5);
  border: 1px solid var(--color-line);
  border-radius: var(--radius-surface);
  background: var(--color-surface-subtle);
}

.payment-status-stage[data-status='PAID'] {
  border-color: color-mix(in srgb, var(--color-success) 42%, var(--color-line));
  background: var(--color-success-soft);
}

.payment-status-stage[data-status='PENDING'] {
  border-color: color-mix(in srgb, var(--color-warning) 42%, var(--color-line));
  background: var(--color-warning-soft);
}

.payment-status-stage[data-status='FAILED'],
.payment-status-stage[data-status='SUSPENDED'] {
  border-color: color-mix(in srgb, var(--color-danger) 42%, var(--color-line));
  background: var(--color-danger-soft);
}

.payment-status-stage__copy {
  display: grid;
  gap: var(--space-2);
  min-width: 0;
}

.payment-status-stage__copy h2 {
  font-size: var(--text-xl);
}

.payment-status-stage__copy p {
  margin: 0;
  color: var(--color-text);
  line-height: var(--leading-relaxed);
}

.payment-status-stage__eyebrow {
  color: var(--color-brand);
  font-size: var(--text-xs);
  font-weight: 800;
  text-transform: uppercase;
}

.payment-status-stage__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  margin-top: var(--space-1);
}

.payment-redirect-error {
  margin: var(--space-1) 0 0;
  color: var(--color-danger);
  font-size: var(--text-sm);
}

.payment-task__heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
}

.payment-summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: var(--space-3);
  margin: 0;
  padding: var(--space-4) 0;
  border-top: 1px dashed var(--color-line);
  border-bottom: 1px dashed var(--color-line);
}

.payment-summary div {
  display: grid;
  gap: var(--space-1);
  min-width: 0;
  padding-left: var(--space-3);
  border-left: 2px solid var(--color-line);
}

.payment-summary dt {
  color: var(--color-text-muted);
  font-size: var(--text-xs);
}

.payment-summary dd {
  margin: 0;
  overflow-wrap: anywhere;
  font-weight: 700;
}

.refund-task {
  margin-top: var(--space-1);
  padding-top: var(--space-4);
  border-top: 1px solid var(--color-line);
}

.refund-task h3,
.refund-task .task-error {
  grid-column: 1 / -1;
}

.task-error {
  margin: 0;
  color: var(--color-danger);
  font-size: var(--text-sm);
}

.payment-empty {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  min-height: 220px;
  padding: var(--space-5) 0;
  color: var(--color-text-muted);
}

.payment-empty p {
  margin: var(--space-2) 0 0;
}

@media (max-width: 960px) {
  .payment-workspace {
    grid-template-columns: 1fr;
  }

  .create-task {
    position: static;
  }

  .task-form {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .task-form > .el-segmented,
  .task-form > .task-error,
  .task-form > .el-button {
    grid-column: 1 / -1;
  }
}

@media (max-width: 640px) {
  .task-form,
  .task-form--inline {
    grid-template-columns: 1fr;
  }

  .task-form > *,
  .task-form > .el-button {
    grid-column: 1;
    justify-self: stretch;
    width: 100%;
  }

  .task-form > .el-button {
    min-height: 44px;
  }

  .payment-status-stage {
    grid-template-columns: 1fr;
    justify-items: center;
    padding: var(--space-4);
    text-align: center;
  }

  .payment-status-stage__actions {
    justify-content: center;
  }

  .payment-empty {
    display: grid;
    justify-items: center;
    text-align: center;
  }
}
</style>
