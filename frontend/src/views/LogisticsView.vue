<script setup lang="ts">
import { RefreshRight, Search, Van } from '@element-plus/icons-vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import * as logisticsApi from '@/api/logistics'
import OrderStatusTimeline from '@/components/order/OrderStatusTimeline.vue'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import DataTableShell from '@/components/ui/DataTableShell.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import { useAsyncState } from '@/composables/useAsyncState'
import { useNotify } from '@/composables/useNotify'
import type {
  FreightQuoteResponse,
  LogisticsCarrier,
  LogisticsTracking,
  TrackingEvent,
} from '@/types'
import { money } from '@/utils/format'

const route = useRoute()
const { locale, t } = useI18n()
const notify = useNotify()
const trackingResource = useAsyncState<LogisticsTracking | null>({ timeoutMs: 20000 })

const trackingNo = ref('')
const addressText = ref('')
const quote = ref<FreightQuoteResponse | null>(null)

const addressParsePending = ref(false)
const addressParseError = ref('')
const quotePending = ref(false)
const quoteError = ref('')
const shipmentPending = ref(false)
const shipmentError = ref('')
const webhookPending = ref(false)
const webhookError = ref('')

const form = reactive({
  orderId: Number(route.params.orderId ?? route.query.orderId ?? 0),
  carrier: 'SF' as LogisticsCarrier,
  recipientPhone: '',
  province: '',
  city: '',
  district: '',
  detail: '',
  weightKg: 1,
  itemCount: 1,
})

const webhook = reactive({
  event: 'PICKUP' as TrackingEvent,
  eventId: '',
  location: '',
  remark: '',
  signature: '',
})

const tracking = computed(() => trackingResource.data.value)
const currentTrackingNo = computed(() => tracking.value?.trackingNo || trackingNo.value)
const isChinese = computed(() => locale.value === 'zh')

function localized(english: string, chinese: string): string {
  return isChinese.value ? chinese : english
}

function safeCarrier(carrier: string): string {
  const carriers: Record<string, [string, string]> = {
    SF: ['SF Express', '\u987a\u4e30\u901f\u8fd0'],
    ZTO: ['ZTO Express', '\u4e2d\u901a\u5feb\u9012'],
    YTO: ['YTO Express', '\u5706\u901a\u901f\u9012'],
  }
  const label = carriers[carrier]
  return label ? localized(label[0], label[1]) : localized('Delivery carrier', '\u7269\u6d41\u627f\u8fd0\u5546')
}

async function setTracking(result: LogisticsTracking) {
  trackingNo.value = result.trackingNo
  await trackingResource.load(() => Promise.resolve(result), {
    isEmpty: () => false,
    preserveData: false,
    timeoutMs: 0,
  })
}

async function loadByOrder() {
  if (!form.orderId) {
    trackingResource.reset()
    return
  }

  const result = await trackingResource.load(() => logisticsApi.logisticsForOrder(form.orderId), {
    isEmpty: (value) => value === null,
    preserveData: true,
  })
  if (result) {
    trackingNo.value = result.trackingNo
  }
}

async function loadByTrackingNo() {
  if (!trackingNo.value.trim()) {
    return
  }

  const result = await trackingResource.load(
    () => logisticsApi.logisticsByTrackingNo(trackingNo.value.trim()),
    {
      isEmpty: (value) => value === null,
      preserveData: true,
    },
  )
  if (result) {
    form.orderId = result.orderId
  }
}

async function submitAddressParse() {
  if (!addressText.value.trim() || addressParsePending.value) {
    return
  }

  addressParsePending.value = true
  addressParseError.value = ''
  try {
    const parsed = await logisticsApi.parseAddress({ text: addressText.value.trim() })
    form.province = parsed.province
    form.city = parsed.city
    form.district = parsed.district
    form.detail = parsed.detail
    notify.success(t('logistics.addressParsed'), { key: 'logistics:address:parsed' })
  } catch {
    addressParseError.value = t('logistics.addressParseFailed')
  } finally {
    addressParsePending.value = false
  }
}

async function submitQuote() {
  if (quotePending.value) {
    return
  }

  quotePending.value = true
  quoteError.value = ''
  try {
    quote.value = await logisticsApi.quoteFreight({
      carrier: form.carrier,
      province: form.province || undefined,
      weightKg: form.weightKg,
      itemCount: form.itemCount,
    })
  } catch {
    quoteError.value = t('logistics.quoteFailed')
  } finally {
    quotePending.value = false
  }
}

async function submitShipment() {
  if (!form.orderId || shipmentPending.value) {
    return
  }

  shipmentPending.value = true
  shipmentError.value = ''
  try {
    const result = await logisticsApi.createShipment({
      orderId: form.orderId,
      carrier: form.carrier,
      recipientPhone: form.recipientPhone || undefined,
      province: form.province || undefined,
      city: form.city || undefined,
      district: form.district || undefined,
      detail: form.detail || undefined,
      addressText: addressText.value || undefined,
      weightKg: form.weightKg,
      itemCount: form.itemCount,
    })
    await setTracking(result)
    notify.success(t('logistics.shipmentCreated'), {
      key: `logistics:${result.trackingNo}:created`,
    })
  } catch {
    shipmentError.value = t('logistics.createFailed')
  } finally {
    shipmentPending.value = false
  }
}

async function submitWebhook() {
  const activeTrackingNo = currentTrackingNo.value
  if (!activeTrackingNo || webhookPending.value) {
    return
  }

  webhookPending.value = true
  webhookError.value = ''
  try {
    const result = await logisticsApi.pushWebhook({
      carrier: form.carrier,
      trackingNo: activeTrackingNo,
      eventId: webhook.eventId || `${activeTrackingNo}-${Date.now()}`,
      event: webhook.event,
      location: webhook.location || undefined,
      remark: webhook.remark || undefined,
      signature: webhook.signature,
    })
    webhook.eventId = ''
    await setTracking(result)
    notify.success(t('logistics.webhookPushed'), {
      key: `logistics:${activeTrackingNo}:updated`,
    })
  } catch {
    webhookError.value = t('logistics.webhookFailed')
  } finally {
    webhookPending.value = false
  }
}

onMounted(() => {
  void loadByOrder()
})
</script>

<template>
  <div class="route-view logistics-view">
    <PageHeader :title="$t('nav.logistics')">
      <template #actions>
        <el-button
          :icon="RefreshRight"
          :loading="trackingResource.status.value === 'updating'"
          :disabled="!form.orderId"
          @click="loadByOrder"
        >
          {{ $t('common.refresh') }}
        </el-button>
      </template>
    </PageHeader>

    <section class="logistics-task lookup-task" :aria-label="$t('common.tracking')">
      <h2>
        <el-icon aria-hidden="true"><Search /></el-icon>
        {{ $t('common.tracking') }}
      </h2>
      <div class="lookup-grid">
        <form class="task-form task-form--inline" @submit.prevent="loadByOrder">
          <el-input-number
            v-model="form.orderId"
            :min="1"
            controls-position="right"
            :aria-label="$t('logistics.orderId')"
            :placeholder="$t('logistics.orderId')"
          />
          <el-button
            type="primary"
            native-type="submit"
            :disabled="!form.orderId"
            :loading="trackingResource.status.value === 'loading'"
          >
            {{ $t('common.search') }}
          </el-button>
        </form>
        <form class="task-form task-form--inline" @submit.prevent="loadByTrackingNo">
          <el-input
            v-model="trackingNo"
            :aria-label="$t('logistics.trackingNo')"
            :placeholder="$t('logistics.trackingNoPlaceholder')"
          />
          <el-button
            type="primary"
            native-type="submit"
            :disabled="!trackingNo.trim()"
            :loading="trackingResource.status.value === 'loading'"
          >
            {{ $t('common.search') }}
          </el-button>
        </form>
      </div>
    </section>

    <section class="logistics-task address-task" :aria-label="$t('common.parseAddress')">
      <h2>{{ $t('common.parseAddress') }}</h2>
      <form class="task-form" @submit.prevent="submitAddressParse">
        <el-input
          v-model="addressText"
          type="textarea"
          :rows="2"
          :aria-label="$t('logistics.fullAddress')"
          :placeholder="$t('logistics.fullAddress')"
        />
        <el-button
          plain
          native-type="submit"
          :loading="addressParsePending"
          :disabled="addressParsePending || !addressText.trim()"
        >
          {{ $t('common.parseAddress') }}
        </el-button>
        <p v-if="addressParseError" class="task-error" role="alert">
          {{ addressParseError }}
        </p>
      </form>
      <div class="address-grid">
        <el-input v-model="form.province" :placeholder="$t('logistics.province')" />
        <el-input v-model="form.city" :placeholder="$t('logistics.city')" />
        <el-input v-model="form.district" :placeholder="$t('logistics.district')" />
        <el-input v-model="form.detail" :placeholder="$t('logistics.detailAddress')" />
      </div>
    </section>

    <section class="logistics-task quote-task" :aria-label="$t('logistics.quoteFreight')">
      <h2>{{ $t('logistics.quoteFreight') }}</h2>
      <form class="task-form quote-form" @submit.prevent="submitQuote">
        <el-segmented
          v-model="form.carrier"
          :options="[
            { label: safeCarrier('SF'), value: 'SF' },
            { label: safeCarrier('ZTO'), value: 'ZTO' },
            { label: safeCarrier('YTO'), value: 'YTO' },
          ]"
          :aria-label="$t('logistics.carrier')"
        />
        <el-input-number
          v-model="form.weightKg"
          :min="0.01"
          :step="0.01"
          :precision="2"
          controls-position="right"
          :aria-label="$t('logistics.weight')"
          :placeholder="$t('logistics.weight')"
        />
        <el-input-number
          v-model="form.itemCount"
          :min="1"
          controls-position="right"
          :aria-label="$t('logistics.itemCount')"
          :placeholder="$t('logistics.itemCount')"
        />
        <el-button plain native-type="submit" :loading="quotePending" :disabled="quotePending">
          {{ $t('common.quote') }}
        </el-button>
        <p v-if="quoteError" class="task-error" role="alert">{{ quoteError }}</p>
      </form>
      <dl v-if="quote" class="logistics-summary">
        <div>
          <dt>{{ $t('logistics.carrier') }}</dt>
          <dd>{{ safeCarrier(quote.carrier) }}</dd>
        </div>
        <div>
          <dt>{{ $t('logistics.freight') }}</dt>
          <dd>{{ money(quote.amount) }}</dd>
        </div>
        <div>
          <dt>{{ $t('logistics.weight') }}</dt>
          <dd>{{ quote.weightKg }} kg</dd>
        </div>
        <div>
          <dt>ETA</dt>
          <dd>{{ quote.etaHours }} h</dd>
        </div>
      </dl>
    </section>

    <section class="logistics-task shipment-task" :aria-label="$t('logistics.createShipment')">
      <h2>
        <el-icon aria-hidden="true"><Van /></el-icon>
        {{ $t('logistics.createShipment') }}
      </h2>
      <form class="task-form shipment-form" @submit.prevent="submitShipment">
        <el-input
          v-model="form.recipientPhone"
          autocomplete="off"
          inputmode="tel"
          :aria-label="$t('logistics.recipientPhone')"
          :placeholder="$t('logistics.recipientPhone')"
        />
        <p v-if="shipmentError" class="task-error" role="alert">{{ shipmentError }}</p>
        <el-button
          type="primary"
          native-type="submit"
          :loading="shipmentPending"
          :disabled="shipmentPending || !form.orderId"
        >
          {{ $t('common.createShipment') }}
        </el-button>
      </form>
    </section>

    <AsyncStateView
      :status="trackingResource.status.value"
      :error="trackingResource.error.value"
      :empty-title="$t('logistics.noTracking')"
      @retry="loadByOrder"
    >
      <template #idle>
        <section class="tracking-empty" role="status">
          <el-icon aria-hidden="true"><Van /></el-icon>
          <span>{{ $t('logistics.noTracking') }}</span>
        </section>
      </template>

      <template #error>
        <section class="tracking-empty" role="alert">
          <span>{{ $t('logistics.loadFailed') }}</span>
          <el-button :icon="RefreshRight" @click="loadByOrder">
            {{ $t('common.retry') }}
          </el-button>
        </section>
      </template>

      <DataTableShell v-if="tracking" :aria-label="$t('common.tracking')">
        <section class="logistics-task tracking-task">
          <header class="tracking-heading">
            <div>
              <h2>{{ tracking.trackingNo }}</h2>
              <p>{{ safeCarrier(tracking.carrier) }}</p>
            </div>
            <strong>{{ money(tracking.freightAmount) }}</strong>
          </header>
          <p class="tracking-address">
            {{ [tracking.province, tracking.city, tracking.district].filter(Boolean).join(' · ') }}
          </p>
          <OrderStatusTimeline
            :current-status="tracking.status"
            :timestamps="{
              created: tracking.createTime,
              shipped: tracking.pickedUpAt,
              delivered: tracking.signedAt,
            }"
            :logistics-events="tracking.events"
          />
        </section>
      </DataTableShell>
    </AsyncStateView>

    <section class="logistics-task webhook-task" :aria-label="$t('common.pushWebhook')">
      <h2>{{ $t('common.pushWebhook') }}</h2>
      <form class="task-form webhook-form" @submit.prevent="submitWebhook">
        <el-segmented
          v-model="webhook.event"
          :options="[
            { label: $t('logistics.eventPickup'), value: 'PICKUP' },
            { label: $t('logistics.eventTransit'), value: 'TRANSIT' },
            { label: $t('logistics.eventDispatch'), value: 'DISPATCH' },
            { label: $t('logistics.eventSign'), value: 'SIGN' },
          ]"
          :aria-label="$t('logistics.event')"
        />
        <el-input v-model="webhook.eventId" :placeholder="$t('logistics.eventId')" />
        <el-input v-model="webhook.location" :placeholder="$t('logistics.location')" />
        <el-input v-model="webhook.remark" :placeholder="$t('logistics.remark')" />
        <el-input
          v-model="webhook.signature"
          :placeholder="$t('logistics.signature')"
          show-password
        />
        <p v-if="webhookError" class="task-error" role="alert">{{ webhookError }}</p>
        <el-button
          type="warning"
          native-type="submit"
          :loading="webhookPending"
          :disabled="webhookPending || !currentTrackingNo"
        >
          {{ $t('common.pushWebhook') }}
        </el-button>
      </form>
    </section>
  </div>
</template>

<style scoped>
.logistics-view {
  display: grid;
  gap: 18px;
}

.logistics-task {
  border-top: 1px solid var(--el-border-color-lighter);
  display: grid;
  gap: 14px;
  padding-top: 18px;
}

.logistics-task h2 {
  align-items: center;
  display: flex;
  font-size: 1rem;
  gap: 8px;
  margin: 0;
}

.lookup-grid,
.address-grid,
.task-form,
.logistics-summary {
  display: grid;
  gap: 12px;
}

.lookup-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.task-form {
  align-items: start;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
}

.task-form--inline {
  grid-template-columns: minmax(180px, 1fr) auto;
}

.address-grid {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.task-form > .el-button {
  justify-self: start;
  min-height: 40px;
}

.task-error {
  color: var(--el-color-danger);
  font-size: 13px;
  grid-column: 1 / -1;
  margin: 0;
}

.logistics-summary {
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  margin: 0;
}

.logistics-summary div {
  border-left: 2px solid var(--el-border-color);
  display: grid;
  gap: 4px;
  padding-left: 10px;
}

.logistics-summary dt,
.tracking-heading p,
.tracking-address {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.logistics-summary dd,
.tracking-heading p,
.tracking-address {
  margin: 0;
}

.tracking-heading {
  align-items: start;
  display: flex;
  gap: 12px;
  justify-content: space-between;
}

.tracking-heading p {
  margin-top: 4px;
}

.tracking-empty {
  align-items: center;
  color: var(--el-text-color-secondary);
  display: flex;
  gap: 12px;
  min-height: 80px;
}

@media (max-width: 820px) {
  .lookup-grid,
  .address-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .lookup-grid,
  .address-grid,
  .task-form,
  .task-form--inline {
    grid-template-columns: 1fr;
  }

  .task-form > *,
  .task-form > .el-button {
    justify-self: stretch;
    width: 100%;
  }

  .task-form > .el-button {
    min-height: 44px;
  }
}
</style>
