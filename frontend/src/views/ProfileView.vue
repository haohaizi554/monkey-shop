<script setup lang="ts">
import { Refresh, Upload } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { uploadImage } from '@/api/catalog'
import { captchaConfig as loadCaptchaConfig, captchaUrl } from '@/api/auth'
import * as userApi from '@/api/user'
import AppShell from '@/components/AppShell.vue'
import HumanVerification from '@/components/HumanVerification.vue'
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

async function loadProfile() {
  loading.value = true
  try {
    profile.value = await userApi.profile()
    addresses.value = await userApi.addresses()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Unable to load profile')
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
  ElMessage.success('Avatar updated')
  await loadProfile()
}

async function saveAddress() {
  await userApi.addAddress(addressForm)
  Object.assign(addressForm, { receiverName: '', phone: '', detailAddress: '' })
  addresses.value = await userApi.addresses()
}

async function removeAddress(id: number) {
  await ElMessageBox.confirm('Delete this address?', 'Confirm', { type: 'warning' })
  await userApi.deleteAddress(id)
  addresses.value = await userApi.addresses()
}

async function changePassword() {
  if (turnstileEnabled.value && !passwordForm.captcha) {
    ElMessage.error('captcha required')
    return
  }
  await userApi.updatePassword(passwordForm)
  ElMessage.success('Password changed; please sign in again')
  userCaptchaUrl.value = captchaUrl('user')
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
  <AppShell>
    <section v-loading="loading" class="profile-layout">
      <div class="profile-summary">
        <img :src="profile.avatar || defaultAvatar" alt="Avatar">
        <div>
          <h1>{{ profile.username }}</h1>
          <p>{{ profile.identity }} · {{ profile.maskedPhone }}</p>
          <el-tag v-if="profile.passwordChangeRequired" type="warning" disable-transitions>
            Password change required
          </el-tag>
        </div>
        <label class="file-picker" for="profile-avatar-input">
          <el-icon><Upload /></el-icon>
          <span>{{ $t('common.upload') }}</span>
          <input id="profile-avatar-input" type="file" accept="image/png,image/jpeg" @change="changeAvatar">
        </label>
      </div>

      <section class="section-band">
        <h2>{{ $t('common.address') }}</h2>
        <div class="inline-form">
          <el-input v-model="addressForm.receiverName" placeholder="Receiver" />
          <el-input v-model="addressForm.phone" placeholder="Phone" />
          <el-input v-model="addressForm.detailAddress" placeholder="Address" />
          <el-button type="primary" @click="saveAddress">
            {{ $t('common.save') }}
          </el-button>
        </div>
        <el-table :data="addresses" class="data-table">
          <el-table-column prop="receiverName" label="Receiver" />
          <el-table-column prop="phone" label="Phone" />
          <el-table-column prop="detailAddress" label="Address" />
          <el-table-column label="Default" width="120">
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
        </el-table>
      </section>

      <section class="section-band">
        <h2>Security</h2>
        <div class="inline-form">
          <el-input v-model="passwordForm.phone" :placeholder="$t('auth.phone')" />
          <el-input v-model="passwordForm.newPassword" :placeholder="$t('auth.newPassword')" type="password" show-password />
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
                aria-label="Refresh captcha"
                @click="userCaptchaUrl = captchaUrl('user')"
              >
                <img :src="userCaptchaUrl" alt="Captcha">
              </button>
              <el-button :icon="Refresh" circle @click="userCaptchaUrl = captchaUrl('user')" />
            </template>
          </div>
          <el-button type="primary" @click="changePassword">
            {{ $t('common.save') }}
          </el-button>
        </div>
      </section>
    </section>
  </AppShell>
</template>
