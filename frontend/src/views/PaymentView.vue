<script setup lang="ts">
import { CreditCard, Money, RefreshRight, Wallet } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import * as paymentsApi from '@/api/payments'
import AppShell from '@/components/AppShell.vue'
import type { PaymentMethod, PaymentResponse } from '@/types'
import { dateTime, money } from '@/utils/format'

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
    ElMessage.success('Payment created')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Unable to create payment')
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
    ElMessage.success('Refund accepted')
    await loadPayment()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Unable to refund payment')
  } finally {
    busy.value = false
  }
}

onMounted(() => {
  void loadPayment()
})
</script>

<template>
  <AppShell>
    <section class="page-heading">
      <h1>{{ $t('nav.payment') }}</h1>
      <el-button :icon="RefreshRight" @click="loadPayment">
        {{ $t('common.reset') }}
      </el-button>
    </section>

    <section class="payment-layout">
      <form class="payment-panel" @submit.prevent="submitPayment">
        <h2>
          <el-icon><Wallet /></el-icon>
          {{ $t('common.payment') }}
        </h2>
        <el-input-number v-model="form.orderId" :min="1" controls-position="right" />
        <el-segmented
          v-model="form.method"
          :options="[
            { label: 'WeChat', value: 'WECHAT' },
            { label: 'Alipay', value: 'ALIPAY' },
            { label: 'Card', value: 'BANK_CARD' },
          ]"
        />
        <el-input
          v-if="form.method === 'BANK_CARD'"
          v-model="form.bankCardNo"
          :prefix-icon="CreditCard"
          autocomplete="off"
          inputmode="numeric"
          placeholder="Bank card"
        />
        <el-input v-model="form.totpCode" autocomplete="one-time-code" placeholder="TOTP" />
        <el-button type="primary" native-type="submit" :loading="busy">
          {{ $t('common.pay') }}
        </el-button>
      </form>

      <section v-if="payment" class="payment-panel">
        <h2>
          <el-icon><Money /></el-icon>
          {{ payment.paymentNo }}
        </h2>
        <div class="metric-grid">
          <span>{{ payment.method }}</span>
          <strong>{{ payment.status }}</strong>
          <span>{{ money(payment.amount) }}</span>
          <span>{{ dateTime(payment.createTime) }}</span>
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
          <el-input v-model="refundReason" placeholder="Refund reason" />
          <el-button type="warning" native-type="submit" :loading="busy">
            {{ $t('common.refund') }}
          </el-button>
        </form>
      </section>
    </section>
  </AppShell>
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

.refund-row {
  display: grid;
  gap: 10px;
}
</style>
