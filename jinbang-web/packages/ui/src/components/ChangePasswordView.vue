<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { ApiError } from '@jb/api'
import { useAuth } from '@jb/auth'

/** 修改密码。首次登录使用初始密码时强制进入此页（PRD 2.1 ➕P0），改完才能使用其他功能。 */
const auth = useAuth()
const route = useRoute()
const router = useRouter()
const form = reactive({ oldPassword: '', newPassword: '', confirm: '' })
const loading = ref(false)
const strong = computed(() => /^(?=.*[A-Za-z])(?=.*\d)[\x21-\x7e]{8,20}$/.test(form.newPassword))

async function submit() {
  if (!strong.value) return ElMessage.warning('新密码需 8-20 位，且同时包含字母和数字')
  if (form.newPassword !== form.confirm) return ElMessage.warning('两次输入的新密码不一致')
  loading.value = true
  try {
    await auth.changePassword(form.newPassword, form.oldPassword)
    ElMessage.success('密码已修改')
    await auth.loadSession()
    router.replace(typeof route.query.redirect === 'string' ? route.query.redirect : '/')
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="jb-pwd">
    <el-card class="jb-pwd__card">
      <template #header>
        <strong>{{ auth.mustChangePassword ? '首次登录，请先修改初始密码' : '修改密码' }}</strong>
      </template>
      <el-form label-width="90px" @submit.prevent="submit">
        <el-form-item label="原密码"><el-input v-model="form.oldPassword" type="password" show-password data-test="old" /></el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="form.newPassword" type="password" show-password data-test="new" />
          <div class="jb-pwd__hint" :class="{ ok: strong }">8-20 位，同时包含字母和数字</div>
        </el-form-item>
        <el-form-item label="确认新密码"><el-input v-model="form.confirm" type="password" show-password data-test="confirm" /></el-form-item>
        <el-form-item>
          <el-button type="primary" native-type="submit" :loading="loading" data-test="submit">确认修改</el-button>
          <el-button v-if="auth.mustChangePassword" link @click="auth.logout().then(() => router.replace('/login'))">退出登录</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.jb-pwd { min-height: 100%; display: flex; align-items: center; justify-content: center; }
.jb-pwd__card { width: 460px; }
.jb-pwd__hint { width: 100%; font-size: 12px; color: var(--jb-text-secondary); }
.jb-pwd__hint.ok { color: var(--el-color-success); }
</style>
