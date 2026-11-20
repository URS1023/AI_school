<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { http, unwrap, type ApiError, type Schemas } from '@jb/api'
import { MathText, PageHeader, PeriodSubject, useDict, usePeriodSubject } from '@jb/ui'
import TaskResult from './TaskResult.vue'

/** 题目生成（PRD 7.3）：结构化参数 + 自然语言要求；按实际生成题数计费；勾选后一键加入我的题库（➕P0）。 */
interface GenQ { type: string; stem: string; options?: string[]; answer?: string; analysis?: string; knowledge?: string }
const ps = usePeriodSubject()
const { items: diffs, label: diffLabel } = useDict('sys_difficult')
const { label: subjectLabel } = useDict('sys_subject')
const { items: qtypes, label: typeLabel } = useDict('question_type')
const form = ref({ grade: '', difficulty: '3', type: 'SINGLE', count: 5, requirement: '' })
const busy = ref(false)
const task = ref<Schemas['TaskResult'] | null>(null)
const picked = ref<number[]>([])

const questions = computed<GenQ[]>(() => {
  try {
    return JSON.parse(task.value?.items?.[0]?.output ?? '[]') as GenQ[]
  } catch {
    return []
  }
})

async function gen() {
  busy.value = true
  try {
    task.value = await unwrap(http.POST('/api/ai/questions/generate', { body: {
      period: ps.period === '1' ? '高中' : ps.period === '2' ? '初中' : '小学', grade: form.value.grade, subject: subjectLabel(ps.subject),
      difficulty: diffLabel(form.value.difficulty), type: typeLabel(form.value.type), count: form.value.count, requirement: form.value.requirement,
    } as never }))
    picked.value = questions.value.map((_, i) => i)
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  } finally {
    busy.value = false
  }
}

async function save() {
  const list = picked.value.map((i) => questions.value[i])
  if (!list.length) return ElMessage.warning('请勾选要入库的题目')
  const ids = await unwrap(http.POST('/api/ai/questions/save', { body: { subject: ps.subject, difficulty: form.value.difficulty, questions: list as never } }))
  ElMessage.success(`已加入我的题库 ${ids.length} 题，可在「题库组卷 → 我的题库」中组卷`)
}
</script>

<template>
  <div>
    <PageHeader title="题目生成" icon="出" color="var(--jb-c-ai)" desc="按学段学科、难度、题型和题量生成题目，生成的题可一键加入我的题库参与组卷">
      <PeriodSubject v-model:period="ps.period" v-model:subject="ps.subject" />
    </PageHeader>
    <el-card>
      <el-form inline>
        <el-form-item label="年级"><el-input v-model="form.grade" placeholder="如：高一" style="width: 110px" /></el-form-item>
        <el-form-item label="难度"><el-select v-model="form.difficulty" style="width: 100px"><el-option v-for="d in diffs" :key="d.dictValue" :label="d.dictLabel" :value="d.dictValue!" /></el-select></el-form-item>
        <el-form-item label="题型"><el-select v-model="form.type" style="width: 120px"><el-option v-for="t in qtypes" :key="t.dictValue" :label="t.dictLabel" :value="t.dictValue!" /></el-select></el-form-item>
        <el-form-item label="题量"><el-input-number v-model="form.count" :min="1" :max="30" /></el-form-item>
      </el-form>
      <el-input v-model="form.requirement" type="textarea" :rows="2" placeholder="补充要求：考查的知识点、情境、是否分层……" />
      <div class="ops"><el-button type="primary" :loading="busy" data-test="gen-questions" @click="gen">生成题目</el-button></div>
      <TaskResult v-if="task" :task="task" @updated="(t) => (task = t)" />
      <el-checkbox-group v-model="picked" class="qs">
        <div v-for="(q, i) in questions" :key="i" class="q">
          <el-checkbox :value="i">第 {{ i + 1 }} 题</el-checkbox>
          <MathText :text="q.stem" block />
          <div v-if="q.options?.length" class="opts"><MathText v-for="o in q.options" :key="o" :text="o" /></div>
          <div class="ans">答案：<MathText :text="q.answer" />　{{ q.analysis }}<span v-if="q.knowledge" class="jb-muted">　知识点：{{ q.knowledge }}</span></div>
        </div>
      </el-checkbox-group>
      <div v-if="questions.length" class="ops"><el-button type="success" data-test="save-questions" @click="save">加入我的题库（{{ picked.length }}）</el-button></div>
    </el-card>
  </div>
</template>

<style scoped>
.ops { margin-top: 12px; display: flex; justify-content: flex-end; }
.qs { display: flex; flex-direction: column; gap: 12px; margin-top: 8px; }
.q { padding: 12px 14px; border: 1px solid var(--jb-border); border-radius: var(--jb-radius); }
.opts { display: grid; grid-template-columns: repeat(2, 1fr); gap: 2px 20px; }
.ans { margin-top: 6px; padding: 6px 10px; background: var(--jb-accent-light); border-radius: 6px; }
</style>
