<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { http, unwrap, type Schemas } from '@jb/api'

/** 统一消息中心（PRD 2.2 ➕）：审核结果、订单、成绩更正、作业通知等。 */
type Msg = Schemas['MsgMessage']
const rows = ref<Msg[]>([])
const total = ref(0)
const q = reactive({ pageNum: 1, pageSize: 20, category: '' as string, unread: false })
const loading = ref(false)
const CATEGORIES: Record<string, string> = { SYSTEM: '系统', AUDIT: '审核', ORDER: '订单', AI_TASK: 'AI 任务', SCORE: '成绩', HOMEWORK: '作业' }

async function load() {
  loading.value = true
  try {
    const page = await unwrap(http.GET('/api/messages', {
      params: { query: { pageNum: q.pageNum, pageSize: q.pageSize, category: q.category || undefined, unread: q.unread || undefined } },
    }))
    rows.value = page.rows ?? []
    total.value = page.total ?? 0
  } finally {
    loading.value = false
  }
}

async function readAll() {
  await unwrap(http.POST('/api/messages/read', { body: { ids: [] } }))
  load()
}

onMounted(load)
</script>

<template>
  <el-card header="消息中心">
    <div class="bar">
      <el-radio-group v-model="q.category" @change="load">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button v-for="(label, key) in CATEGORIES" :key="key" :value="key">{{ label }}</el-radio-button>
      </el-radio-group>
      <el-checkbox v-model="q.unread" @change="load">只看未读</el-checkbox>
      <el-button @click="readAll">全部已读</el-button>
    </div>
    <el-table v-loading="loading" :data="rows" empty-text="暂无消息">
      <el-table-column width="70"><template #default="{ row }"><el-badge is-dot :hidden="row.readFlag" /></template></el-table-column>
      <el-table-column label="类型" width="90"><template #default="{ row }">{{ CATEGORIES[row.category] ?? row.category }}</template></el-table-column>
      <el-table-column prop="title" label="标题" width="220" />
      <el-table-column prop="content" label="内容" />
      <el-table-column prop="createdAt" label="时间" width="180" />
    </el-table>
    <el-pagination v-model:current-page="q.pageNum" :page-size="q.pageSize" :total="total" layout="prev, pager, next, total" @current-change="load" />
  </el-card>
</template>

<style scoped>
.bar { display: flex; gap: 16px; align-items: center; margin-bottom: 12px; }
</style>
