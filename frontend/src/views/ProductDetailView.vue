<script setup lang="ts">
import { ArrowLeft } from '@element-plus/icons-vue'
import type { FormInstance, FormRules } from 'element-plus'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { getCatalogSpu, listMonkeys } from '@/api/catalog'
import ProductImage from '@/components/ProductImage.vue'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import { useAsyncState } from '@/composables/useAsyncState'
import { useCheckout } from '@/composables/useCheckout'
import { useNotify } from '@/composables/useNotify'
import { productJsonLd } from '@/seo/product-json-ld'
import { useJsonLd } from '@/seo/useJsonLd'
import type { AddressRequest, CatalogSku, CatalogSpu, Monkey } from '@/types'
import { money } from '@/utils/format'

type NoticeLevel = 'error' | 'success' | 'warning'

const route = useRoute()
const { t } = useI18n()
const notify = useNotify()
const productState = useAsyncState<Monkey | null>({ timeoutMs: 20000 })
const selectedSkuId = ref<number | null>(null)
const addressFormRef = ref<FormInstance>()
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
const checkoutProduct = computed(() => {
  if (!product.value) {
    return null
  }
  return {
    ...product.value,
    price: displayPrice.value,
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

function buyCurrentProduct() {
  if (checkoutProduct.value) {
    void openCheckout(checkoutProduct.value)
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

watch(productId, () => void loadProduct(), { immediate: true })
watch(product, () => {
  selectedSkuId.value = skuOptions.value[0]?.id ?? null
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
            <div class="price-stack">
              <strong>{{ money(displayPrice) }}</strong>
              <del v-if="displayStrikePrice">{{ money(displayStrikePrice) }}</del>
            </div>

            <dl class="purchase-meta">
              <div>
                <dt>{{ $t('common.stock') }}</dt>
                <dd>{{ product.stock }}</dd>
              </div>
              <div v-if="selectedSku">
                <dt>SKU</dt>
                <dd>{{ selectedSku.skuCode }}</dd>
              </div>
            </dl>

            <el-button
              class="purchase-action"
              type="primary"
              :loading="openingCheckoutId === product.id"
              :disabled="product.stock <= 0 || openingCheckoutId !== null"
              @click="buyCurrentProduct"
            >
              {{ product.stock > 0 ? $t('shop.buy') : $t('shop.soldOut') }}
            </el-button>
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

.price-stack {
  display: grid;
  gap: var(--space-1);
}

.price-stack strong {
  font-size: var(--text-2xl);
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
  grid-template-columns: minmax(0, 1fr) minmax(180px, 1fr) auto;
  gap: var(--space-5);
  align-items: center;
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
  margin: var(--space-1) 0 0;
  overflow-wrap: anywhere;
  color: var(--color-text);
  font-weight: 700;
}

.purchase-action {
  min-width: 128px;
  min-height: 44px;
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

  .purchase-action,
  .address-form__save {
    width: 100%;
  }
}
</style>
