<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { http, unwrap } from '@jb/api'
import { PageHeader, fmtTime, useDict } from '@jb/ui'
import { EXAM_STATUS, HW_STATUS } from '../../composables/school'

/** 作业 / 考试诊断（PRD 6.5）：已批阅完成的作业和考试列表，进入单次学情报告。 */
interface Row { type: 'homework' | 'exam'; id: string; title: string; subject?: string; status: string; time?: string }
const router = useRouter()
const { label: subjectLabel } = useDict('sys_subject')
const rows = ref<Row[]>([])
const kind = ref<'all' | 'homework' | 'exam'>('all')
const loading = ref(false)
const shown = computed(() => (kind.value === 'all' ? rows.value : rows.value.filter((r) => r.type === kind.value)))
const HW_DONE = ['MARKED', 'ARCHIVED']
const EXAM_DONE = ['MARKED', 'SCORE_AUDIT', 'AUDITED', 'PUBLISHED', 'LEARNING_PUBLISHED', 'ARCHIVED']

onMounted(async () => {
  loading.value = true
  try {
    const [hws, exams] = await Promise.all([
      unwrap(http.GET('/api/exam/homeworks', { params: { query: {} } })).catch(() => []),
      unwrap(http.GET('/api/exam/exams', { params: { query: {} } })).catch(() => []),
    ])
    rows.value = [
      ...hws.filter((c) => HW_DONE.includes(c.homework?.hwStatus ?? '')).map((c) => ({
        type: 'homework' as const, id: c.homework!.id!, title: c.homework!.title!, subject: c.homework!.subject, status: HW_STATUS[c.homework!.hwStatus!], time: c.homework!.markedAt ?? c.homework!.createdAt,
      })),
      ...exams.filter((e) => EXAM_DONE.includes(e.examStatus ?? '')).map((e) => ({
        type: 'exam' as const, id: e.id!, title: e.examName!, subject: e.subject, status: EXAM_STATUS[e.examStatus!], time: e.examTime ?? e.createdAt,
      })),
    ].sort((a, b) => String(b.time).localeCompare(String(a.time)))
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div>
    <PageHeader title="作业考试诊断" icon="诊" color="var(--jb-c-learning)" desc="每次作业、考试批阅完成后自动生成学情：分数段、逐题得分率、薄弱题与学生排名">
      <el-radio-group v-model="kind"><el-radio-button value="all">全部</el-radio-button><el-radio-button value="homework">作业</el-radio-button><el-radio-button value="exam">考试</el-radio-button></el-radio-group>
    </PageHeader>
    <el-card v-loading="loading">
      <el-table :data="shown" empty-text="还没有批阅完成的作业或考试" data-test="report-list" @row-click="(r: Row) => router.push(`/learning/reports/${r.type}/${r.id}`)">
        <el-table-column label="类型" width="80"><template #default="{ row }"><el-tag size="small" :type="row.type === 'exam' ? 'danger' : 'primary'">{{ row.type === 'exam' ? '考试' : '作业' }}</el-tag></template></el-table-column>
        <el-table-column prop="title" label="名称" min-width="220" />
        <el-table-column label="学科" width="90"><template #default="{ row }">{{ subjectLabel(row.subject) }}</template></el-table-column>
        <el-table-column prop="status" label="状态" width="140" />
        <el-table-column label="时间" width="160"><template #default="{ row }">{{ fmtTime(row.time) }}</template></el-table-column>
        <el-table-column width="100"><template #default><el-button link type="primary">查看学情</el-button></template></el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
:deep(.el-table__row) { cursor: pointer; }
</style>
