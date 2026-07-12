<script setup lang="ts">
import { Check, Refresh, Tickets } from '@element-plus/icons-vue'
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { checkoutCart, getCart, previewCartCheckout } from '@/api/cart'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import DataTableShell from '@/components/ui/DataTableShell.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import type { AsyncStatus } from '@/composables/useAsyncState'
import { useNotify } from '@/composables/useNotify'
import type { Cart, CartCheckout, CartCheckoutRequest } from '@/types'

const router = useRouter()
const { t } = useI18n()
const notify = useNotify()
const cart = ref<Cart | null>(null)
const preview = ref<CartCheckout | null>(null)
const addressId = ref<number | null>(null)
const province = ref('')
const couponText = ref('')
const cartStatus = ref<AsyncStatus>('idle')
const cartError = ref<string | null>(null)
const previewStatus = ref<AsyncStatus>('idle')
const previewError = ref<string | null>(null)
const submitting = ref(false)

function requestBody(): CartCheckoutRequest | null {
  if (!addressId.value) {
    notify.warning(t('checkout.selectAddressFirst'), { key: 'checkout:address-required' })
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
  const hadCart = cart.value !== null
  cartStatus.value = hadCart ? 'updating' : 'loading'
  cartError.value = null
  try {
    cart.value = await getCart()
    cartStatus.value = cart.value.selectedQuantity > 0 ? 'success' : 'empty'
  } catch (error) {
    if (hadCart && cart.value) {
      cartStatus.value = cart.value.selectedQuantity > 0 ? 'success' : 'empty'
      notify.fromApiError(error, 'checkout.loadCartFailed')
    } else {
      cartStatus.value = 'error'
      cartError.value = 'checkout.loadCartFailed'
    }
  }
}

async function runPreview() {
  if (previewStatus.value === 'loading' || previewStatus.value === 'updating') {
    return
  }
  const body = requestBody()
  if (!body) {
    return
  }
  const hadPreview = preview.value !== null
  previewStatus.value = hadPreview ? 'updating' : 'loading'
  previewError.value = null
  try {
    preview.value = await previewCartCheckout(body)
    previewStatus.value = 'success'
  } catch (error) {
    if (hadPreview) {
      previewStatus.value = 'success'
      notify.fromApiError(error, 'checkout.previewFailed')
    } else {
      previewStatus.value = 'error'
      previewError.value = 'checkout.previewFailed'
    }
  }
}

async function submitCheckout() {
  if (submitting.value) {
    return
  }
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
    previewStatus.value = 'success'
    await loadCart()
    notify.success(t('checkout.submitSuccess'), { key: 'checkout:submitted' })
    await router.push('/orders')
  } catch (error) {
    notify.fromApiError(error, 'checkout.submitFailed')
  } finally {
    submitting.value = false
  }
}

onMounted(loadCart)
</script>

<template>
  <div class="route-view">
    <section class="checkout-layout">
      <PageHeader :title="t('shop.checkout')" :description="t('checkout.hint')" />

      <AsyncStateView :status="cartStatus" :error="cartError" @retry="loadCart">
        <template #empty>
          <div class="checkout-empty">
            <el-icon aria-hidden="true"><Tickets /></el-icon>
            <strong>{{ $t('common.cartEmpty') }}</strong>
            <p>{{ $t('cart.emptyHint') }}</p>
          </div>
        </template>

        <section class="checkout-form-section" :aria-label="$t('common.selectAddress')">
          <h2>{{ $t('common.selectAddress') }}</h2>
          <div class="checkout-toolbar">
            <div class="checkout-field">
              <span>{{ $t('common.selectAddress') }}</span>
              <el-input-number
                v-model="addressId"
                :aria-label="$t('common.selectAddress')"
                :min="1"
                controls-position="right"
                :placeholder="$t('checkout.addressIdPlaceholder')"
              />
            </div>
            <div class="checkout-field">
              <span>{{ $t('checkout.provincePlaceholder') }}</span>
              <el-input
                v-model="province"
                :aria-label="$t('checkout.provincePlaceholder')"
                :placeholder="$t('checkout.provincePlaceholder')"
              />
            </div>
            <div class="checkout-field checkout-field--coupon">
              <span>{{ $t('checkout.discount') }}</span>
              <el-input
                v-model="couponText"
                :aria-label="$t('checkout.discount')"
                :placeholder="$t('checkout.couponPlaceholder')"
              />
            </div>
            <el-button
              class="checkout-preview-button"
              :icon="Refresh"
              :loading="previewStatus === 'loading' || previewStatus === 'updating'"
              @click="runPreview"
            >
              {{ $t('checkout.preview') }}
            </el-button>
          </div>
        </section>

        <section class="checkout-preview-section" :aria-label="$t('checkout.stockReservation')">
          <h2>{{ $t('checkout.stockReservation') }}</h2>
          <AsyncStateView
            :status="previewStatus"
            :error="previewError"
            @retry="runPreview"
          >
            <template #idle>
              <div class="checkout-empty">
                <el-icon aria-hidden="true"><Tickets /></el-icon>
                <p>{{ $t('checkout.emptyHint') }}</p>
              </div>
            </template>

            <div v-if="preview?.subOrders.length" class="suborder-list">
              <section
                v-for="subOrder in preview.subOrders"
                :key="subOrder.id"
                class="suborder-section"
              >
                <div class="suborder-head">
                  <span>{{ $t('checkout.shop') }} {{ subOrder.shopId }}</span>
                  <strong>{{ subOrder.payableAmount }}</strong>
                </div>
                <DataTableShell :aria-label="`${t('checkout.shop')} ${subOrder.shopId}`">
                  <el-table :data="subOrder.lines" class="checkout-table">
                    <el-table-column
                      prop="productName"
                      :label="$t('checkout.product')"
                      min-width="180"
                    />
                    <el-table-column prop="skuId" label="SKU" min-width="100" />
                    <el-table-column
                      prop="quantity"
                      :label="$t('checkout.quantity')"
                      min-width="80"
                    />
                    <el-table-column
                      prop="originalAmount"
                      :label="$t('checkout.originalAmount')"
                      min-width="110"
                    />
                    <el-table-column
                      prop="discountAmount"
                      :label="$t('checkout.discount')"
                      min-width="110"
                    />
                    <el-table-column
                      prop="payableAmount"
                      :label="$t('checkout.payable')"
                      min-width="110"
                    />
                    <el-table-column :label="$t('checkout.stockReservation')" min-width="180">
                      <template #default="{ row }">
                        <el-tag :type="row.warehouseId ? 'success' : 'info'" disable-transitions>
                          {{ row.warehouseId ? row.reservationKey : $t('checkout.previewing') }}
                        </el-tag>
                      </template>
                    </el-table-column>
                  </el-table>

                  <div class="checkout-mobile-lines">
                    <article
                      v-for="row in subOrder.lines"
                      :key="row.id"
                      class="checkout-mobile-line"
                    >
                      <strong>{{ row.productName }}</strong>
                      <dl>
                        <div><dt>SKU</dt><dd>{{ row.skuId }}</dd></div>
                        <div><dt>{{ $t('checkout.quantity') }}</dt><dd>{{ row.quantity }}</dd></div>
                        <div>
                          <dt>{{ $t('checkout.originalAmount') }}</dt>
                          <dd>{{ row.originalAmount }}</dd>
                        </div>
                        <div>
                          <dt>{{ $t('checkout.discount') }}</dt>
                          <dd>{{ row.discountAmount }}</dd>
                        </div>
                        <div>
                          <dt>{{ $t('checkout.payable') }}</dt>
                          <dd>{{ row.payableAmount }}</dd>
                        </div>
                        <div>
                          <dt>{{ $t('checkout.stockReservation') }}</dt>
                          <dd>{{ row.warehouseId ? row.reservationKey : $t('checkout.previewing') }}</dd>
                        </div>
                      </dl>
                    </article>
                  </div>
                </DataTableShell>
              </section>
            </div>

            <div v-else class="checkout-empty">
              <el-icon aria-hidden="true"><Tickets /></el-icon>
              <p>{{ $t('checkout.emptyHint') }}</p>
            </div>
          </AsyncStateView>
        </section>

        <div class="checkout-summary">
          <div class="checkout-summary__metric">
            <span>{{ $t('checkout.selectedItems') }}</span>
            <strong>{{ cart?.selectedQuantity ?? 0 }}</strong>
          </div>
          <div class="checkout-summary__metric">
            <span>{{ $t('checkout.originalAmount') }}</span>
            <strong>{{ preview?.originalAmount ?? cart?.selectedAmount ?? '0.00' }}</strong>
          </div>
          <div class="checkout-summary__metric">
            <span>{{ $t('checkout.discount') }}</span>
            <strong>{{ preview?.discountAmount ?? '0.00' }}</strong>
          </div>
          <div class="checkout-summary__metric">
            <span>{{ $t('checkout.payable') }}</span>
            <strong>{{ preview?.payableAmount ?? cart?.selectedAmount ?? '0.00' }}</strong>
          </div>
          <el-button
            class="checkout-submit"
            type="primary"
            :icon="Check"
            :loading="submitting"
            :disabled="submitting"
            @click="submitCheckout"
          >
            {{ $t('checkout.submit') }}
          </el-button>
        </div>
      </AsyncStateView>
    </section>
  </div>
</template>

<style scoped>
.checkout-layout {
  display: grid;
  gap: var(--space-4);
  min-width: 0;
}

.checkout-form-section,
.checkout-preview-section {
  display: grid;
  gap: var(--space-3);
  min-width: 0;
}

.checkout-form-section h2,
.checkout-preview-section h2 {
  font-size: var(--text-lg);
}

.checkout-toolbar {
  display: grid;
  grid-template-columns: minmax(150px, 180px) minmax(160px, 220px) minmax(220px, 1fr) auto;
  gap: var(--space-3);
  align-items: end;
}

.checkout-field {
  display: grid;
  gap: var(--space-1);
  min-width: 0;
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}

.checkout-field .el-input-number {
  width: 100%;
}

.checkout-preview-button {
  min-width: 112px;
  min-height: 40px;
}

.checkout-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(130px, 1fr)) minmax(140px, auto);
  gap: var(--space-3);
  align-items: stretch;
}

.checkout-summary__metric {
  min-width: 0;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-surface);
  padding: var(--space-3);
  background: var(--color-surface);
}

.checkout-summary span {
  display: block;
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}

.checkout-summary strong {
  display: block;
  margin-top: var(--space-1);
  overflow-wrap: anywhere;
  font-size: var(--text-xl);
}

.checkout-submit {
  width: 100%;
  min-width: 140px;
  min-height: 48px;
}

.suborder-list {
  display: grid;
  gap: var(--space-4);
}

.suborder-section {
  display: grid;
  gap: var(--space-2);
  min-width: 0;
}

.suborder-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  font-weight: 700;
}

.checkout-table {
  width: 100%;
  min-width: 900px;
}

.checkout-mobile-lines {
  display: none;
}

.checkout-empty {
  display: grid;
  justify-items: center;
  gap: var(--space-2);
  min-height: 160px;
  align-content: center;
  padding: var(--space-5);
  color: var(--color-text-muted);
  text-align: center;
}

.checkout-empty .el-icon {
  width: 36px;
  height: 36px;
}

.checkout-empty strong {
  color: var(--color-text);
}

@media (max-width: 840px) {
  .checkout-toolbar {
    grid-template-columns: 1fr;
  }

  .checkout-preview-button {
    width: 100%;
    min-height: 44px;
  }
}

@media (max-width: 720px) {
  .checkout-layout {
    padding-bottom: 208px;
  }

  .checkout-summary {
    position: fixed;
    right: 0;
    bottom: calc(60px + env(safe-area-inset-bottom));
    left: 0;
    z-index: 30;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 0;
    padding: var(--space-2) var(--space-3);
    border-top: 1px solid var(--color-line);
    background: var(--color-surface);
    box-shadow: var(--shadow-overlay);
  }

  .checkout-summary__metric {
    border: 0;
    border-radius: 0;
    padding: var(--space-1) var(--space-2);
  }

  .checkout-summary span {
    overflow-wrap: anywhere;
    font-size: var(--text-xs);
  }

  .checkout-summary strong {
    font-size: var(--text-base);
  }

  .checkout-submit {
    grid-column: 1 / -1;
    min-width: 0;
    min-height: 44px;
    margin-top: var(--space-2);
  }

  .checkout-table {
    display: none;
  }

  .checkout-mobile-lines {
    display: grid;
  }

  .checkout-mobile-line {
    display: grid;
    gap: var(--space-3);
    min-width: 0;
    padding: var(--space-4);
    border-bottom: 1px solid var(--color-line);
  }

  .checkout-mobile-line:last-child {
    border-bottom: 0;
  }

  .checkout-mobile-line > strong {
    overflow-wrap: anywhere;
  }

  .checkout-mobile-line dl {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: var(--space-2) var(--space-4);
    margin: 0;
  }

  .checkout-mobile-line dl div {
    display: grid;
    gap: var(--space-1);
    min-width: 0;
  }

  .checkout-mobile-line dt {
    color: var(--color-text-muted);
    font-size: var(--text-xs);
  }

  .checkout-mobile-line dd {
    min-width: 0;
    margin: 0;
    overflow-wrap: anywhere;
    font-weight: 650;
  }
}
</style>
