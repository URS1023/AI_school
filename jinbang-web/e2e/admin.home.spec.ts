import { expect, test } from '@playwright/test'
import { ACCOUNTS, uiLogin } from './helpers'

test('学校管理员登录管理后台，看到学校概况和教务入口', async ({ page }) => {
  await uiLogin(page, ACCOUNTS.schoolAdmin)
  await expect(page.getByTestId('school-card')).toBeVisible()
  await expect(page.getByTestId('school-card')).toContainText('2026-2027学年')
  await expect(page.getByTestId('nav')).toContainText('教务管理')
})

test('没有管理权限的老师进入管理后台看到 403', async ({ page }) => {
  await uiLogin(page, ACCOUNTS.mathTeacher)
  await expect(page.getByTestId('forbidden')).toBeVisible()
})
