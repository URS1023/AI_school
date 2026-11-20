<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { http, unwrap, type ApiError, type Schemas } from '@jb/api'
import { useAuth } from '@jb/auth'
import { PageHeader, fmtTime, useDict } from '@jb/ui'
import { EXAM_STATUS, useMyClasses } from '../../composables/school'

/** 考试（PRD 6.3）：按状态筛选；创建考试（年级、学科、答题卡、班级、是否需要成绩审核）。 */
const router = useRouter()
const auth = useAuth()
const { classes } = useMyClasses()
const { items: subjects, label: subjectLabel } = useDict('sys_subject')
const status = ref('')
const exams = ref<Schemas['ExExam'][]>([])
const loading = ref(false)
const open = ref(false)
const sheets = ref<Schemas['ExSheet'][]>([])
const form = ref({ name: '', examType: 'SCHOOL', gradeId: '', subject: 'MATH', sheetId: '', examTime: '', scoreAuditRequired: false, classIds: [] as string[] })
const grades = computed(() => [...new Map(classes.value.map((c) => [c.gradeId, c.gradeName])).entries()])
const gradeClasses = computed(() => classes.value.filter((c) => c.gradeId === form.value.gradeId))
const GROUPS: [string, string[]][] = [['全部', []], ['准备中', ['DRAFT', 'WAIT_SCAN', 'SCANNING']], ['阅卷中', ['MARKING', 'PAUSED']], ['待发布', ['MARKED', 'SCORE_AUDIT', 'AUDITED']], ['已发布', ['PUBLISHED', 'LEARNING_PUBLISHED', 'ARCHIVED']]]
const group = ref('全部')
const shown = computed(() => {
  const g = GROUPS.find(([n]) => n === group.value)?.[1] ?? []
  return g.length ? exams.value.filter((e) => g.includes(e.examStatus ?? '')) : exams.value
})

async function load() {
  loading.value = true
  try {
    exams.value = await unwrap(http.GET('/api/exam/exams', { params: { query: { status: status.value || undefined } } }))
  } finally {
    loading.value = false
  }
}

async function openCreate() {
  sheets.value = await unwrap(http.GET('/api/exam/sheets')).catch(() => [])
  form.value.gradeId = grades.value[0]?.[0] ?? ''
  form.value.classIds = gradeClasses.value.map((c) => c.classId!)
  open.value = true
}

async function create() {
  const f = form.value
  if (!f.name || !f.gradeId) return ElMessage.warning('请填写考试名称并选择年级')
  try {
    const e = await unwrap(http.POST('/api/exam/exams', { body: { ...f, sheetId: f.sheetId || undefined, examTime: f.examTime || undefined } }))
    open.value = false
    router.push(`/exam/exams/${e.id}`)
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader title="考试" icon="考" color="var(--jb-c-exam)" desc="从准备、扫描、阅卷到成绩审核与发布的完整流程；成绩可分批发布、撤回更正">
      <el-button v-if="auth.hasPerm('exam:create')" type="primary" data-test="new-exam" @click="openCreate">创建考试</el-button>
    </PageHeader>
    <el-radio-group v-model="group" class="groups">
      <el-radio-button v-for="[n] in GROUPS" :key="n" :value="n">{{ n }}</el-radio-button>
    </el-radio-group>
    <el-card v-loading="loading">
      <el-table :data="shown" empty-text="还没有考试" data-test="exam-list" @row-click="(r: Schemas['ExExam']) => router.push(`/exam/exams/${r.id}`)">
        <el-table-column prop="examName" label="考试名称" min-width="200" />
        <el-table-column label="学科" width="90"><template #default="{ row }">{{ subjectLabel(row.subject) }}</template></el-table-column>
        <el-table-column label="类型" width="90"><template #default="{ row }">{{ { SCHOOL: '校内考试', UNION: '联考', CLASS: '班级测验' }[row.examType as string] ?? row.examType }}</template></el-table-column>
        <el-table-column label="考试时间" width="160"><template #default="{ row }">{{ fmtTime(row.examTime) }}</template></el-table-column>
        <el-table-column label="状态" width="140"><template #default="{ row }"><el-tag effect="plain">{{ EXAM_STATUS[row.examStatus] }}</el-tag></template></el-table-column>
        <el-table-column label="成绩审核" width="90"><template #default="{ row }">{{ row.scoreAuditRequired ? '需要' : '—' }}</template></el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="open" title="创建考试" width="600px">
      <el-form label-width="90px">
        <el-form-item label="考试名称"><el-input v-model="form.name" placeholder="如：高一上学期期中考试" data-test="exam-name" /></el-form-item>
        <el-form-item label="类型"><el-radio-group v-model="form.examType"><el-radio-button value="SCHOOL">校内考试</el-radio-button><el-radio-button value="CLASS">班级测验</el-radio-button></el-radio-group></el-form-item>
        <el-form-item label="年级">
          <el-select v-model="form.gradeId" @change="form.classIds = gradeClasses.map((c) => c.classId!)"><el-option v-for="[gid, gname] in grades" :key="gid" :label="gname" :value="gid!" /></el-select>
        </el-form-item>
        <el-form-item label="学科"><el-select v-model="form.subject"><el-option v-for="s in subjects" :key="s.dictValue" :label="s.dictLabel" :value="s.dictValue!" /></el-select></el-form-item>
        <el-form-item label="答题卡"><el-select v-model="form.sheetId" clearable filterable placeholder="可稍后绑定" style="width: 100%"><el-option v-for="s in sheets" :key="s.id" :label="s.sheetName" :value="s.id!" /></el-select></el-form-item>
        <el-form-item label="参考班级"><el-checkbox-group v-model="form.classIds"><el-checkbox v-for="c in gradeClasses" :key="c.classId" :value="c.classId!">{{ c.className }}</el-checkbox></el-checkbox-group></el-form-item>
        <el-form-item label="考试时间"><el-date-picker v-model="form.examTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item>
        <el-form-item label="成绩审核"><el-switch v-model="form.scoreAuditRequired" active-text="发布前需要审核" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="open = false">取消</el-button><el-button type="primary" data-test="exam-create" @click="create">创建</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.groups { margin-bottom: 14px; }
:deep(.el-table__row) { cursor: pointer; }
</style>
