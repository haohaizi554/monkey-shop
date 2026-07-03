<script setup lang="ts">
import { ArrowLeft } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { getCatalogSpu, listMonkeys } from '@/api/catalog'
import AppShell from '@/components/AppShell.vue'
import ProductImage from '@/components/ProductImage.vue'
import { useCheckout } from '@/composables/useCheckout'
import { productJsonLd } from '@/seo/product-json-ld'
import { useJsonLd } from '@/seo/useJsonLd'
import type { CatalogSku, CatalogSpu, Monkey } from '@/types'
import { money } from '@/utils/format'

const route = useRoute()
const loading = ref(false)
const { t } = useI18n()
const product = ref<Monkey | null>(null)
const selectedSkuId = ref<number | null>(null)
const productId = computed(() => Number(route.params.productId))
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

useJsonLd('monkeyshop-product-jsonld', productStructuredData)

const {
  openingCheckoutId,
  submittingOrder,
  checkoutOpen,
  addresses,
  selectedMonkey,
  selectedAddressId,
  newAddress,
  openCheckout,
  saveAddress,
  submitOrder,
} = useCheckout()

async function loadProduct() {
  loading.value = true
  try {
    product.value = await loadCatalogProduct()
  } catch (error) {
    product.value = null
    ElMessage.error(error instanceof Error ? error.message : t('common.unableToLoadProduct'))
  } finally {
    loading.value = false
  }
}

async function loadCatalogProduct(): Promise<Monkey | null> {
  try {
    const spu = await getCatalogSpu(productId.value)
    return catalogSpuToMonkey(spu)
  } catch {
    const catalog = await listMonkeys()
    return catalog.find((item) => item.id === productId.value) ?? null
  }
}

function catalogSpuToMonkey(spu: CatalogSpu): Monkey {
  const firstSku = spu.skus.find((sku) => sku.active) ?? spu.skus[0]
  const description =
    typeof spu.attributes.description === 'string' ? spu.attributes.description : spu.title
  return {
    id: spu.id,
    name: spu.name,
    breed: spu.title,
    price: firstSku?.memberPrice ?? spu.memberPrice ?? firstSku?.originalPrice ?? spu.originalPrice,
    description,
    imageUrl: spu.imageUrl || '/images/default_product.png',
    stock: spu.skus.filter((sku) => sku.active).length,
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
    openCheckout(checkoutProduct.value)
  }
}

watch(productId, () => void loadProduct(), { immediate: true })
watch(product, () => {
  selectedSkuId.value = skuOptions.value[0]?.id ?? null
})
</script>

<template>
  <AppShell>
    <el-skeleton :loading="loading" animated>
      <template #default>
        <el-empty v-if="!product" :description="$t('shop.soldOut')">
          <RouterLink to="/shop">
            <el-button type="primary" :icon="ArrowLeft">
              {{ $t('nav.shop') }}
            </el-button>
          </RouterLink>
        </el-empty>

        <section v-else class="product-detail-layout">
          <ProductImage :src="product.imageUrl" :alt="product.name" />
          <div class="product-detail-panel">
            <RouterLink to="/shop" class="back-link">
              <el-icon><ArrowLeft /></el-icon>
              <span>{{ $t('nav.shop') }}</span>
            </RouterLink>

            <div class="product-detail-heading">
              <div>
                <h1>{{ product.name }}</h1>
                <p>{{ product.breed }}</p>
              </div>
              <div class="price-stack">
                <strong>{{ money(displayPrice) }}</strong>
                <del v-if="displayStrikePrice">{{ money(displayStrikePrice) }}</del>
              </div>
            </div>

            <p class="detail-description">
              {{ product.description }}
            </p>

            <div v-if="skuOptions.length" class="sku-selector">
              <span class="sku-title">SKU</span>
              <el-radio-group v-model="selectedSkuId" class="sku-options">
                <el-radio-button v-for="sku in skuOptions" :key="sku.id" :label="sku.id">
                  {{ skuLabel(sku) }}
                </el-radio-button>
              </el-radio-group>
            </div>

            <div class="detail-actions">
              <el-tag :type="product.stock > 0 ? 'success' : 'info'" disable-transitions>
                {{ $t('common.stock') }} {{ product.stock }}
              </el-tag>
              <el-button
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
      </template>
    </el-skeleton>

    <el-dialog v-model="checkoutOpen" :title="$t('shop.checkout')" width="720">
      <div v-if="selectedMonkey" class="checkout-summary">
        <ProductImage :src="selectedMonkey.imageUrl" :alt="selectedMonkey.name" />
        <div>
          <h2>{{ selectedMonkey.name }}</h2>
          <p>{{ selectedMonkey.breed }}</p>
          <strong>{{ money(selectedMonkey.price) }}</strong>
        </div>
      </div>

      <el-radio-group v-model="selectedAddressId" class="address-list">
        <el-radio v-for="address in addresses" :key="address.id" :label="address.id" border>
          {{ address.receiverName }} - {{ address.phone }} - {{ address.detailAddress }}
        </el-radio>
      </el-radio-group>

      <el-divider>{{ $t('shop.addAddress') }}</el-divider>
      <div class="inline-form">
        <el-input v-model="newAddress.receiverName" :placeholder="$t('common.receiver')" />
        <el-input v-model="newAddress.phone" :placeholder="$t('auth.phone')" />
        <el-input v-model="newAddress.detailAddress" :placeholder="$t('common.address')" />
        <el-button plain @click="saveAddress">
          {{ $t('common.save') }}
        </el-button>
      </div>

      <template #footer>
        <el-button @click="checkoutOpen = false">
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
  </AppShell>
</template>

<style scoped>
.price-stack {
  display: grid;
  justify-items: end;
  gap: 4px;
}

.price-stack del {
  color: var(--el-text-color-secondary);
  font-size: 0.9rem;
}

.sku-selector {
  display: grid;
  gap: 8px;
}

.sku-title {
  color: var(--el-text-color-regular);
  font-size: 0.9rem;
  font-weight: 600;
}

.sku-options {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

:deep(.sku-options .el-radio-button__inner) {
  border-radius: 6px;
  min-height: 36px;
}
</style>
