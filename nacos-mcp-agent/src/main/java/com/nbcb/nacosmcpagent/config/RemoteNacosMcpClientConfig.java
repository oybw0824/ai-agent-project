package com.nbcb.nacosmcpagent.config;

import com.alibaba.cloud.ai.mcp.nacos.service.NacosMcpOperationService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 远程 Nacos MCP Client 的最小兼容桥接。
 *
 * <p>官方自动配置已经会根据
 * spring.ai.alibaba.mcp.nacos.client.configs.remote 创建 operation service。
 * 这里不再重复读取 Nacos 地址、命名空间、用户名和密码，只把官方 Map 中的 remote
 * 暴露成同名 Bean，满足 Streamable 分布式客户端按连接名查找的要求。</p>
 */
@Configuration(proxyBeanMethods = false)
public class RemoteNacosMcpClientConfig {

    @Bean("remote")
    @ConditionalOnProperty(
            prefix = "spring.ai.alibaba.mcp.nacos.client",
            name = "enabled",
            havingValue = "true")
    @ConditionalOnMissingBean(name = "remote")
    public NacosMcpOperationService remoteNacosMcpOperationService(
            @Qualifier("nacosMcpOperationServiceMap")
            Map<String, NacosMcpOperationService> operationServices) {
        NacosMcpOperationService service = operationServices.get("remote");
        if (service == null) {
            throw new IllegalStateException(
                    "缺少 Nacos MCP Client 配置: spring.ai.alibaba.mcp.nacos.client.configs.remote");
        }
        return service;
    }
}
