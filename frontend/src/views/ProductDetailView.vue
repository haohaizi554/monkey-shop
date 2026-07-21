<script setup lang="ts">
import { ArrowLeft, ShoppingCart, Star, StarFilled } from '@element-plus/icons-vue'
import type { FormInstance, FormRules } from 'element-plus'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { addCartItem } from '@/api/cart'
import { getCatalogPrice, getCatalogSpu, listMonkeys } from '@/api/catalog'
import { inventoryStocks } from '@/api/inventory'
import { quoteMarketingPrice } from '@/api/marketing'
import {
  addCollection,
  membershipDashboard,
  recordBrowse,
  removeCollection,
} from '@/api/membership'
import ProductImage from '@/components/ProductImage.vue'
import MascotState from '@/components/mascot/MascotState.vue'
import InlineNotice from '@/components/ui/InlineNotice.vue'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import { useAsyncState } from '@/composables/useAsyncState'
import { useCheckout } from '@/composables/useCheckout'
import { useNotify } from '@/composables/useNotify'
import { productJsonLd } from '@/seo/product-json-ld'
import { useJsonLd } from '@/seo/useJsonLd'
import { useAuthStore } from '@/stores/auth'
import { trackEvent } from '@/TrackingSdk'
import type {
  AddressRequest,
  CatalogPriceQuote,
  CatalogSku,
  CatalogSpu,
  MarketingPriceQuote,
  MemberCollection,
  MembershipDashboard,
  Monkey,
  WarehouseStock,
} from '@/types'
import { money } from '@/utils/format'

type NoticeLevel = 'error' | 'success' | 'warning'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const notify = useNotify()
const auth = useAuthStore()
const productState = useAsyncState<Monkey | null>({ timeoutMs: 20000 })
const priceState = useAsyncState<CatalogPriceQuote | null>({ timeoutMs: 10000 })
const inventoryState = useAsyncState<WarehouseStock[]>({ timeoutMs: 10000 })
const marketingState = useAsyncState<MarketingPriceQuote | null>({ timeoutMs: 10000 })
const membershipState = useAsyncState<MembershipDashboard | null>({ timeoutMs: 10000 })
const selectedSkuId = ref<number | null>(null)
const quantity = ref(1)
const cartBusy = ref(false)
const collectionBusy = ref(false)
const savedCollection = ref<MemberCollection | null>(null)
const addressFormRef = ref<FormInstance>()
let recordedBrowseProductId: number | null = null
const productId = computed(() => Number(route.params.productId))
const product = computed(() => productState.data.value)
const skuOptions = computed(() => product.value?.skus?.filter((sku) => sku.active) ?? [])
const selectedSku = computed(
  () => skuOptions.value.find((sku) => sku.id === selectedSkuId.value) ?? skuOptions.value[0],
)
const displayPrice = computed(
  () =>
    selectedSku.value?.memberPrice ?? selectedSku.value?.originalPrice ?? product.value?.price ?? 0,
)
const displayStrikePrice = computed(
  () => selectedSku.value?.strikePrice ?? product.value?.strikePrice,
)
const commercePrice = computed(() => priceState.data.value?.salePrice ?? displayPrice.value)
const commerceStrikePrice = computed(
  () => priceState.data.value?.strikePrice ?? displayStrikePrice.value,
)
const totalAvailable = computed(() =>
  (inventoryState.data.value ?? []).reduce((total, stock) => total + stock.availableQuantity, 0),
)
const inventoryKnown = computed(
  () => inventoryState.status.value === 'success' || inventoryState.status.value === 'empty',
)
const availableQuantity = computed(() =>
  inventoryKnown.value ? totalAvailable.value : (product.value?.stock ?? 0),
)
const warehouseCount = computed(() => inventoryState.data.value?.length ?? 0)
const lowStock = computed(
  () =>
    inventoryState.data.value?.some((stock) => stock.belowSafetyStock) === true ||
    (inventoryKnown.value && availableQuantity.value > 0 && availableQuantity.value <= 3),
)
const soldOut = computed(() => availableQuantity.value <= 0)
const isSaved = computed(() => savedCollection.value !== null)
const resolvedShopId = computed(() => {
  const candidate = Number(product.value?.attributes?.shopId)
  return Number.isFinite(candidate) && candidate > 0 ? candidate : 1
})
const checkoutProduct = computed(() => {
  if (!product.value) {
    return null
  }
  return {
    ...product.value,
    price: commercePrice.value,
    stock: availableQuantity.value,
    selectedSkuId: selectedSku.value?.id,
  }
})
const productStructuredData = computed(() =>
  checkoutProduct.value ? productJsonLd(checkoutProduct.value) : undefined,
)
const addressRules = computed<FormRules<AddressRequest>>(() => ({
  receiverName: [{ required: true, message: t('common.receiver'), trigger: 'blur' }],
  phone: [
    { required: true, message: t('auth.phoneRequired'), trigger: 'blur' },
    { pattern: /^1\d{10}$/, message: t('auth.phoneInvalid'), trigger: 'blur' },
  ],
  detailAddress: [{ required: true, message: t('common.addressDetailRequired'), trigger: 'blur' }],
}))

useJsonLd('monkeyshop-product-jsonld', productStructuredData)

function showNotice(level: NoticeLevel, message: string) {
  if (level === 'error') {
    notify.error(t('feedback.requestFailed'), { key: 'product-detail:checkout-error' })
    return
  }
  notify.notify(level, message)
}

const {
  openingCheckoutId,
  submittingOrder,
  savingAddress,
  checkoutOpen,
  addresses,
  selectedMonkey,
  selectedAddressId,
  newAddress,
  openCheckout,
  saveAddress,
  submitOrder,
} = useCheckout({ notify: showNotice })

async function loadProduct() {
  await productState.load(() => loadCatalogProduct(), {
    isEmpty: (loadedProduct) => loadedProduct === null,
    preserveData: false,
  })
}

function resetProductContext() {
  selectedSkuId.value = null
  quantity.value = 1
  savedCollection.value = null
  recordedBrowseProductId = null
  priceState.reset()
  inventoryState.reset()
  marketingState.reset()
  membershipState.reset()
}

async function loadPricingContext(loadedProduct: Monkey) {
  const quote = await priceState.load(() =>
    getCatalogPrice(productId.value, auth.isLoggedIn ? 'MEMBER' : 'ANONYMOUS'),
  )
  if (!auth.isLoggedIn || !quote) {
    marketingState.reset()
    return
  }
  await marketingState.load(() =>
    quoteMarketingPrice({
      orderAmount: quote.salePrice,
      categoryId: loadedProduct.categoryId,
      shopId: resolvedShopId.value,
      couponCodes: [],
    }),
  )
}

async function loadMembershipContext(loadedProduct: Monkey) {
  if (!auth.isLoggedIn) {
    membershipState.reset()
    savedCollection.value = null
    return
  }
  const dashboard = await membershipState.load(() => membershipDashboard())
  savedCollection.value =
    dashboard?.collections.find((collection) => collection.productId === loadedProduct.id) ?? null
}

async function loadInventoryContext(skuId: number | null) {
  if (!auth.isLoggedIn || skuId === null) {
    inventoryState.reset()
    return
  }
  await inventoryState.load(() => inventoryStocks(skuId), {
    isEmpty: (stocks) => stocks.length === 0,
    preserveData: false,
  })
}

function recordProductBrowse(loadedProduct: Monkey) {
  if (!auth.isLoggedIn || recordedBrowseProductId === loadedProduct.id) {
    return
  }
  recordedBrowseProductId = loadedProduct.id
  void recordBrowse({ productId: loadedProduct.id }).catch(() => {
    recordedBrowseProductId = null
  })
}

async function loadCatalogProduct(): Promise<Monkey | null> {
  const catalog = await listMonkeys()
  try {
    const spu = await getCatalogSpu(productId.value)
    return catalogSpuToMonkey(spu, catalog)
  } catch {
    return catalog.find((item) => item.id === productId.value) ?? null
  }
}

function catalogSpuToMonkey(spu: CatalogSpu, catalog: Monkey[]): Monkey {
  const firstSku = spu.skus.find((sku) => sku.active) ?? spu.skus[0]
  const description =
    typeof spu.attributes.description === 'string' ? spu.attributes.description : spu.title
  // The order pipeline still resolves stock through the purchasable monkey row.
  const purchasableMonkey = catalog.find((item) => item.name === spu.name) ?? null
  return {
    id: purchasableMonkey?.id ?? spu.id,
    name: spu.name,
    breed: spu.title,
    price: firstSku?.memberPrice ?? spu.memberPrice ?? firstSku?.originalPrice ?? spu.originalPrice,
    description,
    imageUrl: spu.imageUrl || purchasableMonkey?.imageUrl || '/images/default_product.jpg',
    stock: purchasableMonkey?.stock ?? spu.skus.filter((sku) => sku.active).length,
    categoryId: spu.categoryId,
    status: spu.status,
    memberPrice: spu.memberPrice,
    strikePrice: spu.strikePrice,
    regionPrices: spu.regionPrices,
    attributes: spu.attributes,
    detailJsonLd: spu.detailJsonLd,
    skus: spu.skus,
  }
}

function skuLabel(sku: CatalogSku): string {
  const label = Object.entries(sku.specification)
    .map(([name, value]) => `${name}: ${value}`)
    .join(' / ')
  return label || sku.skuCode
}

async function requireLogin(): Promise<boolean> {
  if (auth.isLoggedIn) {
    return true
  }
  await router.push({ path: '/login', query: { redirect: route.fullPath } })
  return false
}

async function toggleCollection() {
  if (collectionBusy.value || !product.value || !(await requireLogin())) {
    return
  }
  collectionBusy.value = true
  try {
    if (savedCollection.value) {
      await removeCollection(product.value.id)
      savedCollection.value = null
      notify.success(t('shop.collectionRemoved'), { key: 'product-detail:collection' })
    } else {
      savedCollection.value = await addCollection({ productId: product.value.id })
      notify.success(t('shop.collectionAdded'), { key: 'product-detail:collection' })
    }
  } catch {
    notify.error(t('shop.collectionFailed'), { key: 'product-detail:collection-error' })
  } finally {
    collectionBusy.value = false
  }
}

async function addCurrentSkuToCart() {
  const sku = selectedSku.value
  if (cartBusy.value || !sku || soldOut.value || !(await requireLogin())) {
    return
  }
  cartBusy.value = true
  try {
    await addCartItem({
      skuId: sku.id,
      shopId: resolvedShopId.value,
      quantity: Math.max(1, Math.trunc(quantity.value)),
      selected: true,
    })
    trackEvent('ADD_TO_CART', {
      productId: product.value?.id,
      categoryId: product.value?.categoryId,
      amount: commercePrice.value,
      attributes: { skuId: String(sku.id), quantity: String(quantity.value) },
    })
    notify.success(t('shop.addedToCart'), { key: 'product-detail:cart-added' })
  } catch {
    notify.error(t('shop.addToCartFailed'), { key: 'product-detail:cart-error' })
  } finally {
    cartBusy.value = false
  }
}

async function buyCurrentProduct() {
  const sku = selectedSku.value
  if (checkoutProduct.value && sku && (await requireLogin())) {
    await openCheckout(checkoutProduct.value, {
      skuId: sku.id,
      shopId: resolvedShopId.value,
      quantity: Math.max(1, Math.trunc(quantity.value)),
    })
  }
}

async function validateAndSaveAddress() {
  if (savingAddress.value) {
    return
  }
  const valid = await addressFormRef.value
    ?.validate()
    .then(() => true)
    .catch(() => false)
  if (!valid) {
    return
  }
  await saveAddress()
  if (!newAddress.receiverName && !newAddress.phone && !newAddress.detailAddress) {
    addressFormRef.value?.clearValidate()
  }
}

function clearAddressValidation() {
  addressFormRef.value?.clearValidate()
}

watch(
  productId,
  () => {
    resetProductContext()
    void loadProduct()
  },
  { immediate: true },
)
watch(product, (loadedProduct) => {
  selectedSkuId.value = skuOptions.value[0]?.id ?? null
  quantity.value = 1
  if (!loadedProduct) {
    return
  }
  recordProductBrowse(loadedProduct)
  void Promise.all([loadPricingContext(loadedProduct), loadMembershipContext(loadedProduct)])
})
watch(selectedSkuId, (skuId) => {
  void loadInventoryContext(skuId)
})
</script>

<template>
  <div class="route-view product-detail-view">
    <PageHeader :title="product?.name || $t('common.product')" :eyebrow="product?.breed">
      <template #actions>
        <RouterLink to="/shop" class="secondary-button">
          <el-icon><ArrowLeft /></el-icon>
          <span>{{ $t('common.backToShop') }}</span>
        </RouterLink>
        <el-button
          v-if="product"
          class="collection-action"
          :plain="!isSaved"
          :type="isSaved ? 'primary' : 'default'"
          :loading="collectionBusy || membershipState.isLoading.value"
          :disabled="collectionBusy || membershipState.isLoading.value"
          :aria-label="isSaved ? $t('shop.removeFromSaved') : $t('shop.saveProduct')"
          :aria-pressed="isSaved"
          @click="toggleCollection"
        >
          <el-icon aria-hidden="true">
            <StarFilled v-if="isSaved" />
            <Star v-else />
          </el-icon>
          <span>{{ isSaved ? $t('shop.saved') : $t('common.save') }}</span>
        </el-button>
      </template>
    </PageHeader>

    <AsyncStateView
      :status="productState.status.value"
      :error="productState.error.value"
      :empty-title="$t('common.notFound')"
      @retry="loadProduct"
    >
      <template #error>
        <div class="product-detail-state" role="alert">
          <p>{{ $t('common.unableToLoadProduct') }}</p>
          <el-button type="primary" @click="loadProduct">{{ $t('common.retry') }}</el-button>
        </div>
      </template>

      <template #empty>
        <div class="product-detail-state" role="status">
          <h2>{{ $t('common.notFound') }}</h2>
          <RouterLink to="/shop">
            <el-button type="primary" :icon="ArrowLeft">
              {{ $t('common.backToShop') }}
            </el-button>
          </RouterLink>
        </div>
      </template>

      <section v-if="product" class="product-detail-layout">
        <div class="product-detail__media">
          <ProductImage :src="product.imageUrl" :alt="product.name" />
        </div>

        <div class="product-detail-panel">
          <p class="detail-description">{{ product.description }}</p>

          <div v-if="skuOptions.length" class="sku-selector">
            <span class="sku-title">SKU</span>
            <el-radio-group v-model="selectedSkuId" class="sku-options">
              <el-radio-button v-for="sku in skuOptions" :key="sku.id" :value="sku.id">
                {{ skuLabel(sku) }}
              </el-radio-button>
            </el-radio-group>
          </div>

          <div class="purchase-surface">
            <div class="price-stack" data-testid="commerce-price">
              <span>{{ $t('shop.currentPrice') }}</span>
              <strong>{{ money(commercePrice) }}</strong>
              <del v-if="commerceStrikePrice">{{ money(commerceStrikePrice) }}</del>
              <small v-if="priceState.data.value?.strategy">
                {{ $t('shop.priceStrategy', { strategy: priceState.data.value.strategy }) }}
              </small>
            </div>

            <dl class="purchase-meta">
              <div data-testid="inventory-summary">
                <dt>{{ $t('inventory.available') }}</dt>
                <dd>
                  {{ availableQuantity }}
                  <small v-if="warehouseCount">
                    {{ $t('shop.warehouseCount', { count: warehouseCount }) }}
                  </small>
                </dd>
              </div>
              <div v-if="selectedSku">
                <dt>SKU</dt>
                <dd>{{ selectedSku.skuCode }}</dd>
              </div>
            </dl>

            <div class="quantity-control">
              <span>{{ $t('shop.quantity') }}</span>
              <el-input-number
                v-model="quantity"
                :aria-label="$t('shop.quantity')"
                :min="1"
                :max="Math.max(1, availableQuantity)"
                :disabled="soldOut || cartBusy || openingCheckoutId !== null"
                controls-position="right"
              />
            </div>

            <div class="purchase-actions">
              <el-button
                class="add-cart-action"
                :icon="ShoppingCart"
                :loading="cartBusy"
                :disabled="soldOut || cartBusy || !selectedSku"
                @click="addCurrentSkuToCart"
              >
                {{ $t('shop.addToCart') }}
              </el-button>
              <el-button
                class="purchase-action"
                type="primary"
                :loading="openingCheckoutId === product.id"
                :disabled="soldOut || openingCheckoutId !== null"
                @click="buyCurrentProduct"
              >
                {{ soldOut ? $t('shop.soldOut') : $t('shop.buyNow') }}
              </el-button>
            </div>
          </div>

          <InlineNotice
            v-if="marketingState.data.value && Number(marketingState.data.value.discountAmount) > 0"
            class="commerce-offer"
            severity="success"
            :title="$t('shop.offerEstimate')"
            :message="
              $t('shop.offerEstimateDetail', {
                payable: money(marketingState.data.value.payableAmount),
                savings: money(marketingState.data.value.discountAmount),
              })
            "
          />

          <InlineNotice
            v-if="inventoryState.status.value === 'error'"
            class="inventory-notice"
            severity="warning"
            :message="$t('shop.inventoryUnavailable')"
          />

          <div v-if="lowStock" class="stock-warning" role="status">
            <MascotState pose="warning" size="sm" :alt="$t('shop.lowStockMascotAlt')" />
            <p>{{ $t('shop.lowStockWarning') }}</p>
          </div>
        </div>
      </section>
    </AsyncStateView>

    <el-dialog
      v-model="checkoutOpen"
      :title="$t('shop.checkout')"
      width="min(720px, 94vw)"
      :close-on-click-modal="!submittingOrder"
      :close-on-press-escape="!submittingOrder"
      :show-close="!submittingOrder"
      @closed="clearAddressValidation"
    >
      <div v-if="selectedMonkey" class="checkout-summary">
        <ProductImage :src="selectedMonkey.imageUrl" :alt="selectedMonkey.name" />
        <div>
          <h2>{{ selectedMonkey.name }}</h2>
          <p>{{ selectedMonkey.breed }}</p>
          <strong>{{ money(selectedMonkey.price) }}</strong>
        </div>
      </div>

      <el-radio-group
        v-model="selectedAddressId"
        class="address-list"
        :disabled="submittingOrder || savingAddress"
      >
        <el-radio v-for="address in addresses" :key="address.id" :value="address.id" border>
          {{ address.receiverName }} - {{ address.phone }} - {{ address.detailAddress }}
        </el-radio>
      </el-radio-group>

      <el-divider>{{ $t('shop.addAddress') }}</el-divider>
      <el-form
        ref="addressFormRef"
        class="address-form"
        :model="newAddress"
        :rules="addressRules"
        label-position="top"
      >
        <el-form-item :label="$t('common.receiver')" prop="receiverName">
          <el-input
            v-model="newAddress.receiverName"
            :disabled="submittingOrder || savingAddress"
            :placeholder="$t('common.receiver')"
          />
        </el-form-item>
        <el-form-item :label="$t('auth.phone')" prop="phone">
          <el-input
            v-model="newAddress.phone"
            :disabled="submittingOrder || savingAddress"
            :placeholder="$t('auth.phone')"
          />
        </el-form-item>
        <el-form-item :label="$t('common.address')" prop="detailAddress">
          <el-input
            v-model="newAddress.detailAddress"
            :disabled="submittingOrder || savingAddress"
            :placeholder="$t('common.address')"
          />
        </el-form-item>
        <el-button
          class="address-form__save"
          plain
          :loading="savingAddress"
          :disabled="savingAddress || submittingOrder"
          @click="validateAndSaveAddress"
        >
          {{ $t('common.save') }}
        </el-button>
      </el-form>

      <template #footer>
        <el-button :disabled="submittingOrder" @click="checkoutOpen = false">
          {{ $t('common.cancel') }}
        </el-button>
        <el-button
          type="primary"
          :loading="submittingOrder"
          :disabled="submittingOrder"
          @click="submitOrder"
        >
          {{ $t('shop.placeOrder') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.product-detail-view {
  display: grid;
  gap: var(--space-5);
}

.collection-action {
  min-height: 44px;
}

.product-detail__media {
  aspect-ratio: 4 / 3;
  overflow: hidden;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-surface);
  background: var(--color-surface-subtle);
  box-shadow: var(--shadow-card);
}

.product-detail__media :deep(.product-image) {
  width: 100%;
  height: 100%;
  aspect-ratio: auto;
  object-fit: cover;
}

.product-detail-panel {
  padding: 0;
  border: 0;
  background: transparent;
  box-shadow: none;
}

.price-stack {
  display: grid;
  gap: var(--space-1);
  min-width: 0;
}

.price-stack strong {
  color: var(--color-primary);
  font-size: var(--text-2xl);
}

.price-stack > span,
.price-stack small {
  color: var(--color-text-muted);
  font-size: var(--text-xs);
  font-weight: 700;
}

.price-stack del {
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}

.sku-selector {
  display: grid;
  gap: var(--space-2);
}

.sku-title {
  color: var(--color-text);
  font-size: var(--text-sm);
  font-weight: 600;
}

.sku-options {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

:deep(.sku-options .el-radio-button__inner) {
  min-height: 44px;
  border-radius: var(--radius-control);
}

.purchase-surface {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-4);
  align-items: end;
  border-top: 1px solid var(--color-line);
  padding-top: var(--space-5);
}

.purchase-meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-3);
  margin: 0;
}

.purchase-meta div {
  min-width: 0;
}

.purchase-meta dt {
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}

.purchase-meta dd {
  display: grid;
  gap: var(--space-1);
  margin: var(--space-1) 0 0;
  overflow-wrap: anywhere;
  color: var(--color-text);
  font-weight: 700;
}

.purchase-meta dd small {
  color: var(--color-text-muted);
  font-size: var(--text-xs);
  font-weight: 600;
}

.quantity-control {
  display: grid;
  gap: var(--space-2);
  min-width: 128px;
}

.quantity-control > span {
  color: var(--color-text-muted);
  font-size: var(--text-sm);
  font-weight: 700;
}

.quantity-control :deep(.el-input-number) {
  width: 128px;
}

.purchase-actions {
  display: flex;
  grid-column: 1 / -1;
  gap: var(--space-2);
  justify-content: stretch;
  min-width: 0;
}

.purchase-action,
.add-cart-action {
  flex: 1 1 0;
  min-width: 128px;
  min-height: 44px;
}

.commerce-offer,
.inventory-notice {
  margin-top: var(--space-4);
}

.stock-warning {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: var(--space-3);
  align-items: center;
  margin-top: var(--space-4);
  border-top: 1px solid var(--color-line);
  padding-top: var(--space-4);
}

.stock-warning :deep(.mascot-state) {
  width: 72px;
  height: 72px;
  object-fit: contain;
}

.stock-warning p {
  margin: 0;
  color: var(--color-text-muted);
  line-height: var(--leading-normal);
}

.product-detail-state {
  display: grid;
  justify-items: center;
  gap: var(--space-3);
  min-height: 320px;
  align-content: center;
  color: var(--color-text-muted);
  text-align: center;
}

.address-form {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-3);
}

.address-form__save {
  grid-column: 1 / -1;
  justify-self: end;
  min-height: 44px;
}

@media (max-width: 900px) {
  .purchase-surface,
  .address-form {
    grid-template-columns: 1fr;
  }

  .quantity-control :deep(.el-input-number),
  .purchase-actions,
  .purchase-action,
  .add-cart-action,
  .address-form__save {
    width: 100%;
  }

  .purchase-actions {
    flex-direction: column;
  }
}
</style>
