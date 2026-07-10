<script setup lang="ts">
import { computed } from 'vue'
import { renderMarkdown } from '@/utils/markdown'
import ToolCallCard from './ToolCallCard.vue'

const props = defineProps<{
  role: 'user' | 'assistant'
  content: string
  thinking?: string
  toolCalls?: { toolName: string; input: string; output?: string; status: 'running'|'done'|'error' }[]
  skills?: string[]
  meta?: string
  isStreaming?: boolean
}>()

const html = computed(() => props.content ? renderMarkdown(props.content) : '')
</script>

<template>
  <div class="msg" :class="[`msg--${role}`, { streaming: isStreaming }]">
    <div class="msg-avatar"><span v-if="role==='user'">👤</span><span v-else>🤖</span></div>
    <div class="msg-body">
      <div v-if="thinking" class="msg-think"><span>💭</span>{{ thinking }}</div>
      <div v-if="skills?.length" class="msg-skills"><span v-for="s in skills" :key="s" class="skill-tag">📋{{ s }}</span></div>
      <div v-if="toolCalls?.length" class="msg-tools">
        <ToolCallCard
          v-for="tc in toolCalls"
          :key="tc.toolName"
          :tool-name="tc.toolName"
          :input="tc.input"
          :output="tc.output"
          :status="tc.status"
        />
      </div>
      <div v-if="role==='user'" class="bubble-user">{{ content }}</div>
      <div v-else-if="content" class="bubble-ai prose" v-html="html" />
      <span v-if="isStreaming && role==='assistant'" class="cursor-blink" />
      <div v-if="meta" class="msg-meta">{{ meta }}</div>
    </div>
  </div>
</template>

<style scoped>
.msg{display:flex;gap:10px;max-width:78%;animation:fadeIn .3s var(--ease)}
.msg--user{align-self:flex-end;flex-direction:row-reverse}
.msg--assistant{align-self:flex-start}
.msg-avatar{width:34px;height:34px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-size:15px;flex-shrink:0;box-shadow:var(--shadow-xs)}
.msg--user .msg-avatar{background:var(--brand);color:var(--text-inverse)}
.msg--assistant .msg-avatar{background:var(--bg-inset);color:var(--text-secondary)}
.msg-body{flex:1;min-width:0}
.msg-think{display:flex;align-items:center;gap:6px;padding:6px 10px;font-size:var(--text-xs);color:var(--text-secondary);background:var(--bg-subtle);border-radius:var(--r-sm);margin-bottom:6px}
.msg-skills{display:flex;flex-wrap:wrap;gap:4px;margin-bottom:6px}
.skill-tag{display:inline-flex;align-items:center;gap:3px;padding:2px 8px;border-radius:var(--r-full);font-size:10px;background:var(--brand-soft);color:var(--brand)}
.msg-tools{display:flex;flex-direction:column;gap:4px;margin-bottom:8px}
.bubble-user{padding:8px 14px;border-radius:var(--r-lg);border-bottom-right-radius:4px;background:var(--bg-subtle);color:var(--text-primary);font-size:var(--text-base);line-height:1.6;white-space:pre-wrap;word-break:break-word}
.bubble-ai{padding:0;color:var(--text-primary)}
.cursor-blink{display:inline-block;width:8px;height:16px;background:var(--brand);animation:blink .8s infinite;vertical-align:text-bottom;margin-left:2px;border-radius:2px}
.msg-meta{font-size:10px;color:var(--text-tertiary);margin-top:4px;display:flex;gap:10px}
@keyframes fadeIn{from{opacity:0;transform:translateY(6px)}to{opacity:1;transform:translateY(0)}}
@keyframes blink{0%,50%{opacity:1}51%,100%{opacity:0}}
</style>
