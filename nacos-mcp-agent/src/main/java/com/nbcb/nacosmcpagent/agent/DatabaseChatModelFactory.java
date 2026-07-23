package com.nbcb.nacosmcpagent.agent;

import com.nbcb.nacosmcpagent.audit.AgentCallAuditService;
import com.nbcb.nacosmcpagent.audit.AuditingChatModel;
import com.nbcb.nacosmcpagent.domain.AgentDefinition.AgentNodeDefinition;
import com.nbcb.nacosmcpagent.domain.AgentDefinition.ModelDefinition;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Set;

/**
 * 根据 AI_MODEL 和节点参数创建 Agent 独享的 ChatModel。
 */
public class DatabaseChatModelFactory {

    private static final String ENV_PREFIX = "env:";

    private static final Set<String> SUPPORTED_PROVIDERS =
            Set.of("openai", "deepseek");

    private final AgentCallAuditService auditService;

    public DatabaseChatModelFactory() {
        this(null);
    }

    public DatabaseChatModelFactory(AgentCallAuditService auditService) {
        this.auditService = auditService;
    }

    public ChatModel create(
            ModelDefinition model,
            AgentNodeDefinition node) {
        return create(model, node, null);
    }

    public ChatModel create(
            ModelDefinition model,
            AgentNodeDefinition node,
            String agentId) {
        String provider = required(
                model.providerCode(), model.modelId(), "PROVIDER_CODE")
                .toLowerCase(Locale.ROOT);
        if (!SUPPORTED_PROVIDERS.contains(provider)) {
            throw new IllegalStateException(
                    "不支持的模型提供方：modelId=" + model.modelId()
                            + ", provider=" + provider);
        }

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(required(
                        model.endpointUri(), model.modelId(), "ENDPOINT_URI"))
                .apiKey(resolveCredential(
                        model.credentialRef(), model.modelId()))
                .build();
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .model(required(
                        model.modelName(), model.modelId(), "MODEL_NAME"));
        if (node.temperature() != null) {
            options.temperature(node.temperature().doubleValue());
        }
        if (node.maxToken() != null) {
            options.maxTokens(node.maxToken());
        }
        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options.build())
                .build();
        if (auditService == null) {
            return chatModel;
        }
        return new AuditingChatModel(
                chatModel,
                auditService,
                agentId,
                node.nodeId(),
                model.modelId());
    }

    private static String required(
            String value,
            String modelId,
            String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(
                    "模型配置不能为空：modelId=" + modelId
                            + ", field=" + field);
        }
        return value.trim();
    }

    private static String resolveCredential(
            String value,
            String modelId) {
        String credential = required(value, modelId, "CREDENTIAL_REF");
        if (!credential.startsWith(ENV_PREFIX)) {
            return credential;
        }
        String variableName = credential.substring(ENV_PREFIX.length())
                .trim();
        if (!StringUtils.hasText(variableName)) {
            throw new IllegalStateException(
                    "模型环境变量引用不能为空：modelId=" + modelId
                            + ", field=CREDENTIAL_REF");
        }
        String resolved = System.getenv(variableName);
        if (!StringUtils.hasText(resolved)) {
            throw new IllegalStateException(
                    "模型环境变量未配置：modelId=" + modelId
                            + ", field=CREDENTIAL_REF, env="
                            + variableName);
        }
        return resolved.trim();
    }
}
