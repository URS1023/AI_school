<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http, unwrap, type ApiError, type Schemas } from '@jb/api'
import { useAuth } from '@jb/auth'
import { PageHeader, fmtTime, useDict } from '@jb/ui'
import { EXAM_STATUS, useMyClasses, useNames } from '../../composables/school'
import SourcePanel from './SourcePanel.vue'

/**
 * 考试详情（PRD 6.3、11.5）：流程推进按钮随状态出现；考生管理（缺考 / 免考 / 作废）；
 * 成绩可按班级分批发布、撤回（撤回后学生端不可见，再次发布通知更正）。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuth()
const { classes, className } = useMyClasses()
const names = useNames()
const { label: subjectLabel } = useDict('sys_subject')
const exam = ref<Schemas['ExExam'] | null>(null)
const examinees = ref<Schemas['ExExaminee'][]>([])
const sheets = ref<Schemas['ExSheet'][]>([])
const tab = ref('process')
const panel = ref<InstanceType<typeof SourcePanel>>()
const id = computed(() => route.params.id as string)
const st = computed(() => exam.value?.examStatus ?? '')
const classIds = computed(() => [...new Set(examinees.value.map((e) => e.classId!))])
const ORDER = ['DRAFT', 'WAIT_SCAN', 'SCANNING', 'MARKING', 'MARKED', 'PUBLISHED', 'LEARNING_PUBLISHED']
const stepIndex = computed(() => {
  const map: Record<string, string> = { PAUSED: 'MARKING', SCORE_AUDIT: 'MARKED', AUDITED: 'MARKED', ARCHIVED: 'LEARNING_PUBLISHED' }
  return ORDER.indexOf(map[st.value] ?? st.value)
})

interface Act { action: string; label: string; type?: 'primary' | 'success' | 'warning' | 'danger'; confirm: string }
const actions = computed<Act[]>(() => {
  const a: Record<string, Act[]> = {
    DRAFT: [{ action: 'READY', label: '完成准备', type: 'primary', confirm: '确认考生与答题卡已就绪？之后可开始扫描。' }],
    SCANNING: [{ action: 'COMPLETE_SCAN', label: '完成扫描', type: 'primary', confirm: '确认全部扫描完成？进入阅卷。' }],
    MARKING: [{ action: 'PAUSE', label: '暂停阅卷', confirm: '暂停后阅卷老师将无法继续批阅。' }, { action: 'FINISH_MARK', label: '阅卷完成', type: 'success', confirm: '确认阅卷完成？生成临时成绩。' }],
    PAUSED: [{ action: 'RESUME', label: '恢复阅卷', type: 'primary', confirm: '恢复阅卷？' }],
    MARKED: exam.value?.scoreAuditRequired
      ? [{ action: 'REOPEN', label: '重新阅卷', confirm: '重新打开阅卷？' }, { action: 'SUBMIT_AUDIT', label: '提交成绩审核', type: 'primary', confirm: '提交给审核人审核成绩？' }]
      : [{ action: 'REOPEN', label: '重新阅卷', confirm: '重新打开阅卷？' }],
    SCORE_AUDIT: [{ action: 'REJECT', label: '驳回', type: 'danger', confirm: '驳回成绩，退回阅卷完成状态？' }, { action: 'APPROVE', label: '审核通过', type: 'success', confirm: '确认成绩无误？' }],
    PUBLISHED: [{ action: 'PUBLISH_LEARNING', label: '发布学情', type: 'primary', confirm: '向学生、家长发布学情分析？' }],
    LEARNING_PUBLISHED: [{ action: 'WITHDRAW_LEARNING', label: '撤回学情', confirm: '撤回已发布的学情？' }, { action: 'ARCHIVE', label: '归档', confirm: '归档后不可再修改，确定？' }],
  }
  return a[st.value] ?? []
})
const canPublish = computed(() => ['MARKED', 'AUDITED', 'PUBLISHED'].includes(st.value) && !(st.value === 'MARKED' && exam.value?.scoreAuditRequired))

async function load() {
  exam.value = await unwrap(http.GET('/api/exam/exams/{id}', { params: { path: { id: id.value } } }))
  examinees.value = await unwrap(http.GET('/api/exam/exams/{id}/examinees', { params: { path: { id: id.value } } })).catch(() => [])
  await names.loadNames(examinees.value.map((e) => e.studentId))
}

async function flow(a: Act) {
  try {
    await ElMessageBox.confirm(a.confirm, a.label)
    exam.value = await unwrap(http.POST('/api/exam/exams/{id}/flow', { params: { path: { id: id.value } }, body: { action: a.action } }))
    ElMessage.success(`已${a.label}`)
    panel.value?.load()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error((e as ApiError).message)
  }
}

async function bindSheet() {
  sheets.value = await unwrap(http.GET('/api/exam/sheets')).catch(() => [])
  const { value } = await ElMessageBox.prompt(sheets.value.map((s) => s.sheetName).join(' / '), '输入要绑定的答题卡名称')
  const s = sheets.value.find((x) => x.sheetName === value.trim())
  if (!s) return ElMessage.warning('没有找到这个答题卡')
  exam.value = await unwrap(http.POST('/api/exam/exams/{id}/sheet', { params: { path: { id: id.value } }, body: { sheetId: s.id! } }))
}

async function publish() {
  const opts = classIds.value.map((c) => className(c)).join('、')
  const { value } = await ElMessageBox.prompt(`参考班级：${opts}。留空 = 全部班级；也可只填部分班级名（逗号隔开）分批发布。`, '发布成绩', { inputValue: '' })
  const picked = value.trim() ? classIds.value.filter((c) => value.split(/[,，\s]+/).includes(className(c))) : classIds.value
  try {
    exam.value = await unwrap(http.POST('/api/exam/exams/{id}/publish', { params: { path: { id: id.value } }, body: { classIds: picked } }))
    ElMessage.success(`已向 ${picked.length} 个班发布成绩`)
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

async function withdraw() {
  await ElMessageBox.confirm('撤回后学生、家长将看不到成绩；更正后再次发布会通知「成绩已更正」。', '撤回成绩', { type: 'warning' })
  exam.value = await unwrap(http.POST('/api/exam/exams/{id}/withdraw', { params: { path: { id: id.value } } }))
}

async function setStatus(e: Schemas['ExExaminee'], status: string) {
  try {
    await unwrap(http.POST('/api/exam/exams/{id}/examinees/status', { params: { path: { id: id.value } }, body: { studentId: e.studentId!, status } }))
    e.examineeStatus = status
  } catch (err) {
    ElMessage.error((err as ApiError).message)
  }
}

async function importClasses() {
  const ids = classes.value.filter((c) => c.gradeId === exam.value?.gradeId).map((c) => c.classId!)
  const n = await unwrap(http.POST('/api/exam/exams/{id}/examinees/import-classes', { params: { path: { id: id.value } }, body: { classIds: ids } }))
  ElMessage.success(`已导入 ${n} 名考生`)
  load()
}

onMounted(load)
</script>

<template>
  <div v-if="exam">
    <PageHeader :title="exam.examName!" icon="考" color="var(--jb-c-exam)" :desc="`${subjectLabel(exam.subject)} · ${fmtTime(exam.examTime) || '未定时间'} · ${EXAM_STATUS[st]}`">
      <el-button v-if="st === 'DRAFT' && !exam.sheetVersionId" @click="bindSheet">绑定答题卡</el-button>
      <template v-if="auth.hasPerm('exam:create') || auth.hasPerm('exam:publish')">
        <el-button v-for="a in actions" :key="a.action" :type="a.type" :data-test="`flow-${a.action}`" @click="flow(a)">{{ a.label }}</el-button>
        <el-button v-if="canPublish" type="success" data-test="publish" @click="publish">{{ st === 'PUBLISHED' ? '继续发布其他班' : '发布成绩' }}</el-button>
        <el-button v-if="st === 'PUBLISHED'" type="danger" plain @click="withdraw">撤回成绩</el-button>
      </template>
      <el-button v-if="stepIndex >= 4" type="primary" plain @click="router.push(`/learning/reports/exam/${exam.id}`)">考试学情</el-button>
    </PageHeader>
    <el-card>
      <el-steps :active="stepIndex" finish-status="success" simple class="steps">
        <el-step v-for="s in ORDER" :key="s" :title="EXAM_STATUS[s]" />
      </el-steps>
      <el-tabs v-model="tab">
        <el-tab-pane label="扫描与阅卷" name="process">
          <SourcePanel v-if="classIds.length" ref="panel" type="EXAM" :id="exam.id!" :class-ids="classIds" />
          <el-empty v-else description="还没有考生"><el-button type="primary" @click="importClasses">按年级导入考生</el-button></el-empty>
        </el-tab-pane>
        <el-tab-pane :label="`考生（${examinees.length}）`" name="examinees">
          <el-table :data="examinees" size="small" max-height="560">
            <el-table-column label="学生"><template #default="{ row }">{{ names.student(row.studentId) }}</template></el-table-column>
            <el-table-column label="班级"><template #default="{ row }">{{ className(row.classId) }}</template></el-table-column>
            <el-table-column label="状态" width="180">
              <template #default="{ row }">
                <el-select :model-value="row.examineeStatus" size="small" @change="(v: string) => setStatus(row, v)">
                  <el-option label="正常" value="NORMAL" /><el-option label="缺考" value="ABSENT" /><el-option label="免考" value="EXEMPT" /><el-option label="作废" value="VOID" />
                </el-select>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<style scoped>
.steps { margin-bottom: 10px; }
</style>
