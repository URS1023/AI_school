<script setup lang="ts">
import { DEMO_ACCOUNTS, LoginView } from '@jb/ui'

const demo = import.meta.env.DEV ? DEMO_ACCOUNTS.filter((d) => d.label === '学校管理员') : []
</script>

<template>
  <LoginView title="世纪金榜AI 管理后台" subtitle="学校管理员 · 平台运营" :demo-accounts="demo" />
</template>
