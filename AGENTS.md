# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Project Overview

- **Type**: Maven Multi-Module Spring Boot Project
- **Group ID**: `com.nbcb`
- **Artifact ID**: `ai-agent-project`
- **Java Version**: 17
- **Base Package**: `com.nbcb`

### Modules

| Module | Port | Transport | Description |
|--------|------|-----------|-------------|
| `mcp-service` | 8081 | WebFlux STREAMABLE | MCP Tool Server — `@Tool` 注解暴露工具，Extensions Registry 注册到 Nacos |
| `agent-server` | 8082 | WebFlux STREAMABLE | Agent Server — ReactAgent + DeepSeek，`/chat` API + Skill Generation |

### Architecture

```
Nacos 3.2.2 (AI Registry)
    ↕                         ↕
mcp-service              agent-server
├─ WeatherTool           ├─ SkillRegistry (classpath:skills/*.md → SkillsAgentHook)
├─ CalculatorTool        ├─ AgentConfig (ReactAgent + 5 治理 Hook)
├─ Extensions Registry   ├─ governance/ (限流降级：超时/次数/Token/空转/工具)
└─ 注册到 Nacos ──────────→ Distributed Client (Nacos 发现 MCP 工具)
                               └─ STREAMABLE ──→ mcp-service:8081/mcp
```

- **ReactAgent**: `com.alibaba.cloud.ai.graph.agent.ReactAgent` — ReAct 模式 (Reasoning + Acting)
- **MCP Server**: WebFlux ASYNC STREAMABLE (`spring-ai-starter-mcp-server-webflux`)
- **MCP 注册**: Extensions `spring-ai-alibaba-starter-mcp-registry` (1.1.2.1)
- **MCP 发现**: Extensions `spring-ai-alibaba-starter-mcp-distributed` (1.1.2.1)
- **MCP 配置前缀**: `spring.ai.alibaba.mcp.nacos.*` (NOT `spring.cloud.nacos.*`)
- **分布式客户端 Bean**: `@Qualifier("distributedAsyncToolCallback")` (ASYNC 模式)
- **Skill 注入**: `SkillsAgentHook` + `SkillRegistry(AbstractSkillRegistry)` — LLM 通过 `read_skill` 工具按需加载
- **Skill 来源**: `classpath:skills/*.md`（不再依赖 Nacos Config）
- **工具治理**: 两层校验 — `McpToolRegistrar`（注册时过滤）+ `ToolGovernanceInterceptor`（运行时校验）
- **Agent 治理**: 5 个 Hook — `SessionTimeoutBudget` / `ModelCallLimit` / `TokenBudget` / `LoopDetect` / `ToolAvailability`

## Package Structure

```
agent-server/src/main/java/com/nbcb/agent/
├─ AgentServerApplication.java
├─ config/          # 配置类（AgentConfig 为核心）
├─ constant/        # 常量（PromptConstant）
├─ controller/      # REST API（ChatController, SkillGenerationController, MetricsController）
├─ domain/          # DTO/领域对象（ChatRequest, StreamEvent, RequestContext 等）
├─ exception/       # 异常类 + GlobalExceptionHandler
├─ governance/      # Agent 治理（限流降级 + 工具开关）
│  ├─ AgentGovernanceProperties.java  # @ConfigurationProperties("agent-governance")
│  ├─ ToolGovernanceProperties.java   # @ConfigurationProperties("tool-governance")
│  ├─ ToolGovernanceInterceptor.java  # 第二层运行时工具校验
│  ├─ ToolGovernanceWrapper.java      # ToolCallback 装饰器
│  ├─ McpToolRegistrar.java           # 第一层注册时工具过滤
│  ├─ SessionTimeoutBudgetHook.java   # 会话超时治理
│  ├─ ModelCallLimitHook.java         # 模型调用次数限制
│  ├─ TokenBudgetHook.java            # Token 预算控制
│  ├─ LoopDetectHook.java             # 空转检测
│  └─ ToolAvailabilityHook.java       # 工具可用性注入
├─ health/          # Actuator 健康指示器
├─ metric/          # Micrometer 监控指标（AgentMetrics）
├─ service/         # 业务服务层
│  ├─ AgentService.java               # 对话编排（核心）
│  ├─ AgentStreamService.java         # SSE 流式对话
│  ├─ PromptService.java              # 提示词管理（classpath:prompt/*.md）
│  ├─ SkillGenerationGraphService.java # Skill 生成流水线
│  ├─ SkillValidator.java             # Skill 格式校验
│  └─ stream/ + tool/                 # 流式处理 + 工具解析
├─ skill/           # 技能系统
│  ├─ SkillRegistry.java              # 继承 AbstractSkillRegistry，classpath 加载
│  ├─ LoggingToolCallback.java        # 工具调用日志/SSE 推送装饰器
│  └─ UnprefixedToolCallbackProvider.java # 去 m_s_ 前缀
└─ util/            # 工具类
```

## Build & Run Commands

```bash
# Build all modules (skip tests)
mvn clean package -DskipTests

# Build with tests
mvn clean verify

# Build a single module
mvn -pl mcp-service clean package
mvn -pl agent-server clean package

# Run a specific app
mvn -pl mcp-service spring-boot:run
mvn -pl agent-server spring-boot:run

# Run tests for agent-server
mvn -pl agent-server test
```

## Key Dependencies

### Parent POM managed BOMs

| BOM | Version | Notes |
|-----|---------|-------|
| spring-boot-starter-parent | 3.5.8 | |
| spring-cloud-dependencies | 2025.0.0 | |
| spring-cloud-alibaba-dependencies | 2025.0.0.0 | |
| spring-ai-bom | 1.1.2 | |
| spring-ai-alibaba-bom | 1.1.2.2 | agent-framework 等 |
| spring-ai-alibaba-extensions-bom | **1.1.2.1** | MCP Registry + Distributed Client |
| nacos-client (override) | 3.2.2 | 覆盖 SCA BOM 中的 3.0.3 |

### Module-specific dependencies

| Module | Dependency | Purpose |
|--------|-----------|---------|
| mcp-service | `spring-ai-starter-mcp-server-webflux` | WebFlux MCP Server (ASYNC STREAMABLE) |
| mcp-service | `spring-ai-alibaba-starter-mcp-registry` | 注册工具到 Nacos AI Registry |
| agent-server | `spring-ai-starter-mcp-client-webflux` | MCP Client 传输层 |
| agent-server | `spring-ai-alibaba-starter-mcp-distributed` | 从 Nacos 发现 MCP Server + 负载均衡 |
| agent-server | `spring-ai-starter-model-openai` | DeepSeek (OpenAI 兼容) |
| agent-server | `spring-ai-alibaba-agent-framework` | ReactAgent + SkillsAgentHook + AbstractSkillRegistry |

### Extensions 仓库

Extensions artifacts 发布在 Sonatype OSS，需要配置仓库：
```xml
<repository>
    <id>sonatype</id>
    <url>https://oss.sonatype.org/content/groups/public/</url>
</repository>
```

## Project Conventions

- All code comments in **Chinese** (详细中文注释)
- Use **Lombok** (`@Slf4j`, `@Data`, `@Builder`)
- **Semantic versioning**: bump PATCH on each change
- Layer separation: `controller` / `service` / `config` / `governance` / `skill` / `domain` / `exception` / `metric` / `util`
- Maven artifact name = parent directory name
- DeepSeek Chat Model via OpenAI compatibility (`spring.ai.openai.*`)
- Never use `@EnableDiscoveryClient` — auto-configured by Extensions starters
- MCP 工具从 Nacos 拉取（非直连 SSE URL）

## Configuration Notes

- **API Key**: `DEEPSEEK_API_KEY` 环境变量（不硬编码在 application.yml 中）
- **MCP 协议**: mcp-service 和 agent-server 都使用 STREAMABLE (ASYNC)
- **Nacos namespace**: `public`（默认），通过 `NACOS_NAMESPACE` 环境变量覆盖
- **MCP 注册**: `mcp-service` 启动时自动注册到 Nacos AI Registry
- **MCP 发现**: `agent-server` 启动时从 Nacos 自动发现 mcp-service → 建立 STREAMABLE 连接
- **工具命名**: 分布式客户端会自动给工具名加前缀（如 `m_s_getWeatherByCity`），`UnprefixedToolCallbackProvider` 去除前缀
- **Skill 加载**: `SkillRegistry` 扫描 `classpath:skills/*.md`，通过 `SkillsAgentHook` 注入
- **治理配置**: `agent-governance.*`（Agent 限流降级）/ `tool-governance.*`（工具开关）在 application.yml 中配置

## Tests

- **测试文件**: 3 个（8 个测试用例）
  - `AgentServerApplicationTests` — Spring Context 加载
  - `AgentServerIntegrationTest` — Bean 存在性集成测试
  - `AgentServiceTest` — 对话服务单元测试
- 使用 `SimpleMeterRegistry` 构建真实 `AgentMetrics`，避免 mock Counter 返回 null

## Frontend

- **位置**: `agent-server/frontend/`
- **框架**: Vue 3 + TypeScript + Vite
- **构建**: `npm run build`（输出到 `src/main/resources/static/`）
- **XSS 防护**: `marked` 渲染 Markdown → `DOMPurify.sanitize()` 消毒

## Infrastructure

- `Dockerfile` — 统一 Dockerfile（ARG MODULE + PORT 支持所有模块）
- `docker-compose.yml` — Nacos 3.2.2 + mcp-service + agent-server
