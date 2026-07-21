import { onScopeDispose, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { directCheckoutCart, type CartDirectCheckoutRequest } from '@/api/cart'
import { browserDeviceFingerprint } from '@/api/http'
import { createOrder } from '@/api/orders'
import { assessRisk } from '@/api/risk'
import { addAddress, addressPage as fetchAddressPage } from '@/api/user'
import { useNotify } from '@/composables/useNotify'
import { useAuthStore } from '@/stores/auth'
import type { Address, AddressRequest, CartCheckoutRequest, Monkey, RiskDecision } from '@/types'
import { getIdempotencyIntent } from '@/utils/idempotencyIntent'

type NoticeLevel = 'error' | 'success' | 'warning'

interface CheckoutOptions {
  afterOrderCreated?: () => Promise<void> | void
  notify?: (level: NoticeLevel, message: string) => void
}

export interface DirectPurchaseSelection {
  skuId: number
  shopId: number
  quantity: number
}

class CheckoutRiskError extends Error {
  readonly decision: RiskDecision

  constructor(decision: RiskDecision) {
    super('checkout-risk-blocked')
    this.decision = decision
  }
}

interface CheckoutDiscountAllocation {
  storeDiscountAmount?: string | number
  platformDiscountAmount?: string | number
}

interface CheckoutOrderReference {
  orderIds?: Array<number | null | undefined>
  subOrders?: Array<{ formalOrderId?: number | null }>
}

export function normalizeCartCheckoutIntent(input: CartCheckoutRequest): CartCheckoutRequest {
  const province = input.province?.trim()
  return {
    addressId: input.addressId,
    ...(province ? { province } : {}),
    couponCodes: input.couponCodes.map((code) => code.trim()).filter(Boolean),
  }
}

export function checkoutDiscountTotals(subOrders: CheckoutDiscountAllocation[]): {
  store: number
  platform: number
} {
  const toCents = (value: string | number | undefined) => {
    const numeric = Number(value ?? 0)
    return Number.isFinite(numeric) ? Math.round(numeric * 100) : 0
  }
  const totals = subOrders.reduce(
    (sum, order) => ({
      store: sum.store + toCents(order.storeDiscountAmount),
      platform: sum.platform + toCents(order.platformDiscountAmount),
    }),
    { store: 0, platform: 0 },
  )
  return { store: totals.store / 100, platform: totals.platform / 100 }
}

export function checkoutOrderIds(checkout: CheckoutOrderReference): number[] {
  const candidates = checkout.orderIds?.length
    ? checkout.orderIds
    : (checkout.subOrders ?? []).map((order) => order.formalOrderId)
  return Array.from(
    new Set(
      candidates.filter(
        (value): value is number =>
          typeof value === 'number' && Number.isSafeInteger(value) && value > 0,
      ),
    ),
  )
}

export function buildDirectCheckoutIntent(
  selection: DirectPurchaseSelection,
  addressId: number,
): CartDirectCheckoutRequest {
  return {
    skuId: selection.skuId,
    shopId: selection.shopId,
    quantity: Math.max(1, Math.trunc(selection.quantity)),
    addressId,
    couponCodes: [],
  }
}

export function useCheckout(options: CheckoutOptions = {}) {
  const router = useRouter()
  const auth = useAuthStore()
  const { t } = useI18n()
  const appNotify = useNotify()
  const openingCheckoutId = ref<number | null>(null)
  const submittingOrder = ref(false)
  const savingAddress = ref(false)
  const loadingAddresses = ref(false)
  const checkoutOpen = ref(false)
  const addresses = ref<Address[]>([])
  const addressPageNumber = ref(0)
  const addressPageSize = 6
  const addressTotal = ref(0)
  const selectedMonkey = ref<Monkey | null>(null)
  const selectedDirectPurchase = ref<DirectPurchaseSelection | null>(null)
  const selectedAddressId = ref<number | null>(null)
  const newAddress = reactive<AddressRequest>({
    receiverName: '',
    phone: '',
    detailAddress: '',
  })
  let addressRequestSequence = 0
  let addressController: AbortController | null = null

  function notify(level: NoticeLevel, message: string) {
    if (options.notify) {
      options.notify(level, message)
      return
    }
    appNotify.notify(level, message)
  }

  function notifyApiError(error: unknown, fallbackKey: string) {
    if (options.notify) {
      options.notify('error', t(fallbackKey))
      return
    }
    appNotify.fromApiError(error, fallbackKey)
  }

  async function loadCheckoutAddresses(
    pageNumber = addressPageNumber.value,
    preferredAddressId: number | null = selectedAddressId.value,
  ): Promise<boolean> {
    const requestId = ++addressRequestSequence
    addressController?.abort()
    const requestController = new AbortController()
    addressController = requestController
    loadingAddresses.value = true
    try {
      const result = await fetchAddressPage({
        page: pageNumber,
        size: addressPageSize,
        sort: 'isDefault,desc',
        signal: requestController.signal,
      })
      if (requestId !== addressRequestSequence) return false

      addressPageNumber.value = result.page
      addressTotal.value = result.totalElements
      addresses.value = result.content
      selectedAddressId.value = result.content.some((item) => item.id === preferredAddressId)
        ? preferredAddressId
        : (result.content.find((item) => item.isDefault === 1)?.id ?? result.content[0]?.id ?? null)
      return true
    } catch (error) {
      if (requestId !== addressRequestSequence) return false
      throw error
    } finally {
      if (requestId === addressRequestSequence) {
        loadingAddresses.value = false
        if (addressController === requestController) addressController = null
      }
    }
  }

  async function changeAddressPage(pageNumber: number) {
    if (submittingOrder.value || savingAddress.value || loadingAddresses.value) return
    try {
      await loadCheckoutAddresses(pageNumber - 1)
    } catch (error) {
      notifyApiError(error, 'checkout.openFailed')
    }
  }

  async function openCheckout(monkey: Monkey, directPurchase?: DirectPurchaseSelection) {
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
      selectedDirectPurchase.value = directPurchase ? { ...directPurchase } : null
      await loadCheckoutAddresses(0, null)
      checkoutOpen.value = true
    } catch (error) {
      notifyApiError(error, 'checkout.openFailed')
    } finally {
      openingCheckoutId.value = null
    }
  }

  async function saveAddress() {
    if (savingAddress.value || submittingOrder.value) {
      return
    }
    const payload = { ...newAddress }
    savingAddress.value = true
    try {
      const saved = await addAddress(payload)
      await loadCheckoutAddresses(0, saved.id)
      if (!addresses.value.some((address) => address.id === saved.id)) {
        addresses.value = [saved, ...addresses.value].slice(0, addressPageSize)
      }
      selectedAddressId.value = saved.id
      Object.assign(newAddress, { receiverName: '', phone: '', detailAddress: '' })
    } catch (error) {
      notifyApiError(error, 'checkout.saveAddressFailed')
    } finally {
      savingAddress.value = false
    }
  }

  async function doSubmitOrder() {
    if (submittingOrder.value) {
      return
    }
    const monkey = selectedMonkey.value
    const addressId = selectedAddressId.value
    if (!monkey || !addressId) {
      notify('warning', t('checkout.selectAddressFirst'))
      return
    }
    submittingOrder.value = true
    try {
      await ensureRiskAllowed(monkey.id)
      if (selectedDirectPurchase.value) {
        const payload = buildDirectCheckoutIntent(selectedDirectPurchase.value, addressId)
        const intent = getIdempotencyIntent('cart:checkout:direct', payload)
        await directCheckoutCart(payload, intent.key)
        intent.complete()
      } else {
        const payload = { monkeyId: monkey.id, addressId }
        const intent = getIdempotencyIntent('order:create', payload)
        await createOrder(payload.monkeyId, payload.addressId, intent.key)
        intent.complete()
      }
      notify('success', t('checkout.orderCreated'))
      checkoutOpen.value = false
      selectedDirectPurchase.value = null
      await options.afterOrderCreated?.()
      await router.push('/orders')
    } catch (error) {
      if (error instanceof CheckoutRiskError) {
        notify('warning', riskDecisionMessage(error.decision))
      } else {
        notifyApiError(error, 'checkout.createFailed')
      }
    } finally {
      submittingOrder.value = false
    }
  }

  async function ensureRiskAllowed(monkeyId: number) {
    const assessment = await assessRisk({
      deviceFingerprint: browserDeviceFingerprint(),
      productId: monkeyId,
    })
    if (assessment.decision !== 'ALLOW') {
      throw new CheckoutRiskError(assessment.decision)
    }
  }

  function riskDecisionMessage(decision: RiskDecision) {
    if (decision === 'RATE_LIMIT') {
      return t('checkout.riskRateLimit')
    }
    if (decision === 'TOTP_REQUIRED') {
      return t('checkout.riskTotpRequired')
    }
    return t('checkout.riskReview')
  }

  async function submitOrder() {
    await doSubmitOrder()
  }

  onScopeDispose(() => {
    addressRequestSequence += 1
    addressController?.abort()
    addressController = null
  })

  return {
    openingCheckoutId,
    submittingOrder,
    savingAddress,
    loadingAddresses,
    checkoutOpen,
    addresses,
    addressPageNumber,
    addressPageSize,
    addressTotal,
    selectedMonkey,
    selectedAddressId,
    newAddress,
    openCheckout,
    changeAddressPage,
    saveAddress,
    submitOrder,
  }
}
