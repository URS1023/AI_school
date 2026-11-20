<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http, unwrap, type ApiError, type Schemas } from '@jb/api'
import { PageHeader, fmtTime, useDict } from '@jb/ui'
import { HW_STATUS, useMyClasses } from '../../composables/school'
import SourcePanel from './SourcePanel.vue'

/** 单份作业：状态流转（完成扫描 → 批阅 → 批阅完成）、公布答案、过程面板、跳转作业学情。 */
const route = useRoute()
const router = useRouter()
const { className } = useMyClasses()
const { label: subjectLabel } = useDict('sys_subject')
const hw = ref<Schemas['ExHomework'] | null>(null)
const panel = ref<InstanceType<typeof SourcePanel>>()
const id = computed(() => route.params.id as string)
const classIds = computed(() => (hw.value?.classIds ?? '').replace(/[[\]"\s]/g, '').split(',').filter(Boolean))
const STEPS = ['ASSIGNED', 'COLLECTING', 'MARKING', 'MARKED', 'ARCHIVED']
const active = computed(() => Math.max(0, STEPS.indexOf(hw.value?.hwStatus === 'PUBLISHED' || hw.value?.hwStatus === 'CLOSED' ? 'COLLECTING' : hw.value?.hwStatus ?? '')))

async function load() {
  const all = await unwrap(http.GET('/api/exam/homeworks', { params: { query: {} } }))
  hw.value = all.find((c) => c.homework?.id === id.value)?.homework ?? null
}

async function act(op: 'complete-scan' | 'close' | 'finish' | 'publish-answer', confirm: string) {
  try {
    await ElMessageBox.confirm(confirm, '确认')
    const p = { params: { path: { id: id.value } } }
    if (op === 'complete-scan') await unwrap(http.POST('/api/exam/homeworks/{id}/complete-scan', p))
    else if (op === 'close') await unwrap(http.POST('/api/exam/homeworks/{id}/close', p))
    else if (op === 'finish') await unwrap(http.POST('/api/exam/homeworks/{id}/finish', p))
    else await unwrap(http.POST('/api/exam/homeworks/{id}/publish-answer', p))
    ElMessage.success('操作成功')
    await load()
    panel.value?.load()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error((e as ApiError).message)
  }
}

onMounted(load)
</script>

<template>
  <div v-if="hw">
    <PageHeader :title="hw.title!" icon="作" color="var(--jb-c-exam)"
                :desc="`${subjectLabel(hw.subject)} · ${hw.hwMode === 'ONLINE' ? '线上作答' : '纸质扫描'} · ${classIds.map(className).join('、')} · 截止 ${fmtTime(hw.deadline) || '不限'}`">
      <el-button v-if="hw.hwStatus === 'COLLECTING'" @click="act('complete-scan', '确认已全部扫描？完成后进入批阅阶段。')">完成扫描</el-button>
      <el-button v-if="hw.hwStatus === 'PUBLISHED'" @click="act('close', '截止收集并开始批阅？')">截止收集</el-button>
      <el-button v-if="hw.hwStatus === 'MARKING' || hw.hwStatus === 'COLLECTING'" type="success" @click="act('finish', '确认批阅完成？完成后生成作业学情。')">批阅完成</el-button>
      <el-button v-if="!hw.answerPublished" @click="act('publish-answer', '向学生公布答案与解析？')">公布答案</el-button>
      <el-button type="primary" plain @click="router.push(`/learning/reports/homework/${hw.id}`)">作业学情</el-button>
    </PageHeader>
    <el-card>
      <el-steps :active="active" finish-status="success" simple class="steps">
        <el-step v-for="s in STEPS" :key="s" :title="HW_STATUS[s]" />
      </el-steps>
      <SourcePanel ref="panel" type="HOMEWORK" :id="hw.id!" :class-ids="classIds" />
    </el-card>
  </div>
  <el-empty v-else description="作业不存在或无权查看" />
</template>

<style scoped>
.steps { margin-bottom: 14px; }
</style>
