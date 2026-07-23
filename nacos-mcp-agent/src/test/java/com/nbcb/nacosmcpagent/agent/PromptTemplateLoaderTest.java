package com.nbcb.nacosmcpagent.agent;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class PromptTemplateLoaderTest {

    private final PromptTemplateLoader loader = new PromptTemplateLoader(
            new DefaultResourceLoader(), new StandardEnvironment());

    @Test
    void shouldLoadSystemPromptFromClasspathResource() {
        assertThat(loader.loadRequired(
                "classpath:prompt/system-prompt.md",
                "systemPrompt"))
                .contains("Skill 使用规则")
                .contains("enterprise-credit-query");
    }

    @Test
    void shouldReturnInlinePromptDirectly() {
        assertThat(loader.load("inline prompt"))
                .isEqualTo("inline prompt");
    }
}
