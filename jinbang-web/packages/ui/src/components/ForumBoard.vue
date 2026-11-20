<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http, unwrap, type ApiError, type Schemas } from '@jb/api'
import { useAuth } from '@jb/auth'
import { fmtTime } from '../composables'

/**
 * 社区版块（PRD 4.4 金榜杏坛 / 3.10 学海共渡）：时间、学段学科筛选；发布前内容审核（学生帖人工审核后可见）；
 * 评论、点赞、举报。教师端与学生端共用。
 */
const props = defineProps<{ board: 'XINGTAN' | 'STUDENT_FORUM'; maxLength?: number }>()
type Post = Schemas['TcPost']
const auth = useAuth()
const posts = ref<Post[]>([])
const total = ref(0)
const q = reactive({ days: undefined as number | undefined, pageNum: 1 })
const draft = reactive({ title: '', content: '' })
const opened = ref<string | null>(null)
const comments = ref<Schemas['TcPostComment'][]>([])
const reply = ref('')

async function load() {
  const page = await unwrap(http.GET('/api/community/posts', { params: { query: { board: props.board, days: q.days, pageNum: q.pageNum, pageSize: 10 } } }))
  posts.value = page.rows ?? []
  total.value = page.total ?? 0
}

async function publish() {
  if (!draft.content.trim()) return ElMessage.warning('请输入内容')
  try {
    const p = await unwrap(http.POST('/api/community/posts', { body: { board: props.board, title: draft.title || undefined, content: draft.content } }))
    ElMessage.success(p.postStatus === 'PENDING' ? '已提交，审核通过后展示' : '发布成功')
    draft.title = ''
    draft.content = ''
    load()
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

async function toggle(p: Post) {
  if (opened.value === p.id) {
    opened.value = null
    return
  }
  opened.value = p.id!
  comments.value = await unwrap(http.GET('/api/community/posts/{id}/comments', { params: { path: { id: p.id! } } }))
}

async function comment(p: Post) {
  if (!reply.value.trim()) return
  try {
    await unwrap(http.POST('/api/community/posts/{id}/comments', { params: { path: { id: p.id! } }, body: { content: reply.value } }))
    reply.value = ''
    opened.value = null
    await toggle(p)
    load()
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

async function like(p: Post) {
  await unwrap(http.POST('/api/community/posts/{id}/like', { params: { path: { id: p.id! } } }))
  load()
}

async function report(p: Post) {
  const { value } = await ElMessageBox.prompt('请说明举报原因', '举报')
  await unwrap(http.POST('/api/reports', { body: { targetType: 'POST', targetId: p.id!, targetOwnerId: p.authorUserId, reason: value } }))
  ElMessage.success('举报已提交')
}

async function remove(p: Post) {
  await ElMessageBox.confirm('删除这条动态？', '删除')
  await unwrap(http.POST('/api/community/posts/{id}/remove', { params: { path: { id: p.id! } } }))
  load()
}

onMounted(load)
</script>

<template>
  <div class="fb">
    <div class="fb__compose jb-card">
      <el-input v-if="props.board === 'STUDENT_FORUM'" v-model="draft.title" placeholder="标题（选填）" />
      <el-input v-model="draft.content" type="textarea" :rows="3" :maxlength="props.maxLength ?? 2000" show-word-limit placeholder="分享你的想法…" data-test="post-input" />
      <div class="jb-between">
        <span class="jb-muted small">{{ props.board === 'STUDENT_FORUM' ? '学生发帖需老师审核后展示' : '发布前会进行内容安全检查' }}</span>
        <el-button type="primary" data-test="post-submit" @click="publish">发布</el-button>
      </div>
    </div>
    <el-radio-group v-model="q.days" class="fb__filter" @change="load">
      <el-radio-button :value="undefined">全部</el-radio-button>
      <el-radio-button :value="7">近一周</el-radio-button>
      <el-radio-button :value="30">近一月</el-radio-button>
      <el-radio-button :value="90">近三月</el-radio-button>
    </el-radio-group>
    <el-empty v-if="!posts.length" description="还没有动态" />
    <article v-for="p in posts" :key="p.id" class="post jb-card" data-test="post">
      <header class="jb-between">
        <strong>{{ p.title || p.authorName || '老师' }}</strong>
        <small class="jb-muted">{{ fmtTime(p.createdAt) }}</small>
      </header>
      <p class="post__body">{{ p.content }}</p>
      <footer class="post__ops">
        <el-button text size="small" @click="like(p)">👍 {{ p.likeCount }}</el-button>
        <el-button text size="small" @click="toggle(p)">评论 {{ p.commentCount }}</el-button>
        <el-button text size="small" @click="report(p)">举报</el-button>
        <el-button v-if="p.authorUserId === auth.me?.userId" text size="small" type="danger" @click="remove(p)">删除</el-button>
      </footer>
      <div v-if="opened === p.id" class="post__cmts">
        <div v-for="c in comments" :key="c.id" class="cmt">{{ c.content }} <small class="jb-muted">{{ fmtTime(c.createdAt) }}</small></div>
        <div class="send"><el-input v-model="reply" size="small" placeholder="写评论" @keyup.enter="comment(p)" /><el-button size="small" type="primary" @click="comment(p)">发送</el-button></div>
      </div>
    </article>
    <el-pagination v-if="total > 10" v-model:current-page="q.pageNum" :total="total" :page-size="10" layout="prev, pager, next" @current-change="load" />
  </div>
</template>

<style scoped>
.fb { display: flex; flex-direction: column; gap: 14px; max-width: 860px; }
.fb__compose { display: flex; flex-direction: column; gap: 10px; }
.small { font-size: 12px; }
.post__body { white-space: pre-wrap; line-height: 1.8; margin: 8px 0; }
.post__ops { display: flex; gap: 4px; border-top: 1px dashed var(--jb-border); padding-top: 6px; }
.post__cmts { margin-top: 8px; padding: 8px 12px; background: var(--jb-brand-soft); border-radius: 8px; }
.cmt { padding: 4px 0; }
.send { display: flex; gap: 6px; margin-top: 6px; }
</style>
