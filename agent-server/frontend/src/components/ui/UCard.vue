<script setup lang="ts">
defineProps<{ padded?: boolean }>()
</script>

<template>
  <div class="ucard" :class="{ padded }">
    <slot />
  </div>
</template>

<style scoped>
.ucard {
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-xs);
  transition: background-color var(--dur-slow) var(--ease), border-color var(--dur-slow) var(--ease), box-shadow var(--dur-slow) var(--ease);
}
.ucard.padded { padding: 16px 18px; }
</style>
