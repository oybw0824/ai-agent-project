# Agent 限流降级设计文档

**范围**：Agent 编排层限流、防循环、防超时、防 Token 失控。  
**目标**：约束单次会话和单次推理循环，避免模型自主推理失控。

---

## 1. 设计说明

Agent 层只处理“单次会话失控”问题，不负责下游系统总容量保护。下游保护由 MCP 工具治理层完成。

核心控制点：

| 控制项 | 目的 |
|---|---|
| 最大迭代次数 | 防止 ReAct 无限循环 |
| 会话总超时预算 | 防止多轮模型和工具调用叠加超时 |
| 模型调用次数限制 | 防止单会话频繁请求模型 |
| Token 预算 | 防止单会话占用过多推理资源 |
| 空转检测 | 识别重复调用、交替调用、降级后重试 |
| 工具可用性注入 | 告知模型哪些工具不可用或已降级 |

---

## 2. 总体流程

```text
用户请求
  -> 初始化会话状态
  -> 执行 Agent Hook
      -> SessionTimeoutBudgetHook
      -> ModelCallLimitHook
      -> TokenBudgetHook
      -> ToolAvailabilityHook
      -> LoopDetectHook
  -> 模型推理
  -> 工具调用
  -> 工具结果写回会话状态
  -> 下一轮 Hook 判断是否继续
```

工具治理层返回的限流、熔断、降级、不可重试结果必须写回会话状态，供下一轮 Hook 判断。

---

## 3. Hook 设计

### 3.1 Hook 顺序

```text
SessionTimeoutBudgetHook
  -> ModelCallLimitHook
  -> TokenBudgetHook
  -> ToolAvailabilityHook
  -> LoopDetectHook
  -> 调用模型
```

### 3.2 Agent 构建示例

```java
ReactAgent agent = ReactAgent.builder()
    .name("credit_agent")
    .chatModel(privateChatModel)
    .tools(availableTools)
    .maxIterations(8)
    .hooks(List.of(
        new SessionTimeoutBudgetHook(timeoutBudgetService),
        new ModelCallLimitHook(modelCallLimitService),
        new TokenBudgetHook(tokenBudgetService),
        new ToolAvailabilityHook(toolStateService),
        new LoopDetectHook(loopDetectService)
    ))
    .build();
```

---

## 4. 核心实现

### 4.1 会话超时预算

```java
public class SessionTimeoutBudgetHook implements ModelHook {

    private final TimeoutBudgetService timeoutBudgetService;

    @Override
    public Map<String, Object> beforeModel(OverAllState state, RunnableConfig config) {
        String sessionId = SessionState.getSessionId(state);
        TimeoutBudget budget = timeoutBudgetService.getBudget(sessionId);

        if (budget.isExceeded()) {
            throw new AgentEarlyTerminationException("会话处理时间已达到上限");
        }

        if (budget.remainingMillis() < budget.minimumNextStepMillis()) {
            throw new AgentEarlyTerminationException("剩余处理时间不足，停止继续推理");
        }

        return Map.of("remainingTimeMillis", budget.remainingMillis());
    }
}
```

### 4.2 模型调用次数限制

```java
public class ModelCallLimitHook implements ModelHook {

    private final ModelCallCounter counter;
    private final AgentGovernanceConfig config;

    @Override
    public Map<String, Object> beforeModel(OverAllState state, RunnableConfig runnableConfig) {
        String sessionId = SessionState.getSessionId(state);
        int used = counter.incrementAndGet(sessionId);
        int limit = config.getModelCallLimit(SessionState.getRiskLevel(state));

        if (used > limit) {
            throw new AgentEarlyTerminationException("模型调用次数超过限制");
        }

        return Map.of("modelCallUsed", used, "modelCallLimit", limit);
    }
}
```

### 4.3 Token 预算

```java
public class TokenBudgetHook implements ModelHook {

    private final TokenBudgetService tokenBudgetService;

    @Override
    public Map<String, Object> beforeModel(OverAllState state, RunnableConfig config) {
        String sessionId = SessionState.getSessionId(state);
        TokenUsage usage = tokenBudgetService.estimateCurrentUsage(state);
        TokenBudget budget = tokenBudgetService.getBudget(sessionId);

        if (usage.totalTokens() > budget.maxTokens()) {
            throw new AgentEarlyTerminationException("会话 Token 消耗超过预算");
        }

        return Map.of("tokenUsed", usage.totalTokens(), "tokenLimit", budget.maxTokens());
    }
}
```

### 4.4 空转检测

检测三种模式：

| 模式 | 示例 | 处理 |
|---|---|---|
| 连续重复 | A、A、A，参数相同 | 终止当前推理 |
| 交替重复 | A、B、A、B、A、B | 终止当前推理 |
| 降级后重试 | `retryable:false` 后仍调用同一工具 | 终止该工具路径 |

```java
public class LoopDetectHook implements ModelHook {

    private static final int STRICT_REPEAT_THRESHOLD = 3;
    private static final int PATTERN_WINDOW = 8;

    @Override
    public Map<String, Object> beforeModel(OverAllState state, RunnableConfig config) {
        List<ToolCallRecord> recent = ToolCallHistory.getRecentCalls(state, PATTERN_WINDOW);

        if (isStrictRepeating(recent, STRICT_REPEAT_THRESHOLD)) {
            throw new AgentEarlyTerminationException("检测到连续重复工具调用");
        }

        if (isAlternatingPattern(recent)) {
            throw new AgentEarlyTerminationException("检测到交替模式空转");
        }

        if (hasRepeatedCallAfterNonRetryableResult(state)) {
            throw new AgentEarlyTerminationException("工具已返回不可重试结果，停止继续尝试");
        }

        return Map.of();
    }
}
```

工具调用比较使用规范化签名：

```text
signature = toolName + canonicalJson(arguments) + keyBusinessContext
```

---

## 5. 工具可用性注入

每轮模型调用前，将不可用工具和降级工具注入上下文。

```json
{
  "unavailableTools": [
    {
      "toolName": "trigger-risk-evaluation",
      "reason": "CIRCUIT_OPEN",
      "retryable": false
    }
  ],
  "degradedTools": [
    {
      "toolName": "query-credit-application",
      "dataFreshness": "CACHED"
    }
  ]
}
```

处理规则：

- `DISABLED`：从工具列表过滤。
- `CIRCUIT_OPEN` / `retryable:false`：不允许模型继续调用同一路径。
- `DEGRADED`：可调用，但必须提示数据新鲜度。

---

## 6. 配置示例

```yaml
agent-governance:
  max-iterations:
    default: 8
    l4-high-risk: 4

  session-timeout:
    default-budget-ms: 15000
    high-risk-budget-ms: 10000
    minimum-next-step-ms: 1500

  model-call-limit:
    default-limit: 8
    l4-high-risk-limit: 4

  token-budget:
    default-max-tokens: 12000
    l4-high-risk-max-tokens: 8000
    warning-ratio: 0.8

  loop-detect:
    strict-repeat-threshold: 3
    pattern-window: 8
    alternating-repeat-threshold: 3
    stop-after-non-retryable-result: true
```

---

## 7. 实施顺序

1. 配置 `maxIterations`。
2. 实现模型调用次数限制。
3. 实现 Token 预算。
4. 实现会话端到端超时预算。
5. 实现连续重复调用检测。
6. 接入工具治理结果写回会话状态。
7. 实现 `retryable:false` 联动终止。
8. 实现交替模式空转检测。
9. 实现工具可用性注入。
