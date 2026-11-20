<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http, postForm, unwrap, type ApiError, type Schemas } from '@jb/api'
import { useAuth } from '@jb/auth'
import { useMyClasses, useNames } from '../../composables/school'

/**
 * 作业 / 考试的过程面板（PRD 6.2–6.4）：收集情况与扫描上传、异常卷处理（指认学生、重复卷恢复、问题卷）、
 * 阅卷进度（去阅卷、AI 批阅、分配阅卷老师）、客观题作答矩阵。作业与考试共用。
 */
const props = defineProps<{ type: 'HOMEWORK' | 'EXAM'; id: string; classIds: string[] }>()
const router = useRouter()
const auth = useAuth()
const { className } = useMyClasses()
const names = useNames()
const tab = ref('collect')
const stats = ref<Schemas['BatchStats'] | null>(null)
const unsubmitted = ref<string[]>([])
const exceptions = ref<Schemas['ExAnswer'][]>([])
const duplicates = ref<Schemas['ExAnswer'][]>([])
const problems = ref<Schemas['ExProblemPaper'][]>([])
const progress = ref<Schemas['QuestionProgress'][]>([])
const matrix = ref<{ answerId: string; studentId: string; total: number; items: { no: string; answer: string; correct: boolean }[] }[]>([])
const students = ref<Schemas['EduStudent'][]>([])
const teachers = ref<Schemas['TeacherBrief'][]>([])
const images = ref<string[]>([])
const uploading = ref(false)
const aiBusy = ref('')
const fileInput = ref<HTMLInputElement>()
const path = { type: props.type, id: props.id }
const canScan = computed(() => auth.hasPerm(props.type === 'EXAM' ? 'exam:scan' : 'homework:scan'))

const TILES = computed(() => [
  ['应交', stats.value?.expected], ['已交', stats.value?.submitted], ['已批', stats.value?.marked], ['未交', stats.value?.unsubmitted],
  ['异常', stats.value?.exceptions], ['重复', stats.value?.duplicates], ['迟交', stats.value?.late], ['图片', stats.value?.images],
])

async function load() {
  stats.value = await unwrap(http.GET('/api/exam/{type}/{id}/stats', { params: { path } })).catch(() => null)
  if (tab.value === 'collect') {
    unsubmitted.value = (await unwrap(http.GET('/api/exam/{type}/{id}/unsubmitted', { params: { path } })).catch(() => [])).map(String)
    await names.loadNames(unsubmitted.value)
  } else if (tab.value === 'exceptions') {
    exceptions.value = await unwrap(http.GET('/api/exam/{type}/{id}/exceptions', { params: { path } })).catch(() => [])
    duplicates.value = await unwrap(http.GET('/api/exam/{type}/{id}/duplicates', { params: { path } })).catch(() => [])
    problems.value = await unwrap(http.GET('/api/exam/{type}/{id}/problems', { params: { path } })).catch(() => [])
    await names.loadNames(duplicates.value.map((d) => d.studentId))
    if (!students.value.length) {
      const all = await Promise.all(props.classIds.map((c) => unwrap(http.GET('/api/school/students', { params: { query: { classId: c } } })).catch(() => [])))
      students.value = all.flat()
    }
  } else if (tab.value === 'marking') {
    progress.value = await unwrap(http.GET('/api/exam/{type}/{id}/marking/progress', { params: { path } })).catch(() => [])
  } else if (tab.value === 'matrix') {
    matrix.value = (await unwrap(http.GET('/api/exam/{type}/{id}/marking/objective-matrix', { params: { path } })).catch(() => [])) as never
    await names.loadNames(matrix.value.map((m) => m.studentId))
  }
}

async function upload(ev: Event) {
  const files = Array.from((ev.target as HTMLInputElement).files ?? [])
  if (!files.length) return
  uploading.value = true
  try {
    const batch = await unwrap(http.POST('/api/exam/scan/batches', { body: { sourceType: props.type, sourceId: props.id, scanType: 'ANSWER_SHEET' } }))
    const fd = new FormData()
    files.forEach((f) => fd.append('files', f))
    await postForm(`/api/exam/scan/batches/${batch.id}/images`, fd)
    ElMessage.success(`已上传 ${files.length} 张，识别完成后自动进入阅卷`)
    load()
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  } finally {
    uploading.value = false
    if (fileInput.value) fileInput.value.value = ''
  }
}

async function showImages(a: Schemas['ExAnswer']) {
  images.value = await unwrap(http.GET('/api/exam/answers/{id}/images', { params: { path: { id: a.id! } } })).catch(() => [])
  if (!images.value.length) ElMessage.info('这份答卷没有图片（线上作答）')
}

async function assignStudent(a: Schemas['ExAnswer'], studentId: string) {
  try {
    await unwrap(http.POST('/api/exam/answers/{id}/assign', { params: { path: { id: a.id! } }, body: { studentId } }))
    ElMessage.success('已指认，答卷进入阅卷')
    load()
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

async function restore(a: Schemas['ExAnswer']) {
  await ElMessageBox.confirm('用这份答卷替换该学生当前的答卷？', '恢复重复卷')
  await unwrap(http.POST('/api/exam/answers/{id}/restore', { params: { path: { id: a.id! } } }))
  load()
}

async function handleProblem(p: Schemas['ExProblemPaper']) {
  await unwrap(http.POST('/api/exam/problems/{id}/handle', { params: { path: { id: p.id! } } }))
  load()
}

async function aiMark(itemNo: string) {
  aiBusy.value = itemNo
  try {
    const r = await unwrap(http.POST('/api/exam/{type}/{id}/marking/ai', { params: { path, query: { itemNo } } }))
    ElMessage.success(`AI 已批阅 ${r.done}/${r.total}，失败 ${r.failed}（失败不扣费，请人工批阅）`)
    load()
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  } finally {
    aiBusy.value = ''
  }
}

async function assignMarkers(itemNo: string) {
  if (!teachers.value.length) teachers.value = await unwrap(http.GET('/api/school/teachers/simple')).catch(() => [])
  const { value } = await ElMessageBox.prompt(`可选：${teachers.value.map((t) => t.name).join('、')}`, `第 ${itemNo} 题分配阅卷老师（姓名用逗号隔开）`)
  const ids = value.split(/[,，\s]+/).map((n) => teachers.value.find((t) => t.name === n)?.identityId).filter(Boolean) as string[]
  if (!ids.length) return ElMessage.warning('没有匹配到老师')
  await unwrap(http.POST('/api/exam/{type}/{id}/marking/assign', { params: { path }, body: { itemNo, identityIds: ids } }))
  ElMessage.success('已分配')
}

const matrixNos = computed(() => matrix.value[0]?.items.map((i) => i.no) ?? [])
defineExpose({ load })
onMounted(load)
</script>

<template>
  <div>
    <div class="tiles">
      <div v-for="[l, v] in TILES" :key="l" class="tile"><small>{{ l }}</small><b>{{ v ?? '—' }}</b></div>
    </div>
    <el-tabs v-model="tab" @tab-change="load">
      <el-tab-pane label="收集情况" name="collect">
        <div class="jb-between bar">
          <span class="jb-muted">纸质作答请用扫描仪扫描后上传（支持多张），系统识别学号与客观题后自动进入阅卷。</span>
          <template v-if="canScan">
            <el-button type="primary" :loading="uploading" @click="fileInput?.click()">上传扫描图片</el-button>
            <input ref="fileInput" type="file" accept="image/*,application/pdf" multiple hidden @change="upload" />
          </template>
        </div>
        <p><b>未交名单（{{ unsubmitted.length }}）</b></p>
        <div class="names"><el-tag v-for="s in unsubmitted" :key="s" effect="plain">{{ names.student(s) }}</el-tag><span v-if="!unsubmitted.length" class="jb-muted">全部已交</span></div>
      </el-tab-pane>

      <el-tab-pane :label="`异常处理（${(stats?.exceptions ?? 0) + (stats?.duplicates ?? 0)}）`" name="exceptions">
        <h4>无法匹配学生的答卷</h4>
        <el-table :data="exceptions" size="small" empty-text="没有异常卷">
          <el-table-column label="识别学号" width="120"><template #default="{ row }">{{ row.rawStudentNo || '未识别' }}</template></el-table-column>
          <el-table-column prop="exceptionReason" label="原因" />
          <el-table-column label="原卷" width="80"><template #default="{ row }"><el-button link @click="showImages(row)">查看</el-button></template></el-table-column>
          <el-table-column label="指认学生" width="260">
            <template #default="{ row }">
              <el-select size="small" filterable placeholder="选择学生" @change="(v: string) => assignStudent(row, v)">
                <el-option v-for="s in students" :key="s.id" :label="`${s.studentName}（${s.studentNo} · ${className(s.classId)}）`" :value="s.id!" />
              </el-select>
            </template>
          </el-table-column>
        </el-table>
        <h4>重复卷（同一学生多份，默认保留最新）</h4>
        <el-table :data="duplicates" size="small" empty-text="没有重复卷">
          <el-table-column label="学生"><template #default="{ row }">{{ names.student(row.studentId) }}</template></el-table-column>
          <el-table-column prop="submittedAt" label="提交时间" />
          <el-table-column width="160"><template #default="{ row }"><el-button link @click="showImages(row)">原卷</el-button><el-button link type="primary" @click="restore(row)">改用这份</el-button></template></el-table-column>
        </el-table>
        <h4>问题卷（阅卷老师上报）</h4>
        <el-table :data="problems" size="small" empty-text="没有问题卷">
          <el-table-column prop="itemNo" label="题号" width="70" />
          <el-table-column prop="problemType" label="类型" width="110" />
          <el-table-column prop="remark" label="说明" />
          <el-table-column width="90"><template #default="{ row }"><el-button v-if="row.problemStatus !== 'HANDLED'" link type="primary" @click="handleProblem(row)">已处理</el-button><span v-else class="jb-muted">已处理</span></template></el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="阅卷进度" name="marking">
        <el-table :data="progress" size="small" empty-text="还没有可阅的答卷" data-test="marking-progress">
          <el-table-column prop="itemNo" label="题号" width="70" />
          <el-table-column label="类型" width="80"><template #default="{ row }">{{ row.objective ? '客观' : '主观' }}</template></el-table-column>
          <el-table-column prop="fullScore" label="满分" width="70" />
          <el-table-column label="进度" min-width="200"><template #default="{ row }"><el-progress :percentage="row.total ? Math.round((row.marked / row.total) * 100) : 0" :format="() => `${row.marked}/${row.total}`" /></template></el-table-column>
          <el-table-column label="均分" width="80"><template #default="{ row }">{{ row.avg ?? '—' }}</template></el-table-column>
          <el-table-column label="待复核" width="80" prop="needsReview" />
          <el-table-column width="260">
            <template #default="{ row }">
              <template v-if="!row.objective">
                <el-button link type="primary" @click="router.push({ path: `/exam/marking/${props.type.toLowerCase()}/${props.id}`, query: { itemNo: row.itemNo } })">去阅卷</el-button>
                <el-button link :loading="aiBusy === row.itemNo" @click="aiMark(row.itemNo)">AI 批阅</el-button>
                <el-button v-if="props.type === 'EXAM'" link @click="assignMarkers(row.itemNo)">分配</el-button>
              </template>
              <span v-else class="jb-muted">自动判分</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="客观题矩阵" name="matrix">
        <el-table :data="matrix" size="small" max-height="520" empty-text="暂无数据">
          <el-table-column label="学生" width="100" fixed><template #default="{ row }">{{ names.student(row.studentId) }}</template></el-table-column>
          <el-table-column label="总分" width="70" fixed prop="total" />
          <el-table-column v-for="(no, i) in matrixNos" :key="no" :label="no" width="54" align="center">
            <template #default="{ row }"><span :class="row.items[i]?.correct ? 'ok' : 'bad'">{{ row.items[i]?.answer || '—' }}</span></template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog :model-value="images.length > 0" title="原卷" width="860px" top="4vh" @close="images = []">
      <div class="imgs"><img v-for="u in images" :key="u" :src="u" alt="答卷图片" /></div>
    </el-dialog>
  </div>
</template>

<style scoped>
.tiles { display: grid; grid-template-columns: repeat(8, 1fr); gap: 10px; margin-bottom: 8px; }
.tile { background: color-mix(in srgb, var(--jb-c-exam) 7%, var(--jb-surface)); border-radius: 10px; padding: 10px 12px; display: flex; flex-direction: column; }
.tile b { font-size: 20px; color: var(--jb-c-exam); }
.bar { margin-bottom: 12px; }
.names { display: flex; flex-wrap: wrap; gap: 6px; }
h4 { margin: 16px 0 8px; }
.ok { color: #2a8a52; }
.bad { color: #c4561e; font-weight: 700; }
.imgs { display: flex; flex-direction: column; gap: 10px; max-height: 78vh; overflow: auto; }
.imgs img { width: 100%; border: 1px solid var(--jb-border); }
</style>
