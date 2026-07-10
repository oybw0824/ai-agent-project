<script setup lang="ts">
import { computed } from 'vue'
const props = withDefaults(defineProps<{ modelValue?: string; type?: 'text'|'textarea'; placeholder?: string; rows?: number; maxlength?: number; disabled?: boolean }>(), { type: 'text', modelValue: '' })
const emit = defineEmits<{ 'update:modelValue': [v: string]; 'keydown': [e: KeyboardEvent] }>()
const value = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})
function onInput(e: Event) { emit('update:modelValue', (e.target as HTMLTextAreaElement|HTMLInputElement).value) }
</script>
<template>
  <div class="uinput-wrap" :class="{ disabled }">
    <input
      v-if="type==='text'" class="uinput" :value="modelValue" @input="onInput" :placeholder :maxlength :disabled
      @keydown="emit('keydown', $event)"
    />
    <textarea
      v-else class="uinput uinput--area" :value="modelValue" @input="onInput" :placeholder :rows :maxlength :disabled
      @keydown="emit('keydown', $event)"
    />
  </div>
</template>
<style scoped>
.uinput-wrap{position:relative;width:100%}
.uinput-wrap.disabled{opacity:.45}
.uinput{width:100%;padding:8px 12px;border:1px solid var(--border-strong);border-radius:var(--r-md);font-size:var(--text-sm);font-family:var(--font-sans);color:var(--text-primary);background:var(--bg-surface);outline:none;transition:border-color var(--dur) var(--ease),box-shadow var(--dur) var(--ease);line-height:1.5}
.uinput::placeholder{color:var(--text-tertiary)}
.uinput:focus{border-color:var(--brand);box-shadow:0 0 0 2px var(--brand-glow)}
.uinput:disabled{cursor:not-allowed}
.uinput--area{resize:vertical;min-height:80px}
</style>
