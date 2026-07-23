import { ref } from 'vue'

const API_BASE = import.meta.env.VITE_API_BASE || ''

export interface SSEOptions {
  url: string
  body: Record<string, unknown>
  onEvent: (type: string, data: Record<string, unknown>) => void
  onError?: (err: Error) => void
  signal?: AbortSignal
}

/**
 * useSSE — 通用 SSE 流式封装（对话 + Skill 生成复用）
 * 消除旧 index.html 和 skill-generator.html 中重复的 SSE 行解析代码
 */
export function useSSE() {
  const isRunning = ref(false)
  let reader: ReadableStreamDefaultReader<Uint8Array> | null = null

  async function start(opts: SSEOptions): Promise<void> {
    isRunning.value = true
    try {
      const res = await fetch(API_BASE + opts.url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'text/event-stream',
        },
        body: JSON.stringify(opts.body),
        signal: opts.signal,
      })
      if (!res.ok) throw new Error(`HTTP ${res.status} ${res.statusText}`)
      if (!res.body) throw new Error('响应无 body')

      reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      let currentEvent = 'message'

      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''
        for (const line of lines) {
          if (line.startsWith('event:')) {
            currentEvent = line.substring(6).trim()
          } else if (line.startsWith('data:')) {
            const ds = line.substring(5).trim()
            if (ds) {
              try {
                const data = JSON.parse(ds)
                opts.onEvent(currentEvent, data)
              } catch {
                opts.onEvent(currentEvent, { raw: ds })
              }
            }
          } else if (line === '') {
            currentEvent = 'message'
          }
        }
      }
      // 处理残余 buffer
      if (buffer.trim()) {
        const lines = buffer.split('\n').filter(l => l.trim())
        for (const line of lines) {
          if (line.startsWith('event:')) currentEvent = line.substring(6).trim()
          else if (line.startsWith('data:')) {
            const ds = line.substring(5).trim()
            if (ds) {
              try { opts.onEvent(currentEvent, JSON.parse(ds)) } catch { /***/ }
            }
          }
        }
      }
    } catch (err: unknown) {
      if (err instanceof Error && err.name !== 'AbortError') {
        opts.onError?.(err)
      }
    } finally {
      isRunning.value = false
      reader = null
    }
  }

  function cancel() {
    reader?.cancel().catch(() => {})
    isRunning.value = false
  }

  return { isRunning, start, cancel }
}

/** 简易 fetch 包装（非流式） */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code?: string,
    message?: string,
  ) {
    super(message || `HTTP ${status}`)
    this.name = 'ApiError'
  }
}

async function throwApiError(res: Response): Promise<never> {
  try {
    const body = await res.json() as { code?: string; error?: string; message?: string }
    throw new ApiError(res.status, body.code, body.error || body.message)
  } catch (err) {
    if (err instanceof ApiError) throw err
    throw new ApiError(res.status)
  }
}

export async function apiGet<T>(url: string): Promise<T> {
  const res = await fetch(API_BASE + url)
  if (!res.ok) return throwApiError(res)
  return res.json()
}

export async function apiPost<T>(url: string, body?: Record<string, unknown>): Promise<T> {
  const res = await fetch(API_BASE + url, {
    method: 'POST',
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) return throwApiError(res)
  return res.json()
}
