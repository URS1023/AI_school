<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { http, unwrap, type Schemas } from '@jb/api'
import { useAuth } from '@jb/auth'

/** 管理首页：学校管理员看本校概况与待办（入校审核），平台运营看待审核内容；入口按权限出现。 */
const auth = useAuth()
const router = useRouter()
const isSchool = computed(() => auth.hasPerm('school:org:manage'))
const isOps = computed(() => auth.hasPerm('op:'))
const year = ref<Schemas['EduAcademicYear'] | null>(null)
const pendingJoin = ref(0)
const pendingAudit = ref(0)

const schoolEntries = [
  { name: '学年与班级', path: '/edu-affairs/org', perm: 'school:org:manage' },
  { name: '教师管理', path: '/edu-affairs/teacher', perm: 'school:teacher:manage' },
  { name: '学生管理', path: '/edu-affairs/student', perm: 'school:student:manage' },
  { name: '入校审核', path: '/edu-affairs/join', perm: 'school:join:audit' },
]

onMounted(async () => {
  if (isSchool.value) {
    year.value = await unwrap(http.GET('/api/school/years/current')).catch(() => null)
    pendingJoin.value = (await unwrap(http.GET('/api/school/join/requests', { params: { query: { status: 'PENDING' } } })).catch(() => [])).length
  }
  if (auth.hasPerm(['op:audit', 'school:resource:audit'])) {
    pendingAudit.value = (await unwrap(http.GET('/api/audit/pending', { params: { query: { pageNum: 1, pageSize: 1 } } })).catch(() => ({ total: 0 }))).total ?? 0
  }
})
</script>

<template>
  <div class="admin-home">
    <el-card v-if="isSchool" header="学校概况" data-test="school-card">
      <el-descriptions :column="3" border>
        <el-descriptions-item label="当前学年">{{ year?.yearName ?? '尚未设置（请先创建学年）' }}</el-descriptions-item>
        <el-descriptions-item label="待审核入校申请">{{ pendingJoin }}</el-descriptions-item>
        <el-descriptions-item label="待审核校本资源">{{ pendingAudit }}</el-descriptions-item>
      </el-descriptions>
      <div class="admin-home__entries">
        <el-button v-for="e in schoolEntries.filter((x) => auth.hasPerm(x.perm))" :key="e.path" @click="router.push(e.path)">{{ e.name }}</el-button>
      </div>
    </el-card>
    <el-card v-if="isOps" header="平台运营" data-test="ops-card">
      <el-statistic title="待审核内容" :value="pendingAudit" />
    </el-card>
  </div>
</template>

<style scoped>
.admin-home { display: flex; flex-direction: column; gap: 16px; }
.admin-home__entries { margin-top: 16px; display: flex; gap: 12px; flex-wrap: wrap; }
.admin-home__entries .el-button { margin: 0; }
</style>
