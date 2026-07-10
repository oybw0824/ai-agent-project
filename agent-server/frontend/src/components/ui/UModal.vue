<script setup lang="ts">
import { ref, watch, type VNode } from 'vue'

const props = withDefaults(defineProps<{
  modelValue?: boolean
  title?: string
  width?: string
}>(), {
  modelValue: false,
  width: '500px',
})

const emit = defineEmits<{ 'update:modelValue': [v: boolean] }>()
const visible = ref(props.modelValue)

watch(() => props.modelValue, (v) => { visible.value = v })
watch(visible, (v) => emit('update:modelValue', v))

function close() { visible.value = false }
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="visible" class="overlay" @click.self="close">
        <div class="modal-card" :style="{ maxWidth: width }" role="dialog" aria-modal="true">
          <div v-if="title || $slots.header" class="modal-header">
            <slot name="header"><h3>{{ title }}</h3></slot>
            <button class="close-btn" @click="close" aria-label="关闭">&times;</button>
          </div>
          <div class="modal-body"><slot /></div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.overlay {
  position: fixed; inset: 0; z-index: 9999;
  background: rgba(0, 0, 0, 0.5);
  display: flex; align-items: center; justify-content: center;
}
.modal-card {
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--r-xl);
  box-shadow: var(--shadow-lg);
  width: 90vw;
  max-height: 85vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.modal-header {
  padding: 14px 18px;
  border-bottom: 1px solid var(--border);
  display: flex; align-items: center; justify-content: space-between;
  font-size: var(--text-lg); font-weight: 600;
}
.close-btn {
  width: 32px; height: 32px;
  border: none; border-radius: var(--r-md);
  background: transparent; color: var(--text-tertiary);
  font-size: 20px; cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  transition: all var(--dur) var(--ease);
}
.close-btn:hover { background: var(--bg-subtle); color: var(--text-primary); }
.modal-body { flex: 1; overflow-y: auto; padding: 20px; }
.modal-enter-active, .modal-leave-active { transition: opacity var(--dur) var(--ease); }
.modal-enter-from, .modal-leave-to { opacity: 0; }
.modal-enter-active .modal-card { transition: transform var(--dur) var(--ease); }
.modal-enter-from .modal-card { transform: scale(0.95) translateY(8px); }
</style>
