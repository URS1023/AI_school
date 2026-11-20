import { expect, test } from '@playwright/test'
import { ACCOUNTS, uiLogin } from './helpers'

const SHOTS = process.env.JB_SHOTS_DIR
const shot = async (page: import('@playwright/test').Page, name: string) => {
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/${name}.png`, fullPage: true })
}

test.beforeEach(async ({ page }) => {
  page.on('pageerror', (e) => {
    throw e
  })
  await uiLogin(page, ACCOUNTS.mathTeacher)
  await expect(page.getByTestId('hello')).toBeVisible()
  await shot(page, 'home')
})

test('智能备课：生成大纲 → 下一步 → 生成课件，存入备课目录', async ({ page }) => {
  await page.goto('/teaching/smart')
  await page.getByTestId('topic').fill('函数的单调性')
  await page.getByTestId('gen-outline').click()
  await expect(page.getByRole('button', { name: '+ 添加章' })).toBeVisible()
  await shot(page, 'smart-outline')
  await page.getByTestId('next').click()
  await page.getByTestId('gen-ppt').click()
  await expect(page.getByText('课件已生成', { exact: true })).toBeVisible()
  await page.goto('/teaching/prep')
  await expect(page.getByText('函数的单调性').first()).toBeVisible()
  await shot(page, 'prep')
})

test('AI 问答：流式输出并标注「内容由 AI 生成」', async ({ page }) => {
  await page.goto('/ai/chat')
  await page.getByTestId('chat-input').fill('如何讲好函数的单调性')
  await page.getByTestId('chat-send').click()
  await expect(page.getByText('内容由 AI 生成，仅供参考').first()).toBeVisible({ timeout: 20_000 })
  await shot(page, 'ai-chat')
  await page.goto('/ai/records')
  await expect(page.getByTestId('records')).toContainText('智能问答')
})

test('AI 出题并加入我的题库', async ({ page }) => {
  await page.goto('/ai/question')
  await page.getByTestId('gen-questions').click()
  await expect(page.getByTestId('task-result')).toBeVisible()
  await page.getByTestId('save-questions').click()
  await expect(page.getByText(/已加入我的题库/)).toBeVisible()
  await shot(page, 'ai-question')
})

test('个人中心：金币明细与成长体系', async ({ page }) => {
  await page.goto('/personal/coins')
  await expect(page.getByTestId('coin-ledger')).toBeVisible()
  await page.goto('/personal')
  await expect(page.getByTestId('pc-balance')).toBeVisible()
  await shot(page, 'personal')
})

test('金榜杏坛：发布动态', async ({ page }) => {
  await page.goto('/teaching/forum')
  const text = `今天的复习课分层练习效果不错 ${Date.now()}`
  await page.getByTestId('post-input').fill(text)
  await page.getByTestId('post-submit').click()
  await expect(page.getByText(text)).toBeVisible()
})
