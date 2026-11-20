<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { http, unwrap, type Schemas } from '@jb/api'
import { PageHeader, fmtTime } from '@jb/ui'

/** 我的试卷（我的卷库）：列表、回收站、恢复。 */
const router = useRouter()
const recycle = ref(false)
const papers = ref<Schemas['QPaper'][]>([])

async function load() {
  papers.value = await unwrap(http.GET('/api/papers', { params: { query: { recycle: recycle.value } } }))
}

async function toggle(p: Schemas['QPaper'], deleted: boolean) {
  await unwrap(http.POST('/api/papers/{id}/recycle', { params: { path: { id: p.id! }, query: { deleted } } }))
  ElMessage.success(deleted ? '已移入回收站' : '已恢复')
  load()
}

watch(recycle, load)
onMounted(load)
</script>

<template>
  <div>
    <PageHeader title="我的试卷" icon="卷" color="var(--jb-c-teaching)" desc="组好的试卷：设置分数、换题调序、试卷分析、导出 Word、制作答题卡、布置作业">
      <el-button type="primary" @click="router.push('/resources/questions')">去组卷</el-button>
    </PageHeader>
    <el-card>
      <el-radio-group v-model="recycle" style="margin-bottom: 12px">
        <el-radio-button :value="false">全部试卷</el-radio-button>
        <el-radio-button :value="true">回收站</el-radio-button>
      </el-radio-group>
      <el-table :data="papers" empty-text="还没有试卷，去题库组一份吧">
        <el-table-column label="试卷名称"><template #default="{ row }"><a class="link" @click="router.push(`/resources/papers/${row.id}`)">{{ row.title }}</a></template></el-table-column>
        <el-table-column prop="subject" label="学科" width="100" />
        <el-table-column label="总分" width="90"><template #default="{ row }">{{ row.totalScore }}</template></el-table-column>
        <el-table-column label="方式" width="90"><template #default="{ row }">{{ row.paperMode === 'SMART' ? '智能组卷' : '选题组卷' }}</template></el-table-column>
        <el-table-column label="创建时间" width="170"><template #default="{ row }">{{ fmtTime(row.createdAt) }}</template></el-table-column>
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <el-button v-if="!recycle" link type="primary" @click="router.push(`/resources/papers/${row.id}`)">编辑</el-button>
            <el-button link :type="recycle ? 'primary' : 'danger'" @click="toggle(row, !recycle)">{{ recycle ? '恢复' : '删除' }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.link { cursor: pointer; color: var(--jb-brand); }
</style>
