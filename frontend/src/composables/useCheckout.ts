import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createOrder } from '@/api/orders'
import { addAddress, addresses as fetchAddresses } from '@/api/user'
import { useAuthStore } from '@/stores/auth'
import type { Address, AddressRequest, Monkey } from '@/types'

type NoticeLevel = 'error' | 'success' | 'warning'

interface CheckoutOptions {
  afterOrderCreated?: () => Promise<void> | void
  notify?: (level: NoticeLevel, message: string) => void
}

export function useCheckout(options: CheckoutOptions = {}) {
  const router = useRouter()
  const auth = useAuthStore()
  const openingCheckoutId = ref<number | null>(null)
  const submittingOrder = ref(false)
  const checkoutOpen = ref(false)
  const addresses = ref<Address[]>([])
  const selectedMonkey = ref<Monkey | null>(null)
  const selectedAddressId = ref<number | null>(null)
  const newAddress = reactive<AddressRequest>({
    receiverName: '',
    phone: '',
    detailAddress: '',
  })
  let submitTimer: ReturnType<typeof setTimeout> | undefined

  function notify(level: NoticeLevel, message: string) {
    options.notify?.(level, message)
  }

  async function openCheckout(monkey: Monkey) {
    if (!auth.isLoggedIn) {
      await router.push('/login')
      return
    }
    if (openingCheckoutId.value !== null) {
      return
    }
    openingCheckoutId.value = monkey.id
    try {
      selectedMonkey.value = monkey
      addresses.value = await fetchAddresses()
      selectedAddressId.value =
        addresses.value.find((item) => item.isDefault === 1)?.id ?? addresses.value[0]?.id ?? null
      checkoutOpen.value = true
    } catch (error) {
      notify('error', error instanceof Error ? error.message : 'Unable to open checkout')
    } finally {
      openingCheckoutId.value = null
    }
  }

  async function saveAddress() {
    try {
      const saved = await addAddress(newAddress)
      addresses.value = await fetchAddresses()
      selectedAddressId.value = saved.id
      Object.assign(newAddress, { receiverName: '', phone: '', detailAddress: '' })
    } catch (error) {
      notify('error', error instanceof Error ? error.message : 'Unable to save address')
    }
  }

  async function doSubmitOrder() {
    if (submittingOrder.value) {
      return
    }
    if (!selectedMonkey.value || !selectedAddressId.value) {
      notify('warning', 'Choose an address first')
      return
    }
    submittingOrder.value = true
    try {
      await createOrder(selectedMonkey.value.id, selectedAddressId.value)
      notify('success', 'Order created')
      checkoutOpen.value = false
      await options.afterOrderCreated?.()
      await router.push('/orders')
    } catch (error) {
      notify('error', error instanceof Error ? error.message : 'Unable to create order')
    } finally {
      submittingOrder.value = false
    }
  }

  function submitOrder() {
    if (submitTimer !== undefined) {
      clearTimeout(submitTimer)
    }
    submitTimer = setTimeout(() => {
      void doSubmitOrder()
    }, 350)
  }

  return {
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
  }
}
