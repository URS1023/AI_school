<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { http, unwrap, type ApiError, type Schemas } from '@jb/api'
import { MathText, PageHeader, PeriodSubject, useDict, usePeriodSubject } from '@jb/ui'

/**
 * 批量录题（手册第 7 章）：左侧输入区按标记录入，右侧检查区实时解析（输入停顿 0.6 秒后），有错误逐题标出，全部正确才能导入。
 * 标记：【题型】【难度】【题文】【选项】【答案】【解析】【题组】【题组小题】【题组结束】
 */
const router = useRouter()
const ps = usePeriodSubject()
const { label: typeLabel } = useDict('question_type')
const SAMPLE = `【题型】单选题
【难度】较易
【题文】已知集合 $A=\\{1,2\\}$，$B=\\{2,3\\}$，则 $A\\cap B=$（ ）
【选项】
A. {1}
B. {2}
C. {3}
D. {1,2,3}
【答案】B
【解析】交集是两个集合的公共元素。
`
const text = ref(SAMPLE)
const preview = ref<Schemas['ImportPreview'] | null>(null)
const importing = ref(false)
let timer: number | undefined

async function check() {
  preview.value = await unwrap(http.POST('/api/questions/import/preview', { body: { text: text.value } })).catch(() => null)
}

watch(text, () => {
  window.clearTimeout(timer)
  timer = window.setTimeout(check, 600)
}, { immediate: true })

async function doImport() {
  importing.value = true
  try {
    const ids = await unwrap(http.POST('/api/questions/import', { body: { text: text.value, period: ps.period, subject: ps.subject } }))
    ElMessage.success(`已导入 ${ids.length} 道题到我的题库`)
    router.push('/resources/questions')
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  } finally {
    importing.value = false
  }
}
</script>

<template>
  <div>
    <PageHeader title="批量录题" icon="录" color="var(--jb-c-teaching)" desc="按标记粘贴或输入题目，右侧检查区实时预览；有错误时不会导入任何一题">
      <PeriodSubject v-model:period="ps.period" v-model:subject="ps.subject" />
      <el-button @click="text = SAMPLE">示例</el-button>
      <el-button @click="text = ''">清除</el-button>
      <el-button type="primary" :loading="importing" :disabled="!preview?.ok" @click="doImport">导入</el-button>
    </PageHeader>
    <div class="imp">
      <div class="imp__in jb-card">
        <div class="jb-muted small">支持标记：【题型】【难度】【题文】【选项】【答案】【解析】【题组】【题组小题】【题组结束】；公式用 $...$</div>
        <el-input v-model="text" type="textarea" :rows="28" resize="none" data-test="import-input" />
      </div>
      <div class="imp__out jb-card">
        <div class="jb-between">
          <strong>检查区</strong>
          <el-tag v-if="preview" :type="preview.ok ? 'success' : 'danger'">{{ preview.ok ? `解析出 ${preview.questions?.length} 题，可以导入` : `${preview.errors?.length} 处错误` }}</el-tag>
        </div>
        <el-alert v-for="(e, i) in preview?.errors ?? []" :key="i" :title="e.message" type="error" :closable="false" show-icon class="err" />
        <div v-for="q in preview?.questions ?? []" :key="q.index" class="pq">
          <div class="jb-muted small">第 {{ q.index }} 题 · {{ typeLabel(q.type) || q.typeLabel }}<span v-if="q.groupIndex"> · 题组 {{ q.groupIndex }}</span></div>
          <MathText :text="q.stem" block />
          <div v-for="o in q.options" :key="o"><MathText :text="o" /></div>
          <div class="jb-muted small">答案：{{ q.answer || '—' }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.imp { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.imp__out { max-height: 720px; overflow: auto; }
.small { font-size: 12px; margin-bottom: 8px; }
.err { margin-top: 8px; }
.pq { padding: 10px 0; border-bottom: 1px dashed var(--jb-border); }
</style>
