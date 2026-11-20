<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http, postForm, unwrap, type ApiError, type Schemas } from '@jb/api'
import { PageHeader, fmtTime } from '@jb/ui'

/** 个人 / 共享知识库（PRD 7.7）：新建、上传（解析中 / 可用 / 失败）、重命名、移动、删除、容量；问答时可引用。 */
const kbs = ref<Schemas['KbView'][]>([])
const active = ref<Schemas['KbView'] | null>(null)
const docs = ref<Schemas['AiKbDoc'][]>([])
const keyword = ref('')
const STATUS: Record<string, { t: string; c: 'success' | 'warning' | 'danger' }> = { READY: { t: '可用', c: 'success' }, PARSING: { t: '解析中', c: 'warning' }, FAILED: { t: '解析失败', c: 'danger' } }

async function load() {
  kbs.value = await unwrap(http.GET('/api/ai/kb'))
  if (!active.value && kbs.value.length) pick(kbs.value[0])
}

async function pick(k: Schemas['KbView']) {
  active.value = k
  docs.value = await unwrap(http.GET('/api/ai/kb/{kbId}/docs', { params: { path: { kbId: k.kb!.id! }, query: { keyword: keyword.value || undefined } } }))
}

async function create() {
  const { value } = await ElMessageBox.prompt('知识库名称', '新建知识库')
  await unwrap(http.POST('/api/ai/kb', { body: { name: value, shared: false } }))
  load()
}

async function upload(ev: Event) {
  const f = (ev.target as HTMLInputElement).files?.[0]
  if (!f || !active.value) return
  const fd = new FormData()
  fd.append('file', f)
  try {
    const d = await postForm<Schemas['AiKbDoc']>(`/api/ai/kb/${active.value.kb!.id}/docs`, fd)
    ElMessage[d.parseStatus === 'READY' ? 'success' : 'warning'](d.parseStatus === 'READY' ? '上传成功，已可在问答中引用' : `解析失败：${d.parseError}`)
    pick(active.value)
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

async function rename(d: Schemas['AiKbDoc']) {
  const { value } = await ElMessageBox.prompt('新名称', '重命名', { inputValue: d.fileName })
  await unwrap(http.PUT('/api/ai/kb/docs/{docId}', { params: { path: { docId: d.id! } }, body: { name: value } }))
  pick(active.value!)
}

async function remove(d: Schemas['AiKbDoc']) {
  await ElMessageBox.confirm(`删除「${d.fileName}」？`, '删除')
  await unwrap(http.POST('/api/ai/kb/docs/{docId}/delete', { params: { path: { docId: d.id! } } }))
  pick(active.value!)
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader title="知识库" icon="库" color="var(--jb-c-ai)" desc="上传教材、课标、教案（txt / md / docx / pdf / pptx），在智能问答中勾选引用，回答会标注来源">
      <el-button type="primary" @click="create">新建知识库</el-button>
    </PageHeader>
    <div class="kb">
      <el-card class="kb__list">
        <a v-for="k in kbs" :key="k.kb?.id" class="item" :class="{ on: active?.kb?.id === k.kb?.id }" @click="pick(k)">
          <b>{{ k.kb?.kbName }}</b>
          <small class="jb-muted">{{ k.kb?.kbType === 'SHARED' ? '共享' : '个人' }} · 已用 {{ ((k.usedBytes ?? 0) / 1048576).toFixed(1) }} / {{ ((k.kb?.quotaBytes ?? 0) / 1048576).toFixed(0) }} MB</small>
        </a>
        <el-empty v-if="!kbs.length" description="还没有知识库" :image-size="50" />
      </el-card>
      <el-card v-if="active" class="kb__main">
        <template #header>
          <div class="jb-between"><strong>{{ active.kb?.kbName }}</strong>
            <div class="jb-row"><el-input v-model="keyword" size="small" placeholder="按名称搜索" @keyup.enter="pick(active!)" />
              <label class="upload el-button el-button--primary el-button--small">上传文件<input type="file" hidden @change="upload" /></label></div></div>
        </template>
        <el-table :data="docs" empty-text="还没有文件">
          <el-table-column prop="fileName" label="文件" />
          <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="STATUS[row.parseStatus]?.c">{{ STATUS[row.parseStatus]?.t }}</el-tag></template></el-table-column>
          <el-table-column label="大小" width="90"><template #default="{ row }">{{ (row.sizeBytes / 1024).toFixed(0) }} KB</template></el-table-column>
          <el-table-column label="上传时间" width="150"><template #default="{ row }">{{ fmtTime(row.createdAt) }}</template></el-table-column>
          <el-table-column width="130"><template #default="{ row }"><el-button link @click="rename(row)">重命名</el-button><el-button link type="danger" @click="remove(row)">删除</el-button></template></el-table-column>
        </el-table>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.kb { display: flex; gap: 16px; }
.kb__list { width: 280px; flex-shrink: 0; align-self: flex-start; }
.kb__main { flex: 1; }
.item { display: flex; flex-direction: column; gap: 4px; padding: 10px; border-radius: 8px; cursor: pointer; }
.item.on, .item:hover { background: var(--jb-brand-light); }
.upload { margin: 0; }
</style>
