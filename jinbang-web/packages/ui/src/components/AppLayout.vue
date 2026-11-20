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
        <button v-for="m in roots" :key="m.id" type="button" :class="{ active: activeRoot?.id === m.id || (!m.children?.length && route.path === m.path) }"
                :aria-current="activeRoot?.id === m.id ? 'page' : undefined" @click="m.children?.length ? goRoot(m) : go(m)">{{ m.name }}</button>
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
        <button v-for="c in sider" :key="c.id" type="button" class="jb-layout__item"
                :class="{ active: activeChild === c.path, disabled: c.comingSoon }" :aria-disabled="c.comingSoon || undefined"
                :aria-current="activeChild === c.path ? 'page' : undefined"
                :data-test="c.comingSoon ? 'coming-soon' : undefined" @click="go(c)">
          <span>{{ c.name }}</span>
          <small v-if="c.comingSoon" class="jb-layout__soon">即将上线</small>
        </button>
      </aside>
      <main class="jb-layout__main">
        <router-view />
      </main>
    </div>
    <el-button v-if="props.aiPath && auth.hasPerm('ai:use')" class="jb-layout__ai" type="primary" :icon="ChatDotRound"
               @click="router.push(props.aiPath)">AI 助手</el-button>
  </div>
</template>

<style scoped>
.jb-layout { min-height: 100%; display: flex; flex-direction: column; }
.jb-layout__header {
  position: sticky; top: 0; z-index: 20; display: flex; align-items: center; gap: 32px; height: var(--jb-header-h);
  padding: 0 24px; background: var(--color-desk); border-bottom: 1px solid var(--color-rule-2);
}
.jb-layout__brand { cursor: pointer; white-space: nowrap; }
.jb-layout__nav { flex: 1; display: flex; gap: 4px; align-self: stretch; }
.jb-layout__nav button {
  all: unset; box-sizing: border-box; display: flex; align-items: center; padding: 0 14px; cursor: pointer;
  color: var(--color-muted); font-weight: 500; white-space: nowrap; border-bottom: 2px solid transparent; margin-bottom: -1px;
  transition: color var(--dur-micro), border-color var(--dur-micro);
}
.jb-layout__nav button:hover { color: var(--color-ink); }
.jb-layout__nav button.active { color: var(--color-ink); font-weight: 600; border-bottom-color: var(--color-ink); }
.jb-layout__nav button:focus-visible { outline: 2px solid var(--color-focus); outline-offset: -4px; }
.jb-layout__right { display: flex; align-items: center; gap: 14px; }
.jb-layout__right :deep(.el-button) { box-shadow: none; }
.jb-layout__user { display: flex; align-items: center; gap: 10px; cursor: pointer; }
.jb-layout__avatar {
  width: 32px; height: 32px; border-radius: 50%; display: grid; place-items: center; font-weight: 600;
  color: var(--color-cotton); background: var(--color-ink);
}
.jb-layout__who { display: flex; flex-direction: column; line-height: 1.3; font-weight: 500; }
.jb-layout__who small { color: var(--color-muted); font-size: var(--text-xs); font-weight: 400; }
.jb-layout__body { flex: 1; display: flex; }
.jb-layout__sider {
  width: var(--jb-sider-w); flex-shrink: 0; padding: 20px 12px; background: var(--color-desk); border-right: 1px solid var(--color-rule-2);
  display: flex; flex-direction: column; gap: 2px;
}
.jb-layout__sider-title { padding: 0 12px 10px; font-size: var(--text-sm); color: var(--color-muted); }
.jb-layout__item {
  all: unset; box-sizing: border-box; display: flex; align-items: center; justify-content: space-between; min-height: 40px; padding: 0 12px;
  border-radius: var(--radius-stock); cursor: pointer; color: var(--color-ink-2);
  transition: background-color var(--dur-micro), box-shadow var(--dur-micro);
}
.jb-layout__item:hover { background: var(--color-paper-2); }
.jb-layout__item.active { background: var(--color-cotton); color: var(--color-ink); font-weight: 600; box-shadow: var(--edge-stock), var(--cast-stock); }
.jb-layout__item.disabled { color: var(--color-muted); cursor: not-allowed; }
.jb-layout__item:focus-visible { outline: 2px solid var(--color-focus); outline-offset: 1px; }
.jb-layout__soon { font-size: var(--text-xs); color: var(--color-muted); border: 1px solid var(--color-rule-2); border-radius: 999px; padding: 0 8px; line-height: 20px; }
.jb-layout__main { flex: 1; min-width: 0; padding: 24px 28px 48px; }
.jb-layout__ai { position: fixed; right: 24px; bottom: 28px; z-index: 30; }
</style>
