<script setup lang="ts">
import { Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listMonkeys } from '@/api/catalog'
import { addAddress, addresses as fetchAddresses } from '@/api/user'
import { createOrder } from '@/api/orders'
import AppShell from '@/components/AppShell.vue'
import ProductImage from '@/components/ProductImage.vue'
import { useAuthStore } from '@/stores/auth'
import type { Address, Monkey } from '@/types'
import { money } from '@/utils/format'

const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const checkoutOpen = ref(false)
const monkeys = ref<Monkey[]>([])
const addresses = ref<Address[]>([])
const selectedMonkey = ref<Monkey | null>(null)
const selectedAddressId = ref<number | null>(null)
const filters = reactive({ keyword: '', minPrice: '', maxPrice: '', inStockOnly: false })
const newAddress = reactive({ receiverName: '', phone: '', detailAddress: '' })

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

async function loadMonkeys() {
  loading.value = true
  try {
    monkeys.value = await listMonkeys()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Unable to load catalog')
  } finally {
    loading.value = false
  }
}

async function openCheckout(monkey: Monkey) {
  if (!auth.isLoggedIn) {
    await router.push('/login')
    return
  }
  selectedMonkey.value = monkey
  addresses.value = await fetchAddresses()
  selectedAddressId.value = addresses.value.find((item) => item.isDefault === 1)?.id ?? addresses.value[0]?.id ?? null
  checkoutOpen.value = true
}

async function saveAddress() {
  const saved = await addAddress(newAddress)
  addresses.value = await fetchAddresses()
  selectedAddressId.value = saved.id
  Object.assign(newAddress, { receiverName: '', phone: '', detailAddress: '' })
}

async function submitOrder() {
  if (!selectedMonkey.value || !selectedAddressId.value) {
    ElMessage.warning('Choose an address first')
    return
  }
  await createOrder(selectedMonkey.value.id, selectedAddressId.value)
  ElMessage.success('Order created')
  checkoutOpen.value = false
  await loadMonkeys()
  await router.push('/orders')
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
        <el-input v-model="filters.keyword" :prefix-icon="Search" :placeholder="$t('common.search')" clearable />
        <el-input v-model="filters.minPrice" type="number" placeholder="Min" />
        <el-input v-model="filters.maxPrice" type="number" placeholder="Max" />
        <el-checkbox v-model="filters.inStockOnly">
          {{ $t('shop.inStockOnly') }}
        </el-checkbox>
      </div>
    </section>

    <el-skeleton :loading="loading" animated :count="6">
      <template #default>
        <div class="product-grid">
          <article v-for="monkey in filteredMonkeys" :key="monkey.id" class="product-card">
            <ProductImage :src="monkey.imageUrl" :alt="monkey.name" />
            <div class="product-body">
              <div class="product-heading">
                <div>
                  <h2>{{ monkey.name }}</h2>
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
                <el-button type="primary" :disabled="monkey.stock <= 0" @click="openCheckout(monkey)">
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
          {{ address.receiverName }} · {{ address.phone }} · {{ address.detailAddress }}
        </el-radio>
      </el-radio-group>

      <el-divider>{{ $t('shop.addAddress') }}</el-divider>
      <div class="inline-form">
        <el-input v-model="newAddress.receiverName" placeholder="Receiver" />
        <el-input v-model="newAddress.phone" placeholder="Phone" />
        <el-input v-model="newAddress.detailAddress" placeholder="Address" />
        <el-button plain @click="saveAddress">
          {{ $t('common.save') }}
        </el-button>
      </div>

      <template #footer>
        <el-button @click="checkoutOpen = false">
          {{ $t('common.cancel') }}
        </el-button>
        <el-button type="primary" @click="submitOrder">
          {{ $t('shop.placeOrder') }}
        </el-button>
      </template>
    </el-dialog>
  </AppShell>
</template>
