<script setup lang="ts">
import { ArrowLeft } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { listMonkeys } from '@/api/catalog'
import AppShell from '@/components/AppShell.vue'
import ProductImage from '@/components/ProductImage.vue'
import { useCheckout } from '@/composables/useCheckout'
import { productJsonLd } from '@/seo/product-json-ld'
import { useJsonLd } from '@/seo/useJsonLd'
import type { Monkey } from '@/types'
import { money } from '@/utils/format'

const route = useRoute()
const loading = ref(false)
const { t } = useI18n()
const product = ref<Monkey | null>(null)
const productId = computed(() => Number(route.params.productId))
const productStructuredData = computed(() =>
  product.value ? productJsonLd(product.value) : undefined,
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
    const catalog = await listMonkeys()
    product.value = catalog.find((item) => item.id === productId.value) ?? null
  } catch (error) {
    product.value = null
    ElMessage.error(error instanceof Error ? error.message : t('common.unableToLoadProduct'))
  } finally {
    loading.value = false
  }
}

watch(productId, () => void loadProduct(), { immediate: true })
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
              <strong>{{ money(product.price) }}</strong>
            </div>

            <p class="detail-description">
              {{ product.description }}
            </p>

            <div class="detail-actions">
              <el-tag :type="product.stock > 0 ? 'success' : 'info'" disable-transitions>
                {{ $t('common.stock') }} {{ product.stock }}
              </el-tag>
              <el-button
                type="primary"
                :loading="openingCheckoutId === product.id"
                :disabled="product.stock <= 0 || openingCheckoutId !== null"
                @click="openCheckout(product)"
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
