<script setup lang="ts">
import { onMounted } from 'vue'
import { useThemeStore } from '@/stores/theme'
import AppSidebar from '@/components/layout/AppSidebar.vue'

const theme = useThemeStore()
onMounted(() => {
  theme.applyTheme()
})
</script>

<template>
  <div class="app-shell">
    <AppSidebar />
    <main class="app-main">
      <RouterView v-slot="{ Component }">
        <Transition name="page" mode="out-in">
          <component :is="Component" />
        </Transition>
      </RouterView>
    </main>
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  height: 100%;
  overflow: hidden;
  background: var(--bg-canvas);
}
.app-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.page-enter-active,
.page-leave-active {
  transition: opacity var(--dur) var(--ease), transform var(--dur) var(--ease);
}
.page-enter-from {
  opacity: 0;
  transform: translateY(4px);
}
.page-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>
