import { defineConfig, presetUno, presetAttributify, presetIcons } from 'unocss'

export default defineConfig({
  presets: [
    presetUno(),
    presetAttributify(),
    presetIcons({
      scale: 1.2,
      cdn: 'https://esm.sh/',
    }),
  ],
  theme: {
    colors: {
      // 映射 CSS 变量，使 UnoCSS 原子类可用 bg-surface / text-primary 等
      canvas: 'var(--bg-canvas)',
      surface: 'var(--bg-surface)',
      subtle: 'var(--bg-subtle)',
      inset: 'var(--bg-inset)',
      primary: 'var(--text-primary)',
      secondary: 'var(--text-secondary)',
      tertiary: 'var(--text-tertiary)',
      brand: 'var(--brand)',
      'brand-hover': 'var(--brand-hover)',
      'brand-soft': 'var(--brand-soft)',
      ok: 'var(--success)',
      warn: 'var(--warning)',
      err: 'var(--danger)',
    },
    fontFamily: {
      sans: 'var(--font-sans)',
      mono: 'var(--font-mono)',
    },
    boxShadow: {
      xs: 'var(--shadow-xs)',
      sm: 'var(--shadow-sm)',
      md: 'var(--shadow-md)',
    },
    borderRadius: {
      sm: 'var(--r-sm)',
      md: 'var(--r-md)',
      lg: 'var(--r-lg)',
      xl: 'var(--r-xl)',
      '2xl': 'var(--r-2xl)',
    },
    transitionTimingFunction: {
      ease: 'var(--ease)',
    },
  },
  shortcuts: {
    'flex-center': 'flex items-center justify-center',
    'flex-between': 'flex items-center justify-between',
    'focus-ring': 'outline-none focus-visible:ring-2 focus-visible:ring-brand/40',
  },
})
