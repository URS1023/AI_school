<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { http, unwrap, type ApiError, type Schemas } from '@jb/api'
import { useAuth } from '@jb/auth'
import { fmtTime } from '@jb/ui'

/**
 * 首页（PRD 3.1）：搜索资源与题目；「今天要处理」队列（待批阅的作业、阅卷中的考试）；
 * 模块目录按当前身份的菜单出现；金币、等级、签到；公告与资讯（运营 CMS）。
 */
interface Todo { key: string; kind: string; title: string; detail: string; path: string }
const auth = useAuth()
const router = useRouter()
const account = ref<Schemas['Summary'] | null>(null)
const notices = ref<Schemas['CmsContent'][]>([])
const news = ref<Schemas['CmsContent'][]>([])
const todos = ref<Todo[] | null>(null)
const searchType = ref<'resource' | 'question'>('resource')
const keyword = ref('')
const signing = ref(false)

const MODULES = ['/resources', '/teaching', '/ai', '/exam', '/learning']
const modules = computed(() => auth.menus.filter((m) => m.children?.length && MODULES.includes(m.path ?? '')))
const isVisitor = computed(() => auth.identity?.type === 'VISITOR_TEACHER')
const canMark = computed(() => auth.hasPerm('homework:mark') || auth.hasPerm('exam:mark'))

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

async function loadTodos() {
  if (!canMark.value) return
  const [hws, exams] = await Promise.all([
    unwrap(http.GET('/api/exam/homeworks', { params: { query: {} } })).catch(() => []),
    unwrap(http.GET('/api/exam/exams', { params: { query: {} } })).catch(() => []),
  ])
  const list: Todo[] = []
  hws.forEach((c) => {
    const h = c.homework!
    const s = c.stats
    if (['PUBLISHED', 'COLLECTING', 'MARKING', 'CLOSED'].includes(h.hwStatus ?? '') && (s?.submitted ?? 0) > (s?.marked ?? 0)) {
      list.push({ key: `h${h.id}`, kind: '作业', title: h.title!, detail: `${(s?.submitted ?? 0) - (s?.marked ?? 0)} 份待批阅`, path: `/exam/marking/homework/${h.id}` })
    }
    if ((s?.exceptions ?? 0) > 0) {
      list.push({ key: `e${h.id}`, kind: '作业', title: h.title!, detail: `${s?.exceptions} 份异常卷待指认`, path: `/exam/homework/${h.id}` })
    }
  })
  exams.filter((e) => ['MARKING', 'SCORE_AUDIT'].includes(e.examStatus ?? '')).forEach((e) => {
    list.push({ key: `x${e.id}`, kind: '考试', title: e.examName!, detail: e.examStatus === 'MARKING' ? '阅卷中' : '等待成绩审核', path: e.examStatus === 'MARKING' ? `/exam/marking/exam/${e.id}` : `/exam/exams/${e.id}` })
  })
  todos.value = list.slice(0, 8)
}

onMounted(async () => {
  account.value = await unwrap(http.GET('/api/account/me')).catch(() => null)
  loadTodos()
  notices.value = await unwrap(http.GET('/api/open/cms/{type}', { params: { path: { type: 'NOTICE' } } })).catch(() => [])
  news.value = await unwrap(http.GET('/api/open/cms/{type}', { params: { path: { type: 'NEWS' } } })).catch(() => [])
})
</script>

<template>
  <div class="home">
    <section class="top">
      <div class="top__who">
        <h1 data-test="hello">你好，{{ auth.me?.nickname }}</h1>
        <p class="jb-muted">{{ auth.identities.find((i) => i.current)?.displayName }}</p>
      </div>
      <div class="search">
        <el-select v-model="searchType" style="width: 92px"><el-option label="资源" value="resource" /><el-option label="题目" value="question" /></el-select>
        <el-input v-model="keyword" placeholder="输入关键词，多个关键词用空格隔开" @keyup.enter="search" />
        <el-button type="primary" @click="search">搜索</el-button>
      </div>
    </section>

    <el-alert v-if="isVisitor" type="info" show-icon :closable="false"
              title="你目前是游客教师：可以使用资源、题库、备课和 AI 工作台；加入学校后可使用作业、考试阅卷和学情分析。">
      <template #default><el-button link type="primary" @click="router.push('/personal/join')">申请加入学校</el-button></template>
    </el-alert>

    <div class="cols">
      <section v-if="canMark" class="queue jb-card">
        <header class="jb-between"><h2>今天要处理</h2><el-button link @click="router.push('/exam/marking')">全部阅卷任务</el-button></header>
        <ol v-if="todos?.length" class="queue__list">
          <li v-for="t in todos" :key="t.key">
            <button type="button" @click="router.push(t.path)">
              <span class="kind">{{ t.kind }}</span>
              <span class="title jb-ellipsis">{{ t.title }}</span>
              <span class="detail">{{ t.detail }}</span>
            </button>
          </li>
        </ol>
        <p v-else-if="todos" class="queue__empty">没有待批阅的作业或考试。布置作业后，学生提交的答卷会出现在这里。</p>
        <div v-else class="queue__skeleton"><i v-for="k in 3" :key="k" /></div>
      </section>

      <aside class="ledger jb-card">
        <div class="ledger__row"><span>金榜币</span><b class="jb-num" data-test="balance">{{ account?.balance ?? '' }}</b></div>
        <div class="ledger__row"><span>等级</span><b>{{ account?.levelName }}</b></div>
        <div class="ledger__row"><span>经验</span><b class="jb-num">{{ account?.exp }}</b></div>
        <p class="jb-muted small">距下一级还差 {{ account?.expToNext }} 经验</p>
        <el-button :disabled="!account || account.signedToday" :loading="signing" @click="signIn">
          {{ account?.signedToday ? `已签到，连续 ${account.continuousDays} 天` : '签到' }}
        </el-button>
        <el-button link @click="router.push('/personal/coins')">金币明细</el-button>
      </aside>
    </div>

    <section class="dir">
      <div v-for="m in modules" :key="m.id" class="dir__col">
        <h3>{{ m.name }}</h3>
        <ul>
          <li v-for="c in m.children" :key="c.id">
            <button type="button" :disabled="c.comingSoon" @click="router.push(c.path!)">{{ c.name }}</button>
            <small v-if="c.comingSoon">即将上线</small>
          </li>
        </ul>
      </div>
    </section>

    <section class="cols cols--even">
      <div class="board">
        <h3>平台公告</h3>
        <p v-if="!notices.length" class="jb-muted small">暂无公告</p>
        <div v-for="n in notices" :key="n.id" class="board__row"><span>{{ n.title }}</span><small class="jb-muted jb-num">{{ fmtTime(n.publishAt) }}</small></div>
      </div>
      <div class="board">
        <h3>教育资讯</h3>
        <p v-if="!news.length" class="jb-muted small">暂无资讯</p>
        <div v-for="n in news" :key="n.id" class="board__row"><span>{{ n.title }}</span><small class="jb-muted jb-num">{{ fmtTime(n.publishAt) }}</small></div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.home { display: flex; flex-direction: column; gap: 24px; max-width: 1240px; }
.top { display: flex; align-items: flex-end; justify-content: space-between; gap: 32px; padding-bottom: 18px; border-bottom: 1px solid var(--color-ink-2); }
.top h1 { margin: 0; font-size: var(--text-2xl); }
.top p { margin: 6px 0 0; font-size: var(--text-sm); }
.search { display: flex; gap: 8px; width: min(560px, 50%); }
.cols { display: grid; grid-template-columns: minmax(0, 1fr) 280px; gap: 20px; align-items: start; }
.cols--even { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 32px; }
h2 { margin: 0; font-size: var(--text-lg); }
h3 { margin: 0 0 10px; font-size: var(--text-md); }
.small { font-size: var(--text-sm); margin: 0; }
.queue header { padding-bottom: 10px; border-bottom: 1px solid var(--color-ink-2); }
.queue__list { list-style: none; margin: 0; padding: 0; }
.queue__list li + li { border-top: 1px solid var(--color-rule); }
.queue__list button {
  all: unset; box-sizing: border-box; width: 100%; min-height: 44px; display: grid; grid-template-columns: 48px minmax(0, 1fr) auto; gap: 12px; align-items: center;
  padding: 0 6px; cursor: pointer; transition: background-color var(--dur-micro);
}
.queue__list button:hover { background: var(--color-paper-2); }
.queue__list button:focus-visible { outline: 2px solid var(--color-focus); outline-offset: -2px; }
.kind { font-size: var(--text-xs); color: var(--color-muted); }
.title { font-weight: 500; }
.detail { font-size: var(--text-sm); color: var(--color-ink-2); }
.queue__empty { color: var(--color-muted); margin: 14px 0 4px; font-size: var(--text-sm); }
.queue__skeleton { display: flex; flex-direction: column; gap: 12px; padding: 14px 0; }
.queue__skeleton i { height: 14px; border-radius: 4px; background: var(--color-paper-3); }
.ledger { display: flex; flex-direction: column; gap: 10px; }
.ledger__row { display: flex; justify-content: space-between; align-items: baseline; padding-bottom: 8px; border-bottom: 1px solid var(--color-rule); }
.ledger__row span { color: var(--color-muted); font-size: var(--text-sm); }
.ledger__row b { font-size: var(--text-lg); font-weight: 600; }
.ledger .el-button { margin-left: 0; }
.dir { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 24px; padding-top: 18px; border-top: 1px solid var(--color-rule); }
.dir ul { list-style: none; margin: 0; padding: 0; }
.dir li { display: flex; align-items: center; gap: 6px; min-height: 30px; }
.dir button { all: unset; cursor: pointer; color: var(--color-ink-2); text-decoration: underline; text-decoration-color: transparent; text-underline-offset: 0.2em; transition: text-decoration-color var(--dur-micro); }
.dir button:hover:not(:disabled) { text-decoration-color: var(--color-accent); color: var(--color-ink); }
.dir button:focus-visible { outline: 2px solid var(--color-focus); outline-offset: 2px; }
.dir button:disabled { color: var(--color-muted); cursor: default; }
.dir small { font-size: var(--text-xs); color: var(--color-muted); }
.board { border-top: 1px solid var(--color-ink-2); padding-top: 12px; }
.board__row { display: flex; justify-content: space-between; gap: 16px; padding: 9px 0; border-bottom: 1px solid var(--color-rule); }
</style>
