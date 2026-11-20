<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { http, unwrap, type Schemas } from '@jb/api'
import { PageHeader, fmtTime, usePaged } from '@jb/ui'
import TaskResult from './TaskResult.vue'

/** 消费记录（PRD 7.1 ➕P0）：时间、智能体、扣费、状态（成功 / 失败-未扣费 / 部分成功），可查看详情并重试失败项。 */
const agents = ref<Schemas['AiAgent'][]>([])
const agentCode = ref('')
const detail = ref<Schemas['TaskResult'] | null>(null)
const { query, rows, total, loading, load } = usePaged<Schemas['TaskView'], Record<string, unknown>>(
  (q) => unwrap(http.GET('/api/ai/tasks', { params: { query: { agentCode: agentCode.value || undefined, pageNum: q.pageNum, pageSize: q.pageSize } } })),
  {},
)
const TYPE: Record<string, 'success' | 'warning' | 'danger' | 'info'> = { SUCCESS: 'success', PARTIAL: 'warning', FAILED: 'danger', EMPTY: 'info', BLOCKED: 'danger', STOPPED: 'info' }

async function open(t: Schemas['TaskView']) {
  detail.value = await unwrap(http.GET('/api/ai/tasks/{taskId}', { params: { path: { taskId: t.taskId! } } }))
}

onMounted(async () => {
  agents.value = await unwrap(http.GET('/api/open/ai/agents')).catch(() => [])
  load()
})
</script>

<template>
  <div>
    <PageHeader title="消费记录" icon="账" color="var(--jb-c-ai)" desc="每次 AI 调用一条记录，与金榜币流水用同一任务号对账；失败、无结果、内容拦截都不扣费" />
    <el-card>
      <el-select v-model="agentCode" clearable placeholder="全部智能体" style="width: 200px; margin-bottom: 12px" @change="load(true)">
        <el-option v-for="a in agents" :key="a.agentCode" :label="a.agentName" :value="a.agentCode!" />
      </el-select>
      <el-table v-loading="loading" :data="rows" empty-text="还没有使用记录" data-test="records">
        <el-table-column label="时间" width="160"><template #default="{ row }">{{ fmtTime(row.createdAt) }}</template></el-table-column>
        <el-table-column prop="agentName" label="智能体" width="140" />
        <el-table-column label="状态" width="130"><template #default="{ row }"><el-tag :type="TYPE[row.status]">{{ row.statusLabel }}</el-tag></template></el-table-column>
        <el-table-column label="预计 / 实扣" width="120"><template #default="{ row }">{{ row.estimate }} / <b>{{ row.charged }}</b></template></el-table-column>
        <el-table-column label="成功项" width="90"><template #default="{ row }">{{ row.successItems }} / {{ row.totalItems }}</template></el-table-column>
        <el-table-column prop="userMessage" label="说明" />
        <el-table-column width="80"><template #default="{ row }"><el-button link type="primary" @click="open(row)">详情</el-button></template></el-table-column>
      </el-table>
      <el-pagination v-model:current-page="query.pageNum" :page-size="query.pageSize" :total="total" layout="prev, pager, next, total" @current-change="load()" />
    </el-card>
    <el-drawer :model-value="!!detail" title="任务详情" size="520px" @close="detail = null">
      <template v-if="detail">
        <TaskResult :task="detail" @updated="(t) => { detail = t; load() }" />
        <el-table :data="detail.items" size="small">
          <el-table-column prop="itemNo" label="#" width="50" />
          <el-table-column prop="label" label="子项" />
          <el-table-column prop="status" label="状态" width="90" />
          <el-table-column prop="charged" label="扣费" width="60" />
          <el-table-column prop="message" label="说明" />
        </el-table>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.el-pagination { margin-top: 12px; justify-content: flex-end; }
</style>
