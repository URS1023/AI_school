<script setup lang="ts">
import { computed } from 'vue'
import { useDict } from '../composables'

/** 学段学科切换器（PRD 2.2）：v-model:period / v-model:subject。 */
const period = defineModel<string>('period', { default: '1' })
const subject = defineModel<string>('subject', { default: 'MATH' })
const { items: periods } = useDict('sys_period')
const { items: subjects } = useDict('sys_subject')
const label = computed(() =>
  `${periods.value.find((p) => p.dictValue === period.value)?.dictLabel ?? ''} ${subjects.value.find((s) => s.dictValue === subject.value)?.dictLabel ?? ''}`.trim(),
)
</script>

<template>
  <el-popover trigger="click" :width="360" placement="bottom-start">
    <template #reference>
      <el-button>{{ label || '选择学段学科' }}</el-button>
    </template>
    <div class="jb-ps">
      <div class="jb-ps__row">
        <span>学段</span>
        <el-radio-group v-model="period" size="small">
          <el-radio-button v-for="p in periods" :key="p.dictValue" :value="p.dictValue">{{ p.dictLabel }}</el-radio-button>
        </el-radio-group>
      </div>
      <div class="jb-ps__row">
        <span>学科</span>
        <div class="jb-ps__subjects">
          <el-check-tag v-for="s in subjects" :key="s.dictValue" :checked="subject === s.dictValue" @change="subject = s.dictValue!">
            {{ s.dictLabel }}
          </el-check-tag>
        </div>
      </div>
    </div>
  </el-popover>
</template>

<style scoped>
.jb-ps { display: flex; flex-direction: column; gap: 12px; }
.jb-ps__row { display: flex; gap: 12px; }
.jb-ps__row > span { width: 32px; flex-shrink: 0; color: var(--jb-text-secondary); padding-top: 4px; }
.jb-ps__subjects { display: flex; flex-wrap: wrap; gap: 6px; }
</style>
