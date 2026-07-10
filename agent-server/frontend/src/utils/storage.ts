/**
 * storage.ts — localStorage 封装
 * 保持 agent_ 前缀，兼容旧前端数据（agent_theme / agent_chat_messages 等）
 */

const PREFIX = 'agent_'

export const storage = {
  get<T>(key: string, fallback: T): T {
    try {
      const raw = localStorage.getItem(PREFIX + key)
      return raw ? (JSON.parse(raw) as T) : fallback
    } catch {
      return fallback
    }
  },

  set<T>(key: string, value: T): void {
    try {
      localStorage.setItem(PREFIX + key, JSON.stringify(value))
    } catch {
      /* quota exceeded */
    }
  },

  remove(key: string): void {
    try {
      localStorage.removeItem(PREFIX + key)
    } catch {
      /* */
    }
  },

  /** 带容量限制的追加（数组） */
  push<T>(key: string, item: T, max: number): T[] {
    const arr = this.get<T[]>(key, [])
    arr.push(item)
    while (arr.length > max) arr.shift()
    this.set(key, arr)
    return arr
  },
}
