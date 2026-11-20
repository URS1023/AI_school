<script setup lang="ts">
/** 模块页头：标题 + 一句说明，右侧放学段学科切换或操作按钮；下方一条墨线收住。icon / color 保留兼容，不再绘制色块。 */
const props = defineProps<{ title: string; desc?: string; icon?: string; color?: string }>()
</script>

<template>
  <section class="jb-ph">
    <div class="jb-ph__text">
      <div class="jb-ph__title">
        <h1>{{ props.title }}</h1>
        <slot name="title-extra" />
      </div>
      <p v-if="props.desc">{{ props.desc }}</p>
    </div>
    <div class="jb-ph__extra"><slot /></div>
  </section>
</template>

<style scoped>
.jb-ph {
  display: flex; align-items: flex-end; gap: 20px; padding: 4px 0 14px; margin-bottom: 20px;
  border-bottom: 1px solid var(--color-ink-2);
}
.jb-ph__text { flex: 1; min-width: 0; }
.jb-ph__title { display: flex; align-items: center; gap: 12px; }
.jb-ph__title h1 { margin: 0; font-size: var(--text-2xl); }
.jb-ph__text p { margin: 6px 0 0; color: var(--color-muted); font-size: var(--text-sm); max-width: 72ch; }
.jb-ph__extra { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; justify-content: flex-end; }
</style>
