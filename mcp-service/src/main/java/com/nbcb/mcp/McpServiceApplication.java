package com.nbcb.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Higress HTTP 工具提供服务主启动类。
 * <p>
 * 应用作为普通 HTTP 服务注册到 Nacos Naming，
 * 由 Higress 读取 Nacos 中的服务实例并将 REST API 转换为 MCP Tools。
 *
 * @author com.nbcb
 */
@SpringBootApplication
public class McpServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServiceApplication.class, args);
    }

}
