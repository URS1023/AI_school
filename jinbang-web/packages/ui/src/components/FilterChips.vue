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
.jb-fc { display: flex; flex-direction: column; gap: 10px; padding: 4px 0 14px; border-bottom: 1px solid var(--color-rule); }
.jb-fc__row { display: flex; gap: 12px; align-items: flex-start; }
.jb-fc__label { width: 56px; flex-shrink: 0; padding-top: 6px; color: var(--color-muted); font-size: var(--text-sm); }
.jb-fc__opts { display: flex; flex-wrap: wrap; gap: 4px; }
.jb-fc__opts button {
  border: none; background: none; min-height: 30px; padding: 0 12px; border-radius: var(--radius-input); cursor: pointer; color: var(--color-ink-2);
  font: inherit; font-size: var(--text-sm); transition: background-color var(--dur-micro), box-shadow var(--dur-micro);
}
.jb-fc__opts button:hover { background: var(--color-paper-2); }
.jb-fc__opts button.on { background: var(--color-cotton); color: var(--color-ink); font-weight: 600; box-shadow: var(--edge-stock), var(--cast-stock); }
</style>
