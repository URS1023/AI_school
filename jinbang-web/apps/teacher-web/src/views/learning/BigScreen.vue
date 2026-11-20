<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { http, unwrap, type Schemas } from '@jb/api'
import { useAuth } from '@jb/auth'
import { EChart, useDict } from '@jb/ui'

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
const gauge = (v?: number, color = '#3fd1b0') => ({
  series: [{
    type: 'gauge', startAngle: 220, endAngle: -40, radius: '95%', progress: { show: true, width: 10, itemStyle: { color } },
    axisLine: { lineStyle: { width: 10, color: [[1, 'rgba(255,255,255,0.08)']] } }, axisTick: { show: false }, splitLine: { show: false }, axisLabel: { show: false }, pointer: { show: false },
    detail: { valueAnimation: true, formatter: '{value}%', color: '#fff', fontSize: 18, offsetCenter: [0, '10%'] }, data: [{ value: pct(v) }],
  }],
})
const bars = computed(() => ({
  textStyle: { color: '#cfe' },
  tooltip: { trigger: 'axis' },
  legend: { textStyle: { color: '#cfe' }, top: 0 },
  grid: { left: 40, right: 10, top: 36, bottom: 24 },
  xAxis: { type: 'category', data: boards.value.map((b) => subjectLabel(b.subject)), axisLine: { lineStyle: { color: '#456' } } },
  yAxis: { type: 'value', max: 100, splitLine: { lineStyle: { color: 'rgba(255,255,255,0.06)' } } },
  series: [
    { name: '扫描率', type: 'bar', data: boards.value.map((b) => pct(b.scanRate)), itemStyle: { color: '#3fd1b0' } },
    { name: '批阅率', type: 'bar', data: boards.value.map((b) => pct(b.markedRate)), itemStyle: { color: '#f1b44c' } },
    { name: '得分率', type: 'bar', data: boards.value.map((b) => pct(b.scoreRate)), itemStyle: { color: '#7aa7ff' } },
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
        <h3>{{ subjectLabel(b.subject) }} <small>{{ b.sources }} 次</small></h3>
        <div class="gauges">
          <div><EChart :option="gauge(b.scanRate)" height="120px" /><span>扫描率</span></div>
          <div><EChart :option="gauge(b.markedRate, '#f1b44c')" height="120px" /><span>批阅率</span></div>
          <div><EChart :option="gauge(b.scoreRate, '#7aa7ff')" height="120px" /><span>得分率</span></div>
        </div>
      </div>
      <div v-if="!boards.length" class="board empty">这段时间还没有数据</div>
    </section>
    <section class="board wide"><h3>学科对比</h3><EChart :option="bars" height="260px" /></section>
  </div>
</template>

<style scoped>
.screen { margin: -24px; padding: 20px 28px 28px; min-height: calc(100vh - var(--jb-header-h)); color: #e6f4f1; background: radial-gradient(circle at 20% 0%, #12403a, transparent 50%), radial-gradient(circle at 90% 10%, #2a2f55, transparent 45%), #0b1418; }
header { display: grid; grid-template-columns: 1fr auto 1fr; align-items: center; margin-bottom: 18px; }
header h1 { margin: 0; font-size: 28px; letter-spacing: 6px; background: linear-gradient(90deg, #3fd1b0, #f1b44c); -webkit-background-clip: text; background-clip: text; color: transparent; }
.right { display: flex; gap: 12px; align-items: center; justify-content: flex-end; }
.summary { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; margin-bottom: 16px; }
.summary > div, .board { background: rgba(255, 255, 255, 0.04); border: 1px solid rgba(63, 209, 176, 0.25); border-radius: 12px; padding: 14px 18px; }
.summary b { display: block; font-size: 32px; color: #3fd1b0; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(360px, 1fr)); gap: 16px; margin-bottom: 16px; }
.board h3 { margin: 0 0 8px; font-size: 16px; }
.board h3 small { color: #8fb; font-weight: normal; margin-left: 6px; }
.gauges { display: grid; grid-template-columns: repeat(3, 1fr); text-align: center; font-size: 12px; color: #9cb; }
.empty { display: grid; place-items: center; height: 160px; color: #789; }
</style>
