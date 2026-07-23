# 流式对话 API 设计文档

> 版本：v2.3 | 更新日期：2026-07-08 | 作者：com.nbcb

---

## 目录

1. [概述](#1-概述)
2. [API 端点](#2-api-端点)
3. [SSE 事件类型](#3-sse-事件类型)
4. [系统架构](#4-系统架构)
5. [工具调用拦截](#5-工具调用拦截)
6. [请求上下文管理](#6-请求上下文管理)
7. [配置参考](#7-配置参考)
8. [错误处理](#8-错误处理)
9. [前端集成](#9-前端集成)

---

## 1. 概述

### 1.1 项目定位

`/api/v1/chat/stream` 是 agent-server 提供的流式对话 API，基于 Server-Sent Events (SSE) 协议，将 ReactAgent 内部的完整推理过程（思考、技能加载、工具调用、文本生成）实时推送到客户端。

### 1.2 技术栈

| 层次 | 技术 | 版本 | 作用 |
|------|------|------|------|
| 传输协议 | SSE (Server-Sent Events) | -- | 服务端单向推送，基于 HTTP POST |
| SSE 实现 | Spring `SseEmitter` | Spring Boot 3.5.8 | 封装 SSE 协议细节 |
| Agent 框架 | `spring-ai-alibaba-agent-framework` | 1.1.2.2 | ReactAgent + React 模式推理 |
| LLM | DeepSeek V4 Flash | -- | 通过 OpenAI 兼容接口调用 |
| 流式处理 | Project Reactor `Flux<NodeOutput>` | -- | 非阻塞流式订阅，`subscribe()` 代替 `blockLast()` |
| MCP 客户端 | `spring-ai-alibaba-starter-mcp-distributed` | 1.1.2.1 | 从 Nacos 发现 MCP Server，ASYNC STREAMABLE |
| 前端 | Vue 3 + TypeScript + Vite | -- | `useSSE` composable + Fetch API ReadableStream |

### 1.3 整体架构图

```
                     HTTP POST /api/v1/chat/stream
前端 (Vue 3) ─────────────────────────────────────────> ChatController
  │                                                           │
  │  useSSE composable                                        │  streamService.streamChat(question)
  │  Fetch + ReadableStream                                   │
  │                                                           ▼
  │  event: thinking                                     AgentStreamService
  │  event: text (逐 token)                                  │
  │  event: skill_load                                       │  sseExecutor.submit()
  │  event: tool_call                                        │     │
  │  event: tool_result                                      │     ▼
  │  event: done                                        ReactAgent.stream(question)
  │<──────────────────────────────────────────────────────────     │
       SSE (text/event-stream)                                     │
                                                                   ├── Hook: SkillsAgentHook
                                                                   │   (read_skill 工具)
                                                                   │
                                                                   ├── Hook: SessionTimeoutBudgetHook
                                                                   ├── Hook: ModelCallLimitHook
                                                                   ├── Hook: TokenBudgetHook
                                                                   ├── Hook: LoopDetectHook
                                                                   ├── Hook: ToolAvailabilityHook
                                                                   │
                                                                   ├── Hook: ToolEventHook
                                                                   │   └── ToolEventInterceptor
                                                                   │       (工具调用 SSE 推送)
                                                                   │
                                                                   └── Node 执行
                                                                       ├── llm node  → StreamingOutput (逐 token)
                                                                       └── tools node → LoggingToolCallback
                                                                                      → ToolGovernanceWrapper
                                                                                      → MCP Server (STREAMABLE)
```

---

## 2. API 端点

### 2.1 基本信息

| 属性 | 值 |
|------|-----|
| URL | `POST /api/v1/chat/stream` |
| Content-Type（请求） | `application/json` |
| Accept（请求） | `text/event-stream` |
| Content-Type（响应） | `text/event-stream` |
| 超时 | 默认 300 秒（可配置 `agent.stream.timeout-seconds`） |
| 认证 | 当前无（后续可扩展 API Key / JWT） |

### 2.2 请求格式

```json
{
  "question": "帮我查询北京今天的天气，并计算 15 * 23 的结果"
}
```

**Java DTO 定义**（`ChatRequest.java`）：

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {
    @NotBlank(message = "问题不能为空")
    @Size(max = 10240, message = "问题内容不能超过 10240 字符")
    private String question;

    @Builder.Default
    private boolean showThinking = false;  // 流式接口自动开启，此字段供非流式使用
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `question` | String | 是 | 用户问题，最大 10240 字符 |
| `showThinking` | boolean | 否 | 是否返回思考过程（默认 false，流式接口自动开启） |

### 2.3 响应格式

响应为标准 SSE 流，每条事件格式如下：

```
event: <事件类型>
data: <JSON 数据>
\n
```

示例：

```
event: thinking
data: {"type":"THINKING","message":"正在分析你的问题，匹配最合适的技能...","timestamp":"2026-07-08T10:30:00.123Z"}

event: text
data: {"type":"TEXT","message":"好的","timestamp":"2026-07-08T10:30:01.456Z"}

event: text
data: {"type":"TEXT","message":"，我","timestamp":"2026-07-08T10:30:01.478Z"}

event: tool_call
data: {"type":"TOOL_CALL","message":"调用工具: m_s_getWeatherByCity","data":{"toolName":"m_s_getWeatherByCity","input":"{\"city\":\"北京\"}"},"timestamp":"2026-07-08T10:30:02.000Z"}

event: tool_result
data: {"type":"TOOL_RESULT","message":"工具返回: m_s_getWeatherByCity","data":{"toolName":"m_s_getWeatherByCity","output":"{\"city\":\"北京\",\"temperature\":28,\"condition\":\"晴\"}"},"timestamp":"2026-07-08T10:30:03.500Z"}

event: done
data: {"type":"DONE","message":"对话完成","data":{"question":"帮我查询北京今天的天气...","matchedSkill":"weather-assistant","calledSkills":["weather-assistant"],"toolCallCount":2,"processingTimeMs":4523,"answerLength":156},"timestamp":"2026-07-08T10:30:04.500Z"}
```

---

## 3. SSE 事件类型

所有事件类型定义于 `StreamEvent.EventType` 枚举，前端通过 `event:` 行的事件名区分。

### 3.1 事件一览

| SSE Event 名 | 枚举值 | 触发时机 | data 字段 | 出现频率 |
|-------------|--------|---------|-----------|---------|
| `thinking` | `THINKING` | 流开始时，Agent 分析问题 | 无额外 data | 每次请求 1 次 |
| `node` | `NODE` | Graph Node 执行完成 | `nodeName`, `isEnd` | 每次 Graph 节点切换 |
| `text` | `TEXT` | LLM 逐 token 流式输出 | 无额外 data（message 即 token 文本） | 高频（每 token 一条） |
| `skill_load` | `SKILL_LOAD` | LLM 通过 read_skill 加载技能 | `skillName`, `contentLength` | 按需（0-N 次） |
| `tool_call` | `TOOL_CALL` | LLM 决定调用 MCP 工具 | `toolName`, `input` | 按需（0-N 次） |
| `tool_result` | `TOOL_RESULT` | MCP 工具返回结果 | `toolName`, `output` | 与 tool_call 成对 |
| `message` | `MESSAGE` | Agent 最终回答（文本块） | 无额外 data | 每次请求 0-1 次 |
| `done` | `DONE` | 对话完成（含完整元数据） | `question`, `matchedSkill`, `calledSkills[]`, `toolCallCount`, `processingTimeMs`, `answerLength` | 每次请求 1 次（正常结束） |
| `error` | `ERROR` | 发生错误 | 无额外 data（message 即错误信息） | 异常时 |
| `skill_stage` | `SKILL_STAGE` | Skill 生成阶段进度（Skill Generation 专用） | `stageName`, `status`, `detail`, `elapsedMs`, `totalStages` | Skill 生成流程 |

### 3.2 关键事件详解

#### 3.2.1 `text` — LLM Token 级流式输出

- **特点**：逐 token（字/词）推送，前端可实时渲染打字机效果
- **增量提取**：`StreamEventHandler` 基于位置追踪（O(1)）提取增量，兼容 LLM 的累积模式和增量模式
- **去重**：流结束后 `TextProcessingUtil.deduplicate()` 去除可能的重叠文本
- **示例**：用户问"你好"，前端可能收到 3 条 text 事件：`"你"` → `"好"` → `"，有什么"`

#### 3.2.2 `tool_call` — 工具调用事件

- **触发机制**：双重拦截
  - `ToolEventInterceptor`（框架 Hook 层，AgentToolNode 内部，100% 覆盖）
  - `LoggingToolCallback`（ToolCallback 装饰层，兜底）
- **数据截断**：入参限制 500 字符，出参限制 200 字符，防止日志/SSE 数据过大
- **安全**：工具参数/结果仅 DEBUG 级别写入生产日志，敏感信息不泄露

#### 3.2.3 `done` — 对话完成事件

- **包含完整元数据**：问题、匹配技能、调用技能列表、工具调用数、处理时间、答案长度
- **RequestContext 清理**：在 `doOnComplete` 回调的 `finally` 块中调用 `context.close()`

#### 3.2.4 `error` — 错误事件

- 流式异常：Agent 执行过程中任何异常都会通过 `doOnError` 捕获
- 同步异常：`sseExecutor.submit()` 内部的 try-catch 捕获启动阶段异常
- 客户端断开：通过 `emitter.onError()` 捕获，推送失败静默处理（debug 级别日志）

### 3.3 StreamEvent JSON 结构

```json
{
  "type": "THINKING",        // 事件类型（枚举大写）
  "message": "正在分析...",    // 人类可读描述，前端直接展示
  "data": {                   // 附加数据（根据事件类型不同，可能为空）
    "toolName": "...",
    "input": "..."
  },
  "timestamp": "2026-07-08T10:30:00.123Z"  // ISO 8601 时间戳
}
```

**序列化**：使用 Spring 注入的 `ObjectMapper`（全局一致配置），支持 `JavaTimeModule`。序列化失败时降级为简单 error JSON。

---

## 4. 系统架构

### 4.1 核心组件

| 组件 | 类路径 | 职责 |
|------|--------|------|
| `ChatController` | `controller/ChatController.java` | REST 端点，接收请求，委托给 `AgentStreamService` |
| `AgentStreamService` | `service/AgentStreamService.java` | 流式对话编排核心：创建 SseEmitter、启动 Agent 流、管理生命周期 |
| `StreamEventHandler` | `service/stream/StreamEventHandler.java` | 解析 ReactAgent 的 `NodeOutput`，转换为 SSE 事件 |
| `StreamingTextCollector` | `service/stream/StreamingTextCollector.java` | 累积流式文本，O(1) 增量提取，最大 100K 字符截断保护 |
| `SsePushHelper` | `util/SsePushHelper.java` | 统一的 SSE 推送工具，`emitter.send(event.name().data())` |
| `AgentConfig` | `config/AgentConfig.java` | ReactAgent 工厂：组装 SkillsAgentHook + 5 治理 Hook + ToolEventHook + MCP 工具 |
| `RequestContext` | `domain/RequestContext.java` | 请求级上下文：ThreadLocal + FALLBACK + REGISTRY 三层查找 |
| `UseSSE` | `frontend/src/composables/useSSE.ts` | 前端通用 SSE 消费 composable |

### 4.2 数据流

```
1. POST /api/v1/chat/stream
       │
2.     ▼  ChatController.chatStream()
       │  - 调用 streamService.streamChat(question)
       │  - 返回 SseEmitter
       │
3.     ▼  AgentStreamService.streamChat()
       │  - 创建 SseEmitter(300s timeout)
       │  - 注册 onTimeout/onError/onCompletion 回调
       │  - 创建 StreamingTextCollector
       │  - sseExecutor.submit() 提交异步任务（不阻塞请求线程）
       │
4.     ▼  sseExecutor 线程
       │  - RequestContext.begin(emitter)   // ★ 创建请求上下文
       │  - SsePushHelper.push(emitter, StreamEvent.thinking(...))
       │  - agent.stream(question)          // 获取 Flux<NodeOutput>
       │
5.     ▼  ReactAgent 流式执行
       │  .doOnNext(nodeOutput -> {
       │      StreamEventHandler.handleNodeOutput(collector, nodeOutput,
       │          event -> SsePushHelper.push(emitter, event))
       │  })
       │  ├── 遇到 StreamingOutput → 提取 delta → text 事件
       │  ├── 遇到普通 NodeOutput   → node 事件 (节点切换)
       │  ├── 工具调用经过 ToolEventInterceptor → tool_call / tool_result 事件
       │  └── SkillsAgentHook 触发 read_skill → skill_load 事件
       │
6.     ▼  .doOnComplete()
       │  - resolveAnswer() 解析最终答案（优先级：累积文本 → AssistantMessage → state data）
       │  - 若未流式推送过，补发 message 事件
       │  - sendDoneEvent() 推送 done 事件（元数据）
       │  - emitter.complete()
       │  - context.close()  // ★ 清理 RequestContext
       │
7.     ▼  .doOnError(e -> { handleStreamError(emitter, e); context.close(); })
       │
8.     ▼  .subscribe()  // ★ 非阻塞订阅，立即返回
```

### 4.3 线程模型

```
HTTP 请求线程 (Tomcat/Netty)
    │
    │  创建 SseEmitter 并立即返回
    │
    └── sseExecutor (ThreadPoolTaskExecutor)
         ├── core-size: 4
         ├── max-size: 16
         ├── queue-capacity: 64（背压保护）
         │
         │  提交 Agent 流式任务
         │
         ├── agent.stream(question)
         │    │
         │    └── ReactAgent 内部线程
         │         ├── llm node → 可能使用 boundedElastic 线程
         │         └── tools node → boundedElastic 线程执行 MCP 调用
         │              │
         │              └── 工具线程需要获取 RequestContext
         │                   ├── ThreadLocal（同线程直接命中）
         │                   └── FALLBACK AtomicReference（跨线程 fallback）
         │
         └── .doOnNext / .doOnComplete / .doOnError 回调
              （在 sseExecutor 线程中执行）
              └── 通过 SseEmitter.send() 推送到客户端
```

**关键设计决策**：使用 `subscribe()` 代替 `blockLast()`

| 方案 | 线程占用 | 风险 |
|------|---------|------|
| `blockLast()` | sseExecutor 线程阻塞直到流结束 | 长时间占用线程，耗尽线程池 |
| `subscribe()`（当前） | 立即返回，回调异步执行 | sseExecutor 线程及时释放，利用率高 |

**RequestContext 注意事项**：由于 `subscribe()` 非阻塞，不可用 try-with-resources。`context.close()` 必须在 `doOnComplete` 和 `doOnError` 的 finally 中手动调用，否则 FALLBACK 会被过早清空，工具调用线程无法获取上下文。

### 4.4 答案解析优先级

最终答案从以下三个来源按优先级提取（`resolveAnswer()` 方法）：

| 优先级 | 来源 | 说明 |
|--------|------|------|
| 1（最高） | `StreamingTextCollector.getAnswer()` | 流式过程中累积的文本，最准确 |
| 2 | `OverAllState` 中的 `AssistantMessage` | 从 messages 列表倒序查找最后的 AssistantMessage |
| 3（最低） | `OverAllState.data().get(outputKey)` | 从状态数据中按 outputKey 取，兜底 |

**去重处理**：`TextProcessingUtil.deduplicate()` 去除 LLM 可能产生的重复片段。

**message 事件补充逻辑**：如果流式过程中已推送过 `text` 事件（`hasStreamingText = true`），则跳过 `message` 事件，避免前端重复渲染。

---

## 5. 工具调用拦截

系统采用**双重拦截机制**确保工具调用的 SSE 推送和治理校验 100% 覆盖。

### 5.1 拦截架构

```
ReactAgent
  │
  ├── AgentToolNode 执行工具调用
  │     │
  │     ├── ★ ToolInterceptor 层（框架官方机制，100% 覆盖）
  │     │     │
  │     │     └── ToolEventHook.getToolInterceptors()
  │     │           └── ToolEventInterceptor.interceptToolCall()
  │     │                 ├── 推送 tool_call SSE 事件
  │     │                 ├── handler.call(request) → 执行工具
  │     │                 ├── 推送 tool_result SSE 事件
  │     │                 └── 记录到 RequestContext.toolRecords
  │     │
  │     └── ToolCallback 层（装饰器，兜底覆盖）
  │           │
  │           └── LoggingToolCallback.call()
  │                 ├── 推送 tool_call SSE 事件（重复推送？极端情况兜底）
  │                 ├── ToolGovernanceWrapper.call() → 校验 → delegate.call()
  │                 ├── 推送 tool_result SSE 事件
  │                 └── 记录到 RequestContext
  │
  └── MCP Server (通过 STREAMABLE 连接)
```

### 5.2 三层组件协作

| 组件 | 层级 | 机制 | 职责 |
|------|------|------|------|
| `ToolEventHook` | Hook 层 | 框架 `Hook.getToolInterceptors()` | 注册拦截器，仅贡献拦截器，不参与模型调用 |
| `ToolEventInterceptor` | 拦截器层 | 框架 `AgentToolNode.executeToolCallWithInterceptors()` | 工具调用前后推送 SSE 事件 + 记录调用历史 |
| `LoggingToolCallback` | 装饰器层 | 实现 `ToolCallback` 接口，包装真实工具 | 兜底 SSE 推送 + Metrics 埋点 + 调用日志 |
| `ToolGovernanceWrapper` | 治理层 | 实现 `ToolCallback` 接口，包装真实工具 | 第二层运行时校验（工具开关），调用前检查 `checkEnabled()` |

### 5.3 ToolEventInterceptor 数据截断策略

| 数据 | 截断长度 | 原因 |
|------|---------|------|
| 工具入参 | 500 字符 | 防止 SSE 消息过大，影响前端渲染 |
| 工具出参 | 200 字符 | 返回结果可能很长，保留关键信息即可 |
| 日志入参 | 100 字符 | 生产日志仅 DEBUG 级别，防止敏感信息泄露 |

### 5.4 治理两层校验

```
第一层（注册时）：McpToolRegistrar.loadAvailableTools()
  └── 过滤 tool-governance.tools.*.status != ENABLED 的工具
  └── 未配置的工具默认 DISABLED，避免遗漏配置导致误放行

第二层（运行时）：ToolGovernanceWrapper.call()
  └── 调用前 interceptor.checkEnabled(toolName)
  └── 校验失败抛出 ToolExecutionException，由框架处理
```

---

## 6. 请求上下文管理

### 6.1 设计目标

Streaming API 需要在多个线程间共享请求级状态（SSE emitter、工具调用记录、已加载技能），同时保证并发请求间的隔离性。

### 6.2 三层查找机制

```
┌─────────────── 查找优先级：高 ───────────────┐
│                                               │
│  1. ThreadLocal<RequestContext> CURRENT       │
│     └── 同线程直接命中（sseExecutor 线程）      │
│                                               │
│  2. AtomicReference<RequestContext> FALLBACK  │
│     └── 跨线程快速 fallback（工具调用线程）     │
│                                               │
│  3. ConcurrentHashMap<UUID, Context> REGISTRY │
│     └── 多请求隔离兜底，防止并发泄漏           │
│                                               │
└─────────────── 查找优先级：低 ───────────────┘
```

### 6.3 生命周期

```
begin(emitter)
  ├── 生成 UUID requestId
  ├── CURRENT.set(ctx)          // ThreadLocal
  ├── FALLBACK.set(ctx)         // AtomicReference
  └── REGISTRY.put(requestId, ctx) // ConcurrentHashMap
       │
       ├── ... 使用期间 ...
       │
       └── close()
            ├── REGISTRY.remove(requestId)  // 精确删除（按 requestId）
            ├── FALLBACK.compareAndSet(this, null)  // CAS 防止误删他人
            └── CURRENT.remove()            // 清理 ThreadLocal
```

### 6.4 关键设计要点

1. **不可用 try-with-resources**：因为 `subscribe()` 非阻塞，try 块会立即退出，导致 FALLBACK 被清空。必须在 `doOnComplete`/`doOnError` 中手动调用 `close()`。

2. **REGISTRY 按 requestId 精确删除**：`REGISTRY.remove(requestId)` 而非遍历清空，保证并发请求的上下文不会被误清理。

3. **FALLBACK 使用 CAS 清除**：`FALLBACK.compareAndSet(this, null)` 而非 `FALLBACK.set(null)`，防止清除其他线程刚设置的上下文。

4. **FALLBACK 的局限性**：高并发场景下 `AtomicReference` 只能存一个引用，极端并发时可能被覆盖。REGISTRY 作为兜底保障。

5. **REGISTRY 的单值 fallback**：`current()` 的第三层 `REGISTRY.values().iterator().next()` 返回任意一个上下文，适合低并发场景。高并发时建议使用 `ToolEventHook` 中携带的会话标识做精确匹配。

### 6.5 使用示例

```java
// AgentStreamService 中
sseExecutor.submit(() -> {
    RequestContext context = RequestContext.begin(emitter);  // ★ 开始
    try {
        agent.stream(question)
            .doOnNext(nodeOutput -> {
                // 同线程，ThreadLocal 直接命中
                RequestContext ctx = RequestContext.current();
                SsePushHelper.push(ctx.getEmitter(), event);
            })
            .doOnComplete(() -> {
                // ★ finally 中清理
                RequestContext ctx = RequestContext.current();
                List<ToolCallRecord> records = ctx.getToolRecords();
                // ... 组装 done 事件 ...
                context.close();  // ★ 清理
            })
            .doOnError(e -> {
                context.close();  // ★ 错误时也清理
            })
            .subscribe();
    } catch (Exception e) {
        context.close();  // ★ 同步异常时清理
    }
});
```

---

## 7. 配置参考

### 7.1 application.yml 配置项

```yaml
# ==================== SSE 流式配置 ====================
agent:
  stream:
    timeout-seconds: 300              # SSE 连接超时（秒），SseEmitter 构造函数参数
    max-text-length: 100_000          # 最大流式文本长度，防止 LLM 输出失控导致 OOM

# ==================== 线程池配置 ====================
agent:
  thread-pool:
    sse:
      core-size: 4                    # SSE 推送核心线程数
      max-size: 16                    # SSE 推送最大线程数
      queue-capacity: 64              # 任务队列容量（达到后触发背压）

# ==================== LLM 配置 ====================
agent:
  llm:
    connect-timeout-ms: 5000          # LLM 连接超时
    read-timeout-ms: 60000            # LLM 单次调用读取超时
    retry:
      max-attempts: 3                 # LLM 调用最大重试次数
      backoff-initial-ms: 1000        # 指数退避初始间隔
      backoff-multiplier: 2.0         # 退避倍数
      backoff-max-ms: 8000            # 退避上限

# ==================== CORS ====================
agent:
  cors:
    allowed-origins: http://localhost:3000,http://localhost:5173

# ==================== DeepSeek Chat Model ====================
spring:
  ai:
    openai:
      api-key: ${DEEPSEEK_API_KEY:}   # 环境变量注入，不硬编码
      base-url: https://api.deepseek.com
      chat:
        options:
          model: deepseek-v4-flash
          max-tokens: 32768

# ==================== MCP Client ====================
spring:
  ai:
    mcp:
      client:
        enabled: true
        name: agent-server
        version: 1.0.0
        request-timeout: 600s
        type: ASYNC

# ==================== Nacos MCP 分布式发现 ====================
spring:
  ai:
    alibaba:
      mcp:
        nacos:
          client:
            enabled: true
            streamable:
              connections:
                server1:
                  service-name: mcp-service
                  version: 1.1.0
            configs:
              server1:
                namespace: ${NACOS_NAMESPACE:public}
                server-addr: ${NACOS_ADDR:127.0.0.1:8848}
                username: ${NACOS_USERNAME:nacos}
                password: ${NACOS_PASSWORD:nacos}
```

### 7.2 工具开关治理配置

```yaml
tool-governance:
  tools:
    m_s_getWeatherByCity:
      status: DISABLED                # 关闭天气查询工具
    m_s_calculate:
      status: ENABLED                 # 开启计算器工具
```

### 7.3 Agent 限流降级配置

```yaml
agent-governance:
  max-iterations:
    default: 8                        # 默认最大推理轮次
    l4-high-risk: 4                   # 高风险场景最大推理轮次
  session-timeout:
    default-budget-ms: 300000         # 默认会话超时（5分钟）
    high-risk-budget-ms: 10000        # 高风险场景会话超时（10秒）
    minimum-next-step-ms: 1500        # 最小步骤间隔
  model-call-limit:
    default-limit: 20                 # 默认模型调用次数上限
    l4-high-risk-limit: 8             # 高风险场景上限
  token-budget:
    default-max-tokens: 12000         # 默认 Token 预算
    l4-high-risk-max-tokens: 8000     # 高风险场景 Token 预算
    warning-ratio: 0.8                # 警告阈值比例
  loop-detect:
    strict-repeat-threshold: 3        # 严格重复检测阈值
    pattern-window: 8                 # 模式检测窗口大小
    alternating-repeat-threshold: 3   # 交替重复检测阈值
    stop-after-non-retryable-result: true  # 不可重试结果后停止
```

---

## 8. 错误处理

### 8.1 流内错误处理（AgentStreamService）

| 错误场景 | 处理方式 | 推送事件 |
|----------|---------|---------|
| Agent 流执行异常 | `doOnError` 捕获 → `handleStreamError()` | `error` 事件 + `emitter.completeWithError()` |
| sseExecutor 同步异常 | try-catch 捕获 | `error` 事件 + `emitter.completeWithError()` |
| 异步 `subscribe()` 异常 | Reactor 错误回调 | `error` 事件 + `emitter.completeWithError()` |
| SSE 推送失败（客户端断开） | `SsePushHelper.push()` 内部 debug 日志 | 静默处理 |
| LLM 输出超长（>100K） | `StreamingTextCollector` 静默截断 | 正常完成（带截断警告日志） |

### 8.2 全局异常处理（GlobalExceptionHandler）

对于常规 REST API（非流式），提供以下统一异常处理：

| 异常类型 | HTTP 状态码 | 说明 |
|---------|------------|------|
| `MethodArgumentNotValidException` | 400 | 参数校验失败（@Valid） |
| `HttpMessageNotReadableException` | 400 | JSON 格式错误 |
| `IllegalArgumentException` | 400 | 业务参数不合法 |
| `TimeoutException` | 504 | 请求超时 |
| `LlmCallException` | 503 | LLM 调用失败 |
| `LlmJsonInvalidException` | 502 | LLM 返回 JSON 不可解析 |
| `AgentEarlyTerminationException` | 422 | 治理 Hook 触发终止（限流/超时/空转） |
| `GraphRunnerException` | 500 | Agent 框架执行异常 |
| `RuntimeException` | 500 | 运行时异常（兜底） |
| `Exception` | 500 | 未知异常（兜底） |

---

## 9. 前端集成

### 9.1 技术方案

前端使用 `fetch + ReadableStream`（而非浏览器原生 `EventSource`），原因：

- `EventSource` 不支持 POST 请求体
- `EventSource` 不支持自定义请求头
- `EventSource` 重新连接策略不可控

### 9.2 useSSE Composable

文件：`agent-server/frontend/src/composables/useSSE.ts`

```typescript
// 使用示例
import { useSSE } from '@/composables/useSSE'

const { isRunning, start, cancel } = useSSE()

await start({
  url: '/api/v1/chat/stream',
  body: { question: '帮我写一个排序函数' },
  onEvent: (type: string, data: Record<string, unknown>) => {
    switch (type) {
      case 'thinking':
        // 显示"分析中..."
        break
      case 'text':
        // 追加文本 token 到回答区
        break
      case 'tool_call':
        // 显示"调用工具: xxx"
        break
      case 'tool_result':
        // 显示工具返回结果
        break
      case 'done':
        // 对话完成，停止加载动画
        break
      case 'error':
        // 错误提示
        break
    }
  },
  onError: (err: Error) => {
    console.error('SSE 错误:', err)
  },
  signal: abortController.signal,  // 支持取消
})
```

### 9.3 SSE 协议解析流程

```
Fetch Response.body (ReadableStream)
  │
  ├── reader.read() 循环读取 chunk
  │
  ├── TextDecoder.decode() → 拼接 buffer
  │
  ├── 按 \n 分割行
  │     ├── "event: xxx"  → 记录当前事件类型
  │     ├── "data: {...}" → JSON.parse() → onEvent(type, data)
  │     └── "" (空行)     → 重置事件类型为 "message"
  │
  └── 读取结束（done === true）
        ├── 处理残余 buffer
        └── isRunning = false
```

### 9.4 Vue 组件集成示例

```vue
<script setup lang="ts">
import { ref } from 'vue'
import { useSSE } from '@/composables/useSSE'

const answer = ref('')
const toolCalls = ref<Array<{ name: string; input: string; output?: string }>>([])

const { isRunning, start, cancel } = useSSE()

async function sendQuestion(question: string) {
  answer.value = ''
  toolCalls.value = []
  await start({
    url: '/api/v1/chat/stream',
    body: { question },
    onEvent(type, data) {
      switch (type) {
        case 'text':
          answer.value += data.message || ''
          break
        case 'tool_call':
          toolCalls.value.push({
            name: data.data?.toolName as string,
            input: data.data?.input as string,
          })
          break
        case 'tool_result':
          const last = toolCalls.value[toolCalls.value.length - 1]
          if (last) last.output = data.data?.output as string
          break
      }
    },
    onError(err) {
      answer.value = '请求失败: ' + err.message
    }
  })
}
</script>
```

### 9.5 CORS 配置

开发环境前端（Vite 端口 5173）通过后端 CORS 配置允许跨域：

```yaml
agent:
  cors:
    allowed-origins: http://localhost:3000,http://localhost:5173
```

前端 `useSSE` 使用 `import.meta.env.DEV` 判断开发环境，直接连接后端 `http://localhost:8082`（绕过 Vite proxy，避免 Content-Type 被丢弃）。

---

## 附录

### A. 关键文件索引

| 文件 | 完整路径 |
|------|---------|
| ChatController | `agent-server/src/main/java/com/nbcb/agent/controller/ChatController.java` |
| AgentStreamService | `agent-server/src/main/java/com/nbcb/agent/service/AgentStreamService.java` |
| StreamEventHandler | `agent-server/src/main/java/com/nbcb/agent/service/stream/StreamEventHandler.java` |
| StreamingTextCollector | `agent-server/src/main/java/com/nbcb/agent/service/stream/StreamingTextCollector.java` |
| StreamEvent | `agent-server/src/main/java/com/nbcb/agent/domain/StreamEvent.java` |
| RequestContext | `agent-server/src/main/java/com/nbcb/agent/domain/RequestContext.java` |
| ToolCallRecord | `agent-server/src/main/java/com/nbcb/agent/domain/ToolCallRecord.java` |
| ChatRequest | `agent-server/src/main/java/com/nbcb/agent/domain/ChatRequest.java` |
| AgentConfig | `agent-server/src/main/java/com/nbcb/agent/config/AgentConfig.java` |
| ToolEventHook | `agent-server/src/main/java/com/nbcb/agent/governance/ToolEventHook.java` |
| ToolEventInterceptor | `agent-server/src/main/java/com/nbcb/agent/governance/ToolEventInterceptor.java` |
| LoggingToolCallback | `agent-server/src/main/java/com/nbcb/agent/skill/LoggingToolCallback.java` |
| ToolGovernanceWrapper | `agent-server/src/main/java/com/nbcb/agent/governance/ToolGovernanceWrapper.java` |
| SsePushHelper | `agent-server/src/main/java/com/nbcb/agent/util/SsePushHelper.java` |
| GlobalExceptionHandler | `agent-server/src/main/java/com/nbcb/agent/exception/GlobalExceptionHandler.java` |
| application.yml | `agent-server/src/main/resources/application.yml` |
| useSSE.ts | `agent-server/frontend/src/composables/useSSE.ts` |
| api.ts | `agent-server/frontend/src/types/api.ts` |

### B. 版本历史

| 版本 | 日期 | 变更内容 |
|------|------|---------|
| v2.0 | -- | 初始流式 API 实现 |
| v2.1 | -- | LLM 自主匹配技能，无需传 skillId |
| v2.2 | -- | 新增 `node` 事件（Graph Node 切换可视化） |
| v2.3 | -- | 新增 `text` 事件（LLM Token 级逐字流式推送） |
| v3.0 | -- | 双重工具拦截（ToolEventInterceptor + LoggingToolCallback），两层治理校验 |
