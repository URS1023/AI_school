import { expect, test } from '@playwright/test'
import { ACCOUNTS, api, apiLogin, setDefaultIdentity, uiLogin } from './helpers'

test.beforeEach(async () => {
  await setDefaultIdentity(ACCOUNTS.visitor, 'VISITOR_TEACHER')
})

test('学校教师登录后看到按身份下发的菜单、金币和身份信息', async ({ page }) => {
  await uiLogin(page, ACCOUNTS.mathTeacher)
  await expect(page.getByTestId('hello')).toContainText('你好')
  const nav = page.getByTestId('nav')
  await expect(nav).toContainText('考试阅卷')
  await expect(nav).toContainText('学情分析')
  await expect(nav).not.toContainText('教务管理') // 管理类菜单在管理后台
  await expect(page.getByTestId('user-menu')).toContainText('李老师')
})

test('AC-16：游客打开教务、考试页面看到 403 提示和申请指引', async ({ page }) => {
  await uiLogin(page, ACCOUNTS.visitor)
  await expect(page.getByTestId('hello')).toBeVisible()
  await expect(page.getByTestId('nav')).not.toContainText('考试阅卷')
  for (const path of ['/edu-affairs', '/exam-center/list', '/learning-center/homework']) {
    await page.goto(path)
    await expect(page.getByTestId('forbidden'), path).toBeVisible()
    await expect(page.getByTestId('forbidden')).toContainText('加入学校')
  }
})

test('AC-17：占位入口灰显并带「即将上线」角标', async ({ page }) => {
  await uiLogin(page, ACCOUNTS.visitor)
  await page.getByTestId('nav').getByText('教学中心').hover()
  const soon = page.getByTestId('coming-soon').first()
  await expect(soon).toBeVisible()
  await expect(soon).toContainText('即将上线')
  await expect(soon).toHaveClass(/is-disabled/)
})

test('AC-13：游客直接调用学校接口返回 403，不返回任何学校数据', async () => {
  const token = await apiLogin(ACCOUNTS.visitor)
  for (const path of ['/api/school/students?classId=1', '/api/exam/exams', '/api/learning/wrong']) {
    const body = await api(token, 'GET', path)
    expect(body.code, path).toBe(403)
    expect(body.data?.guide).toBeTruthy()
  }
})

test('首次登录必须先改初始密码，改完才能进入', async ({ page }) => {
  const admin = await apiLogin(ACCOUNTS.schoolAdmin)
  const phone = `137${String(Date.now()).slice(-8)}`
  const added = await api(admin, 'POST', '/api/school/teachers', { name: '新来的老师', phone, rels: [] })
  expect(added.code, JSON.stringify(added)).toBe(200)
  await uiLogin(page, phone, '123456')
  await expect(page).toHaveURL(/change-password/)
  await page.getByTestId('old').fill('123456')
  await page.getByTestId('new').fill('NewPass123')
  await page.getByTestId('confirm').fill('NewPass123')
  await page.getByTestId('submit').click()
  await expect(page.getByTestId('hello')).toContainText('新来的老师')
})

test('单点登录：ticket 换取登录态，且只能用一次', async ({ page }) => {
  const token = await apiLogin(ACCOUNTS.mathTeacher)
  const t = await api(token, 'POST', '/api/auth/sso/ticket')
  await page.goto(`/sso?ticket=${t.data.ticket}`)
  await expect(page.getByTestId('hello')).toBeVisible()
  await expect(page).not.toHaveURL(/ticket=/) // ticket 不留在地址栏和历史里
  await page.evaluate(() => localStorage.clear())
  await page.goto(`/sso?ticket=${t.data.ticket}`)
  await expect(page.getByText('登录凭证已失效').first()).toBeVisible()
})

test('身份切换：多身份用户切换后菜单随身份变化', async ({ page }) => {
  // 学校把这位游客教师加为本校教师 → 同一账号有了第二个身份（已加过则返回 409）
  const admin = await apiLogin(ACCOUNTS.schoolAdmin)
  const add = await api(admin, 'POST', '/api/school/teachers', { name: '游客老师', phone: ACCOUNTS.visitor, rels: [] })
  expect([200, 409], JSON.stringify(add)).toContain(add.code)

  await uiLogin(page, ACCOUNTS.visitor)
  await expect(page.getByTestId('user-menu')).toContainText('游客教师')
  await page.getByTestId('user-menu').click()
  const items = page.getByTestId('identity-item')
  await expect(items).toHaveCount(2)
  await items.filter({ hasText: '演示中学' }).click()
  await expect(page.getByText('已切换身份')).toBeVisible()
  await expect(page.getByTestId('user-menu')).toContainText('演示中学')
})

test('开发环境登录页：点演示账号即可登录（密码前后带空格也能登录）', async ({ page }) => {
  await page.goto('/login')
  await page.getByTestId('demo-accounts').getByText('数学老师').click()
  await expect(page.getByTestId('account')).toHaveValue('13900000003')
  await page.getByTestId('password').fill(' Demo1234 ')
  await page.getByTestId('submit').click()
  await expect(page.getByTestId('hello')).toBeVisible()
})
