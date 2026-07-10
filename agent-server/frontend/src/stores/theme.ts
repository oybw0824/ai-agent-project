import { defineStore } from 'pinia'
import { ref, computed, watch } from 'vue'
import { storage } from '@/utils/storage'

export type ThemeMode = 'light' | 'dark'

export const useThemeStore = defineStore('theme', () => {
  const stored = storage.get<ThemeMode | null>('theme', null)
  const prefersDark = typeof window !== 'undefined' && window.matchMedia('(prefers-color-scheme: dark)').matches
  const mode = ref<ThemeMode>(stored || (prefersDark ? 'dark' : 'light'))

  const isDark = computed(() => mode.value === 'dark')

  function applyTheme() {
    document.documentElement.dataset.theme = mode.value
  }

  function toggle() {
    mode.value = mode.value === 'dark' ? 'light' : 'dark'
  }

  function setTheme(m: ThemeMode) {
    mode.value = m
  }

  watch(
    mode,
    (m) => {
      storage.set('theme', m)
      applyTheme()
    },
    { immediate: true }
  )

  return { mode, isDark, toggle, setTheme, applyTheme }
})
