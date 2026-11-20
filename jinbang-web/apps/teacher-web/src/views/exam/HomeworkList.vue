<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { http, unwrap, type ApiError, type Schemas } from '@jb/api'
import { useAuth } from '@jb/auth'
import { PageHeader, fmtTime, useDict } from '@jb/ui'
import { HW_STATUS, useMyClasses } from '../../composables/school'

/**
 * 作业（PRD 6.2）：按班级查看作业卡片（收集与批阅进度）；布置作业：纸质（扫描）/ 线上作答，选择答题卡、班级、截止时间。
 */
const router = useRouter()
const auth = useAuth()
const { classes } = useMyClasses()
const { items: subjects, label: subjectLabel } = useDict('sys_subject')
const classId = ref<string>('')
const cards = ref<Schemas['HomeworkCard'][]>([])
const loading = ref(false)
const open = ref(false)
const sheets = ref<Schemas['ExSheet'][]>([])
const form = ref({ title: '', mode: 'OFFLINE', scanType: 'ANSWER_SHEET', subject: 'MATH', sheetId: '', classIds: [] as string[], deadline: '', allowLate: true, allowWithdraw: false })
const STATUS_TYPE: Record<string, 'success' | 'warning' | 'info' | 'primary'> = { MARKED: 'success', MARKING: 'warning', ARCHIVED: 'info' }
const pct = (a?: number, b?: number) => (b ? Math.round(((a ?? 0) / b) * 100) : 0)
const empty = computed(() => !loading.value && !cards.value.length)

async function load() {
  loading.value = true
  try {
    cards.value = await unwrap(http.GET('/api/exam/homeworks', { params: { query: { classId: classId.value || undefined } } }))
  } finally {
    loading.value = false
  }
}

async function openAssign() {
  sheets.value = await unwrap(http.GET('/api/exam/sheets')).catch(() => [])
  form.value.classIds = classId.value ? [classId.value] : []
  open.value = true
}

async function assign() {
  const f = form.value
  if (!f.title || !f.classIds.length) return ElMessage.warning('请填写作业名称并选择班级')
  try {
    const h = await unwrap(http.POST('/api/exam/homeworks', { body: {
      title: f.title, mode: f.mode, scanType: f.scanType, subject: f.subject, sheetId: f.sheetId || undefined, classIds: f.classIds,
      deadline: f.deadline || undefined, allowLate: f.allowLate, allowWithdraw: f.allowWithdraw,
    } }))
    open.value = false
    ElMessage.success('作业已布置')
    router.push(`/exam/homework/${h.id}`)
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader title="作业" icon="作" color="var(--jb-c-exam)" desc="布置纸质或线上作业，扫描后自动判客观题；批阅完成即生成作业学情">
      <el-select v-model="classId" clearable placeholder="全部班级" style="width: 160px" @change="load">
        <el-option v-for="c in classes" :key="c.classId" :label="c.className" :value="c.classId!" />
      </el-select>
      <el-button v-if="auth.hasPerm('homework:assign')" type="primary" data-test="assign-hw" @click="openAssign">布置作业</el-button>
    </PageHeader>
    <div v-loading="loading" class="cards">
      <el-empty v-if="empty" description="还没有作业，点右上角「布置作业」" />
      <a v-for="c in cards" :key="c.homework?.id" class="card" data-test="hw-card" @click="router.push(`/exam/homework/${c.homework?.id}`)">
        <div class="jb-between">
          <b class="jb-ellipsis">{{ c.homework?.title }}</b>
          <el-tag size="small" :type="STATUS_TYPE[c.homework?.hwStatus ?? ''] ?? 'primary'">{{ HW_STATUS[c.homework?.hwStatus ?? ''] }}</el-tag>
        </div>
        <span class="jb-muted">{{ subjectLabel(c.homework?.subject) }} · {{ c.homework?.hwMode === 'ONLINE' ? '线上作答' : '纸质扫描' }} · 截止 {{ fmtTime(c.homework?.deadline) || '不限' }}</span>
        <div class="bars">
          <span>已交 {{ c.stats?.submitted }}/{{ c.stats?.expected }}</span><el-progress :percentage="pct(c.stats?.submitted, c.stats?.expected)" :show-text="false" :stroke-width="6" />
          <span>已批 {{ c.stats?.marked }}/{{ c.stats?.submitted }}</span><el-progress :percentage="pct(c.stats?.marked, c.stats?.submitted)" :show-text="false" :stroke-width="6" status="success" />
        </div>
        <small v-if="c.stats?.exceptions" class="warn">{{ c.stats.exceptions }} 份异常卷待处理</small>
      </a>
    </div>

    <el-dialog v-model="open" title="布置作业" width="600px">
      <el-form label-width="90px">
        <el-form-item label="作业名称"><el-input v-model="form.title" data-test="hw-title" /></el-form-item>
        <el-form-item label="作答方式"><el-radio-group v-model="form.mode"><el-radio-button value="OFFLINE">纸质（扫描）</el-radio-button><el-radio-button value="ONLINE">线上作答</el-radio-button></el-radio-group></el-form-item>
        <el-form-item v-if="form.mode === 'OFFLINE'" label="扫描内容"><el-radio-group v-model="form.scanType"><el-radio value="ANSWER_SHEET">答题卡</el-radio><el-radio value="WORKBOOK">教辅（免答题卡）</el-radio></el-radio-group></el-form-item>
        <el-form-item label="学科"><el-select v-model="form.subject"><el-option v-for="s in subjects" :key="s.dictValue" :label="s.dictLabel" :value="s.dictValue!" /></el-select></el-form-item>
        <el-form-item label="答题卡">
          <el-select v-model="form.sheetId" clearable filterable placeholder="选择答题卡" style="width: 100%" data-test="hw-sheet">
            <el-option v-for="s in sheets" :key="s.id" :label="s.sheetName" :value="s.id!" />
          </el-select>
        </el-form-item>
        <el-form-item label="班级"><el-checkbox-group v-model="form.classIds"><el-checkbox v-for="c in classes" :key="c.classId" :value="c.classId!">{{ c.className }}</el-checkbox></el-checkbox-group></el-form-item>
        <el-form-item label="截止时间"><el-date-picker v-model="form.deadline" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="不设置则不限" /></el-form-item>
        <el-form-item label="规则"><el-checkbox v-model="form.allowLate">允许迟交</el-checkbox><el-checkbox v-model="form.allowWithdraw">允许学生撤回重交</el-checkbox></el-form-item>
      </el-form>
      <template #footer><el-button @click="open = false">取消</el-button><el-button type="primary" data-test="hw-submit" @click="assign">布置</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 14px; min-height: 120px; }
.card { background: var(--jb-surface); border-radius: var(--jb-radius); border-left: 4px solid var(--jb-c-exam); padding: 14px 16px; display: flex; flex-direction: column; gap: 8px; cursor: pointer; box-shadow: var(--jb-shadow); }
.card:hover { transform: translateY(-2px); }
.card .jb-muted { font-size: 12px; }
.bars { display: grid; grid-template-columns: 110px 1fr; gap: 4px 8px; align-items: center; font-size: 12px; }
.warn { color: #c4561e; }
</style>
