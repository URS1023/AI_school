<script setup lang="ts">
import { AppLayout } from '@jb/ui'
import type { MenuItem } from '@jb/auth'

/** 管理类菜单（教务管理、运营后台）和学生 / 家长端菜单不在教师 Web 里显示，分别在管理后台和学生端。 */
const OTHER_APPS = ['/edu-affairs', '/admin', '/student', '/parent']
const rootFilter = (m: MenuItem) => !OTHER_APPS.includes(m.path ?? '')
</script>

<template>
  <AppLayout app-name="世纪金榜AI" :root-filter="rootFilter" home-path="/" />
</template>
