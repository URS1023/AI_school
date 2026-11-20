import { createApp, type Component } from 'vue'
import { createPinia } from 'pinia'
import type { Router } from 'vue-router'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import { installAuthGuard, useAuth, vPerm } from '@jb/auth'
import { canEnter } from './menu'

/**
 * 各端共用的启动流程：Pinia、Element Plus（中文）、权限指令、登录守卫；
 * 另加一道菜单守卫：标记了 meta.menu 的页面，必须在当前身份的菜单里才能进（直接拼地址也进不去，AC-16）。
 */
export function bootstrap(root: Component, router: Router, mountTo = '#app') {
  const app = createApp(root)
  app.use(createPinia())
  app.use(ElementPlus, { locale: zhCn })
  app.directive('perm', vPerm)
  installAuthGuard(router)
  router.beforeEach((to) => {
    if (to.meta.menu && !canEnter(useAuth().menus, to.path)) {
      return { path: '/403', query: { from: to.fullPath } }
    }
    return true
  })
  router.afterEach((to) => {
    document.title = to.meta.title ? `${to.meta.title} - 世纪金榜AI` : '世纪金榜AI'
  })
  app.use(router)
  app.mount(mountTo)
  return app
}

declare module 'vue-router' {
  interface RouteMeta {
    /** 该页面属于后端下发的菜单：必须在当前身份菜单里才可进入。 */
    menu?: boolean
  }
}
