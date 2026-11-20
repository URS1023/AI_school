<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { http, unwrap, type Schemas } from '@jb/api'
import { PageHeader, fmtTime, useDict } from '@jb/ui'
import { EXAM_STATUS, HW_STATUS } from '../../composables/school'

/** 阅卷（PRD 6.4）：汇总待批阅的作业与考试，显示每份的主观题进度，一键进入阅卷台。 */
interface Task { type: 'homework' | 'exam'; id: string; title: string; subject?: string; status: string; time?: string; progress: Schemas['QuestionProgress'][] }
const router = useRouter()
const { label: subjectLabel } = useDict('sys_subject')
const tasks = ref<Task[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const [hws, exams] = await Promise.all([
      unwrap(http.GET('/api/exam/homeworks', { params: { query: {} } })).catch(() => []),
      unwrap(http.GET('/api/exam/exams', { params: { query: {} } })).catch(() => []),
    ])
    const list: Task[] = [
      ...hws.filter((c) => ['MARKING', 'COLLECTING', 'CLOSED', 'PUBLISHED'].includes(c.homework?.hwStatus ?? '')).map((c) => ({
        type: 'homework' as const, id: c.homework!.id!, title: c.homework!.title!, subject: c.homework!.subject, status: HW_STATUS[c.homework!.hwStatus!], time: c.homework!.deadline, progress: [],
      })),
      ...exams.filter((e) => e.examStatus === 'MARKING').map((e) => ({
        type: 'exam' as const, id: e.id!, title: e.examName!, subject: e.subject, status: EXAM_STATUS[e.examStatus!], time: e.examTime, progress: [],
      })),
    ]
    await Promise.all(list.map(async (t) => {
      t.progress = (await unwrap(http.GET('/api/exam/{type}/{id}/marking/progress', { params: { path: { type: t.type.toUpperCase(), id: t.id } } })).catch(() => []))
        .filter((p) => !p.objective)
    }))
    tasks.value = list
  } finally {
    loading.value = false
  }
}

const sum = (t: Task, k: 'marked' | 'total') => t.progress.reduce((s, p) => s + (p[k] ?? 0), 0)
onMounted(load)
</script>

<template>
  <div>
    <PageHeader title="阅卷" icon="阅" color="var(--jb-c-exam)" desc="按题批阅主观题：快捷给分、自动跳下一份；看不清或有问题可上报问题卷" />
    <div v-loading="loading" class="list">
      <el-empty v-if="!loading && !tasks.length" description="目前没有待批阅的作业或考试" />
      <div v-for="t in tasks" :key="t.type + t.id" class="task jb-card" data-test="marking-task">
        <div class="task__head">
          <el-tag :type="t.type === 'exam' ? 'danger' : 'primary'" effect="dark" size="small">{{ t.type === 'exam' ? '考试' : '作业' }}</el-tag>
          <b>{{ t.title }}</b>
          <span class="jb-muted">{{ subjectLabel(t.subject) }}，{{ t.status }}，{{ fmtTime(t.time) }}</span>
          <span class="spacer" />
          <span>主观题 {{ sum(t, 'marked') }} / {{ sum(t, 'total') }}</span>
          <el-button type="primary" @click="router.push(`/exam/marking/${t.type}/${t.id}`)">进入阅卷</el-button>
        </div>
        <div class="qs">
          <a v-for="p in t.progress" :key="p.itemNo" class="q" :class="{ done: p.total && p.marked === p.total }" @click="router.push({ path: `/exam/marking/${t.type}/${t.id}`, query: { itemNo: p.itemNo } })">
            第 {{ p.itemNo }} 题 <small>{{ p.marked }}/{{ p.total }}</small>
          </a>
          <span v-if="!t.progress.length" class="jb-muted">暂无已识别的答卷</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.list { display: flex; flex-direction: column; gap: 12px; min-height: 120px; }
.task__head { display: flex; align-items: center; gap: 10px; }
.spacer { flex: 1; }
.qs { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.q { padding: 6px 12px; border-radius: 8px; background: color-mix(in srgb, var(--jb-c-exam) 9%, var(--jb-surface)); cursor: pointer; font-size: 13px; }
.q.done { background: var(--jb-brand-light); color: var(--jb-brand-dark); }
.q small { margin-left: 4px; color: var(--jb-text-secondary); }
</style>
