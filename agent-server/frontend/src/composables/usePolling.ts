import { ref, onUnmounted } from 'vue'
import { apiGet } from './useSSE'

/**
 * usePolling — 自动轮询封装
 * ★ 页面不可见时自动暂停，恢复时立即拉取一次
 */
export function usePolling<T>(
  url: string,
  intervalMs: number,
  onData: (data: T) => void,
  onError?: (err: Error) => void,
) {
  const isActive = ref(true)
  let timer: ReturnType<typeof setInterval> | null = null

  async function fetch() {
    if (!isActive.value) return
    try {
      const data = await apiGet<T>(url)
      onData(data)
    } catch (err) {
      onError?.(err instanceof Error ? err : new Error(String(err)))
    }
  }

  function start() {
    stop()
    isActive.value = true
    fetch()
    timer = setInterval(fetch, intervalMs)
  }

  function stop() {
    if (timer) { clearInterval(timer); timer = null }
    isActive.value = false
  }

  function onVisibilityChange() {
    if (document.hidden) {
      stop()
    } else {
      start()
    }
  }

  document.addEventListener('visibilitychange', onVisibilityChange)
  onUnmounted(() => {
    stop()
    document.removeEventListener('visibilitychange', onVisibilityChange)
  })

  return { start, stop, fetch: () => fetch(), isActive }
}
