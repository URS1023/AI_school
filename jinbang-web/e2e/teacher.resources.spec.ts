import { expect, test } from '@playwright/test'
import { ACCOUNTS, uiLogin } from './helpers'

const SHOTS = process.env.JB_SHOTS_DIR

test('资源中心与题库：主要页面可用，组卷流程走通', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (e) => errors.push(e.message))
  await uiLogin(page, ACCOUNTS.mathTeacher)
  await expect(page.getByTestId('hello')).toBeVisible()

  await page.goto('/resources/platform')
  await expect(page.getByRole('heading', { name: '平台资源' })).toBeVisible()
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/resources-platform.png`, fullPage: true })

  await page.goto('/resources/questions')
  await expect(page.getByRole('heading', { name: '题库组卷' })).toBeVisible()
  await expect(page.getByTestId('question-item').first()).toBeVisible()
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/resources-questions.png`, fullPage: true })
  // 本页加入试题篮 → 生成试卷 → 进入组卷编辑页
  await page.getByRole('button', { name: '本页加入试题篮' }).click()
  await page.getByRole('button', { name: /试题篮 \d+/ }).click()
  await page.getByRole('button', { name: '生成试卷' }).click()
  await expect(page).toHaveURL(/\/resources\/papers\/\d+/)
  await expect(page.getByText(/满分 \d+/)).toBeVisible()
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/paper-editor.png`, fullPage: true })

  await page.goto('/resources/questions/import')
  await expect(page.getByText(/解析出 1 题/)).toBeVisible()
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/question-import.png`, fullPage: true })

  await page.goto('/resources/collection')
  await expect(page.getByRole('heading', { name: '资源征集' })).toBeVisible()
  expect(errors, errors.join('\n')).toHaveLength(0)
})
