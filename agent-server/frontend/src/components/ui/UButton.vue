<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger'
  size?: 'sm' | 'md' | 'lg'
  disabled?: boolean
  loading?: boolean
}>(), {
  variant: 'secondary',
  size: 'md',
  disabled: false,
  loading: false,
})

const emit = defineEmits<{ click: [e: MouseEvent] }>()

const classes = computed(() => [
  'ubtn',
  `ubtn--${props.variant}`,
  `ubtn--${props.size}`,
  { 'ubtn--loading': props.loading },
])
</script>

<template>
  <button :class="classes" :disabled="disabled || loading" @click="emit('click', $event)">
    <span v-if="loading" class="ubtn-spinner" />
    <span class="ubtn-content" :class="{ invisible: loading }">
      <slot />
    </span>
  </button>
</template>

<style scoped>
.ubtn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 1px solid transparent;
  border-radius: var(--r-md);
  font-weight: 500;
  cursor: pointer;
  transition: all var(--dur) var(--ease);
  white-space: nowrap;
  position: relative;
  letter-spacing: -0.01em;
}
.ubtn:disabled { opacity: 0.4; cursor: not-allowed; }
.ubtn--sm { padding: 4px 10px; font-size: var(--text-xs); height: 30px; }
.ubtn--md { padding: 6px 14px; font-size: var(--text-sm); height: 34px; }
.ubtn--lg { padding: 8px 18px; font-size: var(--text-base); height: 40px; }

.ubtn--primary {
  background: var(--brand);
  color: var(--text-inverse);
}
.ubtn--primary:hover:not(:disabled) {
  background: var(--brand-hover);
  transform: translateY(-1px);
  box-shadow: var(--shadow-sm);
}
.ubtn--secondary {
  background: var(--bg-surface);
  border-color: var(--border-strong);
  color: var(--text-secondary);
}
.ubtn--secondary:hover:not(:disabled) {
  background: var(--bg-subtle);
  border-color: var(--border-strong);
  color: var(--text-primary);
}
.ubtn--ghost {
  background: transparent;
  color: var(--text-secondary);
}
.ubtn--ghost:hover:not(:disabled) {
  background: var(--bg-subtle);
  color: var(--text-primary);
}
.ubtn--danger {
  background: var(--danger-soft);
  color: var(--danger);
  border-color: var(--danger-border);
}
.ubtn--danger:hover:not(:disabled) {
  background: var(--danger);
  color: #fff;
}

.ubtn--loading { cursor: wait; }
.ubtn-spinner {
  position: absolute;
  width: 14px; height: 14px;
  border: 2px solid currentColor;
  border-top-color: transparent;
  border-radius: 50%;
  animation: spin 0.45s linear infinite;
}
.ubtn-content.invisible { visibility: hidden; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
