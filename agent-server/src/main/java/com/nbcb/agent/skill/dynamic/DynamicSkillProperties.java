package com.nbcb.agent.skill.dynamic;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 动态 Skill 配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.skill.dynamic")
public class DynamicSkillProperties {

    /** 当前进程承载的固定 Agent 名称。 */
    private String agentName = "skill-agent";

    /** NAS Skill 根目录，数据库文件路径必须位于该目录内。 */
    private String nasRoot = "/nas/skills";
}
