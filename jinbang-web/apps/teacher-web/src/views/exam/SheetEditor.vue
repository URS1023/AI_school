<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { http, unwrap, type ApiError, type Schemas } from '@jb/api'
import { PageHeader, fmtTime, openLink, useDict } from '@jb/ui'
import { BLOCK_TYPES } from '../../composables/school'

/**
 * 答题卡编辑（PRD 6.1）：左侧题块表（题号、题型、分值、选项数、标准答案），右侧版面预览；
 * 保存后若答题卡已被使用则生成新版本。生成 PDF 时同时生成识别区域坐标，供扫描识别使用。
 */
type Block = Schemas['Block']
const route = useRoute()
const { label: subjectLabel } = useDict('sys_subject')
const view = ref<Schemas['SheetView'] | null>(null)
const blocks = ref<Block[]>([])
const versions = ref<Schemas['ExSheetVersion'][]>([])
const dirty = ref(false)
const saving = ref(false)
const pdf = ref<Schemas['SheetPdf'] | null>(null)
const id = computed(() => route.params.id as string)
const total = computed(() => blocks.value.reduce((s, b) => s + Number(b.fullScore ?? 0), 0))
const objective = computed(() => blocks.value.filter((b) => b.objective))
const subjective = computed(() => blocks.value.filter((b) => !b.objective))

async function load() {
  view.value = await unwrap(http.GET('/api/exam/sheets/{id}', { params: { path: { id: id.value } } }))
  blocks.value = (view.value.blocks ?? []).map((b) => ({ ...b }))
  versions.value = await unwrap(http.GET('/api/exam/sheets/{id}/versions', { params: { path: { id: id.value } } })).catch(() => [])
  dirty.value = false
}

function changeType(b: Block) {
  b.objective = ['SINGLE', 'MULTI', 'JUDGE'].includes(b.type ?? '')
  if (b.type === 'JUDGE') b.options = 2
  if (b.objective && !b.options) b.options = 4
  dirty.value = true
}

function add(type: string) {
  const objective = ['SINGLE', 'MULTI', 'JUDGE'].includes(type)
  blocks.value.push({ no: String(blocks.value.length + 1), type, objective, fullScore: objective ? 4 : 10, options: objective ? (type === 'JUDGE' ? 2 : 4) : undefined, answer: '' })
  dirty.value = true
}

function renumber() {
  blocks.value.forEach((b, i) => (b.no = String(i + 1)))
  dirty.value = true
}

async function save() {
  saving.value = true
  try {
    const before = view.value?.version?.versionNo
    view.value = await unwrap(http.PUT('/api/exam/sheets/{id}/blocks', { params: { path: { id: id.value } }, body: { blocks: blocks.value } }))
    ElMessage.success(view.value.version?.versionNo !== before ? `答题卡已被使用，已保存为新版本 v${view.value.version?.versionNo}` : '已保存')
    pdf.value = null
    load()
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  } finally {
    saving.value = false
  }
}

async function makePdf() {
  if (dirty.value) await save()
  try {
    pdf.value = await unwrap(http.POST('/api/exam/sheets/{id}/pdf', { params: { path: { id: id.value } } }))
    openLink(pdf.value.url!)
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

const letters = (n?: number) => 'ABCDEFGH'.slice(0, n ?? 4).split('')
onMounted(load)
</script>

<template>
  <div>
    <PageHeader :title="view?.sheet?.sheetName ?? '答题卡'" icon="卡" color="var(--jb-c-exam)"
                :desc="`${subjectLabel(view?.sheet?.subject)}，${view?.sheet?.paperSize}，当前 v${view?.version?.versionNo ?? 1}${view?.version?.frozen ? '（已被使用，修改会生成新版本）' : ''}`">
      <el-button :disabled="!dirty" :loading="saving" type="primary" data-test="sheet-save" @click="save">保存</el-button>
      <el-button type="warning" data-test="sheet-pdf" @click="makePdf">生成 PDF</el-button>
    </PageHeader>
    <div class="ed">
      <el-card class="ed__blocks">
        <template #header>
          <div class="jb-between">
            <span>题块（共 {{ blocks.length }} 题，满分 <b>{{ total }}</b>）</span>
            <div class="jb-row">
              <el-dropdown @command="add"><el-button size="small">＋ 添加题</el-button>
                <template #dropdown><el-dropdown-menu><el-dropdown-item v-for="(l, k) in BLOCK_TYPES" :key="k" :command="k">{{ l }}</el-dropdown-item></el-dropdown-menu></template>
              </el-dropdown>
              <el-button size="small" @click="renumber">重新编号</el-button>
            </div>
          </div>
        </template>
        <el-table :data="blocks" size="small" max-height="620">
          <el-table-column label="题号" width="70"><template #default="{ row }"><el-input v-model="row.no" size="small" @input="dirty = true" /></template></el-table-column>
          <el-table-column label="题型" width="100">
            <template #default="{ row }"><el-select v-model="row.type" size="small" @change="changeType(row)"><el-option v-for="(l, k) in BLOCK_TYPES" :key="k" :label="l" :value="k" /></el-select></template>
          </el-table-column>
          <el-table-column label="分值" width="110"><template #default="{ row }"><el-input-number v-model="row.fullScore" :min="0.5" :step="0.5" size="small" controls-position="right" style="width: 90px" @change="dirty = true" /></template></el-table-column>
          <el-table-column label="选项" width="90"><template #default="{ row }"><el-input-number v-if="row.objective" v-model="row.options" :min="2" :max="8" size="small" controls-position="right" style="width: 70px" @change="dirty = true" /></template></el-table-column>
          <el-table-column label="标准答案（客观题用于自动判分）">
            <template #default="{ row }"><el-input v-if="row.objective" v-model="row.answer" size="small" :placeholder="row.type === 'JUDGE' ? 'T / F' : '如 A 或 ACD'" @input="dirty = true" /><span v-else class="jb-muted">人工 / AI 阅卷</span></template>
          </el-table-column>
          <el-table-column width="56"><template #default="{ $index }"><el-button link type="danger" @click="blocks.splice($index, 1); dirty = true">删</el-button></template></el-table-column>
        </el-table>
      </el-card>

      <div class="ed__side">
        <div class="paper">
          <div class="paper__head">
            <b>{{ view?.sheet?.sheetName }}</b>
            <div class="paper__info"><span>姓名 ________</span><span>班级 ________</span></div>
            <div class="paper__no"><span>学号</span><i v-for="k in (view?.sheet?.studentNoDigits ?? 8)" :key="k" /></div>
          </div>
          <div v-if="objective.length" class="paper__obj">
            <div v-for="b in objective" :key="b.no" class="opt"><em>{{ b.no }}</em><span v-for="l in letters(b.options)" :key="l">[{{ l }}]</span></div>
          </div>
          <div v-for="b in subjective" :key="b.no" class="paper__sub" :class="{ essay: b.type === 'ESSAY' }">
            <em>{{ b.no }}.（{{ b.fullScore }} 分）</em>
          </div>
          <p class="paper__foot">请在各题目的答题区域内作答，超出黑色矩形边框限定区域的答案无效</p>
        </div>
        <el-card header="历史版本" class="vers">
          <div v-for="v in versions" :key="v.id" class="jb-between ver">
            <span>v{{ v.versionNo }}，{{ v.totalScore }} 分 <el-tag v-if="v.frozen" size="small" type="info">已使用</el-tag></span>
            <small class="jb-muted">{{ fmtTime(v.createdAt) }}</small>
          </div>
          <p v-if="pdf" class="jb-muted">PDF：{{ pdf.pages }} 页，识别区域 {{ pdf.regions?.length }} 个</p>
        </el-card>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ed { display: flex; gap: 16px; align-items: flex-start; }
.ed__blocks { flex: 1; min-width: 0; }
.ed__side { width: 380px; flex-shrink: 0; display: flex; flex-direction: column; gap: 16px; }
.paper { background: var(--color-cotton); border: 1px solid var(--color-rule); border-radius: 6px; padding: 18px 20px; box-shadow: var(--jb-shadow); font-size: 12px; }
.paper__head { text-align: center; border-bottom: 2px solid var(--color-ink); padding-bottom: 10px; }
.paper__head b { font-size: 15px; }
.paper__info { display: flex; justify-content: space-around; margin: 8px 0; }
.paper__no { display: flex; justify-content: center; align-items: center; gap: 3px; }
.paper__no i { width: 16px; height: 20px; border: 1px solid var(--color-ink-2); }
.paper__obj { display: grid; grid-template-columns: repeat(3, 1fr); gap: 4px 10px; padding: 10px 0; border-bottom: 1px dashed var(--color-rule-2); }
.opt { display: flex; gap: 2px; align-items: center; font-family: monospace; }
.opt em { width: 18px; font-style: normal; font-weight: 700; }
.paper__sub { height: 70px; border: 1.5px solid var(--color-ink); margin-top: 8px; padding: 4px 6px; }
.paper__sub.essay { height: 140px; background-image: repeating-linear-gradient(90deg, transparent 0 13px, var(--color-rule) 13px 14px), repeating-linear-gradient(transparent 0 13px, var(--color-rule) 13px 14px); }
.paper__sub em { font-style: normal; font-weight: 700; }
.paper__foot { color: var(--color-muted); font-size: var(--text-xs); text-align: center; margin: 10px 0 0; }
.ver { padding: 6px 0; border-bottom: 1px dashed var(--jb-border); }
</style>
