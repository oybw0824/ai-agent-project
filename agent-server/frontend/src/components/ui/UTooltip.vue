<script setup lang="ts">
import { ref } from 'vue'

const props = withDefaults(defineProps<{ text: string; position?: 'top'|'bottom'|'left'|'right'; delay?: number }>(), { position: 'top', delay: 300 })
const show = ref(false); let timer: ReturnType<typeof setTimeout> | null = null
function onEnter() { timer = setTimeout(() => { show.value = true }, props.delay) }
function onLeave() { if (timer) { clearTimeout(timer); timer = null }; show.value = false }
</script>
<template>
  <div class="utip" @mouseenter="onEnter" @mouseleave="onLeave">
    <slot /><Transition name="tip"><div v-if="show" :class="['utip-pop', `utip--${position}`]" role="tooltip">{{ text }}</div></Transition>
  </div>
</template>
<style scoped>
.utip{position:relative;display:inline-flex}
.utip-pop{position:absolute;padding:4px 10px;border-radius:var(--r-sm);background:var(--text-primary);color:var(--text-inverse);font-size:var(--text-xs);white-space:nowrap;pointer-events:none;z-index:1000;box-shadow:var(--shadow-md)}
.utip--top{bottom:calc(100% + 6px);left:50%;transform:translateX(-50%)}
.utip--bottom{top:calc(100% + 6px);left:50%;transform:translateX(-50%)}
.tip-enter-active{transition:opacity .15s var(--ease),transform .15s var(--ease)}
.tip-leave-active{transition:opacity .1s var(--ease)}
.tip-enter-from,.tip-leave-to{opacity:0}
.utip--top.tip-enter-from{transform:translateX(-50%) translateY(4px)}
.utip--bottom.tip-enter-from{transform:translateX(-50%) translateY(-4px)}
</style>
