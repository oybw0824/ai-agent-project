
# ai-agent-project

基于 **Spring AI Alibaba + Nacos 3.2.2 + DeepSeek** 的 AI Agent 微服务项目。

## 项目架构

```
┌──────────────────────────────────────────────────────────┐
│                     Nacos 3.2.2                           │
│  ┌──────────────────┐  ┌──────────────────────────────┐  │
│  │   AI Registry    │  │        Config Center          │  │
│  │  (MCP/Skill注册)  │  │  (spring.ai.alibaba.*)       │  │
│  └────────┬─────────┘  └──────────────┬───────────────┘  │
└───────────┼───────────────────────────┼──────────────────┘
            │ 注册                       │ 发现
            ▼                            ▼
┌───────────────────┐          ┌───────────────────────────┐
│    mcp-service    │          │       agent-server         │
│    (端口 8081)     │          │        (端口 8082)          │
│                   │ STREAMABLE│                           │
│  WeatherTool      │◄─────────│  NacosSkillLoader (技能)    │
│  CalculatorTool   │          │  Distributed Client (工具)  │
│  Extensions       │          │  ReactAgent + DeepSeek     │
│  Registry         │          │  /chat /skills /tools API  │
└───────────────────┘          └───────────────────────────┘
```

## 子模块

| 模块 | 端口 | 传输 | 说明 |
|------|------|------|------|
| `mcp-service` | 8081 | WebFlux STREAMABLE | MCP 工具服务端，Extensions Registry 注册到 Nacos |
| `agent-server` | 8082 | WebFlux STREAMABLE | 智能体服务端，Nacos 分布式发现 MCP 工具 + AiService SDK 加载技能 |

## 技术栈

| 组件 | 版本 |
|------|------|
| JDK | 17 |
| Spring Boot | 3.5.8 |
| Spring AI | 1.1.2 |
| Spring AI Alibaba | 1.1.2.2 |
| Spring AI Alibaba Extensions | 1.1.2.1 |
| Nacos Server | 3.2.2 |
| Nacos Client | 3.2.2 |
| Chat Model | DeepSeek (deepseek-chat) |

## 快速启动

### 1. 环境准备

- JDK 17+
- Docker（用于运行 Nacos）
- DeepSeek API Key（从 [platform.deepseek.com](https://platform.deepseek.com) 获取）

### 2. Docker Compose 一键启动

```bash
export DEEPSEEK_API_KEY="sk-xxxxxxxxxxxxxxxx"
docker compose up -d
```

### 3. 本地开发模式

```bash
# 终端 1：启动 Nacos
docker compose up -d nacos

# 终端 2：启动 mcp-service
mvn -pl mcp-service spring-boot:run

# 终端 3：启动 agent-server
export DEEPSEEK_API_KEY="sk-xxxxxxxxxxxxxxxx"
mvn -pl agent-server spring-boot:run
```

### 4. 接口验证

```bash
# 查看已加载技能
curl http://localhost:8082/skills

# 查看已发现 MCP 工具
curl http://localhost:8082/tools

# 对话测试
curl -X POST http://localhost:8082/chat \
  -H "Content-Type: application/json" \
  -d '{"question":"北京今天天气怎么样？","skillId":"general-assistant"}'
```

## 构建与测试

```bash
# 编译
mvn clean compile

# 测试
mvn test
```
