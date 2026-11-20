<script setup lang="ts">
import { computed } from 'vue'
import katex from 'katex'
import 'katex/dist/katex.min.css'

/**
 * 题干 / 答案 / AI 回答的文本渲染：转义 HTML，$...$ 与 $$...$$ 用 KaTeX 渲染，换行保留。
 * 只渲染公式，不解析任意 HTML，避免题目内容里的脚本注入。
 */
const props = defineProps<{ text?: string | null; block?: boolean }>()

function esc(s: string) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

const html = computed(() => {
  const src = props.text ?? ''
  const parts = src.split(/(\$\$[\s\S]+?\$\$|\$[^$\n]+?\$)/g)
  return parts
    .map((p) => {
      const display = p.startsWith('$$') && p.endsWith('$$') && p.length > 4
      const inline = !display && p.startsWith('$') && p.endsWith('$') && p.length > 2
      if (display || inline) {
        try {
          return katex.renderToString(p.slice(display ? 2 : 1, display ? -2 : -1), { displayMode: display, throwOnError: false })
        } catch {
          return esc(p)
        }
      }
      return esc(p).replace(/\n/g, '<br>')
    })
    .join('')
})
</script>

<template>
  <!-- eslint-disable-next-line vue/no-v-html -->
  <component :is="props.block ? 'div' : 'span'" class="jb-math" v-html="html" />
</template>

<style scoped>
.jb-math { line-height: 1.8; word-break: break-word; }
</style>
