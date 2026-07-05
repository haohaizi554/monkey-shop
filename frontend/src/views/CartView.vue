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
    ElMessage.error(error instanceof Error ? error.message : '无法加载购物车')
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
    ElMessage.success('购物车已更新')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '无法更新购物车')
  } finally {
    saving.value = false
  }
}

async function updateQuantity(rowSkuId: number, nextQuantity: number) {
  saving.value = true
  try {
    cart.value = await updateCartItem(rowSkuId, { quantity: nextQuantity })
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '无法更新数量')
  } finally {
    saving.value = false
  }
}

async function updateSelection(rowSkuId: number, nextSelected: boolean) {
  saving.value = true
  try {
    cart.value = await selectCartItem(rowSkuId, { selected: nextSelected })
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '无法更新选中状态')
  } finally {
    saving.value = false
  }
}

async function removeItem(rowSkuId: number) {
  saving.value = true
  try {
    cart.value = await removeCartItem(rowSkuId)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '无法移除商品')
  } finally {
    saving.value = false
  }
}

onMounted(loadCart)
</script>

<template>
  <AppShell>
    <section class="cart-layout">
      <section class="page-heading">
        <h1>{{ $t('nav.cart') }}</h1>
        <el-button :icon="Refresh" :loading="loading" @click="loadCart">刷新</el-button>
      </section>

      <div class="cart-toolbar">
        <el-input-number v-model="skuId" :min="1" controls-position="right" placeholder="SKU" />
        <el-input-number v-model="shopId" :min="1" controls-position="right" placeholder="店铺" />
        <el-input-number v-model="quantity" :min="1" :max="999" controls-position="right" />
        <el-switch v-model="selected" active-text="已选" />
        <el-button type="primary" :icon="ShoppingCart" :loading="saving" @click="addCurrentItem">
          加入购物车
        </el-button>
      </div>

      <div class="cart-summary">
        <div>
          <span>已选商品</span>
          <strong>{{ cart?.selectedQuantity ?? 0 }}</strong>
        </div>
        <div>
          <span>已选金额</span>
          <strong>{{ cart?.selectedAmount ?? '0.00' }}</strong>
        </div>
        <RouterLink class="checkout-link" to="/checkout">去结算</RouterLink>
      </div>

      <el-table v-loading="loading" :data="cart?.items ?? []" class="cart-table">
        <el-table-column label="已选" width="110">
          <template #default="{ row }">
            <el-switch
              :model-value="row.selected"
              @change="(value: boolean) => updateSelection(row.skuId, value)"
            />
          </template>
        </el-table-column>
        <el-table-column prop="productName" label="商品" min-width="180" />
        <el-table-column prop="skuId" label="SKU" min-width="100" />
        <el-table-column prop="shopId" label="店铺" min-width="100" />
        <el-table-column prop="unitPrice" label="单价" min-width="100" />
        <el-table-column label="数量" min-width="130">
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
        <el-table-column prop="lineAmount" label="金额" min-width="120" />
        <el-table-column label="" width="76">
          <template #default="{ row }">
            <el-button
              :aria-label="$t('common.delete')"
              :icon="Delete"
              circle
              text
              type="danger"
              @click="removeItem(row.skuId)"
            />
          </template>
        </el-table-column>
        <template #empty>
          <div class="cart-empty">
            <strong>购物车还是空的</strong>
            <span>先去商城选择商品，或用上方 SKU 快速加入。</span>
          </div>
        </template>
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

.cart-empty {
  display: grid;
  gap: 6px;
  padding: 28px 0;
  color: var(--text-muted);
}

.cart-empty strong {
  color: var(--text);
  font-size: 1rem;
}
</style>
