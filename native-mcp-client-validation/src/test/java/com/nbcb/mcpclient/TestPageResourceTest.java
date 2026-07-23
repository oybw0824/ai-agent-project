package com.nbcb.mcpclient;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP 验证页面静态资源测试。
 */
class TestPageResourceTest {

    @Test
    void shouldPackageTestPageWithRequiredApiEndpoints() throws Exception {
        ClassPathResource resource = new ClassPathResource("static/index.html");

        assertThat(resource.exists()).isTrue();
        String html = resource.getContentAsString(StandardCharsets.UTF_8);
        assertThat(html)
                .contains("AI 网关 MCP 验证台")
                .contains("/api/v1/mcp/tools")
                .contains("/api/v1/agent/chat");
    }
}
