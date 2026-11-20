<script setup lang="ts">
import { ForumBoard, PageHeader } from '@jb/ui'
</script>

<template>
  <div>
    <PageHeader title="金榜杏坛" icon="坛" color="var(--jb-c-teaching)" desc="教师交流社区：分享教学心得与资源，评论、点赞，发布前自动进行内容安全检查" />
    <ForumBoard board="XINGTAN" :max-length="200" />
  </div>
</template>
