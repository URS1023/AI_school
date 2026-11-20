import createClient, { type Middleware } from 'openapi-fetch'
import type { components, paths } from './schema'

export type Schemas = components['schemas']

/** 后端统一返回体 R<T>。 */
export interface Result<T> {
  code?: number
  msg?: string
  data?: T
}

/** 业务错误：code 来自后端 ErrorCode（403 无权限、1004 首登改密、2001 余额不足、3002 得分越界……）。 */
export class ApiError extends Error {
  constructor(
    public readonly code: number,
    message: string,
    public readonly data?: unknown,
  ) {
    super(message)
  }
}

/** 全局事件：由 auth 包订阅，做跳转（登录页 / 改密页 / 403 页）。 */
export type ApiEvent =
  | { type: 'unauthorized' }
  | { type: 'forbidden'; guide?: string }
  | { type: 'mustChangePassword' }

const listeners = new Set<(e: ApiEvent) => void>()
export function onApiEvent(fn: (e: ApiEvent) => void): () => void {
  listeners.add(fn)
  return () => listeners.delete(fn)
}
function emit(e: ApiEvent) {
  listeners.forEach((fn) => fn(e))
}

const TOKEN_KEY = 'jb_token'
let memoryToken: string | null = null

/** token 存 localStorage（读写都容错：隐私模式下退回内存）。 */
export const tokenStore = {
  get(): string | null {
    try {
      return localStorage.getItem(TOKEN_KEY) ?? memoryToken
    } catch {
      return memoryToken
    }
  },
  set(token: string | null) {
    memoryToken = token
    try {
      if (token) localStorage.setItem(TOKEN_KEY, token)
      else localStorage.removeItem(TOKEN_KEY)
    } catch {
      /* 忽略：用内存 token */
    }
  },
}

const authMiddleware: Middleware = {
  onRequest({ request }) {
    const token = tokenStore.get()
    if (token) request.headers.set('Authorization', `Bearer ${token}`)
    return request
  },
}

export const http = createClient<paths>({ baseUrl: '' })
http.use(authMiddleware)

/**
 * 解包 R<T>：HTTP 或业务失败都抛 ApiError，并广播需要全局处理的情况。
 * 用法：const me = await unwrap(http.GET('/api/system/user/me'))
 */
export async function unwrap<T>(
  call: Promise<{ data?: Result<T>; error?: Result<unknown>; response: Response }>,
): Promise<T> {
  let res: { data?: Result<T>; error?: Result<unknown>; response: Response }
  try {
    res = await call
  } catch {
    throw new ApiError(503, '网络异常，请检查网络后重试')
  }
  const body = (res.data ?? res.error ?? {}) as Result<unknown>
  const status = res.response.status
  const code = body.code ?? status
  if (status === 401 || code === 401) {
    emit({ type: 'unauthorized' })
    throw new ApiError(401, body.msg ?? '请先登录')
  }
  if (code === 1004) {
    emit({ type: 'mustChangePassword' })
    throw new ApiError(1004, body.msg ?? '首次登录请先修改初始密码')
  }
  if (code === 403) {
    const guide = (body.data as { guide?: string } | undefined)?.guide
    emit({ type: 'forbidden', guide })
    throw new ApiError(403, body.msg ?? '当前身份无权限', body.data)
  }
  if (code !== 200) {
    throw new ApiError(code, body.msg ?? '服务暂时不可用，请稍后再试', body.data)
  }
  return body.data as T
}
