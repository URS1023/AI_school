import { expect, test, type Page } from '@playwright/test'
import { ACCOUNTS, api, apiLogin, uiLogin } from './helpers'

const SHOTS = process.env.JB_SHOTS_DIR
const shot = async (page: Page, name: string) => {
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/${name}.png`, fullPage: true })
}
const ok = (page: Page) => page.getByRole('button', { name: /^(确定|OK)$/ }).click()

test.beforeEach(async ({ page }) => {
  page.on('pageerror', (e) => {
    throw e
  })
})

test('答题卡 → 线上作业 → 学生提交 → 阅卷台批阅 → 批阅完成 → 作业学情', async ({ page }) => {
  test.setTimeout(120_000)
  const stamp = Date.now().toString().slice(-6)
  await uiLogin(page, ACCOUNTS.mathTeacher)
  await expect(page.getByTestId('hello')).toBeVisible()

  // 1. 快速新建答题卡（默认 10 选择 + 4 填空 + 4 解答）
  await page.goto('/exam/sheets')
  await page.getByTestId('new-sheet').click()
  await page.getByTestId('sheet-name').fill(`周测答题卡 ${stamp}`)
  await page.getByTestId('sheet-create').click()
  await expect(page).toHaveURL(/\/exam\/sheets\/\d+/)
  await expect(page.getByText('满分')).toBeVisible()
  await shot(page, 'sheet-editor')

  // 2. 布置线上作业给高一1班
  await page.goto('/exam/homework')
  await page.getByTestId('assign-hw').click()
  const dlg = page.getByRole('dialog', { name: '布置作业' })
  await dlg.getByTestId('hw-title').fill(`函数单调性练习 ${stamp}`)
  await dlg.getByText('线上作答').click()
  await dlg.getByTestId('hw-sheet').click()
  await page.getByRole('option', { name: `周测答题卡 ${stamp}` }).click()
  await dlg.getByText('高一1班').click()
  await dlg.getByTestId('hw-submit').click()
  await expect(page).toHaveURL(/\/exam\/homework\/\d+/)
  const hwId = page.url().split('/').pop()!

  // 3. 学生（张三）线上提交
  const teacherToken = await apiLogin(ACCOUNTS.mathTeacher)
  const ids = await api(teacherToken, 'GET', '/api/auth/identities')
  const tenantId = ids.data.identities.find((i: { type: string }) => i.type === 'TEACHER').tenantId
  const stu = await apiLogin(`s${tenantId}260101`)
  const answers: Record<string, string> = {}
  for (let i = 1; i <= 18; i++) answers[String(i)] = i <= 10 ? 'A' : `第 ${i} 题的作答：先求导，再判断符号`
  const sub = await api(stu, 'POST', `/api/portal/homeworks/${hwId}/submit`, { answers })
  expect(sub.code, JSON.stringify(sub)).toBe(200)

  // 4. 阅卷台：第 11 题给满分，自动跳到「已全部批完」
  await page.goto('/exam/marking')
  await expect(page.getByTestId('marking-task').filter({ hasText: `函数单调性练习 ${stamp}` })).toBeVisible()
  await page.goto(`/exam/marking/homework/${hwId}?itemNo=11`)
  await expect(page.getByTestId('marking-paper')).toContainText('先求导')
  await shot(page, 'marking-desk')
  await page.getByRole('button', { name: '5', exact: true }).click()
  await expect(page.getByText('本题已全部批完')).toBeVisible()

  // 其余主观题用接口批完
  for (let no = 12; no <= 18; no++) {
    const next = await api(teacherToken, 'GET', `/api/exam/HOMEWORK/${hwId}/marking/next?itemNo=${no}`)
    if (next.data?.answerId) {
      await api(teacherToken, 'POST', '/api/exam/marking/submit', { answerId: next.data.answerId, itemNo: String(no), score: Math.min(Number(next.data.fullScore), no % 2 ? 3 : 8) })
    }
  }

  // 5. 批阅完成 → 作业学情
  await page.goto(`/exam/homework/${hwId}`)
  await page.getByRole('button', { name: '截止收集' }).click()
  await ok(page)
  await page.getByRole('button', { name: '批阅完成' }).click()
  await ok(page)
  await expect(page.getByText('操作成功')).toBeVisible()
  await page.getByRole('button', { name: '作业学情' }).click()
  await expect(page.getByTestId('report-kpis')).toContainText('1 / ')
  await shot(page, 'report')
  await page.getByRole('tab', { name: '试卷讲评' }).click()
  await page.getByRole('button', { name: '☆ 设为重点' }).first().click()
  await expect(page.getByRole('button', { name: '★ 重点讲评' }).first()).toBeVisible()
})

test('考试：创建 → 详情显示流程按钮；学情各页面可打开', async ({ page }) => {
  await uiLogin(page, ACCOUNTS.mathTeacher)
  await expect(page.getByTestId('hello')).toBeVisible()
  await page.goto('/exam/exams')
  await page.getByTestId('new-exam').click()
  await page.getByTestId('exam-name').fill(`月考 ${Date.now().toString().slice(-6)}`)
  await page.getByTestId('exam-create').click()
  await expect(page).toHaveURL(/\/exam\/exams\/\d+/)
  await expect(page.getByTestId('flow-READY')).toBeVisible()
  await shot(page, 'exam-detail')

  for (const [path, text] of [
    ['/learning/reports', '作业考试诊断'], ['/learning/class', '班级学情'], ['/learning/wrong', '错题本'],
    ['/learning/data-center', '数据中心'], ['/learning/screen', '教学质量看板'],
  ]) {
    await page.goto(path)
    await expect(page.getByText(text).first()).toBeVisible()
  }
  await shot(page, 'big-screen')
})
