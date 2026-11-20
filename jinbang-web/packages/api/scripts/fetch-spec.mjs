// 从运行中的后端拉取 OpenAPI 文档：JB_API_BASE 默认 http://localhost:8080
import { writeFileSync } from 'node:fs'

const base = process.env.JB_API_BASE ?? 'http://localhost:8080'
const res = await fetch(`${base}/v3/api-docs`)
if (!res.ok) {
  console.error(`拉取 ${base}/v3/api-docs 失败：HTTP ${res.status}（请先启动后端）`)
  process.exit(1)
}
const spec = await res.json()
writeFileSync(new URL('../openapi.json', import.meta.url), JSON.stringify(spec, null, 2))
console.log(`已更新 openapi.json：${Object.keys(spec.paths ?? {}).length} 个接口路径`)
