<script setup lang="ts">
import { Check, Location, Plus, Refresh, Tickets } from '@element-plus/icons-vue'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { checkoutCart, getCart, previewCartCheckout, type CartCheckoutResult } from '@/api/cart'
import { addressPage as fetchAddressPage } from '@/api/user'
import MascotState from '@/components/mascot/MascotState.vue'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import DataTableShell from '@/components/ui/DataTableShell.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import type { AsyncStatus } from '@/composables/useAsyncState'
import {
  checkoutDiscountTotals,
  checkoutOrderIds,
  normalizeCartCheckoutIntent,
} from '@/composables/useCheckout'
import { useNotify } from '@/composables/useNotify'
import type { Address, Cart, CartCheckoutRequest } from '@/types'
import { getIdempotencyIntent } from '@/utils/idempotencyIntent'

const router = useRouter()
const { t } = useI18n()
const notify = useNotify()
const cart = ref<Cart | null>(null)
const preview = ref<CartCheckoutResult | null>(null)
const addresses = ref<Address[]>([])
const addressPageNumber = ref(0)
const addressPageSize = 6
const addressTotal = ref(0)
const addressId = ref<number | null>(null)
const province = ref('')
const couponText = ref('')
const cartStatus = ref<AsyncStatus>('idle')
const cartError = ref<string | null>(null)
const addressStatus = ref<AsyncStatus>('idle')
const addressError = ref<string | null>(null)
const previewStatus = ref<AsyncStatus>('idle')
const previewError = ref<string | null>(null)
const submitting = ref(false)
const activePreviewRequestId = ref<number | null>(null)
const previewSnapshotKey = ref<string | null>(null)
let previewRequestSequence = 0
let addressRequestSequence = 0
let addressController: AbortController | null = null

interface CheckoutInputSnapshot {
  addressId: number | null
  province: string
  couponCodes: string[]
}

function captureInputSnapshot(): CheckoutInputSnapshot {
  return {
    addressId: addressId.value,
    province: province.value,
    couponCodes: couponText.value.split(','),
  }
}

function normalizedBody(snapshot: CheckoutInputSnapshot): CartCheckoutRequest | null {
  if (!snapshot.addressId) {
    notify.warning(t('checkout.selectAddressFirst'), { key: 'checkout:address-required' })
    return null
  }
  return normalizeCartCheckoutIntent({
    addressId: snapshot.addressId,
    province: snapshot.province,
    couponCodes: snapshot.couponCodes,
  })
}

function snapshotKey(snapshot: CheckoutInputSnapshot): string {
  return JSON.stringify(
    normalizeCartCheckoutIntent({
      addressId: snapshot.addressId ?? 0,
      province: snapshot.province,
      couponCodes: snapshot.couponCodes,
    }),
  )
}

const currentPreview = computed(() => {
  const currentKey = snapshotKey(captureInputSnapshot())
  return previewSnapshotKey.value === currentKey ? preview.value : null
})
const previewPending = computed(() => activePreviewRequestId.value !== null)
const selectedAddress = computed(
  () => addresses.value.find((address) => address.id === addressId.value) ?? null,
)
const discountTotals = computed(() => checkoutDiscountTotals(currentPreview.value?.subOrders ?? []))
const storeDiscount = computed(() => amount(discountTotals.value.store))
const platformDiscount = computed(() => amount(discountTotals.value.platform))

function amount(value: string | number | undefined): string {
  const numeric = Number(value ?? 0)
  return Number.isFinite(numeric) ? numeric.toFixed(2) : '0.00'
}

function maskedPhone(value: string): string {
  const trimmed = value.trim()
  if (trimmed.length < 7) {
    return trimmed
  }
  return `${trimmed.slice(0, 3)}****${trimmed.slice(-4)}`
}

function invalidatePreview() {
  preview.value = null
  previewSnapshotKey.value = null
  previewError.value = null
  previewStatus.value = 'idle'
  activePreviewRequestId.value = null
}

watch([addressId, province, couponText], invalidatePreview, { flush: 'sync' })

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

async function loadAddresses(pageNumber = addressPageNumber.value) {
  const requestId = ++addressRequestSequence
  addressController?.abort()
  const requestController = new AbortController()
  addressController = requestController
  const hadAddresses = addresses.value.length > 0
  addressStatus.value = hadAddresses ? 'updating' : 'loading'
  addressError.value = null
  try {
    const result = await fetchAddressPage({
      page: pageNumber,
      size: addressPageSize,
      sort: 'isDefault,desc',
      signal: requestController.signal,
    })
    if (requestId !== addressRequestSequence) return
    addressPageNumber.value = result.page
    addressTotal.value = result.totalElements
    addresses.value = result.content
    if (!result.content.some((address) => address.id === addressId.value)) {
      addressId.value =
        result.content.find((address) => address.isDefault === 1)?.id ??
        result.content[0]?.id ??
        null
    }
    addressStatus.value = result.content.length > 0 ? 'success' : 'empty'
  } catch (error) {
    if (requestId !== addressRequestSequence) return
    if (hadAddresses) {
      addressStatus.value = 'success'
      notify.fromApiError(error, 'checkout.loadAddressesFailed')
    } else {
      addressStatus.value = 'error'
      addressError.value = 'checkout.loadAddressesFailed'
    }
  } finally {
    if (requestId === addressRequestSequence && addressController === requestController) {
      addressController = null
    }
  }
}

function changeAddressPage(pageNumber: number) {
  if (submitting.value || previewPending.value) return
  void loadAddresses(pageNumber - 1)
}

async function runPreview() {
  if (previewPending.value || submitting.value) {
    return
  }
  const snapshot = captureInputSnapshot()
  const body = normalizedBody(snapshot)
  if (!body) {
    return
  }
  const key = snapshotKey(snapshot)
  const requestId = ++previewRequestSequence
  const hadPreview = currentPreview.value !== null
  activePreviewRequestId.value = requestId
  previewStatus.value = hadPreview ? 'updating' : 'loading'
  previewError.value = null
  try {
    const result = await previewCartCheckout(body)
    if (
      activePreviewRequestId.value !== requestId ||
      snapshotKey(captureInputSnapshot()) !== key ||
      submitting.value
    ) {
      return
    }
    preview.value = result
    previewSnapshotKey.value = key
    previewStatus.value = 'success'
  } catch (error) {
    if (
      activePreviewRequestId.value !== requestId ||
      snapshotKey(captureInputSnapshot()) !== key ||
      submitting.value
    ) {
      return
    }
    if (hadPreview) {
      previewStatus.value = 'success'
      notify.fromApiError(error, 'checkout.previewFailed')
    } else {
      previewStatus.value = 'error'
      previewError.value = 'checkout.previewFailed'
    }
  } finally {
    if (activePreviewRequestId.value === requestId) {
      activePreviewRequestId.value = null
    }
  }
}

async function submitCheckout() {
  if (submitting.value || previewPending.value) {
    return
  }
  const snapshot = captureInputSnapshot()
  const body = normalizedBody(snapshot)
  if (!body) {
    return
  }
  const submittedSnapshotKey = snapshotKey(snapshot)
  submitting.value = true
  previewRequestSequence += 1
  activePreviewRequestId.value = null
  try {
    const intent = getIdempotencyIntent('cart:checkout', body)
    const result = await checkoutCart(body, intent.key)
    const orderIds = checkoutOrderIds(result)
    if (orderIds.length === 0) {
      throw new Error('Checkout response did not contain persisted order ids')
    }
    preview.value = result
    previewSnapshotKey.value = submittedSnapshotKey
    previewStatus.value = 'success'
    intent.complete()
    notify.success(t('checkout.submitSuccess'), { key: 'checkout:submitted' })
    await router.push({
      path: `/payment/${orderIds[0]}`,
      query: orderIds.length > 1 ? { orderIds: orderIds.join(',') } : undefined,
    })
  } catch (error) {
    notify.fromApiError(error, 'checkout.submitFailed')
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  void Promise.all([loadCart(), loadAddresses()])
})

onBeforeUnmount(() => {
  addressRequestSequence += 1
  addressController?.abort()
  addressController = null
})
</script>

<template>
  <div class="route-view">
    <section class="checkout-layout">
      <PageHeader :title="t('shop.checkout')" :description="t('checkout.hint')" />

      <ol class="checkout-progress" :aria-label="$t('checkout.progress')">
        <li class="is-current"><span>1</span>{{ $t('checkout.deliveryStep') }}</li>
        <li><span>2</span>{{ $t('checkout.reviewStep') }}</li>
        <li><span>3</span>{{ $t('checkout.paymentStep') }}</li>
      </ol>

      <AsyncStateView :status="cartStatus" :error="cartError" @retry="loadCart">
        <template #empty>
          <div class="checkout-empty checkout-empty--cart">
            <MascotState pose="cart" size="md" :alt="$t('cart.emptyMascotAlt')" />
            <strong>{{ $t('common.cartEmpty') }}</strong>
            <p>{{ $t('cart.emptyHint') }}</p>
            <RouterLink class="checkout-empty__action" to="/shop">
              {{ $t('common.backToShop') }}
            </RouterLink>
          </div>
        </template>

        <div class="checkout-workspace">
          <section class="checkout-form-section" :aria-label="$t('common.selectAddress')">
            <header class="section-heading">
              <div>
                <span class="section-kicker">01</span>
                <h2>{{ $t('common.selectAddress') }}</h2>
              </div>
              <RouterLink class="section-link" to="/profile">
                <el-icon aria-hidden="true"><Plus /></el-icon>
                {{ $t('checkout.manageAddresses') }}
              </RouterLink>
            </header>

            <AsyncStateView
              :status="addressStatus"
              mode="grid"
              :error="addressError"
              @retry="loadAddresses"
            >
              <template #empty>
                <div class="checkout-empty">
                  <MascotState pose="clipboard" size="sm" :alt="$t('checkout.addressMascotAlt')" />
                  <strong>{{ $t('checkout.noAddresses') }}</strong>
                  <p>{{ $t('common.noAddressesHint') }}</p>
                  <RouterLink class="checkout-empty__action" to="/profile">
                    {{ $t('common.addAddress') }}
                  </RouterLink>
                </div>
              </template>

              <el-radio-group v-model="addressId" class="address-grid">
                <el-radio
                  v-for="address in addresses"
                  :key="address.id"
                  class="address-option"
                  :value="address.id"
                >
                  <span class="address-option__content">
                    <span class="address-option__heading">
                      <strong>{{ address.receiverName }}</strong>
                      <el-tag v-if="address.isDefault === 1" type="success" size="small">
                        {{ $t('checkout.defaultAddress') }}
                      </el-tag>
                    </span>
                    <span>{{ maskedPhone(address.phone) }}</span>
                    <span class="address-option__detail">
                      <el-icon aria-hidden="true"><Location /></el-icon>
                      {{ address.detailAddress }}
                    </span>
                  </span>
                </el-radio>
              </el-radio-group>

              <el-pagination
                v-if="addressTotal > addressPageSize"
                class="checkout-address-pagination"
                background
                layout="prev, pager, next, total"
                :current-page="addressPageNumber + 1"
                :page-size="addressPageSize"
                :total="addressTotal"
                :disabled="submitting || previewPending || addressStatus === 'updating'"
                @current-change="changeAddressPage"
              />
            </AsyncStateView>
          </section>

          <section class="checkout-form-section" :aria-label="$t('checkout.orderOptions')">
            <header class="section-heading">
              <div>
                <span class="section-kicker">02</span>
                <h2>{{ $t('checkout.orderOptions') }}</h2>
              </div>
              <span v-if="selectedAddress" class="selected-address-note">
                {{ selectedAddress.receiverName }}
              </span>
            </header>

            <div class="checkout-toolbar">
              <div class="checkout-field">
                <span id="checkout-province-label">{{ $t('checkout.provincePlaceholder') }}</span>
                <el-input
                  id="checkout-province"
                  v-model="province"
                  aria-labelledby="checkout-province-label"
                  :placeholder="$t('checkout.provincePlaceholder')"
                />
              </div>
              <div class="checkout-field checkout-field--coupon">
                <span id="checkout-coupons-label">{{ $t('checkout.couponCodes') }}</span>
                <el-input
                  id="checkout-coupons"
                  v-model="couponText"
                  aria-labelledby="checkout-coupons-label"
                  :placeholder="$t('checkout.couponPlaceholder')"
                />
              </div>
              <el-button
                class="checkout-preview-button"
                :icon="Refresh"
                :loading="previewStatus === 'loading' || previewStatus === 'updating'"
                :disabled="submitting || addressStatus !== 'success'"
                @click="runPreview"
              >
                {{ $t('checkout.preview') }}
              </el-button>
            </div>
          </section>

          <section class="checkout-preview-section" :aria-label="$t('checkout.stockReservation')">
            <header class="section-heading">
              <div>
                <span class="section-kicker">03</span>
                <h2>{{ $t('checkout.reviewStep') }}</h2>
              </div>
            </header>

            <AsyncStateView :status="previewStatus" :error="previewError" @retry="runPreview">
              <template #idle>
                <div class="checkout-empty checkout-empty--preview">
                  <el-icon aria-hidden="true"><Tickets /></el-icon>
                  <p>{{ $t('checkout.emptyHint') }}</p>
                </div>
              </template>

              <div v-if="currentPreview?.subOrders.length" class="suborder-list">
                <section
                  v-for="subOrder in currentPreview.subOrders"
                  :key="subOrder.id"
                  class="suborder-section"
                >
                  <div class="suborder-head">
                    <div>
                      <span>{{ $t('checkout.shop') }} {{ subOrder.shopId }}</span>
                      <small>{{ subOrder.orderNo }}</small>
                    </div>
                    <strong>{{ amount(subOrder.payableAmount) }}</strong>
                  </div>

                  <dl class="suborder-allocations">
                    <div>
                      <dt>{{ $t('checkout.originalAmount') }}</dt>
                      <dd>{{ amount(subOrder.originalAmount) }}</dd>
                    </div>
                    <div>
                      <dt>{{ $t('checkout.storeDiscount') }}</dt>
                      <dd>{{ amount(subOrder.storeDiscountAmount) }}</dd>
                    </div>
                    <div>
                      <dt>{{ $t('checkout.platformDiscount') }}</dt>
                      <dd>{{ amount(subOrder.platformDiscountAmount) }}</dd>
                    </div>
                    <div>
                      <dt>{{ $t('checkout.payable') }}</dt>
                      <dd>{{ amount(subOrder.payableAmount) }}</dd>
                    </div>
                  </dl>

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
                          <div>
                            <dt>SKU</dt>
                            <dd>{{ row.skuId }}</dd>
                          </div>
                          <div>
                            <dt>{{ $t('checkout.quantity') }}</dt>
                            <dd>{{ row.quantity }}</dd>
                          </div>
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
                            <dd>
                              {{ row.warehouseId ? row.reservationKey : $t('checkout.previewing') }}
                            </dd>
                          </div>
                        </dl>
                      </article>
                    </div>
                  </DataTableShell>
                </section>
              </div>

              <div v-else class="checkout-empty checkout-empty--preview">
                <el-icon aria-hidden="true"><Tickets /></el-icon>
                <p>{{ $t('checkout.emptyHint') }}</p>
              </div>
            </AsyncStateView>
          </section>
        </div>

        <div class="checkout-summary">
          <div class="checkout-summary__metric checkout-summary__metric--optional">
            <span>{{ $t('checkout.selectedItems') }}</span>
            <strong>{{ cart?.selectedQuantity ?? 0 }}</strong>
          </div>
          <div class="checkout-summary__metric checkout-summary__metric--optional">
            <span>{{ $t('checkout.originalAmount') }}</span>
            <strong>{{ amount(currentPreview?.originalAmount ?? cart?.selectedAmount) }}</strong>
          </div>
          <div class="checkout-summary__metric">
            <span>{{ $t('checkout.storeDiscount') }}</span>
            <strong>{{ storeDiscount }}</strong>
          </div>
          <div class="checkout-summary__metric">
            <span>{{ $t('checkout.platformDiscount') }}</span>
            <strong>{{ platformDiscount }}</strong>
          </div>
          <div class="checkout-summary__metric">
            <span>{{ $t('checkout.freight') }}</span>
            <strong>--</strong>
            <small>{{ $t('checkout.freightPending') }}</small>
          </div>
          <div class="checkout-summary__metric checkout-summary__metric--payable">
            <span>{{ $t('checkout.payable') }}</span>
            <strong>{{ amount(currentPreview?.payableAmount ?? cart?.selectedAmount) }}</strong>
          </div>
          <el-button
            class="checkout-submit"
            type="primary"
            :icon="Check"
            :loading="submitting"
            :disabled="submitting || previewPending || !addressId || addressStatus !== 'success'"
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
.checkout-layout,
.checkout-workspace {
  display: grid;
  gap: var(--space-5);
  min-width: 0;
}

.checkout-progress {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-2);
  margin: 0;
  padding: 0 0 var(--space-4);
  border-bottom: 1px solid var(--color-line);
  list-style: none;
}

.checkout-progress li {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-width: 0;
  color: var(--color-text-muted);
  font-size: var(--text-sm);
  font-weight: 650;
}

.checkout-progress li::after {
  width: 100%;
  height: 1px;
  margin-left: var(--space-2);
  background: var(--color-line);
  content: '';
}

.checkout-progress li:last-child::after {
  display: none;
}

.checkout-progress span {
  display: grid;
  flex: 0 0 28px;
  width: 28px;
  height: 28px;
  place-items: center;
  border: 1px solid var(--color-line-strong);
  border-radius: var(--radius-circle);
}

.checkout-progress .is-current {
  color: var(--color-brand);
}

.checkout-progress .is-current span {
  border-color: var(--color-brand);
  background: var(--color-brand);
  color: var(--color-text-inverse);
}

.checkout-form-section,
.checkout-preview-section {
  display: grid;
  gap: var(--space-4);
  min-width: 0;
  padding-top: var(--space-2);
}

.section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
}

.section-heading > div {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  min-width: 0;
}

.section-heading h2 {
  margin: 0;
  font-size: var(--text-lg);
}

.section-kicker {
  color: var(--color-brand);
  font-size: var(--text-xs);
  font-weight: 800;
}

.section-link {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  min-height: 38px;
  color: var(--color-brand);
  font-size: var(--text-sm);
  font-weight: 700;
  text-decoration: none;
}

.section-link:focus-visible {
  border-radius: var(--radius-control);
  outline: var(--focus-width) solid var(--focus-ring);
  outline-offset: var(--focus-offset);
}

.selected-address-note {
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}

.address-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: var(--space-3);
  width: 100%;
}

.checkout-address-pagination {
  justify-self: center;
  margin-top: var(--space-4);
}

.address-option.el-radio {
  display: flex;
  align-items: flex-start;
  width: 100%;
  height: auto;
  min-height: 124px;
  margin: 0;
  padding: var(--space-4);
  border: 1px solid var(--color-line);
  border-radius: var(--radius-surface);
  background: var(--color-surface);
  white-space: normal;
  transition:
    border-color var(--motion-fast),
    background var(--motion-fast),
    box-shadow var(--motion-fast);
}

.address-option.el-radio:hover {
  border-color: var(--color-brand);
}

.address-option.el-radio.is-checked {
  border-color: var(--color-brand);
  background: var(--color-brand-soft);
  box-shadow: inset 3px 0 0 var(--color-brand);
}

.address-option :deep(.el-radio__input) {
  margin-top: 3px;
}

.address-option :deep(.el-radio__label) {
  width: 100%;
  min-width: 0;
  padding-left: var(--space-3);
  color: var(--color-text);
  white-space: normal;
}

.address-option__content {
  display: grid;
  gap: var(--space-2);
  min-width: 0;
}

.address-option__heading {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
}

.address-option__detail {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  color: var(--color-text-muted);
  line-height: var(--leading-normal);
}

.address-option.el-radio.is-checked .address-option__detail {
  color: var(--color-text);
}

.address-option__detail .el-icon {
  flex: 0 0 auto;
  margin-top: 3px;
}

.checkout-toolbar {
  display: grid;
  grid-template-columns: minmax(180px, 240px) minmax(260px, 1fr) auto;
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

.checkout-preview-button {
  min-width: 126px;
  min-height: 40px;
}

.suborder-list {
  display: grid;
  gap: var(--space-6);
}

.suborder-section {
  display: grid;
  gap: var(--space-3);
  min-width: 0;
  padding-top: var(--space-4);
  border-top: 1px solid var(--color-line);
}

.suborder-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
}

.suborder-head > div {
  display: grid;
  gap: var(--space-1);
  font-weight: 750;
}

.suborder-head small {
  color: var(--color-text-muted);
  font-weight: 500;
}

.suborder-head > strong {
  color: var(--color-brand);
  font-size: var(--text-xl);
}

.suborder-allocations {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--space-2);
  margin: 0;
  padding: var(--space-3) 0;
  border-top: 1px dashed var(--color-line);
  border-bottom: 1px dashed var(--color-line);
}

.suborder-allocations div {
  min-width: 0;
  padding: 0 var(--space-3);
  border-right: 1px solid var(--color-line);
}

.suborder-allocations div:last-child {
  border-right: 0;
}

.suborder-allocations dt {
  color: var(--color-text-muted);
  font-size: var(--text-xs);
}

.suborder-allocations dd {
  margin: var(--space-1) 0 0;
  overflow-wrap: anywhere;
  font-weight: 750;
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
  min-height: 180px;
  align-content: center;
  padding: var(--space-5);
  color: var(--color-text-muted);
  text-align: center;
}

.checkout-empty--cart {
  min-height: 420px;
}

.checkout-empty--preview {
  min-height: 150px;
}

.checkout-empty--preview .el-icon {
  width: 36px;
  height: 36px;
}

.checkout-empty strong {
  color: var(--color-text);
}

.checkout-empty p {
  max-width: 520px;
  margin: 0;
}

.checkout-empty__action {
  display: inline-grid;
  min-height: 40px;
  place-items: center;
  margin-top: var(--space-2);
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-control);
  background: var(--color-brand);
  color: var(--color-text-inverse);
  font-weight: 700;
  text-decoration: none;
}

.checkout-summary {
  display: grid;
  grid-template-columns: repeat(6, minmax(100px, 1fr)) minmax(150px, auto);
  gap: 0;
  align-items: stretch;
  margin: 0;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-surface);
  background: var(--color-surface);
  box-shadow: var(--shadow-surface);
}

.checkout-summary__metric {
  min-width: 0;
  padding: var(--space-3);
  border-right: 1px solid var(--color-line);
}

.checkout-summary span,
.checkout-summary small {
  display: block;
  color: var(--color-text-muted);
  font-size: var(--text-xs);
}

.checkout-summary strong {
  display: block;
  margin-top: var(--space-1);
  overflow-wrap: anywhere;
  font-size: var(--text-lg);
}

.checkout-summary__metric--payable strong {
  color: var(--color-brand);
}

.checkout-submit {
  width: 100%;
  min-width: 150px;
  min-height: 52px;
  border-radius: 0 var(--radius-control) var(--radius-control) 0;
}

@media (max-width: 1100px) {
  .checkout-summary {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .checkout-submit {
    grid-column: 1 / -1;
    border-radius: 0 0 var(--radius-control) var(--radius-control);
  }
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
    padding-bottom: 176px;
  }

  .checkout-progress li {
    align-items: flex-start;
    font-size: var(--text-xs);
  }

  .checkout-progress li::after {
    display: none;
  }

  .section-heading {
    align-items: flex-start;
  }

  .address-grid,
  .suborder-allocations {
    grid-template-columns: 1fr;
  }

  .address-option.el-radio {
    min-height: 112px;
  }

  .suborder-allocations div {
    display: flex;
    justify-content: space-between;
    gap: var(--space-3);
    padding: var(--space-1) var(--space-2);
    border-right: 0;
  }

  .suborder-allocations dd {
    margin: 0;
  }

  .checkout-summary {
    position: fixed;
    right: 0;
    bottom: env(safe-area-inset-bottom);
    left: 0;
    z-index: 30;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 0;
    border: 0;
    border-top: 1px solid var(--color-line);
    border-radius: 0;
    padding: var(--space-2) var(--space-3);
    background: var(--color-surface);
    box-shadow: var(--shadow-overlay);
  }

  .checkout-summary__metric {
    border-right: 0;
    padding: var(--space-1) var(--space-2);
  }

  .checkout-summary__metric--optional {
    display: none;
  }

  .checkout-summary span,
  .checkout-summary small {
    overflow-wrap: anywhere;
    font-size: var(--text-xs);
  }

  .checkout-summary small {
    display: none;
  }

  .checkout-summary strong {
    font-size: var(--text-base);
  }

  .checkout-submit {
    grid-column: 1 / -1;
    min-width: 0;
    min-height: 44px;
    margin-top: var(--space-2);
    border-radius: var(--radius-control);
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
