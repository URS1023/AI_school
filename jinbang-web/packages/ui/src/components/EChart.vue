<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart, RadarChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent, TitleComponent, RadarComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([BarChart, LineChart, PieChart, RadarChart, GridComponent, LegendComponent, TooltipComponent, TitleComponent, RadarComponent, CanvasRenderer])

/** 统一图表组件：主题色取自设计令牌，容器尺寸变化自动重绘。 */
const props = defineProps<{ option: echarts.EChartsCoreOption; height?: string }>()
const el = ref<HTMLDivElement>()
const chart = shallowRef<echarts.ECharts>()
let ro: ResizeObserver | undefined

const PALETTE = ['#0f7a68', '#e8a33d', '#3b6fd8', '#c2486f', '#7a5bd6', '#4a5a73', '#3fa7a0']

function render() {
  chart.value?.setOption({ color: PALETTE, textStyle: { fontFamily: 'inherit' }, ...props.option }, true)
}

onMounted(() => {
  chart.value = echarts.init(el.value!)
  render()
  ro = new ResizeObserver(() => chart.value?.resize())
  ro.observe(el.value!)
})
watch(() => props.option, render, { deep: true })
onBeforeUnmount(() => {
  ro?.disconnect()
  chart.value?.dispose()
})
</script>

<template>
  <div ref="el" :style="{ width: '100%', height: props.height ?? '300px' }" />
</template>
