<script setup lang="ts">
/** 平台标志：书页 + 星火，墨绿底。仅为本项目绘制，不使用原站标识。 */
defineProps<{ size?: number; text?: string }>()
</script>

<template>
  <span class="jb-brand">
    <svg :width="size ?? 32" :height="size ?? 32" viewBox="0 0 32 32" aria-hidden="true">
      <rect width="32" height="32" rx="9" fill="var(--jb-brand)" />
      <path d="M7 10.5c3-1.4 6-1.4 9 .4v12.6c-3-1.8-6-1.8-9-.4z" fill="#fff" opacity=".95" />
      <path d="M25 10.5c-3-1.4-6-1.4-9 .4v12.6c3-1.8 6-1.8 9-.4z" fill="#fff" opacity=".75" />
      <circle cx="23.5" cy="7.5" r="2.6" fill="var(--jb-accent)" />
    </svg>
    <span v-if="text" class="jb-brand__text">{{ text }}</span>
  </span>
</template>

<style scoped>
.jb-brand { display: inline-flex; align-items: center; gap: 10px; }
.jb-brand__text { font-weight: 700; font-size: 18px; color: var(--jb-brand-dark); letter-spacing: 0.5px; }
</style>
