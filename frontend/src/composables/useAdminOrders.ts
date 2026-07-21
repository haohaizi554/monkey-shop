import { computed, onMounted, onUnmounted, ref } from 'vue'
import { allOrderPage } from '@/api/orders'
import type { OrderSummary } from '@/api/orders'
import type { PageEnvelope } from '@/api/page'
import { useAsyncState } from '@/composables/useAsyncState'

interface AdminOrderQueryOptions {
  statuses?: () => readonly string[] | undefined
  keyword?: () => string | undefined
  pageSize?: number
}

export function useAdminOrders(options: AdminOrderQueryOptions = {}) {
  const resource = useAsyncState<PageEnvelope<OrderSummary>>({ preserveData: true })
  const currentPage = ref(0)
  const pageSize = options.pageSize ?? 25
  const page = computed(() => resource.data.value)
  const orders = computed(() => page.value?.content ?? [])

  async function loadOrders(pageNumber = currentPage.value) {
    currentPage.value = pageNumber
    return resource.load(
      ({ signal }) =>
        allOrderPage({
          page: pageNumber,
          size: pageSize,
          status: options.statuses?.()?.join(','),
          keyword: options.keyword?.()?.trim() || undefined,
          signal,
        }),
      {
        preserveData: true,
        isEmpty: (result) => result.content.length === 0,
      },
    )
  }

  function changePage(pageNumber: number) {
    void loadOrders(pageNumber - 1)
  }

  function patchOrder(order: OrderSummary) {
    const rows = resource.data.value?.content
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

  onUnmounted(() => resource.cancel())

  return {
    ...resource,
    page,
    pageSize,
    currentPage,
    orders,
    loadOrders,
    changePage,
    patchOrder,
  }
}
