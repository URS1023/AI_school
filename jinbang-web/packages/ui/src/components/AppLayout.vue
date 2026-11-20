<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Bell } from '@element-plus/icons-vue'
import { http, unwrap, type ApiError } from '@jb/api'
import { useAuth, type MenuItem } from '@jb/auth'
import { findMenu } from '../menu'

/**
 * 通用布局：顶部导航按当前身份下发（R1），「即将上线」灰显带角标（AC-17）；
 * 右侧消息铃铛（未读数）、大字号模式、身份切换（R3，切换后整页数据按新身份重载）、退出。
 */
const props = defineProps<{
  appName: string
  /** 只显示这些顶级菜单（按路径前缀）；不传显示全部。 */
  rootFilter?: (m: MenuItem) => boolean
  homePath?: string
}>()

const auth = useAuth()
const route = useRoute()
const router = useRouter()
const unread = ref(0)
const large = ref(document.documentElement.classList.contains('jb-large'))
let timer: number | undefined

const roots = computed(() => (props.rootFilter ? auth.menus.filter(props.rootFilter) : auth.menus))
const active = computed(() => findMenu(auth.menus, route.path)?.path ?? route.path)
const currentIdentity = computed(() => auth.identities.find((i) => i.current))

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
  <el-container class="jb-layout">
    <el-header class="jb-layout__header">
      <div class="jb-layout__brand" @click="router.push(props.homePath ?? '/')">{{ props.appName }}</div>
      <el-menu mode="horizontal" :default-active="active" :ellipsis="false" class="jb-layout__menu" data-test="nav">
        <template v-for="m in roots" :key="m.id">
          <el-sub-menu v-if="m.children?.length" :index="m.path ?? String(m.id)">
            <template #title>{{ m.name }}</template>
            <el-menu-item v-for="c in m.children" :key="c.id" :index="c.path ?? String(c.id)" :disabled="c.comingSoon"
                          :data-test="c.comingSoon ? 'coming-soon' : undefined" @click="go(c)">
              {{ c.name }}
              <el-tag v-if="c.comingSoon" size="small" type="info" class="jb-layout__soon">即将上线</el-tag>
            </el-menu-item>
          </el-sub-menu>
          <el-menu-item v-else :index="m.path ?? String(m.id)" :disabled="m.comingSoon" @click="go(m)">
            {{ m.name }}
            <el-tag v-if="m.comingSoon" size="small" type="info" class="jb-layout__soon">即将上线</el-tag>
          </el-menu-item>
        </template>
      </el-menu>
      <div class="jb-layout__right">
        <el-badge :value="unread" :hidden="!unread" :max="99">
          <el-button :icon="Bell" circle text aria-label="消息" @click="router.push('/messages')" />
        </el-badge>
        <el-dropdown trigger="click" @command="onCommand">
          <span class="jb-layout__user" data-test="user-menu">
            {{ auth.me?.nickname }}
            <small>{{ currentIdentity?.displayName }}</small>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item disabled>切换身份</el-dropdown-item>
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
    </el-header>
    <el-main class="jb-layout__main">
      <router-view />
    </el-main>
  </el-container>
</template>

<style scoped>
.jb-layout { min-height: 100%; }
.jb-layout__header { display: flex; align-items: center; gap: 16px; height: var(--jb-header-h); background: var(--jb-surface); border-bottom: 1px solid var(--jb-border); }
.jb-layout__brand { font-weight: 700; font-size: 18px; color: var(--jb-brand); cursor: pointer; white-space: nowrap; }
.jb-layout__menu { flex: 1; border-bottom: none; }
.jb-layout__soon { margin-left: 6px; }
.jb-layout__right { display: flex; align-items: center; gap: 16px; }
.jb-layout__user { cursor: pointer; display: flex; flex-direction: column; line-height: 1.2; }
.jb-layout__user small { color: var(--jb-text-secondary); font-size: 12px; }
.jb-layout__main { padding: 20px 24px; }
</style>
