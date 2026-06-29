<script setup lang="ts">
import { Upload } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { stats as fetchStats } from '@/api/admin'
import { addMonkey, deleteMonkey, listMonkeys, updateMonkey, uploadImage } from '@/api/catalog'
import * as ordersApi from '@/api/orders'
import AppShell from '@/components/AppShell.vue'
import ProductImage from '@/components/ProductImage.vue'
import type { Monkey, MonkeyRequest, Order, Stats } from '@/types'
import { dateTime, money, orderStatusKey, statusType } from '@/utils/format'

const loading = ref(false)
const productDialog = ref(false)
const monkeys = ref<Monkey[]>([])
const orders = ref<Order[]>([])
const stats = ref<Stats | null>(null)
const orderKeyword = ref('')
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
    const [statsData, productData, orderData] = await Promise.all([fetchStats(), listMonkeys(), ordersApi.allOrders()])
    stats.value = statsData
    monkeys.value = productData
    orders.value = orderData
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Unable to load admin data')
  } finally {
    loading.value = false
  }
}

async function uploadProductImage(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) {
    return
  }
  const uploaded = await uploadImage(file, 'product')
  productForm.imageUrl = uploaded.path
  ElMessage.success(uploaded.cropped ? 'Image uploaded and cropped' : 'Image uploaded')
}

async function saveProduct() {
  if (productForm.id) {
    await updateMonkey(productForm)
  } else {
    await addMonkey(productForm)
  }
  productDialog.value = false
  await loadAdmin()
}

async function removeProduct(id: number) {
  await ElMessageBox.confirm('Delete this product?', 'Confirm', { type: 'warning' })
  await deleteMonkey(id)
  await loadAdmin()
}

async function runOrderAction(action: () => Promise<Order>) {
  await action()
  await loadAdmin()
}

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
          <span>Orders</span>
          <strong>{{ stats?.totalOrders || 0 }}</strong>
        </div>
        <div class="metric">
          <span>Visits</span>
          <strong>{{ stats?.totalVisits || 0 }}</strong>
        </div>
        <div class="metric">
          <span>Return rate</span>
          <strong>{{ stats?.returnRate || '0%' }}</strong>
        </div>
      </div>

      <section class="section-band">
        <div class="section-title">
          <h2>Products</h2>
          <el-button type="primary" @click="resetProductForm()">
            Add product
          </el-button>
        </div>
        <el-table :data="monkeys" class="data-table">
          <el-table-column width="88">
            <template #default="{ row }">
              <ProductImage :src="row.imageUrl" :alt="row.name" />
            </template>
          </el-table-column>
          <el-table-column prop="name" label="Name" />
          <el-table-column prop="breed" label="Breed" />
          <el-table-column label="Price">
            <template #default="{ row }">
              {{ money(row.price) }}
            </template>
          </el-table-column>
          <el-table-column prop="stock" label="Stock" />
          <el-table-column width="190">
            <template #default="{ row }">
              <el-button plain @click="resetProductForm(row)">
                Edit
              </el-button>
              <el-button type="danger" plain @click="removeProduct(row.id)">
                Delete
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <section class="section-band">
        <div class="section-title">
          <h2>Orders</h2>
          <el-input v-model="orderKeyword" class="table-search" placeholder="Search orders" clearable />
        </div>
        <el-table :data="filteredOrders" class="data-table">
          <el-table-column prop="orderNo" label="Order" min-width="160" />
          <el-table-column prop="productName" label="Product" />
          <el-table-column prop="buyerName" label="Buyer" />
          <el-table-column label="Created">
            <template #default="{ row }">
              {{ dateTime(row.createTime) }}
            </template>
          </el-table-column>
          <el-table-column label="Status">
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
                @click="runOrderAction(() => ordersApi.shipOrder(row.id))"
              >
                Ship
              </el-button>
              <el-button
                v-if="orderStatusKey(row.status) === 'RETURN_REQUESTED'"
                plain
                @click="runOrderAction(() => ordersApi.approveReturn(row.id))"
              >
                Approve return
              </el-button>
              <el-button
                v-if="orderStatusKey(row.status) === 'RETURN_SHIPPING'"
                plain
                @click="runOrderAction(() => ordersApi.confirmReturn(row.id))"
              >
                Refund
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </section>

    <el-dialog v-model="productDialog" title="Product" width="680">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="Name">
            <el-input v-model="productForm.name" />
          </el-form-item>
          <el-form-item label="Breed">
            <el-input v-model="productForm.breed" />
          </el-form-item>
          <el-form-item label="Price">
            <el-input v-model="productForm.price" type="number" />
          </el-form-item>
          <el-form-item label="Stock">
            <el-input v-model.number="productForm.stock" type="number" />
          </el-form-item>
        </div>
        <el-form-item label="Description">
          <el-input v-model="productForm.description" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="Image">
          <label class="file-picker" for="product-image-input">
            <el-icon><Upload /></el-icon>
            <span>{{ $t('common.upload') }}</span>
            <input id="product-image-input" type="file" accept="image/png,image/jpeg" @change="uploadProductImage">
          </label>
          <ProductImage v-if="productForm.imageUrl" :src="productForm.imageUrl" :alt="productForm.name || 'Product'" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="productDialog = false">
          {{ $t('common.cancel') }}
        </el-button>
        <el-button type="primary" @click="saveProduct">
          {{ $t('common.save') }}
        </el-button>
      </template>
    </el-dialog>
  </AppShell>
</template>
