import { tokenStore } from '@jb/api'

export interface StreamHandlers {
  onStart?: (taskId: string) => void
  onDelta?: (text: string) => void
  onDone?: (data: unknown) => void
  onError?: (err: { code?: number; msg?: string }) => void
}

/**
 * AI 流式对话（前端方案 4.5）：POST + fetch 读 ReadableStream，解析 SSE 的 start / delta / done / error 事件。
 * 返回 AbortController，调用 abort() 只断开连接；要真正停止生成请再调用 /api/ai/tasks/{taskId}/stop。
 */
export function streamChat(body: unknown, h: StreamHandlers): AbortController {
  const ctrl = new AbortController()
  const token = tokenStore.get()
  ;(async () => {
    try {
      const res = await fetch('/api/ai/chat/stream', {
        method: 'POST',
        signal: ctrl.signal,
        headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
        body: JSON.stringify(body),
      })
      if (!res.ok || !res.body) {
        h.onError?.({ code: res.status, msg: res.status === 401 ? '请先登录' : '服务暂时不可用，本次未扣费' })
        return
      }
      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buf = ''
      for (;;) {
        const { value, done } = await reader.read()
        if (done) break
        buf += decoder.decode(value, { stream: true })
        let idx: number
        while ((idx = buf.indexOf('\n\n')) >= 0) {
          const raw = buf.slice(0, idx)
          buf = buf.slice(idx + 2)
          let event = 'message'
          const data: string[] = []
          raw.split('\n').forEach((line) => {
            if (line.startsWith('event:')) event = line.slice(6).trim()
            else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
          })
          const text = data.join('\n')
          const parse = () => {
            try {
              return JSON.parse(text)
            } catch {
              return text
            }
          }
          if (event === 'start') h.onStart?.((parse() as { taskId: string }).taskId)
          else if (event === 'delta') h.onDelta?.(text)
          else if (event === 'done') h.onDone?.(parse())
          else if (event === 'error') h.onError?.(parse() as { code?: number; msg?: string })
        }
      }
    } catch (e) {
      if ((e as Error).name !== 'AbortError') h.onError?.({ msg: '网络中断，请重试' })
    }
  })()
  return ctrl
}
