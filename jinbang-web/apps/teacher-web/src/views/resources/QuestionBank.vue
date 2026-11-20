<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { http, unwrap, type ApiError, type Schemas } from '@jb/api'
import { FilterChips, MathText, PageHeader, PeriodSubject, useDict, usePaged, usePeriodSubject, type FilterRow } from '@jb/ui'

/**
 * 题库与组卷（PRD 3.3、6.11）：统一题目模型——平台题、校本题、我的题（含 AI 生成）。
 * 左侧教材 / 知识点树；题型、难度、来源筛选；试题篮 → 生成试卷；智能组卷（知识点 × 难度 × 各题型题量）。
 */
type Q = Schemas['QQuestion']
const router = useRouter()
const route = useRoute()
const ps = usePeriodSubject()
const { items: qtypes, label: typeLabel } = useDict('question_type')
const { items: diffs, label: diffLabel } = useDict('sys_difficult')
const { items: sources } = useDict('question_source')
const { label: subjectLabel } = useDict('sys_subject')
const scope = ref<'PLATFORM' | 'MINE' | 'SCHOOL'>('PLATFORM')
const treeType = ref<'TEXTBOOK' | 'KNOWLEDGE'>('KNOWLEDGE')
const tree = ref<Schemas['ResCatalog'][]>([])
const node = ref<Schemas['ResCatalog'] | null>(null)
const filters = ref<Record<string, string | number | undefined>>({})
const keyword = ref((route.query.keyword as string) ?? '')
const showAnswer = ref(false)
const basket = ref<string[]>([])
const basketOpen = ref(false)
const paperTitle = ref('')
const smartOpen = ref(false)
const smart = ref({ title: '', difficulty: '3', counts: { SINGLE: 5, MULTIPLE: 0, BLANK: 3, JUDGE: 0, ANSWER: 2 } as Record<string, number>, knowledge: [] as string[] })
const addOpen = ref(false)
const addForm = ref({ type: 'SINGLE', difficulty: '3', stem: '', options: ['A. ', 'B. ', 'C. ', 'D. '], answer: '', analysis: '', knowledge: '' })

const rows = computed<FilterRow[]>(() => [
  { key: 'source', label: '来源', options: sources.value.map((s) => ({ label: s.dictLabel!, value: s.dictValue! })) },
  { key: 'type', label: '题型', options: qtypes.value.filter((t) => !t.parentValue || t.parentValue === ps.subject).map((s) => ({ label: s.dictLabel!, value: s.dictValue! })) },
  { key: 'difficulty', label: '难度', options: diffs.value.map((s) => ({ label: s.dictLabel!, value: s.dictValue! })) },
])

const treeData = computed(() => {
  const byParent = new Map<string, Schemas['ResCatalog'][]>()
  tree.value.forEach((c) => {
    const k = String(c.parentId ?? 0)
    byParent.set(k, [...(byParent.get(k) ?? []), c])
  })
  const build = (pid: string): { id: string; label: string; raw: Schemas['ResCatalog']; children: unknown[] }[] =>
    (byParent.get(pid) ?? []).map((c) => ({ id: c.id!, label: c.catalogName!, raw: c, children: build(c.id!) }))
  return build('0')
})

const { query, rows: list, total, loading, load } = usePaged<Q, Record<string, unknown>>(
  (q) => unwrap(http.GET('/api/questions', {
    params: { query: {
      scope: scope.value, period: scope.value === 'PLATFORM' ? ps.period : undefined, subject: ps.subject,
      type: filters.value.type as string | undefined, difficulty: filters.value.difficulty as string | undefined,
      source: filters.value.source as string | undefined, keyword: keyword.value || undefined,
      catalogId: treeType.value === 'TEXTBOOK' ? node.value?.id : undefined,
      knowledge: treeType.value === 'KNOWLEDGE' ? node.value?.catalogName : undefined,
      pageNum: q.pageNum, pageSize: q.pageSize,
    } },
  })),
  {},
  10,
)

function pickNode(d: { raw: Schemas['ResCatalog'] } | null) {
  node.value = d?.raw ?? null
  load(true)
}

async function loadTree() {
  tree.value = await unwrap(http.GET('/api/catalogs', { params: { query: { type: treeType.value, period: ps.period, subject: ps.subject } } })).catch(() => [])
}

async function loadBasket() {
  const b = await unwrap(http.GET('/api/basket/{itemType}', { params: { path: { itemType: 'QUESTION' } } })).catch(() => [])
  basket.value = b.map((x) => x.itemId!)
}

async function toggleBasket(q: Q) {
  const inBasket = basket.value.includes(q.id!)
  await unwrap(http.POST(inBasket ? '/api/basket/{itemType}/remove' : '/api/basket/{itemType}/add', {
    params: { path: { itemType: 'QUESTION' } }, body: { ids: [q.id!] },
  }))
  loadBasket()
}

async function clearBasket() {
  await unwrap(http.POST('/api/basket/{itemType}/remove', { params: { path: { itemType: 'QUESTION' } }, body: { ids: [] } }))
  loadBasket()
}

async function addPage() {
  await unwrap(http.POST('/api/basket/{itemType}/add', { params: { path: { itemType: 'QUESTION' } }, body: { ids: list.value.map((q) => q.id!) } }))
  ElMessage.success('本页题目已加入试题篮')
  loadBasket()
}

async function collect(q: Q) {
  await unwrap(http.POST('/api/questions/{id}/collect', { params: { path: { id: q.id! } } }))
  ElMessage.success('已收入我的题库')
}

async function createPaper() {
  try {
    const v = await unwrap(http.POST('/api/papers/from-basket', { body: { title: paperTitle.value || `${new Date().toISOString().slice(0, 10)} ${subjectLabel(ps.subject)} 作业`, subject: ps.subject } }))
    basketOpen.value = false
    router.push(`/resources/papers/${v.paper?.id}`)
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

async function smartPaper() {
  const counts = Object.fromEntries(Object.entries(smart.value.counts).filter(([, n]) => n > 0))
  if (!Object.keys(counts).length) return ElMessage.warning('请至少设置一种题型的题量')
  try {
    const v = await unwrap(http.POST('/api/papers/smart', { body: {
      title: smart.value.title || `${new Date().toISOString().slice(0, 10)} ${subjectLabel(ps.subject)} 智能组卷`, period: ps.period, subject: ps.subject, difficulty: smart.value.difficulty,
      knowledge: smart.value.knowledge, typeCounts: counts,
    } }))
    if (v.paper?.sealLine?.startsWith('题量不足')) ElMessage.warning(v.paper.sealLine)
    router.push(`/resources/papers/${v.paper?.id}`)
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

async function addQuestion() {
  const f = addForm.value
  const choice = f.type === 'SINGLE' || f.type === 'MULTIPLE'
  try {
    await unwrap(http.POST('/api/questions', { body: {
      period: ps.period, subject: ps.subject, type: f.type, difficulty: f.difficulty, stem: f.stem,
      options: choice ? f.options.filter((o) => o.trim().length > 3) : undefined, answer: f.answer, analysis: f.analysis, knowledge: f.knowledge,
    } }))
    ElMessage.success('已加入我的题库')
    addOpen.value = false
    if (scope.value === 'MINE') load()
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

function options(q: Q): string[] {
  try {
    return q.options ? (JSON.parse(q.options) as string[]) : []
  } catch {
    return []
  }
}

watch([() => ps.period, () => ps.subject, treeType], () => {
  node.value = null
  loadTree()
  load(true)
})
watch(scope, () => load(true))
onMounted(() => {
  loadTree()
  load()
  loadBasket()
})
</script>

<template>
  <div>
    <PageHeader title="题库组卷" icon="题" color="var(--jb-c-teaching)" desc="平台、校本与个人题库统一检索；选题组卷、智能组卷，组好的试卷可一键制作答题卡">
      <PeriodSubject v-model:period="ps.period" v-model:subject="ps.subject" />
      <el-button @click="router.push('/resources/questions/import')">批量录题</el-button>
      <el-button @click="addOpen = true">单题录入</el-button>
      <el-button type="primary" @click="smartOpen = true">智能组卷</el-button>
      <el-badge :value="basket.length" :hidden="!basket.length" type="warning">
        <el-button type="warning" plain @click="basketOpen = true">试题篮 {{ basket.length }}</el-button>
      </el-badge>
    </PageHeader>

    <div class="qb">
      <aside class="qb__tree jb-card">
        <el-segmented v-model="treeType" :options="[{ label: '知识点', value: 'KNOWLEDGE' }, { label: '教材', value: 'TEXTBOOK' }]" block />
        <el-tree :data="treeData" node-key="id" highlight-current default-expand-all :expand-on-click-node="false"
                 empty-text="该学科暂无目录" @node-click="pickNode" />
        <el-button v-if="node" link @click="pickNode(null)">清除目录筛选</el-button>
      </aside>
      <section class="qb__main">
        <el-tabs v-model="scope">
          <el-tab-pane label="平台题库" name="PLATFORM" />
          <el-tab-pane label="我的题库" name="MINE" />
          <el-tab-pane label="校本题库" name="SCHOOL" />
        </el-tabs>
        <FilterChips v-model="filters" :rows="rows" @change="load(true)" />
        <div class="bar">
          <el-input v-model="keyword" placeholder="搜索题干" clearable style="width: 260px" @keyup.enter="load(true)" @clear="load(true)" />
          <div class="jb-row">
            <el-switch v-model="showAnswer" active-text="显示答案解析" />
            <el-button @click="addPage">本页加入试题篮</el-button>
          </div>
        </div>
        <div v-loading="loading" class="qlist">
          <el-empty v-if="!loading && !list.length" description="没有符合条件的题目" />
          <article v-for="(q, i) in list" :key="q.id" class="q" data-test="question-item">
            <header class="jb-between">
              <div class="jb-row jb-muted">
                <span class="q__no">{{ (query.pageNum - 1) * query.pageSize + i + 1 }}</span>
                <el-tag size="small" effect="plain">{{ typeLabel(q.qType) }}</el-tag>
                <span>{{ diffLabel(q.difficulty) }}</span>
                <span v-if="q.knowledge">· {{ q.knowledge }}</span>
                <span>· 组卷 {{ q.usedCount }} 次</span>
              </div>
            </header>
            <MathText class="q__stem" :text="q.stem" block />
            <div v-if="options(q).length" class="q__opts"><MathText v-for="o in options(q)" :key="o" :text="o" /></div>
            <div v-if="showAnswer" class="q__ans">
              <div><b>答案：</b><MathText :text="q.answer" /></div>
              <div v-if="q.analysis"><b>解析：</b><MathText :text="q.analysis" /></div>
            </div>
            <footer class="q__ops">
              <el-button v-if="scope !== 'MINE'" size="small" text @click="collect(q)">收入我的题库</el-button>
              <el-button size="small" :type="basket.includes(q.id!) ? 'danger' : 'primary'" plain @click="toggleBasket(q)">
                {{ basket.includes(q.id!) ? '移出试题篮' : '加入试题篮' }}
              </el-button>
            </footer>
          </article>
        </div>
        <el-pagination v-model:current-page="query.pageNum" :page-size="query.pageSize" :total="total" layout="prev, pager, next, total" @current-change="load()" />
      </section>
    </div>

    <el-drawer v-model="basketOpen" title="试题篮" size="380px">
      <p class="jb-muted">已选 {{ basket.length }} 题。生成试卷后可设置分数、调整题序、换题并导出。</p>
      <el-input v-model="paperTitle" placeholder="试卷标题（默认：日期 + 学科 + 作业）" />
      <div class="jb-row" style="margin-top: 12px">
        <el-button type="primary" :disabled="!basket.length" @click="createPaper">生成试卷</el-button>
        <el-button :disabled="!basket.length" @click="clearBasket">清空</el-button>
      </div>
    </el-drawer>

    <el-dialog v-model="smartOpen" title="智能组卷" width="560px">
      <el-form label-width="90px">
        <el-form-item label="试卷标题"><el-input v-model="smart.title" placeholder="选填" /></el-form-item>
        <el-form-item label="考查知识点">
          <el-select v-model="smart.knowledge" multiple filterable allow-create placeholder="选择或输入知识点" style="width: 100%">
            <el-option v-for="c in tree.filter((t) => t.catalogType === 'KNOWLEDGE')" :key="c.id" :label="c.catalogName" :value="c.catalogName!" />
          </el-select>
        </el-form-item>
        <el-form-item label="试卷难度">
          <el-radio-group v-model="smart.difficulty"><el-radio-button v-for="d in diffs" :key="d.dictValue" :value="d.dictValue!">{{ d.dictLabel }}</el-radio-button></el-radio-group>
        </el-form-item>
        <el-form-item label="各题型题量">
          <div class="counts">
            <span v-for="t in Object.keys(smart.counts)" :key="t">{{ typeLabel(t) }} <el-input-number v-model="smart.counts[t]" :min="0" :max="30" size="small" /></span>
          </div>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="smartOpen = false">取消</el-button><el-button type="primary" @click="smartPaper">出卷</el-button></template>
    </el-dialog>

    <el-dialog v-model="addOpen" title="单题录入（加入我的题库）" width="640px">
      <el-form label-width="70px">
        <el-form-item label="题型">
          <el-select v-model="addForm.type"><el-option v-for="t in qtypes" :key="t.dictValue" :label="t.dictLabel" :value="t.dictValue!" /></el-select>
          <el-select v-model="addForm.difficulty" style="margin-left: 8px"><el-option v-for="d in diffs" :key="d.dictValue" :label="d.dictLabel" :value="d.dictValue!" /></el-select>
        </el-form-item>
        <el-form-item label="题文"><el-input v-model="addForm.stem" type="textarea" :rows="3" placeholder="公式用 $...$，如 $x^2+1$" /></el-form-item>
        <el-form-item v-if="addForm.type === 'SINGLE' || addForm.type === 'MULTIPLE'" label="选项">
          <div class="opts-edit">
            <el-input v-for="(_, i) in addForm.options" :key="i" v-model="addForm.options[i]" />
            <el-button size="small" @click="addForm.options.push(`${String.fromCharCode(65 + addForm.options.length)}. `)">增加选项</el-button>
          </div>
        </el-form-item>
        <el-form-item label="答案"><el-input v-model="addForm.answer" /></el-form-item>
        <el-form-item label="解析"><el-input v-model="addForm.analysis" type="textarea" /></el-form-item>
        <el-form-item label="知识点"><el-input v-model="addForm.knowledge" /></el-form-item>
        <el-form-item label="预览"><MathText :text="addForm.stem" block /></el-form-item>
      </el-form>
      <template #footer><el-button @click="addOpen = false">取消</el-button><el-button type="primary" @click="addQuestion">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.qb { display: flex; gap: 16px; }
.qb__tree { width: 240px; flex-shrink: 0; align-self: flex-start; display: flex; flex-direction: column; gap: 10px; }
.qb__main { flex: 1; min-width: 0; }
.bar { display: flex; justify-content: space-between; margin: 12px 0; }
.qlist { display: flex; flex-direction: column; gap: 12px; min-height: 120px; }
.q { background: var(--jb-surface); border: 1px solid var(--jb-border); border-radius: var(--jb-radius); padding: 14px 18px; }
.q__no { display: inline-grid; place-items: center; width: 24px; height: 24px; border-radius: 6px; background: var(--jb-brand-light); color: var(--jb-brand-dark); font-weight: 700; }
.q__stem { margin: 10px 0 6px; }
.q__opts { display: grid; grid-template-columns: repeat(2, 1fr); gap: 4px 24px; }
.q__ans { margin-top: 10px; padding: 10px 12px; border-radius: 8px; background: var(--jb-accent-light); }
.q__ops { display: flex; justify-content: flex-end; gap: 8px; margin-top: 8px; border-top: 1px dashed var(--jb-border); padding-top: 8px; }
.counts { display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px 16px; }
.opts-edit { display: flex; flex-direction: column; gap: 6px; width: 100%; }
.el-pagination { margin-top: 14px; justify-content: flex-end; }
</style>
