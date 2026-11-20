<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { http, postForm, unwrap, type ApiError, type Schemas } from '@jb/api'
import { PageHeader } from '@jb/ui'
import TaskResult from './TaskResult.vue'

/** 试卷批改（PRD 7.5）：按顺序上传试卷图片（或粘贴识别文本），可上传标准答案与评分细则（➕P0）；标注方式与评语可选；按页计费。 */
interface Page { name: string; key?: string; text?: string }
interface Result { items: { no: string; correct: boolean; score: number; fullScore: number; comment?: string }[]; total: number }
const form = ref({ subject: '数学', period: '高中', grade: '', markMode: '标注对错及得分', withComment: true, standardAnswer: '' })
const pages = ref<Page[]>([])
const pasted = ref('')
const busy = ref(false)
const task = ref<Schemas['TaskResult'] | null>(null)
const results = computed(() => (task.value?.items ?? []).map((i) => {
  try {
    return { item: i, r: i.status === 'SUCCESS' ? (JSON.parse(i.output ?? '{}') as Result) : null }
  } catch {
    return { item: i, r: null }
  }
}))

async function add(ev: Event) {
  const files = Array.from((ev.target as HTMLInputElement).files ?? [])
  for (const f of files) {
    const fd = new FormData()
    fd.append('file', f)
    try {
      pages.value.push({ name: f.name, key: await postForm<string>('/api/ai/upload', fd) })
    } catch (e) {
      ElMessage.error((e as ApiError).message)
    }
  }
}

async function correct() {
  const list = [...pages.value.map((p) => p.key!), ...(pasted.value.trim() ? [`text:${pasted.value}`] : [])]
  if (!list.length) return ElMessage.warning('请按顺序上传试卷图片，或粘贴作答文本')
  busy.value = true
  try {
    task.value = await unwrap(http.POST('/api/ai/paper/correct', { body: { ...form.value, pages: list } as never }))
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div>
    <PageHeader title="试卷批改" icon="批" color="var(--jb-c-ai)" desc="上传标准答案能显著提高准确度；逐题给出对错、得分与评语，每题得分不会超过该题满分" />
    <el-card>
      <el-form label-width="90px">
        <el-form-item label="学科年级"><el-input v-model="form.subject" style="width: 120px" /><el-input v-model="form.period" style="width: 100px; margin-left: 8px" /><el-input v-model="form.grade" placeholder="年级" style="width: 100px; margin-left: 8px" /></el-form-item>
        <el-form-item label="标注方式"><el-radio-group v-model="form.markMode"><el-radio-button value="标注对错">标注对错</el-radio-button><el-radio-button value="标注得分">标注得分</el-radio-button><el-radio-button value="标注对错及得分">对错及得分</el-radio-button></el-radio-group></el-form-item>
        <el-form-item label="评语"><el-switch v-model="form.withComment" active-text="给出评语" /></el-form-item>
        <el-form-item label="标准答案"><el-input v-model="form.standardAnswer" type="textarea" :rows="3" placeholder="每题答案与评分细则（建议填写）" /></el-form-item>
        <el-form-item label="试卷图片">
          <div class="pages"><el-tag v-for="(p, i) in pages" :key="i" closable @close="pages.splice(i, 1)">第 {{ i + 1 }} 页，{{ p.name }}</el-tag>
            <label class="upload">＋ 按顺序添加图片<input type="file" accept="image/*" multiple hidden @change="add" /></label></div>
        </el-form-item>
        <el-form-item label="或粘贴作答"><el-input v-model="pasted" type="textarea" :rows="3" placeholder="没有图片时可直接粘贴学生作答文本" /></el-form-item>
        <el-form-item><el-button type="primary" :loading="busy" @click="correct">开始批改</el-button></el-form-item>
      </el-form>
      <TaskResult v-if="task" :task="task" @updated="(t) => (task = t)" />
      <el-card v-for="x in results" :key="x.item.itemNo" shadow="never" class="page">
        <template #header><div class="jb-between"><b>{{ x.item.label }}</b><el-tag v-if="x.r" type="success">本页得分 {{ x.r.total }}</el-tag><el-tag v-else type="info">{{ x.item.message }}</el-tag></div></template>
        <el-table v-if="x.r" :data="x.r.items" size="small">
          <el-table-column prop="no" label="题号" width="70" />
          <el-table-column label="对错" width="70"><template #default="{ row }">{{ row.correct ? '✔' : '✘' }}</template></el-table-column>
          <el-table-column label="得分" width="100"><template #default="{ row }">{{ row.score }} / {{ row.fullScore }}</template></el-table-column>
          <el-table-column prop="comment" label="评语" />
        </el-table>
      </el-card>
    </el-card>
  </div>
</template>

<style scoped>
.pages { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.upload { cursor: pointer; color: var(--jb-brand); }
.page { margin-top: 12px; }
</style>
