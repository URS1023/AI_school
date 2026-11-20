import type { Directive } from 'vue'
import type { Router } from 'vue-router'
import { onApiEvent } from '@jb/api'
import { useAuth } from './store'

export interface GuardOptions {
  loginPath?: string
  changePasswordPath?: string
  forbiddenPath?: string
  /** 不需要登录的路由名（登录、注册、SSO 落地页等）。 */
  publicNames?: string[]
}

declare module 'vue-router' {
  interface RouteMeta {
    /** 访问该页面需要的权限码（任一满足即可）。没有则只要求登录。 */
    perm?: string | string[]
    public?: boolean
    title?: string
  }
}

/**
 * 路由守卫：未登录 → 登录页；必须改密 → 改密页；没有权限 → 403 页（PRD R8、R9、AC-16）。
 * 同时订阅接口层事件：任何接口返回 401 / 1004 都跳到对应页面。
 */
export function installAuthGuard(router: Router, opts: GuardOptions = {}) {
  const loginPath = opts.loginPath ?? '/login'
  const changePasswordPath = opts.changePasswordPath ?? '/change-password'
  const forbiddenPath = opts.forbiddenPath ?? '/403'

  router.beforeEach(async (to) => {
    const auth = useAuth()
    if (to.meta.public) return true
    if (!auth.isLoggedIn) {
      return { path: loginPath, query: { redirect: to.fullPath } }
    }
    if (!auth.ready) {
      try {
        await auth.loadSession()
      } catch {
        auth.clear()
        return { path: loginPath, query: { redirect: to.fullPath } }
      }
    }
    if (auth.mustChangePassword && to.path !== changePasswordPath) {
      return { path: changePasswordPath, query: { redirect: to.fullPath } }
    }
    if (to.meta.perm && !auth.hasPerm(to.meta.perm)) {
      return { path: forbiddenPath, query: { from: to.fullPath } }
    }
    return true
  })

  onApiEvent((e) => {
    const auth = useAuth()
    const current = router.currentRoute.value
    if (e.type === 'unauthorized' && !current.meta.public) {
      auth.clear()
      router.replace({ path: loginPath, query: { redirect: current.fullPath } })
    } else if (e.type === 'mustChangePassword') {
      auth.mustChangePassword = true
      router.replace({ path: changePasswordPath, query: { redirect: current.fullPath } })
    } else if (e.type === 'forbidden') {
      auth.lastForbiddenGuide = e.guide ?? null
    }
  })
}

/** v-perm="'exam:publish'"：没有权限的按钮直接不渲染（后端照样拦截，这里只负责「藏」）。 */
export const vPerm: Directive<HTMLElement, string | string[]> = {
  mounted(el, binding) {
    if (!useAuth().hasPerm(binding.value)) el.remove()
  },
}
