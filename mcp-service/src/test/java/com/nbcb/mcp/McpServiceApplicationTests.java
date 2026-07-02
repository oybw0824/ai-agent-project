package com.nbcb.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * MCP 服务启动测试 — 验证 Spring 容器加载
 *
 * @author com.nbcb
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class McpServiceApplicationTests {

    @Test
    void contextLoads() {
        // 验证 Spring 上下文加载成功
    }
}
