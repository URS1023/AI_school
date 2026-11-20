<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart, RadarChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent, TitleComponent, RadarComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { chartColor } from '../chart'

echarts.use([BarChart, LineChart, PieChart, RadarChart, GridComponent, LegendComponent, TooltipComponent, TitleComponent, RadarComponent, CanvasRenderer])

/** 统一图表组件：主题色取自设计令牌，容器尺寸变化自动重绘。 */
const props = defineProps<{ option: echarts.EChartsCoreOption; height?: string }>()
const el = ref<HTMLDivElement>()
const chart = shallowRef<echarts.ECharts>()
let ro: ResizeObserver | undefined

function render() {
  const palette = [1, 2, 3, 4, 5, 6].map((i) => chartColor(`--chart-${i}`))
  chart.value?.setOption({
    color: palette,
    textStyle: { fontFamily: 'inherit', color: chartColor('--chart-2') },
    ...props.option,
  }, true)
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
