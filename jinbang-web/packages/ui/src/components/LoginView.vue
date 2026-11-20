<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance } from 'element-plus'
import { ApiError, http, unwrap } from '@jb/api'
import { useAuth } from '@jb/auth'

const props = defineProps<{ title: string; subtitle?: string; allowRegister?: boolean }>()

const auth = useAuth()
const router = useRouter()
const route = useRoute()
const tab = ref<'password' | 'sms' | 'register' | 'forgot'>('password')
const loading = ref(false)
const formRef = ref<FormInstance>()
const form = reactive({ account: '', password: '', captchaCode: '', phone: '', code: '', newPassword: '', nickname: '' })
const captcha = reactive({ id: '', image: '' })
const smsCountdown = ref(0)

async function refreshCaptcha() {
  try {
    const c = await unwrap(http.GET('/api/auth/captcha'))
    captcha.id = c.captchaId ?? ''
    captcha.image = c.imageBase64 ?? ''
    form.captchaCode = ''
  } catch {
    /* 验证码拉取失败时登录接口会提示 */
  }
}

const sceneOf = { sms: 'LOGIN', register: 'REGISTER', forgot: 'RESET', password: 'LOGIN' } as const

async function sendSms() {
  if (!/^1\d{10}$/.test(form.phone)) {
    ElMessage.warning('请输入正确的手机号')
    return
  }
  try {
    await unwrap(http.POST('/api/auth/sms/send', { body: { phone: form.phone, scene: sceneOf[tab.value] } }))
    ElMessage.success('验证码已发送')
    smsCountdown.value = 60
    const timer = setInterval(() => {
      smsCountdown.value -= 1
      if (smsCountdown.value <= 0) clearInterval(timer)
    }, 1000)
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

async function enter() {
  await auth.loadSession()
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
  router.replace(auth.mustChangePassword ? { path: '/change-password', query: { redirect } } : redirect)
}

async function submit() {
  loading.value = true
  try {
    if (tab.value === 'password') {
      await auth.loginByPassword(form.account.trim(), form.password, captcha.id, form.captchaCode)
      await enter()
    } else if (tab.value === 'sms') {
      await auth.loginBySms(form.phone, form.code)
      await enter()
    } else if (tab.value === 'register') {
      await auth.register(form.phone, form.code, form.newPassword, form.nickname || undefined)
      ElMessage.success('注册成功，已赠送 500 金榜币')
      await enter()
    } else {
      await unwrap(http.POST('/api/auth/password/forgot', { body: { phone: form.phone, code: form.code, newPassword: form.newPassword } }))
      ElMessage.success('密码已重置，请登录')
      tab.value = 'password'
      form.account = form.phone
    }
  } catch (e) {
    ElMessage.error((e as ApiError).message)
    if (tab.value === 'password') refreshCaptcha()
  } finally {
    loading.value = false
  }
}

onMounted(refreshCaptcha)
</script>

<template>
  <div class="jb-login">
    <div class="jb-login__card">
      <h1>{{ props.title }}</h1>
      <p v-if="props.subtitle" class="jb-login__sub">{{ props.subtitle }}</p>
      <el-tabs v-model="tab" stretch>
        <el-tab-pane label="密码登录" name="password" />
        <el-tab-pane label="验证码登录" name="sms" />
        <el-tab-pane v-if="props.allowRegister" label="注册" name="register" />
        <el-tab-pane label="忘记密码" name="forgot" />
      </el-tabs>
      <el-form ref="formRef" :model="form" label-width="0" size="large" @submit.prevent="submit">
        <template v-if="tab === 'password'">
          <el-form-item><el-input v-model="form.account" placeholder="手机号 / 登录名" autocomplete="username" data-test="account" /></el-form-item>
          <el-form-item><el-input v-model="form.password" type="password" placeholder="密码" show-password autocomplete="current-password" data-test="password" /></el-form-item>
          <el-form-item>
            <div class="jb-login__captcha">
              <el-input v-model="form.captchaCode" placeholder="计算结果" data-test="captcha" />
              <img v-if="captcha.image" :src="captcha.image" alt="验证码，点击刷新" title="点击刷新" @click="refreshCaptcha" />
            </div>
          </el-form-item>
        </template>
        <template v-else>
          <el-form-item><el-input v-model="form.phone" placeholder="手机号" maxlength="11" /></el-form-item>
          <el-form-item>
            <div class="jb-login__captcha">
              <el-input v-model="form.code" placeholder="短信验证码" maxlength="6" />
              <el-button :disabled="smsCountdown > 0" @click="sendSms">{{ smsCountdown > 0 ? `${smsCountdown}s` : '获取验证码' }}</el-button>
            </div>
          </el-form-item>
          <el-form-item v-if="tab === 'register'"><el-input v-model="form.nickname" placeholder="昵称（选填）" /></el-form-item>
          <el-form-item v-if="tab === 'register' || tab === 'forgot'">
            <el-input v-model="form.newPassword" type="password" show-password placeholder="新密码：8-20 位，含字母和数字" />
          </el-form-item>
        </template>
        <el-button type="primary" native-type="submit" :loading="loading" class="jb-login__submit" data-test="submit">
          {{ { password: '登录', sms: '登录', register: '注册并登录', forgot: '重置密码' }[tab] }}
        </el-button>
      </el-form>
      <p class="jb-login__tip">登录即表示同意《用户协议》和《隐私政策》</p>
    </div>
  </div>
</template>

<style scoped>
.jb-login { min-height: 100%; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, var(--jb-brand-light), var(--jb-bg)); }
.jb-login__card { width: 400px; padding: 36px 40px; background: var(--jb-surface); border-radius: 12px; box-shadow: 0 8px 30px rgba(0, 0, 0, 0.08); }
.jb-login__card h1 { margin: 0; font-size: 24px; color: var(--jb-brand); }
.jb-login__sub { margin: 6px 0 12px; color: var(--jb-text-secondary); }
.jb-login__captcha { display: flex; gap: 8px; width: 100%; }
.jb-login__captcha img { height: 40px; cursor: pointer; border-radius: 4px; }
.jb-login__submit { width: 100%; }
.jb-login__tip { margin: 16px 0 0; font-size: 12px; color: var(--jb-text-secondary); text-align: center; }
</style>
