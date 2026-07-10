/* ============================================================
   api.ts — 后端 API 类型定义
   对应 com.nbcb.agent.controller 的 3 个 Controller 契约
   ============================================================ */

/* ===== Skills API: GET /api/v1/skills ===== */
export interface SkillsResponse {
  nacosConnected: boolean
  source: string
  skillCount: number
  availableSkillIds: string[]
  mode: string
}

/* ===== Tools API: GET /api/v1/tools ===== */
export interface ToolInfo {
  name: string
  description: string
  source?: string
}

/* ===== Metrics API: GET /api/v1/metrics ===== */
export interface ChatMetrics {
  total: number
  success: number
  failure: number
  retry: number
  durationAvg: string
}
export interface SseMetrics {
  connections: number
  completed: number
  timeout: number
  error: number
  active: number
  durationAvg: string
}
export interface ToolMetrics {
  total: number
  success: number
  failure: number
}
export interface SkillGenMetrics {
  total: number
  success: number
  failure: number
  durationAvg: string
  phaseDecompose: string
  phaseToolMap: string
  phaseStepGen: string
  phaseAssembly: string
}
export interface NacosMetrics {
  connected: number
  loadedSkills: number
  serverAddr?: string
}
export interface ThreadPoolMetrics {
  gauge: Record<string, string>
}
export interface MetricsResponse {
  chat: ChatMetrics
  sse: SseMetrics
  tool: ToolMetrics
  skillGen: SkillGenMetrics
  nacos: NacosMetrics
  threadPool: ThreadPoolMetrics
}

/* ===== SSE 事件类型（StreamEvent.EventType 映射） ===== */
export type SSEEventType =
  | 'thinking'
  | 'text'
  | 'node'
  | 'skill_load'
  | 'tool_call'
  | 'tool_result'
  | 'message'
  | 'done'
  | 'error'
  | 'skill_stage'
  | 'progress'

export interface StreamEvent<T = Record<string, unknown>> {
  type: SSEEventType
  message: string
  data: T
  timestamp: string
}

/* ===== 对话 SSE 事件 data 结构 ===== */
export interface ThinkingData {}
export interface TextData {}
export interface SkillLoadData {
  skillName: string
  contentLength: number
}
export interface ToolCallData {
  toolName: string
  input: string
}
export interface ToolResultData {
  toolName: string
  output: string
}
export interface NodeData {
  nodeName: string
  isEnd: boolean
}
export interface MessageData {}
export interface ChatDoneData {
  processingTimeMs?: number
  calledSkills?: string[]
  toolCallCount?: number
}

/* ===== Skill 生成 SSE 事件 data 结构 ===== */
export interface StageInfo {
  name: string
  status: 'pending' | 'running' | 'completed' | 'failed'
  detail?: string
  elapsedMs?: number
}
export interface SkillStageData {
  stageName: string
  status: string
  detail: { stages: StageInfo[] }
  elapsedMs: number
  totalStages: number
}
export interface SkillDoneData {
  valid: boolean
  processingTimeMs: number
  skillMarkdown: string
  validationErrors: string[]
}

/* ===== 请求体 ===== */
export interface ChatRequest {
  question: string
}
export interface SkillGenerationRequest {
  prdContent: string
  template?: string
}
