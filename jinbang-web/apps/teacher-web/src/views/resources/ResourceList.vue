<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http, postForm, unwrap, type ApiError, type Schemas } from '@jb/api'
import { useAuth } from '@jb/auth'
import { FilterChips, PageHeader, PeriodSubject, fmtTime, openLink, useDict, usePaged, usePeriodSubject, type FilterRow } from '@jb/ui'

/**
 * 资源列表（PRD 3.2）：平台资源 / 校本资源共用。学段学科切换、类型 / 等级筛选、综合 / 下载 / 浏览排序、
 * 资源篮、收藏、下载（付费资源首次下载扣币，买断后永久可下）。校本资源可上传并提交本校审核。
 */
const props = defineProps<{ scope: 'PLATFORM' | 'SCHOOL' }>()
type Res = Schemas['ResResource']

const router = useRouter()
const route = useRoute()
const auth = useAuth()
const ps = usePeriodSubject()
const { items: types, label: typeLabel } = useDict('resource_type')
const { items: levels, label: levelLabel } = useDict('platform_level')
const filters = ref<Record<string, string | number | undefined>>({})
const keyword = ref((route.query.keyword as string) ?? '')
const sort = ref('')
const basket = ref<Schemas['ResBasket'][]>([])
const basketOpen = ref(false)
const uploadOpen = ref(false)
const upload = ref({ title: '', resType: 'COURSEWARE', summary: '', file: null as File | null })

const rows = computed<FilterRow[]>(() => [
  { key: 'resType', label: '类型', options: types.value.map((t) => ({ label: t.dictLabel!, value: t.dictValue! })) },
  ...(props.scope === 'PLATFORM'
    ? [{ key: 'level', label: '等级', options: levels.value.map((t) => ({ label: t.dictLabel!, value: t.dictValue! })) }]
    : []),
])

const { query, rows: list, total, loading, load } = usePaged<Res, Record<string, unknown>>(
  (q) => unwrap(http.GET('/api/resources', {
    params: { query: {
      scope: props.scope, period: ps.period, subject: ps.subject, keyword: keyword.value || undefined, sort: sort.value || undefined,
      resType: filters.value.resType as string | undefined, level: filters.value.level as string | undefined,
      pageNum: q.pageNum, pageSize: q.pageSize,
    } },
  })),
  {},
  12,
)

async function loadBasket() {
  basket.value = await unwrap(http.GET('/api/basket/{itemType}', { params: { path: { itemType: 'RESOURCE' } } })).catch(() => [])
}

async function addBasket(r: Res) {
  await unwrap(http.POST('/api/basket/{itemType}/add', { params: { path: { itemType: 'RESOURCE' } }, body: { ids: [r.id!] } }))
  ElMessage.success('已加入资源篮')
  loadBasket()
}

async function removeBasket(ids: string[]) {
  await unwrap(http.POST('/api/basket/{itemType}/remove', { params: { path: { itemType: 'RESOURCE' } }, body: { ids } }))
  loadBasket()
}

async function favorite(r: Res) {
  await unwrap(http.POST('/api/favorites', { body: { targetType: 'RESOURCE', targetId: r.id!, on: true } }))
  ElMessage.success('已收藏')
}

async function download(r: Res) {
  try {
    if (r.resLevel !== 'FREE' && props.scope === 'PLATFORM') {
      await ElMessageBox.confirm(`「${r.title}」为${levelLabel(r.resLevel)}资源，首次下载将消耗 ${r.priceCoins} 金榜币，之后可永久下载。`, '下载确认')
    }
    const d = await unwrap(http.POST('/api/resources/{id}/download', { params: { path: { id: r.id! } } }))
    openLink(String(d.url))
    if (Number(d.paidCoins) > 0) ElMessage.success(`已扣除 ${d.paidCoins} 金榜币`)
  } catch (e) {
    if (e !== 'cancel') ElMessage.error((e as ApiError).message)
  }
}

async function submitUpload() {
  if (!upload.value.title || !upload.value.file) return ElMessage.warning('请填写标题并选择文件')
  const form = new FormData()
  form.append('meta', new Blob([JSON.stringify({
    scope: 'SCHOOL', title: upload.value.title, resType: upload.value.resType, summary: upload.value.summary,
    period: ps.period, subject: ps.subject,
  })], { type: 'application/json' }))
  form.append('file', upload.value.file)
  try {
    const r = await postForm<Res>('/api/resources', form)
    await unwrap(http.POST('/api/resources/{id}/submit', { params: { path: { id: r.id! } } }))
    ElMessage.success('已提交，学科组长或学校管理员审核通过后发布')
    uploadOpen.value = false
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

onMounted(() => {
  load()
  loadBasket()
})
</script>

<template>
  <div>
    <PageHeader :title="props.scope === 'PLATFORM' ? '平台资源' : '校本资源'" icon="资"
                :desc="props.scope === 'PLATFORM' ? '按教材同步的课件、教案、学案、试卷与微课，支持资源篮批量取用' : '本校老师分享、经审核发布的教学资源，仅本校可见'">
      <PeriodSubject v-model:period="ps.period" v-model:subject="ps.subject" @update:period="load(true)" @update:subject="load(true)" />
      <el-badge :value="basket.length" :hidden="!basket.length">
        <el-button @click="basketOpen = true">资源篮</el-button>
      </el-badge>
      <el-button v-if="props.scope === 'SCHOOL'" type="primary" @click="uploadOpen = true">分享资源</el-button>
    </PageHeader>

    <FilterChips v-model="filters" :rows="rows" @change="load(true)" />

    <div class="bar">
      <el-radio-group v-model="sort" @change="load(true)">
        <el-radio-button value="">综合</el-radio-button>
        <el-radio-button value="download">下载最多</el-radio-button>
        <el-radio-button value="view">浏览最多</el-radio-button>
      </el-radio-group>
      <el-input v-model="keyword" placeholder="搜索资源标题" clearable style="width: 280px" @keyup.enter="load(true)" @clear="load(true)" />
    </div>

    <div v-loading="loading" class="list">
      <el-empty v-if="!loading && !list.length" description="暂无资源" />
      <div v-for="r in list" :key="r.id" class="res" data-test="resource-item">
        <div class="res__type">{{ (r.fileExt ?? typeLabel(r.resType)).toUpperCase().slice(0, 4) }}</div>
        <div class="res__main">
          <a class="res__title" @click="router.push(`${props.scope === 'PLATFORM' ? '/resources/platform' : '/resources/school'}/${r.id}`)">{{ r.title }}</a>
          <div class="jb-muted res__meta">
            <el-tag size="small" effect="plain">{{ typeLabel(r.resType) }}</el-tag>
            <el-tag v-if="r.resLevel !== 'FREE'" size="small" type="warning" effect="plain">{{ levelLabel(r.resLevel) }} · {{ r.priceCoins }} 币</el-tag>
            <span>下载 {{ r.downloadCount }}</span><span>浏览 {{ r.viewCount }}</span><span>{{ fmtTime(r.publishedAt) }}</span>
          </div>
        </div>
        <div class="res__ops">
          <el-button size="small" @click="favorite(r)">收藏</el-button>
          <el-button size="small" @click="addBasket(r)">加入资源篮</el-button>
          <el-button size="small" type="primary" @click="download(r)">下载</el-button>
        </div>
      </div>
    </div>
    <el-pagination v-model:current-page="query.pageNum" :page-size="query.pageSize" :total="total" layout="prev, pager, next, total" @current-change="load()" />

    <el-drawer v-model="basketOpen" title="资源篮" size="420px">
      <el-empty v-if="!basket.length" description="资源篮空空如也" />
      <div v-else>
        <p class="jb-muted">共 {{ basket.length }} 个资源。在资源列表下载，或在「独立备课 → 找资源」中取用。</p>
        <el-button size="small" @click="removeBasket([])">清空</el-button>
        <ul class="basket"><li v-for="b in basket" :key="b.id">资源 #{{ b.itemId }} <el-button link type="danger" @click="removeBasket([b.itemId!])">移出</el-button></li></ul>
      </div>
    </el-drawer>

    <el-dialog v-model="uploadOpen" title="分享到校本资源" width="520px">
      <el-form label-width="80px">
        <el-form-item label="标题"><el-input v-model="upload.title" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="upload.resType"><el-option v-for="t in types" :key="t.dictValue" :label="t.dictLabel" :value="t.dictValue!" /></el-select>
        </el-form-item>
        <el-form-item label="简介"><el-input v-model="upload.summary" type="textarea" /></el-form-item>
        <el-form-item label="文件"><input type="file" @change="upload.file = ($event.target as HTMLInputElement).files?.[0] ?? null" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="uploadOpen = false">取消</el-button>
        <el-button type="primary" :disabled="!auth.hasPerm('school:resource')" @click="submitUpload">提交审核</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.bar { display: flex; justify-content: space-between; margin: 14px 0; }
.list { display: flex; flex-direction: column; gap: 10px; min-height: 120px; }
.res { display: flex; align-items: center; gap: 16px; padding: 14px 18px; background: var(--jb-surface); border-radius: var(--jb-radius); border: 1px solid var(--jb-border); }
.res__type { width: 48px; height: 56px; border-radius: 8px; display: grid; place-items: center; font-weight: 700; font-size: 12px; color: var(--jb-brand-dark); background: var(--jb-brand-light); }
.res__main { flex: 1; min-width: 0; }
.res__title { font-weight: 600; cursor: pointer; color: var(--jb-text); }
.res__title:hover { color: var(--jb-brand); }
.res__meta { display: flex; gap: 10px; align-items: center; margin-top: 6px; font-size: 12px; }
.basket { padding-left: 18px; line-height: 2; }
.el-pagination { margin-top: 14px; justify-content: flex-end; }
</style>
