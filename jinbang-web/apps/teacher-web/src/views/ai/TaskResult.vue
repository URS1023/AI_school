<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { http, unwrap, type ApiError, type Schemas } from '@jb/api'

/** AI 任务结果条：本次消耗 / 未扣费、成功与失败数；失败项可重试（同一任务 ID，幂等，AC-11）。 */
const props = defineProps<{ task: Schemas['TaskResult'] }>()
const emit = defineEmits<{ updated: [Schemas['TaskResult']] }>()

const TYPE: Record<string, 'success' | 'warning' | 'danger' | 'info'> = { SUCCESS: 'success', PARTIAL: 'warning', FAILED: 'danger', EMPTY: 'info', BLOCKED: 'danger', STOPPED: 'info' }

async function retry() {
  try {
    emit('updated', await unwrap(http.POST('/api/ai/tasks/{taskId}/retry', { params: { path: { taskId: props.task.taskId! } } })))
    ElMessage.success('已重试失败项')
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}
</script>

<template>
  <el-alert :type="TYPE[props.task.status ?? ''] ?? 'info'" :closable="false" show-icon class="tr" data-test="task-result">
    <template #title>
      {{ props.task.userMessage }}
      <span class="jb-muted">（预计 {{ props.task.estimate }} 币，实际扣除 {{ props.task.charged }} 币）</span>
      <el-button v-if="props.task.status === 'PARTIAL' || props.task.status === 'FAILED' || props.task.status === 'EMPTY'" size="small" link type="primary" @click="retry">重试失败项</el-button>
    </template>
    <template #default><span class="jb-muted small">{{ props.task.disclaimer }} · 任务号 {{ props.task.taskId }}</span></template>
  </el-alert>
</template>

<style scoped>
.tr { margin: 12px 0; }
.small { font-size: 12px; }
</style>
