package com.nbcb.nacosmcpagent.agent;

import com.nbcb.nacosmcpagent.domain.AgentDefinition.AgentNodeDefinition;
import com.nbcb.nacosmcpagent.domain.AgentDefinition.ModelDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.ApiKey;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseChatModelFactoryTest {

    private final DatabaseChatModelFactory factory =
            new DatabaseChatModelFactory();

    @Test
    void shouldCreateIndependentModelsFromDatabaseFields() {
        String databaseApiKey = "database-secret-key";
        ModelDefinition model = new ModelDefinition(
                "model-1",
                "deepseek",
                "database-model-name",
                "https://database-endpoint.example.com",
                databaseApiKey);
        AgentNodeDefinition node = new AgentNodeDefinition(
                "node-1", "prompt", "",
                new BigDecimal("0.35"), 1234,
                model, java.util.List.of(), java.util.List.of());

        ChatModel first = factory.create(model, node);
        ChatModel second = factory.create(model, node);

        assertThat(first).isNotSameAs(second);
        OpenAiChatOptions options = (OpenAiChatOptions)
                first.getDefaultOptions();
        assertThat(options.getModel()).isEqualTo("database-model-name");
        assertThat(options.getTemperature()).isEqualTo(0.35d);
        assertThat(options.getMaxTokens()).isEqualTo(1234);

        OpenAiApi api = (OpenAiApi) ReflectionTestUtils.getField(
                first, "openAiApi");
        assertThat(api).isNotNull();
        ApiKey apiKey = (ApiKey) ReflectionTestUtils.getField(
                api, "apiKey");
        assertThat(apiKey).isNotNull();
        assertThat(apiKey.getValue()).isEqualTo(databaseApiKey);
    }

    @Test
    void shouldRejectMissingDatabaseApiKeyWithoutPrintingAKey() {
        ModelDefinition model = new ModelDefinition(
                "model-without-key",
                "deepseek",
                "deepseek-chat",
                "https://api.deepseek.com",
                " ");
        AgentNodeDefinition node = new AgentNodeDefinition(
                "node-1", "prompt", "", null, null,
                model, java.util.List.of(), java.util.List.of());

        assertThatThrownBy(() -> factory.create(model, node))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model-without-key")
                .hasMessageContaining("CREDENTIAL_REF");
    }

    @Test
    void shouldRejectMissingEnvironmentApiKeyReference() {
        ModelDefinition model = new ModelDefinition(
                "model-with-missing-env",
                "deepseek",
                "deepseek-chat",
                "https://api.deepseek.com",
                "env:__NACOS_MCP_AGENT_MISSING_TEST_KEY__");
        AgentNodeDefinition node = new AgentNodeDefinition(
                "node-1", "prompt", "", null, null,
                model, java.util.List.of(), java.util.List.of());

        assertThatThrownBy(() -> factory.create(model, node))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model-with-missing-env")
                .hasMessageContaining("__NACOS_MCP_AGENT_MISSING_TEST_KEY__")
                .satisfies(ex -> assertThat(ex.getMessage())
                        .doesNotContain("env:"));
    }
}
