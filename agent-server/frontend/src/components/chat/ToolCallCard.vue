<script setup lang="ts">
import { ref } from 'vue'

defineProps<{
  toolName: string
  input: string
  output?: string
  status: 'running' | 'done' | 'error'
}>()

const expanded = ref(false)

function toggle() { expanded.value = !expanded.value }

const statusConfig: Record<string, { icon: string; label: string; barColor: string }> = {
  running: { icon: '⏳', label: '执行中', barColor: 'var(--info)' },
  done: { icon: '✅', label: '完成', barColor: 'var(--success)' },
  error: { icon: '❌', label: '失败', barColor: 'var(--danger)' },
}
</script>

<template>
  <div
    class="tool-card"
    :class="`tool-card--${status}`"
    :style="{ '--bar-color': statusConfig[status]?.barColor }"
    @click="toggle"
  >
    <div class="tool-hd">
      <span class="tool-icon">{{ statusConfig[status]?.icon }}</span>
      <strong class="tool-name">{{ toolName }}</strong>
      <span class="tool-status">{{ statusConfig[status]?.label }}</span>
      <span class="tool-chev">{{ expanded ? '▾' : '▸' }}</span>
    </div>
    <Transition name="collapse">
      <div v-if="expanded" class="tool-bd">
        <div class="tool-section">
          <div class="tool-label">Input</div>
          <pre class="tool-pre">{{ input }}</pre>
        </div>
        <div v-if="output" class="tool-section">
          <div class="tool-label">Output</div>
          <pre class="tool-pre">{{ output }}</pre>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.tool-card {
  border: 1px solid var(--border);
  border-left: 2px solid var(--bar-color, var(--info));
  border-radius: var(--r-md);
  overflow: hidden;
  font-size: var(--text-xs);
  cursor: pointer;
  transition: border-color var(--dur-fast), box-shadow var(--dur-fast);
  background: var(--bg-surface);
}
.tool-card:hover {
  border-color: var(--border-strong);
  box-shadow: var(--shadow-xs);
}

.tool-hd {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  background: var(--bg-subtle);
  user-select: none;
}
.tool-icon { font-size: 12px; flex-shrink: 0 }
.tool-name { font-size: 11px; font-weight: 600; color: var(--text-primary) }
.tool-status {
  margin-left: auto;
  font-size: 10px;
  color: var(--text-tertiary);
}
.tool-chev {
  font-size: 10px;
  color: var(--text-tertiary);
  flex-shrink: 0;
}

.tool-bd {
  padding: 10px;
  border-top: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.tool-section { display: flex; flex-direction: column; gap: 3px }
.tool-label { font-size: 10px; font-weight: 600; color: var(--text-tertiary); text-transform: uppercase; letter-spacing: .04em }
.tool-pre {
  background: var(--bg-inset);
  padding: 6px 10px;
  border-radius: var(--r-sm);
  overflow-x: auto;
  font-size: 10px;
  line-height: 1.5;
  font-family: var(--font-mono);
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 140px;
  overflow-y: auto;
  margin: 0;
}

/* collapse transition */
.collapse-enter-active,
.collapse-leave-active {
  transition: all .2s var(--ease);
  overflow: hidden;
}
.collapse-enter-from,
.collapse-leave-to {
  opacity: 0;
  max-height: 0;
  padding-top: 0;
  padding-bottom: 0;
}
.collapse-enter-to,
.collapse-leave-from {
  opacity: 1;
  max-height: 500px;
}
</style>
