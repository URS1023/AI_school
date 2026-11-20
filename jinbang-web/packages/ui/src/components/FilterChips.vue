<script setup lang="ts">
import type { FilterRow } from '../filter'
/** 筛选行：「标签 + 一排可选项」，第一个选项「全部」值为空。v-model 是 { key: value } 对象。 */
const props = defineProps<{ rows: FilterRow[]; modelValue: Record<string, string | number | undefined> }>()
const emit = defineEmits<{ 'update:modelValue': [Record<string, string | number | undefined>]; change: [] }>()

function pick(key: string, value: string | number | undefined) {
  emit('update:modelValue', { ...props.modelValue, [key]: value })
  emit('change')
}
</script>

<template>
  <div class="jb-fc">
    <div v-for="r in props.rows" :key="r.key" class="jb-fc__row">
      <span class="jb-fc__label">{{ r.label }}</span>
      <div class="jb-fc__opts">
        <button type="button" :class="{ on: props.modelValue[r.key] === undefined || props.modelValue[r.key] === '' }"
                @click="pick(r.key, undefined)">全部</button>
        <button v-for="o in r.options" :key="o.value" type="button" :class="{ on: props.modelValue[r.key] === o.value }"
                @click="pick(r.key, o.value)">{{ o.label }}</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.jb-fc { display: flex; flex-direction: column; gap: 8px; padding: 14px 18px; background: var(--jb-surface); border-radius: var(--jb-radius); border: 1px solid var(--jb-border); }
.jb-fc__row { display: flex; gap: 12px; align-items: flex-start; }
.jb-fc__label { width: 56px; flex-shrink: 0; padding-top: 4px; color: var(--jb-text-secondary); font-size: 13px; }
.jb-fc__opts { display: flex; flex-wrap: wrap; gap: 6px; }
.jb-fc__opts button {
  border: 1px solid transparent; background: none; padding: 4px 12px; border-radius: 14px; cursor: pointer; color: var(--jb-text); font-size: 13px;
}
.jb-fc__opts button:hover { color: var(--jb-brand); }
.jb-fc__opts button.on { background: var(--jb-brand-light); color: var(--jb-brand-dark); border-color: var(--el-color-primary-light-7); font-weight: 600; }
</style>
