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
 *   <li>根据数据库绑定从 NAS 按版本加载 Skill</li>
 *   <li>通过 Streamable HTTP 从 Higress AI 网关拉取 MCP 工具</li>
 *   <li>ReactAgent + DeepSeek Chat → /chat REST 接口</li>
 * </ol>
 * <p>
 * Agent 仅连接 Higress 暴露的 MCP 地址，不直接访问后端 HTTP 服务。
 *
 * @author com.nbcb
 */
@Slf4j
@SpringBootApplication
@MapperScan({"com.nbcb.agent.governance.mapper", "com.nbcb.agent.skill.dynamic.mapper"})
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
