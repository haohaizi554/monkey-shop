<script setup lang="ts">
import { RefreshRight } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import * as logisticsApi from '@/api/logistics'
import AppShell from '@/components/AppShell.vue'
import type {
  FreightQuoteResponse,
  LogisticsCarrier,
  LogisticsTracking,
  TrackingEvent,
} from '@/types'
import { dateTime, money } from '@/utils/format'

const route = useRoute()
const busy = ref(false)
const tracking = ref<LogisticsTracking | null>(null)
const quote = ref<FreightQuoteResponse | null>(null)
const trackingNo = ref('')
const addressText = ref('Zhejiang Hangzou Xihu Wenyi Road 100')

const form = reactive({
  orderId: Number(route.params.orderId ?? route.query.orderId ?? 0),
  carrier: 'SF' as LogisticsCarrier,
  recipientPhone: '13800138000',
  province: 'Zhejiang',
  city: 'Hangzhou',
  district: 'Xihu',
  detail: 'Wenyi Road 100',
  weightKg: 1.2,
  itemCount: 1,
})

const webhook = reactive({
  event: 'PICKUP' as TrackingEvent,
  eventId: '',
  location: 'Hangzhou hub',
  remark: 'Sandbox logistics push',
})

const currentTrackingNo = computed(() => tracking.value?.trackingNo || trackingNo.value)

async function loadByOrder() {
  if (!form.orderId) {
    return
  }
  busy.value = true
  try {
    tracking.value = await logisticsApi.logisticsForOrder(form.orderId)
    trackingNo.value = tracking.value.trackingNo
  } catch {
    tracking.value = null
  } finally {
    busy.value = false
  }
}

async function loadByTrackingNo() {
  if (!trackingNo.value) {
    return
  }
  busy.value = true
  try {
    tracking.value = await logisticsApi.logisticsByTrackingNo(trackingNo.value)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Unable to load tracking')
  } finally {
    busy.value = false
  }
}

async function submitQuote() {
  busy.value = true
  try {
    quote.value = await logisticsApi.quoteFreight({
      carrier: form.carrier,
      province: form.province,
      weightKg: form.weightKg,
      itemCount: form.itemCount,
    })
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Unable to quote freight')
  } finally {
    busy.value = false
  }
}

async function submitAddressParse() {
  busy.value = true
  try {
    const parsed = await logisticsApi.parseAddress({ text: addressText.value })
    form.province = parsed.province
    form.city = parsed.city
    form.district = parsed.district
    form.detail = parsed.detail
    ElMessage.success('Address parsed')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Unable to parse address')
  } finally {
    busy.value = false
  }
}

async function submitShipment() {
  if (!form.orderId) {
    return
  }
  busy.value = true
  try {
    tracking.value = await logisticsApi.createShipment({
      orderId: form.orderId,
      carrier: form.carrier,
      recipientPhone: form.recipientPhone,
      province: form.province,
      city: form.city,
      district: form.district,
      detail: form.detail,
      addressText: addressText.value || undefined,
      weightKg: form.weightKg,
      itemCount: form.itemCount,
    })
    trackingNo.value = tracking.value.trackingNo
    ElMessage.success('Shipment created')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Unable to create shipment')
  } finally {
    busy.value = false
  }
}

async function submitWebhook() {
  if (!currentTrackingNo.value) {
    return
  }
  busy.value = true
  try {
    tracking.value = await logisticsApi.pushWebhook({
      carrier: form.carrier,
      trackingNo: currentTrackingNo.value,
      eventId: webhook.eventId || `${currentTrackingNo.value}-${webhook.event}-${Date.now()}`,
      event: webhook.event,
      location: webhook.location,
      remark: webhook.remark,
    })
    webhook.eventId = ''
    ElMessage.success('Webhook accepted')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Unable to push webhook')
  } finally {
    busy.value = false
  }
}

onMounted(() => {
  void loadByOrder()
})
</script>

<template>
  <AppShell>
    <section class="page-heading">
      <h1>{{ $t('nav.logistics') }}</h1>
      <el-button :icon="RefreshRight" @click="loadByOrder">
        {{ $t('common.reset') }}
      </el-button>
    </section>

    <section class="logistics-layout">
      <form class="logistics-panel" @submit.prevent="submitShipment">
        <h2>{{ $t('common.logistics') }}</h2>
        <div class="field-grid">
          <el-input-number v-model="form.orderId" :min="1" controls-position="right" />
          <el-segmented
            v-model="form.carrier"
            :options="[
              { label: 'SF', value: 'SF' },
              { label: 'ZTO', value: 'ZTO' },
              { label: 'YTO', value: 'YTO' },
            ]"
          />
        </div>
        <el-input v-model="form.recipientPhone" autocomplete="off" inputmode="tel" />
        <el-input v-model="addressText" type="textarea" :rows="2" />
        <div class="field-grid">
          <el-input v-model="form.province" />
          <el-input v-model="form.city" />
          <el-input v-model="form.district" />
        </div>
        <el-input v-model="form.detail" />
        <div class="field-grid">
          <el-input-number
            v-model="form.weightKg"
            :min="0.01"
            :precision="2"
            controls-position="right"
          />
          <el-input-number v-model="form.itemCount" :min="1" controls-position="right" />
        </div>
        <div class="inline-actions">
          <el-button plain :loading="busy" @click="submitAddressParse">
            {{ $t('common.parseAddress') }}
          </el-button>
          <el-button plain :loading="busy" @click="submitQuote">
            {{ $t('common.quote') }}
          </el-button>
          <el-button type="primary" native-type="submit" :loading="busy">
            {{ $t('common.createShipment') }}
          </el-button>
        </div>
      </form>

      <section class="logistics-panel">
        <h2>{{ $t('common.tracking') }}</h2>
        <form class="tracking-search" @submit.prevent="loadByTrackingNo">
          <el-input v-model="trackingNo" />
          <el-button type="primary" native-type="submit" :loading="busy">
            {{ $t('common.search') }}
          </el-button>
        </form>
        <div v-if="quote" class="metric-grid">
          <span>{{ quote.carrier }} / {{ quote.province }}</span>
          <strong>{{ money(quote.amount) }}</strong>
          <span>{{ quote.weightKg }} kg</span>
          <span>{{ quote.etaHours }} h</span>
        </div>
        <div v-if="tracking" class="tracking-card">
          <div class="tracking-title">
            <strong>{{ tracking.trackingNo }}</strong>
            <el-tag>{{ tracking.status }}</el-tag>
          </div>
          <p>{{ tracking.province }} / {{ tracking.city }} / {{ tracking.district }}</p>
          <p>{{ money(tracking.freightAmount) }} / {{ tracking.etaHours }}h</p>
          <el-timeline>
            <el-timeline-item
              v-for="event in tracking.events"
              :key="event.id"
              :timestamp="dateTime(event.eventTime)"
            >
              {{ event.fromStatus }} -> {{ event.toStatus }} / {{ event.location }}
            </el-timeline-item>
          </el-timeline>
        </div>
        <form class="webhook-form" @submit.prevent="submitWebhook">
          <el-segmented
            v-model="webhook.event"
            :options="[
              { label: 'Pickup', value: 'PICKUP' },
              { label: 'Transit', value: 'TRANSIT' },
              { label: 'Dispatch', value: 'DISPATCH' },
              { label: 'Sign', value: 'SIGN' },
            ]"
          />
          <el-input v-model="webhook.eventId" placeholder="event id" />
          <el-input v-model="webhook.location" />
          <el-input v-model="webhook.remark" />
          <el-button type="warning" native-type="submit" :loading="busy">
            {{ $t('common.pushWebhook') }}
          </el-button>
        </form>
      </section>
    </section>
  </AppShell>
</template>

<style scoped>
.logistics-layout {
  display: grid;
  gap: 16px;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
}

.logistics-panel {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  display: grid;
  gap: 12px;
  padding: 16px;
}

.field-grid,
.metric-grid {
  display: grid;
  gap: 10px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.inline-actions,
.tracking-search {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.tracking-card {
  display: grid;
  gap: 8px;
}

.tracking-title {
  align-items: center;
  display: flex;
  justify-content: space-between;
}

.webhook-form {
  display: grid;
  gap: 10px;
}
</style>
