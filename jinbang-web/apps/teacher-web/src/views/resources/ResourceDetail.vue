<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http, unwrap, type ApiError, type Schemas } from '@jb/api'
import { fmtTime, openLink, useDict } from '@jb/ui'

/** 资源详情（PRD 3.2）：基本信息、下载、收藏、评论、纠错、投诉；举报走统一举报流程。 */
const route = useRoute()
const router = useRouter()
const id = String(route.params.id)
const res = ref<Schemas['ResResource'] | null>(null)
const comments = ref<Schemas['ResComment'][]>([])
const text = ref('')
const kind = ref<'COMMENT' | 'ERROR' | 'COMPLAIN'>('COMMENT')
const { label: typeLabel } = useDict('resource_type')
const { label: levelLabel } = useDict('platform_level')

async function load() {
  res.value = await unwrap(http.GET('/api/resources/{id}', { params: { path: { id } } }))
  comments.value = await unwrap(http.GET('/api/resources/{id}/comments', { params: { path: { id } } }))
}

async function download() {
  try {
    if (res.value?.resLevel !== 'FREE') await ElMessageBox.confirm(`首次下载将消耗 ${res.value?.priceCoins} 金榜币，之后可永久下载。`, '下载确认')
    const d = await unwrap(http.POST('/api/resources/{id}/download', { params: { path: { id } } }))
    openLink(String(d.url))
  } catch (e) {
    if (e !== 'cancel') ElMessage.error((e as ApiError).message)
  }
}

async function send() {
  if (!text.value.trim()) return
  try {
    await unwrap(http.POST('/api/resources/{id}/comments', { params: { path: { id } }, body: { type: kind.value, content: text.value } }))
    ElMessage.success(kind.value === 'COMMENT' ? '评论已发布' : '已提交，平台会尽快处理')
    text.value = ''
    load()
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

async function favorite() {
  await unwrap(http.POST('/api/favorites', { body: { targetType: 'RESOURCE', targetId: id, on: true } }))
  ElMessage.success('已收藏')
}

async function report() {
  const { value } = await ElMessageBox.prompt('请说明举报原因', '举报资源')
  await unwrap(http.POST('/api/reports', { body: { targetType: 'RESOURCE', targetId: id, targetOwnerId: res.value?.authorUserId, reason: value } }))
  ElMessage.success('举报已提交，处理结果会通过消息通知你')
}

onMounted(load)
</script>

<template>
  <div v-if="res" class="jb-stack">
    <el-page-header :content="res.title" @back="router.back()" />
    <el-card>
      <el-descriptions :column="3" border>
        <el-descriptions-item label="类型">{{ typeLabel(res.resType) }}</el-descriptions-item>
        <el-descriptions-item label="等级">{{ levelLabel(res.resLevel) }}{{ res.resLevel !== 'FREE' ? ` · ${res.priceCoins} 币` : '' }}</el-descriptions-item>
        <el-descriptions-item label="格式">{{ res.fileExt?.toUpperCase() }}</el-descriptions-item>
        <el-descriptions-item label="下载">{{ res.downloadCount }}</el-descriptions-item>
        <el-descriptions-item label="浏览">{{ res.viewCount }}</el-descriptions-item>
        <el-descriptions-item label="发布时间">{{ fmtTime(res.publishedAt) }}</el-descriptions-item>
        <el-descriptions-item label="简介" :span="3">{{ res.summary || '—' }}</el-descriptions-item>
      </el-descriptions>
      <div class="ops">
        <el-button type="primary" @click="download">下载</el-button>
        <el-button @click="favorite">收藏</el-button>
        <el-button link type="danger" @click="report">举报</el-button>
      </div>
    </el-card>
    <el-card header="评论与反馈">
      <div class="send">
        <el-select v-model="kind" style="width: 110px">
          <el-option label="评论" value="COMMENT" /><el-option label="纠错" value="ERROR" /><el-option label="投诉" value="COMPLAIN" />
        </el-select>
        <el-input v-model="text" placeholder="说说你的看法…" @keyup.enter="send" />
        <el-button type="primary" @click="send">发送</el-button>
      </div>
      <el-empty v-if="!comments.length" description="还没有评论" :image-size="60" />
      <div v-for="c in comments" :key="c.id" class="cmt"><span>{{ c.content }}</span><small class="jb-muted">{{ fmtTime(c.createdAt) }}</small></div>
    </el-card>
  </div>
</template>

<style scoped>
.ops { margin-top: 14px; display: flex; gap: 8px; }
.send { display: flex; gap: 8px; margin-bottom: 12px; }
.cmt { display: flex; justify-content: space-between; padding: 10px 0; border-bottom: 1px dashed var(--jb-border); }
</style>
