import { computed, onMounted } from 'vue'
import { allOrders } from '@/api/orders'
import { useAsyncState } from '@/composables/useAsyncState'
import type { Order } from '@/types'

export function useAdminOrders() {
  const resource = useAsyncState<Order[]>({ preserveData: true })
  const orders = computed(() => resource.data.value ?? [])

  async function loadOrders() {
    return resource.load(() => allOrders(), {
      preserveData: true,
      isEmpty: (rows) => rows.length === 0,
    })
  }

  function patchOrder(order: Order) {
    const rows = resource.data.value
    if (!rows) {
      return
    }
    const index = rows.findIndex((candidate) => candidate.id === order.id)
    if (index >= 0) {
      rows.splice(index, 1, order)
    } else {
      rows.unshift(order)
    }
  }

  onMounted(() => {
    void loadOrders()
  })

  return {
    ...resource,
    orders,
    loadOrders,
    patchOrder,
  }
}
