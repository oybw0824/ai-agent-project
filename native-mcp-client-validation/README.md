# 原生 MCP Client 2025-11-25 验证工程

本工程用于独立验证 AI 网关的 MCP Streamable HTTP 接入，不依赖仓库中的父 POM，也不会启动现有 `agent-server` 或 `mcp-service`。

相邻的 `native-mcp-server-validation` 是按接入文档实现的本地 AI 网关，可以直接提供 `/mcp` 和 `/mcp/mcp-service`，并且不校验 JWT，适合本地端到端验证。

验证链路：

```text
ReactAgent
    ↓ ToolCallback
AsyncMcpToolCallbackProvider
    ↓
原生 McpAsyncClient（MCP 2025-11-25）
    ↓ Streamable HTTP + x-client-token
AI 网关 /mcp
```

## 验证内容

- 严格协商 MCP `2025-11-25`，不允许降级。
- 按顺序完成 `initialize`、`notifications/initialized`、`tools/list` 和 `tools/call`。
- 每次请求携带 `x-client-token`。
- 自动处理 `MCP-Protocol-Version`、`MCP-Session-Id`、JSON 响应和 SSE 响应。
- 支持直接查看/调用 MCP 工具，并支持 ReactAgent 自动选择工具。
- 启动时连接网关并加载工具；初始化失败、鉴权失败或版本不匹配时直接终止启动。

## 环境要求

- JDK 17
- Maven 3.9+
- 可以访问的 AI 网关
- 是否需要 JWT 由 AI 网关配置决定；HTTP 工具后端本身不校验 JWT
- 有效的 DeepSeek API Key

PowerShell 配置示例：

```powershell
$env:AI_GATEWAY_BASE_URL = "http://localhost:11001"
$env:AI_GATEWAY_MCP_ENDPOINT = "/mcp"
$env:AI_GATEWAY_MCP_APPLICATION = "mcp-service"
$env:AI_GATEWAY_AUTH_ENABLED = "true"
$env:AI_GATEWAY_CLIENT_TOKEN = "你的JWT"

$env:DEEPSEEK_API_KEY = "你的DeepSeek API Key"
$env:DEEPSEEK_BASE_URL = "https://api.deepseek.com"
$env:DEEPSEEK_MODEL = "deepseek-chat"
```

连接未启用 JWT 校验的本地 AI 网关时关闭鉴权，无需配置 Token：

```powershell
$env:AI_GATEWAY_AUTH_ENABLED = "false"
$env:AI_GATEWAY_CLIENT_TOKEN = ""
```

可选配置：

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `SERVER_PORT` | `8090` | 验证服务端口 |
| `AI_GATEWAY_BASE_URL` | `http://127.0.0.1:11001` | AI 网关基础地址 |
| `AI_GATEWAY_MCP_ENDPOINT` | `/mcp` | 网关全量 MCP endpoint；当前网关只允许在此执行 `tools/call` |
| `AI_GATEWAY_MCP_APPLICATION` | `mcp-service` | 从全量工具中选择的应用名；Agent 侧自动隐藏 `应用名___` 前缀 |
| `AI_GATEWAY_AUTH_ENABLED` | `true` | 是否发送并强制校验客户端 Token 配置 |
| `AI_GATEWAY_REQUEST_TIMEOUT` | `60s` | 工具查询和调用超时 |
| `AI_GATEWAY_INITIALIZATION_TIMEOUT` | `30s` | MCP 初始化超时 |
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com` | OpenAI 兼容模型地址 |
| `DEEPSEEK_MODEL` | `deepseek-chat` | 模型名称 |

## 构建和启动

进入本目录执行：

```powershell
mvn clean verify
mvn spring-boot:run
```

启动成功时应出现类似日志：

```text
原生 MCP Client 初始化成功：http://localhost:11001/mcp，协议版本：2025-11-25
启动自检完成：从 AI 网关获取到 2 个 MCP 工具
ReactAgent 加载原生 MCP 工具完成，共 2 个
```

## 接口验证

### 可视化测试页面

客户端启动后直接打开：

```text
http://localhost:8090/
```

页面支持查看网关工具、按 Input Schema 直接调用工具、发送 ReactAgent 对话以及查看请求耗时和原始响应。

### 查询工具

```powershell
curl.exe http://localhost:8090/api/v1/mcp/tools
```

### 直接调用工具

```powershell
curl.exe -X POST "http://localhost:8090/api/v1/mcp/tools/calculate/call" `
  -H "Content-Type: application/json" `
  -d '{"arguments":{"expression":"(12+8)*3"}}'
```

如果工具执行完成但业务执行失败，接口仍返回 MCP 结果，并通过 `error: true` 标识；网关、协议或 JSON-RPC 调用异常返回 HTTP 502。

### 让 Agent 自动调用工具

```powershell
curl.exe -X POST "http://localhost:8090/api/v1/agent/chat" `
  -H "Content-Type: application/json" `
  -d '{"question":"请调用计算工具计算 (12+8)*3"}'
```

## 常见问题

### `AI_GATEWAY_CLIENT_TOKEN` 不能为空

没有配置 JWT。设置环境变量后重新启动。

### HTTP 401 或 403

Token 缺失、过期、签名错误，或调用方没有对应 MCP 应用权限。更新 JWT 后重启验证工程。

### 协议版本不匹配

验证工程只接受 `2025-11-25`。AI 网关必须在 `initialize` 响应中返回同一版本，不能返回 `2025-06-18` 等旧版本。

### 手工 `tools/list` 成功，但工程启动失败

严格 MCP 客户端必须先执行初始化。检查网关是否支持：

```text
initialize
notifications/initialized
MCP-Protocol-Version
MCP-Session-Id（网关启用会话时）
```

### 工具修改后 Agent 没有更新

工具列表和 ReactAgent 工具集合在启动时创建快照。修改网关工具后重启验证工程。

### Agent 接口调用失败

先调用 `/api/v1/mcp/tools` 和直接工具调用接口，确认 MCP 链路正常；然后检查 `DEEPSEEK_API_KEY`、模型名称和模型服务地址。
