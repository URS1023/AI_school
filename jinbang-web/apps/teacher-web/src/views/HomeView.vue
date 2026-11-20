<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { http, unwrap, type ApiError, type Schemas } from '@jb/api'
import { useAuth } from '@jb/auth'
import { flattenMenus } from '@jb/ui'

/** 首页：问候与当前身份、金币与成长等级、签到；快捷入口取自当前身份的菜单（没有的功能不出现）。 */
const auth = useAuth()
const router = useRouter()
const account = ref<Schemas['Summary'] | null>(null)
const signing = ref(false)

const entries = computed(() =>
  flattenMenus(auth.menus).filter((m) => !m.children?.length && m.path && m.path !== '/home' && !m.comingSoon).slice(0, 12),
)
const isVisitor = computed(() => auth.identity?.type === 'VISITOR_TEACHER')

async function load() {
  account.value = await unwrap(http.GET('/api/account/me'))
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

onMounted(() => load().catch(() => undefined))
</script>

<template>
  <div class="home">
    <el-card class="home__hello">
      <div class="home__row">
        <div>
          <h2 data-test="hello">你好，{{ auth.me?.nickname }}</h2>
          <p class="muted">当前身份：{{ auth.identities.find((i) => i.current)?.displayName }}</p>
          <el-alert v-if="isVisitor" type="info" :closable="false" show-icon
                    title="你目前是游客教师：可以使用资源、备课和 AI 助手；加入学校后可使用考试阅卷、学情等功能。" />
        </div>
        <div v-if="account" class="home__stats">
          <el-statistic title="金榜币" :value="account.balance ?? 0" data-test="balance" />
          <el-statistic title="冻结中" :value="account.frozen ?? 0" />
          <div>
            <div class="muted">成长等级</div>
            <strong>{{ account.levelName }}</strong>
            <div class="muted small">经验 {{ account.exp }}，距下一级还差 {{ account.expToNext }}</div>
          </div>
          <el-button type="primary" :disabled="account.signedToday" :loading="signing" @click="signIn">
            {{ account.signedToday ? `已签到 · 连续 ${account.continuousDays} 天` : '签到' }}
          </el-button>
        </div>
      </div>
    </el-card>
    <el-card header="快捷入口">
      <div class="home__entries">
        <el-button v-for="m in entries" :key="m.id" @click="router.push(m.path!)">{{ m.name }}</el-button>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.home { display: flex; flex-direction: column; gap: 16px; }
.home__row { display: flex; justify-content: space-between; gap: 24px; align-items: center; }
.home__row h2 { margin: 0 0 6px; }
.home__stats { display: flex; gap: 32px; align-items: center; }
.home__entries { display: flex; flex-wrap: wrap; gap: 12px; }
.home__entries .el-button { margin: 0; }
.muted { color: var(--jb-text-secondary); margin: 0 0 8px; }
.small { font-size: 12px; }
</style>
