<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { http, unwrap, type Schemas } from '@jb/api'
import { EChart, PageHeader, useDict } from '@jb/ui'
import { useNames } from '../../composables/school'

/** 数据中心（PRD 6.8）：按日期、学科统计扫描量；按老师统计布置与扫描情况。 */
const { label: subjectLabel } = useDict('sys_subject')
const names = useNames()
const range = ref<[string, string]>([new Date(Date.now() - 29 * 864e5).toISOString().slice(0, 10), new Date().toISOString().slice(0, 10)])
const data = ref<Schemas['ScanReport'] | null>(null)

async function load() {
  data.value = await unwrap(http.GET('/api/exam/datacenter/scan', { params: { query: { from: range.value?.[0], to: range.value?.[1] } } })).catch(() => null)
  await names.loadNames([], data.value?.teachers?.map((t) => t.userId) ?? [])
}

const totals = computed(() => ({
  images: data.value?.daily?.reduce((s, d) => s + (d.images ?? 0), 0) ?? 0,
  batches: data.value?.daily?.reduce((s, d) => s + (d.batches ?? 0), 0) ?? 0,
  teachers: data.value?.teachers?.length ?? 0,
}))

const chart = computed(() => {
  const days = [...new Set(data.value?.daily?.map((d) => d.date) ?? [])].sort()
  const subs = [...new Set(data.value?.daily?.map((d) => d.subject) ?? [])]
  return {
    tooltip: { trigger: 'axis' },
    legend: { bottom: 0 },
    grid: { left: 44, right: 16, top: 20, bottom: 50 },
    xAxis: { type: 'category', data: days },
    yAxis: { type: 'value', name: '图片数' },
    series: subs.map((s) => ({ type: 'bar', stack: 'all', name: subjectLabel(s), data: days.map((d) => data.value?.daily?.find((x) => x.date === d && x.subject === s)?.images ?? 0) })),
  }
})

onMounted(load)
</script>

<template>
  <div>
    <PageHeader title="数据中心" icon="数" color="var(--jb-c-learning)" desc="统计本校的扫描量与老师使用情况">
      <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" :clearable="false" style="width: 260px" @change="load" />
    </PageHeader>
    <div class="kpis">
      <div><small>扫描图片</small><b>{{ totals.images }}</b></div>
      <div><small>扫描批次</small><b>{{ totals.batches }}</b></div>
      <div><small>使用老师</small><b>{{ totals.teachers }}</b></div>
    </div>
    <el-card header="每日扫描量（按学科）">
      <EChart v-if="data?.daily?.length" :option="chart" height="300px" />
      <el-empty v-else description="这段时间没有扫描" :image-size="60" />
    </el-card>
    <el-card header="老师使用情况" class="mt">
      <el-table :data="data?.teachers ?? []" size="small" empty-text="暂无数据">
        <el-table-column label="老师"><template #default="{ row }">{{ names.user(row.userId) }}</template></el-table-column>
        <el-table-column label="学科"><template #default="{ row }">{{ row.subjects?.map(subjectLabel).join('、') }}</template></el-table-column>
        <el-table-column prop="works" label="布置次数" width="100" />
        <el-table-column prop="scans" label="扫描批次" width="100" />
        <el-table-column prop="images" label="扫描图片" width="100" />
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.kpis { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; margin-bottom: 14px; }
.kpis > div { background: var(--jb-surface); border-radius: var(--jb-radius); padding: 14px 18px; box-shadow: var(--jb-shadow); display: flex; flex-direction: column; border-left: 4px solid var(--jb-c-learning); }
.kpis b { font-size: 26px; color: var(--jb-c-learning); }
.mt { margin-top: 14px; }
</style>
