<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'

export interface ToastInstance {
  id: number
  type: 'success' | 'error' | 'warning' | 'info'
  message: string
  duration: number
}
let nextId = 0
const toasts = ref<ToastInstance[]>([])

const icons: Record<string, string> = { success: '✅', error: '❌', warning: '⚠️', info: 'ℹ️' }

function show(type: ToastInstance['type'], message: string, duration = 3000) {
  const id = ++nextId
  toasts.value.push({ id, type, message, duration })
  if (duration > 0) setTimeout(() => remove(id), duration)
}
function remove(id: number) {
  toasts.value = toasts.value.filter(t => t.id !== id)
}

defineExpose({ show, remove })

// 快捷方法
function success(msg: string, dur?: number) { show('success', msg, dur) }
function error(msg: string, dur?: number) { show('error', msg, dur) }
function warning(msg: string, dur?: number) { show('warning', msg, dur) }
function info(msg: string, dur?: number) { show('info', msg, dur) }

// 全局单例
let globalInstance: { success: typeof success; error: typeof error; warning: typeof warning; info: typeof info } | null = null
onMounted(() => { globalInstance = { success, error, warning, info } })

// 通过模块导出供外部使用
export function useToast() {
  setTimeout(() => { /* ensure instance mounted */ })
  return {
    show: (type: ToastInstance['type'], msg: string, dur?: number) => toasts.value.push({ id: ++nextId, type, message: msg, duration: dur ?? 3000 }),
    success: (msg: string, dur?: number) => toasts.value.push({ id: ++nextId, type: 'success', message: msg, duration: dur ?? 3000 }),
    error: (msg: string, dur?: number) => toasts.value.push({ id: ++nextId, type: 'error', message: msg, duration: dur ?? 3000 }),
    warning: (msg: string, dur?: number) => toasts.value.push({ id: ++nextId, type: 'warning', message: msg, duration: dur ?? 3000 }),
    info: (msg: string, dur?: number) => toasts.value.push({ id: ++nextId, type: 'info', message: msg, duration: dur ?? 3000 }),
  }
}
</script>

<template>
  <Teleport to="body">
    <div class="toast-container" aria-live="polite">
      <TransitionGroup name="toast">
        <div v-for="t in toasts" :key="t.id" :class="['toast', `toast--${t.type}`]" role="alert">
          <span class="toast-icon">{{ icons[t.type] }}</span>
          <span class="toast-msg">{{ t.message }}</span>
          <button class="toast-close" @click="remove(t.id)" aria-label="关闭">&times;</button>
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<style scoped>
.toast-container {
  position: fixed; top: 20px; right: 20px; z-index: 10000;
  display: flex; flex-direction: column; gap: 8px;
  pointer-events: none; max-width: 380px;
}
.toast {
  pointer-events: auto;
  display: flex; align-items: center; gap: 10px;
  padding: 12px 16px;
  border-radius: var(--r-md);
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  box-shadow: var(--shadow-md);
  font-size: var(--text-sm);
  color: var(--text-primary);
  transition: background-color var(--dur-slow) var(--ease);
}
.toast--success { border-left: 3px solid var(--success); }
.toast--error   { border-left: 3px solid var(--danger); }
.toast--warning { border-left: 3px solid var(--warning); }
.toast--info    { border-left: 3px solid var(--info); }
.toast-icon { font-size: 15px; flex-shrink: 0; }
.toast-msg { flex: 1; line-height: 1.4; }
.toast-close {
  background: none; border: none;
  color: var(--text-tertiary); font-size: 16px;
  cursor: pointer; padding: 2px;
  transition: color var(--dur-fast);
}
.toast-close:hover { color: var(--text-primary); }

.toast-enter-active { transition: all 0.3s var(--ease); }
.toast-leave-active { transition: all 0.3s var(--ease); }
.toast-enter-from { opacity: 0; transform: translateX(40px); }
.toast-leave-to   { opacity: 0; transform: translateX(40px); }
</style>
