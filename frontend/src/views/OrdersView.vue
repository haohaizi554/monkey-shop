<script setup lang="ts">
import { useDebounceFn } from '@vueuse/core'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import * as ordersApi from '@/api/orders'
import AppShell from '@/components/AppShell.vue'
import ProductImage from '@/components/ProductImage.vue'
import type { Order } from '@/types'
import { dateTime, money, orderStatusKey, statusType } from '@/utils/format'

const loading = ref(false)
const actionInProgress = ref<string | null>(null)
const orders = ref<Order[]>([])
const { t } = useI18n()

async function loadOrders() {
  loading.value = true
  try {
    orders.value = await ordersApi.myOrders()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('common.unableToLoadOrders'))
  } finally {
    loading.value = false
  }
}

function orderActionKey(action: string, orderId: number): string {
  return `${action}:${orderId}`
}

function isUserDismissal(error: unknown): boolean {
  return error === 'cancel' || error === 'close'
}

const runAction = useDebounceFn(
  async (key: string, messageKey: string, action: () => Promise<unknown>) => {
    if (actionInProgress.value !== null) {
      return
    }
    actionInProgress.value = key
    try {
      await ElMessageBox.confirm(t(messageKey), t('common.confirm'), { type: 'warning' })
      await action()
      ElMessage.success(t('common.updated'))
      await loadOrders()
    } catch (error) {
      if (!isUserDismissal(error)) {
        ElMessage.error(error instanceof Error ? error.message : t('common.unableToUpdateOrder'))
      }
    } finally {
      actionInProgress.value = null
    }
  },
  350,
)

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

    <el-empty v-if="!loading && orders.length === 0" :description="$t('common.noOrders')" />
    <div v-else v-loading="loading" class="order-list">
      <article v-for="order in orders" :key="order.id" class="order-row">
        <ProductImage :src="order.productImage" :alt="order.productName" />
        <div class="order-main">
          <div class="order-title">
            <div>
              <h2>{{ order.productName }}</h2>
              <p>{{ order.orderNo }} / {{ dateTime(order.createTime) }}</p>
            </div>
            <el-tag :type="statusType(order.status)" disable-transitions>
              {{ order.status }}
            </el-tag>
          </div>
          <p>{{ order.receiverName }} / {{ order.receiverPhone }} / {{ order.addressSnapshot }}</p>
          <strong>{{ money(order.price) }}</strong>
        </div>
        <div class="row-actions">
          <el-button
            v-if="orderStatusKey(order.status) === 'SHIPPED'"
            type="primary"
            :loading="actionInProgress === orderActionKey('receive', order.id)"
            :disabled="actionInProgress !== null"
            @click="
              runAction(orderActionKey('receive', order.id), 'common.receiveOrderConfirm', () =>
                ordersApi.receiveOrder(order.id),
              )
            "
          >
            {{ $t('common.receive') }}
          </el-button>
          <el-button
            v-if="orderStatusKey(order.status) === 'COMPLETED'"
            plain
            :loading="actionInProgress === orderActionKey('return', order.id)"
            :disabled="actionInProgress !== null"
            @click="
              runAction(orderActionKey('return', order.id), 'common.requestReturnConfirm', () =>
                ordersApi.applyReturn(order.id),
              )
            "
          >
            {{ $t('common.return') }}
          </el-button>
          <RouterLink
            v-if="orderStatusKey(order.status) === 'COMPLETED'"
            :to="`/orders/${order.id}/review`"
          >
            <el-button plain>
              {{ $t('common.review') }}
            </el-button>
          </RouterLink>
          <RouterLink :to="`/payment/${order.id}`">
            <el-button plain>
              {{ $t('common.payment') }}
            </el-button>
          </RouterLink>
          <el-button
            v-if="orderStatusKey(order.status) === 'WAITING_RETURN_SHIPMENT'"
            plain
            :loading="actionInProgress === orderActionKey('ship-return', order.id)"
            :disabled="actionInProgress !== null"
            @click="
              runAction(orderActionKey('ship-return', order.id), 'common.shipReturnConfirm', () =>
                ordersApi.shipReturn(order.id),
              )
            "
          >
            {{ $t('common.shipReturn') }}
          </el-button>
          <el-button
            type="danger"
            plain
            :loading="actionInProgress === orderActionKey('hide', order.id)"
            :disabled="actionInProgress !== null"
            @click="
              runAction(orderActionKey('hide', order.id), 'common.hideOrderConfirm', () =>
                ordersApi.hideOrder(order.id),
              )
            "
          >
            {{ $t('common.delete') }}
          </el-button>
        </div>
      </article>
    </div>
  </AppShell>
</template>
