import { createRouter, createWebHistory } from 'vue-router'
import { ChangePasswordView, ForbiddenView, PlaceholderView } from '@jb/ui'
import Layout from './views/TeacherLayout.vue'

/**
 * 教师 Web（主站 + AI 工作台合并，前端方案 8.1）。
 * 已实现的页面单独登记；其余菜单路径落到占位页，并由菜单守卫保证「不在当前身份菜单里就 403」。
 */
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('./views/LoginPage.vue'), meta: { public: true, title: '登录' } },
    { path: '/sso', component: () => import('./views/SsoLanding.vue'), meta: { public: true, title: '登录中' } },
    { path: '/change-password', component: ChangePasswordView, meta: { title: '修改密码' } },
    {
      path: '/',
      component: Layout,
      children: [
        { path: '', component: () => import('./views/HomeView.vue'), meta: { title: '首页' } },
        { path: 'home', redirect: '/' },
        { path: 'messages', component: () => import('./views/MessagesView.vue'), meta: { title: '消息中心' } },
        { path: '403', component: ForbiddenView, props: { joinPath: '/personal/join' }, meta: { title: '无权限' } },
        { path: ':pathMatch(.*)*', component: PlaceholderView, meta: { menu: true, title: '建设中' } },
      ],
    },
  ],
})
