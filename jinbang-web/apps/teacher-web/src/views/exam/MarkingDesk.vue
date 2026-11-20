<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http, unwrap, type ApiError, type Schemas } from '@jb/api'
import { MathText } from '@jb/ui'

/**
 * 阅卷台（PRD 6.4）：左侧题目进度；中间学生作答（原卷切图或线上作答文本，不显示学生姓名以保证公平）；
 * 右侧打分板（快捷分、步长 0.5、数字键直接给分、回车提交），提交后自动下一份；可上报问题卷。
 */
const route = useRoute()
const router = useRouter()
const type = computed(() => String(route.params.type).toUpperCase())
const id = computed(() => route.params.id as string)
const itemNo = ref((route.query.itemNo as string) ?? '')
const progress = ref<Schemas['QuestionProgress'][]>([])
const cur = ref<Schemas['NextItem'] | null>(null)
const score = ref<number | undefined>()
const loading = ref(false)
const done = ref(false)
const path = computed(() => ({ type: type.value, id: id.value }))
const subjective = computed(() => progress.value.filter((p) => !p.objective))
const full = computed(() => Number(cur.value?.fullScore ?? 0))
const quick = computed(() => {
  const step = full.value > 10 ? Math.ceil(full.value / 10) : 1
  const arr: number[] = []
  for (let v = 0; v <= full.value; v += step) arr.push(v)
  if (arr[arr.length - 1] !== full.value) arr.push(full.value)
  return arr
})
const imgs = computed(() => {
  try {
    const v = JSON.parse(cur.value?.images ?? '[]')
    return Array.isArray(v) ? (v as string[]) : []
  } catch {
    return cur.value?.images ? [cur.value.images] : []
  }
})

async function loadProgress() {
  progress.value = await unwrap(http.GET('/api/exam/{type}/{id}/marking/progress', { params: { path: path.value } })).catch(() => [])
  if (!itemNo.value) itemNo.value = subjective.value.find((p) => (p.marked ?? 0) < (p.total ?? 0))?.itemNo ?? subjective.value[0]?.itemNo ?? ''
}

async function next() {
  if (!itemNo.value) return
  loading.value = true
  try {
    cur.value = await unwrap(http.GET('/api/exam/{type}/{id}/marking/next', { params: { path: path.value, query: { itemNo: itemNo.value } } }))
    done.value = !cur.value?.answerId
    score.value = cur.value?.currentScore ?? undefined
  } catch (e) {
    cur.value = null
    done.value = true
    if ((e as ApiError).code !== 404) ElMessage.error((e as ApiError).message)
  } finally {
    loading.value = false
  }
}

async function submit(v?: number) {
  const s = v ?? score.value
  if (s === undefined || s === null || !cur.value?.answerId) return
  if (s < 0 || s > full.value) return ElMessage.warning(`分数应在 0 ~ ${full.value} 之间`)
  try {
    await unwrap(http.POST('/api/exam/marking/submit', { body: { answerId: cur.value.answerId, itemNo: itemNo.value, score: s } }))
    score.value = undefined
    await Promise.all([next(), loadProgress()])
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

async function problem() {
  const { value } = await ElMessageBox.prompt('说明问题（如：图片不清、答错位置、疑似雷同）', '上报问题卷')
  await unwrap(http.POST('/api/exam/problems', { body: { answerId: cur.value!.answerId!, itemNo: itemNo.value, problemType: 'UNCLEAR', remark: value } }))
  ElMessage.success('已上报，由负责老师处理')
  next()
}

function pick(no: string) {
  itemNo.value = no
  router.replace({ query: { itemNo: no } })
}

function onKey(e: KeyboardEvent) {
  if ((e.target as HTMLElement).tagName === 'INPUT') {
    if (e.key === 'Enter') submit()
    return
  }
  if (/^[0-9]$/.test(e.key) && Number(e.key) <= full.value) submit(Number(e.key))
}

watch(itemNo, next)
onMounted(async () => {
  await loadProgress()
  next()
  window.addEventListener('keydown', onKey)
})
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <div class="desk">
    <aside class="desk__qs jb-card">
      <el-button text @click="router.back()">← 返回</el-button>
      <a v-for="p in subjective" :key="p.itemNo" class="q" :class="{ on: p.itemNo === itemNo }" @click="pick(p.itemNo!)">
        <b>第 {{ p.itemNo }} 题</b><small>{{ p.fullScore }} 分</small>
        <el-progress :percentage="p.total ? Math.round(((p.marked ?? 0) / p.total) * 100) : 0" :stroke-width="5" :show-text="false" />
        <small class="jb-muted">{{ p.marked }}/{{ p.total }}{{ p.needsReview ? ` · 待复核 ${p.needsReview}` : '' }}</small>
      </a>
      <el-empty v-if="!subjective.length" description="没有主观题" :image-size="40" />
    </aside>
    <section v-loading="loading" class="desk__paper jb-card" data-test="marking-paper">
      <template v-if="cur?.answerId">
        <div class="jb-between head"><b>第 {{ itemNo }} 题 · 满分 {{ cur.fullScore }}</b><span class="jb-muted">本题剩余 {{ cur.remaining }} 份<template v-if="cur.reviewReason"> · 复核原因：{{ cur.reviewReason }}</template></span></div>
        <div v-if="imgs.length" class="imgs"><img v-for="u in imgs" :key="u" :src="u" alt="学生作答" /></div>
        <div v-else class="text"><MathText :text="cur.studentAnswer || '（未作答）'" block /></div>
      </template>
      <el-result v-else-if="done" icon="success" title="本题已全部批完" sub-title="可以在左侧选择其他题目继续">
        <template #extra><el-button @click="router.back()">返回</el-button></template>
      </el-result>
    </section>
    <aside class="desk__pad jb-card">
      <b>给分</b>
      <div class="quick">
        <button v-for="v in quick" :key="v" type="button" :class="{ full: v === full, zero: v === 0 }" :disabled="!cur?.answerId" @click="submit(v)">{{ v }}</button>
      </div>
      <el-input-number v-model="score" :min="0" :max="full" :step="0.5" :disabled="!cur?.answerId" style="width: 100%" data-test="score-input" />
      <el-button type="primary" size="large" :disabled="!cur?.answerId || score === undefined" data-test="score-submit" @click="submit()">提交并下一份（Enter）</el-button>
      <el-button :disabled="!cur?.answerId" @click="problem">上报问题卷</el-button>
      <p class="jb-muted tip">数字键 0–9 直接给分；为保证公平，阅卷时不显示学生姓名。</p>
    </aside>
  </div>
</template>

<style scoped>
.desk { display: flex; gap: 14px; height: calc(100vh - var(--jb-header-h) - 64px); min-height: 520px; }
.desk__qs { width: 200px; flex-shrink: 0; overflow: auto; display: flex; flex-direction: column; gap: 6px; }
.q { display: flex; flex-direction: column; gap: 3px; padding: 8px 10px; border-radius: 8px; cursor: pointer; }
.q:hover, .q.on { background: color-mix(in srgb, var(--jb-c-exam) 10%, var(--jb-surface)); }
.desk__paper { flex: 1; min-width: 0; overflow: auto; }
.head { margin-bottom: 12px; }
.imgs img { width: 100%; border: 1px solid var(--jb-border); border-radius: 6px; }
.text { padding: 16px; background: #fffdf8; border: 1px solid #e5dfcf; border-radius: 6px; min-height: 200px; line-height: 2; }
.desk__pad { width: 240px; flex-shrink: 0; display: flex; flex-direction: column; gap: 12px; }
.desk__pad .el-button { margin-left: 0; }
.quick { display: grid; grid-template-columns: repeat(4, 1fr); gap: 6px; }
.quick button { height: 40px; border-radius: 8px; border: 1px solid var(--jb-border); background: var(--jb-surface); font-size: 16px; font-weight: 600; cursor: pointer; }
.quick button:hover:not(:disabled) { border-color: var(--jb-c-exam); color: var(--jb-c-exam); }
.quick button.full { background: var(--jb-brand-light); color: var(--jb-brand-dark); }
.quick button.zero { background: #fff1ec; color: #c4561e; }
.quick button:disabled { opacity: 0.4; cursor: not-allowed; }
.tip { font-size: 12px; }
</style>
