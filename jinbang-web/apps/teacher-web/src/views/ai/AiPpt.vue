<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { http, unwrap, type ApiError, type Schemas } from '@jb/api'
import { PageHeader, PeriodSubject, openLink, useDict, usePeriodSubject } from '@jb/ui'
import TaskResult from './TaskResult.vue'

/**
 * PPT 生成（PRD 7.4）：①填写要求 → ②大纲（免费，可编辑）→ ③选模板生成课件（按页计费）。
 * 课件自动进入「独立备课 → AI 生成」目录（➕P0 主站与 AI 平台打通）。
 */
interface Outline { title: string; chapters: { title: string; sections: { title: string; points: string[] }[] }[] }
const router = useRouter()
const ps = usePeriodSubject()
const { label: subjectLabel } = useDict('sys_subject')
const form = ref({ edition: '', topic: '', requirement: '' })
const outline = ref<Outline | null>(null)
const outlineText = ref('')
const templates = ref<{ code: string; name: string }[]>([])
const template = ref('fresh_light')
const busy = ref(false)
const task = ref<Schemas['TaskResult'] | null>(null)

async function genOutline() {
  if (!form.value.topic) return ElMessage.warning('请填写课题')
  busy.value = true
  try {
    const r = await unwrap(http.POST('/api/ai/ppt/outline', { body: { ...form.value, subject: subjectLabel(ps.subject), period: ps.period } as never }))
    outline.value = JSON.parse(r.items?.[0]?.output ?? 'null') as Outline
    outlineText.value = toText(outline.value)
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  } finally {
    busy.value = false
  }
}

/** 大纲以缩进文本编辑：一级 = 章，两个空格 = 节，四个空格 = 要点。 */
function toText(o: Outline | null): string {
  if (!o) return ''
  return [o.title, ...o.chapters.flatMap((c) => [c.title, ...c.sections.flatMap((s) => [`  ${s.title}`, ...s.points.map((p) => `    ${p}`)])])].join('\n')
}

function fromText(t: string): Outline {
  const lines = t.split('\n').filter((l) => l.trim())
  const o: Outline = { title: lines.shift()?.trim() ?? '课件', chapters: [] }
  for (const l of lines) {
    if (l.startsWith('    ')) o.chapters.at(-1)?.sections.at(-1)?.points.push(l.trim())
    else if (l.startsWith('  ')) o.chapters.at(-1)?.sections.push({ title: l.trim(), points: [] })
    else o.chapters.push({ title: l.trim(), sections: [] })
  }
  return o
}

async function generate() {
  busy.value = true
  try {
    task.value = await unwrap(http.POST('/api/ai/ppt/generate', { body: { outline: fromText(outlineText.value), template: template.value } as never }))
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  } finally {
    busy.value = false
  }
}

async function download() {
  openLink(String(await unwrap(http.GET('/api/ai/files/link', { params: { query: { taskId: task.value!.taskId! } } }))))
}

onMounted(async () => {
  templates.value = ((await unwrap(http.GET('/api/open/ai/ppt/templates')).catch(() => [])) as { code: string; name: string }[])
})
</script>

<template>
  <div>
    <PageHeader title="PPT 生成" icon="P" color="var(--jb-c-ai)" desc="大纲免费生成、可随意修改；确认后按页计费生成课件，并自动同步到独立备课">
      <PeriodSubject v-model:period="ps.period" v-model:subject="ps.subject" />
    </PageHeader>
    <div class="ppt">
      <el-card header="① 填写要求">
        <el-form label-width="70px">
          <el-form-item label="版本"><el-input v-model="form.edition" placeholder="如：人教A版 必修一" /></el-form-item>
          <el-form-item label="课题"><el-input v-model="form.topic" placeholder="如：函数的单调性" /></el-form-item>
          <el-form-item label="说明"><el-input v-model="form.requirement" type="textarea" placeholder="页数、难度、侧重点" /></el-form-item>
          <el-button type="primary" :loading="busy" @click="genOutline">生成大纲（免费）</el-button>
        </el-form>
      </el-card>
      <el-card header="② 编辑大纲">
        <el-input v-model="outlineText" type="textarea" :rows="16" placeholder="生成后可在这里修改：顶格 = 章，空两格 = 节，空四格 = 要点" />
      </el-card>
      <el-card header="③ 选择模板">
        <el-radio-group v-model="template" class="tpls"><el-radio v-for="t in templates" :key="t.code" :value="t.code" border>{{ t.name }}</el-radio></el-radio-group>
        <el-button type="primary" :disabled="!outlineText" :loading="busy" @click="generate">生成课件</el-button>
        <TaskResult v-if="task" :task="task" @updated="(t) => (task = t)" />
        <div v-if="task?.status === 'SUCCESS'" class="jb-row">
          <el-button @click="download">下载 PPTX</el-button>
          <el-button link type="primary" @click="router.push('/teaching/prep')">在独立备课中查看</el-button>
        </div>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.ppt { display: grid; grid-template-columns: 1fr 1.2fr 1fr; gap: 16px; align-items: start; }
.tpls { display: flex; flex-direction: column; align-items: flex-start; gap: 8px; margin-bottom: 12px; }
</style>
