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
    ElMessage.error(error instanceof Error ? error.message : 'Unable to load cart')
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
    ElMessage.error(error instanceof Error ? error.message : 'Unable to preview checkout')
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
    ElMessage.success('Checkout submitted')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Unable to checkout')
  } finally {
    submitting.value = false
  }
}

onMounted(loadCart)
</script>

<template>
  <AppShell>
    <section class="checkout-layout">
      <div class="checkout-toolbar">
        <el-input-number
          v-model="addressId"
          :min="1"
          controls-position="right"
          placeholder="Address"
        />
        <el-input v-model="province" placeholder="Province" />
        <el-input v-model="couponText" placeholder="Coupons: PLATFORM-20,SHOP-10" />
        <el-button :icon="Refresh" :loading="loading" @click="runPreview">Preview</el-button>
        <el-button type="primary" :icon="Check" :loading="submitting" @click="submitCheckout">
          Submit
        </el-button>
      </div>

      <div class="checkout-summary">
        <div>
          <span>Cart selected</span>
          <strong>{{ cart?.selectedQuantity ?? 0 }}</strong>
        </div>
        <div>
          <span>Original</span>
          <strong>{{ preview?.originalAmount ?? cart?.selectedAmount ?? '0.00' }}</strong>
        </div>
        <div>
          <span>Discount</span>
          <strong>{{ preview?.discountAmount ?? '0.00' }}</strong>
        </div>
        <div>
          <span>Payable</span>
          <strong>{{ preview?.payableAmount ?? cart?.selectedAmount ?? '0.00' }}</strong>
        </div>
      </div>

      <div v-if="preview" class="suborder-list">
        <section v-for="subOrder in preview.subOrders" :key="subOrder.id" class="suborder-section">
          <div class="suborder-head">
            <span>Shop {{ subOrder.shopId }}</span>
            <strong>{{ subOrder.payableAmount }}</strong>
          </div>
          <el-table :data="subOrder.lines" class="checkout-table">
            <el-table-column prop="productName" label="Product" min-width="180" />
            <el-table-column prop="skuId" label="SKU" min-width="100" />
            <el-table-column prop="quantity" label="Qty" min-width="80" />
            <el-table-column prop="originalAmount" label="Original" min-width="110" />
            <el-table-column prop="discountAmount" label="Discount" min-width="110" />
            <el-table-column prop="payableAmount" label="Payable" min-width="110" />
            <el-table-column label="Reservation" min-width="180">
              <template #default="{ row }">
                <el-tag :type="row.warehouseId ? 'success' : 'info'" disable-transitions>
                  {{ row.warehouseId ? row.reservationKey : 'Preview' }}
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
