# 本地 AI 网关 MCP 验证工程

本工程模拟《AI网关接入文档》中的 AI 网关接口，供相邻的 `native-mcp-client-validation` 使用。工具直接在网关工程内部执行，不依赖 Nacos、Higress或其他 MCP Server。

## 接口与行为

| 地址 | 方法 | 行为 |
|---|---|---|
| `/mcp` | `POST` | 查询全部工具或调用完整名称工具 |
| `/mcp/mcp-service` | `POST` | 查询或调用 `mcp-service` 的工具 |
| `/mcp`、`/mcp/mcp-service` | `DELETE` | 关闭 MCP 会话 |
| `/api/v1/validation/status` | `GET` | 查看验证网关状态 |

支持的 JSON-RPC 方法：

- `initialize`
- `notifications/initialized`
- `ping`
- `tools/list`
- `tools/call`

协议版本严格为 `2025-11-25`。初始化响应返回 `MCP-Session-Id`，后续响应返回 `MCP-Protocol-Version`。

`x-client-token` 可以携带、缺省或填写任意内容，本验证网关都不会校验。

## 工具命名

访问 `/mcp` 时：

- `mcp-service___calculate`
- `mcp-service___getWeatherByCity`

访问 `/mcp/mcp-service` 时：

- `calculate`
- `getWeatherByCity`

这与接入文档中的全量接口和指定应用接口命名规则一致。

## 构建与启动

```powershell
cd D:\project\ai-agent-project\native-mcp-server-validation
mvn clean verify
mvn spring-boot:run
```

默认监听 `http://localhost:11001`。

## 按文档直接调用

查询指定应用工具：

```powershell
$body = @{
  jsonrpc = "2.0"
  id = "list-1"
  method = "tools/list"
} | ConvertTo-Json -Compress

Invoke-RestMethod `
  -Uri "http://localhost:11001/mcp/mcp-service" `
  -Method Post `
  -ContentType "application/json; charset=utf-8" `
  -Headers @{"x-client-token" = "任意内容"} `
  -Body $body
```

调用计算工具：

```powershell
$body = @{
  jsonrpc = "2.0"
  id = "call-1"
  method = "tools/call"
  params = @{
    name = "calculate"
    arguments = @{expression = "(12+8)*3"}
  }
} | ConvertTo-Json -Depth 5

Invoke-RestMethod `
  -Uri "http://localhost:11001/mcp/mcp-service" `
  -Method Post `
  -ContentType "application/json; charset=utf-8" `
  -Body $body
```

调用全量接口时使用完整工具名：

```powershell
$body = @{
  jsonrpc = "2.0"
  id = "call-all-1"
  method = "tools/call"
  params = @{
    name = "mcp-service___getWeatherByCity"
    arguments = @{city = "北京"}
  }
} | ConvertTo-Json -Depth 5

Invoke-RestMethod `
  -Uri "http://localhost:11001/mcp" `
  -Method Post `
  -ContentType "application/json; charset=utf-8" `
  -Body $body
```

## 供原生 MCP Client 调用

先启动本工程，再启动客户端：

```powershell
$env:AI_GATEWAY_BASE_URL = "http://localhost:11001"
$env:AI_GATEWAY_MCP_ENDPOINT = "/mcp"
$env:AI_GATEWAY_MCP_APPLICATION = "mcp-service"
$env:AI_GATEWAY_AUTH_ENABLED = "false"
$env:AI_GATEWAY_CLIENT_TOKEN = ""

cd D:\project\ai-agent-project\native-mcp-client-validation
mvn spring-boot:run
```

客户端启动后验证：

```powershell
curl.exe http://localhost:8090/api/v1/mcp/tools

curl.exe -X POST "http://localhost:8090/api/v1/mcp/tools/calculate/call" `
  -H "Content-Type: application/json" `
  -d '{"arguments":{"expression":"(12+8)*3"}}'
```

预期客户端发现两个工具，计算调用返回包含 `60.0` 的 MCP Tool 结果。
