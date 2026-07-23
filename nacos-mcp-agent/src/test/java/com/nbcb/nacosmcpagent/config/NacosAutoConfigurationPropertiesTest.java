package com.nbcb.nacosmcpagent.config;

import com.alibaba.cloud.ai.mcp.nacos.NacosMcpClientProperties;
import com.alibaba.cloud.ai.mcp.nacos.NacosMcpStreamableClientProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class NacosAutoConfigurationPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(PropertyBindingConfiguration.class)
                    .withPropertyValues(
                            "spring.ai.alibaba.mcp.nacos.client.configs.remote.server-addr=127.0.0.1:8848",
                            "spring.ai.alibaba.mcp.nacos.client.streamable.connections.remote.service-name=remote-credit-mcp",
                            "spring.ai.alibaba.mcp.nacos.client.streamable.connections.remote.version=1.0.16");

    @Test
    void shouldBindMatchingRemoteConfigurationKey() {
        contextRunner.run(context -> {
            NacosMcpClientProperties configs =
                    context.getBean(NacosMcpClientProperties.class);
            NacosMcpStreamableClientProperties connections =
                    context.getBean(NacosMcpStreamableClientProperties.class);

            assertThat(configs.getConfigs()).containsOnlyKeys("remote");
            assertThat(connections.getConnections()).containsOnlyKeys("remote");
            assertThat(connections.getConnections().get("remote").serviceName())
                    .isEqualTo("remote-credit-mcp");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
            NacosMcpClientProperties.class,
            NacosMcpStreamableClientProperties.class
    })
    static class PropertyBindingConfiguration {
    }
}
