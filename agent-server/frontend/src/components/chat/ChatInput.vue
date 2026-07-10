<script setup lang="ts">
import { ref } from 'vue'
defineProps<{ modelValue?: string; placeholder?: string; disabled?: boolean; loading?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [v: string]; send: []; stop: [] }>()
const input = ref<HTMLTextAreaElement | null>(null)
function autoResize() { const el = input.value; if (el) { el.style.height = 'auto'; el.style.height = Math.min(el.scrollHeight, 120) + 'px' } }
function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); emit('send') }
}
function focus() { input.value?.focus() }
defineExpose({ focus })
</script>

<template>
  <div class="chat-input-wrap">
    <div class="chat-input-box">
      <textarea ref="input" class="chat-input" :placeholder="placeholder||'输入您的问题...'" rows="1" :disabled :value="modelValue"
        @input="emit('update:modelValue', ($event.target as HTMLTextAreaElement).value); autoResize()"
        @keydown="onKeydown"
      />
      <button v-if="loading" class="send-btn stop" @click="emit('stop')" title="停止">⏹</button>
      <button v-else class="send-btn" @click="emit('send')" :disabled="!modelValue?.trim() || disabled" title="发送 (Enter)">➤</button>
    </div>
    <div class="hint">Enter 发送 · Shift+Enter 换行</div>
  </div>
</template>
<style scoped>
.chat-input-wrap{padding:12px 24px 16px;background:var(--bg-canvas);flex-shrink:0}
.chat-input-box{display:flex;align-items:flex-end;gap:8px;background:var(--bg-surface);border:1px solid var(--border-strong);border-radius:var(--r-2xl);padding:6px 8px 6px 16px;box-shadow:var(--shadow-xs);transition:border-color var(--dur) var(--ease),box-shadow var(--dur) var(--ease)}
.chat-input-box:focus-within{border-color:var(--brand);box-shadow:0 0 0 3px var(--brand-glow)}
.chat-input{flex:1;border:none;outline:none;resize:none;font-size:var(--text-base);line-height:1.5;max-height:120px;min-height:28px;font-family:var(--font-sans);background:transparent;color:var(--text-primary);padding:4px 0}
.chat-input::placeholder{color:var(--text-tertiary)}
.send-btn{width:38px;height:38px;border:none;border-radius:var(--r-lg);background:var(--brand);color:var(--text-inverse);cursor:pointer;font-size:15px;display:flex;align-items:center;justify-content:center;flex-shrink:0;transition:all var(--dur) var(--ease)}
.send-btn:hover:not(:disabled){transform:scale(1.05)}
.send-btn:disabled{opacity:.35;cursor:not-allowed}
.send-btn.stop{background:var(--danger)}
.hint{text-align:center;font-size:10px;color:var(--text-tertiary);margin-top:6px}
</style>
