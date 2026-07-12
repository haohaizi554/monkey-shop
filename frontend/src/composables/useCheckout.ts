import { reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { browserDeviceFingerprint } from '@/api/http'
import { createOrder } from '@/api/orders'
import { assessRisk } from '@/api/risk'
import { addAddress, addresses as fetchAddresses } from '@/api/user'
import { useNotify } from '@/composables/useNotify'
import { useAuthStore } from '@/stores/auth'
import type { Address, AddressRequest, Monkey, RiskDecision } from '@/types'
import { getIdempotencyIntent } from '@/utils/idempotencyIntent'

type NoticeLevel = 'error' | 'success' | 'warning'

interface CheckoutOptions {
  afterOrderCreated?: () => Promise<void> | void
  notify?: (level: NoticeLevel, message: string) => void
}

class CheckoutRiskError extends Error {
  readonly decision: RiskDecision

  constructor(decision: RiskDecision) {
    super('checkout-risk-blocked')
    this.decision = decision
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
  const checkoutOpen = ref(false)
  const addresses = ref<Address[]>([])
  const selectedMonkey = ref<Monkey | null>(null)
  const selectedAddressId = ref<number | null>(null)
  const newAddress = reactive<AddressRequest>({
    receiverName: '',
    phone: '',
    detailAddress: '',
  })

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
      addresses.value = await fetchAddresses()
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
      const payload = { monkeyId: monkey.id, addressId }
      await ensureRiskAllowed(payload.monkeyId)
      const intent = getIdempotencyIntent('order:create', payload)
      await createOrder(payload.monkeyId, payload.addressId, intent.key)
      intent.complete()
      notify('success', t('checkout.orderCreated'))
      checkoutOpen.value = false
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

  return {
    openingCheckoutId,
    submittingOrder,
    savingAddress,
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
