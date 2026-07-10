<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  variant?: 'text' | 'circle'
  width?: string
  height?: string
}>(), {
  variant: 'text',
})

const style = computed(() => ({
  width: props.width || (props.variant === 'circle' ? '32px' : '100%'),
  height: props.height || (props.variant === 'circle' ? '32px' : '14px'),
  borderRadius: props.variant === 'circle' ? '50%' : 'var(--r-sm)',
}))
</script>

<template>
  <div class="uskeleton" :style="style" />
</template>

<style scoped>
.uskeleton {
  background: linear-gradient(90deg, var(--bg-inset) 25%, var(--bg-subtle) 50%, var(--bg-inset) 75%);
  background-size: 200% 100%;
  animation: skeleton 1.5s ease-in-out infinite;
}
@keyframes skeleton {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}
</style>
