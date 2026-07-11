<script setup lang="ts">
import { RefreshRight } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import * as logisticsApi from '@/api/logistics'
import type {
  FreightQuoteResponse,
  LogisticsCarrier,
  LogisticsTracking,
  TrackingEvent,
} from '@/types'
import {
  dateTime,
  money,
  trackingEventLabel,
  trackingStatusLabel,
  trackingStatusType,
} from '@/utils/format'

const route = useRoute()
const busy = ref(false)
const tracking = ref<LogisticsTracking | null>(null)
const quote = ref<FreightQuoteResponse | null>(null)
const trackingNo = ref('')
const addressText = ref('浙江省杭州市西湖区文一路 100 号')

const form = reactive({
  orderId: Number(route.params.orderId ?? route.query.orderId ?? 0),
  carrier: 'SF' as LogisticsCarrier,
  recipientPhone: '13800138000',
  province: '浙江',
  city: '杭州',
  district: '西湖',
  detail: '文一路 100 号',
  weightKg: 1.2,
  itemCount: 1,
})

const webhook = reactive({
  event: 'PICKUP' as TrackingEvent,
  eventId: '',
  location: '杭州分拨中心',
  remark: '本地物流轨迹推送',
  signature: '',
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
    ElMessage.error(error instanceof Error ? error.message : '无法加载物流轨迹')
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
    ElMessage.error(error instanceof Error ? error.message : '无法试算运费')
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
    ElMessage.success('地址已解析')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '无法解析地址')
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
    ElMessage.success('物流单已创建')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '无法创建物流单')
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
      signature: webhook.signature,
    })
    webhook.eventId = ''
    ElMessage.success('轨迹已推送')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '无法推送轨迹')
  } finally {
    busy.value = false
  }
}

onMounted(() => {
  void loadByOrder()
})
</script>

<template>
  <div class="route-view">
    <section class="page-heading">
      <h1>{{ $t('nav.logistics') }}</h1>
      <el-button :icon="RefreshRight" @click="loadByOrder"> 刷新 </el-button>
    </section>

    <section class="logistics-layout">
      <form class="logistics-panel" @submit.prevent="submitShipment">
        <h2>创建物流</h2>
        <div class="field-grid">
          <el-input-number
            v-model="form.orderId"
            :min="1"
            controls-position="right"
            placeholder="订单 ID"
          />
          <el-segmented
            v-model="form.carrier"
            :options="[
              { label: 'SF', value: 'SF' },
              { label: 'ZTO', value: 'ZTO' },
              { label: 'YTO', value: 'YTO' },
            ]"
          />
        </div>
        <el-input
          v-model="form.recipientPhone"
          autocomplete="off"
          inputmode="tel"
          placeholder="收件手机号"
        />
        <el-input v-model="addressText" type="textarea" :rows="2" placeholder="完整地址" />
        <div class="field-grid">
          <el-input v-model="form.province" placeholder="省份" />
          <el-input v-model="form.city" placeholder="城市" />
          <el-input v-model="form.district" placeholder="区县" />
        </div>
        <el-input v-model="form.detail" placeholder="详细地址" />
        <div class="field-grid">
          <el-input-number
            v-model="form.weightKg"
            :min="0.01"
            :precision="2"
            controls-position="right"
            placeholder="重量 kg"
          />
          <el-input-number
            v-model="form.itemCount"
            :min="1"
            controls-position="right"
            placeholder="件数"
          />
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
          <el-input v-model="trackingNo" placeholder="物流单号" />
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
            <el-tag :type="trackingStatusType(tracking.status)" disable-transitions>
              {{ trackingStatusLabel(tracking.status) }}
            </el-tag>
          </div>
          <p>{{ tracking.province }} / {{ tracking.city }} / {{ tracking.district }}</p>
          <p>{{ money(tracking.freightAmount) }} / {{ tracking.etaHours }}h</p>
          <el-timeline>
            <el-timeline-item
              v-for="event in tracking.events"
              :key="event.id"
              :timestamp="dateTime(event.eventTime)"
            >
              {{ trackingEventLabel(event.eventType) }}：{{
                trackingStatusLabel(event.fromStatus)
              }}
              → {{ trackingStatusLabel(event.toStatus) }} / {{ event.location || '未知位置' }}
            </el-timeline-item>
          </el-timeline>
        </div>
        <el-empty v-else description="暂无物流轨迹" :image-size="80" />
        <form class="webhook-form" @submit.prevent="submitWebhook">
          <el-segmented
            v-model="webhook.event"
            :options="[
              { label: '揽收', value: 'PICKUP' },
              { label: '运输', value: 'TRANSIT' },
              { label: '派送', value: 'DISPATCH' },
              { label: '签收', value: 'SIGN' },
            ]"
          />
          <el-input v-model="webhook.eventId" placeholder="事件 ID" />
          <el-input v-model="webhook.location" placeholder="位置" />
          <el-input v-model="webhook.remark" placeholder="备注" />
          <el-input v-model="webhook.signature" placeholder="签名" show-password />
          <el-button type="warning" native-type="submit" :loading="busy">
            {{ $t('common.pushWebhook') }}
          </el-button>
        </form>
      </section>
    </section>
  </div>
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
