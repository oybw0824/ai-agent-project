package com.nbcb.agent.skill.dynamic;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * 应用启动后预加载当前 Agent 的全部启用 Skill。
 *
 * <p>预加载失败不会阻止应用启动，后续可通过前端 Reload 接口恢复。</p>
 */
@Slf4j
@Component
public class DynamicSkillStartupLoader implements ApplicationRunner, Ordered {

    private final DynamicSkillProperties properties;
    private final DynamicSkillRuntimeService runtimeService;

    public DynamicSkillStartupLoader(DynamicSkillProperties properties,
                                     DynamicSkillRuntimeService runtimeService) {
        this.properties = properties;
        this.runtimeService = runtimeService;
    }

    @Override
    public void run(ApplicationArguments args) {
        String agentName = properties.getAgentName();
        try {
            AgentSkillSnapshot snapshot = runtimeService.reloadAll(agentName);
            if (snapshot.skills().isEmpty()) {
                log.warn("Agent 未配置启用的 Skill，等待显式 Reload: agent={}", agentName);
            } else {
                log.info("Agent Skill 启动预加载完成: agent={}, skillCount={}",
                        agentName, snapshot.skills().size());
            }
        } catch (RuntimeException ex) {
            log.error("Agent Skill 启动预加载失败，等待显式 Reload: agent={}", agentName, ex);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
