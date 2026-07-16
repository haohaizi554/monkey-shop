<script setup lang="ts">
import { ArrowLeft, Close, RefreshRight, Star, Upload } from '@element-plus/icons-vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { uploadImage } from '@/api/catalog'
import { orderReviews, reviewOrder } from '@/api/orders'
import MascotState from '@/components/mascot/MascotState.vue'
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
  if (!file || uploadPending.value) return

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
  if (submitPending.value || uploadPending.value) return

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
    <PageHeader :title="$t('common.review')" :description="$t('reviews.hint')">
      <template #actions>
        <el-button :icon="ArrowLeft" @click="$router.push('/orders')">
          {{ $t('nav.orders') }}
        </el-button>
      </template>
    </PageHeader>

    <div class="review-workspace">
      <section class="review-composer" :aria-label="$t('common.submitReview')">
        <header class="section-heading">
          <div>
            <h2>{{ $t('reviews.composeTitle') }}</h2>
            <p>{{ $t('reviews.composeHint') }}</p>
          </div>
          <span>{{ $t('reviews.ratingSummary', { rating: form.rating }) }}</span>
        </header>

        <form class="review-form" @submit.prevent="submitReview">
          <div class="review-field review-rating">
            <span id="review-rating-label">{{ $t('reviews.ratingLabel') }}</span>
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
              :rows="6"
              maxlength="1000"
              show-word-limit
              :disabled="submitPending"
              :aria-label="$t('common.reviewContent')"
              :placeholder="$t('common.reviewContent')"
            />
          </div>

          <div class="review-upload">
            <div class="review-upload__heading">
              <strong>{{ $t('common.upload') }}</strong>
              <span>{{ $t('reviews.imageHint') }}</span>
            </div>

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

              <label class="upload-control" for="review-image-upload">
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
          </div>

          <div class="anonymous-control">
            <div>
              <strong>{{ $t('common.anonymous') }}</strong>
              <span>{{ $t('reviews.anonymousHint') }}</span>
            </div>
            <el-switch v-model="form.anonymous" :aria-label="$t('common.anonymous')" />
          </div>

          <p v-if="submitError" class="task-error" role="alert">{{ submitError }}</p>

          <div class="review-submit">
            <el-button
              type="primary"
              native-type="submit"
              :loading="submitPending"
              :disabled="submitPending || uploadPending"
            >
              {{ $t('common.submitReview') }}
            </el-button>
          </div>
        </form>
      </section>

      <section class="review-history" :aria-label="$t('reviews.historyTitle')">
        <header class="section-heading">
          <div>
            <h2>{{ $t('reviews.historyTitle') }}</h2>
            <p>{{ $t('reviews.historyHint') }}</p>
          </div>
          <el-button
            text
            :icon="RefreshRight"
            :loading="reviewResource.status.value === 'updating'"
            @click="loadReviews"
          >
            {{ $t('common.refresh') }}
          </el-button>
        </header>

        <AsyncStateView
          :status="reviewResource.status.value"
          :error="reviewResource.error.value"
          :empty-title="$t('reviews.emptyHistory')"
          @retry="loadReviews"
        >
          <template #error>
            <div class="history-error" role="alert">
              <span>{{ $t('reviews.loadFailed') }}</span>
              <el-button :icon="RefreshRight" @click="loadReviews">
                {{ $t('common.retry') }}
              </el-button>
            </div>
          </template>

          <template #empty>
            <div class="history-empty" role="status">
              <MascotState pose="clipboard" size="md" :alt="$t('reviews.emptyMascotAlt')" />
              <p>{{ $t('reviews.emptyHistory') }}</p>
            </div>
          </template>

          <DataTableShell :aria-label="$t('reviews.historyTitle')">
            <div class="review-list">
              <article v-for="review in reviews" :key="review.id" class="review-item">
                <header class="review-title">
                  <div>
                    <el-icon aria-hidden="true"><Star /></el-icon>
                    <strong>{{ $t('reviews.ratingSummary', { rating: review.rating }) }}</strong>
                  </div>
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
  </div>
</template>

<style scoped>
.review-view {
  display: grid;
  gap: var(--space-5);
}

.review-workspace {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(340px, 0.85fr);
  gap: var(--space-6);
  border-top: 1px solid var(--color-line);
  padding-top: var(--space-5);
}

.review-composer,
.review-history,
.review-form,
.review-field,
.review-upload,
.review-list,
.review-item {
  display: grid;
}

.review-composer,
.review-history {
  align-content: start;
  gap: var(--space-5);
  min-width: 0;
}

.review-history {
  padding-left: var(--space-6);
  border-left: 1px solid var(--color-line);
}

.section-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
}

.section-heading h2,
.section-heading p,
.history-empty p,
.review-item p {
  margin: 0;
}

.section-heading h2 {
  font-size: var(--text-lg);
}

.section-heading p,
.section-heading > span,
.review-upload__heading span,
.anonymous-control span,
.review-title time {
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}

.section-heading p {
  margin-top: var(--space-1);
}

.section-heading > span {
  flex: 0 0 auto;
  padding-top: var(--space-1);
  font-weight: 700;
}

.review-form {
  gap: var(--space-5);
}

.review-field,
.review-upload,
.review-item {
  gap: var(--space-3);
}

.review-rating > span {
  color: var(--color-text-muted);
  font-size: var(--text-sm);
  font-weight: 700;
}

.review-rating :deep(.el-rate) {
  min-height: 36px;
}

.review-upload {
  padding-top: var(--space-4);
  border-top: 1px solid var(--color-line);
}

.review-upload__heading,
.anonymous-control {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
}

.review-upload__heading span {
  text-align: right;
}

.review-images,
.review-thumbs {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.review-image,
.upload-control {
  width: 88px;
  height: 88px;
}

.review-image {
  position: relative;
}

.review-image :deep(.product-image),
.review-thumbs :deep(.product-image) {
  width: 88px;
  height: 88px;
  aspect-ratio: 1;
}

.review-image__remove {
  position: absolute;
  top: var(--space-1);
  right: var(--space-1);
}

.upload-control {
  display: grid;
  align-content: center;
  justify-items: center;
  gap: var(--space-1);
  border: 1px dashed var(--color-line-strong);
  border-radius: var(--radius-control);
  color: var(--color-text-muted);
  cursor: pointer;
  font-size: var(--text-xs);
}

.upload-control:hover {
  border-color: var(--color-brand);
  color: var(--color-brand);
}

.upload-control:focus-within {
  outline: 2px solid var(--color-brand);
  outline-offset: 2px;
}

.upload-control input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
}

.upload-progress {
  display: grid;
  grid-template-columns: minmax(160px, 320px) auto;
  align-items: center;
  gap: var(--space-3);
}

.upload-progress span {
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}

.anonymous-control {
  min-height: 58px;
  padding-block: var(--space-3);
  border-block: 1px solid var(--color-line);
}

.anonymous-control > div {
  display: grid;
  gap: var(--space-1);
}

.task-error {
  margin: 0;
  color: var(--color-danger);
  font-size: var(--text-sm);
}

.review-submit {
  display: flex;
  justify-content: flex-end;
}

.review-submit .el-button {
  min-width: 160px;
  min-height: 42px;
}

.history-empty {
  display: grid;
  align-content: center;
  justify-items: center;
  gap: var(--space-2);
  min-height: 300px;
  color: var(--color-text-muted);
  text-align: center;
}

.history-error {
  display: grid;
  justify-items: start;
  gap: var(--space-3);
  padding-block: var(--space-5);
}

.review-list {
  gap: 0;
}

.review-item {
  padding: var(--space-4);
  border-bottom: 1px solid var(--color-line);
}

.review-item:last-child {
  border-bottom: 0;
}

.review-title,
.review-title > div {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.review-title {
  justify-content: space-between;
}

.review-title > div {
  color: var(--color-warning);
}

.review-item p {
  overflow-wrap: anywhere;
  line-height: 1.65;
}

@media (max-width: 920px) {
  .review-workspace {
    grid-template-columns: 1fr;
  }

  .review-history {
    padding-top: var(--space-5);
    padding-left: 0;
    border-top: 1px solid var(--color-line);
    border-left: 0;
  }
}

@media (max-width: 560px) {
  .section-heading,
  .review-upload__heading {
    display: grid;
  }

  .section-heading > span,
  .review-upload__heading span {
    text-align: left;
  }

  .review-submit .el-button {
    width: 100%;
    min-height: 44px;
  }
}
</style>
