# MCP 工具开关治理设计文档

**目标**：两层校验控制工具是否可被调用——第一层在从 Nacos 拉取 MCP 工具注册信息时校验工具是否启用，未启用则不注册给模型；第二层在工具调用前再次校验配置状态，兜底拒绝。开关状态统一配置在 yaml 文件中。

---

## 1. 状态定义

| 状态 | 行为 |
|---|---|
| ENABLED | 正常放行调用 |
| DISABLED | 模型不可见该工具；若仍被调用，直接拒绝，不执行实际逻辑 |

---

## 2. 配置模型

工具开关状态配置在本地 yaml 文件（如 `application.yml` 或独立的 `tool-governance.yml`），通过 `@ConfigurationProperties` 绑定加载，两层校验共用同一份配置对象。

```yaml
tool-governance:
  tools:
    query-credit-application:
      status: ENABLED

    trigger-risk-evaluation:
      status: ENABLED

    adjust-credit-limit:
      status: DISABLED   # 管理员下线，注册时不放行 + 调用时兜底拒绝
```

```java
@ConfigurationProperties(prefix = "tool-governance")
public class ToolGovernanceProperties {

    private Map<String, ToolPolicy> tools = new HashMap<>();

    public Map<String, ToolPolicy> getTools() {
        return tools;
    }

    public static class ToolPolicy {
        private ToolStatus status = ToolStatus.DISABLED; // 默认未启用，避免漏配置误放行
        public ToolStatus getStatus() { return status; }
        public void setStatus(ToolStatus status) { this.status = status; }
    }
}

public enum ToolStatus {
    ENABLED, DISABLED
}
```

---

## 3. 第一层：工具注册拉取时校验（源头过滤）

从 Nacos 拉取 MCP 工具注册列表时，结合本地 yaml 中的工具状态配置做过滤，未启用的工具不注册到可用工具集合中，模型自然看不到、也无法调用。

```java
public class McpToolRegistrar {

    private final ToolGovernanceProperties governanceProperties; // 从 yaml 加载
    private final NacosMcpRegistryClient nacosClient;             // 从 Nacos 拉取工具注册信息

    /**
     * 拉取 Nacos 注册的工具列表，结合 yaml 配置的状态过滤后再注册给 Agent。
     */
    public List<ToolCallback> loadAvailableTools() {
        List<McpToolMetadata> registeredTools = nacosClient.fetchToolRegistrations();

        return registeredTools.stream()
            .filter(tool -> isEnabled(tool.getToolName()))
            .map(this::toToolCallback)
            .toList();
    }

    private boolean isEnabled(String toolName) {
        ToolGovernanceProperties.ToolPolicy policy = governanceProperties.getTools().get(toolName);
        // yaml 未配置该工具时，policy 为 null，按未启用处理，避免遗漏配置导致误放行
        return policy != null && policy.getStatus() == ToolStatus.ENABLED;
    }

    private ToolCallback toToolCallback(McpToolMetadata tool) {
        return ToolCallback.from(tool);
    }
}
```

```java
llmNodeBuilder.toolCallbacks(mcpToolRegistrar.loadAvailableTools());
```

要点：拉取动作发生在 Agent 启动或工具列表刷新时（非每次调用都拉取），只依赖本地 yaml 中的状态做判断，不需要额外依赖 Nacos 侧配置。

---

## 4. 第二层：调用前校验（兜底拒绝）

即使工具列表未及时刷新、或并发会话仍持有旧工具列表，调用时也必须再次读取 yaml 配置检查状态并拒绝。

```java
public class ToolGovernanceInterceptor implements ToolInterceptor {

    private final ToolGovernanceProperties governanceProperties; // 与第一层共用同一份配置

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        ToolGovernanceProperties.ToolPolicy policy = governanceProperties.getTools().get(request.getToolName());

        if (policy == null || policy.getStatus() == ToolStatus.DISABLED) {
            return ToolCallResponse.rejected(
                "TOOL_DISABLED",
                "工具[" + request.getToolName() + "]已下线，暂不可用"
            );
        }

        return handler.handle(request);
    }
}
```

---

## 5. 返回结构

```json
{
  "success": false,
  "reason": "TOOL_DISABLED",
  "userMessage": "该功能当前已下线，暂不可用",
  "retryable": false,
  "toolName": "adjust-credit-limit"
}
```

---

## 6. 落地要点

1. **双重校验**：第一层（注册拉取时过滤）+ 第二层（调用前兜底拒绝），两者共用同一份 `ToolGovernanceProperties`，缺一不可——第一层减少模型看到已下线工具的机会，第二层防止工具列表未及时刷新时被绕过。
2. **配置来源**：状态配置维护在 yaml 文件中，随应用启动加载；如需不重启生效，可结合 Spring Cloud 的 `@RefreshScope` 或监听文件变更重新绑定 `ToolGovernanceProperties`。
3. **默认值安全**：yaml 中未显式配置的工具，`status` 默认按 `DISABLED` 处理，避免遗漏配置导致误放行。
4. **两层共用配置对象**：避免第一层和第二层各自维护一份状态判断逻辑，产生不一致。
