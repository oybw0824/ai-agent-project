<script setup lang="ts">
defineProps<{ icon?: string; src?: string; size?: number }>()
</script>
<template>
  <div class="uavatar" :style="{ width: (size || 34)+'px', height: (size || 34)+'px' }">
    <img v-if="src" :src :alt="icon||'avatar'" class="uavatar-img" />
    <span v-else class="uavatar-icon">{{ icon || '?' }}</span>
  </div>
</template>
<style scoped>
.uavatar{flex-shrink:0;border-radius:50%;display:flex;align-items:center;justify-content:center;font-size:15px;box-shadow:var(--shadow-xs);overflow:hidden}
.uavatar-img{width:100%;height:100%;object-fit:cover}
.uavatar-icon{color:#fff}
</style>
