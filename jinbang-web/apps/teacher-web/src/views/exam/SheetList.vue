<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { http, unwrap, type ApiError, type Schemas } from '@jb/api'
import { PageHeader, fmtTime, useDict } from '@jb/ui'

/**
 * 答题卡（PRD 6.1）：从试卷生成，或按题量快速生成（客观题 + 主观题）。
 * 答题卡被作业 / 考试使用后会冻结，再修改自动生成新版本，不影响已布置的作业。
 */
const router = useRouter()
const { items: subjects, label: subjectLabel } = useDict('sys_subject')
const sheets = ref<Schemas['ExSheet'][]>([])
const loading = ref(false)
const open = ref(false)
const form = ref({ name: '', subject: 'MATH', single: 10, singleScore: 4, options: 4, blank: 4, blankScore: 5, answer: 4, answerScore: 12, essay: false })

async function load() {
  loading.value = true
  try {
    sheets.value = await unwrap(http.GET('/api/exam/sheets'))
  } finally {
    loading.value = false
  }
}

async function create() {
  const f = form.value
  if (!f.name.trim()) return ElMessage.warning('请填写答题卡名称')
  const blocks: Schemas['Block'][] = []
  let n = 1
  for (let i = 0; i < f.single; i++) blocks.push({ no: String(n++), type: 'SINGLE', objective: true, fullScore: f.singleScore, options: f.options, answer: '' })
  for (let i = 0; i < f.blank; i++) blocks.push({ no: String(n++), type: 'BLANK', objective: false, fullScore: f.blankScore })
  for (let i = 0; i < f.answer; i++) blocks.push({ no: String(n++), type: 'ANSWER', objective: false, fullScore: f.answerScore })
  if (f.essay) blocks.push({ no: String(n++), type: 'ESSAY', objective: false, fullScore: 60, minWords: 800 })
  try {
    const v = await unwrap(http.POST('/api/exam/sheets', { body: { name: f.name, subject: f.subject, blocks } }))
    open.value = false
    router.push(`/exam/sheets/${v.sheet?.id}`)
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader title="答题卡" icon="卡" color="var(--jb-c-exam)" desc="题卡分离、可扫描识别的答题卡；从「我的试卷」一键生成，或按题量快速生成">
      <el-button @click="router.push('/resources/papers')">从试卷生成</el-button>
      <el-button type="primary" data-test="new-sheet" @click="open = true">快速新建</el-button>
    </PageHeader>
    <div v-loading="loading" class="grid">
      <el-empty v-if="!loading && !sheets.length" description="还没有答题卡" />
      <a v-for="s in sheets" :key="s.id" class="sheet" data-test="sheet-item" @click="router.push(`/exam/sheets/${s.id}`)">
        <div class="sheet__paper"><i v-for="k in 6" :key="k" /></div>
        <b class="jb-ellipsis">{{ s.sheetName }}</b>
        <span class="jb-muted">{{ subjectLabel(s.subject) }}，{{ s.paperSize }}，v{{ s.currentVersion }}</span>
        <small class="jb-muted">{{ fmtTime(s.updatedAt) }}</small>
      </a>
    </div>

    <el-dialog v-model="open" title="快速新建答题卡" width="560px">
      <el-form label-width="90px">
        <el-form-item label="名称"><el-input v-model="form.name" placeholder="如：高一数学周测（一）" data-test="sheet-name" /></el-form-item>
        <el-form-item label="学科"><el-select v-model="form.subject"><el-option v-for="s in subjects" :key="s.dictValue" :label="s.dictLabel" :value="s.dictValue!" /></el-select></el-form-item>
        <el-form-item label="选择题"><el-input-number v-model="form.single" :min="0" :max="60" /> 题，每题 <el-input-number v-model="form.singleScore" :min="1" :max="20" size="small" /> 分，<el-input-number v-model="form.options" :min="2" :max="8" size="small" /> 个选项</el-form-item>
        <el-form-item label="填空题"><el-input-number v-model="form.blank" :min="0" :max="30" /> 题，每题 <el-input-number v-model="form.blankScore" :min="1" :max="20" size="small" /> 分</el-form-item>
        <el-form-item label="解答题"><el-input-number v-model="form.answer" :min="0" :max="20" /> 题，每题 <el-input-number v-model="form.answerScore" :min="1" :max="60" size="small" /> 分</el-form-item>
        <el-form-item label="作文"><el-switch v-model="form.essay" active-text="带作文格（60 分）" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="open = false">取消</el-button><el-button type="primary" data-test="sheet-create" @click="create">创建并编辑</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 16px; min-height: 120px; }
.sheet { background: var(--jb-surface); border: 1px solid var(--jb-border); border-radius: var(--jb-radius); padding: 14px; display: flex; flex-direction: column; gap: 4px; cursor: pointer; transition: transform var(--dur-short) var(--ease-out), box-shadow var(--dur-short) var(--ease-out); }
.sheet:hover { border-color: var(--jb-c-exam); transform: translateY(-1px); box-shadow: var(--edge-stock), var(--cast-stock-lift); }
.sheet__paper { height: 120px; border-radius: 6px; background: var(--color-paper); border: 1px dashed var(--color-rule-2); padding: 12px; display: flex; flex-direction: column; gap: 9px; margin-bottom: 8px; }
.sheet__paper i { height: 6px; border-radius: 3px; background: var(--color-paper-3); }
.sheet__paper i:nth-child(odd) { width: 70%; }
</style>
