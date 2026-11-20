<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { http, unwrap, type ApiError, type Schemas } from '@jb/api'
import { PageHeader, PeriodSubject, useDict, usePeriodSubject } from '@jb/ui'

/**
 * 智能备课三步（PRD 4.1.2、11.2）：①输入主题 → ②AI 大纲（⚠️P0 修复：可增删改章、节、要点）→ ③选模板生成课件。
 * 课件存入所选目录；每完成一次智能备课 +20 经验 +10 金币。另提供「生成教案（Word）」。
 */
interface Outline { title: string; chapters: { title: string; sections: { title: string; points: string[] }[] }[] }

const route = useRoute()
const router = useRouter()
const ps = usePeriodSubject()
const { label: subjectLabel } = useDict('sys_subject')
const step = ref(0)
const busy = ref(false)
const cats = ref<Schemas['TcPrepCatalog'][]>([])
const templates = ref<{ code: string; name: string }[]>([])
const form = ref({ catalogId: (route.query.catalogId as string) ?? '', topic: '', edition: '统编版', course: '', requirement: '' })
const prepId = ref('')
const outline = ref<Outline | null>(null)
const template = ref('urban_blue')
const result = ref<Schemas['PrepView'] | null>(null)
const COLORS: Record<string, string> = { urban_blue: '#2a5bb8', deep_calm: '#1c2230', fresh_light: '#5ab4c8', simple_green: '#3c8f57', red_china: '#aa1e1e' }

const pages = computed(() => 1 + (outline.value?.chapters.reduce((n, c) => n + 1 + c.sections.length, 0) ?? 0))

async function start() {
  if (!form.value.topic.trim()) return ElMessage.warning('请输入备课主题')
  busy.value = true
  try {
    const v = await unwrap(http.POST('/api/prep/smart', { body: {
      catalogId: form.value.catalogId || undefined, topic: form.value.topic, edition: form.value.edition,
      course: form.value.course || form.value.topic, requirement: form.value.requirement, subject: subjectLabel(ps.subject), period: ps.period,
    } }))
    prepId.value = v.prep!.id!
    outline.value = v.outline as unknown as Outline
    step.value = 1
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  } finally {
    busy.value = false
  }
}

async function saveOutline() {
  await unwrap(http.PUT('/api/prep/smart/{id}/outline', { params: { path: { id: prepId.value } }, body: outline.value as never }))
}

async function next() {
  try {
    await saveOutline()
    step.value = 2
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

async function generate() {
  busy.value = true
  try {
    result.value = await unwrap(http.POST('/api/prep/smart/{id}/generate', { params: { path: { id: prepId.value } }, body: { template: template.value } }))
    step.value = 3
    ElMessage.success('课件已生成并存入备课目录，获得 20 经验、10 金榜币')
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  } finally {
    busy.value = false
  }
}

async function lessonPlan() {
  busy.value = true
  try {
    await unwrap(http.POST('/api/prep/lesson-plan', { body: { catalogId: form.value.catalogId || undefined, topic: form.value.course || form.value.topic, requirement: form.value.requirement } }))
    ElMessage.success('教案已生成并存入备课目录')
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  } finally {
    busy.value = false
  }
}

function addPoint(points: string[], e: Event) {
  const t = e.target as HTMLInputElement
  if (t.value.trim()) {
    points.push(t.value.trim())
    t.value = ''
  }
}

const addChapter = () => outline.value!.chapters.push({ title: '新章节', sections: [{ title: '新小节', points: ['要点'] }] })
const move = <T,>(arr: T[], i: number, d: number) => {
  const j = i + d
  if (j < 0 || j >= arr.length) return
  ;[arr[i], arr[j]] = [arr[j], arr[i]]
}

onMounted(async () => {
  cats.value = await unwrap(http.GET('/api/prep/catalogs')).catch(() => [])
  templates.value = ((await unwrap(http.GET('/api/open/ai/ppt/templates')).catch(() => [])) as { code: string; name: string }[])
})
</script>

<template>
  <div>
    <PageHeader title="智能备课" icon="智" color="var(--jb-c-ai)" desc="输入课题，AI 生成规范化教学大纲；大纲可以自由修改，确认后一键生成课件。主站备课不扣金榜币">
      <PeriodSubject v-model:period="ps.period" v-model:subject="ps.subject" />
    </PageHeader>
    <el-card>
      <el-steps :active="step" finish-status="success" align-center class="steps">
        <el-step title="输入主题" /><el-step title="编辑大纲" /><el-step title="选择模板" /><el-step title="完成" />
      </el-steps>

      <el-form v-if="step === 0" label-width="100px" class="form">
        <el-form-item label="存入目录">
          <el-select v-model="form.catalogId" clearable placeholder="不选则存入「AI 生成」目录" style="width: 100%">
            <el-option v-for="c in cats" :key="c.id" :label="c.catalogName" :value="c.id!" />
          </el-select>
        </el-form-item>
        <el-form-item label="教材版本"><el-input v-model="form.edition" /></el-form-item>
        <el-form-item label="课目"><el-input v-model="form.course" placeholder="如：沁园春·长沙" /></el-form-item>
        <el-form-item label="备课主题"><el-input v-model="form.topic" type="textarea" :rows="2" placeholder="帮我写一篇关于……的规范化教学大纲" data-test="topic" /></el-form-item>
        <el-form-item label="其他要求"><el-input v-model="form.requirement" placeholder="课时、学情、侧重点（选填）" /></el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="busy" data-test="gen-outline" @click="start">生成大纲</el-button>
          <el-button :loading="busy" @click="lessonPlan">直接生成教案（Word）</el-button>
        </el-form-item>
      </el-form>

      <div v-else-if="step === 1 && outline" class="outline">
        <el-input v-model="outline.title" class="outline__title" />
        <div v-for="(ch, ci) in outline.chapters" :key="ci" class="ch">
          <div class="row">
            <span class="badge">{{ ci + 1 }}</span>
            <el-input v-model="ch.title" />
            <el-button text @click="move(outline.chapters, ci, -1)">上移</el-button>
            <el-button text @click="move(outline.chapters, ci, 1)">下移</el-button>
            <el-button text type="danger" @click="outline.chapters.splice(ci, 1)">删除章</el-button>
          </div>
          <div v-for="(sec, si) in ch.sections" :key="si" class="sec">
            <div class="row">
              <el-input v-model="sec.title" size="small" />
              <el-button size="small" text type="danger" @click="ch.sections.splice(si, 1)">删除节</el-button>
            </div>
            <div class="pts">
              <el-tag v-for="(pt, pi) in sec.points" :key="pi" closable @close="sec.points.splice(pi, 1)">{{ pt }}</el-tag>
              <el-input size="small" placeholder="+ 要点，回车添加" style="width: 160px" @keyup.enter="addPoint(sec.points, $event)" />
            </div>
          </div>
          <el-button size="small" text type="primary" @click="ch.sections.push({ title: '新小节', points: [] })">+ 添加节</el-button>
        </div>
        <div class="jb-between foot">
          <el-button @click="addChapter">+ 添加章</el-button>
          <div><el-button @click="step = 0">上一步</el-button><el-button type="primary" data-test="next" @click="next">下一步：选择模板</el-button></div>
        </div>
      </div>

      <div v-else-if="step === 2" class="tpls">
        <p class="jb-muted">预计 {{ pages }} 页</p>
        <div class="tpl-grid">
          <button v-for="t in templates" :key="t.code" type="button" class="tpl" :class="{ on: template === t.code }" @click="template = t.code">
            <span class="tpl__cover" :style="{ background: COLORS[t.code] ?? 'var(--jb-brand)' }">{{ outline?.title }}</span>
            <span>{{ t.name }}</span>
          </button>
        </div>
        <div class="foot"><el-button @click="step = 1">上一步</el-button><el-button type="primary" :loading="busy" data-test="gen-ppt" @click="generate">生成课件</el-button></div>
      </div>

      <el-result v-else icon="success" title="课件已生成" :sub-title="`「${result?.material?.fileName}」已存入备课目录`">
        <template #extra>
          <el-button type="primary" @click="router.push('/teaching/prep')">去备课目录查看</el-button>
          <el-button @click="step = 0; outline = null">再备一课</el-button>
        </template>
      </el-result>
    </el-card>
  </div>
</template>

<style scoped>
.steps { margin: 6px 0 26px; }
.form { max-width: 680px; margin: 0 auto; }
.outline { max-width: 860px; margin: 0 auto; display: flex; flex-direction: column; gap: 14px; }
.outline__title :deep(input) { font-size: 18px; font-weight: 700; }
.ch { padding: 12px 14px; border: 1px solid var(--jb-border); border-radius: var(--jb-radius); background: var(--jb-brand-soft); }
.row { display: flex; gap: 8px; align-items: center; }
.badge { width: 26px; height: 26px; flex-shrink: 0; border-radius: 50%; background: var(--jb-brand); color: #fff; display: grid; place-items: center; font-weight: 700; }
.sec { margin: 10px 0 0 34px; }
.pts { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 6px; }
.foot { margin-top: 16px; display: flex; justify-content: flex-end; gap: 8px; }
.tpl-grid { display: grid; grid-template-columns: repeat(5, 1fr); gap: 14px; }
.tpl { border: 2px solid transparent; background: none; border-radius: 10px; padding: 6px; cursor: pointer; display: flex; flex-direction: column; gap: 6px; }
.tpl.on { border-color: var(--jb-brand); }
.tpl__cover { height: 90px; border-radius: 6px; color: #fff; display: grid; place-items: center; padding: 6px; font-size: 13px; }
</style>
