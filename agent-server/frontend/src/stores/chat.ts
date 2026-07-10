import { defineStore } from 'pinia'
import { ref } from 'vue'
import { storage } from '@/utils/storage'

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  thinking?: string
  toolCalls?: { toolName: string; input: string; output?: string; status: 'running'|'done'|'error' }[]
  skills?: string[]
  meta?: string
  timestamp: number
}

export const useChatStore = defineStore('chat', () => {
  const messages = ref<ChatMessage[]>(storage.get<ChatMessage[]>('chat_messages', []))
  const isStreaming = ref(false)
  function addMessage(msg: ChatMessage) { messages.value.push(msg); persist() }
  function updateLast(content: string) { if (messages.value.length > 0) messages.value[messages.value.length - 1].content = content }
  function updateLastTools(tc: ChatMessage['toolCalls']) { if (messages.value.length) messages.value[messages.value.length - 1].toolCalls = tc }
  function updateLastMeta(m: string) { if (messages.value.length) messages.value[messages.value.length - 1].meta = m }
  function setThinking(v: string) { if (messages.value.length) messages.value[messages.value.length - 1].thinking = v }
  function addSkill(s: string) { if (messages.value.length) { const last = messages.value[messages.value.length - 1]; if (!last.skills) last.skills = []; last.skills.push(s) } }
  function clear() { messages.value = []; storage.remove('chat_messages') }
  function persist() { storage.set('chat_messages', messages.value.slice(-50).map(m => ({ id: m.id, role: m.role, content: m.content, thinking: m.thinking, toolCalls: m.toolCalls?.map(t => ({ ...t, output: t.output?.substring(0, 500) })), skills: m.skills, meta: m.meta, timestamp: m.timestamp }))) }
  return { messages, isStreaming, addMessage, updateLast, updateLastTools, updateLastMeta, setThinking, addSkill, clear, persist }
})
