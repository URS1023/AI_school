<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http, postForm, unwrap, type ApiError, type Schemas } from '@jb/api'
import { PageHeader, fmtTime, openLink } from '@jb/ui'

/**
 * 独立备课（PRD 4.1）：左侧「我的备课目录」树 + 回收站；右侧四个入口（智能备课 / 找资源 / 上传文件 / 新建文件）+ 资料列表。
 * 资料归个人（离职后保留）。AI 平台生成的课件会自动进入「AI 生成」目录。
 */
type Cat = Schemas['TcPrepCatalog']
type Mat = Schemas['TcMaterial']
const router = useRouter()
const cats = ref<Cat[]>([])
const current = ref<Cat | null>(null)
const recycle = ref(false)
const mats = ref<Mat[]>([])
const loading = ref(false)
const preview = ref<Schemas['Preview'] | null>(null)
const previewOpen = ref(false)
const versions = ref<Schemas['TcMaterialVersion'][]>([])
const versionsOf = ref<Mat | null>(null)
const pickOpen = ref(false)
const resources = ref<Schemas['ResResource'][]>([])
const fileInput = ref<HTMLInputElement>()

const ICON: Record<string, string> = { PPTX: 'PPT', DOCX: 'DOC', PDF: 'PDF', VIDEO: '视频', OTHER: '文件' }
const SOURCE: Record<string, string> = { UPLOAD: '上传', BLANK: '新建', RESOURCE: '平台资源', AI_PPT: 'AI 课件', AI_PLAN: 'AI 教案', COPY: '副本', COLLECTIVE: '集体备课' }

const tree = computed(() => {
  const by = new Map<string, Cat[]>()
  cats.value.forEach((c) => by.set(String(c.parentId ?? 0), [...(by.get(String(c.parentId ?? 0)) ?? []), c]))
  const build = (pid: string): { id: string; label: string; raw: Cat; children: unknown[] }[] =>
    (by.get(pid) ?? []).map((c) => ({ id: c.id!, label: c.catalogName!, raw: c, children: build(c.id!) }))
  return build('0')
})

async function loadCats() {
  cats.value = await unwrap(http.GET('/api/prep/catalogs'))
}

async function loadMats() {
  loading.value = true
  try {
    mats.value = await unwrap(http.GET('/api/prep/materials', { params: { query: { catalogId: recycle.value ? undefined : current.value?.id, recycle: recycle.value } } }))
  } finally {
    loading.value = false
  }
}

function pick(d: { raw: Cat }) {
  current.value = d.raw
  recycle.value = false
  loadMats()
}

function onCatalogCmd(c: string, cat: Cat) {
  if (c === 'sub') newCatalog(cat)
  else if (c === 'rename') renameCatalog(cat)
  else deleteCatalog(cat)
}

function showRecycle() {
  recycle.value = true
  current.value = null
  loadMats()
}

async function newCatalog(parent?: Cat) {
  const { value } = await ElMessageBox.prompt('目录名称', parent ? `在「${parent.catalogName}」下新建` : '新建目录')
  await unwrap(http.POST('/api/prep/catalogs', { body: { parentId: parent?.id, name: value } }))
  loadCats()
}

async function renameCatalog(c: Cat) {
  const { value } = await ElMessageBox.prompt('新名称', '重命名', { inputValue: c.catalogName })
  await unwrap(http.PUT('/api/prep/catalogs/{id}', { params: { path: { id: c.id! } }, body: { name: value } }))
  loadCats()
}

async function deleteCatalog(c: Cat) {
  await ElMessageBox.confirm(`删除目录「${c.catalogName}」？目录下的资料会进入回收站。`, '删除目录')
  try {
    await unwrap(http.POST('/api/prep/catalogs/{id}/delete', { params: { path: { id: c.id! } } }))
    if (current.value?.id === c.id) current.value = null
    loadCats()
    loadMats()
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

async function upload(e: Event) {
  const f = (e.target as HTMLInputElement).files?.[0]
  if (!f) return
  const form = new FormData()
  form.append('file', f)
  const q = current.value ? `?catalogId=${current.value.id}` : ''
  try {
    await postForm(`/api/prep/materials/upload${q}`, form)
    ElMessage.success('已上传')
    loadMats()
  } catch (err) {
    ElMessage.error((err as ApiError).message)
  } finally {
    if (fileInput.value) fileInput.value.value = ''
  }
}

async function blank(type: 'PPTX' | 'DOCX') {
  const { value } = await ElMessageBox.prompt('文件名称', type === 'PPTX' ? '新建 PPT' : '新建 Word')
  await unwrap(http.POST('/api/prep/materials/blank', { body: { catalogId: current.value?.id, name: value, type } }))
  loadMats()
}

async function openPicker() {
  const page = await unwrap(http.GET('/api/resources', { params: { query: { scope: 'PLATFORM', pageNum: 1, pageSize: 50 } } }))
  resources.value = page.rows ?? []
  pickOpen.value = true
}

async function takeResource(r: Schemas['ResResource']) {
  try {
    await unwrap(http.POST('/api/prep/materials/from-resource', { body: { catalogId: current.value?.id, resourceId: r.id! } }))
    ElMessage.success('已加入当前目录')
    pickOpen.value = false
    loadMats()
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

async function showPreview(m: Mat) {
  try {
    preview.value = await unwrap(http.GET('/api/prep/materials/{id}/preview', { params: { path: { id: m.id! } } }))
    if (preview.value.type === 'DOWNLOAD' || preview.value.type === 'PDF') {
      openLink(preview.value.urls![0])
    } else {
      previewOpen.value = true
    }
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

async function download(m: Mat) {
  openLink(String(await unwrap(http.POST('/api/prep/materials/{id}/download', { params: { path: { id: m.id! } } }))))
}

async function onMore(cmd: string, m: Mat) {
  try {
    if (cmd === 'rename') {
      const { value } = await ElMessageBox.prompt('新名称', '重命名', { inputValue: m.fileName })
      await unwrap(http.PUT('/api/prep/materials/{id}', { params: { path: { id: m.id! } }, body: { name: value } }))
    } else if (cmd === 'copy') {
      await unwrap(http.POST('/api/prep/materials/{id}/copy', { params: { path: { id: m.id! } } }))
    } else if (cmd === 'delete') {
      await unwrap(http.POST('/api/prep/materials/{id}/recycle', { params: { path: { id: m.id! }, query: { deleted: true } } }))
    } else if (cmd === 'restore') {
      await unwrap(http.POST('/api/prep/materials/{id}/recycle', { params: { path: { id: m.id! }, query: { deleted: false } } }))
    } else if (cmd === 'versions') {
      versionsOf.value = m
      versions.value = await unwrap(http.GET('/api/prep/materials/{id}/versions', { params: { path: { id: m.id! } } }))
      return
    }
    loadMats()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error((e as ApiError).message)
  }
}

async function restoreVersion(no: number) {
  await unwrap(http.POST('/api/prep/materials/{id}/versions/{no}/restore', { params: { path: { id: versionsOf.value!.id!, no } } }))
  ElMessage.success('已恢复为新版本')
  versionsOf.value = null
  loadMats()
}

async function purge() {
  await ElMessageBox.confirm('彻底删除回收站中的全部资料？此操作不可恢复。', '清空回收站', { type: 'warning' })
  const n = await unwrap(http.POST('/api/prep/recycle/purge'))
  ElMessage.success(`已彻底删除 ${n} 个文件`)
  loadMats()
}

onMounted(() => {
  loadCats()
  loadMats()
})
</script>

<template>
  <div>
    <PageHeader title="独立备课" icon="备" color="var(--jb-c-teaching)" desc="按教材整理个人备课资料；AI 生成的课件、教案自动归档到「AI 生成」目录" />
    <div class="prep">
      <aside class="prep__tree jb-card">
        <div class="jb-between"><strong>我的备课目录</strong><el-button size="small" text type="primary" @click="newCatalog()">+ 新建</el-button></div>
        <el-tree :data="tree" node-key="id" highlight-current default-expand-all :expand-on-click-node="false" empty-text="还没有目录" @node-click="pick">
          <template #default="{ data }">
            <span class="node">
              <span class="jb-ellipsis">{{ data.label }}</span>
              <el-dropdown trigger="click" @command="onCatalogCmd($event, data.raw)">
                <span class="node__more" @click.stop>⋮</span>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="sub">新建子目录</el-dropdown-item>
                    <el-dropdown-item command="rename">重命名</el-dropdown-item>
                    <el-dropdown-item command="delete">删除</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </span>
          </template>
        </el-tree>
        <a class="recycle" :class="{ on: recycle }" @click="showRecycle">回收站</a>
      </aside>
      <section class="prep__main">
        <div v-if="!recycle" class="entries">
          <button class="entry e1" type="button" @click="router.push({ path: '/teaching/smart', query: current ? { catalogId: current.id } : {} })"><b>智能备课</b><span>AI 生成大纲与课件</span></button>
          <button class="entry e2" type="button" @click="openPicker"><b>找资源</b><span>从平台资源取用</span></button>
          <button class="entry e3" type="button" @click="fileInput?.click()"><b>上传文件</b><span>PPT / Word / PDF / 视频</span></button>
          <el-dropdown trigger="click" @command="blank">
            <button class="entry e4" type="button"><b>新建文件</b><span>空白 PPT 或 Word</span></button>
            <template #dropdown><el-dropdown-menu><el-dropdown-item command="PPTX">新建 PPT</el-dropdown-item><el-dropdown-item command="DOCX">新建 Word</el-dropdown-item></el-dropdown-menu></template>
          </el-dropdown>
          <input ref="fileInput" type="file" hidden @change="upload" />
        </div>
        <el-card>
          <template #header>
            <div class="jb-between">
              <strong>{{ recycle ? '回收站' : current?.catalogName ?? '全部资料' }}</strong>
              <el-button v-if="recycle" size="small" type="danger" plain @click="purge">清空回收站</el-button>
            </div>
          </template>
          <el-table v-loading="loading" :data="mats" empty-text="这个目录还没有资料">
            <el-table-column label="文件" min-width="280">
              <template #default="{ row }">
                <div class="file"><span class="file__ico" :class="row.fileType">{{ ICON[row.fileType] ?? '文件' }}</span><a @click="showPreview(row)">{{ row.fileName }}</a></div>
              </template>
            </el-table-column>
            <el-table-column label="来源" width="100"><template #default="{ row }">{{ SOURCE[row.source] ?? row.source }}</template></el-table-column>
            <el-table-column label="版本" width="70"><template #default="{ row }">v{{ row.versionNo }}</template></el-table-column>
            <el-table-column label="时间" width="150"><template #default="{ row }">{{ fmtTime(row.updatedAt) }}</template></el-table-column>
            <el-table-column label="操作" width="210">
              <template #default="{ row }">
                <div v-if="!recycle" class="ops">
                  <el-button link type="primary" @click="showPreview(row)">预览</el-button>
                  <el-button link @click="download(row)">下载</el-button>
                  <el-dropdown trigger="click" @command="onMore($event, row)">
                    <el-button link>更多</el-button>
                    <template #dropdown>
                      <el-dropdown-menu>
                        <el-dropdown-item command="rename">重命名</el-dropdown-item>
                        <el-dropdown-item command="copy">复制</el-dropdown-item>
                        <el-dropdown-item command="versions">历史版本</el-dropdown-item>
                        <el-dropdown-item command="delete" divided>删除</el-dropdown-item>
                      </el-dropdown-menu>
                    </template>
                  </el-dropdown>
                </div>
                <el-button v-else link type="primary" @click="onMore('restore', row)">恢复</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </section>
    </div>

    <el-dialog v-model="previewOpen" title="预览" width="900px" top="4vh">
      <div v-if="preview?.type === 'IMAGES'" class="slides"><img v-for="u in preview.urls" :key="u" :src="u" alt="课件页面" /></div>
      <!-- eslint-disable-next-line vue/no-v-html -->
      <div v-else-if="preview?.type === 'HTML'" class="doc" v-html="preview.html || '<p>（空文档）</p>'" />
    </el-dialog>

    <el-dialog v-model="pickOpen" title="找资源（平台资源）" width="720px">
      <el-table :data="resources" max-height="460">
        <el-table-column prop="title" label="资源" />
        <el-table-column label="等级" width="90"><template #default="{ row }">{{ row.resLevel === 'FREE' ? '免费' : `${row.priceCoins} 币` }}</template></el-table-column>
        <el-table-column width="100"><template #default="{ row }"><el-button size="small" type="primary" @click="takeResource(row)">取用</el-button></template></el-table-column>
      </el-table>
    </el-dialog>

    <el-dialog :model-value="!!versionsOf" title="历史版本" width="480px" @close="versionsOf = null">
      <el-table :data="versions">
        <el-table-column label="版本" width="80"><template #default="{ row }">v{{ row.versionNo }}</template></el-table-column>
        <el-table-column label="保存时间"><template #default="{ row }">{{ fmtTime(row.createdAt) }}</template></el-table-column>
        <el-table-column width="100"><template #default="{ row }"><el-button v-if="row.versionNo !== versionsOf?.versionNo" link type="primary" @click="restoreVersion(row.versionNo)">恢复</el-button></template></el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<style scoped>
.prep { display: flex; gap: 16px; }
.prep__tree { width: 250px; flex-shrink: 0; align-self: flex-start; display: flex; flex-direction: column; gap: 10px; }
.node { flex: 1; display: flex; justify-content: space-between; min-width: 0; padding-right: 6px; }
.node__more { padding: 0 6px; cursor: pointer; color: var(--jb-text-secondary); }
.recycle { padding: 8px; border-top: 1px solid var(--jb-border); cursor: pointer; color: var(--jb-text-secondary); }
.recycle.on { color: var(--jb-brand); font-weight: 600; }
.prep__main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 16px; }
.entries { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; }
.entries :deep(.el-dropdown) { display: block; }
.entry { width: 100%; border: none; border-radius: var(--jb-radius); padding: 18px; text-align: left; cursor: pointer; color: #fff; display: flex; flex-direction: column; gap: 6px; }
.entry b { font-size: 16px; }
.entry span { font-size: 12px; opacity: 0.9; }
.e1 { background: linear-gradient(135deg, #0f7a68, #2fa38e); }
.e2 { background: linear-gradient(135deg, #3b6fd8, #6d93e6); }
.e3 { background: linear-gradient(135deg, #e0703a, #eea06d); }
.e4 { background: linear-gradient(135deg, #7a5bd6, #a189e4); }
.ops { display: flex; align-items: center; gap: 12px; white-space: nowrap; }
.ops .el-button + .el-button { margin-left: 0; }
.file { display: flex; align-items: center; gap: 10px; }
.file a { cursor: pointer; }
.file a:hover { color: var(--jb-brand); }
.file__ico { font-size: 11px; font-weight: 700; width: 34px; height: 34px; display: grid; place-items: center; border-radius: 6px; background: var(--jb-brand-light); color: var(--jb-brand-dark); }
.file__ico.PPTX { background: #fdebe2; color: #c4561e; }
.file__ico.DOCX { background: #e6edfb; color: #2f5bc0; }
.file__ico.PDF { background: #fbe6ec; color: #b23558; }
.slides { display: flex; flex-direction: column; gap: 12px; max-height: 76vh; overflow: auto; }
.slides img { width: 100%; border: 1px solid var(--jb-border); border-radius: 6px; }
.doc { max-height: 72vh; overflow: auto; line-height: 1.9; }
</style>
