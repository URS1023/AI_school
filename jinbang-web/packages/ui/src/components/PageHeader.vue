<script setup lang="ts">
/** 模块页头：左侧色块图标 + 标题 + 说明，右侧放学段学科切换或操作按钮。 */
const props = defineProps<{ title: string; desc?: string; icon?: string; color?: string }>()
</script>

<template>
  <section class="jb-ph" :style="{ '--ph-color': props.color ?? 'var(--jb-brand)' }">
    <div class="jb-ph__icon">{{ props.icon ?? props.title.slice(0, 1) }}</div>
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
  display: flex; align-items: center; gap: 16px; padding: 18px 22px; margin-bottom: 16px; border-radius: var(--jb-radius);
  background: linear-gradient(100deg, color-mix(in srgb, var(--ph-color) 10%, var(--jb-surface)), var(--jb-surface) 60%);
  border: 1px solid var(--jb-border);
}
.jb-ph__icon {
  width: 46px; height: 46px; border-radius: 12px; display: grid; place-items: center; font-size: 20px; font-weight: 700;
  color: #fff; background: var(--ph-color); flex-shrink: 0;
}
.jb-ph__text { flex: 1; min-width: 0; }
.jb-ph__title { display: flex; align-items: center; gap: 12px; }
.jb-ph__title h1 { margin: 0; font-size: 20px; }
.jb-ph__text p { margin: 4px 0 0; color: var(--jb-text-secondary); font-size: 13px; }
.jb-ph__extra { display: flex; gap: 10px; align-items: center; }
</style>
