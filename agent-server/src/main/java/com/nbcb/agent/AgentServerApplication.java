package com.nbcb.agent;

import com.nbcb.agent.domain.RequestContext;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 智能体服务端 主启动类
 * <p>
 * 功能：
 * <ol>
 *   <li>启动 Spring Boot Web 容器</li>
 *   <li>Nacos AiService SDK 加载 Skill（推送式，无需 @Scheduled）</li>
 *   <li>Nacos Distributed Client 发现 MCP 工具（STREAMABLE 协议）</li>
 *   <li>ReactAgent + DeepSeek Chat → /chat REST 接口</li>
 * </ol>
 * <p>
 * 无需 {@code @EnableDiscoveryClient}，MCP 服务发现由 Extensions 自动完成。
 *
 * @author com.nbcb
 */
@Slf4j
@SpringBootApplication
@MapperScan("com.nbcb.agent.governance.mapper")  // ★ Agent 治理组件 MyBatis Mapper 扫描
public class AgentServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentServerApplication.class, args);
    }

    /**
     * ★ 应用关闭时清理所有 ThreadLocal，防止内存泄漏
     */
    @PreDestroy
    public void cleanup() {
        log.info("★ 应用关闭，清理 ThreadLocal...");
        RequestContext.cleanupAll();
        log.info("★ ThreadLocal 清理完成");
    }
}
