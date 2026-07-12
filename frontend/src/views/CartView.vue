<script setup lang="ts">
import { Delete, Refresh, ShoppingCart } from '@element-plus/icons-vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { addCartItem, getCart, removeCartItem, selectCartItem, updateCartItem } from '@/api/cart'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import DataTableShell from '@/components/ui/DataTableShell.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import type { AsyncStatus } from '@/composables/useAsyncState'
import { useNotify } from '@/composables/useNotify'
import type { Cart } from '@/types'

const { t } = useI18n()
const notify = useNotify()
const cart = ref<Cart | null>(null)
const cartStatus = ref<AsyncStatus>('idle')
const cartError = ref<string | null>(null)
const adding = ref(false)
const pendingMutations = reactive(new Set<string>())
const rowErrors = reactive(new Map<number, string>())
const skuId = ref<number | null>(null)
const shopId = ref<number | null>(1)
const quantity = ref(1)
const selected = ref(true)
const cartBusy = computed(
  () =>
    cartStatus.value === 'loading' ||
    cartStatus.value === 'updating' ||
    adding.value ||
    pendingMutations.size > 0,
)

type MutationAction = 'quantity' | 'select' | 'remove'

function mutationKey(action: MutationAction, rowSkuId: number) {
  return `${action}:${rowSkuId}`
}

function mutationPending(action: MutationAction, rowSkuId: number) {
  return pendingMutations.has(mutationKey(action, rowSkuId))
}

function replaceCart(nextCart: Cart) {
  cart.value = nextCart
  cartStatus.value = nextCart.items.length === 0 ? 'empty' : 'success'
  cartError.value = null
}

async function loadCart() {
  const hadCart = cart.value !== null
  cartStatus.value = hadCart ? 'updating' : 'loading'
  cartError.value = null
  try {
    replaceCart(await getCart())
  } catch (error) {
    if (hadCart && cart.value) {
      cartStatus.value = cart.value.items.length === 0 ? 'empty' : 'success'
      notify.fromApiError(error, 'cart.loadFailed')
    } else {
      cartStatus.value = 'error'
      cartError.value = 'cart.loadFailed'
    }
  }
}

async function addCurrentItem() {
  if (!skuId.value || !shopId.value || adding.value) {
    return
  }
  adding.value = true
  try {
    replaceCart(
      await addCartItem({
        skuId: skuId.value,
        shopId: shopId.value,
        quantity: quantity.value,
        selected: selected.value,
      }),
    )
    notify.success(t('cart.updated'), { key: 'cart:updated' })
  } catch (error) {
    notify.fromApiError(error, 'cart.updateFailed')
  } finally {
    adding.value = false
  }
}

async function updateQuantity(rowSkuId: number, nextQuantity: number) {
  const key = mutationKey('quantity', rowSkuId)
  const row = cart.value?.items.find((item) => item.skuId === rowSkuId)
  if (!row || pendingMutations.has(key) || row.quantity === nextQuantity) {
    return
  }

  const previousQuantity = row.quantity
  rowErrors.delete(rowSkuId)
  pendingMutations.add(key)
  row.quantity = nextQuantity
  try {
    replaceCart(await updateCartItem(rowSkuId, { quantity: nextQuantity }))
  } catch {
    const currentRow = cart.value?.items.find((item) => item.skuId === rowSkuId)
    if (currentRow) {
      currentRow.quantity = previousQuantity
    }
    rowErrors.set(rowSkuId, t('cart.quantityUpdateFailed'))
  } finally {
    pendingMutations.delete(key)
  }
}

async function updateSelection(rowSkuId: number, nextSelected: boolean) {
  const key = mutationKey('select', rowSkuId)
  const row = cart.value?.items.find((item) => item.skuId === rowSkuId)
  if (!row || pendingMutations.has(key) || row.selected === nextSelected) {
    return
  }

  const previousSelected = row.selected
  rowErrors.delete(rowSkuId)
  pendingMutations.add(key)
  row.selected = nextSelected
  try {
    replaceCart(await selectCartItem(rowSkuId, { selected: nextSelected }))
  } catch {
    const currentRow = cart.value?.items.find((item) => item.skuId === rowSkuId)
    if (currentRow) {
      currentRow.selected = previousSelected
    }
    rowErrors.set(rowSkuId, t('cart.selectUpdateFailed'))
  } finally {
    pendingMutations.delete(key)
  }
}

async function removeItem(rowSkuId: number) {
  const key = mutationKey('remove', rowSkuId)
  if (pendingMutations.has(key)) {
    return
  }

  rowErrors.delete(rowSkuId)
  pendingMutations.add(key)
  try {
    replaceCart(await removeCartItem(rowSkuId))
  } catch {
    rowErrors.set(rowSkuId, t('cart.removeFailed'))
  } finally {
    pendingMutations.delete(key)
  }
}

function guardCheckout(event: MouseEvent) {
  if (!cart.value?.selectedQuantity) {
    event.preventDefault()
  }
}

onMounted(loadCart)
</script>

<template>
  <div class="route-view">
    <section class="cart-layout">
      <PageHeader :title="t('nav.cart')">
        <template #actions>
          <el-button
            :icon="Refresh"
            :loading="cartStatus === 'loading' || cartStatus === 'updating'"
            @click="loadCart"
          >
            {{ $t('common.refresh') }}
          </el-button>
        </template>
      </PageHeader>

      <div class="cart-summary">
        <div class="cart-summary__metric">
          <span>{{ $t('cart.selectedItems') }}</span>
          <strong>{{ cart?.selectedQuantity ?? 0 }}</strong>
        </div>
        <div class="cart-summary__metric">
          <span>{{ $t('cart.selectedAmount') }}</span>
          <strong>{{ cart?.selectedAmount ?? '0.00' }}</strong>
        </div>
        <RouterLink
          class="checkout-link"
          :class="{ 'is-disabled': !cart?.selectedQuantity }"
          to="/checkout"
          :aria-disabled="!cart?.selectedQuantity ? 'true' : undefined"
          @click="guardCheckout"
        >
          {{ $t('cart.checkout') }}
        </RouterLink>
      </div>

      <DataTableShell
        :aria-label="t('nav.cart')"
        :busy="cartBusy"
        :empty="cartStatus === 'empty'"
      >
        <template #toolbar>
          <div class="cart-toolbar">
            <el-input-number
              v-model="skuId"
              :min="1"
              controls-position="right"
              :placeholder="$t('cart.skuPlaceholder')"
            />
            <el-input-number
              v-model="shopId"
              :min="1"
              controls-position="right"
              :placeholder="$t('cart.shopPlaceholder')"
            />
            <el-input-number
              v-model="quantity"
              :aria-label="$t('common.quantity')"
              :min="1"
              :max="999"
              controls-position="right"
            />
            <el-switch
              v-model="selected"
              :aria-label="$t('cart.selected')"
              :active-text="$t('cart.selected')"
            />
            <el-button
              type="primary"
              :icon="ShoppingCart"
              :loading="adding"
              :disabled="!skuId || !shopId"
              @click="addCurrentItem"
            >
              {{ $t('common.addToCart') }}
            </el-button>
          </div>
        </template>

        <template #empty>
          <div class="cart-empty">
            <strong>{{ $t('common.cartEmpty') }}</strong>
            <span>{{ $t('cart.emptyHint') }}</span>
          </div>
        </template>

        <AsyncStateView :status="cartStatus" :error="cartError" @retry="loadCart">
          <el-table :data="cart?.items ?? []" class="cart-table">
            <el-table-column :label="$t('cart.selected')" width="110">
              <template #default="{ row }">
                <el-switch
                  :model-value="row.selected"
                  :aria-label="`${t('cart.selected')} ${row.productName}`"
                  :disabled="mutationPending('select', row.skuId)"
                  @change="(value: boolean) => updateSelection(row.skuId, value)"
                />
              </template>
            </el-table-column>
            <el-table-column
              prop="productName"
              :label="$t('common.productName')"
              min-width="180"
            />
            <el-table-column prop="skuId" label="SKU" min-width="100" />
            <el-table-column
              prop="shopId"
              :label="$t('cart.shopPlaceholder')"
              min-width="100"
            />
            <el-table-column prop="unitPrice" :label="$t('common.price')" min-width="100" />
            <el-table-column :label="$t('common.quantity')" min-width="170">
              <template #default="{ row }">
                <div class="cart-quantity-cell">
                  <el-input-number
                    :model-value="row.quantity"
                    :aria-label="`${t('common.quantity')} ${row.productName}`"
                    :min="1"
                    :max="999"
                    :disabled="mutationPending('quantity', row.skuId)"
                    controls-position="right"
                    @change="(value: number | undefined) => updateQuantity(row.skuId, value ?? 1)"
                  />
                  <p v-if="rowErrors.get(row.skuId)" class="cart-line-error" role="alert">
                    {{ rowErrors.get(row.skuId) }}
                  </p>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="lineAmount" :label="$t('common.subtotal')" min-width="120" />
            <el-table-column :label="''" width="76">
              <template #default="{ row }">
                <el-button
                  :aria-label="`${t('common.delete')} ${row.productName}`"
                  :icon="Delete"
                  :loading="mutationPending('remove', row.skuId)"
                  circle
                  text
                  type="danger"
                  @click="removeItem(row.skuId)"
                />
              </template>
            </el-table-column>
          </el-table>

          <div class="cart-mobile-list">
            <article v-for="row in cart?.items ?? []" :key="row.skuId" class="cart-mobile-item">
              <header class="cart-mobile-item__header">
                <strong>{{ row.productName }}</strong>
                <el-button
                  :aria-label="`${t('common.delete')} ${row.productName}`"
                  :icon="Delete"
                  :loading="mutationPending('remove', row.skuId)"
                  circle
                  text
                  type="danger"
                  @click="removeItem(row.skuId)"
                />
              </header>
              <dl class="cart-mobile-item__facts">
                <div><dt>SKU</dt><dd>{{ row.skuId }}</dd></div>
                <div><dt>{{ $t('cart.shopPlaceholder') }}</dt><dd>{{ row.shopId }}</dd></div>
                <div><dt>{{ $t('common.price') }}</dt><dd>{{ row.unitPrice }}</dd></div>
                <div><dt>{{ $t('common.subtotal') }}</dt><dd>{{ row.lineAmount }}</dd></div>
              </dl>
              <div class="cart-mobile-item__controls">
                <div class="cart-mobile-item__control">
                  <span>{{ $t('cart.selected') }}</span>
                  <el-switch
                    :model-value="row.selected"
                    :aria-label="`${t('cart.selected')} ${row.productName}`"
                    :disabled="mutationPending('select', row.skuId)"
                    @change="(value: boolean) => updateSelection(row.skuId, value)"
                  />
                </div>
                <div class="cart-mobile-item__control">
                  <span>{{ $t('common.quantity') }}</span>
                  <el-input-number
                    :model-value="row.quantity"
                    :aria-label="`${t('common.quantity')} ${row.productName}`"
                    :min="1"
                    :max="999"
                    :disabled="mutationPending('quantity', row.skuId)"
                    controls-position="right"
                    @change="(value: number | undefined) => updateQuantity(row.skuId, value ?? 1)"
                  />
                </div>
              </div>
              <p v-if="rowErrors.get(row.skuId)" class="cart-line-error" role="alert">
                {{ rowErrors.get(row.skuId) }}
              </p>
            </article>
          </div>
        </AsyncStateView>
      </DataTableShell>
    </section>
  </div>
</template>

<style scoped>
.cart-layout {
  display: grid;
  gap: var(--space-4);
  min-width: 0;
}

.cart-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  width: 100%;
}

.cart-toolbar .el-input-number {
  max-width: 160px;
}

.cart-summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
  gap: var(--space-3);
  align-items: stretch;
}

.cart-summary__metric,
.checkout-link {
  border: 1px solid var(--color-line);
  border-radius: var(--radius-surface);
  padding: var(--space-3);
  background: var(--color-surface);
}

.cart-summary span {
  display: block;
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}

.cart-summary strong {
  display: block;
  margin-top: var(--space-1);
  font-size: var(--text-xl);
}

.checkout-link {
  display: grid;
  place-items: center;
  min-height: 48px;
  color: var(--color-brand);
  font-weight: 700;
  text-decoration: none;
}

.checkout-link.is-disabled {
  color: var(--el-text-color-disabled);
  cursor: not-allowed;
  opacity: 0.6;
}

.cart-table {
  width: 100%;
  min-width: 940px;
}

.cart-quantity-cell {
  display: grid;
  gap: var(--space-1);
  align-items: start;
  min-width: 0;
}

.cart-quantity-cell .el-input-number {
  width: 140px;
}

.cart-line-error {
  margin: 0;
  color: var(--color-danger);
  font-size: var(--text-xs);
  line-height: var(--leading-snug);
}

.cart-mobile-list {
  display: none;
}

.cart-empty {
  display: grid;
  gap: var(--space-2);
  justify-items: center;
  padding: var(--space-6) 0;
  color: var(--color-text-muted);
}

.cart-empty strong {
  color: var(--color-text);
  font-size: var(--text-base);
}

@media (max-width: 720px) {
  .cart-layout {
    padding-bottom: 128px;
  }

  .cart-summary {
    position: fixed;
    right: 0;
    bottom: calc(60px + env(safe-area-inset-bottom));
    left: 0;
    z-index: 30;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) minmax(112px, 1fr);
    gap: 0;
    padding: var(--space-2) var(--space-3);
    border-top: 1px solid var(--color-line);
    background: var(--color-surface);
    box-shadow: var(--shadow-overlay);
  }

  .cart-summary__metric,
  .checkout-link {
    min-width: 0;
    border: 0;
    border-radius: 0;
    padding: var(--space-2);
  }

  .cart-summary span {
    overflow-wrap: anywhere;
    font-size: var(--text-xs);
  }

  .cart-summary strong {
    font-size: var(--text-lg);
  }

  .checkout-link {
    min-height: 44px;
    border-left: 1px solid var(--color-line);
    text-align: center;
  }

  .cart-toolbar {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .cart-toolbar .el-input-number {
    width: 100%;
    max-width: none;
  }

  .cart-toolbar .el-button {
    grid-column: 1 / -1;
    min-height: 44px;
  }

  .cart-table {
    display: none;
  }

  .cart-mobile-list {
    display: grid;
  }

  .cart-mobile-item {
    display: grid;
    gap: var(--space-3);
    min-width: 0;
    padding: var(--space-4);
    border-bottom: 1px solid var(--color-line);
  }

  .cart-mobile-item:last-child {
    border-bottom: 0;
  }

  .cart-mobile-item__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-3);
    min-width: 0;
  }

  .cart-mobile-item__header strong {
    min-width: 0;
    overflow-wrap: anywhere;
  }

  .cart-mobile-item__facts {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: var(--space-2) var(--space-4);
    margin: 0;
  }

  .cart-mobile-item__facts div,
  .cart-mobile-item__control {
    display: grid;
    gap: var(--space-1);
    min-width: 0;
  }

  .cart-mobile-item__facts dt,
  .cart-mobile-item__controls span {
    color: var(--color-text-muted);
    font-size: var(--text-xs);
  }

  .cart-mobile-item__facts dd {
    min-width: 0;
    margin: 0;
    overflow-wrap: anywhere;
    font-weight: 650;
  }

  .cart-mobile-item__controls {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(132px, 1fr);
    gap: var(--space-3);
    align-items: end;
  }

  .cart-mobile-item__controls .el-input-number {
    width: 100%;
  }
}
</style>
