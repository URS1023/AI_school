<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { ApiError } from '@jb/api'
import { useAuth } from '@jb/auth'

/**
 * 兼容原 agent 域名的单点登录链接：/sso?ticket=xxx&redirect=/ai。
 * ticket 一次性、60 秒失效（PRD 13）；换取后立刻从地址栏去掉，避免留在浏览器历史里。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuth()
const error = ref('')

onMounted(async () => {
  const ticket = typeof route.query.ticket === 'string' ? route.query.ticket : ''
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
  history.replaceState(null, '', '/sso')
  if (!ticket) {
    router.replace('/login')
    return
  }
  try {
    await auth.loginByTicket(ticket)
    await auth.loadSession()
    router.replace(redirect)
  } catch (e) {
    error.value = (e as ApiError).message
  }
})
</script>

<template>
  <el-result v-if="error" icon="error" title="登录凭证已失效" :sub-title="error">
    <template #extra><el-button type="primary" @click="router.replace('/login')">重新登录</el-button></template>
  </el-result>
  <div v-else v-loading="true" style="height: 100%" element-loading-text="正在登录…" />
</template>
