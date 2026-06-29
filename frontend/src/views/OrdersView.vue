<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, ref } from 'vue'
import * as ordersApi from '@/api/orders'
import AppShell from '@/components/AppShell.vue'
import ProductImage from '@/components/ProductImage.vue'
import type { Order } from '@/types'
import { dateTime, money, orderStatusKey, statusType } from '@/utils/format'

const loading = ref(false)
const orders = ref<Order[]>([])

async function loadOrders() {
  loading.value = true
  try {
    orders.value = await ordersApi.myOrders()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Unable to load orders')
  } finally {
    loading.value = false
  }
}

async function runAction(label: string, action: () => Promise<unknown>) {
  await ElMessageBox.confirm(`${label}?`, 'Confirm', { type: 'warning' })
  await action()
  ElMessage.success('Updated')
  await loadOrders()
}

onMounted(() => {
  void loadOrders()
})
</script>

<template>
  <AppShell>
    <section class="page-heading">
      <h1>{{ $t('nav.orders') }}</h1>
      <el-button @click="loadOrders">
        {{ $t('common.reset') }}
      </el-button>
    </section>

    <el-empty v-if="!loading && orders.length === 0" description="No orders yet" />
    <div v-else v-loading="loading" class="order-list">
      <article v-for="order in orders" :key="order.id" class="order-row">
        <ProductImage :src="order.productImage" :alt="order.productName" />
        <div class="order-main">
          <div class="order-title">
            <div>
              <h2>{{ order.productName }}</h2>
              <p>{{ order.orderNo }} · {{ dateTime(order.createTime) }}</p>
            </div>
            <el-tag :type="statusType(order.status)" disable-transitions>
              {{ order.status }}
            </el-tag>
          </div>
          <p>{{ order.receiverName }} · {{ order.receiverPhone }} · {{ order.addressSnapshot }}</p>
          <strong>{{ money(order.price) }}</strong>
        </div>
        <div class="row-actions">
          <el-button
            v-if="orderStatusKey(order.status) === 'SHIPPED'"
            type="primary"
            @click="runAction('Receive order', () => ordersApi.receiveOrder(order.id))"
          >
            Receive
          </el-button>
          <el-button
            v-if="orderStatusKey(order.status) === 'COMPLETED'"
            plain
            @click="runAction('Request return', () => ordersApi.applyReturn(order.id))"
          >
            Return
          </el-button>
          <el-button
            v-if="orderStatusKey(order.status) === 'WAITING_RETURN_SHIPMENT'"
            plain
            @click="runAction('Ship return', () => ordersApi.shipReturn(order.id))"
          >
            Ship return
          </el-button>
          <el-button
            type="danger"
            plain
            @click="runAction('Hide order', () => ordersApi.hideOrder(order.id))"
          >
            {{ $t('common.delete') }}
          </el-button>
        </div>
      </article>
    </div>
  </AppShell>
</template>
