# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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
| `agent-server` | 8082 | WebFlux STREAMABLE | Agent Server — ReactAgent + DeepSeek，Nacos Distributed Client 发现 MCP 工具，`/chat` API |

### Architecture

```
Nacos 3.2.2 (AI Registry + Config)
    ↕                         ↕
mcp-service              agent-server
├─ WeatherTool           ├─ NacosSkillLoader (技能从 Nacos 加载)
├─ CalculatorTool        ├─ NacosSkillRegistry (→ SkillsAgentHook)
├─ Extensions Registry   ├─ NacosAiClient (Nacos REST API)
└─ 注册到 Nacos ──────────→ Distributed Client (Nacos 发现 MCP 工具)
                               └─ STREAMABLE ──→ mcp-service:8081/mcp
```

- **ReactAgent**: `com.alibaba.cloud.ai.graph.agent.ReactAgent` — ReAct 模式 (Reasoning + Acting)
- **MCP Server**: WebFlux ASYNC STREAMABLE (`spring-ai-starter-mcp-server-webflux`)
- **MCP 注册**: Extensions `spring-ai-alibaba-starter-mcp-registry` (1.1.2.1)
- **MCP 发现**: Extensions `spring-ai-alibaba-starter-mcp-distributed` (1.1.2.1)
- **MCP 配置前缀**: `spring.ai.alibaba.mcp.nacos.*` (NOT `spring.cloud.nacos.*`)
- **分布式客户端 Bean**: `@Qualifier("distributedAsyncToolCallback")` (ASYNC 模式)
- **Skill 注入**: SkillsAgentHook + read_skill 工具（非文本拼接）

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

# Docker Compose (all services: Nacos + mcp-service + agent-server)
docker compose up -d
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
| agent-server | `spring-ai-alibaba-agent-framework` | ReactAgent + SkillsAgentHook |

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
- Layer separation: `controller` / `service` / `config` / `tool` / `domain` / `skill`
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
- **工具命名**: 分布式客户端会自动给工具名加前缀（如 `m_s_getWeatherByCity`）

## Infrastructure

- `Dockerfile` — 统一 Dockerfile（ARG MODULE + PORT 支持所有模块）
- `docker-compose.yml` — Nacos 3.2.2 + mcp-service + agent-server
