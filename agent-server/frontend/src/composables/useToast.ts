import { ref } from 'vue'

export interface ToastInstance {
  id: number
  type: 'success' | 'error' | 'warning' | 'info'
  message: string
  duration: number
}

let nextId = 0
const toasts = ref<ToastInstance[]>([])

function remove(id: number) {
  toasts.value = toasts.value.filter(toast => toast.id !== id)
}

function show(type: ToastInstance['type'], message: string, duration = 3000) {
  const id = ++nextId
  toasts.value.push({ id, type, message, duration })
  if (duration > 0) setTimeout(() => remove(id), duration)
}

export function useToast() {
  return {
    show,
    success: (message: string, duration?: number) => show('success', message, duration),
    error: (message: string, duration?: number) => show('error', message, duration),
    warning: (message: string, duration?: number) => show('warning', message, duration),
    info: (message: string, duration?: number) => show('info', message, duration),
  }
}

export function useToastState() {
  return { toasts, remove }
}
