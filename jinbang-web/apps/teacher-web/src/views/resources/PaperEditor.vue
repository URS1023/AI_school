<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { http, unwrap, type ApiError, type Schemas } from '@jb/api'
import { useAuth } from '@jb/auth'
import { EChart, MathText, openLink, useDict } from '@jb/ui'

/**
 * 组卷编辑页（PRD 3.3 /createTestPaper）：密封线、按大题分组、分数设置、换题、调整题序、试卷分析、导出 Word、制作答题卡。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuth()
const id = String(route.params.id)
const view = ref<Schemas['PaperView'] | null>(null)
const scores = ref<Record<string, number>>({})
const showAnswer = ref(false)
const { label: typeLabel } = useDict('question_type')
const { label: diffLabel } = useDict('sys_difficult')
const CN = ['一', '二', '三', '四', '五', '六', '七', '八', '九', '十']

function apply(v: Schemas['PaperView']) {
  view.value = v
  scores.value = {}
  v.sections?.forEach((s) => s.items?.forEach((i) => (scores.value[i.itemId!] = Number(i.score))))
}

async function load() {
  apply(await unwrap(http.GET('/api/papers/{id}', { params: { path: { id } } })))
}

async function saveScores() {
  try {
    apply(await unwrap(http.POST('/api/papers/{id}/scores', { params: { path: { id } }, body: scores.value as unknown as Record<string, number> })))
    ElMessage.success('分数已保存')
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

const allItems = computed(() => view.value?.sections?.flatMap((s) => s.items ?? []) ?? [])

async function move(itemId: string, delta: number) {
  const ids = allItems.value.map((i) => i.itemId!)
  const at = ids.indexOf(itemId)
  const to = at + delta
  if (to < 0 || to >= ids.length) return
  ;[ids[at], ids[to]] = [ids[to], ids[at]]
  apply(await unwrap(http.POST('/api/papers/{id}/reorder', { params: { path: { id } }, body: { ids } })))
}

async function replace(itemId: string) {
  try {
    apply(await unwrap(http.POST('/api/papers/{id}/items/{itemId}/replace', { params: { path: { id, itemId } } })))
    ElMessage.success('已换成同类题')
  } catch (e) {
    ElMessage.warning((e as ApiError).message)
  }
}

async function exportWord() {
  openLink(String(await unwrap(http.POST('/api/papers/{id}/export', { params: { path: { id }, query: { withAnswer: true } } }))))
}

async function makeSheet() {
  try {
    const s = await unwrap(http.POST('/api/exam/sheets', { body: { name: view.value?.paper?.title, paperId: id } }))
    ElMessage.success('答题卡已生成')
    router.push(`/exam/sheets/${s.sheet?.id}`)
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

function options(q?: Schemas['QQuestion']): string[] {
  try {
    return q?.options ? (JSON.parse(q.options) as string[]) : []
  } catch {
    return []
  }
}

const typeChart = computed(() => ({
  tooltip: { trigger: 'item', formatter: '{b}：{c} 分（{d}%）' },
  legend: { bottom: 0, type: 'scroll' },
  series: [{ type: 'pie', radius: ['40%', '65%'], center: ['50%', '42%'], label: { show: false }, data: Object.entries(view.value?.analysis?.scoreByType ?? {}).map(([k, v]) => ({ name: typeLabel(k), value: Number(v) })) }],
}))
const diffChart = computed(() => {
  const d = view.value?.analysis?.byDifficulty ?? {}
  return { tooltip: {}, xAxis: { type: 'category', data: Object.keys(d).map((k) => diffLabel(k)) }, yAxis: { type: 'value', minInterval: 1 }, series: [{ type: 'bar', data: Object.values(d) }] }
})

onMounted(load)
</script>

<template>
  <div v-if="view" class="pe">
    <div class="pe__paper jb-card">
      <el-page-header @back="router.back()"><template #content><strong>{{ view.paper?.title }}</strong></template></el-page-header>
      <div class="seal">学校：________　姓名：________　班级：________　考号：________</div>
      <p class="jb-muted center">满分 {{ view.paper?.totalScore }} 分</p>
      <section v-for="(s, si) in view.sections" :key="s.title">
        <h3>{{ CN[si] }}、{{ s.title }}（共 {{ s.items?.reduce((a, i) => a + Number(i.score), 0) }} 分）</h3>
        <div v-for="it in s.items" :key="it.itemId" class="pq">
          <div class="pq__head">
            <span class="pq__no">{{ it.no }}.</span>
            <MathText :text="it.question?.stem" block />
          </div>
          <div v-if="options(it.question).length" class="pq__opts"><MathText v-for="o in options(it.question)" :key="o" :text="o" /></div>
          <div v-if="showAnswer" class="pq__ans">答案：<MathText :text="it.question?.answer" />　<MathText :text="it.question?.analysis" /></div>
          <div class="pq__ops">
            <el-input-number v-model="scores[it.itemId!]" :min="0.5" :step="0.5" size="small" /> 分
            <el-button size="small" text @click="move(it.itemId!, -1)">上移</el-button>
            <el-button size="small" text @click="move(it.itemId!, 1)">下移</el-button>
            <el-button size="small" text type="primary" @click="replace(it.itemId!)">换题</el-button>
          </div>
        </div>
      </section>
    </div>
    <aside class="pe__side">
      <el-card header="操作">
        <div class="jb-stack">
          <el-switch v-model="showAnswer" active-text="显示答案解析" />
          <el-button type="primary" @click="saveScores">保存分数</el-button>
          <el-button @click="exportWord">导出 Word（含答案）</el-button>
          <el-button v-if="auth.hasPerm('homework:assign')" @click="makeSheet">制作答题卡</el-button>
        </div>
      </el-card>
      <el-card header="题型分值">
        <EChart :option="typeChart" height="200px" />
      </el-card>
      <el-card header="难度分布">
        <EChart :option="diffChart" height="180px" />
      </el-card>
      <el-card header="知识点">
        <el-tag v-for="(n, k) in view.analysis?.byKnowledge" :key="k" class="kn" effect="plain">{{ k }} × {{ n }}</el-tag>
      </el-card>
    </aside>
  </div>
</template>

<style scoped>
.pe { display: flex; gap: 16px; align-items: flex-start; }
.pe__paper { flex: 1; min-width: 0; padding: 24px 32px; }
.pe__side { width: 300px; display: flex; flex-direction: column; gap: 12px; position: sticky; top: 80px; }
.seal { margin: 16px 0 4px; padding: 8px; text-align: center; border: 1px dashed var(--jb-border); color: var(--jb-text-secondary); }
.center { text-align: center; }
h3 { margin: 20px 0 8px; font-size: 16px; }
.pq { padding: 10px 0; border-bottom: 1px dashed var(--jb-border); }
.pq__head { display: flex; gap: 6px; }
.pq__no { font-weight: 700; }
.pq__opts { display: grid; grid-template-columns: repeat(2, 1fr); gap: 2px 24px; padding-left: 22px; }
.pq__ans { margin: 6px 0 0 22px; padding: 6px 10px; background: var(--jb-accent-light); border-radius: 6px; }
.pq__ops { display: flex; align-items: center; gap: 6px; justify-content: flex-end; margin-top: 4px; }
.kn { margin: 0 6px 6px 0; }
</style>
