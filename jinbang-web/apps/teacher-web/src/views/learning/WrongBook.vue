<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http, unwrap, type ApiError, type Schemas } from '@jb/api'
import { MathText, PageHeader, fmtTime, useDict, usePaged } from '@jb/ui'
import { useMyClasses, useNames } from '../../composables/school'

/**
 * 错题本（PRD 6.7）：班级 / 学科 / 是否掌握筛选；AI 举一反三生成变式题；
 * 勾选错题组成错题本，可生成答题卡用于再练。
 */
const { classes, className } = useMyClasses()
const names = useNames()
const { items: subjects, label: subjectLabel } = useDict('sys_subject')
const filter = ref({ classId: '', subject: '', mastered: '' as '' | 'true' | 'false' })
const picked = ref<Schemas['LnWrongItem'][]>([])
const books = ref<Schemas['LnWrongBook'][]>([])
const variants = ref('')
const busy = ref('')

const { query, rows, total, loading, load } = usePaged<Schemas['LnWrongItem'], Record<string, unknown>>(
  async (q) => {
    const p = await unwrap(http.GET('/api/learning/wrong', { params: { query: {
      classIds: filter.value.classId ? [filter.value.classId] : undefined, subject: filter.value.subject || undefined,
      mastered: filter.value.mastered === '' ? undefined : filter.value.mastered === 'true', pageNum: q.pageNum, pageSize: q.pageSize,
    } as never } }))
    await names.loadNames(p.rows?.map((r) => r.studentId) ?? [])
    return p
  },
  {},
  20,
)

async function loadBooks() {
  books.value = await unwrap(http.GET('/api/learning/wrong-books', { params: { query: {} } })).catch(() => [])
}

async function master(w: Schemas['LnWrongItem']) {
  await unwrap(http.POST('/api/learning/wrong/{id}/master', { params: { path: { id: w.id! }, query: { mastered: !w.mastered } } }))
  w.mastered = !w.mastered
}

async function variant(w: Schemas['LnWrongItem']) {
  busy.value = w.id!
  try {
    const r = await unwrap(http.POST('/api/learning/wrong/{id}/variants', { params: { path: { id: w.id! }, query: { count: 2 } } }))
    variants.value = `${r.content ?? ''}\n\n（本次消耗 ${r.charged ?? 0} 金榜币，内容由 AI 生成，仅供参考）`
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  } finally {
    busy.value = ''
  }
}

async function makeBook() {
  if (!picked.value.length) return ElMessage.warning('请先勾选错题')
  const { value } = await ElMessageBox.prompt('错题本名称', '组成错题本', { inputValue: `${subjectLabel(picked.value[0].subject)}错题再练` })
  await unwrap(http.POST('/api/learning/wrong-books', { body: { name: value, subject: picked.value[0].subject, wrongIds: picked.value.map((w) => w.id!) } }))
  ElMessage.success('已创建')
  loadBooks()
}

async function removeBook(b: Schemas['LnWrongBook']) {
  await ElMessageBox.confirm(`删除「${b.bookName}」？`, '删除')
  await unwrap(http.POST('/api/learning/wrong-books/{id}/delete', { params: { path: { id: b.id! } } }))
  loadBooks()
}

onMounted(() => {
  load()
  loadBooks()
})
</script>

<template>
  <div>
    <PageHeader title="错题本" icon="错" color="var(--jb-c-learning)" desc="作业与考试中的错题自动收录；AI 生成变式题巩固，勾选错题组成错题本再练">
      <el-button type="primary" :disabled="!picked.length" @click="makeBook">组成错题本（{{ picked.length }}）</el-button>
    </PageHeader>
    <div class="wb">
      <el-card class="wb__main">
        <div class="jb-row bar">
          <el-select v-model="filter.classId" clearable placeholder="全部班级" style="width: 140px" @change="load(true)"><el-option v-for="c in classes" :key="c.classId" :label="c.className" :value="c.classId!" /></el-select>
          <el-select v-model="filter.subject" clearable placeholder="全部学科" style="width: 120px" @change="load(true)"><el-option v-for="s in subjects" :key="s.dictValue" :label="s.dictLabel" :value="s.dictValue!" /></el-select>
          <el-radio-group v-model="filter.mastered" @change="load(true)"><el-radio-button value="">全部</el-radio-button><el-radio-button value="false">未掌握</el-radio-button><el-radio-button value="true">已掌握</el-radio-button></el-radio-group>
        </div>
        <el-table v-loading="loading" :data="rows" size="small" empty-text="还没有错题" data-test="wrong-list" @selection-change="(v: Schemas['LnWrongItem'][]) => (picked = v)">
          <el-table-column type="selection" width="40" />
          <el-table-column label="学生" width="90"><template #default="{ row }">{{ names.student(row.studentId) }}</template></el-table-column>
          <el-table-column label="班级" width="90"><template #default="{ row }">{{ className(row.classId) }}</template></el-table-column>
          <el-table-column label="来源"><template #default="{ row }">{{ row.sourceTitle }}，第 {{ row.itemNo }} 题</template></el-table-column>
          <el-table-column label="得分" width="80"><template #default="{ row }">{{ row.score }}/{{ row.fullScore }}</template></el-table-column>
          <el-table-column label="作答" width="100" prop="studentAnswer" show-overflow-tooltip />
          <el-table-column label="时间" width="140"><template #default="{ row }">{{ fmtTime(row.occurredAt) }}</template></el-table-column>
          <el-table-column width="170">
            <template #default="{ row }">
              <el-button link :type="row.mastered ? 'success' : 'default'" @click="master(row)">{{ row.mastered ? '已掌握' : '标记掌握' }}</el-button>
              <el-button link type="primary" :loading="busy === row.id" @click="variant(row)">举一反三</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination v-model:current-page="query.pageNum" :page-size="query.pageSize" :total="total" layout="prev, pager, next, total" @current-change="load()" />
      </el-card>
      <el-card header="我的错题本" class="wb__books">
        <div v-for="b in books" :key="b.id" class="book">
          <b>{{ b.bookName }}</b>
          <small class="jb-muted">{{ subjectLabel(b.subject) }}，{{ (b.wrongIds ?? '').split(',').filter(Boolean).length }} 题，{{ fmtTime(b.createdAt) }}</small>
          <el-button link type="danger" size="small" @click="removeBook(b)">删除</el-button>
        </div>
        <el-empty v-if="!books.length" description="勾选错题后组成错题本" :image-size="40" />
      </el-card>
    </div>
    <el-dialog :model-value="!!variants" title="举一反三" width="680px" @close="variants = ''">
      <MathText :text="variants" block />
    </el-dialog>
  </div>
</template>

<style scoped>
.wb { display: flex; gap: 14px; align-items: flex-start; }
.wb__main { flex: 1; min-width: 0; }
.wb__books { width: 280px; flex-shrink: 0; }
.bar { margin-bottom: 12px; }
.book { display: flex; flex-direction: column; gap: 2px; padding: 8px 0; border-bottom: 1px dashed var(--jb-border); align-items: flex-start; }
.el-pagination { margin-top: 12px; justify-content: flex-end; }
</style>
