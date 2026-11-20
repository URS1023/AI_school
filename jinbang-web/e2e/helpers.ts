import { expect, request, type Page } from '@playwright/test'

export const API = process.env.JB_API_BASE ?? 'http://127.0.0.1:8080'
export const PASSWORD = 'Demo1234'
export const ACCOUNTS = {
  schoolAdmin: '13900000001',
  headTeacher: '13900000002',
  mathTeacher: '13900000003',
  visitor: '13900000009',
}

/** 直接调后端拿 token（准备数据用，不走界面）。 */
export async function apiLogin(account: string, password = PASSWORD): Promise<string> {
  const ctx = await request.newContext({ baseURL: API })
  const r = await ctx.post('/api/auth/login', { data: { account, password } })
  const body = await r.json()
  expect(body.code, JSON.stringify(body)).toBe(200)
  return body.data.token as string
}

export async function api(token: string | null, method: 'GET' | 'POST', path: string, data?: unknown) {
  const ctx = await request.newContext({ baseURL: API, extraHTTPHeaders: token ? { Authorization: `Bearer ${token}` } : {} })
  const r = method === 'GET' ? await ctx.get(path) : await ctx.post(path, { data })
  return r.json()
}

/** 在界面上用账号密码登录（dev 配置下验证码不校验）。 */
export async function uiLogin(page: Page, account: string, password = PASSWORD) {
  await page.goto('/login')
  await page.getByTestId('account').fill(account)
  await page.getByTestId('password').fill(password)
  await page.getByTestId('captcha').fill('0')
  await page.getByTestId('submit').click()
}

/** 把某账号的默认身份设为指定类型（多身份用户下次登录就进入该身份），保证用例可重复运行。 */
export async function setDefaultIdentity(account: string, type: string) {
  const token = await apiLogin(account)
  const ids = await api(token, 'GET', '/api/auth/identities')
  const target = ids.data.identities.find((i: { type: string }) => i.type === type)
  expect(target, `${account} 没有 ${type} 身份`).toBeTruthy()
  const r = await api(token, 'POST', '/api/auth/identity/default', { identityId: target.identityId })
  expect(r.code).toBe(200)
}
