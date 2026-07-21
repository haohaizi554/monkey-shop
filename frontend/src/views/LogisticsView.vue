<script setup lang="ts">
import { Location, RefreshRight, Search, Van } from '@element-plus/icons-vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { isPositiveApiId, normalizeApiId } from '@/api/ids'
import * as logisticsApi from '@/api/logistics'
import MascotState from '@/components/mascot/MascotState.vue'
import OrderStatusTimeline from '@/components/order/OrderStatusTimeline.vue'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import DataTableShell from '@/components/ui/DataTableShell.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import { useAsyncState } from '@/composables/useAsyncState'
import { useNotify } from '@/composables/useNotify'
import type { FreightQuoteResponse, LogisticsCarrier, LogisticsTracking } from '@/types'
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

const form = reactive({
  orderId: normalizeApiId(route.params.orderId || route.query.orderId),
  carrier: 'SF' as LogisticsCarrier,
  province: '',
  city: '',
  district: '',
  detail: '',
  weightKg: 1,
  itemCount: 1,
})

const tracking = computed(() => trackingResource.data.value)
const isChinese = computed(() => locale.value === 'zh')
const lookupPending = computed(() =>
  ['loading', 'updating'].includes(trackingResource.status.value),
)

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
  return label
    ? localized(label[0], label[1])
    : localized('Delivery carrier', '\u7269\u6d41\u627f\u8fd0\u5546')
}

async function loadByOrder() {
  if (lookupPending.value) return
  if (!isPositiveApiId(form.orderId)) {
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
  const normalizedTrackingNo = trackingNo.value.trim()
  if (lookupPending.value || !normalizedTrackingNo) return

  const result = await trackingResource.load(
    () => logisticsApi.logisticsByTrackingNo(normalizedTrackingNo),
    {
      isEmpty: (value) => value === null,
      preserveData: true,
    },
  )
  if (result) {
    form.orderId = normalizeApiId(result.orderId)
  }
}

async function submitAddressParse() {
  const input = addressText.value.trim()
  if (!input || addressParsePending.value) return

  addressParsePending.value = true
  addressParseError.value = ''
  try {
    const parsed = await logisticsApi.parseAddress({ text: input })
    if (addressText.value.trim() !== input) return
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
  if (quotePending.value) return

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

onMounted(() => {
  void loadByOrder()
})
</script>

<template>
  <div class="route-view logistics-view">
    <PageHeader :title="$t('nav.logistics')" :description="$t('logistics.hint')">
      <template #actions>
        <el-button
          :icon="RefreshRight"
          :loading="trackingResource.status.value === 'updating'"
          :disabled="lookupPending || !form.orderId"
          @click="loadByOrder"
        >
          {{ $t('common.refresh') }}
        </el-button>
      </template>
    </PageHeader>

    <section class="lookup-task" :aria-label="$t('common.tracking')">
      <div class="section-heading">
        <span class="section-heading__icon" aria-hidden="true">
          <el-icon><Search /></el-icon>
        </span>
        <div>
          <h2>{{ $t('common.tracking') }}</h2>
          <p>{{ $t('logistics.lookupHint') }}</p>
        </div>
      </div>

      <div class="lookup-grid">
        <form class="lookup-form" @submit.prevent="loadByOrder">
          <el-input
            v-model="form.orderId"
            inputmode="numeric"
            :disabled="lookupPending"
            :aria-label="$t('logistics.orderId')"
            :placeholder="$t('logistics.orderId')"
          />
          <el-button
            type="primary"
            native-type="submit"
            :icon="Search"
            :disabled="lookupPending || !form.orderId"
            :loading="lookupPending"
          >
            {{ $t('common.search') }}
          </el-button>
        </form>

        <form class="lookup-form" @submit.prevent="loadByTrackingNo">
          <el-input
            v-model="trackingNo"
            :disabled="lookupPending"
            :aria-label="$t('logistics.trackingNo')"
            :placeholder="$t('logistics.trackingNoPlaceholder')"
          />
          <el-button
            type="primary"
            native-type="submit"
            :icon="Search"
            :disabled="lookupPending || !trackingNo.trim()"
            :loading="lookupPending"
          >
            {{ $t('common.search') }}
          </el-button>
        </form>
      </div>
    </section>

    <section class="estimate-workspace" :aria-label="$t('logistics.addressTools')">
      <div class="address-task">
        <div class="section-heading section-heading--compact">
          <span class="section-heading__icon" aria-hidden="true">
            <el-icon><Location /></el-icon>
          </span>
          <div>
            <h2>{{ $t('common.parseAddress') }}</h2>
            <p>{{ $t('logistics.addressTools') }}</p>
          </div>
        </div>

        <form class="address-form" @submit.prevent="submitAddressParse">
          <el-input
            v-model="addressText"
            type="textarea"
            :rows="3"
            :disabled="addressParsePending"
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
          <el-input
            v-model="form.province"
            :disabled="addressParsePending"
            :aria-label="$t('logistics.province')"
            :placeholder="$t('logistics.province')"
          />
          <el-input
            v-model="form.city"
            :disabled="addressParsePending"
            :aria-label="$t('logistics.city')"
            :placeholder="$t('logistics.city')"
          />
          <el-input
            v-model="form.district"
            :disabled="addressParsePending"
            :aria-label="$t('logistics.district')"
            :placeholder="$t('logistics.district')"
          />
          <el-input
            v-model="form.detail"
            :disabled="addressParsePending"
            :aria-label="$t('logistics.detailAddress')"
            :placeholder="$t('logistics.detailAddress')"
          />
        </div>
      </div>

      <div class="quote-task">
        <div class="section-heading section-heading--compact">
          <span class="section-heading__icon" aria-hidden="true">
            <el-icon><Van /></el-icon>
          </span>
          <div>
            <h2>{{ $t('logistics.quoteFreight') }}</h2>
            <p>{{ $t('logistics.addressTools') }}</p>
          </div>
        </div>

        <form class="quote-form" @submit.prevent="submitQuote">
          <el-segmented
            v-model="form.carrier"
            :options="[
              { label: safeCarrier('SF'), value: 'SF' },
              { label: safeCarrier('ZTO'), value: 'ZTO' },
              { label: safeCarrier('YTO'), value: 'YTO' },
            ]"
            :aria-label="$t('logistics.carrier')"
            :disabled="quotePending"
          />
          <div class="quote-measures">
            <el-input-number
              v-model="form.weightKg"
              :min="0.01"
              :step="0.01"
              :precision="2"
              controls-position="right"
              :disabled="quotePending"
              :aria-label="$t('logistics.weight')"
              :placeholder="$t('logistics.weight')"
            />
            <el-input-number
              v-model="form.itemCount"
              :min="1"
              controls-position="right"
              :disabled="quotePending"
              :aria-label="$t('logistics.itemCount')"
              :placeholder="$t('logistics.itemCount')"
            />
          </div>
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
      </div>
    </section>

    <section class="tracking-result" :aria-label="$t('logistics.trackingDetails')">
      <AsyncStateView
        :status="trackingResource.status.value"
        :error="trackingResource.error.value"
        :empty-title="$t('logistics.noTracking')"
        @retry="loadByOrder"
      >
        <template #idle>
          <div class="tracking-empty" role="status">
            <MascotState pose="package" size="md" :alt="$t('logistics.emptyMascotAlt')" />
            <div>
              <h2>{{ $t('logistics.noTracking') }}</h2>
              <p>{{ $t('logistics.emptyHint') }}</p>
            </div>
          </div>
        </template>

        <template #empty>
          <div class="tracking-empty" role="status">
            <MascotState pose="package" size="md" :alt="$t('logistics.emptyMascotAlt')" />
            <div>
              <h2>{{ $t('logistics.noTracking') }}</h2>
              <p>{{ $t('logistics.emptyHint') }}</p>
            </div>
          </div>
        </template>

        <template #error>
          <div class="tracking-load-error" role="alert">
            <span>{{ $t('logistics.loadFailed') }}</span>
            <el-button :icon="RefreshRight" @click="loadByOrder">
              {{ $t('common.retry') }}
            </el-button>
          </div>
        </template>

        <DataTableShell v-if="tracking" :aria-label="$t('common.tracking')">
          <article class="tracking-task">
            <header class="tracking-heading">
              <div>
                <span>{{ safeCarrier(tracking.carrier) }}</span>
                <h2>{{ tracking.trackingNo }}</h2>
              </div>
              <strong>{{ money(tracking.freightAmount) }}</strong>
            </header>
            <p class="tracking-address">
              <el-icon aria-hidden="true"><Location /></el-icon>
              {{
                [tracking.province, tracking.city, tracking.district].filter(Boolean).join(' / ')
              }}
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
          </article>
        </DataTableShell>
      </AsyncStateView>
    </section>
  </div>
</template>

<style scoped>
.logistics-view {
  display: grid;
  gap: var(--space-5);
}

.lookup-task,
.estimate-workspace,
.tracking-result {
  border-top: 1px solid var(--color-line);
  padding-top: var(--space-5);
}

.lookup-task,
.address-task,
.quote-task,
.tracking-task {
  display: grid;
  gap: var(--space-4);
}

.section-heading {
  display: flex;
  align-items: flex-start;
  gap: var(--space-3);
}

.section-heading__icon {
  display: inline-grid;
  flex: 0 0 36px;
  width: 36px;
  height: 36px;
  place-items: center;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-control);
  color: var(--color-brand);
}

.section-heading h2,
.section-heading p,
.tracking-empty h2,
.tracking-empty p,
.tracking-heading h2,
.tracking-address {
  margin: 0;
}

.section-heading h2 {
  font-size: var(--text-base);
}

.section-heading p,
.tracking-empty p,
.tracking-heading span,
.tracking-address {
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}

.section-heading p {
  margin-top: var(--space-1);
}

.lookup-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-4);
}

.lookup-form {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--space-2);
}

.lookup-form :deep(.el-input-number) {
  width: 100%;
}

.estimate-workspace {
  display: grid;
  grid-template-columns: minmax(0, 1.25fr) minmax(340px, 0.75fr);
  gap: var(--space-6);
}

.quote-task {
  padding-left: var(--space-6);
  border-left: 1px solid var(--color-line);
}

.address-form,
.quote-form,
.address-grid,
.quote-measures,
.logistics-summary {
  display: grid;
  gap: var(--space-3);
}

.address-form {
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: start;
}

.address-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.quote-measures {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.quote-measures :deep(.el-input-number) {
  width: 100%;
}

.address-form > .el-button,
.quote-form > .el-button {
  min-height: 40px;
  justify-self: start;
}

.task-error {
  grid-column: 1 / -1;
  margin: 0;
  color: var(--color-danger);
  font-size: var(--text-sm);
}

.logistics-summary {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  margin: 0;
}

.logistics-summary div {
  min-width: 0;
  padding-left: var(--space-3);
  border-left: 2px solid var(--color-line);
}

.logistics-summary dt {
  color: var(--color-text-muted);
  font-size: var(--text-xs);
}

.logistics-summary dd {
  margin: var(--space-1) 0 0;
  overflow-wrap: anywhere;
  font-weight: 700;
}

.tracking-result {
  min-height: 300px;
}

.tracking-empty {
  display: grid;
  grid-template-columns: minmax(128px, 192px) minmax(0, 340px);
  align-items: center;
  justify-content: center;
  gap: var(--space-5);
  min-height: 300px;
}

.tracking-empty > div {
  display: grid;
  gap: var(--space-2);
}

.tracking-empty h2 {
  font-size: var(--text-lg);
}

.tracking-load-error {
  display: grid;
  justify-items: start;
  gap: var(--space-3);
  padding: var(--space-5) 0;
}

.tracking-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
}

.tracking-heading h2 {
  margin-top: var(--space-1);
  overflow-wrap: anywhere;
  font-size: var(--text-xl);
}

.tracking-heading strong {
  font-size: var(--text-lg);
}

.tracking-address {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

@media (max-width: 920px) {
  .estimate-workspace {
    grid-template-columns: 1fr;
  }

  .quote-task {
    padding-top: var(--space-5);
    padding-left: 0;
    border-top: 1px solid var(--color-line);
    border-left: 0;
  }
}

@media (max-width: 680px) {
  .lookup-grid,
  .address-grid,
  .quote-measures,
  .logistics-summary {
    grid-template-columns: 1fr;
  }

  .lookup-form,
  .address-form {
    grid-template-columns: 1fr;
  }

  .lookup-form > *,
  .address-form > *,
  .address-form > .el-button,
  .quote-form > .el-button {
    width: 100%;
  }

  .lookup-form > .el-button,
  .address-form > .el-button,
  .quote-form > .el-button {
    min-height: 44px;
  }

  .tracking-empty {
    grid-template-columns: 1fr;
    justify-items: center;
    text-align: center;
  }
}
</style>
