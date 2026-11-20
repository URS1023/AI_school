<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { findMenu } from '../menu'
import { useAuth } from '@jb/auth'

/** 菜单已下发、页面尚在后续阶段开发时的占位页：明确告知「建设中」，不做成可点击却无响应（PRD 2.2 🚧）。 */
const route = useRoute()
const auth = useAuth()
const menu = computed(() => findMenu(auth.menus, route.path))
</script>

<template>
  <el-empty :description="`「${menu?.name ?? route.path}」页面正在建设中，后端接口已就绪，前端按排期上线`" data-test="placeholder" />
</template>
