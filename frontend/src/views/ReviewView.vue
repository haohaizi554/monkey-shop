<script setup lang="ts">
import { Star, Upload } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { uploadImage } from '@/api/catalog'
import { orderReviews, reviewOrder } from '@/api/orders'
import ProductImage from '@/components/ProductImage.vue'
import type { OrderReview } from '@/types'
import { dateTime } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const loading = ref(false)
const submitting = ref(false)
const uploading = ref(false)
const reviews = ref<OrderReview[]>([])
const form = reactive({
  skuId: undefined as number | undefined,
  rating: 5,
  content: '',
  imageUrls: [] as string[],
  anonymous: false,
})

const orderId = computed(() => Number(route.params.id))

async function loadReviews() {
  loading.value = true
  try {
    reviews.value = await orderReviews(orderId.value)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('common.unableToLoadOrders'))
  } finally {
    loading.value = false
  }
}

async function uploadReviewImage(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file || uploading.value) {
    return
  }
  uploading.value = true
  try {
    const uploaded = await uploadImage(file, 'product')
    form.imageUrls.push(uploaded.path)
    ElMessage.success(t('common.imageUploaded'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('common.unableToUploadImage'))
  } finally {
    uploading.value = false
    ;(event.target as HTMLInputElement).value = ''
  }
}

async function submitReview() {
  if (submitting.value) {
    return
  }
  submitting.value = true
  try {
    await reviewOrder(orderId.value, {
      skuId: form.skuId,
      rating: form.rating,
      content: form.content,
      imageUrls: form.imageUrls,
      anonymous: form.anonymous,
    })
    ElMessage.success(t('common.reviewSubmitted'))
    await loadReviews()
    await router.push('/orders')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('common.unableToReview'))
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  void loadReviews()
})
</script>

<template>
  <div class="route-view">
    <section class="page-heading">
      <h1>{{ $t('common.review') }}</h1>
      <el-button @click="$router.push('/orders')">
        {{ $t('nav.orders') }}
      </el-button>
    </section>

    <section class="review-layout">
      <form class="review-form" @submit.prevent="submitReview">
        <el-rate v-model="form.rating" :max="5" size="large" />
        <el-input
          v-model="form.content"
          type="textarea"
          :rows="5"
          :placeholder="$t('common.reviewContent')"
        />
        <el-switch v-model="form.anonymous" :active-text="$t('common.anonymous')" />
        <div class="review-images">
          <ProductImage
            v-for="image in form.imageUrls"
            :key="image"
            :src="image"
            :alt="$t('common.review')"
          />
          <label class="upload-chip" for="review-image-upload">
            <el-icon><Upload /></el-icon>
            <span>{{ $t('common.upload') }}</span>
            <input
              id="review-image-upload"
              type="file"
              accept="image/*"
              :disabled="uploading"
              @change="uploadReviewImage"
            />
          </label>
        </div>
        <el-button type="primary" native-type="submit" :loading="submitting">
          {{ $t('common.submitReview') }}
        </el-button>
      </form>

      <section v-loading="loading" class="review-history">
        <article v-for="review in reviews" :key="review.id" class="review-item">
          <div class="review-title">
            <el-rate :model-value="review.rating" disabled />
            <span>{{ dateTime(review.createTime) }}</span>
          </div>
          <p>{{ review.content }}</p>
          <div class="review-thumbs">
            <ProductImage
              v-for="image in review.imageUrls"
              :key="image"
              :src="image"
              :alt="$t('common.review')"
            />
          </div>
        </article>
        <el-empty v-if="!loading && reviews.length === 0" :image-size="96">
          <template #description>
            <el-icon class="empty-icon"><Star /></el-icon>
          </template>
        </el-empty>
      </section>
    </section>
  </div>
</template>

<style scoped>
.review-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(280px, 420px);
  gap: 18px;
}

.review-form,
.review-history {
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 16px;
  background: var(--surface-color);
}

.review-form {
  display: grid;
  gap: 14px;
  align-content: start;
}

.review-images,
.review-thumbs {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.review-images :deep(.product-image),
.review-thumbs :deep(.product-image) {
  width: 72px;
  height: 72px;
}

.upload-chip {
  width: 72px;
  height: 72px;
  display: grid;
  place-items: center;
  gap: 2px;
  border: 1px dashed var(--border-color);
  border-radius: 8px;
  cursor: pointer;
  color: var(--text-muted);
  font-size: 12px;
}

.upload-chip input {
  display: none;
}

.review-history {
  display: grid;
  gap: 12px;
}

.review-item {
  border-bottom: 1px solid var(--border-color);
  padding-bottom: 12px;
}

.review-item:last-child {
  border-bottom: 0;
  padding-bottom: 0;
}

.review-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: var(--text-muted);
}

@media (max-width: 760px) {
  .review-layout {
    grid-template-columns: 1fr;
  }
}
</style>
