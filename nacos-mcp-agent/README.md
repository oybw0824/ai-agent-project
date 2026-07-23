# Nacos MCP Agent（公共表驱动版）

版本 `1.0.18`。本模块同时提供本地 MCP Server、Nacos 注册和数据库驱动的 ReactAgent。

## 启动模型

项目启动时读取全部 `PUBLISHED` 且 `IS_DELETE=0` 的配置，并按下面关系一次性构建 ReactAgent：

```text
AI_AGENT
  → AI_AGENT_NODE
  → AI_MODEL
  → AI_AGENT_NODE_SKILL_BINDING
  → AI_SKILL
  → AI_TOOL_BINDING
  → AI_TOOL
  → 启动时 ToolCallback 快照
  → ReactAgent
```

对话请求不会重新查询配置、发现工具或创建 ReactAgent，只按 `agentId` 复用启动时实例。数据库配置、Skill 文件或 Nacos 工具发生变化后，需要重启应用才能生效。

当前验证工程支持一个 Agent 配置一个有效 DIRECT 节点；缺少模型、重复节点、Tool 无法匹配或默认 Agent 不存在时，应用会启动失败并输出具体配置项。Skill 文件缺失时会记录告警并跳过该 Skill。

## 表与代码职责

| 表 | 运行职责 |
|---|---|
| `AI_MODEL` | Provider、模型名、Endpoint、API Key |
| `AI_AGENT` | Agent 标识、名称和发布状态 |
| `AI_AGENT_NODE` | 提示词、模型、temperature、max token |
| `AI_SKILL` | Skill Code 和发布状态 |
| `AI_AGENT_NODE_SKILL_BINDING` | 控制节点能看到哪些 Skill |
| `AI_TOOL` | 通过 `MCP_CODE + TOOL_CODE` 定位本地或 Nacos 工具 |
| `AI_TOOL_BINDING` | 控制 Skill 或节点可以获得哪些工具 |

H2 验证结构和演示数据位于：

- `src/main/resources/sql/h2-schema.sql`
- `src/main/resources/sql/h2-data.sql`

演示数据发布 `credit-agent`，绑定 `enterprise-credit-query` 和 `local-weather-query`。提示词字段保存 `classpath:prompt/system-prompt.md`，因此提示词正文仍由 resources 管理。

## 本地启动

H2 演示数据中的 `AI_MODEL.CREDENTIAL_REF` 使用 `env:DEEPSEEK_API_KEY` 引用环境变量。真实对话前只需要在启动应用的同一终端设置该环境变量，不要将真实密钥提交到代码仓库。

```powershell
$env:DEEPSEEK_API_KEY = "你的 API Key"
$env:NACOS_ADDR = "127.0.0.1:8848"
$env:MCP_REGISTER_ENABLED = "true"
$env:MCP_DISCOVERY_ENABLED = "false"
mvn -pl nacos-mcp-agent spring-boot:run
```

打开 `http://127.0.0.1:8083/`。页面会先调用 `GET /api/v1/agents`，展示启动时已构建的 Agent。

```http
POST /api/v1/agent/chat
Content-Type: application/json

{
  "agentId": "credit-agent",
  "question": "请查询统一社会信用代码 91110000MA01NB001X 的企业征信信息"
}
```

`agentId` 可省略，此时使用数据库中第一个已发布 Agent。

## 接入 Oracle

使用实际 Oracle 表时关闭演示初始化，并配置数据源：

```powershell
$env:AGENT_DB_URL = "jdbc:oracle:thin:@//127.0.0.1:1521/ORCLPDB1"
$env:AGENT_DB_USERNAME = "AI_AGENT"
$env:AGENT_DB_PASSWORD = "数据库密码"
$env:AGENT_DB_DRIVER = "oracle.jdbc.OracleDriver"
$env:AGENT_DB_INIT_MODE = "never"
```

部署环境需要提供 Oracle JDBC Driver。表名、字段名和约束按 `公共Agent表结构清单-V1.xlsx` 建表；应用查询使用的就是清单中的七张逻辑表名。

`AI_MODEL.CREDENTIAL_REF` 本次直接保存实际 API Key；`MODEL_NAME` 和 `ENDPOINT_URI` 也按数据库原值使用，不解析环境变量名或 Spring 占位符。应用不会在启动日志、Agent 列表或异常响应中输出 API Key。生产环境应通过数据库权限和部署安全限制该字段的读取。

## Nacos 工具匹配

- `AI_TOOL.MCP_CODE=nacos-mcp-agent`：从本地 Java 工具匹配。
- 其他 `MCP_CODE`：从启动时 Nacos 分布式工具快照匹配。
- `TOOL_CODE` 必须与 MCP Tool 名一致。
- 远程工具按服务名前缀精确匹配；不同版本前缀规则不同时，仅允许唯一后缀匹配。

本工程的 `getWeatherByCity` 和 `queryEnterpriseCredit` 仍通过 `/mcp` 暴露并注册到 Nacos，但主 Agent 直接使用本地 ToolCallback，不从 Nacos 拉取自身工具。

## 主要环境变量

| 变量 | 默认值 |
|---|---|
| `SERVER_PORT` | `8083` |
| `AGENT_RUNTIME_ENABLED` | `true` |
| `NACOS_ADDR` | `127.0.0.1:8848` |
| `MCP_SERVER_VERSION` | `1.0.18` |
| `MCP_REGISTER_ENABLED` | `true` |
| `MCP_DISCOVERY_ENABLED` | `false` |
| `AGENT_DB_URL` | H2 内存库 |
| `AGENT_DB_INIT_MODE` | `always`；Oracle 使用 `never` |

## 验证

```powershell
mvn -pl nacos-mcp-agent -am clean verify
```

测试覆盖 Spring Boot 自动装配开关、本地 MCP 工具导出、七表聚合、数据库模型配置、Skill 资源加载，以及每个 ReactAgent/ChatModel 仅在启动时独立构建并在多次对话中复用。
