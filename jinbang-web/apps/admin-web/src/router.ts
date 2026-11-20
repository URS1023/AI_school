import { createRouter, createWebHistory } from 'vue-router'
import { ChangePasswordView, ForbiddenView, LoginView, PlaceholderView } from '@jb/ui'
import Layout from './views/AdminLayout.vue'

/**
 * 管理后台：同一个应用，按身份下发「学校后台」（教务管理）或「运营后台」菜单。
 * 首页按权限给出入口；没有任何管理权限的身份进入时直接给 403 提示。
 */
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      component: LoginView,
      props: { title: '世纪金榜AI 管理后台', subtitle: '学校管理员 · 平台运营' },
      meta: { public: true, title: '登录' },
    },
    { path: '/change-password', component: ChangePasswordView, meta: { title: '修改密码' } },
    {
      path: '/',
      component: Layout,
      children: [
        { path: '', component: () => import('./views/AdminHome.vue'), meta: { title: '管理首页', perm: ['school:org:manage', 'school:teacher:manage', 'school:student:manage', 'school:join:audit', 'op:'] } },
        { path: '403', component: ForbiddenView, meta: { title: '无权限' } },
        { path: ':pathMatch(.*)*', component: PlaceholderView, meta: { menu: true, title: '建设中' } },
      ],
    },
  ],
})
