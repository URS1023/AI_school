<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { http, postForm, unwrap, type ApiError, type Schemas } from '@jb/api'
import { PageHeader, fmtTime, useDict, usePeriodSubject } from '@jb/ui'

/** 资源征集（PRD 3.6）：活动列表、投稿（进入平台审核，通过后发放奖励）、我的投稿（审核状态、驳回原因，➕P0）。 */
const ps = usePeriodSubject()
const activities = ref<Schemas['ResCollectionActivity'][]>([])
const mine = ref<Schemas['ResResource'][]>([])
const { items: types } = useDict('resource_type')
const open = ref(false)
const form = ref({ activityId: '', title: '', resType: 'COURSEWARE', summary: '', file: null as File | null, agree: false })
const STATUS: Record<string, { text: string; type: 'info' | 'success' | 'danger' | 'warning' }> = {
  PENDING: { text: '审核中', type: 'warning' }, ONLINE: { text: '已通过', type: 'success' },
  REJECTED: { text: '已驳回', type: 'danger' }, OFFLINE: { text: '已下架', type: 'info' }, DRAFT: { text: '草稿', type: 'info' },
}

async function load() {
  activities.value = await unwrap(http.GET('/api/collection/activities'))
  mine.value = await unwrap(http.GET('/api/collection/mine'))
}

function submitTo(a: Schemas['ResCollectionActivity']) {
  form.value = { activityId: a.id!, title: '', resType: 'COURSEWARE', summary: '', file: null, agree: false }
  open.value = true
}

async function submit() {
  const f = form.value
  if (!f.title || !f.file) return ElMessage.warning('请填写标题并选择文件')
  if (!f.agree) return ElMessage.warning('请先确认原创承诺')
  const data = new FormData()
  data.append('meta', new Blob([JSON.stringify({ scope: 'PLATFORM', activityId: f.activityId, title: f.title, resType: f.resType,
    summary: f.summary, period: ps.period, subject: ps.subject })], { type: 'application/json' }))
  data.append('file', f.file)
  try {
    await postForm('/api/resources', data)
    ElMessage.success('投稿成功，审核结果会通过消息通知你')
    open.value = false
    load()
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

onMounted(load)
</script>

<template>
  <div class="jb-stack">
    <PageHeader title="资源征集" icon="征" desc="参与平台征集活动，优质资源审核通过后纳入平台资源并获得金榜币与经验奖励" />
    <div class="acts">
      <el-empty v-if="!activities.length" description="暂无进行中的征集活动" />
      <el-card v-for="a in activities" :key="a.id" shadow="hover" class="act">
        <div class="jb-between"><strong>{{ a.title }}</strong><el-tag :type="a.activityStatus === 'ONGOING' ? 'success' : 'info'">{{ a.activityStatus === 'ONGOING' ? '进行中' : '已结束' }}</el-tag></div>
        <p class="jb-muted">{{ a.rules }}</p>
        <div class="jb-between">
          <span class="jb-muted">奖励：{{ a.rewardCoins }} 币 + {{ a.rewardExp }} 经验 · 截止 {{ fmtTime(a.endAt) || '不限' }}</span>
          <el-button type="primary" size="small" :disabled="a.activityStatus !== 'ONGOING'" @click="submitTo(a)">我要投稿</el-button>
        </div>
      </el-card>
    </div>
    <el-card header="我的投稿">
      <el-table :data="mine" empty-text="还没有投稿">
        <el-table-column prop="title" label="资源标题" />
        <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="STATUS[row.resStatus]?.type">{{ STATUS[row.resStatus]?.text ?? row.resStatus }}</el-tag></template></el-table-column>
        <el-table-column prop="rejectReason" label="驳回原因" />
        <el-table-column label="投稿时间" width="170"><template #default="{ row }">{{ fmtTime(row.createdAt) }}</template></el-table-column>
      </el-table>
    </el-card>
    <el-dialog v-model="open" title="投稿" width="520px">
      <el-form label-width="80px">
        <el-form-item label="标题"><el-input v-model="form.title" /></el-form-item>
        <el-form-item label="类型"><el-select v-model="form.resType"><el-option v-for="t in types" :key="t.dictValue" :label="t.dictLabel" :value="t.dictValue!" /></el-select></el-form-item>
        <el-form-item label="简介"><el-input v-model="form.summary" type="textarea" /></el-form-item>
        <el-form-item label="文件"><input type="file" @change="form.file = ($event.target as HTMLInputElement).files?.[0] ?? null" /></el-form-item>
        <el-form-item><el-checkbox v-model="form.agree">我承诺投稿内容为原创或已获授权，不侵犯他人权益</el-checkbox></el-form-item>
      </el-form>
      <template #footer><el-button @click="open = false">取消</el-button><el-button type="primary" @click="submit">提交</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.acts { display: grid; grid-template-columns: repeat(2, 1fr); gap: 16px; }
.act p { min-height: 40px; }
</style>
