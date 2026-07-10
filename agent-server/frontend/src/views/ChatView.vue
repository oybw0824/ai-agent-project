<script setup lang="ts">
import { ref, nextTick, computed, onMounted } from 'vue'
import { useChatStore, type ChatMessage } from '@/stores/chat'
import { useSSE } from '@/composables/useSSE'
import type { ChatDoneData, ToolCallData, ToolResultData, SkillLoadData } from '@/types/api'
import AppHeader from '@/components/layout/AppHeader.vue'
import UButton from '@/components/ui/UButton.vue'
import MessageBubble from '@/components/chat/MessageBubble.vue'
import ChatInput from '@/components/chat/ChatInput.vue'

const store = useChatStore()
const { start, cancel } = useSSE()
const input = ref('')
const abortCtrl = ref<AbortController | null>(null)
const messagesRef = ref<HTMLElement | null>(null)

const quickActions = [
  { icon: '🌤️', text: '北京今天天气怎么样？' },
  { icon: '🔢', text: '帮我计算 25+36×2 等于多少？' },
  { icon: '📊', text: '分析一下销售下滑的原因' },
  { icon: '⚡', text: '帮我生成一个数据分析的 Skill' },
]

function scrollDown() {
  nextTick(() => { if (messagesRef.value) messagesRef.value.scrollTop = messagesRef.value.scrollHeight })
}

async function send() {
  const q = input.value.trim()
  if (!q || store.isStreaming) return
  input.value = ''

  const userMsg: ChatMessage = { id: crypto.randomUUID(), role: 'user', content: q, timestamp: Date.now() }
  store.addMessage(userMsg)
  const assistId = crypto.randomUUID()
  store.addMessage({ id: assistId, role: 'assistant', content: '', thinking: '正在分析...', timestamp: Date.now() })
  store.isStreaming = true
  scrollDown()

  const ctrl = new AbortController()
  abortCtrl.value = ctrl

  const toolMap = new Map<string, ChatMessage['toolCalls'] extends infer T ? T[number] : never>()

  await start({
    url: '/api/v1/chat/stream',
    body: { question: q },
    signal: ctrl.signal,
    onEvent(type, data) {
      switch (type) {
        case 'thinking':
          store.setThinking(data.message as string || '分析中...')
          break
        case 'text':
          store.setThinking('')
          store.updateLast(data.message as string || '')
          scrollDown()
          break
        case 'skill_load': {
          const sl = data as unknown as SkillLoadData
          store.setThinking('')
          if (sl.data?.skillName) store.addSkill(sl.data.skillName)
          break
        }
        case 'tool_call': {
          const tc = data as unknown as ToolCallData
          store.setThinking('')
          if (tc.data?.toolName) {
            toolMap.set(tc.data.toolName, { toolName: tc.data.toolName, input: tc.data.input || '', status: 'running' })
            store.updateLastTools(Array.from(toolMap.values()))
          }
          break
        }
        case 'tool_result': {
          const tr = data as unknown as ToolResultData
          if (tr.data?.toolName) {
            const t = toolMap.get(tr.data.toolName)
            if (t) { t.output = tr.data.output || ''; t.status = 'done' }
            store.updateLastTools(Array.from(toolMap.values()))
          }
          break
        }
        case 'message':
          store.setThinking('')
          store.updateLast(data.message as string || '')
          scrollDown()
          break
        case 'done': {
          const done = data as unknown as { data?: ChatDoneData }
          let meta = ''
          if (done.data?.processingTimeMs) meta += `⏱ ${done.data.processingTimeMs}ms `
          if (done.data?.toolCallCount) meta += `🔧 ${done.data.toolCallCount}次 `
          store.updateLastMeta(meta)
          break
        }
      }
    },
    onError(err) {
      store.updateLast(`❌ 请求失败: ${err.message}`)
    },
  })

  store.isStreaming = false
  abortCtrl.value = null
  store.persist()
  scrollDown()
}

function stopGeneration() {
  abortCtrl.value?.abort()
  store.isStreaming = false
  abortCtrl.value = null
}

function clearHistory() {
  if (confirm('确定清空对话记录？')) store.clear()
}

onMounted(scrollDown)
</script>

<template>
  <div class="chat-page">
    <AppHeader title="💬 对话">
      <template #actions>
        <UButton variant="ghost" size="sm" @click="clearHistory">🗑 清空</UButton>
      </template>
    </AppHeader>

    <div ref="messagesRef" class="chat-messages">
      <div v-if="store.messages.length===0" class="welcome">
        <h1>👋 AI Agent</h1>
        <p>技能驱动的智能助手 — 按需加载技能，调用 MCP 工具完成任务</p>
        <div class="quick-grid">
          <button v-for="qa in quickActions" :key="qa.text" class="quick-card" @click="input=qa.text; send()">
            <span class="qc-icon">{{ qa.icon }}</span><span>{{ qa.text }}</span>
          </button>
        </div>
      </div>

      <div v-for="m in store.messages" :key="m.id">
        <MessageBubble v-bind="m" :is-streaming="store.isStreaming && m.id===store.messages[store.messages.length-1]?.id" />
      </div>
    </div>

    <ChatInput v-model="input" :loading="store.isStreaming" @send="send" @stop="stopGeneration" placeholder="输入您的问题，Enter 发送，Shift+Enter 换行..." />
  </div>
</template>

<style scoped>
.chat-page{display:flex;flex-direction:column;height:100%}
.chat-messages{flex:1;overflow-y:auto;padding:20px 24px;display:flex;flex-direction:column;gap:14px;scroll-behavior:smooth}
.welcome{text-align:center;padding:48px 20px;margin:auto 0}
.welcome h1{font-size:var(--text-2xl);font-weight:600;margin-bottom:6px;letter-spacing:-.02em}
.welcome p{font-size:var(--text-base);color:var(--text-secondary);margin-bottom:24px}
.quick-grid{display:grid;grid-template-columns:repeat(2,1fr);gap:10px;max-width:480px;margin:0 auto}
.quick-card{display:flex;align-items:center;gap:8px;padding:12px 16px;border:1px solid var(--border);border-radius:var(--r-lg);background:var(--bg-surface);cursor:pointer;font-size:var(--text-sm);color:var(--text-primary);transition:all var(--dur) var(--ease);text-align:left}
.quick-card:hover{border-color:var(--border-strong);transform:translateY(-1px);box-shadow:var(--shadow-sm)}
.qc-icon{font-size:20px}
</style>
