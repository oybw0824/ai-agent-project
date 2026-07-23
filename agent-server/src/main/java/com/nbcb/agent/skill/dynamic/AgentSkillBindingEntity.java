package com.nbcb.agent.skill.dynamic;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent-Skill 绑定表实体。
 */
@Data
@TableName("agent_skill_binding")
public class AgentSkillBindingEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String agentName;
    private String skillName;
    private String skillVersion;
    private String skillFilePath;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public AgentSkillBinding toBinding() {
        return new AgentSkillBinding(agentName, skillName, skillVersion, skillFilePath,
                Boolean.TRUE.equals(enabled));
    }
}
