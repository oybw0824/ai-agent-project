<script setup lang="ts">
import { computed } from 'vue'

export interface StepItem {
  label: string
  status?: 'pending' | 'running' | 'completed' | 'failed'
  elapsed?: string
}
const props = defineProps<{ steps: StepItem[] }>()

const currentIndex = computed(() => props.steps.findIndex(s => s.status === 'running'))

const statusColors: Record<string, string> = {
  completed: 'var(--success)',
  running: 'var(--brand)',
  failed: 'var(--danger)',
  pending: 'var(--border-strong)',
}
</script>

<template>
  <div class="usteeps">
    <div
      v-for="(step, i) in steps"
      :key="i"
      class="step"
      :class="[`step--${step.status || 'pending'}`]"
    >
      <div class="step-node">
        <span v-if="step.status === 'completed'" class="step-check">✓</span>
        <span v-else-if="step.status === 'failed'" class="step-cross">✕</span>
        <span v-else-if="step.status === 'running'" class="step-spinner" />
        <span v-else class="step-num">{{ i + 1 }}</span>
      </div>
      <div class="step-info">
        <span class="step-label">{{ step.label }}</span>
        <span v-if="step.elapsed" class="step-time">{{ step.elapsed }}</span>
      </div>
      <div v-if="i < steps.length - 1" class="step-line" :style="{
        background: (i < currentIndex || (i === currentIndex && step.status === 'completed'))
          ? `linear-gradient(to right, ${statusColors.completed}, ${statusColors[steps[i+1]?.status || 'pending']})`
          : undefined
      }" />
    </div>
  </div>
</template>

<style scoped>
.usteeps {
  display: flex;
  align-items: flex-start;
  position: relative;
  padding: 8px 0;
}
.step {
  display: flex;
  flex-direction: column;
  align-items: center;
  flex: 1;
  position: relative;
  text-align: center;
  min-width: 0;
}
.step-node {
  width: 32px; height: 32px;
  border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  font-size: var(--text-xs);
  font-weight: 600;
  z-index: 1;
  transition: all 0.3s var(--ease);
}
.step--pending .step-node {
  background: var(--bg-inset);
  border: 2px solid var(--border-strong);
  color: var(--text-tertiary);
}
.step--completed .step-node {
  background: var(--success-soft);
  border: 2px solid var(--success);
  color: var(--success);
}
.step--running .step-node {
  background: var(--brand-soft);
  border: 2px solid var(--brand);
  animation: dotPulse 1.2s ease-in-out infinite;
}
.step--failed .step-node {
  background: var(--danger-soft);
  border: 2px solid var(--danger);
  color: var(--danger);
}
.step-check, .step-cross { font-size: 12px; }
.step-spinner {
  width: 14px; height: 14px;
  border: 2px solid var(--brand);
  border-top-color: transparent;
  border-radius: 50%;
  animation: spin 0.5s linear infinite;
}
.step-info {
  display: flex; flex-direction: column; gap: 2px;
  margin-top: 6px; font-size: var(--text-xs);
  color: var(--text-secondary);
}
.step--completed .step-label { color: var(--text-primary); font-weight: 500; }
.step--running .step-label { color: var(--brand); font-weight: 600; }
.step-time { font-size: 10px; color: var(--text-tertiary); font-variant-numeric: tabular-nums; }
.step-line {
  position: absolute;
  top: 16px;
  left: calc(50% + 18px);
  right: calc(-50% + 18px);
  height: 2px;
  background: var(--border-strong);
  border-radius: 2px;
  z-index: 0;
  transition: background 0.5s var(--ease);
}
@keyframes dotPulse {
  0%, 100% { box-shadow: 0 0 0 0 var(--brand-glow); }
  50% { box-shadow: 0 0 0 8px transparent; }
}
@keyframes spin { to { transform: rotate(360deg); } }
</style>
