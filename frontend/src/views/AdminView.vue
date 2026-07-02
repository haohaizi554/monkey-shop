<script setup lang="ts">
import { Upload } from '@element-plus/icons-vue'
import { useDebounceFn } from '@vueuse/core'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { stats as fetchStats } from '@/api/admin'
import { addMonkey, deleteMonkey, listMonkeys, updateMonkey, uploadImage } from '@/api/catalog'
import * as ordersApi from '@/api/orders'
import AppShell from '@/components/AppShell.vue'
import ProductImage from '@/components/ProductImage.vue'
import type { Monkey, MonkeyRequest, Order, Stats } from '@/types'
import { dateTime, money, orderStatusKey, statusType } from '@/utils/format'

const loading = ref(false)
const savingProduct = ref(false)
const uploadingProductImage = ref(false)
const deletingProductId = ref<number | null>(null)
const orderActionInProgress = ref<string | null>(null)
const productDialog = ref(false)
const monkeys = ref<Monkey[]>([])
const orders = ref<Order[]>([])
const stats = ref<Stats | null>(null)
const orderKeyword = ref('')
const { t } = useI18n()
const productForm = reactive<MonkeyRequest>({
  id: null,
  name: '',
  breed: '',
  price: '',
  description: '',
  imageUrl: '',
  stock: 0,
})

const filteredOrders = computed(() => {
  const keyword = orderKeyword.value.trim().toLowerCase()
  return orders.value.filter(
    (order) =>
      !keyword ||
      order.orderNo.toLowerCase().includes(keyword) ||
      order.productName.toLowerCase().includes(keyword) ||
      order.buyerName.toLowerCase().includes(keyword),
  )
})

function resetProductForm(monkey?: Monkey) {
  Object.assign(productForm, {
    id: monkey?.id ?? null,
    name: monkey?.name ?? '',
    breed: monkey?.breed ?? '',
    price: monkey?.price ?? '',
    description: monkey?.description ?? '',
    imageUrl: monkey?.imageUrl ?? '',
    stock: monkey?.stock ?? 0,
  })
  productDialog.value = true
}

async function loadAdmin() {
  loading.value = true
  try {
    const [statsData, productData, orderData] = await Promise.all([
      fetchStats(),
      listMonkeys(),
      ordersApi.allOrders(),
    ])
    stats.value = statsData
    monkeys.value = productData
    orders.value = orderData
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('common.unableToLoadAdmin'))
  } finally {
    loading.value = false
  }
}

async function uploadProductImage(event: Event) {
  if (uploadingProductImage.value) {
    return
  }
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) {
    return
  }
  uploadingProductImage.value = true
  try {
    const uploaded = await uploadImage(file, 'product')
    productForm.imageUrl = uploaded.path
    ElMessage.success(
      uploaded.cropped ? t('common.imageUploadedCropped') : t('common.imageUploaded'),
    )
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('common.unableToUploadImage'))
  } finally {
    uploadingProductImage.value = false
  }
}

const saveProduct = useDebounceFn(async () => {
  if (savingProduct.value) {
    return
  }
  savingProduct.value = true
  try {
    if (productForm.id) {
      await updateMonkey(productForm)
    } else {
      await addMonkey(productForm)
    }
    productDialog.value = false
    await loadAdmin()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('common.unableToSaveProduct'))
  } finally {
    savingProduct.value = false
  }
}, 350)

function orderActionKey(action: string, orderId: number): string {
  return `${action}:${orderId}`
}

function isUserDismissal(error: unknown): boolean {
  return error === 'cancel' || error === 'close'
}

const removeProduct = useDebounceFn(async (id: number) => {
  if (deletingProductId.value !== null) {
    return
  }
  deletingProductId.value = id
  try {
    await ElMessageBox.confirm(t('common.deleteProductConfirm'), t('common.confirm'), {
      type: 'warning',
    })
    await deleteMonkey(id)
    await loadAdmin()
  } catch (error) {
    if (!isUserDismissal(error)) {
      ElMessage.error(error instanceof Error ? error.message : t('common.unableToDeleteProduct'))
    }
  } finally {
    deletingProductId.value = null
  }
}, 350)

const runOrderAction = useDebounceFn(async (key: string, action: () => Promise<Order>) => {
  if (orderActionInProgress.value !== null) {
    return
  }
  orderActionInProgress.value = key
  try {
    await action()
    await loadAdmin()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('common.unableToUpdateOrder'))
  } finally {
    orderActionInProgress.value = null
  }
}, 350)

onMounted(() => {
  void loadAdmin()
})
</script>

<template>
  <AppShell>
    <section v-loading="loading" class="admin-layout">
      <div class="metric-grid">
        <div class="metric">
          <span>GMV</span>
          <strong>{{ stats?.totalGmv || '0.00' }}</strong>
        </div>
        <div class="metric">
          <span>{{ $t('common.orders') }}</span>
          <strong>{{ stats?.totalOrders || 0 }}</strong>
        </div>
        <div class="metric">
          <span>{{ $t('common.visits') }}</span>
          <strong>{{ stats?.totalVisits || 0 }}</strong>
        </div>
        <div class="metric">
          <span>{{ $t('common.returnRate') }}</span>
          <strong>{{ stats?.returnRate || '0%' }}</strong>
        </div>
      </div>

      <section class="section-band">
        <div class="section-title">
          <h2>{{ $t('common.products') }}</h2>
          <el-button type="primary" @click="resetProductForm()">
            {{ $t('common.addProduct') }}
          </el-button>
        </div>
        <el-table :data="monkeys" class="data-table">
          <el-table-column width="88">
            <template #default="{ row }">
              <ProductImage :src="row.imageUrl" :alt="row.name" />
            </template>
          </el-table-column>
          <el-table-column prop="name" :label="$t('common.name')" />
          <el-table-column prop="breed" :label="$t('common.breed')" />
          <el-table-column :label="$t('common.price')">
            <template #default="{ row }">
              {{ money(row.price) }}
            </template>
          </el-table-column>
          <el-table-column prop="stock" :label="$t('common.stock')" />
          <el-table-column width="190">
            <template #default="{ row }">
              <el-button plain @click="resetProductForm(row)"> {{ $t('common.edit') }} </el-button>
              <el-button
                type="danger"
                plain
                :loading="deletingProductId === row.id"
                :disabled="deletingProductId !== null"
                @click="removeProduct(row.id)"
              >
                {{ $t('common.delete') }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <section class="section-band">
        <div class="section-title">
          <h2>{{ $t('common.orders') }}</h2>
          <el-input
            v-model="orderKeyword"
            class="table-search"
            :placeholder="$t('common.searchOrders')"
            clearable
          />
        </div>
        <el-table :data="filteredOrders" class="data-table">
          <el-table-column prop="orderNo" :label="$t('common.order')" min-width="160" />
          <el-table-column prop="productName" :label="$t('common.product')" />
          <el-table-column prop="buyerName" :label="$t('common.buyer')" />
          <el-table-column :label="$t('common.created')">
            <template #default="{ row }">
              {{ dateTime(row.createTime) }}
            </template>
          </el-table-column>
          <el-table-column :label="$t('common.status')">
            <template #default="{ row }">
              <el-tag :type="statusType(row.status)" disable-transitions>
                {{ row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column width="260">
            <template #default="{ row }">
              <el-button
                v-if="orderStatusKey(row.status) === 'PAID'"
                type="primary"
                :loading="orderActionInProgress === orderActionKey('ship', row.id)"
                :disabled="orderActionInProgress !== null"
                @click="
                  runOrderAction(orderActionKey('ship', row.id), () => ordersApi.shipOrder(row.id))
                "
              >
                {{ $t('common.ship') }}
              </el-button>
              <el-button
                v-if="orderStatusKey(row.status) === 'RETURN_REQUESTED'"
                plain
                :loading="orderActionInProgress === orderActionKey('approve-return', row.id)"
                :disabled="orderActionInProgress !== null"
                @click="
                  runOrderAction(orderActionKey('approve-return', row.id), () =>
                    ordersApi.approveReturn(row.id),
                  )
                "
              >
                {{ $t('common.approveReturn') }}
              </el-button>
              <el-button
                v-if="orderStatusKey(row.status) === 'RETURN_SHIPPING'"
                plain
                :loading="orderActionInProgress === orderActionKey('refund', row.id)"
                :disabled="orderActionInProgress !== null"
                @click="
                  runOrderAction(orderActionKey('refund', row.id), () =>
                    ordersApi.confirmReturn(row.id),
                  )
                "
              >
                {{ $t('common.refund') }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </section>

    <el-dialog v-model="productDialog" :title="$t('common.product')" width="680">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item :label="$t('common.name')">
            <el-input v-model="productForm.name" />
          </el-form-item>
          <el-form-item :label="$t('common.breed')">
            <el-input v-model="productForm.breed" />
          </el-form-item>
          <el-form-item :label="$t('common.price')">
            <el-input v-model="productForm.price" type="number" />
          </el-form-item>
          <el-form-item :label="$t('common.stock')">
            <el-input v-model.number="productForm.stock" type="number" />
          </el-form-item>
        </div>
        <el-form-item :label="$t('common.description')">
          <el-input v-model="productForm.description" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item :label="$t('common.image')">
          <label class="file-picker" for="product-image-input">
            <el-icon><Upload /></el-icon>
            <span>{{ $t('common.upload') }}</span>
            <input
              id="product-image-input"
              type="file"
              accept="image/png,image/jpeg"
              :disabled="uploadingProductImage"
              @change="uploadProductImage"
            />
          </label>
          <ProductImage
            v-if="productForm.imageUrl"
            :src="productForm.imageUrl"
            :alt="productForm.name || $t('common.product')"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="productDialog = false">
          {{ $t('common.cancel') }}
        </el-button>
        <el-button
          type="primary"
          :loading="savingProduct"
          :disabled="savingProduct"
          @click="saveProduct"
        >
          {{ $t('common.save') }}
        </el-button>
      </template>
    </el-dialog>
  </AppShell>
</template>
