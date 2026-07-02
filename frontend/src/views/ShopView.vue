<script setup lang="ts">
import { Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { listMonkeys } from '@/api/catalog'
import AppShell from '@/components/AppShell.vue'
import ProductImage from '@/components/ProductImage.vue'
import { useCheckout } from '@/composables/useCheckout'
import { productListJsonLd } from '@/seo/product-json-ld'
import { useJsonLd } from '@/seo/useJsonLd'
import type { Monkey } from '@/types'
import { money } from '@/utils/format'

const loading = ref(false)
const monkeys = ref<Monkey[]>([])
const filters = reactive({ keyword: '', minPrice: '', maxPrice: '', inStockOnly: false })
const { t } = useI18n()
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
} = useCheckout({ afterOrderCreated: loadMonkeys })

const filteredMonkeys = computed(() =>
  monkeys.value.filter((monkey) => {
    const keyword = filters.keyword.trim().toLowerCase()
    const price = Number(monkey.price)
    return (
      (!keyword ||
        monkey.name.toLowerCase().includes(keyword) ||
        monkey.breed.toLowerCase().includes(keyword)) &&
      (!filters.minPrice || price >= Number(filters.minPrice)) &&
      (!filters.maxPrice || price <= Number(filters.maxPrice)) &&
      (!filters.inStockOnly || monkey.stock > 0)
    )
  }),
)
const productListStructuredData = computed(() => productListJsonLd(filteredMonkeys.value))
useJsonLd('monkeyshop-product-list-jsonld', productListStructuredData)

async function loadMonkeys() {
  loading.value = true
  try {
    monkeys.value = await listMonkeys()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('common.unableToLoadCatalog'))
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void loadMonkeys()
})
</script>

<template>
  <AppShell>
    <section class="toolbar-band">
      <div>
        <h1>{{ $t('shop.title') }}</h1>
        <p>{{ $t('shop.subtitle') }}</p>
      </div>
      <div class="catalog-tools">
        <el-input
          v-model="filters.keyword"
          :prefix-icon="Search"
          :placeholder="$t('common.search')"
          clearable
        />
        <el-input v-model="filters.minPrice" type="number" :placeholder="$t('common.minPrice')" />
        <el-input v-model="filters.maxPrice" type="number" :placeholder="$t('common.maxPrice')" />
        <el-checkbox v-model="filters.inStockOnly">
          {{ $t('shop.inStockOnly') }}
        </el-checkbox>
      </div>
    </section>

    <el-skeleton :loading="loading" animated :count="6">
      <template #default>
        <div class="product-grid">
          <article v-for="monkey in filteredMonkeys" :key="monkey.id" class="product-card">
            <RouterLink :to="`/shop/${monkey.id}`" :aria-label="monkey.name">
              <ProductImage :src="monkey.imageUrl" :alt="monkey.name" />
            </RouterLink>
            <div class="product-body">
              <div class="product-heading">
                <div>
                  <RouterLink :to="`/shop/${monkey.id}`">
                    <h2>{{ monkey.name }}</h2>
                  </RouterLink>
                  <p>{{ monkey.breed }}</p>
                </div>
                <strong>{{ money(monkey.price) }}</strong>
              </div>
              <p class="description">
                {{ monkey.description }}
              </p>
              <div class="product-actions">
                <el-tag :type="monkey.stock > 0 ? 'success' : 'info'" disable-transitions>
                  {{ $t('common.stock') }} {{ monkey.stock }}
                </el-tag>
                <el-button
                  type="primary"
                  :loading="openingCheckoutId === monkey.id"
                  :disabled="monkey.stock <= 0 || openingCheckoutId !== null"
                  @click="openCheckout(monkey)"
                >
                  {{ monkey.stock > 0 ? $t('shop.buy') : $t('shop.soldOut') }}
                </el-button>
              </div>
            </div>
          </article>
        </div>
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
