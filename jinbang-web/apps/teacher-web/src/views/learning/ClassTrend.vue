<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { http, unwrap, type Schemas } from '@jb/api'
import { EChart, PageHeader, fmtPct, useDict } from '@jb/ui'
import { useMyClasses, useNames } from '../../composables/school'

/** 班级学情（PRD 6.6）：班级历次作业 / 考试得分率走势，需要关注的学生与临界生。 */
const router = useRouter()
const { classes } = useMyClasses()
const names = useNames()
const { items: subjects } = useDict('sys_subject')
const classId = ref('')
const subject = ref('')
const range = ref<[string, string] | null>(null)
const trend = ref<Schemas['ClassTrend'] | null>(null)

watch(classes, (c) => {
  if (!classId.value && c.length) classId.value = c[0].classId!
}, { immediate: true })

async function load() {
  if (!classId.value) return
  trend.value = await unwrap(http.GET('/api/learning/classes/{classId}/trend', { params: { path: { classId: classId.value }, query: {
    subject: subject.value || undefined, from: range.value?.[0], to: range.value?.[1],
  } } })).catch(() => null)
  await names.loadNames([...(trend.value?.attention ?? []), ...(trend.value?.critical ?? [])])
}
watch([classId, subject, range], load)

const chart = computed(() => ({
  tooltip: { trigger: 'axis', valueFormatter: (v: number) => `${v}%` },
  grid: { left: 44, right: 20, top: 30, bottom: 60 },
  xAxis: { type: 'category', data: trend.value?.points?.map((p) => p.title) ?? [], axisLabel: { rotate: 20, width: 90, overflow: 'truncate' } },
  yAxis: { type: 'value', max: 100, name: '得分率 %' },
  series: [{ type: 'line', smooth: true, areaStyle: { opacity: 0.12 }, symbolSize: 8, data: trend.value?.points?.map((p) => Math.round(Number(p.avgRate) * 100)) ?? [] }],
}))

function open(p: Schemas['TrendPoint']) {
  router.push(`/learning/reports/${String(p.sourceType).toLowerCase()}/${p.sourceId}`)
}
</script>

<template>
  <div>
    <PageHeader title="班级学情" icon="班" color="var(--jb-c-learning)" desc="跟踪班级历次作业与考试的得分率变化，及时发现需要关注的学生">
      <el-select v-model="classId" style="width: 150px"><el-option v-for="c in classes" :key="c.classId" :label="c.className" :value="c.classId!" /></el-select>
      <el-select v-model="subject" clearable placeholder="全部学科" style="width: 120px"><el-option v-for="s in subjects" :key="s.dictValue" :label="s.dictLabel" :value="s.dictValue!" /></el-select>
      <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始" end-placeholder="结束" style="width: 240px" />
    </PageHeader>
    <el-empty v-if="!classes.length" description="你目前没有任教班级" />
    <template v-else>
      <el-card header="得分率走势">
        <EChart v-if="trend?.points?.length" :option="chart" height="320px" />
        <el-empty v-else description="这个班还没有批阅完成的作业或考试" :image-size="60" />
      </el-card>
      <div class="two">
        <el-card header="需要关注（连续下滑或低于及格线）">
          <div class="names"><el-tag v-for="s in trend?.attention" :key="s" type="danger" effect="plain">{{ names.student(s) }}</el-tag><span v-if="!trend?.attention?.length" class="jb-muted">暂无</span></div>
        </el-card>
        <el-card header="临界生（离上一等级只差一点）">
          <div class="names"><el-tag v-for="s in trend?.critical" :key="s" type="warning" effect="plain">{{ names.student(s) }}</el-tag><span v-if="!trend?.critical?.length" class="jb-muted">暂无</span></div>
        </el-card>
      </div>
      <el-card header="历次记录">
        <el-table :data="trend?.points ?? []" size="small" @row-click="open">
          <el-table-column prop="title" label="名称" />
          <el-table-column label="类型" width="80"><template #default="{ row }">{{ row.sourceType === 'EXAM' ? '考试' : '作业' }}</template></el-table-column>
          <el-table-column prop="time" label="时间" width="170" />
          <el-table-column prop="valid" label="有效人数" width="90" />
          <el-table-column label="得分率" width="90"><template #default="{ row }">{{ fmtPct(row.avgRate) }}</template></el-table-column>
        </el-table>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.two { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; margin: 14px 0; }
.names { display: flex; flex-wrap: wrap; gap: 6px; }
:deep(.el-table__row) { cursor: pointer; }
</style>
