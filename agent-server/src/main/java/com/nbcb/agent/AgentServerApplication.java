package com.nbcb.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 智能体服务端 主启动类
 * <p>
 * 功能：
 * 1. 启动 Spring Boot Web 容器
 * 2. Nacos AiService SDK 加载 Skill（推送式，无需 @Scheduled）
 * 3. Nacos Distributed Client 发现 MCP 工具（STREAMABLE 协议）
 * 4. ReactAgent + DeepSeek Chat → /chat REST 接口
 * <p>
 * 无需 {@code @EnableDiscoveryClient}，MCP 服务发现由 Extensions 自动完成。
 *
 * @author com.nbcb
 */
@SpringBootApplication
public class AgentServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentServerApplication.class, args);
    }
}
