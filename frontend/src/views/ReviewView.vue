<script setup lang="ts">
import { Close, RefreshRight, Star, Upload } from '@element-plus/icons-vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { uploadImage } from '@/api/catalog'
import { orderReviews, reviewOrder } from '@/api/orders'
import ProductImage from '@/components/ProductImage.vue'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import DataTableShell from '@/components/ui/DataTableShell.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import { useAsyncState } from '@/composables/useAsyncState'
import { useNotify } from '@/composables/useNotify'
import type { OrderReview } from '@/types'
import { dateTime } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const notify = useNotify()
const reviewResource = useAsyncState<OrderReview[]>({ timeoutMs: 20000 })

const uploadPending = ref(false)
const uploadProgress = ref(0)
const uploadError = ref('')
const submitPending = ref(false)
const submitError = ref('')

const form = reactive({
  skuId: undefined as number | undefined,
  rating: 5,
  content: '',
  imageUrls: [] as string[],
  anonymous: false,
})

const orderId = computed(() => Number(route.params.id))
const reviews = computed(() => reviewResource.data.value ?? [])

async function loadReviews() {
  await reviewResource.load(() => orderReviews(orderId.value), {
    isEmpty: (items) => items.length === 0,
    preserveData: true,
  })
}

async function uploadReviewImage(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file || uploadPending.value) {
    return
  }

  uploadPending.value = true
  uploadProgress.value = 15
  uploadError.value = ''
  try {
    const uploaded = await uploadImage(file, 'product')
    uploadProgress.value = 100
    form.imageUrls.push(uploaded.path)
    notify.success(t('common.imageUploaded'), { key: `review-upload:${uploaded.path}` })
  } catch {
    uploadError.value = t('common.unableToUploadImage')
  } finally {
    uploadPending.value = false
    uploadProgress.value = 0
    input.value = ''
  }
}

function removeImage(index: number) {
  if (!submitPending.value) {
    form.imageUrls.splice(index, 1)
  }
}

async function submitReview() {
  if (submitPending.value || uploadPending.value) {
    return
  }

  submitPending.value = true
  submitError.value = ''
  try {
    await reviewOrder(orderId.value, {
      skuId: form.skuId,
      rating: form.rating,
      content: form.content,
      imageUrls: form.imageUrls,
      anonymous: form.anonymous,
    })
    notify.success(t('common.reviewSubmitted'), { key: `review:${orderId.value}:submitted` })
    await loadReviews()
    await router.push('/orders')
  } catch {
    submitError.value = t('common.unableToReview')
  } finally {
    submitPending.value = false
  }
}

onMounted(() => {
  void loadReviews()
})
</script>

<template>
  <div class="route-view review-view">
    <PageHeader :title="$t('common.review')">
      <template #actions>
        <el-button @click="$router.push('/orders')">
          {{ $t('nav.orders') }}
        </el-button>
      </template>
    </PageHeader>

    <section class="review-task" :aria-label="$t('common.submitReview')">
      <form class="review-form" @submit.prevent="submitReview">
        <div class="review-field">
          <span id="review-rating-label">{{ $t('common.review') }}</span>
          <el-rate
            id="review-rating"
            v-model="form.rating"
            :max="5"
            size="large"
            aria-labelledby="review-rating-label"
          />
        </div>

        <div class="review-field">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="5"
            :aria-label="$t('common.reviewContent')"
            :placeholder="$t('common.reviewContent')"
          />
        </div>

        <el-switch
          v-model="form.anonymous"
          :aria-label="$t('common.anonymous')"
          :active-text="$t('common.anonymous')"
        />

        <div class="review-images">
          <div v-for="(image, index) in form.imageUrls" :key="image" class="review-image">
            <ProductImage :src="image" :alt="$t('common.review')" />
            <el-tooltip :content="$t('common.delete')">
              <el-button
                class="review-image__remove"
                circle
                :icon="Close"
                :aria-label="$t('common.delete')"
                :disabled="submitPending"
                @click="removeImage(index)"
              />
            </el-tooltip>
          </div>

          <label class="upload-chip" for="review-image-upload">
            <el-icon aria-hidden="true"><Upload /></el-icon>
            <span>{{ $t('common.upload') }}</span>
            <input
              id="review-image-upload"
              type="file"
              accept="image/*"
              :disabled="uploadPending || submitPending"
              @change="uploadReviewImage"
            />
          </label>
        </div>

        <div v-if="uploadPending" class="upload-progress" role="status">
          <el-progress
            :percentage="uploadProgress"
            :indeterminate="true"
            :duration="2"
            :show-text="false"
          />
          <span>{{ $t('common.loading') }}</span>
        </div>
        <p v-if="uploadError" class="task-error" role="alert">{{ uploadError }}</p>
        <p v-if="submitError" class="task-error" role="alert">{{ submitError }}</p>

        <el-button
          type="primary"
          native-type="submit"
          :loading="submitPending"
          :disabled="submitPending || uploadPending"
        >
          {{ $t('common.submitReview') }}
        </el-button>
      </form>
    </section>

    <section class="review-history" :aria-label="$t('common.review')">
      <AsyncStateView
        :status="reviewResource.status.value"
        :error="reviewResource.error.value"
        :empty-title="$t('common.noData')"
        @retry="loadReviews"
      >
        <template #error>
          <div class="history-error" role="alert">
            <span>{{ $t('common.unableToLoadOrders') }}</span>
            <el-button :icon="RefreshRight" @click="loadReviews">
              {{ $t('common.retry') }}
            </el-button>
          </div>
        </template>

        <template #empty>
          <div class="history-empty" role="status">
            <el-icon aria-hidden="true"><Star /></el-icon>
            <span>{{ $t('common.noData') }}</span>
          </div>
        </template>

        <DataTableShell :aria-label="$t('common.review')">
          <div class="review-list">
            <article v-for="review in reviews" :key="review.id" class="review-item">
              <header class="review-title">
                <el-rate :model-value="review.rating" disabled />
                <time :datetime="review.createTime">{{ dateTime(review.createTime) }}</time>
              </header>
              <p v-if="review.content">{{ review.content }}</p>
              <div v-if="review.imageUrls.length" class="review-thumbs">
                <ProductImage
                  v-for="image in review.imageUrls"
                  :key="image"
                  :src="image"
                  :alt="$t('common.review')"
                />
              </div>
            </article>
          </div>
        </DataTableShell>
      </AsyncStateView>
    </section>
  </div>
</template>

<style scoped>
.review-view {
  display: grid;
  gap: 18px;
}

.review-task,
.review-history {
  border-top: 1px solid var(--el-border-color-lighter);
  padding-top: 18px;
}

.review-form {
  align-content: start;
  display: grid;
  gap: 14px;
  max-width: 760px;
}

.review-field {
  display: grid;
  gap: 8px;
}

.review-field > span {
  color: var(--el-text-color-regular);
  font-size: 14px;
}

.review-images,
.review-thumbs {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.review-image {
  height: 80px;
  position: relative;
  width: 80px;
}

.review-image :deep(.product-image),
.review-thumbs :deep(.product-image) {
  height: 80px;
  width: 80px;
}

.review-image__remove {
  position: absolute;
  right: 4px;
  top: 4px;
}

.upload-chip {
  align-content: center;
  border: 1px dashed var(--el-border-color);
  border-radius: var(--radius-surface);
  color: var(--el-text-color-secondary);
  cursor: pointer;
  display: grid;
  font-size: 12px;
  gap: 4px;
  height: 80px;
  justify-items: center;
  width: 80px;
}

.upload-chip:focus-within {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}

.upload-chip input {
  height: 1px;
  opacity: 0;
  position: absolute;
  width: 1px;
}

.upload-progress {
  align-items: center;
  display: grid;
  gap: 10px;
  grid-template-columns: minmax(160px, 320px) auto;
}

.upload-progress span {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.task-error {
  color: var(--el-color-danger);
  font-size: 13px;
  margin: 0;
}

.review-form > .el-button {
  justify-self: start;
  min-height: 40px;
}

.review-list {
  display: grid;
}

.review-item {
  border-bottom: 1px solid var(--el-border-color-lighter);
  display: grid;
  gap: 10px;
  padding: 14px 0;
}

.review-item:last-child {
  border-bottom: 0;
}

.review-item p {
  margin: 0;
  overflow-wrap: anywhere;
}

.review-title {
  align-items: center;
  color: var(--el-text-color-secondary);
  display: flex;
  gap: 12px;
  justify-content: space-between;
}

.history-error,
.history-empty {
  align-items: center;
  color: var(--el-text-color-secondary);
  display: flex;
  gap: 12px;
  min-height: 80px;
}

@media (max-width: 560px) {
  .review-form > .el-button {
    justify-self: stretch;
    min-height: 44px;
    width: 100%;
  }

  .upload-progress {
    grid-template-columns: 1fr;
  }
}
</style>
