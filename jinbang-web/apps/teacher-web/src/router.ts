import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { ChangePasswordView, ForbiddenView, PlaceholderView } from '@jb/ui'
import Layout from './views/TeacherLayout.vue'

/** 菜单页：必须在当前身份的菜单里才能进入（直接拼地址也不行，AC-16）。 */
function page(path: string, title: string, component: RouteRecordRaw['component'], props?: Record<string, unknown>): RouteRecordRaw {
  return { path, component, props, meta: { menu: true, title } } as RouteRecordRaw
}

/**
 * 教师 Web（主站 + AI 工作台合并，前端方案 8.1）。
 * 已实现的页面单独登记；其余菜单路径落到占位页。
 */
export const router = createRouter({
  history: createWebHistory(),
  scrollBehavior: () => ({ top: 0 }),
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

        // 资源中心
        page('/resources/platform', '平台资源', () => import('./views/resources/ResourceList.vue'), { scope: 'PLATFORM' }),
        page('/resources/platform/:id', '资源详情', () => import('./views/resources/ResourceDetail.vue')),
        page('/resources/school', '校本资源', () => import('./views/resources/ResourceList.vue'), { scope: 'SCHOOL' }),
        page('/resources/school/:id', '资源详情', () => import('./views/resources/ResourceDetail.vue')),
        page('/resources/questions', '题库组卷', () => import('./views/resources/QuestionBank.vue')),
        page('/resources/questions/import', '批量录题', () => import('./views/resources/QuestionImport.vue')),
        page('/resources/papers', '我的试卷', () => import('./views/resources/PaperList.vue')),
        page('/resources/papers/:id', '组卷编辑', () => import('./views/resources/PaperEditor.vue')),
        page('/resources/collection', '资源征集', () => import('./views/resources/CollectionView.vue')),

        // 教学中心
        page('/teaching/prep', '独立备课', () => import('./views/teaching/PrepView.vue')),
        page('/teaching/smart', '智能备课', () => import('./views/teaching/SmartPrep.vue')),
        page('/teaching/collective', '集体备课', () => import('./views/teaching/CollectiveView.vue')),
        page('/teaching/forum', '金榜杏坛', () => import('./views/teaching/ForumView.vue')),

        // AI 工作台
        page('/ai/chat', '智能问答', () => import('./views/ai/AiChat.vue')),
        page('/ai/question', '题目生成', () => import('./views/ai/AiQuestion.vue')),
        page('/ai/ppt', 'PPT 生成', () => import('./views/ai/AiPpt.vue')),
        page('/ai/paper', '试卷批改', () => import('./views/ai/AiPaper.vue')),
        page('/ai/essay', '作文批改', () => import('./views/ai/AiEssay.vue')),
        page('/ai/kb', '知识库', () => import('./views/ai/AiKb.vue')),
        page('/ai/records', '消费记录', () => import('./views/ai/AiRecords.vue')),

        // 考试阅卷
        page('/exam/sheets', '答题卡', () => import('./views/exam/SheetList.vue')),
        page('/exam/sheets/:id', '答题卡编辑', () => import('./views/exam/SheetEditor.vue')),
        page('/exam/homework', '作业', () => import('./views/exam/HomeworkList.vue')),
        page('/exam/homework/:id', '作业详情', () => import('./views/exam/HomeworkDetail.vue')),
        page('/exam/exams', '考试', () => import('./views/exam/ExamList.vue')),
        page('/exam/exams/:id', '考试详情', () => import('./views/exam/ExamDetail.vue')),
        page('/exam/marking', '阅卷', () => import('./views/exam/MarkingCenter.vue')),
        page('/exam/marking/:type/:id', '阅卷台', () => import('./views/exam/MarkingDesk.vue')),

        // 学情分析
        page('/learning/reports', '作业考试诊断', () => import('./views/learning/ReportList.vue')),
        page('/learning/reports/:type/:id', '学情报告', () => import('./views/learning/ReportView.vue')),
        page('/learning/class', '班级学情', () => import('./views/learning/ClassTrend.vue')),
        page('/learning/wrong', '错题本', () => import('./views/learning/WrongBook.vue')),
        page('/learning/data-center', '数据中心', () => import('./views/learning/DataCenter.vue')),
        page('/learning/screen', '学情大屏', () => import('./views/learning/BigScreen.vue')),

        // 个人中心（所有身份都有，不属于菜单）
        { path: 'personal/:tab?', component: () => import('./views/personal/PersonalView.vue'), meta: { title: '个人中心' } },

        { path: ':pathMatch(.*)*', component: PlaceholderView, meta: { menu: true, title: '建设中' } },
      ],
    },
  ],
})
