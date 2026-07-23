package com.nbcb.nacosmcpagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Nacos MCP Server 与 Agent 双角色应用。
 */
@SpringBootApplication
public class NacosMcpAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(NacosMcpAgentApplication.class, args);
    }
}
