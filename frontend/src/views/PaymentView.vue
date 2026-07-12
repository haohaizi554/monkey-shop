<script setup lang="ts">
import { CreditCard, Money, RefreshRight, Search, Wallet } from '@element-plus/icons-vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import * as paymentsApi from '@/api/payments'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import DataTableShell from '@/components/ui/DataTableShell.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import { useAsyncState } from '@/composables/useAsyncState'
import { useNotify } from '@/composables/useNotify'
import type { PaymentMethod, PaymentResponse } from '@/types'
import { dateTime, money } from '@/utils/format'
import { getIdempotencyIntent } from '@/utils/idempotencyIntent'

const route = useRoute()
const { locale, t } = useI18n()
const notify = useNotify()
const paymentResource = useAsyncState<PaymentResponse | null>({ timeoutMs: 20000 })

const createPending = ref(false)
const createError = ref('')
const refundPending = ref(false)
const refundError = ref('')
const refundAmount = ref(0)
const refundReason = ref('')

const form = reactive({
  orderId: Number(route.params.orderId ?? route.query.orderId ?? 0),
  method: 'WECHAT' as PaymentMethod,
  bankCardNo: '',
  totpCode: '',
})

const payment = computed(() => paymentResource.data.value)
const fundsOperationPending = computed(() => createPending.value || refundPending.value)
const paymentControlsLocked = computed(
  () => fundsOperationPending.value || paymentResource.isLoading.value,
)
const refundable = computed(() => {
  if (!payment.value) {
    return 0
  }
  return Math.max(0, Number(payment.value.paidAmount) - Number(payment.value.refundedAmount))
})
const isChinese = computed(() => locale.value === 'zh')

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

function paymentStatusType(status: string): 'success' | 'warning' | 'info' | 'danger' {
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

async function loadPayment() {
  if (fundsOperationPending.value) {
    return
  }
  await fetchPayment()
}

async function fetchPayment() {
  if (!form.orderId) {
    paymentResource.reset()
    return
  }
  await paymentResource.load(() => paymentsApi.paymentForOrder(form.orderId), {
    isEmpty: (result) => result === null,
    preserveData: true,
  })
}

async function setCurrentPayment(result: PaymentResponse) {
  await paymentResource.load(() => Promise.resolve(result), {
    isEmpty: () => false,
    preserveData: false,
    timeoutMs: 0,
  })
}

async function submitPayment() {
  if (!form.orderId || paymentControlsLocked.value) {
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
  try {
    paymentResource.cancel()
    const intent = getIdempotencyIntent('payment:create', payload)
    const created = await paymentsApi.createPayment(payload, intent.key)
    intent.complete()
    createPending.value = false
    await setCurrentPayment(created)
    notify.success(t('payment.paymentCreated'), { key: `payment:${payload.orderId}:created` })
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

    <section class="payment-task create-task" :aria-label="$t('payment.createPayment')">
      <h2>
        <el-icon aria-hidden="true"><Wallet /></el-icon>
        {{ $t('payment.createPayment') }}
      </h2>
      <form class="task-form" @submit.prevent="submitPayment">
        <el-segmented
          v-model="form.method"
          :options="[
            { label: $t('payment.wechat'), value: 'WECHAT' },
            { label: $t('payment.alipay'), value: 'ALIPAY' },
            { label: $t('payment.bankCard'), value: 'BANK_CARD' },
          ]"
          :aria-label="$t('payment.paymentMethod')"
          :disabled="paymentControlsLocked"
        />
        <el-input
          v-if="form.method === 'BANK_CARD'"
          v-model="form.bankCardNo"
          :prefix-icon="CreditCard"
          autocomplete="off"
          inputmode="numeric"
          :disabled="paymentControlsLocked"
          :aria-label="$t('payment.bankCardNo')"
          :placeholder="$t('payment.bankCardNo')"
        />
        <el-input
          v-model="form.totpCode"
          autocomplete="one-time-code"
          :disabled="paymentControlsLocked"
          :aria-label="$t('payment.totpCode')"
          :placeholder="$t('payment.totpCode')"
        />
        <p v-if="createError" class="task-error" role="alert">{{ createError }}</p>
        <el-button
          type="primary"
          native-type="submit"
          :loading="createPending"
          :disabled="paymentControlsLocked || !form.orderId"
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
          <el-icon aria-hidden="true"><Money /></el-icon>
          <div>
            <h2>{{ $t('payment.noPayment') }}</h2>
            <p>{{ $t('payment.noPaymentHint') }}</p>
          </div>
        </section>
      </template>

      <template #error>
        <section class="payment-empty" role="alert">
          <el-icon aria-hidden="true"><Money /></el-icon>
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
          <header class="payment-task__heading">
            <h2>
              <el-icon aria-hidden="true"><Money /></el-icon>
              {{ $t('common.payment') }} {{ payment.paymentNo }}
            </h2>
            <el-tag :type="paymentStatusType(payment.status)" disable-transitions>
              {{ safePaymentStatus(payment.status) }}
            </el-tag>
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

          <form class="refund-task task-form" @submit.prevent="submitRefund">
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
</template>

<style scoped>
.payment-view {
  display: grid;
  gap: 18px;
}

.payment-task {
  border-top: 1px solid var(--el-border-color-lighter);
  display: grid;
  gap: 14px;
  padding: 18px 0 0;
}

.payment-task h2,
.payment-task h3,
.payment-empty h2 {
  align-items: center;
  display: flex;
  font-size: 1rem;
  gap: 8px;
  margin: 0;
}

.task-form {
  align-items: start;
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
}

.task-form--inline {
  grid-template-columns: minmax(220px, 360px) auto;
  justify-content: start;
}

.task-form > .el-button {
  justify-self: start;
  min-height: 40px;
}

.payment-task__heading {
  align-items: center;
  display: flex;
  gap: 12px;
  justify-content: space-between;
}

.payment-summary {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  margin: 0;
}

.payment-summary div {
  border-left: 2px solid var(--el-border-color);
  display: grid;
  gap: 4px;
  padding-left: 10px;
}

.payment-summary dt {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.payment-summary dd {
  margin: 0;
  overflow-wrap: anywhere;
}

.refund-task {
  border-top: 1px solid var(--el-border-color-lighter);
  margin-top: 4px;
  padding-top: 14px;
}

.refund-task h3,
.refund-task .task-error {
  grid-column: 1 / -1;
}

.task-error {
  color: var(--el-color-danger);
  font-size: 13px;
  margin: 0;
}

.payment-empty {
  align-items: start;
  color: var(--el-text-color-secondary);
  display: flex;
  gap: 12px;
  padding: 18px 0;
}

.payment-empty p {
  margin: 6px 0 0;
}

@media (max-width: 640px) {
  .task-form,
  .task-form--inline {
    grid-template-columns: 1fr;
  }

  .task-form > *,
  .task-form > .el-button {
    justify-self: stretch;
    width: 100%;
  }

  .task-form > .el-button {
    min-height: 44px;
  }
}
</style>
