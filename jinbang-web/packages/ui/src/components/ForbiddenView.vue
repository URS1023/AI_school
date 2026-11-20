<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuth } from '@jb/auth'

/** 统一的无权限提示页（PRD 2.2 ⚠️P0、R9、AC-16）：说明原因并给出申请路径，而不是空白或无反应。 */
const props = defineProps<{ joinPath?: string }>()
const auth = useAuth()
const router = useRouter()
const isVisitor = computed(() => auth.identity?.type === 'VISITOR_TEACHER')
const guide = computed(
  () =>
    auth.lastForbiddenGuide ??
    (isVisitor.value
      ? '游客教师可在「个人中心 → 加入学校」输入学校邀请码申请，学校审核通过后即可使用考试阅卷、教务等功能。'
      : '请联系本校管理员为你分配相应的角色或任教班级；如果你有多个身份，可以切换到有权限的身份。'),
)
const otherIdentities = computed(() => auth.identities.filter((i) => !i.current))
</script>

<template>
  <div class="jb-403" data-test="forbidden">
    <el-result icon="warning" title="当前身份无权限访问" :sub-title="guide">
      <template #extra>
        <el-button type="primary" @click="router.replace('/')">返回首页</el-button>
        <el-button v-if="isVisitor && props.joinPath" @click="router.push(props.joinPath)">申请加入学校</el-button>
        <el-dropdown v-if="otherIdentities.length" @command="(id: string) => auth.switchIdentity(id).then(() => router.replace('/'))">
          <el-button>切换身份</el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-for="i in otherIdentities" :key="i.identityId" :command="i.identityId">{{ i.displayName }}</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </template>
    </el-result>
  </div>
</template>

<style scoped>
.jb-403 { padding: 60px 0; }
</style>
