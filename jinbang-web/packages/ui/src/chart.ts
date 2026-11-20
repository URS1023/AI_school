/** 图表颜色取自设计令牌（tokens.css 里的 --chart-*，hex 值；ECharts 不认 oklch）。 */
export function chartColor(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || '#312926'
}

export const chartGood = () => chartColor('--chart-good')
export const chartBad = () => chartColor('--chart-bad')
