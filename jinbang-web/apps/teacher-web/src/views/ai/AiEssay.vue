<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { http, postForm, unwrap, type ApiError, type Schemas } from '@jb/api'
import { EChart, PageHeader } from '@jb/ui'
import TaskResult from './TaskResult.vue'

/**
 * 作文批改（PRD 7.6）：语文 / 英语，文体、满分；一次可批多篇（文字或原图），每篇可登记学生，
 * 按学生建档追踪进步（➕P0）；空白或无法识别的篇不收费，可重试（AC-10）。
 */
interface Essay { studentName: string; text: string; storageKey?: string; fileName?: string }
interface Report { score: number; fullScore: number; overall: string; dimensions: Record<string, number>; highlights: string[]; problems: string[]; suggestions: string[] }
const form = ref({ subject: 'CHINESE', genre: '记叙文', fullScore: 60, title: '' })
const essays = ref<Essay[]>([{ studentName: '', text: '' }])
const busy = ref(false)
const task = ref<Schemas['TaskResult'] | null>(null)
const history = ref<Schemas['AiEssayRecord'][]>([])
const DIMS: Record<string, string> = { content: '内容项', expression: '表达项', structure: '结构', development: '发展等级' }

const reports = computed(() => (task.value?.items ?? []).map((i) => {
  try {
    return { item: i, report: i.status === 'SUCCESS' ? (JSON.parse(i.output ?? '{}') as Report) : null }
  } catch {
    return { item: i, report: null }
  }
}))

async function upload(e: Essay, ev: Event) {
  const f = (ev.target as HTMLInputElement).files?.[0]
  if (!f) return
  const form = new FormData()
  form.append('file', f)
  try {
    e.storageKey = await postForm<string>('/api/ai/upload', form)
    e.fileName = f.name
  } catch (err) {
    ElMessage.error((err as ApiError).message)
  }
}

async function correct() {
  const list = essays.value.filter((e) => e.text.trim() || e.storageKey)
  if (!list.length) return ElMessage.warning('请至少输入或上传一篇作文')
  busy.value = true
  try {
    task.value = await unwrap(http.POST('/api/ai/essay/correct', { body: { ...form.value, essays: list.map((e) => ({
      studentName: e.studentName || undefined, text: e.storageKey ? undefined : e.text, storageKey: e.storageKey,
    })) } as never }))
    loadHistory()
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  } finally {
    busy.value = false
  }
}

async function loadHistory() {
  history.value = await unwrap(http.GET('/api/ai/essay/history')).catch(() => [])
}

const trend = computed(() => ({
  tooltip: { trigger: 'axis' },
  xAxis: { type: 'category', data: history.value.map((h) => `${h.studentName ?? '未登记'} ${String(h.createdAt).slice(5, 10)}`) },
  yAxis: { type: 'value', name: '得分率 %', max: 100 },
  series: [{ type: 'line', smooth: true, data: history.value.map((h) => Math.round((Number(h.score) / Number(h.fullScore)) * 100)) }],
}))

onMounted(loadHistory)
</script>

<template>
  <div>
    <PageHeader title="作文批改" icon="文" color="var(--jb-c-ai)" desc="AI 给出得分与分项评语：表达、内容、结构、发展等级，以及亮点、问题和改进建议；按学生追踪进步" />
    <el-card>
      <el-form inline>
        <el-form-item label="科目"><el-radio-group v-model="form.subject"><el-radio-button value="CHINESE">语文</el-radio-button><el-radio-button value="ENGLISH">英语</el-radio-button></el-radio-group></el-form-item>
        <el-form-item label="文体"><el-input v-model="form.genre" style="width: 140px" /></el-form-item>
        <el-form-item label="满分"><el-input-number v-model="form.fullScore" :min="10" :max="150" /></el-form-item>
      </el-form>
      <el-input v-model="form.title" placeholder="作文题目与要求" />
      <div v-for="(e, i) in essays" :key="i" class="essay">
        <div class="jb-row">
          <b>第 {{ i + 1 }} 篇</b>
          <el-input v-model="e.studentName" placeholder="学生姓名（用于进步追踪）" size="small" style="width: 200px" />
          <label class="upload">上传原图<input type="file" accept="image/*" hidden @change="upload(e, $event)" /></label>
          <span v-if="e.fileName" class="jb-muted">{{ e.fileName }}</span>
          <el-button v-if="essays.length > 1" text type="danger" size="small" @click="essays.splice(i, 1)">移除</el-button>
        </div>
        <el-input v-if="!e.storageKey" v-model="e.text" type="textarea" :rows="4" placeholder="粘贴作文正文，或上传作文原图" />
      </div>
      <div class="jb-between ops">
        <el-button @click="essays.push({ studentName: '', text: '' })">＋ 再加一篇</el-button>
        <el-button type="primary" :loading="busy" data-test="essay-submit" @click="correct">开始批改（{{ essays.length }} 篇）</el-button>
      </div>
      <TaskResult v-if="task" :task="task" @updated="(t) => (task = t)" />
      <div class="reports">
        <el-card v-for="r in reports" :key="r.item.itemNo" shadow="never">
          <template #header><div class="jb-between"><b>{{ r.item.label }}</b><el-tag v-if="r.report" type="success" size="large">{{ r.report.score }} / {{ r.report.fullScore }}</el-tag><el-tag v-else type="info">{{ r.item.message }}</el-tag></div></template>
          <template v-if="r.report">
            <p>{{ r.report.overall }}</p>
            <div class="dims"><span v-for="(v, k) in r.report.dimensions" :key="k">{{ DIMS[k] ?? k }}：{{ v }}</span></div>
            <p><b>亮点</b>：{{ r.report.highlights?.join('；') }}</p>
            <p><b>问题</b>：{{ r.report.problems?.join('；') }}</p>
            <p><b>改进建议</b>：{{ r.report.suggestions?.join('；') }}</p>
          </template>
        </el-card>
      </div>
      <p class="jb-muted small">作文批阅结果基于图片识别文字生成，字迹潦草可能存在识别误差。</p>
    </el-card>
    <el-card header="进步追踪" class="hist">
      <EChart v-if="history.length" :option="trend" height="240px" />
      <el-empty v-else description="批改记录会显示在这里" :image-size="50" />
    </el-card>
  </div>
</template>

<style scoped>
.essay { margin-top: 12px; padding: 12px; border: 1px dashed var(--jb-border); border-radius: var(--jb-radius); display: flex; flex-direction: column; gap: 8px; }
.upload { cursor: pointer; color: var(--jb-brand); font-size: 13px; }
.ops { margin-top: 12px; }
.reports { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; }
.dims { display: flex; gap: 14px; flex-wrap: wrap; color: var(--jb-text-secondary); }
.small { font-size: 12px; }
.hist { margin-top: 16px; }
</style>
