import { reactive, ref, watch, type Ref } from 'vue'
import { http, unwrap, type Schemas } from '@jb/api'

export type DictItem = Schemas['SysDictData']

const dictCache = new Map<string, Promise<DictItem[]>>()

/** 字典（PRD 2.3）：同一类型只请求一次。 */
export function loadDict(type: string): Promise<DictItem[]> {
  let p = dictCache.get(type)
  if (!p) {
    p = unwrap(http.GET('/api/open/dict/{dictType}', { params: { path: { dictType: type } } })).catch((e) => {
      dictCache.delete(type)
      throw e
    })
    dictCache.set(type, p)
  }
  return p
}

export function useDict(type: string) {
  const items = ref<DictItem[]>([])
  loadDict(type).then((d) => (items.value = d)).catch(() => undefined)
  const label = (value?: string | null) => items.value.find((i) => i.dictValue === value)?.dictLabel ?? value ?? ''
  return { items, label }
}

const PS_KEY = 'jb_period_subject'

/** 学段学科切换器的状态：记住上次选择（PRD 2.2）。存储失败时退回默认值。 */
export function usePeriodSubject() {
  let saved = { period: '1', subject: 'MATH' }
  try {
    saved = { ...saved, ...JSON.parse(localStorage.getItem(PS_KEY) ?? '{}') }
  } catch {
    /* 隐私模式等：用默认 */
  }
  const state = reactive(saved)
  watch(state, (v) => {
    try {
      localStorage.setItem(PS_KEY, JSON.stringify(v))
    } catch {
      /* 忽略 */
    }
  })
  return state
}

/** 分页列表：loader 返回 { total, rows }。 */
export function usePaged<T, Q extends Record<string, unknown>>(
  loader: (q: Q & { pageNum: number; pageSize: number }) => Promise<{ total?: number; rows?: T[] }>,
  initial: Q,
  pageSize = 20,
) {
  const query = reactive({ ...initial, pageNum: 1, pageSize }) as Q & { pageNum: number; pageSize: number }
  const rows = ref([]) as Ref<T[]>
  const total = ref(0)
  const loading = ref(false)
  async function load(resetPage = false) {
    if (resetPage) query.pageNum = 1
    loading.value = true
    try {
      const r = await loader(query)
      rows.value = r.rows ?? []
      total.value = r.total ?? 0
    } finally {
      loading.value = false
    }
  }
  return { query, rows, total, loading, load }
}

export function fmtTime(t?: string | null): string {
  return t ? t.replace('T', ' ').slice(0, 16) : ''
}

export function fmtPct(r?: number | null): string {
  return r == null ? '—' : `${(Number(r) * 100).toFixed(1)}%`
}

/** 触发浏览器下载 / 打开后端返回的短时链接。 */
export function openLink(url: string) {
  window.open(url, '_blank', 'noopener')
}
