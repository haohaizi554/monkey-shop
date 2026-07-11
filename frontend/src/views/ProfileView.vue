<script setup lang="ts">
import { Check, Lock, Location, Refresh, Upload } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { uploadImage } from '@/api/catalog'
import { captchaConfig as loadCaptchaConfig, captchaUrl } from '@/api/auth'
import * as userApi from '@/api/user'
import HumanVerification from '@/components/HumanVerification.vue'
import { useAuthStore } from '@/stores/auth'
import type { Address, CaptchaConfig, UserProfile } from '@/types'

const profile = ref<UserProfile>({})
const defaultAvatar = '/images/default_avatar.png'
const addresses = ref<Address[]>([])
const loading = ref(false)
const userCaptchaUrl = ref(captchaUrl('user'))
const captchaConfig = ref<CaptchaConfig>({ provider: 'local', siteKey: '' })
const turnstileEnabled = computed(() => captchaConfig.value.provider === 'turnstile')
const addressForm = reactive({ receiverName: '', phone: '', detailAddress: '' })
const passwordForm = reactive({ phone: '', newPassword: '', captcha: '' })
const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()

const profileMeta = computed(() => {
  const identityLabel =
    profile.value.identity === 'ADMIN'
      ? t('nav.admin')
      : profile.value.identity === 'USER'
        ? t('nav.profile')
        : profile.value.identity
  const phoneLabel =
    profile.value.maskedPhone === 'not bound' ? '未绑定手机号' : profile.value.maskedPhone
  const meta = [identityLabel, phoneLabel].filter(Boolean)
  return meta.length ? meta.join(' / ') : t('profile.accountPending')
})

const passwordButtonLabel = computed(() =>
  profile.value.passwordChangeRequired
    ? t('auth.completePasswordUpdate')
    : t('auth.updatePassword'),
)

async function loadProfile() {
  loading.value = true
  try {
    profile.value = await userApi.profile()
    addresses.value = profile.value.passwordChangeRequired ? [] : await userApi.addresses()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('auth.unableToLoadProfile'))
  } finally {
    loading.value = false
  }
}

async function changeAvatar(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) {
    return
  }
  const uploaded = await uploadImage(file, 'avatar')
  await userApi.updateAvatar(uploaded.path)
  ElMessage.success(t('auth.avatarUpdated'))
  await loadProfile()
}

async function saveAddress() {
  await userApi.addAddress(addressForm)
  Object.assign(addressForm, { receiverName: '', phone: '', detailAddress: '' })
  addresses.value = await userApi.addresses()
}

async function removeAddress(id: number) {
  await ElMessageBox.confirm(t('auth.deleteAddressConfirm'), t('common.confirm'), {
    type: 'warning',
  })
  await userApi.deleteAddress(id)
  addresses.value = await userApi.addresses()
}

async function changePassword() {
  if (turnstileEnabled.value && !passwordForm.captcha) {
    ElMessage.error(t('auth.captchaRequired'))
    return
  }
  await userApi.updatePassword(passwordForm)
  ElMessage.success(t('auth.passwordChanged'))
  Object.assign(passwordForm, { phone: '', newPassword: '', captcha: '' })
  userCaptchaUrl.value = captchaUrl('user')
  auth.clearLocalSession()
  await router.push('/login')
}

onMounted(() => {
  void loadProfile()
  loadCaptchaConfig()
    .then((config) => {
      captchaConfig.value = config
    })
    .catch(() => {
      captchaConfig.value = { provider: 'local', siteKey: '' }
    })
})
</script>

<template>
  <div class="route-view">
    <section v-loading="loading" class="profile-layout">
      <div class="profile-summary">
        <img :src="profile.avatar || defaultAvatar" :alt="$t('auth.avatar')" />
        <div class="profile-copy">
          <p class="profile-kicker">{{ $t('profile.accountOverview') }}</p>
          <h1>{{ profile.username || $t('nav.profile') }}</h1>
          <p class="profile-meta">{{ profileMeta }}</p>
          <el-tag
            v-if="profile.passwordChangeRequired"
            class="profile-required-tag"
            type="warning"
            disable-transitions
          >
            {{ $t('common.passwordChangeRequired') }}
          </el-tag>
        </div>
        <div class="profile-actions">
          <label class="file-picker" for="profile-avatar-input">
            <el-icon><Upload /></el-icon>
            <span>{{ $t('common.upload') }}</span>
            <input
              id="profile-avatar-input"
              type="file"
              accept="image/png,image/jpeg"
              @change="changeAvatar"
            />
          </label>
          <RouterLink class="secondary-button" to="/membership">
            {{ $t('nav.membership') }}
          </RouterLink>
        </div>
      </div>

      <section class="section-band">
        <h2 class="section-heading">
          <el-icon><Lock /></el-icon>
          <span>{{ $t('common.security') }}</span>
        </h2>
        <div v-if="profile.passwordChangeRequired" class="security-alert" role="alert">
          <el-icon class="security-alert-icon"><Lock /></el-icon>
          <div>
            <strong>{{ $t('auth.passwordUpdateRequiredTitle') }}</strong>
            <p>{{ $t('auth.passwordUpdateRequiredDescription') }}</p>
          </div>
        </div>
        <form class="inline-form" @submit.prevent="changePassword">
          <el-input v-model="passwordForm.phone" :placeholder="$t('auth.phone')" />
          <el-input
            v-model="passwordForm.newPassword"
            :placeholder="$t('auth.newPassword')"
            type="password"
            show-password
          />
          <HumanVerification
            v-if="turnstileEnabled"
            v-model="passwordForm.captcha"
            action="change-password"
            :site-key="captchaConfig.siteKey"
          />
          <div class="captcha-row">
            <template v-if="!turnstileEnabled">
              <el-input v-model="passwordForm.captcha" :placeholder="$t('auth.captcha')" />
              <button
                class="captcha-image-button"
                type="button"
                :aria-label="$t('common.refreshCaptcha')"
                @click="userCaptchaUrl = captchaUrl('user')"
              >
                <img :src="userCaptchaUrl" alt="Captcha" />
              </button>
              <el-button
                :icon="Refresh"
                circle
                native-type="button"
                @click="userCaptchaUrl = captchaUrl('user')"
              />
            </template>
          </div>
          <el-button type="primary" native-type="submit" :icon="Lock">
            {{ passwordButtonLabel }}
          </el-button>
        </form>
      </section>

      <section v-if="!profile.passwordChangeRequired" class="section-band">
        <h2 class="section-heading">
          <el-icon><Location /></el-icon>
          <span>{{ $t('common.address') }}</span>
        </h2>
        <form class="inline-form" @submit.prevent="saveAddress">
          <el-input v-model="addressForm.receiverName" :placeholder="$t('common.receiver')" />
          <el-input v-model="addressForm.phone" :placeholder="$t('auth.phone')" />
          <el-input v-model="addressForm.detailAddress" :placeholder="$t('common.address')" />
          <el-button type="primary" native-type="submit" :icon="Check">
            {{ $t('common.save') }}
          </el-button>
        </form>
        <el-table :data="addresses" class="data-table">
          <el-table-column prop="receiverName" :label="$t('common.receiver')" />
          <el-table-column prop="phone" :label="$t('auth.phone')" />
          <el-table-column prop="detailAddress" :label="$t('common.address')" />
          <el-table-column :label="$t('common.default')" width="120">
            <template #default="{ row }">
              <el-switch
                :model-value="row.isDefault === 1"
                @change="userApi.setDefaultAddress(row.id).then(loadProfile)"
              />
            </template>
          </el-table-column>
          <el-table-column width="120">
            <template #default="{ row }">
              <el-button type="danger" plain @click="removeAddress(row.id)">
                {{ $t('common.delete') }}
              </el-button>
            </template>
          </el-table-column>
          <template #empty>{{ $t('profile.noAddresses') }}</template>
        </el-table>
      </section>
    </section>
  </div>
</template>
