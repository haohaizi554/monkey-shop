<script setup lang="ts">
import { CreditCard, Money, RefreshRight, Wallet } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import * as paymentsApi from '@/api/payments'
import type { PaymentMethod, PaymentResponse } from '@/types'
import {
  dateTime,
  money,
  paymentMethodLabel,
  paymentStatusLabel,
  paymentStatusType,
} from '@/utils/format'

const route = useRoute()
const busy = ref(false)
const payment = ref<PaymentResponse | null>(null)
const refundAmount = ref(0)
const refundReason = ref('')

const form = reactive({
  orderId: Number(route.params.orderId ?? route.query.orderId ?? 0),
  method: 'WECHAT' as PaymentMethod,
  bankCardNo: '',
  totpCode: '',
})

const refundable = computed(() => {
  if (!payment.value) {
    return 0
  }
  return Math.max(0, Number(payment.value.paidAmount) - Number(payment.value.refundedAmount))
})

async function loadPayment() {
  if (!form.orderId) {
    return
  }
  busy.value = true
  try {
    payment.value = await paymentsApi.paymentForOrder(form.orderId)
  } catch {
    payment.value = null
  } finally {
    busy.value = false
  }
}

async function submitPayment() {
  if (!form.orderId) {
    return
  }
  busy.value = true
  try {
    payment.value = await paymentsApi.createPayment({
      orderId: form.orderId,
      method: form.method,
      bankCardNo: form.method === 'BANK_CARD' ? form.bankCardNo : undefined,
      totpCode: form.totpCode || undefined,
    })
    ElMessage.success('支付单已创建')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '无法创建支付单')
  } finally {
    busy.value = false
  }
}

async function submitRefund() {
  if (!payment.value || refundAmount.value <= 0) {
    return
  }
  busy.value = true
  try {
    await paymentsApi.refundPayment({
      paymentNo: payment.value.paymentNo,
      amount: refundAmount.value,
      reason: refundReason.value || undefined,
    })
    ElMessage.success('退款申请已受理')
    await loadPayment()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '无法提交退款')
  } finally {
    busy.value = false
  }
}

onMounted(() => {
  void loadPayment()
})
</script>

<template>
  <div class="route-view">
    <section class="page-heading">
      <h1>{{ $t('nav.payment') }}</h1>
      <el-button :icon="RefreshRight" @click="loadPayment"> 刷新 </el-button>
    </section>

    <section class="payment-layout">
      <form class="payment-panel" @submit.prevent="submitPayment">
        <h2>
          <el-icon><Wallet /></el-icon>
          发起支付
        </h2>
        <el-input-number
          v-model="form.orderId"
          :min="1"
          controls-position="right"
          placeholder="订单 ID"
        />
        <el-segmented
          v-model="form.method"
          :options="[
            { label: '微信', value: 'WECHAT' },
            { label: '支付宝', value: 'ALIPAY' },
            { label: '银行卡', value: 'BANK_CARD' },
          ]"
        />
        <el-input
          v-if="form.method === 'BANK_CARD'"
          v-model="form.bankCardNo"
          :prefix-icon="CreditCard"
          autocomplete="off"
          inputmode="numeric"
          placeholder="银行卡号"
        />
        <el-input v-model="form.totpCode" autocomplete="one-time-code" placeholder="动态验证码" />
        <el-button type="primary" native-type="submit" :loading="busy"> 提交支付 </el-button>
      </form>

      <section v-if="payment" class="payment-panel">
        <h2>
          <el-icon><Money /></el-icon>
          {{ payment.paymentNo }}
        </h2>
        <div class="metric-grid">
          <div class="metric-item">
            <span>支付方式</span>
            <el-tag disable-transitions>{{ paymentMethodLabel(payment.method) }}</el-tag>
          </div>
          <div class="metric-item">
            <span>支付状态</span>
            <el-tag :type="paymentStatusType(payment.status)" disable-transitions>
              {{ paymentStatusLabel(payment.status) }}
            </el-tag>
          </div>
          <div class="metric-item">
            <span>支付金额</span>
            <strong>{{ money(payment.amount) }}</strong>
          </div>
          <div class="metric-item">
            <span>创建时间</span>
            <strong>{{ dateTime(payment.createTime) }}</strong>
          </div>
        </div>
        <p v-if="payment.providerTradeNo">{{ payment.providerTradeNo }}</p>
        <p v-if="payment.bankCardLast4">**** {{ payment.bankCardLast4 }}</p>
        <form class="refund-row" @submit.prevent="submitRefund">
          <el-input-number
            v-model="refundAmount"
            :min="0"
            :max="refundable"
            :precision="2"
            controls-position="right"
          />
          <el-input v-model="refundReason" placeholder="退款原因" />
          <el-button type="warning" native-type="submit" :loading="busy">
            {{ $t('common.refund') }}
          </el-button>
        </form>
      </section>
      <section v-else class="payment-panel muted-panel">
        <h2>
          <el-icon><Money /></el-icon>
          暂无支付单
        </h2>
        <p>输入订单 ID 后可以查询或创建支付单。</p>
      </section>
    </section>
  </div>
</template>

<style scoped>
.payment-layout {
  display: grid;
  gap: 16px;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
}

.payment-panel {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  display: grid;
  gap: 12px;
  padding: 16px;
}

.payment-panel h2 {
  align-items: center;
  display: flex;
  font-size: 1rem;
  gap: 8px;
  margin: 0;
}

.metric-grid {
  display: grid;
  gap: 8px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.metric-item {
  align-items: start;
  background: var(--page);
  border-radius: 8px;
  display: grid;
  gap: 6px;
  min-width: 0;
  padding: 10px;
}

.metric-item span {
  color: var(--text-muted);
  font-size: 0.85rem;
}

.metric-item strong {
  min-width: 0;
  overflow-wrap: anywhere;
}

.refund-row {
  display: grid;
  gap: 10px;
}

@media (max-width: 640px) {
  .metric-grid {
    grid-template-columns: 1fr;
  }
}

.muted-panel {
  color: var(--text-muted);
}

.muted-panel p {
  margin: 0;
}
</style>
