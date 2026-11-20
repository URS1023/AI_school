<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Bell, ChatDotRound } from '@element-plus/icons-vue'
import { http, unwrap, type ApiError } from '@jb/api'
import { useAuth, type MenuItem } from '@jb/auth'
import { findMenu } from '../menu'
import BrandMark from './BrandMark.vue'

/**
 * 通用布局：顶部一级导航（按当前身份下发，R1）+ 左侧二级菜单；「即将上线」灰显带角标（AC-17）；
 * 右侧消息铃铛、个人中心、大字号模式、身份切换（R3）、退出；右下角 AI 助手入口。
 */
const props = defineProps<{
  appName: string
  rootFilter?: (m: MenuItem) => boolean
  homePath?: string
  personalPath?: string
  aiPath?: string
}>()

const auth = useAuth()
const route = useRoute()
const router = useRouter()
const unread = ref(0)
const large = ref(document.documentElement.classList.contains('jb-large'))
let timer: number | undefined

const roots = computed(() => (props.rootFilter ? auth.menus.filter(props.rootFilter) : auth.menus))
const activeRoot = computed(() =>
  roots.value.find((r) => route.path === r.path || route.path.startsWith(`${r.path}/`) ||
    r.children?.some((c) => c.path && (route.path === c.path || route.path.startsWith(`${c.path}/`)))),
)
const sider = computed(() => activeRoot.value?.children ?? [])
const activeChild = computed(() => findMenu(auth.menus, route.path)?.path ?? route.path)
const currentIdentity = computed(() => auth.identities.find((i) => i.current))

function goRoot(m: MenuItem) {
  const first = m.children?.find((c) => !c.comingSoon && c.path)
  router.push(first?.path ?? m.path ?? '/')
}

function go(m: MenuItem) {
  if (m.comingSoon) {
    ElMessage.info(`「${m.name}」即将上线，敬请期待`)
    return
  }
  if (m.path) router.push(m.path)
}

async function loadUnread() {
  try {
    unread.value = await unwrap(http.GET('/api/messages/unread-count'))
  } catch {
    /* 铃铛失败不打扰用户 */
  }
}

async function onCommand(cmd: string) {
  if (cmd === 'logout') {
    await auth.logout()
    router.replace('/login')
  } else if (cmd === 'large') {
    large.value = !large.value
    document.documentElement.classList.toggle('jb-large', large.value)
  } else if (cmd === 'password') {
    router.push('/change-password')
  } else if (cmd === 'personal' && props.personalPath) {
    router.push(props.personalPath)
  } else if (cmd.startsWith('id:')) {
    try {
      await auth.switchIdentity(cmd.slice(3))
      ElMessage.success('已切换身份')
      router.replace(props.homePath ?? '/')
    } catch (e) {
      ElMessage.error((e as ApiError).message)
    }
  }
}

onMounted(() => {
  loadUnread()
  timer = window.setInterval(loadUnread, 60_000)
})
onBeforeUnmount(() => window.clearInterval(timer))
</script>

<template>
  <div class="jb-layout">
    <header class="jb-layout__header">
      <div class="jb-layout__brand" @click="router.push(props.homePath ?? '/')"><BrandMark :text="props.appName" /></div>
      <nav class="jb-layout__nav" data-test="nav">
        <a v-for="m in roots" :key="m.id" :class="{ active: activeRoot?.id === m.id || (!m.children?.length && route.path === m.path) }"
           @click="m.children?.length ? goRoot(m) : go(m)">{{ m.name }}</a>
      </nav>
      <div class="jb-layout__right">
        <el-badge :value="unread" :hidden="!unread" :max="99">
          <el-button :icon="Bell" circle text aria-label="消息" @click="router.push('/messages')" />
        </el-badge>
        <el-dropdown trigger="click" @command="onCommand">
          <span class="jb-layout__user" data-test="user-menu">
            <span class="jb-layout__avatar">{{ (auth.me?.nickname ?? '?').slice(0, 1) }}</span>
            <span class="jb-layout__who">
              {{ auth.me?.nickname }}
              <small>{{ currentIdentity?.displayName }}</small>
            </span>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-if="props.personalPath" command="personal">个人中心</el-dropdown-item>
              <el-dropdown-item disabled divided>切换身份</el-dropdown-item>
              <el-dropdown-item v-for="i in auth.identities" :key="i.identityId" :command="`id:${i.identityId}`"
                                :disabled="i.current" data-test="identity-item">
                {{ i.current ? '✓ ' : '' }}{{ i.displayName }}
              </el-dropdown-item>
              <el-dropdown-item divided command="large">{{ large ? '关闭' : '开启' }}大字号模式</el-dropdown-item>
              <el-dropdown-item command="password">修改密码</el-dropdown-item>
              <el-dropdown-item command="logout" data-test="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>
    <div class="jb-layout__body">
      <aside v-if="sider.length" class="jb-layout__sider">
        <div class="jb-layout__sider-title">{{ activeRoot?.name }}</div>
        <a v-for="c in sider" :key="c.id" class="jb-layout__item"
           :class="{ active: activeChild === c.path, disabled: c.comingSoon }"
           :data-test="c.comingSoon ? 'coming-soon' : undefined" @click="go(c)">
          <span>{{ c.name }}</span>
          <el-tag v-if="c.comingSoon" size="small" type="warning" effect="plain" round>即将上线</el-tag>
        </a>
      </aside>
      <main class="jb-layout__main">
        <router-view />
      </main>
    </div>
    <el-button v-if="props.aiPath && auth.hasPerm('ai:use')" class="jb-layout__ai" type="primary" round :icon="ChatDotRound"
               @click="router.push(props.aiPath)">AI 助手</el-button>
  </div>
</template>

<style scoped>
.jb-layout { min-height: 100%; display: flex; flex-direction: column; }
.jb-layout__header {
  position: sticky; top: 0; z-index: 20; display: flex; align-items: center; gap: 28px; height: var(--jb-header-h);
  padding: 0 24px; background: var(--jb-surface); border-bottom: 1px solid var(--jb-border);
}
.jb-layout__brand { cursor: pointer; white-space: nowrap; }
.jb-layout__nav { flex: 1; display: flex; gap: 6px; }
.jb-layout__nav a {
  padding: 8px 14px; border-radius: 18px; cursor: pointer; color: var(--jb-text); font-weight: 500; white-space: nowrap;
}
.jb-layout__nav a:hover { background: var(--jb-brand-soft); }
.jb-layout__nav a.active { background: var(--jb-brand); color: #fff; }
.jb-layout__right { display: flex; align-items: center; gap: 14px; }
.jb-layout__user { display: flex; align-items: center; gap: 10px; cursor: pointer; }
.jb-layout__avatar {
  width: 34px; height: 34px; border-radius: 50%; display: grid; place-items: center; color: #fff; font-weight: 600;
  background: linear-gradient(135deg, var(--jb-brand), var(--jb-accent));
}
.jb-layout__who { display: flex; flex-direction: column; line-height: 1.25; }
.jb-layout__who small { color: var(--jb-text-secondary); font-size: 12px; }
.jb-layout__body { flex: 1; display: flex; }
.jb-layout__sider {
  width: var(--jb-sider-w); flex-shrink: 0; padding: 18px 12px; background: var(--jb-surface); border-right: 1px solid var(--jb-border);
}
.jb-layout__sider-title { padding: 0 12px 12px; font-size: 12px; color: var(--jb-text-secondary); letter-spacing: 1px; }
.jb-layout__item {
  display: flex; align-items: center; justify-content: space-between; padding: 10px 12px; margin-bottom: 4px;
  border-radius: 8px; cursor: pointer; color: var(--jb-text);
}
.jb-layout__item:hover { background: var(--jb-brand-soft); }
.jb-layout__item.active { background: var(--jb-brand-light); color: var(--jb-brand-dark); font-weight: 600; border-left: 3px solid var(--jb-brand); }
.jb-layout__item.disabled { color: var(--jb-text-secondary); cursor: not-allowed; }
.jb-layout__main { flex: 1; min-width: 0; padding: 20px 24px 40px; }
.jb-layout__ai { position: fixed; right: 24px; bottom: 28px; z-index: 30; box-shadow: var(--jb-shadow); }
</style>
