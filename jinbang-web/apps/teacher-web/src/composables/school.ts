import { ref } from 'vue'
import { http, unwrap, type Schemas } from '@jb/api'

export type MyClass = Schemas['MyClass']

let classCache: Promise<MyClass[]> | null = null

/** 当前身份可见的班级（当前学年）。切换身份会整页刷新，缓存随之失效。 */
export function useMyClasses() {
  const classes = ref<MyClass[]>([])
  classCache ??= unwrap(http.GET('/api/school/my-classes')).catch(() => [])
  classCache.then((c) => (classes.value = c))
  const className = (id?: string | number | null) => classes.value.find((c) => String(c.classId) === String(id))?.className ?? (id ? `班级${String(id).slice(-4)}` : '—')
  return { classes, className }
}

/** 学生 / 用户姓名（按需批量取，结果合并缓存）。 */
const studentNames = ref<Record<string, string>>({})
const userNames = ref<Record<string, string>>({})

export async function loadNames(studentIds: (string | number | undefined | null)[] = [], userIds: (string | number | undefined | null)[] = []) {
  const s = [...new Set(studentIds.filter(Boolean).map(String))].filter((id) => !studentNames.value[id])
  const u = [...new Set(userIds.filter(Boolean).map(String))].filter((id) => !userNames.value[id])
  if (!s.length && !u.length) return
  const r = (await unwrap(http.POST('/api/school/names', { body: { studentIds: s, userIds: u } })).catch(() => null)) as
    | { students?: Record<string, { name?: string }>; users?: Record<string, string> }
    | null
  Object.entries(r?.students ?? {}).forEach(([k, v]) => (studentNames.value[k] = v.name ?? k))
  Object.entries(r?.users ?? {}).forEach(([k, v]) => (userNames.value[k] = v))
}

export function useNames() {
  const student = (id?: string | number | null) => (id ? studentNames.value[String(id)] ?? `学生${String(id).slice(-4)}` : '未匹配')
  const user = (id?: string | number | null) => (id ? userNames.value[String(id)] ?? `用户${String(id).slice(-4)}` : '—')
  return { student, user, loadNames }
}

export const HW_STATUS: Record<string, string> = {
  ASSIGNED: '已布置', COLLECTING: '收集中', PUBLISHED: '已发布', CLOSED: '已截止', MARKING: '批阅中', MARKED: '批阅完成', ARCHIVED: '已归档',
}
export const EXAM_STATUS: Record<string, string> = {
  DRAFT: '草稿', WAIT_SCAN: '待扫描', SCANNING: '扫描中', MARKING: '阅卷中', PAUSED: '暂停阅卷', MARKED: '阅卷完成', SCORE_AUDIT: '成绩审核中',
  AUDITED: '成绩已审核', PUBLISHED: '已发布成绩', LEARNING_PUBLISHED: '已发布学情', ARCHIVED: '已归档',
}
export const BLOCK_TYPES: Record<string, string> = { SINGLE: '单选', MULTI: '多选', JUDGE: '判断', BLANK: '填空', ANSWER: '解答', ESSAY: '作文' }
