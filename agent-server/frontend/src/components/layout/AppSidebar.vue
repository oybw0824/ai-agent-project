<script setup lang="ts">
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import { useThemeStore } from '@/stores/theme'

const theme = useThemeStore()
const route = useRoute()
const expanded = ref(false)

const navItems = [
  { name: 'chat', to: '/chat', icon: '💬', label: '对话' },
  { name: 'skills', to: '/skills', icon: '🧩', label: 'Skill 管理' },
  { name: 'skill-generator', to: '/skill-generator', icon: '⚡', label: 'Skill 生成' },
]
</script>

<template>
  <aside
    class="sidebar"
    :class="{ expanded }"
    @mouseenter="expanded = true"
    @mouseleave="expanded = false"
  >
    <div class="sidebar-top">
      <div class="logo">⚡</div>
      <span v-if="expanded" class="brand-name">AI Agent</span>
    </div>

    <nav class="nav-list">
      <RouterLink
        v-for="item in navItems"
        :key="item.name"
        :to="item.to"
        class="nav-item"
        :class="{ active: route.name === item.name }"
        :title="item.label"
      >
        <span class="nav-icon">{{ item.icon }}</span>
        <Transition name="label">
          <span v-if="expanded" class="nav-label">{{ item.label }}</span>
        </Transition>
      </RouterLink>
    </nav>

    <div class="sidebar-bottom">
      <button
        class="icon-btn"
        :title="theme.isDark ? '切换到亮色' : '切换到暗色'"
        @click="theme.toggle()"
      >
        <span>{{ theme.isDark ? '☀️' : '🌙' }}</span>
      </button>
    </div>
  </aside>
</template>

<style scoped>
.sidebar {
  width: var(--sidebar-width);
  background: var(--bg-surface);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  overflow: hidden;
  transition: width var(--dur-slow) var(--ease), background-color var(--dur-slow) var(--ease);
}
.sidebar.expanded {
  width: var(--sidebar-width-expanded);
}

.sidebar-top {
  height: var(--header-height);
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 16px;
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
}
.logo {
  width: 32px;
  height: 32px;
  border-radius: var(--r-md);
  background: var(--brand);
  color: var(--text-inverse);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 17px;
  flex-shrink: 0;
}
.brand-name {
  font-size: var(--text-lg);
  font-weight: 600;
  letter-spacing: -0.01em;
  white-space: nowrap;
}

.nav-list {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 8px;
  overflow-y: auto;
}

.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  height: 40px;
  padding: 0 12px;
  border-radius: var(--r-md);
  font-size: var(--text-sm);
  color: var(--text-secondary);
  transition: background var(--dur) var(--ease), color var(--dur) var(--ease);
  white-space: nowrap;
}
.nav-item:hover {
  background: var(--bg-subtle);
  color: var(--text-primary);
}
.nav-item.active {
  background: var(--brand-soft);
  color: var(--text-primary);
  font-weight: 500;
}
.nav-item.active::before {
  content: '';
  position: absolute;
  left: -8px;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 18px;
  border-radius: 0 3px 3px 0;
  background: var(--brand);
}
.nav-icon {
  font-size: 16px;
  flex-shrink: 0;
  width: 20px;
  text-align: center;
}
.nav-label {
  font-size: var(--text-sm);
}

.label-enter-active,
.label-leave-active {
  transition: opacity var(--dur-fast) var(--ease);
}
.label-enter-from,
.label-leave-to {
  opacity: 0;
}

.sidebar-bottom {
  padding: 8px;
  border-top: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.icon-btn {
  width: 40px;
  height: 40px;
  border: none;
  border-radius: var(--r-md);
  background: transparent;
  color: var(--text-secondary);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  transition: background var(--dur) var(--ease), color var(--dur) var(--ease);
}
.icon-btn:hover {
  background: var(--bg-subtle);
  color: var(--text-primary);
}
</style>
