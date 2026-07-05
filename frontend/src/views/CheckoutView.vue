<script setup lang="ts">
import { Check, Refresh, Tickets } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { onMounted, ref } from 'vue'
import { checkoutCart, getCart, previewCartCheckout } from '@/api/cart'
import AppShell from '@/components/AppShell.vue'
import type { Cart, CartCheckout, CartCheckoutRequest } from '@/types'

const cart = ref<Cart | null>(null)
const preview = ref<CartCheckout | null>(null)
const addressId = ref<number | null>(null)
const province = ref('')
const couponText = ref('')
const loading = ref(false)
const submitting = ref(false)

function requestBody(): CartCheckoutRequest | null {
  if (!addressId.value) {
    return null
  }
  return {
    addressId: addressId.value,
    province: province.value || undefined,
    couponCodes: couponText.value
      .split(',')
      .map((value) => value.trim())
      .filter(Boolean),
  }
}

async function loadCart() {
  loading.value = true
  try {
    cart.value = await getCart()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '无法加载购物车')
  } finally {
    loading.value = false
  }
}

async function runPreview() {
  const body = requestBody()
  if (!body) {
    return
  }
  loading.value = true
  try {
    preview.value = await previewCartCheckout(body)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '无法预览结算')
  } finally {
    loading.value = false
  }
}

async function submitCheckout() {
  const body = requestBody()
  if (!body) {
    return
  }
  submitting.value = true
  try {
    const key =
      typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
        ? crypto.randomUUID()
        : `checkout-${Date.now()}`
    preview.value = await checkoutCart(body, key)
    await loadCart()
    ElMessage.success('结算已提交')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '无法提交结算')
  } finally {
    submitting.value = false
  }
}

onMounted(loadCart)
</script>

<template>
  <AppShell>
    <section class="checkout-layout">
      <section class="page-heading">
        <h1>{{ $t('shop.checkout') }}</h1>
        <p>选择地址、核对优惠和应付金额后再提交。</p>
      </section>

      <div class="checkout-toolbar">
        <el-input-number
          v-model="addressId"
          :min="1"
          controls-position="right"
          placeholder="地址 ID"
        />
        <el-input v-model="province" placeholder="省份" />
        <el-input v-model="couponText" placeholder="优惠券：PLATFORM-20,SHOP-10" />
        <el-button :icon="Refresh" :loading="loading" @click="runPreview">预览</el-button>
        <el-button type="primary" :icon="Check" :loading="submitting" @click="submitCheckout">
          提交
        </el-button>
      </div>

      <div class="checkout-summary">
        <div>
          <span>已选商品</span>
          <strong>{{ cart?.selectedQuantity ?? 0 }}</strong>
        </div>
        <div>
          <span>原价</span>
          <strong>{{ preview?.originalAmount ?? cart?.selectedAmount ?? '0.00' }}</strong>
        </div>
        <div>
          <span>优惠</span>
          <strong>{{ preview?.discountAmount ?? '0.00' }}</strong>
        </div>
        <div>
          <span>应付</span>
          <strong>{{ preview?.payableAmount ?? cart?.selectedAmount ?? '0.00' }}</strong>
        </div>
      </div>

      <div v-if="preview" class="suborder-list">
        <section v-for="subOrder in preview.subOrders" :key="subOrder.id" class="suborder-section">
          <div class="suborder-head">
            <span>店铺 {{ subOrder.shopId }}</span>
            <strong>{{ subOrder.payableAmount }}</strong>
          </div>
          <el-table :data="subOrder.lines" class="checkout-table">
            <el-table-column prop="productName" label="商品" min-width="180" />
            <el-table-column prop="skuId" label="SKU" min-width="100" />
            <el-table-column prop="quantity" label="数量" min-width="80" />
            <el-table-column prop="originalAmount" label="原价" min-width="110" />
            <el-table-column prop="discountAmount" label="优惠" min-width="110" />
            <el-table-column prop="payableAmount" label="应付" min-width="110" />
            <el-table-column label="库存预占" min-width="180">
              <template #default="{ row }">
                <el-tag :type="row.warehouseId ? 'success' : 'info'" disable-transitions>
                  {{ row.warehouseId ? row.reservationKey : '预览中' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </div>

      <el-empty v-else :image-size="96">
        <template #description>
          <span class="checkout-empty-icon">
            <el-icon><Tickets /></el-icon>
          </span>
          <p>填写地址后可预览拆单、优惠和应付金额。</p>
        </template>
      </el-empty>
    </section>
  </AppShell>
</template>

<style scoped>
.checkout-layout {
  display: grid;
  gap: 16px;
}

.checkout-toolbar {
  display: grid;
  grid-template-columns: minmax(140px, 180px) minmax(160px, 220px) minmax(220px, 1fr) auto auto;
  gap: 10px;
  align-items: center;
}

.checkout-summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 12px;
}

.checkout-summary > div {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 14px;
}

.checkout-summary span {
  display: block;
  color: var(--el-text-color-secondary);
  font-size: 0.85rem;
}

.checkout-summary strong {
  display: block;
  font-size: 1.35rem;
  margin-top: 4px;
}

.suborder-list {
  display: grid;
  gap: 14px;
}

.suborder-section {
  display: grid;
  gap: 10px;
}

.suborder-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 700;
}

.checkout-table {
  width: 100%;
}

@media (max-width: 840px) {
  .checkout-toolbar {
    grid-template-columns: 1fr;
  }
}
</style>
