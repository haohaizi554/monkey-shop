<script setup lang="ts">
import { Delete, Plus, RefreshRight, Search, Wallet } from '@element-plus/icons-vue'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { isPositiveApiId, normalizeApiId, sameApiId, type ApiId } from '@/api/ids'
import { adminPaymentForOrder, adminRefundPayment, reconcilePayment } from '@/api/payments'
import AdminCommerceNav from '@/components/admin/AdminCommerceNav.vue'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatusTag from '@/components/ui/StatusTag.vue'
import { useAsyncState } from '@/composables/useAsyncState'
import { useNotify } from '@/composables/useNotify'
import type {
  PaymentMethod,
  PaymentReconciliationResponse,
  PaymentResponse,
  ReconciliationLine,
} from '@/types'
import { money, paymentMethodLabel } from '@/utils/format'
import { getIdempotencyIntent } from '@/utils/idempotencyIntent'

defineOptions({ name: 'PaymentOperationsView' })

interface EditableReconciliationLine {
  paymentNo: string
  providerTradeNo: string
  amount: string
}

function defaultReportDate() {
  const date = new Date()
  date.setDate(date.getDate() - 1)
  return date.toISOString().slice(0, 10)
}

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const notify = useNotify()
const paymentState = useAsyncState<PaymentResponse>({ preserveData: true })
const reconciliationState = useAsyncState<PaymentReconciliationResponse>({
  preserveData: true,
})
const orderId = ref('')
const refundAmount = ref('')
const refundReason = ref('')
const refundPending = ref(false)
const provider = ref<PaymentMethod>('WECHAT')
const reportDate = ref(defaultReportDate())
const reconciliationLines = ref<EditableReconciliationLine[]>([
  { paymentNo: '', providerTradeNo: '', amount: '' },
])
const payment = computed(() => paymentState.data.value)
const reconciliation = computed(() => reconciliationState.data.value)
const loadedPaymentReady = computed(
  () =>
    Boolean(payment.value) &&
    sameApiId(payment.value?.orderId, orderId.value) &&
    !paymentState.isLoading.value,
)
const orderIdValid = computed(() => isPositiveApiId(orderId.value))
const remainingRefund = computed(() => {
  if (!payment.value) {
    return 0
  }
  return Math.max(
    0,
    Number(payment.value.paidAmount || payment.value.amount) -
      Number(payment.value.refundedAmount || 0),
  )
})
const canRefund = computed(
  () =>
    loadedPaymentReady.value &&
    payment.value?.status !== 'REFUNDED' &&
    Number(refundAmount.value) > 0 &&
    Number(refundAmount.value) <= remainingRefund.value &&
    !refundPending.value,
)
const canReconcile = computed(
  () =>
    Boolean(reportDate.value) &&
    reconciliationLines.value.length > 0 &&
    reconciliationLines.value.every(
      (line) =>
        line.paymentNo.trim().length > 0 &&
        Number.isFinite(Number(line.amount)) &&
        Number(line.amount) >= 0,
    ),
)

async function loadPayment(id: ApiId) {
  const result = await paymentState.load(() => adminPaymentForOrder(id), {
    preserveData: true,
  })
  if (result) {
    const remaining = Math.max(
      0,
      Number(result.paidAmount || result.amount) - Number(result.refundedAmount || 0),
    )
    refundAmount.value = remaining > 0 ? remaining.toFixed(2) : ''
  }
}

async function submitPaymentLookup() {
  const id = normalizeApiId(orderId.value)
  if (!isPositiveApiId(id)) {
    return
  }
  const queryId = id
  if (route.query.orderId !== queryId) {
    await router.replace({ query: { ...route.query, orderId: queryId } })
    return
  }
  await loadPayment(id)
}

async function issueRefund() {
  const currentPayment = payment.value
  if (!currentPayment || !canRefund.value) {
    return
  }
  const payload = {
    paymentNo: currentPayment.paymentNo,
    amount: Number(refundAmount.value).toFixed(2),
    reason: refundReason.value.trim() || undefined,
  }
  const accepted = await notify.confirm({
    content: t('adminCommerce.refundPaymentConfirm', {
      amount: money(payload.amount),
      paymentNo: currentPayment.paymentNo,
    }),
    confirmText: t('adminCommerce.refundPayment'),
    type: 'warning',
  })
  if (!accepted) {
    return
  }

  refundPending.value = true
  try {
    const intent = getIdempotencyIntent('admin:payment-refund', payload)
    await adminRefundPayment(payload, intent.key)
    intent.complete()
    await loadPayment(currentPayment.orderId)
    refundReason.value = ''
    notify.success(t('adminCommerce.refundComplete'), {
      key: `payment:refund:${currentPayment.paymentNo}`,
    })
  } catch (caught) {
    notify.fromApiError(caught, 'common.unableToRefund')
  } finally {
    refundPending.value = false
  }
}

function addReconciliationLine() {
  reconciliationLines.value.push({ paymentNo: '', providerTradeNo: '', amount: '' })
}

function removeReconciliationLine(index: number) {
  if (reconciliationLines.value.length === 1) {
    reconciliationLines.value[0] = { paymentNo: '', providerTradeNo: '', amount: '' }
    return
  }
  reconciliationLines.value.splice(index, 1)
}

async function runReconciliation() {
  if (!canReconcile.value || reconciliationState.isLoading.value) {
    return
  }
  const lines: ReconciliationLine[] = reconciliationLines.value.map((line) => ({
    paymentNo: line.paymentNo.trim(),
    providerTradeNo: line.providerTradeNo.trim() || undefined,
    amount: Number(line.amount).toFixed(2),
  }))
  await reconciliationState.load(
    () =>
      reconcilePayment({
        provider: provider.value,
        reportDate: reportDate.value,
        lines,
      }),
    { preserveData: true },
  )
}

watch(
  () => route.query.orderId,
  (value) => {
    const raw = Array.isArray(value) ? value[0] : value
    const id = normalizeApiId(raw)
    if (isPositiveApiId(id)) {
      orderId.value = id
      void loadPayment(id)
    }
  },
  { immediate: true },
)
</script>

<template>
  <div class="route-view commerce-page">
    <PageHeader
      :eyebrow="t('adminCommerce.workspace')"
      :title="t('adminCommerce.paymentsTitle')"
      :description="t('adminCommerce.paymentsDescription')"
    />

    <AdminCommerceNav />

    <section class="commerce-section" :aria-labelledby="'payment-details-title'">
      <div class="commerce-section__heading">
        <div>
          <h2 id="payment-details-title">{{ t('adminCommerce.paymentTitle') }}</h2>
        </div>
      </div>

      <div class="commerce-form-grid">
        <div class="commerce-field">
          <span>{{ t('adminCommerce.orderId') }}</span>
          <el-input
            id="payment-order-id"
            v-model="orderId"
            inputmode="numeric"
            :aria-label="t('adminCommerce.orderId')"
            @keyup.enter="submitPaymentLookup"
          />
        </div>
        <div class="commerce-actions">
          <el-button
            type="primary"
            :icon="Search"
            :loading="paymentState.isLoading.value"
            :disabled="!orderIdValid"
            @click="submitPaymentLookup"
          >
            {{ t('adminCommerce.loadPayment') }}
          </el-button>
          <el-button
            v-if="payment"
            :icon="RefreshRight"
            :loading="paymentState.isLoading.value"
            @click="loadPayment(payment.orderId)"
          >
            {{ t('common.refresh') }}
          </el-button>
        </div>
      </div>

      <AsyncStateView
        :status="paymentState.status.value"
        mode="detail"
        :error="paymentState.error.value"
        preserve-content-on-error
        @retry="orderId && loadPayment(orderId)"
      >
        <template #idle>
          <p class="commerce-inline-state">{{ t('adminCommerce.noPaymentSelected') }}</p>
        </template>

        <dl v-if="payment" class="commerce-kv">
          <div>
            <dt>{{ t('adminCommerce.paymentNo') }}</dt>
            <dd>{{ payment.paymentNo }}</dd>
          </div>
          <div>
            <dt>{{ t('adminCommerce.paymentMethod') }}</dt>
            <dd>{{ paymentMethodLabel(payment.method) }}</dd>
          </div>
          <div>
            <dt>{{ t('adminCommerce.paymentStatus') }}</dt>
            <dd><StatusTag :status="payment.status" /></dd>
          </div>
          <div>
            <dt>{{ t('adminCommerce.paidAmount') }}</dt>
            <dd>{{ money(payment.paidAmount) }}</dd>
          </div>
          <div>
            <dt>{{ t('adminCommerce.refundedAmount') }}</dt>
            <dd>{{ money(payment.refundedAmount) }}</dd>
          </div>
        </dl>

        <div v-if="payment && remainingRefund > 0" class="commerce-form-grid">
          <div class="commerce-field">
            <span>{{ t('adminCommerce.refundAmount') }}</span>
            <el-input
              id="payment-refund-amount"
              v-model="refundAmount"
              inputmode="decimal"
              :aria-label="t('adminCommerce.refundAmount')"
            />
          </div>
          <div class="commerce-field">
            <span>{{ t('adminCommerce.refundReason') }}</span>
            <el-input
              id="payment-refund-reason"
              v-model="refundReason"
              :placeholder="t('adminCommerce.refundReasonPlaceholder')"
              :aria-label="t('adminCommerce.refundReason')"
            />
          </div>
          <div class="commerce-actions commerce-actions--end commerce-field--wide">
            <el-button
              type="danger"
              plain
              :icon="Wallet"
              :loading="refundPending"
              :disabled="!canRefund"
              @click="issueRefund"
            >
              {{ t('adminCommerce.refundPayment') }}
            </el-button>
          </div>
        </div>
      </AsyncStateView>
    </section>

    <section class="commerce-section" :aria-labelledby="'reconciliation-title'">
      <div class="commerce-section__heading">
        <div>
          <h2 id="reconciliation-title">{{ t('adminCommerce.reconciliation') }}</h2>
          <p>{{ t('adminCommerce.reconciliationDescription') }}</p>
        </div>
        <el-button :icon="Plus" @click="addReconciliationLine">
          {{ t('adminCommerce.addLine') }}
        </el-button>
      </div>

      <div class="commerce-form-grid">
        <div class="commerce-field">
          <span>{{ t('adminCommerce.provider') }}</span>
          <el-select
            id="reconciliation-provider"
            v-model="provider"
            :aria-label="t('adminCommerce.provider')"
          >
            <el-option label="WeChat" value="WECHAT" />
            <el-option label="Alipay" value="ALIPAY" />
            <el-option label="Bank card" value="BANK_CARD" />
          </el-select>
        </div>
        <div class="commerce-field">
          <span>{{ t('adminCommerce.reportDate') }}</span>
          <el-date-picker
            id="reconciliation-date"
            v-model="reportDate"
            type="date"
            value-format="YYYY-MM-DD"
            :aria-label="t('adminCommerce.reportDate')"
          />
        </div>
      </div>

      <div>
        <div v-for="(line, index) in reconciliationLines" :key="index" class="commerce-line-editor">
          <div class="commerce-field">
            <span>{{ t('adminCommerce.paymentNo') }}</span>
            <el-input
              :id="`reconciliation-payment-${index}`"
              v-model="line.paymentNo"
              :aria-label="t('adminCommerce.paymentNo')"
            />
          </div>
          <div class="commerce-field">
            <span>{{ t('adminCommerce.providerTradeNo') }}</span>
            <el-input
              :id="`reconciliation-trade-${index}`"
              v-model="line.providerTradeNo"
              :aria-label="t('adminCommerce.providerTradeNo')"
            />
          </div>
          <div class="commerce-field">
            <span>{{ t('adminCommerce.lineAmount') }}</span>
            <el-input
              :id="`reconciliation-amount-${index}`"
              v-model="line.amount"
              inputmode="decimal"
              :aria-label="t('adminCommerce.lineAmount')"
            />
          </div>
          <el-tooltip :content="t('adminCommerce.removeLine')">
            <el-button
              circle
              :icon="Delete"
              :aria-label="t('adminCommerce.removeLine')"
              @click="removeReconciliationLine(index)"
            />
          </el-tooltip>
        </div>
      </div>

      <div class="commerce-actions commerce-actions--end">
        <el-button
          type="primary"
          :icon="RefreshRight"
          :loading="reconciliationState.isLoading.value"
          :disabled="!canReconcile"
          @click="runReconciliation"
        >
          {{ t('adminCommerce.runReconciliation') }}
        </el-button>
      </div>

      <AsyncStateView
        :status="reconciliationState.status.value"
        mode="detail"
        :error="reconciliationState.error.value"
        preserve-content-on-error
        @retry="runReconciliation"
      >
        <dl v-if="reconciliation" class="commerce-kv">
          <div>
            <dt>{{ t('common.status') }}</dt>
            <dd><StatusTag :status="reconciliation.status" /></dd>
          </div>
          <div>
            <dt>{{ t('adminCommerce.platformAmount') }}</dt>
            <dd>{{ money(reconciliation.platformAmount) }}</dd>
          </div>
          <div>
            <dt>{{ t('adminCommerce.providerAmount') }}</dt>
            <dd>{{ money(reconciliation.providerAmount) }}</dd>
          </div>
          <div>
            <dt>{{ t('adminCommerce.diffAmount') }}</dt>
            <dd>{{ money(reconciliation.diffAmount) }}</dd>
          </div>
          <div>
            <dt>{{ t('adminCommerce.issueCount') }}</dt>
            <dd>{{ reconciliation.issueCount }}</dd>
          </div>
        </dl>
      </AsyncStateView>
    </section>
  </div>
</template>
