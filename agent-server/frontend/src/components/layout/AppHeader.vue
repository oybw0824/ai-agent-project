<script setup lang="ts">
defineProps<{ title: string; subtitle?: string }>()
</script>

<template>
  <header class="app-header">
    <div class="header-left">
      <h1 class="header-title">{{ title }}</h1>
      <span v-if="subtitle" class="header-subtitle">{{ subtitle }}</span>
    </div>
    <div class="header-right"><slot name="actions" /></div>
  </header>
</template>

<style scoped>
.app-header {
  height: var(--header-height);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  border-bottom: 1px solid var(--border);
  background: var(--bg-surface);
  flex-shrink: 0;
  transition: background-color var(--dur-slow) var(--ease), border-color var(--dur-slow) var(--ease);
}
.header-left { display: flex; align-items: baseline; gap: 10px; min-width: 0; }
.header-title { font-size: var(--text-lg); font-weight: 600; letter-spacing: -0.01em; white-space: nowrap; }
.header-subtitle { font-size: var(--text-sm); color: var(--text-tertiary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.header-right { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
</style>
