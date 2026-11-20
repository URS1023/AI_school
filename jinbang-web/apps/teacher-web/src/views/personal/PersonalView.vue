<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http, unwrap, type ApiError, type Schemas } from '@jb/api'
import { useAuth } from '@jb/auth'
import { fmtTime } from '@jb/ui'

/**
 * 个人中心（PRD 5.1、5.2）：用户卡与等级、金币明细（➕P0）、充值与订单、成长体系、反馈与举报记录（➕P1）、
 * 加入学校（R5、11.9）、绑定孩子（11.10）、账号安全（改密、注销）。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuth()
const tab = ref((route.params.tab as string) || 'overview')
const summary = ref<Schemas['Summary'] | null>(null)
const levels = ref<Schemas['Level'][]>([])
const ledger = ref<Schemas['LedgerVO'][]>([])
const ledgerTotal = ref(0)
const direction = ref('')
const expLedger = ref<Schemas['ExpLedger'][]>([])
const packages = ref<Schemas['Package'][]>([])
const orders = ref<Schemas['PayOrder'][]>([])
const feedback = ref<Schemas['OpFeedback'][]>([])
const reports = ref<Schemas['OpReport'][]>([])
const joins = ref<Schemas['EduJoinRequest'][]>([])
const children = ref<Schemas['EduParentBinding'][]>([])
const fb = ref({ feedbackType: '功能建议', contact: '', content: '' })
const join = ref({ inviteCode: '', realName: '', subject: '' })
const bind = ref({ schoolId: '', studentNo: '', studentName: '', inviteCode: '', relation: '父亲' })

const TABS = [
  ['overview', '概览'], ['coins', '金币明细'], ['orders', '充值与订单'], ['growth', '成长体系'],
  ['feedback', '反馈建议'], ['reports', '我的举报'], ['join', '加入学校'], ['children', '绑定孩子'], ['security', '账号安全'],
]
const DIR: Record<string, string> = { IN: '收入', OUT: '支出', FREEZE: '冻结', UNFREEZE: '解冻' }
const ORDER: Record<string, string> = { PENDING: '待支付', PAID: '已支付', CANCELLED: '已取消', REFUNDING: '退款中', REFUNDED: '已退款' }
const level = computed(() => levels.value.find((l) => l.no === summary.value?.levelNo))
const progress = computed(() => {
  const next = levels.value.find((l) => (l.minExp ?? 0) > (summary.value?.exp ?? 0))
  if (!next || !level.value) return 100
  return Math.round((((summary.value?.exp ?? 0) - (level.value.minExp ?? 0)) / ((next.minExp ?? 1) - (level.value.minExp ?? 0))) * 100)
})

async function loadTab() {
  router.replace(`/personal/${tab.value}`)
  summary.value = await unwrap(http.GET('/api/account/me'))
  if (tab.value === 'coins') {
    const p = await unwrap(http.GET('/api/account/coins/ledger', { params: { query: { direction: direction.value || undefined, pageNum: 1, pageSize: 50 } } }))
    ledger.value = p.rows ?? []
    ledgerTotal.value = p.total ?? 0
  } else if (tab.value === 'orders') {
    packages.value = await unwrap(http.GET('/api/open/pay/packages'))
    orders.value = (await unwrap(http.GET('/api/account/orders', { params: { query: { pageNum: 1, pageSize: 50 } } }))).rows ?? []
  } else if (tab.value === 'growth') {
    expLedger.value = (await unwrap(http.GET('/api/account/exp/ledger', { params: { query: { pageNum: 1, pageSize: 50 } } }))).rows ?? []
  } else if (tab.value === 'feedback') {
    feedback.value = (await unwrap(http.GET('/api/feedback/mine', { params: { query: { pageNum: 1, pageSize: 50 } } }))).rows ?? []
  } else if (tab.value === 'reports') {
    reports.value = (await unwrap(http.GET('/api/reports/mine', { params: { query: { pageNum: 1, pageSize: 50 } } }))).rows ?? []
  } else if (tab.value === 'join') {
    joins.value = await unwrap(http.GET('/api/school/join/mine'))
  } else if (tab.value === 'children') {
    children.value = await unwrap(http.GET('/api/school/parent/children'))
  }
}

async function sign() {
  try {
    summary.value = await unwrap(http.POST('/api/account/sign-in'))
    ElMessage.success('签到成功')
  } catch (e) {
    ElMessage.warning((e as ApiError).message)
  }
}

async function recharge(p: Schemas['Package']) {
  try {
    const r = await unwrap(http.POST('/api/account/orders', { body: { packageCode: p.code } }))
    await ElMessageBox.alert(`订单 ${r.orderNo} 已创建，请在 30 分钟内完成支付。\n支付参数：${r.payParams}`, '去支付')
    loadTab()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error((e as ApiError).message)
  }
}

async function orderOp(o: Schemas['PayOrder'], op: 'repay' | 'cancel') {
  try {
    if (op === 'repay') {
      const r = await unwrap(http.POST('/api/account/orders/{orderNo}/repay', { params: { path: { orderNo: o.orderNo! } } }))
      await ElMessageBox.alert(`支付参数：${r.payParams}`, '重新支付')
    } else {
      await unwrap(http.POST('/api/account/orders/{orderNo}/cancel', { params: { path: { orderNo: o.orderNo! } } }))
    }
    loadTab()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error((e as ApiError).message)
  }
}

async function submitFeedback() {
  try {
    await unwrap(http.POST('/api/feedback', { body: { ...fb.value, sourceApp: 'WEB' } }))
    ElMessage.success('感谢反馈，处理结果会通过消息通知你')
    fb.value.content = ''
    loadTab()
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

async function applyJoin() {
  try {
    await unwrap(http.POST('/api/school/join', { body: join.value }))
    ElMessage.success('已提交申请，请等待学校审核')
    loadTab()
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

async function bindChild() {
  try {
    await unwrap(http.POST('/api/school/parent/bind', { body: bind.value }))
    ElMessage.success('绑定成功，可在头像菜单切换到家长身份查看')
    await auth.loadSession()
    loadTab()
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

async function unbind(b: Schemas['EduParentBinding']) {
  await ElMessageBox.confirm('解除绑定后将无法再查看该学生的任何信息，确定？', '解除绑定')
  await unwrap(http.POST('/api/school/parent/bindings/{id}/unbind', { params: { path: { id: b.id! } } }))
  loadTab()
}

async function cancelAccount() {
  await ElMessageBox.confirm('注销后账号的全部身份将被撤销、手机号解绑，且不可恢复。确定继续？', '注销账号', { type: 'warning' })
  await unwrap(http.POST('/api/auth/sms/send-self', { body: { phone: '', scene: 'CANCEL' } }))
  const { value } = await ElMessageBox.prompt('已向你的手机发送验证码', '输入验证码')
  try {
    await unwrap(http.POST('/api/auth/cancel', { body: { code: value } }))
    auth.clear()
    router.replace('/login')
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

watch(tab, loadTab)
watch(direction, loadTab)
onMounted(async () => {
  levels.value = await unwrap(http.GET('/api/open/account/levels')).catch(() => [])
  loadTab()
})
</script>

<template>
  <div class="pc">
    <aside class="pc__side jb-card">
      <div class="card">
        <span class="avatar">{{ (auth.me?.nickname ?? '?').slice(0, 1) }}</span>
        <b>{{ auth.me?.nickname }}</b>
        <small class="jb-muted">{{ auth.identities.find((i) => i.current)?.displayName }}</small>
        <el-tag>{{ summary?.levelName ?? '—' }}</el-tag>
      </div>
      <a v-for="[k, label] in TABS" :key="k" class="tab" :class="{ on: tab === k }" @click="tab = k">{{ label }}</a>
    </aside>
    <section class="pc__main">
      <template v-if="tab === 'overview'">
        <div class="tiles">
          <div class="tile t1"><span>金榜币</span><b data-test="pc-balance">{{ summary?.balance }}</b><small>冻结中 {{ summary?.frozen }}</small>
            <el-button size="small" @click="tab = 'orders'">去充值</el-button></div>
          <div class="tile t2"><span>成长等级</span><b>{{ summary?.levelName }}</b><el-progress :percentage="progress" :show-text="false" :stroke-width="6" />
            <small>经验 {{ summary?.exp }}，距下一级 {{ summary?.expToNext }}</small></div>
          <div class="tile t3"><span>每日签到</span><b>{{ summary?.signedToday ? '已签到' : '未签到' }}</b><small>连续 {{ summary?.continuousDays }} 天</small>
            <el-button size="small" :disabled="summary?.signedToday" @click="sign">签到</el-button></div>
        </div>
        <div class="quick">
          <button v-for="[k, label] in TABS.slice(1)" :key="k" type="button" @click="tab = k">{{ label }}</button>
        </div>
        <el-card header="等级特权">
          <el-steps :active="(summary?.levelNo ?? 0)" align-center>
            <el-step v-for="l in levels.slice(1)" :key="l.no" :title="l.name" :description="`${l.minExp}+`" />
          </el-steps>
          <p class="jb-muted center">当前特权：{{ level?.privilege }}</p>
        </el-card>
      </template>

      <el-card v-else-if="tab === 'coins'" header="金币明细">
        <el-radio-group v-model="direction" style="margin-bottom: 12px">
          <el-radio-button value="">全部</el-radio-button><el-radio-button value="IN">收入</el-radio-button><el-radio-button value="OUT">支出</el-radio-button>
          <el-radio-button value="FREEZE">冻结</el-radio-button><el-radio-button value="UNFREEZE">解冻</el-radio-button>
        </el-radio-group>
        <el-table :data="ledger" empty-text="暂无流水" data-test="coin-ledger">
          <el-table-column label="时间" width="160"><template #default="{ row }">{{ fmtTime(row.createdAt) }}</template></el-table-column>
          <el-table-column prop="bizLabel" label="业务" width="120" />
          <el-table-column label="类型" width="80"><template #default="{ row }">{{ DIR[row.direction] }}</template></el-table-column>
          <el-table-column label="金额" width="90"><template #default="{ row }"><b :class="row.direction">{{ row.direction === 'IN' || row.direction === 'UNFREEZE' ? '+' : '-' }}{{ row.amount }}</b></template></el-table-column>
          <el-table-column prop="balanceAfter" label="余额" width="90" />
          <el-table-column prop="remark" label="说明" />
          <el-table-column prop="bizId" label="关联单号" width="200" show-overflow-tooltip />
        </el-table>
      </el-card>

      <template v-else-if="tab === 'orders'">
        <el-card header="充值金榜币">
          <div class="pkgs"><button v-for="p in packages" :key="p.code" type="button" class="pkg" @click="recharge(p)"><b>{{ p.coins }} 币</b><span>{{ p.label }}</span><em>¥{{ ((p.amountFen ?? 0) / 100).toFixed(2) }}</em></button></div>
        </el-card>
        <el-card header="我的订单">
          <el-table :data="orders" empty-text="暂无订单">
            <el-table-column prop="orderNo" label="订单号" width="210" />
            <el-table-column prop="subject" label="购买内容" />
            <el-table-column label="金额" width="90"><template #default="{ row }">¥{{ (row.amountFen / 100).toFixed(2) }}</template></el-table-column>
            <el-table-column label="状态" width="90"><template #default="{ row }">{{ ORDER[row.orderStatus] }}</template></el-table-column>
            <el-table-column label="创建时间" width="160"><template #default="{ row }">{{ fmtTime(row.createdAt) }}</template></el-table-column>
            <el-table-column width="140"><template #default="{ row }"><template v-if="row.orderStatus === 'PENDING'"><el-button link type="primary" @click="orderOp(row, 'repay')">重新支付</el-button><el-button link @click="orderOp(row, 'cancel')">取消</el-button></template></template></el-table-column>
          </el-table>
        </el-card>
      </template>

      <el-card v-else-if="tab === 'growth'" header="经验流水">
        <el-table :data="expLedger" empty-text="暂无记录">
          <el-table-column label="时间" width="160"><template #default="{ row }">{{ fmtTime(row.createdAt) }}</template></el-table-column>
          <el-table-column prop="remark" label="行为" />
          <el-table-column label="经验" width="90"><template #default="{ row }">+{{ row.amount }}</template></el-table-column>
          <el-table-column prop="expAfter" label="累计" width="90" />
        </el-table>
      </el-card>

      <template v-else-if="tab === 'feedback'">
        <el-card header="提交反馈">
          <el-form label-width="80px">
            <el-form-item label="类型"><el-radio-group v-model="fb.feedbackType"><el-radio-button value="功能建议">功能建议</el-radio-button><el-radio-button value="问题反馈">问题反馈</el-radio-button><el-radio-button value="内容纠错">内容纠错</el-radio-button></el-radio-group></el-form-item>
            <el-form-item label="联系方式"><el-input v-model="fb.contact" placeholder="手机或邮箱（必填）" /></el-form-item>
            <el-form-item label="内容"><el-input v-model="fb.content" type="textarea" :rows="4" /></el-form-item>
            <el-form-item><el-button type="primary" @click="submitFeedback">提交</el-button></el-form-item>
          </el-form>
        </el-card>
        <el-card header="我的反馈记录">
          <el-table :data="feedback" empty-text="暂无反馈">
            <el-table-column prop="content" label="内容" />
            <el-table-column label="状态" width="90"><template #default="{ row }">{{ { OPEN: '待处理', REPLIED: '已回复', CLOSED: '已关闭' }[row.fbStatus as string] }}</template></el-table-column>
            <el-table-column prop="reply" label="回复" />
          </el-table>
        </el-card>
      </template>

      <el-card v-else-if="tab === 'reports'" header="我的举报">
        <el-table :data="reports" empty-text="暂无举报">
          <el-table-column prop="reason" label="原因" />
          <el-table-column label="结果" width="100"><template #default="{ row }">{{ { PENDING: '处理中', UPHELD: '举报成立', REJECTED: '不成立' }[row.reportStatus as string] }}</template></el-table-column>
          <el-table-column prop="result" label="说明" />
          <el-table-column label="时间" width="160"><template #default="{ row }">{{ fmtTime(row.createdAt) }}</template></el-table-column>
        </el-table>
      </el-card>

      <template v-else-if="tab === 'join'">
        <el-card header="申请加入学校">
          <p class="jb-muted">向学校管理员索取学校邀请码；审核通过后你会获得「学校教师」身份，个人备课、资源和金榜币全部保留。</p>
          <el-form label-width="90px" style="max-width: 480px">
            <el-form-item label="学校邀请码"><el-input v-model="join.inviteCode" data-test="join-code" /></el-form-item>
            <el-form-item label="真实姓名"><el-input v-model="join.realName" /></el-form-item>
            <el-form-item label="任教学科"><el-input v-model="join.subject" /></el-form-item>
            <el-form-item><el-button type="primary" @click="applyJoin">提交申请</el-button></el-form-item>
          </el-form>
        </el-card>
        <el-card header="申请记录">
          <el-table :data="joins" empty-text="暂无申请">
            <el-table-column prop="realName" label="姓名" width="120" />
            <el-table-column label="状态" width="100"><template #default="{ row }">{{ { PENDING: '审核中', APPROVED: '已通过', REJECTED: '已驳回' }[row.reqStatus as string] }}</template></el-table-column>
            <el-table-column prop="rejectReason" label="驳回原因" />
            <el-table-column label="时间" width="160"><template #default="{ row }">{{ fmtTime(row.createdAt) }}</template></el-table-column>
          </el-table>
        </el-card>
      </template>

      <template v-else-if="tab === 'children'">
        <el-card header="绑定孩子">
          <p class="jb-muted">学号、姓名和班级邀请码（向班主任索取）三项都正确才能绑定；可以绑定多个孩子。</p>
          <el-form label-width="100px" style="max-width: 480px">
            <el-form-item label="学校编号"><el-input v-model="bind.schoolId" placeholder="班主任提供" /></el-form-item>
            <el-form-item label="学号"><el-input v-model="bind.studentNo" /></el-form-item>
            <el-form-item label="学生姓名"><el-input v-model="bind.studentName" /></el-form-item>
            <el-form-item label="班级邀请码"><el-input v-model="bind.inviteCode" /></el-form-item>
            <el-form-item label="关系"><el-radio-group v-model="bind.relation"><el-radio-button value="父亲">父亲</el-radio-button><el-radio-button value="母亲">母亲</el-radio-button><el-radio-button value="其他">其他</el-radio-button></el-radio-group></el-form-item>
            <el-form-item><el-button type="primary" @click="bindChild">绑定</el-button></el-form-item>
          </el-form>
        </el-card>
        <el-card header="已绑定">
          <el-table :data="children" empty-text="暂无绑定">
            <el-table-column prop="studentId" label="学生" />
            <el-table-column prop="relation" label="关系" width="90" />
            <el-table-column label="绑定时间" width="160"><template #default="{ row }">{{ fmtTime(row.createdAt) }}</template></el-table-column>
            <el-table-column width="100"><template #default="{ row }"><el-button link type="danger" @click="unbind(row)">解除绑定</el-button></template></el-table-column>
          </el-table>
        </el-card>
      </template>

      <el-card v-else-if="tab === 'security'" header="账号安全">
        <div class="jb-stack">
          <div class="jb-between"><span>登录密码</span><el-button @click="router.push('/change-password')">修改密码</el-button></div>
          <div class="jb-between"><span>手机号</span><span class="jb-muted">{{ auth.me?.phone }}</span></div>
          <div class="jb-between"><span>注销账号（个人信息保护法）</span><el-button type="danger" plain @click="cancelAccount">申请注销</el-button></div>
        </div>
      </el-card>
    </section>
  </div>
</template>

<style scoped>
.pc { display: flex; gap: 16px; }
.pc__side { width: 220px; flex-shrink: 0; align-self: flex-start; display: flex; flex-direction: column; gap: 2px; }
.card { display: flex; flex-direction: column; align-items: center; gap: 6px; padding: 8px 0 16px; border-bottom: 1px solid var(--jb-border); margin-bottom: 8px; }
.avatar { width: 56px; height: 56px; border-radius: 50%; display: grid; place-items: center; font-size: var(--text-xl); font-weight: 600; color: var(--color-cotton); background: var(--color-ink); }
.tab { min-height: 38px; display: flex; align-items: center; padding: 0 12px; border-radius: var(--radius-stock); cursor: pointer; color: var(--color-ink-2); }
.tab:hover { background: var(--color-paper-2); }
.tab.on { background: var(--color-cotton); color: var(--color-ink); font-weight: 600; box-shadow: var(--edge-stock), var(--cast-stock); }
.pc__main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 16px; }
.tiles { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; }
.tile { border-radius: var(--radius-card); padding: 16px 18px; background: var(--color-cotton); box-shadow: var(--edge-stock), var(--cast-stock); display: flex; flex-direction: column; gap: 6px; align-items: flex-start; }
.tile > span { color: var(--color-muted); font-size: var(--text-sm); }
.tile b { font-size: var(--text-2xl); font-weight: 600; font-family: var(--font-outlier); font-variant-numeric: tabular-nums; }
.tile small { color: var(--color-muted); }
.tile .el-progress { width: 100%; }
.quick { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }
.quick button { all: unset; box-sizing: border-box; min-height: 44px; padding: 0 14px; display: flex; align-items: center; border-radius: var(--radius-stock); cursor: pointer; background: var(--color-cotton); box-shadow: var(--edge-stock), var(--cast-stock); transition: transform var(--dur-short) var(--ease-out); }
.quick button:focus-visible { outline: 2px solid var(--color-focus); outline-offset: 2px; }
.quick button:hover { transform: translateY(-1px); }
.center { text-align: center; }
.IN, .UNFREEZE { color: var(--color-paid); }
.OUT, .FREEZE { color: var(--color-ink-2); }
.pkgs { display: flex; gap: 14px; }
.pkg { all: unset; box-sizing: border-box; min-width: 140px; padding: 16px 20px; border-radius: var(--radius-stock); cursor: pointer; display: flex; flex-direction: column; gap: 4px; align-items: center; background: var(--color-cotton); box-shadow: var(--edge-stock), var(--cast-stock); transition: transform var(--dur-short) var(--ease-out); }
.pkg:focus-visible { outline: 2px solid var(--color-focus); outline-offset: 2px; }
.pkg:hover { transform: translateY(-1px); }
.pkg b { font-size: var(--text-xl); font-weight: 600; color: var(--color-ink); }
.pkg em { font-style: normal; font-family: var(--font-outlier); color: var(--color-ink-2); }
</style>
