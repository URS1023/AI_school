<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { http, unwrap, type ApiError, type Schemas } from '@jb/api'
import { EChart, PageHeader, chartBad, chartColor, chartGood, fmtPct, fmtTime, useDict } from '@jb/ui'
import { BLOCK_TYPES, useMyClasses, useNames } from '../../composables/school'

/**
 * 单次学情（PRD 6.5）：概览（参考人数、均分、得分率、最高最低、分数段）、逐题得分率、题型得分率、学生排名；
 * 讲评（按得分率筛题、标记重点讲评题）；学生详情（失分题型、易失分题、优势题）。
 */
const route = useRoute()
const type = computed(() => String(route.params.type))
const id = computed(() => route.params.id as string)
const { classes, className } = useMyClasses()
const names = useNames()
const { label: subjectLabel } = useDict('sys_subject')
const classId = ref('')
const data = ref<Schemas['SourceReport'] | null>(null)
const review = ref<Schemas['ReviewItem'][]>([])
const rateRange = ref<[number, number]>([0, 100])
const keyOnly = ref(false)
const tab = ref('overview')
const student = ref<Schemas['StudentDetail'] | null>(null)
const r = computed(() => data.value?.report)
const path = computed(() => ({ type: type.value, id: id.value }))
const BAND: Record<string, string> = { 优秀: '优秀', 良好: '良好', 合格: '合格', 不合格: '待提高' }
const BAND_COLOR: Record<string, string> = { 优秀: 'var(--chart-4)', 良好: 'var(--chart-2)', 合格: 'var(--chart-3)', 不合格: 'var(--chart-1)' }
const BAND_CHART: Record<string, string> = { 优秀: '--chart-4', 良好: '--chart-2', 合格: '--chart-3', 不合格: '--chart-1' }

async function load() {
  try {
    data.value = await unwrap(http.GET('/api/learning/{type}/{id}/report', { params: { path: path.value, query: { classId: classId.value || undefined } } }))
    await names.loadNames(data.value.report?.students?.map((s) => s.studentId) ?? [])
    if (tab.value === 'review') loadReview()
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

async function loadReview() {
  review.value = await unwrap(http.GET('/api/learning/{type}/{id}/review', { params: { path: path.value, query: {
    classId: classId.value || undefined, minRate: rateRange.value[0] / 100, maxRate: rateRange.value[1] / 100, keyOnly: keyOnly.value,
  } } })).catch(() => [])
}

async function toggleKey(item: Schemas['ReviewItem']) {
  await unwrap(http.POST('/api/learning/{type}/{id}/key', { params: { path: path.value }, body: { itemNo: item.no!, on: !item.key } }))
  item.key = !item.key
}

async function openStudent(sid: string) {
  student.value = await unwrap(http.GET('/api/learning/{type}/{id}/students/{studentId}', { params: { path: { ...path.value, studentId: sid } } }))
}

const bandChart = computed(() => ({
  tooltip: { trigger: 'item' },
  legend: { bottom: 0 },
  series: [{ type: 'pie', radius: ['45%', '70%'], label: { show: false },
    data: Object.entries(r.value?.bands ?? {}).map(([k, v]) => ({ name: BAND[k] ?? k, value: v, itemStyle: { color: chartColor(BAND_CHART[k] ?? '--chart-5') } })) }],
}))
const itemChart = computed(() => ({
  tooltip: { trigger: 'axis', valueFormatter: (v: number) => `${v}%` },
  grid: { left: 40, right: 16, top: 20, bottom: 30 },
  xAxis: { type: 'category', data: r.value?.items?.map((i) => i.no) ?? [] },
  yAxis: { type: 'value', max: 100 },
  series: [{ type: 'bar', barMaxWidth: 26, data: (r.value?.items ?? []).map((i) => ({
    value: Math.round(Number(i.scoreRate) * 100), itemStyle: { color: Number(i.scoreRate) < 0.6 ? chartBad() : chartGood() },
  })) }],
}))
const typeChart = computed(() => ({
  tooltip: { trigger: 'axis' },
  radar: { radius: '62%', indicator: Object.keys(r.value?.typeRates ?? {}).map((k) => ({ name: BLOCK_TYPES[k] ?? k, max: 100 })) },
  series: [{ type: 'radar', data: [{ value: Object.values(r.value?.typeRates ?? {}).map((v) => Math.round(Number(v) * 100)), name: '题型得分率' }] }],
}))

onMounted(load)
</script>

<template>
  <div>
    <PageHeader :title="data?.title ?? '学情报告'" icon="析" color="var(--jb-c-learning)" :desc="`${subjectLabel(data?.subject)}，${fmtTime(data?.time)}`">
      <el-select v-model="classId" clearable placeholder="全部可见班级" style="width: 170px" @change="load">
        <el-option v-for="c in classes" :key="c.classId" :label="c.className" :value="c.classId!" />
      </el-select>
    </PageHeader>
    <el-tabs v-model="tab" @tab-change="(t: string | number) => t === 'review' && loadReview()">
      <el-tab-pane label="成绩概览" name="overview">
        <div class="kpis" data-test="report-kpis">
          <div><small>参考 / 应考</small><b>{{ r?.counts?.valid }} / {{ r?.counts?.total }}</b><em>缺考 {{ r?.counts?.absent }}，未交 {{ r?.counts?.missing }}</em></div>
          <div><small>平均分</small><b>{{ r?.avgScore }}</b><em>满分 {{ r?.fullScore }}</em></div>
          <div><small>得分率</small><b>{{ fmtPct(r?.avgRate) }}</b><em>百分制 {{ r?.avgHundred }}</em></div>
          <div><small>最高 / 最低</small><b>{{ r?.maxScore }} / {{ r?.minScore }}</b><em>&nbsp;</em></div>
        </div>
        <div class="charts">
          <el-card header="分数段分布"><EChart :option="bandChart" height="260px" /></el-card>
          <el-card header="逐题得分率（红色为低于 60% 的薄弱题）"><EChart :option="itemChart" height="260px" /></el-card>
          <el-card header="题型得分率"><EChart v-if="Object.keys(r?.typeRates ?? {}).length > 2" :option="typeChart" height="260px" />
            <div v-else class="types"><div v-for="(v, k) in r?.typeRates" :key="k" class="jb-between"><span>{{ BLOCK_TYPES[k] ?? k }}</span><b>{{ fmtPct(v) }}</b></div></div></el-card>
        </div>
        <el-alert v-if="data?.weakItems?.length" type="warning" :closable="false" show-icon :title="`薄弱题：第 ${data.weakItems.join('、')} 题，建议在讲评中重点讲解`" class="weak" />
        <el-card header="学生成绩">
          <el-table :data="r?.students ?? []" size="small" max-height="460" @row-click="(row: Schemas['RankRow']) => openStudent(row.studentId!)">
            <el-table-column label="班级排名" width="90" prop="classRank" />
            <el-table-column label="年级排名" width="90" prop="gradeRank" />
            <el-table-column label="姓名"><template #default="{ row }">{{ names.student(row.studentId) }}<el-tag v-if="row.critical" size="small" type="warning" class="crit">临界</el-tag></template></el-table-column>
            <el-table-column label="班级"><template #default="{ row }">{{ className(row.classId) }}</template></el-table-column>
            <el-table-column prop="score" label="得分" width="80" />
            <el-table-column label="得分率" width="90"><template #default="{ row }">{{ fmtPct(row.rate) }}</template></el-table-column>
            <el-table-column label="等级" width="90"><template #default="{ row }"><span :style="{ color: BAND_COLOR[row.band] }">{{ BAND[row.band] ?? row.band }}</span></template></el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
      <el-tab-pane label="试卷讲评" name="review">
        <div class="jb-row filter">
          <span>得分率</span><el-slider v-model="rateRange" range :step="5" style="width: 260px" @change="loadReview" />
          <el-checkbox v-model="keyOnly" @change="loadReview">只看重点讲评题</el-checkbox>
        </div>
        <el-table :data="review" size="small" empty-text="没有符合条件的题">
          <el-table-column prop="no" label="题号" width="70" />
          <el-table-column label="题型" width="90"><template #default="{ row }">{{ BLOCK_TYPES[row.type] ?? row.type }}</template></el-table-column>
          <el-table-column prop="fullScore" label="满分" width="70" />
          <el-table-column label="班级得分率"><template #default="{ row }"><el-progress :percentage="Math.round(row.classRate * 100)" :color="row.classRate < 0.6 ? 'var(--color-bad)' : 'var(--color-ink-2)'" /></template></el-table-column>
          <el-table-column label="年级得分率" width="110"><template #default="{ row }">{{ fmtPct(row.gradeRate) }}</template></el-table-column>
          <el-table-column label="正确率" width="90"><template #default="{ row }">{{ row.correctRate == null ? '—' : fmtPct(row.correctRate) }}</template></el-table-column>
          <el-table-column width="130"><template #default="{ row }"><el-button size="small" :type="row.key ? 'warning' : 'default'" @click="toggleKey(row)">{{ row.key ? '★ 重点讲评' : '☆ 设为重点' }}</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-drawer :model-value="!!student" :title="`${student?.name ?? ''} 的本次学情`" size="520px" @close="student = null">
      <template v-if="student">
        <div class="kpis small">
          <div><small>得分</small><b>{{ student.score }}</b></div>
          <div><small>得分率</small><b>{{ fmtPct(student.rate) }}</b></div>
          <div><small>班 / 年级排名</small><b>{{ student.classRank }} / {{ student.gradeRank }}</b></div>
        </div>
        <h4>失分题型</h4>
        <el-table :data="student.typeLoss" size="small">
          <el-table-column prop="type" label="题型" /><el-table-column label="题号"><template #default="{ row }">{{ row.nos?.join('、') }}</template></el-table-column>
          <el-table-column prop="lost" label="失分" width="70" /><el-table-column label="失分率" width="80"><template #default="{ row }">{{ fmtPct(row.errorRate) }}</template></el-table-column>
        </el-table>
        <p><b>易失分题：</b>{{ student.easyLost?.join('、') || '无' }}</p>
        <p><b>优势题：</b>{{ student.advantage?.join('、') || '无' }}</p>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 16px; }
.kpis.small { grid-template-columns: repeat(3, 1fr); }
.kpis > div { padding: 12px 2px 14px; border-top: 1px solid var(--color-ink-2); display: flex; flex-direction: column; gap: 2px; }
.kpis b { font-size: var(--text-2xl); font-weight: 500; font-family: var(--font-outlier); font-variant-numeric: tabular-nums; color: var(--color-ink); }
.kpis em { font-style: normal; font-size: 12px; color: var(--jb-text-secondary); }
.charts { display: grid; grid-template-columns: 1fr 1.6fr 1fr; gap: 14px; margin-bottom: 14px; }
.types { display: flex; flex-direction: column; gap: 10px; padding-top: 10px; }
.weak { margin-bottom: 14px; }
.crit { margin-left: 6px; }
.filter { margin-bottom: 12px; gap: 14px; }
:deep(.el-table__row) { cursor: pointer; }
</style>
