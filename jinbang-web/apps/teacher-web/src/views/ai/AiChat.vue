<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http, unwrap, type Schemas } from '@jb/api'
import { MathText, fmtTime, streamChat } from '@jb/ui'

/**
 * 智能问答（PRD 7.2）：会话侧栏（新对话、搜索、置顶、重命名、删除）、按身份推荐问题、流式输出、停止生成、
 * 预计消耗提示（11.1 统一口径）、引用知识库、点赞点踩、「内容由 AI 生成」标识；失败不扣费。
 * 同一页面切换智能体：问答 / 客服（免费）/ 教案 / 家校沟通 / 学情分析助手。
 */
interface Msg { id?: string; role: 'user' | 'assistant'; content: string; taskId?: string; status?: string; charged?: number; note?: string }
const route = useRoute()
const agents = ref<Schemas['AiAgent'][]>([])
const agent = ref((route.query.agent as string) ?? 'ask')
const sessions = ref<Schemas['AiSession'][]>([])
const keyword = ref('')
const sessionId = ref<string | null>(null)
const messages = ref<Msg[]>([])
const recommends = ref<string[]>([])
const input = ref('')
const streaming = ref(false)
const taskId = ref<string | null>(null)
const estimate = ref(0)
const kbs = ref<Schemas['KbView'][]>([])
const kbIds = ref<string[]>([])
const tipClosed = ref(false)
const box = ref<HTMLDivElement>()
let ctrl: AbortController | null = null

const CHAT_AGENTS = ['ask', 'service', 'lesson_plan', 'home_school', 'learning']
const chatAgents = computed(() => agents.value.filter((a) => CHAT_AGENTS.includes(a.agentCode!) && a.agentStatus === 'ONLINE'))
const current = computed(() => agents.value.find((a) => a.agentCode === agent.value))
const free = computed(() => current.value?.priceMode === 'FREE')

async function loadSessions() {
  sessions.value = await unwrap(http.GET('/api/ai/sessions', { params: { query: { agentCode: agent.value, keyword: keyword.value || undefined } } }))
}

async function switchAgent(code: string) {
  agent.value = code
  newChat()
  recommends.value = await unwrap(http.GET('/api/ai/recommend', { params: { query: { agentCode: code } } })).catch(() => [])
  estimate.value = await unwrap(http.GET('/api/ai/estimate', { params: { query: { agentCode: code, units: current.value?.defaultUnits ?? 1 } } })).catch(() => 0)
  loadSessions()
}

function newChat() {
  sessionId.value = null
  messages.value = []
}

async function openSession(s: Schemas['AiSession']) {
  sessionId.value = s.id!
  const list = await unwrap(http.GET('/api/ai/sessions/{id}/messages', { params: { path: { id: s.id! } } }))
  messages.value = list.map((m) => ({ id: m.id, role: m.msgRole as 'user' | 'assistant', content: m.content ?? '', taskId: m.taskId, status: m.msgStatus }))
  scroll()
}

async function patchSession(s: Schemas['AiSession'], cmd: string) {
  if (cmd === 'rename') {
    const { value } = await ElMessageBox.prompt('会话名称', '重命名', { inputValue: s.title })
    await unwrap(http.PUT('/api/ai/sessions/{id}', { params: { path: { id: s.id! } }, body: { title: value } }))
  } else if (cmd === 'pin') {
    await unwrap(http.PUT('/api/ai/sessions/{id}', { params: { path: { id: s.id! } }, body: { pinned: !s.pinned } }))
  } else {
    await unwrap(http.PUT('/api/ai/sessions/{id}', { params: { path: { id: s.id! } }, body: { deleted: true } }))
    if (sessionId.value === s.id) newChat()
  }
  loadSessions()
}

function scroll() {
  nextTick(() => box.value?.scrollTo({ top: box.value.scrollHeight, behavior: 'smooth' }))
}

function send(text?: string) {
  const q = (text ?? input.value).trim()
  if (!q || streaming.value) return
  input.value = ''
  messages.value.push({ role: 'user', content: q })
  const reply: Msg = { role: 'assistant', content: '' }
  messages.value.push(reply)
  const live = messages.value[messages.value.length - 1]
  streaming.value = true
  scroll()
  ctrl = streamChat({ agentCode: agent.value, sessionId: sessionId.value, question: q, kbIds: kbIds.value.length ? kbIds.value : undefined }, {
    onStart: (id) => (taskId.value = id),
    onDelta: (t) => {
      live.content += t
      scroll()
    },
    onDone: (d) => {
      const r = d as Schemas['ChatReply']
      sessionId.value = r.sessionId ?? sessionId.value
      live.id = r.messageId
      live.taskId = r.task?.taskId
      live.status = r.task?.status
      live.charged = Number(r.task?.charged ?? 0)
      if (r.task?.status !== 'SUCCESS' && r.task?.status !== 'STOPPED') live.content = r.task?.userMessage ?? live.content
      live.note = r.task?.userMessage
      finish()
      loadSessions()
    },
    onError: (e) => {
      live.content = e.msg ?? '服务暂时不可用，本次未扣费'
      live.status = 'FAILED'
      finish()
    },
  })
}

function finish() {
  streaming.value = false
  taskId.value = null
  ctrl = null
}

async function stop() {
  if (taskId.value) await unwrap(http.POST('/api/ai/tasks/{taskId}/stop', { params: { path: { taskId: taskId.value } } })).catch(() => undefined)
}

async function feedback(m: Msg, liked: boolean) {
  if (!m.id) return
  await unwrap(http.POST('/api/ai/messages/{id}/feedback', { params: { path: { id: m.id } }, body: { liked } }))
  ElMessage.success('感谢反馈')
}

function copy(m: Msg) {
  navigator.clipboard?.writeText(m.content).then(() => ElMessage.success('已复制'))
}

watch(keyword, () => loadSessions())
onMounted(async () => {
  agents.value = await unwrap(http.GET('/api/ai/agents')).catch(() => [])
  kbs.value = await unwrap(http.GET('/api/ai/kb')).catch(() => [])
  switchAgent(agent.value)
})
onBeforeUnmount(() => ctrl?.abort())
</script>

<template>
  <div class="chat">
    <aside class="chat__side jb-card">
      <el-button type="primary" class="full" @click="newChat">开启新对话</el-button>
      <el-input v-model="keyword" size="small" placeholder="搜索历史记录" clearable />
      <div class="sessions">
        <div v-for="s in sessions" :key="s.id" class="sess" :class="{ on: sessionId === s.id }" @click="openSession(s)">
          <span class="jb-ellipsis">{{ s.title }}</span><small v-if="s.pinned" class="pin">置顶</small>
          <el-dropdown trigger="click" @command="patchSession(s, $event)" @click.stop>
            <span class="more" @click.stop>⋯</span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="pin">{{ s.pinned ? '取消置顶' : '置顶' }}</el-dropdown-item>
                <el-dropdown-item command="rename">重命名</el-dropdown-item>
                <el-dropdown-item command="delete">删除</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
        <el-empty v-if="!sessions.length" description="暂无历史" :image-size="40" />
      </div>
    </aside>
    <section class="chat__main">
      <div class="agents">
        <button v-for="a in chatAgents" :key="a.agentCode" type="button" :class="{ on: agent === a.agentCode }" @click="switchAgent(a.agentCode!)">
          {{ a.agentName }}<small v-if="a.priceMode === 'FREE'">免费</small>
        </button>
      </div>
      <el-alert v-if="!free && !tipClosed" type="warning" show-icon :closable="true" @close="tipClosed = true"
                :title="`计费智能体：先使用再扣费。本次预计消耗不超过 ${estimate} 金榜币，按实际用量结算；失败或无结果不扣费。`" />
      <div ref="box" class="msgs">
        <div v-if="!messages.length" class="hero">
          <h2>{{ current?.agentName ?? 'AI 助手' }}</h2>
          <p class="jb-muted">{{ current?.description }}</p>
          <div class="recs"><button v-for="r in recommends" :key="r" type="button" @click="send(r)">{{ r }}</button></div>
        </div>
        <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
          <div class="bubble" :class="{ failed: m.status && m.status !== 'SUCCESS' && m.status !== 'STOPPED' }">
            <MathText v-if="m.content" :text="m.content" block />
            <span v-else class="typing">思考中…</span>
          </div>
          <div v-if="m.role === 'assistant' && m.content && !(streaming && i === messages.length - 1)" class="meta">
            <span class="jb-muted">内容由 AI 生成，仅供参考{{ m.note ? `，${m.note}` : '' }}</span>
            <el-button text size="small" @click="copy(m)">复制</el-button>
            <el-button text size="small" @click="feedback(m, true)">有用</el-button>
            <el-button text size="small" @click="feedback(m, false)">没帮助</el-button>
          </div>
        </div>
      </div>
      <div class="composer jb-card">
        <el-input v-model="input" type="textarea" :rows="3" resize="none" placeholder="输入问题，Enter 发送，Shift+Enter 换行" data-test="chat-input"
                  @keydown.enter.exact.prevent="send()" />
        <div class="jb-between">
          <el-select v-model="kbIds" multiple collapse-tags size="small" placeholder="引用知识库（可选）" style="width: 260px">
            <el-option v-for="k in kbs" :key="k.kb?.id" :label="k.kb?.kbName" :value="k.kb!.id!" />
          </el-select>
          <div class="jb-row">
            <el-button v-if="streaming" @click="stop">停止生成</el-button>
            <el-button type="primary" :loading="streaming" data-test="chat-send" @click="send()">发送</el-button>
          </div>
        </div>
      </div>
      <p class="jb-muted foot">会话更新于 {{ fmtTime(sessions.find((s) => s.id === sessionId)?.updatedAt) || '—' }}</p>
    </section>
  </div>
</template>

<style scoped>
.chat { display: flex; gap: 16px; height: calc(100vh - var(--jb-header-h) - 64px); min-height: 520px; }
.chat__side { width: 240px; flex-shrink: 0; display: flex; flex-direction: column; gap: 10px; }
.full { width: 100%; }
.sessions { flex: 1; overflow: auto; }
.sess { display: flex; justify-content: space-between; gap: 6px; padding: 8px 10px; border-radius: 8px; cursor: pointer; }
.sess:hover { background: var(--color-paper-2); }
.sess.on { background: var(--color-cotton); box-shadow: var(--edge-stock), var(--cast-stock); font-weight: 500; }
.pin { font-size: var(--text-xs); color: var(--color-muted); flex-shrink: 0; }
.more { padding: 0 4px; color: var(--jb-text-secondary); }
.chat__main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 10px; }
.agents { display: flex; gap: 8px; flex-wrap: wrap; }
.agents { background: var(--color-paper-2); border-radius: var(--radius-stock); padding: 3px; box-shadow: var(--press-groove); align-self: flex-start; }
.agents button { border: none; background: transparent; min-height: 32px; padding: 0 14px; border-radius: 6px; cursor: pointer; font: inherit; color: var(--color-ink-2); }
.agents button.on { background: var(--color-cotton); color: var(--color-ink); font-weight: 600; box-shadow: var(--edge-stock), var(--cast-stock); }
.agents small { margin-left: 4px; font-size: 11px; opacity: 0.8; }
.msgs { flex: 1; overflow: auto; padding: 8px 4px; display: flex; flex-direction: column; gap: 14px; }
.hero { margin: auto; text-align: center; max-width: 720px; }
.hero h2 { margin: 0 0 6px; font-size: var(--text-2xl); color: var(--color-ink); }
.recs { display: flex; flex-wrap: wrap; justify-content: center; gap: 10px; margin-top: 18px; }
.recs button { border: none; font: inherit; background: var(--color-cotton); box-shadow: var(--edge-stock), var(--cast-stock); padding: 10px 14px; border-radius: var(--radius-stock); cursor: pointer; text-align: left; }
.recs button:focus-visible, .agents button:focus-visible { outline: 2px solid var(--color-focus); outline-offset: 2px; }
.msg { display: flex; flex-direction: column; max-width: 80%; }
.msg.user { align-self: flex-end; align-items: flex-end; }
.msg.assistant { align-self: flex-start; }
.bubble { padding: 10px 14px; border-radius: 12px; background: var(--jb-surface); border: 1px solid var(--jb-border); }
.msg.user .bubble { background: var(--color-ink); color: var(--color-cotton); border-color: var(--color-ink); }
.bubble.failed { background: var(--color-bad-soft); border-color: var(--color-rule-2); }
.typing { color: var(--jb-text-secondary); }
.meta { display: flex; align-items: center; gap: 2px; font-size: 12px; margin-top: 2px; }
.composer { display: flex; flex-direction: column; gap: 8px; }
.foot { font-size: 12px; margin: 0; }
</style>
