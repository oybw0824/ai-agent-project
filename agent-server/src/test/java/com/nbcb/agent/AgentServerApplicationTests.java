package com.nbcb.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Agent 服务启动测试
 * <p>
 * 使用 test profile 禁用外部依赖（Nacos/MCP/DeepSeek），仅验证 Spring 容器加载。
 *
 * @author com.nbcb
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentServerApplicationTests {

    @Test
    void contextLoads() {
        // 验证 Spring 上下文加载成功
    }
}
