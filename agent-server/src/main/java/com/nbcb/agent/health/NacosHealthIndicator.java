package com.nbcb.agent.health;

import com.nbcb.agent.skill.NacosSkillLoader;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * ★ Nacos 连接健康指示器
 * <p>
 * 暴露到 Actuator {@code /actuator/health} 端点，
 * 供监控系统（Prometheus、K8s 探针等）检测 Nacos 连通性。
 *
 * @author com.nbcb
 */
@Component
public class NacosHealthIndicator implements HealthIndicator {

    private final NacosSkillLoader skillLoader;

    public NacosHealthIndicator(NacosSkillLoader skillLoader) {
        this.skillLoader = skillLoader;
    }

    @Override
    public Health health() {
        boolean available = skillLoader.isNacosAvailable();
        int skillCount = skillLoader.getLoadedSkillCount();

        if (available) {
            return Health.up()
                    .withDetail("nacos", "connected")
                    .withDetail("loadedSkills", skillCount)
                    .build();
        } else {
            return Health.down()
                    .withDetail("nacos", "disconnected")
                    .withDetail("loadedSkills", skillCount)
                    .withDetail("note", "Nacos 不可用，使用内置默认 Skill 兜底")
                    .build();
        }
    }
}