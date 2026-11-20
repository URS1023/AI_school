<script setup lang="ts">
import { DEMO_ACCOUNTS, LoginView } from '@jb/ui'

const demo = import.meta.env.DEV ? DEMO_ACCOUNTS : []
</script>

<template>
  <LoginView title="世纪金榜AI 教学平台" subtitle="备课 · 资源 · 阅卷 · 学情 · AI 助手" allow-register :demo-accounts="demo" />
</template>
