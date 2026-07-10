<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  variant?: 'default' | 'success' | 'warning' | 'danger'
  size?: 'sm' | 'md'
}>(), {
  variant: 'default',
  size: 'sm',
})

const cls = computed(() => ['ubadge', `ubadge--${props.variant}`, `ubadge--${props.size}`])
</script>

<template>
  <span :class="cls"><slot /></span>
</template>

<style scoped>
.ubadge {
  display: inline-flex;
  align-items: center;
  font-weight: 500;
  border-radius: var(--r-full);
  transition: all var(--dur) var(--ease);
}
.ubadge--sm { padding: 2px 8px; font-size: 10px; }
.ubadge--md { padding: 3px 10px; font-size: var(--text-sm); }

.ubadge--default { background: var(--bg-inset); color: var(--text-secondary); }
.ubadge--success { background: var(--success-soft); color: var(--success); }
.ubadge--warning { background: var(--warning-soft); color: var(--warning); }
.ubadge--danger  { background: var(--danger-soft);  color: var(--danger); }
</style>
