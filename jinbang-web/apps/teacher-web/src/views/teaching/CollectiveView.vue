<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { http, unwrap, type ApiError, type Schemas } from '@jb/api'
import { useAuth } from '@jb/auth'
import { PageHeader, fmtTime } from '@jb/ui'

/**
 * 集体备课（PRD 4.3，➕P0 菜单入口）：发布主题 → 指定参与教师 → 上传主备稿 → 研讨评论 → 结束；成员可二次备课。
 */
type Theme = Schemas['TcCollectiveTheme']
const auth = useAuth()
const themes = ref<Theme[]>([])
const active = ref<Theme | null>(null)
const comments = ref<Schemas['TcCollectiveComment'][]>([])
const stats = ref<Record<string, number>>({})
const names = ref<Record<string, string>>({})
const text = ref('')
const createOpen = ref(false)
const teachers = ref<Schemas['TeacherBrief'][]>([])
const myMats = ref<Schemas['TcMaterial'][]>([])
const form = ref({ title: '', subject: '', description: '', members: [] as string[] })

async function load() {
  themes.value = await unwrap(http.GET('/api/collective/themes'))
}

async function open(t: Theme) {
  active.value = t
  comments.value = await unwrap(http.GET('/api/collective/themes/{id}/comments', { params: { path: { id: t.id! } } }))
  stats.value = (await unwrap(http.GET('/api/collective/themes/{id}/stats', { params: { path: { id: t.id! } } }))) as Record<string, number>
  const ids = Object.keys(stats.value)
  const r = await unwrap(http.POST('/api/school/names', { body: { userIds: ids } })).catch(() => ({ users: {} }))
  names.value = (r.users ?? {}) as Record<string, string>
}

async function openCreate() {
  teachers.value = await unwrap(http.GET('/api/school/teachers/simple')).catch(() => [])
  createOpen.value = true
}

async function create() {
  try {
    const t = await unwrap(http.POST('/api/collective/themes', { body: { title: form.value.title, subject: form.value.subject, description: form.value.description, memberUserIds: form.value.members } }))
    createOpen.value = false
    await load()
    open(t)
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

async function send() {
  if (!text.value.trim() || !active.value) return
  try {
    await unwrap(http.POST('/api/collective/themes/{id}/comments', { params: { path: { id: active.value.id! } }, body: { content: text.value } }))
    text.value = ''
    open(active.value)
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

async function chooseDraft() {
  myMats.value = await unwrap(http.GET('/api/prep/materials', { params: { query: {} } }))
}

async function setDraft(m: Schemas['TcMaterial']) {
  active.value = await unwrap(http.POST('/api/collective/themes/{id}/main-draft', { params: { path: { id: active.value!.id! } }, body: { materialId: m.id! } }))
  myMats.value = []
  ElMessage.success('主备稿已上传')
}

async function secondPrep() {
  const m = await unwrap(http.POST('/api/collective/themes/{id}/second-prep', { params: { path: { id: active.value!.id! } }, body: {} }))
  ElMessage.success(`已复制到你的备课资料：${m.fileName}`)
}

async function finish() {
  active.value = await unwrap(http.POST('/api/collective/themes/{id}/finish', { params: { path: { id: active.value!.id! } } }))
  load()
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader title="集体备课" icon="研" color="var(--jb-c-teaching)" desc="学科组围绕一个主题研讨：主备人上传主备稿，成员研讨发言，结束后可二次备课">
      <el-button v-if="auth.hasPerm('collective:create')" type="primary" @click="openCreate">发布研讨主题</el-button>
    </PageHeader>
    <div class="col">
      <el-card class="col__list" header="我参与的主题">
        <el-empty v-if="!themes.length" description="暂无研讨主题" :image-size="60" />
        <a v-for="t in themes" :key="t.id" class="theme" :class="{ on: active?.id === t.id }" @click="open(t)">
          <b>{{ t.title }}</b>
          <span class="jb-muted">{{ t.themeStatus === 'ONGOING' ? '研讨中' : '已结束' }}，{{ fmtTime(t.createdAt) }}</span>
        </a>
      </el-card>
      <el-card v-if="active" class="col__main">
        <template #header>
          <div class="jb-between">
            <strong>{{ active.title }}</strong>
            <div class="jb-row">
              <el-button v-if="active.organizerUserId === auth.me?.userId && active.themeStatus === 'ONGOING'" size="small" @click="chooseDraft">上传主备稿</el-button>
              <el-button v-if="active.mainMaterialId" size="small" @click="secondPrep">二次备课</el-button>
              <el-button v-if="active.organizerUserId === auth.me?.userId && active.themeStatus === 'ONGOING'" size="small" type="warning" @click="finish">结束研讨</el-button>
            </div>
          </div>
        </template>
        <p class="jb-muted">{{ active.description || '（无说明）' }}　主备稿：{{ active.mainMaterialId ? '已上传' : '未上传' }}</p>
        <div class="stats"><el-tag v-for="(n, uid) in stats" :key="uid" effect="plain">{{ names[uid] ?? `成员${String(uid).slice(-4)}` }} 发言 {{ n }}</el-tag></div>
        <div class="talk">
          <div v-for="c in comments" :key="c.id" class="msg"><b>{{ names[c.userId!] ?? '成员' }}</b><span>{{ c.content }}</span><small class="jb-muted">{{ fmtTime(c.createdAt) }}</small></div>
          <el-empty v-if="!comments.length" description="还没有发言" :image-size="50" />
        </div>
        <div v-if="active.themeStatus === 'ONGOING'" class="send"><el-input v-model="text" placeholder="发表研讨意见" @keyup.enter="send" /><el-button type="primary" @click="send">发送</el-button></div>
      </el-card>
      <el-card v-else class="col__main"><el-empty description="选择左侧主题查看研讨" /></el-card>
    </div>

    <el-dialog v-model="createOpen" title="发布研讨主题" width="560px">
      <el-form label-width="80px">
        <el-form-item label="主题"><el-input v-model="form.title" /></el-form-item>
        <el-form-item label="学科"><el-input v-model="form.subject" placeholder="如：数学" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="form.description" type="textarea" /></el-form-item>
        <el-form-item label="参与教师">
          <el-select v-model="form.members" multiple filterable style="width: 100%">
            <el-option v-for="t in teachers" :key="t.userId" :label="`${t.name}（${t.subjects ?? ''}）`" :value="t.userId!" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="createOpen = false">取消</el-button><el-button type="primary" @click="create">发布</el-button></template>
    </el-dialog>
    <el-dialog :model-value="myMats.length > 0" title="选择主备稿（来自我的备课资料）" width="560px" @close="myMats = []">
      <el-table :data="myMats" max-height="400">
        <el-table-column prop="fileName" label="文件" />
        <el-table-column width="90"><template #default="{ row }"><el-button size="small" type="primary" @click="setDraft(row)">选用</el-button></template></el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<style scoped>
.col { display: flex; gap: 16px; }
.col__list { width: 300px; flex-shrink: 0; align-self: flex-start; }
.col__main { flex: 1; min-width: 0; }
.theme { display: flex; flex-direction: column; gap: 4px; padding: 10px; border-radius: 8px; cursor: pointer; }
.theme span { font-size: 12px; }
.theme.on, .theme:hover { background: var(--jb-brand-light); }
.stats { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 12px; }
.talk { max-height: 420px; overflow: auto; display: flex; flex-direction: column; gap: 10px; }
.msg { display: flex; gap: 10px; align-items: baseline; padding: 8px 12px; background: var(--jb-brand-soft); border-radius: 8px; }
.msg span { flex: 1; }
.send { display: flex; gap: 8px; margin-top: 12px; }
</style>
