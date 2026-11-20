import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { http, tokenStore, unwrap, type Schemas } from '@jb/api'

export type Me = Schemas['MeVO']
export type LoginResult = Schemas['LoginVO']
export type IdentityItem = Schemas['IdentityVO']
export type MenuItem = Schemas['MenuVO']

/**
 * 当前会话：token、我是谁（含当前身份与权限码）、可切换的身份、按身份下发的菜单（PRD R1、R3）。
 * 账户（金币、经验）跟着自然人走，身份切换只改变数据范围和菜单。
 */
export const useAuth = defineStore('jb-auth', () => {
  const token = ref<string | null>(tokenStore.get())
  const me = ref<Me | null>(null)
  const identities = ref<IdentityItem[]>([])
  const menus = ref<MenuItem[]>([])
  const mustChangePassword = ref(false)
  const lastForbiddenGuide = ref<string | null>(null)
  const ready = ref(false)

  const identity = computed(() => me.value?.identity)
  const perms = computed(() => new Set(identity.value?.perms ?? []))
  const isLoggedIn = computed(() => !!token.value)

  /** 权限判断：支持超管 "*"，以及以冒号结尾的前缀（如 "op:" 表示任一运营权限）。 */
  function hasPerm(perm?: string | string[] | null): boolean {
    if (!perm || (Array.isArray(perm) && perm.length === 0)) return true
    const list = Array.isArray(perm) ? perm : [perm]
    const own = perms.value
    if (own.has('*')) return true
    return list.some((p) => (p.endsWith(':') ? [...own].some((x) => x.startsWith(p)) : own.has(p)))
  }

  function applyLogin(r: LoginResult) {
    token.value = r.token ?? null
    tokenStore.set(token.value)
    identities.value = r.identities ?? []
    mustChangePassword.value = !!r.mustChangePassword
    ready.value = false
  }

  async function loginByPassword(account: string, password: string, captchaId?: string, captchaCode?: string) {
    applyLogin(await unwrap(http.POST('/api/auth/login', { body: { account, password, captchaId, captchaCode } })))
  }

  async function loginBySms(phone: string, code: string) {
    applyLogin(await unwrap(http.POST('/api/auth/sms/login', { body: { phone, code } })))
  }

  async function register(phone: string, code: string, password: string, nickname?: string) {
    applyLogin(await unwrap(http.POST('/api/auth/register', { body: { phone, code, password, nickname } })))
  }

  /** 从主站带 ticket 进入（兼容原 agent 域名的 SSO 链接）。ticket 一次性、60 秒失效。 */
  async function loginByTicket(ticket: string) {
    applyLogin(await unwrap(http.POST('/api/auth/sso/login', { body: { ticket } })))
  }

  /** 拉取会话：我是谁、可切换身份、菜单。必须先改密时不拉菜单（后端也会拦截）。 */
  async function loadSession() {
    me.value = await unwrap(http.GET('/api/system/user/me'))
    if (mustChangePassword.value) {
      ready.value = true
      return
    }
    const [ids, routers] = await Promise.all([
      unwrap(http.GET('/api/auth/identities')),
      unwrap(http.GET('/api/system/menu/routers')),
    ])
    identities.value = ids.identities ?? []
    mustChangePassword.value = !!ids.mustChangePassword
    menus.value = routers
    ready.value = true
  }

  async function switchIdentity(identityId: string) {
    applyLogin({ ...(await unwrap(http.POST('/api/auth/identity/switch', { body: { identityId } }))), token: token.value ?? undefined })
    await loadSession()
  }

  async function changePassword(newPassword: string, oldPassword?: string, smsCode?: string) {
    await unwrap(http.POST('/api/auth/password/change', { body: { newPassword, oldPassword, smsCode } }))
    mustChangePassword.value = false
    ready.value = false
  }

  async function logout() {
    try {
      if (token.value) await unwrap(http.POST('/api/auth/logout'))
    } catch {
      /* 已失效也照常清理 */
    }
    clear()
  }

  function clear() {
    token.value = null
    tokenStore.set(null)
    me.value = null
    identities.value = []
    menus.value = []
    mustChangePassword.value = false
    ready.value = false
  }

  return {
    token, me, identities, menus, mustChangePassword, lastForbiddenGuide, ready,
    identity, perms, isLoggedIn, hasPerm,
    loginByPassword, loginBySms, register, loginByTicket, loadSession, switchIdentity, changePassword, logout, clear,
  }
})
