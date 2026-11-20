<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { http, unwrap, type ApiError, type Schemas } from '@jb/api'
import { useAuth } from '@jb/auth'
import { fmtTime } from '@jb/ui'

/**
 * 首页（PRD 3.1）：搜索资源与题目；模块入口按当前身份的菜单出现；金币、等级、签到；公告与资讯（运营 CMS）。
 */
const auth = useAuth()
const router = useRouter()
const account = ref<Schemas['Summary'] | null>(null)
const notices = ref<Schemas['CmsContent'][]>([])
const news = ref<Schemas['CmsContent'][]>([])
const searchType = ref<'resource' | 'question'>('resource')
const keyword = ref('')
const signing = ref(false)

const MODULE_COLOR: Record<string, string> = {
  '/resources': 'var(--jb-c-resource)', '/teaching': 'var(--jb-c-teaching)', '/ai': 'var(--jb-c-ai)',
  '/exam': 'var(--jb-c-exam)', '/learning': 'var(--jb-c-learning)',
}
const modules = computed(() => auth.menus.filter((m) => m.children?.length && MODULE_COLOR[m.path ?? '']))
const isVisitor = computed(() => auth.identity?.type === 'VISITOR_TEACHER')

function search() {
  const q = keyword.value.trim()
  if (searchType.value === 'resource') router.push({ path: '/resources/platform', query: q ? { keyword: q } : {} })
  else router.push({ path: '/resources/questions', query: q ? { keyword: q } : {} })
}

async function signIn() {
  signing.value = true
  try {
    account.value = await unwrap(http.POST('/api/account/sign-in'))
    ElMessage.success('签到成功')
  } catch (e) {
    ElMessage.warning((e as ApiError).message)
  } finally {
    signing.value = false
  }
}

onMounted(async () => {
  account.value = await unwrap(http.GET('/api/account/me')).catch(() => null)
  notices.value = await unwrap(http.GET('/api/open/cms/{type}', { params: { path: { type: 'NOTICE' } } })).catch(() => [])
  news.value = await unwrap(http.GET('/api/open/cms/{type}', { params: { path: { type: 'NEWS' } } })).catch(() => [])
})
</script>

<template>
  <div class="home">
    <section class="hero">
      <div class="hero__text">
        <h2 data-test="hello">你好，{{ auth.me?.nickname }}</h2>
        <p>{{ auth.identities.find((i) => i.current)?.displayName }}</p>
        <div class="search">
          <el-select v-model="searchType" style="width: 96px"><el-option label="资源" value="resource" /><el-option label="题目" value="question" /></el-select>
          <el-input v-model="keyword" placeholder="输入关键词，多个关键词用空格隔开" @keyup.enter="search" />
          <el-button type="warning" @click="search">搜索</el-button>
        </div>
      </div>
      <div v-if="account" class="hero__stats">
        <div><small>金榜币</small><b data-test="balance">{{ account.balance }}</b></div>
        <div><small>{{ account.levelName }}</small><b>{{ account.exp }}</b><small>距下一级 {{ account.expToNext }}</small></div>
        <el-button round :disabled="account.signedToday" :loading="signing" @click="signIn">{{ account.signedToday ? `已签到 ${account.continuousDays} 天` : '签到' }}</el-button>
      </div>
    </section>

    <el-alert v-if="isVisitor" type="info" show-icon :closable="false"
              title="你目前是游客教师：可以使用资源、题库、备课和 AI 工作台；加入学校后可使用作业、考试阅卷和学情分析。">
      <template #default><el-button link type="primary" @click="router.push('/personal/join')">申请加入学校 →</el-button></template>
    </el-alert>

    <section class="modules">
      <div v-for="m in modules" :key="m.id" class="mod" :style="{ '--c': MODULE_COLOR[m.path!] }">
        <h3>{{ m.name }}</h3>
        <div class="mod__links">
          <a v-for="c in m.children" :key="c.id" :class="{ soon: c.comingSoon }" @click="!c.comingSoon && router.push(c.path!)">
            {{ c.name }}<em v-if="c.comingSoon">即将上线</em>
          </a>
        </div>
      </div>
    </section>

    <section class="cols">
      <el-card header="平台公告">
        <el-empty v-if="!notices.length" description="暂无公告" :image-size="50" />
        <div v-for="n in notices" :key="n.id" class="news"><span>{{ n.title }}</span><small class="jb-muted">{{ fmtTime(n.publishAt) }}</small></div>
      </el-card>
      <el-card header="教育资讯">
        <el-empty v-if="!news.length" description="暂无资讯" :image-size="50" />
        <div v-for="n in news" :key="n.id" class="news"><span>{{ n.title }}</span><small class="jb-muted">{{ fmtTime(n.publishAt) }}</small></div>
      </el-card>
    </section>
  </div>
</template>

<style scoped>
.home { display: flex; flex-direction: column; gap: 16px; }
.hero {
  display: flex; justify-content: space-between; align-items: center; gap: 24px; padding: 28px 32px; border-radius: 16px; color: #fff;
  background: radial-gradient(circle at 85% 20%, rgba(232, 163, 61, 0.45), transparent 40%), linear-gradient(120deg, #0a5a4d, #0f7a68 55%, #2c9c86);
}
.hero__text { flex: 1; max-width: 680px; }
.hero h2 { margin: 0; font-size: 26px; }
.hero p { margin: 6px 0 16px; opacity: 0.85; }
.search { display: flex; gap: 0; background: #fff; border-radius: 10px; padding: 4px; }
.search :deep(.el-input__wrapper), .search :deep(.el-select__wrapper) { box-shadow: none; }
.hero__stats { display: flex; gap: 28px; align-items: center; }
.hero__stats > div { display: flex; flex-direction: column; }
.hero__stats b { font-size: 28px; }
.hero__stats small { opacity: 0.85; }
.modules { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 14px; }
.mod { background: var(--jb-surface); border-radius: var(--jb-radius); padding: 16px 18px; border-top: 4px solid var(--c); box-shadow: var(--jb-shadow); }
.mod h3 { margin: 0 0 10px; color: var(--c); font-size: 16px; }
.mod__links { display: flex; flex-wrap: wrap; gap: 8px 14px; }
.mod__links a { cursor: pointer; color: var(--jb-text); font-size: 13px; }
.mod__links a:hover { color: var(--c); }
.mod__links a.soon { color: var(--jb-text-secondary); cursor: default; }
.mod__links em { font-style: normal; font-size: 11px; margin-left: 3px; color: var(--jb-accent); }
.cols { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.news { display: flex; justify-content: space-between; padding: 8px 0; border-bottom: 1px dashed var(--jb-border); }
</style>
