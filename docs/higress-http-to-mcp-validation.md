# Higress + Nacos HTTP 转 MCP 验证

## 调用链

```text
agent-server
  └─ Streamable MCP → Higress :8080/mcp/mcp-service
                         └─ HTTP → mcp-service:8081/api/tools/*
                                           └─ 注册到 Nacos Naming
```

`mcp-service` 不再是原生 MCP Server，它只提供普通 HTTP API。Nacos 保存 MCP Server 和 Tool 的声明，Higress 负责执行 MCP 到 HTTP 的协议转换。

## 1. 启动基础服务

```bash
docker compose up -d nacos mcp-service higress
```

访问地址：

- Nacos 控制台：`http://localhost:8083`
- Higress 控制台：`http://localhost:8001`
- Higress 网关：`http://localhost:8080`
- HTTP 工具服务：`http://localhost:8081`

先验证 HTTP API：

```bash
curl "http://localhost:8081/api/tools/weather?city=%E5%8C%97%E4%BA%AC"
curl "http://localhost:8081/api/tools/calculate?expression=2%2B3*4"
```

在 Nacos 的“服务管理”中应能看到 `mcp-service` 实例。

## 2. Higress 添加 Nacos 3.x 服务来源

在 Higress 控制台进入“服务来源”，新建 Nacos 3.x 服务来源：

| 配置 | 值 |
|---|---|
| 类型 | `nacos3` |
| 地址 | `nacos` |
| 端口 | `8848` |
| 命名空间 | `public` |
| 服务分组 | `DEFAULT_GROUP` |
| 开启 MCP Server | 是 |
| MCP 路径前缀 | `/mcp` |

Higress 和 Nacos 处于同一 Docker 网络，因此 Nacos 地址使用容器服务名 `nacos`，不要填 `127.0.0.1`。

## 3. Nacos 创建 HTTP 转 MCP Server

进入“MCP 管理 → MCP 列表 → 创建 MCP Server”：

| 配置 | 值 |
|---|---|
| MCP 服务名 | `mcp-service` |
| 协议类型 | `streamable` |
| HTTP 转 MCP | `http` |
| 后端服务 | 使用已有服务 |
| 服务引用 | `mcp-service` / `DEFAULT_GROUP` |
| 服务版本 | `1.0.0` |

## 4. 添加天气 Tool

- Tool 名称：`getWeatherByCity`
- Tool 描述：`查询中国城市的模拟天气，返回温度、湿度、天气和建议`
- 入参：`city`，类型 `string`，必填

协议转换配置：

```json
{
  "requestTemplate": {
    "url": "/api/tools/weather",
    "argsToUrlParam": true,
    "method": "GET"
  },
  "responseTemplate": {
    "body": "{{ . }}"
  },
  "argsPosition": {
    "city": "query"
  }
}
```

## 5. 添加计算 Tool

- Tool 名称：`calculate`
- Tool 描述：`执行数学表达式计算，支持加减乘除和括号`
- 入参：`expression`，类型 `string`，必填

协议转换配置：

```json
{
  "requestTemplate": {
    "url": "/api/tools/calculate",
    "argsToUrlParam": true,
    "method": "GET"
  },
  "responseTemplate": {
    "body": "{{ . }}"
  },
  "argsPosition": {
    "expression": "query"
  }
}
```

点击“发布为最新版本”，不要只点“保存”。

## 6. 启动 Agent 并验证

确认 Higress 的 MCP 连接地址为：

```text
http://localhost:8080/mcp/mcp-service
```

然后启动 Agent：

```bash
docker compose up -d agent-server
```

查看 Agent 已拉取的工具：

```bash
curl http://localhost:8082/api/v1/tools
```

应包含 `getWeatherByCity` 和 `calculate`。最后发起对话：

```bash
curl -X POST http://localhost:8082/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{"question":"请查询北京天气，再计算 (12+8)*3"}'
```

Agent 启动时会建立 MCP 客户端并构建 ReactAgent 工具快照。修改 Nacos 中的 Tool 后，需要重启 `agent-server` 使工具列表生效。
