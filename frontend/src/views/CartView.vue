<script setup lang="ts">
import { Delete, Refresh, ShoppingCart } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { onMounted, ref } from 'vue'
import { addCartItem, getCart, removeCartItem, selectCartItem, updateCartItem } from '@/api/cart'
import AppShell from '@/components/AppShell.vue'
import type { Cart } from '@/types'

const cart = ref<Cart | null>(null)
const loading = ref(false)
const saving = ref(false)
const skuId = ref<number | null>(null)
const shopId = ref<number | null>(1)
const quantity = ref(1)
const selected = ref(true)

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

async function addCurrentItem() {
  if (!skuId.value || !shopId.value) {
    return
  }
  saving.value = true
  try {
    cart.value = await addCartItem({
      skuId: skuId.value,
      shopId: shopId.value,
      quantity: quantity.value,
      selected: selected.value,
    })
    ElMessage.success('Cart updated')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Unable to update cart')
  } finally {
    saving.value = false
  }
}

async function updateQuantity(rowSkuId: number, nextQuantity: number) {
  saving.value = true
  try {
    cart.value = await updateCartItem(rowSkuId, { quantity: nextQuantity })
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Unable to update quantity')
  } finally {
    saving.value = false
  }
}

async function updateSelection(rowSkuId: number, nextSelected: boolean) {
  saving.value = true
  try {
    cart.value = await selectCartItem(rowSkuId, { selected: nextSelected })
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Unable to update selection')
  } finally {
    saving.value = false
  }
}

async function removeItem(rowSkuId: number) {
  saving.value = true
  try {
    cart.value = await removeCartItem(rowSkuId)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Unable to remove item')
  } finally {
    saving.value = false
  }
}

onMounted(loadCart)
</script>

<template>
  <AppShell>
    <section class="cart-layout">
      <div class="cart-toolbar">
        <el-input-number v-model="skuId" :min="1" controls-position="right" placeholder="SKU" />
        <el-input-number v-model="shopId" :min="1" controls-position="right" placeholder="Shop" />
        <el-input-number v-model="quantity" :min="1" :max="999" controls-position="right" />
        <el-switch v-model="selected" active-text="Selected" />
        <el-button type="primary" :icon="ShoppingCart" :loading="saving" @click="addCurrentItem">
          Add
        </el-button>
        <el-button :icon="Refresh" :loading="loading" @click="loadCart">Refresh</el-button>
      </div>

      <div class="cart-summary">
        <div>
          <span>Selected items</span>
          <strong>{{ cart?.selectedQuantity ?? 0 }}</strong>
        </div>
        <div>
          <span>Selected amount</span>
          <strong>{{ cart?.selectedAmount ?? '0.00' }}</strong>
        </div>
        <RouterLink class="checkout-link" to="/checkout">Checkout</RouterLink>
      </div>

      <el-table v-loading="loading" :data="cart?.items ?? []" class="cart-table">
        <el-table-column label="Selected" width="110">
          <template #default="{ row }">
            <el-switch
              :model-value="row.selected"
              @change="(value: boolean) => updateSelection(row.skuId, value)"
            />
          </template>
        </el-table-column>
        <el-table-column prop="productName" label="Product" min-width="180" />
        <el-table-column prop="skuId" label="SKU" min-width="100" />
        <el-table-column prop="shopId" label="Shop" min-width="100" />
        <el-table-column prop="unitPrice" label="Unit" min-width="100" />
        <el-table-column label="Qty" min-width="130">
          <template #default="{ row }">
            <el-input-number
              :model-value="row.quantity"
              :min="1"
              :max="999"
              controls-position="right"
              @change="(value: number | undefined) => updateQuantity(row.skuId, value ?? 1)"
            />
          </template>
        </el-table-column>
        <el-table-column prop="lineAmount" label="Amount" min-width="120" />
        <el-table-column label="" width="76">
          <template #default="{ row }">
            <el-button :icon="Delete" circle text type="danger" @click="removeItem(row.skuId)" />
          </template>
        </el-table-column>
      </el-table>
    </section>
  </AppShell>
</template>

<style scoped>
.cart-layout {
  display: grid;
  gap: 16px;
}

.cart-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}

.cart-toolbar .el-input-number {
  max-width: 160px;
}

.cart-summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
  gap: 12px;
  align-items: stretch;
}

.cart-summary > div,
.checkout-link {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 14px;
}

.cart-summary span {
  display: block;
  color: var(--el-text-color-secondary);
  font-size: 0.85rem;
}

.cart-summary strong {
  display: block;
  font-size: 1.35rem;
  margin-top: 4px;
}

.checkout-link {
  display: grid;
  place-items: center;
  color: var(--el-color-primary);
  font-weight: 700;
  text-decoration: none;
}

.cart-table {
  width: 100%;
}
</style>
