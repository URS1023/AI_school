<script setup lang="ts">
import { AppLayout } from '@jb/ui'
import type { MenuItem } from '@jb/auth'

/** 管理后台只显示教务管理（学校后台）与运营后台两组菜单。 */
const ADMIN_ROOTS = ['/edu-affairs', '/admin']
const rootFilter = (m: MenuItem) => ADMIN_ROOTS.includes(m.path ?? '')
</script>

<template>
  <AppLayout app-name="世纪金榜AI · 管理后台" :root-filter="rootFilter" home-path="/" />
</template>
