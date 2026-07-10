package com.nbcb.agent.health;

import com.nbcb.agent.skill.SkillRegistry;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 技能加载健康指示器
 */
@Component
public class NacosHealthIndicator implements HealthIndicator {
    private final SkillRegistry skillRegistry;

    public NacosHealthIndicator(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    @Override
    public Health health() {
        int count = skillRegistry.size();
        return count > 0
                ? Health.up().withDetail("source", "classpath:skills/").withDetail("loadedSkills", count).build()
                : Health.down().withDetail("source", "classpath:skills/").withDetail("loadedSkills", 0).withDetail("note", "使用内置默认 Skill").build();
    }
}
