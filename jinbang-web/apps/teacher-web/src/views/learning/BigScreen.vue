<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { http, unwrap, type Schemas } from '@jb/api'
import { useAuth } from '@jb/auth'
import { EChart, chartColor, useDict } from '@jb/ui'

/** 学情大屏（PRD 6.9）：适合投屏的深色看板，按学科展示扫描率、批阅率、得分率；每 5 分钟自动刷新。 */
const auth = useAuth()
const { label: subjectLabel } = useDict('sys_subject')
const days = ref(30)
const boards = ref<Schemas['SubjectBoard'][]>([])
const now = ref(new Date())
let timer: number | undefined
let clock: number | undefined

async function load() {
  boards.value = await unwrap(http.GET('/api/learning/big-screen', { params: { query: { days: days.value } } })).catch(() => [])
}

const pct = (v?: number) => Math.round(Number(v ?? 0) * 100)
const gauge = (v?: number, token = '--chart-3') => ({
  series: [{
    type: 'gauge', startAngle: 220, endAngle: -40, radius: '95%', progress: { show: true, width: 10, itemStyle: { color: chartColor(token) } },
    axisLine: { lineStyle: { width: 10, color: [[1, chartColor('--chart-board-track')]] } }, axisTick: { show: false }, splitLine: { show: false }, axisLabel: { show: false }, pointer: { show: false },
    detail: { valueAnimation: false, formatter: '{value}%', color: chartColor('--chart-board-type'), fontSize: 18, fontFamily: 'Geist Mono, monospace', offsetCenter: [0, '10%'] }, data: [{ value: pct(v) }],
  }],
})
const bars = computed(() => ({
  textStyle: { color: chartColor('--chart-board-type') },
  tooltip: { trigger: 'axis' },
  legend: { textStyle: { color: chartColor('--chart-board-type') }, top: 0 },
  grid: { left: 40, right: 10, top: 36, bottom: 24 },
  xAxis: { type: 'category', data: boards.value.map((b) => subjectLabel(b.subject)), axisLine: { lineStyle: { color: chartColor('--chart-board-track') } } },
  yAxis: { type: 'value', max: 100, splitLine: { lineStyle: { color: chartColor('--chart-board-track') } } },
  series: [
    { name: '扫描率', type: 'bar', barMaxWidth: 22, data: boards.value.map((b) => pct(b.scanRate)), itemStyle: { color: chartColor('--chart-3') } },
    { name: '批阅率', type: 'bar', barMaxWidth: 22, data: boards.value.map((b) => pct(b.markedRate)), itemStyle: { color: chartColor('--chart-4') } },
    { name: '得分率', type: 'bar', barMaxWidth: 22, data: boards.value.map((b) => pct(b.scoreRate)), itemStyle: { color: chartColor('--chart-board-accent') } },
  ],
}))

function fullscreen() {
  document.documentElement.requestFullscreen?.()
}

onMounted(() => {
  load()
  timer = window.setInterval(load, 300_000)
  clock = window.setInterval(() => (now.value = new Date()), 1000)
})
onBeforeUnmount(() => {
  clearInterval(timer)
  clearInterval(clock)
})
</script>

<template>
  <div class="screen" data-test="big-screen">
    <header>
      <span>{{ auth.identities.find((i) => i.current)?.displayName?.split('·')[0] }}</span>
      <h1>教学质量看板</h1>
      <div class="right">
        <el-radio-group v-model="days" size="small" @change="load"><el-radio-button :value="7">近 7 天</el-radio-button><el-radio-button :value="30">近 30 天</el-radio-button><el-radio-button :value="90">近 90 天</el-radio-button></el-radio-group>
        <span>{{ now.toLocaleString('zh-CN', { hour12: false }) }}</span>
        <el-button size="small" @click="fullscreen">全屏</el-button>
      </div>
    </header>
    <section class="summary">
      <div><small>学科</small><b>{{ boards.length }}</b></div>
      <div><small>作业 / 考试</small><b>{{ boards.reduce((s, b) => s + (b.sources ?? 0), 0) }}</b></div>
      <div><small>平均得分率</small><b>{{ boards.length ? Math.round(boards.reduce((s, b) => s + pct(b.scoreRate), 0) / boards.length) : 0 }}%</b></div>
    </section>
    <section class="grid">
      <div v-for="b in boards" :key="b.subject" class="board">
        <h3>{{ subjectLabel(b.subject) }}</h3><p class="src">本期 {{ b.sources }} 次作业与考试</p>
        <div class="gauges">
          <div><EChart :option="gauge(b.scanRate)" height="120px" /><span>扫描率</span></div>
          <div><EChart :option="gauge(b.markedRate, '--chart-4')" height="120px" /><span>批阅率</span></div>
          <div><EChart :option="gauge(b.scoreRate, '--chart-board-accent')" height="120px" /><span>得分率</span></div>
        </div>
      </div>
      <div v-if="!boards.length" class="board empty">这段时间还没有数据</div>
    </section>
    <section class="board wide"><h3>学科对比</h3><EChart :option="bars" height="260px" /></section>
  </div>
</template>

<style scoped>
.screen { margin: -24px -28px -48px; padding: 24px 32px 32px; min-height: calc(100vh - var(--jb-header-h)); color: var(--color-board-type); background: var(--color-board); }
header { display: grid; grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr); align-items: center; gap: 16px; margin-bottom: 20px; padding-bottom: 14px; border-bottom: 1px solid oklch(100% 0 0 / 0.12); }
header h1 { margin: 0; font-size: var(--text-2xl); color: var(--color-board-type); }
header > span { color: var(--color-board-muted); }
.right { display: flex; gap: 12px; align-items: center; justify-content: flex-end; color: var(--color-board-muted); font-family: var(--font-outlier); font-size: var(--text-sm); }
.right :deep(.el-radio-group) { background: oklch(100% 0 0 / 0.06); box-shadow: none; }
.right :deep(.el-radio-button__inner) { color: var(--color-board-muted); }
.right :deep(.el-radio-button.is-active .el-radio-button__inner) { background: var(--color-board-type); color: var(--color-board); }
.summary { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; margin-bottom: 16px; }
.summary > div, .board { background: oklch(100% 0 0 / 0.04); border-radius: var(--radius-card); padding: 14px 18px; box-shadow: inset 1px 1px 0 oklch(100% 0 0 / 0.06); }
.summary small { color: var(--color-board-muted); }
.summary b { display: block; font-size: 32px; font-weight: 500; font-family: var(--font-outlier); font-variant-numeric: tabular-nums; color: var(--color-board-type); }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(360px, 1fr)); gap: 16px; margin-bottom: 16px; }
.board h3 { margin: 0; font-size: var(--text-md); color: var(--color-board-type); }
.src { margin: 2px 0 6px; color: var(--color-board-muted); font-size: var(--text-sm); }
.gauges { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); text-align: center; font-size: var(--text-xs); color: var(--color-board-muted); }
.empty { display: grid; place-items: center; height: 160px; color: var(--color-board-muted); }
</style>
